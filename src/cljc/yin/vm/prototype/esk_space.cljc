(ns yin.vm.prototype.esk-space
  "Prototype: a CESK machine whose E, S, K live entirely as datoms in a
  dao.space datom set.

  The thesis (see docs/agents/... and the [[project_code_continuations_in_dao_space]]
  memory): the environment (E), store (S) and continuation (K) of an
  abstract machine are not host data structures — they are *tuples*. If we
  represent them as datoms in one dao.space, three things stop being
  features you build and become properties of the substrate:

    - CESK time = datom `t`. Each step appends datoms under a fresh `t`, so
      the transaction axis *is* the step counter. The whole run is a
      queryable log.
    - Time-travel = `:as-of`. Any past machine configuration is recoverable
      by folding the space `:as-of` an earlier `t`.
    - Migration = the space *is* the machine. Serialize the datom vector,
      ship it, resume from any config eid. No separate heap to marshal.
    - Introspection = datalog. \"Which frames bind `n`?\", \"how deep is the
      continuation at step 12?\" are `q` queries over live machine state,
      not ad-hoc traversal.

  Only Control (C) is not a datom: the current s-expression, carried as the
  *value* of a `:cfg/ctrl` datom (an ordinary EDN value). Datomizing the AST
  too is the natural next step but orthogonal to the E/S/K thesis.

  The language is a small call-by-value lambda calculus:

    <e> ::= n | true | false | x
          | (lambda (x) <e>)          ; single-parameter closures
          | (<e> <e>)                 ; application
          | (if <e> <e> <e>)
          | (let [x <e>] <e>)
          | (letrec [x <e>] <e>)      ; allocate-then-set; enables recursion
          | (<op> <e> <e>)            ; op in + - * < =

  Every environment frame, binding, store cell, continuation frame, closure
  and per-step configuration is an entity in the space. Reads go through the
  real `dao.space.query` (`q`/`pull`); writes append assertion datoms. Every
  cell is written exactly once (fresh address per binding; letrec allocates
  then sets once), so current-state per address is single-valued and
  `:as-of` history is exact without retractions."
  (:require [dao.space.query :as q]))


;; ---------------------------------------------------------------------------
;; The space and its accessors
;; ---------------------------------------------------------------------------
;; Machine = {:space <vec of [e a v t m]>, :eid <next entity id>, :t <step>}.

(def ^:private prim-ops '#{+ - * < =})


(defn- new-eid
  [st]
  [(:eid st) (update st :eid inc)])


(defn- add
  "Append assertion datoms (each a [e a v] triple) at the current step `t`."
  [st & triples]
  (update st :space into (map (fn [[e a v]] [e a v (:t st) 1]) triples)))


(defn- tick
  [st]
  (update st :t inc))


;; E — environment as a chain of frames, each binding its own entity.
(defn- parent
  [space f]
  (when f (:frame/parent (q/pull space f [:frame/parent]))))


(defn- env-lookup
  "Walk the frame chain in the space, returning the store address bound to
  `name`, or nil. A pure datalog query per frame — the environment is data."
  [space frame name]
  (loop [f frame]
    (when f
      (let [hit (q/q '[:find ?a :in $ ?f ?n :where [?b :bind/frame ?f]
                       [?b :bind/name ?n] [?b :bind/addr ?a]]
                     space
                     f
                     name)]
        (if (seq hit) (ffirst hit) (recur (parent space f)))))))


(defn- env-extend
  "Push a frame binding `name` -> `addr` on top of `env`. Returns [frame st]."
  [st env name addr]
  (let [[f st] (new-eid st)
        [b st] (new-eid st)
        st (cond-> st
             env (add [f :frame/parent env])
             :always
             (add [b :bind/frame f] [b :bind/name name] [b :bind/addr addr]))]
    [f st]))


;; S — store as content cells addressed by entity id.
(defn- store-get
  [space addr]
  (:cell/value (q/pull space addr [:cell/value])))


(defn- store-set
  [st addr v]
  (add st [addr :cell/value v]))


;; K — continuation frames, each an entity linked by :k/next.
(defn- mk-kont
  "Reify a continuation frame: tag + fields, linked to its parent `next`."
  [st tag fields next]
  (let [[k st] (new-eid st)
        st (reduce (fn [s [a v]] (add s [k a v])) st fields)
        st (add st [k :k/tag tag])
        st (cond-> st next (add [k :k/next next]))]
    [k st]))


(defn- load-kont
  [space k]
  (q/pull space
          k
          [:k/tag :k/next :k/then :k/else :k/env :k/name :k/body :k/addr :k/op
           :k/b :k/v1 :k/clo :k/arg]))


;; Config — one entity per machine step, so the trace itself is datoms.
(defn- mk-cfg
  [st triples]
  (let [[c st] (new-eid st)
        triples (conj (vec triples) [c :cfg/step (:t st)])
        st (reduce (fn [s [_ a v]] (add s [c a v])) st triples)]
    [c st]))


(defn- eval-cfg
  [st ctrl env kont]
  (mk-cfg st
          (cond-> [[:_ :cfg/mode :eval] [:_ :cfg/ctrl ctrl] [:_ :cfg/kont kont]]
            env (conj [:_ :cfg/env env]))))


(defn- apply-cfg
  [st val kont]
  (mk-cfg st [[:_ :cfg/mode :apply] [:_ :cfg/val val] [:_ :cfg/kont kont]]))


(defn- load-cfg
  [space c]
  (q/pull space c [:cfg/mode :cfg/ctrl :cfg/env :cfg/kont :cfg/val :cfg/step]))


;; ---------------------------------------------------------------------------
;; Values
;; ---------------------------------------------------------------------------
;; Numbers and booleans are themselves; a closure is {:closure <eid>}.

(defn- truthy?
  [v]
  (and (not (false? v)) (not (nil? v))))


(defn- apply-prim
  [op a b]
  (case op
    + (+ a b)
    - (- a b)
    * (* a b)
    < (< a b)
    = (= a b)))


;; ---------------------------------------------------------------------------
;; The reduction relation
;; ---------------------------------------------------------------------------

(defn- eval-step
  "Evaluate `ctrl` in `env` with continuation `kont`. Returns [st next-cfg]."
  [st ctrl env kont]
  (cond
    (or (number? ctrl) (boolean? ctrl)) (apply-cfg st ctrl kont)
    (symbol? ctrl) (apply-cfg st
                              (store-get (:space st)
                                         (env-lookup (:space st) env ctrl))
                              kont)
    (seq? ctrl)
    (let [[op & args] ctrl]
      (case op
        (lambda fn) (let [[params body] args
                          [clo st] (new-eid st)
                          st (add st
                                  [clo :clo/param (first params)]
                                  [clo :clo/body body]
                                  [clo :clo/env env])]
                      (apply-cfg st {:closure clo} kont))
        if (let [[c t e] args
                 [k st]
                 (mk-kont st :if {:k/then t, :k/else e, :k/env env} kont)]
             (eval-cfg st c env k))
        let (let [[[x rhs] body] args
                  [k st] (mk-kont st
                                  :let
                                  {:k/name x, :k/body body, :k/env env}
                                  kont)]
              (eval-cfg st rhs env k))
        letrec (let [[[x rhs] body] args
                     [addr st] (new-eid st) ; allocate cell, unset
                     [env' st] (env-extend st env x addr) ; rhs sees its
                     ;; own binding
                     [k st] (mk-kont st
                                     :letrec
                                     {:k/addr addr, :k/body body, :k/env env'}
                                     kont)]
                 (eval-cfg st rhs env' k))
        (if (contains? prim-ops op)
          (let [[a b] args
                [k st]
                (mk-kont st :prim1 {:k/op op, :k/b b, :k/env env} kont)]
            (eval-cfg st a env k))
          ;; application: (rator rand)
          (let [rand (first args)
                [k st] (mk-kont st :app-fn {:k/arg rand, :k/env env} kont)]
            (eval-cfg st op env k)))))
    :else (throw (ex-info "cannot evaluate" {:ctrl ctrl}))))


(defn- continue-step
  "Return value `v` to continuation `k`. Returns [st next-cfg], or
  [st [:halt v]] when the top (:done) continuation is reached."
  [st v k]
  (let [{:k/keys [tag next then else env name body addr op b v1 clo arg]}
        (load-kont (:space st) k)]
    (case tag
      :done [[:halt v] st]
      :if (eval-step st (if (truthy? v) then else) env next)
      :let (let [[cell st] (new-eid st)
                 st (store-set st cell v)
                 [env' st] (env-extend st env name cell)]
             (eval-step st body env' next))
      :letrec (let [st (store-set st addr v)] (eval-step st body env next))
      :prim1 (let [[k2 st] (mk-kont st :prim2 {:k/op op, :k/v1 v} next)]
               (eval-step st b env k2))
      :prim2 (apply-cfg st (apply-prim op v1 v) next)
      :app-fn (let [[k2 st] (mk-kont st :app-arg {:k/clo (:closure v)} next)]
                (eval-step st arg env k2))
      :app-arg (let [{:clo/keys [param body env]}
                     (q/pull (:space st) clo [:clo/param :clo/body :clo/env])
                     [cell st] (new-eid st)
                     st (store-set st cell v)
                     [env' st] (env-extend st env param cell)]
                 (eval-step st body env' next)))))


(defn step
  "One transition. Reads the current config from the space, computes the
  next, ticks `t`. Returns [st next-cfg] or [st [:halt value]]."
  [st cfg]
  (let [{:cfg/keys [mode ctrl env kont val]} (load-cfg (:space st) cfg)
        [result st] (if (= mode :eval)
                      (eval-step st ctrl env kont)
                      (continue-step st val kont))]
    [(tick st) result]))


;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn inject
  "Seed a machine: the empty space, the empty env, and the top (:done)
  continuation focused on `expr`. Returns [st cfg]."
  [expr]
  (let [st {:space [], :eid 1, :t 0}
        [done st] (mk-kont st :done {} nil)
        [cfg st] (eval-cfg st expr nil done)]
    [(tick st) cfg]))


(defn- halt?
  [result]
  (and (vector? result) (= :halt (first result))))


(defn drive
  "Step (st, cfg) to a value. This is the *resume* half of `run`: a machine
  that was serialized mid-flight (its whole state being datoms) restarts here
  from any config eid. Returns {:value v :st st :steps n}."
  [st cfg fuel]
  (loop [st st
         cfg cfg
         n 0]
    (when (> n fuel) (throw (ex-info "out of fuel" {:steps n})))
    (let [[st result] (step st cfg)]
      (if (halt? result)
        {:value (second result), :st st, :steps (inc n)}
        (recur st result (inc n))))))


(defn run
  "Run `expr` to a value. Returns {:value v :st st :steps n}.
  `st` retains the full datom log for time-travel / introspection / migration."
  ([expr] (run expr 100000))
  ([expr fuel] (let [[st cfg] (inject expr)] (drive st cfg fuel))))


(comment
  ;; Exploration: the four properties the thesis predicts, at the REPL.
  ;; (q is the ns-level alias for dao.space.query.)
  (def r
    (run '(letrec
           [fact (lambda (n) (if (< n 2) 1 (* n (fact (- n 1)))))]
           (fact 5))))
  (:value r)    ; => 120
  (def space (:space (:st r)))
  (count space) ; => 511 datoms for fact 5
  ;; [1] CESK time = datom t. The step counter is the transaction axis.
  (apply max (map peek (q/q '[:find ?s :where [_ :cfg/step ?s]] space))) ; =>
                                                                         ; 67
  ;; [2] Introspection = datalog over LIVE machine state. The entire live
  ;;     recursion stack's bindings of n, with no bespoke stack-walking:
  (sort (map first
          (q/q '[:find ?v :in $ ?name :where [?b :bind/name ?name]
                 [?b :bind/addr ?a] [?a :cell/value ?v]]
               space
               'n))) ; => (1 2 3 4 5)
  ;; K is data too — the continuation's frame tags are queryable:
  (set (map first (q/q '[:find ?t :where [_ :k/tag ?t]] space)))
  ;; => #{:if :prim2 :done :prim1 :app-arg :app-fn :letrec}
  ;; [3] Time-travel = :as-of. Cell 15 (n=5) is unset before its t, set at
  ;; it:
  (q/pull space 15 [:cell/value] {:as-of 5}) ; => #:db{:id 15}
  (q/pull space 15 [:cell/value] {:as-of 6}) ; => {:cell/value 5 :db/id 15}
  ;; [4] Migration = the space IS the machine. Freeze mid-flight to text,
  ;;     revive, resume — no host heap to marshal, only datoms. Concretely
  ;;     (asserted in migration-is-the-space): step to a mid-flight [st
  ;;     cfg],
  ;;     then  (drive (edn/read-string (pr-str st)) cfg fuel)  => 120.
  (let [seed (inject '(fact 5)) ; (with fact in scope)
        [st cfg] (nth (iterate (fn [[s c]] (step s c)) seed) 20)]
    [(count (:space st)) cfg])  ; the machine, as data
  ;; Where this points (see the conversation that produced this file):
  ;;  - S here is write-once, so no retractions are needed; a mutating
  ;;  store
  ;;    (set!) routes through dao.space.transact's card-one retract+assert,
  ;;    which is exactly what preserves correct :as-of history under
  ;;    mutation.
  ;;  - Hot-path cost: every allocation is a datom. The real integration
  ;;    wants a two-tier store — a transient map promoted to datoms only at
  ;;    park / migration boundaries — so straight-line eval pays nothing.
  ;;  - C (the AST) is still plain EDN carried in :cfg/ctrl. Datomizing the
  ;;    program too (code = data) closes the loop: then `q` spans
  ;;    code+state.
)

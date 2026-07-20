(ns yin.vm.prototype.cesk-space
  "Prototype: a CESK machine stored *entirely* as datoms in a dao.space.

  The thesis (see docs/agents/... and the [[project_code_continuations_in_dao_space]]
  memory): a machine is an eid into a space. Control (C), environment (E),
  store (S) and continuation (K) are not host data structures — they are
  tuples. Representing all four as datoms in one dao.space makes these
  properties of the substrate rather than features you build:

    - Queryability = datalog. One `q` spans code AND state: \"which lambda's
      parameter currently binds 5?\", \"which AST nodes never entered
      control?\" are joins, not traversals. The test file is the query
      catalog — the contract any optimization must preserve.
    - CESK time = datom `t`. Each step appends datoms under a fresh `t`, so
      the transaction axis *is* the step counter, and `:as-of` recovers any
      past configuration. The program itself is genesis: loaded at t=0.
    - Migration = the space *is* the machine. Serialize the datom vector,
      ship it, resume from any config eid — program included, no separate
      heap to marshal.
    - Coordination = Linda, in the substrate's own verbs. `(out k v)` is
      transact: append tuple datoms. `(rd k)` is a blocking
      dao.space.query/match: it parks as a wait entity carrying its
      positional template as data, resumed when the template hits. Two
      machines sharing one space coordinate through the terrain —
      stigmergy, made operational.
    - Ownership = the `m` slot. Every datom a machine writes carries its
      reified owner entity as `m` (see dao.datom: ids >= 1025 are metadata
      entity refs). The space is open today; a Shibi capability filter goes
      into the single read chokepoint (`view`) later without touching the
      reduction relation.

  The language is a small call-by-value lambda calculus:

    <e> ::= n | true | false | x
          | (lambda (x) <e>)          ; single-parameter closures
          | (<e> <e>)                 ; application
          | (if <e> <e> <e>)
          | (let [x <e>] <e>)
          | (letrec [x <e>] <e>)      ; allocate-then-set; enables recursion
          | (<op> <e> <e>)            ; op in + - * < =
          | (out <key> <e>)           ; Linda: write tuple, value passes through
          | (rd <key>)                ; Linda: block until tuple, yield its value

  The program is datomized into the production `:yin/*` vocabulary
  (:yin/type, :yin/operator, :yin/operands, :yin/body, ...) so queries
  written here transfer verbatim to the semantic VM's AST db. `let`/`letrec`
  are prototype-local node types (the schema is open). Closures are entities;
  closure *values* travel in ref attributes (:cfg/val-ref, :cell/ref) while
  scalars stay in value attributes (:cfg/val, :cell/value), so `q` unifies
  through them without eid/number collisions. Every cell is written exactly
  once, so current-state per address is single-valued and `:as-of` history is
  exact without retractions."
  (:require [dao.space.query :as q]))


;; ---------------------------------------------------------------------------
;; The machine cursor and its space
;; ---------------------------------------------------------------------------
;; Machine = {:space <vec of [e a v t m]>, :eid <next entity id>, :t <step>,
;;            :owner <owner eid>, :name <owner name>, :cfg <current cfg eid>}.
;; Everything else lives in the space.

(def ^:private prim-ops '#{+ - * < =})


(defn- new-eid
  [st]
  [(:eid st) (update st :eid inc)])


(defn- sync-st-cache
  [st]
  (let [space (:space st)
        start (or (:cache-seq st) 0)
        end (count space)]
    (if (= start end)
      st
      (let [new-datoms (subvec space start end)
            cache' (reduce (fn [c [e a v]] (assoc-in c [e a] v))
                           (or (:cache st) {})
                           new-datoms)
            bind-attrs #{:bind/frame :bind/name :bind/addr}
            env-cache' (if (some #(contains? bind-attrs (second %)) new-datoms)
                         (reduce (fn [ec [e a _]]
                                   (if (contains? bind-attrs a)
                                     (let [attrs (get cache' e)
                                           f (:bind/frame attrs)
                                           name (:bind/name attrs)
                                           addr (:bind/addr attrs)]
                                       (if (and f name addr)
                                         (assoc-in ec [f name] addr)
                                         ec))
                                     ec))
                                 (or (:env-cache st) {})
                                 new-datoms)
                         (or (:env-cache st) {}))]
        (assoc st
               :cache cache'
               :env-cache env-cache'
               :cache-seq end)))))


(defn- cached-pull
  "Map-based entity lookup replacing `q/pull` for the hot path.
  Returns nil when the entity is not in the cache, matching `q/pull` behavior."
  [cache eid pattern]
  (if-let [attrs (get cache eid)]
    (reduce (fn [res attr]
              (if-let [e (find attrs attr)]
                (assoc res (key e) (val e))
                res))
            {:db/id eid}
            pattern)
    nil))


(defn- add
  "Append assertion datoms (each a [e a v] triple) at the current step `t`,
  stamped with the machine's owner entity as `m`."
  [st & triples]
  (let [st' (update st
                    :space
                    into
                    (map (fn [[e a v]] [e a v (:t st) (:owner st)]) triples))]
    (sync-st-cache st')))


(defn- tick
  [st]
  (update st :t inc))


(defn- view
  "The single read chokepoint: every read the machine issues against its
  space goes through here. Today it is the whole space (the door is open).
  A Shibi capability filter and/or a folded-index cache slots in here later,
  invisible to the reduction relation."
  [st]
  (:space st))


;; ---------------------------------------------------------------------------
;; Values
;; ---------------------------------------------------------------------------
;; Scalars (numbers, booleans, symbols) are themselves. A closure value is a
;; bare entity id, wrapped in {:ref eid} only while in flight in host code;
;; in the space it always travels in a ref attribute (:cfg/val-ref,
;; :cell/ref, :k/clo), never a scalar one.

(defn- ref-val
  [eid]
  {:ref eid})


(defn- ref-val?
  [v]
  (and (map? v) (contains? v :ref)))


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
;; C — the program as :yin/* datoms
;; ---------------------------------------------------------------------------

(defn- emit-operands
  [st exprs emit]
  (reduce (fn [[st acc] expr] (let [[e st] (emit st expr)] [st (conj acc e)]))
          [st []]
          exprs))


(defn- emit-node
  "Datomize `expr` into :yin/* AST datoms. Returns [root-eid st]."
  [st expr]
  (cond
    (or (number? expr) (boolean? expr))
    (let [[e st] (new-eid st)]
      [e (add st [e :yin/type :literal] [e :yin/value expr])])
    (symbol? expr) (let [[e st] (new-eid st)]
                     [e (add st [e :yin/type :variable] [e :yin/name expr])])
    (seq? expr)
    (let [[op & args] expr]
      (case op
        (lambda fn) (let [[params body] args
                          [b st] (emit-node st body)
                          [e st] (new-eid st)]
                      [e
                       (add st
                            [e :yin/type :lambda]
                            [e :yin/params (vec params)]
                            [e :yin/body b])])
        if (let [[c t a] args
                 [ce st] (emit-node st c)
                 [te st] (emit-node st t)
                 [ae st] (emit-node st a)
                 [e st] (new-eid st)]
             [e
              (add st
                   [e :yin/type :if]
                   [e :yin/test ce]
                   [e :yin/consequent te]
                   [e :yin/alternate ae])])
        (let letrec) (let [[[x rhs] body] args
                           [re st] (emit-node st rhs)
                           [be st] (emit-node st body)
                           [e st] (new-eid st)]
                       [e
                        (add st
                             [e :yin/type (if (= op 'let) :let :letrec)]
                             [e :yin/name x]
                             [e :yin/init re]
                             [e :yin/body be])])
        out
        (let [[k vexpr] args
              [ve st] (emit-node st vexpr)
              [e st] (new-eid st)]
          [e
           (add st [e :yin/type :out] [e :yin/key k] [e :yin/val-node ve])])
        rd (let [[k] args
                 [e st] (new-eid st)]
             [e (add st [e :yin/type :rd] [e :yin/key k])])
        ;; application — prims included; resolved at eval time by
        ;; operator
        (let [[fe st] (emit-node st op)
              [st operand-eids] (emit-operands st args emit-node)
              [e st] (new-eid st)]
          [e
           (add st
                [e :yin/type :application]
                [e :yin/operator fe]
                [e :yin/operands operand-eids])])))
    :else (throw (ex-info "cannot datomize" {:expr expr}))))


(defn- load-node
  [st eid]
  (cached-pull (:cache st)
               eid
               [:yin/type :yin/value :yin/name :yin/params :yin/body :yin/test
                :yin/consequent :yin/alternate :yin/operator :yin/operands
                :yin/init :yin/key :yin/val-node]))


;; ---------------------------------------------------------------------------
;; E — environment as a chain of frames, each binding its own entity.
;; ---------------------------------------------------------------------------

(defn- parent
  [st f]
  (when f (:frame/parent (cached-pull (:cache st) f [:frame/parent]))))


(defn- env-lookup
  "Walk the frame chain in the space, returning the store address bound to
  `name`, or nil. A pure datalog query per frame — the environment is data."
  [st frame name]
  (loop [f frame]
    (when f
      (if-let [addr (get-in st [:env-cache f name])]
        addr
        (recur (parent st f))))))


(defn- env-extend
  "Push a frame binding `name` -> `addr` on top of `env`. Returns [frame st]."
  [st env name addr]
  (let [[f st] (new-eid st)
        [b st] (new-eid st)
        st (cond-> st
             env (add [f :frame/parent env])
             (and (:trace? st) (:cfg st)) (add [f :frame/created-by (:cfg st)])
             :always
             (add [b :bind/frame f] [b :bind/name name] [b :bind/addr addr]))]
    [f st]))


;; ---------------------------------------------------------------------------
;; S — store as content cells addressed by entity id.
;; ---------------------------------------------------------------------------

(defn- store-get
  [st addr]
  (let [{:cell/keys [value ref]}
        (cached-pull (:cache st) addr [:cell/value :cell/ref])]
    (if ref (ref-val ref) value)))


(defn- store-set
  [st addr v]
  (let [vt (if (ref-val? v) [addr :cell/ref (:ref v)] [addr :cell/value v])]
    (if-let [c (and (:trace? st) (:cfg st))]
      (add st vt [addr :cell/set-by c])
      (add st vt))))


;; ---------------------------------------------------------------------------
;; K — continuation frames, each an entity linked by :k/next.
;; ---------------------------------------------------------------------------

(defn- mk-kont
  "Reify a continuation frame: tag + fields, linked to its parent `next`."
  [st tag fields next]
  (let [[k st] (new-eid st)
        st (reduce (fn [s [a v]] (add s [k a v])) st fields)
        st (add st [k :k/tag tag])
        st (cond-> st next (add [k :k/next next]))]
    [k st]))


(defn- load-kont
  [st k]
  (cached-pull (:cache st)
               k
               [:k/tag :k/next :k/then :k/else :k/env :k/name :k/body :k/addr
                :k/op :k/b :k/v1 :k/clo :k/arg :k/key]))


;; ---------------------------------------------------------------------------
;; Config — one entity per machine step, so the trace itself is datoms.
;; ---------------------------------------------------------------------------

(defn- mk-cfg
  [st triples]
  (let [[c st] (new-eid st)
        triples (cond-> (conj (vec triples) [:_ :cfg/step (:t st)])
                  (and (:trace? st) (:cfg st)) (conj [:_ :cfg/prev (:cfg st)]))
        st (reduce (fn [s [_ a v]] (add s [c a v])) st triples)]
    [c st]))


(defn- eval-cfg
  [st ctrl env kont]
  (mk-cfg st
          (cond-> [[:_ :cfg/mode :eval] [:_ :cfg/ctrl ctrl] [:_ :cfg/kont kont]]
            env (conj [:_ :cfg/env env]))))


(defn- apply-cfg
  [st val kont]
  (mk-cfg st
          [[:_ :cfg/mode :apply]
           (if (ref-val? val) [:_ :cfg/val-ref (:ref val)] [:_ :cfg/val val])
           [:_ :cfg/kont kont]]))


(defn- load-cfg
  [st c]
  (cached-pull (:cache st)
               c
               [:cfg/mode :cfg/ctrl :cfg/env :cfg/kont :cfg/val :cfg/val-ref
                :cfg/step]))


;; ---------------------------------------------------------------------------
;; Linda tuples
;; ---------------------------------------------------------------------------
;; out and rd are not VM features; they are the substrate's own operations.
;; out = transact (`add`: append assertion datoms — the write-once degenerate
;; case; a mutating store or a destructive `in` would route through
;; dao.space.transact's card-one retract+assert). rd = a blocking
;; dao.space.query/match: the wait entity carries the positional template
;; itself as data, so the scheduler is generic — it matches datoms against
;; open templates and never knows about "keys".

(defn- rd-match
  "Linda rd as dao.space.query/match: run the positional template through
  the chokepoint; when a tuple entity matches, yield its value.
  NOTE: `q/match` still folds the raw space — this is the remaining re-fold
  site after the read cache optimization. Linda-heavy workloads will want a
  positional index here (plan step 3 applies: fold at safepoints)."
  [st pattern]
  (when-let [d (first (q/match (view st) pattern))]
    (let [{:tuple/keys [val ref]}
          (cached-pull (:cache st) (nth d 0) [:tuple/val :tuple/ref])]
      (if ref (ref-val ref) val))))


;; ---------------------------------------------------------------------------
;; The reduction relation
;; ---------------------------------------------------------------------------

(defn- eval-step
  "Evaluate node `ctrl` (an AST eid) in `env` with continuation `kont`.
  Returns [next st] where next is a cfg eid, [:halt v], or [:blocked w]."
  [st ctrl env kont]
  (let [node (load-node st ctrl)]
    (case (:yin/type node)
      :literal (apply-cfg st (:yin/value node) kont)
      :variable
      (apply-cfg st (store-get st (env-lookup st env (:yin/name node))) kont)
      :lambda (let [[clo st] (new-eid st)
                    st (add st
                            [clo :clo/param (first (:yin/params node))]
                            [clo :clo/body (:yin/body node)])
                    st (cond-> st env (add [clo :clo/env env]))]
                (apply-cfg st (ref-val clo) kont))
      :if (let [[k st] (mk-kont st
                                :if
                                {:k/then (:yin/consequent node),
                                 :k/else (:yin/alternate node),
                                 :k/env env}
                                kont)]
            (eval-cfg st (:yin/test node) env k))
      :let (let [[k st] (mk-kont st
                                 :let
                                 {:k/name (:yin/name node),
                                  :k/body (:yin/body node),
                                  :k/env env}
                                 kont)]
             (eval-cfg st (:yin/init node) env k))
      :letrec (let [[addr st] (new-eid st) ; allocate cell, unset
                    ;; rhs sees its own binding
                    [env' st] (env-extend st env (:yin/name node) addr)
                    [k st] (mk-kont st
                                    :letrec
                                    {:k/addr addr,
                                     :k/body (:yin/body node),
                                     :k/env env'}
                                    kont)]
                (eval-cfg st (:yin/init node) env' k))
      :out (let [[k st] (mk-kont st :out-val {:k/key (:yin/key node)} kont)]
             (eval-cfg st (:yin/val-node node) env k))
      :rd (let [pattern ['_ :tuple/key (:yin/key node)]
                hit (rd-match st pattern)]
            (if (some? hit)
              (apply-cfg st hit kont)
              ;; park: the blocked continuation is a queryable wait entity
              ;; carrying its match template as data
              (let [[w st] (new-eid st)
                    st (add st [w :wait/pattern pattern] [w :wait/k kont])]
                [[:blocked w] st])))
      :application (let [operands (:yin/operands node)
                         op-node (load-node st (:yin/operator node))
                         op-name (:yin/name op-node)]
                     (if (and (= :variable (:yin/type op-node))
                              (contains? prim-ops op-name)
                              (nil? (env-lookup st env op-name)))
                       ;; primitive: (op a b)
                       (let [[a b] operands
                             [k st] (mk-kont st
                                             :prim1
                                             {:k/op op-name, :k/b b, :k/env env}
                                             kont)]
                         (eval-cfg st a env k))
                       ;; closure application: (rator rand)
                       (let [[k st] (mk-kont st
                                             :app-fn
                                             {:k/arg (first operands),
                                              :k/env env}
                                             kont)]
                         (eval-cfg st (:yin/operator node) env k))))
      (throw (ex-info "cannot evaluate" {:ctrl ctrl, :node node})))))


(defn- continue-step
  "Return value `v` to continuation `k`. Returns [next st], or
  [[:halt v] st] when the top (:done) continuation is reached."
  [st v k]
  (let [{:k/keys [tag next then else env name body addr op b v1 clo arg key]}
        (load-kont st k)]
    (case tag
      :done [[:halt v] st]
      :if (eval-cfg st (if (truthy? v) then else) env next)
      :let (let [[cell st] (new-eid st)
                 st (store-set st cell v)
                 [env' st] (env-extend st env name cell)]
             (eval-cfg st body env' next))
      :letrec (let [st (store-set st addr v)] (eval-cfg st body env next))
      :prim1 (let [[k2 st] (mk-kont st :prim2 {:k/op op, :k/v1 v} next)]
               (eval-cfg st b env k2))
      :prim2 (apply-cfg st (apply-prim op v1 v) next)
      :app-fn
      (if (ref-val? v)
        (let [[k2 st]
              (mk-kont st :app-arg {:k/clo (:ref v), :k/env env} next)]
          (eval-cfg st arg env k2))
        (throw (ex-info "cannot apply non-closure" {:val v})))
      :app-arg
      (let [{:clo/keys [param body env]}
            (cached-pull (:cache st) clo [:clo/param :clo/body :clo/env])
            [cell st] (new-eid st)
            st (store-set st cell v)
            [env' st] (env-extend st env param cell)]
        (eval-cfg st body env' next))
      :out-val (let [[e st] (new-eid st)
                     st (add st
                             [e :tuple/key key]
                             (if (ref-val? v)
                               [e :tuple/ref (:ref v)]
                               [e :tuple/val v]))]
                 (apply-cfg st v next)))))


(defn step
  "One transition. Reads the current config from the space, computes the
  next, ticks `t`. Returns [st next] where next is a cfg eid, [:halt value],
  or [:blocked wait-eid]."
  [st cfg]
  (let [st (assoc st :cfg cfg)
        {:cfg/keys [mode ctrl env kont val val-ref]} (load-cfg st cfg)
        v (if val-ref (ref-val val-ref) val)
        [result st] (if (= mode :eval)
                      (eval-step st ctrl env kont)
                      (continue-step st v kont))]
    [(tick st) result]))


;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn inject
  "Seed a machine into a space (a fresh one, or a shared one via :space).
  The program is datomized at t=0 — genesis — into the same space that will
  hold E, S, K and the step trace. Creates a reified owner entity; every
  datom this machine writes carries it as `m`. Options:
    :space      existing space to join (default [])
    :eid-base   start of this machine's eid range (default 2048; keep ranges
                disjoint between machines sharing a space; must be >= 1025
                so `m` refs a metadata entity, see dao.datom)
    :owner-name symbol naming the owner entity (default 'machine)
    :trace?     whether to record telemetry (default true)
  Returns [st cfg]."
  ([expr] (inject expr {}))
  ([expr
    {:keys [space eid-base owner-name trace?],
     :or {space [], eid-base 2048, owner-name 'machine, trace? true}}]
   (let [owner eid-base
         st {:space space,
             :eid (inc eid-base),
             :t 0,
             :owner owner,
             :name owner-name,
             :trace? trace?,
             :cache-seq 0,
             :cache {},
             :env-cache {}}
         st (sync-st-cache st)
         st (add st [owner :agent/name owner-name])
         [root st] (emit-node st expr) ; genesis: C enters the space at t=0
         [done st] (mk-kont st :done {} nil)
         [cfg st] (eval-cfg st root nil done)]
     [(tick st) cfg])))


(defn- halt?
  [result]
  (and (vector? result) (= :halt (first result))))


(defn- blocked?
  [result]
  (and (vector? result) (= :blocked (first result))))


(defn- try-resume
  "If a wait entity's match template now hits, resume its parked
  continuation with the matched tuple's value. Returns [st next] or nil."
  [st w]
  (let [{:wait/keys [pattern k]}
        (cached-pull (:cache st) w [:wait/pattern :wait/k])
        hit (rd-match st pattern)]
    (when (some? hit)
      (let [[result st] (continue-step st hit k)] [(tick st) result]))))


(defn drive
  "Step (st, cfg) to a value. This is the *resume* half of `run`: a machine
  that was serialized mid-flight (its whole state being datoms) restarts here
  from any config eid. Returns {:value v :st st :steps n}.
  An initial `sync-st-cache` catches any datoms appended to the space after
  serialization (e.g., by another machine); if :cache-seq matches the space
  length (the common case for a just-deserialized machine), it's a no-op."
  [st cfg fuel]
  (let [st (sync-st-cache st)]
    (loop [st st
           cfg cfg
           n 0]
      (when (> n fuel) (throw (ex-info "out of fuel" {:steps n})))
      (let [[st' result] (step st cfg)]
        (cond (halt? result) {:value (second result), :st st', :steps (inc n)}
              (blocked? result) (throw (ex-info "deadlock: rd with no tuple"
                                                {:wait (second result)}))
              :else (recur st' result (inc n)))))))


(defn run
  "Run `expr` to a value. Returns {:value v :st st :steps n}.
  `st` retains the full datom log for time-travel / introspection / migration."
  ([expr] (run expr 100000))
  ([expr fuel] (run expr fuel {}))
  ([expr fuel opts] (let [[st cfg] (inject expr opts)] (drive st cfg fuel))))


(defn- machine-status
  [next]
  (cond (halt? next) {:status :halted, :value (second next)}
        (blocked? next) {:status :blocked, :wait (second next)}
        :else {:status :running}))


(defn- machine-turn
  "Give one machine its turn against the shared space. Returns
  [shared machine progressed?]."
  [shared {:keys [st next], :as machine}]
  (let [st (assoc st :space shared)
        st (sync-st-cache st)]
    (cond (halt? next) [shared machine false]
          (blocked? next) (if-let [[st' result] (try-resume st (second next))]
                            [(:space st')
                             (assoc machine
                                    :st st'
                                    :next result) true]
                            [shared (assoc machine :st st) false])
          :else (let [[st' result] (step st next)]
                  [(:space st')
                   (assoc machine
                          :st st'
                          :next result) true]))))


(defn run-shared
  "Round-robin driver for machines sharing one space. `seeds` are [st cfg]
  pairs from `inject`, given in injection order (each inject chained on the
  previous machine's :space, so the last seed's space is the union). Stops
  when every machine is halted, or when a full round makes no progress
  (remaining machines are blocked on tuples nobody will write). Returns
  {:space space :machines {owner-name {:status ... :value ... :wait ...}}}."
  [seeds fuel]
  (let [shared (or (:space (first (last seeds))) [])
        machines (mapv (fn [[st cfg]] {:st st, :next cfg}) seeds)]
    (loop [shared shared
           machines machines
           n 0]
      (when (> n fuel) (throw (ex-info "out of fuel" {:rounds n})))
      (let [[shared machines progressed?]
            (reduce (fn [[shared ms progressed?] machine]
                      (let [[shared m p?] (machine-turn shared machine)]
                        [shared (conj ms m) (or progressed? p?)]))
                    [shared [] false]
                    machines)]
        (if (or (every? #(halt? (:next %)) machines) (not progressed?))
          {:space shared,
           :machines (into {}
                           (map (fn [{:keys [st next]}]
                                  [(:name st)
                                   (machine-status
                                     next)]))
                           machines)}
          (recur shared machines (inc n)))))))


(comment
  ;; Exploration: the whole machine — C, E, S, K, trace — as one queryable
  ;; space. (q is the ns-level alias for dao.space.query.)
  (def r
    (run '(letrec
           [fact (lambda (n) (if (< n 2) 1 (* n (fact (- n 1)))))]
           (fact 5))))
  (:value r) ; => 120
  (def space (:space (:st r)))
  ;; [1] C is datoms: the program answers semantic.cljc-shaped queries.
  (q/q '[:find ?e :where [?e :yin/type :lambda]] space)
  ;; ... and is genesis — already visible before the first step:
  (q/q '[:find ?e :where [?e :yin/type :lambda]] space {:as-of 0})
  ;; [2] One q spans code AND state: variable nodes joined to live
  ;; bindings.
  (sort (map first
          (q/q '[:find ?v :where [?var :yin/type :variable] [?var :yin/name ?nm]
                 [?b :bind/name ?nm] [?b :bind/addr ?a] [?a :cell/value ?v]]
               space))) ; => (1 2 3 4 5)
  ;; [3] Provenance: why does a cell hold 5? The write names its config.
  (q/q '[:find ?s :where [?a :cell/value 5] [?a :cell/set-by ?cfg]
         [?cfg :cfg/step ?s]]
       space)
  ;; [4] Dead code = control-trace set difference, no instrumentation:
  (let [{:keys [st]} (run '(if (< 1 2) 10 20))
        s (:space st)
        entered (set (map first (q/q '[:find ?n :where [_ :cfg/ctrl ?n]] s)))]
    (remove entered (map first (q/q '[:find ?e :where [?e :yin/type ?_]] s))))
  ;; [5] Ownership: every datom's m is the machine's reified owner entity.
  (set (map peek space)) ; => #{2048}
  (q/pull space 2048 [:agent/name]) ; => {:agent/name machine, :db/id 2048}
  ;; [6] Linda: two machines, one space; bob's out resumes alice's rd.
  (let [[st-a cfg-a] (inject '(+ 1 (rd answer)) {:owner-name 'alice})
        [st-b cfg-b]
          (inject '(out answer 41)
                  {:owner-name 'bob, :space (:space st-a), :eid-base 65536})]
    (run-shared [[st-a cfg-a] [st-b cfg-b]] 1000))
  ;; => alice halts with 42, bob with 41; the wait entity, both owners, and
  ;;    the tuple are all in the shared space, partitioned by m.
  ;; Where this points:
  ;;  - This vocabulary (:frame/* :bind/* :cell/* :k/* :cfg/* :wait/*
  ;;    :tuple/*) is the proposed machine-state extension of the :yin/*
  ;;    schema; the projection of semantic.cljc's E/S/K should emit it.
  ;;  - Hot-path cost: every read re-folds the raw vector. The real
  ;;    integration keeps the log as truth and maps as caches (as
  ;;    semantic.cljc already does for C with :db + :index-arr), promoted
  ;;    at safepoints. The test file's queries are the contract that
  ;;    tiering must preserve.
  ;;  - The `view` chokepoint is where a Shibi capability filter goes:
  ;;    (view st token) instead of the whole space — see
  ;;    docs/design/dao.space.security.md.
)

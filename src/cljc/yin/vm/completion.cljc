(ns yin.vm.completion
  "Dependency completion — the work-item fixed point
   (`docs/design/yin.vm.dependency-completion.md`, U14; refines
   `yin.vm.code-as-tuples.md` §7.7.2–7.7.3 and UCF §7.6.1–7.6.3).

   For one quiescent `SemanticVM`, `complete` computes the conservative
   closure the lift needs: `:yin.k/requires` (every field), the reachable
   store slice, the referenced parked slice, and `:yin.k/discovery`. It
   encodes nothing, lifts nothing, and checks no satisfaction: it hands
   those phases finished sets.

   The unit of analysis is the work item `[address context]` (§7.7.3). An
   address is a content address (`dao.jing/segment-key`); a context is the
   finite abstraction of a captured environment (§4): the set of abstract
   values an env reaches, names and collection shape dropped — sound because
   an environment never discharges a name (UCF §7.6.1 *Names*) and only
   contributes reachable values. Analysis factorizes into code facts (once
   per address) and value facts (once per context); both are memoized in
   the walk state, which is one persistent map, monotone in every field.

   Everything here is a pure function over that state map. The store and
   parked slices are pulled, never copied wholesale, and hold raw emitter
   values: encoding happens after convergence, by the lift."
  (:require [dao.jing :as jing]
            [dao.space.query :as query]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.module :as module]))


;; =============================================================================
;; Vocabulary
;; =============================================================================

(def ffi-pair-keys
  "The FFI pair's store keys (UCF §7.5.4 / §7.6.2): never part of a store
   slice — the pair is a receiver capability, named by `:yin.k/ffi-ops`."
  #{vm/call-in-stream-key vm/call-out-stream-key vm/call-out-cursor-key})


(def ^:private layout-profile
  "The one lowering profile whose body layout `segment-scope` reads."
  "ast-to-bytecode")


(defn- refuse
  "Append one refusal, once: refusals are a vector for stable reporting and
   a set in effect, so re-ruling stays idempotent (§8 step 4)."
  [state refusal]
  (if (some #(= refusal %) (:refusals state))
    state
    (update state :refusals conj refusal)))


;; =============================================================================
;; §4 The context abstraction
;; =============================================================================

(declare context-of)


(defn- address-of
  "The content address of runtime segment id `seg`, through the loaded
   image's `:address`; nil when the image claims none (§3)."
  [vm seg]
  (get-in vm [:code seg :address]))


(defn- typed?
  [v type]
  (and (map? v) (= type (:type v))))


(defn abstract-value
  "§4 `abstract`, flattened: the set of abstract values `v` contributes.
   A scalar contributes none; a collection the union over its elements;
   one VM value exactly one. `env` is `{:vm vm :registry r :canonical-names
   c}`. A closure or continuation over an unaddressed segment contributes
   `[:unaddressed seg]`, which the fold turns into a refusal — there is
   nothing portable to name."
  [env v]
  (let [{:keys [vm registry canonical-names]} env
        addr (fn [seg] (address-of vm seg))
        frame (fn [f]
                (if-let [a (addr (:segment f))]
                  [:frame a (:pc f) (context-of env (:env f))]
                  [:unaddressed (:segment f)]))]
    (cond
      (typed? v :closure)
      (if-let [a (addr (:segment v))]
        #{[:closure a (:entry v) (context-of env (:env v))]}
        #{[:unaddressed (:segment v)]})

      (typed? v :stream-ref) #{[:stream (:id v)]}
      (typed? v :cursor-ref) #{[:cursor (:id v)]}
      (typed? v :parked-continuation) #{[:parked (:id v)]}

      (typed? v :reified-continuation)
      (if-let [a (addr (:segment v))]
        #{[:k a (:pc v) (context-of env (:env v))
           (mapv frame (:k v))
           (into #{} (mapcat #(abstract-value env %)) (:stack v))]}
        #{[:unaddressed (:segment v)]})

      (fn? v)
      (let [sym (vm/name-of registry canonical-names v)]
        (cond
          (nil? sym) #{[:unnamed-function]}
          (= ::vm/ambiguous sym) #{[:ambiguous-primitive]}
          :else #{[:primitive sym]}))

      (map? v) (into #{} (mapcat #(abstract-value env %))
                     (concat (keys v) (vals v)))
      (coll? v) (into #{} (mapcat #(abstract-value env %)) v)
      :else #{})))


(defn context-of
  "§4 `context(E)`: the set of abstract values of E's bindings. A plain
   persistent set — structural equality is context identity. Well-founded:
   env values were created before the env that holds them, so the
   env-reachable graph is acyclic; cycles arise only through the store,
   which is no part of any context."
  [env E]
  (into #{} (mapcat #(abstract-value env %)) (vals E)))


;; =============================================================================
;; §5.1 code-facts(address)
;; =============================================================================

(defn- closure-ranges
  "`[[c body r] …]` for every `:closure` of canonical vector `v`: the body
   of the closure at pc `c` is `[body, r]`, `r` the first `:return` at or
   after `body` (§5.1.1). nil `r` when there is none."
  [v]
  (let [n (count v)]
    (keep (fn [c]
            (let [t (nth v c)]
              (when (= :closure (nth t 0))
                (let [body (nth t 2)]
                  [c body (first (filter #(= :return (nth (nth v %) 0))
                                         (range body n)))]))))
          (range n))))


(defn- layout-conforms?
  "Whether `v` has the `\"ast-to-bytecode\"` body layout the range rule
   reads: every body closed by a `:return`, no body covering pc 0 (bodies
   follow the main code), and bodies pairwise disjoint (contiguous, never
   interleaved). A vector that fails is ruled all-free (§5.1.1)."
  [v]
  (let [ranges (closure-ranges v)
        spans (distinct (map (fn [[_ b r]] [b r]) ranges))]
    (and (every? (fn [[b r]] (and r (pos? b))) spans)
         (= (count spans) (count (distinct (map second spans))))
         (every? (fn [[[b1 r1] [b2 r2]]]
                   (or (< r1 b2) (< r2 b1)))
                 (for [x spans, y spans :when (neg? (compare x y))] [x y])))))


(defn segment-scope
  "§5.1.1's scope relation `#{[address pc c]}` of one canonical vector: pc
   lies in the body of the closure at pc `c`."
  [v]
  (let [address (jing/segment-key v)]
    (into #{}
          (mapcat (fn [[c body r]]
                    (when r
                      (map (fn [pc] [address pc c]) (range body (inc r))))))
          (closure-ranges v))))


(def ^:private segment-scope-rules
  '[[(seg-bound? ?addr ?pc ?name)
     [$scope ?addr ?pc ?c]
     [$code ?addr ?c :closure ?params _]
     [(member? ?params ?name)]]
    [(seg-bound? ?addr ?pc ?name)
     [$scope ?addr ?pc ?c]
     (seg-bound? ?addr ?c ?name)]])


(defn segment-free-names
  "§5.1.1 segment side: every `:var` name free at some pc of canonical
   vector `v`. A `:var` at pc `p` is bound iff `p` lies in the body of a
   `:closure` whose params hold the name, transitively through enclosing
   bodies. `profile` is the segment's lowering profile; anything but
   `\"ast-to-bytecode\"` — or a vector that does not have that profile's
   layout — rules every `:var` free: over-approximating obligations is
   always admissible (§7.7.2)."
  ([v] (segment-free-names v layout-profile))
  ([v profile]
   (let [address (jing/segment-key v)
         db (query/relation (code/project-segment-qualified v))]
     (if (and (= layout-profile profile) (layout-conforms? v))
       (set (map first
                 (query/collect
                   (query/q '[:find ?name :in $code $scope % ?addr
                              :where
                              [$code ?addr ?pc :var ?name]
                              (not (seg-bound? ?addr ?pc ?name))]
                            db
                            (query/relation (segment-scope v))
                            segment-scope-rules
                            address
                            {:fns vm/occurrence-fns}))))
       (set (map first
                 (query/collect
                   (query/q '[:find ?name :in $code
                              :where [$code _ _ :var ?name]]
                            db))))))))


(defn- single-column
  [q db]
  (set (map first (query/collect (query/q q db)))))


(defn code-facts
  "§5.1: what one address contributes regardless of context. `payload` is
   what `fetch` answered: a canonical instruction vector (or
   `{:vector v}`), or a tree `{:root id :rows {id row}}`. `opts`:
     :profile  the segment's lowering profile (default \"ast-to-bytecode\")
     :tree     the tree a `:derive` record lowers to this segment, when the
               ledger holds one — its `free-names` take precedence (§5.1.1)

   `:closures` is per-side diagnostic — entry pcs for a segment, `:lambda`
   row ids for a tree — since the context arrives with the value, not the
   code; the other five fields are the §7.7.1 conformance surface."
  ([payload] (code-facts payload {}))
  ([payload opts]
   (if (and (map? payload) (contains? payload :rows))
     (let [{:keys [root rows]} payload
           db (query/relation (vals rows))]
       (assoc (vm/ast-requirements db)
              :kind :tree
              :free-names (vm/free-names
                            db (query/relation (vm/occurrences payload)) root)
              :closures (into #{} (map vector)
                              (single-column
                                '[:find ?l :in $ast
                                  :where [$ast ?l :lambda _ _]]
                                db))))
     (let [v (if (map? payload) (:vector payload) payload)
           db (query/relation (code/project-segment-qualified v))
           tree (:tree opts)]
       (assoc (vm/segment-requirements db)
              :kind :segment
              :free-names (if tree
                            (vm/free-names
                              (query/relation (vals (:rows tree)))
                              (query/relation (vm/occurrences tree))
                              (:root tree))
                            (segment-free-names
                              v (get opts :profile layout-profile)))
              :closures (into #{} (map vector)
                              (single-column
                                '[:find ?entry :in $code
                                  :where [$code _ _ :closure _ ?entry]]
                                db)))))))


;; =============================================================================
;; Fetch: the emitter's own images first
;; =============================================================================

(def ^:private opcode->mnemonic
  "Inverse of the loader's decode (`yin.vm.semantic`'s mnemonic aliases
   over `vm/opcode-table`). `:tailcall` and `:call` both read back as
   `:call`, the fold undone by `image->vector`."
  (let [aliases {:literal :const, :load-var :var, :lambda :closure,
                 :branch :branch-false, :current-cont :current-continuation,
                 :dao.stream.apply/call :ffi-call}]
    (into {} (map (fn [[k n]] [n (get aliases k k)])) vm/opcode-table)))


(defn image->vector
  "The canonical instruction vector a loaded image decodes from, or nil
   when the reconstruction does not hash to the image's `:address` — an
   address is earned, never trusted, here as at load."
  [image]
  (let [tailcall (:tailcall vm/opcode-table)
        call (:call vm/opcode-table)
        v (mapv (fn [inst]
                  (let [op (nth inst 0)]
                    (cond
                      (= op tailcall) [:call (nth inst 1) true]
                      (= op call) [:call (nth inst 1) false]
                      :else (into [(get opcode->mnemonic op)] (rest inst)))))
                (:code image))]
    (when (and (:address image) (jing/segment-matches? (:address image) v))
      v)))


(defn vm-fetch
  "The emitter's own `fetch`: answer an address from the VM's loaded
   images, else from the caller's `fetch` (e.g. over
   `yin.vm.content/fetch-vector` / `load-rows`). A caller fetch that throws
   the content namespace's \"resolves to no payload\" is a miss, as nil is."
  [vm fetch]
  (fn [address]
    (or (some-> (get (:code-aliases vm) address)
                (->> (get (:code vm)))
                image->vector)
        (when fetch
          (try (fetch address)
               (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 (when-not (= "dao.jing content address resolves to no payload"
                              (ex-message e))
                   (throw e))))))))


;; =============================================================================
;; §5.2 value-facts(context)
;; =============================================================================

(def ^:private empty-value-facts
  {:items #{}, :closures #{}, :streams #{}, :cursors #{}, :parked #{},
   :primitives #{}, :refusals #{}})


(defn- abstract-facts
  "Fold one abstract value into a value-facts map (§5.2)."
  [facts av]
  (case (nth av 0)
    :closure (let [[_ a entry ctx] av]
               (-> facts
                   (update :items conj [a ctx])
                   (update :closures conj [a entry ctx])))
    :stream (update facts :streams conj (nth av 1))
    :cursor (update facts :cursors conj (nth av 1))
    :parked (update facts :parked conj (nth av 1))
    :k (let [[_ a _pc ctx frames stack] av]
         (reduce abstract-facts
                 (update facts :items conj [a ctx])
                 (concat frames stack)))
    :frame (let [[_ a _pc ctx] av] (update facts :items conj [a ctx]))
    :primitive (update facts :primitives conj (nth av 1))
    :unaddressed (update facts :refusals conj
                         {:kind :unaddressed-segment, :segment (nth av 1)})
    :unnamed-function (update facts :refusals conj {:kind :unnamed-function})
    :ambiguous-primitive (update facts :refusals conj
                                 {:kind :ambiguous-primitive})))


(defn value-facts
  "§5.2: what one context — any set of abstract values — contributes
   regardless of address. Pure data; `apply-value-facts` folds it into the
   walk state."
  [context]
  (reduce abstract-facts empty-value-facts context))


;; =============================================================================
;; §6–§7 Discharge and the slices
;; =============================================================================

(declare apply-value-facts)


(defn- push-item
  "An item enters the frontier only if it is in neither `:items` nor the
   frontier (§3)."
  [state item]
  (if (or (contains? (:items state) item)
          (contains? (:queued state) item))
    state
    (-> state
        (update :frontier conj item)
        (update :queued conj item))))


(defn- pull-store
  "§7.1: pull store key `k` when the store holds it; the pulled value is
   abstracted and folded at once. The `:store-slice` key set is the memo
   that closes cycles through the store. An FFI pair key is never pulled."
  [state env k]
  (let [store (:store (:vm env))]
    (if (or (contains? (:store-slice state) k)
            (contains? ffi-pair-keys k)
            (not (contains? store k)))
      state
      (let [v (get store k)
            state (assoc-in state [:store-slice k] v)
            ;; a cursor-entry `{:stream-id id :cursor <opaque>}` pulls its
            ;; stream and names its transport profile (§7.1 rule 4, §9)
            state (if (and (map? v) (contains? v :stream-id)
                           (contains? v :cursor))
                    (-> state
                        (update-in [:requires :yin.k/streams] conj
                                   (:stream-id v))
                        (update-in [:requires :yin.k/cursor-profiles] conj
                                   ((:cursor-profile env) (:cursor v)))
                        (pull-store env (:stream-id v)))
                    state)]
        (apply-value-facts state env (value-facts (abstract-value env v)))))))


(defn- pull-record
  "Fold one `{segment pc env stack k}` register map — a parked record, a
   wait entry, the machine's own frame — as roots (§7.2, §8 step 2)."
  [state env {:keys [segment k stack] E :env}]
  (let [vm (:vm env)
        frames (cond-> (vec k) segment (conj {:segment segment, :env E}))
        state (reduce (fn [state f]
                        (if-let [a (address-of vm (:segment f))]
                          (push-item state [a (context-of env (:env f))])
                          (refuse state {:kind :unaddressed-segment,
                                         :segment (:segment f)})))
                      state
                      frames)]
    (apply-value-facts
      state env
      (value-facts (into #{} (mapcat #(abstract-value env %)) stack)))))


(defn- pull-parked
  "§7.2 with the §12.1 split: a pid absent from `(:parked vm)` is
   `:missing :parked` (→ `:incomplete`) when code names it — a requirement
   the emitter cannot supply — and a `:foreign-parked-ref` refusal when a
   value names it — corrupted state, halting the lift before encoding."
  [state env pid origin]
  (let [parked (:parked (:vm env))]
    (cond
      (contains? (:parked-slice state) pid) state
      (contains? parked pid) (-> state
                                 (assoc-in [:parked-slice pid] (get parked pid))
                                 (pull-record env (get parked pid)))
      (= :code origin) (update-in state [:missing :parked] conj pid)
      :else (refuse state {:kind :foreign-parked-ref, :parked-id pid}))))


(defn- module-of
  "§6 / §12.4: the module a namespaced free name resolves through, in
   `resolve-var`'s terms, as `{:name module :manifest address-or-nil
   :effects set-or-nil}`; nil when the registry holds no such binding.
   Manifests and footprints are declarations the caller supplies under
   `:modules` (`{module {:yin.k/manifest address :yin.k/effects #{…}}}`);
   the VM's registry value only says the binding exists."
  [env sym]
  (when-let [ns-name (namespace sym)]
    (when (some? (module/resolve-module
                   (:modules (:vm env))
                   (symbol (str ns-name "." (name sym)))))
      (let [m (symbol ns-name)
            decl (get (:modules env) m)]
        {:name m,
         :manifest (:yin.k/manifest decl),
         :effects (:yin.k/effects decl)}))))


(defn discharge
  "§6: rule one obligation, in `resolve-var`'s order minus env — store,
   primitives, modules. Idempotent. A name `(:primitives vm)` holds without
   a published profile is `:missing :profiles`, never silently `:pure`."
  [state env sym]
  (let [vm (:vm env)
        profile (vm/profile-of (:registry env) sym)
        ruled (fn [state how] (assoc-in state [:obligations sym] how))]
    (cond
      (contains? (:store vm) sym)
      (-> state (ruled :store) (pull-store env sym))

      profile
      (cond-> (-> state
                  (ruled :primitive)
                  (assoc-in [:requires :yin.k/primitives sym] profile)
                  (update-in [:requires :yin.k/effects] into
                             (:yin.k/effects profile)))
        (= :host (:yin.k/class profile))
        (refuse {:kind :host-state-primitive, :name sym}))

      (contains? (:primitives vm) sym)
      (-> state (ruled :primitive) (update-in [:missing :profiles] conj sym))

      :else
      (if-let [{:keys [name manifest effects]} (module-of env sym)]
        (cond-> (-> state
                    (ruled :module)
                    (assoc-in [:requires :yin.k/modules name] manifest))
          effects (update-in [:requires :yin.k/effects] into effects)
          (nil? effects) (update-in [:missing :footprints] conj name))
        (-> state
            (ruled :undischarged)
            (update-in [:missing :obligations] conj sym))))))


(defn- apply-value-facts
  "Fold one value-facts map into the state: push its work items, pull its
   stream/cursor keys and parked records, discharge its primitives."
  [state env facts]
  (as-> state s
        (reduce push-item s (:items facts))
        (update-in s [:values :closures] into (:closures facts))
        (update-in s [:values :streams] into (:streams facts))
        (update-in s [:values :cursors] into (:cursors facts))
        (update-in s [:values :parked] into (:parked facts))
        (update-in s [:values :primitives] into (:primitives facts))
        (update-in s [:requires :yin.k/streams] into
                   (remove ffi-pair-keys) (:streams facts))
        (reduce refuse s (:refusals facts))
        (reduce #(pull-store %1 env %2) s (:streams facts))
        (reduce #(pull-store %1 env %2) s (:cursors facts))
        (reduce #(pull-parked %1 env %2 :value) s (:parked facts))
        (reduce #(discharge %1 env %2) s (:primitives facts))))


(defn- apply-code-facts
  "§8 step 3's code half: store keys pulled (or `:store-named`), parked ids
   pulled, FFI ops and effects required, free names discharged."
  [state env facts]
  (let [store (:store (:vm env))]
    (as-> state s
          (reduce (fn [s k]
                    (cond
                      (contains? ffi-pair-keys k)
                      (refuse s {:kind :ffi-pair-key, :key k})
                      (contains? store k) (pull-store s env k)
                      :else (update s :store-named conj k)))
                  s (:store-keys facts))
          (reduce #(pull-parked %1 env %2 :code) s (:parked-ids facts))
          (update-in s [:requires :yin.k/ffi-ops] into (:ffi-ops facts))
          (update-in s [:requires :yin.k/effects] into (:effects facts))
          (reduce #(discharge %1 env %2) s (:free-names facts)))))


;; =============================================================================
;; §8 The traversal
;; =============================================================================

(def ^:private empty-queue
  "The frontier is a vector read from the front: one representation on
   every host."
  [])


(defn- queue-pop
  [q]
  (subvec q 1))


(defn- queue-peek
  [q]
  (nth q 0))


(defn- initial-state
  [contract]
  {:contract contract,
   :code {},
   :items #{},
   :frontier empty-queue,
   :queued #{},
   :contexts {},
   :values {:closures #{}, :streams #{}, :cursors #{}, :parked #{},
            :primitives #{}},
   :obligations {},
   :store-slice {},
   :store-named #{},
   :parked-slice {},
   :requires {:yin.k/segments #{},
              :yin.k/primitives {},
              :yin.k/modules {},
              :yin.k/effects #{},
              :yin.k/ffi-ops #{},
              :yin.k/streams #{},
              :yin.k/cursor-profiles #{}},
   :missing {:segments #{}, :parked #{}, :footprints #{}, :profiles #{},
             :obligations #{}},
   :refusals []})


(defn- ensure-code
  "Fetch and analyze `address` once (§7.7.3 \"code once\"). A miss marks it
   `:missing`; the walk goes on over everything else."
  [state env address]
  (if (contains? (:code state) address)
    state
    (if-let [payload ((:fetch env) address)]
      (let [tree? (and (map? payload) (contains? payload :rows))
            facts (code-facts payload
                              (when-not tree?
                                {:profile ((:segment-profile env) address),
                                 :tree ((:tree-of env) address)}))]
        (assoc-in state [:code address]
                  {:kind (:kind facts),
                   :facts (dissoc facts :kind),
                   :status :loaded}))
      (-> state
          (assoc-in [:code address] {:kind nil, :facts nil, :status :missing})
          (update-in [:missing :segments] conj address)))))


(defn- ensure-context
  [state context]
  (if (contains? (:contexts state) context)
    state
    (assoc-in state [:contexts context] (value-facts context))))


(defn- analyze
  "§8 step 3 for one work item."
  [state env [address context :as item]]
  (let [state (-> state
                  (update :items conj item)
                  (ensure-code env address)
                  (ensure-context context))
        state (apply-value-facts state env (get-in state [:contexts context]))]
    (if-let [facts (get-in state [:code address :facts])]
      (apply-code-facts state env facts)
      state)))


(defn- drain
  [state env]
  (loop [state state]
    (if (empty? (:frontier state))
      state
      (let [item (queue-peek (:frontier state))]
        (recur (analyze (-> state
                            (update :frontier queue-pop)
                            (update :queued disj item))
                        env item))))))


(defn- seed
  "§8 steps 1–2: the early refusals, then the roots — the frame, the K
   frames, the operand stack and accumulator, and every wait entry with its
   `:stream-id` / `:cursor-ref` as abstract values."
  [state env]
  (let [vm (:vm env)
        state (cond-> state
                (seq (:ready-queue vm)) (refuse {:kind :yin.k/not-quiescent}))
        state (reduce (fn [state [seg image]]
                        (if (:address image)
                          state
                          (refuse state {:kind :unaddressed-segment,
                                         :segment seg})))
                      state
                      (sort-by (comp str key) (:code vm)))
        state (pull-record state env
                           {:segment (:segment (:control vm)),
                            :env (:env vm),
                            :k (:k vm),
                            :stack (conj (vec (:stack vm)) (:value vm))})]
    (reduce (fn [state entry]
              (let [refs (cond-> #{}
                           (:stream-id entry) (conj [:stream (:stream-id entry)])
                           (:cursor-ref entry)
                           (into (abstract-value env (:cursor-ref entry))))]
                (-> state
                    (pull-record env entry)
                    (apply-value-facts env (value-facts refs)))))
            state
            (:wait-set vm))))


(defn- verify
  "§8 step 4, the literal §7.7.3 criterion: with the frontier empty,
   re-run value-facts over all contexts and discharge over all obligations;
   the state must not change. A change is a bug in the walk, not a
   legitimate continuation."
  [state env]
  (let [again (as-> state s
                    (reduce #(apply-value-facts %1 env (value-facts %2))
                            s (keys (:contexts state)))
                    (reduce #(discharge %1 env %2) s (keys (:obligations state))))]
    (when-not (= again state)
      (throw (ex-info "Dependency completion did not converge: the verification pass changed the state"
                      {:rule :not-converged})))
    state))


(defn discovery
  "§8 step 5: `:blocked` dominates `:incomplete` dominates `:complete`."
  [{:keys [missing]}]
  (cond
    (seq (:segments missing)) :blocked
    (some seq (vals (dissoc missing :segments))) :incomplete
    :else :complete))


(defn walk
  "§8 steps 1–4: the converged, verified walk state of §3. `complete`
   assembles its result from this; tests read it directly."
  [{:keys [vm fetch registry modules contract cursor-profile tree-of
           segment-profile]}]
  (let [env {:vm vm,
             :fetch (vm-fetch vm fetch),
             :registry (or registry (:primitives vm)),
             :canonical-names (or (:primitive-canonical-names vm) {}),
             :modules (or modules {}),
             :cursor-profile (or cursor-profile (constantly nil)),
             :tree-of (or tree-of (constantly nil)),
             :segment-profile (or segment-profile (constantly layout-profile))}]
    (-> (initial-state (or contract "v2"))
        (seed env)
        (drain env)
        (verify env))))


(defn complete
  "`{:vm :fetch :registry :modules :contract …} → result` (§8–§9).

     :vm              a quiescent SemanticVM
     :fetch           address → canonical vector | {:root :rows} | nil, asked
                      after the VM's own loaded images
     :registry        primitive registry for `profile-of`/`name-of`
                      (default `(:primitives vm)`)
     :modules         `{module {:yin.k/manifest address :yin.k/effects #{…}}}`
     :contract        execution contract stamp (default \"v2\")
     :cursor-profile  opaque cursor → transport profile
     :tree-of         segment address → `{:root :rows}` a `:derive` record
                      names, or nil (§5.1.1 precedence)
     :segment-profile segment address → lowering profile
                      (default \"ast-to-bytecode\")

   The store and parked slices are raw emitter values; the lift encodes
   them. A non-empty `:yin.k/refusals` means the lift refuses."
  [opts]
  (let [state (walk opts)
        status (discovery state)
        loaded (into #{} (keep (fn [[a c]] (when (= :loaded (:status c)) a)))
                     (:code state))]
    {:yin.k/requires (-> (:requires state)
                         (update :yin.k/cursor-profiles disj nil)
                         (assoc :yin.k/segments
                                (into loaded (:segments (:missing state)))
                                :yin.k/discovery status)),
     :yin.k/store (:store-slice state),
     :yin.k/scheduler {:yin.k/parked (:parked-slice state),
                       :yin.k/id-counter (:id-counter (:vm opts))},
     :yin.k/missing (:missing state),
     :yin.k/refusals (:refusals state),
     ::work-items (:items state)}))

(ns yin.vm.debruijn-resolve
  "B2 (docs/design/yin.vm.debruijn.stack.md S3.1): the de Bruijn resolver.
   `resolve` walks named `:yin/*` datoms once, in the named linearizer's
   own evaluation order (operator then operands left to right, `if` test
   then arms, a lambda's body under its own parameter vector pushed
   innermost-first), and produces resolved tuples: one resolved record per
   OCCURRENCE of a source node, keyed by `[source-eid lexical-context]`
   (`lexical-context` the complete innermost-first stack of enclosing
   parameter vectors at that occurrence). Two references to a shared source
   entity under equal contexts hit the same record; under unequal contexts
   they mint two distinct records, each independently resolved -- a shared
   entity's occurrences never overwrite one another's resolution the way a
   bare-entity-keyed memo would.

   A resolved record's id is always fresh and deterministic (first-visit
   order) and never equals its source entity's id. A `:variable` record
   carries `:yin.resolved/depth`+`:yin.resolved/position` (bound, from
   `resolve-name`'s `{:bound [depth position]}`) or `:yin.resolved/free`
   (free, from `{:free name}`) in place of `:yin/name`. A `:lambda` record
   carries `:yin.resolved/arity` in place of `:yin/params`. Every other
   attribute is carried unchanged, including structural child refs, which
   are rewritten to point at the child's own resolved record id. `resolve`
   returns `{:tuples resolved-tuples, :source {resolved-id source-eid},
   :params {resolved-lambda-id [param-symbol ...]}}`: `:source` names every
   record's source entity (total over the records), `:params` names every
   resolved lambda's exact parameter vector. No per-occurrence name is
   stored; a bound occurrence's original spelling is recoverable from the
   owning resolved lambda's parameter at the resolved position.

   `resolve-name` (`yin.vm.debruijn`, line ~606) is the one helper reused
   from the merged dormant projection namespace, exactly as section 3
   requires; no other helper there is reached, and this namespace does
   not touch `yin.vm.linearize` or named lowering semantics at all -- it
   reads only named `:yin/*` datoms and the public `yin.vm/index-datoms`
   reader `yin.vm.linearize` itself uses for the same purpose."
  (:refer-clojure :exclude [resolve])
  (:require [yin.vm :as vm]
            [yin.vm.debruijn :as debruijn]))


;; =============================================================================
;; Tree shape: every supported node type's child slots, in evaluation order
;; =============================================================================
;; Shared by `resolve`'s build walk and `validate-resolved`'s independent
;; check walk, so the two can never silently disagree about which entities
;; are children of which. `:literal`, `:variable`, `:stream/make`,
;; `:vm/gensym`, `:vm/store-get`, `:vm/store-put`, `:vm/park`, and
;; `:vm/current-continuation` have no child slots and are leaves; `:lambda`
;; is handled specially by every walk below because it also carries scope
;; (a parameter vector, or its resolved arity), not only a child.

(def ^:private child-slots
  "Node type -> ordered `[attr kind]` child slots, `kind` one of `:child`
   (one entity ref) or `:children` (an ordered vector of entity refs).
   Mirrors `yin.vm.linearize/flatten-program`'s own case dispatch, read
   from the same `:yin/*` attributes, but as data instead of code."
  {:application [[:yin/operator :child] [:yin/operands :children]]
   :dao.stream.apply/call [[:yin/operands :children]]
   :if [[:yin/test :child] [:yin/consequent :child] [:yin/alternate :child]]
   :stream/put [[:yin/target :child] [:yin/val-node :child]]
   :stream/cursor [[:yin/source :child]]
   :stream/next [[:yin/source :child]]
   :stream/close [[:yin/source :child]]
   :vm/resume [[:yin/val-node :child]]})


(def ^:private unsupported-types
  "Node types outside both evaluators' supported corpus, matching
   `yin.vm.linearize/unsupported` exactly -- read here, not required
   from there, since that namespace stays untouched."
  #{:yin/macro-expand :vm/store-update})


;; =============================================================================
;; resolve: named datoms -> resolved tuples + provenance side table
;; =============================================================================

(defn- reject-host-value!
  [source attr v]
  (when-not (vm/plain-data? v)
    (throw (ex-info (str "Cannot resolve a host value into " attr)
                    {:node source, :attr attr, :value v}))))


(defn- source-tm
  "`{source-eid [t m]}`, one entry per entity, from its first datom --
   every datom `ast->datoms-with-root` emits for one entity shares one
   `t`/`m` pair, so the first is exact for every attribute."
  [datoms]
  (reduce (fn [acc [e _a _v t m]]
            (if (contains? acc e) acc (assoc acc e [t m])))
          {}
          datoms))


(declare visit! validate-resolved)


(defn- put!
  [ctx rid source-e a v]
  (let [[t m] (get (:tm ctx) source-e [0 :db/add])]
    (swap! (:rows ctx) conj [rid a v t m])))


(declare visit!)


(defn- emit-node!
  "Builds one resolved record's rows (into `(:rows ctx)`) for source
   entity `e` under `stack`, recursing into every child slot through
   `visit!` so a child gets its own occurrence identity under this node's
   extended `stack`/`active`/`path`. `rid` is `e`'s already-minted resolved
   id for this occurrence."
  [ctx e stack rid active path]
  (let [get-attr (:get-attr ctx)
        type (get-attr e :yin/type)
        emit! (fn [a v] (put! ctx rid e a v))
        child! (fn [child-e s] (visit! ctx child-e s active path))]
    (when (nil? type)
      (throw (ex-info "Cannot resolve a node with no :yin/type" {:entity e})))
    (when (contains? unsupported-types type)
      (throw (ex-info (str "Cannot resolve unsupported node " type)
                      {:type type, :entity e})))
    (when-let [tail? (get-attr e :yin/tail?)]
      (emit! :yin/tail? tail?))
    (case type
      :literal
      (let [val (get-attr e :yin/value)]
        (reject-host-value! e :yin/value val)
        (emit! :yin/type :literal)
        (emit! :yin/value val))

      :variable
      (let [name (get-attr e :yin/name)]
        (reject-host-value! e :yin/name name)
        (emit! :yin/type :variable)
        (let [resolution (debruijn/resolve-name stack name)]
          (if-let [bound (:bound resolution)]
            (do (emit! :yin.resolved/depth (nth bound 0))
                (emit! :yin.resolved/position (nth bound 1)))
            (emit! :yin.resolved/free (:free resolution)))))

      :lambda
      (let [params (get-attr e :yin/params)
            macro? (get-attr e :yin/macro?)]
        (reject-host-value! e :yin/params params)
        (emit! :yin/type :lambda)
        (when macro? (emit! :yin/macro? macro?))
        (emit! :yin.resolved/arity (count params))
        (swap! (:params ctx) assoc rid params)
        (let [body-rid (child! (get-attr e :yin/body) (into [params] stack))]
          (emit! :yin/body body-rid)))

      :application
      (do (emit! :yin/type :application)
          (let [op-rid (child! (get-attr e :yin/operator) stack)
                operand-rids (mapv #(child! % stack) (get-attr e :yin/operands))]
            (emit! :yin/operator op-rid)
            (emit! :yin/operands operand-rids)))

      :dao.stream.apply/call
      (let [op (get-attr e :yin/op)]
        (reject-host-value! e :yin/op op)
        (emit! :yin/type :dao.stream.apply/call)
        (emit! :yin/op op)
        (emit! :yin/operands (mapv #(child! % stack) (get-attr e :yin/operands))))

      :if
      (do (emit! :yin/type :if)
          (let [test-rid (child! (get-attr e :yin/test) stack)
                cons-rid (child! (get-attr e :yin/consequent) stack)
                alt-rid (child! (get-attr e :yin/alternate) stack)]
            (emit! :yin/test test-rid)
            (emit! :yin/consequent cons-rid)
            (emit! :yin/alternate alt-rid)))

      :vm/gensym
      (let [prefix (get-attr e :yin/prefix)]
        (reject-host-value! e :yin/prefix prefix)
        (emit! :yin/type :vm/gensym)
        (emit! :yin/prefix prefix))

      :vm/store-get
      (let [key (get-attr e :yin/key)]
        (reject-host-value! e :yin/key key)
        (emit! :yin/type :vm/store-get)
        (emit! :yin/key key))

      :vm/store-put
      (let [key (get-attr e :yin/key)
            val (get-attr e :yin/value)]
        (reject-host-value! e :yin/key key)
        (reject-host-value! e :yin/value val)
        (emit! :yin/type :vm/store-put)
        (emit! :yin/key key)
        (emit! :yin/value val))

      :stream/make
      (let [buffer (get-attr e :yin/buffer)]
        (reject-host-value! e :yin/buffer buffer)
        (emit! :yin/type :stream/make)
        (emit! :yin/buffer buffer))

      :stream/put
      (do (emit! :yin/type :stream/put)
          (let [target-rid (child! (get-attr e :yin/target) stack)
                val-rid (child! (get-attr e :yin/val-node) stack)]
            (emit! :yin/target target-rid)
            (emit! :yin/val-node val-rid)))

      (:stream/cursor :stream/next :stream/close)
      (do (emit! :yin/type type)
          (emit! :yin/source (child! (get-attr e :yin/source) stack)))

      :vm/park
      (emit! :yin/type :vm/park)

      :vm/current-continuation
      (emit! :yin/type :vm/current-continuation)

      :vm/resume
      (let [parked-id (get-attr e :yin/parked-id)]
        (reject-host-value! e :yin/parked-id parked-id)
        (emit! :yin/type :vm/resume)
        (emit! :yin/parked-id parked-id)
        (emit! :yin/val-node (child! (get-attr e :yin/val-node) stack)))

      (throw (ex-info (str "Cannot resolve node of unknown type " type)
                      {:type type, :entity e})))))


(defn- visit!
  "Returns the resolved id for source entity `e`'s occurrence under
   `stack`, minting a fresh one and emitting its rows on first visit,
   reusing the memoized id for a repeat of the same `[e stack]` occurrence.
   `active` (source entities on the current DFS path) is checked before
   the memo, matching the dormant projection's own `project-node`: an
   entity revisited before its own resolution has completed is a cycle
   even if some other occurrence of it was already memoized."
  [ctx e stack active path]
  (when (contains? active e)
    (throw (ex-info "Cyclic AST reference"
                    {:rule :cycle, :entity e, :path path})))
  (let [occ [e stack]]
    (if-let [existing (find @(:memo ctx) occ)]
      (val existing)
      (let [rid ((:next-id! ctx))]
        (swap! (:memo ctx) assoc occ rid)
        (swap! (:source ctx) assoc rid e)
        (emit-node! ctx e stack rid (conj active e) (conj path e))
        rid))))


(defn resolve
  "Named `:yin/*` datoms (or an `:yin/root`-marked batch of them) ->
   `{:tuples resolved-tuples, :source side-table, :params side-table}`
   (section 3.1). Every occurrence of a source node gets its own resolved
   record and id (never equal to the source id); `:source` maps every
   record id to its source entity, `:params` maps every resolved lambda's
   id to its exact parameter vector. A `:variable` record carries
   `:yin.resolved/depth`+`:yin.resolved/position` or `:yin.resolved/free`
   in place of `:yin/name`; a `:lambda` record carries
   `:yin.resolved/arity` in place of `:yin/params`. Every other attribute,
   including `:yin/tail?` and every structural child ref (rewritten to the
   child's own record id), is carried unchanged.

   Throws naming the node on an unsupported or unknown node type, a host
   value in a data operand (exactly as `yin.vm.linearize/lower` throws for
   the same conditions on the same input), or a cyclic reference. Also
   runs `validate-resolved` on the tuples and side table it just built,
   before returning them, so a program whose bound reference would fall
   outside its enclosing arity chain -- impossible from this function's own
   walk, but not from a hand-built resolved-tuple set -- is refused here
   rather than reaching a lowerer."
  [named-datoms]
  (let [datoms (vec named-datoms)
        {:keys [get-attr root-id error]} (vm/index-datoms datoms)]
    (when error
      (throw (ex-info "Cannot resolve a program with a dangling root" error)))
    (when (nil? root-id)
      (throw (ex-info "Cannot resolve a program with no root" {})))
    (let [ctx {:get-attr get-attr
               :memo (atom {})
               :source (atom {})
               :params (atom {})
               :rows (atom [])
               :next-id! (let [counter (atom -1)]
                           #(let [id @counter] (swap! counter dec) id))
               :tm (source-tm datoms)}
          root-rid (visit! ctx root-id [] #{} [])
          [t m] (get (:tm ctx) root-id [0 :db/add])
          _ (swap! (:rows ctx) conj [root-rid :yin/root true t m])
          tuples (vec @(:rows ctx))
          source @(:source ctx)
          params @(:params ctx)
          defect (validate-resolved tuples source)]
      (when defect
        (throw (ex-info "Cannot resolve to an invalid resolved-tuple set"
                        {:defect defect})))
      {:tuples tuples, :source source, :params params})))


;; =============================================================================
;; validate-resolved: the resolver's own independent check, over resolved
;; tuples plus their provenance side table
;; =============================================================================

(defn- count?
  [x]
  (and (integer? x) (not (neg? x))))


(defn- variable-scope-defect
  [get-attr e chain]
  (let [depth (get-attr e :yin.resolved/depth)
        position (get-attr e :yin.resolved/position)
        free (get-attr e :yin.resolved/free)]
    (cond
      (some? free) nil

      (not (and (count? depth) (count? position)))
      {:rule :scope, :entity e}

      (>= depth (count chain))
      {:rule :scope, :entity e}

      (>= position (nth chain depth))
      {:rule :scope, :entity e}

      :else nil)))


(defn- walk-scope
  "The tree-level scope check (section 3.1, section 3.2 item 1): walks
   resolved tuples from `e` under `chain` (the innermost-first vector of
   enclosing arities), checking every `:variable`'s resolved depth and
   position address a real enclosing lambda and a real parameter inside
   it. Returns the first `{:rule :scope :entity e}` defect, or nil.

   `active` (records on the current walk path) guards against a
   hand-built cyclic resolved-tuple set -- `resolve`'s own output can
   never be cyclic (`visit!` already refuses that), but this validator
   is also run on possibly hand-built input, so it needs the same guard.
   A revisit before its own walk completes is refused as `{:rule :cycle}`
   instead of recursing forever.

   Assumes the tuples are otherwise shape valid; an unknown or malformed
   `:yin/type` is simply not a scope defect, and is not diagnosed here."
  [get-attr e chain active]
  (if (contains? active e)
    {:rule :cycle, :entity e}
    (let [type (get-attr e :yin/type)
          active (conj active e)]
      (cond
        (= :variable type) (variable-scope-defect get-attr e chain)

        (= :lambda type)
        (let [arity (get-attr e :yin.resolved/arity)]
          (if-not (count? arity)
            {:rule :scope, :entity e}
            (walk-scope get-attr (get-attr e :yin/body) (into [arity] chain) active)))

        (contains? child-slots type)
        (some (fn [[attr kind]]
                (case kind
                  :child (walk-scope get-attr (get-attr e attr) chain active)
                  :children (some #(walk-scope get-attr % chain active)
                                  (get-attr e attr))))
              (get child-slots type))

        :else nil))))


(defn- collect-refs
  "Every child-ref value `e`'s own attributes name, for the dangling-ref
   check below -- a flat pass, not a walk, since membership does not need
   the enclosing stack."
  [get-attr e]
  (let [type (get-attr e :yin/type)]
    (cond
      (= :lambda type) [(get-attr e :yin/body)]

      (contains? child-slots type)
      (mapcat (fn [[attr kind]]
                (case kind
                  :child [(get-attr e attr)]
                  :children (get-attr e attr)))
              (get child-slots type))

      :else [])))


(defn- attr-present?
  [by-entity e attr]
  (some (fn [[_e a]] (= a attr)) (get by-entity e)))


(defn- missing-children-defect
  "`get-attr` returns `[]` alike for an absent `:children`-kind attribute
   and one present with an empty vector, so the dangling-ref check above
   cannot tell them apart -- a hand-built node with no `:yin/operands`
   datom at all would otherwise pass validation, asymmetric with a
   `:child`-kind slot (a missing one IS caught, as a dangling `nil` ref).
   Checks `by-entity` (raw datom presence) directly instead."
  [get-attr by-entity e]
  (let [type (get-attr e :yin/type)]
    (when (contains? child-slots type)
      (some (fn [[attr kind]]
              (when (and (= kind :children)
                         (not (attr-present? by-entity e attr)))
                {:rule :missing-operands, :entity e, :attr attr}))
            (get child-slots type)))))


(defn validate-resolved
  "Section 3.1's own validator, independent of `resolve`: `tuples` --
   `resolve`'s own output, or a hand-built set -- plus its `source` side
   table (`{resolved-id source-eid}`), checked for: tree shape, every
   bound reference addressing a real enclosing lambda and a real
   parameter inside it (the tree-level scope check), every structural
   child ref resolving to a known record, no record carrying both a bound
   and a free resolution, and `source` being exactly the record set
   `tuples` itself names (every entity with a `:yin/type` row, no more no
   less). nil when every check passes, else the first `{:rule ...}`
   defect found."
  [tuples source]
  (let [{:keys [get-attr by-entity root-id error]} (vm/index-datoms tuples)]
    (cond
      error {:rule :shape, :reason error}
      (nil? root-id) {:rule :shape, :reason :no-root}
      :else
      (let [node-ids (into #{} (keep (fn [[e a]] (when (= a :yin/type) e))) tuples)
            dangling (some (fn [e]
                             (some (fn [r]
                                     (when-not (contains? node-ids r)
                                       {:rule :dangling-ref, :entity e, :ref r}))
                                   (collect-refs get-attr e)))
                           node-ids)
            missing (some #(missing-children-defect get-attr by-entity %) node-ids)
            ambiguous (some (fn [e]
                              (when (and (= :variable (get-attr e :yin/type))
                                         (some? (get-attr e :yin.resolved/free))
                                         (or (some? (get-attr e :yin.resolved/depth))
                                             (some? (get-attr e :yin.resolved/position))))
                                {:rule :ambiguous-resolution, :entity e}))
                            node-ids)
            scope (walk-scope get-attr root-id [] #{})]
        (or dangling
            missing
            ambiguous
            scope
            (when (not= node-ids (set (keys source)))
              {:rule :source-total}))))))


;; =============================================================================
;; unresolve: resolved tuples + provenance side table -> named datoms
;; =============================================================================
;; The test oracle section 3.1 requires: `unresolve(resolve x, source,
;; params) = x` over the corpus, including a shared source entity: every
;; resolved record whose `:source` names that entity carries equal facts
;; by construction (they came from the same original datom row set under
;; the shared entity's own attributes), so this walk emits one entity's
;; rows once, at its first-visited record, and returns its source id
;; without re-emitting for a later record of the same source -- the merge
;; step, mirroring `ast->datoms-with-root`'s own `seen-eids` dedup for a
;; pre-assigned `:eid` shared at the NAMED level.

(defn- resolved-tm
  [tuples]
  (reduce (fn [acc [rid _a _v t m]]
            (if (contains? acc rid) acc (assoc acc rid [t m])))
          {}
          tuples))


(defn- put-source!
  [ctx rid a v]
  (let [[t m] (get (:tm ctx) rid [0 :db/add])
        e (get (:source ctx) rid)]
    (swap! (:rows ctx) conj [e a v t m])))


(defn- unresolve-node!
  "Returns the source entity for record `rid`, emitting its named rows on
   first visit (skipping re-emission, per the merge step above, when this
   source entity was already emitted by an earlier record). `chain` is
   the innermost-first vector of enclosing resolved LAMBDA record ids, so
   a bound occurrence's original spelling is read from `(:params ctx)` at
   the owning lambda's resolved position."
  [ctx rid chain]
  (let [e (get (:source ctx) rid)
        emit! (fn [a v] (put-source! ctx rid a v))]
    (if (contains? @(:seen ctx) e)
      e
      (do
        (swap! (:seen ctx) conj e)
        (let [get-attr (:get-attr ctx)
              type (get-attr rid :yin/type)]
          (when-let [tail? (get-attr rid :yin/tail?)]
            (emit! :yin/tail? tail?))
          (emit! :yin/type type)
          (case type
            :literal (emit! :yin/value (get-attr rid :yin/value))

            :variable
            (let [free (get-attr rid :yin.resolved/free)]
              (emit! :yin/name
                     (if (some? free)
                       free
                       (let [depth (get-attr rid :yin.resolved/depth)
                             position (get-attr rid :yin.resolved/position)
                             owner (nth chain depth)]
                         (nth (get (:params ctx) owner) position)))))

            :lambda
            (do (when-let [macro? (get-attr rid :yin/macro?)]
                  (emit! :yin/macro? macro?))
                (emit! :yin/params (get (:params ctx) rid))
                (emit! :yin/body
                       (unresolve-node! ctx (get-attr rid :yin/body) (into [rid] chain))))

            :application
            (let [op-source (unresolve-node! ctx (get-attr rid :yin/operator) chain)
                  operand-sources (mapv #(unresolve-node! ctx % chain)
                                        (get-attr rid :yin/operands))]
              (emit! :yin/operator op-source)
              (emit! :yin/operands operand-sources))

            :dao.stream.apply/call
            (do (emit! :yin/op (get-attr rid :yin/op))
                (emit! :yin/operands (mapv #(unresolve-node! ctx % chain)
                                           (get-attr rid :yin/operands))))

            :if
            (let [test-source (unresolve-node! ctx (get-attr rid :yin/test) chain)
                  cons-source (unresolve-node! ctx (get-attr rid :yin/consequent) chain)
                  alt-source (unresolve-node! ctx (get-attr rid :yin/alternate) chain)]
              (emit! :yin/test test-source)
              (emit! :yin/consequent cons-source)
              (emit! :yin/alternate alt-source))

            :vm/gensym (emit! :yin/prefix (get-attr rid :yin/prefix))
            :vm/store-get (emit! :yin/key (get-attr rid :yin/key))
            :vm/store-put (do (emit! :yin/key (get-attr rid :yin/key))
                              (emit! :yin/value (get-attr rid :yin/value)))
            :stream/make (emit! :yin/buffer (get-attr rid :yin/buffer))

            :stream/put
            (let [target-source (unresolve-node! ctx (get-attr rid :yin/target) chain)
                  val-source (unresolve-node! ctx (get-attr rid :yin/val-node) chain)]
              (emit! :yin/target target-source)
              (emit! :yin/val-node val-source))

            (:stream/cursor :stream/next :stream/close)
            (emit! :yin/source (unresolve-node! ctx (get-attr rid :yin/source) chain))

            :vm/park nil
            :vm/current-continuation nil

            :vm/resume
            (do (emit! :yin/parked-id (get-attr rid :yin/parked-id))
                (emit! :yin/val-node
                       (unresolve-node! ctx (get-attr rid :yin/val-node) chain)))

            nil))
        e))))


(defn unresolve
  "The inverse of `resolve` (section 3.1's test oracle): resolved
   `tuples` plus their `source`/`params` provenance side table -> named
   datoms, with every record of one source entity merged back into that
   one entity, so `(= named-datoms (unresolve (:tuples r) (:source r)
   (:params r)))` holds exactly over the corpus, `r` = `(resolve
   named-datoms)`, including a shared-entity fixture."
  [tuples source params]
  (let [{:keys [get-attr root-id]} (vm/index-datoms tuples)]
    (if (nil? root-id)
      []
      (let [ctx {:get-attr get-attr, :source source, :params params,
                 :rows (atom []), :seen (atom #{}), :tm (resolved-tm tuples)}
            root-source (unresolve-node! ctx root-id [])
            [t m] (get (:tm ctx) root-id [0 :db/add])]
        (conj (vec @(:rows ctx)) [root-source :yin/root true t m])))))

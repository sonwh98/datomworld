(ns yin.vm.macro
  "Canonical datom-stream macro expansion engine.

   Implements the macro expansion model from docs/macros.md:
   - Macros are lambda entities with :yin/macro? true and :yin/phase-policy
   - :yin/macro-expand nodes mark call sites in the datom stream
   - Expansion output datoms carry m = expansion-event-eid for provenance
   - Original call datoms are immutable and unchanged
   - Compile-time: fixpoint expansion on bounded stream before bytecode compilation
   - Runtime: semantic VM expands :yin/macro-expand nodes inline
   - Guard limits: max depth 100, max datoms 10,000"
  (:require
    [yin.vm :as vm]
    [yin.vm.engine :as engine]))


;; =============================================================================
;; Guard limits
;; =============================================================================

(def default-max-depth 100)
(def default-max-datoms 10000)


;; =============================================================================
;; AST ref attribute sets
;; =============================================================================

(def ^:private single-ref-attrs
  "Single entity-ref valued attributes in AST nodes."
  #{:yin/body
    :yin/operator
    :yin/test
    :yin/consequent
    :yin/alternate
    :yin/source
    :yin/target
    :yin/val-node})


(def ^:private vec-ref-attrs
  "Vector entity-ref valued attributes in AST nodes."
  #{:yin/operands})


;; =============================================================================
;; EID management
;; =============================================================================

(defn- min-datom-eid
  "Find the minimum entity ID across all datoms. Returns -1025 if datoms is empty."
  [datoms]
  (if (empty? datoms)
    -1025
    (transduce (map first) (completing min) 0 datoms)))


(defn make-eid-counter!
  "Create an atom for generating fresh negative EIDs below all existing ones."
  [datoms]
  (atom (dec (min-datom-eid datoms))))


;; =============================================================================
;; Provenance: expansion event datoms
;; =============================================================================

(defn expansion-event-datoms
  "Build datoms for a :macro-expand-event provenance entity.

   call-eid: EID of the :yin/macro-expand call site (immutable, unchanged)
   macro-lambda-eid: EID of the macro lambda entity
   expansion-root-eid: EID of top-level node produced by expansion
   phase: :compile or :runtime"
  [event-eid call-eid macro-lambda-eid expansion-root-eid phase]
  [[event-eid :yin/type :macro-expand-event 0 0]
   [event-eid :yin/source-call call-eid 0 0]
   [event-eid :yin/macro macro-lambda-eid 0 0]
   [event-eid :yin/phase phase 0 0]
   [event-eid :yin/expansion-root expansion-root-eid 0 0]])


(defn mark-with-provenance
  "Set m = event-eid on all datoms. Returns updated datom vector."
  [datoms event-eid]
  (mapv (fn [[e a v t _m]] [e a v t event-eid]) datoms))


;; =============================================================================
;; Tree transform
;; =============================================================================

(defn- copy-node-datoms
  "Create new datoms for node `eid` mapped to `new-eid`, substituting child refs
   per `subst-map`. All new datoms carry m = provenance-eid."
  [by-entity eid new-eid subst-map provenance-eid]
  (let [orig (get by-entity eid [])]
    (mapv (fn [[_e a v _t _m]]
            (let [new-v (cond
                          (contains? single-ref-attrs a)
                          (get subst-map v v)
                          (contains? vec-ref-attrs a)
                          (mapv #(get subst-map % %) v)
                          :else v)]
              [new-eid a new-v 0 provenance-eid]))
          orig)))


(defn- transform
  "Post-order tree transform. Expands all :yin/macro-expand nodes reachable from eid.

   macro-registry: {macro-lambda-eid -> (fn [ctx] {:datoms [...] :root-eid eid})}
   phase: :compile or :runtime
   visited: set of EIDs in the current DFS path (cycle guard)

   Returns [result-eid new-datoms expanded?] where:
   - result-eid: EID to use in place of eid (same as eid if no expansion)
   - new-datoms: new datoms produced during this subtree's expansion
   - expanded?: true if any expansion occurred"
  [get-attr by-entity eid macro-registry eid-counter phase visited]
  (if (contains? visited eid)
    ;; Shared reference or cycle guard: return original EID unchanged
    [eid [] false]
    (let [node-type (get-attr eid :yin/type)]
      (cond
        ;; Macro call site: invoke the macro and return the expansion root
        (= :yin/macro-expand node-type)
        (let [macro-lambda-eid (get-attr eid :yin/operator)
              arg-eids (or (get-attr eid :yin/operands) [])
              macro-fn (get macro-registry macro-lambda-eid)]
          (when-not macro-fn
            (throw (ex-info "No macro function registered for macro lambda EID"
                            {:macro-lambda-eid macro-lambda-eid
                             :call-eid eid
                             :phase phase
                             :registered-keys (keys macro-registry)})))
          (let [event-eid (swap! eid-counter dec)
                fresh-eid-fn (fn [] (swap! eid-counter dec))
                ctx {:get-attr get-attr
                     :by-entity by-entity
                     :arg-eids arg-eids
                     :fresh-eid fresh-eid-fn
                     :phase phase}
                result (macro-fn ctx)
                exp-datoms (:datoms result)
                exp-root (:root-eid result)
                evt-datoms (expansion-event-datoms
                             event-eid eid macro-lambda-eid exp-root phase)
                marked-exp (mark-with-provenance exp-datoms event-eid)
                all-new (vec (concat evt-datoms marked-exp))]
            [exp-root all-new true]))

        ;; Regular node: recurse into children, rebuild if any child changed
        :else
        (let [visited' (conj visited eid)
              ;; Transform single-ref children: {attr -> {:old eid :new eid :datoms [...] :expanded? bool}}
              single-results
              (into {}
                    (keep (fn [attr]
                            (when-let [child-eid (get-attr eid attr)]
                              (let [[new-child child-datoms child-exp?]
                                    (transform get-attr by-entity child-eid
                                               macro-registry eid-counter phase visited')]
                                [attr {:old child-eid
                                       :new new-child
                                       :datoms child-datoms
                                       :expanded? child-exp?}])))
                          single-ref-attrs))
              ;; Transform vector-ref children: {attr -> {:old [eid...] :new [eid...] :datoms [...] :expanded? bool}}
              vec-results
              (into {}
                    (keep (fn [attr]
                            (when-let [child-eids (get-attr eid attr)]
                              (let [results (mapv #(transform get-attr by-entity %
                                                              macro-registry eid-counter phase visited')
                                                  child-eids)]
                                [attr {:old child-eids
                                       :new (mapv first results)
                                       :datoms (vec (mapcat second results))
                                       :expanded? (boolean (some #(nth % 2) results))}])))
                          vec-ref-attrs))
              ;; Collect all new datoms produced by children
              all-child-datoms (vec (concat (mapcat :datoms (vals single-results))
                                            (mapcat :datoms (vals vec-results))))
              any-expanded? (or (some :expanded? (vals single-results))
                                (some :expanded? (vals vec-results)))]
          (if any-expanded?
            ;; At least one child changed: create a new version of this node with updated refs
            (let [new-eid (swap! eid-counter dec)
                  ;; Build substitution map: old-child-eid -> new-child-eid
                  subst (merge
                          (into {} (map (fn [[_attr {:keys [old new]}]] [old new]) single-results))
                          (into {} (mapcat (fn [[_attr {:keys [old new]}]] (map vector old new))
                                           vec-results)))
                  new-datoms (copy-node-datoms by-entity eid new-eid subst 0)]
              [new-eid (vec (concat all-child-datoms new-datoms)) true])
            ;; No children changed: return same EID, no new datoms
            [eid [] false]))))))


;; =============================================================================
;; Public API
;; =============================================================================

(defn expand-once
  "Run one pass of macro expansion over the datom stream.

   Finds all :yin/macro-expand nodes reachable from root-eid, calls the
   corresponding macro function from macro-registry, and produces:
   - New datoms: expansion event entity + macro output + updated ancestor chain
   - New root EID: may differ from root-eid if the root itself was expanded

   macro-registry: {macro-lambda-eid -> (fn [ctx] {:datoms [...] :root-eid eid})}
   where ctx is {:get-attr fn :by-entity map :arg-eids [eid...] :fresh-eid fn :phase kw}

   opts:
   - :phase - :compile (default) or :runtime

   Returns {:datoms updated-datoms :root-eid new-root :expanded? bool}."
  ([datoms root-eid macro-registry]
   (expand-once datoms root-eid macro-registry {}))
  ([datoms root-eid macro-registry opts]
   (let [{:keys [by-entity get-attr]} (vm/index-datoms datoms {:root-id root-eid})
         phase (or (:phase opts) :compile)
         eid-counter (make-eid-counter! datoms)
         [new-root new-datoms expanded?]
         (transform get-attr by-entity root-eid macro-registry eid-counter phase #{})]
     {:datoms (vec (concat datoms new-datoms))
      :root-eid new-root
      :expanded? expanded?})))


(defn expand-all
  "Fixpoint macro expansion with guard limits.

   Runs expand-once repeatedly until no new :yin/macro-expand nodes are found
   (fixpoint), or until a guard limit is hit (hard error).

   Guards:
   - max-depth (default 100): max expansion passes
   - max-datoms (default 10,000): max total datoms in stream per pass

   Returns {:datoms final-datoms :root-eid final-root}."
  ([datoms root-eid macro-registry]
   (expand-all datoms root-eid macro-registry {}))
  ([datoms root-eid macro-registry opts]
   (let [max-depth (or (:max-depth opts) default-max-depth)
         max-datoms (or (:max-datoms opts) default-max-datoms)]
     (loop [datoms (vec datoms)
            root-eid root-eid
            depth 0]
       (when (>= depth max-depth)
         (throw (ex-info "Macro expansion depth guard exceeded"
                         {:depth depth
                          :max-depth max-depth})))
       (when (> (count datoms) max-datoms)
         (throw (ex-info "Macro expansion datom count guard exceeded"
                         {:count (count datoms)
                          :max-datoms max-datoms})))
       (let [{:keys [datoms root-eid expanded?]}
             (expand-once datoms root-eid macro-registry opts)]
         (if expanded?
           (recur datoms root-eid (inc depth))
           {:datoms datoms :root-eid root-eid}))))))


(defn macro-call-node?
  "Returns true if the entity at eid is a :yin/macro-expand node."
  [get-attr eid]
  (= :yin/macro-expand (get-attr eid :yin/type)))

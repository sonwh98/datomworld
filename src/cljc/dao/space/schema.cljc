(ns dao.space.schema
  "Schema representation and extraction over the tuple space
   (docs/design/dao.space.schema.md).

   The schema lives in the medium it describes: schema rows are ordinary
   d5 tuples distinguished by the fixed :db/* attribute vocabulary. This
   namespace provides the axiom definitions (the single source of truth for
   the five interpreter properties), the bootstrap transaction data, the
   value-type predicates, the extraction function that reads schema from a
   d5 source through the public q surface, the resolve-props seam used
   by the view and wrapper, the validating write wrapper, and the
   publisher (publish!, published, :dao.space.schema/published opener)."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.transactor :as tx]
            [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


;; =============================================================================
;; Axioms: the five property idents and their fixed property maps
;; =============================================================================

(def axioms
  "The axiom property map: each of the five :db/* property idents maps to
   its fixed property map. This is the single source of truth for the
   interpreter axioms. All five are card-one; :db/ident is unique;
   :db/unique and :db/index are booleans. Rows describing :db/* attributes
   themselves resolve from this map regardless of what the stream says
   (resolve-props)."
  {:db/ident     {:db/valueType :db.type/keyword
                  :db/cardinality :db.cardinality/one
                  :db/unique true}
   :db/valueType  {:db/valueType :db.type/keyword
                   :db/cardinality :db.cardinality/one}
   :db/cardinality {:db/valueType :db.type/keyword
                    :db/cardinality :db.cardinality/one}
   :db/unique     {:db/valueType :db.type/boolean
                   :db/cardinality :db.cardinality/one}
   :db/index      {:db/valueType :db.type/boolean
                   :db/cardinality :db.cardinality/one}})


;; =============================================================================
;; Bootstrap: genesis tx-data from the axioms
;; =============================================================================

(defn- entity-id-for
  "Return the conventional genesis entity id for a :db/* property ident.
   16 :db/ident, 17 :db/valueType, 18 :db/cardinality, 19 :db/unique,
   20 :db/index."
  [ident]
  ({:db/ident datom/first-user-id
    :db/valueType 17
    :db/cardinality 18
    :db/unique 19
    :db/index 20}
   ident))


(defn bootstrap
  "Return tx-data: one flat vector of [:db/add e a v] triples for the
   bootstrap. Each entity gets its :db/ident row first, then property
   triples. Entity ids by convention: 16 :db/ident, 17 :db/valueType,
   18 :db/cardinality, 19 :db/unique, 20 :db/index."
  []
  (into []
        (mapcat (fn [[ident props]]
                  (let [eid (entity-id-for ident)]
                    (cons [:db/add eid :db/ident ident]
                          (map (fn [[a v]] [:db/add eid a v]) props)))))
        axioms))


;; =============================================================================
;; Type predicates: :db.type/* -> cross-platform value predicates
;; =============================================================================

(defn- non-integral?
  "True when x is a number but not an integer (e.g. a double). Used
   cross-platform to identify :db.type/double values."
  [x]
  (and (number? x) (not (integer? x))))


(def type-pred
  "Map from :db.type/* keywords to predicates that validate values
   identically on JVM Clojure and ClojureScript.

   :db.type/string  - string? (core predicate, identical on both hosts)
   :db.type/keyword - keyword? (core predicate, identical on both hosts)
   :db.type/boolean - boolean? (core predicate, identical on both hosts)
   :db.type/long    - int? (core predicate, identical on all hosts)
   :db.type/double  - number? but not integer? (cross-platform via non-integral?)
   :db.type/ref     - non-negative integer (entity id); int? and (not neg?)"
  {:db.type/string  string?
   :db.type/keyword keyword?
   :db.type/boolean boolean?
   :db.type/long    int?
   :db.type/double  non-integral?
   :db.type/ref     #(and (int? %) (not (neg? %)))})


;; =============================================================================
;; Extraction: read schema from d5 rows through the public q surface
;; =============================================================================

(defn- property?
  "True when ident is one of the five :db/* property idents."
  [ident]
  (contains? axioms ident))


(defn extract-schema
  "Pure function: extract the live schema from canonical d5 rows. Rows are
   [e a v t m] vectors, already as-of-bounded by the caller. Fetches
   through the public q surface over the history view, then applies the
   private fold: current-state semantics per [se p v], name entities by
   live :db/ident, collapse per [entity property] (greatest t, tie under
   index/compare-vals). Returns {ident property-map} of live,
   declared properties. Includes :db/* entities if present but does not
   synthesize them. Throws on unknown :db.type and on conflicting
   same-[se p v t]-different-m rows."
  [d5-rows]
  (let [source (query/relation d5-rows)
        view   (query/history source)
        ;; Fetch all property rows through the public q surface.
        fetched (query/collect
                  (query/q '[:find ?se ?p ?v ?t ?m
                             :where [?se ?p ?v ?t ?m] [(property? ?p)]]
                           view
                           {:fns {'property? property?}}))
        ;; Step 1: current-state semantics per [se p v].
        ;; Greatest t wins; same [se p v t] different m throws;
        ;; retractions removed.
        live (query/current-state-seq (mapv vec fetched))
        ;; current-state-seq has already removed the reserved retraction
        ;; marker. Keep every surviving fact, including one whose m names a
        ;; reified metadata entity rather than the reserved assert marker.
        facts live
        ;; Build entity -> ident map from live :db/ident facts.
        ident-rows (filterv #(= :db/ident (index/datom-a %)) facts)
        entity->ident
        (persistent!
          (reduce (fn [m row]
                    (let [e    (index/datom-e row)
                          prev (get m e)]
                      (cond (nil? prev) (assoc! m e row)
                            (< (index/datom-t prev) (index/datom-t row))
                            (assoc! m e row)
                            (> (index/datom-t prev) (index/datom-t row))
                            m
                            :else
                            (let [c (index/compare-vals
                                      (index/datom-v prev)
                                      (index/datom-v row))]
                              (if (neg? c) m (assoc! m e row))))))
                  (transient {})
                  ident-rows))
        ;; Step 2: collapse per [entity property] over surviving asserts.
        ;; Entities without a live :db/ident are ignored.
        valid-es (set (keys entity->ident))
        prop-asserts (filterv #(and (not= :db/ident (index/datom-a %))
                                    (contains? valid-es (index/datom-e %)))
                              facts)
        ;; Greatest t wins; on a same-t tie, least v under compare-vals.
        ;; m is a metadata entity reference, never an ordering coordinate.
        by-ep (persistent!
                (reduce (fn [m row]
                          (let [k [(index/datom-e row) (index/datom-a row)]]
                            (assoc! m k
                                    (let [prev (get m k)]
                                      (cond (nil? prev) row
                                            (< (index/datom-t prev)
                                               (index/datom-t row)) row
                                            (> (index/datom-t prev)
                                               (index/datom-t row)) prev
                                            :else
                                            (let [c (index/compare-vals
                                                      (index/datom-v prev)
                                                      (index/datom-v row))]
                                              (if (neg? c) prev row)))))))
                        (transient {})
                        prop-asserts))
        ;; Build schema: {ident {prop val, ...}}
        schema
        (reduce-kv
          (fn [m _eid-ea row]
            (let [e     (index/datom-e row)
                  ident (-> (entity->ident e) index/datom-v)]
              (when ident
                (let [prop (index/datom-a row)
                      val  (index/datom-v row)]
                  (when (= prop :db/valueType)
                    (when-not (contains? type-pred val)
                      (throw
                        (ex-info
                          (str "unknown :db.type: " (pr-str val))
                          {:db/type val, :entity e}))))
                  (assoc m ident
                         (assoc (get m ident {}) prop val))))))
          {}
          by-ep)]
    schema))


;; =============================================================================
;; Resolve: the seam the view and wrapper both use
;; =============================================================================

(defn resolve-props
  "Resolve the property map for ident. If ident is one of the five :db/*
   idents, returns (get axioms ident) regardless of what schema says
   (axioms are authority; stream rows are provenance). Otherwise
   (get schema ident)."
  [schema ident]
  (if (contains? axioms ident)
    (get axioms ident)
    (get schema ident)))


;; =============================================================================
;; The read view: schema/current (extraction + card-one collapse)
;; =============================================================================

(defn- validate-not-nested-view!
  "Reject descriptors whose :dao.stream/type is a query-layer view or
   another schema view — these are not open!-dispatchable d5 sources."
  [d]
  (let [t (:dao.stream/type d)]
    (when (or (= t :dao.space/current)
              (= t :dao.space/history)
              (= t :dao.space.schema/current))
      (throw (ex-info
               (str "schema/current source must be an open!-dispatchable d5 "
                    "descriptor, not a nested view: " (pr-str t))
               {:dao.stream/type t})))))


#_{:clj-kondo/ignore [:unresolved-var]}


;; kondo cannot resolve dao.stream vars defined behind :clj reader
;; conditionals; query.cljc/transactor.cljc carry these unsuppressed.
(defn- interpret-view
  "Shared interpretation core for the schema/current view. Given an
   already-opened (and closed) source realization plus as-of /
   schema-as-of bounds, returns a closed ViewStream of d3 facts with
   card-one collapse applied. Composition of public interpretations:
   history view for data, history view for schema, extract-schema,
   current-state-seq, then card-one collapse per [e a]."
  [source as-of schema-as-of]
  (let [data-rows   (ds/strict-vec (query/history source as-of))
        schema-rows (ds/strict-vec (query/history source
                                                  (or schema-as-of as-of)))
        schema (extract-schema schema-rows)
        ;; Seed with axiom idents: the five :db/* attrs are card-one by
        ;; axiom, with or without bootstrap/ident rows present.
        card-one-es (into (set (keys axioms))
                          (keep (fn [[ident _pm]]
                                  (when (= :db.cardinality/one
                                           (get (resolve-props schema ident)
                                                :db/cardinality))
                                    ident)))
                          schema)
        surviving (query/current-state-seq data-rows)
        {card-one-rows true,
         pass-through  false}
        (group-by #(contains? card-one-es (index/datom-a %)) surviving)
        collapsed
        (vals
          (reduce
            (fn [m row]
              (let [k [(index/datom-e row) (index/datom-a row)]]
                (assoc m k
                       (let [prev (get m k)]
                         (cond (nil? prev) row
                               (< (index/datom-t prev)
                                  (index/datom-t row)) row
                               (> (index/datom-t prev)
                                  (index/datom-t row)) prev
                               :else
                               (let [c (index/compare-vals
                                         (index/datom-v prev)
                                         (index/datom-v row))]
                                 (if (neg? c) prev row)))))))
            {}
            (or card-one-rows [])))
        all-rows (sort index/eavt-cmp
                       (into (vec collapsed) pass-through))
        d3-rows (mapv #(subvec (vec %) 0 3) all-rows)]
    (query/->ViewStream d3-rows true)))


#_{:clj-kondo/ignore [:unresolved-var]}


(defn current
  "The schema-aware current view. Given a descriptor, returns a pure
   semantic view value `{:dao.stream/type :dao.space.schema/current ...}`
   interpreted by q; given an already-opened closed realization, returns
   a read-only, closed, derived ViewStream of d3 facts with card-one
   collapse applied. Opts map: {:as-of n :schema-as-of n}, both optional.
   Dual-path: descriptor returns view value, realization interprets directly."
  ([source] (current source nil))
  ([source opts]
   (let [as-of       (when (map? opts) (:as-of opts))
         schema-as-of (when (map? opts) (:schema-as-of opts))]
     (if (ds/realization? source)
       (do (when-not (satisfies? ds/IDaoStreamBound source)
             (throw (ex-info
                      "borrowed input must satisfy IDaoStreamBound"
                      {:source source})))
           (when-not (ds/closed? source)
             (throw (ex-info
                      "borrowed input must be closed"
                      {:source source})))
           (interpret-view source as-of schema-as-of))
       (let [d (when (map? source) source)]
         (when-not d
           (throw (ex-info
                    "source must be a descriptor or a closed realization"
                    {:source source})))
         (when-not (keyword? (:dao.stream/type d))
           (throw (ex-info
                    "descriptor must carry :dao.stream/type"
                    {:source source})))
         (validate-not-nested-view! d)
         (cond-> {:dao.stream/type :dao.space.schema/current
                  :source d
                  :dao.stream/bound (:dao.stream/bound d)}
           (some? as-of)        (assoc :as-of as-of)
           (some? schema-as-of) (assoc :schema-as-of schema-as-of)))))))


#_{:clj-kondo/ignore [:unresolved-symbol :unresolved-var]}


(ds/defopen :dao.space.schema/current
            [desc]
            (let [src (:source desc)]
              (when-not (map? src)
                (throw (ex-info "schema/current descriptor must carry :source"
                                {:descriptor desc})))
              (let [r (ds/open! src)]
                (try (when-not (satisfies? ds/IDaoStreamReader r)
                       (throw (ex-info "open! did not produce a reader realization"
                                       {:descriptor desc})))
                     (ds/close! r)
                     (interpret-view r (:as-of desc) (:schema-as-of desc))
                     (catch #?(:clj Throwable
                               :cljs :default
                               :cljd Object)
                            error
                       (ds/close! r)
                       (throw error))))))


;; =============================================================================
;; The write boundary: validating wrapper (wave 3a)
;; =============================================================================

(defn- resolve-lookup-ref
  "Resolve a lookup ref [:attr value] against the planned unique state.
   A unique index retains every claimant so lax violations remain explicit.
   Returns the sole entity id, nil when absent, and throws when ambiguous."
  [ref state]
  (when (and (vector? ref) (= 2 (count ref)))
    (let [[attr val] ref
          owners (get-in (:unique state) [attr val] #{})]
      (case (count owners)
        0 nil
        1 (first owners)
        (throw (ex-info (str "ambiguous lookup ref: " (pr-str ref)
                             " names entities " (pr-str owners))
                        {:lookup-ref ref, :entities owners}))))))


(defn- unique-attr?
  [schema attr]
  (let [props (resolve-props schema attr)]
    (and (:db/unique props)
         (= :db.cardinality/one (:db/cardinality props)))))


(defn- add-unique-owner
  [unique attr value e]
  (update-in unique [attr value] (fnil conj #{}) e))


(defn- remove-unique-owner
  [unique attr value e]
  (if-not (contains? (get unique attr {}) value)
    unique
    (let [owners (disj (get-in unique [attr value]) e)]
      (cond
        (seq owners) (assoc-in unique [attr value] owners)
        (= 1 (count (get unique attr))) (dissoc unique attr)
        :else (update unique attr dissoc value)))))


(defn- resolve-ref-value
  "Resolve a ref value if the attr is :db.type/ref. Lookup refs resolve
   against the planned unique state."
  [v attr schema state]
  (let [resolved-props (resolve-props schema attr)]
    (if (and (= :db.type/ref (:db/valueType resolved-props))
             (vector? v)
             (= 2 (count v)))
      (or (resolve-lookup-ref v state)
          (throw (ex-info (str "unmatched lookup ref in ref position: "
                               (pr-str v))
                          {:lookup-ref v, :attr attr})))
      v)))


(defn- emit-retract
  "Emit a retraction datom [e a v nil 0] and update state."
  [e a v datoms state]
  (let [k [e a]
        vs (get-in state [:values k])
        present? (contains? (or vs #{}) v)
        vs' (disj vs v)
        values (if (empty? vs')
                 (dissoc (:values state) k)
                 (assoc (:values state) k vs'))
        fact-count (get-in state [:entity-fact-count e] 0)
        fact-count' (if present? (dec fact-count) fact-count)
        entity-fact-count (cond
                            (not present?) (:entity-fact-count state)
                            (pos? fact-count')
                            (assoc (:entity-fact-count state) e fact-count')
                            :else
                            (dissoc (:entity-fact-count state) e))]
    (assoc state
           :datoms (conj! datoms
                          [e a v nil (:db/retract datom/reserved)])
           :values values
           :entity-fact-count entity-fact-count
           :entities (if (and present? (zero? fact-count'))
                       (disj (:entities state) e)
                       (:entities state))
           :unique (remove-unique-owner (:unique state) a v e))))


(defn- emit-assert
  "Emit an assertion datom [e a v] and update state. A unique value already
   held by a different entity — live in the index or asserted earlier in
   this record — is a strict-mode rejection (§3.1: the batch is validated
   against the index and against itself); lax appends it and the §6 audit
   measures it."
  [e a v datoms state]
  (let [k [e a]
        new-fact? (not (contains? (get-in state [:values k] #{}) v))
        resolved-props (resolve-props (:schema state) a)
        is-unique (and (:db/unique resolved-props)
                       (= :db.cardinality/one
                          (:db/cardinality resolved-props)))
        conflicting-es (when is-unique
                         (disj (get-in (:unique state) [a v] #{}) e))]
    ;; :db/ident duplication is a schema-structure violation in both modes;
    ;; validate-schema-row! owns that diagnostic below this translation step.
    (when (and (seq conflicting-es) (:strict? state) (not= :db/ident a))
      (throw
        (ex-info
          (str "unique duplicate for " (pr-str a) ": " (pr-str v)
               " already held by entities " (pr-str conflicting-es))
          {:attr a, :value v, :entity e, :conflicts conflicting-es})))
    (assoc state
           :datoms (conj! datoms [e a v])
           :values (update (:values state) k (fnil conj #{}) v)
           :entity-fact-count (if new-fact?
                                (update (:entity-fact-count state) e
                                        (fnil inc 0))
                                (:entity-fact-count state))
           :entities (conj (:entities state) e)
           :unique (if is-unique
                     (add-unique-owner (:unique state) a v e)
                     (:unique state)))))


(defn- validate-ref!
  "Validate assertion-time ref existence in strict mode. The target must be
   live before this item or denoted by an assertion-emitting entity map in
   this record."
  [v e a state strict?]
  (when strict?
    (when (and (integer? v) (not (neg? v)))
      (when-not (contains? (:entities state) v)
        (throw (ex-info (str "dangling ref: " (pr-str v)
                             " not found for " (pr-str a))
                        {:ref v, :entity e, :attr a}))))))


(defn- validate-type!
  "Validate valueType in strict mode. Skips when undeclared."
  [v attr schema _state strict?]
  (when strict?
    (let [resolved-props (resolve-props schema attr)
          vt (:db/valueType resolved-props)]
      (when vt
        (let [pred (get type-pred vt)]
          (when (and pred (not (pred v)))
            (throw (ex-info (str "valueType mismatch for " (pr-str attr)
                                 ": expected " (pr-str vt)
                                 ", got " (pr-str v))
                            {:attr attr, :value v, :expected vt}))))))))


(declare schema-row?)


(defn- translate-entity
  "Translate one entity map into datoms. Returns {:datoms [...] :state s'}."
  [entity state schema strict?]
  (when-not (contains? entity :db/id)
    (throw (ex-info "entity map requires :db/id" {:entity entity})))
  (let [raw-e (:db/id entity)
        e (if (and (vector? raw-e) (= 2 (count raw-e)))
            (or (resolve-lookup-ref raw-e state)
                (throw (ex-info (str "unmatched lookup ref: " (pr-str raw-e))
                                {:lookup-ref raw-e})))
            raw-e)
        entity-attrs (dissoc entity :db/id)
        emits-assertion?
        (some
          (fn [[attr val]]
            (let [card (:db/cardinality (resolve-props schema attr))
                  lookup-ref? (and (vector? val)
                                   (= 2 (count val))
                                   (keyword? (first val))
                                   (unique-attr? schema (first val)))]
              (or (= :db.cardinality/one card)
                  lookup-ref?
                  (not (and (coll? val) (empty? val))))))
          entity-attrs)]
    (loop [attrs entity-attrs
           datoms (transient [])
           ;; Pre-mark only a map that will emit an assertion. This preserves
           ;; self-refs without granting existence to an empty collection.
           st (if emits-assertion? (update state :entities conj e) state)]
      (if (empty? attrs)
        {:datoms (persistent! datoms)
         :state st}
        (let [[attr val] (first attrs)
              resolved-props (resolve-props schema attr)
              card (:db/cardinality resolved-props)
              live (get-in st [:values [e attr]])]
          (cond
            (= card :db.cardinality/one)
            (let [v (resolve-ref-value val attr schema st)]
              (when-not (schema-row? attr)
                (validate-type! v attr schema st strict?)
                (validate-ref! v e attr st strict?))
              ;; Desired-state repair: retract every other live value before
              ;; asserting v. This restores raw-current coherence even after a
              ;; lax writer left several card-one values live.
              (let [[repair-datoms repair-state]
                    (reduce
                      (fn [[ds s] v-old]
                        (let [after (emit-retract e attr v-old ds s)]
                          [(:datoms after) after]))
                      [datoms st]
                      (sort index/compare-vals (disj (or live #{}) v)))
                    after (emit-assert e attr v repair-datoms repair-state)]
                (recur (rest attrs)
                       (:datoms after)
                       after)))

            ;; card-many or undeclared
            :else
            (let [ref-type? (= :db.type/ref (:db/valueType resolved-props))
                  ;; For :db.type/ref, a 2-vector whose first element is a
                  ;; keyword naming a unique attribute is a lookup ref
                  ;; (scalar), not a collection. [5 6] → two entity ids;
                  ;; [:person/email "x@y"] → one lookup ref.
                  lookup-ref? (and ref-type?
                                   (vector? val)
                                   (= 2 (count val))
                                   (keyword? (first val))
                                   (unique-attr? schema (first val)))
                  vs (if (and (coll? val) (not lookup-ref?))
                       (vec val)
                       [val])
                  result
                  (reduce
                    (fn [acc v-raw]
                      (let [v (resolve-ref-value v-raw attr schema
                                                 (:state acc))]
                        (when-not (schema-row? attr)
                          (validate-type! v attr schema (:state acc) strict?)
                          (validate-ref! v e attr (:state acc) strict?))
                        (let [after (emit-assert e attr v (:d acc)
                                                 (:state acc))]
                          {:d (:datoms after)
                           :state after})))
                    {:d datoms :state st}
                    vs)]
              (recur (rest attrs)
                     (:d result)
                     (:state result)))))))))


(defn- schema-row?
  "True when a is one of the five :db/* property idents."
  [a]
  (contains? axioms a))


(defn- db-star-attr?
  "True when a is a namespaced :db/* keyword (but not one of the five
   property names or the map/op syntax :db/id, :db/add, :db/retract)."
  [a]
  (and (keyword? a)
       (= "db" (namespace a))
       (not (schema-row? a))
       (not (#{:db/id :db/add :db/retract} a))))


(defn- validate-schema-row!
  "Validate a translated datom [e a v] against the both-modes schema-row
   rules. Throws on violations. Called per datom before emission.
   Axiom protection (rule 3) fires FIRST — it is both-modes and takes
   precedence over generic :db/* checks."
  [e a v state]
  ;; Rule 3: axiom protection (FIRST — both-modes, takes precedence)
  ;; Only applies when entity already has a live ident that is an axiom.
  (when (and (not= a :db/ident) (get (:entity->ident state) e))
    (let [live-ident-for-e (get (:entity->ident state) e)]
      (when (contains? axioms live-ident-for-e)
        (let [axiom-val (get-in axioms [live-ident-for-e a])]
          (when (nil? axiom-val)
            (throw (ex-info (str "axiom protection: cannot add property "
                                 (pr-str a) " to axiom entity "
                                 (pr-str live-ident-for-e))
                            {:entity e, :attr a,
                             :ident live-ident-for-e})))
          (when (not= axiom-val v)
            (throw (ex-info (str "axiom protection: " (pr-str a) " on "
                                 (pr-str live-ident-for-e) " must be "
                                 (pr-str axiom-val) ", got " (pr-str v))
                            {:entity e, :attr a, :expected axiom-val,
                             :actual v, :ident live-ident-for-e})))))))
  ;; Unknown :db/* name (not one of five, not op syntax) → reject both modes
  (when (db-star-attr? a)
    (throw (ex-info (str "unknown :db/* attribute name: " (pr-str a))
                    {:attr a, :entity e})))
  ;; Schema-row rules (a is one of the five property names)
  (when (schema-row? a)
    ;; Rule 1: value legality
    (case a
      :db/valueType
      (when-not (contains? type-pred v)
        (throw (ex-info (str "illegal :db/valueType: " (pr-str v))
                        {:value v})))
      :db/cardinality
      (when-not (#{:db.cardinality/one :db.cardinality/many} v)
        (throw (ex-info (str "illegal :db/cardinality: " (pr-str v))
                        {:value v})))
      :db/ident
      (when-not (and (keyword? v) (some? (namespace v)))
        (throw (ex-info (str "illegal :db/ident: must be a namespaced keyword, got "
                             (pr-str v))
                        {:value v})))
      :db/unique
      (when-not (boolean? v)
        (throw (ex-info (str "illegal :db/unique: must be boolean, got "
                             (pr-str v))
                        {:value v})))
      :db/index
      (when-not (boolean? v)
        (throw (ex-info (str "illegal :db/index: must be boolean, got "
                             (pr-str v))
                        {:value v})))
      nil)
      ;; Rule 2: :db/ident uniqueness
    (when (= a :db/ident)
      (let [existing-es (disj (get-in (:unique state) [:db/ident v] #{}) e)]
        (when (seq existing-es)
          (throw (ex-info (str "duplicate :db/ident " (pr-str v)
                               ": already owned by entities " (pr-str existing-es))
                          {:ident v, :existing-entities existing-es,
                           :new-entity e})))))))


(defn- validate-unique-card-one!
  "Pre-emission schema-structure check: every attribute whose proposed
   schema declares :db/unique true must also declare card-one."
  [schema]
  (doseq [[attr props] schema
          :when (:db/unique props)]
    (when-not (= :db.cardinality/one (:db/cardinality props))
      (throw (ex-info (str ":db/unique requires :db.cardinality/one, but "
                           (pr-str attr) " is "
                           (pr-str (:db/cardinality props)))
                      {:attr attr})))))


(defn- translate-record
  "Translate one record (entity map or datom vector) into flat datom vectors.
   Returns {:datoms [...] :state s'}."
  [item state schema strict?]
  (cond
    (map? item)
    (translate-entity item state schema strict?)

    ;; [:db/add e a v] or bare [e a v] shorthand
    (and (vector? item)
         (or (= :db/add (first item))
             (and (= 3 (count item)) (not (keyword? (first item))))))
    (let [[e-raw a v] (if (= :db/add (first item)) (rest item) item)
          e (if (and (vector? e-raw) (= 2 (count e-raw)))
              (or (resolve-lookup-ref e-raw state)
                  (throw (ex-info (str "unmatched lookup ref: "
                                       (pr-str e-raw))
                                  {:lookup-ref e-raw})))
              e-raw)
          resolved-v (resolve-ref-value v a schema state)]
      ;; Axiom identity is itself protected. This must precede strict
      ;; cardinality diagnostics so the both-mode structural rule wins.
      (when (= a :db/ident)
        (let [live-ident-for-e (get (:entity->ident state) e)]
          (when (and (contains? axioms live-ident-for-e)
                     (not= live-ident-for-e resolved-v))
            (throw (ex-info (str "axiom protection: cannot rename "
                                 (pr-str live-ident-for-e) " to "
                                 (pr-str resolved-v))
                            {:entity e, :ident live-ident-for-e,
                             :actual resolved-v})))))
      ;; Skip data-level type validation for schema rows — the both-modes
      ;; validate-schema-row! handles them with proper error messages.
      (when-not (schema-row? a)
        (validate-type! resolved-v a schema state strict?)
        (validate-ref! resolved-v e a state strict?))
      ;; BOTH-MODES: axiom protection on [:db/add] (takes precedence over
      ;; card-one collision which is strict-only)
      (when (and (not= a :db/ident) (not (#{:db/id :db/add :db/retract} a)))
        (let [live-ident-for-e (get (:entity->ident state) e)]
          (when (and live-ident-for-e (contains? axioms live-ident-for-e))
            (let [axiom-val (get-in axioms [live-ident-for-e a])]
              (when (nil? axiom-val)
                (throw (ex-info (str "axiom protection: cannot add property "
                                     (pr-str a) " to axiom entity "
                                     (pr-str live-ident-for-e))
                                {:entity e, :attr a,
                                 :ident live-ident-for-e})))
              (when (not= axiom-val resolved-v)
                (throw (ex-info (str "axiom protection: " (pr-str a) " on "
                                     (pr-str live-ident-for-e) " must be "
                                     (pr-str axiom-val) ", got "
                                     (pr-str resolved-v))
                                {:entity e, :attr a, :expected axiom-val,
                                 :actual resolved-v,
                                 :ident live-ident-for-e})))))))
      ;; STRICT: [:db/add] on card-one with different live value → reject
      (when strict?
        (let [resolved-props (resolve-props schema a)
              live (get-in state [:values [e a]])]
          (when (and (= :db.cardinality/one
                        (:db/cardinality resolved-props))
                     live
                     (not (contains? live resolved-v)))
            (throw (ex-info (str "card-one collision on " (pr-str a)
                                 ": live value " (pr-str (first live))
                                 " differs. Use retract first or map form.")
                            {:entity e, :attr a,
                             :live (first live), :new resolved-v})))))
      (let [after (emit-assert e a resolved-v (transient []) state)]
        {:datoms (persistent! (:datoms after))
         :state after}))

    (and (vector? item)
         (= :db/retract (first item))
         (= 4 (count item)))
    (let [[_ e-raw a v] item
          e (if (and (vector? e-raw) (= 2 (count e-raw)))
              (or (resolve-lookup-ref e-raw state)
                  (throw (ex-info (str "unmatched lookup ref: "
                                       (pr-str e-raw))
                                  {:lookup-ref e-raw})))
              e-raw)]
      ;; Both-modes: axiom protection for retractions
      (let [live-ident-for-e (get (:entity->ident state) e)]
        (when (and live-ident-for-e (contains? axioms live-ident-for-e))
          (when (contains? axioms a)
            (throw (ex-info (str "axiom protection: cannot retract property "
                                 (pr-str a) " of axiom entity "
                                 (pr-str live-ident-for-e))
                            {:entity e, :attr a,
                             :ident live-ident-for-e})))))
      (let [after (emit-retract e a v (transient []) state)]
        {:datoms (persistent! (:datoms after))
         :state after}))

    (and (vector? item)
         (= :db/retract (first item))
         (= 3 (count item)))
    (let [[_ e-raw a] item
          e (if (and (vector? e-raw) (= 2 (count e-raw)))
              (or (resolve-lookup-ref e-raw state)
                  (throw (ex-info (str "unmatched lookup ref: "
                                       (pr-str e-raw))
                                  {:lookup-ref e-raw})))
              e-raw)]
      ;; Both-modes: axiom protection for retractions
      (let [live-ident-for-e (get (:entity->ident state) e)]
        (when (and live-ident-for-e (contains? axioms live-ident-for-e))
          (when (contains? axioms a)
            (throw (ex-info (str "axiom protection: cannot retract property "
                                 (pr-str a) " of axiom entity "
                                 (pr-str live-ident-for-e))
                            {:entity e, :attr a,
                             :ident live-ident-for-e})))))
      (let [vs (sort index/compare-vals (get-in state [:values [e a]]))
            [datoms next-state]
            (reduce
              (fn [[ds s] v]
                (let [after (emit-retract e a v ds s)]
                  [(:datoms after) after]))
              [(transient []) state]
              vs)]
        {:datoms (persistent! datoms)
         :state next-state}))

    :else
    (throw (ex-info "unrecognized tx-data item"
                    {:item item}))))


(defn- least-value
  [values]
  (first (sort index/compare-vals values)))


(defn- reindex-state
  "Derive every lookup projection from live values and one schema epoch.
   Unique values retain a set of all claimants so lax ambiguity is data."
  [state schema strict?]
  (let [values (:values state)
        entity-fact-count
        (reduce-kv
          (fn [counts [e _a] vs]
            (update counts e (fnil + 0) (count vs)))
          {}
          values)
        unique
        (reduce-kv
          (fn [idx [e a] vs]
            (if (unique-attr? schema a)
              (reduce (fn [m v] (add-unique-owner m a v e)) idx vs)
              idx))
          {}
          values)
        entity->ident
        (reduce-kv
          (fn [m [e a] vs]
            (if (and (= :db/ident a) (seq vs))
              (assoc m e (least-value vs))
              m))
          {}
          values)]
    {:values values
     :unique unique
     :entities (set (keys entity-fact-count))
     :entity-fact-count entity-fact-count
     :entity->ident entity->ident
     :schema schema
     :strict? strict?}))


(defn- rows->state
  [rows schema strict?]
  (let [live (query/current-state-seq rows)
        values (reduce
                 (fn [m d]
                   (update m
                           [(index/datom-e d) (index/datom-a d)]
                           (fnil conj #{})
                           (index/datom-v d)))
                 {}
                 live)]
    (reindex-state {:values values} schema strict?)))


(defn- next-transaction-time
  [rows]
  (if (seq rows)
    (inc (reduce max (map index/datom-t rows)))
    0))


(defn- stamp-candidate
  [t d]
  (let [[e a v _dt m] d]
    [e a v t (if (nil? m) datom/default-op m)]))


(declare contains-schema-row?)


(defn- proposed-schema
  "Interpret schema rows as the next transaction would, without emitting.
   The wrapper and its inner transactor are serialized together, so the
   predicted t is the inner transactor's next t."
  [local-stream schema datoms]
  (if-not (contains-schema-row? datoms)
    schema
    (let [rows (index/snapshot-datoms local-stream)
          t (next-transaction-time rows)]
      (extract-schema (into rows (map #(stamp-candidate t %) datoms))))))


#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-write-lock
  "Serialize one wrapper's validate -> append -> state transition. The state
   atom is a private per-wrapper coordination handle, never global state."
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(deftype SchemaWrapper
  [inner local-stream strict? state]

  ds/IDaoStreamBound

  (close!
    [_]
    (with-write-lock state #(ds/close! inner)))


  (closed?
    [_]
    (ds/closed? inner)))


(defn transactor
  "Open a schema-validating wrapper over a local stream and intake pool.
   opts: {:strict true} for strict mode (default lax). OWNS the inner
   :transactor handle; closing the wrapper delegates inward."
  ([local-stream intake-pool]
   (transactor local-stream intake-pool nil))
  ([local-stream intake-pool opts]
   (let [strict? (boolean (:strict opts))
         inner (ds/open! {:dao.stream/type :transactor
                          :local-stream local-stream
                          :intake-pool intake-pool
                          :name "schema"})
         rows (index/snapshot-datoms local-stream)
         schema (extract-schema rows)]
     (->SchemaWrapper inner local-stream strict?
                      (atom (rows->state rows schema strict?))))))


(defn- contains-schema-row?
  "True when any datom in the batch has an attribute that is one of the
   five :db/* property names."
  [datoms]
  (some (fn [d] (schema-row? (second d))) datoms))


(defn- validate-record-ops!
  "Reject item-order ambiguity between assertion and retraction for one EAV.
   Persisted current-state semantics treats same-t opposite operations as
   conflicting history, so such a record cannot cross the append boundary."
  [datoms]
  (reduce
    (fn [ops d]
      (let [eav (subvec (vec d) 0 3)
            op (if (= 5 (count d)) (nth d 4) datom/default-op)]
        (when (and (contains? ops eav) (not= (get ops eav) op))
          (throw (ex-info (str "opposite operations on one EAV in a single "
                               "transaction: " (pr-str eav))
                          {:eav eav, :operations #{(get ops eav) op}})))
        (assoc ops eav op)))
    {}
    datoms)
  nil)


(defn- translated-op
  [d]
  (if (= 5 (count d)) (nth d 4) datom/default-op))


(defn- validate-axiom-datoms!
  "Protect axiom entities using the complete translated record. Looking at
   the whole record makes the rule independent of item order: an axiom ident
   declared in this record protects every row on that entity in this record."
  [datoms state]
  (let [established
        (reduce-kv
          (fn [m [e a] values]
            (if (= :db/ident a)
              (reduce (fn [acc ident]
                        (if (contains? axioms ident)
                          (update acc e (fnil conj #{}) ident)
                          acc))
                      m
                      values)
              m))
          {}
          (:values state))
        axiom-idents
        (reduce
          (fn [m d]
            (let [[e a v] d]
              (if (and (= :db/ident a)
                       (= datom/default-op (translated-op d))
                       (contains? axioms v))
                (update m e (fnil conj #{}) v)
                m)))
          established
          datoms)]
    (doseq [[e idents] axiom-idents]
      (when (< 1 (count idents))
        (throw (ex-info (str "axiom protection: entity " (pr-str e)
                             " cannot name multiple axioms "
                             (pr-str idents))
                        {:entity e, :idents idents}))))
    (doseq [d datoms
            :let [[e a v] d
                  ident (first (get axiom-idents e))]
            :when ident]
      (let [expected (assoc (get axioms ident) :db/ident ident)]
        (when (or (not= datom/default-op (translated-op d))
                  (not (contains? expected a))
                  (not= (get expected a) v))
          (throw (ex-info (str "axiom protection: " (pr-str ident)
                               " permits only its verbatim axiom facts, got "
                               (pr-str [e a v]))
                          {:entity e, :ident ident, :attr a, :value v,
                           :expected (get expected a),
                           :operation (translated-op d)}))))))
  nil)


(defn transact!
  "Translate, validate, and commit tx-data as one atomic record. Returns
   {:result :ok :t t :datoms datoms}. One per-wrapper serialized transition
   plans and validates the complete next state before the single append, then
   installs that already-planned state only after append succeeds. Schema
   changes become effective for the next transaction."
  [^SchemaWrapper wrapper tx-data]
  (let [lock (.-state wrapper)]
    (with-write-lock
      lock
      (fn []
        (when (ds/closed? wrapper)
          (throw (ex-info "cannot transact! on closed wrapper" {})))
        (when (empty? tx-data)
          (throw (ex-info "transact! requires at least one item"
                          {:tx-data tx-data})))
        (let [st @lock
              schema (:schema st)
              strict? (.-strict? wrapper)
              translated
              (loop [items (vec tx-data)
                     datoms []
                     state st]
                (if (empty? items)
                  {:datoms datoms, :state state}
                  (let [item (first items)
                        result (translate-record item state schema strict?)]
                    ;; Both-mode schema structure validation is part of the
                    ;; plan, before any append can occur.
                    (doseq [d (:datoms result)]
                      (validate-schema-row! (nth d 0) (nth d 1) (nth d 2)
                                            (:state result)))
                    (recur (rest items)
                           (into datoms (:datoms result))
                           (:state result)))))
              datoms (:datoms translated)]
          (when (empty? datoms)
            (throw (ex-info "transact! produced no datoms"
                            {:tx-data tx-data})))
          (validate-record-ops! datoms)
          (validate-axiom-datoms! datoms st)
          (let [schema-change? (boolean (contains-schema-row? datoms))
                next-schema (proposed-schema (.-local-stream wrapper)
                                             schema
                                             datoms)
                _ (validate-unique-card-one! next-schema)
                next-state (if schema-change?
                             (reindex-state (:state translated)
                                            next-schema
                                            strict?)
                             (-> (:state translated)
                                 (dissoc :datoms)
                                 (assoc :schema next-schema
                                        :strict? strict?)))
                result (tx/transact! (.-inner wrapper) datoms)]
            (reset! lock next-state)
            result))))))


;; =============================================================================
;; Publisher: publish! and published descriptor (§5)
;; =============================================================================

(defn publish!
  "Build and enqueue covered indexes over the wrapper's local stream.
   Thin passthrough to the inner handle's dao.space.transactor/publish!.
   Returns {:manifest-address ... :manifest ...}."
  ([^SchemaWrapper wrapper] (publish! wrapper nil))
  ([^SchemaWrapper wrapper opts]
   (when (ds/closed? wrapper)
     (throw (ex-info "cannot publish! on closed wrapper" {})))
   (tx/publish! (.-inner wrapper) opts)))


(defn published
  "Construct a §5 published descriptor for one immutable covered-index
   manifest. content-store is a serializable DaoJing coordinate (map with
   :dao.jing/type); manifest-address is a segment content address.
   Validates like index/published-index but carries the schema type."
  [content-store manifest-address]
  (when-not (and (map? content-store) (keyword? (:dao.jing/type content-store)))
    (throw (ex-info "published requires a DaoJing store coordinate"
                    {:content-store content-store})))
  (when-not (jing/segment-address? manifest-address)
    (throw (ex-info "published requires a manifest content address"
                    {:manifest-address manifest-address})))
  {:dao.stream/type :dao.space.schema/published
   :dao.stream/bound {:manifest-address manifest-address}
   :dao.stream/comparator :dao.space.index/eavt
   :content-store content-store
   :manifest-address manifest-address})


#_{:clj-kondo/ignore [:unresolved-symbol :unresolved-var :private-call]}


(ds/defopen :dao.space.schema/published
            [descriptor]
            (let [{:keys [content-store manifest-address]} descriptor
                  expected (published content-store manifest-address)]
              (when-not (= expected descriptor)
                (throw (ex-info "invalid schema/published descriptor"
                                {:descriptor descriptor, :expected expected})))
              (let [r (ds/open! (index/published-index content-store manifest-address))]
                ;; The btree is lazily loaded from the store.  Eagerly force all
                ;; datoms now so the data is cached in memory.  When schema/current
                ;; later calls ds/close! (which closes the jing store) and then
                ;; interpret-view, the datoms delay is already realized and next()
                ;; reads from the cached vector — no store access needed.
                (ds/strict-vec r)
                r)))

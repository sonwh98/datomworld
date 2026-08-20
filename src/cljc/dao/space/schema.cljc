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
   live :db/ident, collapse per [entity property] (greatest (t, m), tie
   under index/compare-vals). Returns {ident property-map} of live,
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
        ;; Collect surviving assertions.
        asserts (filterv datom/asserted? live)
        ;; Build entity -> ident map from live :db/ident assertions.
        ident-rows (filterv #(= :db/ident (index/datom-a %)) asserts)
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
                            (< (index/datom-m prev) (index/datom-m row))
                            (assoc! m e row)
                            (> (index/datom-m prev) (index/datom-m row))
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
                              asserts)
        ;; Greatest (t, m) lexicographic wins; on full tie, least v
        ;; under compare-vals.
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
                                            (< (index/datom-m prev)
                                               (index/datom-m row)) row
                                            (> (index/datom-m prev)
                                               (index/datom-m row)) prev
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
        asserts (filterv datom/asserted? surviving)
        {card-one-rows true,
         pass-through  false}
        (group-by #(contains? card-one-es (index/datom-a %)) asserts)
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
                               (< (index/datom-m prev)
                                  (index/datom-m row)) row
                               (> (index/datom-m prev)
                                  (index/datom-m row)) prev
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
  "Resolve a lookup ref [:attr value] against unique + record state.
   Returns the entity id, or nil if not found."
  [ref state record-unique]
  (when (and (vector? ref) (= 2 (count ref)))
    (let [[attr val] ref]
      (or (get-in record-unique [attr val])
          (get-in (:unique state) [attr val])))))


(defn- resolve-ref-value
  "Resolve a ref value if the attr is :db.type/ref. Lookup refs resolve
   against unique + record state."
  [v attr schema state record-unique]
  (let [resolved-props (resolve-props schema attr)]
    (if (and (= :db.type/ref (:db/valueType resolved-props))
             (vector? v)
             (= 2 (count v)))
      (or (resolve-lookup-ref v state record-unique)
          (throw (ex-info (str "unmatched lookup ref in ref position: "
                               (pr-str v))
                          {:lookup-ref v, :attr attr})))
      v)))


(defn- emit-retract
  "Emit a retraction datom [e a v nil 0] and update state."
  [e a v datoms state]
  (let [k [e a]]
    (assoc state
           :datoms (conj! datoms [e a v nil 0])
           :values (let [vs (get-in state [:values k])
                         vs' (disj vs v)]
                     (if (empty? vs')
                       (dissoc (:values state) k)
                       (assoc (:values state) k vs')))
           :unique (if-let [attr-uv (get-in state [:unique a])]
                     (let [attr-uv' (dissoc attr-uv v)]
                       (if (empty? attr-uv')
                         (dissoc (:unique state) a)
                         (assoc (:unique state) a attr-uv')))
                     (:unique state)))))


(defn- emit-assert
  "Emit an assertion datom [e a v] and update state. A unique value already
   held by a different entity — live in the index or asserted earlier in
   this record — is a strict-mode rejection (§3.1: the batch is validated
   against the index and against itself); lax appends it and the §6 audit
   measures it."
  [e a v datoms state record-unique record-es]
  (let [k [e a]
        resolved-props (resolve-props (:schema state) a)
        is-unique (and (:db/unique resolved-props)
                       (= :db.cardinality/one
                          (:db/cardinality resolved-props)))
        conflicting-e (when is-unique
                        (let [live-e (get-in (:unique state) [a v])
                              rec-e (get record-unique [a v])]
                          (cond (and live-e (not= live-e e)) live-e
                                (and rec-e (not= rec-e e)) rec-e)))]
    (when (and conflicting-e (:strict? state))
      (throw
        (ex-info
          (str "unique duplicate for " (pr-str a) ": " (pr-str v)
               " already held by entity " conflicting-e)
          {:attr a, :value v, :entity e, :conflict conflicting-e})))
    (assoc state
           :datoms (conj! datoms [e a v])
           :values (update (:values state) k (fnil conj #{}) v)
           :unique (if is-unique
                     (assoc-in (:unique state) [a v] e)
                     (:unique state))
           :record-unique (if is-unique
                            (assoc-in record-unique [a v] e)
                            record-unique)
           :record-es (conj record-es e))))


(defn- validate-ref!
  "Validate ref existence in strict mode. Target must be in entities or
   record-es."
  [v e a state record-es strict?]
  (when strict?
    (when (and (integer? v) (not (neg? v)))
      (when-not (or (contains? (:entities state) v)
                    (contains? record-es v))
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
  "Translate one entity map into datoms. Returns {:datoms [...] :state s'
   :record-unique ru' :record-es re'}."
  [entity state schema strict? record-unique record-es]
  (when-not (contains? entity :db/id)
    (throw (ex-info "entity map requires :db/id" {:entity entity})))
  (let [raw-e (:db/id entity)
        e (if (and (vector? raw-e) (= 2 (count raw-e)))
            (or (resolve-lookup-ref raw-e state record-unique)
                (throw (ex-info (str "unmatched lookup ref: " (pr-str raw-e))
                                {:lookup-ref raw-e})))
            raw-e)]
    (loop [attrs (dissoc entity :db/id)
           datoms (transient [])
           st state
           ru record-unique
           re record-es]
      (if (empty? attrs)
        {:datoms (persistent! datoms)
         :state st
         :record-unique ru
         :record-es (conj re e)}
        (let [[attr val] (first attrs)
              resolved-props (resolve-props schema attr)
              card (:db/cardinality resolved-props)
              live (get-in st [:values [e attr]])]
          (cond
            (= card :db.cardinality/one)
            (let [v (resolve-ref-value val attr schema st ru)]
              (when-not (schema-row? attr)
                (validate-type! v attr schema st strict?)
                (validate-ref! v e attr st re strict?))
              (if (and live (not= (first live) v))
                ;; supersession: retract old + assert new
                (let [v-old (first live)
                      _ (when (and (= :db.type/ref
                                      (:db/valueType resolved-props))
                                   strict?)
                          (validate-ref! v-old e attr st re strict?))
                      after-retract (emit-retract e attr v-old datoms st)
                      after-assert (emit-assert e attr v
                                                (:datoms after-retract)
                                                after-retract ru re)]
                  (recur (rest attrs)
                         (:datoms after-assert)
                         after-assert
                         (:record-unique after-assert)
                         (:record-es after-assert)))
                ;; assert only
                (let [after (emit-assert e attr v datoms st ru re)]
                  (recur (rest attrs)
                         (:datoms after)
                         after
                         (:record-unique after)
                         (:record-es after)))))

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
                                   (some? (get-in (:unique st) [(first val)])))
                  vs (if (and (coll? val) (not lookup-ref?))
                       (vec val)
                       [val])
                  result
                  (reduce
                    (fn [acc v-raw]
                      (let [v (resolve-ref-value v-raw attr schema
                                                 (:state acc) (:ru acc))]
                        (when-not (schema-row? attr)
                          (validate-type! v attr schema (:state acc) strict?)
                          (validate-ref! v e attr (:state acc) (:er acc) strict?))
                        (let [after (emit-assert e attr v (:d acc)
                                                 (:state acc) (:ru acc) (:er acc))]
                          {:d (:datoms after)
                           :state after
                           :ru (:record-unique after)
                           :er (:record-es after)})))
                    {:d datoms :state st :ru ru :er re}
                    vs)]
              (recur (rest attrs)
                     (:d result)
                     (:state result)
                     (:ru result)
                     (:er result)))))))))


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
  [e a v state _schema record-unique _record-es]
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
      (let [existing-e (get-in (:unique state) [:db/ident v])]
        (when (and existing-e (not= existing-e e))
          (throw (ex-info (str "duplicate :db/ident " (pr-str v)
                               ": already owned by entity " existing-e)
                          {:ident v, :existing-entity existing-e,
                           :new-entity e}))))
      (let [record-owner (get-in record-unique [:db/ident v])]
        (when (and record-owner (not= record-owner e))
          (throw (ex-info (str "duplicate :db/ident " (pr-str v)
                               " within record: entities " record-owner
                               " and " e)
                          {:ident v, :entities [record-owner e]})))))))


(defn- validate-unique-card-one!
  "Post-record check: every attr declared :db/unique must be card-one.
   Checks both live schema, record declarations, and the translated datoms
   for :db/unique assertions."
  [state schema record-unique datoms]
  ;; Collect all attrs that have :db/unique declared (live + record + datoms)
  (let [;; From live unique index
        live-unique-attrs (set (keys (:unique state)))
        ;; From record-unique
        record-unique-attrs (set (keys record-unique))
        ;; From datoms: any [:db/add e :db/unique true] declares e's ident as unique
        datom-unique-attrs
        (into #{}
              (keep (fn [d]
                      (when (and (= :db/unique (nth d 1))
                                 (= true (nth d 2)))
                        (let [e (nth d 0)
                              ident (or (get (:entity->ident state) e)
                                        (some (fn [[v ent]]
                                                (when (= ent e) v))
                                              (get record-unique :db/ident)))]
                          (when ident ident)))))
              datoms)
        all-unique-attrs (into live-unique-attrs
                               (concat record-unique-attrs datom-unique-attrs))]
    (doseq [attr all-unique-attrs]
      (when-not (contains? axioms attr)
        (let [rp (resolve-props schema attr)]
          (when-not (= :db.cardinality/one (:db/cardinality rp))
            (throw (ex-info (str ":db/unique requires :db.cardinality/one, but "
                                 (pr-str attr) " is " (pr-str (:db/cardinality rp)))
                            {:attr attr}))))))))


(defn- translate-record
  "Translate one record (entity map or datom vector) into flat datom vectors.
   Returns {:datoms [...] :state s' :record-unique ru' :record-es re'}."
  [item state schema strict? record-unique record-es]
  (cond
    (map? item)
    (translate-entity item state schema strict? record-unique record-es)

    ;; [:db/add e a v] or bare [e a v] shorthand
    (and (vector? item)
         (or (= :db/add (first item))
             (and (= 3 (count item)) (not (keyword? (first item))))))
    (let [[e-raw a v] (if (= :db/add (first item)) (rest item) item)
          e (if (and (vector? e-raw) (= 2 (count e-raw)))
              (or (resolve-lookup-ref e-raw state record-unique)
                  (throw (ex-info (str "unmatched lookup ref: "
                                       (pr-str e-raw))
                                  {:lookup-ref e-raw})))
              e-raw)
          resolved-v (resolve-ref-value v a schema state record-unique)]
      ;; Skip data-level type validation for schema rows — the both-modes
      ;; validate-schema-row! handles them with proper error messages.
      (when-not (schema-row? a)
        (validate-type! resolved-v a schema state strict?)
        (validate-ref! resolved-v e a state record-es strict?))
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
      {:datoms [[e a resolved-v]]
       :state state
       :record-unique (let [rp (resolve-props schema a)]
                        (if (and (:db/unique rp)
                                 (= :db.cardinality/one (:db/cardinality rp)))
                          (assoc-in record-unique [a resolved-v] e)
                          record-unique))
       :record-es (conj record-es e)})

    (and (vector? item)
         (= :db/retract (first item))
         (= 4 (count item)))
    (let [[_ e-raw a v] item
          e (if (and (vector? e-raw) (= 2 (count e-raw)))
              (or (resolve-lookup-ref e-raw state record-unique)
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
      {:datoms [[e a v nil 0]]
       :state (let [k [e a]
                    vs (get-in state [:values k])
                    vs' (disj vs v)]
                (assoc state :values
                       (if (empty? vs')
                         (dissoc (:values state) k)
                         (assoc (:values state) k vs'))))
       :record-unique record-unique
       :record-es record-es})

    (and (vector? item)
         (= :db/retract (first item))
         (= 3 (count item)))
    (let [[_ e-raw a] item
          e (if (and (vector? e-raw) (= 2 (count e-raw)))
              (or (resolve-lookup-ref e-raw state record-unique)
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
      (let [vs (get-in state [:values [e a]])
            retract-datoms (mapv (fn [v] [e a v nil 0]) vs)]
        {:datoms retract-datoms
         :state (assoc state :values (dissoc (:values state) [e a]))
         :record-unique record-unique
         :record-es record-es}))

    :else
    (throw (ex-info "unrecognized tx-data item"
                    {:item item}))))


(deftype SchemaWrapper
  [inner local-stream strict? schema state]

  ds/IDaoStreamBound

  (close!
    [_]
    (ds/close! inner))


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
         schema (extract-schema rows)
         cs (query/current-state-seq rows)
         asserts (filterv datom/asserted? cs)
         values (reduce
                  (fn [m d]
                    (let [k [(index/datom-e d) (index/datom-a d)]]
                      (update m k (fnil conj #{}) (index/datom-v d))))
                  {}
                  asserts)
         unique (reduce
                  (fn [m d]
                    (let [a (index/datom-a d)
                          rp (resolve-props schema a)]
                      (if (and (:db/unique rp)
                               (= :db.cardinality/one
                                  (:db/cardinality rp)))
                        (assoc-in m [a (index/datom-v d)]
                                  (index/datom-e d))
                        m)))
                  {}
                  asserts)
         entities (into #{} (map index/datom-e) asserts)
         ;; entity->ident: reverse of (:unique :db/ident) for axiom lookup
         entity->ident (into {}
                             (map (fn [[v e]] [e v]))
                             (get unique :db/ident))]
     (->SchemaWrapper inner local-stream strict? schema
                      (atom {:values values
                             :unique unique
                             :entities entities
                             :entity->ident entity->ident
                             :schema schema
                             :strict? strict?})))))


(defn- contains-schema-row?
  "True when any datom in the batch has an attribute that is one of the
   five :db/* property names."
  [datoms]
  (some (fn [d] (schema-row? (second d))) datoms))


(defn transact!
  "Translate, validate, and commit tx-data as one atomic record. Returns
   {:result :ok :t t :datoms datoms}. After successful append, replays
   the d5 datoms into wrapper state. Schema-row validation (both-modes)
   runs per datom before emission; unique-card-one check runs per record."
  [^SchemaWrapper wrapper tx-data]
  (when (ds/closed? wrapper)
    (throw (ex-info "cannot transact! on closed wrapper" {})))
  (when (empty? tx-data)
    (throw (ex-info "transact! requires at least one item"
                    {:tx-data tx-data})))
  (let [st @(.-state wrapper)
        schema (:schema st)
        strict? (.-strict? wrapper)
        translated
        (loop [items (vec tx-data)
               datoms []
               state st
               record-unique {}
               record-es #{}]
          (if (empty? items)
            {:datoms datoms, :state state,
             :record-unique record-unique, :record-es record-es}
            (let [item (first items)
                  result (translate-record item state schema strict?
                                           record-unique record-es)]
              ;; Validate each translated datom (both-modes schema-row checks)
              ;; Use PRE-translation record-unique/record-es for duplicate
              ;; detection (the result has already been updated).
              (doseq [d (:datoms result)]
                (validate-schema-row! (nth d 0) (nth d 1) (nth d 2)
                                      (:state result) schema
                                      record-unique
                                      record-es))
              (recur (rest items)
                     (into datoms (:datoms result))
                     (:state result)
                     (:record-unique result)
                     (:record-es result)))))
        datoms (:datoms translated)]
    (when (empty? datoms)
      (throw (ex-info "transact! produced no datoms" {:tx-data tx-data})))
    (let [result (tx/transact! (.-inner wrapper) datoms)
          d5-datoms (:datoms result)
          asserts (filterv datom/asserted? d5-datoms)
          has-schema-rows? (contains-schema-row? datoms)
          new-schema (if has-schema-rows?
                       (extract-schema
                         (index/snapshot-datoms
                           (.-local-stream wrapper)))
                       schema)
          ;; Post-record: unique-requires-card-one check (uses new-schema)
          _ (validate-unique-card-one! (:state translated) new-schema
                                       (:record-unique translated)
                                       datoms)
          st @(.-state wrapper)
          new-values
          (reduce
            (fn [m d]
              (let [k [(index/datom-e d) (index/datom-a d)]]
                (update m k (fnil conj #{}) (index/datom-v d))))
            (:values st)
            asserts)
          ;; F2: derive new-unique from new-schema (not stale outer schema)
          new-unique
          (reduce
            (fn [m d]
              (let [a (index/datom-a d)
                    rp (resolve-props new-schema a)]
                (if (and (:db/unique rp)
                         (= :db.cardinality/one (:db/cardinality rp)))
                  (assoc-in m [a (index/datom-v d)] (index/datom-e d))
                  m)))
            (:unique st)
            asserts)
          new-entities
          (into (:entities st) (map index/datom-e) asserts)
          ;; Update entity->ident from :db/ident assertions
          new-entity->ident
          (reduce
            (fn [m d]
              (if (= :db/ident (index/datom-a d))
                (assoc m (index/datom-e d) (index/datom-v d))
                m))
            (get st :entity->ident {})
            asserts)]
      (reset! (.-state wrapper)
              {:values new-values
               :unique new-unique
               :entities new-entities
               :entity->ident new-entity->ident
               :schema new-schema
               :strict? (.-strict? wrapper)})
      result)))


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

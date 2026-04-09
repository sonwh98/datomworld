(ns dao.db.in-memory
  "InMemoryDaoDB: pure Clojure, ClojureScript, and ClojureDart, no DataScript dependency.
   Full 5-tuple [e a v t m] datoms with sorted-set indexes, transaction
   pipeline, schema bootstrap, and embedded Datalog query engine.
   Datomic-compatible query API."
  (:require
    [dao.db :as dao-db :refer [IDaoStorage IDaoTransactor IDaoQueryEngine IDaoDB]]
    #?(:cljs [me.tonsky.persistent-sorted-set :as psset])))


;; Sentinel for unbound query variables (must precede seek helpers)
(def ^:private FREE ::free)


;; =============================================================================
;; Comparators
;; =============================================================================

(defn- datom-e
  [d]
  #?(:clj  (.-e ^dao.db.Datom d)
     :cljs (.-e ^js d)
     :cljd (:e d)))


(defn- datom-a
  [d]
  #?(:clj  (.-a ^dao.db.Datom d)
     :cljs (.-a ^js d)
     :cljd (:a d)))


(defn- datom-v
  [d]
  #?(:clj  (.-v ^dao.db.Datom d)
     :cljs (.-v ^js d)
     :cljd (:v d)))


(defn- datom-t
  [d]
  #?(:clj  (.-t ^dao.db.Datom d)
     :cljs (.-t ^js d)
     :cljd (:t d)))


(defn- datom-m
  [d]
  #?(:clj  (.-m ^dao.db.Datom d)
     :cljs (.-m ^js d)
     :cljd (:m d)))


(defn- cmp-long-field
  [a b]
  (cond
    (nil? a) (if (nil? b) 0 -1)
    (nil? b) 1
    :else #?(:clj  (Long/compare (long a) (long b))
             :cljs (compare a b)
             :cljd (compare a b))))


(defn- cmp-keyword-field
  [a b]
  (cond
    (nil? a) (if (nil? b) 0 -1)
    (nil? b) 1
    :else (compare a b)))


(defn- eavt-cmp
  [d1 d2]
  (let [c (cmp-long-field (datom-e d1) (datom-e d2))]
    (if (zero? c)
      (let [c (cmp-keyword-field (datom-a d1) (datom-a d2))]
        (if (zero? c)
          (let [c (dao-db/compare-vals (datom-v d1) (datom-v d2))]
            (if (zero? c)
              (let [c (cmp-long-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (cmp-long-field (datom-m d1) (datom-m d2))
                  c))
              c))
          c))
      c)))


(defn- aevt-cmp
  [d1 d2]
  (let [c (cmp-keyword-field (datom-a d1) (datom-a d2))]
    (if (zero? c)
      (let [c (cmp-long-field (datom-e d1) (datom-e d2))]
        (if (zero? c)
          (let [c (dao-db/compare-vals (datom-v d1) (datom-v d2))]
            (if (zero? c)
              (let [c (cmp-long-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (cmp-long-field (datom-m d1) (datom-m d2))
                  c))
              c))
          c))
      c)))


(defn- avet-cmp
  [d1 d2]
  (let [c (cmp-keyword-field (datom-a d1) (datom-a d2))]
    (if (zero? c)
      (let [c (dao-db/compare-vals (datom-v d1) (datom-v d2))]
        (if (zero? c)
          (let [c (cmp-long-field (datom-e d1) (datom-e d2))]
            (if (zero? c)
              (let [c (cmp-long-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (cmp-long-field (datom-m d1) (datom-m d2))
                  c))
              c))
          c))
      c)))


(defn- vaet-cmp
  [d1 d2]
  (let [c (dao-db/compare-vals (datom-v d1) (datom-v d2))]
    (if (zero? c)
      (let [c (cmp-keyword-field (datom-a d1) (datom-a d2))]
        (if (zero? c)
          (let [c (cmp-long-field (datom-e d1) (datom-e d2))]
            (if (zero? c)
              (let [c (cmp-long-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (cmp-long-field (datom-m d1) (datom-m d2))
                  c))
              c))
          c))
      c)))


(defn- meat-cmp
  [d1 d2]
  (let [c (cmp-long-field (datom-m d1) (datom-m d2))]
    (if (zero? c)
      (let [c (cmp-long-field (datom-e d1) (datom-e d2))]
        (if (zero? c)
          (let [c (cmp-keyword-field (datom-a d1) (datom-a d2))]
            (if (zero? c)
              (let [c (dao-db/compare-vals (datom-v d1) (datom-v d2))]
                (if (zero? c)
                  (cmp-long-field (datom-t d1) (datom-t d2))
                  c))
              c))
          c))
      c)))


(defn- sorted-index-by
  [cmp]
  #?(:clj  ((requiring-resolve 'me.tonsky.persistent-sorted-set/sorted-set-by) cmp)
     :cljs (psset/sorted-set-by cmp)
     :cljd (sorted-set-by cmp)))


;; =============================================================================
;; Seek helpers (range queries via nil-field sentinels)
;; =============================================================================

(defn- subseq-from
  "Find all entries in a persistent-sorted-set >= sentinel using the comparator.
   Returns a lazy sequence of entries from the first match onward."
  [sorted-set cmp sentinel]
  (let [s (seq sorted-set)]
    (drop-while #(neg? (cmp % sentinel)) s)))


(defn- seek-e
  [eavt e]
  (take-while #(= e (:e %))
              (subseq-from eavt eavt-cmp (dao-db/->datom e nil nil nil nil))))


(defn- seek-ea
  [eavt e a]
  (if (= a FREE)
    (seek-e eavt e)
    (take-while #(and (= e (:e %)) (= a (:a %)))
                (subseq-from eavt eavt-cmp (dao-db/->datom e a nil nil nil)))))


(defn- seek-av
  [avet a v]
  (take-while #(and (= a (:a %)) (= v (:v %)))
              (subseq-from avet avet-cmp (dao-db/->datom nil a v nil nil))))


(defn- seek-a
  [aevt a]
  (take-while #(= a (:a %))
              (subseq-from aevt aevt-cmp (dao-db/->datom nil a nil nil nil))))


(defn- seek-aev
  "Seek AEVT by a and e."
  [aevt a e]
  (take-while #(and (= a (:a %)) (= e (:e %)))
              (subseq-from aevt aevt-cmp (dao-db/->datom e a nil nil nil))))


(defn- seek-m
  "Seek MEAVT by m."
  [meat m]
  (take-while #(= m (:m %))
              (subseq-from meat meat-cmp (dao-db/->datom nil nil nil nil m))))


(defn- seek-me
  "Seek MEAVT by m and e."
  [meat m e]
  (take-while #(and (= m (:m %)) (= e (:e %)))
              (subseq-from meat meat-cmp (dao-db/->datom e nil nil nil m))))


(defn- seek-v
  "Seek VAET by v."
  [vaet v]
  (take-while #(= v (:v %))
              (subseq-from vaet vaet-cmp (dao-db/->datom nil nil v nil nil))))


(defn- seek-va
  "Seek VAET by v and a."
  [vaet v a]
  (take-while #(and (= v (:v %)) (= a (:a %)))
              (subseq-from vaet vaet-cmp (dao-db/->datom nil a v nil nil))))


;; =============================================================================
;; InMemoryDaoDB record
;; =============================================================================

(defrecord InMemoryDaoDB
  [eavt           ; sorted-set of Datom by [e a v t m]
   aevt           ; sorted-set of Datom by [a e v t m]
   avet           ; sorted-set of Datom by [a v e t m] — indexed + ref attrs
   vaet           ; sorted-set of Datom by [v a e t m] — ref attrs only
   meat           ; sorted-set of Datom by [m e a v t] — m != 0 only
   log            ; vector of [t added retracted], one entry per committed tx (temporal index)
   schema         ; {attr-kw {:db/valueType ... :db/cardinality ...}} — cache
   next-t         ; integer, monotonic tx counter
   next-eid       ; integer, next permanent entity ID (starts at 1025)
   ref-attrs      ; set of keywords with :db.type/ref
   card-many      ; set of keywords with :db.cardinality/many
   unique-attrs   ; set of keywords with :db/unique
   indexed-attrs  ; set of keywords with :db/index true
   ])


(def ^:private schema-defining-attrs
  #{:db/cardinality :db/valueType :db/index :db/ident :db/unique})


(declare as-of since)


(defn- build-schema-cache
  "Scan EAVT index and derive schema cache + attribute-class sets.
   Returns {:schema {attr-kw {attr-props}} :ref-attrs #{} :card-many #{} ...}"
  [eavt]
  (let [ident-datoms  (filter #(= :db/ident (:a %)) (seq eavt))
        eid->kw       (into {} (map (fn [d] [(:e d) (:v d)]) ident-datoms))
        schema-attrs  #{:db/valueType :db/cardinality :db/unique :db/index}
        schema        (reduce (fn [acc d]
                                (let [a (:a d)]
                                  (if (contains? schema-attrs a)
                                    (let [attr-kw (get eid->kw (:e d))]
                                      (if attr-kw
                                        (update acc attr-kw assoc a (:v d))
                                        acc))
                                    acc)))
                              {}
                              (seq eavt))
        ref-attrs     (into #{} (keep (fn [[k v]] (when (= :db.type/ref (:db/valueType v)) k)) schema))
        card-many     (into #{} (keep (fn [[k v]] (when (= :db.cardinality/many (:db/cardinality v)) k)) schema))
        unique-attrs  (into #{} (keep (fn [[k v]] (when (:db/unique v) k)) schema))
        indexed-attrs (into #{} (keep (fn [[k v]] (when (:db/index v) k)) schema))]
    {:schema schema
     :ref-attrs ref-attrs
     :card-many card-many
     :unique-attrs unique-attrs
     :indexed-attrs indexed-attrs}))


;; =============================================================================
;; Index helpers
;; =============================================================================

(defn- avet-attr?
  "True when attr is maintained in AVET for [a v] lookup."
  [db a]
  (or (contains? (:indexed-attrs db) a)
      (contains? (:ref-attrs db) a)))


(defn- rebuild-secondary-indexes
  "Rebuild AVET, VAET, and MEAVT from EAVT using db's current schema caches.
   EAVT and AEVT are assumed already correct; only secondary indexes are reset."
  [db]
  (let [{:keys [ref-attrs indexed-attrs eavt]} db
        db' (assoc db
                   :avet (empty (:avet db))
                   :vaet (empty (:vaet db))
                   :meat (empty (:meat db)))]
    (reduce (fn [d datom]
              (let [a (:a datom)]
                (cond-> d
                  (or (contains? indexed-attrs a) (contains? ref-attrs a))
                  (update :avet conj datom)
                  (contains? ref-attrs a)
                  (update :vaet conj datom)
                  (not (zero? (:m datom)))
                  (update :meat conj datom))))
            db'
            (seq eavt))))


(defn- add-datom-to-indexes
  "Conj a Datom into all appropriate indexes of db."
  [db datom]
  (let [{:keys [ref-attrs indexed-attrs eavt aevt avet vaet meat]} db
        a (:a datom)
        ref? (contains? ref-attrs a)
        avet? (or (contains? indexed-attrs a) ref?)
        db' (assoc db
                   :eavt (conj eavt datom)
                   :aevt (conj aevt datom))
        db' (if avet? (assoc db' :avet (conj avet datom)) db')
        db' (if ref? (assoc db' :vaet (conj vaet datom)) db')]
    (if (zero? (:m datom))
      db'
      (assoc db' :meat (conj meat datom)))))


(defn- retract-datom-from-indexes
  "Disj a Datom from all possible indexes of db.
   Retraction is broader than insertion because disj is a no-op when the datom
   is absent, and broad removal also clears entries that were indexed under an
   earlier schema classification."
  [db datom]
  (let [{:keys [eavt aevt avet vaet meat]} db
        db' (assoc db
                   :eavt (disj eavt datom)
                   :aevt (disj aevt datom)
                   :avet (disj avet datom)
                   :vaet (disj vaet datom))]
    (if (zero? (:m datom))
      db'
      (assoc db' :meat (disj meat datom)))))


;; =============================================================================
;; Transaction pipeline
;; =============================================================================

(defn- parse-tx-data
  "Normalize tx-data items into op-maps {:op :e :a :v :m-raw}.
   Map form entities without :db/id receive auto-assigned negative tempids."
  [tx-data]
  (:ops
    (reduce (fn [{:keys [ops next-tmp]} item]
              (cond
                (vector? item)
                (let [[op e a v & rest] item
                      m-raw (if (seq rest) (first rest) 0)]
                  {:ops (conj ops {:op op :e e :a a :v v :m-raw m-raw})
                   :next-tmp next-tmp})
                (map? item)
                (let [e      (or (:db/id item) next-tmp)
                      attrs  (dissoc item :db/id)
                      new-ops (mapv (fn [[a v]] {:op :db/add :e e :a a :v v :m-raw 0}) attrs)]
                  {:ops (into ops new-ops)
                   :next-tmp (if (:db/id item) next-tmp (dec next-tmp))})
                :else (throw (ex-info "Invalid tx-data item" {:item item}))))
            {:ops [] :next-tmp -1}
            tx-data)))


(defn- expand-m-map
  "If :m-raw is a map, allocate a metadata entity and return extra Datoms.
   Returns [updated-op extra-datoms new-next-eid]."
  [op next-eid t]
  (if (map? (:m-raw op))
    (let [meta-eid next-eid
          extra    (mapv (fn [[k v]] (dao-db/->datom meta-eid k v t 0)) (:m-raw op))]
      [(assoc op :m meta-eid) extra (inc next-eid)])
    [(assoc op :m (or (:m-raw op) 0)) [] next-eid]))


(defn- tempid?
  [x]
  (and (integer? x) (neg? x)))


(defn- collect-tempids
  "Return sorted distinct negative integers used as entity, ref-value, or metadata IDs.
   Metadata datoms are included so metadata-only refs allocate permanent IDs
   before their values are rewritten."
  [ops ref-attrs extra-datoms]
  (distinct
    (sort
      (concat
        (mapcat (fn [{:keys [e a v m]}]
                  (cond-> []
                    (tempid? e)
                    (conj e)
                    (and (contains? ref-attrs a)
                         (tempid? v))
                    (conj v)
                    (tempid? m)
                    (conj m)))
                ops)
        (mapcat (fn [d]
                  (let [e (:e d)
                        v (:v d)
                        m (:m d)]
                    (cond-> []
                      (tempid? e)
                      (conj e)
                      (and (contains? ref-attrs (:a d))
                           (tempid? v))
                      (conj v)
                      (tempid? m)
                      (conj m))))
                extra-datoms)))))


(defn- resolve-tempids
  "Build {tempid -> perm-id} map starting from next-eid."
  [tempids next-eid]
  (zipmap tempids (range next-eid (+ next-eid (count tempids)))))


(defn- apply-tempid-map
  "Rewrite negative :e, metadata :m, and ref-typed :v in ops using tempid-map."
  [ops tempid-map ref-attrs]
  (let [resolve (fn [id]
                  (if (tempid? id)
                    (get tempid-map id id)
                    id))]
    (mapv (fn [op]
            (-> op
                (update :e resolve)
                (update :m resolve)
                (update :v (fn [v]
                             (if (and (contains? ref-attrs (:a op))
                                      (integer? v))
                               (resolve v)
                               v)))))
          ops)))


(defn- lookup-ident-eid
  "Resolve an ident keyword to an entity id using same-tx idents first, then db."
  [db same-tx-ident->eid ident]
  (when (keyword? ident)
    (or (get same-tx-ident->eid ident)
        ;; Try optimized O(log N) lookup first
        (->> (seek-av (:avet db) :db/ident ident)
             (first)
             (:e))
        ;; Fallback to AEVT scan if not indexed yet (e.g. during bootstrap/import)
        (some (fn [d] (when (= ident (:v d)) (:e d)))
              (seek-a (:aevt db) :db/ident)))))


(defn- effective-ref-attrs-for-tx
  "Compute the ref-typed attrs visible during tx preprocessing.
   This respects same-tx :db/valueType adds and retractions in order."
  [db ops same-tx-ident->eid]
  (if-not (some #(= :db/valueType (:a %)) ops)
    (:ref-attrs db)
    (let [eid->ident (into {} (map (fn [d] [(:e d) (:v d)])
                                   (filter #(= :db/ident (:a %)) (seq (:eavt db)))))
          eid->ident (into eid->ident
                           (keep (fn [{:keys [op e a v]}]
                                   (when (and (= op :db/add) (= a :db/ident)) [e v]))
                                 ops))]
      (reduce (fn [ref-attrs {:keys [op e a v]}]
                (if (= a :db/valueType)
                  (let [resolved-e (if (keyword? e)
                                     (lookup-ident-eid db same-tx-ident->eid e)
                                     e)
                        attr-kw    (or (when (and (keyword? e) resolved-e) e)
                                       (get eid->ident resolved-e))]
                    (if attr-kw
                      (case op
                        :db/add (if (= v :db.type/ref)
                                  (conj ref-attrs attr-kw)
                                  (disj ref-attrs attr-kw))
                        :db/retract (if (= v :db.type/ref)
                                      (disj ref-attrs attr-kw)
                                      ref-attrs)
                        ref-attrs)
                      ref-attrs))
                  ref-attrs))
              (:ref-attrs db)
              ops))))


(defn run-tx
  "Apply tx-data to an InMemoryDaoDB.
   Returns {:db-after db' :db-before db :tx-data [...] :tempids {tempid->perm-id}}.
   Also includes :db as an alias for :db-after for backward compatibility."
  [db tx-data]
  (let [t (:next-t db)
        pre-next-eid (:next-eid db)
        ;; Step 1: parse
        parsed-ops (parse-tx-data tx-data)
        ;; Step 2: resolve keyword entity IDs via :db/ident lookup.
        ;; [:db/add :my-attr ...] → [:db/add <resolved-eid> ...]
        ;; Includes idents established in this same tx (e.g. -1 given :db/ident :tags
        ;; in op 1; later op uses :tags as entity — resolves to -1, then tempid pipeline
        ;; promotes it to a permanent EID).
        same-tx-ident->eid
        (into {} (keep (fn [{:keys [op e a v]}]
                         (when (and (= op :db/add) (= a :db/ident)) [v e]))
                       parsed-ops))

        ;; Compute effective-ref-attrs BEFORE Step 2 so keyword idents in :v
        ;; can be resolved for ref attributes defined in the same tx.
        ;; This also respects same-tx :db/valueType retractions.
        effective-ref-attrs
        (effective-ref-attrs-for-tx db parsed-ops same-tx-ident->eid)

        ;; Explicit positive user entity ids must advance allocation immediately,
        ;; otherwise metadata/tempid allocation can reuse them in this tx or the next.
        ;; Scans both entity ID and ref-value IDs (respecting same-tx ref schema).
        alloc-base-eid
        (reduce (fn [eid {:keys [op e a v m-raw]}]
                  (let [mx (if (and (= op :db/add) (integer? e) (>= e 1025)) (max eid (inc e)) eid)
                        mx (if (and (= op :db/add) (contains? effective-ref-attrs a)
                                    (integer? v) (>= v 1025))
                             (max mx (inc v))
                             mx)
                        mx (if (and (= op :db/add) (integer? m-raw) (>= m-raw 1025))
                             (max mx (inc m-raw))
                             mx)]
                    mx))
                (:next-eid db)
                parsed-ops)

        ops parsed-ops
        ;; Step 2: resolve keyword entity IDs via :db/ident lookup.
        ;; Includes idents established in this same tx.
        ops (mapv (fn [op]
                    (let [resolve (fn [id]
                                    (if (keyword? id)
                                      (lookup-ident-eid db same-tx-ident->eid id)
                                      id))
                          e' (resolve (:e op))
                          v' (if (contains? effective-ref-attrs (:a op))
                               (resolve (:v op))
                               (:v op))]
                      (when (and (keyword? (:e op)) (not e'))
                        (throw (ex-info "Unknown entity ident" {:ident (:e op)})))
                      (when (and (contains? effective-ref-attrs (:a op))
                                 (keyword? (:v op)) (not v'))
                        (throw (ex-info "Unknown value ident" {:ident (:v op)})))
                      (assoc op :e e' :v v')))
                  ops)
        ;; Step 3: expand m-maps, allocate metadata entity IDs
        [ops extra-datoms next-eid-after-m]
        (reduce (fn [[acc-ops acc-extra eid] op]
                  (let [[exp extras new-eid] (expand-m-map op eid t)]
                    [(conj acc-ops exp) (into acc-extra extras) new-eid]))
                [[] [] alloc-base-eid]
                ops)
        ;; Steps 4-5: collect and resolve tempids (uses effective-ref-attrs)
        tempids    (collect-tempids ops effective-ref-attrs extra-datoms)
        tempid-map (resolve-tempids tempids next-eid-after-m)
        new-next-eid (+ next-eid-after-m (count tempids))
        ;; Step 6: apply tempid map to ops (uses effective-ref-attrs)
        ops (apply-tempid-map ops tempid-map effective-ref-attrs)
        ;; Step 7: apply tempid map to ref-typed extra-datom values.
        ;; Only resolve when the metadata attribute is schema-declared as :db.type/ref.
        ;; Scalar metadata values like {:error/code -1} must not be rewritten.
        extra-datoms (mapv (fn [d]
                             (let [d (update d :m #(if (tempid? %)
                                                     (get tempid-map % %)
                                                     %))]
                               (if (contains? effective-ref-attrs (:a d))
                                 (let [v  (:v d)
                                       v' (if (keyword? v)
                                            (or (lookup-ident-eid db same-tx-ident->eid v)
                                                (throw (ex-info "Unknown value ident" {:ident v})))
                                            v)
                                       v' (if (tempid? v')
                                            (get tempid-map v' v')
                                            v')]
                                   (assoc d :v v'))
                                 d)))
                           extra-datoms)
        ;; Build {op datom} pairs
        op-datoms (mapv (fn [{:keys [op e a v m]}]
                          {:op op :datom (dao-db/->datom e a v t m)})
                        ops)
        ;; Step 8: enforce uniqueness constraints.
        ;; Compute effective unique-attrs for this tx: start from the pre-tx set,
        ;; add attrs gaining :db/unique in this tx, remove attrs losing it.
        ;; This makes same-tx schema changes visible to the uniqueness check.
        effective-unique-attrs
        (if-not (some (fn [{:keys [datom]}] (= :db/unique (:a datom))) op-datoms)
          (:unique-attrs db)
          (let [eid->ident (into {} (map (fn [d] [(:e d) (:v d)])
                                         (filter #(= :db/ident (:a %)) (seq (:eavt db)))))
                ;; Include idents being defined in this tx
                eid->ident (into eid->ident
                                 (keep (fn [{:keys [op datom]}]
                                         (when (and (= op :db/add) (= :db/ident (:a datom)))
                                           [(:e datom) (:v datom)]))
                                       op-datoms))
                gaining (into #{} (keep (fn [{:keys [op datom]}]
                                          (when (and (= op :db/add) (= :db/unique (:a datom)))
                                            (get eid->ident (:e datom))))
                                        op-datoms))
                losing  (into #{} (keep (fn [{:keys [op datom]}]
                                          (when (and (= op :db/retract) (= :db/unique (:a datom)))
                                            (get eid->ident (:e datom))))
                                        op-datoms))]
            (-> (:unique-attrs db) (into gaining) (#(apply disj % losing)))))
        _ (let [add-claim (fn [m k e]
                            (let [existing (get m k)]
                              (cond
                                (nil? existing) (assoc m k e)
                                (set? existing) (update m k conj e)
                                (= existing e) m
                                :else (assoc m k #{existing e}))))
                normalize-eids (fn [x]
                                 (if (set? x) x #{x}))
                tx-av-eids (reduce (fn [m {:keys [op datom]}]
                                     (if (and (= op :db/add)
                                              (contains? effective-unique-attrs (:a datom)))
                                       (add-claim m [(:a datom) (:v datom)] (:e datom))
                                       m))
                                   {}
                                   op-datoms)]
            (when (seq tx-av-eids)
              (let [releasing-by-av (reduce (fn [m {:keys [op datom]}]
                                              (if (and (= op :db/retract)
                                                       (contains? effective-unique-attrs (:a datom)))
                                                (update m [(:a datom) (:v datom)]
                                                        (fnil conj #{}) (:e datom))
                                                m))
                                            {}
                                            op-datoms)
                    attr-has-existing-data? (fn [a]
                                              (if (avet-attr? db a)
                                                (seq (seek-a (:avet db) a))
                                                (seq (seek-a (:aevt db) a))))
                    attrs-with-existing-data (into #{} (filter attr-has-existing-data?)
                                                   effective-unique-attrs)]
                (doseq [[[a v] eids-or-eid] tx-av-eids]
                  (let [eids (normalize-eids eids-or-eid)]
                    ;; Two distinct entities in the same tx claiming the same unique value.
                    (when (> (count eids) 1)
                      (throw (ex-info "Unique constraint violated" {:attr a :val v})))
                    ;; Same-tx retractions release pre-existing unique slots.
                    (when (contains? attrs-with-existing-data a)
                      (let [releasing (get releasing-by-av [a v] #{})
                            existing-datoms (if (avet-attr? db a)
                                              (seek-av (:avet db) a v)
                                              (filter #(= v (:v %)) (seek-a (:aevt db) a)))
                            existing (->> existing-datoms
                                          (map :e)
                                          (remove eids)
                                          (remove releasing))]
                        (when (seq existing)
                          (throw (ex-info "Unique constraint violated"
                                          {:attr a :val v :existing-eid (first existing)}))))))))))
        ;; Steps 9-11: apply ops to db (cardinality + index updates)
        ;; Track added datoms alongside db state.
        ;; Schema-defining attrs: after any op on these, rebuild caches immediately so
        ;; subsequent ops in the same tx see the correct card-many, ref-attrs, etc.
        tx-schema-touched?
        (or (some (fn [{:keys [datom]}] (contains? schema-defining-attrs (:a datom))) op-datoms)
            (some #(contains? schema-defining-attrs (:a %)) extra-datoms))

        base-db (assoc db :next-eid new-next-eid :next-t (inc t))
        [db' added-datoms retracted-datoms _seen-card-one]
        (reduce (fn [[db added-acc retracted-acc seen-card-one] {:keys [op datom]}]
                  (case op
                    :db/add
                    (let [card-one? (not (contains? (:card-many db) (:a datom)))
                          card-one-key (when card-one? [(:e datom) (:a datom)])
                          new-entity? (and (integer? (:e datom))
                                           (>= (:e datom) pre-next-eid))
                          can-skip-implicit? (and card-one?
                                                  new-entity?
                                                  (not (contains? seen-card-one card-one-key)))
                          implicit (when (and card-one? (not can-skip-implicit?))
                                     (vec (seek-ea (:eavt db) (:e datom) (:a datom))))
                          db' (if (seq implicit)
                                (reduce retract-datom-from-indexes db implicit)
                                db)
                          db' (add-datom-to-indexes db' datom)
                          ;; If this op touches schema, rebuild caches now so the next
                          ;; op in this tx uses correct card-many/ref-attrs/indexed-attrs.
                          db' (if (contains? schema-defining-attrs (:a datom))
                                (let [{:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
                                      (build-schema-cache (:eavt db'))]
                                  (assoc db'
                                         :schema schema
                                         :ref-attrs ref-attrs
                                         :card-many card-many
                                         :unique-attrs unique-attrs
                                         :indexed-attrs indexed-attrs))
                                db')]
                      [db'
                       (conj added-acc datom)
                       (into retracted-acc (or implicit []))
                       (cond-> seen-card-one card-one? (conj card-one-key))])
                    :db/retract
                    (let [existing   (seek-ea (:eavt db) (:e datom) (:a datom))
                          to-retract (vec (filter #(= (:v %) (:v datom)) existing))]
                      [(reduce retract-datom-from-indexes db to-retract)
                       added-acc
                       (into retracted-acc to-retract)
                       seen-card-one])
                    (throw (ex-info "Unknown tx op" {:op op}))))
                [base-db [] [] #{}]
                op-datoms)
        ;; Add metadata entities (already Datom records)
        db' (reduce add-datom-to-indexes db' extra-datoms)
        all-tx-datoms (into added-datoms extra-datoms)
        ;; Step 12: rebuild schema cache and secondary indexes.
        ;; Rebuilding secondary indexes (AVET/VAET/MEAVT) from EAVT with the final
        ;; schema caches ensures they are consistent even when a same-tx :db/retract
        ;; on a schema-defining attr left a stale entry (e.g. keyword v in VAET).
        db-with-log (update db' :log conj [t (vec all-tx-datoms) (vec retracted-datoms)])
        db-after (if tx-schema-touched?
                   (let [{:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
                         (build-schema-cache (:eavt db-with-log))]
                     (-> db-with-log
                         (assoc :schema schema
                                :ref-attrs ref-attrs
                                :card-many card-many
                                :unique-attrs unique-attrs
                                :indexed-attrs indexed-attrs)
                         rebuild-secondary-indexes))
                   db-with-log)]
    {:db       db-after   ; backward compat alias
     :db-after db-after
     :db-before db
     :tx-data  all-tx-datoms
     :tempids  tempid-map}))


;; =============================================================================
;; Datalog Query Engine
;; =============================================================================

(defn- and-then
  [x f]
  (when (some? x) (f x)))


(defn- resolve-binding
  "Return the bound value for sym in binding, or FREE if unbound symbol,
   or the literal value itself if sym is not a symbol."
  [binding sym]
  (if (symbol? sym)
    (get binding sym FREE)
    sym))


(defn- pad-to-5
  "Extend a clause vector to 5 elements, padding with FREE sentinels."
  [clause]
  (let [cnt (count clause)]
    (into (vec clause) (repeat (- 5 cnt) FREE))))


(defn- unify
  "Attempt to unify binding with sym=val.
   Returns extended binding on success, nil on conflict.
   FREE and _ are wildcards: always succeed without binding."
  [binding sym val]
  (cond
    (= sym FREE)               binding
    (= sym '_)                 binding
    (not (symbol? sym))        (when (= sym val) binding)
    (contains? binding sym)    (when (= (get binding sym) val) binding)
    :else                      (assoc binding sym val)))


(defn- select-datoms
  "Choose the best index for a pattern [e a v] and return matching datoms."
  [db e a v]
  (cond
    (not= e FREE)
    (seek-ea (:eavt db) e a)
    (and (not= a FREE) (not= v FREE)
         (avet-attr? db a))
    (seek-av (:avet db) a v)
    (not= a FREE)
    (seek-a (:aevt db) a)
    :else
    (seq (:eavt db))))


(defn- db-sym?
  "True if x is a symbol whose name starts with $."
  [x]
  (and (symbol? x) (= \$ (first (name x)))))


(defn- classify-in-pattern
  "Classify an :in binding pattern as :db, :scalar, :relation, :coll, or :tuple."
  [pattern]
  (cond
    (db-sym? pattern)                                              :db
    (symbol? pattern)                                              :scalar
    (and (vector? pattern)
         (>= (count pattern) 2)
         (vector? (first pattern))
         (= '... (last pattern)))                                  :relation
    (and (vector? pattern)
         (>= (count pattern) 2)
         (= '... (last pattern)))                                  :coll
    (vector? pattern)                                              :tuple
    :else (throw (ex-info "Unknown :in pattern" {:pattern pattern}))))


(defn- expand-in-binding
  "Expand one :in pattern + value into a seq of partial binding maps."
  [pattern value]
  (case (classify-in-pattern pattern)
    :db       [{::dbs {pattern value}}]
    :scalar   [{pattern value}]
    :coll     (let [sym (first pattern)]
                (mapv (fn [elem] {sym elem}) value))
    :tuple    [(zipmap pattern value)]
    :relation (let [vars (first pattern)]
                (mapv (fn [tup] (zipmap vars tup)) value))))


(defn- merge-bindings
  "Merge two binding maps, unioning their ::dbs registries."
  [b1 b2]
  (let [merged-dbs (merge (get b1 ::dbs {}) (get b2 ::dbs {}))]
    (cond-> (merge b1 b2)
      (seq merged-dbs) (assoc ::dbs merged-dbs))))


(defn- cross-join-bindings
  "Cross-join two sequences of binding maps."
  [rows1 rows2]
  (for [b1 rows1, b2 rows2] (merge-bindings b1 b2)))


(defn- build-init-bindings
  "Build initial binding rows from :in patterns and input values."
  [in-patterns inputs]
  (reduce (fn [acc [pat val]]
            (cross-join-bindings acc (expand-in-binding pat val)))
          [{}]
          (map vector in-patterns inputs)))


(defn- clause-db-and-pattern
  "Split a clause into [db-sym pattern-vec]. Defaults to '$ if no db prefix."
  [clause]
  (if (db-sym? (first clause))
    [(first clause) (vec (rest clause))]
    ['$ (vec clause)]))


(defn- resolve-db
  "Look up a database by its symbol in a binding's ::dbs registry."
  [binding db-sym]
  (get (get binding ::dbs) db-sym))


(defn- eval-clause
  "Evaluate one where clause against the database found in binding, extending it."
  [clause binding]
  (let [[db-sym pattern] (clause-db-and-pattern clause)
        db    (resolve-db binding db-sym)
        [ce ca cv ct cm] (pad-to-5 pattern)
        e-val (resolve-binding binding ce)
        a-val (resolve-binding binding ca)
        v-val (resolve-binding binding cv)
        datoms (select-datoms db e-val a-val v-val)]
    (keep (fn [d]
            (-> binding
                (unify ce (:e d))
                (and-then #(unify % ca (:a d)))
                (and-then #(unify % cv (:v d)))
                (and-then #(unify % ct (:t d)))
                (and-then #(unify % cm (:m d)))))
          datoms)))


(defn- estimate-clause-cost
  "Estimate candidate stream size for a positive datom clause under one binding."
  [clause binding]
  (let [[db-sym pattern] (clause-db-and-pattern clause)
        db (resolve-db binding db-sym)]
    (if-not db
      1000000
      (let [[ce ca cv] (pad-to-5 pattern)
            e-val (resolve-binding binding ce)
            a-val (resolve-binding binding ca)
            v-val (resolve-binding binding cv)]
        (cond
          (and (not= e-val FREE) (not= a-val FREE) (not= v-val FREE))
          1

          (and (not= e-val FREE) (not= a-val FREE))
          2

          (not= e-val FREE)
          4

          (and (not= a-val FREE) (not= v-val FREE)
               (contains? (:ref-attrs db) a-val))
          8

          (and (not= a-val FREE) (not= v-val FREE)
               (contains? (:indexed-attrs db) a-val))
          16

          (not= a-val FREE)
          64

          :else
          1024)))))


(defn- plan-where
  "Order positive datom clauses by estimated stream cardinality.
   Datalog conjunction is order-independent; this only changes interpretation cost."
  [clauses init-bindings]
  (if (or (<= (count clauses) 1) (empty? init-bindings))
    clauses
    (let [binding (first init-bindings)]
      (mapv second
            (sort-by (fn [[idx clause]]
                       [(estimate-clause-cost clause binding) idx])
                     (map-indexed vector clauses))))))


(defn- eval-where
  "Fold clauses over init-bindings, returning final relation."
  [clauses init-bindings]
  (let [planned-clauses (plan-where clauses init-bindings)]
    (reduce (fn [bindings clause]
              (mapcat #(eval-clause clause %) bindings))
            init-bindings
            planned-clauses)))


(defn- normalize-query
  "Accept both map queries and vector queries like [:find ... :in ... :where ...]."
  [query]
  (if (map? query)
    query
    (let [v (vec query)]
      (loop [i 0 result {} cur-key nil cur-vals []]
        (if (>= i (count v))
          (if cur-key (assoc result cur-key cur-vals) result)
          (let [x (nth v i)]
            (if (#{:find :in :where} x)
              (recur (inc i)
                     (if cur-key (assoc result cur-key cur-vals) result)
                     x [])
              (recur (inc i) result cur-key (conj cur-vals x)))))))))


;; =============================================================================
;; Entity and Pull APIs
;; =============================================================================

(defn native-entity
  "Return an entity map for eid with :db/id. Card-many attrs are sets.
   Returns nil if entity has no datoms."
  [db eid]
  (when eid
    (let [datoms (seek-e (:eavt db) eid)]
      (when (seq datoms)
        (reduce (fn [m d]
                  (let [a (:a d) v (:v d)]
                    (if (contains? (:card-many db) a)
                      (update m a (fnil conj #{}) v)
                      (assoc m a v))))
                {:db/id eid}
                datoms)))))


(defn- pull-attr-val
  "Return value(s) for attr on eid. Returns set for card-many, scalar otherwise."
  [db eid attr]
  (let [ds (seek-ea (:eavt db) eid attr)]
    (when (seq ds)
      (if (contains? (:card-many db) attr)
        (into #{} (map :v) ds)
        (:v (first ds))))))


(declare native-pull-impl)


(defn- pull-spec
  "Apply one pattern element to the result map for eid."
  [db eid result spec depth]
  (cond
    (= '* spec)
    (let [datoms (seek-e (:eavt db) eid)]
      (reduce (fn [r d]
                (let [a (:a d) v (:v d)]
                  (if (contains? (:card-many db) a)
                    (update r a (fnil conj #{}) v)
                    (assoc r a v))))
              (assoc result :db/id eid)
              datoms))

    (keyword? spec)
    (if (= :db/id spec)
      (assoc result :db/id eid)
      (if-let [v (pull-attr-val db eid spec)]
        (assoc result spec v)
        result))

    (map? spec)
    (reduce (fn [r [ref-attr sub-pattern]]
              (let [ds (seek-ea (:eavt db) eid ref-attr)]
                (if (empty? ds)
                  r
                  (let [vals (map :v ds)]
                    (if (contains? (:card-many db) ref-attr)
                      (assoc r ref-attr
                             (into #{} (keep #(native-pull-impl db sub-pattern % (inc depth))) vals))
                      (assoc r ref-attr
                             (native-pull-impl db sub-pattern (first vals) (inc depth))))))))
            result
            spec)

    :else result))


(defn- native-pull-impl
  [db pattern eid depth]
  (when (and eid (< depth 64))
    (reduce #(pull-spec db eid %1 %2 depth) {} pattern)))


(defn native-pull
  "Pull structured data from db using pattern for entity eid.
   Supports: [*], [:attr1 :attr2], [:db/id :attr], [{:ref-attr [:sub]}]."
  [db pattern eid]
  (native-pull-impl db pattern eid 0))


(defn native-pull-many
  "Pull structured data for multiple eids."
  [db pattern eids]
  (mapv #(native-pull db pattern %) eids))


;; =============================================================================
;; IDaoDb helper functions
;; =============================================================================

(defn native-transact
  "Transact tx-data. Returns Datomic-compatible result map."
  [db tx-data]
  (run-tx db tx-data))


(defn native-q
  "Run a Datalog query against an InMemoryDaoDB.
   Supports full Datomic :in binding forms: scalars, collections, tuples,
   relations, and multiple named databases."
  [db query inputs]
  (let [{:keys [find in where]} (normalize-query query)
        in-patterns   (or in ['$])
        ;; Prepend db to inputs so it becomes the first variadic arg
        all-inputs    (cons db inputs)
        init-bindings (build-init-bindings in-patterns all-inputs)
        result        (eval-where (or where []) init-bindings)]
    (into #{} (map (fn [b] (mapv #(get b %) find)) result))))


(defn native-datoms
  "Return datoms for index with optional leading-component filter.
   components is a seq of values corresponding to the index's leading positions."
  ([db index] (native-datoms db index nil))
  ([db index components]
   (let [cs (if components (vec components) [])]
     (case (keyword (name index))
       :eavt
       (case (count cs)
         0 (seq (:eavt db))
         1 (seek-e  (:eavt db) (cs 0))
         2 (seek-ea (:eavt db) (cs 0) (cs 1))
         3 (filter #(= (cs 2) (:v %)) (seek-ea (:eavt db) (cs 0) (cs 1)))
         (seq (:eavt db)))

       :aevt
       (case (count cs)
         0 (seq (:aevt db))
         1 (seek-a   (:aevt db) (cs 0))
         2 (seek-aev (:aevt db) (cs 0) (cs 1))
         (seq (:aevt db)))

       :avet
       (case (count cs)
         0 (seq (:avet db))
         1 (seek-a  (:avet db) (cs 0))
         2 (seek-av (:avet db) (cs 0) (cs 1))
         3 (filter #(= (cs 2) (:e %)) (seek-av (:avet db) (cs 0) (cs 1)))
         (seq (:avet db)))

       :vaet
       (case (count cs)
         0 (seq (:vaet db))
         1 (seek-v  (:vaet db) (cs 0))
         2 (seek-va (:vaet db) (cs 0) (cs 1))
         (seq (:vaet db)))

       :meat
       (case (count cs)
         0 (seq (:meat db))
         1 (seek-m  (:meat db) (cs 0))
         2 (seek-me (:meat db) (cs 0) (cs 1))
         (seq (:meat db)))

       (seq (get db (keyword (name (str index)))))))))


(defn native-entity-attrs
  [db eid]
  (reduce (fn [m d]
            (let [a (:a d) v (:v d)]
              (if (contains? (:card-many db) a)
                (update m a (fnil conj []) v)
                (assoc m a v))))
          {}
          (seek-e (:eavt db) eid)))


(defn native-find-eids-by-av
  [db a v]
  (if (avet-attr? db a)
    (into #{} (map :e) (seek-av (:avet db) a v))
    (->> (seek-a (:aevt db) a)
         (filter #(= v (:v %)))
         (map :e)
         set)))


(defn native-with
  "Apply tx-data to db value. Returns Datomic-compatible result map."
  [db tx-data]
  (run-tx db tx-data))


(defn native-basis-t
  "Return the basis-t (last committed transaction ID)."
  [db]
  (dec (:next-t db)))


;; =============================================================================
;; Factory
;; =============================================================================

(defn- schema->tx-data
  "Convert a DataScript-format schema map to InMemoryDaoDB tx-data.
   Each entry {attr-kw props} becomes [:db/add tempid :db/ident attr-kw] plus
   property assertions."
  [schema]
  (let [counter (atom 0)
        tempid! (fn [] (swap! counter dec) @counter)]
    (mapcat (fn [[attr-kw props]]
              (let [eid (tempid!)]
                (cond-> [[:db/add eid :db/ident attr-kw]]
                  (:db/valueType props)
                  (conj [:db/add eid :db/valueType (:db/valueType props)])
                  (:db/cardinality props)
                  (conj [:db/add eid :db/cardinality (:db/cardinality props)])
                  (:db/unique props)
                  (conj [:db/add eid :db/unique (:db/unique props)])
                  (:db/index props)
                  (conj [:db/add eid :db/index (:db/index props)]))))
            schema)))


(defn empty-db
  "Create an empty InMemoryDaoDB with bootstrap schema entities (eids 1-13)."
  []
  (let [e-set  (sorted-index-by eavt-cmp)
        ae-set (sorted-index-by aevt-cmp)
        av-set (sorted-index-by avet-cmp)
        va-set (sorted-index-by vaet-cmp)
        me-set (sorted-index-by meat-cmp)
        base   (->InMemoryDaoDB e-set ae-set av-set va-set me-set
                                [[0 (vec dao-db/bootstrap-datoms) []]]
                                {} 1 1025 #{} #{} #{} #{})
        db     (reduce add-datom-to-indexes base dao-db/bootstrap-datoms)
        {:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
        (build-schema-cache (:eavt db))]
    (assoc db
           :schema schema
           :ref-attrs ref-attrs
           :card-many card-many
           :unique-attrs unique-attrs
           :indexed-attrs indexed-attrs)))


(defn create
  "Create an InMemoryDaoDB from a DataScript-format schema map."
  [schema]
  (let [db      (empty-db)
        tx-data (schema->tx-data schema)]
    (if (seq tx-data)
      (:db-after (run-tx db tx-data))
      db)))


;; =============================================================================
;; Four-Component Protocol Wrapper Functions
;; =============================================================================

(defn latest-t
  "Get the latest transaction ID from a database."
  [db]
  (dao-db/latest-t db))


(defn write-segment!
  "Write a segment to a database."
  [db segment]
  (dao-db/write-segment! db segment))


(defn read-segments
  "Read segments from a database."
  [db t-min t-max]
  (dao-db/read-segments db t-min t-max))


(defn transact!
  "Transact on a database."
  [db tx-data]
  (dao-db/transact! db tx-data))


(defn run-q
  "Run a Datalog query on a database."
  [db query inputs]
  (dao-db/run-q db query inputs))


(defn datoms
  "Get datoms from a database index."
  ([db index]
   (dao-db/datoms db index))
  ([db index & components]
   (apply dao-db/datoms db index components)))


(defn entity-attrs
  "Get entity attributes from a database."
  [db eid]
  (dao-db/entity-attrs db eid))


(defn find-eids-by-av
  "Find entity IDs by attribute and value."
  [db a v]
  (dao-db/find-eids-by-av db a v))


;; =============================================================================
;; Protocol Implementations
;; ============================================================================="

(extend-type InMemoryDaoDB

  IDaoStorage
  (write-segment! [this [t added retracted]]
    ;; segment is a [t added retracted] entry — the same format read-segments returns.
    ;; Appends to log, applies additions and retractions, advances :next-t, then
    ;; rebuilds schema caches and secondary indexes so the returned db is self-consistent.
    (let [db' (-> this
                  (update :log conj [t (vec added) (vec retracted)])
                  (as-> db (reduce add-datom-to-indexes db added))
                  (as-> db (reduce retract-datom-from-indexes db retracted))
                  (update :next-t #(max % (inc t))))
          {:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
          (build-schema-cache (:eavt db'))
          max-imported-eid (reduce (fn [mx d]
                                     (let [e (:e d) v (:v d) a (:a d) m (:m d)
                                           mx (if (and (integer? e) (pos? e)) (max mx e) mx)
                                           mx (if (and (contains? ref-attrs a)
                                                       (integer? v) (pos? v))
                                                (max mx v)
                                                mx)
                                           mx (if (and (integer? m) (pos? m))
                                                (max mx m)
                                                mx)]
                                       mx))
                                   1024
                                   (concat added retracted))]
      (-> (assoc db'
                 :schema        schema
                 :ref-attrs     ref-attrs
                 :card-many     card-many
                 :unique-attrs  unique-attrs
                 :indexed-attrs indexed-attrs
                 :next-eid      (max (:next-eid db') (inc max-imported-eid)))
          rebuild-secondary-indexes)))
  (read-segments [this t-min t-max]
    ;; O(number of transactions in range). Log is ordered by t.
    ;; Returns 3-element [t added retracted] entries.
    (->> (:log this)
         (filter (fn [[t]] (and (>= t t-min) (<= t t-max))))))
  (latest-t [this]
    (dec (:next-t this)))

  IDaoTransactor
  (transact! [this tx-data] (run-tx this tx-data))
  (with [this tx-data] (native-with this tx-data))

  IDaoQueryEngine
  (run-q           [this query inputs] (native-q this query inputs))
  (index-datoms    [this index components] (native-datoms this index components))

  IDaoDB
  (entity          [this eid] (native-entity this eid))
  (pull            [this pattern eid] (native-pull this pattern eid))
  (pull-many       [this pattern eids] (native-pull-many this pattern eids))
  (basis-t         [this] (native-basis-t this))
  (as-of           [this t] (as-of this t))
  (since           [this t] (since this t))
  (entity-attrs    [this eid] (native-entity-attrs this eid))
  (find-eids-by-av [this a v] (native-find-eids-by-av this a v)))


;; =============================================================================
;; History
;; =============================================================================

(defn- replay-log-entries
  "Replay a seq of [t added retracted] log entries onto empty indexes.
   Two-phase: phase 1 populates EAVT/AEVT with empty schema caches (so no
   stale ref/indexed decisions are made); phase 2 rebuilds schema caches from
   the final EAVT then re-populates AVET/VAET/MEAVT correctly."
  [db entries]
  (let [entries  (vec entries)
        ;; Start with empty indexes AND empty schema caches so add-datom-to-indexes
        ;; never writes stale AVET/VAET entries during phase 1.
        base     (assoc db
                        :eavt          (empty (:eavt db))
                        :aevt          (empty (:aevt db))
                        :avet          (empty (:avet db))
                        :vaet          (empty (:vaet db))
                        :meat          (empty (:meat db))
                        :schema        {}
                        :ref-attrs     #{}
                        :card-many     #{}
                        :unique-attrs  #{}
                        :indexed-attrs #{})
        ;; Phase 1: replay into EAVT/AEVT only (schema caches are empty so
        ;; add-datom-to-indexes skips AVET/VAET/MEAVT — correct, we fill those later).
        replayed (reduce (fn [db [_t added retracted]]
                           (as-> (reduce add-datom-to-indexes db added) d
                                 (reduce retract-datom-from-indexes d retracted)))
                         base
                         entries)
        last-t   (if (seq entries) (apply max (map first entries)) -1)
        ;; Phase 2: rebuild schema caches from the historical EAVT.
        {:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
        (build-schema-cache (:eavt replayed))]
    ;; Phase 3: re-populate AVET/VAET/MEAVT by scanning EAVT with correct caches.
    (-> replayed
        (assoc :next-t        (inc last-t)
               :schema        schema
               :ref-attrs     ref-attrs
               :card-many     card-many
               :unique-attrs  unique-attrs
               :indexed-attrs indexed-attrs)
        rebuild-secondary-indexes)))


(defn as-of
  "Return a db view containing only datoms with t <= tx.
   Uses the storage log as the temporal index: O(txns-in-range + datoms)."
  [db t]
  (->> (:log db)
       (filter #(<= (first %) t))
       (replay-log-entries db)))


(defn since
  "Return a db view containing only datoms with t > tx.
   Uses the storage log as the temporal index: O(txns-in-range + datoms).
   Schema caches are taken from the full db: schema established before the cutoff
   is still in effect and must govern AVET/VAET classification of post-cutoff datoms."
  [db t]
  (let [replayed (->> (:log db)
                      (filter #(> (first %) t))
                      (replay-log-entries db))]
    ;; replay-log-entries rebuilds schema caches from the truncated EAVT, which
    ;; excludes schema defined before the cutoff.  Override with the full db's
    ;; caches, then re-run secondary index rebuild with the correct classification.
    (-> replayed
        (assoc :schema        (:schema db)
               :ref-attrs     (:ref-attrs db)
               :card-many     (:card-many db)
               :unique-attrs  (:unique-attrs db)
               :indexed-attrs (:indexed-attrs db))
        rebuild-secondary-indexes)))

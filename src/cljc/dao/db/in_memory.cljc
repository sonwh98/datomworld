(ns dao.db.in-memory
  "InMemoryDaoDB: pure Clojure, ClojureScript, and ClojureDart, no DataScript dependency.
   Full 5-tuple [e a v t m] datoms with sorted-set indexes, transaction
   pipeline, schema bootstrap, and embedded Datalog query engine.
   Datomic-compatible query API."
  (:require
    [dao.db :refer [IDaoDb]]
    [datomworld :as dw]))


;; Sentinel for unbound query variables (must precede seek helpers)
(def ^:private FREE ::free)


;; =============================================================================
;; Comparators
;; =============================================================================

(defn- make-comparator
  [positions]
  (fn [d1 d2]
    (loop [[p & ps] positions]
      (if (nil? p) 0
          (let [c (dw/compare-vals (get d1 p) (get d2 p))]
            (if (zero? c) (recur ps) c))))))


(def ^:private eavt-cmp (make-comparator [:e :a :v :t :m]))
(def ^:private aevt-cmp (make-comparator [:a :e :v :t :m]))
(def ^:private avet-cmp (make-comparator [:a :v :e :t :m]))
(def ^:private vaet-cmp (make-comparator [:v :a :e :t :m]))
(def ^:private meat-cmp (make-comparator [:m :e :a :t]))


;; =============================================================================
;; Seek helpers (range queries via subseq + nil-field sentinels)
;; =============================================================================

(defn- seek-e
  [eavt e]
  (take-while #(= e (:e %))
              (subseq eavt >= (dw/->Datom e nil nil nil nil))))


(defn- seek-ea
  [eavt e a]
  (if (= a FREE)
    (seek-e eavt e)
    (take-while #(and (= e (:e %)) (= a (:a %)))
                (subseq eavt >= (dw/->Datom e a nil nil nil)))))


(defn- seek-av
  [avet a v]
  (take-while #(and (= a (:a %)) (= v (:v %)))
              (subseq avet >= (dw/->Datom nil a v nil nil))))


(defn- seek-a
  [aevt a]
  (take-while #(= a (:a %))
              (subseq aevt >= (dw/->Datom nil a nil nil nil))))


(defn- seek-aev
  "Seek AEVT by a and e."
  [aevt a e]
  (take-while #(and (= a (:a %)) (= e (:e %)))
              (subseq aevt >= (dw/->Datom e a nil nil nil))))


(defn- seek-m
  "Seek MEAT by m."
  [meat m]
  (take-while #(= m (:m %))
              (subseq meat >= (dw/->Datom nil nil nil nil m))))


(defn- seek-me
  "Seek MEAT by m and e."
  [meat m e]
  (take-while #(and (= m (:m %)) (= e (:e %)))
              (subseq meat >= (dw/->Datom e nil nil nil m))))


(defn- seek-v
  "Seek VAET by v."
  [vaet v]
  (take-while #(= v (:v %))
              (subseq vaet >= (dw/->Datom nil nil v nil nil))))


(defn- seek-va
  "Seek VAET by v and a."
  [vaet v a]
  (take-while #(and (= v (:v %)) (= a (:a %)))
              (subseq vaet >= (dw/->Datom nil a v nil nil))))


;; =============================================================================
;; InMemoryDaoDB record
;; =============================================================================

(defrecord InMemoryDaoDB
  [eavt           ; sorted-set of Datom by [e a v t m]
   aevt           ; sorted-set of Datom by [a e v t m]
   avet           ; sorted-set of Datom by [a v e t m] — indexed + ref attrs
   vaet           ; sorted-set of Datom by [v a e t m] — ref attrs only
   meat           ; sorted-set of Datom by [m e a t]   — m != 0 only
   log            ; vector of [t [Datom ...]], one entry per committed tx (temporal index)
   schema         ; {attr-kw {:db/valueType ... :db/cardinality ...}} — cache
   next-t         ; integer, monotonic tx counter
   next-eid       ; integer, next permanent entity ID (starts at 1025)
   ref-attrs      ; set of keywords with :db.type/ref
   card-many      ; set of keywords with :db.cardinality/many
   unique-attrs   ; set of keywords with :db/unique
   indexed-attrs  ; set of keywords with :db/index true
   ])


;; =============================================================================
;; Schema
;; =============================================================================

(def ^:private bootstrap-datoms
  [(dw/->Datom 2  :db/ident :db/ident            0 0)
   (dw/->Datom 3  :db/ident :db/valueType        0 0)
   (dw/->Datom 4  :db/ident :db/cardinality      0 0)
   (dw/->Datom 5  :db/ident :db/unique           0 0)
   (dw/->Datom 6  :db/ident :db/index            0 0)
   (dw/->Datom 7  :db/ident :db.cardinality/one  0 0)
   (dw/->Datom 8  :db/ident :db.cardinality/many 0 0)
   (dw/->Datom 9  :db/ident :db.type/ref         0 0)
   (dw/->Datom 10 :db/ident :db.type/string      0 0)
   (dw/->Datom 11 :db/ident :db.type/long        0 0)
   (dw/->Datom 12 :db/ident :db.type/boolean     0 0)
   (dw/->Datom 13 :db/ident :db.type/keyword     0 0)])


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

(defn- add-datom-to-indexes
  "Conj a Datom into all appropriate indexes of db."
  [db datom]
  (let [{:keys [ref-attrs indexed-attrs]} db
        a (:a datom)]
    (cond-> (-> db
                (update :eavt conj datom)
                (update :aevt conj datom))
      (or (contains? indexed-attrs a)
          (contains? ref-attrs a))
      (update :avet conj datom)
      (contains? ref-attrs a)
      (update :vaet conj datom)
      (not (zero? (:m datom)))
      (update :meat conj datom))))


(defn- retract-datom-from-indexes
  "Disj a Datom from all five indexes of db."
  [db datom]
  (cond-> (-> db
              (update :eavt disj datom)
              (update :aevt disj datom)
              (update :avet disj datom)
              (update :vaet disj datom))
    (not (zero? (:m datom)))
    (update :meat disj datom)))


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
          extra    (mapv (fn [[k v]] (dw/->Datom meta-eid k v t 0)) (:m-raw op))]
      [(assoc op :m meta-eid) extra (inc next-eid)])
    [(assoc op :m (or (:m-raw op) 0)) [] next-eid]))


(defn- collect-tempids
  "Return sorted distinct negative integers used as entity or ref-value IDs."
  [ops ref-attrs]
  (distinct
    (sort
      (mapcat (fn [{:keys [e a v]}]
                (cond-> []
                  (and (integer? e) (neg? e))
                  (conj e)
                  (and (contains? ref-attrs a)
                       (integer? v) (neg? v))
                  (conj v)))
              ops))))


(defn- resolve-tempids
  "Build {tempid -> perm-id} map starting from next-eid."
  [tempids next-eid]
  (zipmap tempids (range next-eid (+ next-eid (count tempids)))))


(defn- apply-tempid-map
  "Rewrite negative :e and ref-typed :v in ops using tempid-map."
  [ops tempid-map ref-attrs]
  (let [resolve (fn [id]
                  (if (and (integer? id) (neg? id))
                    (get tempid-map id id)
                    id))]
    (mapv (fn [op]
            (-> op
                (update :e resolve)
                (update :v (fn [v]
                             (if (and (contains? ref-attrs (:a op))
                                      (integer? v))
                               (resolve v)
                               v)))))
          ops)))


(defn- enforce-cardinality-one
  "For card-one attrs retract all existing [e a ?v] datoms before adding new one."
  [db e a]
  (if (contains? (:card-many db) a)
    db
    (reduce retract-datom-from-indexes db (seek-ea (:eavt db) e a))))


(defn run-tx
  "Apply tx-data to an InMemoryDaoDB.
   Returns {:db-after db' :db-before db :tx-data [...] :tempids {tempid->perm-id}}.
   Also includes :db as an alias for :db-after for backward compatibility."
  [db tx-data]
  (let [t (:next-t db)
        ;; Step 1: parse
        ops (parse-tx-data tx-data)
        ;; Step 2: expand m-maps, allocate metadata entity IDs
        [ops extra-datoms next-eid-after-m]
        (reduce (fn [[acc-ops acc-extra eid] op]
                  (let [[exp extras new-eid] (expand-m-map op eid t)]
                    [(conj acc-ops exp) (into acc-extra extras) new-eid]))
                [[] [] (:next-eid db)]
                ops)
        ;; Steps 3-4: collect and resolve tempids
        tempids    (collect-tempids ops (:ref-attrs db))
        tempid-map (resolve-tempids tempids next-eid-after-m)
        new-next-eid (+ next-eid-after-m (count tempids))
        ;; Step 5: apply tempid map to ops
        ops (apply-tempid-map ops tempid-map (:ref-attrs db))
        ;; Build {op datom} pairs
        op-datoms (mapv (fn [{:keys [op e a v m]}]
                          {:op op :datom (dw/->Datom e a v t m)})
                        ops)
        ;; Steps 6-8: apply ops to db (cardinality + index updates)
        ;; Track added datoms alongside db state
        base-db (assoc db :next-eid new-next-eid :next-t (inc t))
        [db' added-datoms]
        (reduce (fn [[db acc] {:keys [op datom]}]
                  (case op
                    :db/add
                    [(-> db
                         (enforce-cardinality-one (:e datom) (:a datom))
                         (add-datom-to-indexes datom))
                     (conj acc datom)]
                    :db/retract
                    (let [existing  (seek-ea (:eavt db) (:e datom) (:a datom))
                          to-retract (filter #(= (:v %) (:v datom)) existing)]
                      [(reduce retract-datom-from-indexes db to-retract) acc])
                    (throw (ex-info "Unknown tx op" {:op op}))))
                [base-db []]
                op-datoms)
        ;; Add metadata entities (already Datom records)
        db' (reduce add-datom-to-indexes db' extra-datoms)
        all-tx-datoms (into added-datoms extra-datoms)
        ;; Step 9: rebuild schema cache
        {:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
        (build-schema-cache (:eavt db'))
        db-after (-> db'
                     (assoc :schema schema
                            :ref-attrs ref-attrs
                            :card-many card-many
                            :unique-attrs unique-attrs
                            :indexed-attrs indexed-attrs)
                     (update :log conj [t (vec all-tx-datoms)]))]
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
         (contains? (:indexed-attrs db) a))
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


(defn- eval-where
  "Fold clauses over init-bindings, returning final relation."
  [clauses init-bindings]
  (reduce (fn [bindings clause]
            (mapcat #(eval-clause clause %) bindings))
          init-bindings
          clauses))


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


(defn q
  "Run a Datalog query against an InMemoryDaoDB.
   Supports full Datomic :in binding forms: scalars, collections, tuples,
   relations, and multiple named databases."
  [query & inputs]
  (let [{:keys [find in where]} (normalize-query query)
        in-patterns   (or in ['$])
        init-bindings (build-init-bindings in-patterns inputs)
        result        (eval-where (or where []) init-bindings)]
    (into #{} (map (fn [b] (mapv #(get b %) find)) result))))


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
  [db query inputs]
  (apply q query db inputs))


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
         1 (seek-a  (:aevt db) (cs 0))
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
  [db attr val]
  (if (contains? (:indexed-attrs db) attr)
    (into #{} (map :e) (seek-av (:avet db) attr val))
    (->> (seek-a (:aevt db) attr)
         (filter #(= val (:v %)))
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
  "Create an empty InMemoryDaoDB with bootstrap schema entities (eids 2-13)."
  []
  (let [e-set  (sorted-set-by eavt-cmp)
        ae-set (sorted-set-by aevt-cmp)
        av-set (sorted-set-by avet-cmp)
        va-set (sorted-set-by vaet-cmp)
        me-set (sorted-set-by meat-cmp)
        base   (->InMemoryDaoDB e-set ae-set av-set va-set me-set
                                [[0 (vec bootstrap-datoms)]]
                                {} 1 1025 #{} #{} #{} #{})
        db     (reduce add-datom-to-indexes base bootstrap-datoms)
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
;; Three-Component Protocols
;; =============================================================================

(defprotocol IDaoStorage
  "Append-only log of datom segments, keyed by transaction range."

  (write-segment!
    [this segment]
    "Append a segment (seq of datoms) to storage. Returns latest-t.")

  (read-segments
    [this t-min t-max]
    "Return a seq of datom seqs, one per transaction, for t-min <= t <= t-max.")

  (latest-t
    [this]
    "Return the highest committed transaction ID."))


(defprotocol IDaoTransactor
  "Single writer. Serializes all transactions to preserve monotonic t."

  (transact!
    [this tx-data]
    "Apply tx-data. Returns {:db-after db :tempids {...} :tx-data [...]}.")

  (current-db
    [this]
    "Return the current db value (the five indexes as an immutable snapshot)."))


(defprotocol IDaoQueryEngine
  "Read-only query engine over a cached db snapshot."

  (run-q
    [this query inputs]
    "Run a Datalog query. inputs is a seq of extra bindings beyond $.")

  (datoms
    [this index components]
    "Return datoms for index with optional leading-component filter seq.")

  (entity-attrs
    [this eid]
    "Return {attr val} map for entity. Card-many attrs are vectors.")

  (find-eids-by-av
    [this attr val]
    "Return set of entity IDs where attribute = val."))


(extend-type InMemoryDaoDB

  IDaoStorage
  (write-segment! [this segment]
    ;; Appends segment to log and updates indexes. Returns max t across segment.
    ;; In the collapsed single-process topology run-tx is the primary write path;
    ;; write-segment! is available for raw datom ingestion without tx bookkeeping.
    (let [seg-t (apply max (map :t segment))]
      (-> this
          (update :log conj [seg-t (vec segment)])
          (as-> db (reduce add-datom-to-indexes db segment)))
      seg-t))
  (read-segments [this t-min t-max]
    ;; O(number of transactions in range). Log is ordered by t.
    (->> (:log this)
         (filter (fn [[t _]] (and (>= t t-min) (<= t t-max))))
         (map second)))
  (latest-t [this]
    (dec (:next-t this)))

  IDaoTransactor
  (transact! [this tx-data] (run-tx this tx-data))
  (current-db [this] this)

  IDaoQueryEngine
  (run-q           [this query inputs] (native-q this query inputs))
  (datoms          [this index components] (native-datoms this index components))
  (entity-attrs    [this eid] (native-entity-attrs this eid))
  (find-eids-by-av [this attr val] (native-find-eids-by-av this attr val)))


;; =============================================================================
;; History
;; =============================================================================

(defn- rebuild-indexes
  "Return db with all five sorted-set indexes rebuilt from a seq of datoms.
   Uses the current db's comparators (empty preserves comparator) and
   schema caches (ref-attrs, indexed-attrs)."
  [db datoms]
  (reduce add-datom-to-indexes
          (assoc db
                 :eavt (empty (:eavt db))
                 :aevt (empty (:aevt db))
                 :avet (empty (:avet db))
                 :vaet (empty (:vaet db))
                 :meat (empty (:meat db)))
          datoms))


(defn as-of
  "Return a db view containing only datoms with t <= tx.
   Uses the storage log as the temporal index: O(txns-in-range + datoms)."
  [db t]
  (->> (:log db)
       (filter #(<= (first %) t))
       (mapcat second)
       (rebuild-indexes db)))


(defn since
  "Return a db view containing only datoms with t > tx.
   Uses the storage log as the temporal index: O(txns-in-range + datoms)."
  [db t]
  (->> (:log db)
       (filter #(> (first %) t))
       (mapcat second)
       (rebuild-indexes db)))


(extend-type InMemoryDaoDB
  IDaoDb
  (run-query       [db query inputs]      (native-q db query inputs))
  (transact        [db tx-data]           (native-transact db tx-data))
  (with            [db tx-data]           (native-with db tx-data))
  (index-datoms
    ([db index]             (native-datoms db index nil))
    ([db index components]  (native-datoms db index components)))
  (entity          [db eid]               (native-entity db eid))
  (pull            [db pattern eid]       (native-pull db pattern eid))
  (pull-many       [db pattern eids]      (native-pull-many db pattern eids))
  (basis-t         [db]                   (native-basis-t db))
  (as-of           [db t]                 (as-of db t))
  (since           [db t]                 (since db t))
  (entity-attrs    [db eid]               (native-entity-attrs db eid))
  (find-eids-by-av [db attr val]          (native-find-eids-by-av db attr val)))

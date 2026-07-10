(ns dao.space.transact
  "Stateless transaction processing library. Extracts tx-data into raw datoms
   by parsing entity maps, resolving tempids, enforcing uniqueness, and
   generating cardinality-one retractions using dao.space.query."
  (:require [clojure.string :as str]
            [dao.datom :as datom]
            [dao.space.query :as query]))


(defn- tempid?
  [x]
  (or (and (string? x) (str/starts-with? x "tid_")) (and (number? x) (neg? x))))


(defn- parse-op
  [m e a v default-op]
  (if (map? m)
    (let [{:keys [db/op], :as without-op} (dissoc m :db/op)
          op-kw (or op default-op)
          datom-m-val (get datom/reserved op-kw op-kw)]
      {:op op-kw,
       :e e,
       :a a,
       :v v,
       :m-raw datom-m-val,
       :m-map (when (seq without-op) without-op)})
    (let [op-kw (if (= m (:db/retract datom/reserved)) :db/retract :db/add)
          m-raw (or m (:db/assert datom/reserved))]
      {:op op-kw, :e e, :a a, :v v, :m-raw m-raw, :m-map nil})))


(defn- map->datoms
  "Convert an entity map into a seq of [e a v] or parsed ops."
  [m]
  (let [e (or (:db/id m) (str "tid_" (gensym)))]
    (mapcat (fn [[a v]]
              (if (and (or (sequential? v) (set? v))
                       (not (map? v))
                       (not= a :db/valueType)
                       (not= a :db/cardinality))
                (map #(parse-op nil e a % :db/add) v)
                [(parse-op nil e a v :db/add)]))
            (dissoc m :db/id))))


(defn- parse-tx-data
  "Normalize tx-data into a flat sequence of parsed ops."
  [tx-data]
  (mapcat (fn [item]
            (if (map? item)
              (map->datoms item)
              (let [[op e a v m] item] [(parse-op m e a v op)])))
          tx-data))


(defn- lookup-ident-eid
  "Resolve an ident keyword to its entity id."
  [base-datoms same-tx-ident->eid ident]
  (or (get same-tx-ident->eid ident)
      (let [res (query/match base-datoms ['_ :db/ident ident])]
        (when (seq res) (nth (first res) 0)))))


(defn- effective-ref-attrs-for-tx
  "Compute the set of :db.type/ref attributes, incorporating same-tx schema changes."
  [base-datoms parsed-ops same-tx-ident->eid]
  (let [eid->ident (into {}
                         (keep (fn [d]
                                 (when (= (nth d 1) :db/ident)
                                   [(nth d 0) (nth d 2)])))
                         base-datoms)
        eid->ident (into eid->ident
                         (keep (fn [{:keys [op e a v]}]
                                 (when (and (= op :db/add) (= a :db/ident))
                                   [e v])))
                         parsed-ops)
        existing-refs (into #{}
                            (keep (fn [d] (get eid->ident (nth d 0))))
                            (query/match base-datoms
                              ['_ :db/valueType :db.type/ref]))
        gaining (into #{}
                      (keep (fn [{:keys [op e a v]}]
                              (when (and (= op :db/add)
                                         (= a :db/valueType)
                                         (= v :db.type/ref))
                                (let [resolved-e (if (keyword? e)
                                                   (lookup-ident-eid
                                                     base-datoms
                                                     same-tx-ident->eid
                                                     e)
                                                   e)]
                                  (or (when (keyword? e) e)
                                      (get eid->ident resolved-e))))))
                      parsed-ops)
        losing (into #{}
                     (keep (fn [{:keys [op e a v]}]
                             (when (and (= op :db/retract)
                                        (= a :db/valueType)
                                        (= v :db.type/ref))
                               (let [resolved-e (if (keyword? e)
                                                  (lookup-ident-eid
                                                    base-datoms
                                                    same-tx-ident->eid
                                                    e)
                                                  e)]
                                 (or (when (keyword? e) e)
                                     (get eid->ident resolved-e))))))
                     parsed-ops)]
    (-> existing-refs
        (into gaining)
        (#(apply disj % losing)))))


(defn- expand-m-map
  "Expand inline metadata maps into additional datoms."
  [op eid t]
  (let [m-map (:m-map op)]
    (if m-map
      (let [m-datoms (mapcat
                       (fn [[a v]]
                         (if (and (or (sequential? v) (set? v)) (not (map? v)))
                           (map #(vector eid a % t (:db/assert datom/reserved))
                                v)
                           [[eid a v t (:db/assert datom/reserved)]]))
                       m-map)
            op' (assoc op :m-raw eid)]
        [op' m-datoms (inc eid)])
      [op [] eid])))


(defn- collect-tempids
  "Collect all tempids from parsed ops and extra datoms."
  [ops effective-ref-attrs extra-datoms]
  (let [op-tempids (mapcat (fn [{:keys [e a v m-raw]}]
                             (cond-> []
                               (tempid? e) (conj e)
                               (and (contains? effective-ref-attrs a)
                                    (tempid? v))
                               (conj v)
                               (tempid? m-raw) (conj m-raw)))
                           ops)
        extra-tempids (mapcat (fn [d]
                                (cond-> []
                                  (tempid? (nth d 0)) (conj (nth d 0))
                                  (and (contains? effective-ref-attrs (nth d 1))
                                       (tempid? (nth d 2)))
                                  (conj (nth d 2))
                                  (tempid? (nth d 4)) (conj (nth d 4))))
                              extra-datoms)]
    (into #{} (concat op-tempids extra-tempids))))


(defn- resolve-tempids
  "Assign sequential permanent entity IDs to tempids."
  [tempids start-eid]
  (let [sorted-tempids (sort (fn [a b]
                               (cond (and (number? a) (number? b)) (compare a b)
                                     (and (string? a) (string? b)) (compare a b)
                                     (number? a) -1
                                     :else 1))
                             tempids)]
    (zipmap sorted-tempids
            (range start-eid (+ start-eid (count sorted-tempids))))))


(defn- apply-tempid-map
  "Replace tempids with permanent entity IDs in parsed ops."
  [ops tempid-map effective-ref-attrs]
  (mapv (fn [op]
          (let [e' (if (tempid? (:e op))
                     (get tempid-map (:e op) (:e op))
                     (:e op))
                v' (if (and (contains? effective-ref-attrs (:a op))
                            (tempid? (:v op)))
                     (get tempid-map (:v op) (:v op))
                     (:v op))
                m-raw' (if (tempid? (:m-raw op))
                         (get tempid-map (:m-raw op) (:m-raw op))
                         (:m-raw op))]
            (assoc op
                   :e e'
                   :v v'
                   :m-raw m-raw')))
        ops))


(defn prepare-tx
  "Takes base-datoms and tx-data.
   Returns {:datoms [...] :tempids {...} :next-eid new-eid :next-t new-t}.
   Can optionally pass next-eid and next-t to avoid scanning."
  [{:keys [base-datoms tx-data next-t next-eid]}]
  (let [t (or next-t
              (inc (reduce (fn [mx d] (max mx (nth d 3))) 0 base-datoms)))
        base-eid (or next-eid
                     (reduce (fn [mx d]
                               (let [e (nth d 0)
                                     v (nth d 2)]
                                 (-> mx
                                     (max (if (number? e) (inc e) 0))
                                     (max (if (and (number? v)
                                                   (not= (nth d 1) :db/ident))
                                            (inc v)
                                            0)))))
                             1025
                             base-datoms))
        parsed-ops (parse-tx-data tx-data)
        same-tx-ident->eid
        (into {}
              (keep (fn [{:keys [op e a v]}]
                      (when (and (= op :db/add) (= a :db/ident)) [v e])))
              parsed-ops)
        effective-ref-attrs
        (effective-ref-attrs-for-tx base-datoms parsed-ops same-tx-ident->eid)
        alloc-base-eid
        (reduce (fn [eid {:keys [op e a v m-raw]}]
                  (let [mx (if (and (= op :db/add) (number? e) (>= e 1025))
                             (max eid (inc e))
                             eid)
                        mx (if (and (= op :db/add)
                                    (contains? effective-ref-attrs a)
                                    (number? v)
                                    (>= v 1025))
                             (max mx (inc v))
                             mx)
                        mx (if (and (= op :db/add)
                                    (number? m-raw)
                                    (>= m-raw 1025))
                             (max mx (inc m-raw))
                             mx)]
                    mx))
                base-eid
                parsed-ops)
        ops (mapv (fn [op]
                    (let [resolve (fn [id]
                                    (if (keyword? id)
                                      (or (lookup-ident-eid base-datoms
                                                            same-tx-ident->eid
                                                            id)
                                          (throw (ex-info "Unknown ident"
                                                          {:ident id})))
                                      id))
                          e' (resolve (:e op))
                          v' (if (contains? effective-ref-attrs (:a op))
                               (resolve (:v op))
                               (:v op))]
                      (assoc op
                             :e e'
                             :v v')))
                  parsed-ops)
        [ops extra-datoms next-eid-after-m]
        (reduce (fn [[acc-ops acc-extra eid] op]
                  (let [[exp extras new-eid] (expand-m-map op eid t)]
                    [(conj acc-ops exp) (into acc-extra extras) new-eid]))
                [[] [] alloc-base-eid]
                ops)
        tempids (collect-tempids ops effective-ref-attrs extra-datoms)
        tempid-map (resolve-tempids tempids next-eid-after-m)
        new-next-eid (+ next-eid-after-m (count tempids))
        ops (apply-tempid-map ops tempid-map effective-ref-attrs)
        extra-datoms (mapv (fn [d]
                             (if (contains? effective-ref-attrs (nth d 1))
                               (update d 2 #(get tempid-map % %))
                               d))
                           extra-datoms)
        ;; Cardinality-one retractions and unique constraints
        eid->ident (into {}
                         (keep (fn [d]
                                 (when (= (nth d 1) :db/ident)
                                   [(nth d 0) (nth d 2)])))
                         base-datoms)
        eid->ident (into eid->ident
                         (keep (fn [{:keys [op e a v]}]
                                 (when (and (= op :db/add) (= a :db/ident))
                                   [e v])))
                         ops)
        existing-card-many (into #{}
                                 (keep (fn [d] (get eid->ident (nth d 0))))
                                 (query/match base-datoms
                                   ['_ :db/cardinality :db.cardinality/many]))
        gaining-card-many (into #{}
                                (keep (fn [{:keys [op e a v]}]
                                        (when (and (= op :db/add)
                                                   (= a :db/cardinality)
                                                   (= v :db.cardinality/many))
                                          (get eid->ident e))))
                                ops)
        losing-card-many (into #{}
                               (keep (fn [{:keys [op e a v]}]
                                       (when (and (= op :db/retract)
                                                  (= a :db/cardinality)
                                                  (= v :db.cardinality/many))
                                         (get eid->ident e))))
                               ops)
        effective-card-many (-> existing-card-many
                                (into gaining-card-many)
                                (#(apply disj % losing-card-many)))
        existing-unique (into #{}
                              (keep (fn [d] (get eid->ident (nth d 0))))
                              (query/match base-datoms ['_ :db/unique '_]))
        gaining-unique (into #{}
                             (keep (fn [{:keys [op e a]}]
                                     (when (and (= op :db/add) (= a :db/unique))
                                       (get eid->ident e))))
                             ops)
        losing-unique (into #{}
                            (keep (fn [{:keys [op e a]}]
                                    (when (and (= op :db/retract)
                                               (= a :db/unique))
                                      (get eid->ident e))))
                            ops)
        effective-unique (-> existing-unique
                             (into gaining-unique)
                             (#(apply disj % losing-unique)))
        ;; Group added eavs to find implicit retractions
        added-ops (filter #(= (:op %) :db/add) ops)
        ;; Unique constraint check
        _ (let [tx-av-eids (reduce (fn [m {:keys [e a v]}]
                                     (if (contains? effective-unique a)
                                       (update m [a v] (fnil conj #{}) e)
                                       m))
                                   {}
                                   added-ops)]
            (doseq [[[a v] eids] tx-av-eids]
              (when (> (count eids) 1)
                (throw (ex-info "Unique constraint violated"
                                {:attr a, :val v})))
              (let [existing (query/match base-datoms ['_ a v])]
                (when (seq existing)
                  (let [existing-eid (nth (first existing) 0)]
                    ;; if it's the same entity, it's an update. If it's
                    ;; different, collision! Wait, if the transaction
                    ;; explicitly retracts the existing one, we should
                    ;; allow it. We ignore that for this simplified
                    ;; version, or we can check retracts.
                    (when (not= existing-eid (first eids))
                      (let [retracting? (some (fn [op]
                                                (and (= (:op op) :db/retract)
                                                     (= (:a op) a)
                                                     (= (:v op) v)
                                                     (= (:e op) existing-eid)))
                                              ops)]
                        (when-not retracting?
                          (throw (ex-info "Unique constraint violated"
                                          {:attr a,
                                           :val v,
                                           :existing-eid existing-eid}))))))))))
        ;; Implicit card-one retractions
        seen-card-one (atom #{})
        retractions
        (mapcat (fn [{:keys [op e a]}]
                  (if (and (= op :db/add)
                           (not (contains? effective-card-many a)))
                    (let [key [e a]]
                      (if (contains? @seen-card-one key)
                        []
                        (do (swap! seen-card-one conj key)
                            (let [existing (query/match base-datoms [e a '_])]
                              (map (fn [d]
                                     [e a (nth d 2) t
                                      (:db/retract datom/reserved)])
                                   existing)))))
                    []))
                added-ops)
        ops-datoms (mapv (fn [{:keys [e a v m-raw]}] [e a v t m-raw]) ops)
        all-datoms (vec (concat retractions ops-datoms extra-datoms))]
    {:datoms all-datoms,
     :tempids tempid-map,
     :next-t (inc t),
     :next-eid new-next-eid}))

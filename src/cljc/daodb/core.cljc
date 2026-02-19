(ns daodb.core
  (:require
    [daodb.index :as index]
    [daodb.index.structural :as structural]
    [daodb.index.temporal :as temporal]
    [daodb.primitives :as p]))


(defrecord DaoDB
  [indexes])


(defn create-db
  []
  (->DaoDB {:temporal (temporal/create), :structural (structural/create)}))


(defn transact
  [db datoms]
  "Accepts a sequence of raw datoms.
   Broadcasts the transaction to ALL registered indexes."
  (let [datom-recs (mapv (fn [d]
                           (let [{:keys [e a v t m]}
                                 (if (map? d) d (zipmap [:e :a :v :t :m] d))]
                             (p/->datom e a v t m)))
                         datoms)
        updated-indexes (reduce-kv (fn [idxs k idx]
                                     (assoc idxs
                                            k (index/index-tx idx datom-recs)))
                                   {}
                                   (:indexes db))]
    (assoc db :indexes updated-indexes)))


(defn q-temporal
  [db criteria]
  (temporal/range-seek (get-in db [:indexes :temporal]) criteria))


(defn get-by-hash
  [db h]
  (structural/get-content (get-in db [:indexes :structural]) h))


(defn get-history-of-hash
  [db h]
  (structural/get-meta (get-in db [:indexes :structural]) h))

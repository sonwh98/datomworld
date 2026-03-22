(ns dao.db.datascript
  "DataScript-backed DaoDB implementation."
  (:require
    [dao.db :refer [IDaoDb] :as dao]
    [datascript.core :as d]
    #?@(:cljs [[datascript.db :as ds-db]
               [datascript.query :as dq]])))


#?(:cljs
   ;; Fix DataScript query under Closure advanced compilation.
   (let [original-lookup dq/lookup-pattern-db
         prop->idx {"e" 0, "a" 1, "v" 2, "tx" 3}]
     (set! dq/lookup-pattern-db
           (fn [context db pattern]
             (let [rel (original-lookup context db pattern)
                   tuples (:tuples rel)]
               (if (and (seq tuples) (instance? ds-db/Datom (first tuples)))
                 (let [new-attrs (reduce-kv (fn [m k v]
                                              (assoc m k (get prop->idx v v)))
                                            {}
                                            (:attrs rel))
                       new-tuples (mapv (fn [d]
                                          (to-array [(nth d 0) (nth d 1) (nth d 2)
                                                     (nth d 3) (nth d 4)]))
                                        tuples)]
                   (dq/->Relation new-attrs new-tuples))
                 rel))))))


(defn- schema->card-many
  "Extract set of cardinality-many attribute names from a DataScript schema."
  [schema]
  (->> schema
       (keep (fn [[k v]] (when (= :db.cardinality/many (:db/cardinality v)) k)))
       set))


(defrecord DaoDbDataScript
  [ds-db])


(defn datascript-q
  [db query inputs]
  (apply d/q query (:ds-db db) inputs))


(defn datascript-transact
  [db tx-data]
  (let [ds-db  (:ds-db db)
        conn   (d/conn-from-db ds-db)
        result (d/transact! conn tx-data)
        new-db (->DaoDbDataScript @conn)]
    {:db       new-db
     :db-after new-db
     :db-before db
     :tx-data  (:tx-data result)
     :tempids  (:tempids result)}))


(defn datascript-with
  [db tx-data]
  (let [result (d/with (:ds-db db) tx-data)
        new-db (->DaoDbDataScript (:db-after result))]
    {:db       new-db
     :db-after new-db
     :db-before db
     :tx-data  (:tx-data result)
     :tempids  {}}))


(defn datascript-datoms
  [db index components]
  (if components
    (apply d/datoms (:ds-db db) index components)
    (d/datoms (:ds-db db) index)))


(defn datascript-entity
  [db eid]
  (d/entity (:ds-db db) eid))


(defn datascript-pull
  [db pattern eid]
  (d/pull (:ds-db db) pattern eid))


(defn datascript-pull-many
  [db pattern eids]
  (d/pull-many (:ds-db db) pattern eids))


(defn basis-t
  [db]
  (:max-tx (:ds-db db)))


(defn as-of
  [db t]
  (->DaoDbDataScript
    (d/filter (:ds-db db) (fn [_ datom] (<= (nth datom 3) t)))))


(defn since
  [db t]
  (->DaoDbDataScript
    (d/filter (:ds-db db) (fn [_ datom] (> (nth datom 3) t)))))


(defn datascript-entity-attrs
  [db eid]
  (let [ds-db      (:ds-db db)
        card-many  (schema->card-many (:schema ds-db))]
    (reduce (fn [m datom]
              (let [a (nth datom 1)
                    v (nth datom 2)]
                (if (contains? card-many a)
                  (update m a (fnil conj []) v)
                  (assoc m a v))))
            {}
            (d/datoms ds-db :eavt eid))))


(defn datascript-find-eids-by-av
  [db attr val]
  (->> (d/q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] (:ds-db db) attr val)
       (map first)
       set))


(defn create
  "Create an empty DaoDb backed by DataScript."
  [schema]
  (->DaoDbDataScript (d/empty-db schema)))


(defn from-tx-data
  "Create a DataScript-backed DaoDb from schema + tx-data."
  [schema tx-data]
  (dao/transact (create schema) tx-data))


(extend-type DaoDbDataScript
  IDaoDb
  (run-query       [db query inputs]      (datascript-q db query inputs))
  (transact        [db tx-data]           (datascript-transact db tx-data))
  (with            [db tx-data]           (datascript-with db tx-data))
  (index-datoms
    ([db index]             (datascript-datoms db index nil))
    ([db index components]  (datascript-datoms db index components)))
  (entity          [db eid]               (datascript-entity db eid))
  (pull            [db pattern eid]       (datascript-pull db pattern eid))
  (pull-many       [db pattern eids]      (datascript-pull-many db pattern eids))
  (basis-t         [db]                   (basis-t db))
  (as-of           [db t]                 (as-of db t))
  (since           [db t]                 (since db t))
  (entity-attrs    [db eid]               (datascript-entity-attrs db eid))
  (find-eids-by-av [db attr val]          (datascript-find-eids-by-av db attr val)))

(ns dao.db
  "DaoDB: immutable database as a value.
   Independent of yin/yang. Currently backed by DataScript,
   but the protocol allows future implementations."
  (:require [datascript.core :as d]))


(defprotocol IDaoDb
  (transact [db tx-data]
    "Transact tx-data, return {:db <new-DaoDb> :tempids ...}.")
  (q [db query inputs]
    "Run a Datalog query.")
  (datoms [db index]
    "Return datoms for index (:eavt/:aevt/:avet/:vaet).")
  (entity-attrs [db eid]
    "Return map of {attr val} for entity. Cardinality-many attrs are vectors.")
  (find-eids-by-av [db attr val]
    "Return set of entity IDs where attribute = val."))


(defn- schema->card-many
  "Extract set of cardinality-many attribute names from a DataScript schema."
  [schema]
  (->> schema
       (keep (fn [[k v]] (when (= :db.cardinality/many (:db/cardinality v)) k)))
       set))


(defn- schema->ref-attrs
  "Extract set of ref-typed attribute names from a DataScript schema."
  [schema]
  (->> schema
       (keep (fn [[k v]] (when (= :db.type/ref (:db/valueType v)) k)))
       set))


(defrecord DaoDb [ds-db]
  IDaoDb
    (transact [_ tx-data]
      (let [conn (d/conn-from-db ds-db)
            {:keys [tempids]} (d/transact! conn tx-data)]
        {:db (DaoDb. @conn), :tempids tempids}))
    (q [_ query inputs] (apply d/q query ds-db inputs))
    (datoms [_ index] (d/datoms ds-db index))
    (entity-attrs [_ eid]
      (let [card-many (schema->card-many (:schema ds-db))]
        (reduce (fn [m datom]
                  (let [a (nth datom 1)
                        v (nth datom 2)]
                    (if (contains? card-many a)
                      (update m a (fnil conj []) v)
                      (assoc m a v))))
          {}
          (d/datoms ds-db :eavt eid))))
    (find-eids-by-av [_ attr val]
      (->> (d/q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] ds-db attr val)
           (map first)
           set)))


(defn create
  "Create an empty DaoDb from a schema."
  [schema]
  (->DaoDb (d/empty-db schema)))


(defn from-tx-data
  "Create a DaoDb from schema + tx-data. Returns {:db <DaoDb> :tempids ...}."
  [schema tx-data]
  (transact (create schema) tx-data))


;; =============================================================================
;; DummyDaoDb: portable implementation (no DataScript dependency)
;; =============================================================================

(defrecord DummyDaoDb [datom-vec entity-index ref-attrs card-many next-eid]
  IDaoDb
    (transact [_ tx-data]
      (let [;; Collect all negative tempids from entities and ref-valued
            ;; attrs
            all-tempids (->> tx-data
                             (mapcat (fn [[_op e a v]]
                                       (cond-> []
                                         (neg-int? e) (conj e)
                                         (and (contains? ref-attrs a)
                                              (integer? v)
                                              (neg-int? v))
                                           (conj v))))
                             distinct
                             sort)
            tempid-map (zipmap all-tempids
                               (range next-eid
                                      (+ next-eid (count all-tempids))))
            resolve-id (fn [id] (if (neg-int? id) (get tempid-map id id) id))
            ;; Build resolved datoms [e a v tx]
            new-datoms (mapv (fn [[_op e a v]] [(resolve-id e) a
                                                (if (and (contains? ref-attrs a)
                                                         (integer? v))
                                                  (resolve-id v)
                                                  v) 0])
                         tx-data)
            all-datoms (into datom-vec new-datoms)
            new-index (group-by first all-datoms)]
        {:db (DummyDaoDb. all-datoms
                          new-index
                          ref-attrs
                          card-many
                          (+ next-eid (count all-tempids))),
         :tempids tempid-map}))
    (q [_ _query _inputs]
      (throw (ex-info "DummyDaoDb does not support Datalog queries" {})))
    (datoms [_ _index] datom-vec)
    (entity-attrs [_ eid]
      (reduce (fn [m [_e a v _tx]]
                (if (contains? card-many a)
                  (update m a (fnil conj []) v)
                  (assoc m a v)))
        {}
        (get entity-index eid)))
    (find-eids-by-av [_ attr val]
      (->> datom-vec
           (filter (fn [[_e a v _tx]] (and (= a attr) (= v val))))
           (map first)
           set)))


(defn create-dummy
  "Create an empty DummyDaoDb from a schema. Pure Clojure, no DataScript."
  [schema]
  (->DummyDaoDb [] {} (schema->ref-attrs schema) (schema->card-many schema) 1))

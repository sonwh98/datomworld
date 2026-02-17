(ns dao.db
  "DaoDB: immutable database as a value.
   Independent of yin/yang. Currently backed by DataScript,
   but the protocol allows future implementations."
  (:require [datascript.core :as d]))


(defprotocol IDaoDb
  (transact [db tx-data]
    "Transact tx-data, return {:db <new-DaoDb> :tempids ...}.")
  (q [db query & inputs]
    "Run a Datalog query.")
  (datoms [db index]
    "Return datoms for index (:eavt/:aevt/:avet/:vaet)."))


(defrecord DaoDb [ds-db]
  IDaoDb
    (transact [_ tx-data]
      (let [conn (d/conn-from-db ds-db)
            {:keys [tempids]} (d/transact! conn tx-data)]
        {:db (->DaoDb @conn), :tempids tempids}))
    (q [_ query & inputs] (apply d/q query ds-db inputs))
    (datoms [_ index] (d/datoms ds-db index)))


(defn create
  "Create an empty DaoDb from a schema."
  [schema]
  (->DaoDb (d/empty-db schema)))


(defn from-tx-data
  "Create a DaoDb from schema + tx-data. Returns {:db <DaoDb> :tempids ...}."
  [schema tx-data]
  (transact (create schema) tx-data))

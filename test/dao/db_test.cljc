(ns dao.db-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as dao.db]
    [dao.db.datascript :as ds-db]))


(deftest datascript-backed-generic-api-test
  (testing "DataScript backend satisfies dao.db protocol and generic API"
    (let [schema {:name {}}
          db     (ds-db/create schema)
          {:keys [db-after]} (ds-db/from-tx-data schema [[:db/add 1025 :name "Alice"]])]
      (is (< (dao.db/basis-t db) (dao.db/basis-t db-after)))
      (is (= #{[1025]}
             (dao.db/q '[:find ?e :where [?e :name "Alice"]] db-after)))
      (is (= "Alice"
             (:name (dao.db/entity-attrs db-after 1025)))))))

(ns dao.db-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as dao.db]
    [dao.db.datascript :as ds-db]))


(deftest bootstrap-datoms-test
  (testing "dao.db exposes shared bootstrap system datoms"
    (let [derived (first (filter #(and (= 1 (:e %))
                                       (= :db/ident (:a %)))
                                 dao.db/bootstrap-datoms))]
      (is (= 13 (count dao.db/bootstrap-datoms)))
      (is (= :db/derived (:v derived))))))


(deftest datom-constructor-test
  (testing "dao.db owns the Datom constructor"
    (is (= {:e 1, :a :name, :v "Alice", :t 2, :m 0}
           (select-keys (dao.db/->datom 1 :name "Alice" 2 0)
                        [:e :a :v :t :m])))))


(deftest datoms-api-arity-test
  (testing "dao.db/datoms keeps Datomic-compatible arities"
    (let [schema {:name {}}
          {:keys [db-after]} (ds-db/from-tx-data schema [[:db/add 1025 :name "Alice"]])
          tuple (fn [d] [(nth d 0) (nth d 1) (nth d 2)])]
      (is (some #(= [1025 :name "Alice"] (tuple %))
                (dao.db/datoms db-after :eavt)))
      (is (= [[1025 :name "Alice"]]
             (mapv tuple (dao.db/datoms db-after :eavt 1025 :name)))))))


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


(deftest datascript-with-tempids-test
  (testing "DataScript-backed with returns tempids"
    (let [db       (ds-db/create {:name {}})
          result   (dao.db/with db [[:db/add -1 :name "Alice"]])
          alice-id (get (:tempids result) -1)]
      (is (pos? alice-id))
      (is (= "Alice"
             (:name (dao.db/entity-attrs (:db-after result) alice-id)))))))

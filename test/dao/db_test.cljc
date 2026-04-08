(ns dao.db-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as dao.db]
    #?(:clj [dao.db.datascript :as ds-db]
       :cljs [dao.db.datascript :as ds-db])
    [dao.db.in-memory :as in-m]))


(defn- portable-from-tx-data
  [schema tx-data]
  #?(:cljd (dao.db/transact (in-m/create schema) tx-data)
     :default (ds-db/from-tx-data schema tx-data)))


(defn- datom-triple
  [d]
  [(:e d) (:a d) (:v d)])


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
          {:keys [db-after]} (portable-from-tx-data schema [[:db/add 1025 :name "Alice"]])
          tuples (map datom-triple (dao.db/datoms db-after :eavt))]
      (is (some #(= [1025 :name "Alice"] %)
                tuples))
      (is (= [[1025 :name "Alice"]]
             (mapv datom-triple (dao.db/datoms db-after :eavt 1025 :name)))))))


#?(:cljd
   (deftest datascript-backed-generic-api-test
     (testing "DataScript backend is unavailable on CLJD"
       (is true)))
   :default
   (deftest datascript-backed-generic-api-test
     (testing "DataScript backend satisfies dao.db protocol and generic API"
       (let [schema {:name {}}
             db     (ds-db/create schema)
             {:keys [db-after]} (ds-db/from-tx-data schema [[:db/add 1025 :name "Alice"]])]
         (is (< (dao.db/basis-t db) (dao.db/basis-t db-after)))
         (is (= #{[1025]}
                (dao.db/q '[:find ?e :where [?e :name "Alice"]] db-after)))
         (is (= "Alice"
                (:name (dao.db/entity-attrs db-after 1025))))))))


#?(:cljd
   (deftest datascript-with-tempids-test
     (testing "DataScript backend is unavailable on CLJD"
       (is true)))
   :default
   (deftest datascript-with-tempids-test
     (testing "DataScript-backed with returns tempids"
       (let [db       (ds-db/create {:name {}})
             result   (dao.db/with db [[:db/add -1 :name "Alice"]])
             alice-id (get (:tempids result) -1)]
         (is (pos? alice-id))
         (is (= "Alice"
                (:name (dao.db/entity-attrs (:db-after result) alice-id))))))))

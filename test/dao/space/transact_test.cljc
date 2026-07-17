(ns dao.space.transact-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.transact :as transact]
            [dao.datom :as datom]))


(deftest transact-test
  (testing "tempids are resolved sequentially"
    (let [res (transact/prepare-tx {:base-datoms [],
                                    :tx-data [{:db/id "tid_1", :name "Alice"}
                                              {:db/id "tid_2", :name "Bob"}],
                                    :next-t 1,
                                    :next-eid 1025})]
      (is (= 2 (count (:tempids res))))
      (is (= 1025 (get (:tempids res) "tid_1")))
      (is (= 1026 (get (:tempids res) "tid_2")))
      (is (= 2 (count (:datoms res))))
      (is (= [1025 :name "Alice" 1 1] (first (:datoms res))))))
  (testing "cardinality-one retractions"
    (let [base-datoms [[1 :color "red" 1 1]]
          ;; assume :color is card-one because it's not in base-datoms as
          ;; card-many
          res (transact/prepare-tx {:base-datoms base-datoms,
                                    :tx-data [[:db/add 1 :color "blue"]],
                                    :next-t 2,
                                    :next-eid 1025})]
      (is (= 2 (count (:datoms res))))
      ;; should contain retraction for "red" and assertion for "blue"
      (is (= #{[1 :color "red" 2 0] [1 :color "blue" 2 1]}
             (set (:datoms res))))))
  (testing "idents are resolved when attr is ref"
    (let [base-datoms [[10 :db/ident :status/active 1 1]
                       [20 :db/valueType :db.type/ref 1 1]
                       [20 :db/ident :status 1 1]]
          res (transact/prepare-tx {:base-datoms base-datoms,
                                    :tx-data [[:db/add "tid_1" :status
                                               :status/active]],
                                    :next-t 2,
                                    :next-eid 1025})]
      (is (= 1025 (get (:tempids res) "tid_1")))
      (is (= [1025 :status 10 2 1] (first (:datoms res)))))))

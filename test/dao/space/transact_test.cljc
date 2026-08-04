(ns dao.space.transact-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.space.transact :as transact]))


(deftest allocation-floor-is-first-user-id
  (testing
    "with no :next-eid supplied, allocation starts at datom/first-user-id.
            Every other test in this namespace passes :next-eid explicitly, so
            the default floor is otherwise uncovered — and it is the value the
            reserved range (docs/agents/datom-spec.md, Reserved Entities) pins."
    (let [res (transact/prepare-tx
                {:base-datoms [], :tx-data [{:db/id "tid_1", :name "Alice"}]})]
      (is (= datom/first-user-id (get (:tempids res) "tid_1")))
      (is (= [datom/first-user-id :name "Alice" 1 1] (first (:datoms res))))))
  (testing "an existing user-space id advances the floor past itself"
    ;; 20 is user space under a 16 floor and was reserved under the old
    ;; 1025
    ;; one, so this fails if the boundary is still hardcoded high.
    (let [res (transact/prepare-tx {:base-datoms [[20 :name "Bob" 0 1]],
                                    :tx-data [{:db/id "tid_1",
                                               :name "Alice"}]})]
      (is (= 21 (get (:tempids res) "tid_1")))))
  (testing "reserved ids never advance the floor"
    (let [res (transact/prepare-tx {:base-datoms [[2 :name "Bob" 0 1]],
                                    :tx-data [{:db/id "tid_1",
                                               :name "Alice"}]})]
      (is (= datom/first-user-id (get (:tempids res) "tid_1")))))
  (testing
    "exactly at the edge: the last reserved id does not advance the floor,
            the first user id does. An off-by-one in the guard survives the
            2-vs-20 cases above but not these."
    (let [last-reserved (dec datom/first-user-id)
          below (transact/prepare-tx
                  {:base-datoms [[last-reserved :name "X" 0 1]],
                   :tx-data [{:db/id "tid_1", :name "Alice"}]})
          at (transact/prepare-tx {:base-datoms [[datom/first-user-id :name "X"
                                                  0 1]],
                                   :tx-data [{:db/id "tid_1", :name "Alice"}]})]
      (is (= datom/first-user-id (get (:tempids below) "tid_1")))
      (is (= (inc datom/first-user-id) (get (:tempids at) "tid_1"))))))


(deftest tempids-sequential-resolution-test
  (testing "tempids are resolved sequentially starting from next-eid"
    (let [res (transact/prepare-tx {:base-datoms [],
                                    :tx-data [{:db/id "tid_1", :name "Alice"}
                                              {:db/id "tid_2", :name "Bob"}],
                                    :next-t 1,
                                    :next-eid datom/first-user-id})]
      (is (= 2 (count (:tempids res))))
      (is (= datom/first-user-id (get (:tempids res) "tid_1")))
      (is (= (inc datom/first-user-id) (get (:tempids res) "tid_2")))
      (is (= 2 (count (:datoms res))))
      (is (= [datom/first-user-id :name "Alice" 1 1] (first (:datoms res)))))))


(deftest cardinality-one-retractions-test
  (testing "cardinality-one retractions automatically retract previous values"
    (let [base-datoms [[1 :color "red" 1 1]]
          res (transact/prepare-tx {:base-datoms base-datoms,
                                    :tx-data [[:db/add 1 :color "blue"]],
                                    :next-t 2,
                                    :next-eid datom/first-user-id})]
      (is (= 2 (count (:datoms res))))
      (is (= #{[1 :color "red" 2 0] [1 :color "blue" 2 1]}
             (set (:datoms res)))))))


(deftest ident-ref-resolution-test
  (testing "idents are resolved when attr is ref"
    (let [base-datoms [[10 :db/ident :status/active 1 1]
                       [20 :db/valueType :db.type/ref 1 1]
                       [20 :db/ident :status 1 1]]
          res (transact/prepare-tx {:base-datoms base-datoms,
                                    :tx-data [[:db/add "tid_1" :status
                                               :status/active]],
                                    :next-t 2,
                                    :next-eid datom/first-user-id})]
      (is (= datom/first-user-id (get (:tempids res) "tid_1")))
      (is (= [datom/first-user-id :status 10 2 1] (first (:datoms res)))))))


(deftest unique-constraint-violation-test
  (testing "transact throws when unique attribute values collide"
    (let [base-datoms [[10 :db/unique :db.unique/identity 1 1]
                       [10 :db/ident :user/email 1 1]
                       [100 :user/email "alice@example.com" 1 1]]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"Unique constraint violated"
            (transact/prepare-tx
              {:base-datoms base-datoms,
               :tx-data [{:db/id "tid_new",
                          :user/email "alice@example.com"}],
               :next-t 2,
               :next-eid datom/first-user-id}))))))


(deftest intra-tx-unique-constraint-violation-test
  (testing
    "transact throws when two new entities in the same tx claim the same
     unique value, even with no prior conflicting base-datom"
    (let [base-datoms [[10 :db/unique :db.unique/identity 1 1]
                       [10 :db/ident :user/email 1 1]]]
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"Unique constraint violated"
            (transact/prepare-tx
              {:base-datoms base-datoms,
               :tx-data [{:db/id "tid_a", :user/email "dup@example.com"}
                         {:db/id "tid_b", :user/email "dup@example.com"}],
               :next-t 2,
               :next-eid datom/first-user-id}))))))


(deftest unknown-ident-throws-test
  (testing "transact throws when an unknown ident keyword is referenced"
    (let [base-datoms [[20 :db/valueType :db.type/ref 1 1]
                       [20 :db/ident :status 1 1]]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"Unknown ident"
            (transact/prepare-tx
              {:base-datoms base-datoms,
               :tx-data [[:db/add 1 :status
                          :status/nonexistent]],
               :next-t 2,
               :next-eid datom/first-user-id}))))))

(ns dao.db.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db.core :as d]
    [dao.db.primitives :as p]))


(deftest dual-index-test
  (testing "Unitary Input creates Dual State"
    (let [db (d/create-db)
          ;; Transact a fact: Entity :e1 has attribute :age value 42 at
          ;; time 100
          db-1 (d/transact db [[:e1 :age 42 100 {:src "test"}]])]
      (testing "Temporal Index (Stream)"
        (let [res (d/q-temporal db-1 {:e :e1})]
          (is (= 1 (count res)))
          (is (= 42 (:v (first res))))
          (is (= 100 (:t (first res))))))
      (testing "Structural Lattice (Materialized)"
        (let [h (p/hash-datom-content :e1 :age 42)
              content (d/get-by-hash db-1 h)
              history (d/get-history-of-hash db-1 h)]
          (is (some? content) "Content should be materialized")
          (is (= [:e1 :age 42] content) "Content should match input")
          (is (= 1 (count history)) "Should have 1 history entry")
          (is (= 100 (:t (first history))))
          (is (= {:src "test"} (:m (first history))))))))
  (testing "Deduplication of Content"
    (let [db (d/create-db)
          ;; Transact same fact twice at different times
          db-2 (d/transact db
                           [[:e1 :age 42 100 {:src "v1"}]
                            [:e1 :age 42 200 {:src "v2"}]])]
      (testing "Temporal Index grows linearly"
        (is (= 2 (count (d/q-temporal db-2 {:e :e1})))))
      (testing "Structural Lattice deduplicates content"
        (let [h (p/hash-datom-content :e1 :age 42)
              history (d/get-history-of-hash db-2 h)]
          (is (= 2 (count history)) "History should accumulate context")
          (is (= #{100 200} (set (map :t history)))))))))


(deftest merkle-identity-test
  (testing "AST Node Merkle Identity"
    (let [ast-node {:op :apply, :args [:a :b]}
          id (p/merkle-id-for-node ast-node)]
      (is (string? id))
      (is (seq id))
      (testing "Deterministic ID"
        (is (= id (p/merkle-id-for-node {:args [:a :b], :op :apply})))))))

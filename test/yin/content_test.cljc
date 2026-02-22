(ns yin.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.content :as content]
            [yin.vm :as vm]))


;; =============================================================================
;; Content Addressing Tests
;; =============================================================================

(deftest gauge-invariance-test
  (testing "Same AST with different tempid ranges produces same root hash"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          datoms-a (vm/ast->datoms ast {:id-start -1024})
          datoms-b (vm/ast->datoms ast {:id-start -2048})
          hashes-a (content/compute-content-hashes datoms-a)
          hashes-b (content/compute-content-hashes datoms-b)
          root-a (apply max (keys hashes-a))
          root-b (apply max (keys hashes-b))]
      (is (= (get hashes-a root-a) (get hashes-b root-b))
          "Root hash should be identical regardless of tempid range"))))


(deftest operand-order-test
  (testing "(+ 1 2) and (+ 2 1) produce different hashes"
    (let [ast-12 {:type :application,
                  :operator {:type :variable, :name '+},
                  :operands [{:type :literal, :value 1}
                             {:type :literal, :value 2}]}
          ast-21 {:type :application,
                  :operator {:type :variable, :name '+},
                  :operands [{:type :literal, :value 2}
                             {:type :literal, :value 1}]}
          hashes-12 (content/compute-content-hashes (vm/ast->datoms ast-12))
          hashes-21 (content/compute-content-hashes (vm/ast->datoms ast-21))
          root-12 (get hashes-12 (apply max (keys hashes-12)))
          root-21 (get hashes-21 (apply max (keys hashes-21)))]
      (is (not= root-12 root-21) "Operand order matters: (+ 1 2) != (+ 2 1)"))))


(deftest variable-names-test
  (testing "(lambda [x] x) and (lambda [y] y) produce different hashes"
    (let [ast-x {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}
          ast-y {:type :lambda, :params ['y], :body {:type :variable, :name 'y}}
          hashes-x (content/compute-content-hashes (vm/ast->datoms ast-x))
          hashes-y (content/compute-content-hashes (vm/ast->datoms ast-y))
          root-x (get hashes-x (apply max (keys hashes-x)))
          root-y (get hashes-y (apply max (keys hashes-y)))]
      (is (not= root-x root-y) "Variable names are included in hash"))))


(deftest nested-hash-embedding-test
  (testing "Nested (+ 1 (+ 2 3)): inner subtree hash embedded in outer"
    (let [inner {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value 2}
                            {:type :literal, :value 3}]}
          outer {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value 1} inner]}
          inner-datoms (vm/ast->datoms inner)
          outer-datoms (vm/ast->datoms outer)
          inner-hashes (content/compute-content-hashes inner-datoms)
          outer-hashes (content/compute-content-hashes outer-datoms)
          inner-root-hash (get inner-hashes (apply max (keys inner-hashes)))
          ;; Find the inner subtree in the outer AST
          ;; The inner subtree should have the same hash
          outer-hash-vals (set (vals outer-hashes))]
      (is (contains? outer-hash-vals inner-root-hash)
          "Inner subtree hash appears in outer AST hashes"))))


(deftest annotate-datoms-test
  (testing "annotate-datoms produces one :yin/content-hash per entity with m=1"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          datoms (vm/ast->datoms ast)
          annotated (content/annotate-datoms datoms)
          entity-count (count (group-by first datoms))
          hash-datoms (filter (fn [[_e a _v _t _m]] (= a :yin/content-hash))
                        annotated)]
      (is (= entity-count (count hash-datoms))
          "One content-hash datom per entity")
      (is (every? (fn [[_e _a _v _t m]] (= 1 m)) hash-datoms)
          "All content-hash datoms have m=1 (derived)")
      (is (every? (fn [[_e _a v _t _m]]
                    (clojure.string/starts-with? v "sha256:"))
                  hash-datoms)
          "All hashes start with sha256: prefix"))))


(deftest derived-datoms-excluded-test
  (testing "Derived datoms (m=1) are excluded from hash computation"
    (let [ast {:type :literal, :value 42}
          datoms (vm/ast->datoms ast)
          hash-before (content/compute-content-hashes datoms)
          ;; Add a derived datom with m=1
          datoms-with-derived
            (conj datoms [(ffirst datoms) :yin/content-hash "sha256:fake" 0 1])
          hash-after (content/compute-content-hashes datoms-with-derived)]
      (is (= hash-before hash-after)
          "Adding derived datoms does not change content hashes"))))


(deftest literal-hash-test
  (testing "Same literal produces same hash"
    (let [datoms-a (vm/ast->datoms {:type :literal, :value 42} {:id-start -100})
          datoms-b (vm/ast->datoms {:type :literal, :value 42} {:id-start -200})
          hash-a (get (content/compute-content-hashes datoms-a)
                      (apply max (map first datoms-a)))
          hash-b (get (content/compute-content-hashes datoms-b)
                      (apply max (map first datoms-b)))]
      (is (= hash-a hash-b))))
  (testing "Different literals produce different hashes"
    (let [datoms-a (vm/ast->datoms {:type :literal, :value 42})
          datoms-b (vm/ast->datoms {:type :literal, :value 99})
          hash-a (get (content/compute-content-hashes datoms-a)
                      (apply max (map first datoms-a)))
          hash-b (get (content/compute-content-hashes datoms-b)
                      (apply max (map first datoms-b)))]
      (is (not= hash-a hash-b)))))

(ns dao.space.index-numeric-test
  "dao.space.index orders numbers by exact value across every Jing numeric
   kind and carrier (docs/design/dao.jing.cbor.md, Numeric identity):
   compare-vals's numeric arm is dao.jing.cbor/num-compare, with no string
   fallback, and ties across kinds stay ties, so [e a 1 t m] and
   [e a 1.0 t m] are one covered-index entry."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.datom :as datom]
            [dao.jing.cbor :as cbor]
            [dao.space.index :as index])
  #?@(:cljd [(:import ["dart:core" BigInt])]))


(defn- big
  [s]
  #?(:cljd (BigInt.parse s) :clj (bigint s) :cljs (js/BigInt s)))


(def neg-inf (cbor/float64-from-bits "fff0000000000000"))
(def pos-inf (cbor/float64-from-bits "7ff0000000000000"))
(def nan (cbor/float64-from-bits "7ff8000000000000"))
(def neg-zero (cbor/float64-from-bits "8000000000000000"))


(def ladder
  "Strictly increasing by exact value; every kind and carrier appears."
  [neg-inf
   (cbor/float64 -1e300)
   (big "-100000000000000000000")
   (cbor/decimal -1 -15)
   (cbor/ratio -1 2)
   0
   (cbor/ratio 1 10)
   (cbor/float64 0.1)
   (cbor/decimal -1 2)
   1
   (cbor/float64 1.5)
   (cbor/ratio 7 4)
   (big "9007199254740992")
   (big "9007199254740993")
   (cbor/float64 1e300)
   pos-inf
   nan])


(deftest numbers-order-by-exact-value-across-kinds
  (doseq [[i a] (map-indexed vector ladder)
          [j b] (map-indexed vector ladder)]
    (is (= (compare i j) (index/compare-vals a b)) (pr-str [a b])))
  (testing "no rounding through double"
    (is (= 1 (index/compare-vals (cbor/float64 0.1) (cbor/ratio 1 10))))
    (is (= 1 (index/compare-vals (big "9007199254740993")
                                 (cbor/float64 9007199254740992))))
    (is (= -1 (index/compare-vals (cbor/float64 9007199254740992)
                                  (big "9007199254740993")))))
  (testing "NaN sorts last, not as a tie"
    (is (= 1 (index/compare-vals nan 1)))
    (is (= -1 (index/compare-vals 1 nan)))
    (is (= 1 (index/compare-vals nan pos-inf)))
    (is (= 0 (index/compare-vals nan (cbor/float64-from-bits "7ff8000000000001"))))))


(deftest ties-across-kinds-stay-ties
  (is (zero? (index/compare-vals 1 1.0)))
  (is (zero? (index/compare-vals 1 (cbor/float64 1))))
  (is (zero? (index/compare-vals 1 (cbor/ratio 1 1))))
  (is (zero? (index/compare-vals (cbor/ratio 1 1) 1)))
  (is (zero? (index/compare-vals (cbor/decimal -1 10) (cbor/decimal -2 100))))
  (is (zero? (index/compare-vals 0 neg-zero)))
  (is (zero? (index/compare-vals 1 (big "1")))))


(deftest carriers-stay-in-the-number-bucket
  (doseq [n [1 (cbor/ratio 1 1) (cbor/decimal -1 10) (cbor/float64 1) (big "1")]]
    (is (pos? (index/compare-vals n nil)))
    (is (pos? (index/compare-vals n true)))
    (is (neg? (index/compare-vals n "a")))
    (is (neg? (index/compare-vals n :k)))
    (is (neg? (index/compare-vals n 'sym)))))


(defn- row
  [e v]
  [e :num/v v 1 datom/default-op])


(deftest covered-indexes-order-mixed-kind-values-by-value
  (let [rows [(row 1 (cbor/ratio 7 4)) (row 2 (cbor/decimal -1 2)) (row 3 1)
              (row 4 (cbor/float64 0.1)) (row 5 (big "-3")) (row 6 (cbor/float64 1.5))]
        expected-values [(big "-3") (cbor/float64 0.1) (cbor/decimal -1 2) 1
                         (cbor/float64 1.5) (cbor/ratio 7 4)]]
    (doseq [order [rows (reverse rows) (sort-by first > rows)]]
      (let [idx (index/index-datoms order)]
        (testing "AVET and VAET follow v by value, stably for every insertion order"
          (is (every? true? (map cbor/content= expected-values
                                 (map index/datom-v (bt/seq (:avet idx))))))
          (is (every? true? (map cbor/content= expected-values
                                 (map index/datom-v (bt/seq (:vaet idx)))))))
        (testing "EAVT and AEVT follow e, with every row present"
          (is (= [1 2 3 4 5 6] (map index/datom-e (bt/seq (:eavt idx)))))
          (is (= [1 2 3 4 5 6] (map index/datom-e (bt/seq (:aevt idx))))))))))


(deftest one-index-entry-for-numerically-equal-values-of-one-fact
  ;; The intended consequence: covered-index membership is by numeric value
  ;; while content addressing and query equality are kind-strict.
  (let [idx (index/index-datoms [(row 1 1) (row 1 (cbor/float64 1))])]
    (doseq [k [:eavt :aevt :avet :vaet]]
      (is (= 1 (count (bt/seq (k idx)))) (str k))))
  (let [idx (index/index-datoms [(row 1 (cbor/decimal -1 10)) (row 1 (cbor/decimal -2 100))
                                 (row 2 1)])]
    (is (= 2 (count (bt/seq (:eavt idx)))))))

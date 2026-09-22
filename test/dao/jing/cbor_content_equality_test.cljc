(ns dao.jing.cbor-content-equality-test
  "Kind-strict content equality (dao.jing.cbor/content=, content-hash,
   content-key): the equality dao.space.query's `=` and unification use.
   Two values are content= exactly when content addressing gives them one
   identity, so the decisive check below compares content= with the
   canonical bytes themselves."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx])
  #?@(:cljd [(:import ["dart:core" BigInt])]))


(defn- big
  "A host big integer of a decimal string."
  [s]
  #?(:cljd (BigInt.parse s) :clj (bigint s) :cljs (js/BigInt s)))


(def pos-zero (cbor/float64-from-bits "0000000000000000"))
(def neg-zero (cbor/float64-from-bits "8000000000000000"))
(def nan-a (cbor/float64-from-bits "7ff8000000000000"))
(def nan-b (cbor/float64-from-bits "7ff8000000000001"))


(defn- hex
  [x]
  (fx/bytes->hex (cbor/encode x)))


(deftest numeric-kind-is-the-first-distinction
  (is (= :integer (cbor/numeric-kind 1)))
  (is (= :integer (cbor/numeric-kind (big "1"))))
  (is (= :float64 (cbor/numeric-kind (cbor/float64 1))))
  (is (= :decimal (cbor/numeric-kind (cbor/decimal -1 10))))
  (is (= :rational (cbor/numeric-kind (cbor/ratio 1 1))))
  (is (not (cbor/content= 1 (cbor/float64 1))) "integer 1 and float 1.0")
  (is (not (cbor/content= (cbor/ratio 1 1) 1))
      "rational kind never equals integer kind, even at denominator 1")
  (is (not (cbor/content= (cbor/decimal 0 1) 1)) "decimal 1 and integer 1")
  (is (not (cbor/content= (cbor/decimal 0 1) (cbor/float64 1))))
  (is (not (cbor/content= (cbor/ratio 1 2) (cbor/decimal -1 5))) "1/2 and 0.5M"))


(deftest within-kind-scale-and-zero-sign-are-significant
  (is (not (cbor/content= (cbor/decimal -1 10) (cbor/decimal -2 100)))
      "1.0M and 1.00M differ in scale")
  (is (cbor/content= (cbor/decimal -1 10) (cbor/decimal -1 10)))
  (is (not (cbor/content= pos-zero neg-zero)) "0.0 and -0.0")
  (is (cbor/content= neg-zero (cbor/float64-from-bits "8000000000000000")))
  (is (not (cbor/content= (cbor/decimal 0 0) (cbor/decimal -1 0))) "0M and 0.0M"))


(deftest integer-width-is-not-significant
  (is (cbor/content= 1 (big "1")))
  (is (cbor/content= (big "1") 1))
  (is (= (cbor/content-hash 1) (cbor/content-hash (big "1"))))
  (is (cbor/content= (big "18446744073709551616") (big "18446744073709551616"))))


(deftest canonical-nans-are-one-content
  ;; Every NaN encodes as the one canonical NaN (7ff8000000000000), so
  ;; content addressing gives all of them one identity: content= agrees.
  (is (cbor/content= nan-a nan-b))
  (is (cbor/content= nan-a nan-a))
  (is (= (cbor/content-hash nan-a) (cbor/content-hash nan-b)))
  (is (= (hex nan-a) (hex nan-b))))


(deftest ratios-compare-reduced
  (is (cbor/content= (cbor/ratio 2 4) (cbor/ratio 1 2)))
  (is (cbor/content= (cbor/->Rational (big "2") (big "4")) (cbor/ratio 1 2))
      "a hand-built unreduced carrier is its reduced value"))


(deftest collections-recurse-kind-strictly
  (is (not (cbor/content= [1] [(cbor/float64 1)])))
  (is (not (cbor/content= {:k (cbor/decimal -1 10)} {:k (cbor/decimal -2 100)})))
  (is (not (cbor/content= #{pos-zero} #{neg-zero})))
  (is (not (cbor/content= {1 :a} {(cbor/float64 1) :a})) "map keys by kind")
  (is (cbor/content= [1 {:k (cbor/decimal -1 10)}] [(big "1") {:k (cbor/decimal -1 10)}]))
  (is (cbor/content= [1 2] (fx/fresh-list [1 2])) "lists equal vectors (ruling A6)")
  (is (cbor/content= (with-meta [1] {:doc "d"}) [1]) "metadata ignored")
  (is (cbor/content= (fx/hex->bytes "0102") (fx/hex->bytes "0102")) "bytes by content")
  (is (not (cbor/content= (keyword nil "a/b") (keyword "a" "b")))
      "identifiers by fields, on every host")
  (is (not (cbor/content= "1" 1))))


(deftest equiv-keeps-its-loose-semantics
  (is (cbor/equiv 1 (cbor/float64 1)))
  (is (cbor/equiv pos-zero neg-zero))
  (is (cbor/equiv (cbor/decimal -1 10) (cbor/decimal -2 100)))
  (is (cbor/num= 1 (cbor/ratio 1 1))))


(deftest content-key-gives-host-collections-kind-strict-identity
  (let [values [pos-zero neg-zero (cbor/decimal -1 10) (cbor/decimal -2 100)
                1 (cbor/float64 1) (cbor/ratio 1 1)]]
    (is (= (count values) (count (into #{} (map cbor/content-key) values))))
    (is (= 1 (count (into #{} (map cbor/content-key) [1 (big "1")])))
        "integer width is not identity")))


(def numeric-sample
  "Values across every kind, each distinct pair of which a host = or
   numeric equality could confuse."
  [0 (big "0") pos-zero neg-zero (cbor/decimal 0 0) (cbor/decimal -1 0)
   (cbor/ratio 0 1) 1 (big "1") (cbor/float64 1) (cbor/decimal 0 1)
   (cbor/decimal -1 10) (cbor/decimal -2 100) (cbor/ratio 1 1) (cbor/ratio 2 2)
   (cbor/ratio 1 2) (cbor/decimal -1 5) (cbor/float64 0.5)
   (cbor/float64 0.1) (cbor/ratio 1 10) (cbor/decimal -1 1)
   (big "9007199254740993") (cbor/float64 9007199254740992) (big "9007199254740992")
   nan-a nan-b (cbor/float64-from-bits "7ff0000000000000")
   (cbor/float64-from-bits "fff0000000000000")
   [1] [(cbor/float64 1)] {:k (cbor/decimal -1 10)} {:k (cbor/decimal -2 100)}])


(deftest content-equality-is-exactly-same-canonical-bytes
  (doseq [a numeric-sample
          b numeric-sample]
    (testing (str (hex a) " / " (hex b))
      (is (= (= (hex a) (hex b)) (cbor/content= a b)))
      (when (cbor/content= a b)
        (is (= (cbor/content-hash a) (cbor/content-hash b)))))))

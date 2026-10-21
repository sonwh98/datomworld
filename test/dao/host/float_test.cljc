(ns dao.host.float-test
  "Table B's float-text kernel in `dao.host.float`
   (docs/design/dao.stream.host-boundary.md §4.2 R7-1, table D, slice
   H1), called directly: the five `decimal-text->float` texts the design
   names, `integer_test`'s float-text named cases through
   `float-digits` and `decimal->float`, and the int-contract float rows.
   The integer-v3 rows through `ratio->float` and `trunc-big` are in
   `dao.host.conformance-test`. Every expected double is written as its
   IEEE-754 bits, so no host float reader decides a result."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.host.float :as float]
    [dao.host.integer :as integer]
    [dao.jing.cbor-fixtures :as cfx]))


(defn- refusal
  [f]
  (try
    [:returned (f)]
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (:dao.host/refusal (ex-data e)))))


(defn- bits
  [x]
  (float/float64->bits x))


(defn- digit-values
  [s]
  (mapv #(first (cfx/hex->ints (str "0" %))) s))


(deftest decimal-text-named-cases
  (is (= "3ff8000000000000" (bits (float/decimal-text->float "1.5"))))
  (is (= "3fb999999999999a" (bits (float/decimal-text->float "0.1"))))
  (is (= "4340000000000000" (bits (float/decimal-text->float "9007199254740993.0")))
      "2^53 + 1 ties to even: 2^53")
  (is (= "7ff0000000000000" (bits (float/decimal-text->float (apply str "1" (repeat 399 "0")))))
      "a 400-digit integer text is +inf")
  (is (= :malformed-number (refusal #(float/decimal-text->float "1."))))
  (testing "the grammar is digits[.digits] and nothing else"
    (is (= "0000000000000000" (bits (float/decimal-text->float "0"))))
    (is (= "0000000000000000" (bits (float/decimal-text->float "0.000"))))
    (is (= "4059000000000000" (bits (float/decimal-text->float "100"))))
    (doseq [s ["" "." ".5" "-1.5" "+1" "1e5" "1.5.0" " 1" "1 " "0x10" "inf" "nan" nil 1.5]]
      (is (= :malformed-number (refusal #(float/decimal-text->float s))) (pr-str s)))))


(deftest float-text-kernel-named-cases
  (testing "float-digits: the shortest round-tripping decimal (CPython repr)"
    (is (= ["7120236347223045" -307] (float/float-digits (float/bits->float64 "0060000000000000")))
        "2^-1017: the interval below a power of two is the narrow one")
    (is (= ["6189700196426902" 26] (float/float-digits (float/bits->float64 "4580000000000000"))))
    (is (= ["5" -324] (float/float-digits (float/bits->float64 "0000000000000001"))))
    (is (= ["1" -1] (float/float-digits 0.1)))
    (is (= ["15" 0] (float/float-digits -1.5)) "the sign is the caller's")
    (is (= ["17976931348623157" 308] (float/float-digits (float/bits->float64 "7fefffffffffffff")))))
  (testing "float-digits refuses outside its domain"
    (doseq [x [0.0 -0.0 ##Inf ##-Inf ##NaN]]
      (is (= :out-of-domain (refusal #(float/float-digits x))) (bits x))))
  (testing "decimal->float"
    (is (= "7fefffffffffffff"
           (bits (float/decimal->float
                   (digit-values (integer/to-radix-string
                                   (integer/sub (integer/shift-left-big (integer/from-native 1) 1024)
                                                (integer/shift-left-big (integer/from-native 1) 971))
                                   10))
                   0)))
        "the largest double, 2^1024 - 2^971, reads back exactly")
    (is (= "0000000000000000" (bits (float/decimal->float [0 0 0] 5))))
    (is (= "0000000000000001" (bits (float/decimal->float [5] -324))))
    (is (= "0000000000000000" (bits (float/decimal->float [1] -400))) "below the range: 0.0")
    (is (= "7ff0000000000000" (bits (float/decimal->float [1] 400))) "past the range: +inf")
    (is (= "7ff0000000000000"
           (bits (float/decimal->float [1] (integer/shift-left-big (integer/from-native 1) 80))))
        "a big exponent")
    (is (= "3ff0000000000000" (bits (float/decimal->float (into [1] (repeat 900 0)) -900)))
        "past 800 significant digits"))
  (testing "float-digits and decimal->float invert each other"
    (doseq [h ["3ff0000000000000" "3fb999999999999a" "0000000000000001" "000fffffffffffff"
               "0010000000000000" "7fefffffffffffff" "4340000000000001" "c004000000000000"
               "7e37e43c8800759c" "3fd5555555555555"]]
      (let [x (float/bits->float64 h)
            [d e] (float/float-digits x)
            back (float/decimal->float (digit-values d) (- (inc e) (count d)))]
        (is (= (bits (float/signed (neg? x) back)) h) h)))))


(deftest ratio->float-overflow-and-signs
  (let [b #(integer/parse-decimal %)]
    (is (= "8000000000000000" (bits (float/ratio->float (b "0") (b "5") true))) "-0.0")
    (is (= ::float/float-overflow
           (float/ratio->float (integer/shift-left-big (b "1") 1024) (b "1") false)))
    (is (= "c000000000000000" (bits (float/ratio->float (b "4") (b "2") true))))))


(def ^:private int-contract
  (delay
    (into {}
          (keep (fn [l]
                  (let [[s c & more] (str/split l #" ")]
                    (when (and c (str/starts-with? s "f:"))
                      [[s c] (str/join " " more)]))))
          (str/split-lines (cfx/read-path "test/resources/yang/python/int-contract-v1.txt")))))


(deftest int-contract-float-rows
  (let [rows @int-contract]
    (doseq [[s k h] [["f:2^53" 53 "4340000000000000"] ["f:2^63" 63 "43e0000000000000"]]]
      (let [d (rows [s "dec"])
            x (float/bits->float64 h)]
        (is (some? d) s)
        (is (= h (bits (float/pow2 k))) s)
        (is (= h (bits (float/decimal-text->float d))) s)
        (is (= d (integer/to-radix-string (float/trunc-big x) 10)) s)
        (is (= (subs (rows [s "cbor"]) (- (count (rows [s "cbor"])) 16)) h) s)))))

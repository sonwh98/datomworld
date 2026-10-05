(ns yin.vm.integer-test
  "The `integer` host module (C3 slice S1): exact kernels at and beyond
   the carrier boundaries, promotion and demotion in both directions,
   explicit limits, qualified refusals, composition-only installation,
   the module path on all four VMs, and exact-integer carriers recognized
   as scalars by the UCF encoder, the heap trace, pinning, `kind-of` and
   `data/number?`."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.integer :as integer]
            [yin.vm.integer-v3-fixtures :as fx]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values])
  #?@(:cljd [(:import ["dart:core" BigInt])]))


;; =============================================================================
;; Host oracles, independent of the module
;; =============================================================================

(def ^:private host
  #?(:cljd :dart :clj :jvm :cljs :js))


(defn- big
  "The host big-integer carrier of decimal text `s`, built by the host."
  [s]
  #?(:cljd (BigInt.parse s)
     :clj (clojure.lang.BigInt/fromBigInteger (java.math.BigInteger. ^String s))
     :cljs (js/BigInt s)))


(defn- n
  "Decimal text `s` in its canonical carrier, by the host's own rule:
   native within signed 64 bits (JVM, Dart) or +/-(2^53 - 1) (JS)."
  [s]
  #?(:cljd (let [b (BigInt.parse s)]
             (if (.-isValidInt b) (.toInt b) b))
     :clj (let [b (java.math.BigInteger. ^String s)]
            (if (< (.bitLength b) 64)
              (.longValue b)
              (clojure.lang.BigInt/fromBigInteger b)))
     :cljs (let [b (js/BigInt s)]
             (if (js/Number.isSafeInteger (js/Number b)) (js/Number b) b))))


(defn- carrier
  [x]
  #?(:cljd (cond (dart/is? x int) :native
                 (dart/is? x BigInt) :big
                 :else :other)
     :clj (cond (instance? Long x) :native
                (instance? clojure.lang.BigInt x) :big
                :else :other)
     :cljs (cond (number? x) :native
                 (identical? "bigint" (js* "typeof ~{}" x)) :big
                 :else :other)))


(defn- same?
  "True when `expected` and `actual` are equal values in the same
   carrier, elementwise for vectors."
  [expected actual]
  (if (vector? expected)
    (and (vector? actual)
         (= (count expected) (count actual))
         (every? true? (map same? expected actual)))
    (and (= expected actual) (= (carrier expected) (carrier actual)))))


;; =============================================================================
;; Direct calls
;; =============================================================================

(def ^:private limits
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(def ^:private module-fns (integer/integer-module limits))


(defn- call
  [op & args]
  (apply (get module-fns op) args))


(defn- refusal
  "`[message ex-data]` of the refusal `(apply f args)` throws, or
   `[:returned value]`."
  [f & args]
  (try
    [:returned (apply f args)]
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      [(ex-message e) (ex-data e)])))


(defn- refused
  [op reason data]
  [integer/refusal-message
   (merge {::integer/op op, ::integer/reason reason} data)])


(defn- limited
  "What `refusal` answers for a call that breaches a limit: the reason
   returned as data, never thrown (module version 2)."
  [reason]
  [:returned reason])


(def ^:private two-53 "9007199254740992")
(def ^:private two-63 "9223372036854775808")
(def ^:private two-64 "18446744073709551616")


;; =============================================================================
;; Carriers: promotion before, demotion after
;; =============================================================================

(deftest carrier-boundary-test
  (testing "2^53 is native on the JVM and Dart, a BigInt on JS"
    (let [r (call 'add 9007199254740991 1)]
      (is (same? (n two-53) r))
      (is (= ({:jvm :native, :dart :native, :js :big} host) (carrier r))))
    (let [r (call 'sub -9007199254740991 1)]
      (is (same? (n "-9007199254740992") r))
      (is (= ({:jvm :native, :dart :native, :js :big} host) (carrier r)))))
  (testing "2^53 + 1 is exact, never rounded"
    (is (same? (n "9007199254740993") (call 'add (n two-53) 1)))
    (is (not= (call 'add (n two-53) 1) (n two-53))))
  (testing "2^63 promotes on every host; -2^63 is native on the JVM and Dart"
    (is (same? (big two-63) (call 'add (n "9223372036854775807") 1)))
    (is (= :big (carrier (call 'add (n "9223372036854775807") 1))))
    (let [r (call 'sub (n "-9223372036854775807") 1)]
      (is (same? (n "-9223372036854775808") r))
      (is (= ({:jvm :native, :dart :native, :js :big} host) (carrier r))))
    (is (same? (big two-63) (call 'neg (n "-9223372036854775808"))))
    (is (same? (big two-63) (call 'mul (n "-9223372036854775808") -1))))
  (testing "2^64 and beyond"
    (is (same? (big two-64) (call 'mul 4294967296 4294967296)))
    (is (same? (big "-18446744073709551617")
               (call 'sub (call 'neg (big two-64)) 1))))
  (testing "demotion: a big result back in range is native"
    (is (same? 5 (call 'sub (big two-64) (big "18446744073709551611"))))
    (is (= :native (carrier (call 'sub (big two-64) (big two-64)))))
    (is (same? 0 (call 'add (big two-64) (call 'neg (big two-64)))))
    (is (same? [1 0] (call 'quot-rem (big two-64) (big two-64))))
    (is (same? -1 (call 'add (big "-18446744073709551617") (big two-64))))
    (is (same? 9007199254740991 (call 'sub (big two-53) 1))))
  (testing "a non-canonical input carrier normalizes"
    (is (same? 7 (call 'normalize (big "7"))))
    (is (same? (n two-53) (call 'normalize (big two-53))))))


(deftest compare-test
  (is (= -1 (call 'compare (n two-53) (n "9007199254740993"))))
  (is (= 1 (call 'compare (big two-64) (n "9223372036854775807"))))
  (is (= 0 (call 'compare (big "5") 5)))
  (is (= -1 (call 'compare (big "-18446744073709551617") (big "-18446744073709551616")))))


;; =============================================================================
;; Division
;; =============================================================================

(deftest division-test
  (testing "Python floor division and divisor-signed modulo"
    (is (same? [-3 2] (call 'floor-div-mod -7 3)))
    (is (same? [-3 -2] (call 'floor-div-mod 7 -3)))
    (is (same? [2 -1] (call 'floor-div-mod -7 -3)))
    (is (same? [2 1] (call 'floor-div-mod 7 3)))
    (is (same? [(n "-1000000000000001") 999999999999999]
               (call 'floor-div-mod (big "-1000000000000000000000000000001")
                     1000000000000000)))
    (is (same? [-433680868995 (n "-1840584757335818233")]
               (call 'floor-div-mod (big "1000000000000000000000000000007")
                     (n "-2305843009213693952"))))
    (is (same? [(big "-422550200076076467165567735126") 2]
               (call 'floor-div-mod (big "-1267650600228229401496703205376") 3))))
  (testing "truncating quotient and remainder"
    (is (same? [-2 -1] (call 'quot-rem -7 3)))
    (is (same? [-2 1] (call 'quot-rem 7 -3))))
  (testing "-2^63 by -1 promotes"
    (is (same? [(big two-63) 0]
               (call 'floor-div-mod (n "-9223372036854775808") -1)))
    (is (same? [(big two-63) 0]
               (call 'quot-rem (n "-9223372036854775808") -1))))
  (testing "zero division refuses as data"
    (is (= (refused 'floor-div-mod :zero-division {})
           (refusal call 'floor-div-mod 1 0)))
    (is (= (refused 'quot-rem :zero-division {})
           (refusal call 'quot-rem (big two-64) (big "0"))))))


;; =============================================================================
;; Bits
;; =============================================================================

(deftest bits-test
  (testing "bit operations on negatives, infinite two's complement"
    (let [a (call 'add (call 'neg (call 'shift-left 1 70)) 12345)
          b (call 'add (call 'shift-left 1 70) 999)]
      (is (same? (big "1180591620717411303457") (call 'bit-and a b)))
      (is (same? (big "-1180591620717411290113") (call 'bit-or a b)))
      (is (same? (big "-2361183241434822593570") (call 'bit-xor a b))))
    (is (same? -1 (call 'bit-and -1 -1)))
    (is (same? (big "-1606938044258990275541962092341162602522202993782792835301377")
               (call 'bit-not (call 'shift-left 1 200))))
    (is (same? (call 'sub (call 'neg (call 'shift-left 1 200)) 1)
               (call 'bit-not (call 'shift-left 1 200)))))
  (testing "shifts floor, and huge counts are never narrowed"
    (is (same? -2 (call 'shift-right -3 1)))
    (is (same? -1 (call 'shift-right -1 1)))
    (is (same? -1 (call 'shift-right (big "-18446744073709551617") (big two-64))))
    (is (same? 0 (call 'shift-right (big two-64) (big two-64))))
    (is (same? 0 (call 'shift-left 0 (big two-64))))
    (is (same? (big two-64) (call 'shift-left 1 64)))
    (is (same? 1 (call 'shift-right (big two-64) 64)))
    (is (= (limited ::integer/bit-limit)
           (refusal call 'shift-left 1 (big two-64))))
    (is (= (refused 'shift-right :negative-count {})
           (refusal call 'shift-right 1 -1)))
    (is (= (refused 'shift-left :negative-count {})
           (refusal call 'shift-left 1 (call 'neg (big two-64))))))
  (testing "bit length of the absolute value"
    (is (= 0 (call 'bit-length 0)))
    (is (= 54 (call 'bit-length (n two-53))))
    (is (= 64 (call 'bit-length (n "-9223372036854775808"))))
    (is (= 65 (call 'bit-length (big two-64))))))


;; =============================================================================
;; Powers
;; =============================================================================

(deftest pow-test
  (is (same? 1 (call 'pow 0 0)))
  (is (same? 0 (call 'pow 0 (big two-64))))
  (is (same? 1 (call 'pow 1 (big two-64))))
  (is (same? 1 (call 'pow -1 (big two-64))))
  (is (same? -1 (call 'pow -1 (call 'add (big two-64) 1))))
  (is (same? (big two-64) (call 'pow 2 64)))
  (is (same? (n two-53) (call 'pow 2 53)))
  (is (same? (n "-9223372036854775808") (call 'pow -2 63)))
  (is (same? (big "-36472996377170786403") (call 'pow -3 41)))
  (is (same? (big "515377520732011331036461129765621272702107522001")
             (call 'pow 3 100)))
  (is (= (refused 'pow :negative-exponent {}) (refusal call 'pow 2 -1)))
  (is (= (limited ::integer/bit-limit)
         (refusal call 'pow 2 (big two-64)))
      "a huge exponent is refused before any work")
  (is (= (limited ::integer/bit-limit)
         (refusal call 'pow 3 100000))))


;; =============================================================================
;; Text
;; =============================================================================

(deftest parse-format-test
  (testing "exact decimal round trip"
    (doseq [s ["0" "-1" "9007199254740991" "9007199254740993"
               "-9223372036854775809" "18446744073709551616"
               "-123456789012345678901234567890123456789"]]
      (is (same? (n s) (call 'parse s)) s)
      (is (= s (call 'format (call 'parse s))) s)))
  (testing "leading zeros and other radixes"
    (is (same? 7 (call 'parse "007")))
    (is (same? -255 (call 'parse "-ff" 16)))
    (is (same? -255 (call 'parse "-FF" 16)))
    (is (same? 1295 (call 'parse "zz" 36)))
    (is (= "10000000000000000" (call 'format (big two-64) 16)))
    (is (= "-100000000000000000000000000000000000000000000000000000000000000000"
           (call 'format (call 'neg (call 'shift-left 1 65)) 2))))
  (testing "malformed text refuses as syntax"
    (doseq [s ["" "-" "+1" "1_0" " 1" "12a" "0x10" "1.0" "٣"]]
      (is (= (refused 'parse :syntax {}) (refusal call 'parse s)) s))
    (is (= (refused 'parse :syntax {}) (refusal call 'parse "2" 2))))
  (testing "radix and types"
    (is (= (refused 'parse :out-of-range {::integer/arg 1, ::integer/lo 2,
                                          ::integer/hi 36})
           (refusal call 'parse "1" 37)))
    (is (= (refused 'parse :wrong-type {::integer/arg 0,
                                        ::integer/expected :string})
           (refusal call 'parse 1)))))


(deftest digit-limit-test
  (let [small (integer/integer-module {::integer/max-bits 100000,
                                       ::integer/max-digits 5})
        call (fn [op & args] (apply (get small op) args))]
    (is (same? 99999 (call 'parse "99999")))
    (is (= (limited ::integer/digit-limit)
           (refusal call 'parse "100000")))
    (is (= (limited ::integer/digit-limit)
           (refusal call 'parse "-000001")))
    (is (= "-99999" (call 'format -99999)))
    (is (= (limited ::integer/digit-limit)
           (refusal call 'format 100000)))
    (is (= (limited ::integer/digit-limit)
           (refusal call 'format (big two-64)))
        "refused before the text is built")
    (testing "a power-of-two radix is not digit-limited"
      (is (same? (big two-64) (call 'parse "10000000000000000" 16)))
      (is (= "10000000000000000" (call 'format (big two-64) 16))))))


(deftest bit-limit-test
  (let [small (integer/integer-module {::integer/max-bits 64,
                                       ::integer/max-digits 4300})
        call (fn [op & args] (apply (get small op) args))]
    (is (same? (big "18446744073709551615") (call 'sub (big two-64) 1)))
    (is (= (limited ::integer/bit-limit)
           (refusal call 'add (big "18446744073709551615") 1)))
    (is (= (limited ::integer/bit-limit)
           (refusal call 'mul (big two-63) (big two-63)))
        "refused before the product is built")
    (is (= (limited ::integer/bit-limit)
           (refusal call 'shift-left 1 64)))
    (is (= (limited ::integer/bit-limit)
           (refusal call 'pow 2 64)))
    (is (= (limited ::integer/bit-limit)
           (refusal call 'parse two-64)))
    (testing "every limit-capable export answers the reason, a pair too:
              never half a pair"
      (is (= (limited ::integer/bit-limit) (refusal call 'neg (big two-64))))
      (is (= (limited ::integer/bit-limit)
             (refusal call 'normalize (big two-64))))
      (is (= (limited ::integer/bit-limit)
             (refusal call 'bit-not (big two-64))))
      (is (= (limited ::integer/bit-limit)
             (refusal call 'quot-rem (big two-64) 1)))
      (is (= (limited ::integer/bit-limit)
             (refusal call 'floor-div-mod -1 (big "18446744073709551617")))
          "the quotient -1 fits, the remainder 2^64 does not"))
    (testing "every other reason still throws under the same limits"
      (is (= (refused 'floor-div-mod :zero-division {})
             (refusal call 'floor-div-mod (big two-64) 0)))
      (is (= (refused 'shift-left :negative-count {})
             (refusal call 'shift-left (big two-64) -1)))
      (is (= (refused 'pow :negative-exponent {})
             (refusal call 'pow 2 -1)))
      (is (= (refused 'mul :wrong-type {::integer/arg 1,
                                        ::integer/expected :integer})
             (refusal call 'mul (big two-63) ::integer/bit-limit))
          "a reason fed back in is a wrong type, not a limit")
      (is (= (refused 'add :arity {::integer/argc 1})
             (refusal call 'add (big two-64)))))))


(deftest module-version-test
  (is (= 3 integer/module-version)
      "version 3: the float exports beside the version 2 contract"))


(deftest limits-are-explicit-test
  (doseq [l [nil {} {::integer/max-bits 64} {::integer/max-digits 10}
             {::integer/max-bits 0, ::integer/max-digits 10}
             {::integer/max-bits 1.5, ::integer/max-digits 10}]]
    (is (= :limits (::integer/reason (second (refusal integer/integer-module l))))
        (pr-str l)))
  (is (= :limits
         (::integer/reason
           (second (refusal integer/register-integer-module
                            (module/default-registry) {}))))))


;; =============================================================================
;; Recognition and refusals
;; =============================================================================

(deftest integer?-test
  (doseq [x [0 -1 (n two-53) (big two-64) (big "5")]]
    (is (true? (call 'integer? x))))
  (doseq [x [1.5 "1" nil :one [1] {:a 1}]]
    (is (false? (call 'integer? x)) (pr-str x)))
  #?(:cljs (is (false? (call 'integer? (* -1 0))) "JS -0 is a float")
     :default (is (false? (call 'integer? 1.0)) "a float is not an integer")))


(deftest wrong-type-and-arity-test
  (is (= (refused 'add :wrong-type {::integer/arg 0, ::integer/expected :integer})
         (refusal call 'add 1.5 1)))
  (is (= (refused 'mul :wrong-type {::integer/arg 1, ::integer/expected :integer})
         (refusal call 'mul 1 "2")))
  #?(:cljs (is (= (refused 'add :wrong-type {::integer/arg 0,
                                             ::integer/expected :integer})
                  (refusal call 'add 9007199254740992 1))
               "an unsafe JS Number is not an exact integer"))
  (is (= (refused 'add :arity {::integer/argc 3}) (refusal call 'add 1 2 3)))
  (is (= (refused 'parse :arity {::integer/argc 0}) (refusal call 'parse))))


;; =============================================================================
;; Fast paths (version 3 kernels; results unchanged)
;; =============================================================================

(def ^:private max-safe 9007199254740991)
(def ^:private two-26 67108864)


(deftest fast-path-edge-test
  (testing "add and sub: a result past +/-(2^53 - 1) takes the big path"
    (is (same? max-safe (call 'add 9007199254740990 1)))
    (is (same? (n two-53) (call 'add max-safe 1)))
    (is (same? (n "-9007199254740992") (call 'sub (- max-safe) 1)))
    (is (same? (- max-safe) (call 'sub -9007199254740990 1)))
    (is (same? (n "18014398509481982") (call 'add max-safe max-safe)))
    (is (same? (n "-18014398509481982") (call 'sub (- max-safe) max-safe))))
  (testing "mul: operands up to 2^26 natively, a wider one the big path"
    (is (same? 4503599627370496 (call 'mul two-26 two-26)))
    (is (same? -4503599627370496 (call 'mul (- two-26) two-26)))
    (is (same? 4503599694479360 (call 'mul (inc two-26) two-26)))
    (is (same? 4503599761588225 (call 'mul (inc two-26) (inc two-26))))
    (is (same? (n "81129638414606663681390495662081")
               (call 'mul max-safe max-safe))))
  (testing "a zero product is the integer 0, never a JS -0"
    (doseq [[a b] [[-1 0] [0 -1] [(- two-26) 0]]]
      (let [r (call 'mul a b)]
        (is (same? 0 r) (pr-str [a b]))
        (is (true? (call 'integer? r)) (pr-str [a b])))))
  (testing "compare natively when both are safe"
    (is (= -1 (call 'compare (- max-safe) max-safe)))
    (is (= 0 (call 'compare max-safe max-safe)))
    (is (= -1 (call 'compare max-safe (n two-53))))
    (is (= 1 (call 'compare (n "-9007199254740992") (n "-9007199254740993")))))
  (testing "mixed fast and big operands"
    (is (same? 8 (call 'add (big "5") 3)))
    (is (same? (big "18446744073709551619") (call 'add 3 (big two-64))))
    (is (same? 134217728 (call 'mul (big "2") two-26)))
    (is (= 0 (call 'compare (big "7") 7))))
  (testing "promotion, then cancellation back to a native"
    (is (same? max-safe (call 'sub (call 'add max-safe max-safe) max-safe)))
    (is (same? 2 (call 'sub (call 'add max-safe 2) max-safe)))
    (is (same? 0 (call 'add (call 'mul max-safe max-safe)
                       (call 'neg (call 'mul max-safe max-safe))))))
  #?(:cljs (is (= (refused 'add :wrong-type {::integer/arg 0,
                                             ::integer/expected :integer})
                  (refusal call 'add (* -1 0) 0))
               "a JS -0 operand is still refused, never added natively")))


(deftest fast-path-respects-the-bit-limit-test
  (let [at-53 (integer/integer-module {::integer/max-bits 53,
                                       ::integer/max-digits 4300})
        at-52 (integer/integer-module {::integer/max-bits 52,
                                       ::integer/max-digits 4300})]
    (testing "max-bits 53: every fast result fits, every other is checked"
      (is (same? max-safe ((get at-53 'add) 9007199254740990 1)))
      (is (= ::integer/bit-limit ((get at-53 'add) max-safe 1))
          "2^53 has 54 bits")
      (is (= ::integer/bit-limit ((get at-53 'sub) (- max-safe) 1)))
      (is (same? 4503599627370496 ((get at-53 'mul) two-26 two-26)))
      (is (= ::integer/bit-limit ((get at-53 'mul) 134217728 134217728))
          "2^27 squared is 2^54")
      (is (= ::integer/bit-limit ((get at-53 'mul) 2147483648 4194304))
          "2^31 times 2^22 is 2^53"))
    (testing "below 53 bits there is no fast table"
      (is (= ::integer/bit-limit ((get at-52 'add) 4503599627370495 1)))
      (is (= ::integer/bit-limit ((get at-52 'mul) two-26 two-26))))))


;; =============================================================================
;; Floats (version 3), against CPython 3.9.6
;; =============================================================================

(def ^:private fixture (delay (fx/read-file)))


(defn- rows
  [op]
  (get-in @fixture [:rows op]))


(defn- float-result?
  [x]
  #?(:cljd (dart/is? x double)
     :clj (instance? Double x)
     :cljs (number? x)))


(defn- float-mismatch
  "nil when `actual` is what float row `row` expects as text `expected`,
   else `[row actual]`."
  [row expected actual]
  (when-not (if (= "float-overflow" expected)
              (= ::integer/float-overflow actual)
              (and (float-result? actual)
                   (= expected (fx/double->bits actual))))
    [row actual]))


(deftest float-fixture-test
  (is (= "CPython 3.9.6" (:generator @fixture)))
  (is (= {"to-float" 187, "true-div" 96, "compare-float" 837, "from-float" 29}
         (into {} (map (fn [op] [op (count (rows op))]))
               ["to-float" "true-div" "compare-float" "from-float"]))
      "the fixture keeps every row: 1149 in all")
  (testing "to-float: float(int), ties to even, overflow as the reason"
    (is (= []
           (into []
                 (keep (fn [[a e :as row]]
                         (float-mismatch row e (call 'to-float (n a)))))
                 (rows "to-float")))))
  (testing "true-div: int / int correctly rounded, huge and subnormal"
    (is (<= 90 (count (rows "true-div"))))
    (is (= []
           (into []
                 (keep (fn [[a b e :as row]]
                         (float-mismatch row e
                                         (call 'true-div (n a) (n b)))))
                 (rows "true-div")))))
  (testing "compare-float: exact, the integer never rounded"
    (is (<= 800 (count (rows "compare-float"))))
    (is (= []
           (into []
                 (keep (fn [[a x e :as row]]
                         (let [r (call 'compare-float (n a)
                                       (fx/bits->double x))]
                           (when-not (= ({"-1" -1, "0" 0, "1" 1} e) r)
                             [row r]))))
                 (rows "compare-float")))))
  (testing "from-float: exact truncation into the canonical carrier"
    (is (= []
           (into []
                 (keep (fn [[x e :as row]]
                         (let [r (call 'from-float (fx/bits->double x))]
                           (when-not (same? (n e) r)
                             [row r]))))
                 (rows "from-float"))))))


(deftest float-named-cases-test
  (is (= "3ff0000000000000"
         (fx/double->bits (call 'true-div (call 'pow 10 400)
                                (call 'pow 10 400)))))
  (is (= "0000000000000001"
         (fx/double->bits (call 'true-div 1 (call 'shift-left 1 1074)))))
  (is (= "0000000000000000"
         (fx/double->bits (call 'true-div 1 (call 'shift-left 1 1075))))
      "half the least subnormal ties to even: +0.0")
  (is (= "8000000000000000" (fx/double->bits (call 'true-div 0 -5)))
      "CPython: 0 / -5 is -0.0")
  (is (= "0000000000000000" (fx/double->bits (call 'to-float 0))))
  (is (= ::integer/float-overflow
         (call 'to-float (call 'sub (call 'shift-left 1 1024)
                               (call 'shift-left 1 970))))
      "the tie above the largest finite float rounds to the even overflow")
  (is (= "7fefffffffffffff"
         (fx/double->bits
           (call 'to-float (call 'sub (call 'sub (call 'shift-left 1 1024)
                                            (call 'shift-left 1 970))
                                 1)))))
  (let [inf (fx/bits->double "7ff0000000000000")
        ninf (fx/bits->double "fff0000000000000")]
    (is (= -1 (call 'compare-float (call 'shift-left 1 5000) inf)))
    (is (= 1 (call 'compare-float (call 'neg (call 'shift-left 1 5000))
                   ninf)))))


(deftest float-refusals-test
  (is (= (refused 'true-div :zero-division {}) (refusal call 'true-div 1 0)))
  (is (= (refused 'true-div :zero-division {})
         (refusal call 'true-div (big two-64) (big "0"))))
  (is (= (refused 'to-float :wrong-type {::integer/arg 0,
                                         ::integer/expected :integer})
         (refusal call 'to-float 0.5)))
  (is (= (refused 'compare-float :wrong-type {::integer/arg 1,
                                              ::integer/expected :float})
         (refusal call 'compare-float 1 "1")))
  (is (= (refused 'compare-float :wrong-type
                  {::integer/arg 1, ::integer/expected :non-nan-float})
         (refusal call 'compare-float 1 (fx/bits->double "7ff8000000000000"))))
  (doseq [x ["7ff8000000000000" "7ff0000000000000" "fff0000000000000"]]
    (is (= (refused 'from-float :wrong-type
                    {::integer/arg 0, ::integer/expected :finite-float})
           (refusal call 'from-float (fx/bits->double x)))
        x))
  (let [small (integer/integer-module {::integer/max-bits 64,
                                       ::integer/max-digits 4300})]
    (is (= ::integer/bit-limit
           ((get small 'from-float) (fx/bits->double "7fefffffffffffff")))
        "from-float answers the bit limit like every integer result")
    (is (same? (n "-9223372036854775808")
               ((get small 'from-float)
                (fx/bits->double "c3e0000000000000"))))))


;; =============================================================================
;; Composition
;; =============================================================================

(deftest every-export-is-pure-test
  (is (= #{'integer? 'normalize 'add 'sub 'neg 'mul 'compare 'quot-rem
           'floor-div-mod 'pow 'bit-and 'bit-or 'bit-xor 'bit-not 'shift-left
           'shift-right 'bit-length 'parse 'format 'to-float 'compare-float
           'true-div 'from-float}
         (set (keys module-fns))
         (set (keys integer/integer-profiles))))
  (doseq [[sym profile] integer/integer-profiles]
    (is (= :pure (:yin.k/class profile)) (str sym))
    (is (= #{} (:yin.k/effects profile)) (str sym))
    (is (= :none (:yin.k/host-state profile)) (str sym))))


(deftest installed-only-by-composition-test
  (is (not-any? #(contains? vm/primitives %) (map #(symbol "integer" (name %))
                                                  (keys module-fns))))
  (is (nil? (module/resolve-module (module/default-registry) 'integer)))
  (let [r (integer/register-integer-module (module/default-registry) limits)]
    (is (some? (module/resolve-module r 'integer)))
    (is (some? (module/resolve-module r 'integer.add)))))


;; =============================================================================
;; The four VMs
;; =============================================================================

(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  {:ast-walker (fn [opts ast] (vm/eval (tu/create-vm opts) ast)),
   :semantic (fn [opts ast]
               (vm/run (load-semantic-ast (semantic/create-vm opts)
                                          (vm/ast->datoms ast)))),
   :stack (fn [opts ast]
            (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                   (assoc opts :contract vm/stack-contract)))),
   :register (fn [opts ast]
               (vm/run (rvm/create-vm
                         (:image (rc/adapt (second (vm/ast->datoms-with-root
                                                     ast))))
                         (assoc opts :contract vm/register-contract))))})


(defn- on-every-vm
  "`[vm-key halted-vm]` for each VM; a throw becomes
   `[:thrown message ex-data]`."
  [opts ast]
  (into {}
        (map (fn [[k run]]
               [k (try (run (merge base-opts opts) ast)
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e) (ex-data e)]))]))
        runners))


(def ^:private with-integer
  {:modules (integer/register-integer-module (module/default-registry)
                                             limits)})


(defn- lit
  [x]
  {:type :literal, :value x})


(defn- i
  "A call of export `sym` of the `integer` module."
  [sym & args]
  {:type :application,
   :operator {:type :variable, :name (symbol "integer" (name sym))},
   :operands (vec args)})


(deftest program-runs-on-every-vm-test
  (testing "native literals within +/-(2^53 - 1); big values made at run time"
    (doseq [[ast expected]
            [[(i 'add (lit 9007199254740991) (lit 1)) (n two-53)]
             [(i 'mul (i 'parse (lit two-64)) (lit 2))
              (big "36893488147419103232")]
             [(i 'sub (i 'parse (lit two-64)) (i 'parse (lit two-64))) 0]
             [(i 'format (i 'pow (lit 2) (lit 100)))
              "1267650600228229401496703205376"]
             [(i 'floor-div-mod (lit -7) (lit 3)) [-3 2]]]]
      (doseq [[k result] (on-every-vm with-integer ast)]
        (is (same? expected (vm/value result)) (str k " " (:operator ast))))))
  (testing "a refusal reaches the caller as the same qualified data"
    (doseq [[k result] (on-every-vm with-integer
                                    (i 'floor-div-mod (lit 1) (lit 0)))]
      (is (= (into [:thrown] (refused 'floor-div-mod :zero-division {}))
             result)
          (str k))))
  (testing "a limit breach is the reason as the program's value"
    (doseq [[ast expected]
            [[(i 'pow (lit 2) (lit 100000)) ::integer/bit-limit]
             [(i 'format (i 'shift-left (lit 1) (lit 20000)))
              ::integer/digit-limit]]]
      (doseq [[k result] (on-every-vm with-integer ast)]
        (is (= expected (vm/value result)) (str k)))))
  (testing "absent without registration"
    (doseq [[k result] (on-every-vm {:modules (module/default-registry)}
                                    (i 'add (lit 1) (lit 2)))]
      (is (= :thrown (first result)) (str k)))))


;; =============================================================================
;; Carrier recognition as scalars (ruling 4)
;; =============================================================================

(def ^:private b64 (big two-64))


(defn- cell-ref
  [id]
  {:type :cell-ref, :id id, :seal :s})


(deftest kind-of-and-number?-test
  (is (= :number (values/kind-of b64)))
  (is (= :number (values/kind-of (n two-53))))
  (is (true? ((get data/data-module 'number?) b64)))
  (is (true? ((get data/data-module 'number?) (call 'neg b64)))))


(deftest encoder-lifts-big-integers-as-scalars-test
  (let [child {:store {'x b64,
                       'v [b64 {:k (call 'neg b64)} (list b64)]}}
        lifted (engine/lift-slice child :segment/own ['x 'v])]
    (is (same? b64 (get-in lifted [:slice 'x])) "no wrapper, no marker")
    (is (= [b64
            {:yin.k/tag :yin.k/literal,
             :yin.k/entries [:k (call 'neg b64)]}
            (list b64)]
           (get-in lifted [:slice 'v])))
    (is (= {} (:cells lifted)) "no cell")))


(deftest heap-trace-treats-big-integers-as-leaves-test
  (let [state {:heap {:c1 {:value b64, :seal :s},
                      :c2 {:value [b64 (cell-ref :c3)], :seal :s},
                      :c3 {:value (call 'neg b64), :seal :s},
                      :dead {:value b64, :seal :s}},
               :store {'a (cell-ref :c1), 'b (cell-ref :c2), 'n b64}}
        swept (engine/collect state [b64])]
    (is (= #{:c1 :c2 :c3} (set (keys (:heap swept)))))
    (is (same? b64 (get-in swept [:heap :c1 :value])))))


(deftest pin-refs-treats-big-integers-as-scalars-test
  (let [state {:heap {:c1 {:value b64, :seal :s}}}]
    (is (identical? state (engine/pin-refs state b64)))
    (is (= #{:c1}
           (get-in (engine/pin-refs state {:n b64, :r (cell-ref :c1)})
                   [:gc :pinned])))
    (is (= #{:c1}
           (get-in (engine/pin-refs state [b64 (cell-ref :c1)])
                   [:gc :pinned])))))

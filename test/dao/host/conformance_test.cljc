(ns dao.host.conformance-test
  "Table D of docs/design/dao.stream.host-boundary.md (§4.3), re-asserted
   directly through the primitive profile (slice H1): each V row's cases
   run against `dao.host.integer`, `.float`, `.number` and `.text`, not
   through the namespace that holds them today. Byte-level rows read the
   frozen corpora (test/resources/dao/jing/cbor-v1.json,
   test/resources/yin/vm/integer-v3.txt,
   test/resources/yang/python/int-contract-v1.txt), so equality is with
   the recorded bytes, never with what this host computed elsewhere.

   Also here: §2.4 obligation (i), the P-entry argument-immutability
   property over the bytes, text, float and integer namespaces, and the
   freshness of every \"fresh\" result. `dao.host.bytes-test` holds the
   M-entry prevalidation cases, `dao.host.text-test` the strict UTF-8 and
   NFC rows, `dao.host.float-test` the table B float-text cases."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.host.bytes :as bytes]
    [dao.host.float :as float]
    [dao.host.integer :as integer]
    [dao.host.number :as number]
    [dao.host.text :as text]
    [dao.jing.cbor :as cbor]
    [dao.jing.cbor-fixtures :as cfx]))


(def ^:private host
  #?(:cljd :dart :clj :jvm :cljs :js))


(defn- refusal
  "The `:dao.host/refusal` class `(f)` throws, or `[:returned value]`."
  [f]
  (try
    [:returned (f)]
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (:dao.host/refusal (ex-data e)))))


(defn- big
  "Decimal text `s` as a host big integer, through the profile."
  [s]
  (integer/parse-decimal s))


(defn- n
  "Decimal text `s` in its canonical carrier."
  [s]
  (integer/demote (big s)))


(defn- carrier
  [x]
  (cond (integer/big-carrier? x) :big
        (integer/exact-integer? x) :native
        :else :other))


(defn- same?
  "Equal values in the same canonical carrier."
  [expected actual]
  (and (= expected actual) (= (carrier expected) (carrier actual))))


(defn- dec-text
  [b]
  (integer/to-radix-string b 10))


(defn- op
  "`f` over canonical carriers: promote, apply, demote."
  [f & xs]
  (integer/demote (apply f (map integer/promote xs))))


;; =============================================================================
;; integer: carriers, promote, demote (integer_test carrier-boundary-test)
;; =============================================================================

(deftest integer-carriers-test
  (testing "2^53 is native on the JVM and Dart, a BigInt on JS"
    (let [r (op integer/add 9007199254740991 1)]
      (is (= "9007199254740992" (dec-text (integer/promote r))))
      (is (= ({:jvm :native :dart :native :js :big} host) (carrier r)))))
  (testing "2^63 is big on every host; -2^63 is native on the JVM and Dart"
    (is (= :big (carrier (op integer/add (n "9223372036854775807") 1))))
    (is (= ({:jvm :native :dart :native :js :big} host)
           (carrier (op integer/sub (n "-9223372036854775807") 1)))))
  (testing "demotion: a big result back in range is native"
    (is (same? 5 (op integer/sub (n "18446744073709551616") (n "18446744073709551611"))))
    (is (same? 0 (op integer/sub (n "18446744073709551616") (n "18446744073709551616")))))
  (testing "recognition"
    (is (integer/exact-integer? 1))
    (is (integer/exact-integer? (big "18446744073709551616")))
    (is (integer/exact-integer? (n "18446744073709551616")))
    (is (not (integer/exact-integer? 0.5)))
    (is (not (integer/exact-integer? "1")))
    (is (not (integer/exact-integer? nil)))
    (is (integer/safe-native? 9007199254740991))
    (is (integer/safe-native? -9007199254740991))
    (is (not (integer/safe-native? (n "9007199254740992"))))
    (is (not (integer/safe-native? (n "18446744073709551616"))))
    (is (integer/big-carrier? (n "18446744073709551616")))
    (is (not (integer/big-carrier? 7)))
    (is (integer/host-float? 1.5))
    (is (integer/nan-float? ##NaN))
    (is (not (integer/nan-float? ##Inf)))
    (is (integer/infinite-float? ##-Inf))
    (is (not (integer/infinite-float? 1.5))))
  #?(:cljs
     (testing "JS -0 is floating-point content, not an integer"
       ;; a literal -0 reads as 0; build the negative zero from its bits
       (let [neg-zero (float/bits->float64 "8000000000000000")]
         (is (not (integer/exact-integer? neg-zero)))
         (is (not (integer/safe-native? neg-zero))))))
  (testing "native conversions"
    (is (= 9007199254740991 (integer/to-native (big "9007199254740991"))))
    (is (= "4340000000000000" (float/float64->bits (integer/native->double 9007199254740992))))
    (is (= -2 (integer/trunc-native -2.75)))
    (is (= "0000000000000000"
           (float/float64->bits (integer/native->double (integer/trunc-native -0.5))))
        "truncation never makes a JS -0")))


;; =============================================================================
;; integer: arithmetic, compare, bits, radix (integer_test compare-, division-,
;; bits-, parse-format-test)
;; =============================================================================

(deftest integer-operations-test
  (testing "compare-big answers exactly -1, 0 or 1"
    (is (= -1 (integer/compare-big (big "9007199254740992") (big "9007199254740993"))))
    (is (= 1 (integer/compare-big (big "18446744073709551616") (big "9223372036854775807"))))
    (is (= 0 (integer/compare-big (big "5") (integer/from-native 5))))
    (is (= -1 (integer/compare-big (big "-18446744073709551617") (big "-18446744073709551616"))))
    (is (= 1 (integer/compare-big (big "1000000000000000000000000000000") (big "1")))))
  (testing "add, sub and mul are exact"
    (is (= "9007199254740993" (dec-text (integer/add (big "9007199254740992") (big "1")))))
    (is (= "18446744073709551616" (dec-text (integer/mul (big "4294967296") (big "4294967296")))))
    (is (= "-18446744073709551617"
           (dec-text (integer/sub (big "-18446744073709551616") (big "1"))))))
  (testing "truncating quotient and remainder"
    (is (= ["-2" "-1"] (mapv dec-text [(integer/quot-trunc (big "-7") (big "3"))
                                       (integer/rem-trunc (big "-7") (big "3"))])))
    (is (= ["-2" "1"] (mapv dec-text [(integer/quot-trunc (big "7") (big "-3"))
                                      (integer/rem-trunc (big "7") (big "-3"))])))
    (is (= "9223372036854775808"
           (dec-text (integer/quot-trunc (big "-9223372036854775808") (big "-1"))))))
  (testing "bit operations on negatives, infinite two's complement"
    (let [a (integer/add (integer/sub (big "0") (integer/shift-left-big (big "1") 70)) (big "12345"))
          b (integer/add (integer/shift-left-big (big "1") 70) (big "999"))]
      (is (= "1180591620717411303457" (dec-text (integer/bit-and-big a b))))
      (is (= "-1180591620717411290113" (dec-text (integer/bit-or-big a b))))
      (is (= "-2361183241434822593570" (dec-text (integer/bit-xor-big a b))))))
  (testing "shifts floor"
    (is (= "-2" (dec-text (integer/shift-right-big (big "-3") 1))))
    (is (= "-1" (dec-text (integer/shift-right-big (big "-1") 1))))
    (is (= "18446744073709551616" (dec-text (integer/shift-left-big (big "1") 64))))
    (is (= "1" (dec-text (integer/shift-right-big (big "18446744073709551616") 64)))))
  (testing "bit length of the absolute value"
    (is (= 0 (integer/magnitude-bit-length (big "0"))))
    (is (= 54 (integer/magnitude-bit-length (big "9007199254740992"))))
    (is (= 64 (integer/magnitude-bit-length (big "-9223372036854775808"))))
    (is (= 65 (integer/magnitude-bit-length (big "18446744073709551616")))))
  (testing "radix text: lowercase, a leading - only, no prefix"
    (is (= "10000000000000000" (integer/to-radix-string (big "18446744073709551616") 16)))
    (is (= "-ff" (integer/to-radix-string (big "-255") 16)))
    (is (= "zz" (integer/to-radix-string (big "1295") 36)))
    (is (= "-100000000000000000000000000000000000000000000000000000000000000000"
           (integer/to-radix-string (integer/sub (big "0") (integer/shift-left-big (big "1") 65)) 2))))
  (testing "gcd is never negative"
    (is (= "6" (dec-text (integer/gcd (big "-12") (big "18")))))
    (is (= "0" (dec-text (integer/gcd (big "0") (big "0")))))
    (is (= "1" (dec-text (integer/gcd (big "18446744073709551617") (big "18446744073709551616")))))))


(deftest parse-decimal-test
  (testing "decimal round trip"
    (doseq [s ["0" "-1" "9007199254740991" "9007199254740993"
               "-9223372036854775809" "18446744073709551616"
               "-123456789012345678901234567890123456789"]]
      (is (= s (dec-text (big s))) s)))
  (is (= "7" (dec-text (big "007"))))
  (testing "anything but [-]digits refuses before the host parser sees it"
    (doseq [s ["" "-" "+1" "1_0" " 1" "1 " "12a" "0x10" "1.0" "٣" "--1" "1e3"]]
      (is (= :malformed-number (refusal #(integer/parse-decimal s))) (pr-str s)))
    (is (= :malformed-number (refusal #(integer/parse-decimal 12))))
    (is (= :malformed-number (refusal #(integer/parse-decimal nil))))))


;; =============================================================================
;; integer: the corpus tag 2/3 bytes (cbor_conformance_test) and the
;; CPython int-contract rows (int_contract_test)
;; =============================================================================

(defn- canonical-cases
  [t]
  (filter #(= t (get-in % ["input" "t"])) (cfx/cases-of-kind "canonical")))


(defn- byte-string-head
  "The CBOR head hex of a byte string of `len` bytes (< 256)."
  [len]
  (if (< len 24)
    (integer/to-radix-string (integer/from-native (+ 0x40 len)) 16)
    (str "58" (when (< len 16) "0") (integer/to-radix-string (integer/from-native len) 16))))


(deftest magnitude-bytes-reproduce-the-corpus-bignums
  (let [two-64 (integer/shift-left-big (integer/from-native 1) 64)
        ;; keep, not `for`: ClojureDart's chunked `for` can hand the body a
        ;; nil past 32 elements
        rows (keep (fn [c]
                     (let [v (big (get-in c ["input" "v"]))
                           neg? (neg? (integer/compare-big v (integer/from-native 0)))
                           m (if neg?
                               (integer/sub (integer/sub (integer/from-native 0) v) (integer/from-native 1))
                               v)]
                       (when (<= 0 (integer/compare-big m two-64))
                         [(get c "id") (get c "hex") neg? m])))
                   (canonical-cases "int"))]
    (is (<= 4 (count rows)) "the corpus holds tag 2 and tag 3 cases")
    (doseq [[id hex neg? m] rows
            :let [bs (integer/magnitude-bytes m)]]
      (is (= hex (str (if neg? "c3" "c2") (byte-string-head (bytes/length bs)) (cfx/bytes->hex bs)))
          id)))
  (testing "zero is empty, a negative is refused"
    (is (= 0 (bytes/length (integer/magnitude-bytes (integer/from-native 0)))))
    (is (= [1 0] (bytes/to-vector (integer/magnitude-bytes (integer/from-native 256)))))
    (is (= :out-of-range (refusal #(integer/magnitude-bytes (big "-1")))))))


(deftest corpus-integers-round-trip-through-parse-decimal
  (doseq [c (canonical-cases "int")
          :let [v (get-in c ["input" "v"])]]
    (is (= v (dec-text (big v))) (get c "id"))))


(def ^:private int-contract
  (delay
    (into {}
          (keep (fn [l]
                  (let [[s c & more] (str/split l #" ")]
                    (when (and c (not (#{"profile" "op" "generator"} s)))
                      [[s c] (str/join " " more)]))))
          (remove str/blank? (str/split-lines (cfx/read-path "test/resources/yang/python/int-contract-v1.txt"))))))


(deftest int-contract-rows-recompute-through-the-profile
  (let [rows @int-contract
        names (distinct (keep (fn [[[s c] _]] (when (and (= c "bits") (not (str/starts-with? s "f:"))) s))
                              rows))]
    (is (<= 30 (count names)))
    (doseq [s names
            :let [d (rows [s "dec"])
                  b (big d)]]
      (is (= d (dec-text b)) s)
      (is (= (rows [s "hex"]) (integer/to-radix-string b 16)) s)
      (is (= (rows [s "bits"]) (str (integer/magnitude-bit-length b))) s))))


;; =============================================================================
;; float: bits, fields, finite, integral (the corpus float cases, NaN
;; 7ff8..., -0.0)
;; =============================================================================

(deftest float-bits-reproduce-the-corpus
  (let [cases (canonical-cases "float64")]
    (is (<= 15 (count cases)))
    (doseq [c cases
            :let [bits (get-in c ["input" "bits"])
                  x (float/bits->float64 bits)
                  nan? (integer/nan-float? x)]]
      (is (= (subs (get c "hex") (- (count (get c "hex")) 16)) (float/float64->bits x))
          (str (get c "id") ": the frozen payload"))
      (is (= (if nan? "7ff8000000000000" bits) (float/float64->bits x)) (get c "id"))
      (is (= (not (or nan? (integer/infinite-float? x))) (float/finite? x)) (get c "id")))))


(deftest float-table-a-test
  (testing "bits"
    (is (= "8000000000000000" (float/float64->bits -0.0)))
    (is (= "0000000000000000" (float/float64->bits 0.0)))
    (is (= "3ff8000000000000" (float/float64->bits 1.5)))
    (is (= "7ff8000000000000" (float/float64->bits (float/bits->float64 "fff8000000000001"))))
    (doseq [h ["" "3ff" "3FF8000000000000" "3ff800000000000g" "3ff80000000000000" nil 1.5]]
      (is (= :malformed-number (refusal #(float/bits->float64 h))) (pr-str h))))
  (testing "fields: sign, biased exponent, fraction as a big integer"
    (let [show (fn [x] (let [[s e f] (float/fields x)] [s e (dec-text f)]))]
      (is (= [false 1023 "2251799813685248"] (show 1.5)))
      (is (= [true 0 "0"] (show -0.0)))
      (is (= [false 0 "1"] (show (float/bits->float64 "0000000000000001"))))
      (is (= [false 2047 "0"] (show ##Inf)))
      (is (= [false 2047 "2251799813685248"] (show ##NaN)) "every NaN is 7ff8...")
      (is (= [true 2046 "4503599627370495"] (show (float/bits->float64 "ffefffffffffffff"))))))
  (testing "finite? and integral?"
    (is (float/finite? 0.0))
    (is (not (float/finite? ##Inf)))
    (is (not (float/finite? ##NaN)))
    (is (float/integral? -0.0))
    (is (float/integral? 1.0))
    (is (float/integral? (float/bits->float64 "4330000000000001")) "2^52 + 1")
    (is (not (float/integral? 1.5)))
    (is (not (float/integral? (float/bits->float64 "0000000000000001"))))
    (is (not (float/integral? ##Inf)))
    (is (not (float/integral? ##NaN)))))


;; =============================================================================
;; float: the integer-v3 rows (integer_test float-fixture-test) run directly
;; against the float-text kernel's ratio->float and trunc-big
;; =============================================================================

(def ^:private v3-rows
  (delay
    (reduce (fn [acc l]
              (let [[op & args] (str/split l #" ")]
                (update acc op (fnil conj []) (vec args))))
            {}
            (drop 2 (remove str/blank? (str/split-lines (cfx/read-path "test/resources/yin/vm/integer-v3.txt")))))))


(defn- magnitude
  [b]
  (if (neg? (integer/compare-big b (integer/from-native 0)))
    (integer/sub (integer/from-native 0) b)
    b))


(defn- negative?
  [b]
  (neg? (integer/compare-big b (integer/from-native 0))))


(defn- float-text
  [r]
  (if (= ::float/float-overflow r) "float-overflow" (float/float64->bits r)))


(deftest integer-v3-rows-through-the-kernel
  (let [rows @v3-rows]
    (is (= {"to-float" 187 "true-div" 96 "from-float" 29}
           (into {} (map (fn [op] [op (count (rows op))])) ["to-float" "true-div" "from-float"])))
    (testing "to-float: float(int), ties to even, overflow as the reason"
      (is (= []
             (into []
                   (keep (fn [[a e :as row]]
                           (let [x (big a)
                                 r (float-text (float/ratio->float (magnitude x) (integer/from-native 1) (negative? x)))]
                             (when-not (= e r) [row r]))))
                   (rows "to-float")))))
    (testing "true-div: int / int correctly rounded, huge and subnormal"
      (is (= []
             (into []
                   (keep (fn [[a b e :as row]]
                           (let [x (big a)
                                 y (big b)
                                 r (float-text (float/ratio->float (magnitude x) (magnitude y)
                                                                   (not= (negative? x) (negative? y))))]
                             (when-not (= e r) [row r]))))
                   (rows "true-div")))))
    (testing "from-float: exact truncation"
      (is (= []
             (into []
                   (keep (fn [[x e :as row]]
                           (let [r (dec-text (float/trunc-big (float/bits->float64 x)))]
                             (when-not (= e r) [row r]))))
                   (rows "from-float")))))))


;; =============================================================================
;; number: decimal and ratio construction, access, carriers (the corpus
;; decimal and ratio cases, cbor_test's window and zero-denominator refusals)
;; =============================================================================

(deftest corpus-decimals-and-ratios-through-the-profile
  (doseq [c (canonical-cases "decimal")
          :let [{e "exponent" m "mantissa"} (get c "input")
                d (number/decimal-of (n e) (n m))]]
    (is (number/decimal? d) (get c "id"))
    (is (= e (str (number/decimal-exponent d))) (get c "id"))
    (is (= m (dec-text (number/decimal-mantissa d))) (get c "id"))
    (testing "equal to today's codec field by field"
      (let [old (cbor/decimal (n e) (n m))]
        (is (= (str (cbor/decimal-exponent old)) (str (number/decimal-exponent d))))
        (is (= (dec-text (integer/promote (cbor/decimal-mantissa old)))
               (dec-text (number/decimal-mantissa d)))))))
  (doseq [c (canonical-cases "ratio")
          :let [{a "num" b "den"} (get c "input")
                r (number/ratio-of (n a) (n b))
                old (cbor/ratio (n a) (n b))]]
    (is (number/ratio? r) (get c "id"))
    (is (= (dec-text (integer/promote (cbor/ratio-numerator old))) (dec-text (number/ratio-numerator r)))
        (get c "id"))
    (is (= (dec-text (integer/promote (cbor/ratio-denominator old))) (dec-text (number/ratio-denominator r)))
        (get c "id"))))


(deftest number-laws-test
  (testing "ratios reduce with the sign on the numerator"
    (let [r (number/ratio-of 6 -4)]
      (is (= ["-3" "2"] [(dec-text (number/ratio-numerator r)) (dec-text (number/ratio-denominator r))])))
    (let [r (number/ratio-of 0 -3)]
      (is (= ["0" "1"] [(dec-text (number/ratio-numerator r)) (dec-text (number/ratio-denominator r))])))
    (is (number/ratio? (number/ratio-of 3 1)) "denominator 1 keeps rational kind")
    (is (= (number/ratio-of 1 1) (number/ratio-of 2 2)))
    (is (= (hash (number/ratio-of 1 1)) (hash (number/ratio-of 2 2))))
    (is (not= (number/ratio-of 1 1) (number/ratio-of 2 1))))
  (testing "refusals"
    (is (= :malformed-number (refusal #(number/ratio-of 1 0))))
    (is (= :unsupported-value (refusal #(number/ratio-of 1.5 2))))
    (is (= :unsupported-value (refusal #(number/decimal-of 0 2.5))))
    (is (= :unsupported-value (refusal #(number/decimal-of 0 "1"))))
    (is (= :malformed-number (refusal #(number/decimal-of 2147483649 1))))
    (is (= :malformed-number (refusal #(number/decimal-of -2147483648 1)))))
  (testing "the decimal window's ends are accepted on every host"
    (is (= 2147483648 (number/decimal-exponent (number/decimal-of 2147483648 1))))
    (is (= -2147483647 (number/decimal-exponent (number/decimal-of -2147483647 1)))))
  (testing "decimal carriers equal by fields"
    (is (= (dec-text (number/decimal-mantissa (number/decimal-of -1 10))) "10"))
    (is (= (number/decimal-of -1 10) (number/decimal-of -1 10)))
    (is (not= (number/decimal-exponent (number/decimal-of -1 10))
              (number/decimal-exponent (number/decimal-of 0 1)))
        "scale is kept")
    ;; host `=` on the JVM's BigDecimal ignores scale (§2.5 dialect
    ;; semantics); the carriers elsewhere compare fields
    #?(:cljd (is (not= (number/decimal-of -1 10) (number/decimal-of 0 1)))
       :clj nil
       :cljs (is (not= (number/decimal-of -1 10) (number/decimal-of 0 1)))))
  (testing "float64 content keeps its kind and its value"
    (is (= "3ff0000000000000" (float/float64->bits (number/float-value (number/float64-of 1)))))
    (is (= "8000000000000000" (float/float64->bits (number/float-value (number/float64-of -0.0)))))
    (is (= (number/float-value (number/float64-of 1.5))
           (number/float-value (number/float64-of (number/float64-of 1.5))))))
  #?(:cljs
     (testing "Float64 (table C): equal by bits, NaN equal to NaN, no implicit coercion"
       (is (= (number/float64-of ##NaN) (number/float64-of ##NaN)))
       (is (= (hash (number/float64-of ##NaN)) (hash (number/float64-of (float/bits->float64 "7ff8000000000001")))))
       (is (not= (number/float64-of 0.0) (number/float64-of -0.0)))
       (is (= (number/float64-of 1) (number/float64-of 1.0)))
       (is (= :carrier-coercion (refusal #(+ 1 (number/float64-of 1)))))
       (is (= "#dao.jing/float64 1.5" (pr-str (number/float64-of 1.5))))
       (is (= "#dao.jing/ratio [1 2]" (pr-str (number/ratio-of 1 2))))
       (is (= "#dao.jing/decimal [-1 15]" (pr-str (number/decimal-of -1 15))))))
  #?(:cljd nil
     :clj (testing "the JVM's carriers"
            (is (instance? java.math.BigDecimal (number/decimal-of -1 15)))
            (is (instance? clojure.lang.Ratio (number/ratio-of 1 2)))
            (is (= "#dao.jing/ratio [3 1]" (pr-str (number/ratio-of 3 1)))))))


;; =============================================================================
;; text: code units, units->string, code points (data_test non-bmp-,
;; substring-char-at-, code-points-str-compare-test) and the corpus strings
;; =============================================================================

(def ^:private grin (text/units->string [0xD83D 0xDE00]))


(deftest code-units-and-code-points-test
  (is (= 2 (text/unit-count grin)))
  (is (= [0xD83D 0xDE00] [(text/code-unit grin 0) (text/code-unit grin 1)]))
  (is (= [97 0x1F600] (text/code-points (str "a" grin))))
  (is (= [97 0x1F600 98] (text/code-points (str "a" grin "b"))))
  (is (= [] (text/code-points "")))
  #?(:jolt nil
     :default
     (do
       (is (= [0xD800 97] (text/code-points (text/units->string [0xD800 97]))) "a lone high is its own")
       (is (= [0xDE00] (text/code-points (text/units->string [0xDE00]))) "a lone low is its own")
       (is (= [0xDE00 0xD83D] (text/code-points (text/units->string [0xDE00 0xD83D]))) "a reversed pair")))
  (is (= "" (text/units->string [])))
  #?(:jolt nil
     :default
     (is (= 1 (text/unit-count (text/units->string [0xD800]))) "an unpaired surrogate survives as one unit")))


(defn- string-head-len
  "The number of hex digits of the CBOR text-string head starting `hex`."
  [hex]
  (let [b (first (cfx/hex->ints (subs hex 0 2)))]
    (cond (< b 0x78) 2 (= b 0x78) 4 :else 6)))


(defn- input-text
  "The string a corpus `str` input names: text, or `{\"utf16\" [unit-hex ...]}`."
  [v]
  (if (string? v)
    v
    (text/units->string (mapv #(let [[hi lo] (cfx/hex->ints %)] (+ (* 256 hi) lo))
                              (get v "utf16")))))


(deftest corpus-strings-through-utf8
  (testing "every canonical string encodes to its frozen payload and back"
    (let [cases (canonical-cases "str")]
      (is (<= 15 (count cases)))
      (doseq [c cases
              :let [s (input-text (get-in c ["input" "v"]))
                    hex (get c "hex")
                    payload (subs hex (string-head-len hex))
                    bs (text/utf8-encode s)]]
        (is (= payload (cfx/bytes->hex bs)) (get c "id"))
        (is (= s (text/utf8-decode bs)) (get c "id")))))
  (testing "every unpaired-surrogate string refuses to encode"
    #?(:jolt nil
       :default
       (let [cases (filter #(and (= "unpaired-surrogate" (get % "refusal"))
                                 (= "str" (get-in % ["input" "t"])))
                           (cfx/cases-of-kind "encode-refusal"))]
         (is (<= 4 (count cases)))
         (doseq [c cases]
           (is (= :unpaired-surrogate (refusal #(text/utf8-encode (input-text (get-in c ["input" "v"])))))
               (get c "id")))))))


;; =============================================================================
;; §2.4 (i): P entries leave every argument byte-identical; "fresh" results
;; are fresh
;; =============================================================================

(defn- lcg-bytes
  "A deterministic pseudo-random byte vector of length `len` from `seed`."
  [seed len]
  (loop [x seed out []]
    (if (= len (count out))
      out
      (let [x' (mod (+ (* x 1103515245) 12345) 2147483648)]
        (recur x' (conj out (mod (quot x' 65536) 256)))))))


(deftest p-entries-leave-their-arguments-unchanged
  (doseq [seed (range 1 41)
          :let [len (mod (* seed 7) 23)
                v (lcg-bytes seed len)
                a (bytes/from-vector v)
                b (bytes/from-vector (lcg-bytes (inc seed) len))
                before-a (cfx/bytes->hex a)
                before-b (cfx/bytes->hex b)]]
    (bytes/bytes? a)
    (bytes/length a)
    (when (pos? len) (bytes/get a (mod seed len)))
    (bytes/slice a 0 len)
    (bytes/slice a (quot len 2) len)
    (bytes/equal? a b)
    (bytes/to-vector a)
    (refusal #(text/utf8-decode a))
    (refusal #(text/utf8-decode a 0 len))
    (refusal #(bytes/get a len))
    (refusal #(bytes/slice a 1 0))
    (is (= before-a (cfx/bytes->hex a)) (str "seed " seed))
    (is (= before-b (cfx/bytes->hex b)) (str "seed " seed))
    (is (= v (bytes/to-vector a)) (str "seed " seed))))


(deftest fresh-results-are-fresh
  (testing "slice and from-vector never alias their argument"
    (let [src (bytes/from-vector [1 2 3 4])
          s (bytes/slice src 0 4)]
      (bytes/set! s 0 9)
      (is (= [1 2 3 4] (bytes/to-vector src)))
      (bytes/set! src 1 8)
      (is (= [9 2 3 4] (bytes/to-vector s)))))
  (testing "alloc is zeroed and distinct per call"
    (let [a (bytes/alloc 3)
          b (bytes/alloc 3)]
      (bytes/set! a 0 1)
      (is (= [0 0 0] (bytes/to-vector b)))))
  (testing "utf8-encode and magnitude-bytes answer fresh bytes"
    (let [e (text/utf8-encode "abc")]
      (bytes/set! e 0 0x7a)
      (is (= [0x61 0x62 0x63] (bytes/to-vector (text/utf8-encode "abc")))))
    (let [b (big "18446744073709551617")
          m (integer/magnitude-bytes b)]
      (bytes/set! m 0 0)
      (is (= "18446744073709551617" (dec-text b)))
      (is (= [1 0 0 0 0 0 0 0 1] (bytes/to-vector (integer/magnitude-bytes b)))))))

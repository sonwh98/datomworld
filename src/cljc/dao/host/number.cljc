(ns dao.host.number
  "The Jing numeric carriers of the primitive profile
   (docs/design/dao.stream.host-boundary.md §4.2, table A) and their host
   construction and access, moved from `dao.jing.cbor` (slice H1; H2
   switches the codec to them and drops its own copy).

   Every entry is kind P (§2.4): pure, total over its declared domain,
   synchronous, allocating only fresh values. Oracle: table D's number
   rows, re-asserted through this namespace by
   `dao.host.conformance-test`.

   | Content  | JVM                         | JS                 | Dart       |
   |----------|-----------------------------|--------------------|------------|
   | float64  | `Double`                    | `Float64` carrier  | `double`   |
   | decimal  | `BigDecimal`                | `Decimal` carrier  | `Decimal`  |
   | ratio    | `clojure.lang.Ratio`, or    | `Rational` carrier | `Rational` |
   |          | `Rational` for denominator 1|                    |            |

   Table C laws of the carriers (a port must meet them): `Float64` is
   equal to another `Float64` iff their bits are equal, every NaN equal to
   every NaN (and `-0.0` unequal to `0.0`); its hash is that of its bit
   text, every NaN hashing alike; it refuses implicit numeric coercion
   (`valueOf`) `:carrier-coercion`. `Rational` is equal iff numerator and
   denominator are equal, `Decimal` iff exponent and mantissa are; each
   hashes as the vector of its fields. They print as
   `#dao.jing/float64 v`, `#dao.jing/ratio [n d]` and
   `#dao.jing/decimal [e m]`.

   Until H2 these are distinct types from `dao.jing.cbor`'s copies, so a
   cbor carrier is not recognised here, nor the reverse. Integer arguments
   are exact-integer carriers of `dao.host.integer` (`exact-integer?`) or
   its host big integers; anything else is refused `:unsupported-value`.
   A refusal throws `ex-info` whose data carries `:dao.host/refusal`, a
   class keyword, and `:dao.host/entry`. Every reader conditional names
   `:cljd` first, because the ClojureDart build also reads `:clj`."
  (:refer-clojure :exclude [decimal? ratio?])
  (:require
    [dao.host.integer :as integer])
  #?@(:cljd [(:import ["dart:core" BigInt])]
      :clj [(:import (java.math BigDecimal BigInteger))]))


(defn- refuse
  [entry cls detail]
  (throw (ex-info (str "dao.host refused: " entry " " (name cls) " (" detail ")")
                  {:dao.host/refusal cls :dao.host/entry entry :detail detail})))


(defn- big
  "The host big integer of exact-integer carrier `x`, or a refusal."
  [entry x]
  (if (integer/exact-integer? x)
    (integer/promote x)
    (refuse entry :unsupported-value "not an exact integer")))


(def ^:private zero (integer/from-native 0))


;; =============================================================================
;; Carriers (table C)
;; =============================================================================

#?(:cljs
   (defn- double-bits-hex
     "The 16 hex digits of a JS number's IEEE-754 bits."
     [d]
     (let [view (js/DataView. (js/ArrayBuffer. 8))]
       (.setFloat64 view 0 d)
       (apply str (map #(let [b (.getUint8 view %)]
                          (str (when (< b 16) "0") (.toString b 16)))
                       (range 8))))))


#?(:cljs
   (deftype Float64
     [v]

     Object

     (toString [_] (str v))


     ;; numeric and default coercion refuses: `carrier + 1`, `carrier < 3`
     ;; and `"" + carrier` would otherwise go through toString and answer
     ;; a string or a bare Number that lost the float's kind. String
     ;; conversion (`str`, `String`, `.toString`) asks toString first and is
     ;; unaffected; read the value through an explicit accessor instead.
     (valueOf
       [_]
       (refuse 'Float64 :carrier-coercion
               "a float64 carrier has no implicit numeric value"))


     IEquiv

     (-equiv
       [_ other]
       (and (instance? Float64 other)
            (or (and (js/isNaN v) (js/isNaN (.-v ^Float64 other)))
                (js/Object.is v (.-v ^Float64 other)))))


     IHash

     (-hash [_] (hash (if (js/isNaN v) "nan" (double-bits-hex v))))


     IPrintWithWriter

     (-pr-writer
       [_ writer _]
       (-write writer (str "#dao.jing/float64 " (pr-str v))))))


(deftype Rational
  [numerator denominator]
  #?@(:cljd [cljd.core/IEquiv
             (-equiv [_ other]
                     (and (instance? Rational other)
                          (= numerator (.-numerator ^Rational other))
                          (= denominator (.-denominator ^Rational other))))
             cljd.core/IHash
             (-hash [_] (hash [(str numerator) (str denominator)]))]
      :clj [Object
            (equals [_ other]
                    (and (instance? Rational other)
                         (= numerator (.-numerator ^Rational other))
                         (= denominator (.-denominator ^Rational other))))
            (hashCode [_] (hash [numerator denominator]))
            (toString [_] (str numerator "/" denominator))
            clojure.lang.IHashEq
            (hasheq [_] (hash [numerator denominator]))]
      :cljs [IEquiv
             (-equiv [_ other]
                     (and (instance? Rational other)
                          (= numerator (.-numerator ^Rational other))
                          (= denominator (.-denominator ^Rational other))))
             IHash
             (-hash [_] (hash [(str numerator) (str denominator)]))
             IPrintWithWriter
             (-pr-writer [_ writer _]
                         (-write writer (str "#dao.jing/ratio [" numerator " "
                                             denominator "]")))]))


#?(:cljd
   (deftype Decimal
     [exponent mantissa]

     cljd.core/IEquiv

     (-equiv
       [_ other]
       (and (instance? Decimal other)
            (= exponent (.-exponent ^Decimal other))
            (= mantissa (.-mantissa ^Decimal other))))


     cljd.core/IHash

     (-hash [_] (hash [exponent (str mantissa)])))
   :cljs
   (deftype Decimal
     [exponent mantissa]

     IEquiv

     (-equiv
       [_ other]
       (and (instance? Decimal other)
            (= exponent (.-exponent ^Decimal other))
            (= mantissa (.-mantissa ^Decimal other))))


     IHash

     (-hash [_] (hash [exponent (str mantissa)]))


     IPrintWithWriter

     (-pr-writer
       [_ writer _]
       (-write writer (str "#dao.jing/decimal [" exponent " " mantissa "]")))))


#?(:cljd nil
   :clj
   (defmethod print-method Rational
     [^Rational r ^java.io.Writer w]
     (.write w (str "#dao.jing/ratio [" (.-numerator r) " "
                    (.-denominator r) "]"))))


;; =============================================================================
;; Table A entries
;; =============================================================================

(defn float64-of
  "P. Floating-point content with the value of host number `x`: a
   `Double` on the JVM, a `double` on Dart, the `Float64` carrier on JS
   (so integral floats such as 1.0 keep their kind); a JS `Float64` is
   re-wrapped."
  [x]
  #?(:cljd (.toDouble ^num x)
     :clj (double x)
     :cljs (Float64. (if (instance? Float64 x) (.-v x) (js/Number x)))))


(defn float-value
  "P. The host double of floating-point content `x` (on JS, a `Float64`
   carrier or a bare number)."
  [x]
  #?(:cljd x
     :clj (double x)
     :cljs (if (instance? Float64 x) (.-v x) x)))


(defn decimal?
  "P. True for decimal content: `BigDecimal` on the JVM, the `Decimal`
   carrier on JS and Dart."
  [x]
  #?(:cljd (instance? Decimal x)
     :clj (instance? BigDecimal x)
     :cljs (instance? Decimal x)))


(def decimal-exponent-min
  "Smallest decimal exponent every host accepts: the JVM scale
   (-exponent) is a 32-bit int, so -(2^31 - 1)."
  -2147483647)


(def decimal-exponent-max
  "Largest decimal exponent every host accepts: 2^31 (scale -2^31)."
  2147483648)


(defn decimal-of
  "P, fresh. Decimal content `mantissa x 10^exponent` with exactly this
   scale, for exact integers `exponent` and `mantissa`. Every host accepts
   exactly the exponents in [decimal-exponent-min, decimal-exponent-max],
   the JVM `BigDecimal` window, so acceptance never depends on the host;
   outside it the value is refused `:malformed-number`."
  [exponent mantissa]
  (let [e (big 'decimal-of exponent)
        m (big 'decimal-of mantissa)]
    (when-not (and (<= 0 (integer/compare-big e (integer/promote decimal-exponent-min)))
                   (<= (integer/compare-big e (integer/promote decimal-exponent-max)) 0))
      (refuse 'decimal-of :malformed-number "decimal exponent out of range"))
    #?(:cljd (Decimal. (.toInt ^BigInt e) m)
       :clj (BigDecimal. ^BigInteger m (int (- (.longValue ^BigInteger e))))
       :cljs (Decimal. (js/Number e) m))))


(defn decimal-exponent
  "P. The exponent of decimal content `d`, a native integer."
  [d]
  #?(:cljd (.-exponent ^Decimal d)
     :clj (- (.scale ^BigDecimal d))
     :cljs (.-exponent ^Decimal d)))


(defn decimal-mantissa
  "P. The exact mantissa of decimal content `d` as a host big integer."
  [d]
  #?(:cljd (.-mantissa ^Decimal d)
     :clj (.unscaledValue ^BigDecimal d)
     :cljs (.-mantissa ^Decimal d)))


(defn ratio?
  "P. True for rational content: `clojure.lang.Ratio` or the `Rational`
   carrier."
  [x]
  #?(:cljd (instance? Rational x)
     :clj (or (instance? clojure.lang.Ratio x) (instance? Rational x))
     :cljs (instance? Rational x)))


(defn ratio-of
  "P, fresh. Rational content `numerator/denominator` for exact integers,
   reduced, with the sign on the numerator; both fields are host big
   integers. Denominator 1 keeps rational kind (a `Rational` carrier); on
   the JVM other ratios are `clojure.lang.Ratio`. A zero denominator is
   refused `:malformed-number`."
  [numerator denominator]
  (let [n (big 'ratio-of numerator)
        d (big 'ratio-of denominator)]
    (when (zero? (integer/compare-big d zero))
      (refuse 'ratio-of :malformed-number "zero denominator"))
    (let [[n d] (if (neg? (integer/compare-big d zero))
                  [(integer/sub zero n) (integer/sub zero d)]
                  [n d])
          g (integer/gcd n d)
          n (integer/quot-trunc n g)
          d (integer/quot-trunc d g)]
      #?(:cljd (Rational. n d)
         :clj (if (= (integer/from-native 1) d)
                (Rational. n d)
                (clojure.lang.Ratio. ^BigInteger n ^BigInteger d))
         :cljs (Rational. n d)))))


(defn ratio-numerator
  "P. The numerator of rational content `r`, a host big integer."
  [r]
  #?(:cljd (.-numerator ^Rational r)
     :clj (if (instance? Rational r)
            (.-numerator ^Rational r)
            (.numerator ^clojure.lang.Ratio r))
     :cljs (.-numerator ^Rational r)))


(defn ratio-denominator
  "P. The denominator of rational content `r`, a positive host big
   integer."
  [r]
  #?(:cljd (.-denominator ^Rational r)
     :clj (if (instance? Rational r)
            (.-denominator ^Rational r)
            (.denominator ^clojure.lang.Ratio r))
     :cljs (.-denominator ^Rational r)))

(ns dao.jing.cbor
  "DaoJing's canonical CBOR value codec and portable numeric operations
   (docs/design/dao.jing.cbor.md, phases J1 and J2: JVM, ClojureScript
   and Dart).

   `encode` turns one supported value into its canonical payload bytes;
   `decode` accepts exactly one canonical payload and returns its value.
   The byte contract is the frozen corpus test/resources/dao/jing/cbor-v1.json
   and the rulings in its README; this namespace implements it, it does not
   define it.

   Encoding normalizes a value into a wire tree (see `dao.jing.cbor.boring`)
   and lets Boring write the bytes:

   * keywords and symbols: tag 27 `dao.jing/keyword` / `dao.jing/symbol`
     over `[namespace name]`, read from the identifier's fields;
   * lists and finite seqs: tag 27 `dao.jing/list` over the item array;
     vectors are plain arrays;
   * floating point: tag 27 `dao.jing/float64` over eight big-endian bytes,
     float32 widened exactly, every NaN made 7ff8000000000000;
   * integers: CBOR integers, tags 2/3 beyond 64 bits;
   * decimals: tag 4 `[exponent mantissa]`; ratios: tag 30, reduced;
   * sets: tag 258 over elements sorted by their canonical bytes;
   * metadata on collections and symbols: tag 27 `clojure/with-meta` over
     `[meta value]`, outermost, after stripping unqualified :line :column
     :end-line :end-column (validated before stripping) and omitting empty
     metadata.

   Decoding reads the bytes with Jing's own structural reader, which checks
   every tag, frame name and payload shape before any host conversion (the
   plan's \"validate before any lossy host conversion\"), then requires the
   decoded value to re-encode to the identical bytes. Boring's decoder is not
   used: its native mappings (tag-30 ratios collapsing denominator 1,
   stringref and date tags, with-meta, a dropped trailing index frame, and
   JavaScript numbers that cannot tell 1.0 from 1) would decide Jing bytes.

   Every refusal is an ex-info whose data carries `::refusal`, one of the
   corpus refusal classes as a keyword (`:non-canonical`,
   `:unpaired-surrogate`, ...); `refusal` reads it.

   Numbers: the JVM keeps its native exact kinds (long, BigInt, Double,
   BigDecimal, Ratio) and adds a `Rational` carrier for denominator-1
   ratios. ClojureScript uses safe-integer numbers and BigInt for integers,
   and carriers for float64, decimal and rational content. Host `=` cannot
   compare carriers with native numbers symmetrically, so the portable
   operations are explicit functions: `num=`, `num-hash`, `num-compare`, and
   the recursive `equiv` / `equiv-hash`.

   Dart (J2): the same reader, profile and portable operations run on
   ClojureDart. The byte writer there is the pinned `cbor` 6.5.1 package
   behind `dao.jing.cbor.cljd`, which implements the same wire vocabulary as
   `dao.jing.cbor.boring`. Dart integers are 64-bit ints and BigInt, floats
   are native doubles (a distinct type from int on the Dart VM), and decimals
   and ratios use the Decimal and Rational carriers. Every host reader
   conditional names `:cljd` first."
  (:refer-clojure :exclude [decimal? ratio?])
  (:require
    #?@(:cljd [["dart:convert" :as convert]
               [dao.jing.cbor.cljd :as wire]]
        :default [[dao.jing.cbor.boring :as wire]]))
  #?@(:cljd [(:import
               ["dart:core" BigInt FormatException String]
               ["dart:typed_data" ByteData Uint8List])]
      :clj [(:import
              (java.math
                BigDecimal
                BigInteger)
              (java.nio
                ByteBuffer)
              (java.nio.charset
                CharacterCodingException
                CodingErrorAction
                StandardCharsets))]))


;; ==========================================================================
;; Refusals
;; ==========================================================================

(defn- refuse
  "Throw the refusal `cls` (a corpus refusal class keyword)."
  ([cls] (refuse cls nil))
  ([cls detail]
   (throw (ex-info (str "dao.jing.cbor refused: " (name cls)
                        (when detail (str " (" detail ")")))
                   {::refusal cls :detail detail}))))


(defn- type-name
  "The host type of x, for refusal details."
  [x]
  #?(:cljd (str (.-runtimeType x))
     :default (str (type x))))


(defn refusal
  "The refusal class keyword of a thrown codec refusal, or nil."
  [ex]
  (::refusal (ex-data ex)))


;; ==========================================================================
;; Exact host big integers: BigInteger on the JVM, BigInt on JavaScript
;; ==========================================================================

#?(:cljs
   (defn- bigint?
     [x]
     (identical? "bigint" (js* "typeof ~{}" x))))


(defn- big
  "An exact host big integer from a host integer or a decimal string.
   Anything else (a float, a non-integral number) is refused loudly
   rather than truncated."
  [x]
  #?(:cljd (cond (dart/is? x BigInt) x
                 (dart/is? x int) (BigInt.from x)
                 (string? x) (BigInt.parse x)
                 :else (refuse :unsupported-value
                               (str "not an exact integer: " x)))
     :clj (cond (instance? BigInteger x) x
                (instance? clojure.lang.BigInt x)
                (.toBigInteger ^clojure.lang.BigInt x)
                (string? x) (BigInteger. ^String x)
                (or (instance? Long x) (instance? Integer x)
                    (instance? Short x) (instance? Byte x))
                (BigInteger/valueOf (long x))
                :else (refuse :unsupported-value
                              (str "not an exact integer: " (type x))))
     :cljs (if (and (number? x) (not (js/Number.isInteger x)))
             (refuse :unsupported-value (str "not an exact integer: " x))
             (js/BigInt x))))


(def ^:private zero (big 0))
(def ^:private one (big 1))
(def ^:private two (big 2))
(def ^:private ten (big 10))


(defn- b+
  [a b]
  #?(:cljd (. ^BigInt a "+" b)
     :clj (.add ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} + ~{})" a b)))


(defn- b-
  [a b]
  #?(:cljd (. ^BigInt a "-" b)
     :clj (.subtract ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} - ~{})" a b)))


(defn- b*
  [a b]
  #?(:cljd (. ^BigInt a "*" b)
     :clj (.multiply ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} * ~{})" a b)))


(defn- bquot
  "Quotient truncated toward zero."
  [a b]
  #?(:cljd (. ^BigInt a "~/" b)
     :clj (.divide ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} / ~{})" a b)))


(defn- bcmp
  [a b]
  ;; Dart's BigInt.compareTo returns any negative or positive int, not
  ;; just -1 or 1; every host answers exactly -1, 0 or 1.
  #?(:cljd (let [c (.compareTo ^BigInt a b)] (cond (neg? c) -1 (pos? c) 1 :else 0))
     :clj (.compareTo ^BigInteger a ^BigInteger b)
     :cljs (cond (js* "(~{} < ~{})" a b) -1
                 (js* "(~{} > ~{})" a b) 1
                 :else 0)))


(defn- bsign
  [a]
  (bcmp a zero))


#?(:cljs
   (defn- brem
     [a b]
     (js* "(~{} % ~{})" a b)))


#?(:cljs
   (defn- babs
     [a]
     (if (neg? (bsign a)) (b- zero a) a)))


(defn- bgcd
  [a b]
  #?(:cljd (.gcd ^BigInt a b)
     :clj (.gcd ^BigInteger a ^BigInteger b)
     :cljs (loop [x (babs a) y (babs b)]
             (if (zero? (bsign y)) x (recur y (brem x y))))))


(defn- bpow
  "base raised to the non-negative host integer n, by squaring."
  [base n]
  (loop [result one b base n n]
    (if (zero? n)
      result
      (recur (if (odd? n) (b* result b) result)
             (b* b b)
             (quot n 2)))))


(def ^:private two-64 (bpow two 64))
(def ^:private neg-two-64 (b- zero two-64))


(defn- b->int
  "The host integer for exact b: long or BigInt on the JVM, a safe
   integer number or BigInt on JavaScript, a 64-bit int or BigInt on Dart."
  [b]
  #?(:cljd (if (.-isValidInt ^BigInt b) (.toInt ^BigInt b) b)
     :clj (if (< (.bitLength ^BigInteger b) 64)
            (.longValue ^BigInteger b)
            (clojure.lang.BigInt/fromBigInteger b))
     :cljs (let [n (js/Number b)]
             (if (js/Number.isSafeInteger n) n b))))


(defn- magnitude-bytes
  "Minimal big-endian bytes of non-negative b; empty for zero."
  [b]
  #?(:cljd (let [ff (BigInt.from 255)]
             (loop [b b acc ()]
               (if (zero? (bsign b))
                 (Uint8List.fromList (vec acc))
                 (recur (. ^BigInt b ">>" 8) (conj acc (.toInt ^BigInt (. ^BigInt b "&" ff)))))))
     :clj (let [raw (.toByteArray ^BigInteger b)]
            (if (and (pos? (alength raw)) (zero? (aget raw 0)))
              (java.util.Arrays/copyOfRange raw 1 (alength raw))
              raw))
     :cljs (if (zero? (bsign b))
             (js/Uint8Array. 0)
             (let [h (.toString b 16)
                   h (if (odd? (count h)) (str "0" h) h)
                   out (js/Uint8Array. (quot (count h) 2))]
               (dotimes [i (alength out)]
                 (aset out i (js/parseInt (subs h (* 2 i) (+ 2 (* 2 i))) 16)))
               out))))


;; ==========================================================================
;; Host byte arrays
;; ==========================================================================

(defn- blen
  "The length of a host byte array."
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :default (alength bs)))


(defn- byte-at
  [bs i]
  #?(:cljd (. ^Uint8List bs "[]" i)
     :clj (bit-and (aget ^bytes bs i) 0xff)
     :cljs (aget bs i)))


(defn- copy-range
  [bs start end]
  #?(:cljd (Uint8List.fromList (.sublist ^Uint8List bs start end))
     :clj (java.util.Arrays/copyOfRange ^bytes bs (int start) (int end))
     :cljs (.slice bs start end)))


(defn- span-big
  "Unsigned big-endian integer of bs[start, end)."
  [bs start end]
  (loop [i start acc zero]
    (if (= i end)
      acc
      (recur (inc i) (b+ (b* acc (big 256)) (big (byte-at bs i)))))))


(defn- span-key
  "A string naming the exact bytes bs[start, end), for duplicate checks."
  [bs start end]
  (apply str (map #(let [b (byte-at bs %)]
                     (str (when (< b 16) "0")
                          #?(:cljd (.toRadixString ^int b 16)
                             :clj (Integer/toHexString b)
                             :cljs (.toString b 16))))
                  (range start end))))


(defn- bytes-hex
  [bs]
  (span-key bs 0 (blen bs)))


(defn- bytes=
  [a b]
  (let [n (blen a)]
    (and (= n (blen b))
         (loop [i 0]
           (or (= i n)
               (and (= (byte-at a i) (byte-at b i))
                    (recur (inc i))))))))


;; ==========================================================================
;; Numeric kinds and carriers
;; ==========================================================================

#?(:cljs
   (defn- neg-zero?
     [x]
     (and (zero? x) (neg? (/ 1 x)))))


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


(defn- host-integer?
  [x]
  #?(:cljd (or (dart/is? x int) (dart/is? x BigInt))
     :clj (or (instance? Long x) (instance? Integer x) (instance? Short x)
              (instance? Byte x) (instance? clojure.lang.BigInt x)
              (instance? BigInteger x))
     :cljs (or (bigint? x)
               (and (number? x) (js/Number.isInteger x) (not (neg-zero? x))))))


(defn float64?
  "True for floating-point content: Double or Float on the JVM, the
   Float64 carrier or a non-integral, non-finite or negative-zero number
   on JavaScript, a double on Dart."
  [x]
  #?(:cljd (dart/is? x double)
     :clj (or (instance? Double x) (instance? Float x))
     :cljs (or (instance? Float64 x)
               (and (number? x) (not (host-integer? x))))))


(defn decimal?
  "True for decimal content: BigDecimal on the JVM, the Decimal carrier on
   JavaScript and Dart."
  [x]
  #?(:cljd (instance? Decimal x)
     :clj (instance? BigDecimal x)
     :cljs (instance? Decimal x)))


(defn ratio?
  "True for rational content: clojure.lang.Ratio or the Rational carrier."
  [x]
  #?(:cljd (instance? Rational x)
     :clj (or (instance? clojure.lang.Ratio x) (instance? Rational x))
     :cljs (instance? Rational x)))


(defn numeric?
  "True for every value the portable numeric operations accept."
  [x]
  (or (host-integer? x) (float64? x) (decimal? x) (ratio? x)))


(defn float64
  "Floating-point content with the value of host number x: a Double on
   the JVM, the Float64 carrier on JavaScript (so integral floats such as
   1.0 keep their kind)."
  [x]
  #?(:cljd (.toDouble ^num x)
     :clj (double x)
     :cljs (Float64. (if (instance? Float64 x) (.-v x) (js/Number x)))))


#?(:cljd
   (def ^:private hex-digit
     {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7
      "8" 8 "9" 9 "a" 10 "b" 11 "c" 12 "d" 13 "e" 14 "f" 15}))


#?(:cljd
   (defn- hex-int
     "The value of a short lowercase hex string."
     [h]
     (reduce (fn [acc i] (+ (* 16 acc) (get hex-digit (subs h i (inc i)))))
             0 (range (count h)))))


(defn float64-from-bits
  "Floating-point content with the IEEE-754 bits given as 16 hex digits."
  [hex]
  #?(:cljd (let [bd (ByteData. 8)]
             (.setUint32 bd 0 (hex-int (subs hex 0 8)))
             (.setUint32 bd 4 (hex-int (subs hex 8 16)))
             (.getFloat64 bd 0))
     :clj (Double/longBitsToDouble (Long/parseUnsignedLong hex 16))
     :cljs (let [view (js/DataView. (js/ArrayBuffer. 8))]
             (.setUint32 view 0 (js/parseInt (subs hex 0 8) 16))
             (.setUint32 view 4 (js/parseInt (subs hex 8 16) 16))
             (Float64. (.getFloat64 view 0)))))


(defn- float-value
  "The host double of floating-point content."
  [x]
  #?(:cljd x
     :clj (double x)
     :cljs (if (instance? Float64 x) (.-v x) x)))


(def decimal-exponent-min
  "Smallest decimal exponent either host accepts: the JVM scale
   (-exponent) is a 32-bit int, so -(2^31 - 1)."
  -2147483647)


(def decimal-exponent-max
  "Largest decimal exponent either host accepts: 2^31 (scale -2^31)."
  2147483648)


(defn decimal
  "Decimal content mantissa x 10^exponent with exactly this scale.
   exponent is a host integer, mantissa a host integer or decimal
   string. Every host accepts exactly the exponents in
   [decimal-exponent-min, decimal-exponent-max], the JVM BigDecimal
   window, so acceptance never depends on the host; outside it the
   value is refused :malformed-number."
  [exponent mantissa]
  (let [e (big exponent)]
    (when-not (and (<= 0 (bcmp e (big decimal-exponent-min)))
                   (<= (bcmp e (big decimal-exponent-max)) 0))
      (refuse :malformed-number "decimal exponent out of range"))
    #?(:cljd (Decimal. (.toInt ^BigInt e) (big mantissa))
       :clj (BigDecimal. ^BigInteger (big mantissa)
                         (int (- (.longValue ^BigInteger e))))
       :cljs (Decimal. (js/Number e) (big mantissa)))))


(defn decimal-exponent
  [d]
  #?(:cljd (.-exponent ^Decimal d)
     :clj (- (.scale ^BigDecimal d))
     :cljs (.-exponent ^Decimal d)))


(defn decimal-mantissa
  "The exact mantissa as a host big integer."
  [d]
  #?(:cljd (.-mantissa ^Decimal d)
     :clj (.unscaledValue ^BigDecimal d)
     :cljs (.-mantissa ^Decimal d)))


(defn ratio
  "Rational content numerator/denominator, reduced with the sign on the
   numerator. Denominator 1 keeps rational kind (a Rational carrier);
   on the JVM other ratios are clojure.lang.Ratio. A zero denominator is
   refused."
  [numerator denominator]
  (let [n (big numerator)
        d (big denominator)]
    (when (zero? (bsign d))
      (refuse :malformed-number "zero denominator"))
    (let [[n d] (if (neg? (bsign d)) [(b- zero n) (b- zero d)] [n d])
          g (bgcd n d)
          n (bquot n g)
          d (bquot d g)]
      #?(:cljd (Rational. n d)
         :clj (if (= one d)
                (Rational. n d)
                (clojure.lang.Ratio. ^BigInteger n ^BigInteger d))
         :cljs (Rational. n d)))))


(defn ratio-numerator
  [r]
  #?(:cljd (.-numerator ^Rational r)
     :clj (if (instance? Rational r)
            (.-numerator ^Rational r)
            (.numerator ^clojure.lang.Ratio r))
     :cljs (.-numerator ^Rational r)))


(defn ratio-denominator
  [r]
  #?(:cljd (.-denominator ^Rational r)
     :clj (if (instance? Rational r)
            (.-denominator ^Rational r)
            (.denominator ^clojure.lang.Ratio r))
     :cljs (.-denominator ^Rational r)))


;; ==========================================================================
;; Portable numeric operations
;; ==========================================================================

(defn- float-fields
  "[negative? biased-exponent fraction-big] of a host double."
  [d]
  #?(:cljd (let [bd (ByteData. 8)]
             (.setFloat64 bd 0 d)
             (let [hi (.getUint32 bd 0)
                   lo (.getUint32 bd 4)]
               [(>= hi 0x80000000)
                (bit-and (bit-shift-right hi 20) 0x7ff)
                (b+ (b* (big (bit-and hi 0xfffff)) (bpow two 32)) (big lo))]))
     :clj (let [bits (Double/doubleToRawLongBits (double d))]
            [(neg? bits)
             (bit-and (bit-shift-right bits 52) 0x7ff)
             (big (bit-and bits 0xfffffffffffff))])
     :cljs (let [view (js/DataView. (js/ArrayBuffer. 8))]
             (.setFloat64 view 0 d)
             (let [hi (.getUint32 view 0)
                   lo (.getUint32 view 4)]
               [(>= hi 0x80000000)
                (bit-and (unsigned-bit-shift-right hi 20) 0x7ff)
                (b+ (b* (big (bit-and hi 0xfffff)) (bpow two 32)) (big lo))]))))


(defn- reduced-fraction
  [n d]
  (let [g (bgcd n d)]
    (if (zero? (bsign g)) [:finite zero one] [:finite (bquot n g) (bquot d g)])))


(defn- exact
  "The exact value of numeric x: [:nan], [:inf -1|1], or
   [:finite numerator denominator] reduced with a positive denominator."
  [x]
  (cond
    (host-integer? x)
    (do #?(:cljs (when (and (number? x) (not (js/Number.isSafeInteger x)))
                   (refuse :unsupported-value
                           "unsafe integral JavaScript number: its value is already rounded")))
        [:finite (big x) one])
    (float64? x) (let [[neg e f] (float-fields (float-value x))]
                   (cond
                     (and (= e 0x7ff) (pos? (bsign f))) [:nan]
                     (= e 0x7ff) [:inf (if neg -1 1)]
                     :else (let [m (if (zero? e) f (b+ f (bpow two 52)))
                                 p (if (zero? e) -1074 (- e 1075))
                                 m (if neg (b- zero m) m)]
                             (if (>= p 0)
                               [:finite (b* m (bpow two p)) one]
                               (reduced-fraction m (bpow two (- p)))))))
    (decimal? x) (let [e (decimal-exponent x)
                       m (big (decimal-mantissa x))]
                   (if (>= e 0)
                     [:finite (b* m (bpow ten e)) one]
                     (reduced-fraction m (bpow ten (- e)))))
    ;; Reduced here too, so a hand-built unreduced carrier such as
    ;; (->Rational 2 4) still keys, hashes and compares as 1/2.
    (ratio? x) (let [n (big (ratio-numerator x))
                     d (big (ratio-denominator x))]
                 (when (zero? (bsign d))
                   (refuse :malformed-number "zero denominator"))
                 (if (neg? (bsign d))
                   (reduced-fraction (b- zero n) (b- zero d))
                   (reduced-fraction n d)))
    :else (refuse :unsupported-value (str "not a portable number: " (type-name x)))))


(defn- exact-key
  "A host-independent value key of an exact triple: strings, so equal
   values give equal keys on every host. Its `hash`, and so num-hash
   and equiv-hash, is host-specific (Murmur3 on the JVM, another scheme
   on JavaScript): hash values are for use within one host and must
   never cross hosts."
  [[kind a b]]
  (case kind
    :finite [:finite (str a) (str b)]
    :inf [:inf a]
    :nan [:nan]))


(defn num=
  "Portable numeric equality: exact mathematical value, independent of
   kind, decimal scale and zero sign; canonical NaNs are equal. Symmetric
   across native numbers and carriers."
  [a b]
  (= (exact-key (exact a)) (exact-key (exact b))))


(defn num-hash
  "A hash consistent with num=."
  [x]
  (hash (exact-key (exact x))))


(defn- rank
  [[kind a]]
  (case kind
    :inf (if (neg? a) 0 2)
    :finite 1
    :nan 3))


(defn num-compare
  "Portable numeric order: -infinity < finite values (by exact value,
   never rounded through double) < +infinity < NaN. Returns -1, 0 or 1."
  [a b]
  (let [x (exact a)
        y (exact b)
        rx (rank x)
        ry (rank y)]
    (cond
      (< rx ry) -1
      (> rx ry) 1
      (= :finite (first x)) (let [[_ n1 d1] x
                                  [_ n2 d2] y]
                              (bcmp (b* n1 d2) (b* n2 d1)))
      :else 0)))


(defn- identifier-key
  [x]
  [(if (keyword? x) :keyword :symbol) (namespace x) (name x)])


(defn- sequential-value?
  [x]
  (or (vector? x) (list? x) (seq? x)))


(declare equiv)


(defn equiv-hash
  "A hash consistent with equiv."
  [x]
  (cond
    (numeric? x) (num-hash x)
    (wire/byte-payload? x) (hash (bytes-hex x))
    (or (keyword? x) (symbol? x)) (hash (identifier-key x))
    (sequential-value? x) (hash (mapv equiv-hash x))
    (map? x) (hash (set (map (fn [[k v]] [(equiv-hash k) (equiv-hash v)]) x)))
    (set? x) (hash (set (map equiv-hash x)))
    :else (hash x)))


(defn- member?
  "True when some element of coll is equiv to x."
  [coll x]
  (boolean (some #(equiv x %) coll)))


(defn equiv
  "Portable decoded equality: numbers by num=, byte strings by content,
   identifiers by their namespace and name fields, lists equal to vectors
   with equal items (README ruling A6), maps and sets by members;
   metadata ignored. This is the equality whose collapses encode and
   decode refuse."
  [a b]
  (cond
    (or (numeric? a) (numeric? b)) (and (numeric? a) (numeric? b) (num= a b))
    (or (wire/byte-payload? a) (wire/byte-payload? b))
    (and (wire/byte-payload? a) (wire/byte-payload? b) (bytes= a b))
    (or (keyword? a) (symbol? a)) (and (or (keyword? b) (symbol? b))
                                       (= (identifier-key a) (identifier-key b)))
    (sequential-value? a) (and (sequential-value? b)
                               (loop [xs (seq a) ys (seq b)]
                                 (cond (and (nil? xs) (nil? ys)) true
                                       (or (nil? xs) (nil? ys)) false
                                       (equiv (first xs) (first ys))
                                       (recur (next xs) (next ys))
                                       :else false)))
    (map? a) (and (map? b)
                  (= (count a) (count b))
                  (every? (fn [[k v]]
                            (some (fn [[k2 v2]] (and (equiv k k2) (equiv v v2))) b))
                          a))
    (set? a) (and (set? b)
                  (= (count a) (count b))
                  (every? #(member? b %) a))
    :else (= a b)))


(defn- collapse?
  "True when two of xs are equiv."
  [xs]
  (boolean
    (some (fn [[_ group]]
            (when (< 1 (count group))
              (some (fn [[i x]]
                      (some (fn [y] (equiv x y)) (drop (inc i) group)))
                    (map-indexed vector group))))
          (group-by equiv-hash xs))))


;; ==========================================================================
;; Kind-strict content equality
;; ==========================================================================

(defn numeric-kind
  "The content kind of numeric x: :integer, :float64, :decimal or :rational.
   Host integer width is not a kind: a long and a big integer of one value
   are both :integer."
  [x]
  (cond
    (host-integer? x) :integer
    (float64? x) :float64
    (decimal? x) :decimal
    (ratio? x) :rational
    :else (refuse :unsupported-value (str "not a portable number: " (type-name x)))))


(defn- numeric-content-key
  "The kind-strict identity of numeric x, the same distinctions content
   addressing makes: kind, then within float64 the exact bits (so the zero
   sign counts, and every NaN is the one canonical NaN), within decimal the
   exponent (scale) and mantissa, within rational the reduced numerator and
   denominator. Integer width is not identity. Built from strings and
   keywords, so equal content gives equal keys on every host."
  [x]
  (case (numeric-kind x)
    :integer (do #?(:cljs (when (and (number? x) (not (js/Number.isSafeInteger x)))
                            (refuse :unsupported-value
                                    "unsafe integral JavaScript number: its value is already rounded")))
                 [::integer (str (big x))])
    :float64 (let [[neg e f] (float-fields (float-value x))]
               (if (and (= e 0x7ff) (pos? (bsign f)))
                 [::float64 :nan]
                 [::float64 neg e (str f)]))
    :decimal [::decimal (str (decimal-exponent x)) (str (big (decimal-mantissa x)))]
    :rational (let [[_ n d] (exact x)]
                [::rational (str n) (str d)])))


(defn content-key
  "A host value whose host `=` and `hash` agree with content=: numbers by
   their kind-strict identity, byte strings by content, identifiers by their
   namespace and name fields, lists and vectors alike (README ruling A6)
   element by element, maps and sets by members, metadata ignored, any other
   value as itself. A caller keys a host map or set by it to get
   kind-strict content identity from host collections (JVM `=` merges
   0.0 with -0.0 and 1.0M with 1.00M; ClojureScript merges slash-crossed
   identifiers). The same traversal as equiv, with the kind-strict numeric
   key in place of num=."
  [x]
  (cond
    (numeric? x) (numeric-content-key x)
    (wire/byte-payload? x) [::bytes (bytes-hex x)]
    (or (keyword? x) (symbol? x)) [::identifier (identifier-key x)]
    (sequential-value? x) (mapv content-key x)
    (map? x) (into {} (map (fn [[k v]] [(content-key k) (content-key v)])) x)
    (set? x) (into #{} (map content-key) x)
    :else x))


(defn content=
  "Portable kind-strict decoded equality: two values are content= exactly
   when content addressing would give them the same identity. Numbers must
   share kind (integer, float64, decimal, rational), and within kind the
   float zero sign, the decimal scale and the exact value; host integer
   width is not significant, and canonical NaNs are equal (every NaN has
   the one canonical encoding). Collections recurse the way equiv does,
   metadata ignored. Unlike equiv, (content= 1 1.0) is false."
  [a b]
  (or (identical? a b)
      (= (content-key a) (content-key b))))


(defn content-hash
  "A hash consistent with content=. Host-specific like num-hash: never
   compare hash values across hosts."
  [x]
  (hash (content-key x)))


;; ==========================================================================
;; Encode: normalize a value into a wire tree, then Boring writes it
;; ==========================================================================

(def ^:private reader-position-keys
  [:line :column :end-line :end-column])


(defn- char-code
  [s i]
  #?(:cljd (.codeUnitAt ^String s i)
     :clj (int (.charAt ^String s (int i)))
     :cljs (.charCodeAt s i)))


(defn- check-text
  "s itself, after refusing any unpaired UTF-16 surrogate."
  [s]
  (let [n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (char-code s i)]
          (cond
            (<= 0xD800 c 0xDBFF)
            (if (and (< (inc i) n) (<= 0xDC00 (char-code s (inc i)) 0xDFFF))
              (recur (+ i 2))
              (refuse :unpaired-surrogate (str "unpaired high surrogate at " i)))
            (<= 0xDC00 c 0xDFFF)
            (refuse :unpaired-surrogate (str "unpaired low surrogate at " i))
            :else (recur (inc i)))))))
  s)


(def max-depth
  "The deepest CBOR item nesting encode emits and decode accepts. The
   top-level item is at depth 1 and every array, map and tag puts its
   contents one level deeper, so a list frame (tag, [name items]) costs
   three levels. A J1 policy pending architect ratification (RFC 8949
   section 10 recommends a nesting limit); no corpus payload nests
   beyond a handful of levels. It bounds recursion on both paths, so a
   hostile payload is refused instead of overflowing the host stack."
  128)


(defn- deep!
  "Refuse with cls when an item would sit deeper than max-depth."
  [d cls]
  (when (> d max-depth)
    (refuse cls (str "nesting deeper than " max-depth))))


(declare wire-of)


(defn- int-wire
  "The wire node, at depth d, of exact integer b (a host big integer)."
  [b d]
  (cond
    (and (<= 0 (bcmp b neg-two-64)) (neg? (bcmp b two-64)))
    (do (deep! d :unsupported-value) (wire/integer (b->int b)))
    (pos? (bsign b)) (do (deep! (inc d) :unsupported-value)
                         (wire/tagged 2 (magnitude-bytes b)))
    :else (do (deep! (inc d) :unsupported-value)
              (wire/tagged 3 (magnitude-bytes (b- (b- zero b) one))))))


(defn- float-wire
  [x d]
  (deep! (+ d 2) :unsupported-value)
  (let [f (float-value x)
        bs #?(:cljd (let [bd (ByteData. 8)]
                      (if (.-isNaN ^double f)
                        (do (.setUint32 bd 0 0x7ff80000) (.setUint32 bd 4 0))
                        (.setFloat64 bd 0 f))
                      (.asUint8List (.-buffer bd)))
              :clj (let [buf (ByteBuffer/allocate 8)]
                     (.putLong buf (if (Double/isNaN f)
                                     0x7ff8000000000000
                                     (Double/doubleToRawLongBits f)))
                     (.array buf))
              :cljs (let [out (js/Uint8Array. 8)
                          view (js/DataView. (.-buffer out))]
                      (if (js/isNaN f)
                        (do (.setUint32 view 0 0x7ff80000) (.setUint32 view 4 0))
                        (.setFloat64 view 0 f))
                      out))]
    (wire/frame 'dao.jing/float64 bs)))


(defn- number-wire
  [x d]
  (cond
    (host-integer? x)
    (do #?(:cljs (when (and (number? x) (not (js/Number.isSafeInteger x)))
                   (refuse :unsupported-value
                           "unsafe integral JavaScript number; pass a BigInt or float64")))
        (int-wire (big x) d))
    (float64? x) (float-wire x d)
    (decimal? x) (do (deep! (inc d) :unsupported-value)
                     (wire/tagged 4 [(int-wire (big (decimal-exponent x)) (+ d 2))
                                     (int-wire (big (decimal-mantissa x)) (+ d 2))]))
    :else (let [[_ n den] (exact x)]
            (deep! (inc d) :unsupported-value)
            (wire/tagged 30 [(int-wire n (+ d 2)) (int-wire den (+ d 2))]))))


(defn- members-wire
  "Wire the [key value] pairs (value nil for set elements) of one
   collection, members at depth d, refusing duplicate canonical encodings
   (dup-class) and portable-equality collapses before the collection is
   built. Returns [[key-bytes-hex key-wire value-wire] ...]."
  [pairs set? dup-class d]
  (let [wired (mapv (fn [[k v]]
                      (let [kw (wire-of k d)
                            vw (when-not set? (wire-of v d))]
                        [(bytes-hex (wire/encode kw)) kw vw]))
                    pairs)]
    (when (< (count (set (map first wired))) (count wired))
      (refuse dup-class))
    (when (collapse? (map first pairs))
      (refuse :equality-collapse))
    wired))


(defn- map-wire
  "A map at depth d; keys and values one level deeper."
  [m d]
  (deep! d :unsupported-value)
  (let [wired (members-wire (seq m) false :duplicate-key (inc d))]
    (wire/map-node wired)))


(defn- set-wire
  "Tag 258 at depth d over an array at d+1; elements at d+2."
  [s d]
  (deep! (inc d) :unsupported-value)
  (let [wired (members-wire (map (fn [x] [x nil]) s) true :duplicate-element (+ d 2))]
    (wire/tagged 258 (mapv second (sort-by first wired)))))


(defn- meta-wire
  "The wire node of x at depth d, where (build depth) wires x's own
   content starting at that depth. If x carries metadata that survives
   stripping, a with-meta frame sits at d and x's content moves two
   levels deeper (tag, [meta value]). Stripped values are validated
   first (ruling A9); a metadata map carrying its own metadata is
   refused (ruling A3)."
  [x d build]
  (let [m (meta x)]
    (if (nil? m)
      (build d)
      (do (when (some? (meta m))
            (refuse :malformed-frame "metadata map carries metadata"))
          (doseq [k reader-position-keys
                  :when (contains? m k)]
            (wire-of (get m k) (+ d 3)))
          (let [kept (apply dissoc m reader-position-keys)]
            (if (empty? kept)
              (build d)
              (wire/frame 'clojure/with-meta
                          [(map-wire kept (+ d 2)) (build (+ d 2))])))))))


(defn- identifier-wire
  "Tag 27 at depth d over [name [ns name]]: the components at d+3."
  [tag-symbol x d]
  (deep! (+ d 3) :unsupported-value)
  (wire/frame tag-symbol
              (wire/array-node [(if-some [ns (namespace x)]
                                  (wire/text (check-text ns))
                                  (wire/null))
                                (wire/text (check-text (name x)))])))


(defn- list-wire
  "Tag 27 at depth d over [\"dao.jing/list\" items]: items at d+3."
  [x d]
  (deep! (+ d 2) :unsupported-value)
  (wire/frame 'dao.jing/list (mapv #(wire-of % (+ d 3)) x)))


(defn- wire-of
  "The wire tree of one value whose outermost item sits at depth d, or a
   refusal."
  [x d]
  (cond
    (nil? x) (do (deep! d :unsupported-value) (wire/null))
    (boolean? x) (do (deep! d :unsupported-value) (wire/bool x))
    (string? x) (do (deep! d :unsupported-value) (wire/text (check-text x)))
    ;; Keywords cannot carry metadata on any host (not IObj / IWithMeta;
    ;; pinned by a test), so there is none to wrap.
    (keyword? x) (identifier-wire 'dao.jing/keyword x d)
    (symbol? x) (meta-wire x d #(identifier-wire 'dao.jing/symbol x %))
    (wire/byte-payload? x) (do (deep! d :unsupported-value)
                               (wire/byte-string (copy-range x 0 (blen x))))
    (numeric? x) (number-wire x d)
    (record? x) (refuse :unsupported-value "record")
    (map? x) (meta-wire x d #(map-wire x %))
    (vector? x) (meta-wire x d (fn [d]
                                 (deep! d :unsupported-value)
                                 (wire/array-node (mapv #(wire-of % (inc d)) x))))
    (set? x) (meta-wire x d #(set-wire x %))
    (or (list? x) (seq? x)) (meta-wire x d #(list-wire x %))
    :else (refuse :unsupported-value (type-name x))))


(defn encode
  "The canonical CBOR payload bytes of one supported value (byte[] on the
   JVM, Uint8Array on JavaScript, Uint8List on Dart). Refuses unsupported values, unpaired
   surrogates, nesting deeper than max-depth, and collections whose
   members collide after normalization."
  [value]
  (wire/encode (wire-of value 1)))


(defn encoded-compare
  "Order two supported values by their canonical encodings: the shorter
   encoding first, then unsigned bytewise (the order this profile already
   uses for map keys and set elements). Returns -1, 0 or 1. It encodes both
   values, so callers reserve it for the rare case that needs it (the
   min/max tie-break after numeric order has already tied)."
  [a b]
  (let [ea (encode a)
        eb (encode b)
        la (blen ea)
        lb (blen eb)]
    (cond
      (< la lb) -1
      (> la lb) 1
      :else (let [c (compare (bytes-hex ea) (bytes-hex eb))]
              (cond (neg? c) -1 (pos? c) 1 :else 0)))))


;; ==========================================================================
;; Decode, part 1: a generic structural reader (RFC 8949 data items)
;; ==========================================================================

(defn- need
  [bs pos n]
  (when (> (+ pos n) (blen bs))
    (refuse :malformed-cbor (str "truncated at byte " pos))))


(def ^:private max-length (big 9007199254740991))


(defn- read-head
  "[major additional-info argument next-pos]; argument is a host number,
   a host big integer for eight-byte arguments, or nil for indefinite."
  [bs pos]
  (need bs pos 1)
  (let [ib (byte-at bs pos)
        major (bit-shift-right ib 5)
        ai (bit-and ib 31)
        pos (inc pos)]
    (cond
      (< ai 24) [major ai ai pos]
      (<= ai 26) (let [size (case ai 24 1 25 2 26 4)]
                   (need bs pos size)
                   [major ai
                    (reduce (fn [acc i] (+ (* acc 256) (byte-at bs (+ pos i))))
                            0 (range size))
                    (+ pos size)])
      (= ai 27) (do (need bs pos 8)
                    [major ai (span-big bs pos (+ pos 8)) (+ pos 8)])
      (= ai 31) [major ai nil pos]
      :else (refuse :malformed-cbor (str "reserved additional information " ai)))))


(defn- host-big?
  "True for a host big integer: read-head returns one exactly for an
   eight-byte argument."
  [x]
  #?(:cljd (dart/is? x BigInt)
     :clj (instance? BigInteger x)
     :cljs (bigint? x)))


(defn- length-of
  "A byte or item count as a host number; larger than any input can hold
   means truncated."
  [arg pos]
  (if-not (host-big? arg)
    arg
    (if (pos? (bcmp arg max-length))
      (refuse :malformed-cbor (str "truncated at byte " pos))
      #?(:cljd (.toInt ^BigInt arg)
         :clj (.longValue ^BigInteger arg)
         :cljs (js/Number arg)))))


(defn- utf8
  "Strict UTF-8 decode of bs[start, start+len)."
  [bs start len]
  ;; Dart's decoder drops a U+FEFF at the start of what it decodes; a BOM
  ;; is content here (the corpus pins uni/bom), so leading BOM bytes are
  ;; taken one by one and the decoder only ever starts after them.
  #?(:cljd (let [end (+ start len)]
             (loop [at start boms 0]
               (if (and (<= (+ at 3) end)
                        (= 0xef (byte-at bs at))
                        (= 0xbb (byte-at bs (+ at 1)))
                        (= 0xbf (byte-at bs (+ at 2))))
                 (recur (+ at 3) (inc boms))
                 (str (apply str (repeat boms (String.fromCharCode 0xfeff)))
                      (try
                        (.convert (convert/Utf8Decoder.) bs at end)
                        (catch FormatException e
                          (refuse :invalid-utf8 (str e))))))))
     :clj (try
            (str (.decode (-> StandardCharsets/UTF_8
                              .newDecoder
                              (.onMalformedInput CodingErrorAction/REPORT)
                              (.onUnmappableCharacter CodingErrorAction/REPORT))
                          (ByteBuffer/wrap ^bytes bs (int start) (int len))))
            (catch CharacterCodingException e
              (refuse :invalid-utf8 (.getMessage e))))
     :cljs (try
             (.decode (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true})
                      (.subarray bs start (+ start len)))
             (catch :default e
               (refuse :invalid-utf8 (str e))))))


(defn- arg->big
  [arg]
  (if (host-big? arg) arg (big arg)))


(defn- read-item
  "[item next-pos] for the item at pos, sitting at nesting depth d (the
   top-level item is 1). An item is {:kind k :value v :start s :end e},
   kind one of :uint :nint :bytes :text :array :map :tag :simple :float.
   Nesting past max-depth is refused before recursing, so no payload can
   exhaust the stack; item->value then walks this depth-bounded tree."
  [bs pos d]
  (deep! d :malformed-cbor)
  (let [start pos
        [major ai arg pos] (read-head bs pos)
        item (fn [kind value end] [{:kind kind :value value :start start :end end} end])]
    (case (int major)
      (0 1)
      (if (nil? arg)
        (refuse :malformed-cbor "indefinite integer")
        (let [b (arg->big arg)]
          (item (if (zero? major) :uint :nint)
                (if (zero? major) b (b- (b- zero b) one))
                pos)))

      (2 3)
      (if (nil? arg)
        (loop [pos pos chunks []]
          (need bs pos 1)
          (if (= 0xff (byte-at bs pos))
            (let [pos (inc pos)]
              (item (if (= 2 major) :bytes :text)
                    (if (= 2 major)
                      (let [n (reduce + (map blen chunks))
                            out #?(:cljd (Uint8List. n)
                                   :clj (byte-array n)
                                   :cljs (js/Uint8Array. n))]
                        (reduce (fn [off c]
                                  #?(:cljd (.setRange ^Uint8List out off (+ off (blen c)) c)
                                     :clj (System/arraycopy c 0 out off (alength ^bytes c))
                                     :cljs (.set out c off))
                                  (+ off (blen c)))
                                0 chunks)
                        out)
                      (apply str chunks))
                    pos))
            (let [[cmajor _ carg cpos] (read-head bs pos)]
              (when (or (not= cmajor major) (nil? carg))
                (refuse :malformed-cbor "bad indefinite-length chunk"))
              (let [n (length-of carg cpos)]
                (need bs cpos n)
                (recur (+ cpos n)
                       (conj chunks (if (= 2 major)
                                      (copy-range bs cpos (+ cpos n))
                                      (utf8 bs cpos n))))))))
        (let [n (length-of arg pos)]
          (need bs pos n)
          (item (if (= 2 major) :bytes :text)
                (if (= 2 major) (copy-range bs pos (+ pos n)) (utf8 bs pos n))
                (+ pos n))))

      (4 5)
      (let [n (when arg (length-of arg pos))]
        (loop [pos pos acc []]
          (cond
            (and (nil? n) (do (need bs pos 1) (= 0xff (byte-at bs pos))))
            (item (if (= 4 major) :array :map) acc (inc pos))

            (and n (= n (count acc)))
            (item (if (= 4 major) :array :map) acc pos)

            (= 4 major)
            (let [[x pos] (read-item bs pos (inc d))] (recur pos (conj acc x)))

            :else
            (let [[k pos] (read-item bs pos (inc d))
                  [v pos] (read-item bs pos (inc d))]
              (recur pos (conj acc [k v]))))))

      6
      (if (nil? arg)
        (refuse :malformed-cbor "indefinite tag")
        (let [[inner pos] (read-item bs pos (inc d))]
          ;; [tag-or-nil inner raw-argument]: an eight-byte tag number is
          ;; a host big integer and never one of the profile's tags.
          (item :tag [(when-not (host-big? arg) arg) inner arg] pos)))

      7
      (cond
        (= ai 31) (refuse :malformed-cbor "break outside an indefinite-length item")
        (< ai 24) (item :simple ai pos)
        (= ai 24) (if (< arg 32)
                    (refuse :malformed-cbor "two-byte simple value below 32")
                    (item :simple arg pos))
        :else (item :float ai pos)))))


;; ==========================================================================
;; Decode, part 2: the closed Jing profile over generic items
;; ==========================================================================

(declare item->value)


(defn- int-item
  "The host big integer of an integer item (a bignum too when allowed),
   or nil."
  [bs x allow-bignum?]
  (case (:kind x)
    (:uint :nint) (:value x)
    :tag (let [[tag _] (:value x)]
           (when (and allow-bignum? (#{2 3} tag))
             (big (item->value bs x))))
    nil))


(defn- check-members
  "Refuse duplicate raw encodings among items (dup-class), then
   portable-equality collapses among their values."
  [bs items values dup-class]
  (let [raws (map #(span-key bs (:start %) (:end %)) items)]
    (when (< (count (set raws)) (count items))
      (refuse dup-class)))
  (when (collapse? values)
    (refuse :equality-collapse)))


(defn- built
  "coll, unless building it merged members the portable equality keeps
   apart (host identifier equality on ClojureScript compares the joined
   name): losing an entry is never acceptable."
  [coll expected]
  (when (< (count coll) expected)
    (refuse :host-collapse "the host collection merged distinct members"))
  coll)


(defn- pair-items
  "The two items of a definite-or-indefinite two-element array item, or
   nil for any other shape."
  [p]
  (when (and (= :array (:kind p)) (= 2 (count (:value p))))
    (:value p)))


(defn- frame->value
  [bs frame-name p]
  (case frame-name
    "dao.jing/list"
    (if (= :array (:kind p))
      ;; ClojureDart's list constructor attaches call-site metadata;
      ;; a decoded list carries none until a with-meta frame adds it.
      #?(:cljd (with-meta (apply list (map #(item->value bs %) (:value p))) nil)
         :default (apply list (map #(item->value bs %) (:value p))))
      (refuse :malformed-frame "list payload is not an array"))

    ("dao.jing/keyword" "dao.jing/symbol")
    (let [[ns-item name-item] (or (pair-items p)
                                  (refuse :malformed-frame
                                          "identifier payload is not [ns name]"))]
      (when-not (or (and (= :simple (:kind ns-item)) (= 22 (:value ns-item)))
                    (= :text (:kind ns-item)))
        (refuse :malformed-frame "namespace is neither nil nor text"))
      (when-not (= :text (:kind name-item))
        (refuse :malformed-frame "name is not text"))
      (let [ns (when (= :text (:kind ns-item)) (:value ns-item))]
        (if (= frame-name "dao.jing/keyword")
          (keyword ns (:value name-item))
          (symbol ns (:value name-item)))))

    "dao.jing/float64"
    (let [b (:value p)]
      (if (and (= :bytes (:kind p)) (= 8 (blen b)))
        (float64-from-bits (bytes-hex b))
        (refuse :malformed-frame "float64 payload is not eight bytes")))

    "clojure/with-meta"
    (let [[m-item v-item] (or (pair-items p)
                              (refuse :malformed-frame
                                      "with-meta payload is not [meta value]"))
          m (item->value bs m-item)
          v (item->value bs v-item)]
      (when-not (map? m)
        (refuse :malformed-frame "metadata is not a map"))
      (when (some? (meta m))
        (refuse :malformed-frame "metadata map carries metadata"))
      (when-not (and (or (vector? v) (map? v) (set? v) (list? v) (symbol? v))
                     (nil? (meta v)))
        (refuse :malformed-frame "metadata target is not a bare collection or symbol"))
      (with-meta v m))

    (refuse :unknown-frame-name frame-name)))


(defn- item->value
  [bs x]
  (let [v (:value x)]
    (case (:kind x)
      (:uint :nint) (b->int v)
      :float (refuse :native-float)
      :simple (case (int v)
                20 false
                21 true
                22 nil
                (refuse :unsupported-simple (str "simple(" v ")")))
      :bytes v
      :text v
      :array (mapv #(item->value bs %) v)
      :map (let [pairs (mapv (fn [[k w]] [(item->value bs k) (item->value bs w)]) v)]
             (check-members bs (map first v) (map first pairs) :duplicate-key)
             (built (into {} pairs) (count pairs)))
      :tag
      (let [[tag p raw] v]
        (case tag
          (2 3) (if (= :bytes (:kind p))
                  (let [mag (span-big (:value p) 0 (blen (:value p)))]
                    (b->int (if (= 2 tag) mag (b- (b- zero mag) one))))
                  (refuse :malformed-number "bignum payload is not a byte string"))
          4 (let [[e m] (or (pair-items p)
                            (refuse :malformed-number
                                    "decimal payload is not [exponent mantissa]"))
                  e (int-item bs e false)
                  m (int-item bs m true)]
              (when (or (nil? e) (nil? m))
                (refuse :malformed-number "decimal component is not an integer"))
              (decimal (b->int e) m))
          30 (let [[n d] (or (pair-items p)
                             (refuse :malformed-number
                                     "rational payload is not [numerator denominator]"))
                   n (int-item bs n true)
                   d (int-item bs d true)]
               (when (or (nil? n) (nil? d) (not (pos? (bsign d))))
                 (refuse :malformed-number "rational components out of grammar"))
               (ratio n d))
          258 (if (= :array (:kind p))
                (let [items (:value p)
                      values (mapv #(item->value bs %) items)]
                  (check-members bs items values :duplicate-element)
                  (built (into #{} values) (count values)))
                (refuse :malformed-frame "set payload is not an array"))
          39 (refuse :identifier-tag-39)
          27 (let [[n payload] (pair-items p)]
               (when-not (= :text (:kind n))
                 (refuse :malformed-frame "tag 27 payload is not [name-string payload]"))
               (frame->value bs (:value n) payload))
          (refuse :unknown-tag (str "tag " raw)))))))


(defn decode
  "The value of one canonical Jing payload (byte[], Uint8Array or
   Uint8List).
   Refuses malformed CBOR, trailing data, anything outside the closed
   profile, and any payload whose value does not re-encode to exactly
   these bytes. Returned byte strings are fresh copies."
  [bs]
  (when (zero? (blen bs))
    (refuse :malformed-cbor "empty input"))
  (let [[item pos] (read-item bs 0 1)]
    (when (not= pos (blen bs))
      (refuse :trailing-data (str (- (blen bs) pos) " bytes after the item")))
    (let [value (item->value bs item)]
      (when-not (bytes= bs (encode value))
        (refuse :non-canonical))
      value)))

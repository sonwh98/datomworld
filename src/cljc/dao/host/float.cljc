(ns dao.host.float
  "The binary64 entries of the primitive profile
   (docs/design/dao.stream.host-boundary.md §4.2, table A) and, beside
   them, the float-text kernel (table B, R7-1).

   Table A, kind P (§2.4): `float64->bits`, `bits->float64`, `finite?`,
   `integral?`, `fields`. A host double is a JVM `Double`, a JS `Number`
   or a Dart `double`; the Jing float carrier is `dao.host.number`'s, not
   this namespace's. Every NaN reads as the one quiet encoding
   7ff8000000000000, whatever payload a host holds, so equal arguments
   give equal results on every host. Oracle: table D's float rows,
   re-asserted by `dao.host.conformance-test` and `dao.host.float-test`.

   Table B, P, no host arm, not counted among table A's entries: the
   float-text kernel, written over `dao.host.integer` only. `ratio->float`
   (the one rounding step), `decimal->float` and `float-digits` and their
   helpers are copied from `yin.vm.integer` (slice H1; H2 rewires that
   module to delegate here and deletes its copy), and
   `decimal-text->float` composes them for decimal text.

   A refusal throws `ex-info` whose data carries `:dao.host/refusal`, a
   class keyword, and `:dao.host/entry`. Every reader conditional names
   `:cljd` first, because the ClojureDart build also reads `:clj`."
  (:require
    [clojure.string :as str]
    [dao.host.integer :as integer])
  #?(:cljd (:import ["dart:typed_data" ByteData])))


(defn- refuse
  [entry cls detail]
  (throw (ex-info (str "dao.host refused: " entry " " (name cls) " (" detail ")")
                  {:dao.host/refusal cls :dao.host/entry entry :detail detail})))


;; =============================================================================
;; Table A: bits, fields, finiteness
;; =============================================================================

(defn- words
  "The IEEE-754 bits of host double `x` as two native unsigned 32-bit
   words `[hi lo]`, every NaN the quiet 7ff8000000000000."
  [x]
  #?(:cljd (if (.-isNaN ^double x)
             [0x7ff80000 0]
             (let [bd (ByteData. 8)]
               (.setFloat64 bd 0 x)
               [(.getUint32 bd 0) (.getUint32 bd 4)]))
     :clj (let [v (double x)]
            (if (Double/isNaN v)
              [0x7ff80000 0]
              (let [b (Double/doubleToRawLongBits v)]
                [(unsigned-bit-shift-right b 32) (bit-and b 0xffffffff)])))
     :cljs (if (js/isNaN x)
             [0x7ff80000 0]
             (let [view (js/DataView. (js/ArrayBuffer. 8))]
               (.setFloat64 view 0 x)
               [(.getUint32 view 0) (.getUint32 view 4)]))))


(def ^:private hex-digits "0123456789abcdef")


(defn- word-hex
  "Native unsigned 32-bit `w` as eight lowercase hex digits."
  [w]
  (apply str (map (fn [i]
                    (let [d (bit-and (quot w (nth [268435456 16777216 1048576 65536
                                                   4096 256 16 1]
                                                  i))
                                     15)]
                      (subs hex-digits d (inc d))))
                  (range 8))))


(defn float64->bits
  "P. The IEEE-754 bits of host double `x` as 16 lowercase hex digits,
   big-endian; every NaN is 7ff8000000000000. `-0.0` keeps its sign bit."
  [x]
  (let [[hi lo] (words x)]
    (str (word-hex hi) (word-hex lo))))


(def ^:private hex-values
  (into {} (map (fn [i] [(subs hex-digits i (inc i)) i])) (range 16)))


(defn- hex-word
  "The value of eight lowercase hex digits, or nil."
  [h]
  (reduce (fn [acc i]
            (let [d (get hex-values (subs h i (inc i)))]
              (if (and acc d) (+ (* 16 acc) d) (reduced nil))))
          0
          (range 8)))


(defn bits->float64
  "P. The host double whose IEEE-754 bits are `hex`, exactly 16 lowercase
   hex digits, big-endian. Any other text is refused `:malformed-number`.
   A NaN payload is whatever the host keeps; `float64->bits` reads every
   NaN back as 7ff8000000000000."
  [hex]
  (let [hi (when (and (string? hex) (= 16 (count hex))) (hex-word (subs hex 0 8)))
        lo (when hi (hex-word (subs hex 8 16)))]
    (when-not (and hi lo)
      (refuse 'bits->float64 :malformed-number "not 16 lowercase hex digits"))
    #?(:cljd (let [bd (ByteData. 8)]
               (.setUint32 bd 0 hi)
               (.setUint32 bd 4 lo)
               (.getFloat64 bd 0))
       :clj (Double/longBitsToDouble (bit-or (bit-shift-left (long hi) 32) (long lo)))
       :cljs (let [view (js/DataView. (js/ArrayBuffer. 8))]
               (.setUint32 view 0 hi)
               (.setUint32 view 4 lo)
               (.getFloat64 view 0)))))


(defn finite?
  "P. True when host double `x` is neither infinite nor a NaN."
  [x]
  #?(:cljd (.-isFinite ^double x)
     :clj (Double/isFinite (double x))
     :cljs (js/isFinite x)))


(defn integral?
  "P. True when host double `x` is finite and has no fraction: `-0.0`,
   `1.0` and every finite double of magnitude at least 2^52 are integral."
  [x]
  #?(:cljd (and (.-isFinite ^double x) (== x (.floorToDouble ^double x)))
     :clj (let [v (double x)] (and (Double/isFinite v) (== v (Math/floor v))))
     :cljs (js/Number.isInteger x)))


(defn fields
  "P. `[negative? biased-exponent fraction]` of host double `x`: the sign
   bit as a boolean, the 11-bit biased exponent as a native integer, and
   the 52-bit fraction as a host big integer (`dao.host.integer`). A NaN
   reads as 7ff8000000000000: `[false 2047 2^51]`."
  [x]
  (let [[hi lo] (words x)]
    [(>= hi 0x80000000)
     (bit-and (quot hi 1048576) 0x7ff)
     (integer/from-native (+ (* (bit-and hi 0xfffff) 4294967296) lo))]))


;; =============================================================================
;; Table B: the float-text kernel (copied from yin.vm.integer, R7-1)
;; =============================================================================

(def ^:private zero (integer/from-native 0))
(def ^:private one (integer/from-native 1))


(defn sign
  "-1, 0 or 1: the sign of host big integer `b`."
  [b]
  (integer/compare-big b zero))


(defn bits
  "The bit length of the magnitude of host big integer `b`."
  [b]
  (integer/magnitude-bit-length b))


(defn pow2
  "2^`k` as a host double, exactly, for `k` from -1074 to 1023: every
   intermediate is a power of two in range, and the base is squared only
   while a higher bit of `k` remains."
  [k]
  (loop [result 1.0
         base (if (neg? k) 0.5 2.0)
         k (if (neg? k) (- k) k)]
    (if (zero? k)
      result
      (recur (if (odd? k) (* result base) result)
             (if (> k 1) (* base base) base)
             (quot k 2)))))


(def ^:private two-53-float (pow2 53))
(def ^:private two-117-float (pow2 117))
(def ^:private two-minus-64-float (pow2 -64))


(defn signed
  "Host double `d`, negated when `negative?`. Multiplying by -1.0 keeps
   the sign of a zero on every host."
  [negative? d]
  (if negative? (* -1.0 d) d))


(defn- compare-scaled
  "The sign of `x - y * 2^k` for big `x`, `y` and native `k`."
  [x y k]
  (if (neg? k)
    (integer/compare-big (integer/shift-left-big x (- k)) y)
    (integer/compare-big x (integer/shift-left-big y k))))


(defn ratio->float
  "The binary64 nearest `x / y`, ties to even, for big `x >= 0` and
   `y > 0`, negated when `negative?`; or `::float-overflow` when it is
   not finite. One integer division of 53 or fewer quotient bits and one
   exact scaling, so nothing rounds twice."
  [x y negative?]
  (let [e0 (- (bits x) (bits y))]
    (cond
      ;; x / y < 2^(e0 + 1): at least 2^1024, or below half the least
      ;; subnormal 2^-1074
      (zero? (sign x)) (signed negative? 0.0)
      (> e0 1024) ::float-overflow
      (< e0 -1076) (signed negative? 0.0)
      :else
      (let [;; 2^(e - 1) <= x / y < 2^e
            e (if (neg? (compare-scaled x y e0)) e0 (inc e0))
            ;; the quotient keeps 53 bits, fewer when subnormal
            k (max (- e 53) -1074)
            [num den] (if (neg? k)
                        [(integer/shift-left-big x (- k)) y]
                        [x (integer/shift-left-big y k)])
            q (integer/to-native (integer/quot-trunc num den))
            c (integer/compare-big (integer/shift-left-big
                                     (integer/rem-trunc num den) 1)
                                   den)
            q (if (or (pos? c) (and (zero? c) (odd? q))) (inc q) q)]
        ;; q <= 2^53, and q * 2^k is finite iff it is below 2^1024
        (if (> (+ (bits (integer/from-native q)) k) 1024)
          ::float-overflow
          (signed negative? (* (integer/native->double q) (pow2 k))))))))


(defn trunc-big
  "Finite host double `v` truncated toward zero, as an exact big integer.
   At or above 2^53 `v` is integral: halve it exactly into [2^52, 2^53),
   where every double is an integer, and shift the integer back."
  [v]
  (let [negative? (neg? v)
        av (signed negative? v)]
    (if (< av two-53-float)
      (integer/from-native (integer/trunc-native v))
      (let [[m k] (loop [m av
                         k 0]
                    (cond (>= m two-117-float)
                          (recur (* m two-minus-64-float) (+ k 64))
                          (>= m two-53-float) (recur (* m 0.5) (inc k))
                          :else [m k]))
            t (integer/shift-left-big (integer/from-native (integer/trunc-native m))
                                      k)]
        (if negative? (integer/sub zero t) t)))))


(def ^:private chunk-digits
  "Digits accumulated natively per step: 36^10 < 2^53, so a chunk is
   exact on every host."
  10)


(defn digits->big
  "The big integer of digit values `values` in radix `r`, accumulated
   natively `chunk-digits` at a time."
  [values r]
  (let [n (count values)]
    (loop [acc zero
           i 0]
      (if (< i n)
        (let [end (min n (+ i chunk-digits))
              [chunk scale] (reduce (fn [[c s] d]
                                      [(+ (* c r) d) (* s r)])
                                    [0 1]
                                    (subvec values i end))]
          (recur (integer/add (integer/mul acc (integer/from-native scale))
                              (integer/from-native chunk))
                 end))
        acc))))


(def ^:private ten (integer/from-native 10))


(def small-powers-of-ten
  "10^0 to 10^400 as big integers: every power `float-digits` needs."
  (vec (take 401 (iterate #(integer/mul % ten) one))))


(defn pow10
  "10^`k` as a big integer, for a native `k >= 0`."
  [k]
  (if (< k 401)
    (nth small-powers-of-ten k)
    (loop [result one
           base ten
           k k]
      (if (zero? k)
        result
        (recur (if (odd? k) (integer/mul result base) result)
               (if (> k 1) (integer/mul base base) base)
               (quot k 2))))))


(defn scaled
  "`[num den]` with num / den = (x / y) * 10^`k`, for native `k`."
  [x y k]
  (if (neg? k)
    [x (integer/mul y (pow10 (- k)))]
    [(integer/mul x (pow10 k)) y]))


(def ^:private two-64-float (pow2 64))
(def ^:private two-minus-11-float (pow2 -11))


(defn float-ratio
  "Finite positive host double `v` as big `[x y]`, `v = x / y` exactly
   with `y` a power of two. Scaling by a power of two is exact, so `v` is
   doubled (or multiplied by 2^64 while that stays below 2^53) until it
   is an integer."
  [v]
  (if (>= v two-53-float)
    [(trunc-big v) one]
    (loop [m v
           k 0]
      (cond (= m (integer/native->double (integer/trunc-native m)))
            [(integer/from-native (integer/trunc-native m))
             (integer/shift-left-big one k)]
            (< m two-minus-11-float) (recur (* m two-64-float) (+ k 64))
            :else (recur (* m 2.0) (inc k))))))


(defn decimal-exponent
  "The `e` with 10^e <= x / y < 10^(e + 1), for big `x, y > 0`: an
   estimate from the bit lengths, corrected exactly."
  [x y]
  (let [below? (fn [e]
                 (let [[a b] (scaled x y (- e))]
                   (neg? (integer/compare-big a b))))]
    (loop [e (quot (* (- (bits x) (bits y)) 30103) 100000)]
      (cond (below? e) (recur (dec e))
            (not (below? (inc e))) (recur (inc e))
            :else e))))


(defn strip-trailing-zeros
  [s]
  (loop [end (count s)]
    (if (and (> end 1) (= "0" (subs s (dec end) end)))
      (recur (dec end))
      (subs s 0 end))))


(defn float-digits
  "`[digits exp10]` for a finite nonzero host double `v`: the shortest
   decimal digit string (no trailing zeros) that reads back as `|v|`,
   with `|v| = 0.digits... x 10^(exp10 + 1)`, i.e. the first digit has
   weight 10^exp10 (CPython `repr` digits). The sign is the caller's.
   A zero, an infinity or a NaN is refused `:out-of-domain`."
  [v]
  (when (or (not (finite? v)) (zero? v))
    (refuse 'float-digits :out-of-domain "not a finite nonzero double"))
  (let [av (signed (neg? v) v)
        [x y] (float-ratio av)
        e (decimal-exponent x y)
        reads-back? (fn [d k]
                      ;; d * 10^-k is av
                      (let [[a b] (scaled d one (- k))]
                        (= av (ratio->float a b false))))]
    ;; the n-digit decimals around av are floor and ceiling of
    ;; av * 10^(n - 1 - e); any n-digit decimal that reads back lies
    ;; between av and one of them, so they are the only candidates.
    ;; Nearest alone is not enough: below a power of two the rounding
    ;; interval is half the one above.
    (loop [n 1]
      (let [k (- n 1 e)
            [num den] (scaled x y k)
            lo (integer/quot-trunc num den)
            r (integer/rem-trunc num den)
            hi (integer/add lo one)
            lo? (reads-back? lo k)
            hi? (and (not (zero? (sign r))) (reads-back? hi k))
            c (integer/compare-big (integer/shift-left-big r 1) den)
            d (cond (and lo? hi?)
                    (cond (neg? c) lo
                          (pos? c) hi
                          (zero? (sign (integer/bit-and-big lo one))) lo
                          :else hi)
                    lo? lo
                    hi? hi
                    :else nil)]
        (if d
          (let [text (integer/to-radix-string d 10)]
            ;; a ceiling of 10^n has one digit more
            [(strip-trailing-zeros text) (+ e (- (count text) n))])
          (recur (inc n)))))))


(def ^:private max-significant-digits
  "Digits `decimal->float` keeps before a sticky digit: a double's
   midpoint has at most 768 significant digits (800 plus the sticky digit
   leaves the margin)."
  800)


(defn decimal->float
  "The host double nearest `digits x 10^exp10`, ties to even, for
   `digit-values` a vector of digit values 0-9 and `exp10` an exact
   integer in any carrier: `+inf` past the range, `0.0` below it, never
   a refusal (syntax is the caller's)."
  [digit-values exp10]
  (let [e (integer/promote exp10)
        values digit-values
        first-nonzero (first (keep-indexed (fn [i d] (when (pos? d) i))
                                           values))]
    (if (nil? first-nonzero)
      0.0
      (let [last-nonzero (last (keep-indexed (fn [i d] (when (pos? d) i))
                                             values))
            sig (subvec values first-nonzero (inc last-nonzero))
            e (integer/add e (integer/from-native (- (count values)
                                                     (inc last-nonzero))))
            ;; keep 800 digits and one sticky digit for the rest,
            ;; which is never all zeros once trailing zeros are gone
            [sig e] (if (> (count sig) max-significant-digits)
                      [(conj (subvec sig 0 max-significant-digits) 1)
                       (integer/add e (integer/from-native
                                        (- (count sig)
                                           max-significant-digits 1)))]
                      [sig e])
            ;; 10^(s - 1) <= value < 10^s
            s (integer/add e (integer/from-native (count sig)))]
        (cond
          (pos? (integer/compare-big s (integer/from-native 310))) ##Inf
          (neg? (integer/compare-big s (integer/from-native -326))) 0.0
          :else
          (let [[x y] (scaled (digits->big sig 10) one (integer/to-native e))
                f (ratio->float x y false)]
            (if (keyword? f) ##Inf f)))))))


(def ^:private decimal-digit-values
  (into {} (map (fn [i] [(subs "0123456789" i (inc i)) i])) (range 10)))


(defn- digit-run
  "The digit values of `s`, one or more ASCII digits, or nil."
  [s]
  (let [vs (mapv #(get decimal-digit-values (subs s % (inc %))) (range (count s)))]
    (when (and (seq vs) (every? some? vs)) vs)))


(defn decimal-text->float
  "The host double nearest decimal text `text`, `digits[.digits]` in
   ASCII (no sign, no exponent), through `decimal->float`: `+inf` past
   the range. Any other text, `\"1.\"` and `\".5\"` included, is refused
   `:malformed-number`."
  [text]
  (let [parts (when (string? text) (str/split text #"\." -1))
        [whole fraction] (map digit-run parts)]
    (when-not (and whole (or (= 1 (count parts)) (and (= 2 (count parts)) fraction)))
      (refuse 'decimal-text->float :malformed-number "not digits[.digits]"))
    (decimal->float (into whole fraction) (- (count fraction)))))

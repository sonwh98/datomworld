(ns yin.vm.integer
  "The `integer` host module: exact-integer kernels a language prelude
   writes its integer semantics over (C3 big-integer design, converged
   rulings 1, 2, 5 and 11).

   Every export is `:pure` with declared effects `#{}`. As with
   `yin.vm.data`, the module is not part of `yin.vm/primitives`: a
   composition whose runtime profile names it installs it explicitly with
   `register-integer-module`, and Yin source reaches the exports as
   `integer/add`, `integer/parse` and so on. Guest integers go through
   these exports only; a VM's internal counters stay VM primitives.

   Carriers. Every argument may be any exact-integer carrier the host has
   (`yin.vm.integer.host`); every integer result is in its one canonical
   carrier: native iff within signed 64 bits on the JVM and Dart, or
   within +/-(2^53 - 1) on JS, else the host big integer. Each kernel
   promotes its operands to the host big integer before it operates and
   demotes the result after, so no result ever passes through a rounded
   or wrapped native intermediate, and equal values are `=` on every
   host. The kernels are written once here over the shim. Under a
   `::max-bits` of 53 or more, `add`, `sub`, `mul` and `compare` first
   try a native fast path on operands within +/-(2^53 - 1) whose result
   is provably exact, and fall back to the big path otherwise.

   Semantics, identical on every host:

   - `add`, `sub`, `mul`, `neg` and `compare` are exact; `compare`
     answers -1, 0 or 1.
   - `quot-rem` answers `[q r]` truncated toward zero, `a = b*q + r`;
     `floor-div-mod` answers Python's `[a // b, a % b]`: the quotient
     rounds toward negative infinity and a non-zero remainder takes the
     divisor's sign.
   - `bit-and`, `bit-or`, `bit-xor` and `bit-not` act on infinite signed
     two's complement, so `(bit-not a)` is `-a - 1`. `shift-left` is
     `a * 2^k` and `shift-right` is `floor(a / 2^k)`; the count is any
     exact integer and is never narrowed before it is checked, and a
     right shift past the operand's length answers 0 or -1 without
     building `2^k`.
   - `bit-length` is the bit length of the absolute value, 0 for zero.
   - The float exports (version 3) take and answer host binary64s: a JVM
     `Double`, a Dart `double`, a JS `Number`. `to-float` is CPython's
     `float(int)`, nearest with ties to even; `true-div` is CPython's
     `int / int`, the exact ratio correctly rounded however large both
     operands are, subnormal results included and a zero quotient
     signed by the operands; a result that is not finite answers
     `::float-overflow`. `compare-float` compares an integer with a
     float exactly, -1, 0 or 1, never rounding the integer: the caller
     excludes NaN, and an infinity compares by its sign. `from-float`
     truncates a finite float toward zero, exactly. Each is one
     algorithm over the shim, never a host conversion of a big integer.
   - The float text exports (version 4) use no host float parser or
     printer. `float-digits` answers `[digits exp10]` for a finite
     nonzero float `v`: `digits` is the shortest decimal string, no
     leading or trailing zero, that reads back to `v`, the closest to
     `v` among the shortest (CPython's `repr` digits), and |v| is
     d1.d2...dn x 10^exp10; zero, NaN and infinity are the caller's to
     exclude. `decimal->float` is CPython's `float(str)` after syntax:
     `digits x 10^exp10` for one or more ASCII digits and any exact
     integer exponent, rounded once to nearest with ties to even and
     non-negative; it answers `+inf` past the range and `0.0` below it,
     never a limit reason, and `::max-digits` never applies to its text.
     `max-digits` answers the composition's `::max-digits`.
   - `pow` raises to a non-negative exponent exactly, by squaring.
   - `parse` reads an optional `-` and one or more digits of `radix` (2
     to 36, default 10, either letter case) exactly, never through a
     float or a native-width parser. Signs other than `-`, prefixes,
     underscores and whitespace are the prelude's syntax, not this
     kernel's. `format` writes exact lowercase text, a `-` only when
     negative, no prefix.

   Limits (ruling 11) are explicit composition data with no default, in
   bits and digits: `::max-bits` bounds the bit length of every integer
   result, and `::max-digits` bounds the digit count `parse` reads and
   `format` writes in a radix that is not a power of two. A result that
   would breach `::max-bits` is refused before it is built wherever its
   size is known in advance (`mul`, `shift-left`, `pow`).

   A limit breach is returned, not thrown (version 2): the call answers
   the keyword `::bit-limit` or `::digit-limit` in place of its result,
   and a float result that is not finite answers `::float-overflow`
   (version 3). Every other result is an integer, a float, a boolean, a
   string or a pair, so the sum needs no tag, and a pair is never
   answered with one half refused. A guest prelude translates the three
   reasons into catchable guest exceptions; the Python profile maps
   `::bit-limit` to `MemoryError` and `::digit-limit` to `ValueError`,
   never `OverflowError` (`py/int-result`), and its translator must
   learn `::float-overflow`, which is CPython's `OverflowError`, \"int too
   large to convert to float\".

   Every other refusal throws `ex-info` with the message
   `refusal-message` and ex-data built here, never host exception text,
   never an operand: `::op`, `::reason` and the reason's own keys. These
   reasons are `:arity`, `:wrong-type`, `:out-of-range`, `:syntax`,
   `:zero-division`, `:negative-count` and `:negative-exponent`: a
   caller checks for them before the call, so reaching one is a defect
   of the caller, and it stays a host failure, never a guest
   exception."
  (:require
    [clojure.string :as str]
    [yin.vm :as vm]
    [yin.vm.integer.host :as host]
    [yin.vm.module :as module]))


(def module-name
  "The module `register-integer-module` installs."
  'integer)


(def module-version
  "The version of the kernels' contract. A change to any export's
   semantics is a new version. Version 2 returns the limit reasons
   instead of throwing them; version 3 adds `to-float`, `compare-float`,
   `true-div`, `from-float` and the `::float-overflow` reason, and the
   native fast paths inside `add`, `sub`, `mul` and `compare`, whose
   results are unchanged; version 4 adds the float text kernels
   `float-digits` and `decimal->float`, and `max-digits`, every version 3
   semantic unchanged."
  4)


(def refusal-message
  "The message of every refusal an `integer` export throws."
  "integer primitive refused")


(defn- refuse!
  [op reason data]
  (throw (ex-info refusal-message (merge {::op op, ::reason reason} data))))


(defn- wrong-type!
  "Refuse argument `arg` (0-based) of `op`, which must be `expected`."
  [op arg expected]
  (refuse! op :wrong-type {::arg arg, ::expected expected}))


;; =============================================================================
;; Promotion, demotion and limits
;; =============================================================================

(def ^:private zero (host/from-native 0))
(def ^:private one (host/from-native 1))
(def ^:private neg-one (host/from-native -1))


(defn- big!
  "Argument `arg` of `op` promoted to the host big integer, or a
   `:wrong-type` refusal."
  [op arg x]
  (if (host/exact-integer? x)
    (host/promote x)
    (wrong-type! op arg :integer)))


(defn- sign
  [b]
  (host/compare-big b zero))


(defn- bits
  [b]
  (host/magnitude-bit-length b))


(defn- out
  "Big integer result `b` in its canonical carrier, or `::bit-limit`."
  [limits b]
  (if (> (bits b) (::max-bits limits))
    ::bit-limit
    (host/demote b)))


(defn- pair
  "`[q r]`, or the first of them that is a limit reason."
  [q r]
  (cond (keyword? q) q
        (keyword? r) r
        :else [q r]))


;; =============================================================================
;; Arithmetic
;; =============================================================================

(defn- int-add
  [limits a b]
  (out limits (host/add (big! 'add 0 a) (big! 'add 1 b))))


(defn- int-sub
  [limits a b]
  (out limits (host/sub (big! 'sub 0 a) (big! 'sub 1 b))))


(defn- int-neg
  [limits a]
  (out limits (host/sub zero (big! 'neg 0 a))))


(defn- int-mul
  [limits a b]
  (let [x (big! 'mul 0 a)
        y (big! 'mul 1 b)]
    ;; |x*y| has bits(x) + bits(y) or one fewer bits
    (if (> (+ (bits x) (bits y) -1) (::max-bits limits))
      ::bit-limit
      (out limits (host/mul x y)))))


(defn- int-compare
  [_limits a b]
  (host/compare-big (big! 'compare 0 a) (big! 'compare 1 b)))


;; The fast table (version 3), installed only when ::max-bits >= 53, so
;; a fast result needs no limit check. Results are those of the big path
;; bit for bit, in the same carrier.

(defn- safe-result?
  [r]
  (and (<= -9007199254740991 r) (<= r 9007199254740991)))


(defn- small-native?
  "A safe native of magnitude at most 2^26."
  [x]
  (and (host/safe-native? x) (<= -67108864 x) (<= x 67108864)))


(defn- fast-add
  [limits a b]
  ;; a long cannot wrap below 2^54, and on JS a true sum of 2^53 or
  ;; more rounds to 2^53 or more, so the range check is exact
  (if (and (host/safe-native? a) (host/safe-native? b))
    (let [r (+ a b)]
      (if (safe-result? r) r (int-add limits a b)))
    (int-add limits a b)))


(defn- fast-sub
  [limits a b]
  (if (and (host/safe-native? a) (host/safe-native? b))
    (let [r (- a b)]
      (if (safe-result? r) r (int-sub limits a b)))
    (int-sub limits a b)))


(defn- fast-mul
  [limits a b]
  ;; |a*b| <= 2^52 with no result check: a long product can wrap back
  ;; into range, so only an operand guard is sound. A zero product is
  ;; the literal 0, never JS -0.
  (if (and (small-native? a) (small-native? b))
    (let [r (* a b)]
      (if (zero? r) 0 r))
    (int-mul limits a b)))


(defn- fast-compare
  [limits a b]
  (if (and (host/safe-native? a) (host/safe-native? b))
    (cond (< a b) -1
          (> a b) 1
          :else 0)
    (int-compare limits a b)))


(def ^:private fast-kernels
  {'add fast-add, 'sub fast-sub, 'mul fast-mul, 'compare fast-compare})


(defn- divisor!
  [op b]
  (let [y (big! op 1 b)]
    (when (zero? (sign y))
      (refuse! op :zero-division {}))
    y))


(defn- int-quot-rem
  [limits a b]
  (let [x (big! 'quot-rem 0 a)
        y (divisor! 'quot-rem b)]
    (pair (out limits (host/quot-trunc x y))
          (out limits (host/rem-trunc x y)))))


(defn- int-floor-div-mod
  [limits a b]
  (let [x (big! 'floor-div-mod 0 a)
        y (divisor! 'floor-div-mod b)
        q (host/quot-trunc x y)
        r (host/rem-trunc x y)
        adjust? (and (not (zero? (sign r))) (not= (sign r) (sign y)))]
    (pair (out limits (if adjust? (host/sub q one) q))
          (out limits (if adjust? (host/add r y) r)))))


(defn- int-pow
  [limits a e]
  (let [x (big! 'pow 0 a)
        n (big! 'pow 1 e)
        max-bits (::max-bits limits)]
    (cond
      (neg? (sign n)) (refuse! 'pow :negative-exponent {})
      (zero? (sign n)) 1
      (or (zero? (sign x)) (zero? (host/compare-big x one))) (host/demote x)
      (zero? (host/compare-big x neg-one))
      (if (zero? (sign (host/bit-and-big n one))) 1 -1)
      ;; |x| >= 2 from here, so |x^n| has at least n + 1 bits
      (pos? (host/compare-big n (host/from-native max-bits))) ::bit-limit
      (> (inc (* (dec (bits x)) (host/to-native n))) max-bits) ::bit-limit
      :else
      (out limits
           (loop [result one
                  base x
                  n (host/to-native n)]
             (let [result (if (odd? n) (host/mul result base) result)
                   n (quot n 2)]
               (if (zero? n)
                 result
                 (recur result (host/mul base base) n))))))))


;; =============================================================================
;; Bits
;; =============================================================================

(defn- int-bit-and
  [limits a b]
  (out limits
       (host/bit-and-big (big! 'bit-and 0 a) (big! 'bit-and 1 b))))


(defn- int-bit-or
  [limits a b]
  (out limits
       (host/bit-or-big (big! 'bit-or 0 a) (big! 'bit-or 1 b))))


(defn- int-bit-xor
  [limits a b]
  (out limits
       (host/bit-xor-big (big! 'bit-xor 0 a) (big! 'bit-xor 1 b))))


(defn- int-bit-not
  [limits a]
  (out limits (host/sub (host/sub zero (big! 'bit-not 0 a)) one)))


(defn- count!
  "Shift count argument `k` of `op` promoted, or a `:negative-count`
   refusal."
  [op k]
  (let [n (big! op 1 k)]
    (when (neg? (sign n))
      (refuse! op :negative-count {}))
    n))


(defn- int-shift-left
  [limits a k]
  (let [x (big! 'shift-left 0 a)
        n (count! 'shift-left k)
        max-bits (::max-bits limits)]
    (cond
      (zero? (sign x)) 0
      ;; |x << n| has exactly bits(x) + n bits
      (pos? (host/compare-big n (host/from-native max-bits))) ::bit-limit
      (> (+ (bits x) (host/to-native n)) max-bits) ::bit-limit
      :else (out limits (host/shift-left-big x (host/to-native n))))))


(defn- int-shift-right
  [limits a k]
  (let [x (big! 'shift-right 0 a)
        n (count! 'shift-right k)]
    (if (neg? (host/compare-big n (host/from-native (bits x))))
      (out limits (host/shift-right-big x (host/to-native n)))
      (if (neg? (sign x)) -1 0))))


(defn- int-bit-length
  [_limits a]
  (bits (big! 'bit-length 0 a)))


;; =============================================================================
;; Floats (version 3)
;; =============================================================================

(defn- pow2
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


(defn- signed
  "Host double `d`, negated when `negative?`. Multiplying by -1.0 keeps
   the sign of a zero on every host."
  [negative? d]
  (if negative? (* -1.0 d) d))


(defn- magnitude
  [b]
  (if (neg? (sign b)) (host/sub zero b) b))


(defn- compare-scaled
  "The sign of `x - y * 2^k` for big `x`, `y` and native `k`."
  [x y k]
  (if (neg? k)
    (host/compare-big (host/shift-left-big x (- k)) y)
    (host/compare-big x (host/shift-left-big y k))))


(defn- ratio->float
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
                        [(host/shift-left-big x (- k)) y]
                        [x (host/shift-left-big y k)])
            q (host/to-native (host/quot-trunc num den))
            c (host/compare-big (host/shift-left-big
                                  (host/rem-trunc num den) 1)
                                den)
            q (if (or (pos? c) (and (zero? c) (odd? q))) (inc q) q)]
        ;; q <= 2^53, and q * 2^k is finite iff it is below 2^1024
        (if (> (+ (bits (host/from-native q)) k) 1024)
          ::float-overflow
          (signed negative? (* (host/native->double q) (pow2 k))))))))


(defn- int-to-float
  [_limits a]
  (let [x (big! 'to-float 0 a)]
    (ratio->float (magnitude x) one (neg? (sign x)))))


(defn- int-true-div
  [_limits a b]
  (let [x (big! 'true-div 0 a)
        y (divisor! 'true-div b)]
    ;; CPython: 0 / -5 is -0.0
    (ratio->float (magnitude x) (magnitude y)
                  (not= (neg? (sign x)) (neg? (sign y))))))


(defn- float!
  "Argument `arg` of `op` as a host double, or a `:wrong-type` refusal."
  [op arg v]
  (if (host/host-float? v) v (wrong-type! op arg :float)))


(defn- trunc-big
  "Finite host double `v` truncated toward zero, as an exact big integer.
   At or above 2^53 `v` is integral: halve it exactly into [2^52, 2^53),
   where every double is an integer, and shift the integer back."
  [v]
  (let [negative? (neg? v)
        av (signed negative? v)]
    (if (< av two-53-float)
      (host/from-native (host/trunc-native v))
      (let [[m k] (loop [m av
                         k 0]
                    (cond (>= m two-117-float)
                          (recur (* m two-minus-64-float) (+ k 64))
                          (>= m two-53-float) (recur (* m 0.5) (inc k))
                          :else [m k]))
            t (host/shift-left-big (host/from-native (host/trunc-native m))
                                   k)]
        (if negative? (host/sub zero t) t)))))


(defn- int-compare-float
  [_limits a f]
  (let [x (big! 'compare-float 0 a)
        v (float! 'compare-float 1 f)]
    (cond
      (host/nan-float? v) (wrong-type! 'compare-float 1 :non-nan-float)
      (host/infinite-float? v) (if (pos? v) -1 1)
      :else
      ;; t = trunc(v): x < t means x < v, x > t means x >= t + 1 > v
      ;; (or x > t >= v when v is negative); x = t leaves the fraction
      (let [c (host/compare-big x (trunc-big v))]
        (cond (not (zero? c)) c
              (or (>= (signed (neg? v) v) two-53-float)
                  (= v (host/native->double (host/trunc-native v))))
              0
              (pos? v) -1
              :else 1)))))


(defn- int-from-float
  [limits f]
  (let [v (float! 'from-float 0 f)]
    (if (or (host/nan-float? v) (host/infinite-float? v))
      (wrong-type! 'from-float 0 :finite-float)
      (out limits (trunc-big v)))))


;; =============================================================================
;; Text
;; =============================================================================

(def ^:private digit-values
  "Each digit character, either letter case, to its value."
  (into {}
        (mapcat (fn [i]
                  (let [c (subs "0123456789abcdefghijklmnopqrstuvwxyz" i (inc i))]
                    [[c i] [(str/upper-case c) i]])))
        (range 36)))


(def ^:private chunk-digits
  "Digits accumulated natively per step: 36^10 < 2^53, so a chunk is
   exact on every host."
  10)


(defn- radix!
  "Radix argument `r` of `op` as a native integer from 2 to 36."
  [op r]
  (let [n (big! op 1 r)]
    (when-not (and (<= 0 (host/compare-big n (host/from-native 2)))
                   (<= (host/compare-big n (host/from-native 36)) 0))
      (refuse! op :out-of-range {::arg 1, ::lo 2, ::hi 36}))
    (host/to-native n)))


(defn- power-of-two?
  [r]
  (zero? (bit-and r (dec r))))


(defn- digit-values!
  "The value of each digit of `s` in radix `r`, or a `:syntax` refusal
   of `op`."
  [op s r]
  (mapv (fn [i]
          (let [d (get digit-values (subs s i (inc i)))]
            (if (and d (< d r))
              d
              (refuse! op :syntax {}))))
        (range (count s))))


(defn- digits->big
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
          (recur (host/add (host/mul acc (host/from-native scale))
                           (host/from-native chunk))
                 end))
        acc))))


(defn- int-parse
  ([limits s] (int-parse limits s 10))
  ([limits s radix]
   (when-not (string? s)
     (wrong-type! 'parse 0 :string))
   (let [r (radix! 'parse radix)
         negative? (str/starts-with? s "-")
         digits (if negative? (subs s 1) s)
         n (count digits)]
     (when (zero? n)
       (refuse! 'parse :syntax {}))
     (if (and (not (power-of-two? r)) (> n (::max-digits limits)))
       ::digit-limit
       (let [magnitude (digits->big (digit-values! 'parse digits r) r)]
         (out limits
              (if negative? (host/sub zero magnitude) magnitude)))))))


(defn- ceil-log2
  [r]
  (loop [c 0
         p 1]
    (if (< p r) (recur (inc c) (* 2 p)) c)))


(defn- int-format
  ([limits a] (int-format limits a 10))
  ([limits a radix]
   (let [x (big! 'format 0 a)
         r (radix! 'format radix)
         max-digits (::max-digits limits)
         limited? (not (power-of-two? r))]
     ;; |x| >= 2^(bits - 1), so it has more than (bits - 1) / log2(r)
     ;; digits: refuse before building text that is certain to breach
     (if (and limited?
              (pos? (bits x))
              (>= (dec (bits x)) (* (ceil-log2 r) max-digits)))
       ::digit-limit
       (let [text (host/to-radix-string x r)]
         (if (and limited?
                  (> (- (count text) (if (neg? (sign x)) 1 0)) max-digits))
           ::digit-limit
           text))))))


;; =============================================================================
;; Float text (version 4)
;; =============================================================================

(def ^:private ten (host/from-native 10))


(def ^:private small-powers-of-ten
  "10^0 to 10^400 as big integers: every power `float-digits` needs."
  (vec (take 401 (iterate #(host/mul % ten) one))))


(defn- pow10
  "10^`k` as a big integer, for a native `k >= 0`."
  [k]
  (if (< k 401)
    (nth small-powers-of-ten k)
    (loop [result one
           base ten
           k k]
      (if (zero? k)
        result
        (recur (if (odd? k) (host/mul result base) result)
               (if (> k 1) (host/mul base base) base)
               (quot k 2))))))


(defn- scaled
  "`[num den]` with num / den = (x / y) * 10^`k`, for native `k`."
  [x y k]
  (if (neg? k)
    [x (host/mul y (pow10 (- k)))]
    [(host/mul x (pow10 k)) y]))


(def ^:private two-64-float (pow2 64))
(def ^:private two-minus-11-float (pow2 -11))


(defn- float-ratio
  "Finite positive host double `v` as big `[x y]`, `v = x / y` exactly
   with `y` a power of two. Scaling by a power of two is exact, so `v` is
   doubled (or multiplied by 2^64 while that stays below 2^53) until it
   is an integer."
  [v]
  (if (>= v two-53-float)
    [(trunc-big v) one]
    (loop [m v
           k 0]
      (cond (= m (host/native->double (host/trunc-native m)))
            [(host/from-native (host/trunc-native m))
             (host/shift-left-big one k)]
            (< m two-minus-11-float) (recur (* m two-64-float) (+ k 64))
            :else (recur (* m 2.0) (inc k))))))


(defn- decimal-exponent
  "The `e` with 10^e <= x / y < 10^(e + 1), for big `x, y > 0`: an
   estimate from the bit lengths, corrected exactly."
  [x y]
  (let [below? (fn [e]
                 (let [[a b] (scaled x y (- e))]
                   (neg? (host/compare-big a b))))]
    (loop [e (quot (* (- (bits x) (bits y)) 30103) 100000)]
      (cond (below? e) (recur (dec e))
            (not (below? (inc e))) (recur (inc e))
            :else e))))


(defn- strip-trailing-zeros
  [s]
  (loop [end (count s)]
    (if (and (> end 1) (= "0" (subs s (dec end) end)))
      (recur (dec end))
      (subs s 0 end))))


(defn- int-float-digits
  [_limits f]
  (let [v (float! 'float-digits 0 f)]
    (when (or (host/nan-float? v) (host/infinite-float? v) (zero? v))
      (wrong-type! 'float-digits 0 :finite-nonzero-float))
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
              lo (host/quot-trunc num den)
              r (host/rem-trunc num den)
              hi (host/add lo one)
              lo? (reads-back? lo k)
              hi? (and (not (zero? (sign r))) (reads-back? hi k))
              c (host/compare-big (host/shift-left-big r 1) den)
              d (cond (and lo? hi?)
                      (cond (neg? c) lo
                            (pos? c) hi
                            (zero? (sign (host/bit-and-big lo one))) lo
                            :else hi)
                      lo? lo
                      hi? hi
                      :else nil)]
          (if d
            (let [text (host/to-radix-string d 10)]
              ;; a ceiling of 10^n has one digit more
              [(strip-trailing-zeros text) (+ e (- (count text) n))])
            (recur (inc n))))))))


(def ^:private max-significant-digits
  "Digits `decimal->float` keeps before a sticky digit: a double's
   midpoint has at most 768 significant digits (the design's 767 was off
   by one; 800 plus the sticky digit leaves the margin)."
  800)


(defn- int-decimal->float
  [_limits digits exp10]
  (when-not (string? digits)
    (wrong-type! 'decimal->float 0 :string))
  (let [e (big! 'decimal->float 1 exp10)]
    (when (zero? (count digits))
      (refuse! 'decimal->float :syntax {}))
    (let [values (digit-values! 'decimal->float digits 10)
          first-nonzero (first (keep-indexed (fn [i d] (when (pos? d) i))
                                             values))]
      (if (nil? first-nonzero)
        0.0
        (let [last-nonzero (last (keep-indexed (fn [i d] (when (pos? d) i))
                                               values))
              sig (subvec values first-nonzero (inc last-nonzero))
              e (host/add e (host/from-native (- (count values)
                                                 (inc last-nonzero))))
              ;; keep 800 digits and one sticky digit for the rest,
              ;; which is never all zeros once trailing zeros are gone
              [sig e] (if (> (count sig) max-significant-digits)
                        [(conj (subvec sig 0 max-significant-digits) 1)
                         (host/add e (host/from-native
                                       (- (count sig)
                                          max-significant-digits 1)))]
                        [sig e])
              ;; 10^(s - 1) <= value < 10^s
              s (host/add e (host/from-native (count sig)))]
          (cond
            (pos? (host/compare-big s (host/from-native 310))) ##Inf
            (neg? (host/compare-big s (host/from-native -326))) 0.0
            :else
            (let [[x y] (scaled (digits->big sig 10) one (host/to-native e))
                  f (ratio->float x y false)]
              (if (keyword? f) ##Inf f))))))))


(defn- int-max-digits
  [limits]
  (::max-digits limits))


;; =============================================================================
;; Recognition
;; =============================================================================

(defn- int-integer?
  [_limits x]
  (host/exact-integer? x))


(defn- int-normalize
  [limits a]
  (out limits (big! 'normalize 0 a)))


;; =============================================================================
;; The module
;; =============================================================================

(def ^:private exports
  "`[name arities kernel]` of every export. Each kernel takes the
   composition's limits first."
  [['integer? [1] int-integer?]
   ['normalize [1] int-normalize]
   ['add [2] int-add]
   ['sub [2] int-sub]
   ['neg [1] int-neg]
   ['mul [2] int-mul]
   ['compare [2] int-compare]
   ['quot-rem [2] int-quot-rem]
   ['floor-div-mod [2] int-floor-div-mod]
   ['pow [2] int-pow]
   ['bit-and [2] int-bit-and]
   ['bit-or [2] int-bit-or]
   ['bit-xor [2] int-bit-xor]
   ['bit-not [1] int-bit-not]
   ['shift-left [2] int-shift-left]
   ['shift-right [2] int-shift-right]
   ['bit-length [1] int-bit-length]
   ['parse [1 2] int-parse]
   ['format [1 2] int-format]
   ['to-float [1] int-to-float]
   ['compare-float [2] int-compare-float]
   ['true-div [2] int-true-div]
   ['from-float [1] int-from-float]
   ['float-digits [1] int-float-digits]
   ['decimal->float [2] int-decimal->float]
   ['max-digits [0] int-max-digits]])


(defn- arity-checked
  "`f` refusing, as data, a call whose argument count `arities` does not
   admit, so a wrong count never reaches a host arity exception."
  [op arities f]
  (let [fixed (set arities)]
    (fn [& args]
      (let [n (count args)]
        (when-not (contains? fixed n)
          (refuse! op :arity {::argc n}))
        (apply f args)))))


(defn- check-limits!
  "Refuse `limits` unless both limits are given as positive native
   integers: the module has no default."
  [limits]
  (doseq [k [::max-bits ::max-digits]]
    (let [v (get limits k)]
      (when-not (and (host/exact-integer? v)
                     (not (host/big-carrier? v))
                     (pos? v))
        (throw (ex-info "integer module needs explicit limits"
                        {::reason :limits, ::limit k}))))))


(defn integer-module
  "The `integer` module's exports under `limits`, export symbol to host
   function. `limits` must carry `::max-bits` and `::max-digits`."
  [limits]
  (check-limits! limits)
  (let [limits (select-keys limits [::max-bits ::max-digits])
        ;; chosen once: every fast result fits in 53 bits
        fast? (>= (::max-bits limits) 53)]
    (into {}
          (map (fn [[op arities f]]
                 (let [f (if fast? (get fast-kernels op f) f)]
                   [op (arity-checked op arities (partial f limits))])))
          exports)))


(def integer-profiles
  "UCF 7.5.2 profiles for the `integer` module: every export `:pure`,
   declaring no effects and no host state."
  (into {}
        (map (fn [[op arities _]]
               [op (vm/primitive-profile op :pure arities #{} :none)]))
        exports))


(defn register-integer-module
  "Return registry with the `integer` module installed under `limits`.
   Composition-only: the module is never in `yin.vm/primitives`."
  [registry limits]
  (module/register-host-module registry module-name (integer-module limits)
                               integer-profiles))

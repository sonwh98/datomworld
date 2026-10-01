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
   host. The kernels are written once here over the shim.

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

   A refused call throws `ex-info` with the message `refusal-message` and
   ex-data built here, never host exception text, never an operand:
   `::op`, `::reason` and the reason's own keys. The reasons are
   `:arity`, `:wrong-type`, `:out-of-range`, `:syntax`, `:zero-division`,
   `:negative-count`, `:negative-exponent`, `:bit-limit` and
   `:digit-limit`. A runtime profile maps them to guest exceptions; the
   Python profile maps `:bit-limit` to `MemoryError` and `:digit-limit`
   to `ValueError`, never `OverflowError`."
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
   semantics is a new version."
  1)


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


(defn- bit-limit!
  [limits op]
  (refuse! op :bit-limit {::limit (::max-bits limits)}))


(defn- out
  "Big integer result `b` of `op` in its canonical carrier, or a
   `:bit-limit` refusal."
  [limits op b]
  (when (> (bits b) (::max-bits limits))
    (bit-limit! limits op))
  (host/demote b))


;; =============================================================================
;; Arithmetic
;; =============================================================================

(defn- int-add
  [limits a b]
  (out limits 'add (host/add (big! 'add 0 a) (big! 'add 1 b))))


(defn- int-sub
  [limits a b]
  (out limits 'sub (host/sub (big! 'sub 0 a) (big! 'sub 1 b))))


(defn- int-neg
  [limits a]
  (out limits 'neg (host/sub zero (big! 'neg 0 a))))


(defn- int-mul
  [limits a b]
  (let [x (big! 'mul 0 a)
        y (big! 'mul 1 b)]
    ;; |x*y| has bits(x) + bits(y) or one fewer bits
    (when (> (+ (bits x) (bits y) -1) (::max-bits limits))
      (bit-limit! limits 'mul))
    (out limits 'mul (host/mul x y))))


(defn- int-compare
  [_limits a b]
  (host/compare-big (big! 'compare 0 a) (big! 'compare 1 b)))


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
    [(out limits 'quot-rem (host/quot-trunc x y))
     (out limits 'quot-rem (host/rem-trunc x y))]))


(defn- int-floor-div-mod
  [limits a b]
  (let [x (big! 'floor-div-mod 0 a)
        y (divisor! 'floor-div-mod b)
        q (host/quot-trunc x y)
        r (host/rem-trunc x y)
        adjust? (and (not (zero? (sign r))) (not= (sign r) (sign y)))]
    [(out limits 'floor-div-mod (if adjust? (host/sub q one) q))
     (out limits 'floor-div-mod (if adjust? (host/add r y) r))]))


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
      (pos? (host/compare-big n (host/from-native max-bits)))
      (bit-limit! limits 'pow)
      :else
      (let [n (host/to-native n)]
        (when (> (inc (* (dec (bits x)) n)) max-bits)
          (bit-limit! limits 'pow))
        (out limits
             'pow
             (loop [result one
                    base x
                    n n]
               (let [result (if (odd? n) (host/mul result base) result)
                     n (quot n 2)]
                 (if (zero? n)
                   result
                   (recur result (host/mul base base) n)))))))))


;; =============================================================================
;; Bits
;; =============================================================================

(defn- int-bit-and
  [limits a b]
  (out limits 'bit-and
       (host/bit-and-big (big! 'bit-and 0 a) (big! 'bit-and 1 b))))


(defn- int-bit-or
  [limits a b]
  (out limits 'bit-or
       (host/bit-or-big (big! 'bit-or 0 a) (big! 'bit-or 1 b))))


(defn- int-bit-xor
  [limits a b]
  (out limits 'bit-xor
       (host/bit-xor-big (big! 'bit-xor 0 a) (big! 'bit-xor 1 b))))


(defn- int-bit-not
  [limits a]
  (out limits 'bit-not (host/sub (host/sub zero (big! 'bit-not 0 a)) one)))


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
      (pos? (host/compare-big n (host/from-native max-bits)))
      (bit-limit! limits 'shift-left)
      :else (let [n (host/to-native n)]
              (when (> (+ (bits x) n) max-bits)
                (bit-limit! limits 'shift-left))
              (out limits 'shift-left (host/shift-left-big x n))))))


(defn- int-shift-right
  [limits a k]
  (let [x (big! 'shift-right 0 a)
        n (count! 'shift-right k)]
    (if (neg? (host/compare-big n (host/from-native (bits x))))
      (out limits 'shift-right (host/shift-right-big x (host/to-native n)))
      (if (neg? (sign x)) -1 0))))


(defn- int-bit-length
  [_limits a]
  (bits (big! 'bit-length 0 a)))


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


(defn- digit-limit!
  [limits op]
  (refuse! op :digit-limit {::limit (::max-digits limits)}))


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
     (when (and (not (power-of-two? r)) (> n (::max-digits limits)))
       (digit-limit! limits 'parse))
     (let [values (mapv (fn [i]
                          (let [d (get digit-values (subs digits i (inc i)))]
                            (if (and d (< d r))
                              d
                              (refuse! 'parse :syntax {}))))
                        (range n))
           magnitude
           (loop [acc zero
                  i 0]
             (if (< i n)
               (let [end (min n (+ i chunk-digits))
                     [chunk scale] (reduce (fn [[c s] d] [(+ (* c r) d) (* s r)])
                                           [0 1]
                                           (subvec values i end))]
                 (recur (host/add (host/mul acc (host/from-native scale))
                                  (host/from-native chunk))
                        end))
               acc))]
       (out limits 'parse (if negative? (host/sub zero magnitude) magnitude))))))


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
     (when (and limited?
                (pos? (bits x))
                (>= (dec (bits x)) (* (ceil-log2 r) max-digits)))
       (digit-limit! limits 'format))
     (let [text (host/to-radix-string x r)]
       (when (and limited?
                  (> (- (count text) (if (neg? (sign x)) 1 0)) max-digits))
         (digit-limit! limits 'format))
       text))))


;; =============================================================================
;; Recognition
;; =============================================================================

(defn- int-integer?
  [_limits x]
  (host/exact-integer? x))


(defn- int-normalize
  [limits a]
  (out limits 'normalize (big! 'normalize 0 a)))


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
   ['format [1 2] int-format]])


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
  (let [limits (select-keys limits [::max-bits ::max-digits])]
    (into {}
          (map (fn [[op arities f]]
                 [op (arity-checked op arities (partial f limits))]))
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

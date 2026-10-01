(ns yin.vm.integer.host
  "The per-host shim the exact-integer kernels of `yin.vm.integer` are
   written over (C3 rulings 2 and 5): carrier recognition, promotion to
   the host big integer, demotion to the one canonical carrier, and the
   few exact big-integer operations each host provides natively.

   Each integer value has exactly one carrier per host:

   | Host | Native carrier                      | Big carrier          |
   |------|-------------------------------------|----------------------|
   | JVM  | `long`, signed 64-bit               | `clojure.lang.BigInt`|
   | JS   | `Number`, within +/-(2^53 - 1)      | host `BigInt`        |
   | Dart | `int`, signed 64-bit                | `BigInt`             |

   `promote` turns any exact-integer carrier into the host big integer
   the operations below take (`java.math.BigInteger` on the JVM, which
   never leaves this namespace and `yin.vm.integer`); `demote` turns a
   result back into its canonical carrier. JavaScript holds +/-2^53 as a
   `BigInt`, never as an unsafe `Number`, and a JavaScript `-0` is
   floating-point content, not an integer, as `dao.jing.cbor` classifies
   it.

   This namespace depends on nothing: it does not reach into
   `dao.jing.cbor`'s private big-integer helpers, so Jing stays a passive
   codec. Every reader conditional names `:cljd` first, because the
   ClojureDart build also reads `:clj`."
  #?@(:cljd [(:import ["dart:core" BigInt])]
      :clj [(:import (java.math BigInteger))]))


;; =============================================================================
;; Recognition
;; =============================================================================

#?(:cljs
   (defn- js-bigint?
     [x]
     (identical? "bigint" (js* "typeof ~{}" x))))


(defn big-carrier?
  "True when `x` is a host big-integer carrier: `clojure.lang.BigInt` or
   `BigInteger` on the JVM, a `BigInt` on JS and Dart. On JS and Dart
   such a value answers false to `number?`."
  [x]
  #?(:cljd (dart/is? x BigInt)
     :clj (or (instance? clojure.lang.BigInt x) (instance? BigInteger x))
     :cljs (js-bigint? x)))


(defn exact-integer?
  "True when `x` is an exact integer in any carrier the host has: a JVM
   `long`, `int`, `short`, `byte`, `BigInt` or `BigInteger`; a JS
   safe-integer `Number` other than `-0`, or a `BigInt`; a Dart `int` or
   `BigInt`. A float with an integral value is not, except on JS, which
   cannot tell `1.0` from `1`."
  [x]
  #?(:cljd (or (dart/is? x int) (dart/is? x BigInt))
     :clj (or (instance? Long x) (instance? Integer x) (instance? Short x)
              (instance? Byte x) (instance? clojure.lang.BigInt x)
              (instance? BigInteger x))
     :cljs (or (js-bigint? x)
               (and (number? x)
                    (js/Number.isSafeInteger x)
                    ;; -0 is the one zero whose reciprocal is negative
                    (not (and (zero? x) (neg? (/ 1 x))))))))


;; =============================================================================
;; Promotion and demotion
;; =============================================================================

(defn promote
  "The host big integer of exact-integer carrier `x`. The caller has
   checked `exact-integer?`."
  [x]
  #?(:cljd (if (dart/is? x BigInt) x (BigInt.from x))
     :clj (cond (instance? BigInteger x) x
                (instance? clojure.lang.BigInt x)
                (.toBigInteger ^clojure.lang.BigInt x)
                :else (BigInteger/valueOf (long x)))
     :cljs (if (js-bigint? x) x (js/BigInt x))))


(defn from-native
  "The host big integer of a native integer `n` the kernels computed
   themselves, such as a digit or a chunk value."
  [n]
  #?(:cljd (BigInt.from n)
     :clj (BigInteger/valueOf (long n))
     :cljs (js/BigInt n)))


#?(:cljs
   (def ^:private js-zero (js/BigInt 0)))


#?(:cljs
   (def ^:private js-max-safe (js/BigInt 9007199254740991)))


#?(:cljs
   (def ^:private js-min-safe (js/BigInt -9007199254740991)))


(defn demote
  "The canonical carrier of host big integer `b`: native iff `b` is in
   the host's native range, else the host big carrier."
  [b]
  #?(:cljd (if (.-isValidInt ^BigInt b) (.toInt ^BigInt b) b)
     :clj (if (< (.bitLength ^BigInteger b) 64)
            (.longValue ^BigInteger b)
            (clojure.lang.BigInt/fromBigInteger b))
     :cljs (if (js* "(~{} <= ~{} && ~{} <= ~{})" js-min-safe b b js-max-safe)
             (js/Number b)
             b)))


(defn to-native
  "Host big integer `b` as a native integer. The caller has checked that
   `b` lies within +/-(2^53 - 1)."
  [b]
  #?(:cljd (.toInt ^BigInt b)
     :clj (.longValue ^BigInteger b)
     :cljs (js/Number b)))


;; =============================================================================
;; Exact operations on host big integers
;; =============================================================================

(defn add
  [a b]
  #?(:cljd (. ^BigInt a "+" b)
     :clj (.add ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} + ~{})" a b)))


(defn sub
  [a b]
  #?(:cljd (. ^BigInt a "-" b)
     :clj (.subtract ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} - ~{})" a b)))


(defn mul
  [a b]
  #?(:cljd (. ^BigInt a "*" b)
     :clj (.multiply ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} * ~{})" a b)))


(defn quot-trunc
  "The quotient of `a` by non-zero `b`, truncated toward zero."
  [a b]
  #?(:cljd (. ^BigInt a "~/" b)
     :clj (.divide ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} / ~{})" a b)))


(defn rem-trunc
  "The remainder of `a` by non-zero `b`, with the sign of `a`."
  [a b]
  #?(:cljd (.remainder ^BigInt a b)
     :clj (.remainder ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} % ~{})" a b)))


(defn compare-big
  "-1, 0 or 1 as `a` is less than, equal to or greater than `b`."
  [a b]
  ;; Dart's compareTo answers any negative or positive int.
  #?(:cljd (let [c (.compareTo ^BigInt a b)]
             (cond (neg? c) -1 (pos? c) 1 :else 0))
     :clj (long (.compareTo ^BigInteger a ^BigInteger b))
     :cljs (cond (js* "(~{} < ~{})" a b) -1
                 (js* "(~{} > ~{})" a b) 1
                 :else 0)))


(defn bit-and-big
  "Bitwise and over infinite two's complement."
  [a b]
  #?(:cljd (. ^BigInt a "&" b)
     :clj (.and ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} & ~{})" a b)))


(defn bit-or-big
  "Bitwise or over infinite two's complement."
  [a b]
  #?(:cljd (. ^BigInt a "|" b)
     :clj (.or ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} | ~{})" a b)))


(defn bit-xor-big
  "Bitwise exclusive or over infinite two's complement."
  [a b]
  #?(:cljd (. ^BigInt a "^" b)
     :clj (.xor ^BigInteger a ^BigInteger b)
     :cljs (js* "(~{} ^ ~{})" a b)))


(defn shift-left-big
  "`a` times 2^`k`, for a native, non-negative count `k`."
  [a k]
  #?(:cljd (. ^BigInt a "<<" k)
     :clj (.shiftLeft ^BigInteger a (int k))
     :cljs (js* "(~{} << ~{})" a (js/BigInt k))))


(defn shift-right-big
  "The floor of `a` divided by 2^`k`, for a native, non-negative count
   `k`."
  [a k]
  #?(:cljd (. ^BigInt a ">>" k)
     :clj (.shiftRight ^BigInteger a (int k))
     :cljs (js* "(~{} >> ~{})" a (js/BigInt k))))


(defn magnitude-bit-length
  "The number of bits of the absolute value of `a`, 0 for zero: Python's
   `int.bit_length`."
  [a]
  #?(:cljd (.-bitLength (.abs ^BigInt a))
     :clj (long (.bitLength (.abs ^BigInteger a)))
     :cljs (let [m (if (js* "(~{} < ~{})" a js-zero) (js* "(-~{})" a) a)]
             (if (js* "(~{} === ~{})" m js-zero)
               0
               (.-length (.toString m 2))))))


(defn to-radix-string
  "The exact text of `a` in `radix` (2 to 36): lowercase digits, a
   leading `-` only when negative, no prefix."
  [a radix]
  #?(:cljd (.toRadixString ^BigInt a radix)
     :clj (.toString ^BigInteger a (int radix))
     :cljs (.toString a radix)))

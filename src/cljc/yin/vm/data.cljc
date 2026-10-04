(ns yin.vm.data
  "The `data` host module: pure collection and string primitives a
   language prelude is written over (Architect ruling on mutable guest
   objects, Q5 and Q6).

   Every export is `:pure` with declared effects `#{}`. The module is not
   part of `yin.vm/primitives`: a composition whose language runtime
   profile names it installs it explicitly with `register-data-module`,
   and Yin source reaches the exports as `data/count`, `data/substring`
   and so on.

   Results are identical on CLJ, CLJS and CLJD:

   - Strings are indexed by code point, as Python indexes them, never by
     the hosts' UTF-16 code units: `(data/str-length \"a😀\")` is 2 on
     every host. An unpaired surrogate is one code point of its own.
   - `str-compare` orders by code point, which UTF-16 unit order does not
     for supplementary characters against U+E000 to U+FFFF.
   - Every string call decodes its whole argument, O(n), and search is a
     naive O(n*m) scan: a prelude loop over a string's characters calls
     `str->code-points` once and walks the vector with `nth`, never
     `char-at` per step.
   - An index is any number with an integral value, so `1.0` is index 1
     on the JVM as it is on JS and Dart, which cannot tell it from `1`.
   - Nothing iterates a host map or a host set into an ordered result:
     host iteration order differs by host. An ordered dict is prelude
     code, an index map beside an order vector.
   - A refused call throws `ex-info` with the message `refusal-message`
     and ex-data built here, never host exception text: `::op`,
     `::reason` (`:arity`, `:wrong-type`, `:out-of-range` or
     `:empty-separator`), and the reason's own keys.

   Key equality in `contains?`, `dissoc`, `disj`, `hash-set` and `into` a
   set is the host's `=`, which differs for `1` and `1.0` on the JVM. A
   runtime profile normalizes guest keys before they reach these."
  (:require
    [clojure.string :as str]
    [dao.jing.cbor :as cbor]
    [yin.vm :as vm]
    [yin.vm.integer.host :as integer-host]
    [yin.vm.module :as module]
    [yin.vm.values :as values])
  #?(:cljd (:import ["dart:core" BigInt])))


(def module-name
  "The module `register-data-module` installs."
  'data)


(def refusal-message
  "The message of every refusal a `data` export throws."
  "data primitive refused")


(defn- refuse!
  [op reason data]
  (throw (ex-info refusal-message (merge {::op op, ::reason reason} data))))


(defn- wrong-type!
  "Refuse argument `arg` (0-based) of `op`, which must be `expected`."
  [op arg expected]
  (refuse! op :wrong-type {::arg arg, ::expected expected}))


;; =============================================================================
;; Indices
;; =============================================================================

(def ^:private max-safe
  "The largest integer every host represents exactly, 2^53 - 1."
  9007199254740991)


(defn- integral
  "`x` as an integer when it is a number with an integral value within
   +/- `max-safe`, else nil. The bound keeps an index identical on hosts
   whose large numbers are doubles."
  [x]
  ;; ClojureDart supplies both :clj and :cljd reader features, so its branch
  ;; must come first in every mixed-host reader conditional here.
  (when (and (number? x)
             #?(:cljd (.-isFinite ^num x)
                :clj (or (integer? x)
                         (and (instance? Double x) (Double/isFinite x)))
                :cljs (js/isFinite x))
             (<= (- max-safe) x max-safe))
    #?(:cljd (when (== x (.round ^num x)) (.toInt ^num x))
       :clj (when (or (integer? x) (== x (Math/rint (double x)))) (long x))
       :cljs (when (integer? x) x))))


(defn- index!
  "Argument `arg` of `op` as an integer, or a `:wrong-type` refusal."
  [op arg x]
  (let [i (integral x)]
    (if (nil? i)
      (wrong-type! op arg :integer)
      i)))


(defn- in-range!
  "Refuse `i` unless `lo <= i <= hi`. `::index` is always the position
   the call would access: the caller's index, or for `pop` the last
   index, `(count v) - 1`, so an empty vector reports `::hi` -1 for
   both `nth` and `pop`."
  [op i lo hi]
  (when-not (<= lo i hi)
    (refuse! op :out-of-range {::index i, ::lo lo, ::hi hi}))
  i)


;; =============================================================================
;; Code points
;; =============================================================================

(defn- unit-count
  [s]
  #?(:cljd (.-length ^String s)
     :clj (.length ^String s)
     :cljs (.-length s)))


(defn- code-unit
  [s i]
  #?(:cljd (.codeUnitAt ^String s i)
     :clj (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)))


(defn- high-surrogate?
  [u]
  (<= 0xD800 u 0xDBFF))


(defn- low-surrogate?
  [u]
  (<= 0xDC00 u 0xDFFF))


(defn- code-points
  "The code points of string `s`: a surrogate pair decodes to one code
   point, an unpaired surrogate is its own."
  [s]
  (let [n (unit-count s)]
    (loop [i 0
           out (transient [])]
      (if (< i n)
        (let [u (code-unit s i)
              v (when (< (inc i) n) (code-unit s (inc i)))]
          (if (and (high-surrogate? u) v (low-surrogate? v))
            (recur (+ i 2)
                   (conj! out (+ 0x10000
                                 (* (- u 0xD800) 0x400)
                                 (- v 0xDC00))))
            (recur (inc i) (conj! out u))))
        (persistent! out)))))


(defn- units->string
  "The host string of UTF-16 code units `units`."
  [units]
  #?(:cljd (let [sb (StringBuffer)]
             (doseq [u units] (.writeCharCode sb u))
             (.toString sb))
     :clj (let [sb (StringBuilder.)]
            (doseq [u units] (.append sb (char u)))
            (.toString sb))
     :cljs (let [a (array)]
             (doseq [u units] (.push a (.fromCharCode js/String u)))
             (.join a ""))))


(defn- code-points->string
  "The string of code points `cps`, each already checked to lie in
   0 to 0x10FFFF."
  [cps]
  (units->string
    (mapcat (fn [cp]
              (if (< cp 0x10000)
                [cp]
                (let [c (- cp 0x10000)]
                  [(+ 0xD800 (quot c 0x400)) (+ 0xDC00 (rem c 0x400))])))
            cps)))


(defn- string!
  [op arg x]
  (when-not (string? x)
    (wrong-type! op arg :string))
  x)


(defn- find-code-points
  "The first index at or after `from` where `needle` occurs in `hay`,
   both code point vectors, or -1."
  [hay needle from]
  (let [m (count needle)
        last-start (- (count hay) m)]
    (loop [i from]
      (cond (> i last-start) -1
            (= needle (subvec hay i (+ i m))) i
            :else (recur (inc i))))))


;; =============================================================================
;; Collection exports
;; =============================================================================

(defn- data-count
  [coll]
  (if (or (nil? coll) (coll? coll))
    (count coll)
    (wrong-type! 'count 0 :collection)))


(defn- vector!
  [op arg x]
  (when-not (vector? x)
    (wrong-type! op arg :vector))
  x)


(defn- data-nth
  [v i]
  (vector! 'nth 0 v)
  (let [i (index! 'nth 1 i)]
    (in-range! 'nth i 0 (dec (count v)))
    (nth v i)))


(defn- data-contains?
  [coll k]
  (cond
    (or (nil? coll) (map? coll) (set? coll)) (contains? coll k)
    (vector? coll) (let [i (integral k)]
                     (boolean (and i (< -1 i (count coll)))))
    :else (wrong-type! 'contains? 0 :map-set-or-vector)))


(defn- data-dissoc
  [m & ks]
  (if (or (nil? m) (map? m))
    (apply dissoc m ks)
    (wrong-type! 'dissoc 0 :map)))


(defn- data-disj
  [s & ks]
  (if (or (nil? s) (set? s))
    (apply disj s ks)
    (wrong-type! 'disj 0 :set)))


(defn- data-peek
  [v]
  (if (or (nil? v) (vector? v))
    (peek v)
    (wrong-type! 'peek 0 :vector)))


(defn- data-pop
  [v]
  (cond
    (nil? v) nil
    (not (vector? v)) (wrong-type! 'pop 0 :vector)
    :else (let [last-index (dec (count v))]
            (in-range! 'pop last-index 0 last-index)
            (pop v))))


(defn- data-subvec
  ([v start] (data-subvec v start (if (vector? v) (count v) 0)))
  ([v start end]
   (vector! 'subvec 0 v)
   (let [start (index! 'subvec 1 start)
         end (index! 'subvec 2 end)]
     (in-range! 'subvec end 0 (count v))
     (in-range! 'subvec start 0 end)
     (into [] (subvec v start end)))))


(defn- data-into
  [to from]
  (cond
    (vector? to)
    (if (or (nil? from) (sequential? from))
      (into to from)
      (wrong-type! 'into 1 :sequential))

    (set? to)
    (if (or (nil? from) (sequential? from) (set? from))
      (into to from)
      (wrong-type! 'into 1 :sequential-or-set))

    :else (wrong-type! 'into 0 :vector-or-set)))


;; =============================================================================
;; String exports
;; =============================================================================

(defn- str-concat
  [& strs]
  (doseq [[i s] (map-indexed vector strs)]
    (string! 'str-concat i s))
  (apply str strs))


(defn- str-length
  [s]
  (count (code-points (string! 'str-length 0 s))))


(defn- substring
  ([s start]
   (substring s start (if (string? s) (str-length s) 0)))
  ([s start end]
   (let [cps (code-points (string! 'substring 0 s))
         start (index! 'substring 1 start)
         end (index! 'substring 2 end)]
     (in-range! 'substring end 0 (count cps))
     (in-range! 'substring start 0 end)
     (code-points->string (subvec cps start end)))))


(defn- str-index-of
  ([s needle] (str-index-of s needle 0))
  ([s needle from]
   (let [hay (code-points (string! 'str-index-of 0 s))
         needle (code-points (string! 'str-index-of 1 needle))
         from (index! 'str-index-of 2 from)]
     (in-range! 'str-index-of from 0 (count hay))
     (let [i (find-code-points hay needle from)]
       (when-not (neg? i) i)))))


(defn- str-split
  [s sep]
  (let [hay (code-points (string! 'str-split 0 s))
        sep (code-points (string! 'str-split 1 sep))
        m (count sep)]
    (when (zero? m)
      (refuse! 'str-split :empty-separator {}))
    (loop [from 0
           out []]
      (let [i (find-code-points hay sep from)]
        (if (neg? i)
          (conj out (code-points->string (subvec hay from)))
          (recur (+ i m)
                 (conj out (code-points->string (subvec hay from i)))))))))


(defn- str-join
  [sep strs]
  (string! 'str-join 0 sep)
  (when-not (or (nil? strs) (sequential? strs))
    (wrong-type! 'str-join 1 :sequential))
  (doseq [s strs]
    (when-not (string? s)
      (wrong-type! 'str-join 1 :strings)))
  (str/join sep strs))


(defn- char-at
  [s i]
  (let [cps (code-points (string! 'char-at 0 s))
        i (index! 'char-at 1 i)]
    (in-range! 'char-at i 0 (dec (count cps)))
    (code-points->string [(nth cps i)])))


(defn- str->code-points
  [s]
  (code-points (string! 'str->code-points 0 s)))


(defn- code-points->str
  [cps]
  (when-not (sequential? cps)
    (wrong-type! 'code-points->str 0 :sequential))
  (code-points->string
    (mapv (fn [cp]
            (let [c (integral cp)]
              (when-not (and c (<= 0 c 0x10FFFF))
                (wrong-type! 'code-points->str 0 :code-points))
              c))
          cps)))


(defn- str-compare
  "-1, 0 or 1 as `a` orders before, with or after `b` by code point."
  [a b]
  (let [x (code-points (string! 'str-compare 0 a))
        y (code-points (string! 'str-compare 1 b))
        n (min (count x) (count y))]
    (loop [i 0]
      (if (< i n)
        (let [p (nth x i)
              q (nth y i)]
          (cond (< p q) -1
                (> p q) 1
                :else (recur (inc i))))
        (cond (< (count x) (count y)) -1
              (> (count x) (count y)) 1
              :else 0)))))


;; =============================================================================
;; Kinds
;; =============================================================================

(defn- data-number?
  "True when `x` is a host number or an exact-integer carrier, including
   a JS or Dart big integer, which answers false to `number?`. A
   closure, a continuation, nil and a map are not, whatever they answer
   to `get`."
  [x]
  (or (number? x) (integer-host/big-carrier? x)))


;; =============================================================================
;; Floats: the seam between addressed float content and host arithmetic
;; =============================================================================

(defn float-value
  "The host double of number or float64 content `x`, for host arithmetic:
   what the literal `1.0` is on each host, a double on the JVM and Dart, a
   Number on JS. A prelude unwraps a float with this, computes on bare
   numbers, and wraps the result back with `float64`."
  [x]
  (when-not (or (data-number? x) (cbor/float64? x))
    (wrong-type! 'float-value 0 :number))
  #?(:cljd (if (dart/is? x BigInt) (.toDouble ^BigInt x) (.toDouble ^num x))
     :clj (double x)
     :cljs (cond (number? x) x
                 (cbor/float64? x) (.-v ^not-native x)
                 :else (js/Number x))))


(defn float64
  "Float content with the value of number `x`: Jing's float64, the form a
   float keeps in any value that can reach an addressed row or image (a
   double on the JVM and Dart, the float64 carrier on JS, where a bare
   integral Number is an integer; dao.jing.cbor.md, Numeric identity). The
   producer carries float kind; the codec never infers it."
  [x]
  (cbor/float64 (float-value x)))


(defn- callable?
  "True when `x` can be applied: a host function, a closure or a
   continuation. A map is never callable, whatever keys it carries."
  [x]
  (or (fn? x) (values/closure? x) (values/continuation? x)))


(defn- ref?
  "True when `x` is a stream, cursor or cell reference."
  [x]
  (and (map? x) (contains? #{:stream-ref :cursor-ref :cell-ref} (:type x))))


(defn- data-content=
  "True when `a` and `b` have one identity under content addressing:
   Jing's kind-strict content equality, so every NaN is one value, 0.0 is
   not -0.0, 1 is not 1.0, and collections recurse. A callable or a
   reference compares by host `=`, so content addressing never walks it."
  [a b]
  (or (identical? a b)
      (if (or (callable? a) (callable? b) (ref? a) (ref? b))
        (= a b)
        (cbor/content= a b))))


;; =============================================================================
;; The module
;; =============================================================================

(def ^:private exports
  "`[name arities f]` of every export. `:variadic` means the greatest
   listed fixed arity extends to further arguments."
  [['count [1] data-count]
   ['nth [2] data-nth]
   ['contains? [2] data-contains?]
   ['dissoc [1 :variadic] data-dissoc]
   ['disj [1 :variadic] data-disj]
   ['peek [1] data-peek]
   ['pop [1] data-pop]
   ['subvec [2 3] data-subvec]
   ['hash-set [0 :variadic] hash-set]
   ['into [2] data-into]
   ['str-concat [0 :variadic] str-concat]
   ['str-length [1] str-length]
   ['substring [2 3] substring]
   ['str-index-of [2 3] str-index-of]
   ['str-split [2] str-split]
   ['str-join [2] str-join]
   ['char-at [2] char-at]
   ['str->code-points [1] str->code-points]
   ['code-points->str [1] code-points->str]
   ['str-compare [2] str-compare]
   ['number? [1] data-number?]
   ['float64 [1] float64]
   ['float-value [1] float-value]
   ['content= [2] data-content=]
   ['callable? [1] callable?]])


(defn- arity-checked
  "`f` refusing, as data, a call whose argument count `arities` does not
   admit, so a wrong count never reaches a host arity exception."
  [op arities f]
  (let [fixed (set (filter number? arities))
        variadic-from (when (some #{:variadic} arities) (apply max fixed))]
    (fn [& args]
      (let [n (count args)]
        (when-not (or (contains? fixed n)
                      (and variadic-from (>= n variadic-from)))
          (refuse! op :arity {::argc n}))
        (apply f args)))))


(def data-module
  "The `data` module's exports, export symbol to host function."
  (into {}
        (map (fn [[op arities f]] [op (arity-checked op arities f)]))
        exports))


(def data-profiles
  "UCF 7.5.2 profiles for the `data` module: every export `:pure`,
   declaring no effects and no host state."
  (into {}
        (map (fn [[op arities _]]
               [op (vm/primitive-profile op :pure arities #{} :none)]))
        exports))


(defn register-data-module
  "Return registry with the `data` module installed. Composition-only:
   the module is never in `yin.vm/primitives`."
  [registry]
  (module/register-host-module registry module-name data-module
                               data-profiles))

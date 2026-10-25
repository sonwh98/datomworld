(ns yang.typescript.antlr.render
  "JavaScript text for the values a program logged, derived at the boundary
   from the plain-data snapshots `js/log!` collects (docs/design/
   yang.typescript.md section 10). Portable and deterministic: a number
   arrives as float64 content and a bigint as a bare exact integer, so
   `1` and `1n` print differently on every host, JS included.

   `number->string` is ECMAScript's Number::toString(10) over the shortest
   round-trip digits of the `integer` module's `float-digits`, never host
   `str`: fixed notation when the decimal exponent is from -7 exclusive to
   21 exclusive, `d.ddde+N` otherwise, `-0` prints `0`. A parity law binds
   it to the prelude's `js/number->string`."
  (:require
    [clojure.string :as str]
    [dao.jing.cbor :as cbor]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]))


(def ^:private exact
  "The `integer` exports with limits no snapshot reaches: the renderer
   writes a bigint's exact decimal with no digit limit, and a float's
   shortest digits."
  (integer/integer-module {::integer/max-bits 9007199254740991,
                           ::integer/max-digits 9007199254740991}))


(defn- negative-zero?
  [x]
  (and (zero? x) (neg? (/ 1.0 x))))


(defn- zeros
  [n]
  (apply str (repeat n "0")))


(defn- layout
  "Number::toString(10) of |v| = d1.d2...dk x 10^`e`, with n = e + 1."
  [digits e]
  (let [k (count digits)
        n (inc e)]
    (cond
      (<= k n 21) (str digits (zeros (- n k)))
      (< 0 n 22) (str (subs digits 0 n) "." (subs digits n))
      (< -6 n 1) (str "0." (zeros (- n)) digits)
      :else (str (subs digits 0 1)
                 (when (> k 1) (str "." (subs digits 1)))
                 "e"
                 (if (neg? (dec n)) "-" "+")
                 (let [m (dec n)] (if (neg? m) (- m) m))))))


(defn number->string
  "Number::toString(10) of `f`, a host number or float64 content (on JS,
   Jing's carrier)."
  [f]
  (let [x (data/float-value f)]
    (cond
      #?(:cljd (.-isNaN ^num x)
         :clj (Double/isNaN x)
         :cljs (js/isNaN x))
      "NaN"
      (= x ##Inf) "Infinity"
      (= x ##-Inf) "-Infinity"
      (zero? x) "0"
      :else (let [[digits e] ((get exact 'float-digits) x)]
              (str (when (neg? x) "-") (layout digits e))))))


(defn- code-points->str
  [cps]
  ((get data/data-module 'code-points->str) cps))


(defn- units->code-points
  "The code points of UTF-16 `units`, an unpaired surrogate as U+FFFD, as
   a UTF-8 writer prints it."
  [units]
  (loop [us (seq units)
         out []]
    (if-let [u (first us)]
      (let [v (second us)]
        (cond
          (and (<= 0xD800 u 0xDBFF) v (<= 0xDC00 v 0xDFFF))
          (recur (nnext us)
                 (conj out (+ 0x10000 (* 0x400 (- u 0xD800)) (- v 0xDC00))))
          (<= 0xD800 u 0xDFFF) (recur (next us) (conj out 0xFFFD))
          :else (recur (next us) (conj out u))))
      out)))


(defn js-string
  "The host string of a guest string value `{:js/str s}` or
   `{:js/units [u ...]}`."
  [v]
  (if (contains? v :js/str)
    (:js/str v)
    (code-points->str (units->code-points (:js/units v)))))


(defn- js-string?
  [v]
  (and (map? v) (or (contains? v :js/str) (contains? v :js/units))))


(defn- function-text
  [v]
  (let [n (js-string (:name v))]
    (if (= "" n) "[Function (anonymous)]" (str "[Function: " n "]"))))


(defn inspect
  "util.inspect of one snapshot value as `console.log` shows it as a
   non-first-position argument: strings bare, `-0` as `-0`."
  [v]
  (cond
    (= :js/undefined v) "undefined"
    (= :js/null v) "null"
    (true? v) "true"
    (false? v) "false"
    (js-string? v) (js-string v)
    (cbor/float64? v) (let [x (data/float-value v)]
                        (if (and (zero? x) (negative-zero? x))
                          "-0"
                          (number->string v)))
    ((get exact 'integer?) v) (str ((get exact 'format) v) "n")
    (and (map? v) (= :function (:js/snap v))) (function-text v)
    (and (map? v) (= :object (:js/snap v))) "[object Object]"
    :else (pr-str v)))


(defn to-string
  "ToString of one snapshot value, as the uncaught line prints a thrown
   primitive."
  [v]
  (cond
    (cbor/float64? v) (number->string v)
    ((get exact 'integer?) v) ((get exact 'format) v)
    :else (inspect v)))


(defn line
  "One `console.log` call's text: its arguments, space separated."
  [values]
  (str/join " " (map inspect values)))


(defn- uncaught-text
  [u]
  (cond
    (nil? u) nil
    (contains? u :name) (str (js-string (:name u)) ": " (js-string (:message u)))
    :else (to-string (:value u))))


(defn output
  "A program value's logged lines as text, the uncaught throw as one
   `<name>: <message>` or `ToString(v)` line, or nil."
  [{:js/keys [out uncaught]}]
  {:js/out (mapv line out), :js/uncaught (uncaught-text uncaught)})

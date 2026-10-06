(ns yang.python.antlr.render
  "Python text for the values a program printed, derived at the boundary
   from the plain-data snapshots `py/print` collects (the same place owner
   decision 2 puts traceback line numbers). Portable and deterministic: a
   float arrives tagged `{:py/float x}`, so `4/2` prints `2.0` on every
   host, JS included.

   Float repr is CPython's on every host and for every double: the
   shortest round-trip digits come from the `integer` module's
   `float-digits` (version 4), never from host `str`, in fixed notation
   when the decimal exponent is from -4 to 15 and in exponent notation
   otherwise."
  (:require
    [clojure.string :as str]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]))


(def ^:private exact
  "The `integer` exports with limits no snapshot reaches: the renderer
   writes an integer's exact decimal with no digit limit, as CPython
   3.9.6 prints it (a limit on `print` belongs to guest `str`), and a
   float's shortest digits."
  (integer/integer-module {::integer/max-bits 9007199254740991,
                           ::integer/max-digits 9007199254740991}))


(defn- negative-zero?
  [x]
  (and (zero? x) (neg? (/ 1.0 x))))


(defn- zeros
  [n]
  (apply str (repeat n "0")))


(defn- exponent-text
  "CPython's exponent: `e`, a sign, and at least two digits."
  [e]
  (let [digits (str (if (neg? e) (- e) e))]
    (str (if (neg? e) "e-" "e+")
         (if (< (count digits) 2) (str "0" digits) digits))))


(defn- digits-repr
  "CPython's `repr` of |v| = d1.d2...dn x 10^`e`: fixed notation when
   -4 <= e < 16, with `.0` when there is no fraction, else `d[.ddd]`
   and the exponent."
  [digits e]
  (let [n (count digits)]
    (cond
      (and (<= 0 e) (< e 16))
      (if (<= n (inc e))
        (str digits (zeros (- (inc e) n)) ".0")
        (str (subs digits 0 (inc e)) "." (subs digits (inc e))))
      (and (<= -4 e) (< e 0))
      (str "0." (zeros (dec (- e))) digits)
      :else
      (str (subs digits 0 1)
           (when (> n 1) (str "." (subs digits 1)))
           (exponent-text e)))))


(defn float-repr
  "Python repr of float `f`, a host number or float64 content (on JS,
   Jing's carrier)."
  [f]
  (let [x (data/float-value f)]
    (cond
      #?(:cljd (.-isNaN ^num x)
         :clj (Double/isNaN x)
         :cljs (js/isNaN x))
      "nan"
      (= x ##Inf) "inf"
      (= x ##-Inf) "-inf"
      (zero? x) (if (negative-zero? x) "-0.0" "0.0")
      :else (let [[digits e] ((get exact 'float-digits) x)]
              (str (when (neg? x) "-") (digits-repr digits e))))))


(declare repr)


(defn- code-unit
  [s i]
  #?(:cljd (.codeUnitAt ^String s i)
     :clj (int (.charAt ^String s (int i)))
     :cljs (.charCodeAt s i)))


(defn- hex2
  [n]
  (let [digits "0123456789abcdef"]
    (str (subs digits (quot n 16) (inc (quot n 16)))
         (subs digits (rem n 16) (inc (rem n 16))))))


(defn string-repr
  "Python's str repr: single quotes unless the text has a single quote and
   no double quote; backslash, the quote, \\n \\r \\t and other control
   characters escaped."
  [s]
  (let [q (if (and (str/includes? s "'") (not (str/includes? s "\""))) "\"" "'")]
    (str q
         (apply str
                (map (fn [i]
                       (let [c (code-unit s i)
                             ch (subs s i (inc i))]
                         (cond
                           (= ch "\\") "\\\\"
                           (= ch q) (str "\\" q)
                           (= c 10) "\\n"
                           (= c 13) "\\r"
                           (= c 9) "\\t"
                           (or (< c 32) (= c 127)) (str "\\x" (hex2 c))
                           :else ch)))
                     (range (count s))))
         q)))


(defn- seq-repr
  [open close xs]
  (str open (str/join ", " (map repr xs)) close))


(defn repr
  "Python `repr` of one snapshot value."
  [v]
  (cond
    (nil? v) "None"
    (true? v) "True"
    (false? v) "False"
    (string? v) (string-repr v)
    ;; every exact carrier, a JS or Dart BigInt included, which `number?`
    ;; misses
    ((get exact 'integer?) v) ((get exact 'format) v)
    (number? v) (str v)
    (vector? v) (seq-repr "[" "]" v)
    (map? v)
    (cond
      (contains? v :py/float) (float-repr (:py/float v))
      (contains? v :py/tuple) (let [xs (:py/tuple v)]
                                (if (= 1 (count xs))
                                  (str "(" (repr (first xs)) ",)")
                                  (seq-repr "(" ")" xs)))
      (contains? v :py/dict) (str "{"
                                  (str/join ", "
                                            (map (fn [[k x]]
                                                   (str (repr k) ": " (repr x)))
                                                 (:py/dict v)))
                                  "}")
      (contains? v :py/range) (let [[a b s] (:py/range v)]
                                (if (= 1 s)
                                  (str "range(" a ", " b ")")
                                  (str "range(" a ", " b ", " s ")")))
      (contains? v :py/set) (if (empty? (:py/set v))
                              "set()"
                              (seq-repr "{" "}" (:py/set v)))
      (contains? v :py/instance) (str "<" (:py/instance v) " object>")
      (contains? v :py/class) (str "<class '" (:py/class v) "'>")
      (contains? v :py/function) (str "<function " (:py/function v) ">")
      :else (pr-str v))
    (= :py/method v) "<bound method>"
    :else (pr-str v)))


(defn py-str
  "Python `str` of one snapshot value: a string prints bare."
  [v]
  (if (string? v) v (repr v)))


(defn line
  "One `print` call's text: its arguments' `str`, space separated."
  [values]
  (str/join " " (map py-str values)))


(defn output
  "A program value's printed lines as text, exception kept as data."
  [{:py/keys [out exception]}]
  {:py/out (mapv line out), :py/exception exception})

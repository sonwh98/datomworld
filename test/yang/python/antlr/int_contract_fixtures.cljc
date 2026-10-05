(ns yang.python.antlr.int-contract-fixtures
  "The frozen exact-integer contract of Python phase C3 (slice S0 of
   docs/design/yang.antlr.md 8.5.4), loaded on the JVM, Node, and Dart.

   test/resources/yang/python/int-contract-v1.txt pins, for a fixed list
   of values around 2^53, 2^63 and 2^64 (and a few far ones): CPython
   3.9.6's `hash`, `str` and hex text, the guest dict-key text, the
   canonical CBOR bytes, and the `integer` module's outcomes under two
   named profiles, plus boundary operation rows. A one-off CPython script
   outside the repository wrote it; `render` computes the same text on
   this host from the integer module and Jing, so the file is a target
   later slices reproduce, never a copy of what they produce.

   Value names are a small expression language, `[-]B[^K][(+|-)D]` with
   ordinary precedence, so `-2^63-1` is -(2^63) - 1; `f:2^K` is the
   binary64 2^K. Nothing here writes a file."
  #?@(:cljd [(:require [clojure.string :as str]
                       [dao.jing :as jing]
                       [dao.jing.cbor :as cbor]
                       [dao.jing.cbor-fixtures :as cfx]
                       [yin.vm.integer :as integer])
             (:import ["dart:core" BigInt])]
      :default [(:require [clojure.string :as str]
                          [dao.jing :as jing]
                          [dao.jing.cbor :as cbor]
                          [dao.jing.cbor-fixtures :as cfx]
                          [yin.vm.integer :as integer])]))


(def path
  "The fixture, relative to the repository root."
  "test/resources/yang/python/int-contract-v1.txt")


(def generator
  "The interpreter that computed the CPython columns."
  "CPython 3.9.6")


(def profiles
  "`[name max-bits max-digits]` of the two named limit profiles: `wide`,
   the C3 contract profile (the parity runners install a wider 100000
   bits), and `small`, as the S3a limit tests run."
  [["wide" 4096 4300]
   ["small" 60 5]])


(def int-names
  "The integer values, boundary neighbors included."
  ["0" "1" "-1" "2^31-1" "2^31" "2^31+1" "2^32"
   "2^53-1" "2^53" "2^53+1" "-2^53+1" "-2^53" "-2^53-1"
   "2^62" "2^63-1" "2^63" "2^63+1" "-2^63+1" "-2^63" "-2^63-1"
   "2^64-1" "2^64" "2^64+1" "-2^64+1" "-2^64" "-2^64-1"
   "2^100" "10^30" "2^1074" "2^1075" "2^2000"])


(def float-names
  "The integer-valued binary64 values."
  ["f:2^53" "f:2^63"])


(def int-columns ["bits" "dec" "hex" "hash" "key" "cbor" "wide" "small"])


(def float-columns ["dec" "hash" "key" "cbor" "is-int" "eq-int"])


(def outcome-ops
  "The per-value operations each profile column records, in order."
  ["normalize" "inc" "dec" "square" "format" "format16" "shl"])


(def ops
  "`[profile op & args]` of the boundary operation rows."
  [["wide" "shift-left" "1" "4095"]
   ["wide" "shift-left" "1" "4096"]
   ["wide" "shift-left" "-1" "4095"]
   ["wide" "shift-left" "-1" "4096"]
   ["wide" "mul" "2^2048-1" "2^2048-1"]
   ["wide" "mul" "2^2048" "2^2048-1"]
   ["wide" "mul" "2^2048" "2^2048"]
   ["wide" "mul" "2^4095" "-2"]
   ["wide" "add" "2^4095" "2^4095-1"]
   ["wide" "add" "2^4096-1" "1"]
   ["wide" "sub" "-2^4096+1" "1"]
   ["wide" "pow" "2" "4095"]
   ["wide" "pow" "2" "4096"]
   ["wide" "format" "2^4096-1"]
   ["wide" "format16" "2^4096-1"]
   ["wide" "parse" "10^1233"]
   ["wide" "parse" "10^1234"]
   ["wide" "parse" "10^4299"]
   ["wide" "parse" "10^4300"]
   ["wide" "parse16" "2^4096-1"]
   ["wide" "parse16" "2^4096"]
   ["small" "shift-left" "1" "59"]
   ["small" "shift-left" "1" "60"]
   ["small" "shift-left" "-1" "59"]
   ["small" "shift-left" "-1" "60"]
   ["small" "mul" "2^30-1" "2^30-1"]
   ["small" "mul" "2^30" "2^30-1"]
   ["small" "mul" "2^30" "2^30"]
   ["small" "add" "2^59" "2^59-1"]
   ["small" "add" "2^60-1" "1"]
   ["small" "sub" "-2^60+1" "1"]
   ["small" "pow" "2" "59"]
   ["small" "pow" "2" "60"]
   ["small" "format" "99999"]
   ["small" "format" "100000"]
   ["small" "format" "-99999"]
   ["small" "format" "-100000"]
   ["small" "format16" "2^60-1"]
   ["small" "parse" "99999"]
   ["small" "parse" "100000"]
   ["small" "parse16" "2^60-1"]
   ["small" "parse16" "2^60"]])


;; =============================================================================
;; Values
;; =============================================================================

(defn module
  "The `integer` exports under `max-bits` and `max-digits`."
  [max-bits max-digits]
  (integer/integer-module {::integer/max-bits max-bits,
                           ::integer/max-digits max-digits}))


(def ^:private build
  "Limits wide enough to build every value and operand named here."
  (module 100000 100000))


(defn call
  "Export `op` of module `m` applied to `args`."
  [m op & args]
  (apply (get m op) args))


(defn max-bits
  "The bit limit of the named profile."
  [profile]
  (some (fn [[p b]] (when (= profile p) b)) profiles))


(def ^:private profile-modules
  (into {} (map (fn [[p b d]] [p (module b d)])) profiles))


(defn profile-module
  "The `integer` exports under the named profile."
  [profile]
  (get profile-modules profile))


(defn parse-name
  "`[negative? base exponent op addend]` of value name `s`, the numbers
   as decimal text; `exponent`, `op` and `addend` may be nil."
  [s]
  (let [[_ sign base exponent op addend]
        (re-matches #"(-?)(\d+)(?:\^(\d+))?(?:([+-])(\d+))?" s)]
    (when-not base
      (throw (ex-info "not a value name" {:name s})))
    [(= "-" sign) base exponent op addend]))


(defn value
  "The exact integer that name `s` denotes, in its canonical carrier."
  [s]
  (let [[negative? base exponent op addend] (parse-name s)
        b (call build 'parse base)
        n (if exponent (call build 'pow b (call build 'parse exponent)) b)
        n (if negative? (call build 'neg n) n)]
    (case op
      "+" (call build 'add n (call build 'parse addend))
      "-" (call build 'sub n (call build 'parse addend))
      n)))


(defn float-exponent
  "K of float name `f:2^K`, as decimal text."
  [s]
  (let [[negative? base exponent op] (parse-name (subs s 2))]
    (when-not (and (str/starts-with? s "f:") (not negative?) (= "2" base)
                   exponent (nil? op))
      (throw (ex-info "not a float name" {:name s})))
    exponent))


(defn float-value
  "The host double that float name `s` denotes, built by exact doubling."
  [s]
  (reduce * 0.5 (repeat (inc (call build 'parse (float-exponent s))) 2)))


(defn float->integer
  "The exact integer of an integral host double, in its canonical
   carrier: the host's own conversion, normalized by the module. On the
   JVM not `bigint` of the double, which reads its shortest decimal text
   and answers 9223372036854776000 for 2^63."
  [x]
  (call build 'normalize
        #?(:cljd (BigInt.from x)
           :clj (bigint (java.math.BigDecimal. (double x)))
           :cljs (js/BigInt x))))


;; =============================================================================
;; Columns
;; =============================================================================

(defn bits
  [n]
  (call build 'bit-length n))


(defn dec-text
  [n]
  (call build 'format n))


(defn hex-text
  [n]
  (call build 'format n 16))


(def ^:private modulus
  "P = 2^61 - 1."
  (call build 'sub (call build 'shift-left 1 61) 1))


(defn hash-text
  "Ruling 7's guest hash of integer `n` as decimal text:
   sign(n) * (|n| mod P), with -1 answered as -2."
  [n]
  (let [negative? (neg? (call build 'compare n 0))
        r (second (call build 'floor-div-mod
                        (if negative? (call build 'neg n) n)
                        modulus))
        h (if negative? (call build 'neg r) r)]
    (dec-text (if (zero? (call build 'compare h -1)) -2 h))))


(defn cbor-hex
  "Lowercase hex of the canonical Jing bytes of `v`."
  [v]
  (cfx/bytes->hex (jing/canonical-bytes v)))


(defn outcome
  "A module result's class: the limit reason's name, else \"value\"."
  [r]
  (if (keyword? r) (name r) "value"))


(defn shl-max
  "The largest count k with `(shift-left n k)` a value under `profile`:
   \"any\" for zero, \"none\" when n alone breaches. Checked against the
   module at k and k + 1, and \"inconsistent\" if the module disagrees."
  [profile n]
  (let [m (profile-module profile)
        b (bits n)]
    (cond (zero? (call m 'compare n 0)) "any"
          (> b (max-bits profile)) "none"
          :else (let [k (- (max-bits profile) b)]
                  (if (and (= "value" (outcome (call m 'shift-left n k)))
                           (= "bit-limit"
                              (outcome (call m 'shift-left n (inc k)))))
                    (str k)
                    "inconsistent")))))


(defn outcomes
  "The `outcome-ops` of integer `n` under `profile`, as `op=class`."
  [profile n]
  (let [m (profile-module profile)
        results {"normalize" (outcome (call m 'normalize n)),
                 "inc" (outcome (call m 'add n 1)),
                 "dec" (outcome (call m 'sub n 1)),
                 "square" (outcome (call m 'mul n n)),
                 "format" (outcome (call m 'format n)),
                 "format16" (outcome (call m 'format n 16)),
                 "shl" (shl-max profile n)}]
    (str/join " " (map #(str % "=" (get results %)) outcome-ops))))


(defn op-result
  "The module's result for boundary row `[profile op & args]`."
  [[profile op & args]]
  (let [m (profile-module profile)
        [a b] args]
    (case op
      ("shift-left" "pow") (call m (symbol op) (value a)
                                 (call build 'parse b))
      ("mul" "add" "sub") (call m (symbol op) (value a) (value b))
      "format" (call m 'format (value a))
      "format16" (call m 'format (value a) 16)
      "parse" (call m 'parse (dec-text (value a)))
      "parse16" (call m 'parse (hex-text (value a)) 16))))


(defn int-rows
  "`[column text]` of integer name `s`, in file order."
  [s]
  (let [n (value s)]
    [["bits" (str (bits n))]
     ["dec" (dec-text n)]
     ["hex" (hex-text n)]
     ["hash" (hash-text n)]
     ["key" (str (hex-text n) " 1")]
     ["cbor" (cbor-hex n)]
     ["wide" (outcomes "wide" n)]
     ["small" (outcomes "small" n)]]))


(defn float-rows
  "`[column text]` of float name `s`, in file order."
  [s]
  (let [x (float-value s)
        n (float->integer x)
        content (cbor/float64 x)]
    [["dec" (dec-text n)]
     ["hash" (hash-text n)]
     ["key" (str (hex-text n) " 1")]
     ["cbor" (cbor-hex content)]
     ["is-int" (str (cbor/content= content n))]
     ["eq-int" (str (zero? (call build 'compare n
                                 (value (subs s 2)))))]]))


(defn render
  "The fixture file's text, computed on this host."
  []
  (str (str/join "\n"
                 (concat ["int-contract-v1"
                          (str "generator " generator)]
                         (map (fn [[p b d]]
                                (str "profile " p " max-bits " b
                                     " max-digits " d))
                              profiles)
                         (for [s int-names, [c t] (int-rows s)]
                           (str s " " c " " t))
                         (for [s float-names, [c t] (float-rows s)]
                           (str s " " c " " t))
                         (map (fn [row]
                                (str "op " (str/join " " row) " "
                                     (outcome (op-result row))))
                              ops)))
       "\n"))


;; =============================================================================
;; Reading
;; =============================================================================

(defn read-text
  "The fixture file's text."
  []
  (cfx/read-path path))


(defn read-file
  "The fixture as `{:generator g, :profiles [line ...],
   :rows [[name column text] ...], :ops [[profile op & args outcome] ...]}`.
   Blank lines are ignored (Dart keeps a trailing one)."
  []
  (let [[magic gen & lines] (remove str/blank? (str/split-lines (read-text)))
        tagged (group-by #(first (str/split % #" ")) lines)]
    (when-not (= "int-contract-v1" magic)
      (throw (ex-info "not an int-contract-v1 fixture" {:path path})))
    {:generator (subs gen (count "generator ")),
     :profiles (vec (get tagged "profile")),
     ;; no `for` here: ClojureDart's chunked `for` steps past the end of
     ;; a 300-line seq and hands the body a nil line
     :rows (into []
                 (keep (fn [l]
                         (let [[s c & more] (str/split l #" ")]
                           (when-not (#{"profile" "op"} s)
                             [s c (str/join " " more)]))))
                 lines),
     :ops (mapv #(vec (rest (str/split % #" "))) (get tagged "op"))}))


(defn column
  "The text of `column` of value `s` in fixture `f`, or nil."
  [f s column]
  (some (fn [[s' c t]] (when (and (= s s') (= c column)) t)) (:rows f)))


(defn parse-outcomes
  "A profile column's text as `{op class}`."
  [text]
  (into {}
        (map #(let [[op c] (str/split % #"=")] [op c]))
        (str/split text #" ")))

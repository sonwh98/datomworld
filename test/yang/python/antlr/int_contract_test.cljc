(ns yang.python.antlr.int-contract-test
  "C3 slice S0: the exact-integer contracts frozen in
   test/resources/yang/python/int-contract-v1.txt hold on this host.

   Every column is recomputed here and must equal the file: the text as a
   whole, each row on its own, the module's outcomes under the two named
   profiles, and, through the parserless prelude runners on all four VMs,
   the guest's text, hash, key and bytes of each value, the `is` rows of
   the integer-valued floats, and the guest exception each limit reason
   becomes. The CPython columns are data; on the JVM python3 recomputes
   them when it is exactly 3.9.6, and the test says loudly when it skips."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    #?@(:cljd [] :clj [[clojure.java.shell :as shell]])
    [dao.test-slow :as slow]
    [yang.python.antlr.int-contract-fixtures :as f]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.integer :as integer]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]
    [yin.vm.ucf.handoff :as handoff]
    [yin.vm.ucf.lift-support :as s]))


(def ^:private fixture (delay (f/read-file)))


;; =============================================================================
;; The file
;; =============================================================================

(deftest the-file-holds-exactly-the-rendered-text
  (is (= (f/render) (f/read-text))))


(deftest every-value-has-every-column
  (let [{:keys [generator profiles rows ops]} @fixture
        present (frequencies (map (fn [[s c]] [s c]) rows))]
    (is (= f/generator generator))
    (is (= (map (fn [[p b d]]
                  (str "profile " p " max-bits " b " max-digits " d))
                f/profiles)
           profiles))
    (testing "each integer row has each integer column exactly once, each
              float row each float column, and nothing else is pinned"
      (is (= (merge (zipmap (for [s f/int-names, c f/int-columns] [s c])
                            (repeat 1))
                    (zipmap (for [s f/float-names, c f/float-columns] [s c])
                            (repeat 1)))
             present)))
    (testing "the neighbors n - 1, n, n + 1 of 2^53, 2^63 and 2^64 and of
              their negations are all in the list"
      (doseq [k ["53" "63" "64"]
              s ["2^K-1" "2^K" "2^K+1" "-2^K+1" "-2^K" "-2^K-1"]]
        (is (some #{(str/replace s "K" k)} f/int-names) (str/replace s "K" k))))
    (testing "every profile column names every operation"
      (doseq [s f/int-names, p ["wide" "small"]]
        (is (= (set f/outcome-ops)
               (set (keys (f/parse-outcomes (f/column @fixture s p)))))
            (str s " " p))))
    (testing "the boundary rows are the declared ones, each with an outcome"
      (is (= f/ops (map butlast ops)))
      (is (every? #{"value" "bit-limit" "digit-limit"} (map last ops))))))


;; =============================================================================
;; Each row, recomputed on this host
;; =============================================================================

(deftest integer-rows-recompute-on-this-host
  (doseq [s f/int-names
          [c text] (f/int-rows s)]
    (is (= (f/column @fixture s c) text) (str s " " c))))


(deftest float-rows-recompute-on-this-host
  (doseq [s f/float-names
          [c text] (f/float-rows s)]
    (is (= (f/column @fixture s c) text) (str s " " c))))


(deftest text-round-trips-through-the-module
  (testing "parse of the frozen decimal and hex text gives back the value:
            its canonical bytes are the frozen bytes"
    (doseq [s f/int-names]
      (let [cbor (f/column @fixture s "cbor")]
        (is (= cbor (f/cbor-hex (f/call (f/module 100000 100000) 'parse
                                        (f/column @fixture s "dec"))))
            (str s " dec"))
        (is (= cbor (f/cbor-hex (f/call (f/module 100000 100000) 'parse
                                        (f/column @fixture s "hex") 16)))
            (str s " hex"))))))


(deftest canonical-widths-at-the-boundaries
  (testing "the codec's width boundaries, read off the frozen bytes: the
            last 64-bit head, the first tag 2 and tag 3, and tag 3 over
            -1 - n rather than |n|"
    (doseq [[s cbor] [["2^53" "1b0020000000000000"]
                      ["-2^53" "3b001fffffffffffff"]
                      ["2^63" "1b8000000000000000"]
                      ["-2^63-1" "3b8000000000000000"]
                      ["2^64-1" "1bffffffffffffffff"]
                      ["2^64" "c249010000000000000000"]
                      ["-2^64" "3bffffffffffffffff"]
                      ["-2^64-1" "c349010000000000000000"]]]
      (is (= cbor (f/column @fixture s "cbor")) s))))


(deftest module-outcomes-under-the-named-profiles
  (testing "each per-value outcome, and the largest shift that fits with
            the first that does not"
    (doseq [s f/int-names
            p ["wide" "small"]
            :let [frozen (f/parse-outcomes (f/column @fixture s p))
                  m (f/profile-module p)
                  n (f/value s)
                  k (get frozen "shl")]]
      (is (= frozen (f/parse-outcomes (f/outcomes p n))) (str s " " p))
      (when (re-matches #"\d+" k)
        (let [k (f/call m 'parse k)]
          (is (= "value" (f/outcome (f/call m 'shift-left n k))) s)
          (is (= "bit-limit" (f/outcome (f/call m 'shift-left n (inc k))))
              s)))))
  (testing "each boundary row"
    (doseq [row (:ops @fixture)]
      (is (= (last row) (f/outcome (f/op-result (vec (butlast row)))))
          (str/join " " row)))))


;; =============================================================================
;; The guest, on all four VMs
;; =============================================================================

(defn- opts-under
  "The composition over the real modules, `integer` under `limits`."
  [limits]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                (data/register-data-module {::data/max-items 1048576})
                (integer/register-integer-module limits)
                prelude/admit)})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- runners-under
  "Each VM's runner over the composition `opts`."
  [opts]
  {:ast-walker (fn [ast] (vm/value (vm/eval (tu/create-vm opts) ast))),
   :semantic (fn [ast]
               (vm/value (vm/run (load-semantic-ast (semantic/create-vm opts)
                                                    (vm/ast->datoms ast))))),
   :stack (fn [ast]
            (vm/value
              (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                     (assoc opts
                                            :contract vm/stack-contract))))),
   :register (fn [ast]
               (vm/value
                 (vm/run
                   (rvm/create-vm
                     (:image (rc/adapt (vm/ast->datoms ast)))
                     (assoc opts :contract vm/register-contract)))))})


(defn- profile-runners
  [profile]
  (let [[_ b d] (some #(when (= profile (first %)) %) f/profiles)]
    (runners-under (opts-under {::integer/max-bits b,
                                ::integer/max-digits d}))))


(defn- run-everywhere
  "The value of `form` (prelude notation) after the prelude, per VM."
  [runners form]
  (let [ast (u/mark-tails (u/then prelude/uast (u/sexp->uast form)))]
    (into {}
          (map (fn [[k run]]
                 [k (try (run ast)
                         (catch #?(:cljd Object :clj Exception :cljs :default) e
                           [:thrown (ex-message e)]))]))
          runners)))


(defn- conj-all
  [xs]
  (reduce (fn [acc x] (list 'py/vconj acc x)) [] xs))


(defn- int-form
  "Prelude notation building integer name `s` from small literals."
  [s]
  (let [[negative? base exponent op addend] (f/parse-name s)
        native #(f/call (f/module 64 64) 'parse %)
        n (if exponent
            (list 'integer/pow (native base) (native exponent))
            (native base))
        n (if negative? (list 'integer/neg n) n)]
    (case op
      "+" (list 'integer/add n (native addend))
      "-" (list 'integer/sub n (native addend))
      n)))


(defn- float-form
  "Prelude notation building the Python float of name `f:2^K` by exact
   doubling from 1, in factors of at most 2^32."
  [s]
  (let [k (f/call (f/module 64 64) 'parse (f/float-exponent s))
        factors (concat (repeat (quot k 32) 4294967296)
                        [(reduce * 1 (repeat (rem k 32) 2))])]
    (list 'py/float (list* '* '(data/float-value 1) factors))))


(defn- key-of
  [text]
  (into [:py.numeric/finite] (str/split text #" ")))


(defn- guest-values-on-every-vm
  []
  (testing "the guest's decimal and hex text, hash, dict key and bytes of
            each integer; the hash, key and bytes of each integer-valued
            float, which `==` its integer and `is` not it"
    (let [form (conj-all
                 (concat
                   (for [s f/int-names]
                     (list 'let ['n (int-form s)]
                           (conj-all ['(integer/format n)
                                      '(integer/format n 16)
                                      '(integer/format (py/hash n))
                                      '(py/key n)
                                      'n])))
                   (for [s f/float-names]
                     (list 'let ['x (float-form s)
                                 'n (int-form (subs s 2))]
                           (conj-all ['(integer/format (py/hash x))
                                      '(py/key x)
                                      'x
                                      '(py/is x n)
                                      '(py/eq x n)
                                      (list 'py/is 'n (int-form (subs s 2)))
                                      '(py/is x x)])))))
          col #(f/column @fixture %1 %2)]
      (doseq [[k result] (run-everywhere (profile-runners "wide") form)]
        (testing (str k)
          (is (and (vector? result) (not= :thrown (first result)))
              (pr-str result))
          (when (vector? result)
            (doseq [[s [dec-text hex-text hash-text key n]]
                    (map vector f/int-names result)]
              (is (= [(col s "dec") (col s "hex") (col s "hash")
                      (key-of (col s "key")) (col s "cbor")]
                     [dec-text hex-text hash-text key (f/cbor-hex n)])
                  s))
            (doseq [[s [hash-text key x is-int eq-int is-self is-x]]
                    (map vector f/float-names
                         (drop (count f/int-names) result))]
              (is (= [(col s "hash") (key-of (col s "key")) (col s "cbor")
                      (col s "is-int") (col s "eq-int") true true]
                     [hash-text key (f/cbor-hex (:py/float x)) (str is-int)
                      (str eq-int) is-self is-x])
                  s))))))))


(deftest ^:slow guest-values-on-every-vm-test
  (slow/guard "guest-values-on-every-vm-test" guest-values-on-every-vm))


(def ^:private caught
  "Prelude notation: `[class-name & args]` of the guest exception
   `thunk` raises, else (the `else` arm) `:value`."
  '(fn [thunk]
     (py/try thunk
             (fn [e]
               (data/into (py/vconj []
                                   (get (cell/get (get (cell/get e) :class))
                                        :name))
                          (get (get (get (cell/get e) :attrs) "args") :items)))
             (fn [] :value))))


(defn- guest-outcome
  "What the guest sees of each module outcome (S3a, `py/int-result`)
   under a profile of `max-digits`: the message names the limit (S4)."
  [max-digits]
  {"value" :value,
   "bit-limit" ["MemoryError"],
   "digit-limit" ["ValueError"
                  {:py/str (str "Exceeds the limit (" max-digits
                                " digits) for integer string conversion")}]})


(defn- operand
  "Prelude notation for integer name `s`, parsed from hex: hex text is
   exempt from the digit limit, so only the bit limit can refuse it."
  [s]
  (list 'integer/parse (f/hex-text (f/value s)) 16))


(defn- value-calls
  "`[label call expected-outcome]` of each per-value outcome of integer
   name `s` under `profile`."
  [profile s]
  (let [n (operand s)
        frozen (f/parse-outcomes (f/column @fixture s profile))
        k (get frozen "shl")
        native #(f/call (f/module 64 64) 'parse %)]
    (concat
      (map (fn [[op call]] [(str s " " op) call (get frozen op)])
           [["normalize" (list 'integer/normalize n)]
            ["inc" (list 'integer/add n 1)]
            ["dec" (list 'integer/sub n 1)]
            ["square" (list 'integer/mul n n)]
            ["format" (list 'integer/format n)]
            ["format16" (list 'integer/format n 16)]])
      (if (= "any" k)
        [[(str s " shl any") (list 'integer/shift-left n
                                   (inc (f/max-bits profile)))
          "value"]]
        [[(str s " shl " k) (list 'integer/shift-left n (native k)) "value"]
         [(str s " shl " k "+1") (list 'integer/shift-left n
                                       (inc (native k)))
          "bit-limit"]]))))


(defn- op-call
  "`[label call expected-outcome]` of boundary row `[profile op & args
   outcome]`."
  [[_ op a b :as row]]
  (let [native #(f/call (f/module 64 64) 'parse %)]
    [(str/join " " row)
     (case op
       ("shift-left" "pow") (list (symbol "integer" op) (operand a) (native b))
       ("mul" "add" "sub") (list (symbol "integer" op) (operand a) (operand b))
       "format" (list 'integer/format (operand a))
       "format16" (list 'integer/format (operand a) 16)
       "parse" (list 'integer/parse (f/dec-text (f/value a)))
       "parse16" (list 'integer/parse (f/hex-text (f/value a)) 16))
     (last row)]))


(defn- guest-limit-reasons-on-every-vm
  []
  (testing "every frozen outcome, run as a guest call through
            py/int-result: a value stays a value, bit-limit is a
            MemoryError, digit-limit a ValueError, on every VM"
    (doseq [[p max-bits max-digits] f/profiles]
      (let [fits? (fn [s]
                    (<= (f/call (f/module 64 64) 'parse
                                (f/column @fixture s "bits"))
                        max-bits))
            calls (concat
                    (mapcat #(value-calls p %) (filter fits? f/int-names))
                    (map op-call (filter #(= p (first %)) (:ops @fixture))))
            form (list 'let ['caught caught]
                       (conj-all
                         (map (fn [[_ call]]
                                (list 'caught
                                      (list 'fn []
                                            (list 'py/int-result call))))
                              calls)))]
        (doseq [[k result] (run-everywhere (profile-runners p) form)]
          (testing (str p " " k)
            (is (and (vector? result) (= (count calls) (count result)))
                (pr-str result))
            (when (vector? result)
              (doseq [[[label _ expected] got] (map vector calls result)]
                (is (= ((guest-outcome max-digits) expected) got)
                    label)))))))))


(deftest ^:slow guest-limit-reasons-on-every-vm-test
  (slow/guard "guest-limit-reasons-on-every-vm-test"
              guest-limit-reasons-on-every-vm))


;; =============================================================================
;; Profile mismatch (C3 S7, PM1 and PM2)
;; =============================================================================

(defn- registry-under
  "The Python composition: cell, `data` with `data-limits` (nil: none),
   and `integer` installed by `register-integer` under `limits`."
  [data-limits register-integer limits]
  (-> (module/empty-registry)
      module/register-cell-module
      (cond-> (some? data-limits) (data/register-data-module data-limits)
              (nil? data-limits) data/register-data-module)
      (register-integer limits)))


(def ^:private wide-limits
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(def ^:private items {::data/max-items 1048576})


(defn- version-3-integer
  "The `integer` module as version 3 had it: no `float-digits`,
   `decimal->float` or `max-digits`."
  [registry limits]
  (module/register-host-module
    registry 'integer
    (dissoc (integer/integer-module limits)
            'float-digits 'decimal->float 'max-digits)
    integer/integer-profiles))


(defn- refusal-of
  [thunk]
  (try (thunk)
       :admitted
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (ex-data e))))


(deftest profile-mismatch-is-refused-by-name-test
  (testing "a version-3 integer module is refused naming exactly the three
            exports version 4 added"
    (is (= {:yang.python.antlr/refusal :yang.python.antlr/host-names,
            :yang.python.antlr/missing '[integer/decimal->float
                                         integer/float-digits
                                         integer/max-digits]}
           (refusal-of #(prelude/admit
                          (registry-under items version-3-integer
                                          wide-limits))))))
  (testing "a data module registered without limits is refused naming
            data/max-items"
    (is (= {:yang.python.antlr/refusal :yang.python.antlr/host-names,
            :yang.python.antlr/missing '[data/max-items]}
           (refusal-of #(prelude/admit
                          (registry-under nil integer/register-integer-module
                                          wide-limits))))))
  (testing "the full composition is admitted, unchanged"
    (let [r (registry-under items prelude/register-integer-module
                            wide-limits)]
      (is (identical? r (prelude/admit r)))))
  (testing "under 52 bits the profile's own registrar refuses first"
    (is (= :yang.python.antlr/max-bits
           (:yang.python.antlr/refusal
             (refusal-of #(prelude/admit
                            (registry-under items
                                            prelude/register-integer-module
                                            {::integer/max-bits 52,
                                             ::integer/max-digits 4300}))))))))


(deftest an-image-carries-no-profile-test
  (testing "a halted result holding 2^100, lifted under the wide
            composition with a version-1 header, resumes under `small`
            intact: the body names no limit, and under `small` the value
            meets the S0 `small` column"
    (let [wide (prelude/admit (registry-under items
                                              prelude/register-integer-module
                                              wide-limits))
          small (prelude/admit (registry-under items
                                               prelude/register-integer-module
                                               {::integer/max-bits 60,
                                                ::integer/max-digits 5}))
          m (vm/run (s/load-ast (s/new-machine {:modules wide})
                                (s/app (s/v 'integer/pow) (s/lit 2)
                                       (s/lit 100))))
          r (s/lift m (s/header 0 s/origin #{}))
          out (handoff/resume-task (s/new-machine {:modules small})
                                   (:bytes r) (fn [_] nil))
          resumed? (= :ok (:status out))]
      (is (= :ok (:status r)) (pr-str (dissoc r :bytes)))
      (is (= 1 (:yin.k/version (:body r))))
      (is (not-any? #{::integer/max-bits ::integer/max-digits
                      ::data/max-items}
                    (tree-seq coll? seq (:body r))))
      (is resumed? (pr-str (dissoc out :vm)))
      (when resumed?
        (let [n (vm/value (:vm out))]
          (is (= (f/column @fixture "2^100" "dec")
                 (f/call (f/module 128 64) 'format n)))
          (is (= (f/column @fixture "2^100" "small")
                 (f/outcomes "small" n))))))))


;; =============================================================================
;; CPython itself (JVM only)
;; =============================================================================

#?(:cljd nil
   :clj
   (defn- python-expr
     [s]
     (if (str/starts-with? s "f:")
       (str "float(" (str/replace (subs s 2) "^" "**") ")")
       (str/replace s "^" "**"))))


#?(:cljd nil
   :clj
   (deftest cpython-recomputes-the-pinned-columns
     (let [names (concat f/int-names f/float-names)
           program (str "import sys\n"
                        "print(sys.version.split()[0])\n"
                        "for x in [" (str/join ", " (map python-expr names))
                        "]:\n"
                        "    n = int(x)\n"
                        "    print(str(n), format(n, 'x'), hash(x))\n")
           {:keys [exit out err]}
           (try (shell/sh "python3" "-c" program)
                (catch java.io.IOException e {:exit -1, :err (ex-message e)}))
           [version & lines] (str/split-lines (or out ""))]
       (if (or (not= 0 exit) (not= f/generator (str "CPython " version)))
         (do (println "SKIP cpython-recomputes-the-pinned-columns: python3"
                      "is not CPython 3.9.6 here (exit" exit "version"
                      (pr-str version) (str/trim (or err "")) ");"
                      "the CPython pins were NOT rechecked")
             (is true))
         (do
           (is (= (count names) (count lines)))
           (doseq [[s line] (map vector names lines)]
             (let [[dec-text hex-text hash-text] (str/split line #" ")]
               (is (= (f/column @fixture s "dec") dec-text) s)
               (when (some #{s} f/int-names)
                 (is (= (f/column @fixture s "hex") hex-text) s))
               (is (= (f/column @fixture s "hash") hash-text) s)
               (is (= (str hex-text " 1") (f/column @fixture s "key"))
                   s))))))))

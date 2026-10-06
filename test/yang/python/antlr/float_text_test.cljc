(ns yang.python.antlr.float-text-test
  "Integer module version 4 float text kernels and the portable float
   renderer, against CPython 3.9.6 (float-text-v1.txt) on every host:
   `float-digits` is `repr(float)`'s shortest round-trip digits,
   `decimal->float` is `float(str)`'s correctly rounded reading, and
   `render/float-repr` is `repr(float)` itself. Floats are built from
   bits, never from a host float literal or a host float reader."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.jing.cbor :as cbor]
    [dao.test-slow :as slow]
    [yang.python.antlr.int-ops-fixtures :as fixtures]
    [yang.python.antlr.render :as render]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]
    [yin.vm.integer-v3-fixtures :as fx]))


(def ^:private m
  (integer/integer-module {::integer/max-bits 100000,
                           ::integer/max-digits 4300}))


(defn- call
  [op & args]
  (apply (get m op) args))


(defn- refusal
  [op & args]
  (try (apply call op args)
       nil
       (catch #?(:cljd cljd.core/ExceptionInfo :clj Exception :cljs :default) e
         (select-keys (ex-data e) [::integer/op ::integer/reason]))))


(def ^:private fixture
  (delay (fixtures/read-file fixtures/float-text-path)))


(defn- rows
  [op]
  (filterv #(= op (first %)) @fixture))


(defn- host-double
  "The host double of a tagged float `{:py/float carrier}`."
  [v]
  (data/float-value (:py/float v)))


(defn- bits
  [x]
  (fx/double->bits x))


;; =============================================================================
;; Test-side readers of CPython text, never a host float reader
;; =============================================================================

(defn- repr->digits
  "CPython repr text of a finite nonzero float as `[digits exp10]`, with
   |v| = d1.d2...dn x 10^exp10 and no leading or trailing zero."
  [text]
  (let [text (str/replace text #"^-" "")
        [mantissa e] (str/split text #"e")
        [a b] (str/split mantissa #"\.")
        all (str a b)
        lead (count (take-while #(= \0 %) all))
        digits (str/replace (subs all lead) #"0+$" "")]
    [digits
     (+ (- (count a) 1 lead)
        (if e (call 'parse (str/replace e #"^\+" "")) 0))]))


(def ^:private float-spaces
  "Code points `float(str)` strips, as CPython 3.9.6 measures them: ASCII
   9 to 13 and 32, and the non-ASCII Unicode spaces. 28 to 31 are not
   stripped, though `str.isspace` holds for them."
  (into #{9 10 11 12 13 32 133 160 5760 8232 8233 8239 8287 12288}
        (range 8192 8203)))


(defn- code-unit
  [s i]
  #?(:cljd (.codeUnitAt ^String s i)
     :clj (int (.charAt ^String s (int i)))
     :cljs (.charCodeAt s i)))


(defn- strip-spaces
  [s]
  (let [n (count s)
        space? #(contains? float-spaces (code-unit s %))
        start (or (first (remove space? (range n))) n)
        end (or (first (remove #(space? (dec %)) (range n start -1))) start)]
    (subs s start end)))


(def ^:private decimal-syntax
  "Sign, integer digits, fraction digits and exponent; underscores only
   between digits."
  (re-pattern (str "^([+-]?)([0-9](?:_?[0-9])*)?(?:\\.([0-9](?:_?[0-9])*)?)?"
                   "(?:[eE]([+-]?[0-9](?:_?[0-9])*))?$")))


(defn- split-float-text
  "`float(str)` text as `{:negative? n, :special :inf|:nan}` or
   `{:negative? n, :digits d, :exp10 e}`, or nil when CPython refuses
   it. `e` is an exact integer of any size."
  [s]
  (let [t (strip-spaces s)
        lower (str/lower-case t)]
    (if-let [[_ sign word] (re-find #"^([+-]?)(inf|infinity|nan)$" lower)]
      {:negative? (= "-" sign), :special (if (= "nan" word) :nan :inf)}
      (when-let [[_ sign int-part frac exp] (re-find decimal-syntax t)]
        (when (or int-part frac)
          (let [frac (str/replace (or frac "") "_" "")
                e (if exp
                    (call 'parse (str/replace (str/replace exp "_" "")
                                              #"^\+" ""))
                    0)]
            {:negative? (= "-" sign),
             :digits (str (str/replace (or int-part "") "_" "") frac),
             :exp10 (call 'sub e (count frac))}))))))


(defn- read-float
  "Host double of a split decimal through `decimal->float`, signed."
  [{:keys [negative? digits exp10]}]
  (let [x (call 'decimal->float digits exp10)]
    (if negative? (* -1.0 x) x)))


;; =============================================================================
;; Fast pins, every host
;; =============================================================================

(deftest module-version-4-test
  (is (= 4 integer/module-version))
  (is (= 4300 (call 'max-digits)))
  (is (= 64 ((get (integer/integer-module {::integer/max-bits 64,
                                           ::integer/max-digits 64})
                  'max-digits))))
  (is (= {::integer/op 'max-digits, ::integer/reason :arity}
         (refusal 'max-digits 1))))


(deftest float-digits-pins-test
  (doseq [[hex expected]
          [["0000000000000001" ["5" -324]]
           ["000fffffffffffff" ["2225073858507201" -308]]
           ["0010000000000000" ["22250738585072014" -308]]
           ["7fefffffffffffff" ["17976931348623157" 308]]
           ["4480f0cf064dd592" ["1" 22]]
           ["44b52d02c7e14af6" ["1" 23]]
           ["3fb999999999999a" ["1" -1]]
           ["3fd3333333333333" ["3" -1]]
           ["3fd5555555555555" ["3333333333333333" -1]]
           ["4340000000000000" ["9007199254740992" 15]]
           ["4341c37937e08000" ["1" 16]]
           ["4341c37937e07fff" ["9999999999999998" 15]]
           ["3f1a36e2eb1c432d" ["1" -4]]
           ["3ee4f8b588e368f1" ["1" -5]]
           ["41678c29d0000000" ["123456785" 7]]
           ["be8421f5f40d8376" ["15" -7]]]]
    (is (= expected (call 'float-digits (fx/bits->double hex))) hex))
  (testing "power-of-two boundaries: the shortest digits are not nearest"
    ;; 2^-1017 and 2^89: the rounding interval below a power of two is
    ;; half the one above, so only the far 16-digit candidate reads back
    (is (= ["7120236347223045" -307]
           (call 'float-digits (fx/bits->double "0060000000000000"))))
    (is (= ["6189700196426902" 26]
           (call 'float-digits (fx/bits->double "4580000000000000")))))
  (testing "zero, NaN and infinity are the caller's to exclude"
    (doseq [hex ["0000000000000000" "8000000000000000" "7ff8000000000000"
                 "7ff0000000000000" "fff0000000000000"]]
      (is (= {::integer/op 'float-digits, ::integer/reason :wrong-type}
             (refusal 'float-digits (fx/bits->double hex)))
          hex))
    (is (= {::integer/op 'float-digits, ::integer/reason :wrong-type}
           (refusal 'float-digits "1")))))


(deftest decimal->float-pins-test
  (let [half-least (call 'format (call 'pow 5 1075))]
    (doseq [[digits exp10 expected]
            [["5" -324 "0000000000000001"]
             ["1" 23 "44b52d02c7e14af6"]
             ["1" 22 "4480f0cf064dd592"]
             ["1" -1 "3fb999999999999a"]
             ["3" -1 "3fd3333333333333"]
             ["9007199254740993" 0 "4340000000000000"]
             ["9007199254740995" 0 "4340000000000002"]
             ["17976931348623157" 292 "7fefffffffffffff"]
             ["17976931348623158" 292 "7fefffffffffffff"]
             ["1" 400 "7ff0000000000000"]
             ["1" -400 "0000000000000000"]
             ["0" 400 "0000000000000000"]
             ["000" 0 "0000000000000000"]
             ["0001500" -3 "3ff8000000000000"]
             ["24703282292062327" -340 "0000000000000000"]
             ["24703282292062328" -340 "0000000000000001"]
             ;; 2^-1075 exactly: a tie, to even, so zero; then past it
             [half-least -1075 "0000000000000000"]
             [(str half-least (apply str (repeat 300 "0"))) -1375
              "0000000000000000"]
             [(str half-least (apply str (repeat 300 "0")) "1") -1376
              "0000000000000001"]]]
      (is (= expected (bits (call 'decimal->float digits exp10)))
          (str (subs digits 0 (min 20 (count digits))) " " exp10))))
  (testing "2^1024 - 2^970 is the tie that rounds to infinity"
    (let [edge (call 'sub (call 'shift-left 1 1024) (call 'shift-left 1 970))]
      (is (= "7ff0000000000000"
             (bits (call 'decimal->float (call 'format edge) 0))))
      (is (= "7fefffffffffffff"
             (bits (call 'decimal->float (call 'format (call 'sub edge 1))
                         0))))))
  (testing "any exact exponent, never a limit reason"
    (let [huge (call 'pow 10 40)]
      (is (= "7ff0000000000000" (bits (call 'decimal->float "1" huge))))
      (is (= "0000000000000000"
             (bits (call 'decimal->float "1" (call 'neg huge)))))
      (is (= "0000000000000000" (bits (call 'decimal->float "0" huge))))
      (is (= "3ff0000000000000"
             (bits (call 'decimal->float (apply str "1" (repeat 5000 "0"))
                         -5000)))
          "text is not an integer: ::max-digits never applies")))
  (testing "refusals"
    (is (= {::integer/op 'decimal->float, ::integer/reason :syntax}
           (refusal 'decimal->float "" 0)))
    (is (= {::integer/op 'decimal->float, ::integer/reason :syntax}
           (refusal 'decimal->float "1.5" 0)))
    (is (= {::integer/op 'decimal->float, ::integer/reason :syntax}
           (refusal 'decimal->float "-1" 0)))
    (is (= {::integer/op 'decimal->float, ::integer/reason :wrong-type}
           (refusal 'decimal->float 1 0)))
    (is (= {::integer/op 'decimal->float, ::integer/reason :wrong-type}
           (refusal 'decimal->float "1"
                    (fx/bits->double "3fe0000000000000"))))))


(deftest float-repr-pins-test
  (doseq [[hex expected]
          [["4341c37937e08000" "1e+16"]
           ["4341c37937e07fff" "9999999999999998.0"]
           ["3f1a36e2eb1c432d" "0.0001"]
           ["3ee4f8b588e368f1" "1e-05"]
           ["4480f0cf064dd592" "1e+22"]
           ["7e41eb2d66005835" "1.5e+300"]
           ["0000000000000001" "5e-324"]
           ["7fefffffffffffff" "1.7976931348623157e+308"]
           ["3fb999999999999a" "0.1"]
           ["be8421f5f40d8376" "-1.5e-07"]
           ["41678c29d0000000" "12345678.5"]
           ["42dc12218377de66" "123456789012345.6"]
           ["4340000000000000" "9007199254740992.0"]
           ["444b1ae4d6e2ef50" "1e+21"]
           ["3e7ad7f29abcaf48" "1e-07"]
           ["0000000000000000" "0.0"]
           ["8000000000000000" "-0.0"]
           ["7ff0000000000000" "inf"]
           ["fff0000000000000" "-inf"]
           ["7ff8000000000000" "nan"]]]
    (is (= expected (render/float-repr (cbor/float64-from-bits hex))) hex)
    (is (= expected (render/repr {:py/float (cbor/float64-from-bits hex)}))
        hex)))


;; =============================================================================
;; The CPython fixture, in batches of at most 400 rows
;; =============================================================================

(deftest float-text-fixture-shape-test
  (let [rows @fixture]
    (is (<= 9000 (count (filter #(= "repr_float" (first %)) rows))))
    (is (<= 700 (count (filter #(= "float_str" (first %)) rows))))
    (is (= #{"ValueError"}
           (into #{} (keep #(:error (nth % 3))) rows)))))


(defn- finite-nonzero?
  [x]
  ;; `==`, not `=`: `=` answers true for one boxed NaN on the JVM
  (and (== x x) (not (zero? x)) (not= x ##Inf) (not= x ##-Inf)))


(defn- float-text-fixture
  []
  (testing "repr_float: float-digits and the renderer"
    (doseq [[i batch] (map-indexed vector
                                   (partition-all 400 (rows "repr_float")))]
      (testing (str "batch " i)
        (is (= []
               (into []
                     (keep (fn [[_ v _ expected]]
                             (let [x (host-double v)
                                   text (render/float-repr (:py/float v))]
                               (cond
                                 (not= expected text) [expected text]
                                 (and (finite-nonzero? x)
                                      (not= (repr->digits expected)
                                            (call 'float-digits x)))
                                 [expected (call 'float-digits x)]))))
                     batch))))))
  (testing "float_str: decimal->float, and the syntax the fixture refuses"
    (doseq [[i batch] (map-indexed vector
                                   (partition-all 400 (rows "float_str")))]
      (testing (str "batch " i)
        (is (= []
               (into []
                     (keep (fn [[_ s _ expected]]
                             (let [split (split-float-text s)]
                               (cond
                                 (:error expected)
                                 (when split [s :accepted split])
                                 (nil? split) [s :refused]
                                 (:special split) nil
                                 :else
                                 (let [want (bits (host-double expected))
                                       got (bits (read-float split))]
                                   (when-not (= want got)
                                     [s want got]))))))
                     batch)))))))


(deftest ^:slow float-text-fixture-test
  (slow/guard "float-text-fixture-test" float-text-fixture))

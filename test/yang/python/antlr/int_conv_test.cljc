(ns yang.python.antlr.int-conv-test
  "C3 slice S4: guest conversions, the 11 builtins, the digit-limit
   checks of str, repr, print and int(str), and the deferred numeric
   repairs (ledger a-d, f-i), on all four VMs. The corpus tests read the
   CPython 3.9.6 rows of int-conv-v1 and float-text-v1; every row not
   measured on CPython 3.9.6 is a hand pin and says so."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [clojure.walk :as walk]
    [dao.test-slow :as slow]
    [yang.python.antlr.int-ops-fixtures :as fixtures]
    [yang.python.antlr.int-ops-test :as ops]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]
    [yin.vm.integer-v3-fixtures :as fx]))


(def ^:private exact
  (integer/integer-module
    {::integer/max-bits 100000, ::integer/max-digits 4300}))


(defn- limit-message
  [n]
  (str "Exceeds the limit (" n " digits) for integer string conversion"))


(def ^:private args-of
  "The guest exception's args, as a vector."
  '(fn [thunk]
     (py/try thunk
             (fn [e] (get (get (get (cell/get e) :attrs) "args") :items))
             (fn [] :py/None))))


(def ^:private nan
  "A NaN built by inf - inf: a ##NaN literal row is not equal to itself
   on JS, which breaks content addressing of the program."
  '(fn [] (- (data/float-value ##Inf) (data/float-value ##Inf))))


(defn- check-cases*
  "ops/check-cases with `args-of` and `nan` bound."
  [rs cases]
  (ops/check-cases rs
                   (mapv (fn [[form expected]]
                           [(list 'let ['args-of args-of 'nan nan] form)
                            expected])
                         cases)))


(deftest scalar-conversions-test
  (ops/check-cases
    ops/runners
    [['(py/int-conv (py/str " +1_000 ") :py/missing) 1000]
     ['(py/int-conv (py/str "0x_fF") 0) 255]
     ['(py/int-conv (py/str "0_0") 0) 0]
     ['(caught (fn [] (py/int-conv (py/str "010") 0))) "ValueError"]
     ['(caught (fn [] (py/int-conv (py/str "1__2") 10))) "ValueError"]
     ['(py/int-conv (py/float -2.5) :py/missing) -2]
     ['(py/int-conv true :py/missing) 1]
     ['(caught (fn [] (py/int-conv :py/None :py/missing))) "TypeError"]
     ['(caught (fn [] (py/int-conv 1 10))) "TypeError"]
     ['(caught (fn [] (py/int-conv (py/str "1") 37))) "ValueError"]
     ['(py/float-conv (py/str "-.5_5")) {:py/float -0.55}]
     ['(py/float-conv (py/str "1_0e-2")) {:py/float 0.1}]
     ['(caught (fn [] (py/float-conv (py/str "1_.0")))) "ValueError"]
     ['(py/str-conv (py/float 0.00001) false) {:py/str "1e-05"}]
     ['(py/str-conv (py/float 12345678.5) true) {:py/str "12345678.5"}]
     ['(py/str-conv (py/float (data/float-value 0)) true) {:py/str "0.0"}]
     ['(py/str-conv (py/float (* (data/float-value -1) (data/float-value 0)))
                    true)
      {:py/str "-0.0"}]
     ['(py/str-conv true false) {:py/str "True"}]
     ['(py/str-conv :py/None false) {:py/str "None"}]
     ['(py/str-conv (py/str "a'b") true) {:py/str "\"a'b\""}]
     ['(py/str-conv (py/str "\n\t\\") true) {:py/str "'\\n\\t\\\\'"}]
     ['(caught (fn [] (py/str-conv (py/list []) false)))
      "NotImplementedError"]
     ['(py/abs-conv (py/float (* (data/float-value -1) (data/float-value 0))))
      {:py/float (data/float-value 0)}]
     ['(py/abs-conv true) 1]
     ['(py/radix-conv -255 16 "0x") {:py/str "-0xff"}]
     ['(py/radix-conv true 2 "0b") {:py/str "0b1"}]
     ['(py/radix-conv 8 8 "0o") {:py/str "0o10"}]
     ['(py/round-conv (py/float 2.5) :py/None) 2]
     ['(py/round-conv (py/float 3.5) :py/None) 4]
     ['(py/round-conv (py/float -2.5) :py/None) -2]
     ['(py/round-conv 250 -2) 200]
     ['(py/round-conv 350 -2) 400]
     ['(py/round-conv -350 -2) -400]
     ['(py/round-conv 123 2) 123]
     ['(caught (fn [] (py/round-conv (py/float 2.5) 1)))
      "NotImplementedError"]
     ['(py/truthy (py/list [])) false]]))


(deftest round-argument-order-test
  ;; CPython 3.9.6 looks up __round__ on the number before it converts
  ;; ndigits (int-conv-v1 measures these): a non-int ndigits is the
  ;; index TypeError for a number, never the round(float, n) deferral
  (check-cases*
    ops/runners
    [['(args-of (fn [] (py/round-conv (py/float 1.5) (py/str "x"))))
      [{:py/str "'str' object cannot be interpreted as an integer"}]]
     ['(args-of (fn [] (py/round-conv (py/float 1.5) (py/float 1.5))))
      [{:py/str "'float' object cannot be interpreted as an integer"}]]
     ['(args-of (fn [] (py/round-conv (py/str "a") (py/float 1.5))))
      [{:py/str "type str doesn't define __round__ method"}]]
     ['(caught (fn [] (py/round-conv (py/float 2.5) true)))
      "NotImplementedError"]
     ['(py/round-conv 7 :py/None) 7]]))


(deftest sequence-repetition-limit-test
  ;; Hand pins, not CPython 3.9.6 (C3 S7, ruling 11): this composition's
  ;; data/max-items is 1048576; a larger result is a MemoryError with
  ;; empty args before anything is built
  (check-cases*
    ops/runners
    [['(args-of (fn []
                  (py/mul (py/str "a")
                          (integer/sub (integer/pow 2 53) 1))))
      []]
     ['(caught (fn []
                 (py/mul (py/str "a")
                         (integer/sub (integer/pow 2 53) 1))))
      "MemoryError"]
     ['(caught (fn [] (py/mul (py/list (py/conj [] 0)) (integer/pow 2 62))))
      "MemoryError"]
     ['(caught (fn [] (py/mul (py/list (py/conj (py/conj [] 1) 2)) 524289)))
      "MemoryError"]
     ['(caught (fn [] (py/mul 524289 (py/tuple (py/conj (py/conj [] 1) 2)))))
      "MemoryError"]
     ['(caught (fn [] (py/mul (py/str "ab") 524289))) "MemoryError"]
     ['(py/mul (py/str "") (integer/pow 2 62)) {:py/str ""}]
     ['(py/mul (py/str "ab") 3) {:py/str "ababab"}]
     ['(py/mul (py/str "ab") true) {:py/str "ab"}]
     ['(py/mul (py/str "ab") -3) {:py/str ""}]
     ['(get (py/mul (py/tuple (py/conj [] 1)) 3) :items) [1 1 1]]
     ['(args-of (fn [] (py/mul (py/str "a") (integer/pow 2 63))))
      [{:py/str "cannot fit 'int' into an index-sized integer"}]]
     ['(py/mul (py/str "") (integer/neg (integer/pow 2 63))) {:py/str ""}]
     ['(caught (fn []
                 (py/mul (py/str "a")
                         (integer/sub (integer/neg (integer/pow 2 63))
                                      1))))
      "OverflowError"]
     ['(caught (fn [] (py/mul (py/str "a") (py/float 2.5)))) "TypeError"]
     ['(let [x (py/list (py/conj [] 1))]
         (py/conj (py/conj [] (py/is (py/imul x 3) x)) (py/len x)))
      [true 3]]
     ['(let [x (py/list (py/conj [] 1))]
         (py/conj (py/conj [] (caught (fn [] (py/imul x 1048577))))
                  (py/len x)))
      ["MemoryError" 1]]]))


(deftest ^:slow sequence-repetition-boundary-test
  ;; Hand pins: exactly data/max-items items build; one more is refused
  (slow/guard
    "sequence-repetition-boundary-test"
    (fn []
      (check-cases*
        ops/runners
        [['(py/len (py/mul (py/list (py/conj (py/conj [] 1) 2)) 524288))
          1048576]
         ['(caught (fn []
                     (py/mul (py/list (py/conj (py/conj [] 1) 2))
                             524289)))
          "MemoryError"]]))))


(deftest small-profile-repetition-test
  ;; Hand pins under the `small` profile (60 bits): the exact size breaches
  ;; the bit limit or max-items first, one class either way
  (check-cases*
    ops/small-runners
    [['(caught (fn [] (py/mul (py/str "a") (integer/pow 2 59))))
      "MemoryError"]
     ['(caught (fn []
                 (py/mul (py/list (py/conj (py/conj [] 1) 2))
                         (integer/pow 2 59))))
      "MemoryError"]
     ['(py/mul (py/str "ab") 3) {:py/str "ababab"}]]))


(deftest deferred-items-test
  (check-cases*
    ops/runners
    [;; (a) the exponent converts to float first
     ['(caught (fn []
                 (py/pow (py/float (data/float-value 1))
                         (integer/sub (integer/pow 2 1024)
                                      (integer/pow 2 970))))) "OverflowError"]
     ;; (b) a negative exponent makes the result a float: base, then
     ;; exponent, are converted before the zero check
     ['(caught (fn [] (py/pow 0 (integer/neg (integer/pow 2 1024)))))
      "OverflowError"]
     ['(caught (fn [] (py/pow 2 (integer/neg (integer/pow 2 1024)))))
      "OverflowError"]
     ['(caught (fn [] (py/pow 0 -1))) "ZeroDivisionError"]
     ;; (d) a finite base and exponent with an infinite result
     ['(args-of (fn [] (py/pow (py/float (data/float-value 2)) 1024)))
      [34 {:py/str "Result too large"}]]
     ['(caught (fn [] (py/pow (py/float 0.5) -2000))) "OverflowError"]
     ['(py/pow (py/float (data/float-value 1)) (py/float (nan)))
      {:py/float (data/float-value 1)}]
     ['(py/pow (py/float 0.5) (py/float (data/float-value ##-Inf)))
      {:py/float (data/float-value ##Inf)}]
     ;; an integral float exponent of any size goes through from-float,
     ;; never py/floor's 2^53 range
     ['(caught (fn []
                 (py/pow (py/float (data/float-value 2))
                         (py/float (data/float-value
                                     9007199254740992)))))
      "OverflowError"]
     ['(py/pow (py/float (data/float-value -1))
               (py/float (py/fpow (data/float-value 10) 300)))
      {:py/float (data/float-value 1)}]
     ;; (f) per-operator float messages
     ['(args-of (fn [] (py/floordiv (py/float (data/float-value 1)) 0)))
      [{:py/str "float floor division by zero"}]]
     ['(args-of (fn [] (py/mod 1 (py/float (data/float-value 0)))))
      [{:py/str "float modulo"}]]
     ;; (g) the catch-class message is a guest string
     ['(args-of (fn [] (py/exc-matches (py/make-instance py.b/ValueError) 1)))
      [{:py/str (str "catching classes that do not inherit from "
                     "BaseException is not allowed")}]]
     ;; (h) NaN identity in containers
     ['(let [x (py/float (nan))]
         (py/in x (py/list (py/conj [] x)))) true]
     ['(let [x (py/float (nan))]
         (py/eq (py/tuple (py/conj [] x)) (py/tuple (py/conj [] x)))) true]
     ;; (i) a float in a range, in constant time
     ['(py/in (py/float 2.5) (py/range3 0 10 1)) false]
     ['(py/in (py/float (data/float-value 2)) (py/range3 0 10 1)) true]
     ['(py/in (py/float (nan)) (py/range3 0 10 1)) false]
     ['(py/in (py/float (data/float-value ##Inf))
              (py/range3 0 (integer/pow 2 2000) 1)) false]
     ['(py/in (py/float (data/float-value 9007199254740992))
              (py/range3 0 (integer/pow 2 2000) 2)) true]]))


(deftest digit-limit-test
  ;; Hand pins, not CPython 3.9.6 (which has no digit limit): the
  ;; profile's limit is N = 4300 here, and the message names N.
  (check-cases*
    ops/runners
    [['(data/str-length
         (get (py/str-conv (py/int-result
                             (integer/sub (integer/pow 10 4300) 1)) false)
              :py/str)) 4300]
     ['(args-of (fn [] (py/str-conv (integer/pow 10 4300) false)))
      [{:py/str (limit-message 4300)}]]
     ['(args-of (fn [] (py/str-conv (integer/pow 10 4300) true)))
      [{:py/str (limit-message 4300)}]]
     ['(data/str-length
         (get (py/str-conv (py/int-conv (py/str (py/text-repeat "7" 4300 ""))
                                        :py/missing) false) :py/str)) 4300]
     ['(args-of (fn []
                  (py/int-conv (py/str (py/text-repeat "7" 4301 ""))
                               :py/missing)))
      [{:py/str (limit-message 4300)}]]
     ;; a syntax error wins over the limit
     ['(data/str-index-of
         (get (get (args-of
                     (fn []
                       (py/int-conv
                         (py/str (py/text-repeat "7_" 4301 ""))
                         :py/missing))) 0) :py/str)
         "invalid literal for int() with base 10: ")
      0]
     ;; power-of-two bases are exempt both ways
     ['(data/str-length
         (get (py/radix-conv (integer/pow 16 5000) 16 "0x") :py/str)) 5003]
     ['(data/str-length
         (get (py/str-conv
                (py/int-result
                  (integer/bit-and
                    (py/int-conv (py/str (py/text-repeat "f" 5000 "")) 16)
                    255)) false) :py/str)) 3]]))


(deftest small-profile-digit-limit-test
  ;; Hand pins under the `small` profile: 60 bits and 5 digits.
  (check-cases*
    ops/small-runners
    [['(py/str-conv 99999 false) {:py/str "99999"}]
     ['(args-of (fn [] (py/str-conv 100000 true)))
      [{:py/str (limit-message 5)}]]
     ['(py/int-conv (py/str "-99999") :py/missing) -99999]
     ['(args-of (fn [] (py/int-conv (py/str "100000") :py/missing)))
      [{:py/str (limit-message 5)}]]
     ['(caught (fn [] (py/print (py/conj [] 123456)))) "ValueError"]
     ;; int(1e300) needs 997 bits
     ['(caught (fn []
                 (py/int-conv
                   (py/float (py/fpow (data/float-value 10) 300))
                   :py/missing)))
      "MemoryError"]]))


(deftest print-atomicity-test
  ;; A breach anywhere in print's arguments, here inside a list inside a
  ;; tuple, raises before anything reaches py.rt/out.
  (check-cases*
    ops/runners
    [['(do (py/print (py/conj [] 1))
           (py/conj
             (py/conj []
                      (caught
                        (fn []
                          (py/print
                            (py/conj
                              (py/conj [] 2)
                              (py/tuple
                                (py/conj
                                  []
                                  (py/list
                                    (py/conj [] (integer/pow 10 4300))))))))))
             (cell/get py.rt/out)))
      ["ValueError" [[1]]]]]))


(deftest departure-pins-test
  ;; Hand pins, not CPython 3.9.6. Under content identity two separately
  ;; made NaNs are one value, so `in` finds one where CPython says False
  ;; (the one-NaN departure); non-ASCII digits are refused (ASCII policy).
  (let [arabic-12 (fixtures/code-points->text "661,662")
        arabic-1-5 (fixtures/code-points->text "661,2e,35")
        astral (fixtures/code-points->text "61,1f600,27")]
    (check-cases*
      ops/runners
      [['(py/in (py/float-conv (py/str "nan"))
                (py/list (py/conj [] (py/float-conv (py/str "nan")))))
        true]
       [(list 'caught (list 'fn [] (list 'py/int-conv (list 'py/str arabic-12)
                                         :py/missing)))
        "ValueError"]
       [(list 'caught (list 'fn [] (list 'py/float-conv
                                         (list 'py/str arabic-1-5))))
        "ValueError"]
       [(list 'py/str-conv (list 'py/str astral) true)
        {:py/str (str "\"" astral "\"")}]]))
  ;; float("-nan") is the one NaN; its sign bit is the host CPU's default
  ;; NaN's, never set from the text (CPython: fff8000000000000)
  (doseq [[k result] (ops/run-with-prelude
                       prelude/uast
                       '(py/num (py/float-conv (py/str "-nan"))))]
    (let [v (data/float-value result)]
      (is (not (<= v v)) (str k)))))


(deftest string-literal-lint-test
  "Scope: prelude/definitions, which now holds the builtin function and
   method forms inside py/init!'s body, so every {:py/str x} literal there
   is walked too."
  (let [bad (atom [])]
    (walk/postwalk
      (fn [x]
        (when (and (map? x) (contains? x :py/str)
                   (not (string? (:py/str x))))
          (swap! bad conj x))
        x)
      prelude/definitions)
    (is (= [] @bad))))


(def ^:private radix-op
  '(fn [op a b]
     (if (= op "hex") (py/radix-conv a 16 "0x")
         (if (= op "oct") (py/radix-conv a 8 "0o")
             (if (= op "bin") (py/radix-conv a 2 "0b")
                 (let [z (py/float (data/float-value 0))]
                   (if (= b 0) (py/truediv a z)
                       (if (= b 1) (py/floordiv a z)
                           (if (= b 2) (py/mod a z)
                               (py/divmod a z))))))))))


(def ^:private keyword-op
  ;; pow(base=a, exp=b) and round(number=a, ndigits=b), through the
  ;; builtins' own binding
  '(fn [op a b]
     (let [kw (fn [k x] (py/conj (py/conj [] k) x))]
       (if (= op "pow_kw")
         (py/call-kw py.b/pow [] (py/conj (py/conj [] (kw "base" a))
                                          (kw "exp" b)))
         (py/call-kw py.b/round [] (py/conj (py/conj [] (kw "number" a))
                                            (kw "ndigits" b)))))))


(def ^:private number-op
  '(fn [op a b]
     (if (= op "abs") (py/abs-conv a)
         (if (= op "round1") (py/round-conv a :py/None)
             (if (= op "round_int") (py/round-conv a b)
                 (if (= op "pow_float") (py/pow a b)
                     (if (if (= op "pow_kw") true (= op "round_kw"))
                       (keyword-op op a b)
                       (radix-op op a b))))))))


(def ^:private conversion-op
  '(fn [op a b]
     (if (= op "int_str") (py/int-conv a b)
         (if (= op "int_float") (py/int-conv a :py/missing)
             (if (= op "float_int") (py/float-conv a)
                 (if (= op "float_str") (py/float-conv (py/str a))
                     (if (= op "repr_float")
                       (get (py/str-conv a true) :py/str)
                       (if (= op "str_int") (py/str-conv a false)
                           (number-op op a b)))))))))


(defn fixture-form
  "At most 400 rows; return values and exception class/message data."
  [rows]
  (list 'let
        ['decode-number ops/decode-number 'radix-op radix-op
         'keyword-op keyword-op 'number-op number-op 'operate conversion-op
         'rows rows 'loop
         '(fn [self i acc]
            (if (< i (data/count rows))
              (let [row (get rows i) result (cell/new nil)]
                (do (py/try
                      (fn []
                        (cell/set! result
                                   (operate (get row 0)
                                            (decode-number (get row 1))
                                            (decode-number (get row 2)))))
                      (fn [e]
                        (cell/set! result
                                   (assoc
                                     (assoc {} :error
                                            (get (cell/get
                                                   (get (cell/get e) :class))
                                                 :name))
                                     :message
                                     (py/snapshot-all
                                       (get (get (get (cell/get e) :attrs)
                                                 "args")
                                            :items) 0 []))))
                      (fn [] :py/None))
                    (self self (+ i 1) (conj acc (cell/get result)))))
              acc))]
        '(loop loop 0 [])))


(defn- comparable
  "Snapshots as comparable data: messages as CPython's str(e), floats as
   bits, every NaN as one (the one-NaN rule: dao.jing writes every NaN
   as 7ff8000000000000, so a NaN's sign is no guest value)."
  [x]
  (ops/canon-ints
    (walk/postwalk
      (fn [n]
        (cond
          (and (map? n) (contains? n :error)
               (vector? (:message n)))
          (assoc n :message
                 (if (= 1 (count (:message n)))
                   (str (first (:message n)))
                   (render/repr {:py/tuple (:message n)})))
          (and (map? n) (contains? n :py/float))
          (let [v (data/float-value (:py/float n))]
            (if (<= v v) {:float-bits (fx/double->bits v)} {:float :nan}))
          (and (map? n) (contains? n :py/str)) (:py/str n)
          :else n))
      (ops/host-floats x))))


(defn- input-text
  [op a]
  (cond
    (= op "float_str") a
    (and (= op "int_str") (map? a)) (:py/str a)
    :else nil))


(defn- departure
  "The hand-pinned expectation where this profile departs from CPython
   3.9.6: py/str-repr, like render/string-repr, escapes only ASCII
   controls, so a message quoting a non-ASCII non-printable character
   (U+0085, U+00A0, U+200B, U+FEFF, ...) carries it raw where CPython
   writes \\x, \\u or \\U. Every other row is CPython's."
  [[op a _ expected]]
  (let [s (input-text op a)
        m (:message expected)]
    (if (and s (string? m) (re-find #"[^\x00-\x7f]" s))
      (let [i (+ 2 (str/index-of m ": "))
            quoted (subs m i)]
        (assoc expected :message
               (str (subs m 0 i)
                    (if (= quoted "''") quoted (render/string-repr s)))))
      expected)))


(defn- check-fixture
  [rows]
  (reduce
    (fn [_ [i batch]]
      (let [batch (mapv (fn [[op a b expected]]
                          [op a (if (and (= op "int_str") (nil? b))
                                  :py/None b) expected]) batch)
            expected (mapv (fn [row]
                             (let [v (departure row)]
                               (if (:int v)
                                 ((get exact 'parse) (:int v) 16) v))) batch)]
        (reduce
          (fn [_ [k answer]]
            (let [actual (comparable answer)
                  expected (comparable expected)
                  bad (if (and (vector? actual)
                               (= (count actual) (count expected)))
                        (vec (keep-indexed
                               (fn [j e]
                                 (when (not= e (get actual j))
                                   [j (get batch j) (get answer j)]))
                               expected))
                        answer)
                  ;; the renderer parity law: guest repr = render/float-repr
                  unequal (vec (keep-indexed
                                 (fn [j [op a]]
                                   (when (and (= op "repr_float")
                                              (not= (get answer j)
                                                    (render/float-repr
                                                      (:py/float a))))
                                     [j a (get answer j)]))
                                 batch))]
              (is (= [] bad) (str k " batch " i " " (vec (take 8 bad))))
              (is (= [] unequal) (str k " parity batch " i " "
                                      (vec (take 8 unequal))))))
          nil (ops/run-with-prelude prelude/uast (fixture-form batch))))
      nil)
    nil (map-indexed vector (partition-all 400 rows))))


(deftest ^:slow cpython-conversion-fixture-test
  (slow/guard
    "cpython-conversion-fixture-test"
    (fn []
      (check-fixture
        (fixtures/read-file "test/resources/yang/python/int-conv-v1.txt")))))


(deftest ^:slow guest-float-text-fixture-test
  (slow/guard
    "guest-float-text-fixture-test"
    (fn []
      (check-fixture (fixtures/read-file fixtures/float-text-path)))))

(ns yang.python.antlr.int-ops-parser-test
  "The CPython operator corpus evaluated once through the JVM parser."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [dao.stream :as stream]
    [yang.python.antlr.e2e-test :as e2e]
    [yang.python.antlr.int-ops-fixtures :as fixtures]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.render :as render]
    [yin.vm :as vm]
    [yin.vm.integer :as integer]
    [yin.vm.test-utils :as tu]))


(def ^:private exact (integer/integer-module e2e/integer-limits))


(defn- source-value
  [v]
  (cond
    (nil? v) "None"
    (true? v) "True"
    (false? v) "False"
    (:int v) (let [hex (:int v)]
               (if (str/starts-with? hex "-")
                 (str "-0x" (subs hex 1)) (str "0x" hex)))
    :else (let [x (double (:py/float v))]
            (cond
              (Double/isNaN x) "(1e309 - 1e309)"
              (= x Double/POSITIVE_INFINITY) "1e309"
              (= x Double/NEGATIVE_INFINITY) "-1e309"
              :else (str x)))))


(defn- expected-text
  [v]
  (if (string? v) v
      (if (:tuple v)
        (str "(" (str/join ", " (map expected-text (:tuple v))) ")")
        (render/repr (if (:int v) ((get exact 'parse) (:int v) 16) v)))))


(def ^:private binary-ops
  [["add" "+"] ["sub" "-"] ["mul" "*"] ["lt" "<"] ["le" "<="]
   ["gt" ">"] ["ge" ">="] ["eq" "=="] ["ne" "!="] ["truediv" "/"]
   ["floordiv" "//"] ["mod" "%"]
   ["pow" "**"] ["bitand" "&"] ["bitor" "|"] ["bitxor" "^"]
   ["lshift" "<<"] ["rshift" ">>"]])


(defn- source
  [rows]
  (let [tokens (into {} binary-ops)]
    (apply str
           (map (fn [[op a b _]]
                  (let [expr (if (contains? #{"neg" "invert"} op)
                               (str (if (= op "neg") "-" "~")
                                    " (" (source-value a) ")")
                               (if (= op "divmod")
                                 (str "divmod(" (source-value a) ", "
                                      (source-value b) ")")
                                 (str "(" (source-value a) ") " (get tokens op)
                                      " (" (source-value b) ")")))]
                    (str "try:\n"
                         "    print(" expr ")\n"
                         "except OverflowError:\n"
                         "    print('!OverflowError')\n"
                         "except ValueError:\n"
                         "    print('!ValueError')\n"
                         "except ZeroDivisionError:\n"
                         "    print('!ZeroDivisionError')\n"))) rows))))


(defn- parser-run
  [text]
  (let [src (tu/new-memory-log)
        cst (tu/new-memory-log)
        program (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (stream/append! src {:yang.source/event :yang.source/chunk
                         :yang.source/unit :int-ops
                         :yang.source/ordinal 0 :yang.source/text text})
    (stream/append! src {:yang.source/event :yang.source/seal
                         :yang.source/unit :int-ops :yang.source/chunk-count 1})
    (parser/step-stage (parser/open-stage src cst))
    (lower/step-stage (lower/open-stage cst program diagnostics
                                        lower/program-medium 4300))
    (let [problems (tu/drain diagnostics)]
      (if (seq problems) {:diagnostics problems}
          (let [{:yin/keys [batch root]} (first (tu/drain program))]
            (render/output
              (vm/value
                (vm/eval (tu/create-vm {:modules (e2e/host-registry)})
                         (nth batch root)))))))))


(deftest ^:slow cpython-operator-parser-test
  ;; Bound each parser/lowering tree; every corpus row is parsed once.
  (doseq [[i rows] (map-indexed vector
                                (partition-all 64 (fixtures/read-file)))]
    (is (= {:py/out (mapv (comp expected-text last) rows) :py/exception nil}
           (parser-run (source rows))) (str "fixture batch " i))))


(deftest augmented-index-and-comparison-test
  (e2e/every-vm=
    {:py/out ["3 [36893488147419103232]" "True True False"
              "IndexError" "IndexError" "[1, 2, 3] [3]"]
     :py/exception nil}
    (str "calls = 0\n"
         "a = [18446744073709551616]\n"
         "def index():\n"
         "    global calls\n"
         "    calls += 1\n"
         "    return 0\n"
         "a[index()] += 1\n"
         "a[index()] -= 1\n"
         "a[index()] *= 2\n"
         "print(calls, a)\n"
         "print(9007199254740992 < 9007199254740993 < 9223372036854775808,"
         " 9007199254740993 in [9007199254740993],"
         " 9007199254740993 == 9007199254740992.0)\n"
         "for k in [18446744073709551616, -18446744073709551616]:\n"
         "    try:\n"
         "        a[k] = 0\n"
         "    except IndexError:\n"
         "        print('IndexError')\n"
         "b = [1, 2, 3]\n"
         "print(b[-18446744073709551616:18446744073709551616],"
         " b[:: -18446744073709551616])\n")))


(deftest ^:slow cpython-s3b-parser-test
  (let [rows (fixtures/read-file
               "test/resources/yang/python/int-ops-v2.txt")]
    (doseq [[i rows] (map-indexed vector (partition-all 64 rows))]
      (is (= {:py/out (mapv (comp expected-text last) rows) :py/exception nil}
             (parser-run (source rows))) (str "v2 fixture batch " i)))))


(deftest divmod-builtin-test
  (e2e/every-vm=
    {:py/out ["(-3, 2) (2.0, 1.5)" "TypeError" "TypeError"]
     :py/exception nil}
    (str "f = divmod\nprint(f(-7, 3), f(7.5, 3))\n"
         "try:\n    f(1)\nexcept TypeError:\n    print('TypeError')\n"
         "try:\n    f(x=1, y=2)\n"
         "except TypeError:\n    print('TypeError')\n")))


(deftest augmented-division-power-test
  (e2e/every-vm=
    {:py/out ["4 [12.5]"] :py/exception nil}
    (str "calls = 0\na = [18446744073709551617]\n"
         "def index():\n    global calls\n    calls += 1\n    return 0\n"
         "a[index()] //= 3\na[index()] %= 7\na[index()] **= 2\n"
         "a[index()] /= 2\nprint(calls, a)\n")))


(deftest ^:slow cpython-s3c-parser-test
  (let [rows (fixtures/read-file
               "test/resources/yang/python/int-ops-v3.txt")]
    (doseq [[i batch] (map-indexed vector (partition-all 64 rows))]
      (is (= {:py/out (mapv (comp expected-text last) batch)
              :py/exception nil}
             (parser-run (source batch))) (str "v3 fixture batch " i)))))


(deftest conversion-builtins-source-test
  ;; S4's builtins from source; a module-level def shadows a builtin
  (e2e/every-vm=
    {:py/out ["-31 5 105.0 3.5e-05 'a' False 2.5 0.5 0xff 0o10 -0b101 2 1200"
              "(\"invalid literal for int() with base 10: 'x'\",)"
              (str "('Exceeds the limit (4300 digits) for integer string"
                   " conversion',)")
              "1e+16 -0.0 -inf 290000000000000000000"
              "shadow 1.5"]
     :py/exception nil}
    (str "print(int(' -0x_1f ', 0), int('101', 2), float('1_0.5e1'),"
         " str(3.5e-05), repr('a'), bool([]), abs(-2.5), pow(2, -1),"
         " hex(255), oct(8), bin(-5), round(2.5), round(1234, -2))\n"
         "try:\n    int('x')\nexcept ValueError as e:\n    print(e.args)\n"
         "x = 10 ** 4300\n"
         "try:\n    print(1, [x])\nexcept ValueError as e:\n"
         "    print(e.args)\n"
         "print(repr(1e16), str(-0.0), float('-inf'), int(2.9e20))\n"
         "def str(x):\n    return 'shadow'\n"
         "print(str(1), repr(1.5))\n")))


(deftest augmented-bitwise-shifts-test
  (e2e/every-vm=
    {:py/out ["5 [18446744073709551617]" "True True False 1"]
     :py/exception nil}
    (str "calls = 0\na = [18446744073709551617]\n"
         "def index():\n    global calls\n    calls += 1\n    return 0\n"
         "a[index()] &= -1\na[index()] |= 2\na[index()] ^= 2\n"
         "a[index()] <<= 54\na[index()] >>= 54\nprint(calls, a)\n"
         "print(True & True, False | True, True ^ True, True & 3)\n")))

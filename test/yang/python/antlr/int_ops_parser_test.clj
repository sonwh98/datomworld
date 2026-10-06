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
   ["pow" "**"]])


(defn- source
  [rows]
  (let [tokens (into {} binary-ops)]
    (apply str
           (map (fn [[op a b _]]
                  (let [expr (if (= op "neg")
                               (str "- (" (source-value a) ")")
                               (if (= op "divmod")
                                 (str "divmod(" (source-value a) ", "
                                      (source-value b) ")")
                                 (str "(" (source-value a) ") " (get tokens op)
                                      " (" (source-value b) ")")))]
                    (str "try:\n"
                         "    print(" expr ")\n"
                         "except OverflowError:\n"
                         "    print('!OverflowError')\n"
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

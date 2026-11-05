(ns yang.python.antlr.source-call-test
  "S11's first real-source conformance legs for Python (§8.10.11).
   Each side is parsed and lowered by its frontend profile. The shared
   conformance composition publishes exports and projects them by name."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.call.conformance :as conformance]))


(deftest python-donor-to-typescript-source-test
  (testing "a Python module export is called by unmodified TypeScript source"
    (is (= {:js/out ["22n"], :js/uncaught nil}
           (conformance/call
             [:python (str "def py_apply(f):\n"
                           "    global stored\n"
                           "    stored = f\n"
                           "    return f(20) + 1 if stored is f else -1")]
             ["py_apply"]
             [:typescript "console.log(py_apply(x => x + 1n));"])))))


(deftest typescript-donor-to-python-source-test
  (testing "a TypeScript module export is called by unmodified Python source"
    (is (= {:py/out ["22"], :py/exception nil}
           (conformance/call
             [:typescript (str "globalThis.ts_apply = f => { globalThis.stored = f; "
                               "return stored === f ? f(20n) + 1n : -1n; }")]
             ["ts_apply"]
             [:python "print(ts_apply(lambda x: x + 1))\n"])))))


(deftest typescript-callback-through-python-source-test
  (testing "Python receives and calls the captured foreign function unchanged"
    (is (= {:py/out ["(True, 21)"], :py/exception nil}
           (conformance/call
             [:typescript (str "const captured = 1n; "
                               "globalThis.foreign = x => x + captured;")]
             ["foreign"]
             [:python (str "def invoke(f):\n"
                           "    stored = f\n"
                           "    return stored is f, f(20)\n"
                           "print(invoke(foreign))\n")])))))


(deftest typescript-opaque-values-through-python-source-test
  (testing "Python returns foreign values unchanged through its source body"
    (is (= {:py/out ["True True True True True"], :py/exception nil}
           (conformance/call
             [:typescript (str "globalThis.opaque = {answer: 42};\n"
                               "globalThis.foreign = x => x;\n"
                               "globalThis.foreignText = 'unchanged';\n"
                               "globalThis.foreignFloat = 1.25;\n"
                               "globalThis.foreignInteger = 9007199254740993123456789n;")]
             ["opaque" "foreign" "foreignText" "foreignFloat" "foreignInteger"]
             [:python (str "def echo(value):\n    return value\n"
                           "print(echo(opaque) is opaque, echo(foreign) is foreign, "
                           "echo(foreignText) is foreignText, "
                           "echo(foreignFloat) is foreignFloat, "
                           "echo(foreignInteger) is foreignInteger)\n")])))))

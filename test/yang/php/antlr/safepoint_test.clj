(ns yang.php.antlr.safepoint-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.safepoint :as hooks]))


(def ^:private vms [:ast-walker :semantic :stack :register])


(defn- every-vm=
  [expected results]
  (doseq [k vms]
    (is (= expected (get results k)) (name k))))


(deftest exhaustion-is-an-operational-fatal-test
  (let [source (str "<?php class C { function __destruct() { echo 'D'; } } "
                    "$c = new C; try { while (true) {} } "
                    "catch (Throwable $e) { echo 'C'; }")]
    (every-vm= (str "PHP Fatal error:  " hooks/fatal-text)
               (h/run-php source {:execution-budget 4}))))


(deftest exhaustion-inside-try-finally-is-fatal-test
  (let [source (str "<?php function g() { yield 1; } "
                    "try { while (true) {} } finally { echo 'F'; }")]
    (every-vm= (str "PHP Fatal error:  " hooks/fatal-text)
               (h/run-php source {:execution-budget 4}))))


(deftest exhaustion-in-shutdown-is-fatal-test
  (let [source (str "<?php register_shutdown_function(function() { while (true) {} });"
                    " echo 'A';")]
    (every-vm= (str "A" "PHP Fatal error:  " hooks/fatal-text)
               (h/run-php source {:execution-budget 4}))))


(deftest budget-validation-test
  (is (thrown? clojure.lang.ExceptionInfo (hooks/program -1 nil)))
  (is (thrown? clojure.lang.ExceptionInfo (hooks/program 1.5 nil)))
  (is (thrown? clojure.lang.ExceptionInfo (hooks/program "4" nil))))


(deftest unbounded-and-unexhausted-runs-agree-test
  (let [source "<?php function f($x) { return $x + 1; } $i = 0; while ($i < 5) { $i = f($i); } echo $i;"
        unbounded (h/run-php source)]
    (testing "the default remains unbudgeted"
      (every-vm= "5" unbounded))
    (testing "a budget that is not exhausted is unobservable"
      (is (= unbounded (h/run-php source {:execution-budget 100}))))))

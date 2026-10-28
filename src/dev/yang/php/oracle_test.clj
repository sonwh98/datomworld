(ns yang.php.oracle-test
  "Dev-only regression tests for the oracle harness's stdout parser; run with
   `clojure -M:test:dev -d src/dev -n yang.php.oracle-test`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.oracle :as oracle]))


(def ^:private uncaught-block
  (str "\nFatal error: Uncaught Exception: e in /w/f.php:3\n"
       "Stack trace:\n#0 {main}\n  thrown in /w/f.php on line 3\n"))


(deftest uncaught-block-keeps-shutdown-output-test
  (testing "output before and after an uncaught exception survives the strip"
    (is (= {:out "aS", :diag [], :uncaught {:class "Exception", :message "e"}}
           (oracle/parse-stdout (str "a" uncaught-block "S") "f.php"))))
  (testing "a block with no trailing output still parses"
    (is (= {:out "a", :diag [], :uncaught {:class "Exception", :message "e"}}
           (oracle/parse-stdout (str "a" uncaught-block) "f.php"))))
  (testing "a multi-line stack trace is consumed up to the terminator"
    (is (= "xS"
           (:out (oracle/parse-stdout
                   (str "x\nFatal error: Uncaught Error: m in /w/f.php:2\nStack trace:\n"
                        "#0 /w/f.php(5): f()\n#1 {main}\n  thrown in /w/f.php on line 2\nS")
                   "f.php"))))))


(deftest plain-fatal-keeps-shutdown-output-test
  (testing "a plain fatal followed by shutdown-time output"
    (let [r (oracle/parse-stdout "a\nFatal error: boom in /w/f.php on line 3\nS" "f.php")]
      (is (= "aS" (:out r)))
      (is (= {:class "Error", :message "boom"} (:uncaught r)))))
  (testing "a plain fatal at the very end"
    (is (= "a"
           (:out (oracle/parse-stdout "a\nFatal error: boom in /w/f.php on line 3\n" "f.php"))))))

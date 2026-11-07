(ns yang.php.antlr.p5b2-lifecycle-test
  "P5b2 regressions: the request lifecycle, merge, header/cookie/status and
  error-triggering completions. Expectations are hand-derived from PHP 8.3;
  every fixture stays available to the oracle recording pass."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.linked-harness :as lh]
    [yang.php.antlr.prelude :as prelude]
    [yang.php.antlr.programs-p5b2 :as p5b2]))


(defn- diagnostics
  [r]
  (mapv (juxt :errno :message) (:php/diag r)))


(deftest p5b2-lifecycle-fixtures-test
  (doseq [{:keys [id source request out diag response status fatal uncaught]} p5b2/fixtures]
    (testing (name id)
      (let [r (h/run-raw source request)]
        (is (= out (:php/out r)))
        (is (= (vec diag) (diagnostics r)))
        (when response (is (= response (:php/response r))))
        (when status (is (= status (:php/status r))))
        (when fatal (is (= fatal (:php/fatal r))))
        (when-not fatal (is (nil? (:php/fatal r))))
        (if uncaught
          (is (= uncaught (select-keys (:php/uncaught r) [:class :message])))
          (is (nil? (:php/uncaught r))))))))


(deftest run-main-consumes-request-without-rewrite-test
  (testing "production initialization seeds superglobals from the operand"
    (let [r (h/run-raw "<?php echo $_GET['v'];" (assoc p5b2/base-request
                                                       :php.request/query
                                                       (p5b2/request-array [["v" "Q"]])))]
      (is (= "Q" (:php/out r)))
      (is (= [] (:php/diag r))))))


(defn- linked-out
  [source request]
  (let [{:keys [values]} (lh/run-fixture {"main.php" (cst/parse-source source)}
                                         "main.php"
                                         {:request request})]
    (into {} (map (fn [[k v]] [k (:php/out v)]) values))))


(deftest linked-profile-consumes-request-and-isolates-test
  (testing "the :linked entry point takes the request operand too"
    (let [r (linked-out "<?php echo $_GET['v'], $_REQUEST['w'];"
                        (assoc p5b2/base-request
                               :php.request/query (p5b2/request-array [["v" "Q"]])
                               :php.request/body (p5b2/request-array [["w" "W"]])))]
      (is (= {:semantic "QW", :stack "QW", :register "QW"} r))))
  (testing "sequential linked runs do not leak request state"
    (let [src "<?php echo $_GET['v'];"
          q (fn [v]
              (assoc p5b2/base-request
                     :php.request/query (p5b2/request-array [["v" v]])))]
      (is (= {:semantic "one", :stack "one", :register "one"} (linked-out src (q "one"))))
      (is (= {:semantic "two", :stack "two", :register "two"} (linked-out src (q "two"))))))
  (testing "no live request cell is a module export"
    (is (not (contains? prelude/module-exports 'rt/request)))
    (is (not (contains? prelude/module-exports 'rt/response)))
    (is (not (contains? prelude/module-exports 'rt/out)))
    (is (not (contains? prelude/module-exports 'rt/diag)))))

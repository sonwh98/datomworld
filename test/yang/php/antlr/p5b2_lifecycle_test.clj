(ns yang.php.antlr.p5b2-lifecycle-test
  "P5b2 regressions: the request lifecycle, merge, header/cookie/status and
  error-triggering completions. Expectations are hand-derived from PHP 8.3;
  every fixture stays available to the oracle recording pass."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p5b2 :as p5b2]))


(defn- diagnostics
  [r]
  (mapv (juxt :errno :message) (:php/diag r)))


(deftest p5b2-lifecycle-fixtures-test
  (doseq [{:keys [id source request out diag response status fatal]} p5b2/fixtures]
    (testing (name id)
      (let [r (h/run-raw source request)]
        (is (= out (:php/out r)))
        (is (= (vec diag) (diagnostics r)))
        (when response (is (= response (:php/response r))))
        (when status (is (= status (:php/status r))))
        (when fatal (is (= fatal (:php/fatal r))))
        (when-not fatal (is (nil? (:php/fatal r))))
        (is (nil? (:php/uncaught r)))))))


(deftest run-main-consumes-request-without-rewrite-test
  (testing "production initialization seeds superglobals from the operand"
    (let [r (h/run-raw "<?php echo $_GET['v'];" (assoc p5b2/base-request
                                                       :php.request/query
                                                       (p5b2/request-array [["v" "Q"]])))]
      (is (= "Q" (:php/out r)))
      (is (= [] (:php/diag r))))))

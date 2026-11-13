(ns yang.php.antlr.ticks-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.linked-harness :as lh]
    [yang.php.antlr.programs-p7b :as p7b]))


(deftest tick-fixtures-test
  (doseq [{:keys [id source out units uncaught status diag]} p7b/fixtures
          :when (nil? units)]
    (testing (name id)
      (let [result (h/run-raw (str "<?php " p7b/prologue source))]
        (is (= out (:php/out result)))
        (is (= uncaught (:php/uncaught result)))
        (is (= status (get result :php/status 0)))
        (is (= diag (:php/diag result)))))))


(deftest include-handoff-test
  (let [{:keys [source units out]} (last p7b/fixtures)
        packets (update-vals (assoc units "main.php" (str "<?php " p7b/prologue source))
                             cst/parse-source)
        result (lh/run-fixture packets "main.php" {})]
    (doseq [[vm value] (:values result)]
      (is (= out (h/latin1->text (:php/out value))) (name vm)))))

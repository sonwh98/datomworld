(ns yang.php.antlr.builtin-surfaces-test
  "P9 builtin surfaces, dynamic scopes, and corner closures."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p9 :as p9]))


(deftest p9-fixtures-test
  (doseq [{:keys [id source out diag]} p9/fixtures]
    (testing (name id)
      (let [r (h/run-raw source)]
        (is (= out (:php/out r)))
        (is (= (vec diag) (mapv (juxt :errno :message) (:php/diag r))))
        (is (nil? (:php/uncaught r)))))))


(deftest p9-compile-refusals-test
  (doseq [[source reason]
          [["<?php eval('$x = 1;');" :eval]
           ["<?php $x = 1; $n = 'x'; $$n =& $x;" :dynamic-reference]
           ["<?php [[&$a]] = [[1]];" :yang.php/compile-error]
           ["<?php $GLOBALS = [];" :yang.php/compile-error]
           ["<?php unset($GLOBALS);" :yang.php/compile-error]]]
    (testing reason
      (let [{:keys [diagnostics]} (h/lower-program source)
            d (first diagnostics)]
        (is (seq diagnostics))
        (is (= reason (or (:construct d) (:yang.php/diagnostic d))))))))

(ns yang.php.antlr.eval-bridge-test
  (:require [clojure.test :refer [deftest is]]
            [yang.php.antlr.e2e-harness :as h]
            [yang.php.antlr.lower :as lower]
            [yang.php.antlr.portable-harness :as portable]))


(deftest eval-in-caller-scope-on-every-vm
  (doseq [[source expected] [["<?php echo eval(\"return 6 * 7;\");" "42"]
                             ["<?php $x = 40; echo eval('return $x + 2;');" "42"]]]
    (let [results (h/run-php source)]
      (doseq [backend [:ast-walker :semantic :stack :register]]
        (is (= expected (get results backend)) (pr-str [backend source results]))))))


(deftest dedicated-prefix-form-lowers-with-service
  (let [packet (portable/packet [:htmlDocument
                                 [:phpBlock [:topStatement [:statement [:expressionStatement
                                                                        [:expression ["Eval" "eval"] ["(" "("]
                                                                         [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                                                                         [")" ")"]] ["SemiColon" ";"]]]]]
                                 ["EOF" "<EOF>"]])]
    (is (:ast (lower/lower-program packet {:eval-service? true})))))

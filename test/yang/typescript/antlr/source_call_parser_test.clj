(ns yang.typescript.antlr.source-call-parser-test
  "The portable S11 fixtures are CSTs of the recorded, unmodified source."
  (:require [clojure.test :refer [deftest is]]
            [yang.php.antlr.cst :as php]
            [yang.php.antlr.e2e-harness :as php-h]
            [yang.typescript.antlr.cst :as ts]
            [yang.typescript.antlr.source-call-fixtures :as fixtures]))


(deftest source-fixtures-match-parser-test
  (doseq [{:keys [php-source php-form ts-source ts-form]}
          [fixtures/direct fixtures/callback]]
    (is (= php-form (php-h/packet->form (php/parse-source php-source))))
    (is (= ts-form (php-h/packet->form (ts/parse-source ts-source))))))

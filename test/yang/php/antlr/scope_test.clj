(ns yang.php.antlr.scope-test
  "P1: PHP scope analysis (section 7.3). Sources parse on the JVM."
  (:require
    [clojure.test :refer [deftest is]]
    [yang.antlr.packet :as packet]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.scope :as scope]))


(defn- analysis
  [src]
  (scope/analyze (packet/validate! (cst/parse-source src))))


(defn- function-scope
  [src]
  (first (vals (:scopes (analysis src)))))


(deftest unit-scope-test
  (let [a (analysis "<?php $x = 1; $y = 2; $x = 3; echo $x;")]
    (is (= :unit (get-in a [:unit :kind])))
    (is (= ["$x" "$y"] (get-in a [:unit :assigned])))
    (is (= {} (:scopes a)))))


(deftest function-scope-test
  (let [s (function-scope "<?php function f($a, $b) { static $n = 0; global $g; $c = 1; return $this; }")]
    (is (= :function (:kind s)))
    (is (= ["$a" "$b"] (:params s)))
    (is (= ["$n"] (:statics s)))
    (is (= ["$g"] (:globals s)))
    (is (= ["$a" "$b" "$n" "$c"] (:assigned s)))
    (is (true? (:this? s)))
    (is (false? (:dynamic? s)))))


(deftest nested-function-does-not-leak-test
  (let [a (analysis "<?php $x = 1; function f() { $y = 2; } $z = 3;")]
    (is (= ["$x" "$z"] (get-in a [:unit :assigned])))
    (is (= ["$y"] (:assigned (first (vals (:scopes a))))))))


(deftest dynamic-variable-test
  (is (true? (:dynamic? (function-scope "<?php function f() { $$a = 1; }")))))

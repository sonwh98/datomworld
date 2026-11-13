(ns yang.php.antlr.eval-scope-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [yang.core.prelude :as core-prelude]
            [yang.php.antlr.cst :as cst]
            [yang.php.antlr.e2e-harness :as h]
            [yang.php.antlr.lower :as lower]))


(deftest dynamic-scope-with-no-static-cvs-is-released
  (let [body (lower/lower-module-body (cst/parse-source "<?php function f(){ extract([]); }"))]
    (is (contains? (core-prelude/free-names body) 'php/scope-release!))))


(deftest generator-dynamic-scope-is-released
  (let [body (lower/lower-module-body (cst/parse-source "<?php function f(){ extract([]); yield 1; }"))]
    (is (contains? (core-prelude/free-names body) 'php/scope-release!))))


(deftest generator-releases-dynamic-bindings
  ;; :oracle :pending -- verify generator destruction order with PHP 8.3.
  (let [source "<?php class C { function __destruct(){ echo 'D'; } } function f($a){ extract($a); yield 1; } $a=['o'=>new C]; $g=f($a); $g->current(); unset($a); unset($g); echo 'Z';"
        results (h/run-php source)]
    (doseq [backend [:ast-walker :semantic :stack :register]]
      (is (= "DZ" (get results backend)) (str backend " " (pr-str results))))))

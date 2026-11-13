(ns yang.php.antlr.eval-scope-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [yang.core.prelude :as core-prelude]
            [yang.php.antlr.cst :as cst]
            [yang.php.antlr.lower :as lower]))


(deftest dynamic-scope-with-no-static-cvs-is-released
  (let [body (lower/lower-module-body (cst/parse-source "<?php function f(){ extract([]); }"))]
    (is (contains? (core-prelude/free-names body) 'php/scope-release!))))

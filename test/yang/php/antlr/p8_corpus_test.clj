(ns yang.php.antlr.p8-corpus-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p8 :as p8]))


(deftest p8-programs-use-php-semantics-test
  (doseq [{:keys [id source out]} p8/fixtures]
    (testing (name id)
      (is (= out (h/run-walker source))))))

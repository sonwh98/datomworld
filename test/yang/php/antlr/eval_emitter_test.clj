(ns yang.php.antlr.eval-emitter-test
  (:require [clojure.test :refer [deftest is]]
            [dao.host.text :as text]
            [yang.core.prelude :as core-prelude]
            [yang.php.antlr.eval-coordinator :as coordinator]
            [yang.php.antlr.lower :as lower]))


(defn- packet
  [source]
  (:cst (coordinator/compile-request
          (coordinator/make-worker)
          {:yang.compile/unit :eval
           :yang.php/source (text/units->string (map #(bit-and % 255) (text/utf8-encode source)))})))


(deftest eval-entry-borrows-runtime-and-scope
  (let [entry (:ast (lower/eval-entry (packet "$x = 2; return $x;")))
        free (core-prelude/free-names entry)]
    (is (= :lambda (:type entry)))
    (is (= '[%runtime %scope %ctx %hooks] (:params entry)))
    (is (not-any? #(= "php" (namespace %)) free))
    (is (not (contains? free '%scope)))
    (is (not (contains? free '%ctx)))))


(deftest eval-entry-binds-derived-hooks
  (let [entry (:ast (lower/eval-entry (packet "while (false) {}")))
        params (into #{} (mapcat :params) (filter #(and (map? %) (= :lambda (:type %)))
                                                  (tree-seq coll? seq entry)))]
    (is (= '%eval-loop (:loop lower/eval-hook-profile)))
    (is (= '%eval-call (get-in lower/eval-hook-profile [:call :hook])))
    (is (= '%eval-line (get-in lower/eval-hook-profile [:line :hook])))
    (is (every? params '[%eval-loop %eval-call %eval-line]))))


(deftest runtime-environment-uses-prelude-inventory
  (let [free (core-prelude/free-names (lower/eval-runtime-node))]
    (is (contains? free 'php/dict-get))
    (is (contains? free 'php/call-ec))
    (is (not-any? #(= "php.rt" (namespace %)) free))))

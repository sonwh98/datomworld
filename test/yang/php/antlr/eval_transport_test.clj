(ns yang.php.antlr.eval-transport-test
  (:require [clojure.test :refer [deftest is]]
            [yang.php.antlr.eval-transport :as transport]
            [yang.php.antlr.eval-coordinator :as coordinator]))


(def request
  {:yang.compile/request [:caller 1]
   :yang.compile/unit :unit
   :yang.compile/frontend-revision :frontend-v1
   :yang.compile/compiler-revision :compiler-v1
   :yang.compile/goal :eval
   :yang.compile/provenance {:path "caller.php"}
   :yang.compile/options {}
   :yang.php/source "return 1;"})


(deftest sealed-request-boundary
  (is (nil? (transport/request-defect request)))
  (is (= :unknown-field (transport/request-defect (assoc request :scope {}))))
  (is (= :missing-field (transport/request-defect (dissoc request :yang.compile/request))))
  (is (= :wrong-goal (transport/request-defect (assoc request :yang.compile/goal :file))))
  (is (= :non-portable-data (transport/request-defect
                              (assoc-in request [:yang.compile/options :handle] (Object.))))))


(deftest coordinator-consumes-sealed-requests
  (let [worker (coordinator/make-worker)
        prepared (coordinator/compile-transport-request worker request)
        refused (coordinator/compile-transport-request worker (assoc request :scope {}))]
    (is (= :yang.cst/ok (get-in prepared [:cst :yang.cst/outcome])))
    (is (= :yang.compile/rejected (:yang.compile/outcome refused)))
    (is (= :yang.compile/invalid-request (:yang.compile/reason refused)))
    (is (= :unknown-field (:yang.compile/detail refused)))
    (is (= [:caller 1] (:yang.compile/request refused)))
    (is (not (contains? refused :scope)))))

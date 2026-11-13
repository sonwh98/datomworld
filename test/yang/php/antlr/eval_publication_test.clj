(ns yang.php.antlr.eval-publication-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing.mem :as mem]
            [dao.host.text :as text]
            [yang.php.antlr.e2e-harness :as harness]
            [yang.php.antlr.eval-coordinator :as coordinator]
            [yang.php.antlr.eval-publication :as publication]
            [yang.php.antlr.siteset :as siteset]))


(deftest publication-preserves-canonical-sites
  (let [worker (coordinator/make-worker)
        request {:yang.compile/request :r :yang.compile/unit :eval
                 :yang.php/source (text/units->string (text/utf8-encode "while (false) {}"))}
        prepared (coordinator/compile-request worker request)
        store (mem/create-content-mem)
        result (publication/publish! store (harness/host-registry) 'eval.unit prepared)]
    (is (= :ok (:status result)))
    (is (= (:identity prepared) (:identity result)))
    (is (seq (:set (siteset/verify store (:address result) (get-in result [:sites :record-address])))))))

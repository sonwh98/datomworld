(ns yang.call.exception-source-test
  "Generator checks: refusals here are fixture defects, never runtime reds."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-legs :as legs]))


(defn- lower
  [frontend source exports]
  (let [{:keys [parse unit]} (get kit/frontends frontend)]
    (unit (parse source) exports)))


(deftest provenance-source-compilation-test
  (doseq [[frontend source] contract/sources]
    (testing (name frontend)
      (is (map? (lower frontend source [])))))
  (is (map? (lower :python contract/boundary-source []))))


(deftest all-directed-pair-sources-compile-test
  (doseq [frontend (sort contract/frontends)]
    (testing (str "donor " frontend)
      (is (map? (lower frontend (legs/donor-source frontend) [])))))
  (doseq [{:keys [id donor caller scenario]} contract/pair-matrix]
    (testing (str id)
      (is (map? (lower caller (legs/caller-source caller scenario)
                       (legs/donor-exports donor))))))
  (doseq [[[donor caller :as direction] pair-legs]
          (group-by (juxt :donor :caller) contract/pair-matrix)]
    (testing (str "batch " direction)
      (is (map? (lower caller (legs/batch-caller-source caller pair-legs)
                       (legs/donor-exports donor)))))))


(deftest clojure-restricted-catch-surface-preserved-test
  (is (thrown? clojure.lang.ExceptionInfo
        (lower :clojure "(try 1 (finally 2))" [])))
  (is (thrown? clojure.lang.ExceptionInfo
        (lower :clojure "(try 1 (catch Throwable e 2))" []))))

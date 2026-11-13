(ns yang.call.exception-source-test
  "Source admission checks and focused execution pins for Clojure finally."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-legs :as legs]
            [yang.call.exception-probes :as probes]))


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
  ;; Still refused: the admitted catch surface is one Exception/ExceptionInfo
  ;; catch. Retiring the `finally` refusal does not widen the catch classes.
  (is (thrown? clojure.lang.ExceptionInfo
        (lower :clojure "(try 1 (catch Throwable e 2))" []))))


(deftest clojure-finally-admitted-test
  ;; Owner ruling 1794556000000 retired the refusal: a dialect's surface
  ;; derives from its source language. Execution is pinned separately below.
  (is (map? (lower :clojure "(try 1 (finally 2))" []))))


(deftest ^:slow runtime-payload-identity-test
  ;; PHP seeds after unit shutdown; the other frontends retain their
  ;; module-created payloads. Check the complete original expectations in
  ;; the shared batch, including all nine repaired PHP identity observations.
  (doseq [frontend (sort contract/frontends)]
    (let [observations (probes/run-frontend frontend)]
      (doseq [{:keys [id expected]}
              (filter #(= frontend (:frontend %)) contract/fixtures)]
        (testing (str id)
          (let [results (get observations id)]
            (is (= #{:register :semantic :stack} (set (keys results))))
            (doseq [[backend actual] results]
              (is (= expected actual) (str backend)))))))))


(deftest ^:slow clojure-finally-normal-exit-test
  ;; Clojure discards the finally value: preserve body value 1 and execute
  ;; cleanup exactly once. Observe through the established public-entry kit.
  (let [donor (kit/publish-donor! :clojure "")
        source (str "(def cleaned (cell/new 0))\n"
                    "(def result (try 1 (finally (cell/set! cleaned (+ (cell/get cleaned) 1)) 2)))\n"
                    "(defn body_value [] result)\n"
                    "(defn cleanup_count [] (cell/get cleaned))\n")
        call #(kit/boundary-call-form (kit/export-form :clojure %) [] [])
        observation
        (kit/guard-exceptions-form
          [:clojure]
          (list 'let ['body (call "body_value") 'cleanup (call "cleanup_count")]
                (kit/observation-map-form
                  {:body-route (list 'get 'body :route)
                   :value (list 'get 'body :value)
                   :cleanup-route (list 'get 'cleanup :route)
                   :cleanup-count (list 'get 'cleanup :value)})))
        results (kit/run-exception-program
                  donor (kit/compose-exception donor [] :clojure source observation))]
    (is (= #{:register :semantic :stack} (set (keys results))))
    (doseq [[backend actual] results]
      (is (= {:body-route :return :value 1 :cleanup-route :return :cleanup-count 1}
             actual) (str backend)))))


(deftest ^:slow clojure-finally-replacement-leg-test
  ;; Live for the first time now that the pair-matrix exclusion is gone.
  ;; Pair deftests are pair-scale; this pin also exposes a single leg to -v.
  (let [leg (first (filter #(= [:python :clojure :finally-replacement] (:id %))
                           contract/pair-matrix))]
    (is (some? leg))
    (doseq [[backend actual] (legs/run-leg leg)]
      (is (= (legs/expected leg) actual) (str backend)))))

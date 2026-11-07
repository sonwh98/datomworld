(ns yang.python.antlr.exception-call-test
  "Round 0: six directed Python-containing pairs, compliant assertions.
   JS/PHP/Clojure-only pairs remain explicitly pending in the shared matrix.
   A red result is a runtime divergence, never the expected test value."
  (:require [clojure.test :refer [deftest is testing do-report]]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-legs :as legs]))


(defn- check-pair
  [donor caller]
  (let [observations (legs/run-pair donor caller)]
    (doseq [{:keys [id scenario] :as leg}
            (filter #(and (= donor (:donor %)) (= caller (:caller %)))
                    contract/pair-matrix)]
      (testing (str id)
        (try
          (let [expected (legs/expected leg)
                results (get observations id)]
            (println "EXCEPTION-PAIR"
                     (pr-str {:id id :gates (:gates leg) :frontends (:frontends leg)
                              :expected expected
                              :results (update-vals results #(if (instance? Throwable %)
                                                               {:host/error (.getMessage ^Throwable %)
                                                                :host/data (ex-data %)} %))
                              :status (update-vals results #(cond (instance? Throwable %) :error
                                                                  (= expected %) :green :else :red))}))
            (doseq [[backend actual] results]
              (if (instance? Throwable actual)
                (do-report {:type :error :message (str id " " backend " operational failure")
                            :expected expected :actual actual})
                (is (= expected actual) (str donor " -> " caller " " scenario " " backend)))))
          (catch Exception e
            (do-report {:type :error :message (str id " source leg failed to execute")
                        :expected (legs/expected leg) :actual e})))))))


(deftest ^{:gates #{::contract/r1 ::contract/r2} :frontends #{:python :typescript}}
  python-to-typescript-test (check-pair :python :typescript))


(deftest ^{:gates #{::contract/r1 ::contract/r2} :frontends #{:python :typescript}}
  typescript-to-python-test (check-pair :typescript :python))


(deftest ^{:gates #{::contract/r1 ::contract/r2} :frontends #{:python :php}}
  python-to-php-test (check-pair :python :php))


(deftest ^{:gates #{::contract/r1 ::contract/r2} :frontends #{:python :php}}
  php-to-python-test (check-pair :php :python))


(deftest ^{:gates #{::contract/r1 ::contract/r2} :frontends #{:python :clojure}}
  python-to-clojure-test (check-pair :python :clojure))


(deftest ^{:gates #{::contract/r1 ::contract/r2} :frontends #{:python :clojure}}
  clojure-to-python-test (check-pair :clojure :python))

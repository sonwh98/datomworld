(ns yang.call.exception-contract-test
  "Intentionally red until exception rounds 1–4 repair the runtimes. Never
   invert assertions or encode today's divergences as expected behavior."
  (:require [clojure.test :refer [deftest is testing do-report]]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-probes :as probes]))


(defn- check-frontend
  [frontend]
  (let [observations (probes/run-frontend frontend)]
    (doseq [{:keys [id expected] :as fixture}
            (filter #(= frontend (:frontend %)) contract/fixtures)]
      (testing (str id " gates=" (:gates fixture))
        (try
          (let [results (get observations id)]
            (println "PROVENANCE"
                     (pr-str {:id id :gates (:gates fixture) :frontends (:frontends fixture)
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
                (is (= expected actual) (str id " " backend)))))
          (catch Exception e
            (do-report {:type :error :message (str id " boundary fixture failed to execute")
                        :expected expected :actual e})))))))


(deftest ^{:slow true :gates #{::contract/r1 ::contract/r2 ::contract/r3 ::contract/r5}
           :frontends #{:python}}
  python-provenance-test
  (check-frontend :python))


(deftest ^{:slow true :gates #{::contract/r1 ::contract/r2 ::contract/r3 ::contract/r5}
           :frontends #{:php}}
  php-provenance-test
  (check-frontend :php))


(deftest ^{:slow true :gates #{::contract/r1 ::contract/r3 ::contract/r5}
           :frontends #{:typescript}}
  typescript-provenance-test
  (check-frontend :typescript))


(deftest ^{:slow true :gates #{::contract/r1 ::contract/r3 ::contract/r5}
           :frontends #{:clojure}}
  clojure-provenance-test
  (check-frontend :clojure))

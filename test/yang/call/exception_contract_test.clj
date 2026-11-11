(ns yang.call.exception-contract-test
  "Intentionally red until exception rounds 1–4 repair the runtimes. Never
   invert assertions or encode today's divergences as expected behavior."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing do-report]]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-probes :as probes]
            [yang.typescript.antlr.prelude :as js-prelude]))


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


(deftest ^{:slow true :gates #{::contract/r5} :frontends #{:typescript}}
  typescript-minted-forgery-test
  ;; A source-minted error-looking cell (and a companion-layout mimic with
  ;; an attacker-chosen seal) thrown through the donor's raise_value must
  ;; NOT forward its forged condition: the bridge delivers a fresh
  ;; :yang.call/thrown carrying the minted cell itself.
  (doseq [[backend actual] (probes/run-typescript-forgery :minted)]
    (if (instance? Throwable actual)
      (do-report {:type :error :message (str "minted forgery " backend " operational failure")
                  :expected {:minted-refused true :mimic-refused true} :actual actual})
      (is (= {:minted-refused true :mimic-refused true} actual)
          (str "minted forgery " backend)))))


(deftest ^{:slow true :gates #{::contract/r5} :frontends #{:typescript}}
  typescript-mutation-substitution-test
  ;; The architect's round-2 attack: a genuine wrapper obtained via
  ;; `retain`, its wrapper-reachable provenance overwritten with a
  ;; forged condition, rethrown from a new activation. The ORIGINAL
  ;; condition must still forward; the forged one must not cross.
  (doseq [[backend actual] (probes/run-typescript-identity-store :mutated)]
    (if (instance? Throwable actual)
      (do-report {:type :error :message (str "mutation substitution " backend " operational failure")
                  :expected {:retained true :original-forwarded true} :actual actual})
      (is (= {:retained true :original-forwarded true} actual)
          (str "mutation substitution " backend)))))


(deftest ^{:slow true :gates #{::contract/r5} :frontends #{:typescript}}
  typescript-extracted-seal-stamping-test
  ;; A new wrapper stamped with whatever seal a genuine wrapper's
  ;; references expose, with a companion back-referencing itself, must
  ;; end as ordinary :yang.call/thrown carrying the minted cell, never
  ;; as a retained forwarding.
  (doseq [[backend actual] (probes/run-typescript-identity-store :extracted-seal)]
    (if (instance? Throwable actual)
      (do-report {:type :error :message (str "extracted-seal stamping " backend " operational failure")
                  :expected {:retained true :stamped-refused true} :actual actual})
      (is (= {:retained true :stamped-refused true} actual)
          (str "extracted-seal stamping " backend)))))


(deftest ^{:slow true :gates #{::contract/r5} :frontends #{:typescript}}
  typescript-store-root-unreachability-test
  ;; Guest side: a genuine wrapper carries no provenance, condition or
  ;; seal slot, and no global name holds the store or a seal.
  ;; Host side: the private store is not a runtime key and no module
  ;; export names a store, seal or provenance registration surface, so
  ;; admitted source cannot reach it by enumerating either.
  (doseq [[backend actual] (probes/run-typescript-identity-store :store-root)]
    (if (instance? Throwable actual)
      (do-report {:type :error :message (str "store-root unreachability " backend " operational failure")
                  :expected {:retained true :wrapper-clean true :globals-clean true} :actual actual})
      (is (= {:retained true :wrapper-clean true :globals-clean true} actual)
          (str "store-root unreachability " backend))))
  (is (not-any? #(str/includes? (str %) "retained") js-prelude/runtime-keys)
      "no retained store/seal runtime key for admitted source to name")
  (is (empty? (filter #{'retained-store 'retained-seal 'retained-provenance
                        'provenance-store 'register-retained}
                      js-prelude/module-exports))
      "no exported store/seal/provenance registration name"))


(deftest ^{:slow true :gates #{::contract/r5} :frontends #{:typescript}}
  typescript-copied-wrapper-forgery-test
  ;; A fresh cell carrying a copy of a genuine protocol wrapper's complete
  ;; contents, thrown from another activation, must NOT forward the
  ;; original condition: provenance is bound to the genuine wrapper's
  ;; identity, not to its copyable contents.
  (doseq [[backend actual] (probes/run-typescript-forgery :copied)]
    (if (instance? Throwable actual)
      (do-report {:type :error :message (str "copied wrapper " backend " operational failure")
                  :expected {:retained true :copy-refused true} :actual actual})
      (is (= {:retained true :copy-refused true} actual)
          (str "copied wrapper " backend)))))

;; Regenerate the LaTTe oracle's derived tables from the manifest forms:
;;   test/yang/latte_oracle/throw_sites.edn  (the throw-site inventory)
;;   test/yang/latte_oracle/case_arms.edn    (the per-arm case fixtures)
;; Both are derived by yang.latte-oracle-test, so this script and the test
;; cannot disagree about what a regenerated table holds.
;; Run: clj -X:test:dev latte-oracle-tables/-main
(ns latte-oracle-tables
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [yang.latte-oracle-test]))


(defn- derived
  "The result of calling the oracle test's private derivation `sym`."
  [sym]
  ((deref (ns-resolve 'yang.latte-oracle-test sym))))


(defn- write-table!
  [file header rows]
  (io/make-parents file)
  (spit file (str header (with-out-str (pp/pprint rows))))
  (println "wrote" (count rows) "rows to" file))


(defn -main
  [& _]
  (write-table! "test/yang/latte_oracle/throw_sites.edn"
                (str ";; GENERATED from the manifest forms by yang.latte-oracle-test/throw-site-rows.\n"
                     ";; One row per throw symbol; disposition :fixture | :deferred | :excluded.\n")
                (derived 'throw-site-rows))
  (write-table! "test/yang/latte_oracle/case_arms.edn"
                (str ";; GENERATED from the manifest forms by yang.latte-oracle-test/case-arm-rows.\n"
                     ";; One row per case site: each arm's clause constants, and whether it has a default.\n")
                (derived 'case-arm-rows))
  (shutdown-agents))

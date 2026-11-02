;; Regenerate the LaTTe oracle's derived tables from the manifest forms:
;;   test/yang/latte_oracle/throw_sites.edn  (the throw-site inventory)
;;   test/yang/latte_oracle/case_arms.edn    (the per-arm case fixtures)
;; Both are derived by yang.latte-oracle-test, so this script and the test
;; cannot disagree about what a regenerated table holds.
;; Run: clj -X:test:dev latte-oracle-tables/-main
(ns latte-oracle-tables
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
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


(def ^:private exclusions
  {'latte_kernel.utils/nano-time "Reads the host monotonic clock; no portable clock capability is installed."
   'latte_kernel.unparser/show-term "Captures host output and invokes clojure.pprint; formatting is host-local."
   'latte_kernel.proof/show-term "Invokes the excluded host pprint/output formatter."
   'latte_kernel.proof/show-def "Invokes the excluded host pprint/output formatter."
   'latte_kernel.proof/elab-print-term "Writes directly to host stdout; no portable output stream is supplied."
   'latte_kernel.proof/elab-print-type "Writes directly to host stdout; no portable output stream is supplied."
   'latte_kernel.proof/elab-print-def "Writes directly to host stdout; no portable output stream is supplied."
   'latte_kernel.proof/elab-print-defenv "Writes directly to host stdout; no portable output stream is supplied."
   'latte_kernel.proof/print-state "Writes directly to host stdout; no portable output stream is supplied."})


(defn- admission-rows
  []
  (mapv (fn [{:keys [ns name] :as row}]
          (let [excluded (get exclusions (symbol (str ns) (str name)))]
            (assoc row :source (str (str/replace (str ns) "." "/") ".cljc")
                   :disposition (if excluded :excluded :admitted)
                   :admission-reason
                   (or excluded
                       "Guest source and explicit immutable context adapters over data/* and the declared kind/* host seams."))))
        (edn/read-string (slurp "test/yang/latte_oracle/manifest.edn"))))


(defn -main
  [& _]
  (write-table! "test/yang/latte_oracle/manifest.edn"
                ";; GENERATED admission inventory; each binding is :admitted or :excluded with a reason.\n"
                (admission-rows))
  (write-table! "test/yang/latte_oracle/throw_sites.edn"
                (str ";; GENERATED from the manifest forms by yang.latte-oracle-test/throw-site-rows.\n"
                     ";; One row per throw symbol; disposition :fixture | :deferred | :excluded.\n")
                (derived 'throw-site-rows))
  (write-table! "test/yang/latte_oracle/case_arms.edn"
                (str ";; GENERATED from the manifest forms by yang.latte-oracle-test/case-arm-rows.\n"
                     ";; One row per case site: each arm's clause constants, and whether it has a default.\n")
                (derived 'case-arm-rows))
  (shutdown-agents))

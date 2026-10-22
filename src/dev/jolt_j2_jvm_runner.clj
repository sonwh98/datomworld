;; The J2 JVM counts baseline runner (bb test:jolt-j2-jvm), run UNDER the
;; JVM by src/dev/jolt_j2_lane.clj (jvm mode):
;;
;;   java -cp <-A:test:dev classpath> clojure.main src/dev/jolt_j2_jvm_runner.clj NS...
;;
;; src/dev/jolt_test_runner.clj reads jolt's t/counters atom, which the JVM
;; clojure.test does not have; this runner asks clojure.test itself instead:
;; clojure.test/run-tests per namespace, in one process, whose answer is that
;; namespace's own {:test :pass :fail :error} counters. Every test var runs,
;; ^:slow included, as on jolt (owner ruling Q3).
;;
;; It prints the jolt runner's line protocol, so the lane driver parses both
;; the same way: one "NS <ns> tests N assertions M pass P fail F error E"
;; line per loaded namespace (assertions = pass + fail + error), a
;; "LOAD-FAILURE <ns> -- <message>" line per namespace that does not load
;; (the run goes on), then "SUMMARY {...}". The exit status is nonzero when
;; any load failure, failure or error happened.
(ns jolt-j2-jvm-runner
  (:require
    [clojure.test :as t]))


(defn- run-ns
  "Run namespace `ns-sym`; answers {:ns :load-error :counts}."
  [ns-sym]
  (let [load-error (try (require ns-sym) nil (catch Throwable e e))]
    (if load-error
      {:ns ns-sym :load-error (str (ex-message load-error)) :counts {}}
      {:ns ns-sym :load-error nil
       :counts (select-keys (t/run-tests ns-sym) [:test :pass :fail :error])})))


(defn- assertions
  [c]
  (+ (:pass c 0) (:fail c 0) (:error c 0)))


(defn- print-result
  [{:keys [ns load-error counts]}]
  (if load-error
    (println "LOAD-FAILURE" ns "--" load-error)
    (println "NS" ns
             "tests" (:test counts 0)
             "assertions" (assertions counts)
             "pass" (:pass counts 0)
             "fail" (:fail counts 0)
             "error" (:error counts 0)))
  (flush))


(defn run
  "Run `ns-syms`; answers the summary map."
  [ns-syms]
  (let [results (mapv (fn [ns-sym]
                        (doto (run-ns ns-sym) print-result))
                      ns-syms)
        loaded (remove :load-error results)
        total (fn [k] (reduce + (map #(get-in % [:counts k] 0) loaded)))
        summary {:namespaces (count ns-syms)
                 :load-failures (count (filter :load-error results))
                 :tests (total :test)
                 :assertions (reduce + (map #(assertions (:counts %)) loaded))
                 :pass (total :pass)
                 :fail (total :fail)
                 :error (total :error)}]
    (println "SUMMARY" (pr-str summary))
    summary))


(let [args *command-line-args*
      {:keys [load-failures fail error]} (run (mapv symbol args))]
  (shutdown-agents)
  (System/exit (if (and (seq args) (zero? (+ load-failures fail error))) 0 1)))

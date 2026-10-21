;; The jolt test runner (yin.repl-jolt-implementation.md §11; J1), run UNDER
;; jolt:
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj NS...
;;   "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj --self-test
;;
;; How it finds the tests: it does not scan. The namespaces are the explicit
;; command-line list (bb test:jolt-host passes the four dao.host oracle
;; namespaces), and each is loaded by `require` through jolt's own source-root
;; search, which the aliases set: :jolt replaces the paths with src/jolt,
;; src/cljc, src/clj (src/jolt first, so a host override there wins) and
;; :jolt-test adds test (the oracle) and src/dev (this runner and its
;; sentinel). jolt resolves :extra-paths roots BEFORE the :replace-paths
;; ones (`jolt -Srepro -Spath -A:jolt:jolt-test`: test, src/dev, src/jolt,
;; src/cljc, src/clj); neither test nor src/dev holds a dao/host source, so
;; the override order is unaffected. Fixture files the tests read
;; (test/resources/...) are relative to the working directory, the
;; repository root.
;;
;; Per namespace: a load failure is counted and reported, and the run goes on.
;; Per test var, in source order: each runs through clojure.test/test-var,
;; which reports an escaping throw as an :error, inside a second guard of this
;; runner's own, so a throwing test never aborts the batch. Totals are tests,
;; assertions (pass + fail + error), failures, errors and load failures; the
;; exit status is nonzero when any of the last three is.
;;
;; --self-test runs the sentinel namespace jolt-runner-sentinel (a passing
;; test, a test that throws, then a passing test) and exits 0 only when the
;; runner counted exactly that: 3 tests, 1 error, and the test after the
;; throwing one ran.
(ns jolt-test-runner
  (:require
    [clojure.test :as t]))


(defn- tally
  []
  (select-keys @t/counters [:test :pass :fail :error]))


(defn- delta
  [before after]
  (merge-with - after before))


(defn- test-vars-of
  "The test vars of namespace `ns-sym` in source order."
  [ns-sym]
  (->> (vals (ns-interns ns-sym))
       (filter #(:test (meta %)))
       (sort-by #(or (:line (meta %)) 0))))


(defn- run-ns
  "Run namespace `ns-sym`; answers {:ns :load-error :counts :tests}."
  [ns-sym]
  (let [loaded (try
                 (require ns-sym)
                 nil
                 (catch Throwable e e))]
    (if loaded
      {:ns ns-sym :load-error (str (ex-message loaded)) :counts {} :tests []}
      (let [before (tally)
            per-test (mapv (fn [v]
                             (let [b (tally)]
                               (try
                                 (t/test-vars [v])
                                 (catch Throwable e
                                   ;; test-var already guards; this is the
                                   ;; runner's own floor
                                   (t/do-report {:type :error
                                                 :message "escaped test-vars"
                                                 :expected nil :actual e})))
                               [(:name (meta v)) (delta b (tally))]))
                           (test-vars-of ns-sym))]
        {:ns ns-sym
         :load-error nil
         :counts (delta before (tally))
         :tests per-test}))))


(defn- assertions
  [c]
  (+ (:pass c 0) (:fail c 0) (:error c 0)))


(defn- print-result
  [{:keys [ns load-error counts tests]}]
  (if load-error
    (println "LOAD-FAILURE" ns "--" load-error)
    (do
      (doseq [[n c] tests]
        (println (cond (pos? (:error c 0)) "ERROR"
                       (pos? (:fail c 0)) "FAIL "
                       :else "ok   ")
                 (str ns "/" n)
                 (pr-str (select-keys c [:pass :fail :error]))))
      (println "NS" ns
               "tests" (count tests)
               "assertions" (assertions counts)
               "pass" (:pass counts 0)
               "fail" (:fail counts 0)
               "error" (:error counts 0)))))


(defn run
  "Run `ns-syms`; answers the summary map."
  [ns-syms]
  (t/reset-report!)
  (let [results (mapv run-ns ns-syms)
        loaded (remove :load-error results)
        total (fn [k] (reduce + (map #(get-in % [:counts k] 0) loaded)))
        summary {:namespaces (count ns-syms)
                 :load-failures (count (filter :load-error results))
                 :tests (reduce + (map #(count (:tests %)) loaded))
                 :assertions (reduce + (map #(assertions (:counts %)) loaded))
                 :pass (total :pass)
                 :fail (total :fail)
                 :error (total :error)}]
    (run! print-result results)
    (doseq [line (t/failures)]
      (println "DETAIL" line))
    (println "SUMMARY" (pr-str summary))
    (assoc summary :results results)))


(defn- self-test
  []
  (let [{:keys [tests error results]} (run ['jolt-runner-sentinel])
        names (mapv first (:tests (first results)))
        after-ran? (= '[before-the-throw throws after-the-throw] names)
        ok? (and (= 3 tests) (= 1 error) after-ran?
                 (pos? (get-in (first results) [:tests 2 1 :pass] 0)))]
    (println (if ok? "SELF-TEST PASS" "SELF-TEST FAIL")
             "a throwing test is one error and the batch continues"
             (pr-str {:tests tests :error error :order names}))
    (if ok? 0 1)))


(let [args *command-line-args*]
  (System/exit
    (if (= ["--self-test"] (vec args))
      (self-test)
      (let [{:keys [load-failures fail error]} (run (mapv symbol args))]
        (if (and (seq args) (zero? (+ load-failures fail error))) 0 1)))))

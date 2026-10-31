;; One-off timing driver: per namespace, run the quick tier (-e :slow) and the
;; slow tier (-i :slow), one JVM at a time, foreground, logging each run.
;; Usage: bb collab/tiers_run.clj <mode>   mode = main | python-slow
(require '[babashka.process :as p]
         '[clojure.string :as str]
         '[babashka.fs :as fs])


(def all-ns
  ["dao.space.dht-test" "yang.clojure.stream-eval-test"
   "yang.python.antlr.e2e-c1-test" "yang.python.antlr.e2e-c2-test"
   "yang.python.antlr.e2e-test" "yang.python.antlr.int-conv-test"
   "yang.python.antlr.int-literal-test" "yang.python.antlr.int-ops-parser-test"
   "yang.python.antlr.int-ops-test" "yang.python.antlr.prelude-parity-test"
   "yang.python.antlr.safepoint-test" "yin.repl.dht-head-test" "yin.repl.dht-test"
   "yin.repl.main-test" "yin.repl.python-antlr-test" "yin.repl.query-test"
   "yin.vm.linker.dht-end-to-end-test" "yin.vm.ucf.compose-rows-test"
   "yin.vm.ucf.holder.driver-test" "yin.vm.ucf.safepoint-harness-test"
   "yin.vm.ucf.stage-e-process-test" "yin.vm.ucf.stage-e-rows-test"])


(fs/create-dirs "collab/tiers-runs")


(defn run!
  [ns tier]
  (let [flag (if (= tier "quick") "-e" "-i")
        log (str "collab/tiers-runs/" ns "." tier ".log")
        t0 (System/nanoTime)
        r @(p/process {:out :string :err :out} "clojure" "-M:test" flag ":slow" "-n" ns)
        secs (/ (- (System/nanoTime) t0) 1e9)
        out (:out r)
        ran (or (re-find #"Ran \d+ tests containing \d+ assertions\." out) "NO-RAN-LINE")
        fails (or (re-find #"\d+ failures, \d+ errors\." out) "NO-RESULT-LINE")
        line (format "%s\t%s\t%.1f\texit=%d\t%s\t%s" ns tier secs (:exit r) ran fails)]
    (spit log out)
    (spit "collab/tiers-runs/summary.tsv" (str line "\n") :append true)
    (println line)
    (flush)))


(case (first *command-line-args*)
  "main" (doseq [ns all-ns
                 tier (if (= ns "yin.repl.python-antlr-test") ["quick"] ["quick" "slow"])]
           (run! ns tier))
  "python-slow" (run! "yin.repl.python-antlr-test" "slow"))

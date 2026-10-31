;; Reviewer's mutant harness for scan.cljc (phase 5c adversarial review).
;; Run: clojure -M:test collab/5c-review-mutations.clj
;; Backup is taken and verified FIRST; every mutant restores from it in `finally`.
(require '[clojure.test :as t]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         'yin.vm.semantic-register.scan-test
         'yin.vm.linker-test)


(def source "src/cljc/yin/vm/semantic_register/scan.cljc")
(def backup "collab/5c-review-scan.cljc.backup")
(io/copy (io/file source) (io/file backup))
(assert (= 0 (:exit (shell/sh "cmp" source backup))) "backup verified")
(def original (slurp backup))


(def fast-tests
  '[positions-are-the-v4-table free-occurrences-use-the-owner-tree definitions-and-sites
    definitions-respect-conditional-ranges segment-rows-carry-a-and-pc
    footprint-mnemonics-is-the-v3-table-minus-push malformed-vectors-throw
    scanners-agree-on-b0 scanners-agree-on-the-register-corpus])


(def mutations
  [["R1 store-put key read as rd" "[$code _ _ :store-put _ ?key _]" "[$code _ _ :store-put ?key _ _]"]
   ["R2 define key read as rd" "[$code _ _ :define _ ?key _]" "[$code _ _ :define ?key _ _]"]
   ["R3 resume pid read as value reg" "[$code _ _ :resume ?pid _]" "[$code _ _ :resume _ ?pid]"]
   ["R4 free-occurrence order reversed" "{:name name :at pc :in-body? (pos? i)})))) v))))" "{:name name :at pc :in-body? (pos? i)})))) v)) reverse vec)"]
   ["R5 owner chain stops at immediate owner" "(cons (nth (nth v owner) 2) (chain v bs (code/body-index bs owner)))" "[(nth (nth v owner) 2)]"]
   ["R6 var name read from slot 1 (register id)" "(let [name (nth t 2) i (code/body-index bs pc)]" "(let [name (nth t 1) i (code/body-index bs pc)]"]
   ["R7 define counted as application site" "#{:call :ffi-call}" "#{:call :ffi-call :define}"]
   ["R8 yin/def guard removed" "(not (vm/reserved-name? name))" "true"]
   ["R9 footprint table without the :push dissoc" "(dissoc (get-in vm/footprint-table [vm/semantic-contract :mnemonics]) :push)" "(get-in vm/footprint-table [vm/semantic-contract :mnemonics])"]
   ["R10 definition position pinned to 0 (use-before-definition not refused)" "{:name (nth t 2) :at pc" "{:name (nth t 2) :at 0"]
   ["R11 free-occurrence pc off by one" ":at pc :in-body? (pos? i)" ":at (inc pc) :in-body? (pos? i)"]
   ["R12 segment rows swap A and pc" "(into [a pc] t)" "(into [pc a] t)"]
   ["R13 in-body? inverted" ":in-body? (pos? i)" ":in-body? (zero? i)"]
   ["R14 conditional? ignores body index" "(pos? (code/body-index bs pc))" "false"]
   ["R15 store-put dropped from definitions" "#{:store-put :define}" "#{:define}"]
   ["R16 malformed check removed from bodies" "(when-let [defect (code/well-formed? v)]\n    (throw (ex-info \"Cannot scan malformed semantic register vector\" defect)))" ""]])


(defn run-tests
  [names]
  (binding [t/*report-counters* (ref t/*initial-report-counters*)
            t/*test-out* (java.io.StringWriter.)]
    (doseq [n names]
      (t/test-vars [(ns-resolve 'yin.vm.semantic-register.scan-test n)]))
    @t/*report-counters*))


(defn failing-tests
  [names]
  (vec (keep (fn [n]
               (let [c (run-tests [n])]
                 (when (pos? (+ (:fail c) (:error c)))
                   [n (select-keys c [:fail :error])])))
             names)))


(println "BASELINE" (run-tests fast-tests))


(doseq [[label before after] mutations]
  (let [n (count (re-seq (re-pattern (java.util.regex.Pattern/quote before)) original))]
    (assert (= 1 n) (str label " pattern count " n)))
  (try
    (spit source (str/replace original before after))
    (let [loaded (try (load-file source) :ok
                      (catch Throwable e (str "COMPILE-ERROR " (.getMessage e))))]
      (if (= :ok loaded)
        (let [killed (failing-tests fast-tests)]
          (println "MUTANT" label "=>" (if (seq killed) (str "KILLED by " (pr-str killed)) "SURVIVED")))
        (println "MUTANT" label "=>" loaded)))
    (finally
      (io/copy (io/file backup) (io/file source))
      (assert (= 0 (:exit (shell/sh "cmp" source backup))))
      (load-file source)
      (println "RESTORED" label "cmp=0"))))


(println "FINAL git diff --quiet HEAD -- src/:" (:exit (shell/sh "git" "diff" "--quiet" "HEAD" "--" "src/")))
(shutdown-agents)

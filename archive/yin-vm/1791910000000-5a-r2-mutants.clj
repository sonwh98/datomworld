(require '[clojure.test :as test]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[yin.vm.semantic-register.derive-test])


(def source "src/cljc/yin/vm/semantic_register/derive.cljc")
(def backup "collab/1791910000000-5a-r2-derive.cljc.backup")
(def original (slurp backup))


(defn restore!
  []
  (with-open [in (io/input-stream backup) out (io/output-stream source)] (io/copy in out))
  (assert (= 0 (:exit (shell/sh "cmp" source backup))))
  (assert (= 0 (:exit (shell/sh "git" "diff" "--quiet" "HEAD" "--" "src/"))))
  (load-file source))


(def mutants
  [["X13 :ffi-call pushes omitted"
    ":ffi-call (do (doseq [child children] (walk child) (emit! [:push]))"
    ":ffi-call (do (doseq [child children] (walk child))"
    '[stack-operand-goldens stack-execution-oracle]]
   ["X14 :stream-put operands swapped"
    ":stream-put (do (walk (nth children 0)) (emit! [:push])\n                                  (walk (nth children 1)) (emit! [:stream-put]))"
    ":stream-put (do (walk (nth children 1)) (emit! [:push])\n                                  (walk (nth children 0)) (emit! [:stream-put]))"
    '[stack-operand-goldens stack-execution-oracle]]
   ["X19 definition operator visited (non-vacuity check)"
    "(walk (nth args 1))"
    "(do (child :yin/operator) (walk (nth args 1)))"
    '[lexical-address-law-stack]]])


(doseq [[label before after tests] mutants]
  (assert (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote before)) original))))
  (try
    (spit source (str/replace original before after))
    (load-file source)
    (let [failures (volatile! 0)]
      (doseq [test-name tests]
        (let [v (ns-resolve 'yin.vm.semantic-register.derive-test test-name)
              counters (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                         (test/test-vars [v]) @test/*report-counters*)]
          (prn {:mutant label :named-test test-name :counts counters
                :killed (pos? (:fail counters))})
          (vswap! failures + (:fail counters))))
      (assert (pos? @failures) "Each mutant must trigger a named assertion failure"))
    (finally (restore!))))


(println "All mutants restored byte for byte: cmp and git diff --quiet HEAD -- src/ passed.")
(shutdown-agents)

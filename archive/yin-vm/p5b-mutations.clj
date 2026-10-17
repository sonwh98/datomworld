(require '[clojure.test :as t]
         '[clojure.string :as str]
         '[yin.vm.semantic-register.derive-test])


(def source "src/cljc/yin/vm/semantic_register/derive.cljc")
(def backup "collab/p5b-derive.verified-backup.cljc")
(def original (slurp backup))
(assert (= original (slurp source)))


(def mutations
  [["inclusive-expiry" "#(< (nth % 2) pc)" "#(<= (nth % 2) pc)" 'register-goldens]
   ["highest-free-slot" "(first free) next-slot" "(last free) next-slot" 'worked-example-slots]
   ["drop-live-points" "(into (analysis/live a pc) (analysis/defs v pc))" "(analysis/defs v pc)" 'register-goldens]
   ["overwrite-local-bank" "locals (if (nil? owner) 0 (count (nth (nth v owner) 2)))" "locals 0" 'register-goldens]
   ["omit-structural-reservation" "(if missing 1 0)" "0" 'structural-slot]
   ["drop-saved-window" "(rcode/body-liveness image bi)" "{}" 'two-liveness-analyses-agree]
   ["reverse-ffi-operands" ":regs (mapv physical x)" ":regs (mapv physical (if (= op :ffi-call) (reverse x) x))" 'register-operand-goldens]
   ["swap-stream-put-operands" "(nth t (inc i))" "(nth t (if (and (= op :stream-put) (contains? #{1 2} i)) (- 4 i) (inc i)))" 'register-operand-goldens]
   ["shift-body-and-branch-targets" ":regs (mapv physical x)" ":regs (mapv physical x) :pc (inc x)" 'register-images-validate]])


(doseq [[label before after test-name] mutations]
  (assert (str/includes? original before) label)
  (try
    (spit source (str/replace-first original before after))
    (load-file source)
    (let [counts (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                   (t/test-vars [(ns-resolve 'yin.vm.semantic-register.derive-test test-name)])
                   @t/*report-counters*)]
      (println "MUTATION" label "TEST" test-name "COUNTS" counts
               "KILLED" (pos? (+ (:fail counts) (:error counts)))))
    (finally
      (java.nio.file.Files/copy
        (.toPath (java.io.File. backup)) (.toPath (java.io.File. source))
        (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (assert (= original (slurp source)))
      (load-file source))))

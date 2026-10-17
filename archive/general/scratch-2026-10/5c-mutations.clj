(require '[clojure.test :as t]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         'yin.vm.semantic-register.scan-test)


(def source "src/cljc/yin/vm/semantic_register/scan.cljc")
(def backup "collab/5c-scan.cljc.verified-backup")
(def original (slurp backup))
(assert (= original (slurp source)))


(def mutations
  [["M1 lexical owner chain" "(chain v bs i) name" "[] name" 'free-occurrences-use-the-owner-tree]
   ["M2 body enclosure" ":in-body? (pos? i)" ":in-body? false" 'free-occurrences-use-the-owner-tree]
   ["M3 conditional ranges" "(contains? ranges pc)" "false" 'definitions-respect-conditional-ranges]
   ["M4 store operand position" "[$code _ _ :store-get _ ?key]" "[$code _ _ :store-get ?key _]" 'positions-are-the-v4-table]
   ["M5 ffi operand position" "[$code _ _ :ffi-call _ ?op _]" "[$code _ _ :ffi-call ?op _ _]" 'positions-are-the-v4-table]
   ["M6 parked operand position" "[$code _ _ :resume ?pid _]" "[$code _ _ :resume _ ?pid]" 'positions-are-the-v4-table]
   ["M7 ffi application site" "#{:call :ffi-call}" "#{:call}" 'definitions-and-sites]
   ["M8 stream effect normalization" "(mapcat footprint-mnemonics)" "(mapcat (dissoc footprint-mnemonics :stream-close))" 'positions-are-the-v4-table]
   ["M9 segment pc" "(into [a pc] t)" "(into [a (inc pc)] t)" 'segment-rows-carry-a-and-pc]])


(doseq [[label before after test-name] mutations]
  (assert (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote before)) original))))
  (try
    (spit source (str/replace original before after))
    (load-file source)
    (binding [t/*report-counters* (ref t/*initial-report-counters*)]
      (t/test-vars [(ns-resolve 'yin.vm.semantic-register.scan-test test-name)])
      (let [counts @t/*report-counters*]
        (println "MUTATION" label "TEST" test-name "COUNTS" counts
                 "RESULT" (if (pos? (+ (:fail counts) (:error counts))) :killed :SURVIVED))))
    (finally
      (io/copy (io/file backup) (io/file source))
      (assert (= 0 (:exit (shell/sh "cmp" source backup))))
      (assert (= 0 (:exit (shell/sh "git" "diff" "--quiet" "HEAD" "--" source))))
      (load-file source)
      (println "RESTORED" label "cmp=0 git-diff-quiet=0"))))

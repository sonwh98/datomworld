;; One-off: add [dao.test-slow :as slow] after the clojure.test require.
(require '[clojure.string :as str])


(doseq [f ["test/yin/repl/main_test.cljc" "test/yin/repl/python_antlr_test.cljc"
           "test/yin/repl/dht_head_test.cljc" "test/yin/vm/linker/dht_end_to_end_test.cljc"
           "test/yin/vm/ucf/compose_rows_test.cljc" "test/yin/vm/ucf/holder/driver_test.cljc"
           "test/yin/vm/ucf/safepoint_harness_test.cljc" "test/yin/vm/ucf/stage_e_rows_test.cljc"
           "test/yang/python/antlr/int_literal_test.cljc"]]
  (let [s (slurp f)
        m (re-matcher #"\[clojure\.test :refer \[[^\]]*\]\]" s)]
    (when-not (.find m) (throw (ex-info "no clojure.test require" {:file f})))
    (let [start (.start m)
          col (- start (inc (str/last-index-of s "\n" start)))
          s' (str (subs s 0 (.end m)) "\n" (apply str (repeat col " "))
                  "[dao.test-slow :as slow]" (subs s (.end m)))]
      (spit f s')
      (println "added" f))))

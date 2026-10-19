;; JVM reproduction: clojure -Sdeps '{:aliases {:p7m {:extra-paths ["test"]}}}'
;;   -M:p7m collab/1792570000000-engineer-p7m-scan-golden.clj
;; Only the :c4/:py answer is regenerated, using scan-test/print-goldens' old-answer procedure.
(require '[yin.vm.semantic-register.scan-test :as scan]
         '[yin.vm.semantic-register.scanner-goldens :as goldens]
         '[clojure.pprint :as pp]
         '[clojure.data :as data]
         '[clojure.string :as str])


(let [path "test/yin/vm/semantic_register/scanner_goldens.cljc"
      source (slurp path)
      ast (:ast (second (first (filter #(= :py (first %)) (scan/c4-specs)))))
      answer (scan/old-answer ast)
      previous (get-in goldens/scanner-goldens [:c4 :py])
      start (.indexOf source "{:free-names" (.indexOf source "    {:py"))
      end (.indexOf source "\n     :pysp" start)
      replacement (str/replace (str/trimr (with-out-str (pp/pprint answer))) "\n" "\n     ")
      regenerated (str (subs source 0 start) replacement "," (subs source end))]
  (assert (= answer (scan/new-answer ast)))
  (assert (<= 0 start end))
  (assert (= (assoc-in goldens/scanner-goldens [:c4 :py] answer)
             (second (nth (read-string (subs regenerated (.indexOf regenerated "(def scanner-goldens"))) 2))))
  (println "Scanner diff (before-only, after-only, common):")
  (pp/pprint (data/diff previous answer))
  (spit path regenerated))

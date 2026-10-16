;; Generator of the :c4/:py and :c4/:t entries in
;; test/yin/vm/semantic/scanner_goldens.cljc.
;;
;; Provenance: the JVM scanner answers of the C4 modules after the
;; I4-a/I5/I6 prelude, lowering and reload moves — the deferred
;; regeneration of the "C4 scanner pins" row in docs/agents/known-gaps.md
;; (branch c4-i6-r4). Run from the repository root on the JVM, with the
;; test paths on the classpath, to rewrite the entries:
;;
;;   clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' -M:t \
;;    test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj
;;
;; (not -M:test: that alias's main is the test runner and runs the suite)
;;
;; It rebuilds the module specs exactly as
;; yin.vm.semantic.scan-test/c4-specs does, prints the live new-answer of
;; the two deferred groups (:py — the prelude module; :t — the reload
;; test module), and rewrites their pins through the file's own
;; generation pipeline: (pprint scanner-goldens) indented three columns
;; under (def scanner-goldens '). Every other group — the six untouched
;; :c4 modules plus :b0 and :register — is recomputed from the live
;; scanners and checked against the committed pins first; a disagreement
;; aborts before anything is written, because that would be a behavior
;; change, not a pin move. Nothing is hand-authored and pprint is
;; deterministic, so a rerun on an unchanged tree rewrites the same
;; bytes. Rerun it (and widen `deferred`) whenever the prelude, the
;; lowering or the import programs move the scanner answers;
;; yin.vm.semantic.scan-test fails until they are.

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[yin.vm.semantic.corpus :as corpus]
         '[yin.vm.semantic.scan-test :as scan-test]
         '[yin.vm.semantic.scanner-goldens :as goldens])


(def path "test/yin/vm/semantic/scanner_goldens.cljc")

(def deferred #{:py :t})


(def specs (scan-test/c4-specs))


(when (not= 8 (count specs))
  (throw (ex-info "c4-specs changed shape" {:count (count specs)})))


(def answers
  (into {} (map (fn [[group spec]]
                  [group (scan-test/new-answer (:ast spec))])
                specs)))


;; The gate: only the deferred groups may disagree with the committed
;; pins. Anything else moving is a behavior change, not a pin move.
(doseq [[group answer] answers
        :when (and (not (deferred group))
                   (not= answer (get-in goldens/scanner-goldens [:c4 group])))]
  (throw (ex-info "an untouched :c4 group moved" {:group group})))


(doseq [[group programs] [[:b0 scan-test/b0-programs] [:register corpus/programs]]
        [n ast] programs
        :when (not= (get-in goldens/scanner-goldens [group n])
                    (scan-test/new-answer ast))]
  (throw (ex-info "a non-c4 golden moved" {:group group :program n})))


(doseq [group [:py :pysp :k :m :t :w :e :n]
        :let [old (get-in goldens/scanner-goldens [:c4 group])
              new (get answers group)
              added (count (remove (set (:free-names old)) (:free-names new)))
              removed (count (remove (set (:free-names new)) (:free-names old)))]]
  (println (name group)
           ":application-count" (:application-count old) "->"
           (:application-count new)
           ":free-names" (count (:free-names old)) "->" (count (:free-names new))
           "; added" added "removed" removed "free names"))


(doseq [group [:py :t]]
  (println)
  (println "=== :c4" (name group) "new answer ===")
  (binding [*print-length* nil *print-level* nil]
    (pp/pprint (get answers group))))


(def intended
  (assoc-in (assoc-in goldens/scanner-goldens [:c4 :py] (:py answers))
            [:c4 :t] (:t answers)))


(def def-form
  (let [printed (str/trimr (with-out-str
                             (binding [*print-length* nil *print-level* nil]
                               (pp/pprint intended))))
        [head & tail] (str/split-lines printed)]
    (str "(def scanner-goldens\n  '" head "\n"
         (str/join "\n" (map #(str "   " %) tail)) ")\n")))


(def rewritten
  (let [original (slurp path)
        marker "(def scanner-goldens\n"
        at (str/index-of original marker)]
    (when (nil? at)
      (throw (ex-info "the golden def form moved" {:path path})))
    (str (subs original 0 at) def-form)))


;; The rewrite must read back as exactly the intended pins.
(let [anchor "(def scanner-goldens\n  '"
      at (str/index-of rewritten anchor)]
  (when (or (nil? at)
            (not= intended (read-string (subs rewritten (+ at (count anchor))))))
    (throw (ex-info "the rewrite does not read back as the intended pins" {}))))


(spit path rewritten)
(println)
(println "wrote" path)

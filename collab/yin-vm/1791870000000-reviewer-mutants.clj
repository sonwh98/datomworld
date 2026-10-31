;; Reviewer mutation run for slice 5a. Mutates derive.cljc in place, runs
;; EVERY derive-test var per mutant (so every killing test is named), and
;; restores byte for byte from the reviewer's verified backup in `finally`.
(require '[clojure.test :as test]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[yin.vm.debruijn-code :as dc]
         '[yin.vm.semantic-register.derive-test :as dt])


(def source "src/cljc/yin/vm/semantic_register/derive.cljc")
(def backup "collab/1791870000000-reviewer-derive.cljc.backup")
(def original (slurp backup))
(assert (= original (slurp source)) "source must equal backup before mutating")


(defn restore!
  []
  (with-open [in (io/input-stream backup) out (io/output-stream source)] (io/copy in out))
  (assert (= 0 (:exit (shell/sh "cmp" source backup))) "cmp failed")
  (assert (= 0 (:exit (shell/sh "git" "diff" "--quiet" "HEAD" "--" "src/"))) "git diff --quiet HEAD -- src/ failed")
  (load-file source))


;; --- Golden recomputation (task 2): all ten pinned H vectors through the
;; public hash function, compared with the pinned strings. ---------------
(println "=== golden hash recomputation ===")


(doseq [[i golden pinned] (map vector (range 1 11) dt/stack-golden-vectors dt/stack-golden-hashes)]
  (let [h (dc/image-hash golden)]
    (println (format "golden %2d: computed %s pinned %s %s" i h pinned (if (= h pinned) "MATCH" "MISMATCH")))))


(def test-vars
  (->> (ns-publics 'yin.vm.semantic-register.derive-test)
       vals
       (filter #(:test (meta %)))
       (sort-by #(:line (meta %)))
       vec))


(defn run-all
  []
  (into {}
        (map (fn [v]
               (let [counters (binding [test/*report-counters* (ref test/*initial-report-counters*)
                                        test/*test-out* (java.io.StringWriter.)]
                                (test/test-vars [v])
                                @test/*report-counters*)]
                 [(:name (meta v)) (select-keys counters [:fail :error])])))
        test-vars))


(def mutations
  [["X01 de Bruijn depth off by one" "(into [:load-bound rd] bound)" "(into [:load-bound rd] [(inc (first bound)) (second bound)])"]
   ["X02 frames outer-first (shadowing order across frames)" "(into [(nth (nth v owner) 2)]\n                    (chain (code/body-index bodies owner)))" "(conj (chain (code/body-index bodies owner)) (nth (nth v owner) 2))"]
   ["X03 duplicate parameters collapsed (rightmost-wins lost)" "(into [(nth (nth v owner) 2)]" "(into [(vec (distinct (nth (nth v owner) 2)))]"]
   ["X04 :load-bound/:load-free swapped" "(into [:load-bound rd] bound))\n                          [:load-free rd free]))" "(into [:load-free rd] bound))\n                          [:load-bound rd free]))"]
   ["X05 closure body-pc off by one in resolve" "[:closure rd (count x) y]" "[:closure rd (count x) (inc y)]"]
   ["X06 :names recorded for free vars too" "[:load-free rd free]))" "(do (vswap! names assoc pc x) [:load-free rd free])))"]
   ["X07 bodies emitted LIFO instead of FIFO" "(body! (first bodies))\n      (loop [i 0]" "(body! (first bodies)) (vswap! queue #(vec (reverse %)))\n      (loop [i 0]"]
   ["X08 every label marked one pc late" "mark! (fn [l] (vswap! labels assoc l (count @image)))" "mark! (fn [l] (vswap! labels assoc l (inc (count @image))))"]
   ["X09 conditional join label placed before the else arm" "(mark! else) (walk el) (mark! end))" "(mark! else) (mark! end) (walk el))"]
   ["X10 operator push omitted (operands still pushed)" ":call (do (doseq [child children] (walk child) (emit! [:push]))" ":call (do (walk (first children)) (doseq [child (rest children)] (walk child) (emit! [:push]))"]
   ["X11 operator emitted after operands" ":call (do (doseq [child children] (walk child) (emit! [:push]))" ":call (do (doseq [child (concat (rest children) [(first children)])] (walk child) (emit! [:push]))"]
   ["X12 tail flag forced true" "[:call (count y) z]" "[:call (count y) true]"]
   ["X13 :ffi-call pushes omitted" ":ffi-call (do (doseq [child children] (walk child) (emit! [:push]))" ":ffi-call (do (doseq [child children] (walk child))"]
   ["X14 :stream-put operands swapped" ":stream-put (do (walk (nth children 0)) (emit! [:push])\n                                  (walk (nth children 1)) (emit! [:stream-put]))" ":stream-put (do (walk (nth children 1)) (emit! [:push])\n                                  (walk (nth children 0)) (emit! [:stream-put]))"]
   ["X15 :store-put key/value swapped" ":store-put (emit! [op x y])" ":store-put (emit! [op y x])"]
   ["X16 main/lambda terminators swapped" "(if (nil? owner) :halt :return)" "(if (nil? owner) :return :halt)"]
   ["X17 side-table :source shifted" "(diagnostic! {:kind :var :source pc})" "(diagnostic! {:kind :var :source (max 0 (dec pc))})"]
   ["X18 addresses reads the wrong slice of :load-bound" "(into [:bound] (subvec t (- (count t) 2)))" "(into [:bound] (subvec t 1))"]
   ["X19 resolved-addresses visits a definition's operator" "(walk (nth args 1))" "(do (child :yin/operator) (walk (nth args 1)))"]
   ["X20 resolved-addresses bodies LIFO" "(walk root)" "(walk root) (vswap! queue #(vec (reverse %)))"]
   ["X21 stage-value leak: a public digest function" "(defn register-image" "(defn vector-digest [v] (hash v))\n\n(defn register-image"]
   ["X22 register-image returns instead of throwing" "(throw (ex-info \"Register derivation is not in slice 5a\" {:reason :not-in-slice-5a}))" "{:image [] :side-table {}}"]
   ["X23 :define emits the register instead of the name" ":define [:define x]" ":define [:define (nth (nth rv pc) 3)]"]
   ["X24 :resume walks nothing (operand dropped)" "(:define :stream-cursor :stream-next :stream-close :resume)\n                  (do (walk (first children))" "(:define :stream-cursor :stream-next :stream-close :resume)\n                  (do (when-not (= op :resume) (walk (first children)))"]])


(println "=== mutants ===")
(def only (set *command-line-args*))


(doseq [[label before after] mutations
        :when (or (empty? only) (contains? only (subs label 0 3)))]
  (assert (str/includes? original before) (str "pattern not found for " label))
  (assert (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote before)) original))) (str "pattern not unique for " label))
  (try
    (spit source (str/replace original before after))
    (let [compiled (try (load-file source) :ok (catch Throwable e [:compile-error (ex-message e)]))
          results (if (= :ok compiled) (run-all) {})
          killers (into (sorted-map) (filter (fn [[_ c]] (pos? (+ (:fail c) (:error c)))) results))]
      (println (format "%-62s %s" label
                       (cond (not= :ok compiled) (str "COMPILE-ERROR " compiled)
                             (seq killers) (str "KILLED by " (pr-str killers))
                             :else "SURVIVED"))))
    (finally (restore!))))


(println "=== post-run sanity: restored source, all tests ===")
(prn (run-all))
(println "restored: cmp and git diff --quiet HEAD -- src/ both passed on every restore")
(shutdown-agents)

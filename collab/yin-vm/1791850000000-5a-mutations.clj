(require '[clojure.test :as test]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[yin.vm.semantic-register.derive-test])


(def source "src/cljc/yin/vm/semantic_register/derive.cljc")
(def backup "collab/1791850000000-5a-derive.cljc.backup")
(def original (slurp backup))


(defn restore!
  []
  (with-open [in (io/input-stream backup) out (io/output-stream source)] (io/copy in out))
  (assert (= 0 (:exit (shell/sh "cmp" source backup))))
  (assert (= 0 (:exit (shell/sh "git" "diff" "--quiet" "HEAD" "--" source))))
  (load-file source))


(def mutations
  [["M01 bound names omitted" ":names @names" ":names {}" 'resolve-rewrites-only-vars-and-closures]
   ["M02 outer frame lost" "(chain (code/body-index bodies owner))" "[]" 'resolution-is-resolve-names]
   ["M03 binder position off by one" "(into [:load-bound rd] bound)" "(into [:load-bound rd] [(first bound) (inc (second bound))])" 'resolution-is-resolve-names]
   ["M04 closure arity off by one" "[:closure rd (count x) y]" "[:closure rd (inc (count x)) y]" 'resolve-rewrites-only-vars-and-closures]
   ["M05 call argc off by one" "[:call (count y) z]" "[:call (inc (count y)) z]" 'stack-goldens]
   ["M06 tail flag discarded" "[:call (count y) z]" "[:call (count y) false]" 'stack-goldens]
   ["M07 conditional labels off by one" "(assoc t 1 (get @labels (nth t 1)))" "(assoc t 1 (inc (get @labels (nth t 1))))" 'stack-goldens]
   ["M08 closure body label off by one" "(assoc t 2 (get @labels (nth t 2)))" "(assoc t 2 (inc (get @labels (nth t 2))))" 'stack-goldens]
   ["M09 definition key lost" "[:define x]" "[:define 'wrong]" 'stack-goldens]
   ["M10 resume id lost" "[:resume (nth (nth rv pc) 1)]" "[:resume :wrong]" 'stack-goldens]
   ["M11 call pushes omitted" ":call (do (doseq [child children] (walk child) (emit! [:push]))" ":call (do (doseq [child children] (walk child))" 'stack-goldens]
   ["M12 FFI argc off by one" "[:ffi-call x (count y)]" "[:ffi-call x (inc (count y))]" 'stack-execution-oracle]
   ["M14 sibling bodies reordered" "(body! (first bodies))" "(body! (first bodies)) (vswap! queue #(if (> (count %) 1) (assoc % 0 (nth % 1) 1 (nth % 0)) %))" 'stack-images-validate]
   ["M13 lambda terminator becomes halt" "(if (nil? owner) :halt :return)" ":halt" 'stack-images-validate]])


(doseq [[label before after test-name] mutations]
  (assert (str/includes? original before))
  (try
    (spit source (str/replace original before after))
    (load-file source)
    (let [v (ns-resolve 'yin.vm.semantic-register.derive-test test-name)
          counters (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                     (test/test-vars [v]) @test/*report-counters*)]
      (prn {:mutation label :named-test test-name :counts counters
            :killed (pos? (+ (:fail counters) (:error counters)))})
      (assert (pos? (:fail counters)) "Named assertion must fail, not merely compilation"))
    (finally (restore!))))


(println "All source restorations passed cmp and git diff --quiet HEAD; new source is untracked, so cmp is the byte proof.")

(shutdown-agents)

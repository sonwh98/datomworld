(ns bench.yin-vm-v2-bench
  "Semantic VM Phase 4: the ast-walker against the linear semantic VM on
   `yin.vm.v2` (`docs/design/yin.vm.semantic.md` §8).

   The workload is the historic tail-recursive countdown from the deleted
   `yin.vm.bytecode-bench`, so the ratios read against the 1.32x walker and
   1.0–1.1x register/stack line in `docs/cesk-space-optimization.md`.

   The timed region is `vm/run` alone. VM construction, AST → datoms, the
   lowering, and the program load stay outside it. Each timed call runs the
   same loaded VM value: both machines are persistent values and the
   countdown touches no stream, so no reset is needed between runs.

   JVM (Criterium quick-bench):
     clj -Sdeps '{:aliases {:bench {:extra-paths [\"test\"]}}}' \\
       -M:bench -m bench.yin-vm-v2-bench 500 5000
   Node:
     clj -M:cljs -m shadow.cljs.devtools.cli compile vm-bench
     node target/vm-bench.js 500 5000"
  (:require #?@(:cljd [] :clj [[criterium.core :as criterium]])
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.linearize :as linearize]
            [yin.vm.v2.semantic :as semantic])
  #?(:cljd (:import ["dart:core" DateTime])))


(defn tail-countdown-ast
  [n]
  (let [self-fn {:type :lambda,
                 :params ['self 'n],
                 :body {:type :if,
                        :test {:type :application,
                               :operator {:type :variable, :name '<},
                               :operands [{:type :variable, :name 'n}
                                          {:type :literal, :value 1}]},
                        :consequent {:type :literal, :value 0},
                        :alternate
                        {:type :application,
                         :operator {:type :variable, :name 'self},
                         :operands [{:type :variable, :name 'self}
                                    {:type :application,
                                     :operator {:type :variable, :name '-},
                                     :operands [{:type :variable, :name 'n}
                                                {:type :literal, :value 1}]}],
                         :tail? true},
                        :tail? true}}]
    {:type :application,
     :operator self-fn,
     :operands [self-fn {:type :literal, :value n}],
     :tail? true}))


(defn load-walker
  [ast]
  (ast-walker/vm-load-program (ast-walker/create-vm) (vm/ast->datoms ast)))


(defn load-semantic
  [ast]
  (semantic/vm-load-program (semantic/create-vm) (linearize/lower-ast ast)))


(def evaluators
  [["AST Walker" load-walker] ["Semantic" load-semantic]])


(defn- check!
  [label loaded]
  (let [v (vm/value (vm/run loaded))]
    (when-not (= 0 v)
      (throw (ex-info "Benchmark workload returned the wrong value"
                      {:evaluator label, :value v})))))


#?(:cljd
   (defn- measure-ms
     "Mean of 9 samples after 4 warmups, each sample 20 runs, in milliseconds
      per run — the sampling discipline of `yin.register-bench-cljd-v2`."
     [loaded]
     (let [now-ms (fn [] (/ (.-microsecondsSinceEpoch (DateTime/now)) 1000.0))
           sample (fn []
                    (let [start (now-ms)]
                      (dotimes [_ 20] (vm/run loaded))
                      (/ (- (now-ms) start) 20)))]
       (dotimes [_ 4] (sample))
       (let [samples (vec (repeatedly 9 sample))]
         (println "  samples ms/run:" samples)
         (/ (reduce + samples) (count samples)))))
   :clj
   (defn- measure-ms
     "Criterium quick-bench mean, in milliseconds."
     [loaded]
     (let [result (criterium/quick-benchmark (vm/run loaded) {})]
       (criterium/report-result result)
       (* 1e3 (first (:mean result))))))


#?(:cljd nil
   :clj nil
   :cljs
   (defn- measure-ms
     "Mean of 9 samples after 4 warmups, each sample 20 runs, in milliseconds
      per run — the sampling discipline of `yin.register-bench-cljd-v2`."
     [loaded]
     (let [now #(.now js/performance)
           sample (fn []
                    (let [start (now)]
                      (dotimes [_ 20] (vm/run loaded))
                      (/ (- (now) start) 20)))]
       (dotimes [_ 4] (sample))
       (let [samples (vec (repeatedly 9 sample))]
         (println "  samples ms/run:" samples)
         (/ (reduce + samples) (count samples))))))


(defn run-bench
  [ns-list]
  (doseq [n ns-list]
    (let [ast (tail-countdown-ast n)
          means (into {}
                      (for [[label load] evaluators]
                        (let [loaded (load ast)]
                          (check! label loaded)
                          (println (str "\n" label " n=" n))
                          [label (measure-ms loaded)])))
          walker (get means "AST Walker")]
      (println (str "\nRESULT n=" n))
      (doseq [[label _] evaluators]
        (let [ms (get means label)]
          (println (str "  " label ": " ms " ms/run, "
                        (/ ms walker) "x walker")))))))


#?(:cljd nil
   :clj
   (defn -main
     [& args]
     (run-bench (if (seq args) (map #(Long/parseLong %) args) [500 5000]))
     (shutdown-agents)))


#?(:cljd nil
   :clj nil
   :cljs
   (defn -main
     [& args]
     (run-bench (if (seq args) (map #(js/parseInt %) args) [500 5000]))))

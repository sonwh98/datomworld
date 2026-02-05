(ns yin.asm-bench
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.asm :as asm]
            [yin.vm :as vm]))


(defn now-us
  []
  #?(:clj (/ (System/nanoTime) 1000.0)
     :cljs (* (js/performance.now) 1000)))


(defn benchmark
  [f iterations]
  (let [start (now-us)
        _ (dotimes [_ iterations] (f))
        end (now-us)]
    (/ (- end start) iterations)))


(def primitives {'+ +, '- -, '* *, '/ /, '< <, '> >, '= =})

(def literal-ast {:type :literal, :value 42})


(def add-ast
  {:type :application,
   :operator {:type :variable, :name '+},
   :operands [{:type :literal, :value 10} {:type :literal, :value 20}]})


(def lambda-ast
  {:type :application,
   :operator {:type :lambda,
              :params ['x],
              :body {:type :application,
                     :operator {:type :variable, :name '+},
                     :operands [{:type :variable, :name 'x}
                                {:type :literal, :value 1}]}},
   :operands [{:type :literal, :value 10}]})


(def nested-ast
  {:type :application,
   :operator {:type :lambda,
              :params ['x],
              :body {:type :application,
                     :operator {:type :variable, :name '+},
                     :operands [{:type :variable, :name 'x}
                                {:type :application,
                                 :operator {:type :variable, :name '*},
                                 :operands [{:type :literal, :value 2}
                                            {:type :literal, :value 3}]}]}},
   :operands [{:type :literal, :value 10}]})


(def conditional-ast
  {:type :if,
   :test {:type :application,
          :operator {:type :variable, :name '<},
          :operands [{:type :literal, :value 5} {:type :literal, :value 10}]},
   :consequent {:type :literal, :value :yes},
   :alternate {:type :literal, :value :no}})


(defn fmt
  [n]
  #?(:clj (format "%.2f" (double n))
     :cljs (.toFixed n 2)))


(defn compile-to-datoms
  [ast]
  (let [datoms (vm/ast->datoms ast)
        root-id (ffirst datoms)]
    {:node root-id, :datoms datoms}))


(defn run-single-benchmark
  [name ast iterations]
  (let [compiled (compile-to-datoms ast)
        datoms (vm/ast->datoms ast)
        asm-instrs (asm/ast-datoms->stack-assembly datoms)
        {:keys [bc pool]} (asm/stack-assembly->bytecode asm-instrs)
        sem-compile (benchmark #(compile-to-datoms ast) iterations)
        stack-compile (benchmark #(let [d (vm/ast->datoms ast)]
                                    (-> d
                                        asm/ast-datoms->stack-assembly
                                        asm/stack-assembly->bytecode))
                                 iterations)
        sem-run (benchmark #(asm/run-semantic compiled primitives) iterations)
        stack-run (benchmark #(asm/run-bytes bc pool primitives) iterations)]
    {:name name,
     :compile-semantic sem-compile,
     :compile-stack stack-compile,
     :compile-ratio (/ sem-compile stack-compile),
     :execute-semantic sem-run,
     :execute-stack stack-run,
     :execute-ratio (/ sem-run stack-run)}))


(defn print-benchmark
  [result]
  (println (str "--- " (:name result) " ---"))
  (println (str "  Compile - Semantic: "
                (fmt (:compile-semantic result))
                " us | Stack: "
                (fmt (:compile-stack result))
                " us | Ratio: "
                (fmt (:compile-ratio result))
                "x"))
  (println (str "  Execute - Semantic: "
                (fmt (:execute-semantic result))
                " us | Stack: "
                (fmt (:execute-stack result))
                " us | Ratio: "
                (fmt (:execute-ratio result))
                "x"))
  (println))


(defn run-benchmarks
  []
  (let [platform #?(:clj "CLJ"
                    :cljs "CLJS")]
    (println "")
    (println "============================================")
    (println (str platform " Assembly Benchmark: Semantic vs Stack"))
    (println "============================================")
    (println "")
    ;; Warm up
    (print "Warming up... ")
    #?(:clj (flush))
    (dotimes [_ 500]
      (compile-to-datoms nested-ast)
      (let [d (vm/ast->datoms nested-ast)]
        (-> d
            asm/ast-datoms->stack-assembly
            asm/stack-assembly->bytecode))
      (asm/run-semantic (compile-to-datoms nested-ast) primitives)
      (let [d (vm/ast->datoms nested-ast)
            i (asm/ast-datoms->stack-assembly d)
            {:keys [bc pool]} (asm/stack-assembly->bytecode i)]
        (asm/run-bytes bc pool primitives)))
    (println "done.")
    (println "")
    (let [iterations 5000
          results
            [(run-single-benchmark "Literal (42)" literal-ast iterations)
             (run-single-benchmark "Addition (+ 10 20)" add-ast iterations)
             (run-single-benchmark "Lambda ((fn [x] (+ x 1)) 10)"
                                   lambda-ast
                                   iterations)
             (run-single-benchmark "Nested ((fn [x] (+ x (* 2 3))) 10)"
                                   nested-ast
                                   iterations)
             (run-single-benchmark "Conditional (if (< 5 10) :yes :no)"
                                   conditional-ast
                                   iterations)]]
      (doseq [r results] (print-benchmark r))
      (println "--- Query Operations (semantic only) ---")
      (let [compiled (compile-to-datoms nested-ast)
            datoms (:datoms compiled)]
        (let [q (benchmark #(asm/find-applications datoms) iterations)]
          (println (str "  find-applications:  "
                        (fmt q)
                        " us -> "
                        (count (asm/find-applications datoms))
                        " found")))
        (let [q (benchmark #(asm/find-lambdas datoms) iterations)]
          (println (str "  find-lambdas:       "
                        (fmt q)
                        " us -> "
                        (count (asm/find-lambdas datoms))
                        " found")))
        (let [q (benchmark #(asm/find-variables datoms) iterations)]
          (println (str "  find-variables:     "
                        (fmt q)
                        " us -> "
                        (count (asm/find-variables datoms))
                        " found"))))
      (println "")
      (println "Summary: Semantic trades speed for queryability")
      results)))


;; Make it a test so it runs with the test suite
(deftest ^:benchmark assembly-benchmark-test
  (testing "Benchmark semantic vs stack assembly"
    (let [results (run-benchmarks)]
      ;; Just verify benchmarks ran and returned results
      (is (= 5 (count results)))
      (is (every? #(< 0 (:compile-semantic %)) results))
      (is (every? #(< 0 (:execute-semantic %)) results)))))

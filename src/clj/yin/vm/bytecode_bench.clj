(ns yin.vm.bytecode-bench
  (:require
    [clojure.string :as str]
    [criterium.core :as criterium]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(def ^:private opcode->name
  (reduce-kv (fn [m k v] (assoc m v k)) {} vm/opcode-table))


(defn- parse-arg-long
  [s default]
  (if (some? s)
    (Long/parseLong s)
    default))


(defn- parse-opts
  [args]
  (reduce (fn [{:keys [n] :as opts} arg]
            (case arg
              "--fast-only" (assoc opts :fast-only? true)
              "--register-only" (assoc opts :register-only? true)
              "--stack-only" (assoc opts :stack-only? true)
              "--semantic-only" (assoc opts :semantic-only? true)
              "--ast-walker" (assoc opts :ast-walker? true)
              "--ast-walker-only" (assoc opts :ast-walker-only? true)
              (assoc opts :n (parse-arg-long arg n))))
          {:n 1000
           :fast-only? false
           :register-only? false
           :stack-only? false
           :semantic-only? false
           :ast-walker? false
           :ast-walker-only? false}
          args))


(defn- tail-countdown-ast
  [n]
  (let [self-fn {:type :lambda,
                 :params ['self 'n],
                 :body {:type :if,
                        :test {:type :application,
                               :operator {:type :variable, :name '<},
                               :operands [{:type :variable, :name 'n}
                                          {:type :literal, :value 1}]},
                        :consequent {:type :literal, :value 0},
                        :alternate {:type :application,
                                    :operator {:type :variable, :name 'self},
                                    :operands [{:type :variable, :name 'self}
                                               {:type :application,
                                                :operator {:type :variable,
                                                           :name '-},
                                                :operands [{:type :variable,
                                                            :name 'n}
                                                           {:type :literal,
                                                            :value 1}]}],
                                    :tail? true},
                        :tail? true}}]
    {:type :application,
     :operator self-fn,
     :operands [self-fn {:type :literal, :value n}],
     :tail? true}))


(defn- track-opcodes
  "Runs the program once using the slow path and counts opcode occurrences."
  [program]
  (let [counts (atom {})]
    (loop [v program]
      (if (and (not (vm/halted? v)) (not (vm/blocked? v)))
        (let [pc (:control v)
              bc (:bytecode v)
              op (get bc pc)
              op-name (get opcode->name op op)]
          (swap! counts update op-name (fnil inc 0))
          (recur (vm/step v)))
        {:final-vm v
         :opcode-counts @counts}))))


(defn- run-slow
  [vm-state]
  (loop [v vm-state]
    (if (and (not (vm/halted? v)) (not (vm/blocked? v)))
      (recur (vm/step v))
      v)))


(defn- queue-vm
  [vm-state datoms]
  (let [in-stream (ds/open! {:transport {:type :ringbuffer
                                         :capacity nil}})
        queued-vm (assoc vm-state :in-stream in-stream :in-cursor {:position 0})]
    (ds/put! in-stream (vec datoms))
    queued-vm))


(defn- load-register-program
  [ast]
  (queue-vm (register/create-vm) (vm/ast->datoms ast)))


(defn- load-stack-program
  [ast]
  (queue-vm (stack/create-vm) (vm/ast->datoms ast)))


(defn- load-semantic-program
  [ast]
  (queue-vm (semantic/create-vm) (vm/ast->datoms ast)))


(defn- track-vm-steps
  "Runs the VM one step at a time, counting control node types."
  [vm-state]
  (let [counts (atom {})]
    (loop [v vm-state]
      (if (and (not (vm/halted? v)) (not (vm/blocked? v)))
        (let [node-type (get (vm/control v) :type :no-control)]
          (swap! counts update node-type (fnil inc 0))
          (recur (vm/step v)))
        {:final-vm v
         :step-counts @counts}))))


(defn- bench-stepping-vm
  [vm-name loaded {:keys [fast-only?]}]
  (println (str "\n=== " vm-name " ==="))
  (when-not fast-only?
    (println "Analyzing hot paths (step-by-step)...")
    (let [{:keys [step-counts]} (track-vm-steps loaded)
          sorted-counts (reverse (sort-by second step-counts))]
      (doseq [[node-type count] (take 10 sorted-counts)]
        (println (format "  %-16s : %d" (str node-type) count))))
    (println "\nMeasuring SLOW path (step-by-step, Criterium quick-bench)...")
    (criterium/quick-bench (run-slow loaded)))
  (println (if fast-only?
             "Measuring FAST path only (Criterium quick-bench)..."
             "\nMeasuring FAST path (vm/run, Criterium quick-bench)..."))
  (criterium/quick-bench (vm/run loaded)))


(defn- bench-bytecode-vm
  [vm-name program {:keys [fast-only?]}]
  (println (str "\n=== " vm-name " ==="))

  (when-not fast-only?
    ;; 1. Identify Hot Paths (Slow Path Analysis)
    (println "Analyzing hot paths (slow path)...")
    (let [{:keys [opcode-counts]} (track-opcodes program)
          sorted-counts (reverse (sort-by second opcode-counts))]
      (doseq [[op count] (take 10 sorted-counts)]
        (println (format "  %-12s : %d" (str op) count))))
    ;; 2. Slow Path Benchmarking
    (println "\nMeasuring SLOW path (Criterium quick-bench)...")
    (criterium/quick-bench (run-slow program)))

  ;; 3. Fast Path Benchmarking
  (println (if fast-only?
             "Measuring FAST path only (Criterium quick-bench)..."
             "\nMeasuring FAST path (Criterium quick-bench)..."))
  (criterium/quick-bench (vm/run program)))


(defn -main
  [& args]
  (let [{:keys [n fast-only? register-only? stack-only? semantic-only?
                ast-walker? ast-walker-only?] :as opts} (parse-opts args)
        exclusive-flags (cond-> []
                          register-only? (conj "--register-only")
                          stack-only? (conj "--stack-only")
                          semantic-only? (conj "--semantic-only")
                          ast-walker-only? (conj "--ast-walker-only"))]
    (when (> (count exclusive-flags) 1)
      (throw (ex-info (str "Cannot combine " (str/join " and " exclusive-flags))
                      {:args args})))
    (let [run-ast-walker? (or ast-walker-only? ast-walker?
                              (and (not register-only?) (not stack-only?)
                                   (not semantic-only?) (not ast-walker-only?)))
          run-semantic? (or semantic-only?
                            (and (not register-only?) (not stack-only?)
                                 (not ast-walker-only?)))
          run-register? (or register-only?
                            (and (not stack-only?) (not semantic-only?)
                                 (not ast-walker-only?)))
          run-stack? (or stack-only?
                         (and (not register-only?) (not semantic-only?)
                              (not ast-walker-only?)))
          ast (tail-countdown-ast n)
          mode-str (cond
                     ast-walker-only? "ast-walker-only"
                     register-only? "register-only"
                     stack-only? "stack-only"
                     semantic-only? "semantic-only"
                     :else "all")]
      (println "VM optimization benchmark")
      (println (str "workload: tail-recursive countdown n=" n))
      (println (str "mode: " mode-str (when fast-only? ", fast-only")))
      (when run-register?
        (let [program (load-register-program ast)]
          (bench-bytecode-vm "Register VM" program opts)))
      (when run-stack?
        (let [program (load-stack-program ast)]
          (bench-bytecode-vm "Stack VM" program opts)))
      (when run-semantic?
        (let [program (load-semantic-program ast)]
          (bench-stepping-vm "Semantic VM" program opts)))
      (when run-ast-walker?
        (let [program (queue-vm (ast-walker/create-vm) (vm/ast->datoms ast))]
          (bench-stepping-vm "AST Walker VM" program opts))))))

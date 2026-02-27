(ns yin.vm.bytecode-bench
  (:require
    [criterium.core :as criterium]
    [yin.vm :as vm]
    [yin.vm.register :as register]
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
              (assoc opts :n (parse-arg-long arg n))))
          {:n 1000
           :fast-only? false
           :register-only? false
           :stack-only? false}
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
        (let [pc (:pc v)
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


(defn- load-register-program
  [ast]
  (let [datoms (vm/ast->datoms ast)
        {:keys [asm reg-count]} (register/ast-datoms->asm datoms)
        compiled (assoc (register/assemble asm) :reg-count reg-count)]
    (vm/load-program (register/create-vm {:env vm/primitives}) compiled)))


(defn- load-stack-program
  [ast]
  (let [datoms (vm/ast->datoms ast)
        asm (stack/ast-datoms->asm datoms)
        compiled (stack/assemble asm)]
    (vm/load-program (stack/create-vm {:env vm/primitives}) compiled)))


(defn- bench-vm
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
  (let [{:keys [n fast-only? register-only? stack-only?] :as opts} (parse-opts
                                                                     args)]
    (when (and register-only? stack-only?)
      (throw (ex-info "Cannot combine --register-only and --stack-only"
                      {:args args})))
    (let [run-register? (or register-only? (not stack-only?))
          run-stack? (or stack-only? (not register-only?))
          ast (tail-countdown-ast n)
          register-program (load-register-program ast)
          stack-program (load-stack-program ast)]
      (println "Bytecode VM optimization benchmark")
      (println (str "workload: tail-recursive countdown n=" n))
      (println (str "mode: "
                    (cond
                      (and run-register? run-stack?) "register+stack"
                      run-register? "register-only"
                      :else "stack-only")
                    (when fast-only? ", fast-only")))
      (when run-register?
        (bench-vm "Register VM" register-program opts))
      (when run-stack?
        (bench-vm "Stack VM" stack-program opts)))))

(ns thetao.vm.bench
  (:require
    [clojure.string :as str]
    [criterium.core :as criterium]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [thetao.vm.algebra-vm :as algebra-vm]
    [thetao.vm.bytecode-vm :as bytecode-vm]
    [yin.vm :as yin-vm]
    [yin.vm.register :as register]
    [yin.vm.stack :as stack]))


(defn- parse-arg-long
  [s default]
  (if (some? s)
    (Long/parseLong s)
    default))


(defn- parse-opts
  [args]
  (reduce (fn [{:keys [n] :as opts} arg]
            (case arg
              "--n" opts ; handled by next arg
              (if (and (string? arg) (re-matches #"\d+" arg))
                (assoc opts :n (parse-arg-long arg n))
                opts)))
          {:n 1000}
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


(defn- load-yin-register
  [ast]
  (let [in-stream (ds/open! {:type :ringbuffer :capacity nil})
        vm (assoc (register/create-vm) :in-stream in-stream :in-cursor {:position 0})]
    (ds/put! in-stream (vec (yin-vm/ast->datoms ast)))
    vm))


(defn- load-yin-stack
  [ast]
  (let [in-stream (ds/open! {:type :ringbuffer :capacity nil})
        vm (assoc (stack/create-vm) :in-stream in-stream :in-cursor {:position 0})]
    (ds/put! in-stream (vec (yin-vm/ast->datoms ast)))
    vm))


(defn- bench-vm
  [vm-name load-fn run-fn ast]
  (println (str "\n=== " vm-name " ==="))
  (let [loaded (load-fn ast)]
    (println "Warmup and bench...")
    (criterium/quick-bench (run-fn loaded))))


(defn -main
  [& args]
  (let [{:keys [n]} (parse-opts args)
        ast (tail-countdown-ast n)]
    (println "VM Comparison Benchmark")
    (println "workload: tail-recursive countdown n =" n)

    (bench-vm "Yin Register VM"
              load-yin-register
              yin-vm/run
              ast)

    (bench-vm "Yin Stack VM"
              load-yin-stack
              yin-vm/run
              ast)

    (bench-vm "Thetao Bytecode VM"
              (fn [a] (-> (bytecode-vm/create-vm) (bytecode-vm/load-program a) (bytecode-vm/enqueue-root)))
              bytecode-vm/run
              ast)

    (bench-vm "Thetao Algebra VM"
              (fn [a] (-> (algebra-vm/create-vm) (algebra-vm/load-program a) (algebra-vm/enqueue-root)))
              algebra-vm/run
              ast)))

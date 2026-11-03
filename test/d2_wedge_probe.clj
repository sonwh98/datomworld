(ns d2-wedge-probe
  (:require [yang.latte-oracle-test :as t]
            [yin.vm :as vm]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [dao.stream.observer :as observer]
            [yin.vm.engine :as engine]
            [clojure.string :as str]))
(def step-count (atom 0))
(def last-machine (atom nil))
(def original-step vm/step)

(defn counted-step [machine]
  (let [n (swap! step-count inc)]
    (when (= n 1) (println :STEP-STARTED) (flush))
    (when (zero? (mod n 250000)) (println :STEPS n) (flush))
    (when (> n 2000000)
      (println :BUDGET-THROWN-AT n)
      (flush)
      (throw (ex-info "probe budget exhausted" {:steps n})))
    (vreset! last-machine machine)
    (original-step machine)))

(defn bounded-run [machine]
  (loop [v machine n 0]
    (cond
      (or (vm/halted? v) (vm/blocked? v)) v
      (= n 2000000)
      (throw (ex-info "Guest instruction budget exhausted"
                      {:lane :semantic :steps n :consumer v}))
      :else (recur (counted-step v) (inc n)))))

(defn make-load-rows []
  (vm/fresh-code-loader
    (fn [m rows stamp]
      (vm/check-contract! vm/ast-contract stamp)
      (semantic/load-vector
        m
        (:vector ((deref (resolve 'yin.vm.semantic.linearize/project-rows)) rows))
        vm/semantic-contract))
    vm/ast-contract))

(defn -main []
  (let [kgc (deref #'yang.latte-oracle-test/kernel-gate-corpus)
        fixture (first (filter #(= :check-assume-have-discharge (:id %)) (:fixtures kgc)))
        compiled ((deref #'yang.latte-oracle-test/kernel-compile) fixture)
        ast (:ast compiled)
        lm (deref #'yang.clojure-test/library-modules)]
    (println :PIN {:commit "805d81d6"
                   :fixture :check-assume-have-discharge
                   :lane :semantic
                   :budget "2M instrumented"})
    (flush)
    (with-redefs [vm/step counted-step]
      (let [worker (future
                     (try
                       (let [machine (semantic/create-vm
                                       {:make-stream tu/make-stream
                                        :capability-secret tu/secret
                                        :modules lm})
                             session (tu/make-observer-session machine)
                             load-rows (make-load-rows)]
                         (println :DRIVING-EVALUATOR-DIRECT)
                         (flush)
                         (let [done (observer/run-on-stream
                                      session
                                      engine/ready-for-ingress?
                                      load-rows
                                      bounded-run)]
                           (println :ROUND-COMPLETE
                                    {:halted (vm/halted? (:consumer done))
                                     :blocked (vm/blocked? (:consumer done))})
                           (flush)))
                       (catch Exception e
                         (println :WORKER-CAUGHT (ex-message e))
                         (flush))))]
        (loop [last-sampled -1 samples 0]
          (Thread/sleep 15000)
          (let [now @step-count]
            (cond
              (realized? worker)
              (do (println :WORKER-FINISHED) (flush) @worker)
              (= now last-sampled)
              (do (println :WATCHDOG-FROZEN :steps now :samples (inc samples))
                  (flush)
                  (if (>= (inc samples) 2)
                    (let [m @last-machine]
                      (println :WEDGE-CONFIRMED
                               {:frozen-at now
                                :halted (when m (vm/halted? m))
                                :blocked (when m (vm/blocked? m))
                                :cont-depth (when m (count (vm/continuation m)))})
                      (flush))
                    (recur now (inc samples))))
              :else
              (do (println :PROGRESS now) (flush) (recur now 0))))))))
  (System/exit 0))

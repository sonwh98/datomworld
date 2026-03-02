(ns yin.demo
  "Two Register VMs cooperatively compute sum(0..100) by passing
   continuations through a stream.

   Each VM adds one number to the running sum, parks its continuation,
   serializes the full execution state as a datom to a shared stream,
   and the other VM picks it up and resumes.

   The continuation carries everything: registers, instruction pointer,
   call stack, environment, bytecode, constant pool. When it migrates
   through the stream, the receiving VM resumes exactly where the
   sender left off.

   Run: clj -M -m yin.demo"
  (:require
    [yin.vm :as vm]
    [yin.vm.register :as reg]))


;; =============================================================================
;; The Program
;; =============================================================================
;;
;; Recursive sum with explicit park after each step:
;;
;;   (define step (fn [n acc]
;;     (if (> n 100)
;;       acc
;;       ((fn [new-acc]
;;          ((fn [_] (step (+ n 1) new-acc))
;;           (park)))
;;        (+ acc n)))))
;;   (step 0 0)
;;
;; After computing new-acc = acc + n, the VM parks.
;; The parked continuation captures: n, new-acc, the recursive call to step,
;; and the entire call stack. When resumed (with any value), it calls
;; (step (+ n 1) new-acc) and the cycle repeats.
;;
;; The final iteration (n > 100) returns acc directly without parking.

(def define-step-ast
  "AST: (yin/def 'step (fn [n acc] ...))"
  {:type :application,
   :operator {:type :variable, :name 'yin/def},
   :operands
   [{:type :literal, :value 'step}
    {:type :lambda,
     :params ['n 'acc],
     :body {:type :if,
            :test {:type :application,
                   :operator {:type :variable, :name '>},
                   :operands [{:type :variable, :name 'n}
                              {:type :literal, :value 100}]},
            :consequent {:type :variable, :name 'acc},
            :alternate
            {:type :application,
             :operator
             {:type :lambda,
              :params ['new-acc],
              :body {:type :application,
                     :operator
                     {:type :lambda,
                      :params ['_ignored],
                      :body {:type :application,
                             :operator {:type :variable, :name 'step},
                             :operands
                             [{:type :application,
                               :operator {:type :variable, :name '+},
                               :operands [{:type :variable, :name 'n}
                                          {:type :literal, :value 1}]}
                              {:type :variable, :name 'new-acc}]}},
                     :operands [{:type :vm/park}]}},
             :operands [{:type :application,
                         :operator {:type :variable, :name '+},
                         :operands [{:type :variable, :name 'acc}
                                    {:type :variable, :name 'n}]}]}}}]})


(def call-step-ast
  "AST: (step 0 0)"
  {:type :application,
   :operator {:type :variable, :name 'step},
   :operands [{:type :literal, :value 0} {:type :literal, :value 0}]})


;; =============================================================================
;; Stream: a plain vector used as an append-only log
;; =============================================================================
;; No VM-level stream needed. The stream is Clojure data: a vector of datoms.
;; Each datom is [e a v t m] carrying a serialized continuation.

(defn stream-put
  "Append a continuation datom to the stream."
  [stream continuation tx]
  (conj stream [tx :continuation/state continuation tx 0]))


(defn stream-read
  "Read the continuation value from the latest datom."
  [stream]
  (let [[_e _a v _t _m] (peek stream)] v))


;; =============================================================================
;; Continuation Transfer
;; =============================================================================

(defn extract-continuation
  "Extract the parked continuation from a VM that has parked.
   Returns [parked-id parked-cont]."
  [vm]
  (let [parked (:parked vm)] (first parked)))


(defn inject-continuation
  "Inject a parked continuation into a VM's parked map and resume it.
   Returns the VM after resuming (runs until next park or halt)."
  [vm parked-id parked-cont]
  (let [;; Inject the continuation and its store into the receiving VM
        vm-with-cont (assoc vm
                            :parked (assoc (:parked vm) parked-id parked-cont)
                            :store (:store vm))
        ;; Build a resume AST: (resume parked-id nil)
        resume-ast {:type :vm/resume,
                    :parked-id parked-id,
                    :val {:type :literal, :value nil}}]
    (vm/eval vm-with-cont resume-ast)))


;; =============================================================================
;; Demo Driver
;; =============================================================================

(defn run-demo
  []
  (println "=== Cooperative Sum: Two Register VMs, One Computation ===")
  (println)
  (println "Computing sum(0..100) = 5050")
  (println
    "Each VM adds one number, then passes its continuation to the other.")
  (println "Continuations travel as datoms through a shared stream.")
  (println)
  ;; Create two VMs and define 'step on both
  (let [vm-a (-> (reg/create-vm {:env vm/primitives})
                 (vm/eval define-step-ast))
        vm-b (-> (reg/create-vm {:env vm/primitives})
                 (vm/eval define-step-ast))]
    (println "VM-A and VM-B initialized. 'step' function defined on both.")
    (println)
    ;; VM-A starts the computation: (step 0 0)
    ;; This will compute 0+0=0, then park.
    (loop [current-vm (vm/eval vm-a call-step-ast)
           other-vm vm-b
           current-name "VM-A"
           other-name "VM-B"
           stream []
           step-count 0]
      (cond
        ;; Computation complete: VM halted without parking
        (and (vm/halted? current-vm) (empty? (:parked current-vm)))
        (do (println)
            (println (format "=== %s computed final result: %d ==="
                             current-name
                             (vm/value current-vm)))
            (println (format "Total continuation transfers: %d" step-count))
            (println (format "Stream length: %d datoms" (count stream)))
            (vm/value current-vm))
        ;; VM parked: extract continuation, serialize to stream, hand to
        ;; other VM
        (vm/halted? current-vm)
        (let [[parked-id parked-cont] (extract-continuation current-vm)
              ;; The continuation's env tells us where we are in the
              ;; computation
              n (get (:env parked-cont) 'n)
              new-acc (get (:env parked-cont) 'new-acc)
              ;; Serialize continuation to stream as a datom
              stream' (stream-put stream parked-cont step-count)]
          (when (< step-count 5)
            (println
              (format
                "  [%s] step %3d: added %d, sum=%d -> parked, sending to %s"
                current-name
                step-count
                n
                new-acc
                other-name)))
          (when (= step-count 5) (println "  ..."))
          (when (>= step-count 96)
            (println
              (format
                "  [%s] step %3d: added %d, sum=%d -> parked, sending to %s"
                current-name
                step-count
                n
                new-acc
                other-name)))
          ;; Other VM reads continuation from stream and resumes
          (let [cont-from-stream (stream-read stream')
                ;; Transfer store (contains 'step function) along with
                ;; continuation
                other-vm' (assoc other-vm :store (:store current-vm))
                resumed-vm
                (inject-continuation other-vm' parked-id cont-from-stream)]
            (recur resumed-vm
                   ;; The current VM becomes the "other" for next round
                   ;; Reset its parked state so it's clean for next
                   ;; injection
                   (assoc current-vm :parked {})
                   other-name
                   current-name
                   stream'
                   (inc step-count))))
        ;; Blocked (shouldn't happen in this demo)
        :else (do (println (format "  [%s] blocked unexpectedly" current-name))
                  nil)))))


(defn -main
  [& _args]
  (let [result (run-demo)]
    (println)
    (if (= 5050 result)
      (println "SUCCESS: sum(0..100) = 5050")
      (println (format "ERROR: expected 5050, got %s" result)))))

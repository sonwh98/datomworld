(ns yin.demo
  "Two Yin VMs (v2, DaoStream v2) cooperatively compute sum(0..100) by passing
   continuations through a stream.

   Each VM adds one number to the running sum, parks its continuation,
   serializes the full execution state as a datom to a shared stream,
   and the other VM picks it up and resumes.

   The continuation carries everything: reified continuation frames,
   lexical environment, store. When it migrates through the stream, the
   receiving VM resumes exactly where the sender left off.

   What the v2 port changes relative to `yin.demo`:

   - The evaluator is `yin.vm.ast-walker`. `yin.vm` is the ast-walker
     slice; `register` and `stack` are not ported.
   - The shared stream is a real `dao.stream` medium, not a bare vector:
     the host supplies `:make-stream` and the composition appends and reads
     through the writer and reader surfaces it created.
   - Continuations still travel as values, so the medium carries them as
     whole-batch appends and the receiver reads with a minted cursor. No
     position arithmetic: every advance is the successor the transport
     returned.

   Run: clj -M -m yin.demo"
  (:require [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]))


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
;; and the whole reified continuation. When resumed (with any value), it calls
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
;; The shared medium: one DaoStream v2 ring buffer
;; =============================================================================
;; Continuations move as whole-batch appends; each reader advances by the
;; exact successor `next` returns. Nothing here fabricates a position.

(def stream-capacity 1024)


(defn make-stream
  "The `:make-stream` the composition hands each VM: one v2 ring buffer per
   call. A nil capacity is the VM's default, not an unbounded stream."
  [capacity]
  (ringbuffer/create!
    {:dao.stream/type ringbuffer/transport-type,
     ringbuffer/capacity-key (or capacity vm/default-stream-capacity)}))


(defn new-stream
  "Create one ring buffer handle or throw."
  []
  (let [result (make-stream stream-capacity)]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "Demo stream creation failed" {:result result})))))


(defn put-continuation!
  "Append one continuation as a single-element batch. Returns the result so
   the caller can see the outcome."
  [writer continuation]
  (stream/append! writer [continuation]))


(defn read-continuation!
  "Read one continuation at the reader's cursor, advancing to the exact
   successor. `next` returns a whole batch, so the single element is the
   continuation this medium carries; anything else is a lost read and is
   reported rather than returned."
  [reader cursor]
  (let [result (stream/next reader cursor)
        outcome (:dao.stream/outcome result)
        batch (:dao.stream/value result)]
    (if (and (= :dao.stream/ok outcome) (= 1 (count batch)))
      {:value (nth batch 0), :cursor (:dao.stream/cursor result)}
      (throw (ex-info "Continuation read failed"
                      {:outcome outcome, :batch-size (count batch)})))))


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
  (let [;; Inject the continuation into the receiving VM
        vm-with-cont (assoc vm :parked (assoc (:parked vm) parked-id parked-cont))
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
  (println "=== Cooperative Sum: Two Yin VMs (v2), One Computation ===")
  (println)
  (println "Computing sum(0..100) = 5050")
  (println
    "Each VM adds one number, then passes its continuation to the other.")
  (println "Continuations travel as batches through a DaoStream v2 medium.")
  (println)
  ;; One shared medium. The composition is its only writer and, across the
  ;; two VMs taking turns, its only reader.
  (let [medium (new-stream)
        writer medium
        reader-cursor* (atom (vm/mint-oldest medium :continuation-reader))
        ;; Create two VMs and define 'step on both
        vm-a (-> (ast-walker/create-vm {:env {}, :primitives vm/primitives,
                                        :make-stream make-stream})
                 (vm/eval define-step-ast))
        vm-b (-> (ast-walker/create-vm {:env {}, :primitives vm/primitives,
                                        :make-stream make-stream})
                 (vm/eval define-step-ast))]
    (println "VM-A and VM-B initialized. 'step' function defined on both.")
    (println)
    ;; VM-A starts the computation: (step 0 0)
    ;; This will compute 0+0=0, then park.
    (loop [current-vm (vm/eval vm-a call-step-ast)
           other-vm vm-b
           current-name "VM-A"
           other-name "VM-B"
           cursor @reader-cursor*
           step-count 0]
      (cond
        ;; Computation complete: VM halted without parking
        (and (vm/halted? current-vm) (empty? (:parked current-vm)))
        (do (println)
            (println (format "=== %s computed final result: %d ==="
                             current-name
                             (vm/value current-vm)))
            (println (format "Total continuation transfers: %d" step-count))
            (reset! reader-cursor* cursor)
            (vm/value current-vm))
        ;; VM parked: extract continuation, append it to the medium, hand to
        ;; the other VM
        (vm/halted? current-vm)
        (let [[parked-id parked-cont] (extract-continuation current-vm)
              ;; The continuation's env tells us where we are in the
              ;; computation
              n (get (:env parked-cont) 'n)
              new-acc (get (:env parked-cont) 'new-acc)
              ;; Serialize the continuation onto the medium as one batch
              put-result (put-continuation! writer parked-cont)
              outcome (:dao.stream/outcome put-result)]
          (when-not (= :dao.stream/ok outcome)
            (throw (ex-info "Continuation append failed" {:outcome outcome})))
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
          ;; Other VM reads the continuation from the medium and resumes it
          (let [{cont-from-stream :value, cursor' :cursor}
                (read-continuation! medium cursor)
                ;; Transfer the store (holds the 'step closure) along with
                ;; the continuation
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
                   cursor'
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

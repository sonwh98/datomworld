(ns bench.stream-optimization-bench
  (:require
    [dao.stream :as ds]
    [yin.stream :as stream]
    [yin.vm.engine :as engine]))


;; =============================================================================
;; Benchmark Setup
;; =============================================================================

(defn- make-bench-state
  "Create VM state with N reader-writer pairs.
   Store format:
     stream-id -> RingBufferStream
     cursor-id -> {:stream-ref {:type :stream-ref :id stream-id}
                   :stream-id stream-id
                   :position 0}"
  [n capacity]
  (let [state {:store {} :id-counter 1}
        ;; 1. Create N streams
        streams (mapv (fn [i]
                        (let [id (+ 1000 i)
                              stream (ds/open! {:type :ringbuffer :capacity capacity})]
                          [id stream]))
                      (range n))
        store (into {} (map (fn [[id s]] [id s]) streams))
        ;; 2. Create N cursors
        cursors (mapv (fn [i]
                        (let [stream-id (+ 1000 i)
                              cursor-id (+ 2000 i)
                              cursor {:stream-ref {:type :stream-ref :id stream-id}
                                      :stream-id stream-id
                                      :position 0}]
                          [cursor-id cursor]))
                      (range n))
        store (into store cursors)]
    (assoc state :store store)))


(defn- make-waiter
  "Create a waiter entry for a specific cursor."
  [cursor-id stream-id]
  {:reason :next
   :cursor-ref {:type :cursor-ref :id cursor-id}
   :stream-id stream-id})


;; =============================================================
;; Transport-Local Waking (The New Way)
;; =============================

(defn- bench-new-waking
  "Transfer M values through N reader-writer pairs using transport-local waking.
   Readers re-park at the transport level after every wake."
  [n m capacity]
  (let [state (make-bench-state n capacity)
        ;; Initial registration of all N readers at position 0
        _ (doseq [i (range n)]
            (let [stream-id (+ 1000 i)
                  cursor-id (+ 2000 i)
                  stream (get (:store state) stream-id)
                  waiter (make-waiter cursor-id stream-id)]
              (ds/register-reader-waiter! stream 0 waiter)))

        run-bench (fn []
                    (loop [remaining-m m
                           idx 0
                           s state]
                      (if (zero? remaining-m)
                        s
                        (let [stream-idx (mod idx n)
                              stream-id (+ 1000 stream-idx)
                              _          (+ 2000 stream-idx)
                              stream-ref {:type :stream-ref :id stream-id}
                              val remaining-m
                              ;; 1. Put value
                              result (stream/handle-put s {:effect :stream/put :stream stream-ref :val val})
                              woken (:woke result)
                              ;; 2. Process woken entries (if any)
                              woken-entries (#'engine/make-woken-run-queue-entries (:state result) woken)
                              ;; 3. Update store (advances cursor positions)
                              s' (:state result)
                              store-updates (mapcat (fn [e] (vec (:store-updates e))) woken-entries)
                              s'' (update s' :store merge (into {} store-updates))
                              ;; 4. Re-park woken readers at their new positions
                              _ (doseq [entry woken-entries]
                                  (let [c-id (:id (:cursor-ref entry))
                                        c-data (get (:store s'') c-id)
                                        s-id (:stream-id c-data)
                                        strm (get (:store s'') s-id)
                                        pos (:position c-data)
                                        new-waiter (make-waiter c-id s-id)]
                                    (ds/register-reader-waiter! strm pos new-waiter)))]
                          (recur (dec remaining-m) (inc idx) s'')))))]
    (run-bench)))


;; =============================================================
;; VM Scheduler Polling (The Old Way)
;; =============================

(defn- bench-old-polling
  "Transfer M values through N reader-writer pairs using simulated polling.
   Readers re-park in the VM wait-set after every wake."
  [n m capacity]
  (let [state (make-bench-state n capacity)
        ;; Initial park of all N readers in wait-set
        waiters (mapv (fn [i] (make-waiter (+ 2000 i) (+ 1000 i))) (range n))
        state (assoc state :wait-set waiters)

        ;; Simulated old check-wait-set (O(N) iteration every tick)
        ;; Using transients for vector building to avoid excessive allocation
        poll-wait-set (fn [s]
                        (let [wait-set (:wait-set s)]
                          (reduce (fn [acc entry]
                                    (let [result (stream/handle-next acc {:effect :stream/next :cursor (:cursor-ref entry)})]
                                      (if (:park result)
                                        (update acc :wait-set-transient conj! entry)
                                        (let [updated-store (:store (:state result))
                                              cursor-id (:id (:cursor-ref entry))
                                              cursor-update {cursor-id (get updated-store cursor-id)}]
                                          (-> acc
                                              (update :run-queue-transient conj! (assoc entry :value (:value result) :store-updates cursor-update))
                                              (assoc :store updated-store))))))
                                  (assoc s
                                         :wait-set-transient (transient [])
                                         :run-queue-transient (transient []))
                                  wait-set)))

        run-bench (fn []
                    (loop [remaining-m m
                           idx 0
                           s state]
                      (if (zero? remaining-m)
                        s
                        (let [stream-idx (mod idx n)
                              stream-id (+ 1000 stream-idx)
                              stream-ref {:type :stream-ref :id stream-id}
                              val remaining-m
                              ;; 1. Put value (ignores :woke to simulate old way)
                              result (stream/handle-put s {:effect :stream/put :stream stream-ref :val val})
                              s' (:state result)
                              ;; 2. Poll wait-set (this is the O(N) bottleneck)
                              s'' (poll-wait-set s')
                              ;; Persistent-ize vectors
                              wait-set (persistent! (:wait-set-transient s''))
                              run-queue (persistent! (:run-queue-transient s''))
                              s'' (-> s''
                                      (dissoc :wait-set-transient :run-queue-transient)
                                      (assoc :wait-set wait-set :run-queue run-queue))

                              ;; 3. Re-park woken readers
                              s''' (if (seq run-queue)
                                     (update s'' :wait-set into (mapv #(dissoc % :value :store-updates) run-queue))
                                     s'')]
                          (recur (dec remaining-m) (inc idx) (assoc s''' :run-queue []))))))]
    (run-bench)))


;; =============================================================================
;; Main Benchmark Runner
;; =============================================================================

(defn -main
  [& _args]
  (let [n 500       ; 500 concurrent reader-writer pairs
        m 2000      ; 2,000 datom transfers
        capacity 10]

    (println "\nStarting Benchmarks (N=500 pairs, M=2,000 transfers)")
    (println "-----------------------------------------------------------------------")

    ;; Warm up
    (println "Warming up...")
    (dotimes [_ 3]
      (bench-new-waking n 1000 capacity)
      (bench-old-polling n 1000 capacity))

    (println "\n>>> Running Scenario 1: Transport-Local Waking (New Way)")
    (time (dotimes [_ 10] (bench-new-waking n m capacity)))

    (println "\n>>> Running Scenario 2: VM Scheduler Polling (Old Way)")
    (time (dotimes [_ 10] (bench-old-polling n m capacity)))))

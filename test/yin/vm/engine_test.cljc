(ns yin.vm.engine-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [dao.stream.transport.ringbuffer]
    [yin.stream :as stream]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]))


(deftest run-loop-resumes-when-blocked-with-preexisting-run-queue-test
  (testing "Blocked VM still resumes from an already non-empty run-queue"
    (let [resume-calls (atom 0)
          state {:blocked true,
                 :halted false,
                 :run-queue [{:value 42}],
                 :wait-set []}
          result (engine/run-loop state
                                  (fn [_] false)
                                  (fn [v] v)
                                  (fn [v]
                                    (swap! resume-calls inc)
                                    (when-let [entry (first (:run-queue v))]
                                      (assoc v
                                             :value (:value entry)
                                             :run-queue (vec (rest (:run-queue v)))
                                             :blocked false
                                             :halted true))))]
      (is (= 1 @resume-calls))
      (is (= 42 (:value result)))
      (is (false? (:blocked result)))
      (is (true? (:halted result)))
      (is (empty? (:run-queue result))))))


(deftest run-loop-noop-when-blocked-and-no-runnable-work-test
  (testing
    "Blocked VM remains blocked if scheduler cannot wake and run-queue is empty"
    (let [state {:blocked true, :halted false, :run-queue [], :wait-set []}
          result (engine/run-loop state (fn [_] false) (fn [v] v) (fn [_] nil))]
      (is (true? (:blocked result)))
      (is (false? (:halted result)))
      (is (empty? (:run-queue result))))))


;; =============================================================================
;; Fallback Scheduler Tests (Non-waitable streams)
;; =============================================================================

(defrecord NonWaitableStream
  [state-atom]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [s @state-atom
          pos (:position cursor)]
      (if (contains? (:buffer s) pos)
        {:ok (get (:buffer s) pos), :cursor {:position (inc pos)}}
        :blocked)))


  ds/IDaoStreamWriter

  (put!
    [_this val]
    (let [s @state-atom
          tail (:tail s)]
      (swap! state-atom (fn [s] (-> s (assoc-in [:buffer tail] val) (update :tail inc))))
      {:result :ok}))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(deftest check-wait-set-fallback-test
  (testing "check-wait-set still polls non-waitable streams in the scheduler"
    (let [state-atom (atom {:buffer {}, :tail 0, :closed false})
          stream (->NonWaitableStream state-atom)
          state {:store {:s1 stream, :c1 {:stream-id :s1, :position 0}}, :id-counter 0}
          ;; 1. Park reader (not waitable, so it goes to wait-set)
          reader-entry {:reason :next, :cursor-ref {:type :cursor-ref, :id :c1}}
          state (assoc state :wait-set [reader-entry])
          ;; 2. check-wait-set should NOT wake it (stream empty)
          state-still-blocked (#'yin.vm.engine/check-wait-set state)
          _ (is (= 1 (count (:wait-set state-still-blocked))))
          ;; 3. Put data manually
          _ (ds/put! stream 42)
          ;; 4. check-wait-set should now wake it
          state-runnable (#'yin.vm.engine/check-wait-set state-still-blocked)]
      (is (empty? (:wait-set state-runnable)))
      (is (= 1 (count (:run-queue state-runnable))))
      (is (= 42 (:value (first (:run-queue state-runnable))))))))


(deftest check-wait-set-put-fallback-test
  (testing "check-wait-set still polls :put on non-waitable streams"
    (let [state-atom (atom {:buffer {}, :tail 0, :closed false})
          stream (->NonWaitableStream state-atom)
          state {:store {:s1 stream}, :id-counter 0}
          ;; Park writer
          writer-entry {:reason :put, :stream-id :s1, :datom :val}
          state (assoc state :wait-set [writer-entry])
          ;; In this simple mock put! always succeeds, so first poll should wake it
          state-runnable (#'yin.vm.engine/check-wait-set state)]
      (is (empty? (:wait-set state-runnable)))
      (is (= 1 (count (:run-queue state-runnable))))
      (is (= :val (get-in @state-atom [:buffer 0])) "Datom should be written"))))


(deftest check-wait-set-resumes-closed-put-fallback-test
  (testing "check-wait-set resumes closed non-waitable :put waiters with nil"
    (let [state-atom (atom {:buffer {}, :tail 0, :closed false})
          stream (->NonWaitableStream state-atom)
          state {:store {:s1 stream}, :id-counter 0}
          writer-entry {:reason :put, :stream-id :s1, :datom :val}
          state (assoc state :wait-set [writer-entry])
          _ (ds/close! stream)
          checked (#'yin.vm.engine/check-wait-set state)]
      (is (empty? (:wait-set checked)))
      (is (= 1 (count (:run-queue checked))))
      (is (nil? (:value (first (:run-queue checked)))))
      (is (true? (ds/closed? stream)))
      (is (empty? (:buffer @state-atom)))
      (is (= 0 (:tail @state-atom))))))


(defrecord NonWaitableRingBufferStream
  [state-atom]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [s @state-atom
          head (:head s)
          pos (:position cursor)]
      (cond (>= pos (:tail s)) :blocked
            (< pos head) :daostream/gap
            :else {:ok (get-in s [:buffer pos]), :cursor {:position (inc pos)}})))


  ds/IDaoStreamWriter

  (put!
    [_this val]
    (let [s @state-atom
          capacity 1] ; fixed capacity for test
      (if (>= (- (:tail s) (:head s)) capacity)
        {:result :full}
        (do (swap! state-atom (fn [s] (-> s (assoc-in [:buffer (:tail s)] val) (update :tail inc))))
            {:result :ok}))))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(deftest check-wait-set-put-fallback-after-capacity-freed-test
  (testing "check-wait-set still polls :put on non-waitable streams after capacity is freed"
    (let [state-atom (atom {:buffer {0 :a}, :tail 1, :head 0, :closed false})
          stream (->NonWaitableRingBufferStream state-atom)
          state {:store {:s1 stream}, :id-counter 0}
          ;; 1. Park writer on :put because it's full (capacity 1)
          writer-entry {:reason :put, :stream-id :s1, :datom :b}
          state (assoc state :wait-set [writer-entry])
          ;; 2. check-wait-set should NOT wake it
          state-blocked (#'yin.vm.engine/check-wait-set state)
          _ (is (= 1 (count (:wait-set state-blocked))))
          ;; 3. Manually drain (advance head)
          _ (swap! state-atom (fn [s] (-> s (update :buffer dissoc 0) (update :head inc))))
          ;; 4. check-wait-set should now wake it via polling
          state-runnable (#'yin.vm.engine/check-wait-set state-blocked)]
      (is (empty? (:wait-set state-runnable)))
      (is (= 1 (count (:run-queue state-runnable))))
      (is (= :b (get-in @state-atom [:buffer 1])) "Writer should have written :b"))))


(deftest close-enqueues-flattened-transport-writer-entry-test
  (testing
    "stream close should enqueue the parked writer entry itself, not a nested {:entry ...} wrapper"
    (let [state {:store {}, :id-counter 0, :run-queue []}
          [id s'] (engine/gensym state "stream")
          [stream-ref state] (stream/handle-make s' {:capacity 1} id)
          state (:state (stream/handle-put state {:stream stream-ref, :val 1}))
          put-effect {:effect :stream/put, :stream stream-ref, :val 2}
          put-result (engine/handle-effect
                       state
                       put-effect
                       {:park-entry-fns
                        {:stream/put
                         (fn [_s _e r]
                           {:reason :put,
                            :stream-id (:stream-id r),
                            :datom 2,
                            :k {:id :writer-1}})}})
          blocked-state (:state put-result)
          close-result (engine/handle-effect blocked-state
                                             {:effect :stream/close, :stream stream-ref}
                                             {})
          run-entry (first (get-in close-result [:state :run-queue]))]
      (is (:blocked? put-result))
      (is (= 1 (count (get-in close-result [:state :run-queue]))))
      (is (= {:id :writer-1} (:k run-entry))
          "Queued writer should keep its original continuation fields at top level")
      (is (false? (contains? run-entry :entry))
          "Run-queue entry should be flattened before resumption")
      (is (nil? (:value run-entry))))))


(deftest take-enqueues-reader-cursor-advance-when-waking-reader-and-writer-test
  (testing
    "stream take should preserve cursor advance metadata when it wakes a parked reader and writer together"
    (let [state {:store {}, :id-counter 0, :run-queue []}
          [stream-id s'] (engine/gensym state "stream")
          [stream-ref state] (stream/handle-make s' {:capacity 1} stream-id)
          state (:state (stream/handle-put state {:stream stream-ref, :val :a}))
          [cursor-id s''] (engine/gensym state "cursor")
          [cursor-ref state] (stream/handle-cursor s'' {:stream stream-ref} cursor-id)
          state (:state (stream/handle-next state {:cursor cursor-ref}))
          next-result (engine/handle-effect
                        state
                        {:effect :stream/next, :cursor cursor-ref}
                        {:park-entry-fns
                         {:stream/next
                          (fn [_s _e r]
                            {:reason :next,
                             :cursor-ref (:cursor-ref r),
                             :stream-id (:stream-id r),
                             :k {:id :reader}})}})
          put-result (engine/handle-effect
                       (:state next-result)
                       {:effect :stream/put, :stream stream-ref, :val :b}
                       {:park-entry-fns
                        {:stream/put
                         (fn [_s _e r]
                           {:reason :put,
                            :stream-id (:stream-id r),
                            :datom :b,
                            :k {:id :writer}})}})
          stream-after-next (get-in next-result [:state :store stream-id])
          stream-after-put (get-in put-result [:state :store stream-id])
          reader-waiter-count-after-next (count (:reader-waiters @(.-state-atom ^dao.stream.transport.ringbuffer.RingBufferStream stream-after-next)))
          writer-waiter-count-after-put (count (:writer-waiters @(.-state-atom ^dao.stream.transport.ringbuffer.RingBufferStream stream-after-put)))
          take-result (engine/handle-effect (:state put-result)
                                            {:effect :stream/take, :stream stream-ref}
                                            {})
          run-queue (get-in take-result [:state :run-queue])
          reader-entry (some #(when (= {:id :reader} (:k %)) %) run-queue)
          writer-entry (some #(when (= {:id :writer} (:k %)) %) run-queue)]
      (is (:blocked? next-result))
      (is (:blocked? put-result))
      (is (empty? (or (get-in next-result [:state :wait-set]) [])))
      (is (empty? (or (get-in put-result [:state :wait-set]) [])))
      (is (= 1 reader-waiter-count-after-next))
      (is (= 1 writer-waiter-count-after-put))
      (is (= :a (:value take-result)))
      (is (= 2 (count run-queue)))
      (is (= :b (:value writer-entry)))
      (is (= :b (:value reader-entry)))
      (is (= {cursor-id {:stream-id stream-id, :position 2}}
             (:store-updates reader-entry))
          "Reader wake from take! must advance the parked cursor past the appended datom"))))


(deftest daocall-dispatch-and-wake-test
  (testing "dao.stream.apply response wakes caller and advances cursor"
    (let [call-out (ds/open! {:transport {:type :ringbuffer, :capacity nil}})
          state {:store {vm/call-out-stream-key call-out,
                         vm/call-out-cursor-key {:stream-id vm/call-out-stream-key,
                                                 :position 0}},
                 :parked {:parked-0 {:id :parked-0,
                                     :type :parked-continuation,
                                     :k {:id :cont-0},
                                     :env {}}},
                 :run-queue [],
                 :wait-set [],
                 :blocked true,
                 :halted false}
          ;; 1. Register reader-waiter on call-out (happens during dao-call)
          waiter-entry {:cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key}
                        :reason :next
                        :stream-id vm/call-out-stream-key
                        :k {:id :cont-0}}
          _ (ds/register-reader-waiter! call-out 0 waiter-entry)

          ;; 2. Bridge puts response to call-out
          response (dao.stream.apply/response :parked-0 42)
          put-result (ds/put! call-out response)
          woke (:woke put-result)
          _ (is (= 1 (count woke)))

          ;; 3. Use engine to process woken entries
          entries (engine/make-woken-run-queue-entries state woke)
          next-state (update state :run-queue into entries)
          run-entry (first (:run-queue next-state))]

      (is (= 1 (count (:run-queue next-state))))
      (is (= response (:value run-entry)))
      (is (= {vm/call-out-cursor-key {:stream-id vm/call-out-stream-key, :position 1}}
             (:store-updates run-entry))))))

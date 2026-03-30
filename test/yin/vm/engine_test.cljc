(ns yin.vm.engine-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
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


;; NOTE: :next entries are now handled by transport-local readers (IDaoStreamWaitable).
;; check-wait-set no longer processes :next entries. Readers are registered directly
;; on the transport via register-reader-waiter! and woken via put! return values.
;; See handle-effect :stream/next for the new path.


(deftest check-wait-set-stream-put-test
  (testing
    "check-wait-set wakes up a :put parked continuation when capacity becomes available"
    (let [state {:store {}, :id-counter 0}
          ;; 1. Make stream with capacity 1
          [id s'] (engine/gensym state "stream")
          [stream-ref state]
          (stream/handle-make s' {:capacity 1} (constantly id))
          stream-id (:id stream-ref)
          ;; 2. Fill the stream
          state (:state (stream/handle-put state {:stream stream-ref, :val 1}))
          ;; 3. Park a continuation for :put
          parked-entry {:reason :put,
                        :stream-id stream-id,
                        :datom 2,
                        :continuation {:type :some-cont}}
          state (assoc state
                       :wait-set [parked-entry]
                       :run-queue [])
          ;; 4. check-wait-set should NOT wake it yet (stream is full)
          state-still-blocked (#'yin.vm.engine/check-wait-set state)
          _ (is (= 1 (count (:wait-set state-still-blocked))))
          _ (is (empty? (:run-queue state-still-blocked)))
          ;; 5. Manually increase capacity in the store to simulate space
          ;; becoming available
          state-with-capacity
          (update-in state-still-blocked [:store stream-id] assoc :capacity 2)
          ;; 6. check-wait-set should now wake it
          state-runnable (#'yin.vm.engine/check-wait-set state-with-capacity)]
      (is (empty? (:wait-set state-runnable)))
      (is (= 1 (count (:run-queue state-runnable))))
      (is (= 2 (:value (first (:run-queue state-runnable))))))))


(deftest check-wait-set-closed-stream-put-waiter-test
  (testing
    "check-wait-set should not throw when a stream is closed with parked :put entries"
    (let [state {:store {}, :id-counter 0}
          ;; 1. Make stream with capacity 1 and fill it.
          [id s'] (engine/gensym state "stream")
          [stream-ref state]
          (stream/handle-make s' {:capacity 1} (constantly id))
          stream-id (:id stream-ref)
          state (:state (stream/handle-put state {:stream stream-ref, :val 1}))
          ;; 2. Park a :put continuation.
          parked-entry {:reason :put,
                        :stream-id stream-id,
                        :datom 2,
                        :continuation {:type :some-cont}}
          state (assoc state
                       :wait-set [parked-entry]
                       :run-queue [])
          ;; 3. Close stream. :put waiters are intentionally left in
          ;; wait-set.
          close-result (stream/handle-close state {:stream stream-ref})
          closed-state (:state close-result)
          ;; 4. Scheduler re-check should drop the :put waiter silently.
          checked (#'yin.vm.engine/check-wait-set closed-state)
          ;; 5. Second check should be idempotent (nothing left to
          ;; process).
          checked-again (#'yin.vm.engine/check-wait-set checked)
          stream-after (get-in checked-again [:store stream-id])]
      (is (empty? (:wait-set checked))
          "Closed-stream :put waiter should be dropped, not retained")
      (is (empty? (:run-queue checked))
          "Closed-stream :put waiter should not be resumed")
      (is (= checked checked-again)
          "Scheduler should be stable after dropping closed-stream :put waiter")
      (is (ds/closed? stream-after) "Closed-stream state must be preserved")
      (is (= 1 (ds/count-available stream-after))
          "Dropped :put waiter must not append into closed stream"))))


(deftest check-wait-set-put-writer-parking-test
  (testing
    "check-wait-set wakes up a :put parked continuation when data becomes available"
    (let [state {:store {}, :id-counter 0}
          ;; 1. Make stream with capacity 1
          [id s'] (engine/gensym state "stream")
          [stream-ref state]
          (stream/handle-make s' {:capacity 1} id)
          stream-id (:id stream-ref)
          ;; 2. Fill the stream
          state (:state (stream/handle-put state {:stream stream-ref, :val 1}))
          ;; 3. Park a continuation for :put (blocked because full)
          parked-entry {:reason :put,
                        :stream-id stream-id,
                        :datom 2,
                        :continuation {:id :e1}}
          state (assoc state
                       :wait-set [parked-entry]
                       :run-queue [])
          ;; 4. Manually simulate space becoming available
          state-with-capacity (update-in state [:store stream-id] assoc :capacity 2)
          ;; 5. check-wait-set should now wake the :put waiter
          result (#'yin.vm.engine/check-wait-set state-with-capacity)]
      (is (= 1 (count (:run-queue result))) "Parked :put should be runnable")
      (is (= :e1 (:id (:continuation (first (:run-queue result)))))
          "Woken entry should be e1"))))


(deftest close-enqueues-flattened-transport-writer-entry-test
  (testing
    "stream close should enqueue the parked writer entry itself, not a nested {:entry ...} wrapper"
    (let [state {:store {}, :id-counter 0, :run-queue []}
          [id s'] (engine/gensym state "stream")
          [stream-ref state] (stream/handle-make s' {:capacity 1} id)
          stream-id (:id stream-ref)
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
                            :continuation {:id :writer-1}})}})
          blocked-state (:state put-result)
          close-result (engine/handle-effect blocked-state
                                             {:effect :stream/close, :stream stream-ref}
                                             {})
          run-entry (first (get-in close-result [:state :run-queue]))]
      (is (:blocked? put-result))
      (is (= 1 (count (get-in close-result [:state :run-queue]))))
      (is (= {:id :writer-1} (:continuation run-entry))
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
                             :continuation {:id :reader}})}})
          put-result (engine/handle-effect
                       (:state next-result)
                       {:effect :stream/put, :stream stream-ref, :val :b}
                       {:park-entry-fns
                        {:stream/put
                         (fn [_s _e r]
                           {:reason :put,
                            :stream-id (:stream-id r),
                            :datom :b,
                            :continuation {:id :writer}})}})
          take-result (engine/handle-effect (:state put-result)
                                            {:effect :stream/take, :stream stream-ref}
                                            {})
          run-queue (get-in take-result [:state :run-queue])
          reader-entry (some #(when (= {:id :reader} (:continuation %)) %) run-queue)
          writer-entry (some #(when (= {:id :writer} (:continuation %)) %) run-queue)]
      (is (:blocked? next-result))
      (is (:blocked? put-result))
      (is (= :a (:value take-result)))
      (is (= 2 (count run-queue)))
      (is (= :b (:value writer-entry)))
      (is (= :b (:value reader-entry)))
      (is (= {cursor-id {:stream-id stream-id, :position 2}}
             (:store-updates reader-entry))
          "Reader wake from take! must advance the parked cursor past the appended datom"))))


(deftest check-ffi-out-dispatch-and-enqueue-test
  (testing "check-ffi-out dispatches one request and enqueues resumed continuation"
    (let [ffi-out (ds/open! {:transport {:type :ringbuffer, :capacity nil}})
          _ (ds/put! ffi-out {:op :op/echo, :args [42], :parked-id :parked-0})
          state {:store {vm/ffi-out-stream-key ffi-out,
                         vm/ffi-out-cursor-key {:stream-id vm/ffi-out-stream-key,
                                                :position 0}},
                 :bridge-dispatcher {:op/echo identity},
                 :parked {:parked-0 {:id :parked-0,
                                     :type :parked-continuation,
                                     :continuation {:id :cont-0},
                                     :environment {}}},
                 :run-queue [],
                 :wait-set [],
                 :blocked true,
                 :halted false}
          next-state (engine/check-ffi-out state)
          cursor-data (get-in next-state [:store vm/ffi-out-cursor-key])]
      (is (= 1 (:position cursor-data)))
      (is (nil? (get-in next-state [:parked :parked-0])))
      (is (= 1 (count (:run-queue next-state))))
      (is (= 42 (:value (first (:run-queue next-state))))))))

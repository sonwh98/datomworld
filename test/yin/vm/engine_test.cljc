(ns yin.vm.engine-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [yin.stream :as stream]
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


(deftest check-wait-set-stream-next-test
  (testing
    "check-wait-set wakes up a :next parked continuation when data becomes available"
    (let [state {:store {}, :id-counter 0}
          ;; 1. Make stream
          [id s'] (engine/gensym state "stream")
          [stream-ref state]
          (stream/handle-make s' {:capacity 10} (constantly id))
          stream-id (:id stream-ref)
          ;; 2. Make cursor
          [id s'] (engine/gensym state "cursor")
          [cursor-ref state]
          (stream/handle-cursor s' {:stream stream-ref} (constantly id))
          ;; 3. Park a continuation for :next
          parked-entry {:reason :next,
                        :cursor-ref cursor-ref,
                        :stream-id stream-id,
                        :continuation {:type :some-cont}}
          state (assoc state
                       :wait-set [parked-entry]
                       :run-queue [])
          ;; 4. check-wait-set should NOT wake it yet (stream is empty)
          state-still-blocked (#'yin.vm.engine/check-wait-set state)
          _ (is (= 1 (count (:wait-set state-still-blocked))))
          _ (is (empty? (:run-queue state-still-blocked)))
          ;; 5. Put data into stream
          state-with-data (stream/handle-put state-still-blocked
                                             {:stream stream-ref, :val 42})
          ;; 6. check-wait-set should now wake it
          state-runnable (#'yin.vm.engine/check-wait-set
                          (:state state-with-data))]
      (is (empty? (:wait-set state-runnable)))
      (is (= 1 (count (:run-queue state-runnable))))
      (is (= 42 (:value (first (:run-queue state-runnable))))))))


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
      (is (= 1 (ds/length stream-after))
          "Dropped :put waiter must not append into closed stream"))))


(deftest check-wait-set-mixed-status-test
  (testing
    "check-wait-set handles multiple entries, waking some and keeping others"
    (let [state {:store {}, :id-counter 0}
          ;; 1. Make two streams
          [id s'] (engine/gensym state "stream")
          [s1 state] (stream/handle-make s' {:capacity 1} (constantly id))
          [id s'] (engine/gensym state "stream")
          [s2 state] (stream/handle-make s' {:capacity 1} (constantly id))
          ;; 2. Fill s1, s2 is empty
          state (:state (stream/handle-put state {:stream s1, :val 1}))
          ;; 3. Park entries:
          ;;    e1: :next on s1 (runnable)
          ;;    e2: :next on s2 (blocked)
          ;;    e3: :put on s1 (blocked)
          [id s'] (engine/gensym state "cursor")
          [c1 state] (stream/handle-cursor s' {:stream s1} (constantly id))
          [id s'] (engine/gensym state "cursor")
          [c2 state] (stream/handle-cursor s' {:stream s2} (constantly id))
          e1 {:reason :next,
              :cursor-ref c1,
              :stream-id (:id s1),
              :continuation {:id :e1}}
          e2 {:reason :next,
              :cursor-ref c2,
              :stream-id (:id s2),
              :continuation {:id :e2}}
          e3 {:reason :put,
              :stream-id (:id s1),
              :datom 2,
              :continuation {:id :e3}}
          state (assoc state
                       :wait-set [e1 e2 e3]
                       :run-queue [])
          ;; 4. Run scheduler
          result (#'yin.vm.engine/check-wait-set state)]
      (is (= 1 (count (:run-queue result))) "Only e1 should be runnable")
      (is (= :e1 (:id (:continuation (first (:run-queue result))))))
      (is (= 2 (count (:wait-set result)))
          "e2 and e3 should remain in wait-set")
      (is (= #{:e2 :e3}
             (set (map (comp :id :continuation) (:wait-set result))))))))

(ns dao.runtime.v2-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.runtime.v2 :as rt]
            [dao.stream.v2 :as stream]
            [yin.vm.v2.test-utils :as tu]))


(deftest initial-state-test
  (testing "A fresh runtime has no work and is not blocked"
    (is (= {:ready-queue [], :wait-set [], :blocked? false}
           (rt/initial-state)))))


(deftest queue-discipline-test
  (testing "Ready entries pop in order"
    (let [rt (rt/enqueue-ready (rt/initial-state) [{:id 1} {:id 2}])
          [entry rt'] (rt/pop-ready rt)]
      (is (= 1 (:id entry)))
      (is (= [{:id 2}] (:ready-queue rt')))
      (is (nil? (rt/pop-ready (rt/initial-state)))))))


(deftest read-outcome-classification-test
  (testing "Only blocked waits; every other read outcome resolves the task"
    (is (= [:wait] (rt/read-outcome->task {:dao.stream/outcome
                                           :dao.stream/blocked})))
    (is (= [:ready {:value 7, :status :ok, :cursor :c}]
           (rt/read-outcome->task {:dao.stream/outcome :dao.stream/ok,
                                   :dao.stream/value 7,
                                   :dao.stream/cursor :c})))
    (is (= [:ready {:value nil, :status :end}]
           (rt/read-outcome->task {:dao.stream/outcome :dao.stream/end})))
    (is (= [:ready {:value :dao.stream/gap,
                    :status :dao.stream/gap,
                    :cursor :recovery}]
           (rt/read-outcome->task {:dao.stream/outcome :dao.stream/gap,
                                   :dao.stream/cursor :recovery})))
    (doseq [o [:dao.stream/cursor-mismatch :dao.stream/invalid-cursor
               :dao.stream/transport-error]]
      (is (= [:ready {:value o, :status o}]
             (rt/read-outcome->task {:dao.stream/outcome o}))))))


(deftest write-outcome-classification-test
  (testing "Only full waits"
    (is (= [:wait] (rt/write-outcome->task {:dao.stream/outcome
                                            :dao.stream/full}
                                           :v)))
    (is (= [:ready {:value :v, :status :ok}]
           (rt/write-outcome->task {:dao.stream/outcome :dao.stream/ok} :v)))
    (is (= [:ready {:value nil, :status :end}]
           (rt/write-outcome->task {:dao.stream/outcome :dao.stream/closed}
                                   :v)))
    (doseq [o [:dao.stream/invalid-value :dao.stream/transport-error]]
      (is (= [:ready {:value o, :status o}]
             (rt/write-outcome->task {:dao.stream/outcome o} :v))))))


(deftest polling-wait-set-is-the-mechanism-test
  (testing "A reader parked on an empty stream wakes when a value arrives"
    (let [handle (tu/new-stream 4)
          cursor (:dao.stream/cursor (stream/cursor handle
                                                    stream/anchor-oldest))
          {:keys [state result]} (rt/handle-read (rt/initial-state)
                                                 handle
                                                 cursor
                                                 {:id :reader})]
      (is (= :blocked result))
      (is (true? (:blocked? state)))
      (is (= 1 (count (:wait-set state))))
      (testing "No value yet: it stays in the wait set"
        (is (= 1 (count (:wait-set (rt/check-wait-set state))))))
      (stream/append! handle :hello)
      (let [state' (rt/check-wait-set state)]
        (is (empty? (:wait-set state')))
        (is (= 1 (count (:ready-queue state'))))
        (let [entry (first (:ready-queue state'))]
          (is (= :hello (:value entry)))
          (is (= :ok (:status entry)))
          (is (some? (:cursor entry))))))))


(deftest close-does-not-wake-a-reader-directly-test
  (testing "A closed stream is reported by the reader's own next"
    (let [handle (tu/new-stream 4)
          cursor (:dao.stream/cursor (stream/cursor handle
                                                    stream/anchor-oldest))
          {:keys [state]} (rt/handle-read (rt/initial-state)
                                          handle
                                          cursor
                                          {:id :reader})]
      (is (= {:state state, :result :ok} (rt/handle-close state handle)))
      (let [state' (rt/check-wait-set state)]
        (is (empty? (:wait-set state')))
        (is (= :end (:status (first (:ready-queue state')))))))))


(deftest run-once-resumes-a-ready-task-test
  (testing "An entry with a :resume fn is popped and invoked"
    (let [rt (rt/enqueue-ready (rt/initial-state)
                               [{:value 5,
                                 :resume (fn [state _entry value]
                                           (assoc state :done value))}])]
      (is (= 5 (:done (rt/run-once rt))))))
  (testing "An entry without :resume is left for the host"
    (is (nil? (rt/run-once (rt/enqueue-ready (rt/initial-state)
                                             [{:value 5}]))))))

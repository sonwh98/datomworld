(ns dao.runtime.driver-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.runtime :as rt]
    [dao.runtime.driver :as driver]
    [dao.stream :as ds]
    [dao.test-utils :as tu]))


(deftest blocking-driver-drains-internally-enqueued-ready-work-test
  (testing
    "run-loop! executes tasks that become ready inside the runtime, not just externally queued work"
    (let [seen (atom [])
          second-task {:resume
                       (fn [rt _entry _value] (swap! seen conj :second) rt)}
          first-task {:resume (fn [rt _entry _value]
                                (swap! seen conj :first)
                                (rt/enqueue-ready rt [second-task]))}
          rt-atom (atom (driver/make-blocking-driver))]
      (driver/enqueue-ready! rt-atom [first-task])
      (driver/run-loop! rt-atom)
      (is (= [:first :second] @seen)))))


(deftest blocking-driver-polls-runtime-wait-set-without-external-queue-work-test
  (testing
    "run-loop! should continue polling the runtime when work is parked in the wait-set"
    (let [seen (atom [])
          stream (tu/make-non-waitable-stream)
          task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
          driver-state (assoc (driver/make-blocking-driver)
                              :wait-set [{:task task,
                                          :resume (:resume task),
                                          :reason :next,
                                          :stream stream,
                                          :cursor {:position 0}}])
          rt-atom (atom driver-state)
          runner (future (driver/run-loop! rt-atom))]
      (Thread/sleep 20)
      (ds/put! stream :payload)
      @runner
      (is (= [:payload] @seen)))))

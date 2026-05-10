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
      ;; Use a future to run the loop and stop! it after work is done
      (let [runner (future (driver/run-loop! rt-atom))]
        (loop [i 0]
          (if (or (= 2 (count @seen)) (> i 50))
            nil
            (do (Thread/sleep 20) (recur (inc i)))))
        (driver/stop! rt-atom)
        @runner)
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
      ;; Wait for task to be processed
      (loop [i 0]
        (if (or (seq @seen) (> i 50))
          nil
          (do (Thread/sleep 20) (recur (inc i)))))
      (driver/stop! rt-atom)
      @runner
      (is (= [:payload] @seen)))))


(deftest blocking-driver-stays-alive-when-started-idle-test
  (testing
    "starting the driver before any work arrives should keep a runner alive for future enqueue-ready! calls"
    (let [seen (atom [])
          rt-atom (atom (driver/make-blocking-driver))
          task {:resume (fn [rt _entry _value] (swap! seen conj :ran) rt)}
          runner (future (driver/run-loop! rt-atom))]
      (try (Thread/sleep 100)
           (is (not (realized? runner)))
           (driver/enqueue-ready! rt-atom [task])
           (loop [i 0]
             (if (or (seq @seen) (> i 50))
               nil
               (do (Thread/sleep 20) (recur (inc i)))))
           (is (= [:ran] @seen))
           (finally (driver/stop! rt-atom) @runner)))))

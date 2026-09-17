(ns dao.runtime.driver-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.runtime :as rt]
            [dao.runtime.driver :as driver]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]))


(defn- new-stream
  "A v2 ring buffer handle. The v1 driver tests parked on a
   NonWaitableStream from dao.test-utils; the v2 fixture is the v2 reference
   transport, and no v2 test opens anything through v1."
  [capacity]
  (let [result (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                    ringbuffer/capacity-key capacity})]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "Test stream creation failed" {:result result})))))


(defn- oldest-cursor
  "The oldest-anchor cursor of handle, as an opaque value."
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)))


(defn- wait-for
  "Poll pred every 20 ms, up to ~1 s. The driver runs on its own thread."
  [pred]
  (loop [i 0]
    (when (and (not (pred)) (< i 50))
      (Thread/sleep 20)
      (recur (inc i)))))


(deftest run-loop-drains-internally-enqueued-ready-work-test
  (testing
    "run-loop! executes tasks that become ready inside the runtime, not just externally queued work"
    (let [seen (atom [])
          second-task {:resume
                       (fn [rt _entry _value] (swap! seen conj :second) rt)}
          first-task {:resume (fn [rt _entry _value]
                                (swap! seen conj :first)
                                (rt/enqueue-ready rt [second-task]))}
          ;; 20 ms, not the 50 ms default: the cadence is a parameter.
          driver-atom (atom (driver/make-driver 20))]
      (driver/enqueue-ready! driver-atom [first-task])
      (let [runner (future (driver/run-loop! driver-atom))]
        (wait-for #(= 2 (count @seen)))
        (driver/stop! driver-atom)
        @runner)
      (is (= [:first :second] @seen)))))


(deftest run-loop-polls-runtime-wait-set-without-external-queue-work-test
  (testing
    "run-loop! keeps polling the wait set on cadence, so a reader parked on an empty stream wakes when a value is appended with no external queue work at all"
    (let [seen (atom [])
          handle (new-stream 4)
          task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
          driver-atom (atom (driver/make-driver 20))
          {:keys [state result]} (rt/handle-read (:rt @driver-atom)
                                                 handle
                                                 (oldest-cursor handle)
                                                 task)]
      (is (= :blocked result))
      (swap! driver-atom assoc :rt state)
      (let [runner (future (driver/run-loop! driver-atom))]
        ;; A few cadence rounds find nothing first.
        (Thread/sleep 60)
        (stream/append! handle :payload)
        (wait-for #(seq @seen))
        (driver/stop! driver-atom)
        @runner)
      (is (= [:payload] @seen)))))


(deftest run-loop-stays-alive-when-started-idle-test
  (testing
    "starting the driver before any work arrives keeps a runner alive for future enqueue-ready! calls"
    (let [seen (atom [])
          driver-atom (atom (driver/make-driver 20))
          task {:resume (fn [rt _entry _value] (swap! seen conj :ran) rt)}
          runner (future (driver/run-loop! driver-atom))]
      (try (Thread/sleep 100)
           (is (not (realized? runner)))
           (driver/enqueue-ready! driver-atom [task])
           (wait-for #(seq @seen))
           (is (= [:ran] @seen))
           (finally (driver/stop! driver-atom) @runner)))))

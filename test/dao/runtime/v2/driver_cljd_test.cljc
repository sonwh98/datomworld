(ns dao.runtime.v2.driver-cljd-test
  (:require #?(:cljd ["dart:async" :as async])
            #?(:cljd ["dart:core" :as core])
            [clojure.test :refer [deftest is testing]]
            [dao.runtime.v2 :as rt]
            [dao.runtime.v2.driver :as driver]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ringbuffer]))


#?(:cljd
   (defn- new-stream
     "A v2 ring buffer handle. The v1 driver tests parked on a
      NonWaitableStream from dao.test-utils; the v2 fixture is the v2
      reference transport, and no v2 test opens anything through v1."
     [capacity]
     (let [result (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                       ringbuffer/capacity-key capacity})]
       (if (= :dao.stream/ok (:dao.stream/outcome result))
         (:dao.stream/handle result)
         (throw (ex-info "Test stream creation failed" {:result result}))))))


#?(:cljd
   (defn- oldest-cursor
     "The oldest-anchor cursor of handle, as an opaque value."
     [handle]
     (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))))


#?(:cljd
   (defn- park-reader!
     "Park a reader task on an empty stream in the driver's runtime and
      return the driver atom. The state container is the test's, not the
      driver's."
     [driver-atom handle task]
     (let [parked (:state (rt/handle-read (:rt @driver-atom)
                                          handle
                                          (oldest-cursor handle)
                                          task))]
       (swap! driver-atom assoc :rt parked)
       driver-atom)))


#?(:cljd
   (deftest schedule-work-keeps-polling-while-runtime-has-parked-waits-test
     (testing
       "the poll timer keeps firing, so a reader parked on an empty stream wakes when a value is appended with no further schedule-work!"
       (let [seen (atom [])
             completer (async/Completer.)
             handle (new-stream 4)
             task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
             ;; 5 ms, not the 20 ms default: the cadence is a parameter.
             driver-atom (atom (driver/make-driver 5))]
         (park-reader! driver-atom handle task)
         (driver/schedule-work! driver-atom [])
         (.then ^async/Future
          (async/Future.delayed (core/Duration .milliseconds 0))
                (fn [_]
                  (stream/append! handle :payload)
                  (.then ^async/Future
                   (async/Future.delayed (core/Duration .milliseconds 20))
                         (fn [_]
                           (is (= [:payload] @seen))
                           (.complete ^async/Completer completer nil)))))
         (.-future ^async/Completer completer)))))


#?(:cljd
   (deftest
     schedule-work-runs-ready-entries-without-waiting-for-poll-timer-test
     (testing
       "ready entries run on the next microtask even when a wait-set poll timer is pending"
       (let [seen (atom [])
             completer (async/Completer.)
             handle (new-stream 4)
             parked-task {:resume
                          (fn [rt _entry value] (swap! seen conj value) rt)}
             ready-task
             {:resume (fn [rt _entry _value] (swap! seen conj :ready) rt)}
             driver-atom (atom (driver/make-driver 20))]
         (park-reader! driver-atom handle parked-task)
         (driver/schedule-work! driver-atom [])
         (async/scheduleMicrotask
           (fn []
             (driver/schedule-work! driver-atom [ready-task])
             (.then ^async/Future
              (async/Future.delayed (core/Duration .milliseconds 5))
                    (fn [_]
                      (is (= [:ready] @seen))
                      ;; Test hygiene: resolve the parked reader and out-wait one
                      ;; cadence, so this test leaves no live poll chain behind.
                      ;; A permanently parked task polls forever.
                      (stream/append! handle :payload)
                      (.then ^async/Future
                       (async/Future.delayed (core/Duration .milliseconds 25))
                             (fn [_]
                               (.complete ^async/Completer completer nil)))))
             nil))
         (.-future ^async/Completer completer)))))

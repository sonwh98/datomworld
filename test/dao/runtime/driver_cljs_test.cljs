(ns dao.runtime.driver-cljs-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [dao.runtime :as rt]
            [dao.runtime.driver :as driver]
            [dao.stream :as ds]
            [dao.test-utils :as tu]))


(defn- fake-timeout-handle
  [id]
  #js {:id id, :unref (fn [] nil)})


(deftest schedule-work-keeps-polling-while-runtime-has-parked-waits-test
  (async
    done
    (testing
      "CLJS driver should keep polling after a task parks in the wait-set"
      (let [seen (atom [])
            stream (tu/make-non-waitable-stream)
            task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
            parked-state
            (:state
              (rt/handle-read (rt/initial-state) stream {:position 0} task))]
        (driver/set-runtime! parked-state)
        (driver/schedule-work! [])
        (js/setTimeout (fn []
                         (ds/append! stream :payload)
                         (js/setTimeout (fn [] (is (= [:payload] @seen)) (done))
                                        20))
                       0)))))


(deftest schedule-work-runs-ready-entries-without-waiting-for-poll-timer-test
  (async
    done
    (testing
      "CLJS driver should run ready entries on the next microtask even when a wait-set poll timer is pending"
      (let [seen (atom [])
            stream (tu/make-non-waitable-stream)
            parked-task {:resume
                         (fn [rt _entry value] (swap! seen conj value) rt)}
            ready-task {:resume
                        (fn [rt _entry _value] (swap! seen conj :ready) rt)}
            parked-state (:state (rt/handle-read (rt/initial-state)
                                                 stream
                                                 {:position 0}
                                                 parked-task))]
        (driver/set-runtime! parked-state)
        (driver/schedule-work! [])
        (js/queueMicrotask
          (fn []
            (driver/schedule-work! [ready-task])
            (js/setTimeout (fn [] (is (= [:ready] @seen)) (done)) 5)))))))


(deftest schedule-work-does-not-leave-stale-poll-timer-after-poll-break-test
  (async
    done
    (testing
      "CLJS driver should keep only one pending poll timer after expediting ready work during polling"
      (let [real-set-timeout js/setTimeout
            real-clear-timeout js/clearTimeout
            timer-id (atom 0)
            timers (atom {})
            seen (atom [])
            stream (tu/make-non-waitable-stream)
            parked-task {:resume
                         (fn [rt _entry value] (swap! seen conj value) rt)}
            ready-task {:resume
                        (fn [rt _entry _value] (swap! seen conj :ready) rt)}
            parked-state (:state (rt/handle-read (rt/initial-state)
                                                 stream
                                                 {:position 0}
                                                 parked-task))
            cleanup! (fn []
                       (set! js/setTimeout real-set-timeout)
                       (set! js/clearTimeout real-clear-timeout))]
        (try (driver/set-runtime! parked-state)
             (set! js/setTimeout
                   (fn [f _ms]
                     (let [id (swap! timer-id inc)]
                       (swap! timers assoc id f)
                       (fake-timeout-handle id))))
             (set! js/clearTimeout
                   (fn [handle]
                     (when handle (swap! timers dissoc (.-id handle)))))
             (driver/schedule-work! [])
             (real-set-timeout
               (fn []
                 (try (driver/schedule-work! [ready-task])
                      (real-set-timeout
                        (fn []
                          (try (is (= [:ready] @seen))
                               (is (= 1 (count @timers))
                                   (str "expected one live poll timer, saw "
                                        (count @timers)))
                               (finally (cleanup!) (done))))
                        0)
                      (catch :default e (cleanup!) (done) (throw e))))
               0)
             (catch :default e (cleanup!) (done) (throw e)))))))


(deftest schedule-work-coalesces-poll-break-microtasks-test
  (async
    done
    (testing
      "CLJS driver should coalesce repeated schedule-work! calls while polling into one poll-break"
      (let [real-set-timeout js/setTimeout
            real-clear-timeout js/clearTimeout
            timer-id (atom 0)
            timers (atom {})
            seen (atom [])
            stream (tu/make-non-waitable-stream)
            parked-task {:resume
                         (fn [rt _entry value] (swap! seen conj value) rt)}
            ready-a {:resume (fn [rt _entry _value] (swap! seen conj :a) rt)}
            ready-b {:resume (fn [rt _entry _value] (swap! seen conj :b) rt)}
            parked-state (:state (rt/handle-read (rt/initial-state)
                                                 stream
                                                 {:position 0}
                                                 parked-task))
            cleanup! (fn []
                       (set! js/setTimeout real-set-timeout)
                       (set! js/clearTimeout real-clear-timeout))]
        (try
          (driver/set-runtime! parked-state)
          (set! js/setTimeout
                (fn [f _ms]
                  (let [id (swap! timer-id inc)]
                    (swap! timers assoc id f)
                    (fake-timeout-handle id))))
          (set! js/clearTimeout
                (fn [handle] (when handle (swap! timers dissoc (.-id handle)))))
          (driver/schedule-work! [])
          (real-set-timeout
            (fn []
              (try
                (driver/schedule-work! [ready-a])
                (driver/schedule-work! [ready-b])
                (real-set-timeout
                  (fn []
                    (try
                      (is (= [:a :b] @seen))
                      (is
                        (= 1 (count @timers))
                        (str
                          "expected one live poll timer after coalescing, saw "
                          (count @timers)))
                      (finally (cleanup!) (done))))
                  0)
                (catch :default e (cleanup!) (done) (throw e))))
            0)
          (catch :default e (cleanup!) (done) (throw e)))))))

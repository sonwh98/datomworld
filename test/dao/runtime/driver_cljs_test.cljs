(ns dao.runtime.driver-cljs-test
  (:require
    [cljs.test :refer-macros [async deftest is testing]]
    [dao.runtime :as rt]
    [dao.runtime.driver :as driver]
    [dao.stream :as ds]
    [dao.test-utils :as tu]))


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
                         (ds/put! stream :payload)
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

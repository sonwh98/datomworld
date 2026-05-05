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

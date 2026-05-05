(ns dao.runtime.driver-cljd-test
  (:require
    #?(:cljd ["dart:async" :as async])
    #?(:cljd ["dart:core" :as core])
    [clojure.test :refer [deftest is testing]]
    [dao.runtime :as rt]
    [dao.runtime.driver :as driver]
    [dao.stream :as ds]
    [dao.test-utils :as tu]))


#?(:cljd
   (deftest schedule-work-keeps-polling-while-runtime-has-parked-waits-test
     (testing
       "CLJD driver should keep polling after a task parks in the wait-set"
       (let [seen (atom [])
             completer (async/Completer.)
             stream (tu/make-non-waitable-stream)
             task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
             parked-state (:state (rt/handle-read (rt/initial-state)
                                                  stream
                                                  {:position 0}
                                                  task))]
         (driver/set-runtime! parked-state)
         (driver/schedule-work! [])
         (.then ^async/Future
          (async/Future.delayed (core/Duration .milliseconds 0))
                (fn [_]
                  (ds/put! stream :payload)
                  (.then ^async/Future
                   (async/Future.delayed (core/Duration .milliseconds
                                                        20))
                         (fn [_]
                           (is (= [:payload] @seen))
                           (.complete ^async/Completer completer nil)))))
         (.-future ^async/Completer completer)))))

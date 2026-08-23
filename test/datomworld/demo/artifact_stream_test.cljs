(ns datomworld.demo.artifact-stream-test
  (:require [cljs.test :refer-macros [deftest is]]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


(deftest evicting-an-output-stream-keeps-the-newest-value
  (let [stream (ds/open! {:dao.stream/type :ringbuffer,
                          :capacity 1,
                          :eviction-policy :evict-oldest})]
    (is (= :ok (:result (ds/append! stream :first))))
    (is (= :ok (:result (ds/append! stream :second))))
    (is (= :daostream/gap (ds/next stream {:position 0})))
    (is (= :second (:ok (ds/next stream {:position 1}))))))

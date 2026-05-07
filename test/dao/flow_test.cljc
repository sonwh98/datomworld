(ns dao.flow-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.flow :as flow]
    [dao.runtime :as rt]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ring]))


(deftest stream-put-wakes-reader
  (let [buf (ring/make-ring-buffer-stream nil)
        received (atom nil)
        task {:resume (fn [rt entry val] (reset! received val) rt)}
        _ (rt/handle-read (rt/initial-state) buf {:position 0} task)]
    (flow/stream-put! buf :hello)
    (is (= :hello @received))))


(deftest stream-transduce-preserves-nil-payloads
  (testing "a stream element of nil is data, not end-of-stream"
    (let [stream (ring/make-ring-buffer-stream 10)
          _ (ds/put! stream nil)
          _ (ds/put! stream :after-nil)
          collect-rf (fn
                       ([] []) ([acc] acc) ([acc input] (conj acc input)))]
      (is (= [nil :after-nil]
             (flow/stream-transduce identity
                                    collect-rf
                                    []
                                    stream
                                    {:position 0}))))))


(deftest stream-transduce-returns-falsey-completion-values
  (testing "the reducing function completion value is returned even when falsey"
    (doseq [completion [nil false]]
      (let [stream (ring/make-ring-buffer-stream 10)
            _ (ds/put! stream :tick)
            _ (ds/close! stream)
            rf (fn
                 ([] :seed) ([_acc] completion) ([acc _input] acc))]
        (is
          (=
            completion
            (flow/stream-transduce identity rf :seed stream {:position 0})))))))

(ns dao.stream.v2.ringbuffer-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]))


(def spec
  {:dao.stream/type ring/transport-type
   :dao.stream.ringbuffer/capacity 2})


(defn handle
  []
  (:dao.stream/handle (ring/create! spec)))


(defn cur
  [h a]
  (:dao.stream/cursor (stream/cursor h a)))


(deftest retention-and-gaps
  (let [h (handle) c (cur h :dao.stream/oldest)]
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :a))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :b))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! h :c))))
    (is (= :dao.stream/gap (:dao.stream/outcome (stream/next h c))))
    (let [r (stream/next h (:dao.stream/cursor (stream/next h c)))]
      (is (= :dao.stream/ok (:dao.stream/outcome r)))
      (is (= :b (:dao.stream/value r))))))


(deftest anchors-and-identity
  (let [h (handle) other (handle)
        newest (cur h :dao.stream/newest)]
    (is (= :dao.stream/blocked (:dao.stream/outcome (stream/next h newest))))
    (is (= :dao.stream/invalid-anchor
           (:dao.stream/outcome (stream/cursor h :bad))))
    (is (= :dao.stream/cursor-mismatch
           (:dao.stream/outcome
             (stream/next other newest))))))


(deftest close-freezes-attachment
  (let [owner (handle)
        d (:dao.stream/descriptor (stream/descriptor owner))
        attached (:dao.stream/handle
                   ((ring/make-attacher {(:dao.stream/identity d) owner}) d))
        c (cur attached :dao.stream/newest)]
    (stream/append! owner :before)
    (stream/close! attached)
    (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! attached :x))))
    (stream/append! owner :after)
    (let [before (stream/next attached c)]
      (is (= :dao.stream/ok (:dao.stream/outcome before)))
      (is (= :before (:dao.stream/value before)))
      (is (= :dao.stream/end
             (:dao.stream/outcome
               (stream/next attached (:dao.stream/cursor before))))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! owner :ok))))))


(deftest attachment-resolution
  (let [h (handle) d (:dao.stream/descriptor (stream/descriptor h))
        resolver {(:dao.stream/identity d) h}]
    (is (= :dao.stream/ok (:dao.stream/outcome (ring/attach! resolver d))))
    (is (= :dao.stream/not-found
           (:dao.stream/outcome (ring/attach! {} d))))
    (is (= :dao.stream/invalid-descriptor
           (:dao.stream/outcome (ring/attach! resolver {:dao.stream/type :other}))))))

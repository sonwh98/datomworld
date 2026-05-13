(ns thetao.vm.streams
  (:require
    [dao.stream :as ds]
    [dao.stream.ringbuffer]))


(defn open-runtime-streams
  []
  {:execution (ds/open! {:type :ringbuffer :capacity nil})
   :event (ds/open! {:type :ringbuffer :capacity nil})
   :fact (ds/open! {:type :ringbuffer :capacity nil})
   :effect (ds/open! {:type :ringbuffer :capacity nil})
   :effect-response (ds/open! {:type :ringbuffer :capacity nil})})


(defn stream-values
  [stream]
  (vec (ds/->seq nil stream)))

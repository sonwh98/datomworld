(ns dao.stream.transport.ringbuffer
  "Reference implementation of DaoStream using a memory-backed ring buffer.
   
   state-atom holds: {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {} :writer-waiters []}
     :buffer          — map of absolute-index -> value
     :head            — absolute index of next take! position (oldest available)
     :tail            — absolute index of next put! position
     :closed          — boolean
     :reader-waiters  — map of position -> wait-set-entry; woken when put! appends at that position
     :writer-waiters  — vector of wait-set-entries; first one woken when drain-one! frees space"
  (:require
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer.state :as state]))


#?(:clj
   (deftype RingBufferStream
     [capacity state-atom]

     clojure.lang.Counted

     (count
       [_]
       (state/count-state @state-atom))


     ds/IDaoStreamWriter

     (put!
       [_this val]
       (let [result (swap! state-atom state/put-state capacity val)]
         (state/put-outcome result val)))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (state/next-outcome @state-atom cursor))


     ds/IDaoStreamBound

     (close!
       [_this]
       (let [result @state-atom]
         (swap! state-atom state/close-state)
         (state/close-outcome result)))


     (closed? [_this] (state/closed-state? @state-atom))


     ds/IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom state/register-reader-waiter-state position entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom state/register-writer-waiter-state entry))


     ds/IDaoStreamDrainable

     (-drain-one!
       [_this]
       (let [result (swap! state-atom state/drain-one-state)]
         (state/drain-one-outcome result))))

   :default
   (deftype RingBufferStream
     [capacity state-atom]

     ICounted

     (-count
       [_]
       (state/count-state @state-atom))


     ds/IDaoStreamWriter

     (put!
       [_this val]
       (let [result (swap! state-atom state/put-state capacity val)]
         (state/put-outcome result val)))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (state/next-outcome @state-atom cursor))


     ds/IDaoStreamBound

     (close!
       [_this]
       (let [result @state-atom]
         (swap! state-atom state/close-state)
         (state/close-outcome result)))


     (closed? [_this] (state/closed-state? @state-atom))


     ds/IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom state/register-reader-waiter-state position entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom state/register-writer-waiter-state entry))


     ds/IDaoStreamDrainable

     (-drain-one!
       [_this]
       (let [result (swap! state-atom state/drain-one-state)]
         (state/drain-one-outcome result)))))


(defn make-ring-buffer-stream
  ([capacity]
   (make-ring-buffer-stream capacity 0))
  ([capacity position]
   (->RingBufferStream capacity (atom (state/initial-state position)))))


(defmethod ds/open! :ringbuffer [descriptor]
  (let [capacity (get-in descriptor [:transport :capacity])]
    (make-ring-buffer-stream capacity)))

(ns dao.test-utils
  (:require [dao.stream :as ds]))


(defrecord NonWaitableStream
  [state-atom]
  ;; A mock stream that simulates a polled (non-waitable) transport.
  ;; Returns :blocked from ds/next and :full from ds/append! when
  ;; appropriate, but never registers waiters or provides wakeups. Used to
  ;; verify that runtime schedulers correctly fall back to polling.
  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [s @state-atom
          pos (:position cursor)]
      (if (contains? (:buffer s) pos)
        {:ok (get (:buffer s) pos), :cursor {:position (inc pos)}}
        :blocked)))


  ds/IDaoStreamWriter

  (append!
    [_this val]
    (let [s @state-atom
          tail (:tail s)]
      (if (and (:capacity s) (>= (count (:buffer s)) (:capacity s)))
        {:result :full}
        (do (swap! state-atom (fn [s]
                                (-> s
                                    (assoc-in [:buffer tail] val)
                                    (update :tail inc))))
            {:result :ok}))))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(defrecord WaitableRetryStream
  [state-atom]
  ;; A mock stream that returns :blocked exactly once, then succeeds.
  ;; Used to test race conditions where a stream becomes readable
  ;; during/after parking.
  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [call-count (:next-calls (swap! state-atom update :next-calls inc))
          pos (:position cursor)]
      (if (= 1 call-count)
        :blocked
        (if-let [val (get-in @state-atom [:buffer pos])]
          {:ok val, :cursor {:position (inc pos)}}
          :blocked))))


  ds/IDaoStreamWaitable

  (register-reader-waiter!
    [_this position entry]
    (swap! state-atom update :reader-waiters conj [position entry]))


  (register-writer-waiter!
    [_this entry]
    (swap! state-atom update :writer-waiters conj entry))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(defn make-non-waitable-stream
  ([] (make-non-waitable-stream nil))
  ([capacity]
   (->NonWaitableStream
     (atom {:buffer {}, :tail 0, :closed false, :capacity capacity}))))


(defn make-waitable-retry-stream
  ([] (make-waitable-retry-stream {}))
  ([initial-buffer]
   (->WaitableRetryStream (atom {:buffer initial-buffer,
                                 :next-calls 0,
                                 :reader-waiters [],
                                 :writer-waiters [],
                                 :closed false}))))


;; =============================================================================
;; Telemetry Helpers
;; =============================================================================

(defn stream-values
  "Drains a stream into a vector of all current values."
  [stream]
  (vec (ds/->seq nil stream)))


(defn fact?
  "Checks if a specific datom (a v t) exists in a collection of datoms."
  [datoms attr value]
  (boolean (some #(and (= attr (nth % 1)) (= value (nth % 2))) datoms)))

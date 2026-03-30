(ns dao.stream
  "DaoStream: bidirectional channel with cursors.

  Three orthogonal protocol boundaries:

  IDaoStreamReader (canonical read model):
    Non-destructive, cursor-based reading. Multiple cursors advance independently.
    (next [stream cursor]) → {:ok val :cursor cursor'} | :blocked | :end | :daostream/gap

  IDaoStreamWriter (canonical write model):
    Append-only writes.
    (put! [stream val]) → {:result :ok, :woke [...]} | {:result :full} (throws if closed)

  IDaoStreamBound (lifecycle):
    (close! [stream]) → {:woke [...]}
    (closed? [stream]) → boolean

  IDaoStreamWaitable (optional — transport-local reader waking):
    (register-reader-waiter! [stream position entry])

  Descriptor (serializable):
    {:transport {:type :ringbuffer :capacity nil-or-int}}

  Cursor (plain map, constructed inline by caller):
    {:position n}"
  (:refer-clojure :exclude [next]))


;; =============================================================================
;; Stream Protocols (Orthogonal Boundaries)
;; =============================================================================

(defprotocol IDaoStreamReader
  "Non-destructive, cursor-based reading. Multiple cursors on the same stream
   advance independently without consuming values."

  (next
    [this cursor]
    "Returns {:ok val :cursor cursor'} if value exists at cursor position.
     Returns :blocked if stream is open and position is at end.
     Returns :end if stream is closed and position is at or past end.
     Returns :daostream/gap if cursor position has been evicted by transport."))


(defprotocol IDaoStreamWriter
  "Append-only writes. No destructive consumption."

  (put!
    [this val]
    "Appends val. Returns :ok if successful, :full if transport is capacity-bounded
     and full. Throws ex-info if stream is closed."))


(defprotocol IDaoStreamBound
  "Lifecycle: closing streams and checking closed status."

  (close!
    [this]
    "Marks stream closed. Does not erase existing data. Returns {:woke [...]}
     with any reader-waiters that were resolved.")

  (closed?
    [this]
    "Returns boolean. True if stream has been closed."))


(defprotocol IDaoStreamWaitable
  "Optional protocol: transport-local reader-waiter registration.
   Enables readers to register at the transport instead of the VM scheduler."

  (register-reader-waiter!
    [this position entry]
    "Register a waiter (wait-set entry) for a specific cursor position.
     Called when ds/next returns :blocked to avoid polling."))


(defmulti open!
  "Realize a descriptor into an operational IStream transport."
  (fn [descriptor] (get-in descriptor [:transport :type])))


;; =============================================================================
;; RingBufferStream — reference implementation
;; =============================================================================
;;
;; state-atom holds: {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {}}
;;   :buffer         — map of absolute-index -> value
;;   :head           — absolute index of next take! position (oldest available)
;;   :tail           — absolute index of next put! position
;;   :closed         — boolean
;;   :reader-waiters — map of position -> wait-set-entry; woken when put! appends
;;
;; Memory is reclaimed during drain-one! via dissoc.
;; Cursors reading at pos < head receive :daostream/gap.
;; When put! appends at position p and (get reader-waiters p) exists:
;;   the entry is removed from reader-waiters and returned in {:woke [...]}

(defrecord RingBufferStream
  [capacity state-atom]

  IDaoStreamWriter

  (put!
    [_this val]
    (let [result (swap! state-atom
                        (fn [s]
                          (let [head (:head s)
                                tail (:tail s)
                                available (- tail head)]
                            (cond
                              (:closed s)
                              (assoc s ::put-result ::closed)
                              (and capacity (>= available capacity))
                              (assoc s ::put-result :full)
                              :else
                              (let [woken-entry (get (:reader-waiters s) tail)
                                    next-state (-> s
                                                   (assoc-in [:buffer tail] val)
                                                   (update :tail inc))]
                                (if woken-entry
                                  (-> next-state
                                      (update :reader-waiters dissoc tail)
                                      (assoc ::put-result {:ok :ok, :woken-entry woken-entry}))
                                  (assoc next-state ::put-result {:ok :ok})))))))]
      (when (= (::put-result result) ::closed)
        (throw (ex-info "Cannot put to closed stream" {})))
      (let [{:keys [woken-entry]} (::put-result result)]
        (if (= :full (::put-result result))
          {:result :full}
          {:result :ok,
           :woke (if woken-entry
                   [{:entry woken-entry, :value val, :position (dec (:tail result))}]
                   [])}))))


  IDaoStreamReader

  (next
    [_this cursor]
    (let [pos (:position cursor)
          state @state-atom
          head (:head state)
          tail (:tail state)]
      (cond (< pos head) :daostream/gap
            (< pos tail) {:ok (get-in state [:buffer pos]),
                          :cursor (update cursor :position inc)}
            (:closed state) :end
            :else :blocked)))


  IDaoStreamBound

  (close!
    [_this]
    (swap! state-atom
           (fn [s]
             (assoc s :closed true)))
    (let [reader-waiters (:reader-waiters @state-atom)]
      {:woke (mapv (fn [[_pos entry]] {:entry entry, :value nil})
                   reader-waiters)}))


  (closed? [_this] (:closed @state-atom))


  IDaoStreamWaitable

  (register-reader-waiter!
    [_this position entry]
    (swap! state-atom assoc-in [:reader-waiters position] entry)))


(defmethod open! :ringbuffer [descriptor]
  (let [capacity (get-in descriptor [:transport :capacity])]
    (->RingBufferStream capacity (atom {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {}}))))


;; =============================================================================
;; Utilities (Non-Protocol)
;; =============================================================================

(defn drain-one!
  "Destructively consume one value from stream.
   Returns {:ok val} if a value exists, :empty if stream is open and no values
   available, or :end if stream is closed and drained.

   NOT part of the canonical model. Use (next stream cursor) with cursor-based
   reading for reliable, non-destructive traversal. drain-one! is provided for
   legacy compatibility and specific use cases requiring destructive consumption."
  [stream]
  (cond
    (instance? RingBufferStream stream)
    (let [{:keys [state-atom]} stream
          result (swap! state-atom
                        (fn [s]
                          (let [head (:head s)
                                tail (:tail s)]
                            (if (< head tail)
                              (let [val (get-in s [:buffer head])]
                                (-> s
                                    (update :buffer dissoc head)
                                    (update :head inc)
                                    (assoc ::take-result {:ok val})))
                              (if (:closed s)
                                (assoc s ::take-result :end)
                                (assoc s ::take-result :empty))))))]
      (::take-result result))
    :else
    (throw (ex-info "drain-one! not supported for this stream transport" {:stream stream}))))


(defn count-available
  "Returns count of appended but unconsumed values in stream.

   Transport-specific metadata query. NOT part of the canonical model. This count
   is only meaningful for transports that track head/tail indices and is not a
   reliable indicator of stream state (e.g., after drain-one!, counts change)."
  [stream]
  (cond
    (instance? RingBufferStream stream)
    (let [{:keys [state-atom]} stream
          state @state-atom]
      (- (:tail state) (:head state)))
    :else
    (throw (ex-info "count-available not supported for this stream transport" {:stream stream}))))


(defn ->seq
  "Convert a stream into a lazy Clojure sequence of values using cursor-based
   reading via the IDaoStreamReader protocol. A cursor walks the stream until
   (next stream cursor) hits :blocked, :end, or a gap, at which point the lazy
   sequence terminates.

   The returned sequence is a snapshot at call time. For open streams, the
   sequence does not grow as new values are appended; create a new sequence
   or use cursors directly to observe new appends.

   The `ctx` argument is ignored but retained for compatibility with other
   stream helpers."
  [_ stream]
  (when stream
    (letfn [(walk
              [cursor]
              (lazy-seq (let [result (next stream cursor)]
                          (cond (map? result) (cons (:ok result)
                                                    (walk (:cursor result)))
                                (#{:blocked :end :daostream/gap} result) nil
                                :else nil))))]
      (walk {:position 0}))))

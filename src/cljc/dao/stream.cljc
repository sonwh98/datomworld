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

  IDaoStreamWaitable (optional — transport-local reader and writer waking):
    (register-reader-waiter! [stream position entry])
    (register-writer-waiter! [stream entry])

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
  "Optional protocol: transport-local waiter registration.
   Enables readers and writers to register at the transport instead of the VM scheduler."

  (register-reader-waiter!
    [this position entry]
    "Register a reader waiter for a specific cursor position.
     Called when ds/next returns :blocked to avoid polling.")

  (register-writer-waiter!
    [this entry]
    "Register a writer waiter. Called when ds/put! returns :full to avoid polling.
     The entry contains the datom to write; drain-one! will write it and wake the waiter."))


(defprotocol IDaoStreamDrainable
  "Optional protocol for destructive consumption."

  (-drain-one!
    [this]
    "Destructively consume one value from stream.
     Returns {:ok val, :woke [...]} if a value exists (including any woken writers),
     :empty if stream is open and no values available, or :end if stream is closed and drained."))


(defmulti open!
  "Realize a descriptor into an operational IStream transport."
  (fn [descriptor] (get-in descriptor [:transport :type])))


;; =============================================================================
;; Utilities (Non-Protocol)
;; =============================================================================

(defn drain-one!
  "Destructively consume one value from stream.
   Returns {:ok val, :woke [...]} if a value exists (including any woken writers),
   :empty if stream is open and no values available, or :end if stream is closed and drained.

   NOT part of the canonical model. Use (next stream cursor) with cursor-based
   reading for reliable, non-destructive traversal. drain-one! is provided for
   legacy compatibility and specific use cases requiring destructive consumption.

   When a writer-waiter is woken, its datom is atomically written to the stream
   and included in the :woke return value."
  [stream]
  (if (satisfies? IDaoStreamDrainable stream)
    (-drain-one! stream)
    (throw (ex-info "drain-one! not supported for this stream transport" {:stream stream}))))


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

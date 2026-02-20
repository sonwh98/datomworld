(ns dao.stream
  "DaoStream: streams and cursors as pure data functions.

  A stream is an append-only log with optional capacity.
  A cursor is an independent read position into a stream.

  All functions are pure: they take data, return data.
  No continuations, no parking, no side effects.
  The VM handles parking and scheduling."
  (:require
    [dao.stream.storage :as storage]))


;; =============================================================================
;; Stream
;; =============================================================================
;; A stream is a map:
;;   {:storage <IStreamStorage>
;;    :capacity nil|int     ;; nil = unbounded
;;    :closed false}

(defn make-stream
  "Create a new stream with the given storage backend.
   Options:
     :capacity - max datoms before full (nil = unbounded)"
  [storage & {:keys [capacity]}]
  {:storage storage, :capacity capacity, :closed false})


(defn stream-put
  "Append a datom to the stream. Returns:
   {:ok stream'} on success,
   {:full stream} if at capacity,
   throws if stream is closed."
  [stream datom]
  (when (:closed stream) (throw (ex-info "Cannot put to closed stream" {})))
  (let [storage (:storage stream)
        len (storage/s-length storage)]
    (if (and (:capacity stream) (>= len (:capacity stream)))
      {:full stream}
      {:ok (update stream :storage storage/s-append datom)})))


(defn stream-closed?
  "Returns true if the stream is closed."
  [stream]
  (:closed stream))


(defn stream-close
  "Close the stream. No more puts allowed."
  [stream]
  (assoc stream :closed true))


(defn stream-length
  "Number of datoms in the stream."
  [stream]
  (storage/s-length (:storage stream)))


;; =============================================================================
;; Cursor
;; =============================================================================
;; A cursor is a map:
;;   {:stream-ref <keyword>   ;; reference to stream in VM store
;;    :position int}

(defn make-cursor
  "Create a cursor at position 0 for the given stream reference."
  [stream-ref]
  {:stream-ref stream-ref, :position 0})


(defn cursor-next
  "Advance the cursor by one position. Returns:
   {:ok datom, :cursor cursor'} - data available, cursor advanced
   :blocked                     - at end of open stream, no data yet
   :end                         - at end of closed stream
   :daostream/gap               - position was evicted (future)"
  [cursor stream]
  (let [pos (:position cursor)
        storage (:storage stream)
        len (storage/s-length storage)]
    (if (< pos len)
      (let [datom (storage/s-read-at storage pos)]
        {:ok datom, :cursor (update cursor :position inc)})
      (if (:closed stream) :end :blocked))))


(defn cursor-seek
  "Return a cursor at the given position."
  [cursor pos]
  (assoc cursor :position pos))


(defn cursor-position
  "Return the current position of the cursor."
  [cursor]
  (:position cursor))

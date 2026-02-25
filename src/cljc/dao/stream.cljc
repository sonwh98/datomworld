(ns dao.stream
  "DaoStream: bidirectional channel with cursors.

  A stream is a channel with optional capacity.
  put appends values; take destructively consumes them (advancing head).
  Cursors provide independent non-destructive read positions.

  All functions are pure: they take data, return data.
  No continuations, no parking, no side effects.
  The VM handles parking and scheduling."
  (:refer-clojure :exclude [next take])
  (:require
    [dao.stream.storage :as storage]))


;; =============================================================================
;; Stream
;; =============================================================================
;; A stream is a map:
;;   {:storage <IStreamStorage>
;;    :capacity nil|int     ;; nil = unbounded
;;    :closed false
;;    :head 0}              ;; absolute position of first untaken value

(defn make
  "Create a new stream with the given storage backend.
   Options:
     :capacity - max values before full (nil = unbounded)"
  [storage & {:keys [capacity]}]
  {:storage storage, :capacity capacity, :closed false, :head 0})


(defn put
  "Append a value to the stream. Returns:
   {:ok stream'} on success,
   {:full stream} if at capacity,
   throws if stream is closed."
  [stream val]
  (when (:closed stream) (throw (ex-info "Cannot put to closed stream" {})))
  (let [storage (:storage stream)
        len (storage/length storage)
        head (:head stream)
        available (- len head)]
    (if (and (:capacity stream) (>= available (:capacity stream)))
      {:full stream}
      {:ok (update stream :storage storage/append val)})))


(defn closed?
  "Returns true if the stream is closed."
  [stream]
  (:closed stream))


(defn close
  "Close the stream. No more puts allowed."
  [stream]
  (assoc stream :closed true))


(defn length
  "Number of available (untaken) values in the stream."
  [stream]
  (- (storage/length (:storage stream)) (:head stream)))


;; =============================================================================
;; Destructive Take
;; =============================================================================

(defn take
  "Destructively consume the next value from the stream. Returns:
   {:ok val, :stream stream'} - value available, head advanced
   :empty                     - no values, stream open (park the taker)
   :end                       - no values, stream closed"
  [stream]
  (let [head (:head stream)
        storage (:storage stream)
        len (storage/length storage)]
    (if (< head len)
      (let [val (storage/read-at storage head)]
        {:ok val, :stream (update stream :head inc)})
      (if (:closed stream) :end :empty))))


;; =============================================================================
;; Cursor
;; =============================================================================
;; A cursor is a map:
;;   {:stream-ref <keyword>   ;; reference to stream in VM store
;;    :position int}

(defn cursor
  "Create a cursor at position 0 for the given stream reference."
  [stream-ref]
  {:stream-ref stream-ref, :position 0})


(defn next
  "Advance the cursor by one position. Returns:
   {:ok val, :cursor cursor'} - data available, cursor advanced
   :blocked                   - at end of open stream, no data yet
   :end                       - at end of closed stream
   :daostream/gap             - position has been consumed past by take"
  [cursor stream]
  (let [pos (:position cursor)
        head (:head stream)
        storage (:storage stream)
        len (storage/length storage)]
    (cond (< pos head) :daostream/gap
          (< pos len) (let [val (storage/read-at storage pos)]
                        {:ok val, :cursor (update cursor :position inc)})
          (:closed stream) :end
          :else :blocked)))


(defn ->seq
  "Return a lazy seq over the stream's available values in append order."
  [stream]
  (let [storage (:storage stream)
        head (:head stream)
        len (storage/length storage)]
    (letfn [(step
              [i]
              (when (< i len)
                (lazy-seq (cons (storage/read-at storage i) (step (inc i))))))]
      (step head))))


(defn take-last-seq
  "Return a seq of the last n items in the stream (from available values).
   Efficient: uses read-at with high indices, no full scan."
  [stream n]
  (when-not (number? n)
    (throw (ex-info "take-last-seq requires a numeric count" {:n n})))
  (let [n (long n)
        storage (:storage stream)
        head (:head stream)
        len (storage/length storage)
        available (- len head)
        start (+ head (max 0 (- available n)))]
    (letfn [(step
              [i]
              (when (< i len)
                (lazy-seq (cons (storage/read-at storage i) (step (inc i))))))]
      (step start))))


(defn seek
  "Return a cursor at the given position."
  [cursor pos]
  (assoc cursor :position pos))


(defn position
  "Return the current position of the cursor."
  [cursor]
  (:position cursor))

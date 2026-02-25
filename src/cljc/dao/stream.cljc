(ns dao.stream
  "DaoStream: bidirectional channel with cursors.

  A stream is a pure data descriptor (map):
    {:log []|{...}     ;; inline vector or nested stream descriptor
     :head 0           ;; absolute position of first untaken value
     :capacity nil|int ;; nil = unbounded
     :closed false
     :log-limit 1024}  ;; threshold before promoting log to reference

  When :log is a vector (inline mode), all values live in the descriptor.
  When :log is a map (reference mode), values live in the store.

  All data-touching functions take store as first arg.
  Store is ignored when :log is inline (vector).
  Returns include :store only when store was modified."
  (:refer-clojure :exclude [next take]))


;; =============================================================================
;; IStream Protocol (for alternative implementations: remote, file-backed,
;; etc.)
;; =============================================================================

(defprotocol IStream

  (-put [this store val])

  (-take [this store])

  (-next [this store cursor])

  (-length [this store])

  (-close [this])

  (-closed? [this]))


;; =============================================================================
;; Internal: inline vs ref dispatch
;; =============================================================================

(defn- inline?
  [stream]
  (vector? (:log stream)))


(defn- log-length
  "Length of the log. Inline: count of vector. Ref: recursive."
  [store stream]
  (if (inline? stream)
    (count (:log stream))
    (let [inner (:log stream)] (log-length store inner))))


(defn- log-read-at
  "Read value at absolute position. Inline: nth. Ref: recursive."
  [store stream pos]
  (if (inline? stream)
    (get (:log stream) pos)
    (let [inner (:log stream)] (log-read-at store inner pos))))


(defn- log-append
  "Append a value to the log. Returns {:log stream' ...} or {:log stream', :store store'}.
   Handles promotion when inline log exceeds :log-limit."
  [store stream val]
  (if (inline? stream)
    (let [new-log (conj (:log stream) val)
          log-limit (:log-limit stream)]
      (if (and log-limit (> (count new-log) log-limit))
        ;; Promote: move vector into a new inner stream descriptor in the
        ;; store
        (let [inner-stream {:log new-log,
                            :head 0,
                            :capacity nil,
                            :closed false,
                            :log-limit nil}]
          {:stream (assoc stream :log inner-stream), :store store})
        {:stream (assoc stream :log new-log)}))
    ;; Ref mode: append to inner stream descriptor
    (let [inner (:log stream)
          result (log-append store inner val)]
      {:stream (assoc stream :log (:stream result)), :store (:store result)})))


;; =============================================================================
;; Stream
;; =============================================================================

(defn make
  "Create a new stream descriptor.
   Options:
     :capacity  - max values before full (nil = unbounded)
     :log-limit - max inline log size before promoting to reference (default 1024, nil = never promote)"
  [& {:keys [capacity log-limit], :or {log-limit 1024}}]
  {:log [], :head 0, :capacity capacity, :closed false, :log-limit log-limit})


(defn put
  "Append a value to the stream. Returns:
   {:ok stream'}                on success (inline, no store change)
   {:ok stream', :store store'} on success (ref or promotion)
   {:full stream}               if at capacity
   Throws if stream is closed."
  [store stream val]
  (when (:closed stream) (throw (ex-info "Cannot put to closed stream" {})))
  (let [len (log-length store stream)
        head (:head stream)
        available (- len head)]
    (if (and (:capacity stream) (>= available (:capacity stream)))
      {:full stream}
      (let [result (log-append store stream val)]
        (if (:store result)
          {:ok (:stream result), :store (:store result)}
          {:ok (:stream result)})))))


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
  [store stream]
  (- (log-length store stream) (:head stream)))


;; =============================================================================
;; Destructive Take
;; =============================================================================

(defn take
  "Destructively consume the next value from the stream. Returns:
   {:ok val, :stream stream'} - value available, head advanced
   :empty                     - no values, stream open (park the taker)
   :end                       - no values, stream closed"
  [store stream]
  (let [head (:head stream)
        len (log-length store stream)]
    (if (< head len)
      (let [val (log-read-at store stream head)]
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
  [store cursor stream]
  (let [pos (:position cursor)
        head (:head stream)
        len (log-length store stream)]
    (cond (< pos head) :daostream/gap
          (< pos len) (let [val (log-read-at store stream pos)]
                        {:ok val, :cursor (update cursor :position inc)})
          (:closed stream) :end
          :else :blocked)))


(defn ->seq
  "Return a lazy seq over the stream's available values in append order."
  [store stream]
  (let [head (:head stream)
        len (log-length store stream)]
    (letfn [(step
              [i]
              (when (< i len)
                (lazy-seq (cons (log-read-at store stream i) (step (inc i))))))]
      (step head))))


(defn take-last-seq
  "Return a seq of the last n items in the stream (from available values).
   Efficient: uses read-at with high indices, no full scan."
  [store stream n]
  (when-not (number? n)
    (throw (ex-info "take-last-seq requires a numeric count" {:n n})))
  (let [n (long n)
        head (:head stream)
        len (log-length store stream)
        available (- len head)
        start (+ head (max 0 (- available n)))]
    (letfn [(step
              [i]
              (when (< i len)
                (lazy-seq (cons (log-read-at store stream i) (step (inc i))))))]
      (step start))))


(defn seek
  "Return a cursor at the given position."
  [cursor pos]
  (assoc cursor :position pos))


(defn position
  "Return the current position of the cursor."
  [cursor]
  (:position cursor))

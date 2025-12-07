(ns dao-stream.protocol
  "DaoStream Protocol: Everything is a Stream

  Five operations over append-only datom streams [e a v t m]:
  - open:   Establish stream connection
  - read:   Consume lazy datom sequence
  - write:  Append datoms to stream
  - close:  Release resources
  - status: Monitor stream state")

;; Core datom structure: [entity attribute value time metadata]
;; - entity (e):    What thing? (user/42, event/777)
;; - attribute (a): What aspect? (:email/subject, :calendar/event)
;; - value (v):     What data? ("Meeting Tomorrow", event/777)
;; - time (t):      When? (inst or long timestamp)
;; - metadata (m):  What context? ({:origin "mobile", :synced true})

(defprotocol IStream
  "Core stream operations for DaoStream.

  Everything is a stream of immutable datoms. This protocol defines
  the five fundamental operations that enable universal interpretation,
  local-first operation, and distributed synchronization."

  (open [this path-config]
    "Establish connection to a stream.

    Args:
      path-config - Map with :path (required) and optional config
                    {:path      \"/user/42/email\"
                     :offset    0                    ; start position (default: 0)
                     :timeout   5000                 ; connection timeout ms
                     :mode      :read-write}         ; :read-only, :write-only, :read-write

    Returns:
      Map with stream handle and status
      {:stream-id  \"abc-123\"
       :status     :connecting    ; :connecting | :connected | :error
       :path       \"/user/42/email\"
       :offset     0
       :error      nil}

    The :path identifies which stream to access. Paths can be hierarchical
    (\"/user/42/email/inbox\") or flat (\"global-analytics\"). If the stream
    doesn't exist and mode allows writing, it will be created (empty, append-only).

    Examples:
      (open stream {:path \"/user/42/email\"})
      (open stream {:path \"/analytics\" :mode :read-only})
      (open stream {:path \"/sync/device-a\" :offset 1000})")

  (read [this stream-id] [this stream-id opts]
    "Consume datoms from stream as lazy sequence.

    Args:
      stream-id - Stream handle from open
      opts      - Optional map with:
                  {:limit      100        ; max datoms to read (nil = unlimited)
                   :timeout    :infinity  ; block duration (:infinity | ms)
                   :from-time  inst       ; only datoms after this time
                   :to-time    inst}      ; only datoms before this time

    Returns:
      Lazy sequence of datoms: ([e a v t m] [e a v t m] ...)
      Blocks until datoms arrive (or timeout). Returns nil on timeout.
      Sequence ends when stream closes or limit reached.

    This is pull-based streaming. Consumer controls pace. If you read slowly,
    datoms queue. If you read fast, you consume from the log. Filtering and
    queries happen client-side on the sequence.

    Examples:
      (read stream stream-id)
      (read stream stream-id {:limit 10})
      (read stream stream-id {:timeout 1000 :from-time #inst \"2025-11-30\"})

      ;; Lazy consumption
      (->> (read stream stream-id)
           (filter #(= (:a %) :email/subject))
           (take 5))")

  (write [this stream-id datom] [this stream-id datoms]
    "Append datom(s) to stream.

    Args:
      stream-id - Stream handle from open
      datom     - Single datom [e a v t m]
      datoms    - Collection of datoms

    Returns:
      Map with write confirmation
      {:status    :written | :error
       :count     1                      ; number of datoms written
       :time      #inst \"2025-11-30\"   ; server timestamp of write
       :offset    12847                  ; new stream position
       :error     nil}

    Writes are append-only. Never updates. Never deletes. To 'change' something,
    write a new datom with a later timestamp. The old datom remains in the stream.

    For deletion semantics, write a retraction datom:
      [entity :db/retract attribute old-value (now) {:operation :retract}]
    Or mark end-of-life:
      [entity :status/archived true (now) {}]

    Readers interpret these as 'no longer current'. The stream keeps full history.

    Examples:
      (write stream sid [user/42 :email/subject \"Hello\" (now) {}])
      (write stream sid [[user/42 :email/subject \"Hi\" (now) {}]
                         [user/42 :email/to user/99 (now) {}]])

      ;; Retraction
      (write stream sid [user/42 :email/archived true (now) {:op :retract}])")

  (close [this stream-id]
    "Release stream resources and disconnect.

    Args:
      stream-id - Stream handle from open

    Returns:
      Map with final status
      {:status   :closed | :error
       :stream-id \"abc-123\"
       :error     nil}

    Stops consuming, releases file handles, network connections, memory buffers.
    Any pending reads return immediately with remaining data.

    Examples:
      (close stream stream-id)")

  (status [this stream-id]
    "Query stream state and health.

    Args:
      stream-id - Stream handle from open

    Returns:
      Map with stream status
      {:status      :connected        ; :connecting | :connected | :closed | :error
       :stream-id   \"abc-123\"
       :path        \"/user/42/email\"
       :position    12847              ; current offset in stream
       :lag         0                  ; datoms behind latest (0 = caught up)
       :total       12847              ; total datoms in stream
       :mode        :read-write
       :created-at  #inst \"2025-11-30T10:00Z\"
       :peers       [{:node-id \"node-1\"               ; distributed peers
                      :last-seen #inst \"2025-11-30T11:00Z\"
                      :offset 12847}]
       :error       nil}

    For distributed streams, :peers shows other nodes consuming this stream.
    :lag indicates how far behind you are (useful for sync monitoring).

    Examples:
      (status stream stream-id)

      ;; Check if caught up
      (zero? (:lag (status stream sid)))"))


;; Helper functions for working with datoms

(defn datom
  "Create a datom [e a v t m].

  Args:
    e - Entity identifier (keyword, uuid, number, etc.)
    a - Attribute (keyword, must be namespaced: :email/subject)
    v - Value (any Clojure data)
    t - Time (inst, long, or nil for current time)
    m - Metadata map (optional, default {})

  Returns:
    Vector [e a v t m]

  Examples:
    (datom :user/42 :email/subject \"Hello\" (now) {:origin \"mobile\"})
    (datom :event/777 :event/time #inst \"2025-12-01\" nil {})
    (datom :user/99 :name \"Alice\" nil {})"
  ([e a v t m]
   [e a v t m])
  ([e a v t]
   [e a v t {}])
  ([e a v]
   [e a v nil {}]))

(defn entity [datom] (nth datom 0))
(defn attribute [datom] (nth datom 1))
(defn value [datom] (nth datom 2))
(defn time [datom] (nth datom 3))
(defn metadata [datom] (nth datom 4))

(defn datom? [x]
  "Check if x is a valid datom structure."
  (and (vector? x)
       (= 5 (count x))
       (keyword? (attribute x))))

(defn retract-datom
  "Create a retraction datom for an existing datom.

  Args:
    original-datom - The datom to retract [e a v t m]
    retraction-time - When to retract (default: current time)

  Returns:
    Retraction datom [e :db/retract a v t {:operation :retract}]

  Examples:
    (retract-datom [user/42 :email/subject \"Old\" t {}])
    ;; => [user/42 :db/retract :email/subject \"Old\" (now) {:operation :retract}]"
  ([original-datom]
   (retract-datom original-datom nil))
  ([original-datom retraction-time]
   (let [[e a v _t _m] original-datom]
     [e :db/retract a v retraction-time {:operation :retract}])))

(comment
  ;; Usage examples

  ;; Open a stream
  (def sid (open stream {:path "/user/42/email"}))
  ;; => {:stream-id "abc-123", :status :connected, :path "/user/42/email", ...}

  ;; Write datoms
  (write stream (:stream-id sid)
         (datom :user/42 :email/subject "Meeting Tomorrow" #inst "2025-11-30T10:00Z" {:origin "mobile"}))
  (write stream (:stream-id sid)
         (datom :user/42 :email/to :user/99))

  ;; Read datoms (lazy sequence)
  (def datoms (read stream (:stream-id sid)))
  ;; => lazy-seq of datoms

  ;; Filter for specific attributes
  (->> (read stream (:stream-id sid))
       (filter #(= (attribute %) :email/subject))
       (map value)
       (take 10))
  ;; => ("Meeting Tomorrow" "Re: Project" ...)

  ;; Check status
  (status stream (:stream-id sid))
  ;; => {:status :connected, :position 12847, :lag 0, ...}

  ;; Close stream
  (close stream (:stream-id sid))
  ;; => {:status :closed, :stream-id "abc-123"}
  )

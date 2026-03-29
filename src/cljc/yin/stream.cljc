(ns yin.stream
  "Stream library for Yin VM.

  Client-facing library that returns effect descriptors for the VM to interpret.
  Streams are append-only logs with external cursors (non-destructive reads).

    (require '[stream :as s])
    (let [ch (s/make 10)
          c  (s/cursor ch)]
      (s/put! ch 42)
      (s/next! c))

  Reads go through cursors, not directly from streams.
  Cursors advance independently: multiple readers, same stream.

  Streams are realized via dao.stream/open! multimethod.
  The VM handles parking, resumption, and scheduling."
  (:require
    [dao.stream :as ds]
    [yin.module :as module]))


;; ============================================================
;; Client-Facing API (returns effect descriptors)
;; ============================================================

(defn make
  "Create a new stream with given capacity.
   Returns an effect descriptor that the VM will execute."
  ([] (make nil))
  ([capacity] {:effect :stream/make, :capacity capacity}))


(defn put!
  "Put a value onto a stream.
   Returns an effect descriptor that the VM will execute."
  [stream-ref val]
  {:effect :stream/put, :stream stream-ref, :val val})


(defn cursor
  "Create a cursor for a stream. Starts at position 0.
   Returns an effect descriptor that the VM will execute."
  [stream-ref]
  {:effect :stream/cursor, :stream stream-ref})


(defn next!
  "Read the next value from a cursor. May park if no data available.
   Returns an effect descriptor that the VM will execute."
  [cursor-ref]
  {:effect :stream/next, :cursor cursor-ref})


(defn take!
  "Destructively take the next value from a stream. May park if empty.
   Returns an effect descriptor that the VM will execute."
  [stream-ref]
  {:effect :stream/take, :stream stream-ref})


(defn close!
  "Close a stream. No more values can be put.
   Returns an effect descriptor that the VM will execute."
  [stream-ref]
  {:effect :stream/close, :stream stream-ref})


;; ============================================================
;; VM-Level Effect Handlers
;; Called by VM when executing stream effects.
;; These bridge between effect descriptors and dao.stream.
;; ============================================================

(defn handle-make
  "Handle :stream/make effect. Creates a stream in the VM store.
   Returns [stream-ref updated-state]."
  [state effect id]
  (let [capacity (:capacity effect)
        descriptor {:transport {:type :ringbuffer
                                :mode :create
                                :capacity capacity}}
        stream (ds/open! descriptor)
        new-store (assoc (:store state) id stream)
        stream-ref {:type :stream-ref, :id id}]
    [stream-ref (assoc state :store new-store)]))


(defn handle-put
  "Handle :stream/put effect. Appends value to stream.
   Returns result map:
   {:value v, :state s}                    on success
   {:park true, :stream-id id, :state s}   if at capacity"
  [state effect]
  (let [stream-ref (:stream effect)
        val (:val effect)
        stream-id (:id stream-ref)
        stream (get (:store state) stream-id)]
    (when (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref})))
    (let [result (ds/put! stream val)]
      (if (= :ok result)
        {:value val, :state state}
        {:park true, :stream-id stream-id, :state state}))))


(defn handle-cursor
  "Handle :stream/cursor effect. Creates a cursor in the VM store.
   Returns [cursor-ref updated-state].
   cursor-data is VM-internal: {:stream-id id, :position 0}"
  [state effect id]
  (let [stream-ref (:stream effect)
        cursor-data {:stream-id (:id stream-ref), :position 0}
        new-store (assoc (:store state) id cursor-data)
        cursor-ref {:type :cursor-ref, :id id}]
    [cursor-ref (assoc state :store new-store)]))


(defn handle-next
  "Handle :stream/next effect. Advances cursor, returns next value.
   Returns result map:
   {:value val, :state s'}                    data available
   {:park true, :cursor-ref ref, :stream-id id, :state s}  blocked
   {:value nil, :state s}                     end of closed stream"
  [state effect]
  (let [cursor-ref (:cursor effect)
        cursor-id (:id cursor-ref)
        store (:store state)
        cursor-data (get store cursor-id)]
    (when (nil? cursor-data)
      (throw (ex-info "Invalid cursor reference" {:ref cursor-ref})))
    (let [stream-id (:stream-id cursor-data)
          stream (get store stream-id)]
      (when (nil? stream)
        (throw (ex-info "Stream not found for cursor" {:stream-id stream-id})))
      (let [ds-cursor {:position (:position cursor-data)}
            result (ds/next stream ds-cursor)]
        (cond (map? result)
              (let [new-cursor (assoc cursor-data
                                      :position (:position (:cursor result)))
                    new-store (assoc store cursor-id new-cursor)]
                {:value (:ok result), :state (assoc state :store new-store)})
              (= :blocked result) {:park true,
                                   :cursor-ref cursor-ref,
                                   :stream-id stream-id,
                                   :state state}
              (= :end result) {:value nil, :state state}
              ;; Reserved: :daostream/gap (requires eviction, deferred)
              (= :daostream/gap result) {:value :daostream/gap,
                                         :state state})))))


(defn handle-take
  "Handle :stream/take effect. Destructively consumes next value.
   Returns result map:
   {:value val, :state s}                value available, head advanced
   {:park true, :stream-id id, :state s} empty (open stream, no data)
   {:value nil, :state s}                end of closed stream"
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        stream (get (:store state) stream-id)]
    (when (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref})))
    (let [result (ds/take! stream)]
      (cond (map? result) {:value (:ok result), :state state}
            (= :empty result) {:park true, :stream-id stream-id, :state state}
            (= :end result) {:value nil, :state state}))))


(defn handle-close
  "Handle :stream/close effect. Closes the stream.
   Returns {:state s', :resume-parked [parked-entries...]}
   where parked-entries are wait-set entries to resume with nil."
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        stream (get (:store state) stream-id)]
    (ds/close! stream)
    ;; Find parked :next and :take continuations waiting on this stream.
    ;; Both resume with nil (end-of-stream). :put waiters are not woken.
    (let [wait-set (or (:wait-set state) [])
          {to-resume true, to-keep false}
          (group-by (fn [entry]
                      (and (= stream-id (:stream-id entry))
                           (#{:next :take} (:reason entry))))
                    wait-set)]
      {:state (assoc state :wait-set (vec (or to-keep []))),
       :resume-parked (or to-resume [])})))


;; ============================================================
;; Module Registration
;; ============================================================

(defn register!
  "Register the stream module with the VM module system."
  []
  (module/register-module! 'stream
                           {'make make,
                            'put! put!,
                            'cursor cursor,
                            'next! next!,
                            'take! take!,
                            'close! close!}))


;; Auto-register on load
(register!)

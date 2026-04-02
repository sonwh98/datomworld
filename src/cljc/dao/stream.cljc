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


(defmulti open!
  "Realize a descriptor into an operational IStream transport."
  (fn [descriptor] (get-in descriptor [:transport :type])))


;; =============================================================================
;; RingBufferStream — reference implementation
;; =============================================================================
;;
;; state-atom holds: {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {} :writer-waiters []}
;;   :buffer          — map of absolute-index -> value
;;   :head            — absolute index of next take! position (oldest available)
;;   :tail            — absolute index of next put! position
;;   :closed          — boolean
;;   :reader-waiters  — map of position -> wait-set-entry; woken when put! appends at that position
;;   :writer-waiters  — vector of wait-set-entries; first one woken when drain-one! frees space
;;
;; Memory is reclaimed during drain-one! via dissoc.
;; Cursors reading at pos < head receive :daostream/gap.
;; When put! appends at position p and (get reader-waiters p) exists:
;;   the entry is removed from reader-waiters and returned in {:woke [...]}.
;; When drain-one! frees space and (seq writer-waiters) is true:
;;   the first writer is popped, its datom written to buffer[tail], and returned in {:woke [...]}

#?(:clj
   (deftype RingBufferStream
     [capacity state-atom]

     clojure.lang.Counted

     (count
       [_]
       (let [state @state-atom]
         (- (:tail state) (:head state))))


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
       (let [state @state-atom]
         (swap! state-atom
                (fn [s]
                  (assoc s :closed true :reader-waiters {} :writer-waiters [])))
         (let [reader-woken (mapv (fn [[_pos entry]] {:entry entry, :value nil})
                                  (:reader-waiters state))
               writer-woken (mapv (fn [entry] {:entry entry, :value nil})
                                  (:writer-waiters state))]
           {:woke (into reader-woken writer-woken)})))


     (closed? [_this] (:closed @state-atom))


     IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom assoc-in [:reader-waiters position] entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom update :writer-waiters conj entry)))

   :cljs
   (deftype RingBufferStream
     [capacity state-atom]

     ICounted

     (-count
       [_]
       (let [state @state-atom]
         (- (:tail state) (:head state))))


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
       (let [state @state-atom]
         (swap! state-atom
                (fn [s]
                  (assoc s :closed true :reader-waiters {} :writer-waiters [])))
         (let [reader-woken (mapv (fn [[_pos entry]] {:entry entry, :value nil})
                                  (:reader-waiters state))
               writer-woken (mapv (fn [entry] {:entry entry, :value nil})
                                  (:writer-waiters state))]
           {:woke (into reader-woken writer-woken)})))


     (closed? [_this] (:closed @state-atom))


     IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom assoc-in [:reader-waiters position] entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom update :writer-waiters conj entry))))


(defmethod open! :ringbuffer [descriptor]
  (let [capacity (get-in descriptor [:transport :capacity])]
    (->RingBufferStream capacity (atom {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {} :writer-waiters []}))))


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
  (cond
    (instance? RingBufferStream stream)
    (let [state-atom (.-state-atom stream)
          result (swap! state-atom
                        (fn [s]
                          (let [head (:head s)
                                tail (:tail s)]
                            (if (< head tail)
                              (let [val (get-in s [:buffer head])
                                    s' (-> s
                                           (update :buffer dissoc head)
                                           (update :head inc))
                                    writer-waiters (:writer-waiters s')]
                                (if (seq writer-waiters)
                                  ;; Pop first writer, write their datom, wake them.
                                  ;; ALSO: check if any reader was waiting for this new tail position.
                                  (let [writer-entry (first writer-waiters)
                                        datom (:datom writer-entry)
                                        tail (:tail s')
                                        reader-entry (get (:reader-waiters s') tail)
                                        s'' (-> s'
                                                (assoc-in [:buffer tail] datom)
                                                (update :tail inc)
                                                (update :writer-waiters (comp vec rest))
                                                (cond-> reader-entry (update :reader-waiters dissoc tail))
                                                (assoc ::take-result
                                                       {:ok val,
                                                        :woke (cond-> [{:entry writer-entry, :value datom}]
                                                                reader-entry (conj {:entry reader-entry, :value datom, :position tail}))}))]
                                    s'')
                                  ;; No writer waiting
                                  (assoc s' ::take-result {:ok val, :woke []})))
                              (if (:closed s)
                                (assoc s ::take-result :end)
                                (assoc s ::take-result :empty))))))]
      (::take-result result))
    :else
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

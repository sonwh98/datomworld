(ns datomworld.demo.continuation-transport
  "Continuation transport on DaoStream v2, for the v2 continuation demo.

   This is `datomworld.continuation-transport` re-expressed on
   `dao.stream`. Three v1 idioms do not survive:

   - **Cursors are opaque.** v1 seeded `{:position 0}` and read `:position`
     back out. v2 mints every cursor at `:dao.stream/oldest` and advances by
     the exact successor `next` returns; nothing here inspects a cursor.
   - **`append!` takes a batch, not a datom.** One transport event is one
     single-element batch, so a reader sees one event per `next`.
   - **There is no position to key `:pending-ks` on.** v1 used the stream
     position as the correlation id. v2 assigns its own monotone `:seq` and
     carries it in the event summary, so correlation is composition bookkeeping
     rather than a property of the transport.

   The full continuation payload still travels off-stream in `:pending-ks`:
   `:make-stream`, `:primitives` and `:bridge` are functions and are
   re-supplied by the receiving composition, so this demo does not pretend to
   serialize them. Stream capacity is a correctness parameter here, not a
   tuning knob — an evicted event is a continuation the receiving VM can never
   pick up, so a `gap` is raised rather than retried."
  (:require [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]))


(def transport-capacity
  "Declared capacity of the continuation medium, in events."
  4096)


(defn make-medium
  "One DaoStream v2 ring buffer for continuation events."
  []
  (ringbuffer/create!
    {:dao.stream/type ringbuffer/transport-type,
     ringbuffer/capacity-key transport-capacity}))


(defn- mint-cursor!
  "Mint the reader cursor this medium starts every consumer at, or throw."
  [handle what]
  (let [result (stream/cursor handle stream/anchor-oldest)
        outcome (:dao.stream/outcome result)]
    (when-not (= :dao.stream/ok outcome)
      (throw (ex-info "Continuation cursor mint failed"
                      {:what what, :outcome outcome})))
    (:dao.stream/cursor result)))


(defn init-state
  "Create a fresh transport state. vm-keys is a collection of keywords
   identifying the consuming VMs (e.g. [:vm-a :vm-b])."
  [vm-keys]
  (let [k-stream (:dao.stream/handle (make-medium))
        cursor (mint-cursor! k-stream :continuation-reader)]
    {:k-stream k-stream,
     :cursors (into {} (map (fn [k] [k cursor])) vm-keys),
     :pending-ks {},
     :seq 0}))


(defn append-event!
  "Append one datom-shaped event as a single-element batch. Returns the
   outcome, so a caller can treat a failed append as what it is."
  [transport a v seq-n]
  (stream/append! transport [[(+ 7000 seq-n) a v (+ 1 seq-n) 0]]))


(defn enqueue-k
  "Append a continuation summary to the medium and keep the full k payload
   off-stream in :pending-ks, correlated by this composition's own :seq."
  [state summary k]
  (let [seq-n (:seq state)
        result (append-event! (:k-stream state)
                              :stream/k
                              (assoc summary :seq seq-n)
                              seq-n)
        outcome (:dao.stream/outcome result)]
    (when-not (= :dao.stream/ok outcome)
      (throw (ex-info "Continuation event not appended"
                      {:outcome outcome, :seq seq-n})))
    (-> state
        (assoc :seq (inc seq-n))
        (assoc-in [:pending-ks seq-n] k))))


(defn enqueue-batch
  "Append a whole continuation in-band: `datoms` followed by the summary
   datom a reader addresses by. Nothing is kept off-stream, so the reader
   rebuilds the continuation from the batch alone."
  [state summary datoms]
  (let [seq-n (:seq state)
        result (stream/append! (:k-stream state)
                               (conj (vec datoms)
                                     [(+ 7000 seq-n) :stream/k
                                      (assoc summary :seq seq-n) (+ 1 seq-n) 0]))
        outcome (:dao.stream/outcome result)]
    (when-not (= :dao.stream/ok outcome)
      (throw (ex-info "Continuation batch not appended"
                      {:outcome outcome, :seq seq-n})))
    (assoc state :seq (inc seq-n))))


(defn- fail-read
  [state vm-key outcome]
  (throw (ex-info "Continuation stream read failed"
                  {:vm-key vm-key,
                   :outcome outcome,
                   :pending (count (:pending-ks state))})))


(defn consume-k-for
  "Consume the next continuation addressed to vm-key from the medium.
   Returns [state' message-or-nil].

   Skips events addressed elsewhere, always advancing past them. When the
   medium has nothing further, the cursor reached so far is persisted and nil
   is returned — the same \"always advances\" contract the v1 version kept."
  [state vm-key]
  (loop [state state
         cursor (get-in state [:cursors vm-key])]
    (let [result (stream/next (:k-stream state) cursor)
          outcome (:dao.stream/outcome result)]
      (cond
        (= :dao.stream/ok outcome)
        (let [cursor' (:dao.stream/cursor result)
              batch (:dao.stream/value result)
              [_e a summary _t _m] (peek batch)]
          (if (and (= a :stream/k) (= (:to summary) vm-key))
            (let [seq-n (:seq summary)
                  k (get-in state [:pending-ks seq-n])]
              [(-> state
                   (assoc-in [:cursors vm-key] cursor')
                   (update :pending-ks dissoc seq-n))
               {:from (:from summary),
                :to (:to summary),
                :summary summary,
                :k k,
                :batch batch}])
            (recur state cursor')))

        (= :dao.stream/blocked outcome)
        [(assoc-in state [:cursors vm-key] cursor) nil]

        :else (fail-read state vm-key outcome)))))


(defn read-events-from
  "Drain the events visible from `cursor`, returning [events cursor'].
   Used by the composition's views, which read without consuming."
  [transport cursor]
  (loop [cursor cursor
         acc []]
    (let [result (stream/next transport cursor)
          outcome (:dao.stream/outcome result)]
      (if-not (= :dao.stream/ok outcome)
        [acc cursor]
        (recur (:dao.stream/cursor result)
               (into acc (:dao.stream/value result)))))))


(defn oldest-cursor!
  "Mint a fresh read cursor at the oldest retained event."
  [transport]
  (mint-cursor! transport :transport-view))


(defn in-flight-summary
  "Summarize the continuation events visible to each VM between that VM's
   cursor and the head of the medium. Reads without consuming."
  [transport cursors]
  (into []
        (mapcat
          (fn [[vm-key cur]]
            (let [[events _] (read-events-from transport cur)]
              (keep (fn [[_e a summary _t _m]]
                      (when (and (= a :stream/k) (= (:to summary) vm-key))
                        {:from (:from summary),
                         :to (:to summary),
                         :control (:control summary),
                         :k-depth (:k-depth summary),
                         :seq (:seq summary)}))
                    events)))
          cursors)))

(ns dao.space.transactor
  "The `:transactor` stream: a single-writer wrapper over an explicit local
   `dao.stream` plus an explicit DaoJing intake pool (docs/design/dao.jing.md,
   Publication from an agent).

   Descriptor:

     {:dao.stream/type :transactor
      :local-stream s   ; must satisfy IDaoStreamReader and IDaoStreamWriter;
                        ; supplied, never created, registered, or closed
      :intake-pool [p]  ; non-empty; every member satisfies IDaoStreamWriter;
                        ; supplied, never created, registered, or closed
      :name n}          ; optional, diagnostic only

   The transactor owns transaction time. Every append! or transact! writes
   exactly ONE atomic transaction record to the local stream

     {:dao.space/transaction {:t <nonnegative-integer> :datoms [...]}}

   through exactly one ds/append!, so no reader can ever observe a torn
   transaction. t is allocated from a per-wrapper watermark: on open the
   retained history is scanned from cursor zero (index/snapshot-datoms) and
   the next t is derived as 0 for an empty history or 1 + the maximum
   integer datom t otherwise. A retention gap or malformed history throws.
   The scan is the causality boundary; a caller-supplied next-t is never
   accepted.

   Single-writer: calls through one wrapper are serialized around timestamp
   allocation and append. The watermark is per-wrapper state, so two wrappers over
   the same local stream each derive the same next-t and silently write
   colliding records. That is invalid and cannot be silently coordinated
   without shared mutable state. Use one wrapper per local stream.

   publish! builds the covered indexes over the local stream and enqueues
   them into the intake pool (dao.space.index/publish-index!). Publication
   is explicit and acknowledges enqueuing only, never observer
   materialization.

   Closing a wrapper is per handle: it rejects further appends/transacts but
   neither closes nor erases the local stream."
  (:require [dao.datom :as datom]
            [dao.space.index :as index]
            [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


(defn- entity->datoms
  "One datom per k/v pair, all sharing transaction time `t`. `:db/id` is
   required: a durable log cannot mint per-batch tempids without colliding
   across appends. Attribute order within one entity's own datoms is
   whatever the host's map iteration yields — unspecified across platforms."
  [m t]
  (when-not (contains? m :db/id)
    (throw (ex-info "entity map requires :db/id" {:entity m})))
  (let [e (:db/id m)]
    (mapv (fn [[a v]]
            (let [d [e a v t datom/default-op]]
              (when-not (datom/local-datom? d)
                (throw (ex-info "invalid persistent local datom"
                                {:datom d, :entity m})))
              d))
          (remove (fn [[a _v]] (= a :db/id)) m))))


(defn- pad-datom
  "Pad [e a v] / [e a v t m] to the canonical 5-tuple. The transactor owns
   transaction time: a non-nil explicit t is rejected, a nil t is stamped
   with the allocated `t`. A nil m takes the default assertion op; an
   explicit m (0, a retraction) is preserved."
  [d t]
  (let [[e a v dt dm] d]
    (when-not (nil? dt)
      (throw
        (ex-info
          "the transactor owns transaction time: explicit datom t is rejected"
          {:datom d})))
    (let [padded [e a v t (if (nil? dm) datom/default-op dm)]]
      (when-not (datom/local-datom? padded)
        (throw (ex-info "invalid persistent local datom"
                        {:datom padded, :input d})))
      padded)))


(defn- val->datoms
  [val t]
  (cond
    (map? val) (entity->datoms val t)
    (and (vector? val) (<= 3 (count val) 5)) [(pad-datom val t)]
    :else
    (throw
      (ex-info
        "a value must be an entity map or datom vector [e a v] / [e a v nil m]"
        {:val val}))))


#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-write-lock
  "Serialize timestamp allocation and append on hosts with shared-memory
   threads. ClojureScript and ClojureDart calls are synchronous within one
   isolate, so the direct call has the same single-writer semantics."
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- append-packet!
  "Append one atomic transaction record to the local stream. The watermark
   advances only after the underlying ds/append! answers {:result :ok}; on
   any other result or on a thrown error the wrapper keeps the same t and
   the call can be retried. Returns {:result :ok, :t t, :datoms datoms}."
  [local-stream next-t t datoms context]
  (let [record {:dao.space/transaction {:t t, :datoms datoms}}
        result (ds/append! local-stream record)]
    (when-not (and (map? result) (= :ok (:result result)))
      (throw (ex-info (str context " stream append failed: " (pr-str result))
                      {:result result, :t t, :datoms datoms})))
    (swap! next-t inc)
    {:result :ok, :t t, :datoms datoms}))


(defn- derive-next-t
  "Derive the next transaction time from a flattened retained history: 0 for
   an empty history, else 1 + the maximum datom t. A datom t that is not a
   non-negative integer is a malformed history and throws — this scan is the
   causality boundary of the wrapper."
  [datoms]
  (if (empty? datoms)
    0
    (let [ts (mapv index/datom-t datoms)]
      (when-not (every? #(and (integer? %) (<= 0 %)) ts)
        (throw
          (ex-info
            "malformed retained history: datom t must be a non-negative integer"
            {:history datoms})))
      (inc (reduce max ts)))))


(deftype DaoStreamLog
  [local-stream intake-pool stream-name next-t state]

  ds/IDaoStreamWriter

  (append!
    [_this val]
    (with-write-lock
      next-t
      (fn []
        (when (:closed @state)
          (throw (ex-info "Cannot append to closed stream"
                          {:name stream-name})))
        (let [t @next-t
              datoms (val->datoms val t)]
          (when (empty? datoms)
            (throw (ex-info "append! produced no datoms" {:val val})))
          (append-packet! local-stream next-t t datoms "append!")))))


  ds/IDaoStreamReader

  (next [_this cursor] (ds/next local-stream cursor))


  ds/IDaoStreamBound

  (close!
    [_this]
    (with-write-lock next-t
      (fn [] (swap! state assoc :closed true) {:woke []})))


  (closed? [_this] (:closed @state)))


(ds/defopen
  :transactor
  [descriptor]
  (let [{:keys [local-stream intake-pool name]} descriptor
        stream-name (or name "transactor")]
    (when (contains? descriptor :next-t)
      (throw
        (ex-info
          ":transactor derives transaction time from retained history; :next-t is not accepted"
          {:descriptor descriptor})))
    (when-not (and (satisfies? ds/IDaoStreamReader local-stream)
                   (satisfies? ds/IDaoStreamWriter local-stream))
      (throw
        (ex-info
          ":transactor descriptor requires a :local-stream satisfying IDaoStreamReader and IDaoStreamWriter"
          {:descriptor descriptor})))
    (when-not (and (coll? intake-pool) (seq intake-pool))
      (throw
        (ex-info
          ":transactor descriptor requires a non-empty :intake-pool of streams satisfying IDaoStreamWriter"
          {:descriptor descriptor})))
    (doseq [intake intake-pool]
      (when-not (satisfies? ds/IDaoStreamWriter intake)
        (throw
          (ex-info
            ":transactor :intake-pool members must satisfy IDaoStreamWriter"
            {:intake-pool intake-pool}))))
    (let [next-t (derive-next-t (index/snapshot-datoms local-stream))]
      (->DaoStreamLog local-stream
                      intake-pool
                      stream-name
                      (atom next-t)
                      (atom {:closed false})))))


(defn publish!
  "Build and enqueue the covered indexes over the local stream into the
   intake pool (dao.space.index/publish-index!), returning its
   {:manifest-address ... :manifest ...}. opts are passed through to
   publish-index! (e.g. :branching-factor, :select-stream). Publication is
   explicit and acknowledges enqueuing only, never observer
   materialization."
  ([log] (publish! log nil))
  ([^DaoStreamLog log opts]
   (index/publish-index! (.-local-stream log) (.-intake-pool log) opts)))


(defn transact!
  "Commits a non-empty collection of entity maps or datom vectors as a
  single atomic transaction: every datom shares one allocated t and lands
   in one transaction record through exactly one ds/append!, so no partial
   prefix is possible. Throws when the stream is closed, the collection is
   empty, any item is invalid, or the expansion yields no datoms."
  [^DaoStreamLog log tx-data]
  (when (empty? tx-data)
    (throw (ex-info "transact! requires at least one transaction item"
                    {:tx-data tx-data})))
  (let [next-t (.-next-t log)]
    (with-write-lock
      next-t
      (fn []
        (when (ds/closed? log)
          (throw (ex-info "Cannot transact! to closed stream"
                          {:name (.-stream-name log)})))
        (let [t @next-t
              datoms (into [] (mapcat #(val->datoms % t)) tx-data)]
          (when (empty? datoms)
            (throw (ex-info "transact! produced no datoms" {:tx-data tx-data})))
          (append-packet! (.-local-stream log) next-t t datoms "transact!"))))))

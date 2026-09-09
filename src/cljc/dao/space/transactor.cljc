(ns dao.space.transactor
  "The agent-side transactor: a plain value with explicit named operations
   over an explicit local dao.stream.v2 handle plus an explicit intake pool
   of dao.stream.v2 writers (docs/design/dao.jing.md, Publication from an
   agent; docs/design/dao.space.transactor.md).

   A transactor is not a stream in v2 — it is an interpreter over one
   (dao.stream.md, Composition): append!/transact! allocate transaction time
   and write transaction records through the local stream's writer surface,
   and reads go through the caller's own local handle, which the transactor
   never owns.

   Spec (create!):

     {:local-stream s   ; must satisfy stream/reader? and stream/writer?;
                        ; supplied, never created, registered, or closed
      :intake-pool [p]  ; non-empty; every member satisfies stream/writer?;
                        ; supplied, never created, registered, or closed
      :name n}          ; optional, diagnostic only

   create! validates surfaces, never retention: stream/reader? and
   stream/writer? do not establish the complete retention the history scan
   requires. The host composition supplies a handle created by
   dao.stream.v2.memory-log/create!; supplying an evicting transport is a
   host-assembly defect (detectable, deliberately not checked — T18,
   docs/design/dao.space.transactor.md).

   The transactor owns transaction time. Every append! or transact! writes
   exactly ONE atomic transaction record to the local stream

     {:dao.space/transaction {:t <nonnegative-integer> :datoms [...]}}

   through exactly one local append, so no reader can ever observe a torn
   transaction. t is allocated from a per-value watermark: on create! the
   retained history is read from its origin (index/snapshot-datoms) and the
   next t is derived as 0 for an empty history or 1 + the maximum integer
   datom t otherwise. A malformed history throws. The scan is the causality
   boundary; a caller-supplied next-t is never accepted.

   Operational outcomes are data; argument defects keep throwing. The
   watermark advances if and only if the local append answered
   :dao.stream/ok, so retrying the identical call re-attempts the identical
   t. publish! builds the covered indexes over the local stream and
   enqueues them into the intake pool (dao.space.index/publish-index!).
   Publication is explicit and acknowledges enqueuing only, never observer
   materialization.

   Closing is per value: it rejects further appends/transacts with
   {:dao.stream/outcome :dao.stream/closed} but neither closes nor erases
   the local stream or the intake pool."
  (:require [dao.datom :as datom]
            [dao.space.index :as index]
            [dao.stream.v2 :as stream]))


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


(defn- derive-next-t
  "Derive the next transaction time from a flattened retained history: 0 for
   an empty history, else 1 + the maximum datom t. A datom t that is not a
   non-negative integer is a malformed history and throws — this scan is the
   causality boundary of the value."
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


(defn- append-packet!
  "Append one atomic transaction record to the local stream. The answer is
   validated with the contract's own validator before it is interpreted:
   :dao.stream/ok advances the watermark and returns the v2 receipt; any
   other conforming outcome is returned as data with the watermark
   unchanged, so the same t is still pending and retrying the identical
   call re-attempts it; a non-outcome answer is folded to
   :dao.stream/transport-error with the raw answer retained under
   :dao.stream/answer."
  [local-stream next-t t datoms]
  (let [record {:dao.space/transaction {:t t, :datoms datoms}}
        answer (stream/append! local-stream record)]
    (if-not (stream/valid-outcome? :append! answer)
      {:dao.stream/outcome :dao.stream/transport-error
       :dao.stream/answer answer}
      (do (when (= :dao.stream/ok (:dao.stream/outcome answer))
            (swap! next-t inc))
          (if (= :dao.stream/ok (:dao.stream/outcome answer))
            {:dao.stream/outcome :dao.stream/ok
             :dao.space/t t
             :dao.space/datoms datoms}
            answer)))))


(defn create!
  "Create a transactor value from a spec — the value, or a throw: the
   failure modes are a malformed spec and a malformed retained history,
   both of which a caller can only abort on.

   Validates surfaces, never retention (D2/T18): stream/reader? and
   stream/writer? check the dao.stream.v2 reader and writer surfaces, and
   neither establishes the complete-retention transport the history scan
   requires. The host composition supplies a handle created by
   dao.stream.v2.memory-log/create!; supplying an evicting transport is a
   host-assembly defect — detectable, deliberately not checked, because a
   type check would couple dao.space to memory-log by name and reject a
   future correct complete-retention transport."
  [spec]
  (when-not (map? spec)
    (throw
      (ex-info
        "transactor spec must be a map"
        {:spec spec})))
  (let [{:keys [local-stream intake-pool name]} spec]
    (when (contains? spec :next-t)
      (throw
        (ex-info
          "the transactor derives transaction time from retained history; :next-t is not accepted"
          {:spec spec})))
    (when-not (and (stream/reader? local-stream)
                   (stream/writer? local-stream))
      (throw
        (ex-info
          "transactor spec requires a :local-stream satisfying the dao.stream.v2 reader and writer surfaces"
          {:spec spec})))
    (when-not (and (coll? intake-pool) (seq intake-pool))
      (throw
        (ex-info
          "transactor spec requires a non-empty :intake-pool of streams satisfying the dao.stream.v2 writer surface"
          {:spec spec})))
    (doseq [intake intake-pool]
      (when-not (stream/writer? intake)
        (throw
          (ex-info
            "transactor :intake-pool members must satisfy IDaoStreamWriter"
            {:intake-pool intake-pool}))))
    (let [t (derive-next-t (index/snapshot-datoms local-stream))]
      {:dao.space/transactor true
       :local-stream local-stream
       :intake-pool intake-pool
       :name (or name "transactor")
       :next-t (atom t)
       :state (atom {:closed false})})))


(defn append!
  "Append one entity map or datom vector as a single atomic transaction.
   Answers the v2 receipt on ok — {:dao.stream/outcome :dao.stream/ok
   :dao.space/t t :dao.space/datoms datoms} — the local append's conforming
   outcome as data otherwise (watermark unchanged, same t still pending),
   and {:dao.stream/outcome :dao.stream/closed} on a closed transactor.
   A malformed value still throws: it is a defect in the caller's own
   argument, detected before any stream is touched."
  [log val]
  (let [{:keys [local-stream next-t state]} log]
    (with-write-lock
      next-t
      (fn []
        (if (:closed @state)
          {:dao.stream/outcome :dao.stream/closed}
          (let [t @next-t
                datoms (val->datoms val t)]
            (when (empty? datoms)
              (throw (ex-info "append! produced no datoms" {:val val})))
            (append-packet! local-stream next-t t datoms)))))))


(defn transact!
  "Commits a non-empty collection of entity maps or datom vectors as a
  single atomic transaction: every datom shares one allocated t and lands
  in one transaction record through exactly one local append, so no partial
  prefix is possible. Answers the v2 receipt on ok, the local append's
  conforming outcome as data otherwise, and
  {:dao.stream/outcome :dao.stream/closed} on a closed transactor. Throws
  when the collection is empty, any item is invalid, or the expansion
  yields no datoms."
  [log tx-data]
  (when (empty? tx-data)
    (throw (ex-info "transact! requires at least one transaction item"
                    {:tx-data tx-data})))
  (let [{:keys [local-stream next-t state]} log]
    (with-write-lock
      next-t
      (fn []
        (if (:closed @state)
          {:dao.stream/outcome :dao.stream/closed}
          (let [t @next-t
                datoms (into [] (mapcat #(val->datoms % t)) tx-data)]
            (when (empty? datoms)
              (throw (ex-info "transact! produced no datoms" {:tx-data tx-data})))
            (append-packet! local-stream next-t t datoms)))))))


(defn close!
  "Close this transactor value: further append!/transact! answer
   {:dao.stream/outcome :dao.stream/closed} rather than throwing. Per
   value — neither the local stream nor the intake pool is closed or
   erased. Idempotent; linearizes after an in-flight append."
  [log]
  (let [{:keys [next-t state]} log]
    (with-write-lock
      next-t
      (fn []
        (swap! state assoc :closed true)
        {:dao.stream/outcome :dao.stream/ok}))))


(defn publish!
  "Build and enqueue the covered indexes over the local stream into the
   intake pool (dao.space.index/publish-index!), returning its
   {:manifest-address ... :manifest ...}. opts are passed through to
   publish-index! (e.g. :branching-factor, :select-stream). Publication is
   explicit and acknowledges enqueuing only, never observer
   materialization."
  ([log] (publish! log nil))
  ([log opts]
   (index/publish-index! (:local-stream log) (:intake-pool log) opts)))

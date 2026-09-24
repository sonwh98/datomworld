(ns dao.jing.mem
  "An ephemeral, thread-safe, content-addressed in-memory DaoJing byte
   store (docs/design/dao.jing.md, Materialization rule and Reads;
   docs/design/dao.jing.cbor.md, Memory and files).

   (create-content-mem) returns a plain-data byte-store handle carrying
   :put-bytes-fn, :get-bytes-fn, :close-fn and the test-facing :entries-fn,
   accepted by dao.jing/materialize!, dao.jing/get, and dao.jing/close!.
   The handle embeds no intake stream and identifies no source: an address
   is derived solely from a payload, and the store never records which
   stream carried a value, so equal payloads converge on exactly one entry.

   What the store holds is the canonical CBOR payload bytes of each value,
   an immutable snapshot under its content address. The state lives only in
   the closures: no atom or array is reachable through the handle, bytes
   are copied on insertion and on retrieval, so no caller can change a
   snapshot in place. No framing -- nothing here is ever re-ingested across
   process death.

   :put-bytes-fn is an atomic insert-if-absent. The caller-supplied address
   must be a :segment/<algo>-... content address whose digest is the hash
   of the bytes; otherwise it throws. An address already holding the same
   bytes reports :present and is never overwritten; an address already
   holding different bytes is an integrity-failure collision and throws
   loudly. The CAS loop is linearizable under JVM contention. Operations
   after close throw; close itself is idempotent."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]))


(defn- validate-address-bytes!
  "Reject a put before any write: the address must be a valid segment
   content address whose digest is the hash of exactly these bytes."
  [address bs]
  (when-not (jing/segment-address? address)
    (throw (ex-info "dao.jing.mem: not a segment content address"
                    {:address address})))
  (when-not (jing/segment-bytes-match? address bs)
    (throw (ex-info "dao.jing.mem: content address does not match the bytes"
                    {:address address}))))


(defn- put-bytes-fn
  "Backend effect for one byte store: atomic insert-if-absent of a copy of
   the bytes. The winner of the CAS returns :inserted; an existing equal
   snapshot returns :present; an existing unequal snapshot is a collision
   and throws. The closed check happens inside the loop so the operation is
   linearizable against close."
  [state]
  (fn [address bs]
    ;; copy first: the caller owns bs and may change it at any moment, so
    ;; validation and every later comparison see only the snapshot
    (let [snapshot (cbor/copy-bytes bs)]
      (validate-address-bytes! address snapshot)
      (loop []
        (let [s @state
              content (:content s)]
          (when (:closed? s)
            (throw (ex-info "dao.jing.mem: content store is closed"
                            {:address address})))
          (if (contains? content address)
            (if (cbor/bytes= (get content address) snapshot)
              :present
              (throw
                (ex-info
                  (str "dao.jing.mem: content collision: unequal bytes "
                       "at the same content address")
                  {:address address})))
            (if (compare-and-set! state
                                  s
                                  (assoc s :content
                                         (assoc content address snapshot)))
              :inserted
              (recur))))))))


(defn- get-bytes-fn
  "Backend effect for one byte store: lookup with a caller-supplied
   not-found, answering a fresh copy of the stored bytes so the snapshot
   stays immutable."
  [state]
  (fn [address not-found]
    (let [{:keys [closed? content]} @state]
      (when closed?
        (throw (ex-info "dao.jing.mem: content store is closed"
                        {:address address})))
      (if (contains? content address)
        (cbor/copy-bytes (get content address))
        not-found))))


(defn- close-fn
  "Backend effect for one byte store: idempotent close that returns nil."
  [state]
  (fn [] (swap! state assoc :closed? true) nil))


(defn- entries-fn
  "The test-facing snapshot: a map of content address to a fresh copy of
   the stored bytes, readable after close."
  [state]
  (fn []
    (into {}
          (map (fn [[address bs]] [address (cbor/copy-bytes bs)]))
          (:content @state))))


(defn create-content-mem
  "Create an ephemeral, thread-safe, content-addressed in-memory DaoJing
   byte store.

   Returns plain data:

     {:put-bytes-fn f
      :get-bytes-fn g
      :close-fn c
      :entries-fn e}

   The private state is closed over by the four effects and reachable
   through nothing else. The content it holds maps content addresses to
   copies of the canonical payload bytes stored at them and never carries a
   source stream or provenance stamp.

   The optional seed is a map of address to bytes planted verbatim (copied)
   and unverified: a test's way to seat forged content behind an address,
   which no put could ever do."
  ([] (create-content-mem {}))
  ([seed]
   (let [state (atom {:closed? false,
                      :content (into {}
                                     (map (fn [[address bs]]
                                            [address (cbor/copy-bytes bs)]))
                                     seed)})]
     {:put-bytes-fn (put-bytes-fn state),
      :get-bytes-fn (get-bytes-fn state),
      :close-fn (close-fn state),
      :entries-fn (entries-fn state)})))


(defn entries
  "The stored address->value entries of one byte store, each snapshot
   hash-verified against its address and decoded -- this backend's
   test-facing content view. Seeded bytes that do not hash to their address
   make this throw, as any read of them would."
  [handle]
  (into {}
        (map (fn [[address bs]] [address (jing/segment-value address bs)]))
        ((:entries-fn handle))))


(defn entry-bytes
  "The stored address->bytes snapshot of one byte store: fresh copies,
   unverified and undecoded, for tests that inspect what a backend holds."
  [handle]
  ((:entries-fn handle)))

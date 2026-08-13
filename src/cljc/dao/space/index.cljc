(ns dao.space.index
  "The transactor-side indexing library (docs/design/dao.jing.md, Publication
   from an agent).

   In dao.space every agent appending to its own `dao.stream` is its own
   transactor, so every agent also owns indexing its own datoms. This
   library is that duty, the write-side peer of `dao.space.query` (the
   embeddable reader that consumes what this publishes):

     - `publish-index!` snapshots the agent's local stream, builds the four
       covered indexes as immutable content-addressed `dao.data.btree` node
       blobs, and appends them (children before parents, manifest last) to
       one intake stream selected from an explicit pool. A DaoJing observer
       over the pool materializes the blobs into content-addressed storage.
     - `read-manifest` / `read-datoms` read a published manifest back and
       walk its EAVT node graph eagerly; `restored-indexes` re-attaches the
       manifest's trees lazily.

   It owns the index *realization* both sides share:

     - the sort orders (`eavt-cmp`/`aevt-cmp`/`avet-cmp`/`vaet-cmp` over
       heterogeneous values) and the in-memory index (`index-datoms`,
       `subseq-from`)
     - the persisted node-blob format, both directions: nodes store as
       plain-EDN content-addressed segment blobs (Merkle by construction —
       dao.data.btree stores children before parents); the manifest is
       exactly `{:indexes {:eavt addr-or-nil :aevt ... :avet ... :vaet ...}
       :count n :branching-factor n}` — no source stream, no pool, no epoch,
       no own address.

   Build and lazy restore run on every platform: the tree is dao.data.btree,
   one .cljc source (JVM, cljs, cljd), and durability is its IStorage over a
   content-store handle (dao.data.btree.storage). Node blobs are ordinary
   EDN either way."
  (:require [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]
            [dao.stream :as ds]))


;; =============================================================================
;; Value comparison (heterogeneous datom values)
;; =============================================================================

(defn- type-rank
  [x]
  (cond (nil? x) 0
        (boolean? x) 1
        (number? x) 2
        (string? x) 3
        (keyword? x) 4
        (symbol? x) 5
        :else 6))


(defn compare-vals
  "Compare two datom values across heterogeneous types using type-rank."
  [a b]
  (let [ra (type-rank a)
        rb (type-rank b)]
    (if (= ra rb)
      (try (compare a b)
           ;; :cljd FIRST in every reader-conditional: the cljd host-eval
           ;; pass also matches :clj, so a :clj branch appearing earlier
           ;; in the clause list wins on cljd too — here that would mean
           ;; referencing the JVM-only ClassCastException, which doesn't
           ;; exist in Dart.
           (catch #?(:cljd Object
                     :clj ClassCastException
                     :cljs js/Error)
                  _
             (compare (str a) (str b))))
      (compare ra rb))))


;; =============================================================================
;; Datom slots and sort orders
;; =============================================================================

(defn datom-e
  [d]
  (nth d 0))


(defn datom-a
  [d]
  (nth d 1))


(defn datom-v
  [d]
  (nth d 2))


(defn datom-t
  [d]
  (nth d 3))


(defn datom-m
  [d]
  (nth d 4))


(defn datom-ns
  "The namespace slot of a datom, or nil for a local 5-tuple. `[e a v t m]`
   is a literal prefix of `[e a v t m ns]`: a stream stores the short form
   and only a cross-stream fold materializes the sixth slot, so absence is
   ordinary, not an error (docs/agents/datom-spec.md)."
  [d]
  (nth d 5 nil))


(defn- cmp-field
  "Nil-first, heterogeneous-safe field comparison. Entity ids are not
   guaranteed to be integers here — a raw entity map's :db/id is
   caller-chosen and can be any type — so every slot, not just v, needs
   compare-vals."
  [a b]
  (cond (nil? a) (if (nil? b) 0 -1)
        (nil? b) 1
        :else (compare-vals a b)))


;; ns is the last tiebreaker in every order, never a leading component.
;; These sets are *sets*: without it, two datoms from different streams
;; that agree on [e a v t m] compare 0 and one is silently dropped at
;; insert. As a trailing field it leaves 5-tuple ordering identical (nil
;; vs nil is 0, exactly as before) and only separates what was previously
;; conflated. Slot order is not index order — a fold that wants
;; per-stream locality builds its own comparator.

(defn eavt-cmp
  [d1 d2]
  (let [c (cmp-field (datom-e d1) (datom-e d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-a d1) (datom-a d2))]
        (if (zero? c)
          (let [c (cmp-field (datom-v d1) (datom-v d2))]
            (if (zero? c)
              (let [c (cmp-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (let [c (cmp-field (datom-m d1) (datom-m d2))]
                    (if (zero? c) (cmp-field (datom-ns d1) (datom-ns d2)) c))
                  c))
              c))
          c))
      c)))


(defn aevt-cmp
  [d1 d2]
  (let [c (cmp-field (datom-a d1) (datom-a d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-e d1) (datom-e d2))]
        (if (zero? c)
          (let [c (cmp-field (datom-v d1) (datom-v d2))]
            (if (zero? c)
              (let [c (cmp-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (let [c (cmp-field (datom-m d1) (datom-m d2))]
                    (if (zero? c) (cmp-field (datom-ns d1) (datom-ns d2)) c))
                  c))
              c))
          c))
      c)))


(defn avet-cmp
  [d1 d2]
  (let [c (cmp-field (datom-a d1) (datom-a d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-v d1) (datom-v d2))]
        (if (zero? c)
          (let [c (cmp-field (datom-e d1) (datom-e d2))]
            (if (zero? c)
              (let [c (cmp-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (let [c (cmp-field (datom-m d1) (datom-m d2))]
                    (if (zero? c) (cmp-field (datom-ns d1) (datom-ns d2)) c))
                  c))
              c))
          c))
      c)))


(defn vaet-cmp
  "VAET sort: v, a, e, t, m, ns. Reverse-reference lookup — 'which datoms
   point to this value.' Heterogeneous-safe (the ref value is caller-chosen
   and can be any type, the same way entity ids are)."
  [d1 d2]
  (let [c (cmp-field (datom-v d1) (datom-v d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-a d1) (datom-a d2))]
        (if (zero? c)
          (let [c (cmp-field (datom-e d1) (datom-e d2))]
            (if (zero? c)
              (let [c (cmp-field (datom-t d1) (datom-t d2))]
                (if (zero? c)
                  (let [c (cmp-field (datom-m d1) (datom-m d2))]
                    (if (zero? c) (cmp-field (datom-ns d1) (datom-ns d2)) c))
                  c))
              c))
          c))
      c)))


;; =============================================================================
;; In-memory index
;; =============================================================================

(defn- sorted-index-by
  [cmp]
  (bt/sorted-set-by cmp))


(defn subseq-from
  "All elements >= sentinel, in index order: a log-n slice descent that,
   on a lazily-restored set, loads only the nodes on the seek path plus
   the matching range, never the nodes left of the sentinel. One
   implementation on every platform (dao.data.btree)."
  [sorted-set cmp sentinel]
  (bt/slice sorted-set sentinel nil cmp))


(defn index-datoms
  "Build {:eavt ... :aevt ... :avet ... :vaet ...} sorted indexes from a
   seq of datoms."
  [datoms]
  {:eavt (into (sorted-index-by eavt-cmp) datoms),
   :aevt (into (sorted-index-by aevt-cmp) datoms),
   :avet (into (sorted-index-by avet-cmp) datoms),
   :vaet (into (sorted-index-by vaet-cmp) datoms)})


;; =============================================================================
;; Persisted indexes — content-addressed B-Tree node blobs
;; =============================================================================
;; publish-index! stores the four covered indexes as immutable,
;; content-addressed B-Tree node blobs (dao.data.btree stores children
;; before parents, so the blob emission order is Merkle by construction)
;; and appends them plus a manifest to one intake stream. A DaoJing observer
;; materializes the blobs into a content store; consumers either walk the
;; node graph eagerly with plain `jing/get` (walk-index-datoms /
;; read-datoms) or re-attach the trees lazily through dao.data.btree.storage
;; (restored-indexes). Both paths run on every platform.

(def ^:private content-missing
  "Not-found sentinel for content-store reads. An opaque per-host identity
   object, never a keyword: a keyword would be ambiguous with a genuinely
   stored value."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(defn walk-index-datoms
  "Eagerly collect every datom reachable from a persisted index node, in
   index order, by walking the node graph with plain `jing/get`. Node blobs
   are ordinary EDN maps (leaf `{:keys [...]}`, branch `{:level n :keys
   [...] :addresses [...]}`), so this works on every platform — it needs no
   tree-library support at all, only `jing/get` on plain EDN maps. A nil
   address (an empty index) walks to ()."
  [store address]
  (if (nil? address)
    ()
    (let [node (jing/get store address nil)]
      (when (nil? node)
        (throw (ex-info "missing index segment" {:address address})))
      (if-some [addresses (:addresses node)]
        (mapcat #(walk-index-datoms store %) addresses)
        (:keys node)))))


(defn- valid-manifest?
  "A conforming manifest is exactly {:indexes {:eavt ... :aevt ... :avet
   ... :vaet ...} :count n :branching-factor n}, each index address either
   nil when count is zero or a :segment/sha256-... content address when
   count is positive. The B-tree branching factor is at least two."
  [manifest]
  (and (map? manifest)
       (= #{:indexes :count :branching-factor} (set (keys manifest)))
       (map? (:indexes manifest))
       (= #{:eavt :aevt :avet :vaet} (set (keys (:indexes manifest))))
       (integer? (:count manifest))
       (not (neg? (:count manifest)))
       (integer? (:branching-factor manifest))
       (<= 2 (:branching-factor manifest))
       (if (zero? (:count manifest))
         (every? nil? (vals (:indexes manifest)))
         (every? jing/segment-address? (vals (:indexes manifest))))))


(defn read-manifest
  "Retrieve the manifest stored at a content address. Throws on a missing
   address and on a stored value that is not a conforming manifest."
  [content-store manifest-address]
  (let [manifest (jing/get content-store manifest-address content-missing)]
    (when (identical? manifest content-missing)
      (throw (ex-info "missing index manifest" {:address manifest-address})))
    (when-not (valid-manifest? manifest)
      (throw (ex-info "invalid index manifest"
                      {:address manifest-address, :manifest manifest})))
    (let [actual-address (jing/segment-key manifest)]
      (when-not (= manifest-address actual-address)
        (throw (ex-info "index manifest content address mismatch"
                        {:expected manifest-address,
                         :actual actual-address,
                         :manifest manifest}))))
    manifest))


(defn read-datoms
  "Eagerly read every datom in the EAVT index of a published manifest, in
   index order, by walking the node graph with plain `jing/get`. Takes the
   manifest's content address; an empty index reads as ()."
  [content-store manifest-address]
  (let [manifest (read-manifest content-store manifest-address)]
    (vec (walk-index-datoms content-store (:eavt (:indexes manifest))))))


(defn restored-indexes
  "Lazily-loaded {:eavt :aevt :avet :vaet} dao.data.btree sets over a
   published manifest (`{:indexes {...} :count n :branching-factor n}`).
   Nothing is fetched until a query traverses; slice (subseq-from) then
   loads only the seek path plus the matching range. Works on every
   platform.

   The manifest's :count and :branching-factor are threaded through
   restore-tree deliberately: count keeps O(1) `count` on restored trees
   without faulting the graph, and the branching factor reaches every
   restored node so mutation splits at the published thresholds. A manifest
   without :count or :branching-factor is foreign or hand-built and belongs
   to the eager path (walk-index-datoms), not here."
  [content-store manifest]
  (let [{:keys [indexes count branching-factor]} manifest
        storage (bts/kv-storage content-store
                                {:branching-factor (or branching-factor 512)})]
    {:eavt (bt/restore-tree eavt-cmp (:eavt indexes) storage count),
     :aevt (bt/restore-tree aevt-cmp (:aevt indexes) storage count),
     :avet (bt/restore-tree avet-cmp (:avet indexes) storage count),
     :vaet (bt/restore-tree vaet-cmp (:vaet indexes) storage count)}))


;; =============================================================================
;; publish-index! — build, record, append
;; =============================================================================

(defn- local-datom?
  [x]
  (and (vector? x) (= 5 (count x))))


(defn- stream-payload-datoms
  [payload]
  (if (and (map? payload) (contains? payload :dao.space/transaction))
    (let [tx (:dao.space/transaction payload)
          t (:t tx)
          datoms (:datoms tx)]
      (when-not (and (map? tx)
                     (= #{:t :datoms} (set (keys tx)))
                     (integer? t)
                     (not (neg? t))
                     (vector? datoms)
                     (seq datoms)
                     (every? local-datom? datoms)
                     (every? #(= t (datom-t %)) datoms))
        (throw (ex-info "malformed dao.space transaction record"
                        {:payload payload})))
      datoms)
    (if (local-datom? payload)
      [payload]
      (throw
        (ex-info
          "local stream payload must be a datom or dao.space transaction record"
          {:payload payload})))))


(defn snapshot-datoms
  "Eagerly snapshot an agent-local stream by walking `{:position 0}` with
   ds/next. A stream element is either one canonical datom vector or one
   atomic `{:dao.space/transaction {:t n :datoms [...]}}` record; transaction
   records are flattened into their datoms. :blocked and :end finish the
   snapshot at the current tail; :daostream/gap and malformed stream results,
   datoms, or transaction records throw. publish-index! calls this to
   completion before appending anything to an intake stream."
  [local-stream]
  (loop [cursor {:position 0}
         datoms []]
    (let [result (ds/next local-stream cursor)]
      (cond
        (map? result)
        (if (and (contains? result :ok) (contains? result :cursor))
          (recur (:cursor result)
                 (into datoms (stream-payload-datoms (:ok result))))
          (throw
            (ex-info
              "malformed stream result: a successful read must carry both :ok and :cursor"
              {:result result})))
        (= result :blocked) datoms
        (= result :end) datoms
        (= result :daostream/gap)
        (throw (ex-info "stream snapshot gap: position evicted before read"
                        {:cursor cursor}))
        :else (throw (ex-info "malformed stream signal" {:signal result}))))))


(defn- recording-content-handle
  "Temporary in-memory content store for the publish build. :put-content-fn
   records each unique node blob on first insertion (answering :present for
   duplicates, so the recorded order is first-insertion order, deduplicated);
   :get-content-fn reads recorded blobs back. Addresses are minted by
   jing/materialize! through dao.data.btree.storage/kv-storage, so the
   recorded order is exactly the store-tree children-before-parent traversal.
   The handle is a build-time value: no global state is introduced."
  []
  (let [state (atom {:content {}, :order []})]
    {:state state,
     :put-content-fn (fn [address payload]
                       (if (contains? (:content @state) address)
                         :present
                         (do (swap! state
                                    (fn [s]
                                      (-> s
                                          (assoc-in [:content address] payload)
                                          (update :order conj [address payload]))))
                             :inserted))),
     :get-content-fn (fn [address not-found]
                       (get-in @state [:content address] not-found))}))


(defn- validate-branching!
  [branching]
  (when-not (and (integer? branching) (<= 2 branching))
    (throw (ex-info
             "publish-index! :branching-factor must be an integer of at least 2"
             {:branching-factor branching})))
  branching)


(defn- select-intake-stream!
  [intake-pool select-fn]
  (when-not (coll? intake-pool)
    (throw
      (ex-info
        "publish-index! intake-pool must be a collection of writable dao.stream values"
        {:intake-pool intake-pool})))
  (when (empty? intake-pool)
    (throw (ex-info "publish-index! intake-pool must be non-empty"
                    {:intake-pool intake-pool})))
  (when-not (fn? select-fn)
    (throw (ex-info
             "publish-index! :select-stream must be a function of the pool"
             {:select-stream select-fn})))
  (let [selected (select-fn intake-pool)]
    (when-not (boolean (some #(identical? % selected) intake-pool))
      (throw
        (ex-info
          "publish-index! :select-stream returned a value outside the intake pool"
          {:selected selected, :intake-pool intake-pool})))
    selected))


(defn- append-ok!
  "Append one opaque payload to an intake stream; every ds/append! must
   answer `{:result :ok}`, anything else throws with the result attached."
  [stream payload]
  (let [result (ds/append! stream payload)]
    (when-not (and (map? result) (= :ok (:result result)))
      (throw (ex-info (str "publish-index! stream append failed: "
                           (pr-str result))
                      {:stream stream, :result result, :payload payload})))
    result))


(defn publish-index!
  "The transactor entry point, agent-side (docs/design/dao.jing.md,
   Publication from an agent).

   1. Snapshots the agent's local stream (snapshot-datoms) — fully, before
      any publication append. Atomic transaction records are flattened here.
   2. Builds the four covered indexes into a temporary recording content
      handle through dao.data.btree.storage/kv-storage, so addresses are
      minted by jing/materialize! and equal blobs deduplicate in
      children-before-parent first-insertion order.
   3. Appends every unique node blob in that recorded order, then the
      manifest, all to exactly the intake stream selected from the pool.
   4. Returns {:manifest-address (jing/segment-key manifest) :manifest
      manifest}.

   The manifest address is derived from the manifest alone and never depends
   on which intake stream carried it. A partial immutable prefix on a full
   intake stream is acceptable and retry-safe: the manifest is always
   appended last, so a :full failure can only have left node blobs.

   Success acknowledges that every payload was appended to the selected
   intake stream. It does not acknowledge that an asynchronous DaoJing
   observer has materialized those payloads yet. Because the build starts at
   cursor position zero and reconstructs complete indexes, local-stream must
   retain its complete datom history; a retention gap throws before emission.

   Usage:
     (publish-index! local-stream intake-pool)
     (publish-index! local-stream intake-pool
                     {:branching-factor n, :select-stream f})

   opts: {:branching-factor n — max keys per node, at least 2 (default 512)
          :select-stream f   — receives the pool and returns the intake
                               stream to append to (default first)}"
  ([local-stream intake-pool] (publish-index! local-stream intake-pool nil))
  ([local-stream intake-pool opts]
   (let [branching (validate-branching! (:branching-factor opts 512))
         intake (select-intake-stream! intake-pool (:select-stream opts first))
         datoms (snapshot-datoms local-stream)
         handle (recording-content-handle)
         storage (bts/kv-storage handle {:branching-factor branching})
         root-addr
         (fn [cmp]
           ;; an empty index has no root node; nil is the explicit
           ;; "nothing here" (walk of nil => ())
           (when (seq datoms)
             (-> (bt/from-sequential cmp datoms {:branching-factor branching})
                 (bt/store-tree storage))))
         manifest {:indexes {:eavt (root-addr eavt-cmp),
                             :aevt (root-addr aevt-cmp),
                             :avet (root-addr avet-cmp),
                             :vaet (root-addr vaet-cmp)},
                   :count (count datoms),
                   :branching-factor branching}]
     (doseq [[_ payload] (:order @(:state handle))] (append-ok! intake payload))
     (append-ok! intake manifest)
     {:manifest-address (jing/segment-key manifest), :manifest manifest})))

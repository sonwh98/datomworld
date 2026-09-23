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
   EDN either way.

   The same namespace is also the *stateful* dao.stream observer of
   docs/design/dao.space.index.as-observer.md: `session` builds the consumer
   half a `dao.stream.observer/run-on-stream` session drives with
   `fold-batch`/`flush-staged`, `publish!`/`checkpoint`/`restore` publish and
   recover it, and `db-value` hands its live trees to `dao.space.query`. The
   index is a symmetric peer observer there — it never evaluates, and it
   creates, appends to, and closes no medium."
  (:require [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]))


;; =============================================================================
;; Value comparison (heterogeneous datom values)
;; =============================================================================

(defn- type-rank
  "The heterogeneous bucket of a datom value. Every Jing numeric kind is
   one bucket (dao.jing.cbor/numeric?, not host number?): the JVM Rational
   carrier and the JavaScript and Dart float64, decimal and rational
   carriers are not host numbers, and must sort among numbers by value."
  [x]
  (cond (nil? x) 0
        (boolean? x) 1
        (cbor/numeric? x) 2
        (string? x) 3
        (keyword? x) 4
        (symbol? x) 5
        :else 6))


(defn- compare-numbers
  "Portable numeric order (dao.jing.cbor/num-compare: exact, never rounded
   through double; -infinity < finite < +infinity < NaN), with host compare
   kept only where it answers identically: two host integers, and on the
   JVM and ClojureScript two non-NaN doubles (both compare exactly, with
   0.0 and -0.0 equal). Dart's double compareTo orders -0.0 below 0.0, so
   Dart takes the fast path for ints only."
  [a b]
  (if #?(:cljd (and (dart/is? a int) (dart/is? b int))
         :clj (or (and (instance? Long a) (instance? Long b))
                  (and (instance? Double a) (instance? Double b)
                       (not (Double/isNaN ^Double a)) (not (Double/isNaN ^Double b))))
         :cljs (and (number? a) (number? b)
                    (not (js/isNaN a)) (not (js/isNaN b))))
    (compare a b)
    (cbor/num-compare a b)))


(defn compare-vals
  "Compare two datom values across heterogeneous types using type-rank.
   Numbers compare by exact value across every kind and representation,
   with no string fallback (docs/design/dao.jing.cbor.md, Numeric
   identity); ties across kinds stay ties, so (compare-vals 1 1.0) is 0."
  [a b]
  (let [ra (type-rank a)
        rb (type-rank b)]
    (cond
      (not= ra rb) (compare ra rb)
      (= 2 ra) (compare-numbers a b)
      :else
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
             (compare (str a) (str b)))))))


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


(defn- cmp-field
  "Nil-first, heterogeneous-safe field comparison. Entity ids are not
   guaranteed to be integers in query-only db-values, where a raw entity
   map's :db/id is caller-chosen, so every slot, not just v, needs
   compare-vals. Persisted local datoms are validated separately."
  [a b]
  (cond (nil? a) (if (nil? b) 0 -1)
        (nil? b) 1
        :else (compare-vals a b)))


;; Covered indexes order canonical local d5 datoms only. Stream scope belongs
;; to the interpreter selecting a source, not to an appended tuple slot.

(defn eavt-cmp
  [d1 d2]
  (let [c (cmp-field (datom-e d1) (datom-e d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-a d1) (datom-a d2))]
        (if (zero? c)
          (let [c (cmp-field (datom-v d1) (datom-v d2))]
            (if (zero? c)
              (let [c (cmp-field (datom-t d1) (datom-t d2))]
                (if (zero? c) (cmp-field (datom-m d1) (datom-m d2)) c))
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
                (if (zero? c) (cmp-field (datom-m d1) (datom-m d2)) c))
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
                (if (zero? c) (cmp-field (datom-m d1) (datom-m d2)) c))
              c))
          c))
      c)))


(defn vaet-cmp
  "VAET sort: v, a, e, t, m. Reverse-reference lookup — 'which datoms
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
                (if (zero? c) (cmp-field (datom-m d1) (datom-m d2)) c))
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
    (when-not (jing/segment-matches? manifest-address manifest)
      (throw (ex-info "index manifest content address mismatch"
                      {:expected manifest-address,
                       :actual (jing/segment-key manifest),
                       :manifest manifest})))
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
;; The published read coordinate
;; =============================================================================

(defn published-index
  "Construct a transportable descriptor for one immutable covered-index
   manifest. `content-store` is a serializable DaoJing coordinate, not a live
   handle. The manifest address is the exact bound; opening the coordinate is
   the reader's move — dao.space.query's open-published! re-attaches the
   covered sets lazily and defers the EAVT rows, read-datoms below walks them
   eagerly."
  [content-store manifest-address]
  (when-not (and (map? content-store) (keyword? (:dao.jing/type content-store)))
    (throw (ex-info "published index requires a DaoJing store coordinate"
                    {:content-store content-store})))
  (when-not (jing/segment-address? manifest-address)
    (throw (ex-info "published index requires a manifest content address"
                    {:manifest-address manifest-address})))
  {:dao.stream/type :dao.space.index/published,
   :dao.stream/bound {:manifest-address manifest-address},
   :dao.stream/comparator :dao.space.index/eavt,
   :content-store content-store,
   :manifest-address manifest-address})


(defn covered-indexes
  "Return the {:eavt :aevt :avet :vaet} covered d5 sets carried in a
   realization's :indexes field, or nil when the realization carries none.
   Structural check only: the realization must be a map whose :indexes field
   is a map with exactly those four keys, so it is clj/cljs/cljd-portable and
   never an instance/type check. It means the realization carries covered d5
   sets, not that it is lazy: an in-memory index-datoms map is structurally
   indistinguishable, and that is fine (resolution happens the same way
   either way)."
  [realization]
  (when (map? realization)
    (let [indexes (:indexes realization)]
      (when (and (map? indexes)
                 (= #{:eavt :aevt :avet :vaet} (set (keys indexes))))
        indexes))))


;; =============================================================================
;; publish-index! — build, record, append
;; =============================================================================

(defn- element-datoms
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
                     (every? datom/local-datom? datoms)
                     (every? #(= t (datom-t %)) datoms))
        (throw (ex-info "malformed dao.space transaction record"
                        {:payload payload})))
      datoms)
    (cond
      (datom/local-datom? payload) [payload]
      (and (vector? payload) (= 5 (count payload)))
      (throw
        (ex-info
          "malformed local datom: e and t must be non-negative integers, m an integer, and a a namespaced keyword"
          {:datom payload}))
      :else
      (throw
        (ex-info
          "local stream payload must be a datom or dao.space transaction record"
          {:payload payload})))))


(defn datoms-from-elements
  "Flatten a seq of local-stream elements to canonical local d5 datoms —
   the payload vocabulary's public, seq-level spelling. Each element is
   validated and flattened by the same per-element rule snapshot-datoms
   applies inside its read loop: a canonical datom vector passes through, an
   atomic {:dao.space/transaction {:t n :datoms [...]}} record flattens to
   its datoms, and anything malformed throws."
  [elements]
  (into [] (mapcat element-datoms) elements))


(defn- checked
  "Fold a defective result into an exception before anything is read from it.
   The contract's own validator (dao.stream/validate-outcome) answers nil
   for a conforming result and a defect map otherwise; interpreting an
   unvalidated result is how a loop ends up recurring on a nil cursor."
  [operation result]
  (if-let [defect (stream/validate-outcome operation result)]
    (throw (ex-info "malformed local stream result"
                    {:operation operation, :result result, :defect defect}))
    result))


(defn snapshot-datoms
  "Read an agent-local stream in full and flatten it to canonical local d5
   datoms.

   The local stream is on a complete-retention transport
   (dao.stream.memory-log), so a fresh :oldest cursor is the origin and
   `gap` cannot occur — completeness comes from the transport's declared
   retention, never from the anchor (dao.stream.md, *Complete history*).
   `blocked` (an open stream caught up) and `end` (a closed one fully read)
   finish the read at the tail. Each element is validated and flattened as it
   is read, so the first defect in stream order is the one reported and no
   read happens past it. A well-formed but unexpected outcome means the handle
   is not the transport the composition owes."
  [local-stream]
  (let [mint (checked :cursor (stream/cursor local-stream :dao.stream/oldest))]
    (when-not (= :dao.stream/ok (:dao.stream/outcome mint))
      (throw (ex-info "local stream refused an :oldest cursor" {:result mint})))
    (loop [cursor (:dao.stream/cursor mint)
           datoms []]
      (let [r (checked :next (stream/next local-stream cursor))]
        (case (:dao.stream/outcome r)
          :dao.stream/ok
          (recur (:dao.stream/cursor r)
                 (into datoms (element-datoms (:dao.stream/value r))))
          (:dao.stream/blocked :dao.stream/end) datoms
          (throw (ex-info "local stream is not a complete-retention transport"
                          {:outcome (:dao.stream/outcome r), :result r})))))))


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
  "Append one opaque payload to an intake stream; every stream/append!
   must answer `:dao.stream/ok`, anything else throws with the result
   attached."
  [stream payload]
  (let [result (stream/append! stream payload)]
    (when-not (and (map? result) (= :dao.stream/ok (:dao.stream/outcome result)))
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
   the retained history's origin and reconstructs complete indexes,
   local-stream must be on a complete-retention transport
   (dao.stream.memory-log); the snapshot is complete before anything is
   emitted.

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
         build-tree
         (fn [cmp]
           (bt/from-sequential cmp datoms {:branching-factor branching}))
         trees {:eavt (build-tree eavt-cmp),
                :aevt (build-tree aevt-cmp),
                :avet (build-tree avet-cmp),
                :vaet (build-tree vaet-cmp)}
         root-addr (fn [tree]
                     ;; an empty index has no root node; nil is the
                     ;; explicit
                     ;; "nothing here" (walk of nil => ())
                     (when (seq datoms) (bt/store-tree tree storage)))
         manifest {:indexes {:eavt (root-addr (:eavt trees)),
                             :aevt (root-addr (:aevt trees)),
                             :avet (root-addr (:avet trees)),
                             :vaet (root-addr (:vaet trees))},
                   ;; Covered indexes are sets. Their count must describe
                   ;; the distinct tuples actually stored, not duplicate
                   ;; occurrences in the source stream.
                   :count (bt/count (:eavt trees)),
                   :branching-factor branching}]
     (doseq [[_ payload] (:order @(:state handle))] (append-ok! intake payload))
     (append-ok! intake manifest)
     {:manifest-address (jing/segment-key manifest), :manifest manifest})))


;; =============================================================================
;; The index session — dao.space.index as a dao.stream observer
;; (docs/design/dao.space.index.as-observer.md)
;; =============================================================================

;; The index state is the consumer half of one
;; dao.stream.observer/run-on-stream session:
;;
;;   {:indexes  {:eavt bt :aevt bt :avet bt :vaet bt}  ; dao.data.btree values,
;;                                                          structurally shared across batches
;;    :storage  <recording kv-storage>                ; store-tree target; its identity
;;                                                          persists across publishes (§4.1)
;;    :recorder <recording content handle>            ; blobs recorded since the last
;;                                                          flush; drained at each accepted
;;                                                          manifest
;;    :branching-factor n                             ; every node the session folds or
;;                                                          restores splits at
;;    :mode     :resolved | :unresolved               ; fixed at construction (§3.1)
;;    :ids      nil | {:next-eid n :ownership k}      ; allocator; :unresolved only
;;    :batch    n                                     ; ordinal of the next batch,
;;                                                          observer-local
;;    :schema   {attr {:db/valueType ...}}            ; supplied; which attrs are refs
;;    :max-t    nil | n                               ; greatest writer t folded; nil
;;                                                          until one is (§4.2)
;;    :rejected n                                     ; monotonic count of rejected
;;                                                          batches — never drained (§2.2)
;;    :publish  {:intake w                            ; where publications append
;;               :staged nil | {:payloads [...] :next i}   ; §4.1's state machine
;;               :manifest nil | m}                   ; the last publication's manifest
;;    :defects  []}                                   ; one event per rejected batch
;;                                                          since the last drain
;;
;; Driven as (observer/run-on-stream {:observer o :consumer st} ready?
;; index/fold-batch index/flush-staged) with ready? answering
;; (nil? (get-in x [:publish :staged])). The coordination inspects no index
;; field; publication is an explicit composition step between rounds, and only
;; the resumption of a staged publication lives inside run.

(def batch-attr
  "§3.2's batch-ordinal fact attribute. A medium must not assert attributes in
   `:dao.space.index/*` — a contract on media, not a check in the index, which
   inspects no attribute namespace; honoured, resolution facts and observed
   rows have disjoint [e a v] keys and can neither shadow nor conflict."
  :dao.space.index/batch)


(def tempid-attr
  "§3.2's tempid fact attribute: the batch-local tempid a durable id was
   minted for, the raw material for cross-medium provenance."
  :dao.space.index/tempid)


(def mode-matrix
  "§3.1's mode matrix over the three identity-bearing slots. Ids partition
   disjointly: tempid `id < 0`; reserved `0 <= id < datom/first-user-id`
   (default-op lives there); user-positive `id >= datom/first-user-id`.
   [mode slot id-class] -> :pass | :allocate | :defect. The undeclared `v`
   slot is never consulted — any value, untouched, in both modes. Reserved
   `e` on a `:resolved` medium is admitted, not enforced against
   (datom.md's reservation has never been runtime-enforced, and an index
   that started enforcing it would silently diverge from every existing
   read path)."
  {[:unresolved :e :tempid] :allocate,
   [:unresolved :e :reserved] :defect,
   [:unresolved :e :user-positive] :defect,
   [:unresolved :ref-v :tempid] :allocate,
   [:unresolved :ref-v :reserved] :pass,
   [:unresolved :ref-v :user-positive] :defect,
   [:unresolved :m :tempid] :allocate,
   [:unresolved :m :reserved] :pass,
   [:unresolved :m :user-positive] :defect,
   [:resolved :e :tempid] :defect,
   [:resolved :e :reserved] :pass,
   [:resolved :e :user-positive] :pass,
   [:resolved :ref-v :tempid] :defect,
   [:resolved :ref-v :reserved] :pass,
   [:resolved :ref-v :user-positive] :pass,
   [:resolved :m :tempid] :defect,
   [:resolved :m :reserved] :pass,
   [:resolved :m :user-positive] :pass})


(defn- id-class
  "§3.1's disjoint id partition. Answers nil for a non-integer: the matrix
   classifies *ids*, and a declared-ref v that is not an integer is in no
   class, which plan-batch reports as a mode defect rather than guessing."
  [id]
  (when (integer? id)
    (cond (neg? id) :tempid
          (< id datom/first-user-id) :reserved
          :else :user-positive)))


(defn- ref-attrs
  "The attributes the supplied schema declares :db.type/ref — the only v
   slots the mode matrix inspects. Refs are batch-local (§3.2): resolution
   happens only for declared attributes, exactly as the transactor relocates
   only declared refs."
  [schema]
  (into #{} (keep (fn [[a m]] (when (= :db.type/ref (:db/valueType m)) a)))
        schema))


(defn- validate-mode!
  [mode]
  (when-not (contains? #{:resolved :unresolved} mode)
    (throw (ex-info "index session :mode must be :resolved or :unresolved"
                    {:mode mode})))
  mode)


(defn- validate-schema!
  [schema]
  (when-not (and (map? schema) (every? map? (vals schema)))
    (throw (ex-info
             "index session :schema must be a map of attribute to attribute-map"
             {:schema schema})))
  schema)


(defn- validate-ids!
  "The allocator is :unresolved sessions' own: ids are minted from
   datom/first-user-id upward, so a reserved id is never allocated and a
   supplied :next-eid below the boundary is a composition defect.
   :ownership records whether the allocator is :owned by this session or
   :shared with siblings (§3.1) — a value threaded serially by the
   composition, never an atom, and the distinction governs what a checkpoint
   may restore (§4.2)."
  [ids]
  (let [eid (or (:next-eid ids) datom/first-user-id)]
    (when-not (and (integer? eid) (<= datom/first-user-id eid))
      (throw (ex-info
               "index session :ids requires an integer :next-eid of at least datom/first-user-id"
               {:ids ids})))
    (let [ownership (:ownership ids :owned)]
      (when-not (contains? #{:owned :shared} ownership)
        (throw (ex-info "index session :ids :ownership must be :owned or :shared"
                        {:ids ids})))
      {:next-eid eid, :ownership ownership})))


(defn session
  "Construct the initial index state — the consumer half of one
   `dao.stream.observer` session (`{:observer o :consumer st}`; the
   composition attaches the observer half itself). opts:

     {:mode :resolved | :unresolved   fixed for the session's life (§3.1):
                                      :resolved indexes media whose ids are
                                      already durable and allocates nothing;
                                      :unresolved indexes media that carry
                                      per-batch tempids and owns every
                                      positive user id it allocates
      :schema {attr {:db/valueType ...}}  which attributes are refs
      :intake w                        the dao.stream writer publications
                                      append to; nil builds a session that
                                      folds but cannot publish (publish!
                                      throws until an intake is supplied)
      :branching-factor n              at least 2 (default 512)
      :ids {:next-eid n,               :unresolved only; defaults
           :ownership :owned|:shared}  datom/first-user-id / :owned
      :ref-type k                      the recording storage's ref-type;
                                      default :strong (§4.1)}

   The trees carry Settings with ref-type :strong over the session's
   recording storage, so nothing a session holds can evict and refault —
   §4.1's invariant that a tree's refaults resolve against durable content,
   never the recording handle."
  [{:keys [mode schema intake branching-factor ids ref-type]}]
  (validate-mode! mode)
  (validate-schema! schema)
  (when (and (some? intake) (not (stream/writer? intake)))
    (throw (ex-info "index session :intake must be a dao.stream writer"
                    {:intake intake})))
  (validate-branching! (or branching-factor 512))
  (when (and (= :resolved mode) (some? ids))
    (throw (ex-info
             "a :resolved index session allocates nothing; :ids belongs to :unresolved sessions"
             {:mode mode, :ids ids})))
  (let [branching (or branching-factor 512)
        recorder (recording-content-handle)
        storage (bts/kv-storage recorder
                                {:branching-factor branching,
                                 ;; §4.1: every tree an index session holds is
                                 ;; pinned :strong so a root is never evicted
                                 ;; and never refaults through the drained
                                 ;; recording handle. :test is the seam that
                                 ;; pins the hazard deterministically.
                                 :ref-type (or ref-type :strong)})
        empty-tree (fn [cmp] (bt/restore-tree cmp nil storage 0))]
    {:indexes {:eavt (empty-tree eavt-cmp),
               :aevt (empty-tree aevt-cmp),
               :avet (empty-tree avet-cmp),
               :vaet (empty-tree vaet-cmp)},
     :storage storage,
     :recorder recorder,
     :branching-factor branching,
     :mode mode,
     :ids (when (= :unresolved mode)
            (validate-ids! (or ids {}))),
     :batch 0,
     :schema schema,
     :max-t nil,
     :rejected 0,
     :publish {:intake intake, :staged nil, :manifest nil},
     :defects []}))


;; -----------------------------------------------------------------------------
;; fold-batch: the outer grammar, the admitting element rule, the mode matrix
;; -----------------------------------------------------------------------------

(defn- tx-record?
  "§2.2 step 0's first element rule, shape only: any map carrying
   :dao.space/transaction. The record's own checks (one shared t, non-empty,
   exactly {:t :datoms}) are admission's, below."
  [x]
  (and (map? x) (contains? x :dao.space/transaction)))


(defn- d5-shape?
  "§2.2 step 0's second element rule: a vector of exactly five whose first
   slot is an integer and second a keyword is one d5 element — a bare row is
   never mistaken for a five-row batch, and a five-row batch's first slot is
   a vector, not an integer. Looser than local-datom? on purpose: the a
   slot's namespace and the sign classes are admission's business."
  [x]
  (and (vector? x)
       (= 5 (count x))
       (integer? (nth x 0))
       (keyword? (nth x 1))))


(defn- normalize-outer
  "§2.2 step 0: one stream value to a vector of elements, or a whole-batch
   defect. A batch value may be a bare record or a bare d5 row (a medium
   appending one element per batch); any other sequential is a batch of
   elements each matched by the same two rules and nothing else — no
   nesting; anything else (a scalar, a map that is not a transaction record,
   a set) is a defect at position nil, never a batch of map entries."
  [x]
  (cond
    (tx-record? x) [:ok [x]]
    (d5-shape? x) [:ok [x]]
    (sequential? x)
    (if-some [bad (some (fn [[i el]]
                          (when-not (or (tx-record? el) (d5-shape? el))
                            {:position i, :element el}))
                        (map-indexed vector x))]
      [:defect (assoc bad :reason :element)]
      [:ok (vec x)])
    :else [:defect {:position nil, :reason :outer, :element x}]))


(defn- admitting-datom?
  "§2.2 step 1's per-datom rule. In :unresolved mode it is local-datom?
   minus the non-negative-e clause — integer e of either sign, namespaced
   keyword a, t >= 0, integer m; v is unconstrained by local-datom? already,
   so a negative ref-valued v needs no schema knowledge to be admitted. In
   :resolved mode admission is exactly local-datom?. Strictness is restored
   by step 2: every row that reaches the fold satisfies local-datom?."
  [mode row]
  (and (vector? row)
       (= 5 (count row))
       (integer? (nth row 0))
       (or (= :unresolved mode) (not (neg? (nth row 0))))
       (keyword? (nth row 1))
       (some? (namespace (nth row 1)))
       (integer? (nth row 3))
       (not (neg? (nth row 3)))
       (integer? (nth row 4))))


(defn- admitting-element
  "Flatten one grammar-checked element to its datoms under the admitting
   rule: a bare row is admitted by the per-datom rule alone; a transaction
   record by the same rule applied to each of its datoms plus the record's
   own shape checks (exactly {:t :datoms}, one shared t, non-empty). Answers
   nil when anything fails — bad input is data here, a defect for the whole
   batch; the throwing contract belongs to datoms-from-elements, the
   one-shot path (§2.2 step 1)."
  [mode element]
  (if (map? element)
    (let [tx (:dao.space/transaction element)]
      (when (and (map? tx)
                 (= #{:t :datoms} (set (keys tx)))
                 (integer? (:t tx))
                 (not (neg? (:t tx)))
                 (vector? (:datoms tx))
                 (seq (:datoms tx))
                 (every? #(admitting-datom? mode %) (:datoms tx))
                 (every? #(= (:t tx) (datom-t %)) (:datoms tx)))
        (:datoms tx)))
    (when (admitting-datom? mode element)
      [element])))


(defn- resolve-id
  "Apply one §3.1 matrix cell. ctx is the batch-local plan state {:table
   τ→δ, :next n}; allocation is in first-occurrence order, so it is identical
   on every host. Answers [ctx' id'] or nil on a matrix violation — a nil
   class (a declared-ref v that is not an integer) is a defect the same way,
   loud rather than guessed at."
  [ctx mode slot id]
  (case (get mode-matrix [mode slot (id-class id)])
    :pass [ctx id]
    :allocate
    (if-some [[_ δ] (find (:table ctx) id)]
      [ctx δ]
      [(-> ctx
           (assoc-in [:table id] (:next ctx))
           (update :next inc))
       (:next ctx)])
    nil))


(defn- plan-row
  "Steps 1–2 for one observed row: classify e, then a declared-ref v, then m
   (§2.2's within-row order), allocating in first-occurrence order through
   the shared batch-local table — the same tempid in two slots, or in two
   rows, is one durable id. An undeclared v is any value, untouched. Answers
   [ctx' resolved-row] or nil on a defect."
  [ctx mode refs [e a v t m]]
  (let [[ctx-e e'] (resolve-id ctx mode :e e)]
    (when ctx-e
      (let [[ctx-v v'] (if (contains? refs a)
                         (resolve-id ctx-e mode :ref-v v)
                         [ctx-e v])]
        (when ctx-v
          (let [[ctx-m m'] (resolve-id ctx-v mode :m m)]
            (when ctx-m
              [ctx-m [e' a v' t m']])))))))


(defn- resolution-facts
  "§3.2: for every tempid τ the batch mapped to durable δ — whichever slot
   it appeared in — two facts in the observer's own attribute namespace. The
   rows' t is the observer's batch ordinal n, its own logical clock, never a
   host clock and not the observed rows' t; m is default-op, so the
   attribute namespace alone distinguishes these facts from observed rows."
  [table n]
  (into []
        (mapcat (fn [[τ δ]]
                  [[δ batch-attr n n datom/default-op]
                   [δ tempid-attr τ n datom/default-op]]))
        table))


(defn- plan-batch
  "§2.2 steps 0–2, planned against the whole normalized batch before any
   tree is touched or the allocator advanced: normalize the outer value,
   admit every element, and resolve identity per the mode matrix. Answers
   [:plan {:rows [...] :facts [...] :next-eid n}] or
   [:defect {:position p :reason r :element x}] — a batch rejected at any
   step has allocated nothing."
  [index-state batch-value]
  (let [mode (:mode index-state)
        refs (ref-attrs (:schema index-state))
        [status payload] (normalize-outer batch-value)]
    (if (= :defect status)
      [:defect payload]
      (loop [elements payload
             position 0
             ctx {:table {}, :next (get-in index-state [:ids :next-eid])}
             rows []]
        (if-some [element (first elements)]
          (if-some [datoms (admitting-element mode element)]
            (if-some [result
                      (reduce
                        (fn [acc row]
                          (if-some [[ctx' row'] (plan-row (:ctx acc)
                                                          mode
                                                          refs
                                                          row)]
                            (assoc acc :ctx ctx', :rows (conj (:rows acc) row'))
                            (reduced nil)))
                        {:ctx ctx, :rows rows}
                        datoms)]
              (recur (rest elements)
                     (inc position)
                     (:ctx result)
                     (:rows result))
              [:defect {:position position, :reason :mode, :element element}])
            [:defect {:position position, :reason :admission, :element element}])
          [:plan {:rows rows,
                  :facts (resolution-facts (:table ctx) (:batch index-state)),
                  :next-eid (:next ctx)}])))))


(defn- reject-batch
  "§2.2's atomic rejection: trees, :ids, and :max-t unchanged, the monotonic
   :rejected count +1, exactly one drainable defect event, :batch advanced by
   exactly one — never zero, or every later cross-session ordinal (§3.2)
   would drift on the first malformed input."
  [index-state defect]
  (-> index-state
      (update :rejected inc)
      (update :defects conj (assoc defect :batch (:batch index-state)))
      (update :batch inc)))


(defn fold-batch
  "One batch, one pure fold (§2.2): admit the batch through the outer
   grammar and the admitting element rule, resolve identity through the §3.1
   mode matrix, and conj the rows and the resolution facts into the four
   persistent trees — a fold that shares every unmodified node with the
   previous state. Covered indexes are sets: a duplicate row is a no-op.

   The fold is atomic per batch: steps 0–2 validate and plan against the
   whole batch before step 3 touches a tree, so a rejected batch leaves the
   trees, :ids, and :max-t unchanged, increments :rejected, appends one
   defect event, and advances :batch exactly once — bad input is data, and
   the session continues past it; a bug in the fold itself is the throw. An
   empty admitted batch is a valid no-op: :batch advances once and nothing
   else changes. :max-t becomes the greater of itself and the batch's
   greatest observed row t (writer time; resolution facts carry the
   observer's ordinal and never touch it), and is nil until the first row is
   folded."
  [index-state batch-value]
  (let [[status payload] (plan-batch index-state batch-value)]
    (if (= :defect status)
      (reject-batch index-state payload)
      (let [rows (:rows payload)
            facts (:facts payload)]
        (-> index-state
            (update :indexes
                    (fn [indexes]
                      (reduce (fn [idxs row]
                                {:eavt (bt/conj (:eavt idxs) row),
                                 :aevt (bt/conj (:aevt idxs) row),
                                 :avet (bt/conj (:avet idxs) row),
                                 :vaet (bt/conj (:vaet idxs) row)})
                              indexes
                              (concat rows facts))))
            (update :max-t
                    (fn [current]
                      (if (seq rows)
                        (let [greatest (reduce max (map datom-t rows))]
                          (if current (max current greatest) greatest))
                        current)))
            (update :batch inc)
            ;; :ids is present only in :unresolved sessions (§2.1); a
            ;; :resolved session allocates nothing and stays :ids-nil
            (update :ids
                    (fn [ids]
                      (if ids (assoc ids :next-eid (:next-eid payload)) ids))))))))


;; -----------------------------------------------------------------------------
;; Publication: publish! stages, flush-staged resumes (§4.1)
;; -----------------------------------------------------------------------------

(defn- publication-terminal
  "§4.1's throw for an outcome this forwarder cannot continue from — closed,
   invalid-value, transport-error, a malformed answer. The staged
   publication is preserved with :next at the count of accepted payloads
   (manifest-last still holds, so a partial prefix is never a publication),
   and the consumer it reached rides under :consumer so a driving
   run-on-stream carries the session that resumes rather than repeats."
  [index-state accepted payload result]
  (let [state' (assoc-in index-state [:publish :staged :next] accepted)]
    (throw
      (ex-info "index publication cannot continue from this intake outcome"
               {:outcome (when (map? result) (:dao.stream/outcome result)),
                :result result,
                :payload payload,
                :next accepted,
                :consumer state'}))))


(defn- attempt-publication
  "Append staged payloads from :next until the intake accepts the manifest,
   answers full, or answers an outcome this forwarder cannot continue from.
   Returns {:status :ok, :state s'} — staged cleared, recording handle
   drained, ready for the next fold cycle — or {:status :full, :state s',
   :next i} with :next left at the failing payload so an ordinary retry
   never re-appends a payload the intake already accepted (at-most-once
   acceptance per staged occurrence under ordinary retries)."
  [index-state]
  (let [{:keys [intake staged]} (:publish index-state)
        payloads (:payloads staged)]
    (loop [i (:next staged)]
      (if (= i (count payloads))
        ;; :next reached the end: the manifest has been accepted, which is
        ;; the only condition under which the recording handle is drained
        {:status :ok,
         :state (-> index-state
                    (assoc-in [:publish :staged] nil)
                    (update :recorder (fn [handle]
                                        (reset! (:state handle) {:content {}, :order []})
                                        handle)))}
        (let [payload (nth payloads i)
              result (stream/append! intake payload)]
          (cond
            (not (stream/valid-outcome? :append! result))
            (publication-terminal index-state i payload result)

            (= :dao.stream/ok (:dao.stream/outcome result))
            (recur (inc i))

            (= :dao.stream/full (:dao.stream/outcome result))
            {:status :full,
             :state (assoc-in index-state [:publish :staged :next] i),
             :next i}

            :else
            (publication-terminal index-state i payload result)))))))


(defn publish!
  "Stage this state's trees as one publication and attempt it (§2.1, §4.1) —
   the explicit composition step between run-on-stream rounds, after every n
   rounds, on blocked, or on a timer: the composition's choice. Each tree
   stores through the session's persistent recording storage, so only the
   subgraph dirty since the last publication is recorded, and the staged
   payloads are those recorded blobs in store order with the manifest last.

   Returns {:status :ok, :state s'} on success, or {:status :full, :state
   s', :next i} when the intake refused payload i — the staged publication
   is still held (the consumer is not ready; flush-staged resumes at i), and
   a payload already accepted is never appended again by an ordinary retry.
   A terminal intake outcome after accepted blobs throws carrying :next and
   the successor :consumer, so the caller can resume publication from a
   restored session. Calling publish! while a publication is still staged is
   a caller error — a staged publication is never replaced, only resumed."
  [index-state]
  (when-some [staged (get-in index-state [:publish :staged])]
    (throw (ex-info
             "publish! while a publication is still staged is a caller error; flush-staged resumes it"
             {:staged staged})))
  (when-not (stream/writer? (get-in index-state [:publish :intake]))
    (throw (ex-info "publish! requires an intake writer on the session"
                    {:publish (:publish index-state)})))
  (let [trees (:indexes index-state)
        storage (:storage index-state)
        root-addr (fn [tree]
                    ;; an empty index has no root node; nil is the explicit
                    ;; "nothing here" (walk of nil => ())
                    (when (pos? (bt/count tree)) (bt/store-tree tree storage)))
        manifest {:indexes {:eavt (root-addr (:eavt trees)),
                            :aevt (root-addr (:aevt trees)),
                            :avet (root-addr (:avet trees)),
                            :vaet (root-addr (:vaet trees))},
                  ;; Covered indexes are sets: the count describes the
                  ;; distinct tuples actually stored
                  :count (bt/count (:eavt trees)),
                  :branching-factor (:branching-factor index-state)}
        payloads (conj (mapv second (:order @(:state (:recorder index-state))))
                       manifest)]
    (attempt-publication
      (-> index-state
          (assoc-in [:publish :staged] {:payloads payloads, :next 0})
          (assoc-in [:publish :manifest] manifest)))))


(defn flush-staged
  "The session's `run` (§2.1): resume a staged publication at :next, or do
   nothing when nothing is staged (a ready consumer's run is identity —
   publication happens only through publish!, and only its resumption lives
   inside the coordination loop). On completion the staged publication is
   cleared and the recording handle drained, ready for the next fold cycle;
   :full leaves :next at the failing payload and the consumer not-ready; a
   terminal outcome throws with the successor consumer under :consumer and
   :next preserved."
  [index-state]
  (if (nil? (get-in index-state [:publish :staged]))
    index-state
    (:state (attempt-publication index-state))))


(defn drain
  "Clear the drainable diagnostics (§2.2): returns [index-state' defects],
   the events appended since the last drain. :rejected — the durable record
   that the index is partial — is never drained, so completeness survives a
   drain, a checkpoint, and a restore; a composition that needs the
   *identities* of omitted batches retains the drained events itself."
  [index-state]
  [(assoc index-state :defects []) (:defects index-state)])


(defn db-value
  "The consumer's live trees as a query value (§5): the same shape
   open-published! builds — four covered trees plus the EAVT rows deferred
   behind a delay — constructed over the session's trees instead of a
   restored manifest, and recognized by dao.space.query through the same
   covered-index realization path. The trees are persistent, so a db-value
   taken before a fold is unchanged by it. After a successful
   run-on-stream round the value represents every batch the session has
   admitted and folded up to the observer's cursor — nothing more; a test
   that wants to see a batch must drive the observer first, then query."
  [index-state]
  {:dao.space.query/published {:dao.space.index/live true},
   :indexes (:indexes index-state),
   :rows (delay (vec (bt/seq (:eavt (:indexes index-state)))))})


(defn watermark
  "§4.2's writer watermark over this consumer: 0 when nothing is folded
   (:max-t nil — the transactor's empty history), else one plus the greatest
   writer t. Derived from folded rows only, never from observer ordinals;
   over a resumed session it covers the checkpoint plus whatever suffix has
   since been folded, which is the O(suffix) relief the transactor's
   create! asked for."
  [index-state]
  (if-some [t (:max-t index-state)] (inc t) 0))


;; -----------------------------------------------------------------------------
;; Coverage and the checkpoint (§4.2)
;; -----------------------------------------------------------------------------

(defn- require-session
  "checkpoint and coverage read both halves of a {:observer o :consumer c}
   session — the cursor and gap count live in the observer half, where
   run-on-stream advances them, including on a gap. The dependency runs
   dao.space.index over the shape dao.stream.observer publishes, never
   the reverse; the generic loop stays ignorant of every index field."
  [session]
  (let [{:keys [observer consumer]} session]
    (when-not (and (map? observer)
                   (contains? observer :cursor)
                   (contains? observer :ingress-gaps)
                   (map? consumer))
      (throw (ex-info
               "coverage and checkpoint take a {:observer o :consumer c} session"
               {:session (cond-> session (map? session) (dissoc :consumer))})))
    session))


(defn coverage
  "How far the session's db-value reaches and how complete it is (§5): the
   cursor, the gap count, the monotonic rejected count, the batch ordinal,
   and :max-t. The value promises nothing about appends the observer has not
   yet read; a rejected batch is absent from the value and counted here, its
   identity in the drainable defect events."
  [session]
  (let [{:keys [observer consumer]} (require-session session)]
    {:cursor (:cursor observer),
     :ingress-gaps (:ingress-gaps observer),
     :rejected (:rejected consumer),
     :batch (:batch consumer),
     :max-t (:max-t consumer)}))


(defn checkpoint
  "Capture a checkpoint candidate at publish!'s boundary (§4.2): call it on
   the session before any further round folds — nothing may be folded
   between publish! and checkpoint, or a restart would skip every batch in
   between permanently. The candidate is plain data (it survives
   pr-str/read-string; a live session does not), carrying the manifest
   address publish! staged, the observer's cursor and gap count, and the
   consumer's allocator, ordinal, greatest writer t, monotonic rejected
   count, mode, and schema hash.

   A candidate is *not* a checkpoint: it is promoted only by the
   composition, and only after verify-candidate confirms the durable store
   reads it back — intake success means enqueued, not materialized. Until
   promotion the previous checkpoint stands."
  [session]
  (let [{:keys [observer consumer]} (require-session session)
        manifest (get-in consumer [:publish :manifest])]
    (when-not manifest
      (throw (ex-info "checkpoint requires a publication: call publish! first"
                      {:coverage (coverage session)})))
    {:manifest-address (jing/segment-key manifest),
     :cursor (:cursor observer),
     :ids (:ids consumer),
     :batch (:batch consumer),
     :max-t (:max-t consumer),
     :ingress-gaps (:ingress-gaps observer),
     :rejected (:rejected consumer),
     :mode (:mode consumer),
     :schema-address (jing/segment-key (:schema consumer))}))


(defn verify-candidate
  "§4.2's promotion gate: verify, through the durable store's read path
   (jing/get — never a recording handle or any cache whose presence proves
   nothing about durability), that the candidate's manifest reads back valid
   and that every address reachable from all four roots resolves, leaves
   included — checked inside the bt/walk-addresses visitor, because the
   walker does not itself restore leaves. Answers the candidate when fully
   verified; nil is 'verification incomplete, never success — a missing
   manifest, a missing leaf, or a read failure all answer nil, the check may
   be retried later, and because content is immutable and retained, a check
   that once passed stays passed."
  [candidate content-store]
  (try
    (let [manifest (read-manifest content-store (:manifest-address candidate))
          storage (bts/kv-storage content-store
                                  {:branching-factor (:branching-factor
                                                       manifest)})
          missing (volatile! false)
          resident? (fn [address]
                      (if (identical? (jing/get content-store address
                                                content-missing)
                                      content-missing)
                        (do (vreset! missing true) false)
                        true))]
      (doseq [root (vals (:indexes manifest))]
        (bt/walk-addresses storage root resident?))
      (when-not @missing candidate))
    (catch #?(:cljd Object
              :clj Throwable
              :cljs :default)
           _
      nil)))


(defn restore
  "Rebuild the consumer half of a session from a *verified* checkpoint
   candidate (§4.2; verify-candidate is the gate — restore re-reads the
   manifest from the durable store and throws on absence). Refuses — throws
   — when the candidate's :mode or :schema-hash differ from the session
   being constructed, or when its :ids was :shared: a session-local snapshot
   of a shared allocator is stale the moment any sibling allocates after
   it, and restoring it would re-mint ids siblings already hold. For
   independently allocated sessions the four trees are restored lazily
   through a session-constructed :strong kv-storage over the durable store —
   never the query read path (restored-indexes), whose storage takes the
   host default ref-type (:soft on the JVM) and would leave the roots
   evictable into refault through a drained recording handle (§4.1's F2
   hazard). :defects start fresh (drained diagnostics are not state); the
   durable :rejected is reinstated, and the composition re-attaches the
   observer at :cursor with :ingress-gaps seeded from the candidate
   (dao.stream.observer/attach's kept-cursor arity) — a partial index
   stays partial after restart.

   opts:
     {:content-store h   durable jing handle the manifest and blobs read from
      :intake w          the new session's intake writer
      :mode m            of the session being constructed; must equal the
                         candidate's
      :schema s          ditto; its content hash must equal the candidate's
      :ref-type k        the restored trees' ref-type, default :strong (the
                         btree :test seam pins the F2 hazard deterministically)
      :verify? b         kv-storage integrity option (default false)}"
  [candidate {:keys [content-store intake mode schema ref-type verify?]}]
  (when-not (and (map? candidate) (jing/segment-address? (:manifest-address candidate)))
    (throw (ex-info "restore takes a checkpoint candidate carrying a manifest address"
                    {:candidate candidate})))
  (when-not (stream/writer? intake)
    (throw (ex-info "restore :intake must be a dao.stream writer"
                    {:intake intake})))
  (when-not (= mode (:mode candidate))
    (throw (ex-info "checkpoint candidate mode does not match the session being constructed"
                    {:candidate-mode (:mode candidate), :session-mode mode})))
  (validate-schema! schema)
  (when-not (jing/segment-matches? (:schema-address candidate) schema)
    (throw (ex-info "checkpoint candidate schema does not match the session being constructed"
                    {:candidate-schema-address (:schema-address candidate)})))
  (when (= :shared (get-in candidate [:ids :ownership]))
    (throw (ex-info
             "restore refuses a :shared allocator: a session-local snapshot of it is stale the moment any sibling allocates; coordinated recovery is a composition-level checkpoint over every sharing session"
             {:ids (:ids candidate)})))
  (let [manifest (read-manifest content-store (:manifest-address candidate))
        branching (:branching-factor manifest)
        durable (bts/kv-storage content-store
                                {:branching-factor branching,
                                 :ref-type (or ref-type :strong),
                                 :verify? verify?})
        restore-tree (fn [cmp address]
                       (bt/restore-tree cmp address durable (:count manifest)))
        recorder (recording-content-handle)]
    {:indexes {:eavt (restore-tree eavt-cmp (get-in manifest [:indexes :eavt])),
               :aevt (restore-tree aevt-cmp (get-in manifest [:indexes :aevt])),
               :avet (restore-tree avet-cmp (get-in manifest [:indexes :avet])),
               :vaet (restore-tree vaet-cmp (get-in manifest [:indexes :vaet]))},
     :storage (bts/kv-storage recorder
                              {:branching-factor branching, :ref-type :strong}),
     :recorder recorder,
     :branching-factor branching,
     :mode (:mode candidate),
     :ids (:ids candidate),
     :batch (:batch candidate),
     :schema schema,
     :max-t (:max-t candidate),
     :rejected (:rejected candidate),
     :publish {:intake intake, :staged nil, :manifest nil},
     :defects []}))

(ns dao.space.index
  "The transactor-side indexing library (docs/design/dao.space.index.md).

  In Datomic the transactor builds the covered indexes and saves them to
  storage; peers pull segments and answer queries. dao.space decentralizes
  the transactor — every agent appending to its own `dao.stream` is its own
  transactor — so every agent also owns the transactor's other duty:
  indexing its own datoms. This library is that duty, the write-side peer
  of `dao.space.query` (the embeddable Peer that consumes what this
  publishes).

  It owns the index *realization* both sides share:

    - the root conventions: each stream owns `:root/<name>`, named explicitly
      by the query source that consumes it. Every datom root carries one of
      two shapes,
      wholesale `{:datoms [...]}` or owner-built
      `{:indexes {:eavt <segment-key> :aevt ... :avet ... :vaet ...} :count n}`
    - the sort orders (`eavt-cmp`/`aevt-cmp`/`avet-cmp`/`vaet-cmp` over
      heterogeneous values) and the in-memory index (`index-datoms`)
    - the persisted node-blob format, both directions: nodes store as
      plain-EDN content-addressed segment blobs (Merkle by construction —
      dao.data.btree stores children before parents); `restored-indexes`
      re-attaches a manifest lazily, `walk-index-datoms` reads it eagerly,
      both on every platform; `read-datoms` reads either root shape
    - `publish-index!`, the transactor entry point: build, persist the
      segments (put!), advance the root (cas!)

  Build and lazy restore run on every platform: the tree is
  dao.data.btree, one .cljc source (JVM, cljs, cljd), and durability is
  its IStorage over this store (dao.data.btree.storage). Node blobs are
  ordinary EDN either way."
  (:require [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]))


(defn validate-root-key!
  "Throw if `k` is not a valid stream root key. `context` is prepended to the
  error message."
  [k context]
  (when-not (keyword? k)
    (throw (ex-info (str context " requires a keyword key") {:key k})))
  (when-not (= "root" (namespace k))
    (throw (ex-info (str context " requires a :root/<name> key") {:key k})))
  (when (= "" (name k))
    (throw (ex-info (str context " requires a non-empty :root/<name> key")
                    {:key k}))))


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
  guaranteed to be integers here the way dao.db's tempid pipeline
  guarantees — a raw entity map's :db/id is caller-chosen and can be any
  type — so every slot, not just v, needs compare-vals."
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
;; Persisted indexes (Target Architecture: owner-built, lazily pulled)
;; =============================================================================
;; A stream owner persists its covered indexes as immutable, content-
;; addressed B-Tree node blobs (put! under jing/segment-key — Merkle by
;; construction, since dao.data.btree stores children before parents) and
;; publishes `{:indexes {:eavt <segment-key> ...} :count n}` at the stream
;; root via cas!. Node blobs are plain EDN, so any platform can read them
;; eagerly (walk-index-datoms); the *lazy* read path (restored-indexes)
;; rides dao.data.btree's IStorage/restore over dao.data.btree.storage, so
;; it too runs on every platform (JVM, cljs, cljd).

(defn walk-index-datoms
  "Eagerly collect every datom reachable from a persisted index node, in
  index order, by walking the node graph with plain `jing/get`. Node blobs
  are ordinary EDN maps (leaf `{:keys [...]}`, branch `{:level n :keys
  [...] :addresses [...]}`), so this works on every platform — it needs no
  tree-library support at all, only `jing/get` on plain EDN maps. This is
  the eager (and as-of / federated) read path for `{:indexes ...}` roots;
  the lazy path is `restored-indexes`, below — also cross-platform."
  [store address]
  (if (nil? address)
    () ; an empty index has no root node (btree store of an empty set)
    (let [node (jing/get store address nil)]
      (when (nil? node)
        (throw (ex-info "missing index segment" {:address address})))
      (if-some [addresses (:addresses node)]
        (mapcat #(walk-index-datoms store %) addresses)
        (:keys node)))))


(defn read-root
  "Atomically read a stream's root as `{:datoms [...] :expected v
  :reorder-epoch n}` — one `jing/get`, so the three are a consistent
  snapshot. `:expected` is the raw root value exactly as read (or
  `jing/absent` when the key has never been written), and is what a caller
  (`publish-index!`, `transactor/publish!`) hands back to `cas!` so it
  guards against precisely what it saw, closing the lost-update race where
  a concurrent append lands between an index build and its commit.
  `:reorder-epoch` increments only on wholesale→indexes publish (the one
  transition that changes what `next`'s position `n` refers to — see
  `dao.space.transactor`'s cursor gap check); ordinary appends and
  index→wholesale fold-back carry it forward unchanged, since neither
  reorders an already-minted position."
  [store datoms-key]
  (validate-root-key! datoms-key "read-root")
  (let [root (jing/get store datoms-key jing/absent)
        missing? (= jing/absent root)]
    {:datoms (cond missing? []
                   (:indexes root)
                   (vec (walk-index-datoms store (:eavt (:indexes root))))
                   :else (:datoms root)),
     :expected root,
     :reorder-epoch (if missing? 0 (:reorder-epoch root 0))}))


(defn read-datoms
  "Read the datoms held at a dao.jing handle's datoms-key, or [] if never
  seeded. Handles both root shapes: the wholesale `{:datoms [...]}`
  baseline, and the owner-built `{:indexes {:eavt <segment-key> ...}}`
  shape published by `publish-index!` (read by eagerly walking the `:eavt`
  node graph)."
  [store datoms-key]
  (validate-root-key! datoms-key "read-datoms")
  (:datoms (read-root store datoms-key)))


(defn restored-indexes
  "Lazily-loaded {:eavt :aevt :avet :vaet} dao.data.btree sets over a
  published root manifest (`{:indexes {...} :count n}` plus the additive
  `:branching-factor`, absent meaning 512). Nothing is fetched until a
  query traverses; slice (subseq-from) then loads only the seek path plus
  the matching range. Works on every platform.

  The manifest's :count and :branching-factor are threaded through
  restore-tree deliberately (dao.data.btree.md §5.1): count keeps O(1)
  `count` on restored trees without faulting the graph, and the branching
  factor reaches every restored node so mutation splits at the published
  thresholds, never defaults. A manifest without :count is foreign or
  hand-built and belongs to the eager path (walk-index-datoms), not here."
  [store manifest]
  (let [{:keys [indexes count branching-factor]} manifest
        storage (bts/kv-storage store
                                {:branching-factor (or branching-factor 512)})]
    {:eavt (bt/restore-tree eavt-cmp (:eavt indexes) storage count),
     :aevt (bt/restore-tree aevt-cmp (:aevt indexes) storage count),
     :avet (bt/restore-tree avet-cmp (:avet indexes) storage count),
     :vaet (bt/restore-tree vaet-cmp (:vaet indexes) storage count)}))


(defn publish-index!
  "The transactor entry point, owner-side. Builds the four covered indexes
  from the stream's datoms, persists them as immutable content-addressed
  segment blobs (put!), and advances the stream root to
  `{:indexes {...} :count n :branching-factor n}` via cas!. Republishing
  unchanged data is idempotent. Single-writer discipline: throws if the
  root cas! is lost — including when it is lost to a concurrent append
  landing after `datoms` was read: pass `:expected` (from `read-root`) so
  the cas! guards on the root value `datoms` actually came from, not a
  fresher one, otherwise the cas! always finds a value to succeed against
  and silently commits indexes built over stale data, dropping the
  concurrent append. Runs on every platform (dao.data.btree).

  Usage:
    (publish-index! store datoms-key)  — reindexes the current contents of datoms-key
    (publish-index! store datoms opts) — indexes an explicit datom seq

  opts: {:branching-factor n  — max keys per node (default 512, Datomic-
                                style fat segments)
         :key k               — the stream root to advance (required)
         :expected v          — the raw root value `datoms` was read at
                                (default: a fresh read at cas! time, for
                                callers indexing a datom seq unrelated to
                                any live root)
         :reorder-epoch n     — carried into the published root's
                                `:reorder-epoch` + 1 (default: read
                                alongside the :expected fallback)}."
  ([store datoms-key]
   ;; guard the re-aritied API: the old 2-arity took a datom seq, and a
   ;; vector silently becomes a phantom root key. read-root handles this.
   (let [{:keys [datoms expected reorder-epoch]} (read-root store datoms-key)]
     (publish-index!
       store
       datoms
       {:key datoms-key, :expected expected, :reorder-epoch reorder-epoch})))
  ([store datoms opts]
   (let [datoms-key (or (:key opts)
                        (throw (ex-info "publish-index! requires a :key option"
                                        {:opts opts})))]
     (validate-root-key! datoms-key "publish-index! 3-arity")
     (let [branching (:branching-factor opts 512)
           storage (bts/kv-storage store {:branching-factor branching})
           root-addr (fn [cmp]
                       ;; an empty index has no root node; nil is the
                       ;; explicit "nothing here" (walk of nil => ())
                       (when (seq datoms)
                         (-> (bt/from-sequential cmp
                                                 datoms
                                                 {:branching-factor branching})
                             (bt/store-tree storage))))
           indexes {:eavt (root-addr eavt-cmp),
                    :aevt (root-addr aevt-cmp),
                    :avet (root-addr avet-cmp),
                    :vaet (root-addr vaet-cmp)}
           ;; content addressing means `indexes` already tells us whether
           ;; anything changed; comparing against the current root (rather
           ;; than trusting the :expected/:reorder-epoch opts alone) is
           ;; what keeps a same-data republish a true no-op: no cas!, no
           ;; :reorder-epoch bump, no gapping every live cursor over data
           ;; that never actually reordered.
           current (jing/get store datoms-key jing/absent)
           missing? (= jing/absent current)
           expected (if (contains? opts :expected) (:expected opts) current)
           epoch (or (:reorder-epoch opts)
                     (if missing? 0 (:reorder-epoch current 0)))]
       (if (and (not missing?) (= indexes (:indexes current)))
         indexes
         (let [v {:indexes indexes,
                  :count (count datoms),
                  :branching-factor branching,
                  :reorder-epoch (inc epoch)}]
           (when-not (jing/cas! store datoms-key expected v)
             (throw
               (ex-info
                 "publish-index! lost the root cas!"
                 {:key datoms-key,
                  :expected expected,
                  :likely-cause
                  "the root changed since datoms/:expected were read — most
                        often a concurrent append (or, for the 2-arity
                        transactor/publish! path, another publish! winning
                        first — indistinguishable from here); if `datoms`
                        came from some other external write source entirely,
                        it's simply a concurrent writer on this key. Retry
                        by re-reading and republishing, or reconcile with
                        the winner."})))
           indexes))))))

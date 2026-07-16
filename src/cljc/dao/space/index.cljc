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

    - the root-manifest convention: `default-datoms-key` (`:root/datoms`)
      and its two shapes, wholesale `{:datoms [...]}` and owner-built
      `{:indexes {:eavt <segment-key> :aevt ... :avet ... :vaet ...} :count n}`
    - the sort orders (`eavt-cmp`/`aevt-cmp`/`avet-cmp`/`vaet-cmp` over
      heterogeneous values) and the in-memory index (`index-datoms`)
    - the persisted node-blob format, both directions: nodes store as
      plain-EDN content-addressed segment blobs (Merkle by construction —
      psset stores children before parents); `restored-indexes` re-attaches
      a manifest lazily (JVM), `walk-index-datoms` reads it eagerly on
      every platform, `read-datoms` reads either root shape
    - `publish-index!`, the transactor entry point: build, persist the
      segments (put!), advance the root (cas!)

  Build is JVM-only for now (psset durability is a Clojure-only feature);
  readability is universal — node blobs are ordinary EDN."
  (:require [dao.jing :as jing]
            ;; :cljd FIRST: the cljd host pass also matches :clj, and
            ;; persistent-sorted-set has no Dart implementation.
            #?@(:cljd []
                :default [[me.tonsky.persistent-sorted-set :as psset]]))
  #?@(:cljd []
      :clj
      [(:import
         (me.tonsky.persistent_sorted_set Branch IStorage Leaf Settings))]))


(def default-datoms-key
  "The legacy shared root key. The :dao-stream transport no longer writes
  here — each stream owns `:root/<name>` — but a store seeded wholesale at
  this key is still read (see member-keys)."
  :root/datoms)


(def members-key
  "The membership root: `{:members #{:root/<name> ...}}`, the set of stream
  roots currently feeding this store's space. Written by the write path at
  open! (membership is intake, docs/design/dao.space.md); read by
  member-keys to enumerate the roots a query folds. The one shared cas!
  cell left, touched only when a stream attaches — never per append."
  :root/members)


(defn register-member!
  "Add a stream root key to the store's membership root. Idempotent;
  retries a lost cas! (open!-time contention is rare and bounded)."
  [store k]
  (loop []
    (let [root (jing/get store members-key nil)
          rev (:rev root 0)
          members (or (:members root) #{})]
      (or (contains? members k)
          (jing/cas! store members-key rev {:members (conj members k)})
          (recur)))))


(defn member-keys
  "Every datom-bearing root in the store, in a deterministic order:
  the legacy default-datoms-key when present, then the registered members
  sorted by name. This is the enumeration a query folds — IKVStore has no
  scan, so reachability starts from the membership root."
  [store]
  (let [members (:members (jing/get store members-key nil))
        legacy (when (jing/get store default-datoms-key nil)
                 [default-datoms-key])]
    (into (vec legacy) (sort (disj (set members) default-datoms-key)))))


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
           (catch #?(:clj ClassCastException
                     :cljs js/Error
                     :cljd Object)
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


(defn- cmp-field
  "Nil-first, heterogeneous-safe field comparison. Entity ids are not
  guaranteed to be integers here the way dao.db's tempid pipeline
  guarantees — a raw entity map's :db/id is caller-chosen and can be any
  type — so every slot, not just v, needs compare-vals."
  [a b]
  (cond (nil? a) (if (nil? b) 0 -1)
        (nil? b) 1
        :else (compare-vals a b)))


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
  #?(:cljd (sorted-set-by cmp)
     :default (psset/sorted-set-by cmp)))


(defn subseq-from
  "All elements >= sentinel, in index order. On psset platforms this is
  `slice` — a log-n descent that, on a lazily-restored set, loads only the
  nodes on the seek path plus the matching range, never the nodes left of
  the sentinel. The cljd fallback set has no slice; drop-while over seq is
  linear but correct."
  [sorted-set cmp sentinel]
  #?(:cljd (drop-while #(neg? (cmp % sentinel)) (seq sorted-set))
     :default (psset/slice sorted-set sentinel nil cmp)))


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
;; construction, since psset stores children before parents) and publishes
;; `{:indexes {:eavt <segment-key> ...} :count n}` at the stream root via
;; cas!. Node blobs are plain EDN, so any platform can read them eagerly
;; (walk-index-datoms); the *lazy* read path rides persistent-sorted-set's
;; IStorage/restore machinery, a Clojure-only feature of that library, so
;; it is JVM-only for now.
;; :cljd FIRST in every conditional: the cljd host pass also matches :clj.

(defn walk-index-datoms
  "Eagerly collect every datom reachable from a persisted index node, in
  index order, by walking the node graph with plain `jing/get`. Node blobs
  are ordinary EDN maps (leaf `{:keys [...]}`, branch `{:level n :keys
  [...] :addresses [...]}`), so this works on every platform — it needs no
  persistent-sorted-set support, only storage reads. This is the
  cross-platform (and as-of / federated) read path for `{:indexes ...}`
  roots; the lazy path is `restored-indexes`, below, JVM-only."
  [store address]
  (if (nil? address)
    () ; an empty index has no root node (psset/store of an empty set)
    (let [node (jing/get store address nil)]
      (when (nil? node)
        (throw (ex-info "missing index segment" {:address address})))
      (if-some [addresses (:addresses node)]
        (mapcat #(walk-index-datoms store %) addresses)
        (:keys node)))))


(defn read-datoms
  "Read the datoms held at a dao.jing handle's datoms-key (default
  `default-datoms-key`), or [] if never seeded. Handles both root shapes:
  the wholesale `{:datoms [...]}` baseline, and the owner-built
  `{:indexes {:eavt <segment-key> ...}}` shape published by
  `publish-index!` (read by eagerly walking the `:eavt` node graph)."
  ([store] (read-datoms store default-datoms-key))
  ([store datoms-key]
   (let [root (jing/get store datoms-key {:datoms []})]
     (if-some [indexes (:indexes root)]
       (vec (walk-index-datoms store (:eavt indexes)))
       (:datoms root)))))


(defn store-datoms
  "Every datom reachable in the store: read-datoms over each member root
  (see member-keys), concatenated. The store-wide read a query folds."
  [store]
  (mapcat #(read-datoms store %) (member-keys store)))


#?(:cljd nil
   :clj
   (defn- kv-storage
     "psset IStorage over an IKVStore: nodes store as content-addressed
     segment blobs; addresses are the segment keys."
     ^IStorage [store]
     (reify
       IStorage
       (store
         [_ node]
         (let [v (if (instance? Branch node)
                   {:level (.level ^Branch node),
                    :keys (vec (.keys ^Branch node)),
                    :addresses (vec (.addresses ^Branch node))}
                   {:keys (vec (.keys ^Leaf node))})
               k (jing/segment-key v)]
           (jing/put! store k v)
           k))

       (restore
         [_ address]
         (let [{:keys [level keys addresses], :as node}
               (jing/get store address nil)]
           (when (nil? node)
             (throw (ex-info "missing index segment" {:address address})))
           (if addresses
             (Branch. (int level)
                      ^java.util.List keys
                      ^java.util.List addresses
                      (Settings.))
             (Leaf. ^java.util.List keys (Settings.))))))))


#?(:cljd nil
   :clj
   (defn restored-indexes
     "Lazily-loaded {:eavt :aevt :avet :vaet} psset sets over a published
     root's `:indexes` map. Nothing is fetched until a query traverses;
     slice (subseq-from) then loads only the seek path plus the matching
     range."
     [store indexes]
     (let [storage (kv-storage store)]
       {:eavt (psset/restore-by eavt-cmp (:eavt indexes) storage),
        :aevt (psset/restore-by aevt-cmp (:aevt indexes) storage),
        :avet (psset/restore-by avet-cmp (:avet indexes) storage),
        :vaet (psset/restore-by vaet-cmp (:vaet indexes) storage)})))


(defn publish-index!
  "The transactor entry point, owner-side: build the four covered indexes
  from the stream's datoms, persist them as immutable content-addressed
  segment blobs (put!), and advance the stream root to
  `{:indexes {...} :count n}` via cas!. Republishing unchanged data is
  idempotent — content addressing yields the same segment keys, so the same
  root addresses. Single-writer discipline: throws if the root cas! is lost
  to a concurrent writer. JVM-only for now (psset durability is a
  Clojure-only feature); non-JVM readers still read the result eagerly via
  read-datoms.

  opts: {:branching-factor n  — max keys per node (default 512, Datomic-
                                style fat segments)
         :key k               — the stream root to advance (default
                                `default-datoms-key`)}."
  ([store] (publish-index! store (read-datoms store)))
  ([store datoms] (publish-index! store datoms {}))
  ([store datoms opts]
   #?(:cljd (throw (ex-info "publish-index! is JVM-only for now"
                            {:store store, :datoms datoms, :opts opts}))
      :clj (let [datoms-key (:key opts default-datoms-key)
                 branching (:branching-factor opts 512)
                 storage (kv-storage store)
                 root-addr (fn [cmp]
                             ;; an empty index has no root node:
                             ;; psset/store of an empty set yields a
                             ;; phantom; nil is the
                             ;; explicit "nothing here" (walk of nil => ())
                             (when (seq datoms)
                               (-> (psset/from-sequential cmp
                                                          datoms
                                                          {:branching-factor
                                                           branching})
                                   (psset/store storage))))
                 indexes {:eavt (root-addr eavt-cmp),
                          :aevt (root-addr aevt-cmp),
                          :avet (root-addr avet-cmp),
                          :vaet (root-addr vaet-cmp)}
                 rev (:rev (jing/get store datoms-key nil) 0)
                 v {:indexes indexes, :count (count datoms)}]
             (when-not (jing/cas! store datoms-key rev v)
               (throw (ex-info "publish-index! lost the root cas!"
                               {:key datoms-key, :rev rev})))
             indexes)
      :cljs (throw (ex-info "publish-index! is JVM-only for now"
                            {:store store, :datoms datoms, :opts opts})))))

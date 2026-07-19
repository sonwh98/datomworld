(ns dao.jing
  "The minimal key-value storage boundary for DaoJing.
   This protocol represents the 'dumb storage' layer (analogous to Datomic's KVStore,
   as specified in docs/datomic.md) that holds immutable datom segments and mutable
   stream references. It is entirely agnostic to Datalog, indexing, or datoms.
   It only stores opaque byte maps.

   Also owns dao.jing's content-addressing discipline (canonical, content-hash,
   segment-key, key-class): minting a fresh, content-derived key for an
   immutable segment is a property of the storage boundary itself, not of any
   one backend (see docs/design/dao.jing.md, \"The Segment and Root Keyspace\").
   A backend like dao.jing.dht enforces this discipline over an untrusted
   network; it does not invent it."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [datomworld :as dw]))


(defprotocol IKVStore

  (put!
    [this k v-map]
    "Write an entry unconditionally. Used for writing new immutable segments (which never overwrite).
     Returns true on success.")

  (cas!
    [this k old-rev v-map]
    "Compare-And-Swap an entry. Only writes v-map if the current revision matches old-rev.
     Used for updating mutable references like the stream root pointer.
     Returns true if successful, false if the CAS failed (meaning another writer won).
     Distributed backends may instead throw when the authority for k is
     unreachable: unreachability is not the same fact as a lost CAS, and
     returning false would send the caller's retry loop chasing a rev it
     cannot read.")

  (get
    [this k not-found]
    "Read an entry by key. Returns the v-map (which includes its :rev), or not-found if the key
     is absent. The 2-arg signature mirrors clojure.core/get and Datomic's KVStore/get (get(Object, Object)).
     Distributed backends may instead throw when a mutable key's authority is
     unreachable, rather than pass off a freshness failure as absence.")

  (delete!
    [this k]
    "Remove an entry by key.")

  (close!
    [this]
    "Release the storage backend resources."))


;; =============================================================================
;; Content addressing
;; =============================================================================

(defn key-class
  "Dispatch a storage key to its class, :segment or :root. Throws on
  anything else: the class must be recoverable from the key itself, and an
  un-namespaced key has no class."
  [k]
  (case (and (keyword? k) (namespace k))
    "segment" :segment
    "root" :root
    (throw (ex-info "dao.jing keys must be :segment/<hash> or :root/<name>"
                    {:k k}))))


(defn- canonical
  "Order-normalize a value so equal values print identical bytes. This is a
  stand-in for the pinned Eve Flat encoding (see docs/design/dao.jing.dht.md,
  Zero-copy): whatever canonical form a caller relies on must be
  bit-identical on every peer, or content addressing silently fractures."
  [v]
  (cond (map? v) (->> v
                      (map (fn [[k x]] [(canonical k) (canonical x)]))
                      ;; a pr-str-keyed sorted map prints its keys in a
                      ;; fixed order on every platform (array-map is not
                      ;; in
                      ;; ClojureDart)
                      (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))))
        (set? v) (list 'set (sort-by pr-str (map canonical v)))
        (sequential? v) (mapv canonical v)
        :else v))


(defn content-hash
  "SHA-256 of the canonical print of v-map, excluding :rev (:rev is the
  backend's revision stamp, not content)."
  [v-map]
  (dw/sha256 (pr-str (canonical (dissoc v-map :rev)))))


(defn segment-key
  "Mint the content-addressed key for an immutable segment:
  :segment/sha256-<hash(v-map)>. The algorithm prefix is load-bearing twice
  over: it names the hash function, and it keeps the keyword readable EDN —
  a name starting with a bare hex digit cannot survive print -> read, which
  poisons every EDN boundary a root crosses (the file backend's own
  persistence, first of all)."
  [v-map]
  (keyword "segment" (str "sha256-" (content-hash v-map))))


(defn segment-hash
  "The content hash carried by a segment key (strips the algorithm prefix)."
  [k]
  (let [n (name k)]
    (if (str/starts-with? n "sha256-")
      (subs n 7)
      (throw (ex-info "not a sha256 segment key" {:k k})))))

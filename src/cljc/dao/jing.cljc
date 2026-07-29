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
    [this k v]
    "Write v at k unconditionally, replacing whatever is there.

     The value is opaque: stored verbatim, returned verbatim by get, never
     inspected or modified by the store. Any value is legal — scalars,
     collections, nil.

     Used for immutable segments, which are written once under a
     content-derived key and never rewritten. Returns true on success.")

  (cas!
    [this k expected v]
    "Compare-and-swap: write v only if the value currently at k is `=` to
     `expected`. Pass `absent` as `expected` to require that k does not yet
     exist. Used for mutable references like a stream root pointer.

     Returns true if the write landed, false if it lost the race.
     Distributed backends may instead throw when the authority for k is
     unreachable: unreachability is not the same fact as a lost CAS, and
     returning false would send the caller's retry loop chasing a value it
     cannot read.

     The guard is the previous value, not a revision counter, so it cannot
     distinguish A -> B -> A: a writer still holding the first A wins a CAS it
     ought to lose. Callers whose root values can recur must carry their own
     discriminator — dao.space.index does this with a monotonically
     incremented :reorder-epoch.")

  (get
    [this k not-found]
    "Read the value at k, or not-found if the key is absent. Returns exactly
     what was written. The 2-arg signature mirrors clojure.core/get.

     Because nil is a legal stored value, absence must be detected through
     not-found (`absent` serves as a sentinel), never by testing the result
     for nil.

     Distributed backends may instead throw when a mutable key's authority is
     unreachable, rather than pass off a freshness failure as absence.")

  (delete!
    [this k]
    "Remove an entry by key.")

  (close!
    [this]
    "Release the storage backend resources."))


(def absent
  "Sentinel meaning \"no entry\": `cas!`'s `expected` when the key must not
  already exist, and a usable `not-found` for `get`. A namespaced keyword so it
  survives EDN and transit unchanged; it is therefore reserved and must not be
  stored as a value."
  ::absent)


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
  "SHA-256 of the canonical print of v. Total over any value: the store no
  longer stamps anything into the payload, so there is nothing to exclude."
  [v]
  (dw/sha256 (pr-str (canonical v))))


(defn segment-key
  "Mint the content-addressed key for an immutable segment:
  :segment/sha256-<hash(v)>. The algorithm prefix is load-bearing twice
  over: it names the hash function, and it keeps the keyword readable EDN —
  a name starting with a bare hex digit cannot survive print -> read, which
  poisons every EDN boundary a root crosses (the file backend's own
  persistence, first of all)."
  [v]
  (keyword "segment" (str "sha256-" (content-hash v))))


(defn segment-hash
  "The content hash carried by a segment key (strips the algorithm prefix)."
  [k]
  (let [n (name k)]
    (if (str/starts-with? n "sha256-")
      (subs n 7)
      (throw (ex-info "not a sha256 segment key" {:k k})))))

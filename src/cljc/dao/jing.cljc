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
            #?@(:cljs [[goog.crypt :as crypt] goog.crypt.Sha256])
            #?@(:cljd [["dart:convert" :as convert]])))


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


#?(:cljd (do
           (def ^:private mask-32 0xffffffff)
           (def ^:private initial-h
             [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a 0x510e527f 0x9b05688c
              0x1f83d9ab 0x5be0cd19])
           (def ^:private k-table
             [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1
              0x923f82a4 0xab1c5ed5 0xd807aa98 0x12835b01 0x243185be 0x550c7dc3
              0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174 0xe49b69c1 0xefbe4786
              0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
              0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147
              0x06ca6351 0x14292967 0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13
              0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85 0xa2bfe8a1 0xa81a664b
              0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
              0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a
              0x5b9cca4f 0x682e6ff3 0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208
              0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])
           (def ^:private hex-digits "0123456789abcdef")
           (defn- mask32
             [n]
             (bit-and n mask-32))
           (defn- rotr32
             [n b]
             (mask32 (bit-or (unsigned-bit-shift-right n b)
                             (bit-shift-left n (- 32 b)))))
           (defn- shr32
             [n b]
             (unsigned-bit-shift-right n b))
           (defn- ch
             [x y z]
             (bit-xor (bit-and x y) (bit-and (bit-not x) z)))
           (defn- maj
             [x y z]
             (bit-xor (bit-xor (bit-and x y) (bit-and x z)) (bit-and y z)))
           (defn- sigma0
             [x]
             (bit-xor (bit-xor (rotr32 x 2) (rotr32 x 13)) (rotr32 x 22)))
           (defn- sigma1
             [x]
             (bit-xor (bit-xor (rotr32 x 6) (rotr32 x 11)) (rotr32 x 25)))
           (defn- gamma0
             [x]
             (bit-xor (bit-xor (rotr32 x 7) (rotr32 x 18)) (shr32 x 3)))
           (defn- gamma1
             [x]
             (bit-xor (bit-xor (rotr32 x 17) (rotr32 x 19)) (shr32 x 10)))
           (defn- utf8-bytes
             [s]
             (convert/utf8.encode s))
           (defn- pad-message
             [bytes]
             (let [len (count bytes)
                   bit-len (* len 8)
                   k (mod (- 55 (mod len 64)) 64)
                   padded (concat bytes [0x80] (repeat k 0))
                   high-bits (quot bit-len 0x100000000)
                   low-bits (mod bit-len 0x100000000)]
               (concat padded
                       [(bit-shift-right high-bits 24)
                        (bit-and (bit-shift-right high-bits 16) 0xff)
                        (bit-and (bit-shift-right high-bits 8) 0xff)
                        (bit-and high-bits 0xff) (bit-shift-right low-bits 24)
                        (bit-and (bit-shift-right low-bits 16) 0xff)
                        (bit-and (bit-shift-right low-bits 8) 0xff)
                        (bit-and low-bits 0xff)])))
           (defn- process-chunk
             [h chunk]
             (let [w (vec (concat (map (fn [[b0 b1 b2 b3]]
                                         (mask32 (bit-or (bit-shift-left b0 24)
                                                         (bit-shift-left b1 16)
                                                         (bit-shift-left b2 8)
                                                         b3)))
                                       (partition 4 chunk))
                                  (repeat 48 0)))
                   w (loop [i 16
                            w w]
                       (if (< i 64)
                         (let [s0 (gamma0 (nth w (- i 15)))
                               s1 (gamma1 (nth w (- i 2)))
                               v (mask32
                                   (+ (nth w (- i 16)) s0 (nth w (- i 7)) s1))]
                           (recur (inc i) (assoc w i v)))
                         w))]
               (loop [i 0
                      [a b c d e f g h-val] h]
                 (if (< i 64)
                   (let [t1 (mask32 (+ h-val
                                       (sigma1 e)
                                       (ch e f g)
                                       (nth k-table i)
                                       (nth w i)))
                         t2 (mask32 (+ (sigma0 a) (maj a b c)))
                         new-a (mask32 (+ t1 t2))
                         new-e (mask32 (+ d t1))]
                     (recur (inc i) [new-a a b c new-e e f g]))
                   (mapv (fn [orig curr] (mask32 (+ orig curr)))
                         h
                         [a b c d e f g h-val])))))
           (defn- word->hex8
             [word]
             (let [d7 (nth hex-digits (bit-and (bit-shift-right word 28) 0xf))
                   d6 (nth hex-digits (bit-and (bit-shift-right word 24) 0xf))
                   d5 (nth hex-digits (bit-and (bit-shift-right word 20) 0xf))
                   d4 (nth hex-digits (bit-and (bit-shift-right word 16) 0xf))
                   d3 (nth hex-digits (bit-and (bit-shift-right word 12) 0xf))
                   d2 (nth hex-digits (bit-and (bit-shift-right word 8) 0xf))
                   d1 (nth hex-digits (bit-and (bit-shift-right word 4) 0xf))
                   d0 (nth hex-digits (bit-and word 0xf))]
               (str d7 d6 d5 d4 d3 d2 d1 d0)))
           (defn- bytes->hex
             [words]
             (apply str (for [word words] (word->hex8 (mask32 word)))))))


(defn sha256
  "SHA-256 hex digest of string s."
  [s]
  #?(:clj (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                bytes (.digest digest (.getBytes s "UTF-8"))]
            (apply str (map (partial format "%02x") bytes)))
     :cljs (let [hasher (new goog.crypt.Sha256)]
             (.update hasher s)
             (crypt/byteArrayToHex (.digest hasher)))
     :cljd (let [padded (pad-message (utf8-bytes s))
                 chunks (partition 64 padded)
                 final-h (reduce process-chunk initial-h chunks)]
             (bytes->hex final-h))))


(defn content-hash
  "SHA-256 of the canonical print of v. Total over any value: the store no
  longer stamps anything into the payload, so there is nothing to exclude."
  [v]
  (sha256 (pr-str (canonical v))))


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

(ns dao.jing
  "A stream observer: the conceptual fold over a dao.stream that projects an
   append-only log into a materialization target (docs/design/dao.jing.md).

   There is no protocol — storage is a stream (ds/append!) with a materialized
   target (a plain map or atom). Backends return a handle map {:stream s :target a
   ...} and the operations below are plain functions over that data. Nothing is
   polymorphic: what a handle-map holds is visible, and every consumer can add its
   own fields (remote, DHT) without extending anything.

   Also owns the content-addressing discipline (canonical, content-hash,
   segment-key, key-class): minting a fresh, content-derived key for an immutable
   segment is a property of the storage boundary itself, not of any one backend."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [dao.stream :as ds]
            #?@(:cljs [[goog.crypt :as crypt] goog.crypt.Sha256])
            #?@(:cljd [["dart:convert" :as convert]])))


(def absent
  "Sentinel meaning \"no entry\". Used as the `expected` guard in `cas!` when
   a key must not already exist, and as the `not-found` return for `get`.
   A namespaced keyword so it survives EDN and transit unchanged; it is
   therefore reserved and must not be stored as a value."
  ::absent)


(declare materialize-step)


;; =============================================================================
;; Store operations — plain functions over a handle
;; =============================================================================
;; Handle types (no protocol — dispatch on handle contents):
;;   - plain map {:stream s :target a ...}         — mem, in-memory
;;   - atom holding {:stream s :target a ...}       — file (compaction swaps
;;   it)
;;   - map with :call-fn + :close-fn                — remote (RPC delegation)
;;   - map with :local + :node                      — DHT (local + network
;;   fetch)

(defn- resolve-handle
  [h]
  #?(:clj (if (instance? clojure.lang.IAtom h) @h h)
     :cljs (if (instance? cljs.core/Atom h) @h h)
     :cljd h))


(defn cas!
  "Append a [k :cas expected v] tuple. For stream handles, appends to the
   stream and updates the target atom via materialize-step. For handles
   carrying :call-fn, delegates to the RPC call. For handles carrying
   :cas-fn, delegates to the custom CAS implementation.
   Returns true if the CAS landed, false otherwise."
  [handle k expected v]
  (let [h (resolve-handle handle)]
    (cond (:call-fn h) ((:call-fn h) :jing/cas! [k expected v])
          (:cas-fn h) ((:cas-fn h) k expected v)
          :else (let [{:keys [stream target encode-fn]} h
                      rec [k :cas expected v]
                      payload (if encode-fn (encode-fn rec) rec)
                      res (ds/append! stream payload)]
                  (if (and (map? res) (= :ok (:result res)))
                    (let [changed? (atom false)]
                      (swap! (:target h) (fn [old]
                                           (let [new (materialize-step old rec)]
                                             (when-not (= old new)
                                               (reset! changed? true))
                                             new)))
                      (or @changed?
                          (and (= expected absent)
                               (= (clojure.core/get @(:target h) k absent) v))))
                    false)))))


(defn get
  "Read the value at k from the handle. For stream handles, reads from the
   materialized target. For remote handles (carrying :call-fn), delegates to
   the RPC call. For DHT handles (carrying :get-fn), delegates to the DHT
   lookup. Returns not-found if the key is absent."
  [handle k not-found]
  (let [h (resolve-handle handle)]
    (if (:closed h)
      not-found
      (cond (:call-fn h) ((:call-fn h) :jing/get [k not-found])
            (:get-fn h) ((:get-fn h) k not-found)
            :else (let [target (:target h)]
                    (clojure.core/get (if (instance? #?(:clj clojure.lang.IDeref
                                                        :cljs cljs.core/IDeref
                                                        :cljd Object)
                                                     target)
                                        @target
                                        target)
                                      k
                                      not-found))))))


(defn delete!
  "Remove an entry. For stream handles, appends [k :cas current-v absent].
   For remote handles, delegates to the RPC call. For DHT handles, delegates
   to :delete-fn. Returns true if the key was present and deleted, or was
   already absent."
  [handle k]
  (let [h (resolve-handle handle)]
    (cond (:call-fn h) ((:call-fn h) :jing/delete! [k])
          (:delete-fn h) ((:delete-fn h) k)
          :else
          (let [current (get handle k absent)]
            (if (= current absent) true (cas! handle k current absent))))))


(defn close!
  "Release the handle's resources. Calls :close-fn when present, then closes
   the stream. For atom handles (file), sets :closed. Idempotent."
  [handle]
  (let [h (resolve-handle handle)]
    (when-let [close-fn (:close-fn h)] (close-fn))
    (when (:stream h) (ds/close! (:stream h)))
    (when (instance? #?(:clj clojure.lang.IDeref
                        :cljs cljs.core/IDeref
                        :cljd Object)
                     handle)
      (swap! handle assoc :closed true))
    nil))


;; =============================================================================
;; Stream Observer & Step Functions (docs/design/dao.jing.md)
;; =============================================================================

(defn materialize-step
  "Pure step function for in-memory map targets.
   Processes a [k :cas expected v] record and returns the updated map."
  [m record]
  (if (and (vector? record) (= (count record) 4) (= (second record) :cas))
    (let [[k _ expected v] record
          current (clojure.core/get m k absent)]
      (if (= current expected) (if (= v absent) (dissoc m k) (assoc m k v)) m))
    m))


(defn materialize-mutable-step!
  "Side-effectful step function for external/mutable storage targets.
   Uses read-fn, write-fn!, and delete-fn! to update target and returns target."
  [target record read-fn write-fn! delete-fn!]
  (if (and (vector? record) (= (count record) 4) (= (second record) :cas))
    (let [[k _ expected v] record
          current (read-fn target k absent)]
      (if (= current expected)
        (do (if (= v absent) (delete-fn! target k) (write-fn! target k v))
            target)
        target))
    target))


(defn evaluator
  "Constructs a query evaluator function for dao.stream.apply read endpoints.
   Accepts a target and optional read-fn (defaults to clojure.core/get).
   Returns a handler (fn [{:dao.stream.apply/keys [id op args]}]) => response packet."
  ([target] (evaluator target clojure.core/get))
  ([target read-fn]
   (fn [{:dao.stream.apply/keys [id op args]}]
     (let [val (case op
                 :op/get (let [[k] args] (read-fn target k absent))
                 (throw (ex-info "Unknown query op" {:op op})))]
       {:dao.stream.apply/id id, :dao.stream.apply/value val}))))


(defn step-incremental!
  "Single-step incremental materializer over a stream.
   Reads next record at `@cursor-atom`, updates `@target-atom` via `materialize-step`,
   and advances cursor. Returns stream signal (:ok, :wait, :complete, :resync)."
  [target-atom cursor-atom stream]
  (let [res (ds/next stream @cursor-atom)]
    (cond (map? res) (let [record (:ok res)]
                       ;; Loop-level fast-reject before swap!
                       (when (and (vector? record)
                                  (= (count record) 4)
                                  (= (second record) :cas))
                         (swap! target-atom materialize-step record))
                       (reset! cursor-atom (:cursor res))
                       :ok)
          (= res :blocked) :wait
          (= res :end) :complete
          (= res :daostream/gap) :resync)))


(defn step-service!
  "Single-step query evaluator service over a dao.stream.apply endpoint pair.
   Reads query request from request stream, evaluates via handler, and appends response packet."
  [target cursor-atom endpoint-descriptor]
  (let [{:dao.stream.apply/keys [request response]} endpoint-descriptor
        handler (evaluator target)
        res (ds/next request @cursor-atom)]
    (cond (map? res) (let [req (:ok res)
                           resp (handler req)]
                       ;; Best-effort response delivery: if response stream
                       ;; is full/closed, request is dropped
                       (ds/append! response resp)
                       (reset! cursor-atom (:cursor res))
                       :ok)
          (= res :blocked) :wait
          (= res :end) :complete
          (= res :daostream/gap) :resync)))


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

(ns dao.jing
  "DaoJing: the content-addressed storage observer (docs/design/dao.jing.md).

   A content-store handle is plain data, not a protocol:
   {:put-content-fn f, :get-content-fn g, :close-fn c}. The backend effects
   are explicit functions: materialize! computes the content address solely
   from the payload, inserts idempotently, and returns the address only
   after the backend reports durability. get reads only :segment/sha256-...
   content addresses; arbitrary keys and mutable roots are outside DaoJing.

   The observer (observer-state / observe-step! / adopt-cursor) coordinates
   an explicit intake pool of dao.stream reader handles and materializes
   every payload through dao.stream.observe/step. Pool membership and
   every member's initial cursor are supplied by the caller; statuses and
   the scheduling index are ordinary immutable data. There are no atoms,
   globals, registration, or discovery, and the source stream never enters
   an address or a stored value."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.observe :as observe]
            #?@(:cljs [[goog.crypt :as crypt]
                       goog.crypt.Sha256
                       ["@noble/hashes/blake3.js" :as noble-blake3]
                       ["@noble/hashes/utils.js" :as noble-utils]])
            #?@(:cljd [["dart:convert" :as convert]
                       ["package:blake3_dart/blake3_dart.dart"
                        :as blake3-dart]]))
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


(def ^:private content-missing
  "Internal not-found sentinel used to verify :present read-backs. An opaque
   per-host identity object, never a keyword: :dao.jing/content-missing is a
   legal opaque payload, and a keyword sentinel would be ambiguous with a
   genuinely stored value. Never exposed: a conforming backend cannot store
   or return it."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(def ^:private hex-digits-set
  #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f})


;; =============================================================================
;; Content addressing (docs/design/dao.jing.md, Canonical encoding)
;; =============================================================================

(defn- canonical-print
  "Print an order-normalized value following Clojure's printing conventions
   byte for byte — space-separated sequential elements, `, `-separated map
   entries, metadata as a ^m prefix — instead of delegating to the host
   printer, which drops collection metadata in at least one case (a sorted
   set prints through its metadata-less seq on Dart). Scalars still print
   through pr-str."
  [n]
  (let [prefixed (fn [body]
                   (if (meta n)
                     (str "^" (canonical-print (meta n)) " " body)
                     body))]
    (cond (map? n)
          (prefixed
            (str "{"
                 (str/join ", " (map (fn [[k v]]
                                       (str (canonical-print k) " "
                                            (canonical-print v)))
                                     n))
                 "}"))
          (set? n)
          (prefixed
            (str "#{" (str/join " " (map canonical-print n)) "}"))
          (vector? n)
          (prefixed
            (str "[" (str/join " " (map canonical-print n)) "]"))
          (sequential? n)
          (prefixed
            (str "(" (str/join " " (map canonical-print n)) ")"))
          :else (pr-str n))))


(defn- order-normalize
  "Normalize a value so equal values print identically: maps and sets sort by
   canonically-printed key/element, sequences recurse, and collection metadata is
   normalized and reattached so it survives into the address. Reader position
   metadata (:line/:column and friends) is not content and is dropped; every
   other metadata difference changes the address.

   A normalized set stays a (sorted) set and therefore prints with #{}
   braces: the set-ness marker comes from the printer, never from a tag like
   '(set ...) placed inside the ordinary value domain where a real list of
   that shape could collide with it.

   Records are not a supported payload: the hosts cannot even agree on how
   to print one (tagged literal on the JVM and JS, plain map on Dart), so a
   record and its equal plain map would collide somewhere. order-normalize
   throws on records — anywhere in the value — rather than silently
   addressing them as maps.

   Transitional: this exists only to make the print-based content hash
   deterministic and order-insensitive until the pinned, cross-platform
   canonical byte encoding lands (docs/design/dao.jing.md, Canonical
   encoding). It is NOT that canonical encoding."
  [v]
  (let [attach-meta (fn [normalized]
                      (let [;; reader position is not content: the hosts'
                            ;; readers stamp source coordinates onto list
                            ;; literals, which would make an address depend
                            ;; on where a payload was written. Everything
                            ;; else in the metadata is address-significant.
                            m (dissoc (meta v)
                                      :line :column :end-line :end-column)]
                        ;; empty metadata is dropped: = ignores metadata
                        ;; entirely, and the hosts disagree on whether ^{}
                        ;; prints (the JVM skips it, Dart prints it)
                        (if (seq m)
                          (with-meta normalized (order-normalize m))
                          normalized)))]
    (cond (record? v)
          (throw (ex-info "dao.jing does not address records: hosts print them differently, so their addresses would collide across hosts"
                          {:payload v}))
          (map? v) (attach-meta
                     (->> v
                          (map (fn [[k x]]
                                 [(order-normalize k)
                                  (order-normalize x)]))
                          ;; a canonically-printed-keyed sorted map prints
                          ;; its keys in a fixed order on every platform
                          ;; (array-map is not in ClojureDart)
                          (into (sorted-map-by
                                  #(compare (canonical-print %1)
                                            (canonical-print %2))))))
          (set? v) (attach-meta
                     ;; a sorted set prints its elements in one fixed order
                     ;; on every platform and keeps its #{} braces, so it can
                     ;; never print like the list or vector of the same
                     ;; elements
                     (into (sorted-set-by
                             #(compare (canonical-print %1)
                                       (canonical-print %2)))
                           (map order-normalize v)))
          (vector? v) (attach-meta (mapv order-normalize v))
          ;; lists and seqs are one canonical value: = calls them equal and
          ;; both print (e1 e2 ...), so both normalize to a list. The
          ;; with-meta nil is load-bearing: ClojureDart's list returns a list
          ;; carrying cljd.core's own reader metadata (:line, :tag
          ;; PersistentList, ...), which would otherwise print into the address
          (sequential? v) (attach-meta
                            (with-meta (apply list (map order-normalize v))
                              nil))
          :else v)))


(defn- order-normalized-print
  "Transitional encoder: canonical-print over the order-normalized form,
   which prints collection metadata instead of dropping it. NOT the final
   canonical byte encoding; see order-normalize."
  [v]
  (canonical-print (order-normalize v)))


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
  "SHA-256 hex digest of the UTF-8 bytes of string s. Every host digests
   the same bytes: handing goog.crypt.Sha256 the string itself hashes
   char codes, which diverges from the JVM and Dart on any non-ASCII
   payload and mints host-specific addresses."
  [s]
  #?(:clj (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                bytes (.digest digest (.getBytes s "UTF-8"))]
            (apply str (map (partial format "%02x") bytes)))
     :cljs (let [hasher (new goog.crypt.Sha256)]
             (.update hasher (crypt/stringToUtf8ByteArray s))
             (crypt/byteArrayToHex (.digest hasher)))
     :cljd (let [padded (pad-message (utf8-bytes s))
                 chunks (partition 64 padded)
                 final-h (reduce process-chunk initial-h chunks)]
             (bytes->hex final-h))))


(defn sha256-bytes
  "SHA-256 hex digest of host bytes bs (byte[] on the JVM, Uint8Array on
   ClojureScript, Uint8List on Dart). `(sha256-bytes (canonical-bytes v))`
   is `(content-hash v)` — the two are one digest over one byte stream."
  [bs]
  #?(:clj (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                bytes (.digest digest ^bytes bs)]
            (apply str (map (partial format "%02x") bytes)))
     :cljs (let [hasher (new goog.crypt.Sha256)]
             (.update hasher bs)
             (crypt/byteArrayToHex (.digest hasher)))
     :cljd (let [padded (pad-message bs)
                 chunks (partition 64 padded)
                 final-h (reduce process-chunk initial-h chunks)]
             (bytes->hex final-h))))


(def default-hash-algorithm
  "The default algorithm used for implicit minting (content-hash, segment-key,
   materialize!). Never consulted when verifying an existing address."
  :blake3)


(def registry
  "The closed, immutable algorithm registry. Both initial entries produce
   32-byte (64 lowercase hex character) digests."
  {:blake3 {:address-id "blake3", :digest-bytes 32}
   :sha256 {:address-id "sha256", :digest-bytes 32}})


(def ^:private registry-by-address-id
  (into {} (map (fn [[k v]] [(:address-id v) k])) registry))


(def ^:private algorithm-id-pattern
  "Algorithm address identifiers are lowercase ASCII alphanumeric strings."
  #"[a-z0-9]+")


(def ^:private hex-digit-pattern
  #"[0-9a-f]+")


(defn blake3-bytes
  "BLAKE3 lowercase hex digest of host bytes bs (byte[] on the JVM,
   Uint8Array on ClojureScript, Uint8List on Dart)."
  [bs]
  #?(:clj (let [b (io.github.rctcwyvrn.blake3.Blake3/newInstance)]
            (.update b ^bytes bs)
            (.hexdigest b))
     :cljs (noble-utils/bytesToHex (noble-blake3/blake3 bs))
     :cljd (let [^Uint8List u8 (if (instance? Uint8List bs)
                                 bs
                                 (Uint8List.fromList bs))]
             (blake3-dart/blake3Hex u8))))


(defn blake3
  "BLAKE3 lowercase hex digest of the UTF-8 bytes of string s."
  [s]
  #?(:clj (let [b (io.github.rctcwyvrn.blake3.Blake3/newInstance)]
            (.update b (.getBytes ^String s "UTF-8"))
            (.hexdigest b))
     :cljs (blake3-bytes (crypt/stringToUtf8ByteArray s))
     :cljd (blake3-bytes (convert/utf8.encode s))))


(defn digest-bytes
  "Digest host bytes bs with the specified algorithm keyword (:blake3, :sha256).
   Returns lowercase hexadecimal string. Unknown algorithms throw."
  [algorithm bs]
  (case algorithm
    :blake3 (blake3-bytes bs)
    :sha256 (sha256-bytes bs)
    (throw (ex-info (str "unsupported hash algorithm: " algorithm)
                    {:algorithm algorithm}))))


(defn digest-string
  "Digest string s as UTF-8 bytes with the specified algorithm keyword.
   Returns lowercase hexadecimal string. Unknown algorithms throw."
  [algorithm s]
  (case algorithm
    :blake3 (blake3 s)
    :sha256 (sha256 s)
    (throw (ex-info (str "unsupported hash algorithm: " algorithm)
                    {:algorithm algorithm}))))


(defn canonical-bytes
  "Host UTF-8 bytes (byte[] / Uint8Array / Uint8List) of the
   order-normalized print of v -- the exact bytes content hashing digests, so
   `(digest-bytes algo (canonical-bytes v))` is
   `(content-hash v {:algorithm algo})`.
   This is the byte form Jing content travels in across a `dao.stream`
   boundary."
  [v]
  (let [text (order-normalized-print v)]
    #?(:clj (.getBytes ^String text "UTF-8")
       :cljs (js/Uint8Array.from (crypt/stringToUtf8ByteArray text))
       :cljd (utf8-bytes text))))


(defn content-hash
  "Hash the canonical-bytes of v using the specified algorithm (defaulting to
   default-hash-algorithm, i.e. :blake3). Returns lowercase hexadecimal string.
   Arities: [v] and [v {:keys [algorithm]}]."
  ([v]
   (content-hash v {:algorithm default-hash-algorithm}))
  ([v opts]
   (let [algo (clojure.core/get opts :algorithm default-hash-algorithm)]
     (digest-bytes algo (canonical-bytes v)))))


(defn segment-key
  "Mint the content-addressed key for an opaque payload, derived solely from
   the payload: :segment/<algorithm-id>-<digest>.
   The 1-arg form defaults to default-hash-algorithm (:blake3).
   The 2-arg form accepts {:algorithm :sha256} (or any registered algorithm)."
  ([v]
   (segment-key v {:algorithm default-hash-algorithm}))
  ([v opts]
   (let [algo (clojure.core/get opts :algorithm default-hash-algorithm)
         reg (clojure.core/get registry algo)]
     (when-not reg
       (throw (ex-info (str "unsupported hash algorithm: " algo)
                       {:algorithm algo})))
     (let [algo-id (:address-id reg)
           digest (content-hash v {:algorithm algo})]
       (keyword "segment" (str algo-id "-" digest))))))


(defn parse-segment-address
  "The authoritative address parser: requires a `segment` namespace keyword,
   splits the name on the first `-` into algorithm identifier and digest,
   looks the identifier up by exact match (no prefix matching), validates
   digest length from the registry entry and lowercase-hex text, and
   reconstructs the canonical address to reject alternative spellings.
   Returns {:algorithm :<algo>, :digest \"<hex>\", :canonical :segment/...}
   or nil on any rejection (never throws)."
  [address]
  (when (keyword? address)
    (when (= "segment" (namespace address))
      (let [n (name address)
            i (str/index-of n "-")]
        (when (and i (pos? i))
          (let [algo-id (subs n 0 i)
                digest (subs n (inc i))]
            (when (re-matches algorithm-id-pattern algo-id)
              (when-let [algo (clojure.core/get registry-by-address-id algo-id)]
                (let [{:keys [digest-bytes]}
                      (clojure.core/get registry algo)]
                  (when (and (= (* 2 digest-bytes) (count digest))
                             (re-matches hex-digit-pattern digest))
                    (let [canonical (keyword "segment"
                                             (str algo-id "-" digest))]
                      (when (= canonical address)
                        {:algorithm algo,
                         :digest digest,
                         :canonical canonical}))))))))))))


(defn segment-address?
  "True when x is a valid content address in the closed registry:
   :segment/<algorithm>-<digest-hex>."
  [x]
  (boolean (parse-segment-address x)))


(defn segment-algorithm
  "The algorithm keyword (:blake3, :sha256) carried by a segment address.
   Throws on invalid input."
  [address]
  (if-let [parsed (parse-segment-address address)]
    (:algorithm parsed)
    (throw (ex-info "not a segment content address" {:address address}))))


(defn segment-digest
  "The lowercase hex digest carried by a segment address.
   Throws on invalid input."
  [address]
  (if-let [parsed (parse-segment-address address)]
    (:digest parsed)
    (throw (ex-info "not a segment content address" {:address address}))))


(defn segment-hash
  "The content hash carried by a segment address. Equivalent to segment-digest.
   Total only over valid segment addresses; throws on invalid input."
  [k]
  (segment-digest k))


(defn segment-matches?
  "Total verification predicate: true when address is a valid segment address
   and its digest matches the hash of canonical-bytes of payload under the
   address-carried algorithm.
   Never throws: malformed addresses, unknown algorithms, digest mismatches,
   and canonical-encoder refusal all return false."
  [address payload]
  (if-let [{:keys [algorithm digest]} (parse-segment-address address)]
    (try
      (let [bs (canonical-bytes payload)
            actual (digest-bytes algorithm bs)]
        (= digest actual))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _
        false))
    false))


(defn materialize!
  "Content-address payload and store it through the handle's backend.

   Supports [handle payload] (defaulting to default-hash-algorithm, :blake3)
   and [handle payload {:keys [algorithm]}].

   The address is derived solely from the payload via segment-key, and the
   backend effect :put-content-fn is invoked as (put-content-fn address
   payload). The backend must answer with one of:

     :inserted — the value is durably stored now;
     :present  — an equal value is already stored under that address.

   Any other answer is an invalid backend result and throws: DaoJing does
   not accept ambiguous truthiness. The address is returned only after the
   backend reports success.

   On :present the stored value is read back through :get-content-fn and
   verified to hash to the address with segment-matches?. Equal content is
   idempotent and returns the same address. Content that does not hash to its
   own address is an integrity failure and throws loudly. Nothing is ever
   overwritten."
  ([handle payload]
   (materialize! handle payload {:algorithm default-hash-algorithm}))
  ([handle payload opts]
   (let [put (:put-content-fn handle)
         get-fn (:get-content-fn handle)]
     (when-not (fn? put)
       (throw (ex-info "dao.jing handle requires :put-content-fn"
                       {:handle handle})))
     (when-not (fn? get-fn)
       (throw (ex-info "dao.jing handle requires :get-content-fn"
                       {:handle handle})))
     (let [address (segment-key payload opts)
           result (put address payload)]
       (case result
         :inserted address
         :present
         (let [stored (get-fn address content-missing)]
           (cond
             (identical? stored content-missing)
             (throw
               (ex-info
                 "backend reported :present but the content address is absent"
                 {:address address, :payload payload}))
             ;; the read-back must hash to the address it sits at
             (segment-matches? address stored) address
             :else
             (throw
               (ex-info
                 (str "content collision: the stored value does not hash to "
                      "its content address")
                 {:address address, :stored stored, :payload payload}))))
         (throw (ex-info
                  "invalid backend put result"
                  {:result result, :address address, :payload payload})))))))


(defn get
  "Retrieve the opaque value stored at a content address.

   Only valid registered content addresses are valid DaoJing reads;
   arbitrary keys and mutable roots are outside DaoJing and throw before the
   backend is consulted. Returns not-found when the address is absent."
  [handle address not-found]
  (when-not (segment-address? address)
    (throw (ex-info "dao.jing reads only valid segment content addresses"
                    {:address address})))
  (let [get-fn (:get-content-fn handle)]
    (when-not (fn? get-fn)
      (throw (ex-info "dao.jing handle requires :get-content-fn"
                      {:handle handle})))
    (get-fn address not-found)))


(defn close!
  "Close the backend through its optional :close-fn. Idempotency is the
   backend's contract: close! simply delegates, so a backend that tolerates
   repeated closes may be closed repeatedly. Returns nil. Handles without
   :close-fn have nothing to release."
  [handle]
  (when-let [close-fn (:close-fn handle)] (close-fn))
  nil)


;; =============================================================================
;; Intake-pool observer (docs/design/dao.jing.md, The intake pool and
;; Cursor tracking and recovery)
;; =============================================================================

(defn observer-state
  "Construct the observer state for an explicit intake pool.

   members is a sequence of {:stream <dao.stream reader handle> :cursor
   <opaque>} entries. The composition mints each cursor itself, from an
   anchor of its choosing, and hands it in; DaoJing never fabricates one.
   Returns plain data, no atoms or registration:

     {:members [{:stream s, :cursor c, :status :pending} ...]
      :next 0}

   A member without a :cursor, or whose :stream lacks the reader surface,
   is a composition defect and throws here, before any operation. :next is
   the fair round-robin index observe-step! polls first; :pending is the
   never-polled status. Because the state is plain data,
   (observer-state (:members state)) rebuilds one from its members."
  [members]
  {:members (mapv (fn [member]
                    (when-not (map? member)
                      (throw (ex-info
                               "dao.jing pool members are {:stream s :cursor c} maps"
                               {:member member})))
                    (when-not (contains? member :cursor)
                      (throw (ex-info
                               "dao.jing pool member requires a cursor minted by its stream"
                               {:member member})))
                    (when-not (stream/reader? (:stream member))
                      (throw (ex-info
                               "dao.jing pool member requires a dao.stream reader"
                               {:member member})))
                    {:stream (:stream member),
                     :cursor (:cursor member),
                     :status :pending})
                  members),
   :next 0})


(defn adopt-cursor
  "Set member index's cursor to one the stream handed out. Beside a
   successful observation this is the only way a member's cursor changes:
   the pool performs no cursor arithmetic, inspects no cursor shape, and
   mints nothing. Recovering a gap is the caller's decision, made on the
   recovery cursor a gap report carries; nothing here resynchronizes
   anything on its own."
  [state index cursor]
  (assoc-in state [:members index :cursor] cursor))


(defn observe-step!
  "Walk the intake pool once from (:next state) and process at most one
   payload, through dao.stream.observe/step with materialize! as the
   effect. Returns {:state next-state :signal s ...} where the signal is
   drawn from the same seven outcomes dao.stream declares for next:

     {:signal :dao.stream/ok, :address a}
       a payload was materialized; the member advanced to the successor
       cursor its stream returned, and yields its turn;
     {:signal :dao.stream/blocked}
       the pool is empty, or every non-ended member answered blocked;
     {:signal :dao.stream/end}
       every member has ended;
     {:signal :dao.stream/gap, :member i, :cursor recovery}
       member i's position was evicted;
     {:signal k, :member i, :result read}
       k is :dao.stream/cursor-mismatch, :dao.stream/invalid-cursor, or
       :dao.stream/transport-error.

   gap and defect reports leave the member's cursor unchanged and move
   :next past the member, so the same condition is reported again on that
   member's next turn; nothing is auto-resynchronized. A defect carries the
   raw read under :result exactly as the step classified it — a transport
   that answered outside the contract is reported with its answer retained,
   never folded into a meaning nobody chose. Blocked and ended members
   never prevent later members from being checked, and a member that
   yielded a payload loses its turn, so a continuously ready member cannot
   starve another.

   The effect is materialize!, which answers ok or throws: :failed is
   unreachable, and a throwing effect propagates before any cursor moves,
   so the caller's state is untouched and the same payload is reprocessed
   from the same cursor once the backend succeeds."
  [handle state]
  (let [n (count (:members state))]
    (if (zero? n)
      {:state state, :signal :dao.stream/blocked}
      (let [effect (fn [payload]
                     {:dao.stream/outcome :dao.stream/ok
                      :address (materialize! handle payload)})]
        (loop [i (:next state)
               scanned 0
               state' state]
          (if (>= scanned n)
            {:state state'
             :signal (if (every? #(= :dao.stream/end (:status %))
                                 (:members state'))
                       :dao.stream/end
                       :dao.stream/blocked)}
            (let [member (nth (:members state') i)
                  r (observe/step (:stream member) (:cursor member) effect)]
              (case (:status r)
                :advance
                {:state (-> state'
                            (assoc-in [:members i :cursor] (:cursor r))
                            (assoc-in [:members i :status] :dao.stream/ok)
                            (assoc :next (mod (inc i) n)))
                 :signal :dao.stream/ok
                 :address (get-in r [:effect :address])}
                :retry
                (recur (mod (inc i) n)
                       (inc scanned)
                       (assoc-in state' [:members i :status] :dao.stream/blocked))
                :ended
                (recur (mod (inc i) n)
                       (inc scanned)
                       (assoc-in state' [:members i :status] :dao.stream/end))
                :gap
                {:state (-> state'
                            (assoc-in [:members i :status] :dao.stream/gap)
                            (assoc :next (mod (inc i) n)))
                 :signal :dao.stream/gap
                 :member i
                 :cursor (:recovery r)}
                :defect
                {:state (-> state'
                            (assoc-in [:members i :status] (:outcome r))
                            (assoc :next (mod (inc i) n)))
                 :signal (:outcome r)
                 :member i
                 :result (:read r)}
                :failed
                (throw (ex-info
                         "unreachable: the DaoJing effect answers ok or throws"
                         {:result r, :member i}))))))))))

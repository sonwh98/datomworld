(ns dao.jing
  "DaoJing: the content-addressed storage observer (docs/design/dao.jing.md).

   A content-store handle is plain data, not a protocol:
   {:put-content-fn f, :get-content-fn g, :close-fn c}. The backend effects
   are explicit functions: materialize! computes the content address solely
   from the payload, inserts idempotently, and returns the address only
   after the backend reports durability. get reads only :segment/sha256-...
   content addresses; arbitrary keys and mutable roots are outside DaoJing.

   The observer (observer-state / observe-step!) polls an explicit intake
   pool of dao.stream values and materializes every payload. Pool membership
   is supplied by the caller; cursors, statuses, and the scheduling index
   are ordinary immutable data. There are no atoms, globals, registration,
   or discovery, and the source stream never enters an address or a stored
   value."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [dao.stream :as ds]
            #?@(:cljs [[goog.crypt :as crypt] goog.crypt.Sha256])
            #?@(:cljd [["dart:convert" :as convert]])))


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

(defn- order-normalize
  "Normalize a value so equal values print identically: maps sort by printed
   key, sets sort by printed element, sequences recurse.

   Transitional: this exists only to make the print-based content hash
   deterministic and order-insensitive until the pinned, cross-platform
   canonical byte encoding lands (docs/design/dao.jing.md, Canonical
   encoding). It is NOT that canonical encoding."
  [v]
  (cond (map? v) (->> v
                      (map (fn [[k x]]
                             [(order-normalize k)
                              (order-normalize x)]))
                      ;; a pr-str-keyed sorted map prints its keys in a
                      ;; fixed order on every platform (array-map is not
                      ;; in ClojureDart)
                      (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))))
        (set? v) (list 'set (sort-by pr-str (map order-normalize v)))
        (sequential? v) (mapv order-normalize v)
        :else v))


(defn- order-normalized-print
  "Transitional encoder: pr-str over the order-normalized form. NOT the final
   canonical byte encoding; see order-normalize."
  [v]
  (pr-str (order-normalize v)))


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
  "SHA-256 of the order-normalized print of v. Total over any value.

   Transitional: order-normalized pr-str is the current encoder, NOT the
   pinned cross-platform canonical byte encoding the spec calls for
   (docs/design/dao.jing.md, Canonical encoding), which is still an open
   migration item. Content addresses are portable only between
   implementations sharing this exact print rule; when the canonical
   encoding lands, content-hash, segment-key, and every minted address
   change together."
  [v]
  (sha256 (order-normalized-print v)))


(defn segment-key
  "Mint the content-addressed key for an opaque payload, derived solely from
   the payload: :segment/sha256-<hash(payload)>. The algorithm prefix is
   load-bearing twice over: it names the hash function, and it keeps the
   keyword readable EDN — a name starting with a bare hex digit cannot
   survive print -> read, which poisons every EDN boundary content
   addresses cross."
  [v]
  (keyword "segment" (str "sha256-" (content-hash v))))


(defn segment-address?
  "True when x is a content address of the form :segment/sha256-<64 hex>.
   This is the only address class DaoJing reads or writes: arbitrary keys
   and mutable roots are outside DaoJing."
  [x]
  (and (keyword? x)
       (= "segment" (namespace x))
       (let [n (name x)]
         (and (str/starts-with? n "sha256-")
              (let [h (subs n 7)]
                (and (= 64 (count h)) (every? hex-digits-set h)))))))


(defn segment-hash
  "The content hash carried by a segment address. Total only over valid
   :segment/sha256-<64 lowercase hex> addresses; foreign namespaces,
   malformed hashes, non-hex characters, and wrong lengths throw."
  [k]
  (when-not (segment-address? k)
    (throw (ex-info "not a sha256 segment key" {:k k})))
  (subs (name k) 7))


;; =============================================================================
;; Content-store handle API — plain data, explicit backend effects
;; (docs/design/dao.jing.md, Materialization rule and Reads)
;; =============================================================================

(defn materialize!
  "Content-address payload and store it through the handle's backend.

   The address is derived solely from the payload via segment-key, and the
   backend effect :put-content-fn is invoked as (put-content-fn address
   payload). The backend must answer with one of:

     :inserted — the value is durably stored now;
     :present  — an equal value is already stored under that address.

   Any other answer is an invalid backend result and throws: DaoJing does
   not accept ambiguous truthiness. The address is returned only after the
   backend reports success.

   On :present the stored value is read back through :get-content-fn and
   verified. Equal content is idempotent and returns the same address.
   Unequal content at the same address is an integrity failure and throws
   loudly. Nothing is ever overwritten."
  [handle payload]
  (let [put (:put-content-fn handle)
        get-fn (:get-content-fn handle)]
    (when-not (fn? put)
      (throw (ex-info "dao.jing handle requires :put-content-fn"
                      {:handle handle})))
    (when-not (fn? get-fn)
      (throw (ex-info "dao.jing handle requires :get-content-fn"
                      {:handle handle})))
    (let [address (segment-key payload)
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
            (= stored payload) address
            :else
            (throw
              (ex-info
                "content collision: unequal values at the same content address"
                {:address address, :stored stored, :payload payload}))))
        (throw (ex-info
                 "invalid backend put result"
                 {:result result, :address address, :payload payload}))))))


(defn get
  "Retrieve the opaque value stored at a content address.

   Only :segment/sha256-<64 hex> content addresses are valid DaoJing reads;
   arbitrary keys and mutable roots are outside DaoJing and throw before the
   backend is consulted. Returns not-found when the address is absent."
  [handle address not-found]
  (when-not (segment-address? address)
    (throw (ex-info "dao.jing reads only :segment/sha256-... content addresses"
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
  "Construct the immutable observer state for an explicit intake pool.

   Returns plain data, no atoms or registration:

     {:members [{:stream <ref>, :cursor {:position 0}, :status :pending} ...]
      :next 0}

   :members has one entry per pool stream; each entry holds only the stream
   reference, its operational cursor (initialized to position 0), and its
   explicit status. :next is the fair round-robin index of the member the
   next observe-step! polls first. Member status is one of :pending (never
   polled), :ok, :blocked, :end, or :daostream/gap.

   Pool membership is supplied here; DaoJing performs no registration or
   discovery. The source stream is operational state only and never becomes
   part of any address or stored payload."
  [streams]
  {:members (mapv (fn [s] {:stream s, :cursor {:position 0}, :status :pending})
                  streams),
   :next 0})


(defn observe-step!
  "Poll the intake pool round-robin and process at most one payload.

   Starts polling at the member selected by (:next state) and walks the pool
   once, so every active member is checked within the call. Returns:

     {:state next-state, :signal :ok, :address address}
       a payload was materialized and that member's cursor advanced;
     {:state next-state, :signal :blocked}
       the pool is empty, or no member had anything to read;
     {:state next-state, :signal :end}
       every member has explicitly ended;
     {:state next-state, :signal :daostream/gap, :member i}
       member i's cursor is behind the retention boundary. The gap is
       returned immediately, its cursor is left unchanged, and it is never
       auto-resynchronized: resync is the caller's decision.

   On {:ok payload :cursor next-cursor} the payload is materialized before
   the member cursor advances; if materialization throws, the exception
   propagates and the caller-owned state is untouched. Blocked and ended
   members never prevent later members from being checked, and a member
   that produced a payload yields its turn, so a continuously ready member
   cannot starve another."
  [handle state]
  (let [{:keys [members next]} state
        n (count members)]
    (if (zero? n)
      {:state state, :signal :blocked}
      (loop [i next
             scanned 0
             state' state]
        (if (>= scanned n)
          (let [all-ended? (every? #(= :end (:status %)) (:members state'))]
            {:state state', :signal (if all-ended? :end :blocked)})
          (let [member (nth members i)]
            (if (= :end (:status member))
              (recur (mod (inc i) n) (inc scanned) state')
              (let [res (ds/next (:stream member) (:cursor member))]
                (cond
                  (map? res)
                  (if (and (contains? res :ok) (contains? res :cursor))
                    (let [address (materialize! handle (:ok res))]
                      {:state (-> state'
                                  (assoc-in [:members i :cursor]
                                            (:cursor res))
                                  (assoc-in [:members i :status] :ok)
                                  (assoc :next (mod (inc i) n))),
                       :signal :ok,
                       :address address})
                    (throw
                      (ex-info
                        "unexpected stream result: a successful read must carry both :ok and :cursor"
                        {:result res, :member i})))
                  (= res :blocked)
                  (recur (mod (inc i) n)
                         (inc scanned)
                         (assoc-in state' [:members i :status] :blocked))
                  (= res :end) (recur
                                 (mod (inc i) n)
                                 (inc scanned)
                                 (assoc-in state' [:members i :status] :end))
                  (= res :daostream/gap)
                  {:state (-> state'
                              (assoc-in [:members i :status] :daostream/gap)
                              (assoc :next (mod (inc i) n))),
                   :signal :daostream/gap,
                   :member i}
                  :else (throw (ex-info "unexpected stream signal"
                                        {:signal res, :member i})))))))))))

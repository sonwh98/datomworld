(ns dao.jing.dht
  "The DHT content-store backend for the dao.jing storage boundary
   (docs/design/dao.jing.dht.md): one caller-stepped interpreter between a
   raw datagram socket (docs/design/dao.stream.datagram.md) and the
   dao.jing.content request medium.

   (state composition) validates a composition and returns a plain map;
   (step state budget) is the only thing that advances it. No socket,
   thread, atom, promise, callback, scheduler or clock lives here: the
   composition supplies every handle, and time is the tick stream it
   wires. One owner per state drives it.

   Requests and answers are the dao.jing.content convention, exact, with
   one addition: {:jing/request r :jing/unacknowledged reason}. Everything
   DHT-specific goes on :facts as :dao.jing.dht/* maps. A write is
   acknowledged when the local insert has succeeded and its store message
   was handed to the socket toward ack-peers distinct fresh proven peers
   (section 4); a get reads :local first and otherwise looks the digest up
   and verifies what a peer hands it (section 4.5).

   The wire is section 7's, one CBOR value per datagram through
   dao.stream.cbor, with bounded chunk records for larger messages. The
   cookie protocol of section 8 uses host HMAC-SHA-256.

   (store-handle ...) is the portable dao.jing byte-store handle over the
   same composition: its put returns the local verdict at once and appends
   a replicate request; its get reads :local only."
  (:require #?@(:cljd [["dart:typed_data" :as typed]
                       ["package:crypto/crypto.dart" :as crypto]]
                :cljs [["node:crypto" :as node-crypto]])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.dht.kad :as kad]
            [dao.stream :as stream]
            [dao.stream.chunks :as chunks]
            [dao.stream.datagram :as datagram]
            [dao.stream.cbor :as wire]))


;; =============================================================================
;; Constants and defaults (sections 6 to 9)
;; =============================================================================

(def alpha
  "Lookup concurrency width: outstanding queries per lookup."
  3)


(def first-contact-bytes
  "The minimum length of a cookie-less request datagram: a constant of wire
   v1 (section 8)."
  256)


(def cookie-length
  "Bytes in a cookie."
  16)


(def id-length
  "Bytes in a node id and a lookup target."
  32)


(def max-find-peers
  "Peers per :find reply, at most."
  8)


(def ^:private max-safe-integer
  "The largest integer every host's number carries exactly."
  9007199254740991)


(def defaults
  "Section 9's composition defaults."
  {::ack-peers 2
   ::ack-ticks 5000
   ::get-ticks 5000
   ::query-ticks 500
   ::tries 3
   ::max-message-bytes 65536
   ::max-datagram 1200
   ::bind-host "127.0.0.1"
   ::max-partial-messages 64
   ::max-pending-writes 64
   ::max-pending-gets 64
   ::cookie-epoch-ticks 60000
   ::publish? false
   ::bootstrap []})


;; =============================================================================
;; Host bytes and hex ids
;; =============================================================================

(def ^:private hex-digits "0123456789abcdef")


(defn- byte-ints
  [bs]
  #?(:cljd (vec bs)
     :clj (mapv #(bit-and (int %) 0xff) bs)
     :cljs (vec (js/Array.from bs))))


(defn- ints->bytes
  [ints]
  #?(:cljd (typed/Uint8List.fromList ints)
     :clj (byte-array (map unchecked-byte ints))
     :cljs (js/Uint8Array.from (clj->js ints))))


(defn- byte-count
  [bs]
  #?(:cljd (.-length ^typed/Uint8List bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn- zero-bytes
  [n]
  #?(:cljd (typed/Uint8List n)
     :clj (byte-array n)
     :cljs (js/Uint8Array. n)))


(defn bytes->hex
  "Lowercase hex of host bytes: ids live as hex in state, raw on the wire."
  [bs]
  (apply str (map (fn [b]
                    (str (nth hex-digits (quot b 16))
                         (nth hex-digits (rem b 16))))
                  (byte-ints bs))))


(defn hex->bytes
  "Host bytes of an even-length lowercase hex string."
  [hex]
  (let [digit (fn [i]
                (let [c (nth hex i)]
                  (loop [d 0]
                    (if (= c (nth hex-digits d)) d (recur (inc d))))))]
    (ints->bytes (mapv (fn [i] (+ (* 16 (digit i)) (digit (inc i))))
                       (range 0 (count hex) 2)))))


(defn- bytes-of?
  "True when x is host bytes of exactly n bytes."
  [x n]
  (and (wire/byte-payload? x) (= n (byte-count x))))


(defn- node-id?
  [x]
  (and (string? x) (some? (re-matches #"[0-9a-f]{64}" x))))


;; =============================================================================
;; Section 8: cookies
;; =============================================================================

(defn- hmac-sha256
  [key data]
  #?(:cljd (typed/Uint8List.fromList
             (.-bytes (.convert (crypto/Hmac. crypto/sha256 key) data)))
     :clj (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")]
            (.init mac (javax.crypto.spec.SecretKeySpec. ^bytes key "HmacSHA256"))
            (.doFinal mac ^bytes data))
     :cljs (js/Uint8Array.from
             (.digest (.update (.createHmac node-crypto "sha256" key) data)))))


(defn cookie-for
  "First 16 bytes of HMAC(epoch-secret, observed address). Epoch secrets
   derive from the composition's root secret and rotate only with ticks."
  [secret epoch host port]
  (ints->bytes
    (take cookie-length
          (byte-ints (hmac-sha256 (hmac-sha256 secret (wire/encode epoch))
                                  (wire/encode [host port]))))))


(defn- epoch
  [state]
  (quot (or (:now state) 0) (::cookie-epoch-ticks state)))


(defn- valid-cookie?
  "True when c is the cookie for host and port under the current or the
   previous epoch. Verification recomputes; it holds no state."
  [state host port c]
  (and (bytes-of? c cookie-length)
       (let [e (epoch state)
             seen (bytes->hex c)]
         (or (= seen (bytes->hex (cookie-for (::secret state) e host port)))
             (= seen (bytes->hex (cookie-for (::secret state) (dec e) host port)))))))


;; =============================================================================
;; Section 7: the wire
;; =============================================================================

(defn decode-message
  "The DHT message a datagram carries, or nil: a datagram that does not
   decode to a map carrying :dao.jing.dht/v is not the DHT's."
  [bs]
  (try (let [m (wire/decode bs)]
         (when (and (map? m) (contains? m ::v)) m))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- padded
  "A cookie-less request, padded with zero bytes to at least
   first-contact-bytes."
  [m]
  (let [base (byte-count (wire/encode (assoc m :pad (zero-bytes 0))))]
    (assoc m :pad (zero-bytes (max 0 (- first-contact-bytes base))))))


(defn- store-message-size
  "The encoded size bound of the store message for address and bs: the
   largest query id and a full cookie, so the check never under-counts."
  [address bs]
  (byte-count (wire/encode {::v 1, :op :store, :q max-safe-integer,
                            :id (zero-bytes id-length),
                            :cookie (zero-bytes cookie-length),
                            :address address, :bytes bs})))


(defn- oversize?
  [max-message-bytes address bs]
  (> (store-message-size address bs) max-message-bytes))


(defn- send-bytes!
  [state host port bs]
  (:dao.stream/outcome
    (stream/append! (:datagrams state)
                    {:dao.stream.datagram/destination
                     {:dao.stream.datagram/host host
                      :dao.stream.datagram/port port}
                     :dao.stream.datagram/bytes
                     (jing/bytes->base64 bs)})))


(defn- send-datagram!
  "Refuse an overbound message before sending any datagram. A chunk run
   counts only if every raw writer append answered ok."
  [state host port m]
  (let [bs (wire/encode m)
        length (chunks/length bs)]
    (cond
      (not (datagram/ip-literal? host)) :dao.stream/transport-error
      (not= (str/includes? (::bind-host state) ":")
            (str/includes? host ":")) :dao.stream/transport-error
      (> length (::max-message-bytes state)) :dao.stream/transport-error
      (<= length (::max-datagram state)) (send-bytes! state host port bs)
      :else
      (let [frames (chunks/split bs (::max-datagram state)
                                 (fn [part parts piece]
                                   (cond-> {::v 1 :op :chunk :q (:q m)
                                            :dir (if (= :reply (:op m)) :reply :request)
                                            :part part :parts parts :bytes piece}
                                     (and (not= :reply (:op m)) (:cookie m))
                                     (assoc :cookie (:cookie m))))
                                 wire/encode)]
        (reduce (fn [_ frame]
                  (let [outcome (send-bytes! state host port (wire/encode frame))]
                    (if (= :dao.stream/ok outcome)
                      outcome
                      (reduced outcome))))
                :dao.stream/ok frames)))))


;; =============================================================================
;; Output buffers: answers and facts are appended in the step's last stage
;; =============================================================================

(defn- answer
  [state v]
  (update state :out-answers conj v))


(defn- fact
  [state v]
  (update state :out-facts conj v))


(defn- unacknowledged
  [state address reason peers local]
  (fact state {::fact ::unacknowledged, ::address address, ::reason reason,
               ::peers peers, ::local local}))


(defn- miss
  [state address reason]
  (fact state {::fact ::miss, ::address address, ::reason reason}))


(defn- not-found-answer
  [r]
  {:jing/request r, :jing/found? false, :jing/bytes nil})


;; =============================================================================
;; Routing entries (section 6)
;; =============================================================================

(defn routing-entries
  "Every proven routing entry {:id :host :port} the state holds."
  [state]
  (vec (mapcat val (sort-by key (:table state)))))


(defn- forget-address
  [table host port]
  (into {}
        (map (fn [[i bucket]]
               [i
                (into []
                      (remove #(and (= host (:host %)) (= port (:port %))))
                      bucket)]))
        table))


(defn- prove
  "Record a proven entry: the observed address, never a claimed one. An id
   reappearing from another address replaces its entry, and an address
   reappearing with another id drops the old id."
  [state id host port]
  (update state
          :table
          (fn [t]
            (kad/observe (forget-address t host port)
                         (::id state)
                         {:id id, :host host, :port port}))))


(defn- fresh?
  "A peer is fresh when a matched reply from it arrived within the last
   cookie-epoch-ticks, by this node's own ticks."
  [state pkey]
  (let [seen (get-in state [:seen pkey])
        now (:now state)]
    (and (some? seen)
         (some? now)
         (< (- now seen) (::cookie-epoch-ticks state)))))


;; =============================================================================
;; Queries
;; =============================================================================

(defn- mint-q
  [state host port]
  (loop []
    (let [q (rand-int 2147483647)]
      (if (contains? (:queries state) [host port q]) (recur) q))))


(defn- op-record
  [state owner]
  (get-in state owner))


(defn- query-message
  "The request a query sends: its op's fields, the peer's cookie when this
   node holds one, and padding otherwise (only a :ping is ever sent
   without a cookie)."
  [state {:keys [op q owner]} cookie]
  (let [record (op-record state owner)
        m (cond-> {::v 1, :op op, :q q, :id (hex->bytes (::id state))}
            (= :find op) (assoc :target (hex->bytes (:target record)))
            (= :store op) (assoc :address (:address record)
                                 :bytes ((:get-bytes-fn (:local state))
                                         (:address record)
                                         nil))
            (= :fetch op) (assoc :address (:address record)))]
    (if cookie (assoc m :cookie cookie) (padded m))))


(defn- add-query
  "Record a pending query under its key and in its owner's index entry, so
   an operation visits only its own queries."
  [state qkey query]
  (-> state
      (assoc-in [:queries qkey] query)
      (update-in [:query-index (:owner query)] (fnil conj #{}) qkey)))


(defn- remove-query
  "Forget a pending query and its index entry; an owner left with no
   queries leaves the index."
  [state qkey]
  (if-let [owner (get-in state [:queries qkey :owner])]
    (let [left (disj (get-in state [:query-index owner]) qkey)]
      (-> state
          (update :queries dissoc qkey)
          (update :query-index
                  #(if (empty? left) (dissoc % owner) (assoc % owner left)))))
    state))


(defn- issue
  "Send one query for owner to peer pkey. Returns [state' outcome]: with
   no cookie held the query is a padded :ping whatever the purpose; a
   :store additionally needs a fresh peer. A query the socket refused is
   not recorded."
  [state owner [host port :as pkey] purpose]
  (let [cookie (get-in state [:cookies pkey])
        op (case purpose
             :lookup (if cookie :find :ping)
             :fetch (if cookie :fetch :ping)
             :store (if (and cookie (fresh? state pkey)) :store :ping))
        q (mint-q state host port)
        query {:op op, :q q, :owner owner, :pkey pkey, :purpose purpose,
               :sent (:now state), :resent? false}
        outcome (send-datagram! state host port
                                (query-message state query cookie))]
    (if (= :dao.stream/ok outcome)
      [(add-query state [host port q] query) outcome]
      [state outcome])))


(defn- owner-queries
  "Owner's pending queries, visited through the index alone."
  [state owner]
  (keep #(get (:queries state) %) (get-in state [:query-index owner])))


(defn- drop-owner
  "Forget an operation and every query it owns."
  [state owner]
  (-> (reduce remove-query state (get-in state [:query-index owner]))
      (update (first owner) dissoc (second owner))))


;; =============================================================================
;; Lookup as state (section 6)
;; =============================================================================

(defn- new-op
  "A pending operation's lookup state: target, candidates (the table's
   nearest entries and the bootstrap contacts), answered and dead sets,
   and per-peer failed tries."
  [state kind address]
  (let [target (jing/segment-hash address)
        known (into {}
                    (map (fn [e] [[(:host e) (:port e)] e]))
                    (kad/nearest (:table state) target kad/k))
        boot (into {}
                   (map-indexed (fn [i {:keys [host port]}]
                                  [[host port]
                                   {:host host, :port port, :rank i}]))
                   (::bootstrap state))]
    {:kind kind
     :address address
     :target target
     :started (:now state)
     :cands (merge boot known)
     :answered #{}
     :dead #{}
     :failures {}}))


(defn- add-cands
  "Merge the table's nearest entries into an op's candidates."
  [state owner]
  (let [record (op-record state owner)]
    (update-in state
               (conj owner :cands)
               (fn [cands]
                 (reduce (fn [cands e]
                           (let [pkey [(:host e) (:port e)]]
                             (if (:id (get cands pkey))
                               cands
                               (assoc cands pkey e))))
                         cands
                         (kad/nearest (:table state) (:target record) kad/k))))))


(defn- window
  "The k nearest live candidates of an op, nearest first: id-bearing
   candidates by XOR distance, then unproven contacts in their order."
  [record]
  (->> (:cands record)
       (remove (fn [[pkey _]] (contains? (:dead record) pkey)))
       (sort-by (fn [[_ c]]
                  (if (:id c)
                    [0 (kad/distance (:id c) (:target record))]
                    [1 (or (:rank c) 0)])))
       (take kad/k)
       (map first)))


(defn- lookup-done?
  "A lookup ends when k peers have answered or its candidates are
   exhausted, with nothing of it outstanding."
  [state owner]
  (let [record (op-record state owner)]
    (and (not-any? #(= :lookup (:purpose %)) (owner-queries state owner))
         (or (>= (count (:answered record)) kad/k)
             (every? #(contains? (:answered record) %) (window record))))))


(defn- issue-lookup
  "Ask the nearest unasked candidates, at most alpha outstanding."
  [state owner]
  (let [record (op-record state owner)
        mine (owner-queries state owner)
        busy (set (map :pkey mine))
        outstanding (count (filter #(= :lookup (:purpose %)) mine))
        todo (->> (window record)
                  (remove #(contains? (:answered record) %))
                  (remove busy)
                  (take (max 0 (- alpha outstanding))))]
    (if (>= (count (:answered record)) kad/k)
      state
      (reduce (fn [state pkey]
                (let [[state outcome] (issue state owner pkey :lookup)]
                  (if (= :dao.stream/ok outcome)
                    state
                    (update-in state (conj owner :dead) conj pkey))))
              state
              todo))))


(defn- targets
  "The proven entries nearest an op's target that it has not acted on,
   are live for it, and have no query of it outstanding."
  [state owner acted]
  (let [record (op-record state owner)
        busy (set (map :pkey (owner-queries state owner)))]
    (->> (kad/nearest (:table state) (:target record) kad/k)
         (map (fn [e] [(:host e) (:port e)]))
         (remove #(contains? acted %))
         (remove #(contains? (:dead record) %))
         (remove busy))))


;; =============================================================================
;; Section 4: pending writes
;; =============================================================================

(defn- acknowledge
  [state owner]
  (let [{:keys [address ids sent]} (op-record state owner)]
    (-> (reduce (fn [state [r verdict]]
                  (answer state {:jing/request r, :jing/result verdict}))
                state
                ids)
        (fact {::fact ::sent, ::address address, ::peers (count sent)})
        (assoc-in (conj owner :acked?) true)
        (assoc-in (conj owner :ids) []))))


(defn- give-up-write
  [state owner]
  (let [{:keys [address ids sent local]} (op-record state owner)]
    (-> (reduce (fn [state [r _]]
                  (answer state
                          {:jing/request r,
                           :jing/unacknowledged ::too-few-peers}))
                state
                ids)
        (unacknowledged address ::too-few-peers (count sent) local)
        (drop-owner owner))))


(defn- replicated
  [state owner]
  (let [{:keys [address sent confirmed]} (op-record state owner)]
    (-> state
        (fact {::fact ::replicated, ::address address,
               ::peers (count sent), ::confirmed confirmed})
        (drop-owner owner))))


(defn- send-stores
  "Send the store toward fresh targets until `limit` peers are counted,
   and ping stale ones, at most alpha pings outstanding. A peer counts
   only when the socket answered :dao.stream/ok."
  [state owner limit]
  (loop [state state
         todo (targets state owner (:sent (op-record state owner)))]
    (let [record (op-record state owner)
          pings (count (filter #(and (= :store (:purpose %))
                                     (= :ping (:op %)))
                               (owner-queries state owner)))]
      (if (or (empty? todo) (>= (count (:sent record)) limit))
        state
        (let [pkey (first todo)]
          (if (fresh? state pkey)
            (let [[state outcome] (issue state owner pkey :store)]
              (recur (if (= :dao.stream/ok outcome)
                       (update-in state (conj owner :sent) conj pkey)
                       (update-in state (conj owner :dead) conj pkey))
                     (rest todo)))
            (recur (if (< pings alpha)
                     (first (issue state owner pkey :store))
                     state)
                   (rest todo))))))))


(defn- replication-done?
  [state owner]
  (let [sent (:sent (op-record state owner))]
    (and (lookup-done? state owner)
         (empty? (owner-queries state owner))
         (or (>= (count sent) kad/k)
             (empty? (targets state owner sent))))))


(defn- advance-write
  "Section 4.3: send toward known fresh peers until ack-peers are counted,
   look up when too few are known, acknowledge the moment the count is
   reached, then replicate best effort; not acknowledged at ack-ticks."
  [state address]
  (let [owner [:writes address]
        state (update-in state (conj owner :started) #(or % (:now state)))
        {:keys [started acked?]} (op-record state owner)
        now (:now state)
        late? (and now started (>= (- now started) (::ack-ticks state)))]
    (cond
      (and late? (not acked?)) (give-up-write state owner)
      late? (replicated state owner)

      (not acked?)
      (let [state (-> (add-cands state owner)
                      (send-stores owner (::ack-peers state)))]
        (if (>= (count (:sent (op-record state owner))) (::ack-peers state))
          (acknowledge state owner)
          (issue-lookup state owner)))

      :else
      (let [state (-> (add-cands state owner)
                      (send-stores owner kad/k)
                      (issue-lookup owner))]
        (if (replication-done? state owner)
          (replicated state owner)
          state)))))


;; =============================================================================
;; Section 4.5: pending gets
;; =============================================================================

(defn- finish-get
  [state owner found-bytes reason]
  (let [{:keys [address ids]} (op-record state owner)
        state (reduce (fn [state r]
                        (answer state
                                (if found-bytes
                                  {:jing/request r, :jing/found? true,
                                   :jing/bytes (jing/bytes->base64
                                                 found-bytes)}
                                  (not-found-answer r))))
                      state
                      ids)]
    (cond-> (drop-owner state owner)
      reason (miss address reason))))


(defn- advance-get
  "Section 4.5: look the digest up, fetch from proven candidates, at most
   alpha outstanding; not found when exhausted or at get-ticks."
  [state address]
  (let [owner [:gets address]
        state (update-in state (conj owner :started) #(or % (:now state)))
        {:keys [started]} (op-record state owner)
        now (:now state)]
    (if (and now started (>= (- now started) (::get-ticks state)))
      (finish-get state owner nil ::deadline)
      (let [state (add-cands state owner)
            fetching (count (filter #(= :fetch (:purpose %))
                                    (owner-queries state owner)))
            todo (take (max 0 (- alpha fetching))
                       (targets state owner (:fetched (op-record state owner))))
            state (reduce (fn [state pkey]
                            (let [[state outcome]
                                  (issue state owner pkey :fetch)]
                              (if (= :dao.stream/ok outcome)
                                state
                                (update-in state (conj owner :dead)
                                           conj pkey))))
                          state
                          todo)
            state (issue-lookup state owner)]
        (if (and (lookup-done? state owner)
                 (empty? (owner-queries state owner))
                 (empty? (targets state owner
                                  (:fetched (op-record state owner)))))
          (finish-get state owner nil ::exhausted)
          state)))))


;; =============================================================================
;; Stage 2: traffic
;; =============================================================================

(defn- safe-q?
  [q]
  (and (integer? q) (<= 0 q max-safe-integer)))


(defn- reply!
  "Send a reply to the observed source, carrying a cookie for it."
  [state host port q body]
  (send-datagram! state host port
                  (merge {::v 1, :op :reply, :q q,
                          :id (hex->bytes (::id state)),
                          :cookie (cookie-for (::secret state) (epoch state) host port)}
                         body))
  state)


(defn- verified-bytes
  "The canonical bytes a peer handed over for address, or nil: strict
   decode, digest against the address, canonical decode -- the one ingress
   check, dao.jing/accept-bytes!."
  [address bs]
  (try (jing/accept-bytes! address (jing/bytes->base64 bs))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- serve-body
  "The reply body for a request that passed the gate, or nil to drop it.
   With publish? false a :store is refused and a :fetch is not found."
  [state host port m]
  (let [address (:address m)
        local (:local state)]
    (case (:op m)
      :ping {:ok true}

      :find
      (when (bytes-of? (:target m) id-length)
        {:peers (->> (kad/nearest (:table state)
                                  (bytes->hex (:target m))
                                  (inc max-find-peers))
                     (remove #(and (= host (:host %)) (= port (:port %))))
                     (take max-find-peers)
                     (mapv (fn [e]
                             [(hex->bytes (:id e)) (:host e) (:port e)])))})

      :store
      (when (and (jing/segment-address? address)
                 (wire/byte-payload? (:bytes m)))
        {:ok (boolean
               (when (::publish? state)
                 (when-let [bs (verified-bytes address (:bytes m))]
                   (when (or (contains? (:inbound-addresses state) address)
                             (<= (+ (:inbound-bytes state) (byte-count bs))
                                 (::max-inbound-bytes state)))
                     (contains? #{:inserted :present}
                                ((:put-bytes-fn local) address bs))))))})

      :fetch
      (when (jing/segment-address? address)
        (let [bs (when (::publish? state)
                   ((:get-bytes-fn local) address nil))]
          (if bs {:found true, :bytes bs} {:found false})))

      nil)))


(defn- on-request
  "The gate: a request without a valid cookie causes no work, and its only
   possible response is the need-cookie reply, sent only when it encodes
   no longer than the datagram that caused it."
  [state host port m size]
  (let [q (:q m)]
    (cond
      (not (and (safe-q? q) (bytes-of? (:id m) id-length))) state

      (not (valid-cookie? state host port (:cookie m)))
      (let [need {::v 1, :op :reply, :q q, :id (hex->bytes (::id state)),
                  :cookie (cookie-for (::secret state) (epoch state) host port),
                  :need-cookie true}]
        (when (<= (byte-count (wire/encode need)) size)
          (send-datagram! state host port need))
        state)

      :else
      (let [state (prove state (bytes->hex (:id m)) host port)
            body (serve-body state host port m)
            state (if (and (= :store (:op m)) (true? (:ok body))
                           (not (contains? (:inbound-addresses state) (:address m))))
                    (-> state
                        (update :inbound-bytes + (byte-count (:bytes m)))
                        (update :inbound-addresses conj (:address m)))
                    state)]
        (if body (reply! state host port q body) state)))))


(defn- hint
  "Record :find reply peers as candidates, never entries."
  [state owner peers]
  (reduce (fn [state p]
            (let [[id h pt] (when (vector? p) p)]
              (if (and (bytes-of? id id-length)
                       (datagram/ip-literal? h)
                       (integer? pt)
                       (not= (bytes->hex id) (::id state))
                       (not (get-in state (conj owner :cands [h pt]))))
                (assoc-in state
                          (conj owner :cands [h pt])
                          {:id (bytes->hex id), :host h, :port pt})
                state)))
          state
          (take max-find-peers (when (sequential? peers) peers))))


(defn- route-reply
  "Route a matched, full reply to the operation that owns its query."
  [state query m]
  (let [owner (:owner query)
        pkey (:pkey query)]
    (if-not (op-record state owner)
      state
      (case (:op query)
        :find (-> state
                  (update-in (conj owner :answered) conj pkey)
                  (hint owner (:peers m)))
        :store (if (true? (:ok m))
                 (update-in state (conj owner :confirmed) inc)
                 state)
        :fetch (let [address (:address (op-record state owner))
                     bs (when (and (true? (:found m))
                                   (wire/byte-payload? (:bytes m)))
                          (verified-bytes address (:bytes m)))]
                 (if bs
                   (let [verdict ((:put-bytes-fn (:local state)) address bs)]
                     (when-not (#{:inserted :present} verdict)
                       (throw (ex-info "dao.jing.dht: invalid local put result"
                                       {:address address, :result verdict})))
                     (finish-get state owner bs nil))
                   (update-in state (conj owner :fetched) conj pkey)))
        state))))


(defn- on-reply
  "A reply is matched by [observed source, :q] against a pending query sent
   to that address; anything else is dropped. A reply arriving after its
   query's deadline is dropped too: the query stays pending, and its
   owner's own advance records the failed try, so no verdict depends on
   which operations a budgeted step reached. A need-cookie reply's cookie
   rides one re-send, without consuming a try; a full reply proves and
   freshens the peer."
  [state host port m]
  (let [qkey [host port (:q m)]
        query (get-in state [:queries qkey])
        pkey [host port]]
    (cond
      (not (and query
                (bytes-of? (:id m) id-length)
                (bytes-of? (:cookie m) cookie-length)))
      state

      (let [sent (:sent query)
            now (:now state)]
        (and sent now (>= (- now sent) (::query-ticks state))))
      state

      ;; the need-cookie reply's cookie is untrusted: it rides the one
      ;; resend and is kept only once a full reply carries it
      (true? (:need-cookie m))
      (let [owner (:owner query)]
        (cond
          (:resent? query) state
          (nil? (op-record state owner)) (remove-query state qkey)
          :else
          (let [query (assoc query :resent? true :sent (:now state))
                outcome (send-datagram! state host port
                                        (query-message state query
                                                       (:cookie m)))]
            (if (= :dao.stream/ok outcome)
              (assoc-in state [:queries qkey] query)
              ;; a refused resend is a failed send, as in issue
              (-> state
                  (remove-query qkey)
                  (update-in (conj owner :dead) conj pkey))))))

      :else
      (-> state
          (remove-query qkey)
          (prove (bytes->hex (:id m)) host port)
          (assoc-in [:cookies pkey] (:cookie m))
          (assoc-in [:seen pkey] (:now state))
          (route-reply query m)))))


(defn- on-chunk
  [state host port m size]
  (let [{:keys [q dir part parts bytes]} m
        pending? (contains? (:queries state) [host port q])
        request? (= :request dir)
        need {::v 1 :op :reply :q q :id (hex->bytes (::id state))
              :cookie (cookie-for (::secret state) (epoch state) host port)
              :need-cookie true}]
    (cond
      (not (and (safe-q? q) (#{:request :reply} dir)
                (wire/byte-payload? bytes))) state
      (and request? (not (valid-cookie? state host port (:cookie m))))
      (do (when (<= (chunks/length (wire/encode need)) size)
            (send-datagram! state host port need))
          state)
      (and (= :reply dir) (not pending?)) state
      :else
      (let [key [host port dir q]
            [next-state payload]
            (chunks/absorb state key part parts bytes
                           (::max-message-bytes state)
                           (::max-partial-messages state))
            source-keys (filterv (fn [[h p _ _]]
                                   (and (= h host) (= p port)))
                                 (:partial-order next-state))
            share (max 1 (quot (::max-partial-messages state) 4))
            evicted (take (max 0 (- (count source-keys) share)) source-keys)
            next-state (if (seq evicted)
                         (-> next-state
                             (update :partial #(apply dissoc % evicted))
                             (update :partial-order
                                     #(vec (remove (set evicted) %))))
                         next-state)
            next-state (if (and (not (contains? (:partial state) key))
                                (contains? (:partial next-state) key))
                         (assoc-in next-state [:partial key :created] (:now state))
                         next-state)
            whole (when payload (decode-message payload))]
        (if (and whole (= 1 (::v whole)) (= q (:q whole))
                 (if request?
                   (#{:ping :find :store :fetch} (:op whole))
                   (= :reply (:op whole))))
          (if request?
            (on-request next-state host port whole (chunks/length payload))
            (on-reply next-state host port whole))
          next-state)))))


(defn- on-traffic
  [state v]
  (let [source (:dao.stream.datagram/source v)
        host (:dao.stream.datagram/host source)
        port (:dao.stream.datagram/port source)
        b64 (:dao.stream.datagram/bytes v)
        bs (when (and (string? host) (integer? port) (string? b64))
             (try (jing/base64->bytes b64)
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _
                    nil)))
        m (when bs (decode-message bs))]
    (if (and m (= 1 (get m ::v)))
      (case (:op m)
        :reply (on-reply state host port m)
        (:ping :find :store :fetch)
        (on-request state host port m (byte-count bs))
        :chunk (on-chunk state host port m (byte-count bs))
        state)
      state)))


;; =============================================================================
;; Stage 3: requests
;; =============================================================================

(defn- get-request?
  [v]
  (and (map? v)
       (= #{:jing/request :jing/get} (set (keys v)))
       (jing/segment-address? (get v :jing/get))))


(defn- put-request?
  [v]
  (and (map? v)
       (= #{:jing/request :jing/put :jing/bytes} (set (keys v)))
       (jing/segment-address? (get v :jing/put))))


(defn- replicate-request?
  [v]
  (and (map? v)
       (= #{::replicate} (set (keys v)))
       (jing/segment-address? (get v ::replicate))))


(def ^:private absent
  "The local-read sentinel: one opaque host object, never a keyword."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(defn- solo?
  [state]
  (nil? (:datagrams state)))


(defn- write-refusal
  "The at-once reason a write cannot become pending, or nil."
  [state address]
  (cond
    (solo? state) ::solo
    (not (::publish? state)) ::unpublished
    (and (not (contains? (:writes state) address))
         (>= (count (:writes state)) (::max-pending-writes state)))
    ::busy))


(defn- join-write
  "Create or join the one pending write for address; r (optional) waits on
   it with its local verdict. A write already acknowledged answers r at
   once."
  [state address r verdict]
  (let [record (or (get-in state [:writes address])
                   (assoc (new-op state :write address)
                          :ids [] :sent #{} :confirmed 0 :acked? false
                          :local verdict))]
    (cond
      (nil? r) (assoc-in state [:writes address] record)
      (:acked? record) (-> (assoc-in state [:writes address] record)
                           (answer {:jing/request r, :jing/result verdict}))
      :else (assoc-in state [:writes address]
                      (update record :ids conj [r verdict])))))


(defn- on-put
  [state v]
  (let [r (get v :jing/request)
        address (get v :jing/put)
        bs (try (jing/accept-bytes! address (get v :jing/bytes))
                (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))]
    (cond
      ;; the one ingress check refused it: dropped, as serve-step does
      (nil? bs) state

      (oversize? (::max-message-bytes state) address bs)
      (-> (answer state {:jing/request r, :jing/unacknowledged ::oversize})
          (unacknowledged address ::oversize 0 nil))

      :else
      (let [verdict ((:put-bytes-fn (:local state)) address bs)]
        (when-not (#{:inserted :present} verdict)
          (throw (ex-info "dao.jing.dht: invalid local put result"
                          {:address address, :result verdict})))
        (if-let [reason (write-refusal state address)]
          (-> (answer state {:jing/request r, :jing/unacknowledged reason})
              (unacknowledged address reason 0 verdict))
          (join-write state address r verdict))))))


(defn- on-replicate
  [state address]
  (let [bs ((:get-bytes-fn (:local state)) address absent)
        reason (cond
                 (solo? state) ::solo
                 (not (::publish? state)) ::unpublished
                 (identical? absent bs) ::absent
                 (oversize? (::max-message-bytes state) address bs) ::oversize
                 :else (write-refusal state address))]
    (if reason
      (unacknowledged state address reason 0 nil)
      (join-write state address nil nil))))


(defn- on-get
  [state v]
  (let [r (get v :jing/request)
        address (get v :jing/get)
        bs ((:get-bytes-fn (:local state)) address absent)]
    (cond
      (not (identical? absent bs))
      (answer state {:jing/request r, :jing/found? true,
                     :jing/bytes (jing/bytes->base64 bs)})

      (solo? state)
      (-> (answer state (not-found-answer r)) (miss address ::solo))

      (contains? (:gets state) address)
      (update-in state [:gets address :ids] conj r)

      (>= (count (:gets state)) (::max-pending-gets state))
      (-> (answer state (not-found-answer r)) (miss address ::busy))

      :else
      (assoc-in state [:gets address]
                (assoc (new-op state :get address) :ids [r] :fetched #{})))))


(defn- on-request-value
  [state v]
  (cond
    (get-request? v) (on-get state v)
    (put-request? v) (on-put state v)
    (replicate-request? v) (on-replicate state (get v ::replicate))
    ;; a malformed request is dropped without answer
    :else state))


;; =============================================================================
;; Stage 1 and the stream reads
;; =============================================================================

(defn- on-tick
  "Time as data: the newest dao.lease tick reading is now."
  [state v]
  (let [n (when (and (map? v) (= :dao.lease/tick (:dao.lease/event v)))
            (:dao.lease/reading v))]
    (if (number? n)
      (let [state (update state :now #(if (and % (> % n)) % n))
            expired (->> (:partial state)
                         (keep (fn [[key held]]
                                 (when (and (:created held)
                                            (>= (- (:now state) (:created held))
                                                (::query-ticks state))) key)))
                         set)]
        (if (seq expired)
          (-> state
              (update :partial #(apply dissoc % expired))
              (update :partial-order #(vec (remove expired %))))
          state))
      state)))


(defn- drain
  "Read `reader-key` from its cursor to blocked, at most budget values,
   folding each through f. A gap adopts the recovery cursor and says so."
  [state reader-key cursor-key stream-name budget f]
  (loop [state state
         left budget]
    (let [reader (get state reader-key)
          cursor (get state cursor-key)]
      (cond
        (contains? stream/standard-anchors cursor)
        (let [r (stream/cursor reader cursor)]
          (if (= :dao.stream/ok (:dao.stream/outcome r))
            (recur (assoc state cursor-key (:dao.stream/cursor r)) left)
            state))

        (not (pos? left)) state

        :else
        (let [r (stream/next reader cursor)
              outcome (:dao.stream/outcome r)]
          (cond
            (= :dao.stream/ok outcome)
            (recur (f (assoc state cursor-key (:dao.stream/cursor r))
                      (:dao.stream/value r))
                   (dec left))

            (= :dao.stream/gap outcome)
            (recur (-> (assoc state cursor-key (:dao.stream/cursor r))
                       (fact {::fact ::gap, ::stream stream-name}))
                   (dec left))

            :else state))))))


;; =============================================================================
;; Stage 4: advance pending work
;; =============================================================================

(defn- expire-queries
  "A query past query-ticks is a failed try: the peer's cookie is
   discarded (the query may have been dropped in silence), so the next try
   is a padded :ping; a peer out of tries is dead for that operation and
   leaves the table. A query sent before the first tick starts its clock
   at the first reading. Only owner's queries are expired: expiry is part of
   advancing that one operation, so it shares the step's budget."
  [state owner]
  (let [now (:now state)]
    (reduce
      (fn [state [qkey {:keys [sent pkey]}]]
        (cond
          (nil? sent) (assoc-in state [:queries qkey :sent] now)
          (< (- now sent) (::query-ticks state)) state
          :else
          (let [state (-> state
                          (remove-query qkey)
                          (update :cookies dissoc pkey)
                          (update-in (conj owner :failures pkey) (fnil inc 0)))]
            (if (>= (get-in state (conj owner :failures pkey)) (::tries state))
              (-> state
                  (update-in (conj owner :dead) conj pkey)
                  (update :table forget-address (first pkey) (second pkey)))
              state))))
      state
      (if now
        (keep (fn [qkey]
                (when-let [query (get (:queries state) qkey)] [qkey query]))
              (get-in state [:query-index owner]))
        []))))


(defn- advance
  "Advance at most budget pending operations, round robin: the operations
   are taken in order after :advance-cursor (the last one advanced),
   wrapping, and the cursor moves to the last one taken. Each taken
   operation first expires its own queries, then advances."
  [state budget]
  (let [owners (vec (sort (concat (map (fn [a] [:writes a])
                                       (keys (:writes state)))
                                  (map (fn [a] [:gets a])
                                       (keys (:gets state))))))
        after (:advance-cursor state)
        start (or (first (keep-indexed (fn [i o]
                                         (when (or (nil? after)
                                                   (pos? (compare o after)))
                                           i))
                                       owners))
                  0)
        n (count owners)
        taken (mapv #(nth owners (mod (+ start %) n))
                    (range (min (max 0 budget) n)))]
    (reduce (fn [state [kind address :as owner]]
              (cond-> (assoc state :advance-cursor owner)
                (get-in state owner)
                (as-> s (let [s (expire-queries s owner)]
                          (if (= :writes kind)
                            (advance-write s address)
                            (advance-get s address))))))
            state
            taken)))


;; =============================================================================
;; Stage 5 and the public surface
;; =============================================================================

(defn- flush-out
  [state]
  (doseq [v (:out-answers state)] (stream/append! (:answers state) v))
  (doseq [v (:out-facts state)] (stream/append! (:facts state) v))
  (assoc state :out-answers [] :out-facts []))


(defn- defect
  "A composition defect: the error, returned for the caller to throw."
  [message data]
  (ex-info (str "dao.jing.dht: " message) data))


(defn state
  "Validate a composition (section 2) and return the DHT's state: a plain
   map of its handles, cursors, limits, routing table and pending tables.
   Throws on a composition defect; performs no stream operation."
  [composition]
  (let [c (merge defaults composition)
        {:keys [local requests answers facts ticks traffic datagrams]} c
        ack-peers (::ack-peers c)]
    (when-not (and (ifn? (:put-bytes-fn local)) (ifn? (:get-bytes-fn local)))
      (throw (defect "composition needs a :local byte-store handle"
               {:local local})))
    (when-not (and requests (stream/reader? requests))
      (throw (defect "composition needs a :requests reader" {})))
    (when-not (and answers (stream/writer? answers))
      (throw (defect "composition needs an :answers writer" {})))
    (when-not (and facts (stream/writer? facts))
      (throw (defect "composition needs a :facts writer" {})))
    (when-not (and ticks (stream/reader? ticks))
      (throw (defect "composition needs a :ticks reader" {})))
    (when-not (= (some? traffic) (some? datagrams))
      (throw (defect ":traffic and :datagrams are composed together or not"
               {})))
    (when (and traffic
               (not (and (stream/reader? traffic) (stream/writer? datagrams))))
      (throw (defect ":traffic must be a reader and :datagrams a writer" {})))
    (when (and traffic
               (not (and (wire/byte-payload? (::secret c))
                         (<= 32 (byte-count (::secret c))))))
      (throw (defect "socket composition needs a secret of at least 32 bytes" {})))
    (when (and traffic
               (not (and (integer? (::max-inbound-bytes c))
                         (<= 0 (::max-inbound-bytes c)))))
      (throw (defect "socket composition needs a nonnegative max-inbound-bytes" {})))
    (when-not (node-id? (::id c))
      (throw (defect "the node id must be 64 lowercase hex characters"
               {:id (::id c)})))
    (when-not (datagram/ip-literal? (::bind-host c))
      (throw (defect "bind-host must be an IP literal"
               {:bind-host (::bind-host c)})))
    (when-not (and (integer? ack-peers) (<= 2 ack-peers kad/k))
      (throw (defect "ack-peers must be an integer from 2 to k"
               {:ack-peers ack-peers, :k kad/k})))
    (merge c
           {:requests-cursor (get c :requests-cursor :dao.stream/oldest)
            :ticks-cursor (get c :ticks-cursor :dao.stream/oldest)
            :traffic-cursor (get c :traffic-cursor :dao.stream/oldest)
            :now nil
            :table {}
            :cookies {}
            :seen {}
            :queries {}
            :query-index {}
            :partial {}
            :partial-order []
            :inbound-bytes 0
            :inbound-addresses #{}
            :writes {}
            :gets {}
            :advance-cursor nil
            :out-answers []
            :out-facts []})))


(defn step
  "The only thing that advances the DHT, in section 2's order, each stage
   bounded by budget: drain ticks; read traffic; read requests; advance
   pending work; append answers and facts. Returns the successor state.
   This is an interpreter step -- it performs stream operations -- under
   a single-owner precondition: one caller, one state thread."
  [state budget]
  (-> state
      (drain :ticks :ticks-cursor :ticks budget on-tick)
      (cond-> (:traffic state)
        (drain :traffic :traffic-cursor :traffic budget on-traffic))
      (drain :requests :requests-cursor :requests budget on-request-value)
      (cond-> (not (solo? state)) (advance budget))
      flush-out))


;; =============================================================================
;; Section 5.1: the byte-store handle
;; =============================================================================

(defn store-handle
  "The plain-data dao.jing byte-store handle over a DHT composition's
   :local and :requests: put-bytes-fn validates, refuses an oversize store
   message, inserts into :local, appends {:dao.jing.dht/replicate address}
   and returns the local verdict at once -- never the acknowledgement,
   which is the :dao.jing.dht/sent fact. get-bytes-fn reads :local only
   and never touches the network; close-fn closes :local."
  [{:keys [local requests max-message-bytes]}]
  (when-not (and (ifn? (:put-bytes-fn local)) (ifn? (:get-bytes-fn local)))
    (throw (defect "store-handle needs a :local byte-store handle"
             {:local local})))
  (when-not (and requests (stream/writer? requests))
    (throw (defect "store-handle needs a :requests writer" {})))
  (let [limit (or max-message-bytes (::max-message-bytes defaults))]
    {:local local
     :requests requests
     :put-bytes-fn
     (fn [address bs]
       (when-not (jing/segment-bytes-match? address bs)
         (throw (defect "content address does not match the bytes"
                  {:address address})))
       (when (oversize? limit address bs)
         (throw (defect "the store message exceeds max-message-bytes"
                  {:address address, :reason ::oversize,
                   :max-message-bytes limit})))
       (let [verdict ((:put-bytes-fn local) address bs)]
         (stream/append! requests {::replicate address})
         verdict))
     :get-bytes-fn (fn [address not-found]
                     ((:get-bytes-fn local) address not-found))
     :close-fn (fn [] (jing/close! local))}))

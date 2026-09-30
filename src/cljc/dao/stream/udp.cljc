(ns dao.stream.udp
  "The UDP channel of dao.stream.remote.md (3.2), rebuilt on the raw
   datagram layer (docs/design/dao.stream.datagram.md 7): the WebSocket
   deposit model over a socket that retains nothing, with the raw layer
   beneath it feeding and carrying it.

   `make-port` composes the local socket's protocol state, host-free.
   What feeds it is the raw layer: `:send!` is composed by the
   composition as a closure that encodes the bytes as Base64 and appends
   one outbound value to the raw writer handle
   (dao.stream.datagram/writer over a bound host seam), and `send-value!`
   answers that raw writer's first non-ok outcome instead of discarding
   it. `port-step!` is the reading side: one step reading the raw traffic
   ring from the port's own raw cursor to blocked, skipping lifecycle
   events and :dao.jing.dht/v maps (the DHT interpreter sharing the
   socket, dao.stream.datagram.md 6), decoding each datagram event's
   Base64 and calling `receive!` with the source address and bytes. The
   composition drives it, one owner per port, before the projections'
   `step!`.

   One CBOR value, canonical profile, rides one datagram, at most
   `max-datagram` bytes, fragment envelope included -- the figure
   `dao.jing.dht.node` already uses to clear common path MTUs. An
   encoded message over that budget is split into fragment envelopes,
   channel-internal: neither the mirror step nor the link
   (dao.stream.remote) ever sees one, only the complete decoded message
   `receive!` reassembles and deposits.

   Reassembly key, per 3.2: [attachment identity, source address,
   direction, id]. For UDP the attachment identity a socket serves IS
   the remote host:port it saw, so this port's own reassembly key
   collapses the first two components into one address string; a
   fragment's own carried id is unique only per asking side, so the
   direction distinguishes a fragment split from an outgoing request
   from one split from its answer, both landing at the same address
   with the same id, when this side is both asker and mirror on one
   channel. Bounded by two composition data, no clock:
   `:dao.stream.udp/max-message-bytes` (default 64 KiB) refuses an
   oversize send and bounds a reassembly's accumulated chunk bytes;
   and `:dao.stream.udp/max-partial-messages`, evicting the oldest
   partial message.

   The deposit adapter shape: every reassembled datagram value is
   deposited as one event, `{:dao.stream.udp/attachment id
   :dao.stream.udp/source {:dao.stream.udp/host h
   :dao.stream.udp/port p} :dao.stream.udp/value v}`, onto the shared
   traffic medium the composition wires -- one socket may serve several
   attachments, one per remote address, so this medium is shared the
   way a WebSocket connection's own traffic medium is not. `projection`
   and `step!` are this channel's `ws-project`: driven per attachment
   at the composition's cadence, they keep the events naming their own
   attachment and append each value onto the channel ring that
   attachment's reflections read.

   `make-attacher` binds or reuses `:port` and answers a writer handle
   whose `append!` sends toward the descriptor's address, fragmenting
   and refusing oversize per the above; `send-to!` is the same write
   path to an explicit destination from an already-bound port, for hole
   punching (section 4), outside any attach!'d descriptor. Replies
   always go to a datagram's own source address, never a payload's
   claim, because `receive!` is the only place a source address is
   read, and every deposited event carries the address the socket
   itself saw it arrive from."
  (:require [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.cbor :as cbor]
            [dao.stream.datagram :as datagram])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(def transport-type :dao.stream/udp)


(def max-datagram
  "The whole encoded datagram's budget, fragment envelope included: the
   1200-byte figure dao.jing.dht.node already uses to clear common path
   MTUs (docs/design/dao.stream.remote.md 3.2)."
  1200)


(def default-max-message-bytes
  "64 KiB, the default reassembly maximum (3.2)."
  (* 64 1024))


(def default-max-partial-messages
  "No default is named in 3.2; this is this implementation's own bound
   on outstanding partial messages, chosen only to keep reassembly
   memory finite without a clock."
  64)


(defn descriptor?
  "{:dao.stream/type :dao.stream/udp :dao.stream/identity id
     :dao.stream.udp/host h :dao.stream.udp/port p} (3.2)."
  [x]
  (and (map? x)
       (= transport-type (:dao.stream/type x))
       (string? (:dao.stream/identity x))
       (string? (:dao.stream.udp/host x))
       (integer? (:dao.stream.udp/port x))
       (pos? (:dao.stream.udp/port x))))


(defn address-key
  "The attachment identity a socket serves for one remote address: the
   host:port it saw, as a string, so it can key a map."
  [host port]
  (str host ":" port))


;; =============================================================================
;; Portable host byte arrays
;; =============================================================================

(defn- blen
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :default (alength bs)))


(defn- balloc
  [n]
  #?(:cljd (Uint8List. n)
     :clj (byte-array n)
     :cljs (js/Uint8Array. n)))


(defn- bslice
  [bs start end]
  #?(:cljd (Uint8List.fromList (.sublist ^Uint8List bs start end))
     :clj (java.util.Arrays/copyOfRange ^bytes bs (int start) (int end))
     :cljs (.slice bs start end)))


(defn- bconcat
  [chunks]
  (let [total (reduce + (map blen chunks))
        out (balloc total)]
    (reduce (fn [off c]
              (let [n (blen c)]
                #?(:cljd (.setRange ^Uint8List out off (+ off n) c)
                   :clj (System/arraycopy c 0 out off n)
                   :cljs (.set out c off))
                (+ off n)))
            0 chunks)
    out))


(defn- ceil-div
  [a b]
  (quot (+ a (dec b)) b))


;; =============================================================================
;; Fragmentation (write side)
;; =============================================================================

(defn- direction-of
  "A request carries :dao.stream.remote/op; an answer never does."
  [v]
  (if (contains? v :dao.stream.remote/op)
    :dao.stream.udp/request
    :dao.stream.udp/answer))


(defn- fragment-envelope
  [id part parts direction chunk]
  {:dao.stream.remote/id id
   :dao.stream.udp/part part
   :dao.stream.udp/parts parts
   :dao.stream.udp/direction direction
   :dao.stream.udp/bytes chunk})


(defn- fits?
  [id part parts direction chunk-len]
  (<= (blen (cbor/encode
              (fragment-envelope id part parts direction
                                 (balloc chunk-len))))
      max-datagram))


(defn- chunk-size
  "The largest chunk length whose fragment envelope, at the highest
   part index (the widest of the small integers), still fits the
   datagram budget; backs off from an optimistic start."
  [id parts direction]
  (loop [size (- max-datagram 16)]
    (when (pos? size)
      (if (fits? id (dec parts) parts direction size)
        size
        (recur (dec size))))))


(defn- fragments
  "Split encoded `payload` for message `id`, direction `direction`,
   into fragment envelopes that each fit the datagram budget."
  [payload id direction]
  (let [total (blen payload)
        parts (max 1 (ceil-div total (- max-datagram 16)))
        size (or (chunk-size id parts direction)
                 (throw (ex-info
                          "dao.stream.udp: no chunk size fits the budget"
                          {:id id :parts parts})))
        parts (max parts (ceil-div total size))
        size (or (chunk-size id parts direction) size)]
    (for [i (range parts)
          :let [start (* i size)
                end (min total (+ start size))]]
      (fragment-envelope id i parts direction (bslice payload start end)))))


;; =============================================================================
;; The port: shared local-socket protocol state
;; =============================================================================

(defn make-port
  "Compose one local socket's protocol state. `:send!` is the host seam,
   `(send! host port bytes) -> an outcome map or nothing`, called once per
   outbound datagram; it must not wait, and a transport failure it answers
   travels up as `send-value!`'s outcome -- the raw writer's first non-ok
   outcome, not discarded. The composition composes it, over the raw
   datagram layer, as the Base64 closure appending one outbound value to
   `dao.stream.datagram/writer` (dao.stream.datagram.md 7). `:traffic` is
   the shared deposit target the composition wires -- a writer every
   reassembled datagram's event is appended to (`dao.stream/writer?`).
   `:raw-traffic` is the raw datagram layer's traffic ring the port reads
   itself a cursor on, minted :oldest at composition time so a raw gap
   reports lost datagrams -- `port-step!` requires it. `:max-message-bytes`
   and `:max-partial-messages` are the two reassembly bounds (defaults
   above)."
  [{:keys [send! traffic raw-traffic max-message-bytes
           max-partial-messages]}]
  (when-not (and (fn? send!) (stream/writer? traffic))
    (throw (ex-info "invalid dao.stream.udp port composition"
                    {:send! send! :traffic traffic})))
  (when (and (some? raw-traffic) (not (stream/reader? raw-traffic)))
    (throw (ex-info "invalid dao.stream.udp raw traffic composition"
                    {:raw-traffic raw-traffic})))
  (atom {:send! send!
         :traffic traffic
         :raw-traffic raw-traffic
         :raw-cursor (when raw-traffic
                       (:dao.stream/cursor
                         (stream/cursor raw-traffic stream/anchor-oldest)))
         :max-message-bytes (or max-message-bytes
                                default-max-message-bytes)
         :max-partial-messages (or max-partial-messages
                                   default-max-partial-messages)
         :partial {}
         :partial-order []}))


(defn- send-datagram!
  "One datagram out through the composed :send! seam: the seam's own
   outcome map travels up -- the raw writer's first non-ok outcome, not
   discarded -- and a fire-and-forget seam answering nothing is ok."
  [port host dest-port bytes]
  (let [r ((:send! @port) host dest-port bytes)]
    (if (and (map? r) (contains? r :dao.stream/outcome))
      r
      {:dao.stream/outcome :dao.stream/ok})))


(defn send-value!
  "Encode and send `v` toward `host`:`dest-port` from `port`'s socket:
   a single datagram when it fits the budget, fragments when it does
   not, transport-error naming oversize when the encoded message
   exceeds :dao.stream.udp/max-message-bytes -- never sent, torn or
   partially sent. The seam's first non-ok outcome is the answer: a
   fragment run stops there, cleanly, nothing torn following it."
  [port host dest-port v]
  (let [payload (cbor/encode v)
        n (blen payload)]
    (cond
      (> n (:max-message-bytes @port))
      {:dao.stream/outcome :dao.stream/transport-error
       :dao.stream.remote/reason :dao.stream.remote/oversize}

      (<= n max-datagram)
      (send-datagram! port host dest-port payload)

      :else
      (let [id (:dao.stream.remote/id v)
            direction (direction-of v)]
        (loop [frags (fragments payload id direction)]
          (if (empty? frags)
            {:dao.stream/outcome :dao.stream/ok}
            (let [r (send-datagram! port host dest-port
                                    (cbor/encode (first frags)))]
              (if (= :dao.stream/ok (:dao.stream/outcome r))
                (recur (rest frags))
                r))))))))


(defn send-to!
  "The explicit-destination write path from an already-bound port,
   outside any attach!'d descriptor -- hole punching depends on it
   (3.2, section 4)."
  [port host dest-port v]
  (send-value! port host dest-port v))


;; =============================================================================
;; Reassembly (read side)
;; =============================================================================

(defn- fragment-envelope?
  "Any reserved fragment key present makes a datagram a fragment
   attempt: it must then validate fully or be dropped -- a shape
   carrying :parts/:direction/:bytes but missing :part does not fall
   through to the deposit path."
  [v]
  (and (map? v)
       (some #(contains? v %)
             [:dao.stream.udp/part :dao.stream.udp/parts
              :dao.stream.udp/direction :dao.stream.udp/bytes])))


(defn- well-formed-fragment?
  "A fragment envelope's own wire fields, checked before any reassembly
   state can change: :part and :parts are integers with 0 <= :part <
   :parts, 1 <= :parts, :direction one of the two the write side
   mints, and :bytes a host byte payload. A datagram failing this is
   best-effort wire input, dropped below the mirror and the link like
   any other malformed one -- nothing here may throw during
   reassembly, whatever a peer put on the wire."
  [v]
  (let [{:dao.stream.udp/keys [part parts direction bytes]} v]
    (and (integer? part)
         (integer? parts)
         (pos? parts)
         (<= 0 part)
         (< part parts)
         (#{:dao.stream.udp/request :dao.stream.udp/answer} direction)
         (cbor/byte-payload? bytes))))


(defn- forget-partial!
  "Drop the partial message keyed `k` from the port's reassembly
   state, its order entry with it."
  [port k]
  (swap! port (fn [s]
                (-> s
                    (update :partial dissoc k)
                    (update :partial-order
                            (fn [order] (vec (remove #{k} order))))))))


(defn- evict-oldest!
  [port]
  (when-some [k (first (:partial-order @port))]
    (forget-partial! port k)))


(defn- absorb-fragment!
  "Fold one well-formed fragment envelope into the port's reassembly
   state, keyed by [attachment direction id]; returns the complete
   encoded payload once every part has arrived, else nil. The
   max-message-bytes bound is the accumulated chunk bytes themselves,
   as they arrive: a message whose parts add up past the bound is
   dropped the moment they say so, and a completed payload is
   delivered only if its own actual length is within the bound -- no
   declared-parts arithmetic stands in for the real bytes, so a valid
   message near the limit delivers. A duplicate part is ignored, and
   a fragment disagreeing with the parts count a partial already
   holds is dropped, so the state held is never torn."
  [port attachment frag]
  (let [{:dao.stream.remote/keys [id]
         :dao.stream.udp/keys [part parts direction bytes]} frag
        k [attachment direction id]
        bound (:max-message-bytes @port)]
    (when-not (contains? (:partial @port) k)
      (swap! port (fn [s]
                    (-> s
                        (assoc-in [:partial k]
                                  {:parts {} :parts-count parts
                                   :bytes-total 0})
                        (update :partial-order conj k))))
      (when (> (count (:partial @port))
               (:max-partial-messages @port))
        (evict-oldest! port)))
    (when-some [held (get (:partial @port) k)]
      (when (and (= parts (:parts-count held))
                 (not (contains? (:parts held) part)))
        (let [total (+ (:bytes-total held) (blen bytes))]
          (if (> total bound)
            ;; even completed, this message is over the bound: its
            ;; partial state goes now, it can never deliver
            (forget-partial! port k)
            (do (swap! port (fn [s]
                              (-> s
                                  (assoc-in [:partial k :parts part] bytes)
                                  (assoc-in [:partial k :bytes-total] total))))
                (let [held (get (:partial @port) k)]
                  (when (= (count (:parts held)) (:parts-count held))
                    (forget-partial! port k)
                    (let [payload (bconcat (mapv (:parts held)
                                                 (range (:parts-count held))))]
                      (when (<= (blen payload) bound)
                        payload)))))))))))


(defn- deposit!
  [port attachment host src-port v]
  (stream/append! (:traffic @port)
                  {:dao.stream.udp/attachment attachment
                   :dao.stream.udp/source {:dao.stream.udp/host host
                                           :dao.stream.udp/port src-port}
                   :dao.stream.udp/value v}))


(defn receive!
  "Adapter entry: one raw datagram arrived from `host`:`src-port` on
   `port`'s socket, as `bytes`. Malformed CBOR is dropped as a
   diagnostic below the mirror and the link, per dao.stream.remote.md
   2.1, and so is a fragment envelope whose own fields are malformed
   -- checked before any partial state can change. A plain value
   deposits at once; a fragment envelope folds into reassembly and
   deposits only once complete -- loss of any part is loss of the
   whole message, recovered by the link's resend rule, not by this
   port. Replies to what this call learns go to `host`:`src-port`,
   this datagram's own source, never an address a payload might
   claim."
  [port host src-port bytes]
  (when-some [v (try (cbor/decode bytes) (catch #?(:cljd Object
                                                   :clj Throwable
                                                   :cljs :default) _
                                           nil))]
    (let [attachment (address-key host src-port)]
      (if (fragment-envelope? v)
        (when (well-formed-fragment? v)
          (when-some [payload (absorb-fragment! port attachment v)]
            (when-some [whole (try (cbor/decode payload)
                                   (catch #?(:cljd Object :clj Throwable
                                             :cljs :default) _
                                     nil))]
              (deposit! port attachment host src-port whole))))
        (deposit! port attachment host src-port v)))))


;; =============================================================================
;; The projection: one channel ring per attachment (this channel's
;; ws-project)
;; =============================================================================

(defn projection
  "Compose one projection over the attachment identity `:attachment`
   (an `address-key`), the shared traffic reader `:traffic` this port
   deposits onto, the already-minted reading cursor `:cursor`, and the
   channel ring `:ring` this attachment's reflections and mirror steps
   read. Owns the cursor from here: each `step!` advances it."
  [{:keys [attachment traffic cursor ring]}]
  (when-not (and (string? attachment)
                 (stream/reader? traffic)
                 (some? cursor)
                 (stream/reader? ring)
                 (stream/writer? ring))
    (throw (ex-info "invalid dao.stream.udp projection composition"
                    {:attachment attachment :traffic traffic
                     :cursor cursor :ring ring})))
  (atom {:attachment attachment :traffic traffic :cursor cursor
         :ring ring}))


(defn step!
  "Drive the projection once, at the composition's cadence: read the
   traffic medium to blocked, keeping this attachment's own events and
   dropping every other attachment's, appending each kept value onto
   the channel ring. A gap adopts the medium's own recovery cursor.
   Returns the projection."
  [project]
  (loop []
    (let [s @project
          r (stream/next (:traffic s) (:cursor s))]
      (case (:dao.stream/outcome r)
        :dao.stream/ok
        (let [event (:dao.stream/value r)]
          (swap! project assoc :cursor (:dao.stream/cursor r))
          (when (= (:attachment s) (:dao.stream.udp/attachment event))
            (stream/append! (:ring s) (:dao.stream.udp/value event)))
          (recur))

        :dao.stream/gap
        (do (swap! project assoc :cursor (:dao.stream/cursor r))
            (recur))

        project))))


(defn reading-cursor
  [project]
  (:cursor @project))


;; =============================================================================
;; Reading the raw datagram layer: port-step! (dao.stream.datagram.md 7)
;; =============================================================================


(def default-step-budget
  "The default `port-step!` budget: raw values consumed in one step when
   the composition names no bound."
  64)


(defn- dht-owned?
  "True when `bytes` decode to a map carrying :dao.jing.dht/v -- the DHT
   interpreter's own datagram on a shared socket
   (dao.stream.datagram.md 6), dropped by this channel before its
   deposit. This one decode is the price of `receive!`'s fixed
   bytes-taking shape; receive! decodes again."
  [bytes]
  (when-some [v (try (cbor/decode bytes)
                     (catch #?(:cljd Object :clj Throwable :cljs :default)
                            _
                       nil))]
    (and (map? v) (contains? v :dao.jing.dht/v))))


(defn port-step!
  "One step reading the raw datagram layer's traffic ring into this port:
   read to `blocked` from the port's own raw cursor, bounded by `budget`
   values, skip lifecycle events and `:dao.jing.dht/v` maps, decode each
   datagram event's Base64 and call `receive!` with the source address
   the socket observed and the bytes. A raw `gap` adopts the recovery
   cursor: those datagrams are lost, indistinguishable from network
   loss, and the link's resend rule recovers them exactly as it does for
   it. A value that is neither a datagram event nor a lifecycle event is
   not the raw layer's and is skipped. One owner per port drives this,
   and the composition drives it before the projections' `step!`."
  ([port] (port-step! port default-step-budget))
  ([port budget]
   (let [{:keys [raw-traffic]} @port]
     (when-not raw-traffic
       (throw (ex-info "dao.stream.udp port-step! needs :raw-traffic"
                       {})))
     (loop [left budget]
       (if (pos? left)
         (let [r (stream/next raw-traffic (:raw-cursor @port))]
           (case (:dao.stream/outcome r)
             :dao.stream/ok
             (do (swap! port assoc :raw-cursor (:dao.stream/cursor r))
                 (let [v (:dao.stream/value r)]
                   (when (datagram/datagram-event? v)
                     (let [bytes (base64/decode
                                   (:dao.stream.datagram/bytes v))]
                       (when-not (dht-owned? bytes)
                         (let [source (:dao.stream.datagram/source v)]
                           (receive! port
                                     (:dao.stream.datagram/host source)
                                     (:dao.stream.datagram/port source)
                                     bytes))))))
                 (recur (dec left)))

             :dao.stream/gap
             (do (swap! port assoc :raw-cursor (:dao.stream/cursor r))
                 (recur (dec left)))

             port))
         port)))))


;; =============================================================================
;; The writer handle and attacher
;; =============================================================================

(deftype UdpHandle
  [port host dest-port descriptor]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor descriptor
     :dao.stream/identity (:dao.stream/identity descriptor)})


  stream/IDaoStreamWriter

  (append!
    [_ v]
    (send-value! port host dest-port v)))


(defn make-attacher
  "Construct the host-composed unary `attach!` entry point: `:port` is
   an already-composed `make-port` value. `attach!` binds or reuses it
   -- one port serves every descriptor a composition attaches through
   it -- and answers at once with a writer handle whose `append!` sends
   toward the descriptor's address."
  [{:keys [port] :as config}]
  (when-not (some? port)
    (throw (ex-info "dao.stream.udp attacher needs :port" {:config config})))
  (fn attach!
    [descriptor]
    (if-not (descriptor? descriptor)
      {:dao.stream/outcome :dao.stream/invalid-descriptor}
      (let [host (:dao.stream.udp/host descriptor)
            dest-port (:dao.stream.udp/port descriptor)]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/handle (UdpHandle. port host dest-port descriptor)
         :dao.stream/attachment (address-key host dest-port)}))))

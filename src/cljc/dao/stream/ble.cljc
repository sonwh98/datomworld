(ns dao.stream.ble
  "The raw Bluetooth layer of docs/design/dao.stream.ble.md: one radio
   node exposed as dao.stream values. This layer carries bytes, peer
   addresses and link facts and nothing else -- no decoding, retry,
   ordering, dedup, fragmentation, reassembly, routing; each of those is
   an interpreter above it, and the flooding mesh (dao.stream.ble.mesh)
   is the first.

   A radio node retains nothing, so it has no reader surface: a writer
   handle (#{:writer :closable}) whose append! hands one addressed frame
   to the radio, and a traffic stream, supplied by the composition, onto
   which the host adapter deposits every received frame and every host
   fact about the node and its links as one event. Readers hold their own
   cursors on the traffic stream.

   Binding is a host act, not create!, and the composition proceeds in
   two explicit moves (section 3): the host seam (dao.stream.ble/dart)
   takes {:identity id :deposit w :service-uuid u :max-frame-bytes n
   :max-links n :announce-window-ms ms :now-ms f} -- a deposit writer and
   nothing else, no function to invoke -- and returns at once {:send! f
   :close! f}; the seam deposits :dao.stream.ble/bound with the node
   identity on every host; the composition reads that event and calls
   `writer` with the seam and the complete descriptor. A failed start
   deposits :bind-failed and there is no writer.

   A peer address is an opaque, link-scoped string -- Android reports a
   MAC, iOS a per-local-device UUID -- and names one directional GATT
   link as this radio's state observes it. No structure, no global
   meaning, string equality: nothing above this layer may treat a peer
   address as a node's name, which is why the mesh above floods (section
   2 of the design).

   A send failure is the caller's outcome when the host answers at call
   time (:dao.stream/transport-error, clean: nothing was sent) and a
   :dao.stream.ble/send-failed lifecycle event on the traffic stream for
   its other observers -- always the event for a failure the host reports
   only later, which is the channel this transport declares for an answer
   it cannot give at call time (dao.stream.md, Writing). Excluded
   append! outcomes: :full, because this layer holds no outbound queue
   and a radio under pressure loses frames -- the medium's declared
   nature, not a refusal; :refused, because no policy is composed in this
   layer.

   There is no :dao.stream/create entry. `attach!` on a BLE descriptor
   off its host is :dao.stream/not-found; on its own host it resolves
   only against nodes a composition chose to keep in its host-composed
   attach closure -- `make-attacher` is that closure over a
   composition-owned resolver, never a registry. Exposure is a real
   hazard: a raw writer handle entered in a mirror table lets whoever
   reaches it send arbitrary frames from this radio; no composition does
   so without a gate on that entry (section 9)."
  (:require [dao.host.bytes :as bytes]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]))


(def transport-type
  "The dao.stream/type of a bound radio node's descriptor."
  :dao.stream/ble)


(def default-service-uuid
  "The datom.world mesh profile's GATT service: a TX characteristic the
   node notifies its frames on and an RX characteristic a central writes
   its frames to. Composition data with this default; a composition that
   changes it changes who can hear it."
  "7daf6c80-3f2a-4e11-9d47-1b2a3c4d5e6f")


(def default-tx-uuid
  "The mesh profile's TX characteristic (notify): this node's frames out
   to a subscribed central."
  "7daf6c81-3f2a-4e11-9d47-1b2a3c4d5e6f")


(def default-rx-uuid
  "The mesh profile's RX characteristic (write): a central's frames in."
  "7daf6c82-3f2a-4e11-9d47-1b2a3c4d5e6f")


(def default-max-frame-bytes
  "The default symmetric frame budget: one notification payload under the
   smallest ATT MTU hosts commonly negotiate (iOS has granted as little
   as 185). A composition that observes a larger negotiated MTU may raise
   it."
  180)


(def default-max-links
  "The default ceiling on concurrent GATT links: an iOS central holds
   around ten."
  8)


(def default-announce-window-ms
  "The default discovery throttle: at most one peer-seen event per peer
   per window, the latest RSSI winning."
  2000)


(defn- byte-length
  [bs]
  (bytes/length bs))


;; =============================================================================
;; Values (section 2)
;; =============================================================================


(defn peer-address?
  "True iff `s` is a peer address: a non-empty string. Opaque by design --
   no structure is assigned and none is checked."
  [s]
  (and (string? s) (pos? (count s))))


(defn node-id?
  "True iff `s` is a node identity: a non-empty string, composition
   minted, never authenticated by this layer."
  [s]
  (and (string? s) (pos? (count s))))


(defn outbound?
  "True iff `v` is one outbound value of section 2: a destination peer
   address and the frame as strict Base64. The decoded length against the
   budget is the writer's check, not this one's."
  [v]
  (and (map? v)
       (peer-address? (:dao.stream.ble/destination v))
       (base64/text? (:dao.stream.ble/bytes v))))


(defn frame-event?
  "True iff `v` is one received frame as an event: exact bytes as strict
   Base64, and the source peer the radio observed."
  [v]
  (and (map? v)
       (peer-address? (:dao.stream.ble/source v))
       (base64/text? (:dao.stream.ble/bytes v))))


(def lifecycle-events
  "The lifecycle events this layer declares. A transport-owned event
   keyword outside this set is not this layer's."
  #{:dao.stream.ble/bound
    :dao.stream.ble/bind-failed
    :dao.stream.ble/peer-seen
    :dao.stream.ble/peer-lost
    :dao.stream.ble/link-up
    :dao.stream.ble/link-down
    :dao.stream.ble/send-failed
    :dao.stream.ble/closed})


(defn lifecycle-event?
  "True iff `v` is one host fact about the node itself or its links."
  [v]
  (and (map? v)
       (node-id? (:dao.stream.ble/node v))
       (contains? lifecycle-events (:dao.stream.ble/event v))))


(defn bound-event
  "The bound lifecycle event: the node exists, with its identity."
  [id self]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/bound
   :dao.stream.ble/self self})


(defn bind-failed-event
  "The failed-bind lifecycle event. There is no writer."
  [id reason]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/bind-failed
   :dao.stream.ble/reason reason})


(defn peer-seen-event
  "One observed advertisement of the service UUID, throttled by the seam
   to one event per peer per :announce-window-ms, latest RSSI winning."
  [id peer rssi]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/peer-seen
   :dao.stream.ble/peer peer
   :dao.stream.ble/rssi rssi})


(defn peer-lost-event
  "The peer-lost lifecycle event: a link to the peer went down. S1 seams
   deposit it only from link loss, never from advertisement silence --
   silence is not a fact a radio reports."
  [id peer]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/peer-lost
   :dao.stream.ble/peer peer})


(defn link-up-event
  "The link-up lifecycle event: one directional GATT link exists, the
   role named (:central connected out, :peripheral connected in)."
  [id peer direction]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/link-up
   :dao.stream.ble/peer peer
   :dao.stream.ble/direction direction})


(defn link-down-event
  "The link-down lifecycle event: the directional link named by the peer
   address is gone."
  [id peer]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/link-down
   :dao.stream.ble/peer peer})


(defn send-failed-event
  "The send-failure lifecycle event: the channel for a send failure the
   host could not answer at call time, and the observers' copy of one it
   could."
  [id peer reason]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/send-failed
   :dao.stream.ble/peer peer
   :dao.stream.ble/reason reason})


(defn closed-event
  "The closed lifecycle event, deposited while the deposit writer still
   admits it."
  [id]
  {:dao.stream.ble/node id
   :dao.stream.ble/event :dao.stream.ble/closed})


(defn frame-event
  "One received frame as an event: `bytes` encoded as Base64 so no host
   byte array crosses onto a stream, with the source peer the radio
   observed."
  [id peer bytes]
  {:dao.stream.ble/node id
   :dao.stream.ble/source peer
   :dao.stream.ble/bytes (base64/encode bytes)})


;; =============================================================================
;; The descriptor (section 3)
;; =============================================================================


(defn descriptor?
  "{:dao.stream/type :dao.stream/ble :dao.stream/identity id
     :dao.stream.ble/self <node identity>}
   The descriptor names the local radio node. It is reachability data in
   the honest, reduced sense: a BLE node is reachable by radio proximity
   and by advertising the service UUID, never by dialing the descriptor."
  [d]
  (and (map? d)
       (= transport-type (:dao.stream/type d))
       (node-id? (:dao.stream/identity d))
       (node-id? (:dao.stream.ble/self d))))


;; =============================================================================
;; The writer handle (section 4)
;; =============================================================================


(defn- check-outbound
  "One outbound value of section 2 as either {:bytes bs} -- the decoded
   frame, ready for the radio -- or {:refusal reason}: a malformed
   destination, bytes that are not strict Base64, or a decoded length
   over the budget. Nothing was sent for a refusal."
  [v max-frame-bytes]
  (cond
    (not (map? v))
    {:refusal "not an outbound value"}

    (not (peer-address? (:dao.stream.ble/destination v)))
    {:refusal "malformed destination"}

    (not (base64/text? (:dao.stream.ble/bytes v)))
    {:refusal "bytes are not strict Base64"}

    :else
    (let [bs (base64/decode (:dao.stream.ble/bytes v))]
      (if (> (byte-length bs) max-frame-bytes)
        {:refusal "decoded length over :max-frame-bytes"}
        {:bytes bs}))))


(deftype BLEWriter
  [send-fn close-fn the-descriptor max-frame-bytes closed]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor the-descriptor
     :dao.stream/identity (:dao.stream/identity the-descriptor)})


  stream/IDaoStreamWriter

  (append!
    [_ v]
    (if @closed
      {:dao.stream/outcome :dao.stream/closed}
      (let [checked (check-outbound v max-frame-bytes)]
        (if-some [reason (:refusal checked)]
          {:dao.stream/outcome :dao.stream/invalid-value
           :dao.stream.ble/reason reason}
          (let [r (try
                    (send-fn (:dao.stream.ble/destination v)
                             (:bytes checked))
                    (catch #?(:cljd Object :clj Throwable :cljs :default) e
                      {:dao.stream/outcome :dao.stream/transport-error
                       :dao.stream.ble/reason (str e)}))]
            (if (and (map? r) (contains? r :dao.stream/outcome))
              (if (#{:dao.stream/ok :dao.stream/transport-error
                     :dao.stream/closed} (:dao.stream/outcome r))
                r
                {:dao.stream/outcome :dao.stream/transport-error})
              {:dao.stream/outcome :dao.stream/ok}))))))


  stream/IDaoStreamClosable

  (close!
    [_]
    (vreset! closed true)
    (try (close-fn)
         (catch #?(:cljd Object :clj Throwable :cljs :default) _
           nil))
    {:dao.stream/outcome :dao.stream/ok}))


(defn writer
  "The composition's second move (section 3): the writer handle over a
   bound `seam` ({:send! f :close! f}) and the complete `descriptor` the
   bound event reported. `max-frame-bytes` is composition data, default
   180, the same budget the seam was bound with."
  ([seam descriptor]
   (writer seam descriptor default-max-frame-bytes))
  ([seam descriptor max-frame-bytes]
   (when-not (and (map? seam)
                  (fn? (:send! seam))
                  (fn? (:close! seam)))
     (throw (ex-info "a dao.stream.ble seam is {:send! f :close! f}"
                     {:seam seam})))
   (when-not (descriptor? descriptor)
     (throw (ex-info "not a dao.stream.ble descriptor"
                     {:descriptor descriptor})))
   (BLEWriter. (:send! seam) (:close! seam) descriptor
               (long max-frame-bytes) (volatile! false))))


(defn make-attacher
  "The host-composed attach! closure over nodes a composition chose to
   keep: `resolver` maps a node identity to its writer handle, or nil.
   Attaching never creates, resolves nothing ambiently -- there is no
   registry -- and the same handle is shared, not re-minted: a radio node
   does not distinguish attachments."
  [resolver]
  (fn attach!
    [descriptor]
    (if-not (descriptor? descriptor)
      {:dao.stream/outcome :dao.stream/invalid-descriptor}
      (if-let [handle (resolver (:dao.stream/identity descriptor))]
        (if (= (:dao.stream/identity descriptor)
               (:dao.stream/identity (stream/descriptor handle)))
          {:dao.stream/outcome :dao.stream/ok
           :dao.stream/handle handle}
          {:dao.stream/outcome :dao.stream/not-found})
        {:dao.stream/outcome :dao.stream/not-found}))))

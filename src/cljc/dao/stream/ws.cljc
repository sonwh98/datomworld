(ns dao.stream.ws
  "The DaoStream v2 WebSocket protocol boundary.

   This namespace deliberately knows no WebSocket library.  A host adapter owns
   its raw socket and supplies the small synchronous `:connect!`, `:send!`, and
   `:close!` functions below.  The public DaoStream surface is consequently
   callback-free, non-waiting, and portable; socket callbacks enter only through
   the adapter map returned to the host while it constructs a connection.

   The wire codec is a composition choice carried as an explicit codec profile
   (see `codec-profile?`): the `dao.stream.transit-json` text subprotocol and
   the additive `dao.stream.cbor` binary subprotocol run the same state
   machine, envelopes, and lifecycle.  Selection is explicit on both ends — a
   client offers exactly its selected subprotocol, an endpoint serves exactly
   the profiles it was composed with — so no silent downgrade exists."
  (:require [dao.stream :as stream]
            [dao.stream.transit :as transit]
            [clojure.string :as str]))


(def transport-type :dao.stream/ws)
(def subprotocol "dao.stream.transit-json")
(def ended-close-code 4000)
(def protocol-close-code 4002)


(defn codec-profile?
  "True for one codec profile: the WebSocket subprotocol name it negotiates,
   the frame kind its payloads ride (:text or :binary), the portable-value
   predicate for its domain, and its encoder and decoder over host payloads
   (strings for text profiles, host bytes for binary profiles)."
  [x]
  (and (map? x)
       (string? (:ws/subprotocol x))
       (contains? #{:text :binary} (:ws/frame-kind x))
       (fn? (:ws/portable-value? x))
       (fn? (:ws/encode x))
       (fn? (:ws/decode x))))


(defn checked-codec
  "Validate one composition-supplied codec profile, defaulting to Transit."
  [x]
  (let [codec (or x transit/profile)]
    (when-not (codec-profile? codec)
      (throw (ex-info "invalid DaoStream WebSocket codec profile" {:codec x})))
    codec))


(defn- outcome
  [x]
  {:dao.stream/outcome x})


(defn canonical-path?
  "True for the already-canonical request-target form carried on a descriptor.
   URI parsing and bind policy belong to the host adapter; this gate prevents a
   query, fragment, relative path, or unresolved dot segment from reaching the
   exact served-path lookup."
  [path]
  (and (string? path)
       (not (empty? path))
       (str/starts-with? path "/")
       (not (str/includes? path "?"))
       (not (str/includes? path "#"))
       (not (some #{"." ".."} (str/split path #"/")))))


(defn descriptor?
  "The settled WebSocket descriptor gate.  It names a served stream, not a
   particular connection, and contains only portable reachability data.
   The one-argument form keeps the Transit domain as the public default; a
   composition selecting a codec profile gates the same descriptor against
   that profile's domain."
  ([x] (descriptor? x transit/profile))
  ([x codec]
   (and (map? x)
        (= transport-type (:dao.stream/type x))
        (string? (:dao.stream/identity x))
        (string? (:ws/host x))
        (integer? (:ws/port x))
        (pos? (:ws/port x))
        (canonical-path? (:ws/path x))
        ((:ws/portable-value? codec) x))))


(defn admission?
  "Assembly-time declaration required for every transport deposit medium."
  [x]
  (and (map? x)
       (= :evict-oldest (:retention x))
       (integer? (:capacity x))
       (pos? (:capacity x))
       (contains? #{:host-values :portable-values} (:value-domain x))))


(defn- writer-target?
  [x]
  (and (map? x)
       (stream/writer? (:dao.stream/handle x))
       (contains? (:dao.stream/surface x) :writer)))


(defn- checked-target
  [target declaration]
  (when-not (and (writer-target? target) (admission? declaration))
    (throw (ex-info "invalid DaoStream WebSocket deposit composition"
                    {:target target :admission declaration})))
  target)


(defn- attachment-id
  []
  (str (random-uuid)))


(def ^:private bound-keys
  "The byte and frame bounds a composition may set on `make-attacher` and
   `make-endpoint`.  Each is a positive integer or nil; nil is unbounded."
  [:ws/max-frame-bytes :ws/max-pending-frames :ws/max-pending-bytes
   :ws/outbound-high-water :ws/max-outbound-bytes])


(defn- checked-bounds
  [config]
  (let [bounds (select-keys config bound-keys)]
    (when-not (every? (fn [[_ v]] (or (nil? v) (and (integer? v) (pos? v)))) bounds)
      (throw (ex-info "invalid DaoStream WebSocket bounds" {:bounds bounds})))
    bounds))


(defn- payload-size
  "The raw size of one wire payload: characters for a text payload, bytes
   for host bytes.  `:cljd` comes first because the cljd host-eval pass also
   reads a `:clj` branch."
  [payload]
  (if (string? payload)
    (count payload)
    #?(:cljd (.-length ^List payload)
       :cljs (.-length ^js payload)
       :clj (alength ^bytes payload))))


(defn- invoke-close!
  [socket code reason]
  (when-let [f (:close! socket)]
    (try
      (f code reason)
      (catch #?(:cljd Object :clj Throwable :cljs :default) _
        ;; The connection has already transitioned locally.  The caller adds a
        ;; diagnostic where it still has a functioning composed medium.
        :failed))))


(defn- send-result
  [socket payload]
  ;; The payload is whatever the attachment's codec produced — a string for
  ;; text profiles, host bytes for binary profiles.  The transport never
  ;; inspects it; the host `:send!` seam owns the typed frame.
  (try
    (let [x ((:send! socket) payload)]
      (cond
        (or (nil? x) (= true x) (= :ok x)) (outcome :dao.stream/ok)
        (= false x) (outcome :dao.stream/full)
        (and (map? x) (:dao.stream/outcome x)) x
        :else (outcome :dao.stream/transport-error)))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      (outcome :dao.stream/transport-error))))


(declare receive! closed! opened! teardown!)


(defn- outbound-level
  "The outbound bytes the bounds are judged against: the seam's own
   `:queued-bytes` where the host has it, else the cumulative encoded bytes
   sent since open."
  [s socket]
  (if-let [queued (:queued-bytes socket)]
    (queued)
    (:sent-bytes s 0)))


(defn- send-bounded!
  "Send one encoded payload under the outbound bounds.  At or above
   `:ws/max-outbound-bytes` the connection is torn down and the append
   answers `closed`; at or above `:ws/outbound-high-water` (seam
   `:queued-bytes` only) it answers the transient `full`."
  [state attachment s socket payload]
  (let [{:ws/keys [outbound-high-water max-outbound-bytes]} (:bounds s)
        level (when (or outbound-high-water max-outbound-bytes)
                (outbound-level s socket))]
    (cond
      (and max-outbound-bytes (>= level max-outbound-bytes))
      (do (teardown! state attachment 1008 "dao.stream/outbound-overflow"
                     :ws/outbound-overflow)
          (outcome :dao.stream/closed))

      (and outbound-high-water (:queued-bytes socket) (>= level outbound-high-water))
      (outcome :dao.stream/full)

      :else
      (let [result (send-result socket payload)]
        (when (and max-outbound-bytes (nil? (:queued-bytes socket))
                   (= :dao.stream/ok (:dao.stream/outcome result)))
          (swap! state update :sent-bytes (fnil + 0) (payload-size payload)))
        result))))


(defn- deposit!
  [state event]
  (let [target (:deposit @state)
        result (stream/append! (:dao.stream/handle target) event)]
    ;; A host promised this medium admits all boundary events.  A failed
    ;; deposit is therefore terminal, rather than a reason to retain a hidden
    ;; inbox or silently discard the event.
    (when-not (= :dao.stream/ok (:dao.stream/outcome result))
      (let [socket (:socket @state)]
        (swap! state assoc :phase :closed)
        (invoke-close! socket 1000 "dao.stream/deposit-failed")))
    result))


(defn- emit!
  "Deposit one boundary event.  The four-argument form carries a payload and
   always keys it, because nil is an ordinary portable value: a consumer must be
   able to tell `value nil` from `no value` with `contains?`."
  ([state attachment event]
   (deposit! state {:ws/attachment attachment :ws/event event}))
  ([state attachment event value]
   (deposit! state {:ws/attachment attachment :ws/event event :ws/value value})))


(defn- emit-reason!
  [state attachment event reason]
  (deposit! state {:ws/attachment attachment :ws/event event :ws/reason reason}))


(defn- terminal!
  [state attachment event]
  (let [emit? (volatile! false)]
    (swap! state
           (fn [s]
             (if (:terminal? s)
               s
               (do (vreset! emit? true)
                   (assoc s :terminal? true :phase :closed)))))
    (when @emit? (emit! state attachment event))))


(deftype WsHandle
  [state descriptor attachment codec]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor descriptor
     :dao.stream/identity (:dao.stream/identity descriptor)})


  stream/IDaoStreamWriter

  (append!
    [_ value]
    (let [{:keys [phase socket] :as s} @state]
      (cond
        (= :closed phase) (outcome :dao.stream/closed)
        (not= :open phase) (outcome :dao.stream/full)
        (not ((:ws/portable-value? codec) value)) (outcome :dao.stream/invalid-value)
        :else (let [payload (try
                              ((:ws/encode codec) {:ws/frame :ws/value
                                                   :ws/value value})
                              (catch #?(:cljd Object :clj Throwable :cljs :default) _
                                ::unencodable))]
                (if (= ::unencodable payload)
                  (outcome :dao.stream/invalid-value)
                  (try
                    (send-bounded! state attachment s socket payload)
                    (catch #?(:cljd Object :clj Throwable :cljs :default) _
                      (outcome :dao.stream/transport-error))))))))


  stream/IDaoStreamClosable

  (close!
    [_]
    (let [socket (volatile! nil)]
      (swap! state
             (fn [s]
               (if (= :closed (:phase s))
                 s
                 (do (vreset! socket (:socket s)) (assoc s :phase :closed)))))
      (when (= :failed (invoke-close! @socket 1000 "dao.stream/detached"))
        (emit-reason! state attachment :ws/error :ws/close-failure))
      (outcome :dao.stream/ok))))


(defn close-ended!
  "Transport-specific close for a serving composition whose source ended.

   Generic `stream/close!` is a reattachable detachment.  This helper sends the
   WebSocket ended signal (4000 / `dao.stream/ended`) so the peer can deposit
   `:ws/ended`.  The host close callback still owns exactly-once terminal event
   delivery, just as it does for generic close."
  [handle]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)
        socket (volatile! nil)]
    (swap! state
           (fn [s]
             (if (= :closed (:phase s))
               s
               (do (vreset! socket (:socket s)) (assoc s :phase :closed)))))
    (when (= :failed (invoke-close! @socket ended-close-code "dao.stream/ended"))
      (emit-reason! state attachment :ws/error :ws/close-failure))
    (outcome :dao.stream/ok)))


(defn- new-handle
  [descriptor attachment target phase socket codec bounds]
  (let [state (atom {:phase phase :socket socket :deposit target
                     :resolution? false :terminal? false :bounds bounds})]
    [(WsHandle. state descriptor attachment codec) state]))


(defn- teardown!
  "Deposit the `:ws/error` diagnostic, close the phase, and ask the host to
   close with `code`.  The host close callback still owns the terminal event."
  [state attachment code reason diagnostic]
  (emit-reason! state attachment :ws/error diagnostic)
  (swap! state assoc :phase :closed)
  (invoke-close! (:socket @state) code reason))


(defn- protocol-failure!
  [state attachment]
  (teardown! state attachment protocol-close-code "dao.stream/protocol-error"
             :ws/decode-failure))


(defn- frame-too-large!
  [state attachment]
  (teardown! state attachment 1009 "dao.stream/frame-too-large" :ws/frame-too-large))


(defn opened!
  "Adapter entry: the host socket itself is open -- for a client, its own
   `connect!` seam calls this the moment the underlying connection
   establishes, with no wire frame of ours to wait for; for a server,
   `accept-slot!` sets `:open` directly and this stays a guarded no-op
   for it.  There is no admission handshake any more: a served identity's
   presence is the mirror's own answer, per identity, once attached."
  [handle]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)
        emit? (volatile! false)]
    (swap! state (fn [s]
                   (if (and (= :connecting (:phase s)) (not (:resolution? s)))
                     (do (vreset! emit? true) (assoc s :phase :open :resolution? true))
                     s)))
    (when @emit? (emit! state attachment :ws/opened))))


(defn- deliver!
  "Decode one inbound frame payload with the handle's codec and dispatch its
   envelope.  Wire validation only; application values are never judged.

   There is no admission handshake any more, so a value frame can outrun
   the server's own accept-slot! (the client's socket opens and its
   append! gate is live from that moment, ahead of this endpoint's own
   acceptor tick).  A frame arriving while still `:pending` is that race,
   not a protocol violation: it is queued and replayed once `accept-slot!`
   installs the real deposit target and opens the handle.

   Deciding the phase and, when `:pending`, queuing the frame is one
   `swap!`: reading the phase and mutating the queue as two separate
   steps would let `accept-slot!` flip the phase and replay the queue
   in between them, so this frame queues into a queue nobody replays
   again and is lost.  The atomic decision instead tells this call
   whether it must deposit itself (the phase had already opened by the
   time the swap committed) or was safely filed for `accept-slot!`.

   The bounds are judged on the raw payload the peer sent: a frame over
   `:ws/max-frame-bytes` fails before decode, and a frame that would take
   the queue over `:ws/max-pending-frames` or `:ws/max-pending-bytes` is
   the fourth action of the same swap, `:overflow`, which closes the phase
   there so no later frame queues behind it."
  [handle payload]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)
        codec (.-codec ^WsHandle handle)
        {:ws/keys [max-frame-bytes max-pending-frames max-pending-bytes]} (:bounds @state)]
    (try
      (let [size (payload-size payload)]
        (if (and max-frame-bytes (> size max-frame-bytes))
          (frame-too-large! state attachment)
          (let [frame ((:ws/decode codec) payload)]
            (if-not (and (= :ws/value (:ws/frame frame))
                         (contains? frame :ws/value)
                         ((:ws/portable-value? codec) (:ws/value frame)))
              (protocol-failure! state attachment)
              (let [value (:ws/value frame)
                    action (volatile! nil)]
                (swap! state
                       (fn [s]
                         (case (:phase s)
                           :open (do (vreset! action :open) s)
                           (:pending :replaying)
                           (let [queued (+ (:pending-bytes s 0) size)]
                             (if (or (and max-pending-frames
                                          (>= (count (:pending-frames s)) max-pending-frames))
                                     (and max-pending-bytes (> queued max-pending-bytes)))
                               (do (vreset! action :overflow) (assoc s :phase :closed))
                               (do (vreset! action :pending)
                                   (-> s
                                       (update :pending-frames (fnil conj []) value)
                                       (assoc :pending-bytes queued)))))
                           (do (vreset! action :else) s))))
                (case @action
                  :open (emit! state attachment :ws/payload value)
                  :pending nil
                  :overflow (teardown! state attachment 1013 "dao.stream/pending-overflow"
                                       :ws/pending-overflow)
                  :else (protocol-failure! state attachment)))))))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _
        (protocol-failure! state attachment)))))


(defn receive!
  "Adapter entry for one text WebSocket message.  A text frame on a binary
   profile is a protocol failure — the transport reads the frame's declared
   kind, never its content."
  [handle text]
  (if (= :text (:ws/frame-kind (.-codec ^WsHandle handle)))
    (deliver! handle text)
    (protocol-failure! (.-state ^WsHandle handle)
                       (.-attachment ^WsHandle handle))))


(defn receive-binary!
  "Adapter entry for one binary WebSocket message (a host byte payload):
   byte[] on the JVM, Uint8Array on ClojureScript, Uint8List on Dart.  A
   binary frame on a text profile is a protocol failure."
  [handle bytes]
  (if (= :binary (:ws/frame-kind (.-codec ^WsHandle handle)))
    (deliver! handle bytes)
    (protocol-failure! (.-state ^WsHandle handle)
                       (.-attachment ^WsHandle handle))))


(defn closed!
  "Adapter entry for host close completion.  The terminal event is guarded so
   close/error races cannot duplicate it."
  [handle code _reason]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)]
    (when-not (:resolution? @state)
      (swap! state assoc :resolution? true)
      (emit! state attachment :ws/transport-error))
    (terminal! state attachment (if (= ended-close-code code) :ws/ended :ws/closed))))


(defn adapter
  "The only callback-shaped value, for a host socket adapter while wiring its
   private listener.  It is not a DaoStream API and never reaches consumers.
   `:opened!` is the host's own connection-established signal (a client's
   `connect!` seam calls it once the socket opens, with no admission wire
   frame to wait for; a server socket is already open when accepted, so
   this stays a no-op there).  `:message!` receives text frames and
   `:binary!` binary frames — the two typed facts a host reports without
   inspecting content — and `:ws/codec` is the connection's profile, so a
   host `:connect!` seam can negotiate the selected subprotocol without
   any second channel.  `:ws/max-frame-bytes` is the composition's frame
   bound (nil for none), so a host that reassembles fragments stops
   buffering past it and calls `:too-large!`, the same teardown as an
   oversize whole frame."
  [handle]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)]
    {:opened! #(opened! handle)
     :message! #(receive! handle %)
     :binary! #(receive-binary! handle %)
     :closed! #(closed! handle %1 %2)
     :too-large! #(frame-too-large! state attachment)
     :ws/codec (.-codec ^WsHandle handle)
     :ws/max-frame-bytes (get-in @state [:bounds :ws/max-frame-bytes])
     :error! #(emit-reason! state attachment :ws/error :ws/socket-error)}))


(defn make-attacher
  "Construct the host-composed unary `attach!` entry point.

   `:connect!` receives the descriptor and the private adapter map, starts
   connection establishment, and synchronously returns a raw-socket adapter
   containing `:send!` and `:close!`.  It must not wait for the peer.

   `:codec` selects the wire profile explicitly (default: Transit).  The
   offer is exactly the selected subprotocol, so a peer that does not speak
   it fails the handshake rather than being downgraded.

   The byte and frame bounds (`:ws/max-frame-bytes`, `:ws/max-pending-frames`,
   `:ws/max-pending-bytes`, `:ws/outbound-high-water`,
   `:ws/max-outbound-bytes`) are optional positive integers; nil is
   unbounded.  The seam may carry `:queued-bytes`, a no-argument function
   answering the host's outbound backlog; without it the outbound bound is a
   cumulative quota of encoded bytes sent since open."
  [{:keys [traffic admission connect! codec] :as config}]
  (let [target (checked-target traffic admission)
        codec (checked-codec codec)
        bounds (checked-bounds config)]
    (when-not (fn? connect!)
      (throw (ex-info "WebSocket host composition requires :connect!" {:config config})))
    (fn attach!
      [descriptor]
      (if-not (descriptor? descriptor codec)
        (outcome :dao.stream/invalid-descriptor)
        (let [id (attachment-id)
              [handle state] (new-handle descriptor id target :connecting nil codec bounds)]
          (try
            (let [socket (connect! descriptor (adapter handle))]
              (if (and (map? socket) (fn? (:send! socket)) (fn? (:close! socket)))
                (do (swap! state assoc :socket socket)
                    {:dao.stream/outcome :dao.stream/ok
                     :dao.stream/handle handle
                     :dao.stream/attachment id})
                (outcome :dao.stream/transport-error)))
            (catch #?(:cljd Object :clj Throwable :cljs :default) _
              (outcome :dao.stream/transport-error))))))))


;; Server acceptance is intentionally an endpoint composition object, not a
;; global transport directory.  Its slots are supplied and owned by the host.
;; One descriptor, not a served-path table: the mirror answers not-found
;; per identity now, so this transport no longer routes an upgrade by path
;; at all -- every upgrade this endpoint's own codec negotiation accepts is
;; accepted, and what a client can reach through it is entirely the
;; mirror's own table.  The byte and frame bounds are those of
;; `make-attacher`, applied to every accepted connection; the pending bounds
;; are what keeps a peer from growing the pre-acknowledgement queue.
(defn make-endpoint
  [{:keys [descriptor control control-admission slots codecs] :as config}]
  (let [control-target (checked-target control control-admission)
        codecs (or codecs [transit/profile])
        bounds (checked-bounds config)]
    (when-not (seq slots)
      (throw (ex-info "WebSocket endpoint needs handoff slots" {:config config})))
    (when-not (and (seq codecs) (every? codec-profile? codecs))
      (throw (ex-info "WebSocket endpoint needs at least one codec profile" {:codecs codecs})))
    (when-not (apply distinct? (map :ws/subprotocol codecs))
      (throw (ex-info "WebSocket endpoint codec subprotocols must be distinct" {:codecs codecs})))
    (when-not (= :portable-values (:value-domain control-admission))
      (throw (ex-info "endpoint control medium must carry portable values" {:admission control-admission})))
    ;; The one descriptor crosses under every profile the endpoint speaks,
    ;; or the profile that cannot carry it must not be offered.
    (when-not (every? #(descriptor? descriptor %) codecs)
      (throw (ex-info "invalid served descriptor" {:descriptor descriptor})))
    (doseq [slot slots]
      (checked-target (:offer slot) (:offer-admission slot))
      (checked-target (:ack slot) (:ack-admission slot))
      (when-not (and (= 1 (get-in slot [:offer-admission :capacity]))
                     (= :host-values (get-in slot [:offer-admission :value-domain]))
                     (= 1 (get-in slot [:ack-admission :capacity]))
                     (= :host-values (get-in slot [:ack-admission :value-domain]))
                     (some? (:ack-cursor slot)))
        (throw (ex-info "invalid capacity-one WebSocket handoff slot" {:slot slot}))))
    {:config config
     :bounds bounds
     :codecs codecs
     :codec-index (zipmap (map :ws/subprotocol codecs) codecs)
     :state (atom {:control control-target
                   :slots (mapv (fn [slot]
                                  (assoc slot :status :free)) slots)
                   :connections {}})}))


(defn endpoint-state
  [endpoint]
  @(:state endpoint))


(defn- endpoint-target
  [endpoint]
  (:control (endpoint-state endpoint)))


(defn- release-slot!
  "Return one handoff slot to the bounded free pool and forget the connection it
   held.  Releasing drops the retained handle: a released slot's acknowledgement
   is stale by construction, so nothing may resolve through it afterwards."
  [endpoint index attachment]
  (swap! (:state endpoint)
         (fn [s]
           (-> s
               (update-in [:slots index] dissoc
                          :attachment :handle :handle-state :codec :opened-at)
               (assoc-in [:slots index :status] :free)
               (update :connections dissoc attachment)))))


(defn accept-connection!
  "Bounded upgrade callback entry.  An exhausted slot pool is closed
   immediately; otherwise this deposits precisely one host-local offer and
   waits for `endpoint-step` to consume a matching acknowledgement.  There
   is no served-path lookup here any more: every upgrade this endpoint's
   own codec negotiation accepts is accepted, and what a client can reach
   through it is entirely the mirror's own table, answered per identity
   after the connection opens.  `path` is the caller's own routing concern
   (a host bound to more than one endpoint still dispatches an upgrade to
   the right one by it); this transport no longer inspects it.

   The socket seam may carry `:ws/subprotocol`, the subprotocol the host
   negotiated for this upgrade.  An endpoint serves every profile it was
   composed with, concurrently; a named subprotocol it does not speak is a
   refused handshake, never a downgrade, and an absent name is the Transit
   default for sockets that negotiated nothing (direct composition and
   tests)."
  ([endpoint path socket] (accept-connection! endpoint path socket nil))
  ([endpoint _path socket now]
   (let [descriptor (:descriptor (:config endpoint))
         offered (:ws/subprotocol socket)
         codec (if (nil? offered)
                 transit/profile
                 (get (:codec-index endpoint) offered))]
     (cond
       (nil? codec)
       (do (invoke-close! socket protocol-close-code "dao.stream/subprotocol-unsupported")
           {:ws/status :ws/unsupported-subprotocol})

       :else
       (let [state (:state endpoint)
             chosen (volatile! nil)]
         ;; Claim before depositing.  The offer append may synchronously invoke
         ;; host code, so leaving the slot `:free` until after it returns would
         ;; permit a second upgrade callback to overwrite the sole offer.
         (swap! state (fn [s]
                        (if-let [[index slot] (first (keep-indexed (fn [i x]
                                                                     (when (= :free (:status x)) [i x]))
                                                                   (:slots s)))]
                          (do (vreset! chosen [index slot])
                              (assoc-in s [:slots index :status] :reserving))
                          s)))
         (if-not @chosen
           (do (invoke-close! socket 1013 "dao.stream/acceptance-full")
               {:ws/status :ws/full})
           (let [[index slot] @chosen
                 id (attachment-id)
                 [handle hstate] (new-handle descriptor id (endpoint-target endpoint) :pending socket codec
                                             (:bounds endpoint))
                 offer {:ws/attachment id :ws/event :ws/accepted
                        :ws/handle {:dao.stream/handle handle
                                    :dao.stream/surface #{:writer :closable}}}
                 result (stream/append! (:dao.stream/handle (:offer slot)) offer)]
             (if (= :dao.stream/ok (:dao.stream/outcome result))
               (do (swap! state (fn [s]
                                  (-> s
                                      (assoc-in [:slots index :status] :pending)
                                      (assoc-in [:slots index :attachment] id)
                                      (assoc-in [:slots index :handle] handle)
                                      (assoc-in [:slots index :handle-state] hstate)
                                      (assoc-in [:slots index :codec] codec)
                                      (assoc-in [:slots index :opened-at] now)
                                      (assoc-in [:connections id] handle))))
                   {:ws/status :ws/pending :ws/attachment id :ws/handle handle})
               (do (swap! hstate assoc :phase :closed)
                   (invoke-close! socket 1011 "dao.stream/offer-failed")
                   (release-slot! endpoint index id)
                   {:ws/status :ws/offer-failed})))))))))


(defn- valid-ack?
  [ack attachment]
  (and (map? ack)
       (= attachment (:ws/attachment ack))
       (= :ws/accept (:ws/command ack))
       (writer-target? (:ws/deposit ack))
       (admission? (:ws/admission ack))))


(defn- drain-pending!
  "Deposit queued frames in arrival order until the queue is empty, then
   open the handle.  The handle stays `:replaying` throughout, so
   `deliver!` keeps queuing rather than depositing; the swap that finds
   the queue empty is the same one that opens the phase.  Only this
   drainer deposits while replaying, so each frame deposits exactly once
   and no later frame can pass an older one."
  [hstate attachment]
  (loop []
    (let [batch (volatile! nil)]
      (swap! hstate
             (fn [s]
               (let [queued (:pending-frames s)]
                 (vreset! batch queued)
                 (cond-> (assoc s :pending-frames [] :pending-bytes 0)
                   (and (empty? queued) (= :replaying (:phase s)))
                   (assoc :phase :open)))))
      (when (seq @batch)
        (doseq [value @batch]
          (emit! hstate attachment :ws/payload value))
        (recur)))))


(defn- accept-slot!
  "The client is never told over the wire: there is no admission frame any
   more, and nothing needs one -- `opened!` already ran when its own
   socket opened, ahead of any acknowledgement.  This installs the
   deposit target the acknowledgement carries, replays the value frames
   `deliver!` queued while still pending (the client's own socket can
   outrun this endpoint's acceptor tick) via `drain-pending!`, and
   releases the slot."
  [endpoint index slot ack]
  (let [hstate (:handle-state slot)]
    (swap! hstate assoc :deposit (:ws/deposit ack) :phase :replaying
           :resolution? true)
    (drain-pending! hstate (:attachment slot))
    (release-slot! endpoint index (:attachment slot))))


(defn endpoint-step
  "Poll each configured acknowledgement slot once, without waiting or
   self-scheduling.  Returns the endpoint after retaining its next cursors."
  [endpoint now]
  ;; A pending connection can be lost before any acknowledgement: the peer
  ;; closes, the wire protocol fails, or its own handle is closed.  Each of
  ;; those paths closes the handle's state and deposits its terminal event;
  ;; this is where the endpoint observes that and returns the slot, so bounded
  ;; admission is a live bound rather than a monotonically shrinking one.
  (doseq [[index slot] (map-indexed vector (:slots (endpoint-state endpoint)))
          :when (and (= :pending (:status slot))
                     (= :closed (:phase @(:handle-state slot))))]
    (release-slot! endpoint index (:attachment slot)))
  (doseq [[index slot] (map-indexed vector (:slots (endpoint-state endpoint)))
          :when (= :pending (:status slot))]
    (let [next (stream/next (:dao.stream/handle (:ack slot)) (:ack-cursor slot))]
      (when (= :dao.stream/ok (:dao.stream/outcome next))
        (swap! (:state endpoint) assoc-in [:slots index :ack-cursor] (:dao.stream/cursor next))
        (let [ack (:dao.stream/value next)]
          (cond
            (not= (:attachment slot) (:ws/attachment ack)) nil
            (valid-ack? ack (:attachment slot)) (accept-slot! endpoint index slot ack)
            :else (let [hstate (:handle-state slot)]
                    (swap! hstate assoc :phase :closed)
                    (invoke-close! (:socket @hstate) protocol-close-code "dao.stream/invalid-ack")
                    (terminal! hstate (:attachment slot) :ws/closed)
                    (release-slot! endpoint index (:attachment slot))))))))
  ;; Expiry is deliberately clock-domain explicit.  An endpoint composition
  ;; may set `:opened-at` when it accepts an offer; this transport does not
  ;; invent wall-clock time or a scheduler.
  (when-let [expiry (:expiry-ms (:config endpoint))]
    (doseq [[index slot] (map-indexed vector (:slots (endpoint-state endpoint)))
            :when (and (= :pending (:status slot)) (:opened-at slot)
                       (<= (+ (:opened-at slot) expiry) now))]
      (let [hstate (:handle-state slot)]
        (swap! hstate assoc :phase :closed)
        (invoke-close! (:socket @hstate) 1000 "dao.stream/acceptance-expired")
        (terminal! hstate (:attachment slot) :ws/closed)
        (release-slot! endpoint index (:attachment slot)))))
  endpoint)


(defn endpoint-stop!
  "Endpoint-wide stop (dao.stream.ws.md Serving): close every connection
   the endpoint still owns before acknowledgement -- each pending slot's
   handle phase closes, the host is asked to close it with 1001
   `dao.stream/endpoint-stopped`, its terminal :ws/closed is deposited on
   the control medium, and the slot returns to the free pool.
   Acknowledged connections belong to their composition and are not
   touched here.  Idempotent: a second call finds nothing pending.  The
   host listener is the host's to release (its `unbind!`).  Answers the
   endpoint."
  [endpoint]
  (doseq [[index slot] (map-indexed vector (:slots (endpoint-state endpoint)))
          :when (= :pending (:status slot))]
    (let [hstate (:handle-state slot)]
      (swap! hstate assoc :phase :closed)
      (invoke-close! (:socket @hstate) 1001 "dao.stream/endpoint-stopped")
      (terminal! hstate (:attachment slot) :ws/closed)
      (release-slot! endpoint index (:attachment slot))))
  endpoint)

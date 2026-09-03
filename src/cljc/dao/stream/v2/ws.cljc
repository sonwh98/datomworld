(ns dao.stream.v2.ws
  "The DaoStream v2 WebSocket protocol boundary.

   This namespace deliberately knows no WebSocket library.  A host adapter owns
   its raw socket and supplies the small synchronous `:connect!`, `:send!`, and
   `:close!` functions below.  The public DaoStream surface is consequently
   callback-free, non-waiting, and portable; socket callbacks enter only through
   the adapter map returned to the host while it constructs a connection."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.transit :as transit]
            [clojure.string :as str]))


(def transport-type :dao.stream/ws)
(def subprotocol "dao.stream.v2.transit-json")
(def ended-close-code 4000)
(def protocol-close-code 4002)
(def disclaim-close-code 4004)


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
   particular connection, and contains only portable reachability data."
  [x]
  (and (map? x)
       (= transport-type (:dao.stream/type x))
       (string? (:dao.stream/identity x))
       (string? (:ws/host x))
       (integer? (:ws/port x))
       (pos? (:ws/port x))
       (canonical-path? (:ws/path x))
       (transit/portable-value? x)))


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
  [socket text]
  (try
    (let [x ((:send! socket) text)]
      (cond
        (or (nil? x) (= true x) (= :ok x)) (outcome :dao.stream/ok)
        (= false x) (outcome :dao.stream/full)
        (and (map? x) (:dao.stream/outcome x)) x
        :else (outcome :dao.stream/transport-error)))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      (outcome :dao.stream/transport-error))))


(declare receive! closed! opened! disclaimed!)


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
  [state descriptor attachment]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor descriptor
     :dao.stream/identity (:dao.stream/identity descriptor)})


  stream/IDaoStreamWriter

  (append!
    [_ value]
    (let [{:keys [phase socket]} @state]
      (cond
        (= :closed phase) (outcome :dao.stream/closed)
        (not= :open phase) (outcome :dao.stream/full)
        (not (transit/portable-value? value)) (outcome :dao.stream/invalid-value)
        :else (try
                (send-result socket (transit/encode {:ws/frame :ws/value
                                                     :ws/value value}))
                (catch #?(:cljd Object :clj Throwable :cljs :default) _
                  (outcome :dao.stream/invalid-value))))))


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
  [descriptor attachment target phase socket]
  (let [state (atom {:phase phase :socket socket :deposit target
                     :resolution? false :terminal? false})]
    [(WsHandle. state descriptor attachment) state]))


(defn- protocol-failure!
  [state attachment]
  (emit-reason! state attachment :ws/error :ws/decode-failure)
  (swap! state assoc :phase :closed)
  (invoke-close! (:socket @state) protocol-close-code "dao.stream/protocol-error"))


(defn opened!
  "Adapter entry: the peer's accept control was received (client), or the
   endpoint accepted a matching handoff acknowledgement (server)."
  [handle]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)
        emit? (volatile! false)]
    (swap! state (fn [s]
                   (if (and (= :connecting (:phase s)) (not (:resolution? s)))
                     (do (vreset! emit? true) (assoc s :phase :open :resolution? true))
                     s)))
    (when @emit? (emit! state attachment :ws/opened))))


(defn disclaimed!
  "Adapter entry for the authoritative first `:ws/disclaim` control frame."
  [handle]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)
        emit? (volatile! false)]
    (swap! state (fn [s]
                   (if (and (= :connecting (:phase s)) (not (:resolution? s)))
                     (do (vreset! emit? true) (assoc s :resolution? true :phase :closed))
                     s)))
    (when @emit?
      (emit! state attachment :ws/not-found)
      (invoke-close! (:socket @state) disclaim-close-code "dao.stream/not-found"))))


(defn receive!
  "Adapter entry for one text WebSocket message.  It performs only wire
   validation and envelope translation; application values are never judged."
  [handle text]
  (let [state (.-state ^WsHandle handle)
        attachment (.-attachment ^WsHandle handle)]
    (try
      (let [frame (transit/decode text)]
        (cond
          (and (= :ws/accept (:ws/frame frame)) (= :connecting (:phase @state)))
          (opened! handle)

          (and (= :ws/disclaim (:ws/frame frame)) (= :connecting (:phase @state)))
          (disclaimed! handle)

          (and (= :ws/value (:ws/frame frame))
               (contains? frame :ws/value)
               (= :open (:phase @state))
               (transit/portable-value? (:ws/value frame)))
          (emit! state attachment :ws/payload (:ws/value frame))

          :else (protocol-failure! state attachment)))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _
        (protocol-failure! state attachment)))))


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
   private listener.  It is not a DaoStream API and never reaches consumers."
  [handle]
  {:opened! #(opened! handle)
   :disclaimed! #(disclaimed! handle)
   :message! #(receive! handle %)
   :closed! #(closed! handle %1 %2)
   :error! #(emit-reason! (.-state ^WsHandle handle) (.-attachment ^WsHandle handle)
                          :ws/error :ws/socket-error)})


(defn make-attacher
  "Construct the host-composed unary `attach!` entry point.

   `:connect!` receives the descriptor and the private adapter map, starts
   connection establishment, and synchronously returns a raw-socket adapter
   containing `:send!` and `:close!`.  It must not wait for the peer."
  [{:keys [traffic admission connect!] :as config}]
  (let [target (checked-target traffic admission)]
    (when-not (fn? connect!)
      (throw (ex-info "WebSocket host composition requires :connect!" {:config config})))
    (fn attach!
      [descriptor]
      (if-not (descriptor? descriptor)
        (outcome :dao.stream/invalid-descriptor)
        (let [id (attachment-id)
              [handle state] (new-handle descriptor id target :connecting nil)]
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
(defn make-endpoint
  [{:keys [served control control-admission slots] :as config}]
  (let [control-target (checked-target control control-admission)]
    (when-not (and (map? served) (seq slots))
      (throw (ex-info "WebSocket endpoint needs served paths and handoff slots" {:config config})))
    (when-not (= :portable-values (:value-domain control-admission))
      (throw (ex-info "endpoint control medium must carry portable values" {:admission control-admission})))
    (doseq [[path descriptor] served]
      (when-not (and (= path (:ws/path descriptor)) (descriptor? descriptor))
        (throw (ex-info "invalid served descriptor" {:path path :descriptor descriptor}))))
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
                          :attachment :handle :handle-state :opened-at)
               (assoc-in [:slots index :status] :free)
               (update :connections dissoc attachment)))))


(defn accept-connection!
  "Bounded upgrade callback entry.  A bad path or exhausted slots is closed
   immediately; a valid path deposits precisely one host-local offer and waits
   for `endpoint-step` to consume a matching acknowledgement."
  ([endpoint path socket] (accept-connection! endpoint path socket nil))
  ([endpoint path socket now]
   (let [{:keys [served]} (:config endpoint)
         descriptor (get served path)]
     (cond
       (nil? descriptor)
       (do (send-result socket (transit/encode {:ws/frame :ws/disclaim}))
           (invoke-close! socket disclaim-close-code "dao.stream/not-found")
           {:ws/status :ws/disclaimed})

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
                 [handle hstate] (new-handle descriptor id (endpoint-target endpoint) :pending socket)
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


(defn- accept-slot!
  [endpoint index slot ack]
  (let [hstate (:handle-state slot)
        socket (:socket @hstate)
        target (:ws/deposit ack)]
    (swap! hstate assoc :deposit target :phase :open :resolution? true)
    (let [sent (send-result socket (transit/encode {:ws/frame :ws/accept}))]
      (when-not (= :dao.stream/ok (:dao.stream/outcome sent))
        (swap! hstate assoc :phase :closed)
        (invoke-close! socket 1011 "dao.stream/accept-failed")
        (terminal! hstate (:attachment slot) :ws/closed))
      ;; Either way the endpoint is done with this connection: the composition
      ;; owns an accepted session, and a failed acceptance is already torn down.
      (release-slot! endpoint index (:attachment slot)))))


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

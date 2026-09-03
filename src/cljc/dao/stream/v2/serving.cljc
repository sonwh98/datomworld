(ns dao.stream.v2.serving
  "Host-owned WebSocket serving composition for DaoStream v2.

   This namespace deliberately has no listener, scheduler, transport directory,
   or application protocol.  It wires an already-composed endpoint, its bounded
   handoff media, and a host-owned path table into one explicit driver.  The host
   decides when to call `step!`, supplies its clock, and owns the endpoint start
   and stop effects."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.forward :as forward]
            [dao.stream.v2.ws :as ws]))


(defn- target-handle
  [x]
  (if (and (map? x) (contains? x :dao.stream/handle))
    (:dao.stream/handle x)
    x))


(defn- reader?
  [x]
  (stream/reader? (target-handle x)))


(defn- writer-target?
  [x]
  (and (map? x)
       (stream/writer? (:dao.stream/handle x))
       (contains? (:dao.stream/surface x) :writer)))


(defn- terminal-event?
  [event]
  (contains? #{:ws/closed :ws/ended} (:ws/event event)))


(defn- call-close!
  [f handle]
  (try
    (f handle)
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {:dao.stream/outcome :dao.stream/transport-error})))


(defn- supplied-stream?
  [{:keys [descriptor stream]} path]
  (and (ws/descriptor? descriptor)
       (= path (:ws/path descriptor))
       (stream/reader? stream)))


(defn- valid-slot?
  [slot]
  (and (reader? (:offer-reader slot))
       (some? (:offer-cursor slot))
       (writer-target? (:ack-writer slot))))


(defn- traffic-session
  "Validate the composition-created medium.  The factory must have minted the
   reader cursor before acknowledgement; accepting first would permit an open
   or payload event to outrun the reader position."
  [offer made]
  (let [{:keys [traffic admission reader cursor]} made
        socket-handle (get-in offer [:ws/handle :dao.stream/handle])]
    (when (and (writer-target? traffic)
               (ws/admission? admission)
               (reader? reader)
               (some? cursor))
      {:socket-handle socket-handle
       :traffic traffic
       :admission admission
       :traffic-reader (target-handle reader)
       :traffic-cursor cursor})))


(defn make-serving
  "Construct an inert serving composition.  It starts only when `start!` is
   called, and advances only when the host calls `step!` with an explicit time.

   Required config is deliberately concrete composition data:

   * `:endpoint` is a `dao.stream.v2.ws/make-endpoint` value.
   * `:served` maps canonical `:ws/path` strings to
     `{:descriptor <ws descriptor> :stream <reader handle>}`.  It is the
     host's resolution table, not a DaoStream registry.
   * `:control-reader` and `:control-cursor` observe the endpoint's pre-accept
     control medium.
   * `:slots` are in the endpoint's handoff order and contain `:offer-reader`,
     an already-minted `:offer-cursor`, and an `:ack-writer` target.
   * `:make-traffic` receives an accepted offer and returns a newly-created
     per-attachment traffic medium, its declaration, reader, and minted cursor.
   * `:start-endpoint!`, `:stop-endpoint!`, and `:close-ended!` are host policy
     functions.  `:close-ended!` must perform the WebSocket 4000 close; generic
     DaoStream `close!` means only detachment.

   `:inbound-step`, when supplied, is an explicit application interpreter run
   by this driver for non-terminal per-attachment traffic events.  Omitting it
   means inbound payload is deliberately not appended to the served stream."
  [{:keys [endpoint served control-reader control-cursor slots make-traffic
           start-endpoint! stop-endpoint!]
    :as config}]
  (when-not endpoint
    (throw (ex-info "serving composition needs an endpoint" {:config config})))
  (when-not (and (map? served) (seq served)
                 (every? (fn [[path entry]] (supplied-stream? entry path)) served))
    (throw (ex-info "serving composition needs canonical paths and reader streams"
                    {:served served})))
  ;; `ws/make-endpoint` intentionally receives only descriptors while this
  ;; composition additionally owns the source handles.  When given that
  ;; concrete endpoint, reject two subtly different host path tables rather
  ;; than acknowledging an offer for a stream this driver would forward from
  ;; somewhere else.
  (when-let [endpoint-served (get-in endpoint [:config :served])]
    (let [descriptors (into {} (map (fn [[path entry]] [path (:descriptor entry)])) served)]
      (when-not (= endpoint-served descriptors)
        (throw (ex-info "endpoint and serving resolution tables disagree"
                        {:endpoint-served endpoint-served :served descriptors})))))
  (when-not (and (reader? control-reader) (some? control-cursor))
    (throw (ex-info "serving composition needs a control reader and cursor"
                    {:control-reader control-reader :control-cursor control-cursor})))
  (when-not (and (seq slots) (every? valid-slot? slots))
    (throw (ex-info "serving composition needs valid handoff readers and writers"
                    {:slots slots})))
  (when-not (fn? make-traffic)
    (throw (ex-info "serving composition needs a traffic-medium factory" {})))
  (when-not (every? fn? [start-endpoint! stop-endpoint!])
    (throw (ex-info "serving lifecycle policies are required" {})))
  {:config (merge {:endpoint-step ws/endpoint-step
                   :forward-options forward/default-options
                   :close-ended! ws/close-ended!
                   :close-attachment! stream/close!}
                  config)
   ;; This atom is explicit host composition state, not namespace state.  It is
   ;; retained by the returned value and is owned by the host's one driver.
   :state (atom {:lifecycle :new
                 :endpoint endpoint
                 :control-cursor control-cursor
                 :offer-cursors (mapv :offer-cursor slots)
                 :sessions {}})})


(defn state
  "Return the composition's explicit driver state as data."
  [serving]
  @(:state serving))


(defn start!
  "Start the host endpoint once.  The host start function is the only place a
   listener may be bound; no listener or cadence is installed by this namespace."
  [serving]
  (let [run? (volatile! false)]
    (swap! (:state serving)
           (fn [s]
             (if (= :new (:lifecycle s))
               (do (vreset! run? true) (assoc s :lifecycle :starting))
               s)))
    (if-not @run?
      (:start-result (state serving))
      (let [result (try
                     ((:start-endpoint! (:config serving)) (:endpoint (state serving)))
                     (catch #?(:cljd Object :clj Throwable :cljs :default) _
                       {:dao.stream/outcome :dao.stream/transport-error}))]
        (swap! (:state serving) assoc
               :lifecycle (if (= :dao.stream/transport-error (:dao.stream/outcome result))
                            :failed
                            :running)
               :start-result result)
        result))))


(defn- remove-session!
  [serving attachment]
  (swap! (:state serving) update :sessions dissoc attachment))


(defn- close-session!
  [serving attachment]
  (when-let [session (get-in (state serving) [:sessions attachment])]
    (call-close! (:close-attachment! (:config serving)) (:socket-handle session))
    (remove-session! serving attachment)))


(defn stop!
  "Stop the endpoint through the host-owned lifecycle policy.  Established
   attachments are detached, not reported as logical-stream endings; ending a
   served stream is instead handled by the forwarder's `:source-ended` branch."
  [serving]
  (let [stop? (volatile! false)]
    (swap! (:state serving)
           (fn [s]
             (if (contains? #{:new :starting :running :failed} (:lifecycle s))
               (do (vreset! stop? true) (assoc s :lifecycle :stopping))
               s)))
    (if-not @stop?
      (:stop-result (state serving))
      (do
        (doseq [attachment (keys (:sessions (state serving)))]
          (close-session! serving attachment))
        (let [result (try
                       ((:stop-endpoint! (:config serving)) (:endpoint (state serving)))
                       (catch #?(:cljd Object :clj Throwable :cljs :default) _
                         {:dao.stream/outcome :dao.stream/transport-error}))]
          (swap! (:state serving) assoc :lifecycle :stopped :stop-result result)
          result)))))


(defn- offer-descriptor
  [offer]
  (let [handle (get-in offer [:ws/handle :dao.stream/handle])]
    (when (stream/descriptor? handle)
      (let [result (stream/descriptor handle)]
        (when (= :dao.stream/ok (:dao.stream/outcome result))
          (:dao.stream/descriptor result))))))


(defn- acknowledge-offer!
  [serving slot-index offer]
  (let [{:keys [served make-traffic close-attachment!]} (:config serving)
        attachment (:ws/attachment offer)
        socket-handle (get-in offer [:ws/handle :dao.stream/handle])
        descriptor (offer-descriptor offer)
        entry (get served (:ws/path descriptor))
        source (:stream entry)]
    (if-not (and (= :ws/accepted (:ws/event offer))
                 (string? attachment)
                 (stream/writer? socket-handle)
                 (stream/closable? socket-handle)
                 (= (:dao.stream/identity descriptor)
                    (:dao.stream/identity (:descriptor entry))))
      (call-close! close-attachment! socket-handle)
      (let [source-cursor (when source
                            (stream/cursor source
                                           (or (:forward-anchor (:config serving))
                                               stream/anchor-oldest)))
            session (when (= :dao.stream/ok (:dao.stream/outcome source-cursor))
                      (traffic-session offer (make-traffic offer)))]
        (if-not session
          (call-close! close-attachment! socket-handle)
          (let [ack {:ws/attachment attachment
                     :ws/command :ws/accept
                     :ws/deposit (:traffic session)
                     :ws/admission (:admission session)}
                ack-writer (get-in (:config serving) [:slots slot-index :ack-writer])
                appended (stream/append! (:dao.stream/handle ack-writer) ack)]
            (if (= :dao.stream/ok (:dao.stream/outcome appended))
              (swap! (:state serving) assoc-in
                     [:sessions attachment]
                     (assoc session
                            :path (:ws/path descriptor)
                            :identity (:dao.stream/identity descriptor)
                            :source source
                            :forward-state (forward/initial-state
                                             (:dao.stream/cursor source-cursor))))
              (call-close! close-attachment! socket-handle))))))))


(defn- poll-offers!
  [serving]
  (doseq [[index slot] (map-indexed vector (:slots (:config serving)))]
    (let [cursor (get-in (state serving) [:offer-cursors index])
          reader (target-handle (:offer-reader slot))
          result (stream/next reader cursor)]
      (when (= :dao.stream/ok (:dao.stream/outcome result))
        (swap! (:state serving) assoc-in [:offer-cursors index]
               (:dao.stream/cursor result))
        (acknowledge-offer! serving index (:dao.stream/value result))))))


(defn- poll-control!
  [serving]
  (let [{:keys [control-reader]} (:config serving)
        result (stream/next (target-handle control-reader)
                            (:control-cursor (state serving)))]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (swap! (:state serving) assoc :control-cursor (:dao.stream/cursor result))
      (let [event (:dao.stream/value result)]
        ;; Only pre-accept lifecycle belongs in control.  An established
        ;; attachment's lifecycle is observed through its own traffic medium.
        (when (terminal-event? event)
          (remove-session! serving (:ws/attachment event)))))))


(defn- poll-traffic!
  [serving attachment session]
  (let [result (stream/next (:traffic-reader session) (:traffic-cursor session))]
    (case (:dao.stream/outcome result)
      :dao.stream/ok
      (let [event (:dao.stream/value result)]
        (if (terminal-event? event)
          (remove-session! serving attachment)
          (do
            (swap! (:state serving) assoc-in [:sessions attachment :traffic-cursor]
                   (:dao.stream/cursor result))
            ;; This is an interpreter call made by the driver, never a host
            ;; callback.  With no interpreter, requests stay ordinary traffic
            ;; data and are intentionally not appended to the served stream.
            (when-let [interpret (:inbound-step (:config serving))]
              (interpret session event)))))

      :dao.stream/blocked nil

      ;; A traffic gap or terminal reader failure means this composition can no
      ;; longer faithfully associate inbound events with the attachment.
      (close-session! serving attachment))))


(defn- advance-forwarder!
  [serving attachment session]
  (let [result (forward/forward-step (:source session)
                                     (:socket-handle session)
                                     (:forward-state session)
                                     (:forward-options (:config serving)))]
    (case (:status result)
      (:continue :retry)
      (swap! (:state serving) assoc-in [:sessions attachment :forward-state] result)

      :source-ended
      (do
        ;; Generic close! is only detachment.  This injected policy is the
        ;; transport-specific 4000 ended-stream close required by ws protocol.
        (call-close! (:close-ended! (:config serving)) (:socket-handle session))
        (remove-session! serving attachment))

      (close-session! serving attachment))))


(defn step!
  "Advance exactly one host-owned driver tick at `now`.

   Ordering is intentional: transport acceptance/expiry first, then offer and
   control observation, then each established attachment's traffic interpreter
   and forwarder.  This function never waits, schedules itself, or installs a
   callback; cadence is entirely the caller's policy."
  [serving now]
  (when (= :running (:lifecycle (state serving)))
    (let [endpoint (:endpoint (state serving))
          next-endpoint ((:endpoint-step (:config serving)) endpoint now)]
      (when next-endpoint
        (swap! (:state serving) assoc :endpoint next-endpoint)))
    (poll-offers! serving)
    (poll-control! serving)
    ;; Snapshot keys: traffic/lifecycle handling can remove a session during
    ;; this tick without changing which already-admitted sessions are advanced.
    (doseq [attachment (keys (:sessions (state serving)))]
      (when-let [session (get-in (state serving) [:sessions attachment])]
        (poll-traffic! serving attachment session))
      (when-let [session (get-in (state serving) [:sessions attachment])]
        (advance-forwarder! serving attachment session))))
  serving)

(ns yin.repl.v2.serve
  "The server side of the DaoStream v2 Yin REPL — Phase R4.

   `serve!` composes an endpoint and returns immediately with one explicit
   value: the service-lifetime `/repl` stream created at start (D3), the
   boundary control medium, the bounded acceptance handoff pool, the
   composition-owned lifecycle medium with its already-minted cursor, and the
   one shared shell every session evaluates against (D4).  Binding a listener is
   host policy, injected through `yin.repl.v2.host`; every fact about it —
   `:bind-succeeded`, `:bind-failed`, `:upgrade-failed`, `:listener-error`,
   `:stopped` — arrives as plain data on the lifecycle medium, and no host error
   object crosses the boundary.

   `step` is the single server driver: it observes lifecycle, calls the
   transport's `endpoint-step` through `dao.stream.v2.serving`, adopts accepted
   sessions, and advances each one against a single serially threaded REPL
   state.  It never loops on `blocked`, never waits, and never schedules
   itself."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.rpc.ws :as rpc-ws]
            [dao.stream.v2.serving :as serving]
            [dao.stream.v2.ws :as ws]
            [yin.repl.v2.connect :as connect]
            [yin.repl.v2.core :as core]
            [yin.repl.v2.host :as host]))


;; =============================================================================
;; Composition constants
;; =============================================================================

(def service-capacity
  "The `/repl` service-lifetime stream carries no ordinary outbound value in
   this slice, so capacity 1 cannot evict in a correct composition.  It exists
   to anchor the served identity and to make `stop!` observable as `:ws/ended`."
  1)


(def control-capacity 1024)
(def request-capacity 8192)
(def lifecycle-capacity 256)
(def default-slot-count 8)
(def default-bind-host "127.0.0.1")
(def lifecycle-budget 64)


(def eval-operation :op/eval)


(def incomplete-input-code
  "This server's refusal of a well-formed request whose source is an unbalanced
   form.  The envelope owner's malformed-request code would misreport it as a
   codec defect."
  :yin.repl.v2.serve/incomplete-input)


(def wildcard-hosts #{"0.0.0.0" "::" "[::]" "*"})


(def event-key :yin.repl.v2.endpoint/event)
(def value-key :yin.repl.v2.endpoint/value)


(def event-kinds
  "The fixed lifecycle event set.  An unknown kind is surfaced as a diagnostic
   rather than silently ignored or trusted."
  #{:bind-succeeded :bind-failed :upgrade-failed :listener-error :stopped})


(def outbox-event-key :yin.repl.v2.serve/event)
(def text-key :yin.repl.v2.serve/text)


(def portable-admission
  {:retention :evict-oldest :capacity control-capacity :value-domain :portable-values})


(def request-admission
  {:retention :evict-oldest :capacity request-capacity :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


;; =============================================================================
;; Small composition helpers
;; =============================================================================

(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- mint
  [handle anchor]
  (let [result (stream/cursor handle anchor)]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/cursor result))))


(defn- writer-target
  [handle]
  {:dao.stream/handle handle :dao.stream/surface #{:writer}})


(defn- publish
  [endpoint kind text]
  (update endpoint :outbox conj {outbox-event-key kind text-key text}))


(defn take-outbox
  "Return `[entries next-endpoint]`, clearing the publication outbox once."
  [endpoint]
  [(:outbox endpoint) (assoc endpoint :outbox [])])


(defn- deposit-fn
  [lifecycle]
  (fn deposit!
    [kind value]
    (stream/append! lifecycle {event-key kind value-key value})))


;; =============================================================================
;; serve!
;; =============================================================================

(defn- make-slots
  [n]
  (mapv (fn [_]
          (let [offer (buffer 1)
                ack (buffer 1)]
            {:offer offer :ack ack
             :offer-cursor (mint offer stream/anchor-newest)
             :ack-cursor (mint ack stream/anchor-newest)}))
        (range n)))


(defn- endpoint-slots
  [slots]
  (mapv (fn [slot]
          {:offer (writer-target (:offer slot))
           :offer-admission handoff-admission
           :ack (writer-target (:ack slot))
           :ack-admission handoff-admission
           :ack-cursor (:ack-cursor slot)})
        slots))


(defn- serving-slots
  [slots]
  (mapv (fn [slot]
          {:offer-reader (:offer slot)
           :offer-cursor (:offer-cursor slot)
           :ack-writer (writer-target (:ack slot))})
        slots))


(defn- make-traffic
  "One request medium per accepted attachment, capacity 8192, with its cursor
   minted before the acknowledgement is deposited.  One client's eviction
   pressure therefore cannot create another client's request gap."
  [_offer]
  (let [traffic (buffer request-capacity)]
    {:traffic (writer-target traffic)
     :admission request-admission
     :reader traffic
     :cursor (mint traffic stream/anchor-newest)}))


(defn- inert
  "An endpoint value that owns its media and reports why it never bound."
  [base kind value text]
  (let [deposit! (deposit-fn (:lifecycle base))]
    (deposit! kind value)
    (assoc base :status :failed :bind-note text)))


(defn serve!
  "Compose a v2 REPL endpoint and return immediately.

   Every medium and every cursor exists before any binding is attempted:
   the service stream, the boundary control medium, each handoff slot's offer
   and acknowledgement media, and the lifecycle medium.  The returned value is
   the whole of the endpoint's observable state; `step` is the only thing that
   changes it."
  [{:keys [bind-host bind-port advertised-host advertised-port path slots host
           repl identity expiry-ms]
    :or {bind-host default-bind-host slots default-slot-count}}]
  (let [path (connect/repl-target (or path ""))
        service (buffer service-capacity)
        control (buffer control-capacity)
        lifecycle (buffer lifecycle-capacity)
        lifecycle-cursor (mint lifecycle stream/anchor-newest)
        control-cursor (mint control stream/anchor-newest)
        pool (make-slots (max 1 slots))
        advertised-host (or advertised-host
                            (when-not (contains? wildcard-hosts bind-host) bind-host))
        advertised-port (or advertised-port bind-port)
        descriptor {:dao.stream/type ws/transport-type
                    :dao.stream/identity (or identity connect/service-identity)
                    :ws/host (str advertised-host)
                    :ws/port (if (integer? advertised-port) advertised-port 0)
                    :ws/path path}
        base {:service service
              :control control
              :control-cursor control-cursor
              :lifecycle lifecycle
              :lifecycle-cursor lifecycle-cursor
              :lifecycle-ledger :untried
              :slots pool
              :path path
              :descriptor descriptor
              :bind-host bind-host
              :bind-port bind-port
              :resolution nil
              :sessions {}
              :repl (or repl (core/create-state))
              :status :new
              :stop-initiated? false
              :outbox []}]
    (cond
      (nil? advertised-host)
      (inert base :bind-failed
             {:code :yin.repl.v2.endpoint/advertised-host-required
              :message "a wildcard bind requires an explicit advertised host"}
             (str "--host " bind-host " needs an explicit advertised host"))

      ;; Known limit of this slice, stated rather than hidden behind the
      ;; descriptor gate: the advertised descriptor is fixed at `serve!`, so a
      ;; bind to port zero has no advertised port to name.  Serving an
      ;; ephemeral port means constructing the endpoint after `:bind-succeeded`,
      ;; which is a design change to the R4 composition, not a local fix.
      (= 0 advertised-port)
      (inert base :bind-failed
             {:code :yin.repl.v2.endpoint/ephemeral-port-unsupported
              :message (str "an ephemeral bind has no advertised port until it "
                            "binds, and this slice fixes the descriptor at serve!")}
             "--port 0 needs an explicit advertised port in this slice")

      (not (ws/descriptor? descriptor))
      (inert base :bind-failed
             {:code :yin.repl.v2.endpoint/invalid-descriptor
              :message "bind/advertised configuration does not name a servable stream"}
             (str "cannot serve " (pr-str path) " on port " (pr-str bind-port)))

      (not (host/binder? host))
      (inert base :bind-failed
             {:code host/missing-code :message host/missing-text}
             (host/missing-message "--port is not served"))

      :else
      (let [ws-endpoint (ws/make-endpoint {:served {path descriptor}
                                           :control (writer-target control)
                                           :control-admission portable-admission
                                           :slots (endpoint-slots pool)
                                           :expiry-ms expiry-ms})
            deposit! (deposit-fn lifecycle)
            resources (atom nil)
            composition
            (serving/make-serving
              {:endpoint ws-endpoint
               :served {path {:descriptor descriptor :stream service}}
               :control-reader control
               :control-cursor control-cursor
               :slots (serving-slots pool)
               :make-traffic make-traffic
               :start-endpoint!
               (fn [_]
                 (try
                   (let [bound ((:bind! host)
                                {:endpoint ws-endpoint
                                 :bind-host bind-host
                                 :bind-port bind-port
                                 :path path
                                 ;; Exactly the 3-arity `host/websocket`
                                 ;; documents.  A clock-omitting arity would let
                                 ;; host glue stamp a pending acceptance with
                                 ;; nil and silently disable expiry; the host
                                 ;; owns the reading, so it must supply it.
                                 :accept! (fn accept!
                                            [request-path socket now]
                                            (ws/accept-connection! ws-endpoint
                                                                   request-path
                                                                   socket now))
                                 :deposit! deposit!})]
                     (reset! resources bound)
                     {:dao.stream/outcome :dao.stream/ok})
                   (catch #?(:cljd Object :clj Throwable :cljs :default) _
                     ;; A synchronous host bind failure is classified and
                     ;; deposited here; no host error object crosses over.
                     (deposit! :bind-failed
                               {:code :yin.repl.v2.endpoint/bind-threw
                                :message "the host listener failed to bind"})
                     {:dao.stream/outcome :dao.stream/transport-error})))
               :stop-endpoint!
               (fn [_]
                 (if-let [bound @resources]
                   (try
                     ((:unbind! host) bound deposit!)
                     {:dao.stream/outcome :dao.stream/ok}
                     (catch #?(:cljd Object :clj Throwable :cljs :default) _
                       (let [reason {:code :yin.repl.v2.endpoint/unbind-threw
                                     :message "the host listener failed to release"}]
                         (deposit! :listener-error reason)
                         {:dao.stream/outcome :dao.stream/transport-error
                          :dao.stream/diagnostic reason})))
                   ;; `bind!` threw before returning a resource.  No listener
                   ;; exists to release or to deposit a close completion.
                   {:dao.stream/outcome :dao.stream/transport-error
                    :dao.stream/diagnostic
                    {:code :yin.repl.v2.endpoint/never-bound
                     :message "the host listener never returned a resource"}}))})
            endpoint (assoc base
                            :ws-endpoint ws-endpoint
                            :serving composition
                            :deposit! deposit!
                            :resolution {path descriptor}
                            :status :starting)]
        (serving/start! composition)
        endpoint))))


(defn url
  "The descriptor as an operator would type it into `(connect …)`."
  [endpoint]
  (let [d (:descriptor endpoint)]
    (str connect/url-prefix connect/ws-scheme (:ws/host d) ":" (:ws/port d) (:ws/path d))))


;; =============================================================================
;; Lifecycle observation
;; =============================================================================

(defn- lifecycle-transition
  [endpoint kind value]
  (case kind
    :bind-succeeded
    (-> endpoint
        (assoc :status (if (= :stopping (:status endpoint)) :stopping :running))
        (publish :yin.repl.v2.serve/notice
                 (str "Serving " (url endpoint)
                      (when (map? value) (str " (bound " (pr-str value) ")")))))

    :bind-failed
    (-> endpoint
        (assoc :status :failed)
        (publish :yin.repl.v2.serve/notice
                 (str ";; endpoint bind failed: " (pr-str value))))

    :upgrade-failed
    (publish endpoint :yin.repl.v2.serve/notice
             (str ";; upgrade refused: " (pr-str value)))

    :listener-error
    (publish endpoint :yin.repl.v2.serve/notice
             (str ";; listener error: " (pr-str value)))

    :stopped
    (-> endpoint
        (assoc :status :stopped :resolution nil)
        (publish :yin.repl.v2.serve/notice
                 (str "Endpoint stopped: " (pr-str value))))

    (publish endpoint :yin.repl.v2.serve/diagnostic
             (str ";; unknown endpoint event " (pr-str kind)))))


(declare stop!)


(defn- drain-lifecycle
  "Read the lifecycle medium, total over every `next` outcome.  A gap is a fatal
   endpoint-observability failure and triggers shutdown, per R4."
  [endpoint]
  (loop [remaining lifecycle-budget
         endpoint endpoint]
    (if (or (zero? remaining) (nil? (:lifecycle-cursor endpoint)))
      endpoint
      (let [result (stream/next (:lifecycle endpoint) (:lifecycle-cursor endpoint))
            outcome (:dao.stream/outcome result)]
        (case outcome
          :dao.stream/ok
          (let [envelope (:dao.stream/value result)
                kind (get envelope event-key)
                endpoint (assoc endpoint
                                :lifecycle-cursor (:dao.stream/cursor result)
                                :lifecycle-ledger outcome)]
            (recur (dec remaining)
                   (lifecycle-transition endpoint
                                         (when (contains? event-kinds kind) kind)
                                         (get envelope value-key))))

          :dao.stream/gap
          (-> endpoint
              (assoc :lifecycle-cursor (:dao.stream/cursor result)
                     :lifecycle-ledger outcome)
              (publish :yin.repl.v2.serve/notice
                       ";; endpoint lifecycle lost: observability failed, stopping")
              stop!)

          :dao.stream/blocked
          (assoc endpoint :lifecycle-ledger outcome)

          (-> endpoint
              (assoc :lifecycle-ledger outcome)
              (publish :yin.repl.v2.serve/notice
                       (str ";; endpoint lifecycle " (name outcome)))))))))


;; =============================================================================
;; Sessions
;; =============================================================================

(defn- adopt
  "Adopt one accepted session, minting this driver's own cursor on the
   attachment's request medium.  The medium is created per attachment, so the
   oldest anchor loses nothing that arrived before adoption."
  [session]
  {:reader (:traffic-reader session)
   :cursor (mint (:traffic-reader session) stream/anchor-oldest)
   :writer (:socket-handle session)
   :pending-response nil
   :pending-successor nil
   :terminal nil})


(defn- sync-sessions
  [endpoint]
  (let [live (:sessions (serving/state (:serving endpoint)))
        known (:sessions endpoint)
        next-sessions (reduce (fn [m [attachment session]]
                                (assoc m attachment
                                       (or (get known attachment) (adopt session))))
                              {}
                              live)
        joined (remove #(contains? known %) (keys next-sessions))
        departed (remove #(contains? next-sessions %) (keys known))]
    (as-> (assoc endpoint :sessions next-sessions) endpoint
          (reduce (fn [e a]
                    (publish e :yin.repl.v2.serve/notice
                             (str ";; attachment " a " joined")))
                  endpoint joined)
          (reduce (fn [e a]
                    (publish e :yin.repl.v2.serve/notice
                             (str ";; attachment " a " left")))
                  endpoint departed))))


(defn- close-attachment!
  [endpoint attachment reason]
  (when-let [writer (get-in endpoint [:sessions attachment :writer])]
    (stream/close! writer))
  (-> endpoint
      (assoc-in [:sessions attachment :terminal] reason)
      (publish :yin.repl.v2.serve/notice
               (str ";; attachment " attachment " closed: " (name reason)))))


(defn- evaluate
  "Answer one validated request against the one shared shell, serially."
  [repl request]
  (let [id (apply/request-id request)]
    (cond
      (not= eval-operation (apply/request-op request))
      [repl (apply/error-response id :yin.repl.v2.serve/unknown-operation
                                  "This endpoint answers :op/eval only; it does not proxy")]

      (not (string? (first (apply/request-args request))))
      [repl (apply/error-response id :yin.repl.v2.serve/invalid-arguments
                                  "An :op/eval request carries one source string")]

      :else
      (try
        (let [[repl' text] (core/eval-input repl (first (apply/request-args request)))]
          (if (:pending-input repl')
            ;; Line continuation is a terminal concern.  One shared shell means
            ;; an unbalanced request would otherwise prefix the *next*
            ;; attachment's source, so the fragment is refused and dropped here
            ;; rather than retained across an attachment boundary.  The envelope
            ;; was well formed, so the refusal is this server's decision and
            ;; carries this server's code: a client can tell it from a codec
            ;; defect.
            [(assoc repl' :pending-input nil)
             (apply/error-response id incomplete-input-code
                                   "Incomplete input: a request carries one complete form")]
            [repl' (apply/success-response id text)]))
        (catch #?(:cljd Object :clj Throwable :cljs :default) _
          [repl (apply/error-response id :dao.stream.v2.apply/handler-error
                                      "Handler failed")])))))


(defn- deliver-response
  "Append one retained response, advancing the request cursor only when the
   append is accepted.  `full` retries the identical response on a later step
   without re-running the handler."
  [endpoint attachment]
  (let [session (get-in endpoint [:sessions attachment])
        result (stream/append! (:writer session) (:pending-response session))
        outcome (:dao.stream/outcome result)]
    (case outcome
      :dao.stream/ok
      (update-in endpoint [:sessions attachment]
                 assoc
                 :cursor (:pending-successor session)
                 :pending-response nil
                 :pending-successor nil)

      :dao.stream/full
      endpoint

      (-> endpoint
          (update-in [:sessions attachment]
                     assoc
                     :cursor (:pending-successor session)
                     :pending-response nil
                     :pending-successor nil)
          (publish :yin.repl.v2.serve/notice
                   (str ";; response to " attachment " undeliverable: " (name outcome)))
          (close-attachment! attachment outcome)))))


(defn- interpret-element
  [endpoint attachment envelope successor]
  (let [event (get envelope rpc-ws/envelope-event-key)
        advance #(assoc-in % [:sessions attachment :cursor] successor)]
    (cond
      (or (not (map? envelope))
          (not= attachment (get envelope rpc-ws/envelope-attachment-key)))
      (advance endpoint)

      (= rpc-ws/payload-event event)
      (let [request (get envelope rpc-ws/envelope-value-key)]
        (cond
          (apply/request? request)
          (let [[repl response] (evaluate (:repl endpoint) request)]
            (-> endpoint
                (assoc :repl repl)
                (update-in [:sessions attachment] assoc
                           :pending-response response
                           :pending-successor successor)
                (deliver-response attachment)))

          (apply/correlation-id? (apply/request-id request))
          (-> endpoint
              (update-in [:sessions attachment] assoc
                         :pending-response
                         (apply/error-response (apply/request-id request)
                                               :dao.stream.v2.apply/malformed-request
                                               "Malformed request envelope")
                         :pending-successor successor)
              (deliver-response attachment))

          :else
          (-> endpoint
              advance
              (publish :yin.repl.v2.serve/diagnostic
                       (str ";; uncorrelatable request from " attachment)))))

      ;; A terminal event for this attachment retires the session; the serving
      ;; composition observes the same fact on its own cursor.
      (contains? #{:dao.stream.v2.apply/detached :dao.stream.v2.apply/ended}
                 (get rpc-ws/lifecycle-translation event))
      (-> endpoint
          advance
          (assoc-in [:sessions attachment :terminal] event))

      :else (advance endpoint))))


(defn- session-step
  [endpoint attachment]
  (let [session (get-in endpoint [:sessions attachment])]
    (cond
      (or (nil? session) (:terminal session) (nil? (:cursor session))) endpoint

      (:pending-response session) (deliver-response endpoint attachment)

      :else
      (let [result (stream/next (:reader session) (:cursor session))
            outcome (:dao.stream/outcome result)]
        (case outcome
          :dao.stream/ok
          (interpret-element endpoint attachment
                             (:dao.stream/value result)
                             (:dao.stream/cursor result))

          :dao.stream/blocked endpoint

          ;; A defective peer can still overrun its own request medium.  The
          ;; session is closed rather than left with an unanswerable request.
          :dao.stream/gap
          (-> endpoint
              (assoc-in [:sessions attachment :cursor] (:dao.stream/cursor result))
              (publish :yin.repl.v2.serve/notice
                       (str ";; requests lost from " attachment "; closing it"))
              (close-attachment! attachment :dao.stream/gap))

          (close-attachment! endpoint attachment outcome))))))


(defn- advance-sessions
  [endpoint]
  (reduce session-step endpoint (keys (:sessions endpoint))))


;; =============================================================================
;; The driver step
;; =============================================================================

(defn stopped?
  "True when nothing more is owed to this endpoint's shutdown: it reported
   `:stopped`, or it never composed a serving driver at all.

   A refused bind configuration or a missing host package leaves an endpoint
   that owns its media and its reason and nothing else.  No host close
   completion exists to deposit `:stopped` for it, so a shutdown that waited for
   one would spend its whole budget and then report a timeout for a listener
   that never bound."
  [endpoint]
  (or (nil? endpoint)
      (= :stopped (:status endpoint))
      (nil? (:serving endpoint))))


(defn stop!
  "Initiate stop, claiming nothing about completion.

   Closing the service stream is what makes a connected client deposit
   `:ws/ended` rather than `:ws/closed`; the serving composition performs that
   transport-specific close on its next step.  Only the host close completion
   deposits `:stopped`, and only `step` consuming it marks the stop complete and
   releases the resolution-table entry.

   An endpoint with no serving driver is left exactly as it is: it holds no
   listener and no attachment, so there is nothing to initiate, and marking it
   `:stopping` would both claim a stop nobody can complete and erase the status
   that says why it never started."
  [endpoint]
  (if (or (contains? #{:stopping :stopped} (:status endpoint))
          (nil? (:serving endpoint)))
    endpoint
    (do
      (when (:service endpoint)
        (stream/close! (:service endpoint)))
      (assoc endpoint :status :stopping))))


(defn- finish-stop
  [endpoint]
  (if (and (= :stopping (:status endpoint))
           (not (:stop-initiated? endpoint))
           (:serving endpoint))
    (let [result (serving/stop! (:serving endpoint))
          endpoint (assoc endpoint :stop-initiated? true)]
      (if (= :dao.stream/transport-error (:dao.stream/outcome result))
        ;; A synchronous release failure has no later host completion to
        ;; observe.  The composition owns that fact and resolves locally,
        ;; retaining the structured reason in the ordinary notice stream.
        (-> endpoint
            (assoc :status :stopped :resolution nil)
            (publish :yin.repl.v2.serve/notice
                     (str "Endpoint stopped without host completion: "
                          (pr-str (:dao.stream/diagnostic result)))))
        endpoint))
    endpoint))


(defn step
  "Advance the endpoint once at `now`, returning the next endpoint value.

   Ordering is intentional: lifecycle first, so a bind result is known before
   anything claims to be serving; then the transport and serving composition;
   then session adoption; then one bounded step per session against the single
   shared REPL state."
  [endpoint now]
  (if-not endpoint
    endpoint
    (let [endpoint (drain-lifecycle endpoint)]
      (if-not (:serving endpoint)
        ;; An endpoint that never composed a serving driver — a refused bind
        ;; configuration or a missing host package — still owns and reports its
        ;; lifecycle medium; it simply has no sessions to advance.
        endpoint
        (do
          (serving/step! (:serving endpoint) now)
          (-> endpoint
              sync-sessions
              advance-sessions
              finish-stop))))))


(defn summary
  "A serializable summary of endpoint state, for `(repl-state)` and tests."
  [endpoint]
  (when endpoint
    {:status (:status endpoint)
     :url (url endpoint)
     :path (:path endpoint)
     :identity (:dao.stream/identity (:descriptor endpoint))
     :serving? (some? (:resolution endpoint))
     :sessions (vec (sort (keys (:sessions endpoint))))
     :lifecycle {:cursor (:lifecycle-cursor endpoint)
                 :last-outcome (:lifecycle-ledger endpoint)}}))

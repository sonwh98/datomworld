(ns yin.repl.serve
  "The server side of the DaoStream Yin REPL, over the request-and-response
   service of docs/design/dao.stream.remote.md section 5.

   `serve!` composes an endpoint and returns immediately with one explicit
   value: the shared `\"yin.repl/requests\"` and `\"yin.repl/answers\"`
   media a `dao.stream.remote` mirror table names, the boundary control
   medium, the bounded acceptance handoff pool, the composition-owned
   lifecycle medium with its already-minted cursor, and the one shared
   shell every request evaluates against (D4).  Binding a listener is host
   policy, injected through `yin.repl.host`; every fact about it —
   `:bind-succeeded`, `:bind-failed`, `:upgrade-failed`, `:listener-error`,
   `:stopped` — arrives as plain data on the lifecycle medium, and no host
   error object crosses the boundary.

   `step` is the single server driver: it observes lifecycle, drives
   `dao.stream.ws-project/accept-step!` (which itself adopts every accepted
   connection's own private wire channel and runs `dao.stream.remote`'s
   mirror over it, answering every `:dao.stream/cursor`, `/next`,
   `/append!`, and `/descriptor` a connected client's reflections ask), and
   advances the one shared requests/answers pair against a single serially
   threaded REPL state.  It never loops on `blocked`, never waits, and
   never schedules itself.  There is no per-attachment session pool here
   any more: correlation is by the self-minted random id every request and
   answer value carries (`dao.stream.rpc`), not by which WebSocket
   connection carried it, so one shared pair serves every connected client
   exactly as the service convention's toy peer S does."
  (:require [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as ws-project]
            [yin.repl.connect :as connect]
            [yin.repl :as repl]
            [yin.repl.host.common :as host-common]))


;; =============================================================================
;; Composition constants
;; =============================================================================

(def control-capacity 1024)
(def request-capacity 8192)
(def lifecycle-capacity 256)
(def default-slot-count 8)
(def default-bind-host "127.0.0.1")
(def lifecycle-budget 64)
(def request-budget 64)


(def ^:private diagnostic-bounds
  "Bound on operator-facing diagnostic text built from unbounded internal
   state (unvalidated bind config, host-reported lifecycle values)."
  {:depth 3 :items 8 :chars 200})


(def eval-operation :op/eval)


(def incomplete-input-code
  "This server's refusal of a well-formed request whose source is an unbalanced
   form.  The envelope owner's malformed-request code would misreport it as a
   codec defect."
  :yin.repl.serve/incomplete-input)


(def wildcard-hosts #{"0.0.0.0" "::" "[::]" "*"})


(def event-key :yin.repl.endpoint/event)
(def value-key :yin.repl.endpoint/value)


(def event-kinds
  "The fixed lifecycle event set.  An unknown kind is surfaced as a diagnostic
   rather than silently ignored or trusted."
  #{:bind-succeeded :bind-failed :upgrade-failed :listener-error :stopped})


(def outbox-event-key :yin.repl.serve/event)
(def text-key :yin.repl.serve/text)


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


(defn- make-media
  "One request medium per accepted attachment, capacity 8192, with its
   cursor minted before the acknowledgement is deposited, plus the fresh
   channel ring `dao.stream.ws-project/accept-step!` projects deposited
   payload onto and mirror-steps against.  One client's eviction pressure
   therefore cannot create another client's request gap."
  [_offer]
  (let [traffic (buffer request-capacity)]
    {:traffic (writer-target traffic)
     :admission request-admission
     :reader traffic
     :cursor (mint traffic stream/anchor-newest)
     :ring (buffer request-capacity)}))


(defn- inert
  "An endpoint value that owns its media and reports why it never bound."
  [base kind value text]
  (let [deposit! (deposit-fn (:lifecycle base))]
    (deposit! kind value)
    (assoc base :status :failed :bind-note text)))


(defn serve!
  "Compose a REPL endpoint and return immediately.

   Every medium and every cursor exists before any binding is attempted:
   the shared requests and answers media, the boundary control medium,
   each handoff slot's offer and acknowledgement media, and the lifecycle
   medium.  The returned value is the whole of the endpoint's observable
   state; `step` is the only thing that changes it."
  [{:keys [bind-host bind-port advertised-host advertised-port path slots host
           repl identity expiry-ms]
    :or {bind-host default-bind-host slots default-slot-count}}]
  (let [path (connect/repl-target (or path ""))
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
        requests (buffer request-capacity)
        answers (buffer request-capacity)
        requests-cursor (mint requests stream/anchor-oldest)
        table {connect/requests-identity {:handle requests :surface #{:writer}}
               connect/answers-identity {:handle answers :surface #{:reader}}}
        base {:control control
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
              :requests requests
              :answers answers
              :requests-cursor requests-cursor
              :pending-answer nil
              :pending-successor nil
              :host host
              :resources (atom nil)
              :repl (or repl (repl/create-state))
              :status :new
              :stop-initiated? false
              :step-moved? false
              :outbox []}]
    (cond
      (nil? advertised-host)
      (inert base :bind-failed
             {:code :yin.repl.endpoint/advertised-host-required
              :message "a wildcard bind requires an explicit advertised host"}
             (str "binding " bind-host " needs an explicit advertised host"))

      ;; Known limit of this slice, stated rather than hidden behind the
      ;; descriptor gate: the advertised descriptor is fixed at `serve!`, so a
      ;; bind to port zero has no advertised port to name.  Serving an
      ;; ephemeral port means constructing the endpoint after `:bind-succeeded`,
      ;; which is a design change to the R4 composition, not a local fix.
      (= 0 advertised-port)
      (inert base :bind-failed
             {:code :yin.repl.endpoint/ephemeral-port-unsupported
              :message (str "an ephemeral bind has no advertised port until it "
                            "binds, and this slice fixes the descriptor at serve!")}
             "--port 0 needs an explicit advertised port in this slice")

      (not (ws/descriptor? descriptor))
      (inert base :bind-failed
             {:code :yin.repl.endpoint/invalid-descriptor
              :message "bind/advertised configuration does not name a servable stream"}
             (str "cannot serve " (pr-str (data/summarize path diagnostic-bounds))
                  " on port " (pr-str (data/summarize bind-port diagnostic-bounds))))

      (not (host-common/binder? host))
      (inert base :bind-failed
             {:code host-common/missing-code :message host-common/missing-text}
             (host-common/missing-message "--port is not served"))

      :else
      (let [ws-endpoint (ws/make-endpoint {:descriptor descriptor
                                           :control (writer-target control)
                                           :control-admission portable-admission
                                           :slots (endpoint-slots pool)
                                           :expiry-ms expiry-ms})
            deposit! (deposit-fn lifecycle)
            resources (:resources base)
            acceptor (ws-project/make-acceptor
                       {:endpoint ws-endpoint
                        :slots (serving-slots pool)
                        :table table
                        :make-media make-media})
            bind-result
            (try
              (let [bound ((:bind! host)
                           {:endpoint ws-endpoint
                            :bind-host bind-host
                            :bind-port bind-port
                            :path path
                            ;; Exactly the 3-arity `yin.repl.host/websocket`
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
                          {:code :yin.repl.endpoint/bind-threw
                           :message "the host listener failed to bind"})
                {:dao.stream/outcome :dao.stream/transport-error}))
            endpoint (assoc base
                            :ws-endpoint ws-endpoint
                            :acceptor acceptor
                            :deposit! deposit!
                            :resolution {path descriptor}
                            :status (if (= :dao.stream/ok
                                           (:dao.stream/outcome bind-result))
                                      :starting :failed))]
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
        (publish :yin.repl.serve/notice
                 (str "Serving " (url endpoint)
                      (when (map? value)
                        (str " (bound " (pr-str (data/summarize value diagnostic-bounds)) ")")))))

    :bind-failed
    (-> endpoint
        (assoc :status :failed)
        (publish :yin.repl.serve/notice
                 (str ";; endpoint bind failed: "
                      (pr-str (data/summarize value diagnostic-bounds)))))

    :upgrade-failed
    (publish endpoint :yin.repl.serve/notice
             (str ";; upgrade refused: " (pr-str (data/summarize value diagnostic-bounds))))

    :listener-error
    (publish endpoint :yin.repl.serve/notice
             (str ";; listener error: " (pr-str (data/summarize value diagnostic-bounds))))

    :stopped
    (-> endpoint
        (assoc :status :stopped :resolution nil)
        (publish :yin.repl.serve/notice
                 (str "Endpoint stopped: " (pr-str (data/summarize value diagnostic-bounds)))))

    (publish endpoint :yin.repl.serve/diagnostic
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
              (publish :yin.repl.serve/notice
                       ";; endpoint lifecycle lost: observability failed, stopping")
              stop!)

          :dao.stream/blocked
          (assoc endpoint :lifecycle-ledger outcome)

          (-> endpoint
              (assoc :lifecycle-ledger outcome)
              (publish :yin.repl.serve/notice
                       (str ";; endpoint lifecycle " (name outcome)))))))))


;; =============================================================================
;; Requests
;; =============================================================================

(defn- evaluate
  "Answer one validated request against the one shared shell, serially."
  [repl request]
  (let [id (rpc/request-id request)]
    (cond
      (not= eval-operation (rpc/request-op request))
      [repl (rpc/error-answer id :yin.repl.serve/unknown-operation
                              "This endpoint answers :op/eval only; it does not proxy")]

      (not (string? (first (rpc/request-args request))))
      [repl (rpc/error-answer id :yin.repl.serve/invalid-arguments
                              "An :op/eval request carries one source string")]

      :else
      (try
        (let [[repl' text] (repl/eval-input repl (first (rpc/request-args request)))]
          (if (:pending-input repl')
            ;; Line continuation is a terminal concern.  One shared shell means
            ;; an unbalanced request would otherwise prefix the *next*
            ;; caller's source, so the fragment is refused and dropped here
            ;; rather than retained across a caller boundary.  The envelope
            ;; was well formed, so the refusal is this server's decision and
            ;; carries this server's code: a client can tell it from a codec
            ;; defect.
            [(assoc repl' :pending-input nil)
             (rpc/error-answer id incomplete-input-code
                               "Incomplete input: a request carries one complete form")]
            [repl' (rpc/success-answer id text)]))
        (catch #?(:cljd Object :clj Throwable :cljs :default) _
          [repl (rpc/error-answer id :yin.repl.serve/handler-error "Handler failed")])))))


(defn- deliver-answer
  "Append one retained answer, advancing the requests cursor only when the
   append is accepted.  `full` retries the identical answer on a later step
   without re-running the handler."
  [endpoint]
  (let [result (stream/append! (:answers endpoint) (:pending-answer endpoint))
        outcome (:dao.stream/outcome result)]
    (case outcome
      :dao.stream/ok
      (assoc endpoint
             :requests-cursor (:pending-successor endpoint)
             :pending-answer nil
             :pending-successor nil)

      :dao.stream/full
      endpoint

      (-> endpoint
          (assoc :requests-cursor (:pending-successor endpoint)
                 :pending-answer nil
                 :pending-successor nil)
          (publish :yin.repl.serve/notice
                   (str ";; answer undeliverable: " (name outcome)))))))


(defn- advance-requests
  "One bounded sweep of the shared requests/answers pair: a pending answer
   is retried first (its retry runs on the host's cadence, gated on
   nothing), then at most `request-budget` further request/answer cycles.
   Correlation is by the self-minted random id every request and answer
   carries (`dao.stream.rpc`), never by which connection carried it -- the
   mirror already answered every wire-protocol question a reflection
   asked; this is the interpreter over the local requests/answers ends the
   service convention names."
  [endpoint]
  (loop [remaining request-budget
         endpoint endpoint]
    (cond
      (zero? remaining) endpoint

      (:pending-answer endpoint)
      (let [endpoint (deliver-answer endpoint)]
        (if (:pending-answer endpoint)
          endpoint
          (recur (dec remaining) endpoint)))

      :else
      (let [result (stream/next (:requests endpoint) (:requests-cursor endpoint))
            outcome (:dao.stream/outcome result)]
        (case outcome
          :dao.stream/ok
          (let [request (:dao.stream/value result)
                successor (:dao.stream/cursor result)]
            (cond
              ;; rpc's safe-id policy holds on received requests too: an
              ;; apply-shaped request whose id this client could never have
              ;; minted is dropped below, never evaluated.
              (and (rpc/request-value? request)
                   (rpc/safe-id? (rpc/request-id request)))
              (let [[repl' answer] (evaluate (:repl endpoint) request)]
                (recur remaining
                       (assoc endpoint
                              :repl repl'
                              :pending-answer answer
                              :pending-successor successor)))

              ;; Correlatable but malformed: an error answer at least tells
              ;; the caller their own request failed, distinct from silence.
              (and (map? request) (rpc/safe-id? (rpc/request-id request)))
              (recur remaining
                     (assoc endpoint
                            :pending-answer
                            (rpc/error-answer (rpc/request-id request)
                                              :yin.repl.serve/malformed-request
                                              "Malformed request envelope")
                            :pending-successor successor))

              :else
              (recur (dec remaining)
                     (-> endpoint
                         (assoc :requests-cursor successor)
                         (publish :yin.repl.serve/diagnostic
                                  ";; malformed request dropped")))))

          :dao.stream/blocked endpoint

          :dao.stream/gap
          (recur (dec remaining)
                 (-> endpoint
                     (assoc :requests-cursor (:dao.stream/cursor result))
                     (publish :yin.repl.serve/notice
                              ";; requests lost; resuming at the recovery cursor")))

          (publish endpoint :yin.repl.serve/notice
                   (str ";; requests " (name outcome))))))))


;; =============================================================================
;; Stop
;; =============================================================================

(defn stopped?
  "True when nothing more is owed to this endpoint's shutdown: it reported
   `:stopped`, or it never composed an acceptor at all.

   A refused bind configuration or a missing host package leaves an endpoint
   that owns its media and its reason and nothing else.  No host close
   completion exists to deposit `:stopped` for it, so a shutdown that waited for
   one would spend its whole budget and then report a timeout for a listener
   that never bound."
  [endpoint]
  (or (nil? endpoint)
      (= :stopped (:status endpoint))
      (nil? (:acceptor endpoint))))


(defn stop!
  "Initiate stop, claiming nothing about completion.

   Closing the shared requests and answers media is what makes a connected
   client observe `:dao.stream/end` -- the bare source outcome the mirror
   relays verbatim -- on its next read, translated by its RPC client to
   `:dao.stream.rpc/ended`: a permanent conclusion, never a reattachable detach.  Every
   accepted session's own socket closes later, in `finish-stop`, once this
   step's mirror pass has had the chance to deliver that answer over the
   wire; closing it here would race that delivery.  Only the host close
   completion deposits `:stopped`, and only `step` consuming it marks the
   stop complete and releases the resolution-table entry.

   An endpoint with no acceptor is left exactly as it is: it holds no
   listener and no attachment, so there is nothing to initiate, and marking it
   `:stopping` would both claim a stop nobody can complete and erase the status
   that says why it never started."
  [endpoint]
  (if (or (contains? #{:stopping :stopped} (:status endpoint))
          (nil? (:acceptor endpoint)))
    endpoint
    (do
      (stream/close! (:answers endpoint))
      (stream/close! (:requests endpoint))
      (assoc endpoint :status :stopping))))


(def stop-grace-ms
  "How long, in the caller's clock domain, `finish-stop` waits after
   `stop!` closed the shared requests/answers media before it closes any
   accepted session's socket.  Closing the socket too soon would race the
   wire answer a client's outstanding read of the now-ended media is
   owed: that read answers with the media's own `:dao.stream/end`, and
   the client must see that -- `:ended`, never reattachable -- before its
   socket also reports `:ws/closed`, which alone would read as a
   reattachable detach.  A fixed step count cannot bound this: it says
   nothing about the client's own independent poll cadence or the network
   round trip its read takes."
  500)


(defn- finish-stop*
  [endpoint]
  (if (:stop-initiated? endpoint)
    endpoint
    (let [endpoint (assoc endpoint :stop-initiated? true)
          endpoint (do (doseq [[_ session] (ws-project/sessions (:acceptor endpoint))]
                         (stream/close! (:handle session)))
                       endpoint)
          bound @(:resources endpoint)
          result
          (if bound
            (try
              ((:unbind! (:host endpoint)) bound (:deposit! endpoint))
              {:dao.stream/outcome :dao.stream/ok}
              (catch #?(:cljd Object :clj Throwable :cljs :default) _
                (let [reason {:code :yin.repl.endpoint/unbind-threw
                              :message "the host listener failed to release"}]
                  ((:deposit! endpoint) :listener-error reason)
                  {:dao.stream/outcome :dao.stream/transport-error
                   :dao.stream/diagnostic reason})))
            {:dao.stream/outcome :dao.stream/transport-error
             :dao.stream/diagnostic
             {:code :yin.repl.endpoint/never-bound
              :message "the host listener never returned a resource"}})]
      (if (= :dao.stream/transport-error (:dao.stream/outcome result))
        ;; A synchronous release failure has no later host completion to
        ;; observe.  The composition owns that fact and resolves locally,
        ;; retaining the structured reason in the ordinary notice stream.
        (-> endpoint
            (assoc :status :stopped :resolution nil)
            (publish :yin.repl.serve/notice
                     (str "Endpoint stopped without host completion: "
                          (pr-str (data/summarize (:dao.stream/diagnostic result)
                                                  diagnostic-bounds)))))
        endpoint))))


(defn- finish-stop
  [endpoint now]
  (cond
    (not= :stopping (:status endpoint)) endpoint
    (nil? (:acceptor endpoint)) endpoint

    ;; Nothing to wait for: skip the grace period entirely rather than
    ;; delay a stop with no accepted session to deliver anything to.
    (empty? (ws-project/sessions (:acceptor endpoint))) (finish-stop* endpoint)

    ;; Real elapsed time, not a step count: a fixed number of this
    ;; endpoint's own ticks says nothing about whether a client's
    ;; independent poll cadence -- and the network round trip its own
    ;; read takes -- has actually had the chance to run in that time.
    (nil? (:stop-requested-at endpoint))
    (assoc endpoint :stop-requested-at now)

    (< (- now (:stop-requested-at endpoint)) stop-grace-ms)
    endpoint

    :else
    (finish-stop* endpoint)))


;; =============================================================================
;; The driver step
;; =============================================================================

(defn step
  "Advance the endpoint once at `now`, returning the next endpoint value.

   Ordering is intentional: lifecycle first, so a bind result is known before
   anything claims to be serving; then the accepting composition's own
   transport/offer/mirror step; then one bounded sweep of the shared
   requests/answers pair against the single shared REPL state."
  [endpoint now]
  (if-not endpoint
    endpoint
    (let [outbox-before (count (:outbox endpoint))
          endpoint (drain-lifecycle endpoint)]
      (if-not (:acceptor endpoint)
        ;; An endpoint that never composed an acceptor -- a refused bind
        ;; configuration or a missing host package -- still owns and reports
        ;; its lifecycle medium; it simply has no requests to advance.
        endpoint
        (let [before (set (keys (ws-project/sessions (:acceptor endpoint))))
              _ (ws-project/accept-step! (:acceptor endpoint) now)
              departed (remove (ws-project/sessions (:acceptor endpoint)) before)
              endpoint (reduce (fn [endpoint attachment]
                                 (publish endpoint :yin.repl.serve/notice
                                          (str ";; attachment " attachment " left")))
                               endpoint departed)
              cursor-before (:requests-cursor endpoint)
              endpoint (advance-requests endpoint)]
          (-> endpoint
              (assoc :step-moved?
                     (boolean (or (some? (:pending-answer endpoint))
                                  (not= cursor-before (:requests-cursor endpoint))
                                  (> (count (:outbox endpoint)) outbox-before))))
              (finish-stop now)))))))


(defn moved?
  "True when the last `step` moved something a caller's cadence must not
   sleep through: a notice was published, or a computed answer still owes
   an append, which must never wait out a backoff ceiling."
  [endpoint]
  (boolean (or (:step-moved? endpoint) (:pending-answer endpoint))))


(defn summary
  "A serializable summary of endpoint state, for `(repl-state)` and tests."
  [endpoint]
  (when endpoint
    {:status (:status endpoint)
     :url (url endpoint)
     :path (:path endpoint)
     :identity (:dao.stream/identity (:descriptor endpoint))
     :serving? (some? (:resolution endpoint))
     :sessions (if (:acceptor endpoint)
                 (vec (sort (keys (ws-project/sessions (:acceptor endpoint)))))
                 [])
     :lifecycle {:cursor (:lifecycle-cursor endpoint)
                 :last-outcome (:lifecycle-ledger endpoint)}}))

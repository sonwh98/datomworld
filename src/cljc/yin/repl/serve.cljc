(ns yin.repl.serve
  "The server side of the DaoStream Yin REPL, over the request-and-response
   service of docs/design/dao.stream.remote.md section 5.

   `serve!` composes the REPL's two shared media -- `\"yin.repl/requests\"`,
   entered with surface #{:writer} so no remote party reads another
   caller's requests, and `\"yin.repl/answers\"`, entered #{:reader} so no
   remote party forges an answer -- and the one shared shell every request
   evaluates against, and hands the two-entry table to
   `dao.stream.remote-channel/serve` with a portable endpoint specification
   and the host assembly, opaquely.  Endpoint, sessions, mirror, lifecycle
   and stop are the channel composition's, below dao.stream; nothing here
   names a transport.

   `step` is the single server driver: it steps the channel server at the
   driver's `now`, derives this endpoint's status and its notices from the
   server's transitions (bind, refusal, diagnostics, lifecycle gaps,
   departed attachments, stop), and, while running, advances the one
   shared requests/answers pair against a single serially threaded REPL
   state.  It never loops on `blocked`, never waits, and never schedules
   itself.  Correlation is by the self-minted random id every request and
   answer value carries (`dao.stream.rpc`), not by which connection carried
   it, so one shared pair serves every connected client exactly as the
   service convention's toy peer S does.

   `stop!` closes the two media and stops the channel server as ended: a
   connected client's outstanding read of answers is answered with the
   medium's own `end` during the composed 500 ms drain (`repl-bounds`),
   which its RPC client reads as `:ended`, before its connection closes.
   A client whose process is suspended past the profile's idle timeout
   (60 s) is reaped and observes `:detached` on resume.

   A `--port 0` bind advertises the port the host reports once bound;
   until then the endpoint has no URL."
  (:require [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.remote-channel :as remote-channel]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.repl :as repl]
            [yin.repl.connect :as connect]
            [yin.repl.host.common :as host-common]))


;; =============================================================================
;; Composition constants
;; =============================================================================

(def request-capacity 8192)
(def default-bind-host "127.0.0.1")
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


(def outbox-event-key :yin.repl.serve/event)
(def text-key :yin.repl.serve/text)


(def request-admission
  {:retention :evict-oldest :capacity request-capacity :value-domain :portable-values})


(def repl-bounds
  "The REPL's overrides of dao.stream.remote-channel/production-bounds:
   a 500 ms drain so a connected client reads the ended answers medium
   before its connection closes (two polls at the shell's 200 ms backoff
   ceiling plus a round trip)."
  {:drain-grace-ms 500})


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


(defn- publish
  [endpoint kind text]
  (update endpoint :outbox conj {outbox-event-key kind text-key text}))


(defn take-outbox
  "Return `[entries next-endpoint]`, clearing the publication outbox once."
  [endpoint]
  [(:outbox endpoint) (assoc endpoint :outbox [])])


(defn- bind-failed-text
  [value]
  (str ";; endpoint bind failed: "
       (pr-str (data/summarize value diagnostic-bounds))))


(defn- port-unreported-text
  [value]
  (str ";; endpoint bind failed: the host reported no bound port for an "
       "ephemeral bind " (pr-str (data/summarize value diagnostic-bounds))))


;; =============================================================================
;; serve!
;; =============================================================================

(defn url
  "The endpoint as an operator would type it into `(connect …)`; nil while
   the advertised port is 0, an ephemeral bind the host has not yet
   reported."
  [endpoint]
  (let [{:keys [host port path]} (:spec endpoint)]
    (when-not (= 0 port)
      (str connect/url-prefix connect/ws-scheme host ":" port path))))


(defn- inert
  "An endpoint value that owns its media and reports why it never bound:
   `:failed`, its notice already in the outbox for the first `step`."
  [base value text]
  (-> base
      (assoc :status :failed :bind-note text)
      (publish :yin.repl.serve/notice (bind-failed-text value))))


(defn- server-seen
  "What `step` derives notices from: the channel server's status,
   diagnostic count, lifecycle gaps and live session set."
  [server]
  {:status (:status server)
   :diagnostic-count (:diagnostic-count server 0)
   :gaps (:lifecycle-gaps server 0)
   :sessions (set (keep (fn [[id s]] (when-not (:closed? s) id))
                        (remote-channel/sessions server)))})


(defn- refused
  "The endpoint of a channel server `serve` refused synchronously."
  [base server]
  (let [spec (:spec base)]
    (case (:reason server)
      ::remote-channel/no-transport
      (inert base {:code host-common/missing-code :message host-common/missing-text}
             (host-common/missing-message "--port is not served"))

      ::remote-channel/no-port
      (inert base {:code :yin.repl.endpoint/invalid-descriptor
                   :message "bind/advertised configuration does not name a servable stream"}
             (str "cannot serve " (pr-str (data/summarize (:path spec) diagnostic-bounds))
                  " on port " (pr-str (data/summarize (:port spec) diagnostic-bounds))))

      ::remote-channel/bind-failed
      (inert base (or (:detail server)
                      {:code :yin.repl.endpoint/bind-threw
                       :message "the host listener failed to bind"})
             "the host listener failed to bind")

      ::remote-channel/port-unreported
      (-> base
          (assoc :status :failed :bind-note "the host reported no bound port")
          (publish :yin.repl.serve/notice (port-unreported-text (:detail server))))

      (inert base {:code :yin.repl.endpoint/composition-refused
                   :message (str "programming error: the endpoint composition was refused as "
                                 (pr-str (:reason server)))
                   :detail (:detail server)}
             "the endpoint composition was refused"))))


(defn serve!
  "Compose a REPL endpoint and return immediately.

   The two shared media and their cursor exist before any binding is
   attempted; the channel server is composed over them and asked to bind.
   The returned value is the whole of the endpoint's observable state;
   `step` is the only thing that changes it."
  [{:keys [bind-host bind-port advertised-host advertised-port path host repl]
    :or {bind-host default-bind-host}}]
  (let [path (connect/repl-target (or path ""))
        advertised-host (or advertised-host
                            (when-not (contains? wildcard-hosts bind-host) bind-host))
        advertised-port (or advertised-port bind-port)
        requests (buffer request-capacity)
        answers (buffer request-capacity)
        base {:status :new
              :spec {:host (str advertised-host) :port advertised-port :path path
                     :bind-host bind-host :bind-port bind-port}
              :path path
              :server nil
              :server-seen nil
              :requests requests
              :answers answers
              :requests-cursor (mint requests stream/anchor-oldest)
              :pending-answer nil
              :pending-successor nil
              :repl (or repl (repl/create-state))
              :bind-note nil
              :step-moved? false
              :outbox []}]
    (cond
      (nil? advertised-host)
      (inert base
             {:code :yin.repl.endpoint/advertised-host-required
              :message "a wildcard bind requires an explicit advertised host"}
             (str "binding " bind-host " needs an explicit advertised host"))

      (not (host-common/binder? host))
      (inert base {:code host-common/missing-code :message host-common/missing-text}
             (host-common/missing-message "--port is not served"))

      :else
      (let [server (remote-channel/serve
                     {:spec (:spec base)
                      :host host
                      :table {connect/requests-identity
                              {:handle requests :surface #{:writer}}
                              connect/answers-identity
                              {:handle answers :surface #{:reader}}}
                      :bounds repl-bounds})]
        (if (= :starting (:status server))
          (assoc base :status :starting :server server :server-seen (server-seen server))
          (refused base server))))))


;; =============================================================================
;; Observing the channel server
;; =============================================================================

(defn- repl-status
  [channel-status]
  (case channel-status
    :starting :starting
    :serving :running
    :stopping :stopping
    :stopped :stopped
    :refused :failed))


(defn- diagnostic-text
  [{:keys [kind value]}]
  (str (if (= :upgrade-failed kind) ";; upgrade refused: " ";; listener error: ")
       (pr-str (data/summarize value diagnostic-bounds))))


(defn- stopped-text
  [outcome]
  (if (= ::remote-channel/unbind-failed outcome)
    "Endpoint stopped without host completion: unbind-failed"
    (str "Endpoint stopped: " (name outcome))))


(defn- refused-text
  [server]
  (case (:reason server)
    ::remote-channel/bind-failed (bind-failed-text (:detail server))
    ::remote-channel/port-unreported (port-unreported-text (:detail server))
    (str ";; endpoint refused: " (name (:reason server)))))


(defn- notice
  [endpoint text]
  (publish endpoint :yin.repl.serve/notice text))


(defn- observe-server
  "Take `server`, the REPL status it maps to, and publish the notices of
   the transition from what was last seen to it."
  [endpoint server]
  (let [seen (:server-seen endpoint)
        now-seen (server-seen server)
        fresh (- (:diagnostic-count now-seen) (:diagnostic-count seen 0))
        departed (sort (remove (:sessions now-seen) (:sessions seen)))
        changed? (not= (:status seen) (:status now-seen))
        endpoint (assoc endpoint
                        :server server
                        :server-seen now-seen
                        :status (repl-status (:status server)))
        serving? (and changed? (= :serving (:status server)))
        ;; An ephemeral bind is named by the port the host reported; for
        ;; any other bind this is the port already advertised.
        endpoint (cond-> endpoint
                   serving? (assoc-in [:spec :port] (:port (:spec server))))
        endpoint (cond-> endpoint
                   serving? (notice (str "Serving " (url endpoint))))
        endpoint (reduce (fn [endpoint fact] (notice endpoint (diagnostic-text fact)))
                         endpoint
                         (take-last (min fresh (count (:diagnostics server)))
                                    (:diagnostics server)))
        endpoint (cond-> endpoint
                   (> (:gaps now-seen) (:gaps seen 0))
                   (notice ";; endpoint lifecycle gap"))
        endpoint (reduce (fn [endpoint attachment]
                           (notice endpoint (str ";; attachment " attachment " left")))
                         endpoint departed)]
    (cond-> endpoint
      (and changed? (= :refused (:status server)))
      (notice (refused-text server))

      (and changed? (= :stopped (:status server)))
      (notice (stopped-text (get-in server [:stop :outcome]))))))


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
  "True when nothing more is owed to this endpoint's shutdown: it stopped,
   it failed (a refused bind configuration, a missing host package, a bind
   the host refused: nothing listens, or the channel already released it),
   or it never composed a channel server at all.  No host close completion
   exists for those, so a shutdown that waited for one would spend its
   whole budget and then report a timeout for a listener that never bound."
  [endpoint]
  (or (nil? endpoint)
      (contains? #{:stopped :failed} (:status endpoint))
      (nil? (:server endpoint))))


(defn stop!
  "Initiate stop, claiming nothing about completion.

   Closing the shared answers then requests media is what makes a
   connected client observe `:dao.stream/end` -- the bare source outcome
   the mirror relays verbatim -- on its next read, translated by its RPC
   client to `:dao.stream.rpc/ended`: a permanent conclusion, never a
   reattachable detach.  The channel server is stopped as ended, so it
   keeps every session answering for the drain grace before it closes
   them with the ended signal; `step` completes the stop.

   An endpoint with no channel server, or one already stopping, stopped
   or failed, is left exactly as it is: there is nothing to initiate, and
   marking it `:stopping` would claim a stop nobody can complete."
  [endpoint]
  (if (or (nil? (:server endpoint))
          (contains? #{:stopping :stopped :failed} (:status endpoint)))
    endpoint
    (do
      (stream/close! (:answers endpoint))
      (stream/close! (:requests endpoint))
      (-> endpoint
          (update :server remote-channel/stop! {:ended? true})
          (assoc :status :stopping)))))


;; =============================================================================
;; The driver step
;; =============================================================================

(defn step
  "Advance the endpoint once at `now`, returning the next endpoint value.

   Ordering is intentional: the channel server first, so a bind result is
   known before anything claims to be serving and its notices are
   published; then, only while running, one bounded sweep of the shared
   requests/answers pair against the single shared REPL state.  Once
   stopping the media are closed, and advancing them would only answer
   end on every tick."
  [endpoint now]
  (if (or (nil? endpoint) (nil? (:server endpoint)))
    endpoint
    (let [outbox-before (count (:outbox endpoint))
          endpoint (observe-server endpoint
                                   (remote-channel/serve-step (:server endpoint) now))
          cursor-before (:requests-cursor endpoint)
          endpoint (if (= :running (:status endpoint))
                     (advance-requests endpoint)
                     endpoint)]
      (assoc endpoint :step-moved?
             (boolean (or (some? (:pending-answer endpoint))
                          (not= cursor-before (:requests-cursor endpoint))
                          (> (count (:outbox endpoint)) outbox-before)))))))


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
    (let [server (:server endpoint)]
      {:status (:status endpoint)
       :url (url endpoint)
       :path (:path endpoint)
       :serving? (contains? #{:starting :running :stopping} (:status endpoint))
       :sessions (vec (sort (keys (remote-channel/sessions server))))
       :lifecycle {:gaps (:lifecycle-gaps server 0)
                   :diagnostics (:diagnostic-count server 0)}})))

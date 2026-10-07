(ns dao.stream.remote-channel
  "The stepped channel composition of dao.stream.remote.md 3.1: serving
   a table over a WebSocket channel and dialing one to resolve a name
   and attach what it answers, with explicit stop and lifecycle
   observation (3.0).  It composes `dao.stream.ws`,
   `dao.stream.ws-project` and `dao.stream.remote`, and knows no
   vocabulary of any consumer above dao.stream.

   A caller hands down a portable endpoint specification,
   `{:host h :port p :path \"/x\"}` (`:transport :ws` is the only value
   and the default), and a host assembly `{:connect! :bind! :unbind!}`
   of the `yin.repl.host` seam shape.  The concrete ws descriptor is
   formatted here (`descriptor-of`) and never above.

   * `serve` composes endpoint, acceptor and lifecycle medium and binds;
     `serve-step` drives it at `now`; `stop!` initiates an explicit
     stop that `serve-step` completes.
   * `dial` composes the reader's end; `dial-step` drives it at `now`,
     resolving the name and attaching the descriptor answered; `handle`
     is the reflection once attached; `close!` ends the dial.

   Every step is driver-paced: nothing here reads a clock or schedules
   itself, and refusals are data."
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as ws-project]))


;; =============================================================================
;; Production bounds profile
;; =============================================================================

(def production-bounds
  "The production bounds profile: composition data passed down by the
   consumer and overridden by tests.  Each key is validated by the layer
   that enforces it; this profile only sets the values.

   Sizing cross-checks:

   * `:idle-timeout` (60 s) > a follower's poll (5 s) + `give-up-after`
     (15 s): a healthy reader advances its session's mirror cursor at
     every poll and is never reaped; only a reader that stopped asking
     is.
   * `give-up-after` (15 s) >= 2 x poll (5 s): one missed answer is
     loss, never a slow tick.  The dial's link is stepped on every
     driver tick, so expiry is observed within one tick of the deadline.
   * `:step-budget` = `:mirror-budget` = `:capacity` (64): one tick can
     drain a full ring; a continuous producer gets one ring's worth per
     tick and the next session is visited.
   * One session's tick is at most `mirror-budget x min(chase-budget,
     link budget)` = 64 x 8 handle operations; across 64 sessions,
     32 768, acceptable until a shared pool is needed.
   * `:ws/max-pending-bytes` (1 MiB) < `:ws/max-frame-bytes` (4 MiB): a
     single oversize pre-acknowledgement frame fails the frame bound
     (1009) before the pending bound (1013).
   * `:ws/max-outbound-bytes` (16 MiB) caps what a peer that requests
     but never reads costs this host."
  {:step-budget 64
   :mirror-budget 64
   :chase-budget 32
   :max-sessions 64
   :idle-timeout 60000
   :dao.stream.remote/budget 8
   :dao.stream.remote/resend-after nil
   :dao.stream.remote/drain-budget 256
   :dao.stream.remote/max-outstanding 256
   :dao.stream.remote/max-filed 1024
   :dao.stream.remote/give-up-after 15000
   :ws/max-frame-bytes 4194304
   :ws/max-pending-frames 64
   :ws/max-pending-bytes 1048576
   :ws/outbound-high-water 4194304
   :ws/max-outbound-bytes 16777216
   :expiry-ms 15000
   :slot-count 8
   :capacity 64
   :stop-grace-ms 2000})


(def ^:private ws-bound-keys
  [:ws/max-frame-bytes :ws/max-pending-frames :ws/max-pending-bytes
   :ws/outbound-high-water :ws/max-outbound-bytes])


(def ^:private link-policy-keys
  [:dao.stream.remote/budget :dao.stream.remote/resend-after
   :dao.stream.remote/drain-budget :dao.stream.remote/max-outstanding
   :dao.stream.remote/max-filed :dao.stream.remote/give-up-after])


(def ^:private max-diagnostics 8)


;; =============================================================================
;; Address data
;; =============================================================================

(defn descriptor-of
  "The concrete ws descriptor of the portable endpoint specification
   `spec`.  Its identity is the endpoint's address: it names the
   channel, never a served stream."
  [{:keys [host port path]}]
  {:dao.stream/type ws/transport-type
   :dao.stream/identity (str "ws://" host ":" port path)
   :ws/host host
   :ws/port port
   :ws/path path})


(def ^:private ipv6-loopback
  #{"::1" "[::1]" "0:0:0:0:0:0:0:1" "[0:0:0:0:0:0:0:1]"})


(def ^:private digits
  {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})


(defn- octet?
  [s]
  (and (<= 1 (count s) 3)
       (every? #(contains? digits %) s)
       (<= (reduce (fn [n c] (+ (* 10 n) (get digits c))) 0 s) 255)))


(defn loopback-literal?
  "True when `host` is a loopback literal: an IPv4 dotted quad in
   127.0.0.0/8, or the IPv6 loopback ::1, bracketed or not.  A name
   (localhost included) is not a literal, and neither is a host ending
   in a dot, which a resolver would take as a name; `split` drops
   trailing empty parts, so that is checked first."
  [host]
  (and (string? host)
       (or (contains? ipv6-loopback host)
           (let [parts (str/split host #"\.")]
             (and (not= \. (last host))
                  (= 4 (count parts))
                  (= "127" (first parts))
                  (every? octet? parts))))))


;; =============================================================================
;; Small composition helpers
;; =============================================================================

(defn- buffer
  [n]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key n})))


(defn- mint
  [handle anchor]
  (:dao.stream/cursor (stream/cursor handle anchor)))


(defn- writer-target
  [handle]
  {:dao.stream/handle handle :dao.stream/surface #{:writer}})


(defn- admission
  [capacity]
  {:retention :evict-oldest :capacity capacity
   :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn- refused
  [server reason data]
  (merge server {:status :refused :reason reason} data))


(defn- ws-transport?
  [spec]
  (= :ws (:transport spec :ws)))


(defn- link-policy
  "The link policy of `bounds`, checked by `remote/links` now, so an
   invalid bound is a composition error before anything connects."
  [bounds]
  (let [policy (select-keys bounds link-policy-keys)]
    (remote/links policy)
    policy))


(defn- ok-or-nil?
  "True unless `r` is an outcome map that is not ok."
  [r]
  (not (and (map? r)
            (contains? r :dao.stream/outcome)
            (not= :dao.stream/ok (:dao.stream/outcome r)))))


;; =============================================================================
;; Serving
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


(defn- media-maker
  "One traffic medium and one channel ring per accepted connection."
  [capacity]
  (fn [_offer]
    (let [traffic (buffer capacity)]
      {:traffic (writer-target traffic)
       :admission (admission capacity)
       :reader traffic
       :cursor (mint traffic stream/anchor-newest)
       :ring (buffer capacity)})))


(defn- valid-table?
  [table names]
  (and (map? table)
       (every? (fn [[_ e]] (and (map? e) (some? (:handle e)))) table)
       (or (nil? names) (map? names))))


(defn serve
  "Compose the serving end of `spec` and bind its listener through the
   host assembly's `:bind!`.  `:table` and `:names` are the mirror's
   table and name map (dao.stream.remote.md section 2), passed through
   untouched.  `:bounds` merges over `production-bounds`.

   Answers a server value: `:status :starting` with `:spec`,
   `:descriptor` (the formatted ws descriptor), `:endpoint`, `:acceptor`,
   `:listener` (what `:bind!` answered), `:lifecycle`,
   `:lifecycle-cursor` and `:lifecycle-gaps`.  Refusals are data,
   `:status :refused` with `:reason`: `::no-transport` (a transport
   other than :ws, or no `:bind!`), `::no-port` (not a positive
   integer), `::invalid-table`, `::bind-failed` (`:bind!` threw or
   answered an outcome that is not ok).  An invalid bound is the
   composition error the validating layer throws, before anything
   listens."
  [{:keys [spec host table names bounds]}]
  (let [b (merge production-bounds bounds)]
    (cond
      (not (and (ws-transport? spec) (fn? (:bind! host))))
      (refused {:spec spec} ::no-transport {})

      (not (and (integer? (:port spec)) (pos? (:port spec))))
      (refused {:spec spec} ::no-port {})

      (not (valid-table? table names))
      (refused {:spec spec} ::invalid-table {})

      :else
      (let [descriptor (descriptor-of spec)
            capacity (:capacity b)
            pool (make-slots (:slot-count b))
            endpoint (ws/make-endpoint
                       (merge (select-keys b ws-bound-keys)
                              {:descriptor descriptor
                               :control (writer-target (buffer capacity))
                               :control-admission (admission capacity)
                               :slots (mapv (fn [slot]
                                              {:offer (writer-target (:offer slot))
                                               :offer-admission handoff-admission
                                               :ack (writer-target (:ack slot))
                                               :ack-admission handoff-admission
                                               :ack-cursor (:ack-cursor slot)})
                                            pool)
                               :expiry-ms (:expiry-ms b)}))
            acceptor (ws-project/make-acceptor
                       {:endpoint endpoint
                        :slots (mapv (fn [slot]
                                       {:offer-reader (:offer slot)
                                        :offer-cursor (:offer-cursor slot)
                                        :ack-writer (writer-target (:ack slot))})
                                     pool)
                        :table table
                        :names names
                        :make-media (media-maker capacity)
                        :max-sessions (:max-sessions b)
                        :idle-timeout (:idle-timeout b)
                        :step-budget (:step-budget b)
                        :mirror-budget (:mirror-budget b)
                        :chase-budget (:chase-budget b)})
            _ (link-policy b)
            lifecycle (buffer capacity)
            lifecycle-cursor (mint lifecycle stream/anchor-newest)
            deposit! (fn [kind value]
                       (stream/append! lifecycle {:kind kind :value value}))
            server {:spec spec
                    :descriptor descriptor
                    :host host
                    :bounds b
                    :endpoint endpoint
                    :acceptor acceptor
                    :lifecycle lifecycle
                    :lifecycle-cursor lifecycle-cursor
                    :lifecycle-gaps 0
                    :deposit! deposit!}
            bound (try
                    ((:bind! host)
                     {:endpoint endpoint
                      :bind-host (:host spec)
                      :bind-port (:port spec)
                      :path (:path spec)
                      :ws/max-frame-bytes (:ws/max-frame-bytes b)
                      :accept! (fn [request-path socket now]
                                 (ws/accept-connection!
                                   endpoint request-path socket now))
                      :deposit! deposit!})
                    (catch #?(:cljd Object :clj Throwable :cljs :default) _
                      ::threw))]
        (if (or (= ::threw bound) (not (ok-or-nil? bound)))
          (refused (select-keys server [:spec :descriptor]) ::bind-failed {})
          (assoc server :status :starting :listener bound))))))


(defn sessions
  "The acceptor's sessions (ws-project/sessions), or nil."
  [server]
  (some-> (:acceptor server) ws-project/sessions))


(defn- release!
  "Close every session and every pending pre-acknowledgement
   connection; the acceptor stops adopting first."
  [server]
  (when-some [acceptor (:acceptor server)]
    (ws-project/stop! acceptor)
    (ws-project/close-sessions! acceptor))
  (when-some [endpoint (:endpoint server)]
    (ws/endpoint-stop! endpoint))
  server)


(defn- unbind!
  "Release the host listener with the server's deposit!; true when the
   host accepted the request."
  [server]
  (let [f (get-in server [:host :unbind!])]
    (and (fn? f)
         (try
           (ok-or-nil? (f (:listener server) (:deposit! server)))
           (catch #?(:cljd Object :clj Throwable :cljs :default) _
             false)))))


(defn- stopped
  [server outcome]
  (assoc server :status :stopped
         :stop (assoc (:stop server) :outcome outcome)))


(defn- diagnose
  [server fact]
  (update server :diagnostics
          (fn [ds] (vec (take-last max-diagnostics (conj (or ds []) fact))))))


(defn- observe
  "Apply one lifecycle fact the listener deposited (5.1, ok column)."
  [server {:keys [kind value] :as fact}]
  (let [status (:status server)]
    (cond
      (and (= :starting status) (= :bind-succeeded kind))
      (assoc server :status :serving)

      (and (= :starting status) (= :bind-failed kind))
      (refused (release! server) ::bind-failed {:detail value})

      (and (= :stopping status) (= :stopped kind))
      (stopped server :confirmed)

      (= :stopped kind)
      (-> (release! server) (stopped ::host-stopped))

      (contains? #{:listener-error :upgrade-failed} kind)
      (diagnose server fact)

      :else server)))


(defn- lifecycle-lost
  "The lifecycle medium answered gap or end (5.1, gap and end columns)."
  [server end?]
  (case (:status server)
    :starting (do (release! server)
                  (unbind! server)
                  (refused server ::lifecycle-lost {}))
    :serving (if end?
               (-> (release! server) (stopped ::host-stopped))
               (update server :lifecycle-gaps inc))
    :stopping (stopped server ::unconfirmed)
    server))


(def ^:private live #{:starting :serving :stopping})


(defn- drain-lifecycle
  [server]
  (loop [server server]
    (if-not (contains? live (:status server))
      server
      (let [r (stream/next (:lifecycle server) (:lifecycle-cursor server))]
        (case (:dao.stream/outcome r)
          :dao.stream/ok
          (recur (observe (assoc server :lifecycle-cursor (:dao.stream/cursor r))
                          (:dao.stream/value r)))

          :dao.stream/gap
          (recur (lifecycle-lost (assoc server :lifecycle-cursor
                                        (:dao.stream/cursor r))
                                 false))

          :dao.stream/end
          (lifecycle-lost server true)

          server)))))


(defn- begin-stop
  "The first stopping tick (4.3): one last bounded answering pass with
   offers rejected, every session and pending connection closed, then
   the host listener released."
  [server now]
  (ws-project/accept-step! (:acceptor server) now)
  (release! server)
  (let [server (assoc-in server [:stop :since] now)]
    (if (unbind! server)
      server
      (stopped server ::unbind-failed))))


(defn- continue-stop
  "A later stopping tick: newcomers that reached the endpoint before the
   listener went are rejected and closed sessions reaped; then the host's
   `:stopped` fact, or the composed grace, completes the stop."
  [server now]
  (ws-project/accept-step! (:acceptor server) now)
  (ws/endpoint-stop! (:endpoint server))
  (let [server (drain-lifecycle server)]
    (if (and (= :stopping (:status server))
             (>= (- now (get-in server [:stop :since]))
                 (get-in server [:bounds :stop-grace-ms])))
      (stopped server ::unconfirmed)
      server)))


(defn serve-step
  "Advance the server one tick at the driver's `now`.

   Starting or serving: the lifecycle facts first (5.1) -- a bind that
   succeeds makes it `:serving`, one that fails is the `::bind-failed`
   refusal, a gap while starting is the terminal `::lifecycle-lost`
   refusal, a gap while serving is counted under `:lifecycle-gaps`, and
   `:stopped` without `stop!` is `:stopped` with outcome
   `::host-stopped`; `:listener-error` and `:upgrade-failed` are kept,
   the last 8, under `:diagnostics` -- then the acceptor's step.

   Stopping (4.3): the first tick runs the last bounded answering pass,
   closes every session and pending connection and asks the host to
   unbind (`::unbind-failed`, and `:stopped`, when it cannot); later
   ticks complete on the host's `:stopped` fact (outcome `:confirmed`),
   a lifecycle gap (`::unconfirmed`), or `:stop-grace-ms` after the
   first stopping tick (`::unconfirmed`).  Refused and stopped servers
   are answered unchanged."
  [server now]
  (case (:status server)
    (:starting :serving)
    (let [server (drain-lifecycle server)]
      (when (contains? #{:starting :serving} (:status server))
        (ws-project/accept-step! (:acceptor server) now))
      server)

    :stopping
    (if (nil? (get-in server [:stop :since]))
      (begin-stop server now)
      (continue-stop server now))

    server))


(defn stop!
  "Initiate an explicit stop of a starting or serving server: the
   acceptor stops adopting and the server is `:stopping`.  Performs no
   I/O; `serve-step` completes the stop.  Any other server is answered
   unchanged."
  [server]
  (if (contains? #{:starting :serving} (:status server))
    (do (ws-project/stop! (:acceptor server))
        (assoc server :status :stopping :stop {:since nil :outcome nil}))
    server))


;; =============================================================================
;; Dialing
;; =============================================================================

(def ^:private channel-gone
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream.remote/reason :dao.stream.remote/channel-gone})


(defn dial
  "Compose the reader's end toward `spec` over the host assembly's
   `:connect!`, to resolve the name `:name` and attach what it answers.
   `:bounds` merges over `production-bounds`; `:events`, optional, is
   the link's event writer.  Every dial is fresh: its own traffic
   medium, attacher, cursor and channel ring.  Answers `{:status
   :resolving :spec :descriptor :name :dial :handle :identity
   :outcome}`; without `:connect!`, or for a transport other than :ws,
   `{:status :refused :reason ::no-transport}`.  An invalid bound is
   the composition error the validating layer throws."
  [{:keys [spec host bounds events] n :name}]
  (let [b (merge production-bounds bounds)]
    (if-not (and (ws-transport? spec) (fn? (:connect! host)))
      {:status :refused :reason ::no-transport :spec spec :name n}
      (let [capacity (:capacity b)
            traffic (buffer capacity)
            attach! (ws/make-attacher
                      (merge (select-keys b ws-bound-keys)
                             {:traffic (writer-target traffic)
                              :admission (admission capacity)
                              :connect! (:connect! host)}))]
        {:status :resolving
         :spec spec
         :descriptor (descriptor-of spec)
         :name n
         :dial (ws-project/dial
                 (merge (link-policy b)
                        (when events {:dao.stream.remote/events events})
                        {:attach! attach!
                         :traffic (writer-target traffic)
                         :cursor (mint traffic stream/anchor-newest)
                         :ring (buffer capacity)
                         :table {}
                         :step-budget (:step-budget b)
                         :mirror-budget (:mirror-budget b)
                         :chase-budget (:chase-budget b)}))
         :handle nil
         :identity nil
         :outcome nil}))))


(defn- channel-lost?
  [d]
  (let [ch (ws-project/channel (:dial d))]
    (or (:gone? ch)
        (some-> (:project ch) ws-project/closed?))))


(defn- resolve-step
  [d]
  (let [r (ws-project/dial-resolve! (:dial d) (:descriptor d) (:name d))]
    (cond
      (= :dao.stream/ok (:dao.stream/outcome r))
      (let [a (ws-project/dial-reflect! (:dial d) (:dao.stream/descriptor r))]
        (if (= :dao.stream/ok (:dao.stream/outcome a))
          (assoc d :status :attached
                 :handle (:dao.stream/handle a)
                 :identity (:dao.stream/identity r)
                 :outcome r)
          (assoc d :status :lost :outcome a)))

      (and (= :dao.stream/transport-error (:dao.stream/outcome r))
           (true? (:dao.stream/retry? r)))
      (assoc d :outcome r)

      :else
      (assoc d :status :lost :outcome r))))


(defn dial-step
  "Advance the dial one tick at the driver's `now`: the projection, the
   link stepped at `now` and the dial's own mirror first.  Resolving: one
   resolve of the name -- ok attaches the descriptor answered through the
   same channel (`:attached`, `:handle` the reflection, `:identity` the
   source's own); a retryable answer stays; any other answer is `:lost`
   with `:outcome` that answer.  Attached: a channel whose projection
   closed, or whose link expired, is `:lost` with outcome transport-error
   naming channel-gone.  Lost, closed and refused dials are answered
   unchanged."
  [d now]
  (if-not (contains? #{:resolving :attached} (:status d))
    d
    (do (ws-project/dial-step! (:dial d) now)
        (case (:status d)
          :resolving (resolve-step d)
          :attached (if (channel-lost? d)
                      (assoc d :status :lost :outcome channel-gone)
                      d)))))


(defn handle
  "The dial's reflection once attached, else nil."
  [d]
  (:handle d))


(defn close!
  "Close the dial's connection when one was made, and its reflection
   when attached.  Idempotent; answers the dial, `:closed`.  A closed
   dial is not stepped again: a redial composes a fresh one."
  [d]
  (if (= :closed (:status d))
    d
    (do (when-some [h (some-> (:dial d) ws-project/channel :handle)]
          (stream/close! h))
        (when-some [h (:handle d)]
          (when (stream/closable? h)
            (stream/close! h)))
        (assoc d :status :closed))))

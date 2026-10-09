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
     stop that `serve-step` completes, draining for `:drain-grace-ms`
     when the consumer ended its media.
   * `dial` composes the reader's end; `dial-step` drives it at `now`,
     resolving a name and attaching the descriptor answered, or, for
     `:identities`, attaching every identity at once inside `dial`;
     `handle` is a reflection once attached; `detach!` closes only the
     connection, so the reflections observe the loss; `close!` ends the
     dial.

   Writing through a reflection.  A reflection's `append!` answers the
   channel writer's acceptance only, never the peer's: (1) ok means the
   value was accepted for the wire, not that the peer appended or acted
   on it; (2) full means not sent -- the attachment is still
   establishing, the outbound buffer is at its high-water mark, or the
   link is at max-outstanding -- and the identical value is retried
   later; (3) closed means the connection is down and nothing crossed;
   (4) transport-error names not-found (the reflection is gone) or
   no-surface (the entry takes no writes); (5) a value that crossed and
   was appended, whose answer the peer's channel writer refused, stays
   outstanding until `give-up-after`, when the link loses the channel
   as channel-gone although the value crossed.  A consumer that needs
   confirmation correlates an answer on a medium it reads; it never
   consults the append's own wire answer.

   Every step is driver-paced: nothing here reads a clock or schedules
   itself, and refusals are data."
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.remote-route :as route]
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
     but never reads costs this host.
   * `:drain-grace-ms` (0) is how long a stop keeps accepted sessions
     open and answering after its first stopping tick, so a reader of a
     medium the consumer ended observes `end` before its connection
     closes; a consumer that ends media composes one (the REPL: 500)."
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
   :stop-grace-ms 2000
   :drain-grace-ms 0})


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


(defn- valid-entry?
  "An entry declares a non-empty subset of #{:reader :writer} that its
   handle's own natures cover; a narrower surface than the handle's is
   correct (a ring served write-only)."
  [{:keys [handle surface]}]
  (and (some? handle)
       (set? surface)
       (boolean (seq surface))
       (every? #{:reader :writer} surface)
       (or (not (contains? surface :reader)) (stream/reader? handle))
       (or (not (contains? surface :writer)) (stream/writer? handle))))


(defn- table-refusal
  "Nil for a valid table and name map, else the refusal's `:detail`:
   the first invalid entry's identity and declared surface."
  [table names]
  (cond
    (not (map? table)) {:table table}
    (not (or (nil? names) (map? names))) {:names names}
    :else (some (fn [[id e]]
                  (when-not (and (map? e) (valid-entry? e))
                    {:identity id :surface (when (map? e) (:surface e))}))
                table)))


(defn- check-drain-grace!
  "The composition error for a `:drain-grace-ms` that is not a
   non-negative integer, thrown before anything listens."
  [b]
  (let [g (:drain-grace-ms b)]
    (when-not (and (integer? g) (not (neg? g)))
      (throw (ex-info "invalid dao.stream.remote-channel :drain-grace-ms"
                      {:drain-grace-ms g})))))


(defn serve
  "Compose the serving end of `spec` and bind its listener through the
   host assembly's `:bind!`.  `:table` and `:names` are the mirror's
   table and name map (dao.stream.remote.md section 2), passed through
   untouched.  `:bounds` merges over `production-bounds`.

   Each table entry declares a non-empty subset of `#{:reader :writer}`
   as its `:surface`, validated against its handle's own natures: a
   surface the handle lacks is `::invalid-table` with `:detail
   {:identity id :surface S}`.  The spec's optional `:bind-host` and
   `:bind-port` are where the listener binds; the descriptor still
   names `:host` and `:port`.  `:port` 0 is an ephemeral bind: the
   descriptor is provisional until the host's `:bind-succeeded` reports
   the bound port, which `:spec` and `:descriptor` then name; a host
   that reports none is `::port-unreported`.

   Answers a server value: `:status :starting` with `:spec`,
   `:descriptor` (the formatted ws descriptor), `:ephemeral?` (true
   until an ephemeral bind is finalized), `:endpoint`, `:acceptor`,
   `:listener` (what `:bind!` answered), `:lifecycle`,
   `:lifecycle-cursor`, `:lifecycle-gaps` and `:diagnostic-count` (every
   diagnostic kept, monotonic, while `:diagnostics` holds the last 8).
   Refusals are data, `:status :refused` with `:reason`:
   `::no-transport` (a transport other than :ws, or no `:bind!`),
   `::no-port` (not a non-negative integer, or 0 beside a positive
   `:bind-port`), `::invalid-table`,
   `::bind-failed` (`:bind!` threw or answered an outcome that is not
   ok; `:detail` nil).  An invalid bound is the composition error the
   validating layer throws, before anything listens."
  [{:keys [spec host table names bounds]}]
  (let [b (merge production-bounds bounds)]
    (cond
      (not (and (ws-transport? spec) (fn? (:bind! host))))
      (refused {:spec spec} ::no-transport {})

      (not (and (integer? (:port spec)) (not (neg? (:port spec)))
                (not (and (zero? (:port spec))
                          (integer? (:bind-port spec))
                          (pos? (:bind-port spec))))))
      (refused {:spec spec} ::no-port {})

      (some? (table-refusal table names))
      (refused {:spec spec} ::invalid-table {:detail (table-refusal table names)})

      :else
      (let [_ (check-drain-grace! b)
            descriptor (descriptor-of spec)
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
                    :ephemeral? (zero? (:port spec))
                    :host host
                    :bounds b
                    :endpoint endpoint
                    :acceptor acceptor
                    :lifecycle lifecycle
                    :lifecycle-cursor lifecycle-cursor
                    :lifecycle-gaps 0
                    :diagnostic-count 0
                    :deposit! deposit!}
            bound (try
                    ((:bind! host)
                     {:endpoint endpoint
                      :bind-host (or (:bind-host spec) (:host spec))
                      :bind-port (or (:bind-port spec) (:port spec))
                      :path (:path spec)
                      :ws/max-frame-bytes (:ws/max-frame-bytes b)
                      :accept! (fn [request-path socket now]
                                 (ws/accept-connection!
                                   endpoint request-path socket now))
                      :deposit! deposit!})
                    (catch #?(:cljd Object :clj Throwable :cljs :default) _
                      ::threw))]
        (if (or (= ::threw bound) (not (ok-or-nil? bound)))
          (refused (select-keys server [:spec :descriptor]) ::bind-failed
                   {:detail nil})
          (assoc server :status :starting :listener bound))))))


(defn sessions
  "The acceptor's sessions (ws-project/sessions), or nil."
  [server]
  (some-> (:acceptor server) ws-project/sessions))


(defn- release!
  "Close every session and every pending pre-acknowledgement
   connection; the acceptor stops adopting first.  A stop the consumer
   declared `:ended?` closes each session with the ws ended signal
   first (dao.stream.ws.md, Ending a served stream), so the reader's end
   deposits :ws/ended rather than :ws/closed; the generic close after
   it is the handle's idempotent no-op.  Never closes a table handle."
  [server]
  (when-some [acceptor (:acceptor server)]
    (ws-project/stop! acceptor)
    (when (get-in server [:stop :ended?])
      (doseq [[_ session] (ws-project/sessions acceptor)]
        (ws/close-ended! (:handle session))))
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
  (-> server
      (update :diagnostics
              (fn [ds] (vec (take-last max-diagnostics (conj (or ds []) fact)))))
      (update :diagnostic-count (fnil inc 0))))


(defn- observe
  "Apply one lifecycle fact the listener deposited (5.1, ok column)."
  [server {:keys [kind value] :as fact}]
  (let [status (:status server)]
    (cond
      (and (= :starting status) (= :bind-succeeded kind))
      (let [port (:port value)]
        (cond
          (not (:ephemeral? server))
          (assoc server :status :serving)

          (and (integer? port) (pos? port))
          (let [spec (assoc (:spec server) :port port)]
            (ws/endpoint-bound! (:endpoint server) port)
            (assoc server :status :serving :spec spec
                   :descriptor (descriptor-of spec) :ephemeral? false))

          :else
          (do (release! server)
              (unbind! server)
              (refused server ::port-unreported {:detail value}))))

      (and (= :starting status) (= :bind-failed kind))
      (refused (release! server) ::bind-failed {:detail value})

      (and (= :stopping status) (= :stopped kind)
           (some? (get-in server [:stop :released])))
      (stopped server :confirmed)

      (= :stopped kind)
      (-> (release! server) (stopped ::host-stopped))

      (contains? #{:listener-error :upgrade-failed} kind)
      (diagnose server fact)

      :else server)))


(defn- release-stop
  "Release (4.3): every session and pending connection closed, then
   the host listener released, recorded as `:released now`."
  [server now]
  (release! server)
  (let [server (assoc-in server [:stop :released] now)]
    (if (unbind! server)
      server
      (stopped server ::unbind-failed))))


(defn- lifecycle-lost
  "The lifecycle medium answered gap or end (5.1, gap and end columns).
   A drain it cuts short releases first, recorded as `:released now`,
   so a stopped server never owes the listener or a session."
  [server end? now]
  (case (:status server)
    :starting (do (release! server)
                  (unbind! server)
                  (refused server ::lifecycle-lost {}))
    :serving (if end?
               (-> (release! server) (stopped ::host-stopped))
               (update server :lifecycle-gaps inc))
    :stopping (let [server (if (some? (get-in server [:stop :released]))
                             server
                             (release-stop server now))]
                (if (= :stopping (:status server))
                  (stopped server ::unconfirmed)
                  server))
    server))


(def ^:private live #{:starting :serving :stopping})


(defn- drain-lifecycle
  [server now]
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
                                 false now))

          :dao.stream/end
          (lifecycle-lost server true now)

          server)))))


(defn- drained?
  "True when a draining stop may release: no session is left to
   answer, or `:drain-grace-ms` has passed since the first stopping
   tick."
  [server now]
  (or (empty? (sessions server))
      (>= (- now (get-in server [:stop :since]))
          (get-in server [:bounds :drain-grace-ms]))))


(defn- begin-stop
  "The first stopping tick (4.3): one last bounded answering pass with
   offers rejected, and every pending pre-acknowledgement connection
   closed (it holds no reflection).  Without a drain grace, or with no
   session to answer, the stop releases at once; otherwise it drains."
  [server now]
  (ws-project/accept-step! (:acceptor server) now)
  (ws/endpoint-stop! (:endpoint server))
  (let [server (assoc-in server [:stop :since] now)]
    (if (or (zero? (get-in server [:bounds :drain-grace-ms]))
            (empty? (sessions server)))
      (release-stop server now)
      server)))


(defn- drain-stop
  "A draining tick: the lifecycle facts (a host that stops under the
   drain is `::host-stopped`), an answering pass, stragglers closed;
   then release once drained."
  [server now]
  (let [server (drain-lifecycle server now)]
    (if-not (= :stopping (:status server))
      server
      (do (ws-project/accept-step! (:acceptor server) now)
          (ws/endpoint-stop! (:endpoint server))
          (if (drained? server now)
            (release-stop server now)
            server)))))


(defn- continue-stop
  "A tick after release: newcomers that reached the endpoint before the
   listener went are rejected and closed sessions reaped; then the host's
   `:stopped` fact, or the composed grace after release, completes the
   stop."
  [server now]
  (ws-project/accept-step! (:acceptor server) now)
  (ws/endpoint-stop! (:endpoint server))
  (let [server (drain-lifecycle server now)]
    (if (and (= :stopping (:status server))
             (>= (- now (get-in server [:stop :released]))
                 (get-in server [:bounds :stop-grace-ms])))
      (stopped server ::unconfirmed)
      server)))


(defn serve-step
  "Advance the server one tick at the driver's `now`.

   Starting or serving: the lifecycle facts first (5.1) -- a bind that
   succeeds makes it `:serving` (an ephemeral one names the port the
   host reports, or is `::port-unreported`), one that fails is the `::bind-failed`
   refusal, a gap while starting is the terminal `::lifecycle-lost`
   refusal, a gap while serving is counted under `:lifecycle-gaps`, and
   `:stopped` without `stop!` is `:stopped` with outcome
   `::host-stopped`; `:listener-error` and `:upgrade-failed` are kept,
   the last 8, under `:diagnostics` -- then the acceptor's step.

   Stopping (4.3): the first tick runs the last bounded answering pass
   and closes every pending connection (`:stop :since`).  Then, at once
   when `:drain-grace-ms` is 0 or no session is left, else on the
   draining tick where the last session left or the grace has passed,
   the stop releases (`:stop :released`): every session closed, with
   the ws ended signal when `stop!` was `:ended?`, and the host asked
   to unbind (`::unbind-failed`, and `:stopped`, when it cannot).
   While draining, sessions are still answered, so a reader's
   outstanding `next` on a medium the consumer ended is answered `end`
   before its connection closes.  After release, ticks complete on the
   host's `:stopped` fact (outcome `:confirmed`), a lifecycle gap
   (`::unconfirmed`), or `:stop-grace-ms` after release
   (`::unconfirmed`); the host's `:stopped` before release is
   `::host-stopped`.  A lifecycle gap or end while draining releases at
   once (`:released now`) and completes `::unconfirmed`, or
   `::unbind-failed` when the host refuses the unbind.  Refused and stopped servers are answered
   unchanged."
  [server now]
  (case (:status server)
    (:starting :serving)
    (let [server (drain-lifecycle server now)]
      (when (contains? #{:starting :serving} (:status server))
        (ws-project/accept-step! (:acceptor server) now))
      server)

    :stopping
    (let [{:keys [since released]} (:stop server)]
      (cond
        (nil? since) (begin-stop server now)
        (nil? released) (drain-stop server now)
        :else (continue-stop server now)))

    server))


(defn stop!
  "Initiate an explicit stop of a starting or serving server: the
   acceptor stops adopting and the server is `:stopping`, its `:stop`
   `{:since nil :released nil :outcome nil :ended? e}`.  `:ended?`
   declares that the consumer has closed, or will have closed before
   the next tick, the table handles whose end readers should observe;
   which handles end is the consumer's decision, never this
   composition's.  Performs no I/O; `serve-step` completes the stop.
   Any other server is answered unchanged."
  ([server] (stop! server {}))
  ([server {:keys [ended?]}]
   (if (contains? #{:starting :serving} (:status server))
     (do (ws-project/stop! (:acceptor server))
         (assoc server :status :stopping
                :stop {:since nil :released nil :outcome nil
                       :ended? (boolean ended?)}))
     server)))


;; =============================================================================
;; Dialing
;; =============================================================================

(def ^:private channel-gone
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream.remote/reason :dao.stream.remote/channel-gone})


(def ^:private projection-causes
  {:ws/ended :ended
   :ws/closed :dropped
   :dao.stream/end :dropped
   :ws/not-found :not-served
   :ws/transport-error :unreachable})


(defn- lost
  "The dial `d` lost at this step with `outcome` (channel-gone by
   default), its neutral cause and whether its connection ever opened.
   A projection that closed names the cause.  A channel the link lost
   (the channel map's :gone?) while its projection is still open
   expired: on the dialing end only the projection closes the ring, so
   the link loses an open ring only at a deadline, and the dial closed
   the handle itself; the host's :ws/transport-error or :ws/closed that
   follows is the consequence, not the cause, and is never read,
   because the cause is computed once, here.  A dial whose channel was
   never established is :unreachable for a transport-error outcome."
  ([d] (lost d channel-gone))
  ([d outcome]
   (let [ch (ws-project/channel (:dial d))
         p (:project ch)]
     (assoc d :status :lost :outcome outcome
            :cause (cond
                     (some-> p ws-project/closed?)
                     (get projection-causes (ws-project/cause p))
                     (:gone? ch) :expired
                     (and (nil? ch)
                          (= :dao.stream/transport-error (:dao.stream/outcome outcome)))
                     :unreachable
                     :else nil)
            :opened? (boolean (some-> p ws-project/opened?))))))


(defn- valid-target?
  "Exactly one of a name or a non-empty vector of distinct, non-nil
   identities."
  [n identities]
  (if (some? identities)
    (and (nil? n)
         (vector? identities)
         (boolean (seq identities))
         (every? some? identities)
         (apply distinct? identities))
    (some? n)))


(defn- remote-descriptor
  "The dao.stream remote descriptor of `identity` through the channel
   of `spec`."
  [spec identity]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity
   :dao.stream/channel (descriptor-of spec)})


(defn- close-handles!
  [handles]
  (doseq [h (distinct (remove nil? handles))]
    (when (stream/closable? h)
      (stream/close! h))))


(defn- attach-identities
  "Establish the channel and attach every identity at once, in order:
   the first attaches (establishing the channel), each further one
   reflects through it.  Confirmation is deferred (dao.stream.remote.md
   2.4): the probes are sent now, and a reflection whose identity the
   table lacks turns gone when its probe is answered.  An attach that is
   not ok is `:lost` with its outcome, the partial handles and the
   connection closed."
  [d identities]
  (loop [remaining identities
         handles {}]
    (if (empty? remaining)
      (assoc d :status :attached :handles handles)
      (let [id (first remaining)
            rd (remote-descriptor (:spec d) id)
            r (if (empty? handles)
                (ws-project/dial-attach! (:dial d) rd)
                (ws-project/dial-reflect! (:dial d) rd))]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (recur (rest remaining) (assoc handles id (:dao.stream/handle r)))
          (do (close-handles! (vals handles))
              (when-some [h (some-> (:dial d) ws-project/channel :handle)]
                (stream/close! h))
              (assoc d :status :lost :outcome r :handles handles
                     :cause (when (= :dao.stream/transport-error
                                     (:dao.stream/outcome r))
                              :unreachable)
                     :opened? false)))))))


(defn dial
  "Compose the reader's end toward `spec` over the host assembly's
   `:connect!`.  The target is exactly one of `:name`, resolved and
   attached by `dial-step`, or `:identities`, a non-empty vector of
   distinct, non-nil identities attached at once, here; anything else is
   `{:status :refused :reason ::invalid-target}`.  `:bounds` merges over
   `production-bounds`; `:events`, optional, is the link's event writer.
   Every dial is fresh: its own traffic medium, attacher, cursor and
   channel ring.

   A name dial answers `{:status :resolving :spec :descriptor :name
   :policy :dial :handle :handles :identity :outcome :since}`, `:policy`
   the link policy and `:since` the first step's `now`.

   An identities dial takes `:now`, the driver's clock reading at
   dialing: it is recorded on the link before the channel is
   established, so the probes sent at once carry `give-up-after`
   deadlines and a connection that never opens is lost at them (without
   `:now` nothing expires, acceptable only for tests).  It answers
   `:status :attached` with `:handles {identity reflection}` and
   `:handle nil` -- with a writer among the reflections there is no
   \"the\" handle -- or `:status :lost` with the attacher's own
   `:outcome` (transport-error for a local reachability failure,
   invalid-descriptor).

   Without `:connect!`, or for a transport other than :ws, `{:status
   :refused :reason ::no-transport}`.  An invalid bound is the
   composition error the validating layer throws."
  [{:keys [spec host bounds events identities now table names] n :name :as opts}]
  (let [b (merge production-bounds bounds)]
    (cond
      (table-refusal (or table {}) names)
      {:status :refused :reason ::invalid-table :detail (table-refusal (or table {}) names)}

      (:route opts)
      (route/dial opts)

      (not (valid-target? n identities))
      {:status :refused :reason ::invalid-target :spec spec :name n
       :identities identities}

      (not (and (ws-transport? spec) (fn? (:connect! host))))
      {:status :refused :reason ::no-transport :spec spec :name n
       :identities identities}

      :else
      (let [capacity (:capacity b)
            policy (link-policy b)
            traffic (buffer capacity)
            attach! (ws/make-attacher
                      (merge (select-keys b ws-bound-keys)
                             {:traffic (writer-target traffic)
                              :admission (admission capacity)
                              :connect! (:connect! host)}))
            d {:status :resolving
               :spec spec
               :descriptor (descriptor-of spec)
               :name n
               :policy policy
               :dial (ws-project/dial
                       (merge policy
                              (when events {:dao.stream.remote/events events})
                              {:attach! attach!
                               :traffic (writer-target traffic)
                               :cursor (mint traffic stream/anchor-newest)
                               :ring (buffer capacity)
                               :table (or table {})
                               :names names
                               :step-budget (:step-budget b)
                               :mirror-budget (:mirror-budget b)
                               :chase-budget (:chase-budget b)}))
               :handle nil
               :handles {}
               :identity nil
               :outcome nil
               :since nil}]
        (if (nil? identities)
          d
          (do (when (some? now)
                (ws-project/dial-step! (:dial d) now))
              (attach-identities (assoc d :identities identities :since now)
                                 identities)))))))


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
                 :handles {(:dao.stream/identity r) (:dao.stream/handle a)}
                 :identity (:dao.stream/identity r)
                 :outcome r)
          (lost d a)))

      (and (= :dao.stream/transport-error (:dao.stream/outcome r))
           (true? (:dao.stream/retry? r)))
      (assoc d :outcome r)

      :else
      (lost d r))))


(defn- resolve-expired?
  "A dial still resolving `give-up-after` ms after its first step.  A
   connection that never opens refuses every send, so the link holds
   no outstanding request and stamps no deadline; this bound is the
   connect half of the same liveness."
  [d now]
  (let [limit (get-in d [:policy :dao.stream.remote/give-up-after])]
    (and (= :resolving (:status d))
         (some? limit)
         (some? (:since d))
         (>= (- now (:since d)) limit))))


(defn dial-step
  "Advance the dial one tick at the driver's `now`: the projection, the
   link stepped at `now` and the dial's own mirror first.  Resolving: one
   resolve of the name -- ok attaches the descriptor answered through the
   same channel (`:attached`, `:handle` the reflection, `:identity` the
   source's own); a retryable answer stays, until `give-up-after` from
   the dial's first step, when it is `:lost` naming channel-gone; any
   other answer is `:lost` with `:outcome` that answer.  Attached: a
   channel whose projection closed, or whose link expired, is `:lost`
   with outcome transport-error naming channel-gone.  A dial lost here
   carries its neutral `cause` and whether it ever `opened?`.  Lost,
   closed and refused dials are answered unchanged."
  [d now]
  (if (:route-state d)
    (try (route/step d now)
         (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
           (assoc (route/close! d) :status :lost :cause :dropped
                  :failure {:stage :route-step :exception? true})))
    (if-not (contains? #{:resolving :attached} (:status d))
      d
      (let [d (update d :since #(if (some? %) % now))]
        (ws-project/dial-step! (:dial d) now)
        (case (:status d)
          :resolving (let [d (resolve-step d)]
                       (if (resolve-expired? d now)
                         (assoc (lost d) :cause :expired)
                         d))
          :attached (if (channel-lost? d)
                      (lost d)
                      d))))))


(defn cause
  "Why a :lost dial was lost, one of :ended (the peer closed with the
   ended signal), :dropped (the connection closed with any other code,
   or the traffic medium ended), :not-served (the peer disclaimed),
   :unreachable (the connection never opened: refused, or torn down
   before it opened), :expired (a request passed give-up-after); nil
   for a dial that is not lost, or lost by a non-transport attach
   failure."
  [d]
  (:cause d))


(defn opened?
  "True when the dialed connection opened at some point before the
   dial was lost.  With :expired it separates a peer that stopped
   answering (true, reattach) from one that never answered (false)."
  [d]
  (:opened? d))


(defn handle
  "The named dial's reflection once attached, else nil; nil for an
   identities dial, which has no one handle.  With `identity`, that
   identity's reflection."
  ([d] (:handle d))
  ([d identity] (get (:handles d) identity)))


(defn handles
  "Every attached reflection, {identity handle}."
  [d]
  (:handles d))


(defn attachment
  "The dialed ws attachment id, nil before a connection exists and
   after `close!`."
  [d]
  (when-not (= :closed (:status d))
    (some-> (:dial d) ws-project/channel :attachment)))


(defn detach!
  "Close the dialed connection and nothing else: every reflection stays
   open so that the loss reaches it as channel-gone on its next
   operation, and the dial stays steppable until `dial-step` observes
   the projection closed and answers `:lost`.  Marks `:detaching?`.
   Idempotent; identity on a dial that has no connection or is already
   detaching, lost or closed."
  [d]
  (if (:route-state d)
    (assoc (route/close! d) :status :lost :cause :dropped)
    (let [h (some-> (:dial d) ws-project/channel :handle)]
      (if (or (nil? h)
              (:detaching? d)
              (contains? #{:lost :closed :refused} (:status d)))
        d
        (do (stream/close! h)
            (assoc d :detaching? true))))))


(defn close!
  "Close the dial's connection when one was made, and every reflection
   attached.  Idempotent; answers the dial, `:closed`.  A closed dial is
   not stepped again: a redial composes a fresh one."
  [d]
  (if (:route-state d)
    (route/close! d)
    (if (= :closed (:status d))
      d
      (do (when-some [h (some-> (:dial d) ws-project/channel :handle)]
            (stream/close! h))
          (close-handles! (cons (:handle d) (vals (:handles d))))
          (assoc d :status :closed)))))


(defn update-tables
  "Install explicit table/name snapshots before the next mirror pass.
   Returns the retained composition value; never closes table-owned media."
  [composition table names]
  (if-some [detail (table-refusal table names)]
    (throw (ex-info "invalid remote channel table snapshot" detail))
    (do
      (when-some [a (or (:acceptor composition) (:dial composition))]
        (swap! a assoc :table table :names names))
      (if (:route-state composition)
        (route/update-tables composition table names)
        (assoc composition :table table :names names)))))


(defn route-driver
  "Preflight and compose finite route plans with a shared work allowance."
  [plans bounds]
  (route/driver plans bounds))


(defn route-driver-step
  "Advance every planned channel fairly at supplied now; retain returned state."
  [state now]
  (route/driver-step state now))

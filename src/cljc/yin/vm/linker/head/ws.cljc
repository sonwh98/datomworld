(ns yin.vm.linker.head.ws
  "The head board over WebSocket, on loopback
   (docs/design/yin.vm.linker.dht.head.md 5.1, section 6, slice H2).

   The only namespace of the head trace that knows a transport.  Both
   ends are thin compositions of `dao.stream.ws-project`:

   * `serve` composes the board's acceptor: a one-entry mirror table,
     the board under the ring's own identity with surface #{:reader},
     and a one-entry name map, the board name to that identity.  It
     listens at path /head.  The listener is an argument, the host's
     `listen!` of the `yin.repl.host` seam shape.  A bind host that is
     not a loopback literal composes no endpoint.  `serve-step` drives
     it at the composition's cadence.
   * `dial` composes the reader's end over the host's `connect!`, the
     ws attacher's host seam: it resolves the board name, then attaches
     the descriptor answered.  `dial-step` drives it; `handle` is the
     reflection once attached, a `dao.stream` reader handle that the
     follower (`yin.vm.linker.head/attach`) takes like any other.

   A name is lookup data and never a :dao.stream/identity: the board
   name appears only in the name map and in the named descriptor
   request, and the reflection reports the ring's own identity.
   Nothing here reads a clock or schedules itself."
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as ws-project]))


;; =============================================================================
;; Composition constants (section 7)
;; =============================================================================

(def path
  "The board endpoint's path."
  "/head")


(def slot-count
  "The acceptor's handoff slots, as the REPL endpoint's."
  8)


(def ^:private capacity 64)


(def ^:private admission
  {:retention :evict-oldest :capacity capacity
   :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn board-name
  "The board name of `principal`: lookup data, never an identity."
  [principal]
  (str "yin.head/" principal))


;; =============================================================================
;; Loopback literals
;; =============================================================================

(def ^:private ipv6-loopback
  #{"::1" "[::1]" "0:0:0:0:0:0:0:1" "[0:0:0:0:0:0:0:1]"})


(def ^:private digits
  {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})


(defn- octet?
  [s]
  (and (<= 1 (count s) 3)
       (every? #(contains? digits %) s)
       (<= (reduce (fn [n c] (+ (* 10 n) (get digits c))) 0 s) 255)))


(defn loopback?
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


(defn- channel-descriptor
  "The ws descriptor of the board endpoint at `host`:`port`.  Its
   identity is the endpoint's address: it names the channel, never the
   board, and never crosses the wire."
  [host port]
  {:dao.stream/type ws/transport-type
   :dao.stream/identity (str "ws://" host ":" port path)
   :ws/host host
   :ws/port port
   :ws/path path})


(defn- refused
  [reason data]
  (merge {:status :refused :reason reason} data))


;; =============================================================================
;; The board's acceptor
;; =============================================================================

(defn- make-slots
  []
  (mapv (fn [_]
          (let [offer (buffer 1)
                ack (buffer 1)]
            {:offer offer :ack ack
             :offer-cursor (mint offer stream/anchor-newest)
             :ack-cursor (mint ack stream/anchor-newest)}))
        (range slot-count)))


(defn- make-media
  "One traffic medium and one channel ring per accepted connection."
  [_offer]
  (let [traffic (buffer capacity)]
    {:traffic (writer-target traffic)
     :admission admission
     :reader traffic
     :cursor (mint traffic stream/anchor-newest)
     :ring (buffer capacity)}))


(defn serve
  "Compose the board's endpoint and start its listener.  `opts`:
   `:board` the board ring; `:principal` its publisher's principal;
   `:bind-host` and `:bind-port` the TCP bind; `:listen!` the host
   listener, `(fn [{:keys [endpoint bind-host bind-port path accept!
   deposit!]}] resources)`, the `yin.repl.host` `:bind!` shape.

   Answers a server value, `:status :starting`, with `:identity` the
   board ring's own identity, `:name` its board name, `:descriptor`
   the ws descriptor a dialer reaches, `:acceptor` and `:listener` the
   resources `listen!` answered.  Refusals are data, `{:status :refused
   :reason r ...}`, and the first two compose no endpoint and listen on
   nothing: `:yin.head.ws/not-loopback` when the bind host is not a
   loopback literal; `:yin.head.ws/no-port` when the bind port is not
   a positive integer, since the descriptor every accepted handle
   reports names the port from the start; `:yin.head.ws/bind-failed`
   when `listen!` throws or answers an outcome that is not ok."
  [{:keys [board principal bind-host bind-port listen!]}]
  (cond
    (not (loopback? bind-host))
    (refused :yin.head.ws/not-loopback {:bind-host bind-host})

    (not (and (integer? bind-port) (pos? bind-port)))
    (refused :yin.head.ws/no-port {:bind-port bind-port})

    :else
    (let [identity (:dao.stream/identity (stream/descriptor board))
          n (board-name principal)
          descriptor (channel-descriptor bind-host bind-port)
          pool (make-slots)
          lifecycle (buffer capacity)
          endpoint (ws/make-endpoint
                     {:descriptor descriptor
                      :control (writer-target (buffer capacity))
                      :control-admission admission
                      :slots (mapv (fn [slot]
                                     {:offer (writer-target (:offer slot))
                                      :offer-admission handoff-admission
                                      :ack (writer-target (:ack slot))
                                      :ack-admission handoff-admission
                                      :ack-cursor (:ack-cursor slot)})
                                   pool)
                      :expiry-ms nil})
          acceptor (ws-project/make-acceptor
                     {:endpoint endpoint
                      :slots (mapv (fn [slot]
                                     {:offer-reader (:offer slot)
                                      :offer-cursor (:offer-cursor slot)
                                      :ack-writer (writer-target (:ack slot))})
                                   pool)
                      :table {identity {:handle board :surface #{:reader}}}
                      :names {n identity}
                      :make-media make-media})
          lifecycle-cursor (mint lifecycle stream/anchor-newest)
          deposit! (fn [kind value]
                     (stream/append! lifecycle {:kind kind :value value}))
          bound (try
                  (listen! {:endpoint endpoint
                            :bind-host bind-host
                            :bind-port bind-port
                            :path path
                            :accept! (fn [request-path socket now]
                                       (ws/accept-connection!
                                         endpoint request-path socket now))
                            :deposit! deposit!})
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _
                    ::threw))]
      (if (or (= ::threw bound)
              (and (map? bound)
                   (contains? bound :dao.stream/outcome)
                   (not= :dao.stream/ok (:dao.stream/outcome bound))))
        (refused :yin.head.ws/bind-failed {:bind-host bind-host
                                           :bind-port bind-port})
        {:status :starting
         :identity identity
         :name n
         :descriptor descriptor
         :acceptor acceptor
         :listener bound
         :lifecycle lifecycle
         :lifecycle-cursor lifecycle-cursor}))))


(defn- observe
  "Apply one lifecycle fact the listener deposited."
  [server {:keys [kind value]}]
  (case kind
    :bind-succeeded
    (assoc server :status :serving)

    :bind-failed
    (refused :yin.head.ws/bind-failed {:detail value})

    :stopped
    (assoc server :status :stopped)

    server))


(defn serve-step
  "Advance the board's endpoint one tick at `now`: the listener's
   lifecycle facts first (a bind that fails asynchronously becomes the
   `:yin.head.ws/bind-failed` refusal; a bind that succeeds makes the
   server `:serving`), then the acceptor's step, which
   adopts connections and runs the mirror with the name map.  A
   refused or stopped server is answered unchanged.  Answers the
   server."
  [server now]
  (if-not (contains? #{:starting :serving} (:status server))
    server
    (let [server (loop [server server]
                   (let [r (stream/next (:lifecycle server)
                                        (:lifecycle-cursor server))]
                     (if (= :dao.stream/ok (:dao.stream/outcome r))
                       (let [server (observe (assoc server :lifecycle-cursor
                                                    (:dao.stream/cursor r))
                                             (:dao.stream/value r))]
                         (if (contains? #{:starting :serving}
                                        (:status server))
                           (recur server)
                           server))
                       server)))]
      (when (contains? #{:starting :serving} (:status server))
        (ws-project/accept-step! (:acceptor server) now))
      server)))


;; =============================================================================
;; The reader's dial
;; =============================================================================

(defn dial
  "Compose the reader's end toward the board of `principal` at
   `:host`:`:port`, over the host's `:connect!` (the
   `dao.stream.ws/make-attacher` seam).  Every dial is fresh: its own
   traffic medium, attacher, cursor and channel ring, so a lost source
   is redialed by composing another.  Answers a dial value,
   `:status :resolving`; a dial whose attacher cannot be composed is
   `{:status :refused :reason :yin.head.ws/no-attacher}`."
  [{:keys [principal host port connect!]}]
  (let [traffic (buffer capacity)
        attach! (try (ws/make-attacher {:traffic (writer-target traffic)
                                        :admission admission
                                        :connect! connect!})
                     (catch #?(:cljd Object :clj Throwable :cljs :default) _
                       nil))]
    (if-not attach!
      (refused :yin.head.ws/no-attacher {:principal principal})
      {:status :resolving
       :principal principal
       :name (board-name principal)
       :channel (channel-descriptor host port)
       :dial (ws-project/dial {:attach! attach!
                               :traffic (writer-target traffic)
                               :cursor (mint traffic stream/anchor-newest)
                               :ring (buffer capacity)
                               :table {}})
       :handle nil
       :outcome nil})))


(defn dial-step
  "Advance the dial one tick: the projection and the dial's own mirror,
   then, while `:resolving`, one resolve of the board name.  A resolve
   that answers ok attaches the descriptor answered through the same
   channel: `:status :attached`, `:handle` the reflection, which
   reports the ring's own identity.  A resolve that answers retry
   stays `:resolving`; any other answer (not-found, channel-gone, a
   channel attach that fails) is `:status :lost` with `:outcome` that
   answer.  Answers the dial."
  [d]
  (if-not (contains? #{:resolving :attached} (:status d))
    d
    (do (ws-project/dial-step! (:dial d))
        (if (= :attached (:status d))
          d
          (let [r (ws-project/dial-resolve! (:dial d) (:channel d) (:name d))]
            (cond
              (= :dao.stream/ok (:dao.stream/outcome r))
              (let [a (ws-project/dial-reflect! (:dial d)
                                                (:dao.stream/descriptor r))]
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
              (assoc d :status :lost :outcome r)))))))


(defn handle
  "The dial's reader handle once attached, else nil."
  [d]
  (:handle d))


(defn close!
  "Close the dial's connection, if one was made."
  [d]
  (when-some [h (some-> (:dial d) ws-project/channel :handle)]
    (stream/close! h))
  nil)

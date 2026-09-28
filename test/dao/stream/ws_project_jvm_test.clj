(ns dao.stream.ws-project-jvm-test
  "The section-5 toy of dao.stream.remote.md across a real socket.

   Peer B serves the single value \"hello\" as \"str-1\" behind a real
   http-kit listener and drives, per accepted connection, the
   projection and then the mirror step. Peer A dials through the real
   java.net.http client and drives the projection and its own mirror
   step over the dialed attachment; its link sends the descriptor
   probe and drains inside every operation. Nothing is mocked: bytes
   cross a loopback WebSocket in both directions, and the facts are
   the plan's slice-3 proof -- the toy end to end; the surface learned
   from the probe's answer; both directions serving on one connection;
   a closed connection observed as the link's end (2.4); and the same
   endpoint serving a second connection once the closed session is
   reaped.

   The test is the composition's cadence: `tick!` advances both ends
   one turn, as a driver would, and `settle!` keeps ticking until an
   operation stops answering the toy's not-yet outcomes."
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.ws :as ws]
            [dao.stream.ws.jvm :as jvm]
            [dao.stream.ws-project :as project]
            [org.httpkit.server :as http]))


(def ^:private admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def ^:private path "/streams/toy")


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn- eventually
  "Poll pred every 10ms until truthy or the deadline; nil on timeout."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (> deadline (System/currentTimeMillis))
            (Thread/sleep 10)
            (recur))))))


(defn- served-stream
  "The toy's source: the single value, complete history -- the owner's
   close is what makes a later read answer the source's own end."
  [value]
  (let [s (buffer 16)]
    (stream/append! s value)
    (stream/close! s)
    s))


(defn- toy
  "Both peers of the toy. B: endpoint, acceptor, and a real listener
   whose upgrades run ws/accept-connection!; A: a real client
   attacher, the projection onto its ring, and its own mirror over
   `table` -- A serves \"str-2\" back over the same kind of
   connection."
  []
  (let [str-1 (served-stream "hello")
        str-2 (served-stream "backward")
        descriptor {:dao.stream/type :dao.stream/ws
                    :dao.stream/identity "str-1"
                    :ws/host "127.0.0.1"
                    :ws/port 1
                    :ws/path path}
        offers (buffer 1)
        acks (buffer 1)
        control (buffer 16)
        endpoint (ws/make-endpoint
                   {:descriptor descriptor
                    :control {:dao.stream/handle control
                              :dao.stream/surface #{:writer}}
                    :control-admission admission
                    :slots [{:offer {:dao.stream/handle offers
                                     :dao.stream/surface #{:writer}}
                             :offer-admission handoff-admission
                             :ack {:dao.stream/handle acks
                                   :dao.stream/surface #{:writer}}
                             :ack-admission handoff-admission
                             :ack-cursor (newest acks)}]
                    :expiry-ms nil})
        acceptor
        (project/make-acceptor
          {:endpoint endpoint
           :slots [{:offer-reader offers
                    :offer-cursor (newest offers)
                    :ack-writer {:dao.stream/handle acks
                                 :dao.stream/surface #{:writer}}}]
           :table {"str-1" {:handle str-1 :surface #{:reader}}}
           :make-media
           (fn [_offer]
             (let [traffic (buffer 64)
                   channel (buffer 64)]
               {:traffic {:dao.stream/handle traffic
                          :dao.stream/surface #{:writer}}
                :admission admission
                :reader traffic
                :cursor (newest traffic)
                :ring channel}))})
        deposits (atom [])
        listener
        (jvm/listen!
          {:bind-host "127.0.0.1"
           :bind-port 0
           :accept!
           (fn [target socket now]
             (ws/accept-connection! endpoint target socket now))
           :deposit!
           (fn [kind detail] (swap! deposits conj [kind detail]))})
        bound
        (eventually #(http/server-port (:ws.jvm/server listener)) 5000)
        traffic (buffer 64)
        events (buffer 64)
        attacher
        (ws/make-attacher
          {:traffic {:dao.stream/handle traffic
                     :dao.stream/surface #{:writer}}
           :admission admission
           :connect! jvm/connect!})
        table {"str-2" {:handle str-2 :surface #{:reader}}}
        dial
        (project/dial
          {:attach! attacher
           :traffic {:dao.stream/handle traffic
                     :dao.stream/surface #{:writer}}
           :cursor (newest traffic)
           :ring (buffer 64)
           :table table
           :dao.stream.remote/events events})
        values-of
        (fn [h]
          (loop [c (:dao.stream/cursor
                     (stream/cursor h stream/anchor-oldest))
                 acc []]
            (let [r (stream/next h c)]
              (if (= :dao.stream/ok (:dao.stream/outcome r))
                (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
                (vec acc)))))]
    (when (nil? bound)
      (jvm/stop-listening! listener (fn []))
      (throw (ex-info "the toy's listener never bound"
                      {:deposits @deposits})))
    {:descriptor (assoc descriptor :ws/port bound)
     :acceptor acceptor
     :listener listener
     ;; One dial value carries one active attachment, so a test that
     ;; reconnects swaps this atom for a fresh dial with a fresh
     ;; cursor; the drivers always take the current one.
     :dial (atom dial)
     :attacher attacher
     :traffic traffic
     :table table
     :events events
     :values-of values-of}))


(defn- reflect!
  "A's attach! on a remote descriptor naming `identity` over the toy's
   channel."
  [toy identity]
  (project/dial-attach! @(:dial toy)
                        {:dao.stream/type :dao.stream/remote
                         :dao.stream/identity identity
                         :dao.stream/channel (:descriptor toy)}))


(defn- redial!
  "A fresh dial over the toy's attacher and traffic medium: one dial
   value carries one active attachment, so a reattachment composes a
   fresh dial with a fresh cursor minted at the medium's newest, per
   dao.stream.ws.md."
  [toy]
  (swap! (:dial toy)
         (fn [_]
           (project/dial
             {:attach! (:attacher toy)
              :traffic {:dao.stream/handle (:traffic toy)
                        :dao.stream/surface #{:writer}}
              :cursor (newest (:traffic toy))
              :ring (buffer 64)
              :table (:table toy)
              :dao.stream.remote/events (:events toy)}))))


(defn- tick!
  "One cadence turn of the whole toy: B's accepting composition, then
   A's dialing composition."
  [toy]
  (project/accept-step! (:acceptor toy) (System/currentTimeMillis))
  (project/dial-step! @(:dial toy)))


(defn- drive!
  "Tick the toy until `pred` holds or the deadline passes; true when
   it held."
  [toy pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (tick! toy)
            (Thread/sleep 10)
            (recur))))))


(defn- settle!
  "Apply `op`, ticking the toy between attempts, until it stops
   answering the toy's not-yet outcomes -- the pre-answer
   transport-error retry and blocked -- or the deadline passes."
  [toy op ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [r (op)]
      (if (and (contains? #{:dao.stream/transport-error
                            :dao.stream/blocked}
                          (:dao.stream/outcome r))
               (< (System/currentTimeMillis) deadline))
        (do (tick! toy)
            (Thread/sleep 10)
            (recur (op)))
        r))))


(defn- b-reflect!
  "B's own reflection of `identity` over the accepted connection: the
   session end this side holds, through the remote attacher."
  [toy identity]
  (let [attachment (first (keys (project/sessions (:acceptor toy))))
        end (project/session-end (:acceptor toy) attachment)
        result ((remote/attacher
                  {:dao.stream.remote/channels {(:descriptor toy) end}})
                {:dao.stream/type :dao.stream/remote
                 :dao.stream/identity identity
                 :dao.stream/channel (:descriptor toy)})]
    result))


(deftest the-toy-crosses-a-real-socket
  (let [toy (toy)
        attached (reflect! toy "str-1")
        refl (:dao.stream/handle attached)]
    (try
      (is (= :dao.stream/ok (:dao.stream/outcome attached)))
      (is (string? (:dao.stream/attachment attached)))
      (is (drive! toy #(seq (project/sessions (:acceptor toy))) 5000)
          "B adopted the connection and acknowledged its offer")
      ;; descriptor is local: ok before any confirmation (2.4).
      (let [d (stream/descriptor refl)]
        (is (= :dao.stream/ok (:dao.stream/outcome d)))
        (is (= "str-1" (:dao.stream/identity d))))
      ;; cursor :oldest: the link sends, answers the retry, then the
      ;; next ask files the source's ok.
      (let [r (settle! toy #(stream/cursor refl stream/anchor-oldest)
                       5000)]
        (is (= :dao.stream/ok (:dao.stream/outcome r)))
        (is (some? (:dao.stream/cursor r)))
        (is (= #{:reader} (:dao.stream.remote/surface
                            (first ((:values-of toy) (:events toy)))))
            "the probe's answer crossed the wire: the link's event
             writer recorded the confirmation carrying the served
             stream's declared surface")
        ;; next c0: blocked, then the source's own ok "hello" c1.
        (let [c0 (:dao.stream/cursor r)
              n (settle! toy #(stream/next refl c0) 5000)]
          (is (= :dao.stream/ok (:dao.stream/outcome n)))
          (is (= "hello" (:dao.stream/value n))
              "nothing knew it was a string")
          ;; next c1: the source's own end.
          (let [e (settle! toy
                           #(stream/next refl (:dao.stream/cursor n))
                           5000)]
            (is (= :dao.stream/end (:dao.stream/outcome e))))))
      (finally
        (jvm/stop-listening! (:listener toy) (fn []))))))


(deftest both-directions-serve-on-one-connection
  (let [toy (toy)
        a-refl (:dao.stream/handle (reflect! toy "str-1"))]
    (try
      (is (drive! toy #(seq (project/sessions (:acceptor toy))) 5000))
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               (settle! toy
                        #(stream/cursor a-refl stream/anchor-oldest)
                        5000)))
          "A's direction established")
      (let [b-attached (b-reflect! toy "str-2")
            b-refl (:dao.stream/handle b-attached)]
        (is (= :dao.stream/ok (:dao.stream/outcome b-attached))
            "B attached a reflection over the same accepted connection")
        (let [r (settle! toy
                         #(stream/cursor b-refl stream/anchor-oldest)
                         5000)]
          (is (= :dao.stream/ok (:dao.stream/outcome r)))
          (let [n (settle! toy
                           #(stream/next b-refl (:dao.stream/cursor r))
                           5000)]
            (is (= :dao.stream/ok (:dao.stream/outcome n)))
            (is (= "backward" (:dao.stream/value n))
                "B read A's stream back over the one connection: the
                 wire stayed symmetric, one request and one answer at
                 a time, each end serving its own table"))))
      (finally
        (jvm/stop-listening! (:listener toy) (fn []))))))


(deftest a-closed-connection-is-the-links-end
  (let [toy (toy)
        a-refl (:dao.stream/handle (reflect! toy "str-1"))]
    (try
      (is (drive! toy #(seq (project/sessions (:acceptor toy))) 5000))
      (let [a-c0 (:dao.stream/cursor
                   (settle! toy
                            #(stream/cursor a-refl stream/anchor-oldest)
                            5000))
            b-attached (b-reflect! toy "str-2")
            b-refl (:dao.stream/handle b-attached)
            b-c0 (:dao.stream/cursor
                   (settle! toy
                            #(stream/cursor b-refl stream/anchor-oldest)
                            5000))]
        (is (= :dao.stream/ok (:dao.stream/outcome b-attached)))
        ;; A detaches.  Each endpoint deposits its one terminal event,
        ;; each projection closes its ring, and the acceptor reaps the
        ;; closed session once its last mirror pass has run.
        (stream/close! (:handle (project/channel @(:dial toy))))
        (is (drive! toy
                    #(let [a-project (:project (project/channel
                                                 @(:dial toy)))]
                       (and (project/closed? a-project)
                            (empty? (project/sessions
                                      (:acceptor toy)))))
                    5000)
            "both ends saw the terminal event, both rings closed, and
             the closed session was reaped")
        ;; Each link observes the channel's end: not a retry.
        (let [r (stream/next a-refl a-c0)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome r)))
          (is (= :dao.stream.remote/channel-gone
                 (:dao.stream.remote/reason r))
              "A's link abandoned the channel (2.4)"))
        (let [r (stream/next b-refl b-c0)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome r)))
          (is (= :dao.stream.remote/channel-gone
                 (:dao.stream.remote/reason r))
              "B's link abandoned the channel too: the one connection
               carried both directions"))
        ;; The name outlives the channel: descriptor still answers ok.
        (is (= :dao.stream/ok
               (:dao.stream/outcome (stream/descriptor a-refl)))))
      (finally
        (jvm/stop-listening! (:listener toy) (fn []))))))


(deftest a-reaped-session-lets-the-next-connection-serve
  (let [toy (toy)]
    (try
      (is (= :dao.stream/ok
             (:dao.stream/outcome (reflect! toy "str-1"))))
      (is (drive! toy #(seq (project/sessions (:acceptor toy))) 5000)
          "the first connection was adopted")
      (let [first-attachment
            (first (keys (project/sessions (:acceptor toy))))]
        ;; A detaches: the terminal event reaches both projections, the
        ;; rings close, and the acceptor reaps the closed session.
        (stream/close! (:handle (project/channel @(:dial toy))))
        (is (drive! toy #(empty? (project/sessions (:acceptor toy)))
                    5000)
            "the closed session was reaped: nothing accumulates")
        ;; A reconnects over the same listener, endpoint and traffic
        ;; medium: a fresh dial with a fresh cursor, per the
        ;; one-active-attachment reading of dao.stream.ws.md.
        (redial! toy)
        (let [second-refl
              (:dao.stream/handle (reflect! toy "str-1"))]
          (is (not (contains? (project/sessions (:acceptor toy))
                              first-attachment))
              "the first connection's state is gone")
          (let [r (settle! toy
                           #(stream/cursor second-refl
                                           stream/anchor-oldest)
                           5000)]
            (is (= :dao.stream/ok (:dao.stream/outcome r))
                "the second connection works: the probe and the cursor
                 ask were answered over the same endpoint")
            (is (= 1 (count (project/sessions (:acceptor toy))))
                "one connection, one session: the slot was reused, not
                 accumulated")
            (let [n (settle! toy
                             #(stream/next second-refl
                                           (:dao.stream/cursor r))
                             5000)]
              (is (= :dao.stream/ok (:dao.stream/outcome n)))
              (is (= "hello" (:dao.stream/value n))
                  "the second connection serves the toy again")))))
      (finally
        (jvm/stop-listening! (:listener toy) (fn []))))))

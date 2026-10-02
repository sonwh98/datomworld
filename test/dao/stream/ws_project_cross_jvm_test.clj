(ns dao.stream.ws-project-cross-jvm-test
  "Slice 3's cross-host socket proof, JVM half: this process serves and
   a compiled Dart peer dials and reads.

   The ws channel composition was proven on one JVM across real
   loopback sockets already; the same wire spoken by a second host is a
   further fact, and only a second operating-system process of a second
   runtime can falsify it.  Process B is `dao.stream.ws-project-peer`
   in its dial role, compiled ahead of time to `build/ws-project-peer`
   (there is no bb task for it yet):

     clojure -M:clojuredart:cljd compile dao.stream.ws-project-peer
     dart compile exe lib/cljd-out/dao/stream/ws-project-peer.dart \\
       -o build/ws-project-peer

   A lane run without the exe skips loudly rather than failing or
   passing silently.  B receives the ws descriptor as Transit JSON text
   in argv, attaches a reflection of \"str-1\", and reports what its
   reflection answers over the pipe protocol; this parent drives the
   accepting composition between every ask, so the parent's cadence and
   the child's own ticker are the only drivers either side has."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.jvm :as jvm]
            [dao.stream.ws-project :as project]))


(def ^:private dart-peer-exe "build/ws-project-peer")


(def ^:private path "/streams/toy")


(def ^:private admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def ^:private not-yet
  "The outcomes a reflection answers while its request is in flight."
  #{:dao.stream/transport-error :dao.stream/blocked})


(def ^:private boot-ms 30000)
(def ^:private reply-ms 20000)
(def ^:private settle-ms 20000)


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn- served-stream
  [value]
  (let [s (buffer 16)]
    (stream/append! s value)
    (stream/close! s)
    s))


(defn- eventually
  "Poll pred every 10ms until truthy or the deadline; nil on timeout."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (> deadline (System/currentTimeMillis))
            (Thread/sleep 10)
            (recur))))))


;; =============================================================================
;; This process: the accepting composition behind a real listener
;; =============================================================================


(defn- jvm-acceptor
  "The toy's serving end: the ws endpoint with one acceptance-handoff
   slot, the acceptor composition, and a real http-kit listener whose
   bound port is learned from the host's own bind report."
  []
  (let [hello (served-stream "hello")
        descriptor {:dao.stream/type :dao.stream/ws
                    :dao.stream/identity "str-1"
                    :ws/host "127.0.0.1"
                    :ws/port 1
                    :ws/path path}
        offers (buffer 1)
        acks (buffer 1)
        endpoint (ws/make-endpoint
                   {:descriptor descriptor
                    :control {:dao.stream/handle (buffer 16)
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
        acceptor (project/make-acceptor
                   {:endpoint endpoint
                    :slots [{:offer-reader offers
                             :offer-cursor (newest offers)
                             :ack-writer {:dao.stream/handle acks
                                          :dao.stream/surface #{:writer}}}]
                    :table {"str-1" {:handle hello :surface #{:reader}}}
                    :make-media (fn [_offer]
                                  (let [traffic (buffer 64)
                                        channel (buffer 64)]
                                    {:traffic
                                     {:dao.stream/handle traffic
                                      :dao.stream/surface #{:writer}}
                                     :admission admission
                                     :reader traffic
                                     :cursor (newest traffic)
                                     :ring channel}))})
        port (atom nil)
        listener (jvm/listen!
                   {:bind-host "127.0.0.1"
                    :bind-port 0
                    :accept! (fn [target socket now]
                               (ws/accept-connection! endpoint target
                                                      socket now))
                    :deposit! (fn [kind data]
                                (when (= :bind-succeeded kind)
                                  (reset! port (:port data))))})]
    {:acceptor acceptor
     :listener listener
     :port port
     :descriptor descriptor}))


;; =============================================================================
;; Process B: the compiled Dart peer, spoken to only over pipes
;; =============================================================================


(defn- pump!
  [reader on-line]
  (doto (Thread.
          (fn []
            (try
              (loop []
                (when-let [line (.readLine ^java.io.BufferedReader reader)]
                  (on-line line)
                  (recur)))
              (catch Exception _ nil))))
    (.setDaemon true)
    (.start)))


(defn- start-peer!
  "Spawn the Dart peer with the descriptor as its sole argument."
  [descriptor]
  (let [command [dart-peer-exe (transit/encode-descriptor descriptor)]
        process (.start (ProcessBuilder. ^java.util.List command))
        replies (atom [])
        errors (atom [])
        peer {:process process
              :in (io/writer (.getOutputStream process))
              :replies replies
              :errors errors}]
    ;; Anything B prints before the peer protocol starts is noise rather
    ;; than a protocol failure: recording it and carrying on keeps one
    ;; banner from silently killing the reader.
    (pump! (io/reader (.getInputStream process))
           (fn [line]
             (let [reply (try (transit/decode line) (catch Exception _ nil))]
               (if (:reply reply)
                 (swap! replies conj reply)
                 (swap! errors conj line)))))
    ;; stderr must be drained too: an unread pipe fills and would block
    ;; the child mid-report, which would look exactly like a failed fact.
    (pump! (io/reader (.getErrorStream process))
           (fn [line] (swap! errors conj line)))
    peer))


(defn- stop-peer!
  [peer]
  (try (.destroy ^Process (:process peer)) (catch Exception _ nil)))


(defn- tick!
  "One cadence turn of this side's accepting composition."
  [acceptor]
  (project/accept-step! acceptor (System/currentTimeMillis)))


(defn- take-reply!
  "Consume the first reply of `kind`, or nil at the deadline."
  [peer kind timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [index (first (keep-indexed (fn [i r] (when (= kind (:reply r)) i))
                                       @(:replies peer)))]
        (cond
          index (let [reply (nth @(:replies peer) index)]
                  (swap! (:replies peer)
                         (fn [rs]
                           (vec (concat (subvec rs 0 index)
                                        (subvec rs (inc index))))))
                  reply)
          (< deadline (System/currentTimeMillis)) nil
          :else (do (Thread/sleep 5) (recur)))))))


(defn- ask-until!
  "Send one command and consume its `kind` reply once `good?` accepts
   it, ticking this side's composition between attempts; nil at the
   deadline."
  [acceptor peer command kind good? timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (tick! acceptor)
      (let [writer ^java.io.Writer (:in peer)]
        (.write writer (str (transit/encode command) "\n"))
        (.flush writer))
      (or (when-some [reply (take-reply! peer kind reply-ms)]
            (when (good? reply) reply))
          (when (> deadline (System/currentTimeMillis))
            (Thread/sleep 10)
            (recur))))))


(defn- settled-read!
  "Ask one read op until its outcome stops answering not-yet.  The
   child's own ticker drives its dial between the parent's asks; this
   side's ticker is driven here, between every ask."
  [acceptor peer op]
  (ask-until! acceptor peer
              {:cmd :read :op op}
              :read
              (fn [reply]
                (not (contains? not-yet (:outcome reply))))
              settle-ms))


;; =============================================================================
;; cljd -> clj: the Dart peer dials and reads this JVM server
;; =============================================================================


(deftest a-dart-dialer-reads-a-jvm-server
  (if-not (.exists (io/file dart-peer-exe))
    (println ";; SKIPPED a-dart-dialer-reads-a-jvm-server:"
             dart-peer-exe "is absent -- compile it first:"
             "clojure -M:clojuredart:cljd compile dao.stream.ws-project-peer &&"
             "dart compile exe"
             "lib/cljd-out/dao/stream/ws-project-peer.dart -o"
             dart-peer-exe)
    (let [server (jvm-acceptor)
          bound (eventually (fn [] @(:port server)) 5000)
          descriptor (when bound
                       (assoc (:descriptor server) :ws/port bound))
          peer (when descriptor (start-peer! descriptor))]
      (try
        (is (some? bound) "the listener never reported a bound port")
        (when peer
          (let [ready (take-reply! peer :ready boot-ms)]
            (is (map? ready)
                (str "the Dart dialer never bootstrapped; its other output"
                     " was " (pr-str (take 5 @(:errors peer)))))
            (is (= descriptor (:descriptor ready))
                "the descriptor the Dart peer decoded from text differs"))
          (let [attached (ask-until! (:acceptor server) peer
                                     {:cmd :attach} :attach
                                     (fn [r] (contains? r :outcome))
                                     settle-ms)]
            (is (map? attached) "the attach never answered")
            (is (= :dao.stream/ok (:outcome attached))
                "the Dart dial over a real JVM listener was refused")
            (is (string? (:attachment attached))))
          (let [probe (ask-until! (:acceptor server) peer
                                  {:cmd :probe} :probe
                                  (fn [r] (:surface r))
                                  settle-ms)]
            (is (map? probe)
                "the probe's confirmation never crossed the wire")
            (when (map? probe)
              (is (= #{:reader} (:surface probe))
                  "the served surface was learned from the probe's answer")
              (is (= "str-1" (:identity probe)))
              (is (nil? (:ticker-error probe))
                  (str "the Dart ticker recorded: "
                       (pr-str (:ticker-error probe))))))
          (let [begun (settled-read! (:acceptor server) peer :begin)]
            (is (map? begun) "the cursor ask never settled")
            (is (= :dao.stream/ok (:outcome begun))))
          (let [value (settled-read! (:acceptor server) peer :next)]
            (is (map? value) "the first next never settled")
            (is (= :dao.stream/ok (:outcome value)))
            (is (= "hello" (:value value))
                "nothing knew it was a string"))
          (let [end (settled-read! (:acceptor server) peer :next)]
            (is (map? end) "the second next never settled")
            (is (= :dao.stream/end (:outcome end))
                "the source's own end crossed the wire"))
          (let [adopted (eventually
                          #(do (tick! (:acceptor server))
                               (seq (project/sessions (:acceptor server))))
                          settle-ms)]
            (is (some? adopted) "the Dart dial was never adopted")
            (is (= 1 (count (project/sessions (:acceptor server))))
                "one dial, one session"))
          (ask-until! (:acceptor server) peer
                      {:cmd :quit} :quit (fn [r] (some? r)) reply-ms))
        (finally
          (when peer (stop-peer! peer))
          (jvm/stop-listening! (:listener server) (fn [] nil)))))))

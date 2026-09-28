(ns dao.stream.ws-project-cross-node-test
  "Slice 3's cross-host socket proofs, Node half.

   The ws channel composition was proven twice already -- portably in
   one process, and on one JVM across real loopback sockets.  A wire
   spoken by two hosts is a further fact: only two operating-system
   processes of two different runtimes can falsify it.  This namespace
   is the Node parent of two such pairs, and process B is always
   `dao.stream.ws-project-peer` spawned as a real JVM child:

   * clj->Node: the JVM peer serves (its acceptor behind a real
     http-kit listener) and this Node process dials through the real
     `ws` client, attaches a reflection of \"str-1\", and reads the toy
     end to end -- probe confirmation, \"hello\", and the source's own
     end.

   * Node->clj: this Node process serves (the acceptor composition
     behind a real Node `ws` server) and the JVM peer dials, reads
     \"hello\" through its own reflection, and -- because its dial
     carries the \"str-2\" table and its dial-step answers the other
     direction -- reflects back: this end attaches a reflection of
     \"str-2\" over the same accepted session and reads \"backward\"
     through it.

   Each proof is one promise chain over small named steps: a step that
   fails answers nil, which short-circuits the rest without throwing
   through the chain, and the terminal step reports and cleans up.  The
   child is spoken to only over pipes; its replies are all this parent
   ever learns.  Without a Clojure CLI to spawn the JVM peer the pair
   skips loudly rather than failing or passing silently.  The browser
   dials-and-reads lane has no harness in any of the three lanes and is
   not attempted here."
  (:require [cljs.test :refer [async deftest is]]
            [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.node :as node]
            [dao.stream.ws-project :as project]))


(def ^:private peer-main
  "Process B, a whole peer program on this tree's own JVM classpath."
  "dao.stream.ws-project-peer")


(def ^:private path "/streams/toy")


(def ^:private admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def ^:private not-yet
  "The outcomes a reflection answers while its request is in flight."
  #{:dao.stream/transport-error :dao.stream/blocked})


(def ^:private boot-ms 60000)
(def ^:private bind-ms 30000)
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


(defn- poll-until
  "Resolve with the first truthy value `pred` returns, or nil at the
   deadline.  A pred may answer a promise; polling is the only honest
   wait here, and nothing notifies."
  [pred timeout-ms]
  (js/Promise.
    (fn [resolve _reject]
      (let [deadline (+ (js/Date.now) timeout-ms)]
        (letfn [(tick
                  []
                  (let [value (try (pred) (catch :default _ nil))]
                    (.then (js/Promise.resolve value)
                           (fn [v]
                             (cond
                               v (resolve v)
                               (< deadline (js/Date.now)) (resolve nil)
                               :else (js/setTimeout tick 20))))))]
          (tick))))))


(defn- settle
  "Poll `op` until its outcome leaves the not-yet set; the composition's
   own ticker steps between attempts.  The final result, or nil."
  [op timeout-ms]
  (poll-until
    (fn []
      (let [r (op)]
        (when-not (contains? not-yet (:dao.stream/outcome r)) r)))
    timeout-ms))


(defn- chain
  "Run each (step acc), in order, as a value or a promise; a nil acc
   short-circuits the rest, so one failed step ends the proof without
   throwing through the chain."
  [acc steps]
  (if (or (nil? acc) (empty? steps))
    (js/Promise.resolve acc)
    (-> (js/Promise.resolve ((first steps) acc))
        (.then (fn [acc'] (chain acc' (rest steps)))))))


(defn- line-reader
  [readable on-line]
  (let [pending (atom "")]
    (.setEncoding ^js readable "utf8")
    (.on ^js readable "data"
         (fn [chunk]
           (let [parts (str/split (str @pending chunk) #"\n" -1)]
             (reset! pending (last parts))
             (doseq [line (butlast parts)
                     :when (not (str/blank? line))]
               (on-line line)))))))


(defn- jvm-classpath
  "The JVM test classpath, as a promise of a string.  Nil when the
   Clojure CLI is absent: the parent then cannot spawn a JVM peer and
   the pair skips loudly."
  []
  (js/Promise.
    (fn [resolve _reject]
      (let [child-process (js/require "child_process")]
        (.exec child-process "clojure -Spath -M:test"
               (fn [error stdout _stderr]
                 (if (or error (str/blank? (str stdout)))
                   (resolve nil)
                   ;; every clojure launch prints a banner line first;
                   ;; the classpath is the last non-empty line.
                   (resolve (->> (str/split (str stdout) #"\n")
                                 (map str/trim)
                                 (remove str/blank?)
                                 (last))))))))))


(defn- start-jvm-peer!
  "Spawn process B as a JVM child on the given classpath with `args`."
  [classpath args]
  (let [child-process (js/require "child_process")
        command (into ["-cp" classpath "clojure.main" "-m" peer-main] args)
        process (.spawn child-process "java" (to-array command)
                        #js {:stdio #js ["pipe" "pipe" "pipe"]})
        replies (atom [])
        errors (atom [])]
    ;; Loading B's namespaces prints to B's stdout before the peer protocol
    ;; starts, so a line that is not a Transit reply is noise rather than a
    ;; protocol failure.
    (line-reader (.-stdout process)
                 (fn [line]
                   (let [reply (try (transit/decode line)
                                    (catch :default _ nil))]
                     (if (:reply reply)
                       (swap! replies conj reply)
                       (swap! errors conj line)))))
    (line-reader (.-stderr process) #(swap! errors conj %))
    {:process process :replies replies :errors errors}))


(defn- stop-peer!
  [peer]
  (try (.kill ^js (:process peer)) (catch :default _ nil)))


(defn- take-reply!
  "Consume the first reply of `kind`, or nil at the deadline."
  [peer kind timeout-ms]
  (poll-until
    (fn []
      (when-some [index (first (keep-indexed (fn [i r]
                                               (when (= kind (:reply r)) i))
                                             @(:replies peer)))]
        (let [reply (nth @(:replies peer) index)]
          (swap! (:replies peer)
                 (fn [rs] (into (subvec rs 0 index) (subvec rs (inc index)))))
          reply)))
    timeout-ms))


(defn- ask!
  [peer command kind]
  (.write ^js (.-stdin ^js (:process peer))
          (str (transit/encode command) "\n"))
  (take-reply! peer kind reply-ms))


(defn- settled-read!
  "Ask one read op of the child until its outcome stops answering
   not-yet: the child's own ticker drives its composition between the
   parent's asks.  The final reply, or nil at the deadline."
  [peer op timeout-ms]
  (poll-until
    (fn []
      (-> (ask! peer {:cmd :read :op op} :read)
          (.then (fn [reply]
                   (when (and reply
                              (not (contains? not-yet (:outcome reply))))
                     reply)))))
    timeout-ms))


(defn- descriptor-at
  [port]
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "str-1"
   :ws/host "127.0.0.1"
   :ws/port port
   :ws/path path})


(defn- remote-descriptor
  [channel identity]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity
   :dao.stream/channel channel})


(defn- node-dialer
  "This process's own dialing composition: one dial over the real Node
   client, serving the \"str-2\" table so this end answers the other
   direction of the same connection."
  []
  (let [backward (served-stream "backward")
        traffic (buffer 64)
        events (buffer 64)
        dial (project/dial
               {:attach! (ws/make-attacher
                           {:traffic {:dao.stream/handle traffic
                                      :dao.stream/surface #{:writer}}
                            :admission admission
                            :connect! node/connect!})
                :traffic {:dao.stream/handle traffic
                          :dao.stream/surface #{:writer}}
                :cursor (newest traffic)
                :ring (buffer 64)
                :table {"str-2" {:handle backward :surface #{:reader}}}
                :dao.stream.remote/events events})
        timer (js/setInterval (fn [] (project/dial-step! dial)) 5)]
    {:dial dial
     :events events
     :stop! (fn [] (js/clearInterval timer))}))


(defn- node-acceptor
  "This process's own accepting composition: the ws endpoint with one
   handoff slot, the acceptor, and a real Node listener whose bound port
   is learned from the server's own report."
  []
  (let [hello (served-stream "hello")
        descriptor (descriptor-at 1)
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
        listener (node/listen! endpoint
                               {:host "127.0.0.1"
                                :port 0
                                :accept! (fn [target socket now]
                                           (ws/accept-connection!
                                             endpoint target socket now))
                                :on-listening (fn [address]
                                                (reset! port
                                                        (.-port address)))})
        timer (js/setInterval
                (fn [] (project/accept-step! acceptor (js/Date.now))) 5)]
    {:acceptor acceptor
     :listener listener
     :port port
     :stop! (fn []
              (js/clearInterval timer)
              (node/stop-listening! listener))}))


(defn- confirmation-surface
  "The surface of the attach probe's confirmation on the event writer."
  [events]
  (loop [c (:dao.stream/cursor (stream/cursor events stream/anchor-oldest))]
    (let [r (stream/next events c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (or (:dao.stream.remote/surface (:dao.stream/value r))
            (recur (:dao.stream/cursor r)))
        nil))))


(defn- reflection-over-session
  "This end's own reflection of `identity` over the accepted session's
   channel end: the attachment point for the reverse direction of the
   one connection."
  [acceptor descriptor identity]
  (let [attachment (first (keys (project/sessions acceptor)))
        end (project/session-end acceptor attachment)
        reflect! (remote/attacher
                   {:dao.stream.remote/channels {descriptor end}})]
    (:dao.stream/handle
      (reflect! (remote-descriptor descriptor identity)))))


;; =============================================================================
;; clj -> Node: the JVM peer serves, this Node process dials and reads
;; =============================================================================


(defn- p1-descriptor
  "The serving reply becomes the reachable descriptor; nil when the JVM
   listener never reported one."
  [peer]
  (-> (take-reply! peer :serving boot-ms)
      (.then
        (fn [serving]
          (when (is (map? serving)
                    (str "the JVM listener never reported a bound port; "
                         "its other output was "
                         (pr-str (take 5 @(:errors peer)))))
            (is (int? (:port serving)))
            {:peer peer
             :descriptor (descriptor-at (:port serving))})))))


(defn- p1-dialer
  "This end's dialing composition, joined into the chain's state.  The
   stop function rides along for the terminal step's cleanup."
  [acc]
  (let [dialer (node-dialer)]
    (.then (js/Promise.resolve acc)
           (fn [acc'] (merge acc' {:dialer dialer})))))


(defn- p1-attach
  "Attach the reflection of \"str-1\" over the dialed channel."
  [{:keys [descriptor dialer] :as acc}]
  (let [attached (project/dial-attach!
                   (:dial dialer)
                   (remote-descriptor descriptor "str-1"))]
    (when (is (= :dao.stream/ok (:dao.stream/outcome attached))
              "the Node dial over a real JVM listener was refused")
      (is (string? (:dao.stream/attachment attached)))
      (assoc acc :reflection (:dao.stream/handle attached)))))


(defn- p1-cursor
  "Settle the cursor ask and prove the probe's confirmation crossed the
   wire: the event writer recorded the served stream's declared
   surface."
  [{:keys [dialer reflection] :as acc}]
  (-> (settle #(stream/cursor reflection stream/anchor-oldest) settle-ms)
      (.then
        (fn [c0]
          (when (is (some? c0) "the cursor ask never settled")
            (is (= :dao.stream/ok (:dao.stream/outcome c0)))
            (is (= #{:reader} (confirmation-surface (:events dialer)))
                "the confirmation carries the served surface")
            (assoc acc :c0 (:dao.stream/cursor c0)))))))


(defn- p1-value
  "The first next: \"hello\", and nothing knew it was a string."
  [{:keys [reflection c0] :as acc}]
  (-> (settle #(stream/next reflection c0) settle-ms)
      (.then
        (fn [n]
          (when (is (some? n) "the first next never settled")
            (is (= :dao.stream/ok (:dao.stream/outcome n)))
            (is (= "hello" (:dao.stream/value n))
                "nothing knew it was a string")
            (assoc acc :c1 (:dao.stream/cursor n)))))))


(defn- p1-end
  "The second next: the source's own end."
  [{:keys [reflection c1] :as acc}]
  (-> (settle #(stream/next reflection c1) settle-ms)
      (.then
        (fn [e]
          (when (is (some? e) "the second next never settled")
            (is (= :dao.stream/end (:dao.stream/outcome e))
                "the source's own end")
            acc)))))


(defn- p1-sessions
  "The JVM acceptor adopted exactly one connection for the one dial."
  [{:keys [peer] :as acc}]
  (-> (ask! peer {:cmd :sessions} :sessions)
      (.then
        (fn [sessions]
          (when (is (map? sessions) "the sessions ask never answered")
            (is (= 1 (:count sessions))
                "the JVM acceptor adopted one connection for one dial")
            (is (nil? (:ticker-error sessions)))
            acc)))))


(deftest a-node-dialer-reads-a-jvm-server
  (async done
         (.then (jvm-classpath)
                (fn [classpath]
                  (if (nil? classpath)
                    (do (println ";; SKIPPED a-node-dialer-reads-a-jvm-server:"
                                 "no Clojure CLI, so no JVM peer can serve")
                        (done))
                    (let [peer (start-jvm-peer! classpath ["--serve"])
                          finish! (fn []
                                    (stop-peer! peer)
                                    (done))]
                      (-> (chain peer [p1-descriptor
                                       p1-dialer
                                       p1-attach
                                       p1-cursor
                                       p1-value
                                       p1-end
                                       p1-sessions])
                          (.then
                            (fn [acc]
                              (when (nil? acc)
                                (is false "the clj->Node proof stopped early"))
                              (when-some [dialer (:dialer acc)]
                                ((:stop! dialer)))
                              (finish!)))
                          (.catch
                            (fn [error]
                              (is false (str "clj->Node proof failure: "
                                             (pr-str error)))
                              (finish!))))))))))


;; =============================================================================
;; Node -> clj: this Node process serves, the JVM peer dials and its
;; dial reflects back over the same connection
;; =============================================================================


(defn- p2-bound
  "The Node listener's own bound-port report becomes the descriptor this
   parent hands to the child as text."
  [{:keys [server] :as acc}]
  (-> (poll-until (fn [] @(:port server)) bind-ms)
      (.then
        (fn [port]
          (when (is (int? port)
                    "the Node listener never reported a bound port")
            (assoc acc :descriptor (descriptor-at port)))))))


(defn- p2-spawn
  "Spawn the JVM dialer with the descriptor as its sole argument, and
   register it with the proof's kill list so no path leaks the child."
  [{:keys [classpath descriptor peers] :as acc}]
  (let [peer (start-jvm-peer! classpath
                              [(transit/encode-descriptor descriptor)])]
    (swap! peers conj peer)
    (assoc acc :peer peer)))


(defn- p2-ready
  "The child's bootstrap: the descriptor it decoded from text is the
   one this parent encoded."
  [{:keys [peer descriptor] :as acc}]
  (-> (take-reply! peer :ready boot-ms)
      (.then
        (fn [ready]
          (when (is (map? ready)
                    (str "the JVM dialer never bootstrapped; its other"
                         " output was "
                         (pr-str (take 5 @(:errors peer)))))
            (is (= descriptor (:descriptor ready))
                "the descriptor the JVM peer decoded from text differs")
            acc)))))


(defn- p2-attach
  "The child dials: one attach over the descriptor, one attachment."
  [{:keys [peer] :as acc}]
  (-> (ask! peer {:cmd :attach} :attach)
      (.then
        (fn [attached]
          (when (is (map? attached) "the attach never answered")
            (is (= :dao.stream/ok (:outcome attached)))
            (is (string? (:attachment attached)))
            acc)))))


(defn- p2-probe-confirmed
  "Poll the probe until the confirmation's surface is present."
  [{:keys [peer] :as acc}]
  (-> (poll-until
        (fn []
          (-> (ask! peer {:cmd :probe} :probe)
              (.then #(when (:surface %) %))))
        settle-ms)
      (.then
        (fn [confirmed]
          (when (is (some? confirmed)
                    "the probe's confirmation never crossed the wire")
            acc)))))


(defn- p2-probe
  "The confirmation's facts: the served surface, the served identity,
   and a child ticker that never threw."
  [{:keys [peer] :as acc}]
  (-> (ask! peer {:cmd :probe} :probe)
      (.then
        (fn [probe]
          (when (is (map? probe)
                    "the probe's confirmation never crossed the wire")
            (is (= #{:reader} (:surface probe))
                "the served surface was learned from the probe's answer")
            (is (= "str-1" (:identity probe)))
            (is (nil? (:ticker-error probe)))
            acc)))))


(defn- p2-read-begun
  "The child's cursor ask settles ok."
  [{:keys [peer] :as acc}]
  (-> (settled-read! peer :begin settle-ms)
      (.then
        (fn [reply]
          (when (is (map? reply) "the JVM dialer's cursor ask never settled")
            (is (= :dao.stream/ok (:outcome reply)))
            acc)))))


(defn- p2-read-hello
  "The child reads \"hello\" through its reflection of this end's
   served stream."
  [{:keys [peer] :as acc}]
  (-> (settled-read! peer :next settle-ms)
      (.then
        (fn [reply]
          (when (is (map? reply) "the child's first next never settled")
            (is (= :dao.stream/ok (:outcome reply)))
            (is (= "hello" (:value reply))
                "the clj dial read the Node acceptor's value over the wire")
            acc)))))


(defn- p2-read-end
  "The child's source end: \"hello\" was a complete history."
  [{:keys [peer] :as acc}]
  (-> (settled-read! peer :next settle-ms)
      (.then
        (fn [reply]
          (when (is (map? reply) "the child's second next never settled")
            (is (= :dao.stream/end (:outcome reply))
                "the source's own end crossed the wire too")
            acc)))))


(defn- p2-b-reflect
  "The clj dial reflects back: this end attaches a reflection of the
   dialer's own \"str-2\" table entry over the same accepted session,
   to be read through the dial-side mirror the JVM peer runs on that
   one connection."
  [{:keys [acceptor descriptor] :as acc}]
  (-> (poll-until #(seq (project/sessions acceptor)) settle-ms)
      (.then
        (fn [adopted]
          (when (is (some? adopted) "the JVM dial was never adopted")
            (assoc acc :b-reflection
                   (reflection-over-session acceptor descriptor "str-2")))))))


(defn- p2-b-cursor
  "The reverse reflection's cursor ask settles ok over the session."
  [{:keys [b-reflection] :as acc}]
  (-> (settle #(stream/cursor b-reflection stream/anchor-oldest) settle-ms)
      (.then
        (fn [c0]
          (when (is (some? c0)
                    (str "the reflection over the accepted session"
                         " never established"))
            (is (= :dao.stream/ok (:dao.stream/outcome c0)))
            (assoc acc :b-c0 (:dao.stream/cursor c0)))))))


(defn- p2-b-value
  "\"backward\": the value the child's dial-side mirror served back over
   the same connection."
  [{:keys [b-reflection b-c0] :as acc}]
  (-> (settle #(stream/next b-reflection b-c0) settle-ms)
      (.then
        (fn [n]
          (when (is (some? n) "the reverse next never settled")
            (is (= :dao.stream/ok (:dao.stream/outcome n)))
            (is (= "backward" (:dao.stream/value n))
                "the clj dial reflected back over the same connection")
            (assoc acc :b-c1 (:dao.stream/cursor n)))))))


(defn- p2-b-end
  "The reverse direction's own source end."
  [{:keys [b-reflection b-c1] :as acc}]
  (-> (settle #(stream/next b-reflection b-c1) settle-ms)
      (.then
        (fn [e]
          (is (= :dao.stream/end (:dao.stream/outcome e)))
          acc))))


(defn- p2-one-session
  "Both directions rode one adopted session."
  [{:keys [acceptor] :as acc}]
  (is (= 1 (count (project/sessions acceptor)))
      "both directions rode one adopted session")
  acc)


(defn- p2-quit
  "Tell the child to leave; its exit closes its end of the socket."
  [{:keys [peer] :as acc}]
  (-> (ask! peer {:cmd :quit} :quit)
      (.then (fn [_reply] acc))))


(deftest a-jvm-dialer-serves-back-over-one-node-connection
  (async done
         (.then (jvm-classpath)
                (fn [classpath]
                  (if (nil? classpath)
                    (do (println ";; SKIPPED"
                                 "a-jvm-dialer-serves-back-over-one-connection:"
                                 "no Clojure CLI, so no JVM peer can dial")
                        (done))
                    (let [server (node-acceptor)
                          peers (atom [])
                          finish! (fn []
                                    ((:stop! server))
                                    (doseq [peer @peers] (stop-peer! peer))
                                    (done))]
                      (-> (chain {:classpath classpath
                                  :acceptor (:acceptor server)
                                  :server server
                                  :peers peers}
                                 [p2-bound
                                  p2-spawn
                                  p2-ready
                                  p2-attach
                                  p2-probe-confirmed
                                  p2-probe
                                  p2-read-begun
                                  p2-read-hello
                                  p2-read-end
                                  p2-b-reflect
                                  p2-b-cursor
                                  p2-b-value
                                  p2-b-end
                                  p2-one-session
                                  p2-quit])
                          (.then
                            (fn [acc]
                              (when (nil? acc)
                                (is false "the Node->clj proof stopped early"))
                              (finish!)))
                          (.catch
                            (fn [error]
                              (is false (str "Node->clj proof failure: "
                                             (pr-str error)))
                              (finish!))))))))))

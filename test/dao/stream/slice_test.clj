(ns dao.stream.slice-test
  "Phase 5 — the WebSocket slice, end to end, across two operating-system
   processes on the JVM.

   This namespace is process A.  It creates a ring buffer, serves it over two
   independent WebSocket endpoints, and appends.  Process B is
   `dao.stream.slice-peer`, spawned as a real child process; it obtains A's
   descriptor as Transit JSON text on its command line — the bootstrap channel
   is part of the test, not an assumption — attaches, appends outbound, and
   reports what its own deposit medium received.

   Nothing is shared between A and B but bytes on a pipe and bytes on a
   socket.  That is what a same-process socket test cannot establish, and it
   is why the plan requires two processes here.

   The five facts of the plan's Phase 5 are one `deftest` each.  They share one
   process A through a `:once` fixture, because fact 2 is precisely that the
   served stream survives a connection's death: a per-test server would make
   survival unfalsifiable."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.serving :as serving]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.jvm :as jvm]))


;; =============================================================================
;; Explicit composition data
;; =============================================================================

(def admission
  {:retention :evict-oldest :capacity 256 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


;; Deadlines.  A peer is a whole JVM, so its first breath is slow while every
;; later exchange is a pipe round trip.
(def ^:private peer-ready-ms 60000)
(def ^:private reply-ms 15000)
(def ^:private event-ms 15000)


(defn- buffer
  ([] (buffer 256))
  ([capacity]
   (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type
                                      ring/capacity-key capacity}))))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn- drain
  "Every retained value on a medium, read from the oldest position.  Reads are
   non-destructive, so this never disturbs the driver's own cursor."
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))
         seen []]
    (let [result (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome result))
        (recur (:dao.stream/cursor result) (conj seen (:dao.stream/value result)))
        seen))))


(defn- terminal-event
  "The lifecycle event that ends an attachment's history, if it has arrived."
  [events]
  (first (filter #(contains? #{:ws/closed :ws/ended} (:ws/event %)) events)))


(defn- await-terminal
  "Poll one server-side per-attachment medium until its history is complete.

   Asserting attribution over a history that has not yet ended is the hole this
   closes: `every?` over events that exclude the terminal one is satisfied by an
   implementation that mislabels `:ws/attachment` exactly on `:ws/closed`."
  [endpoint attachment]
  (let [deadline (+ (System/currentTimeMillis) event-ms)]
    (loop []
      (let [events (drain (get @(:media endpoint) attachment))]
        (if (or (terminal-event events) (< deadline (System/currentTimeMillis)))
          events
          (do (Thread/sleep 20) (recur)))))))


(defn- logical-identity
  "The served stream's own logical-stream identity, taken from the handle
   rather than invented by this test.  A constant here would prove only that
   two hand-written descriptors share a constant."
  [source]
  (:dao.stream/identity (stream/descriptor source)))


(defn- table-descriptor
  "The served table's descriptor.  Its port is a placeholder because the bound
   port is only known after listening; path lookup never consults the port, and
   each test repairs reachability for its peer from the bound address."
  [source path]
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity (logical-identity source)
   :ws/host "127.0.0.1"
   :ws/port 1
   :ws/path path})


;; =============================================================================
;; Process A: one served ring buffer behind two endpoints
;; =============================================================================

(defn- make-endpoint-composition
  "Compose one serving endpoint over `source`, with `slot-count` capacity-one
   acceptance-handoff slots.  Two slots would make fact 3 a race against the
   driver's cadence rather than a test of attachment identity."
  [source path slot-count]
  (let [descriptor (table-descriptor source path)
        control (buffer)
        slots (vec (repeatedly slot-count (fn [] {:offer (buffer 1) :ack (buffer 1)})))
        endpoint (ws/make-endpoint
                   {:served {path descriptor}
                    :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
                    :control-admission admission
                    :slots (mapv (fn [{:keys [offer ack]}]
                                   {:offer {:dao.stream/handle offer
                                            :dao.stream/surface #{:writer}}
                                    :offer-admission handoff-admission
                                    :ack {:dao.stream/handle ack
                                          :dao.stream/surface #{:writer}}
                                    :ack-admission handoff-admission
                                    :ack-cursor (newest ack)})
                                 slots)
                    :expiry-ms nil})
        media (atom {})
        listener (atom nil)
        port (atom nil)
        lifecycle (atom [])
        composition
        (serving/make-serving
          {:endpoint endpoint
           :served {path {:descriptor descriptor :stream source}}
           :control-reader control
           :control-cursor (newest control)
           :slots (mapv (fn [{:keys [offer ack]}]
                          {:offer-reader offer
                           :offer-cursor (newest offer)
                           :ack-writer {:dao.stream/handle ack
                                        :dao.stream/surface #{:writer}}})
                        slots)
           ;; Newest rather than the default oldest: these tests share one
           ;; served stream, and a replay from oldest would let one fact's
           ;; appends arrive at another fact's attachment.
           :forward-anchor stream/anchor-newest
           :forward-options {:batch-budget 16 :gap-policy :terminate}
           :make-traffic (fn [offer-event]
                           (let [traffic (buffer)]
                             (swap! media assoc (:ws/attachment offer-event) traffic)
                             {:traffic {:dao.stream/handle traffic
                                        :dao.stream/surface #{:writer}}
                              :admission admission
                              :reader traffic
                              :cursor (newest traffic)}))
           ;; No `:inbound-step`: inbound payload stays ordinary traffic data
           ;; on the per-attachment medium and is deliberately not interpreted
           ;; into appends on the served stream.
           :start-endpoint! (fn [ep]
                              (let [result
                                    (jvm/listen!
                                      {:bind-host "127.0.0.1"
                                       :bind-port 0
                                       :accept! (fn [target socket now]
                                                  (ws/accept-connection! ep target socket now))
                                       :deposit! (fn [kind data]
                                                   (swap! lifecycle conj [kind data])
                                                   (when (= :bind-succeeded kind)
                                                     (reset! port (:port data))))})]
                                (reset! listener result)
                                {:dao.stream/outcome :dao.stream/ok}))
           :stop-endpoint! (fn [_]
                             (jvm/stop-listening! @listener (fn [] nil)))})]
    {:path path
     :descriptor descriptor
     :composition composition
     :media media
     :port port
     :lifecycle lifecycle}))


(def ^:private process-a (atom nil))


(defn- start-process-a!
  []
  (let [source (buffer)
        primary (make-endpoint-composition source "/slice" 4)
        secondary (make-endpoint-composition source "/slice-b" 4)
        running (atom true)]
    (serving/start! (:composition primary))
    (serving/start! (:composition secondary))
    (let [driver (doto (Thread.
                         (fn []
                           (while @running
                             (let [now (System/currentTimeMillis)]
                               (serving/step! (:composition primary) now)
                               (serving/step! (:composition secondary) now))
                             (Thread/sleep 5)))
                         "slice-test-driver")
                   (.setDaemon true)
                   (.start))]
      {:source source
       :primary primary
       :secondary secondary
       :running running
       :driver driver})))


(defn- stop-process-a!
  [a]
  (reset! (:running a) false)
  (.join ^Thread (:driver a) 2000)
  (serving/stop! (:composition (:primary a)))
  (serving/stop! (:composition (:secondary a))))


(defn- reachable
  "Repair a served descriptor's reachability from the bound port.  This is the
   value that crosses to B as text, and the only thing B ever learns about A."
  [endpoint]
  (assoc (:descriptor endpoint) :ws/port @(:port endpoint)))


;; =============================================================================
;; Process B: a real child process, spoken to only over pipes
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
  "Spawn process B on this process's own classpath.

   Reusing the live classpath rather than shelling out to a fresh `clojure`
   invocation keeps the child's startup to a JVM boot instead of a dependency
   resolution, and guarantees B runs the same tree A does."
  [descriptor]
  (let [command ["java" "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "dao.stream.slice-peer"
                 (transit/encode-descriptor descriptor)]
        process (.start (ProcessBuilder. ^java.util.List command))
        replies (atom [])
        errors (atom [])
        peer {:process process
              :in (io/writer (.getOutputStream process))
              :replies replies
              :errors errors
              :seen (atom [])}]
    ;; Loading B's namespaces prints to B's stdout before the peer protocol
    ;; starts, so a line that is not a Transit reply is noise rather than a
    ;; protocol failure: recording it and carrying on keeps one banner from
    ;; silently killing the reader and making every fact look unmet.
    (pump! (io/reader (.getInputStream process))
           (fn [line]
             (let [reply (try (transit/decode line) (catch Exception _ nil))]
               (if (:reply reply)
                 (swap! replies conj reply)
                 (swap! errors conj line)))))
    ;; stderr must be drained too: an unread pipe fills and would block the
    ;; child mid-report, which would look exactly like a failed assertion.
    (pump! (io/reader (.getErrorStream process))
           (fn [line] (swap! errors conj line)))
    peer))


(defn- stop-peer!
  [peer]
  (try (.destroy ^Process (:process peer)) (catch Exception _ nil)))


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
                         (fn [rs] (vec (concat (subvec rs 0 index) (subvec rs (inc index))))))
                  reply)
          (< deadline (System/currentTimeMillis)) nil
          :else (do (Thread/sleep 5) (recur)))))))


(defn- ask!
  [peer command kind timeout-ms]
  (let [writer ^java.io.Writer (:in peer)]
    (.write writer (str (transit/encode command) "\n"))
    (.flush writer))
  (take-reply! peer kind timeout-ms))


(defn- poll-events!
  "Ask B for whatever its medium has accumulated since B last reported, and
   remember it.  B advances only its own kept cursor; A never touches it."
  [peer]
  (swap! (:seen peer) into (:events (ask! peer {:cmd :events} :events reply-ms)))
  @(:seen peer))


(defn- await-event
  [peer pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [hit (first (filter pred (poll-events! peer)))]
        hit
        (when-not (< deadline (System/currentTimeMillis))
          (Thread/sleep 20)
          (recur))))))


(defn- attach-peer!
  "Spawn B, wait for its bootstrap, attach, and wait for the opened event."
  [descriptor]
  (let [peer (start-peer! descriptor)
        ready (take-reply! peer :ready peer-ready-ms)]
    (is (some? ready)
        (str "process B never reported its bootstrap; its other output was "
             (pr-str @(:errors peer))))
    (is (= descriptor (:descriptor ready))
        "the descriptor B decoded from text differs from the one A encoded")
    (let [attach (ask! peer {:cmd :attach} :attach reply-ms)]
      (is (= :dao.stream/ok (:outcome attach)))
      (is (some? (await-event peer #(= :ws/opened (:ws/event %)) event-ms))
          "the attachment never opened")
      (assoc peer :attachment (:attachment attach)))))


(defn- server-attachment
  "Correlate B to the server-side session it joined by the token it appended.
   The two sides mint their own attachment identities, so nothing but a
   payload can connect them — which is itself worth asserting."
  [endpoint token]
  (let [deadline (+ (System/currentTimeMillis) event-ms)]
    (loop []
      (let [hit (first (filter (fn [[_ medium]]
                                 (some #(= token (:ws/value %)) (drain medium)))
                               @(:media endpoint)))]
        (cond
          hit (first hit)
          (< deadline (System/currentTimeMillis)) nil
          :else (do (Thread/sleep 20) (recur)))))))


;; =============================================================================
;; Fixture
;; =============================================================================

(use-fixtures :once
  (fn [run]
    (reset! process-a (start-process-a!))
    (try (run)
         (finally
           (stop-process-a! @process-a)
           (reset! process-a nil)))))


;; =============================================================================
;; The five facts
;; =============================================================================

(deftest detachment-is-observable-on-both-sides
  ;; Fact 1.
  (let [{:keys [source primary]} @process-a
        peer (attach-peer! (reachable primary))]
    (try
      (is (= :dao.stream/ok (:outcome (ask! peer {:cmd :append :value :slice/established}
                                            :append reply-ms)))
          "an established attachment must accept an outbound append")
      (let [token [:slice/fact-1 (:attachment peer)]]
        (is (= :dao.stream/ok (:outcome (ask! peer {:cmd :append :value token}
                                              :append reply-ms))))
        (let [attachment (server-attachment primary token)]
          (is (some? attachment) "A never received B's outbound payload")

          ;; Kill the connection from B's side.
          (is (= :dao.stream/ok (:outcome (ask! peer {:cmd :close} :close reply-ms))))

          (testing "B's handle answers closed"
            (is (= :dao.stream/closed
                   (:outcome (ask! peer {:cmd :append :value :after-close}
                                   :append reply-ms))))
            (let [departure (await-event peer #(= :ws/closed (:ws/event %)) event-ms)]
              (is (some? departure)
                  "B's own medium never received the terminal event")
              (is (= (:attachment peer) (:ws/attachment departure))
                  "B's terminal event was not attributed to its attachment")))

          (testing "A's boundary deposits the departure"
            (let [events (await-terminal primary attachment)
                  departure (terminal-event events)]
              (is (= :ws/closed (:ws/event (last events)))
                  "A's per-attachment medium never deposited the departure")
              (is (= attachment (:ws/attachment departure))
                  "A's terminal event was not attributed to its session"))
            (is (not (contains? (:sessions (serving/state (:composition primary)))
                                attachment))
                "the driver kept a session for a departed attachment"))

          (testing "the served stream is untouched by a connection's death"
            (is (= :dao.stream/ok (:dao.stream/outcome
                                    (stream/append! source :slice/still-alive)))))))
      (finally (stop-peer! peer)))))


(deftest the-same-descriptor-rejoins-the-same-logical-stream
  ;; Fact 2.  The peer from fact 1 is gone; this one attaches, is killed, and
  ;; reattaches with the identical bootstrapped descriptor value.
  (let [{:keys [source primary]} @process-a
        peer (attach-peer! (reachable primary))]
    (try
      (is (= :dao.stream/ok (:outcome (ask! peer {:cmd :close} :close reply-ms))))
      (is (some? (await-event peer #(= :ws/closed (:ws/event %)) event-ms)))
      (let [again (ask! peer {:cmd :attach} :attach reply-ms)]
        (is (= :dao.stream/ok (:outcome again))
            "reattachment with the same descriptor was refused")
        (is (not= (:attachment peer) (:attachment again))
            "a reattachment is a new attachment, not a resurrected one")
        (is (some? (await-event peer (fn [event]
                                       (and (= :ws/opened (:ws/event event))
                                            (= (:attachment again) (:ws/attachment event))))
                                event-ms)))
        (let [token [:slice/fact-2 (:attachment again)]]
          ;; A appends to the surviving logical stream; the rejoined
          ;; attachment receives it, which is what "same logical stream" means
          ;; operationally.
          (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source token))))
          (is (some? (await-event peer #(= token (:ws/value %)) event-ms))
              "the rejoined attachment received nothing from the served stream")))
      (finally (stop-peer! peer)))))


(deftest simultaneous-attachments-carry-distinct-attachment-identity
  ;; Fact 3.  Two whole processes, so nothing but the wire could make their
  ;; identities agree by accident.
  (let [{:keys [source primary]} @process-a
        descriptor (reachable primary)
        peer-1 (attach-peer! descriptor)
        peer-2 (attach-peer! descriptor)]
    (try
      (is (string? (:attachment peer-1)))
      (is (string? (:attachment peer-2)))
      (is (not= (:attachment peer-1) (:attachment peer-2))
          "two simultaneous attachments shared one attachment identity")

      (let [token-1 [:slice/fact-3 (:attachment peer-1)]
            token-2 [:slice/fact-3 (:attachment peer-2)]]
        (ask! peer-1 {:cmd :append :value token-1} :append reply-ms)
        (ask! peer-2 {:cmd :append :value token-2} :append reply-ms)
        (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source :slice/fanout))))
        (await-event peer-1 #(= :slice/fanout (:ws/value %)) event-ms)
        (await-event peer-2 #(= :slice/fanout (:ws/value %)) event-ms)

        (let [a-1 (server-attachment primary token-1)
              a-2 (server-attachment primary token-2)]
          (is (some? a-1))
          (is (some? a-2))
          (is (not= a-1 a-2)
              "two connections were folded into one server-side attachment")

          ;; Close both before asserting attribution.  Correlation checked
          ;; while the attachments are live sees only resolution and payload
          ;; events; the terminal ones would arrive later, during teardown,
          ;; unobserved.  An implementation that emitted a wrong or missing
          ;; `:ws/attachment` exactly on `:ws/closed` would pass such a check.
          (doseq [peer [peer-1 peer-2]]
            (is (= :dao.stream/ok (:outcome (ask! peer {:cmd :close} :close reply-ms)))))

          (testing "every event on a client medium carries that client's identity"
            (doseq [peer [peer-1 peer-2]]
              (is (some? (await-event peer #(= :ws/closed (:ws/event %)) event-ms))
                  "a client medium never received its terminal event")
              (let [events @(:seen peer)
                    terminal (terminal-event events)]
                (is (seq events))
                ;; Resolution, payload, and lifecycle alike.
                (is (every? #(= (:attachment peer) (:ws/attachment %)) events)
                    "an event on B's medium was attributed to another attachment")
                (is (some? terminal))
                (is (= (:attachment peer) (:ws/attachment terminal))
                    "B's terminal event was not attributed to its attachment"))))

          (testing "every event on a server medium carries that session's identity"
            (doseq [attachment [a-1 a-2]]
              (let [events (await-terminal primary attachment)
                    terminal (terminal-event events)]
                (is (seq events))
                (is (some? terminal)
                    "a server medium never deposited its terminal event")
                ;; Payload and lifecycle alike.
                (is (every? #(= attachment (:ws/attachment %)) events))
                (is (= attachment (:ws/attachment terminal))
                    "A's terminal event was not attributed to its session"))))))
      (finally
        (stop-peer! peer-1)
        (stop-peer! peer-2)))))


(deftest the-deposit-medium-outlives-the-socket
  ;; Fact 4.  The cursor is B's, on B's medium; it is never handed to
  ;; `attach!` and never used through the WebSocket handle.
  (let [{:keys [source primary]} @process-a
        peer (attach-peer! (reachable primary))]
    (try
      (is (= #{:writer :closable} (:value (ask! peer {:cmd :surfaces} :surfaces reply-ms)))
          "the ws handle must have no reader surface for this fact to mean anything")

      (let [token [:slice/fact-4 (:attachment peer)]]
        (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source token))))
        (is (some? (await-event peer #(= token (:ws/value %)) event-ms))))

      ;; The socket dies.  B's medium and B's position on it do not.
      (is (= :dao.stream/ok (:outcome (ask! peer {:cmd :close} :close reply-ms))))
      (is (some? (await-event peer #(= :ws/closed (:ws/event %)) event-ms))
          "the kept cursor did not resume across the socket's death")

      (testing "the cursor resumed rather than restarted"
        ;; Every event B has reported came from one uninterrupted walk of one
        ;; cursor: an opened, a payload, and exactly one terminal event, in
        ;; order and without repetition.
        (let [events @(:seen peer)]
          (is (= [:ws/opened :ws/payload :ws/closed] (mapv :ws/event events)))
          (is (apply distinct? events))))
      (finally (stop-peer! peer)))))


(deftest two-endpoints-differ-in-reachability-and-agree-on-identity
  ;; Fact 5.
  (let [{:keys [source primary secondary]} @process-a
        projection (stream/descriptor source)
        identity (:dao.stream/identity projection)
        d-1 (reachable primary)
        d-2 (reachable secondary)]
    (is (not= d-1 d-2) "two endpoints produced the same descriptor")
    (is (not= (select-keys d-1 [:ws/host :ws/port :ws/path])
              (select-keys d-2 [:ws/host :ws/port :ws/path]))
        "the two endpoints are not separately reachable")

    (testing "the served descriptors carry the ring buffer's own identity"
      ;; Not a constant this test chose: the value the served stream mints.
      (is (= :dao.stream/ok (:dao.stream/outcome projection)))
      (is (string? identity))
      (is (= identity (:dao.stream/identity (:dao.stream/descriptor projection)))
          "the sibling identity disagrees with the one inside the envelope")
      (is (= identity (:dao.stream/identity d-1)))
      (is (= identity (:dao.stream/identity d-2))
          "one logical stream served twice must carry one identity"))

    (let [peer-1 (attach-peer! d-1)
          peer-2 (attach-peer! d-2)]
      (try
        (testing "each handle projects its own reachability and the shared identity"
          (let [h-1 (ask! peer-1 {:cmd :handle-descriptor} :handle-descriptor reply-ms)
                h-2 (ask! peer-2 {:cmd :handle-descriptor} :handle-descriptor reply-ms)]
            (is (not= (:descriptor h-1) (:descriptor h-2)))
            (is (= identity (:identity h-1)))
            (is (= identity (:identity h-2))
                "an attached handle projected an identity the served stream never minted")))

        (testing "cursors compare identity, never a descriptor"
          ;; One cursor on the served stream is valid for the logical stream
          ;; both endpoints serve; neither descriptor participates.  The cursor
          ;; carries the same identity the descriptors do, which is what makes
          ;; that comparison possible without either descriptor.
          (let [minted (stream/cursor source stream/anchor-newest)]
            (is (= :dao.stream/ok (:dao.stream/outcome minted)))
            (is (= identity (:dao.stream.ringbuffer/identity (:dao.stream/cursor minted)))
                "the cursor's identity disagrees with the served descriptors'")
            (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source :slice/both))))
            (is (= :slice/both (:dao.stream/value
                                 (stream/next source (:dao.stream/cursor minted)))))))

        (testing "both endpoints serve the same logical stream"
          (is (some? (await-event peer-1 #(= :slice/both (:ws/value %)) event-ms)))
          (is (some? (await-event peer-2 #(= :slice/both (:ws/value %)) event-ms))))
        (finally
          (stop-peer! peer-1)
          (stop-peer! peer-2))))))

(ns dao.stream.v2.slice-test
  "Phase 5 — the WebSocket slice, end to end, across two operating-system
   processes on Node.

   The JVM half of this phase is `test/dao/stream/v2/slice_test.clj`, which
   carries the same five facts against the same peer program.  The two files
   share a namespace name and no code: one is invisible to the other's
   compiler, and the plan's host matrix wants Node server plus Node client
   proven as its own slice rather than inferred from the JVM run.

   Process A is here.  Process B is `dao.stream.v2.slice-peer`, compiled by
   the `:slice-peer` shadow build to `target/slice-peer.js` and spawned as a
   real child `node` process — see the `test:cljs` task in `bb.edn`, which
   compiles that build before this one.  B receives A's descriptor as Transit
   JSON text in `argv` and learns nothing else about A.

   Node is single-threaded, so every wait here is a turn on the event loop:
   the driver is an explicit `setInterval` the test owns, and each step of a
   fact is a promise that polls.  Nothing in the transport schedules itself."
  (:require ["child_process" :as child-process]
            [cljs.test :refer [async deftest is testing]]
            [clojure.string :as str]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.serving :as serving]
            [dao.stream.v2.transit :as transit]
            [dao.stream.v2.ws :as ws]
            [dao.stream.v2.ws.node :as node]))


;; =============================================================================
;; Explicit composition data
;; =============================================================================

(def admission
  {:retention :evict-oldest :capacity 256 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def peer-script "target/slice-peer.js")


(def bind-ms 5000)
(def reply-ms 10000)
(def event-ms 10000)


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
   acceptance-handoff slots.  One slot would make fact 3 a race against the
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
           ;; Newest rather than the default oldest: an attachment observes
           ;; what is appended after it joined, so one fact's appends cannot
           ;; be replayed into another fact's attachment.
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
                              (reset! listener (node/listen! ep {:host "127.0.0.1"
                                                                 :port 0}))
                              {:dao.stream/outcome :dao.stream/ok})
           :stop-endpoint! (fn [_]
                             (node/stop-listening! @listener)
                             {:dao.stream/outcome :dao.stream/ok})})]
    {:path path
     :descriptor descriptor
     :composition composition
     :media media
     :listener listener}))


(defn- server-fixture
  []
  (let [source (buffer)]
    {:source source
     :primary (make-endpoint-composition source "/slice" 4)
     :secondary (make-endpoint-composition source "/slice-b" 4)}))


(defn- reachable
  "Repair a served descriptor's reachability from the bound port.  This is the
   value that crosses to B as text, and the only thing B ever learns about A."
  [endpoint]
  (assoc (:descriptor endpoint) :ws/port (node/listener-port @(:listener endpoint))))


;; =============================================================================
;; Waiting, as promises over the event loop
;; =============================================================================

(defn- poll-until
  "Resolve with the first truthy value `pred` returns, or reject at the
   deadline.  Polling is the only honest wait here: no operation blocks, and
   nothing notifies."
  [pred timeout-ms message]
  (js/Promise.
    (fn [resolve reject]
      (let [deadline (+ (js/Date.now) timeout-ms)]
        (letfn [(tick
                  []
                  (let [value (try (pred) (catch :default _ nil))]
                    (cond
                      value (resolve value)
                      (< deadline (js/Date.now)) (reject (js/Error. message))
                      :else (js/setTimeout tick 10))))]
          (tick))))))


(defn- await-terminal
  "Resolve with one server-side medium's history once it is complete.

   Asserting attribution over a history that has not yet ended is the hole this
   closes: `every?` over events that exclude the terminal one is satisfied by an
   implementation that mislabels `:ws/attachment` exactly on `:ws/closed`."
  [endpoint attachment]
  (poll-until (fn []
                (let [events (drain (get @(:media endpoint) attachment))]
                  (when (terminal-event events) events)))
              event-ms
              "a server medium never deposited its terminal event"))


;; =============================================================================
;; Process B: a real child process, spoken to only over pipes
;; =============================================================================

(defn- line-reader
  "Collect whole newline-terminated lines out of a Node stream's chunks."
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


(defn- start-peer!
  [descriptor]
  (let [process (.spawn child-process "node"
                        #js [peer-script (transit/encode-descriptor descriptor)]
                        #js {:stdio #js ["pipe" "pipe" "pipe"]})
        replies (atom [])
        errors (atom [])]
    ;; Loading B's namespaces may print to B's stdout before the peer protocol
    ;; starts, so a line that is not a Transit reply is noise rather than a
    ;; protocol failure: recording it and carrying on keeps one banner from
    ;; silently stopping the reader.
    (line-reader (.-stdout process)
                 (fn [line]
                   (let [reply (try (transit/decode line) (catch :default _ nil))]
                     (if (:reply reply)
                       (swap! replies conj reply)
                       (swap! errors conj line)))))
    (line-reader (.-stderr process) (fn [line] (swap! errors conj line)))
    {:process process :replies replies :errors errors :seen (atom [])}))


(defn- stop-peer!
  [peer]
  (try (.kill ^js (:process peer)) (catch :default _ nil)))


(defn- take-reply!
  "Consume the first reply of `kind`."
  [peer kind]
  (poll-until
    (fn []
      (when-let [index (first (keep-indexed (fn [i r] (when (= kind (:reply r)) i))
                                            @(:replies peer)))]
        (let [reply (nth @(:replies peer) index)]
          (swap! (:replies peer)
                 (fn [rs] (vec (concat (subvec rs 0 index) (subvec rs (inc index))))))
          reply)))
    reply-ms
    (str "process B never replied " kind "; its other output was "
         (pr-str @(:errors peer)))))


(defn- ask!
  [peer command kind]
  (.write ^js (.-stdin ^js (:process peer)) (str (transit/encode command) "\n"))
  (take-reply! peer kind))


(defn- poll-events!
  "Ask B for whatever its medium has accumulated since B last reported, and
   remember it.  B advances only its own kept cursor; A never touches it."
  [peer]
  (.then (ask! peer {:cmd :events} :events)
         (fn [reply]
           (swap! (:seen peer) into (:events reply))
           @(:seen peer))))


(defn- await-event
  [peer pred message]
  (let [deadline (+ (js/Date.now) event-ms)]
    (js/Promise.
      (fn [resolve reject]
        (letfn [(tick
                  []
                  (.then (poll-events! peer)
                         (fn [seen]
                           (if-let [hit (first (filter pred seen))]
                             (resolve hit)
                             (if (< deadline (js/Date.now))
                               (reject (js/Error. message))
                               (js/setTimeout tick 20))))
                         reject))]
          (tick))))))


(defn- attach-peer!
  "Spawn B, wait for its bootstrap, attach, and wait for the opened event."
  [peers descriptor]
  (let [peer (start-peer! descriptor)]
    (swap! peers conj peer)
    (-> (take-reply! peer :ready)
        (.then (fn [ready]
                 (is (= descriptor (:descriptor ready))
                     "the descriptor B decoded from text differs from the one A encoded")
                 (ask! peer {:cmd :attach} :attach)))
        (.then (fn [attach]
                 (is (= :dao.stream/ok (:outcome attach)))
                 (.then (await-event peer #(= :ws/opened (:ws/event %))
                                     "the attachment never opened")
                        (fn [_] (assoc peer :attachment (:attachment attach)))))))))


(defn- server-attachment
  "Correlate B to the server-side session it joined by the token it appended.
   The two sides mint their own attachment identities, so nothing but a
   payload can connect them — which is itself worth asserting."
  [endpoint token]
  (poll-until
    (fn []
      (first (first (filter (fn [[_ medium]]
                              (some #(= token (:ws/value %)) (drain medium)))
                            @(:media endpoint)))))
    event-ms
    "A never received B's outbound payload"))


(defn- run-slice
  "Start process A, run one fact, then tear everything down exactly once."
  [done body]
  (let [fixture (server-fixture)
        peers (atom [])]
    (serving/start! (:composition (:primary fixture)))
    (serving/start! (:composition (:secondary fixture)))
    (let [ticker (js/setInterval
                   (fn []
                     (let [now (js/Date.now)]
                       (serving/step! (:composition (:primary fixture)) now)
                       (serving/step! (:composition (:secondary fixture)) now)))
                   5)
          teardown (fn []
                     (js/clearInterval ticker)
                     (doseq [peer @peers] (stop-peer! peer))
                     (serving/stop! (:composition (:primary fixture)))
                     (serving/stop! (:composition (:secondary fixture))))]
      (-> (poll-until #(and (node/listener-port @(:listener (:primary fixture)))
                            (node/listener-port @(:listener (:secondary fixture))))
                      bind-ms
                      "the listeners never reported bound ports")
          (.then (fn [_] (body fixture peers)))
          (.then (fn [_] nil)
                 (fn [error] (is false (str "slice failure: " (.-message ^js error)))))
          (.then (fn [_] (teardown) (done)))))))


;; =============================================================================
;; The five facts
;; =============================================================================

(deftest detachment-is-observable-on-both-sides
  ;; Fact 1.
  (async done
         (run-slice
           done
           (fn [{:keys [source primary]} peers]
             (let [me (atom nil)
                   token (atom nil)
                   attachment (atom nil)]
               (-> (attach-peer! peers (reachable primary))
                   (.then (fn [peer]
                            (reset! me peer)
                            (reset! token [:slice/fact-1 (:attachment peer)])
                            (ask! peer {:cmd :append :value :slice/established} :append)))
                   (.then (fn [reply]
                            (is (= :dao.stream/ok (:outcome reply))
                                "an established attachment must accept an outbound append")
                            (ask! @me {:cmd :append :value @token} :append)))
                   (.then (fn [reply]
                            (is (= :dao.stream/ok (:outcome reply)))
                            (server-attachment primary @token)))
                   (.then (fn [server-side]
                            (reset! attachment server-side)
                            ;; Kill the connection from B's side.
                            (ask! @me {:cmd :close} :close)))
                   (.then (fn [reply]
                            (is (= :dao.stream/ok (:outcome reply)))
                            (ask! @me {:cmd :append :value :after-close} :append)))
                   (.then (fn [reply]
                            (testing "B's handle answers closed"
                              (is (= :dao.stream/closed (:outcome reply))))
                            (await-event @me #(= :ws/closed (:ws/event %))
                                         "B's own medium never received the terminal event")))
                   (.then (fn [departure]
                            (is (= (:attachment @me) (:ws/attachment departure))
                                "B's terminal event was not attributed to its attachment")
                            (await-terminal primary @attachment)))
                   (.then (fn [events]
                            (testing "A's boundary deposits the departure"
                              (is (= :ws/closed (:ws/event (last events)))
                                  "A's per-attachment medium never deposited the departure")
                              (is (= @attachment (:ws/attachment (terminal-event events)))
                                  "A's terminal event was not attributed to its session")
                              (is (not (contains? (:sessions (serving/state (:composition primary)))
                                                  @attachment))
                                  "the driver kept a session for a departed attachment"))
                            (testing "the served stream is untouched by a connection's death"
                              (is (= :dao.stream/ok (:dao.stream/outcome
                                                      (stream/append! source :slice/still-alive)))))))))))))


(deftest the-same-descriptor-rejoins-the-same-logical-stream
  ;; Fact 2.
  (async done
         (run-slice
           done
           (fn [{:keys [source primary]} peers]
             (let [me (atom nil)
                   first-attachment (atom nil)
                   again (atom nil)
                   token (atom nil)]
               (-> (attach-peer! peers (reachable primary))
                   (.then (fn [peer]
                            (reset! me peer)
                            (reset! first-attachment (:attachment peer))
                            (ask! peer {:cmd :close} :close)))
                   (.then (fn [reply]
                            (is (= :dao.stream/ok (:outcome reply)))
                            (await-event @me #(= :ws/closed (:ws/event %))
                                         "the attachment never closed")))
                   ;; The identical bootstrapped descriptor value, reused verbatim.
                   (.then (fn [_] (ask! @me {:cmd :attach} :attach)))
                   (.then (fn [reply]
                            (reset! again (:attachment reply))
                            (is (= :dao.stream/ok (:outcome reply))
                                "reattachment with the same descriptor was refused")
                            (is (not= @first-attachment @again)
                                "a reattachment is a new attachment, not a resurrected one")
                            (await-event @me
                                         (fn [event]
                                           (and (= :ws/opened (:ws/event event))
                                                (= @again (:ws/attachment event))))
                                         "the reattachment never opened")))
                   (.then (fn [_]
                            (reset! token [:slice/fact-2 @again])
                            ;; A appends to the surviving logical stream; the
                            ;; rejoined attachment receives it, which is what "same
                            ;; logical stream" means operationally.
                            (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source @token))))
                            (await-event @me #(= @token (:ws/value %))
                                         "the rejoined attachment received nothing from the served stream")))
                   (.then (fn [hit] (is (some? hit))))))))))


(deftest simultaneous-attachments-carry-distinct-attachment-identity
  ;; Fact 3.
  (async done
         (run-slice
           done
           (fn [{:keys [source primary]} peers]
             (let [descriptor (reachable primary)
                   one (atom nil)
                   two (atom nil)
                   server-sessions (atom nil)]
               (-> (attach-peer! peers descriptor)
                   (.then (fn [peer] (reset! one peer) (attach-peer! peers descriptor)))
                   (.then (fn [peer]
                            (reset! two peer)
                            (is (string? (:attachment @one)))
                            (is (string? (:attachment @two)))
                            (is (not= (:attachment @one) (:attachment @two))
                                "two simultaneous attachments shared one attachment identity")
                            (ask! @one {:cmd :append :value [:slice/fact-3 (:attachment @one)]}
                                  :append)))
                   (.then (fn [_]
                            (ask! @two {:cmd :append :value [:slice/fact-3 (:attachment @two)]}
                                  :append)))
                   (.then (fn [_]
                            (is (= :dao.stream/ok (:dao.stream/outcome
                                                    (stream/append! source :slice/fanout))))
                            (await-event @one #(= :slice/fanout (:ws/value %))
                                         "the first attachment received nothing")))
                   (.then (fn [_]
                            (await-event @two #(= :slice/fanout (:ws/value %))
                                         "the second attachment received nothing")))
                   (.then (fn [_]
                            (js/Promise.all
                              #js [(server-attachment primary [:slice/fact-3 (:attachment @one)])
                                   (server-attachment primary [:slice/fact-3 (:attachment @two)])])))
                   (.then (fn [sessions]
                            (reset! server-sessions (vec sessions))
                            (is (not= (first @server-sessions) (second @server-sessions))
                                "two connections were folded into one server-side attachment")
                            ;; Close both before asserting attribution.  Correlation
                            ;; checked while the attachments are live sees only
                            ;; resolution and payload events; the terminal ones would
                            ;; arrive later, during teardown, unobserved.  An
                            ;; implementation that emitted a wrong or missing
                            ;; `:ws/attachment` exactly on `:ws/closed` would pass
                            ;; such a check.
                            (js/Promise.all
                              #js [(ask! @one {:cmd :close} :close)
                                   (ask! @two {:cmd :close} :close)])))
                   (.then (fn [closes]
                            (doseq [reply (vec closes)]
                              (is (= :dao.stream/ok (:outcome reply))))
                            (js/Promise.all
                              #js [(await-event @one #(= :ws/closed (:ws/event %))
                                                "the first client medium never received its terminal event")
                                   (await-event @two #(= :ws/closed (:ws/event %))
                                                "the second client medium never received its terminal event")])))
                   (.then (fn [_]
                            (testing "every event on a client medium carries that client's identity"
                              (doseq [peer [@one @two]]
                                (let [events @(:seen peer)
                                      terminal (terminal-event events)]
                                  (is (seq events))
                                  ;; Resolution, payload, and lifecycle alike.
                                  (is (every? #(= (:attachment peer) (:ws/attachment %)) events)
                                      "an event on B's medium was attributed to another attachment")
                                  (is (some? terminal))
                                  (is (= (:attachment peer) (:ws/attachment terminal))
                                      "B's terminal event was not attributed to its attachment"))))
                            (js/Promise.all
                              #js [(await-terminal primary (first @server-sessions))
                                   (await-terminal primary (second @server-sessions))])))
                   (.then (fn [histories]
                            (testing "every event on a server medium carries that session's identity"
                              (doseq [[attachment events] (map vector @server-sessions (vec histories))]
                                (let [terminal (terminal-event events)]
                                  (is (seq events))
                                  (is (some? terminal)
                                      "a server medium never deposited its terminal event")
                                  ;; Payload and lifecycle alike.
                                  (is (every? #(= attachment (:ws/attachment %)) events))
                                  (is (= attachment (:ws/attachment terminal))
                                      "A's terminal event was not attributed to its session"))))))))))))


(deftest the-deposit-medium-outlives-the-socket
  ;; Fact 4.  The cursor is B's, on B's medium; it is never handed to
  ;; `attach!` and never used through the WebSocket handle.
  (async done
         (run-slice
           done
           (fn [{:keys [source primary]} peers]
             (let [me (atom nil)
                   token (atom nil)]
               (-> (attach-peer! peers (reachable primary))
                   (.then (fn [peer]
                            (reset! me peer)
                            (reset! token [:slice/fact-4 (:attachment peer)])
                            (ask! peer {:cmd :surfaces} :surfaces)))
                   (.then (fn [reply]
                            (is (= #{:writer :closable} (:value reply))
                                "the ws handle must have no reader surface for this fact to mean anything")
                            (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source @token))))
                            (await-event @me #(= @token (:ws/value %))
                                         "the payload never arrived")))
                   ;; The socket dies.  B's medium and B's position on it do not.
                   (.then (fn [_] (ask! @me {:cmd :close} :close)))
                   (.then (fn [reply]
                            (is (= :dao.stream/ok (:outcome reply)))
                            (await-event @me #(= :ws/closed (:ws/event %))
                                         "the kept cursor did not resume across the socket's death")))
                   (.then (fn [_]
                            (testing "the cursor resumed rather than restarted"
                              ;; Every event B has reported came from one
                              ;; uninterrupted walk of one cursor: an opened, a
                              ;; payload, and exactly one terminal event, in order
                              ;; and without repetition.
                              (let [events @(:seen @me)]
                                (is (= [:ws/opened :ws/payload :ws/closed] (mapv :ws/event events)))
                                (is (apply distinct? events))))))))))))


(deftest two-endpoints-differ-in-reachability-and-agree-on-identity
  ;; Fact 5.
  (async done
         (run-slice
           done
           (fn [{:keys [source primary secondary]} peers]
             (let [projection (stream/descriptor source)
                   identity (:dao.stream/identity projection)
                   d-1 (reachable primary)
                   d-2 (reachable secondary)
                   one (atom nil)
                   two (atom nil)]
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
               (-> (attach-peer! peers d-1)
                   (.then (fn [peer] (reset! one peer) (attach-peer! peers d-2)))
                   (.then (fn [peer]
                            (reset! two peer)
                            (ask! @one {:cmd :handle-descriptor} :handle-descriptor)))
                   (.then (fn [h-1]
                            (.then (ask! @two {:cmd :handle-descriptor} :handle-descriptor)
                                   (fn [h-2]
                                     (testing "each handle projects its own reachability and the shared identity"
                                       (is (not= (:descriptor h-1) (:descriptor h-2)))
                                       (is (= identity (:identity h-1)))
                                       (is (= identity (:identity h-2))
                                           "an attached handle projected an identity the served stream never minted"))))))
                   (.then (fn [_]
                            (testing "cursors compare identity, never a descriptor"
                              ;; One cursor on the served stream is valid for the
                              ;; logical stream both endpoints serve; neither
                              ;; descriptor participates.
                              ;; The cursor carries the same identity the descriptors
                              ;; do, which is what makes that comparison possible
                              ;; without either descriptor.
                              (let [minted (stream/cursor source stream/anchor-newest)]
                                (is (= :dao.stream/ok (:dao.stream/outcome minted)))
                                (is (= identity (:dao.stream.ringbuffer/identity
                                                  (:dao.stream/cursor minted)))
                                    "the cursor's identity disagrees with the served descriptors'")
                                (is (= :dao.stream/ok (:dao.stream/outcome
                                                        (stream/append! source :slice/both))))
                                (is (= :slice/both (:dao.stream/value
                                                     (stream/next source (:dao.stream/cursor minted)))))))
                            (await-event @one #(= :slice/both (:ws/value %))
                                         "the first endpoint's attachment received nothing")))
                   (.then (fn [_]
                            (await-event @two #(= :slice/both (:ws/value %))
                                         "the second endpoint's attachment received nothing")))
                   (.then (fn [hit] (is (some? hit))))))))))

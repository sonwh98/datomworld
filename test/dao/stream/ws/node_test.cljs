(ns dao.stream.ws.node-test
  "Node host-adapter tests for the DaoStream v2 WebSocket boundary.

   Two layers, deliberately separate:

   * deterministic tests drive a fake host socket through the real adapter
     wiring, so event translation, framing, and teardown are proven with
     injected socket primitives and no network; and
   * end-to-end tests bind a real `ws` listener on an ephemeral loopback port
     and run the full client and serving compositions across real sockets.

   The `ws` npm package (package.json) is the prerequisite for the second
   layer.  Every driver below is an explicit test-owned ticker; the adapter
   itself owns no cadence, cursor, medium, or registry."
  (:require ["ws" :as ws-package]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.node :as node]
            [dao.stream.ws-project :as project]))


;; =============================================================================
;; Explicit test composition data
;; =============================================================================


(def admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn- buffer
  ([] (buffer 64))
  ([capacity]
   (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type
                                      ring/capacity-key capacity}))))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn- drain
  "Collect every retained value from the oldest position."
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))
         seen []]
    (let [result (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome result))
        (recur (:dao.stream/cursor result) (conj seen (:dao.stream/value result)))
        seen))))


(defn- event-kinds
  [handle]
  (mapv (juxt :ws/event :ws/reason) (drain handle)))


(defn- payload-values
  "The :ws/value of every :ws/payload envelope, in deposit order."
  [events]
  (mapv :ws/value (filter #(= :ws/payload (:ws/event %)) events)))


(defn- descriptor
  ([] (descriptor "/yin/repl"))
  ([path]
   {:dao.stream/type :dao.stream/ws
    :dao.stream/identity "node-adapter-service"
    :ws/host "127.0.0.1"
    :ws/port 9182
    :ws/path path}))


;; =============================================================================
;; A deterministic fake host socket (injected socket primitives)
;; =============================================================================


(defn- fake-socket
  "A recording stand-in for one `ws` socket: additive `.on` registration plus
   the `send`/`close` surface `node/raw-socket` wraps.  `fire` replays the
   host events the real library would emit."
  []
  (let [handlers (atom {})
        sent (atom [])
        closes (atom [])
        socket (js-obj "on" (fn [event f] (swap! handlers assoc event f))
                       ;; The host library's send returns nil; the fake must
                       ;; answer with the same shape the transport classifies.
                       "send" (fn [data] (swap! sent conj data) nil)
                       "close" (fn [code reason] (swap! closes conj [code reason])))]
    {:socket socket
     :handlers handlers
     :sent sent
     :closes closes
     :fire (fn [event & args]
             (let [f (get @handlers event)]
               (assert (some? f) (str "no listener for host event " event))
               (apply f args)))}))


(defn- fake-connect!
  "A `:connect!` seam whose host socket is the fake: the deterministic
   loopback composition used where a real socket adds nothing."
  [fake]
  (fn [_descriptor adapter]
    (node/wire! (:socket fake) adapter)
    (node/raw-socket (:socket fake))))


(defn- attach-with-fake
  [traffic fake]
  ((ws/make-attacher {:traffic {:dao.stream/handle traffic
                                :dao.stream/surface #{:writer}}
                      :admission admission
                      :connect! (fake-connect! fake)})
   (descriptor)))


(defn- fire-text!
  [fake value]
  ((:fire fake) "message" (.from js/Buffer (transit/encode value)) false))


;; =============================================================================
;; Pure host-edge data
;; =============================================================================


(deftest canonical-path-follows-the-wire-spec
  (is (= "/yin/repl" (node/canonical-path "/yin/repl")))
  ;; Query and fragment are ignored for lookup and never reach the table.
  (is (= "/yin/repl" (node/canonical-path "/yin/repl?after=1")))
  (is (= "/yin/repl" (node/canonical-path "/yin/repl#frag")))
  (is (= "/yin/repl" (node/canonical-path "/yin/repl?a=b#frag")))
  ;; An empty path is the root; relative paths and dot segments normalize.
  (is (= "/" (node/canonical-path "")))
  (is (= "/a/b" (node/canonical-path "/a/./b")))
  (is (= "/b" (node/canonical-path "/a/../b")))
  (is (= "/x" (node/canonical-path "/../../x")))
  ;; Repeated and trailing slashes are significant; encoded slashes are not.
  (is (= "/a//b" (node/canonical-path "/a//b")))
  (is (= "/a/" (node/canonical-path "/a/")))
  ;; RFC 3986 keeps the trailing slash a final dot segment leaves behind, and
  ;; the client canonicalizer (`yin.repl.connect`) agrees.
  (is (= "/a/" (node/canonical-path "/a/b/..")))
  (is (= "/repl/" (node/canonical-path "/repl/.")))
  (is (= "/" (node/canonical-path "/..")))
  (is (= "/a%2Fb" (node/canonical-path "/a%2fb")))
  ;; Unreserved octets decode; remaining escapes upper-case.
  (is (= "/a~b" (node/canonical-path "/a%7Eb")))
  (is (= "/A" (node/canonical-path "/%41")))
  (is (= "/repl/%C3%A4" (node/canonical-path "/repl/%c3%a4")))
  (is (= "/x" (node/canonical-path "/%2e%2e/x")))
  ;; Anything that cannot reach the canonical form is refused, not repaired.
  (is (nil? (node/canonical-path "yin/repl")))
  (is (nil? (node/canonical-path "%zz")))
  (is (nil? (node/canonical-path "/repl/%E")))
  (is (nil? (node/canonical-path "/repl/%")))
  (is (nil? (node/canonical-path 42))))


(deftest socket-url-uses-descriptor-reachability-only
  (is (= "ws://127.0.0.1:9182/yin/repl"
         (node/socket-url (descriptor))))
  (is (= "ws://example.org:9090/"
         (node/socket-url (assoc (descriptor "/") :ws/host "example.org" :ws/port 9090)))))


;; =============================================================================
;; Deterministic wiring through the fake socket
;; =============================================================================


(deftest wiring-translates-host-events-and-keeps-framing-honest
  (let [traffic (buffer)
        fake (fake-socket)
        result (attach-with-fake traffic fake)
        handle (:dao.stream/handle result)
        me (:dao.stream/attachment result)]
    (is (= :dao.stream/ok (:dao.stream/outcome result)))
    (is (string? me))
    ;; The four host events the boundary translates are subscribed; the
    ;; adapter is the socket's sole subscriber.
    (is (= #{"open" "message" "close" "error"} (set (keys @(:handlers fake)))))
    ;; While establishing, append! answers full and nothing is sent.
    (is (= :dao.stream/full (:dao.stream/outcome (stream/append! handle :early))))
    (is (empty? @(:sent fake)))
    ;; The host's own open resolves the attachment; there is no admission
    ;; wire frame any more.
    ((:fire fake) "open")
    (is (= [[:ws/opened nil]] (event-kinds traffic)))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! handle {:a 1}))))
    (is (= [{:ws/frame :ws/value :ws/value {:a 1}}]
           (mapv transit/decode @(:sent fake))))
    ;; Inbound values are enveloped and attributed, never judged.
    (fire-text! fake {:ws/frame :ws/value :ws/value [:yin :hi]})
    (is (= [[:ws/opened nil] [:ws/payload nil]] (event-kinds traffic)))
    (is (= [:yin :hi] (:ws/value (second (drain traffic)))))
    (is (every? #(= me (:ws/attachment %)) (drain traffic)))
    ;; A binary frame is undecodable by the text codec: the transport performs
    ;; its own decode-failure teardown (deposit plus close 4002).
    ((:fire fake) "message" (.from js/Buffer #js [0 1 2]) true)
    (is (= [[:ws/opened nil] [:ws/payload nil] [:ws/error :ws/decode-failure]]
           (event-kinds traffic)))
    (is (= [ws/protocol-close-code "dao.stream/protocol-error"] (last @(:closes fake))))
    ;; Host close completion deposits exactly one terminal event.
    ((:fire fake) "close" ws/protocol-close-code "dao.stream/protocol-error")
    (is (= [[:ws/opened nil] [:ws/payload nil] [:ws/error :ws/decode-failure]
            [:ws/closed nil]]
           (event-kinds traffic)))
    (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! handle :late))))
    ;; close! on a live attachment reaches the host socket as a plain
    ;; detachment with no close-failure diagnostic.
    (let [fresh (fake-socket)
          traffic-2 (buffer)
          handle-2 (:dao.stream/handle (attach-with-fake traffic-2 fresh))]
      ((:fire fresh) "open")
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! handle-2))))
      (is (= [1000 "dao.stream/detached"] (last @(:closes fresh)))))))


;; A dao.stream.remote reflection over the wired boundary is proven
;; portably (no host, no socket) in dao.stream.ws-project-test's own
;; attachment-filtering and dial/reflection tests; nothing Node-specific
;; remains to prove here once the wire is a channel rather than an RPC
;; envelope.


;; =============================================================================
;; Async harness for the real-socket layer
;; =============================================================================


(defn- wait-for
  "Poll `pred` every 10ms until it returns truthy or `deadline-ms` passes.
   The callback receives the value or nil, exactly once."
  [pred deadline-ms cb]
  (let [deadline (+ (.now js/Date) deadline-ms)]
    (letfn [(poll
              []
              (let [v (try (pred) (catch :default _ nil))]
                (cond
                  v (cb v)
                  (< deadline (.now js/Date)) (cb nil)
                  :else (js/setTimeout poll 10))))]
      (poll))))


(defn- finish-once
  [done]
  (let [armed (atom true)]
    (fn []
      (when @armed
        (reset! armed false)
        (done)))))


(defn- teardown!
  [fixture clients & tickers]
  (doseq [t tickers] (js/clearInterval t))
  (doseq [c clients] (stream/close! (:handle c)))
  (node/stop-listening! @(:listener fixture)))


(defn- run-step
  "Invoke (step value) guarded so an unexpected throw still completes the
   async test instead of hanging its ticker."
  [finish step value]
  (try
    (step value)
    (catch :default e
      (is false (str "unexpected error: " e))
      (finish))))


(defn- after-bound
  "Invoke (step port) once the server reports its bound port -- `server-
   fixture` already started listening.  A bind that never completes
   fails, tears down, finishes."
  [fixture ticker finish step]
  (wait-for #(node/listener-port @(:listener fixture)) 2000
            (fn [port]
              (if (number? port)
                (run-step finish step port)
                (do (is false "listener never reported a bound port")
                    (teardown! fixture [] ticker)
                    (finish))))))


(defn- after
  "Poll pred every 10ms until truthy or deadline, then invoke (step value).
   A timeout fails with message, tears down, and finishes."
  [deadline-ms pred message fixture ticker clients finish step]
  (wait-for pred deadline-ms
            (fn [value]
              (if value
                (run-step finish step value)
                (do (is false message)
                    (teardown! fixture clients ticker)
                    (finish))))))


;; =============================================================================
;; The serving composition around a real Node listener
;; =============================================================================


(defn- server-fixture
  "Compose the full serving stack around a real Node listener.

   Everything here is explicit test-owned state: media, cursors minted before
   listener bind, a per-attachment traffic registry the medium factory fills,
   a per-attachment RPC service registry the inbound interpreter fills, and
   the listener the start policy retains.  The adapter owns none of it.

   `:codecs` (an optional key of the map arity) selects the endpoint's codec
   table and therefore the subprotocols its listener negotiates; the default
   is the Transit-only table, exactly as before the dual-profile work.

   The served descriptor carries a placeholder port because the bound port is
   only known after listening; each test repairs reachability for its client
   from the bound address, exactly as the R4 bind/advertised split prescribes."
  ([] (server-fixture {}))
  ([{:keys [codecs slot-count] :or {slot-count 1} :as _options}]
   (let [echo (buffer)
         control (buffer)
         handoffs (mapv (fn [_] {:offer (buffer 1) :ack (buffer 1)}) (range slot-count))
         table-descriptor (assoc (descriptor) :ws/port 1)
         endpoint (ws/make-endpoint
                    (cond-> {:descriptor table-descriptor
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
                                          handoffs)
                             :expiry-ms nil}
                      codecs (assoc :codecs codecs)))
         traffic-media (atom {})
         listener-errors (atom [])
         acceptor (project/make-acceptor
                    {:endpoint endpoint
                     :slots (mapv (fn [{:keys [offer ack]}]
                                    {:offer-reader offer
                                     :offer-cursor (newest offer)
                                     :ack-writer {:dao.stream/handle ack
                                                  :dao.stream/surface #{:writer}}})
                                  handoffs)
                     :table {"echo" {:handle echo :surface #{:reader :writer}}}
                     :make-media (fn [offer-event]
                                   (let [traffic (buffer)]
                                     (swap! traffic-media
                                            assoc (:ws/attachment offer-event) traffic)
                                     {:traffic {:dao.stream/handle traffic
                                                :dao.stream/surface #{:writer}}
                                      :admission admission
                                      :reader traffic
                                      :cursor (newest traffic)
                                      :ring (buffer)}))})
         listener (atom (node/listen! endpoint
                                      {:host "127.0.0.1" :port 0
                                       :on-error #(swap! listener-errors conj :listener-error)
                                       ;; The listener negotiates exactly the
                                       ;; endpoint's own codec table.
                                       :codecs codecs}))]
     {:echo echo
      :control control
      :offer (:offer (first handoffs))
      :listener listener
      :listener-errors listener-errors
      :traffic-media traffic-media
      :acceptor acceptor
      :descriptor table-descriptor})))


(defn- server-tick
  "One explicit driver tick: transport, offer and mirror, over the
   `\"echo\"` identity every accepted connection's reflection reads and
   writes directly -- there is no handler to step, since `append!` and
   `next` are the mirror's own primitives."
  [fixture now]
  (project/accept-step! (:acceptor fixture) now))


(defn- make-client
  "Dial the endpoint and attach one reflection of `\"echo\"`, over a fresh
   traffic medium and channel ring, before any attach! -- the boundary
   composition discipline of dao.stream.ws.md.  The optional map arity
   selects the wire codec profile (default Transit)."
  ([client-descriptor] (make-client client-descriptor {}))
  ([client-descriptor {:keys [codec] :as _options}]
   (let [traffic (buffer)
         cursor (newest traffic)
         ring (buffer)
         attach! (ws/make-attacher
                   (cond-> {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                            :admission admission
                            :connect! node/connect!}
                     codec (assoc :codec codec)))
         dial (project/dial {:attach! attach!
                             :traffic {:dao.stream/handle traffic}
                             :cursor cursor
                             :ring ring
                             :table {}})
         attach (project/dial-attach!
                  dial {:dao.stream/type :dao.stream/remote
                        :dao.stream/identity "echo"
                        :dao.stream/channel client-descriptor})]
     {:traffic traffic
      :cursor cursor
      :dial dial
      :attach attach
      :handle (:dao.stream/handle attach)
      :completed (atom [])})))


(defn- client-tick!
  [client]
  (project/dial-step! (:dial client)))


(defn- client-ticker
  [client]
  (js/setInterval (fn [] (client-tick! client)) 10))


(defn- channel-attachment
  "The channel's ws attachment identity -- the id every envelope the wire
   deposits for this connection carries, as distinct from the remote
   attacher's own attachment id on the reflection dial-attach! answered."
  [client]
  (:attachment (project/channel (:dial client))))


(defn- channel-handle
  "The channel's ws handle -- the socket writer a detachment closes -- as
   distinct from the reflection handle dial-attach! answered."
  [client]
  (:handle (project/channel (:dial client))))


;; =============================================================================
;; End to end over real loopback sockets
;; =============================================================================


(defn- read-oldest
  "Mint the oldest cursor (retrying while the reflection answers a
   retryable mint) then read one value past it.  Returns the read
   result, or nil while either step is still pending."
  [handle]
  (let [c (stream/cursor handle stream/anchor-oldest)]
    (when (= :dao.stream/ok (:dao.stream/outcome c))
      (let [r (stream/next handle (:dao.stream/cursor c))]
        (when (= :dao.stream/ok (:dao.stream/outcome r)) r)))))


(defn- read-next-ok
  "One next read past `cursor`, answered only on `:dao.stream/ok` --
   while the reflection's own ask is in flight it answers blocked, so a
   caller polling this until truthy is the retry that op never gets for
   itself.  Returns the read result, or nil while still pending."
  [handle cursor]
  (let [r (stream/next handle cursor)]
    (when (= :dao.stream/ok (:dao.stream/outcome r)) r)))


(defn- append-ok
  "One append attempt, answered only on `:dao.stream/ok` -- a reflection's
   append! answers `:dao.stream/full` (never retried on its own) while the
   underlying socket is still `:connecting`, exactly as the descriptor
   probe is; a caller polling this until truthy is the retry that op
   never gets for itself.  Returns the outcome map, or nil while still
   pending."
  [handle value]
  (let [r (stream/append! handle value)]
    (when (= :dao.stream/ok (:dao.stream/outcome r)) r)))


(defn- a-reflection-verified
  "The round trip's assertions: the reflected value, the real new-flow
   event sequence on the client medium, and the per-attachment
   correlation."
  [fixture server ticker client finish result]
  (is (= "hello" (:dao.stream/value result)))
  (let [events (drain (:traffic client))
        answers (payload-values events)
        me (channel-attachment client)]
    ;; The new flow's real sequence: the accept event, then one envelope
    ;; per mirror answer -- the descriptor probe's ok (the link's first
    ;; asker-minted id 0, carrying the served surface), the append!'s
    ;; source ok, the cursor mint, and the next with the echoed value.
    (is (= [[:ws/opened nil] [:ws/payload nil] [:ws/payload nil]
            [:ws/payload nil] [:ws/payload nil]]
           (mapv (juxt :ws/event :ws/reason) events)))
    (is (= 0 (:dao.stream.remote/id (first answers))))
    (is (= #{:writer :reader}
           (:dao.stream.remote/surface (first answers))))
    (is (some? (:dao.stream/cursor (nth answers 2))))
    (is (= "echo" (:dao.stream/identity (last answers))))
    (is (= "hello" (:dao.stream/value (last answers))))
    ;; Every deposited envelope on the client medium is attributed to
    ;; this attachment: demultiplexing survived the real wire.
    (is (every? #(= me (:ws/attachment %)) events)))
  (is (= 1 (count @(:traffic-media fixture))))
  (is (empty? (drain (:control fixture))))
  (teardown! fixture [client] server ticker)
  (finish))


(defn- a-reflection-read-back
  "One accepted append: read the echo back through the reflection."
  [fixture server ticker client finish appended]
  (is (= :dao.stream/ok (:dao.stream/outcome appended)))
  (after 4000 #(read-oldest (:handle client))
         "the echoed value never arrived" fixture server [client] finish
         (fn [result]
           (a-reflection-verified fixture server ticker client finish
                                  result))))


(deftest a-reflection-round-trips-append-and-next-through-a-real-node-listener
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval
                        (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [client (make-client
                                         (assoc (:descriptor fixture)
                                                :ws/port port))
                                ticker (client-ticker client)]
                            (after 4000 #(append-ok (:handle client) "hello")
                                   "append! never accepted" fixture server
                                   [client] finish
                                   (fn [appended]
                                     (a-reflection-read-back
                                       fixture server ticker client finish
                                       appended)))))))))


(deftest closing-the-served-streams-source-ends-reads-not-the-channel
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [client (make-client (assoc (:descriptor fixture) :ws/port port))
                                ticker (client-ticker client)]
                            (after 4000 (fn []
                                          (some #(= :ws/opened (:ws/event %))
                                                (drain (:traffic client))))
                                   "attachment never opened" fixture server [client] finish
                                   (fn [_]
                                     ;; The echo identity's own source ends; the mirror relays
                                     ;; the source's bare outcome, never a protocol error, and
                                     ;; the channel itself -- the socket -- stays open.
                                     (stream/close! (:echo fixture))
                                     (after 4000
                                            (fn []
                                              (let [c (stream/cursor (:handle client)
                                                                     stream/anchor-oldest)]
                                                (when (= :dao.stream/ok (:dao.stream/outcome c))
                                                  (let [r (stream/next (:handle client)
                                                                       (:dao.stream/cursor c))]
                                                    (when (= :dao.stream/end (:dao.stream/outcome r))
                                                      r)))))
                                            "the reflection never observed the source's end"
                                            fixture server [client] finish
                                            (fn [_]
                                              (is (not (some #(= :ws/closed (:ws/event %))
                                                             (drain (:traffic client))))
                                                  "the channel is untouched by its served
                                                   identity's own end")
                                              (teardown! fixture [client] server ticker)
                                              (finish)))))))))))


(defn- channel-gone-outcome
  "Poll `handle`'s `cursor` op until it answers the reflection's own
   channel-loss translation, or nil."
  [handle]
  (let [r (stream/cursor handle stream/anchor-oldest)]
    (when (and (= :dao.stream/transport-error (:dao.stream/outcome r))
               (= :dao.stream.remote/channel-gone (:dao.stream.remote/reason r)))
      r)))


(defn- lost-next-outcome
  "Ask one more `next` at `cursor` -- a position already read, so the ask
   is a fresh wire round trip while the channel lives -- until the
   reflection answers its own channel-loss translation (2.4), or nil.
   This is the observation the RPC client itself polls: next answering
   channel-gone is what translates to the reattachable /detached."
  [handle cursor]
  (let [r (stream/next handle cursor)]
    (when (and (= :dao.stream/transport-error (:dao.stream/outcome r))
               (= :dao.stream.remote/channel-gone
                  (:dao.stream.remote/reason r)))
      r)))


;; There is no more path table at the wire boundary: one endpoint owns
;; exactly one descriptor, and every accepted socket speaks it regardless
;; of the request target it upgraded from, so no path can be wire-level
;; disclaimed any more.  An unknown *identity* now answers not-found from
;; the mirror's own table instead, proven portably (no host, no socket)
;; in dao.stream.ws-project-test.


(deftest unreachable-endpoints-resolve-as-retryable-channel-loss
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               client (make-client (assoc (:descriptor fixture) :ws/port 1))
               ticker (client-ticker client)]
           (after 4000 #(channel-gone-outcome (:handle client))
                  "no channel-gone outcome" fixture ticker [client] finish
                  (fn [_]
                    (is (= [[:ws/error :ws/socket-error]
                            [:ws/transport-error nil]
                            [:ws/closed nil]]
                           (event-kinds (:traffic client))))
                    (teardown! fixture [client] ticker)
                    (finish))))))


(deftest binary-frames-are-protocol-failures-not-payloads
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [raw ^js (new (.-WebSocket ws-package)
                                             (str "ws://127.0.0.1:" port "/yin/repl")
                                             ws/subprotocol)
                                frames (atom [])
                                opened (atom false)]
                            (.on raw "open" (fn [] (reset! opened true)))
                            (.on raw "message"
                                 (fn [data is-binary]
                                   (swap! frames conj
                                          (if is-binary ::binary (.toString data "utf8")))))
                            (.on raw "close"
                                 (fn [code reason]
                                   (swap! frames conj
                                          [::close code (.toString reason "utf8")])))
                            (after 4000 (fn [] @opened)
                                   "raw client never opened" fixture server [] finish
                                   (fn [_]
                                     ;; A binary frame cannot be a payload: it is a protocol
                                     ;; failure torn down with close 4002.
                                     (.send raw (.from js/Buffer #js [0 1 2]))
                                     (after 4000 (fn [] (some #(= ::close (first %)) @frames))
                                            "no protocol teardown observed" fixture server [] finish
                                            (fn [_]
                                              (let [[_ code reason] (last @frames)
                                                    [attachment traffic] (first @(:traffic-media fixture))]
                                                (is (= ws/protocol-close-code code))
                                                (is (= "dao.stream/protocol-error" reason))
                                                (is (string? attachment))
                                                (is (= [[:ws/error :ws/decode-failure] [:ws/closed nil]]
                                                       (event-kinds traffic)))
                                                (teardown! fixture [] server)
                                                (finish))))))))))))


(deftest upgrades-without-the-v2-subprotocol-are-refused-before-the-upgrade
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [raw ^js (new (.-WebSocket ws-package)
                                             (str "ws://127.0.0.1:" port "/yin/repl"))
                                errors (atom [])
                                closes (atom [])]
                            (.on raw "error" (fn [e] (swap! errors conj (.-message ^js e))))
                            (.on raw "close" (fn [code _reason] (swap! closes conj code)))
                            (after 4000 (fn [] (seq @errors))
                                   "refused connection never failed" fixture server [] finish
                                   (fn [_]
                                     ;; The spec says the server refuses the upgrade: the handshake
                                     ;; fails with an HTTP status, rather than completing and then
                                     ;; closing an established socket.
                                     (is (some #(str/includes? % "400") @errors)
                                         (str "expected a refused handshake, got " (pr-str @errors)))
                                     (is (not (some #(= 1002 %) @closes))
                                         "no upgraded socket was closed after the fact")
                                     ;; The refusal precedes the endpoint: no handoff slot and no
                                     ;; traffic medium were consumed.
                                     (is (empty? (drain (:offer fixture))))
                                     (is (empty? @(:traffic-media fixture)))
                                     (teardown! fixture [] server)
                                     (finish)))))))))


(deftest a-pending-slot-expires-on-the-clock-the-listener-supplies
  (async done
         (let [finish (finish-once done)
               control (buffer)
               offer (buffer 1)
               ack (buffer 1)
               table-descriptor (assoc (descriptor) :ws/port 1)
               ;; Nothing acknowledges this endpoint's offers, so an accepted
               ;; connection stays pending until admission expiry releases it.
               endpoint (ws/make-endpoint
                          {:descriptor table-descriptor
                           :control {:dao.stream/handle control :dao.stream/surface #{:writer}}
                           :control-admission admission
                           :slots [{:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                                    :offer-admission handoff-admission
                                    :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                                    :ack-admission handoff-admission
                                    :ack-cursor (newest ack)}]
                           :expiry-ms 50})
               listener (node/listen! endpoint {:host "127.0.0.1" :port 0
                                                ;; An explicit host clock reading is
                                                ;; what makes expiry effective.
                                                :clock (constantly 1000)})
               pending-slot #(first (:slots (ws/endpoint-state endpoint)))]
           (wait-for #(node/listener-port listener) 2000
                     (fn [port]
                       (if-not (number? port)
                         (do (is false "listener never reported a bound port")
                             (node/stop-listening! listener)
                             (finish))
                         (let [raw ^js (new (.-WebSocket ws-package)
                                            (str "ws://127.0.0.1:" port "/yin/repl")
                                            ws/subprotocol)
                               closes (atom [])]
                           (.on raw "close" (fn [code reason]
                                              (swap! closes conj [code (.toString ^js reason "utf8")])))
                           (wait-for #(:opened-at (pending-slot)) 2000
                                     (fn [opened-at]
                                       (is (= 1000 opened-at)
                                           "listen! stamps the slot with the clock reading it was given")
                                       ;; One step at the expiry deadline, in the same clock domain.
                                       (ws/endpoint-step endpoint 1050)
                                       (wait-for #(seq @closes) 2000
                                                 (fn [_]
                                                   (is (= [1000 "dao.stream/acceptance-expired"] (last @closes)))
                                                   (is (= :free (:status (pending-slot)))
                                                       "the slot is returned to the bounded pool")
                                                   (node/stop-listening! listener)
                                                   (finish))))))))))))


(defn- first-traffic-medium
  "The first accepted attachment's own serving medium."
  [fixture]
  (first (vals @(:traffic-media fixture))))


(defn- detach-second-round-trip
  "The reattached session's reads: the fresh attachment first sees the
   stream's retained history, then its own append behind it."
  [fixture server finish ticker ticker-2 client-2 _appended]
  (after 4000 #(read-oldest (:handle client-2))
         "reattached round trip never completed" fixture server
         [client-2] finish
         (fn [result]
           (is (= "first" (:dao.stream/value result)))
           (after 4000 #(read-next-ok (:handle client-2)
                                      (:dao.stream/cursor result))
                  "the reattached append never arrived" fixture server
                  [client-2] finish
                  (fn [appended-result]
                    (is (= "again" (:dao.stream/value appended-result)))
                    (is (= 2 (count @(:traffic-media fixture)))
                        "the fresh dial is a second, distinct accepted
                         connection")
                    (teardown! fixture [client-2] server ticker ticker-2)
                    (finish))))))


(defn- detach-reattach
  "Reattach: `dial`'s own contract is a fresh dial with a fresh cursor,
   never a rebind of the old one -- its ring and traffic cursor already
   belong to the dead attachment."
  [fixture server finish port client ticker]
  (let [client-2 (make-client (assoc (:descriptor fixture) :ws/port port))
        ticker-2 (client-ticker client-2)
        first-me (channel-attachment client)
        second-me (channel-attachment client-2)]
    (is (string? second-me))
    (is (not= first-me second-me))
    (after 4000 #(append-ok (:handle client-2) "again")
           "append! never accepted" fixture server [client-2] finish
           (fn [_appended]
             (detach-second-round-trip fixture server finish ticker ticker-2
                                       client-2 _appended)))))


(defn- detach-verify-loss
  "After the channel-gone observation: the client saw its own close, and
   the server observed the departure on that attachment's own medium."
  [fixture server finish port client ticker]
  (is (= :ws/closed (:ws/event (last (drain (:traffic client))))))
  (is (= :ws/closed
         (:ws/event (last (drain (first-traffic-medium fixture)))))
      "the server observed the departure")
  (detach-reattach fixture server finish port client ticker))


(defn- detach-own-attachment
  "The client detaches its own attachment: the channel's ws handle
   closes, its close completion deposits :ws/closed, and the projection
   ends the ring -- the link's next read then observes the loss as
   channel-gone.  A consumed position is asked again, so the
   reflection's cursor op cannot observe the loss: its idempotent mint
   answer stays filed by design."
  [fixture server finish port client ticker first-result]
  (stream/close! (channel-handle client))
  (after 4000
         #(lost-next-outcome (:handle client)
                             (:dao.stream/cursor first-result))
         "no channel-gone outcome" fixture server [] finish
         (fn [_]
           (detach-verify-loss fixture server finish port client ticker))))


(deftest detachment-reattaches-with-a-fresh-dial-and-a-new-attachment
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval
                        (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [client (make-client
                                         (assoc (:descriptor fixture)
                                                :ws/port port))
                                ticker (client-ticker client)]
                            (after 4000 #(append-ok (:handle client) "first")
                                   "append! never accepted" fixture server
                                   [client] finish
                                   (fn [_appended]
                                     (after 4000 #(read-oldest (:handle client))
                                            "first round trip never
                                             completed"
                                            fixture server [client] finish
                                            (fn [first-result]
                                              (is (= "first"
                                                     (:dao.stream/value
                                                       first-result)))
                                              (detach-own-attachment
                                                fixture server finish port
                                                client ticker
                                                first-result)))))))))))


;; =============================================================================
;; The dual-subprotocol wire: one listener, two codec profiles
;; =============================================================================


(defn- dual-both-sent
  "Send one payload per client until each first succeeds, the Transit
   send strictly before the CBOR one is attempted, so the shared served
   stream's value order is deterministic.  Metadata rides the CBOR
   profile only: if the binary session were silently downgraded to the
   Transit text wire, the echoed reader position would come back
   stripped.  Each client's own send is attempted at most until it
   first succeeds -- retrying a client that already sent would append a
   duplicate."
  [transit-client cbor-client transit-sent cbor-sent]
  (when-not @transit-sent
    (when (append-ok (:handle transit-client) "text-payload")
      (reset! transit-sent true)))
  (when @transit-sent
    (when-not @cbor-sent
      (when (append-ok (:handle cbor-client)
                       (with-meta [:bin-payload] {:line 5}))
        (reset! cbor-sent true))))
  (and @transit-sent @cbor-sent))


(defn- dual-both-read
  "Capture the reads the assertions need, or nil while any is still
   pending.  Both sessions reflect the same served identity, so each
   client's oldest read answers with the shared stream's retained
   history (the Transit payload); the CBOR session's own append is read
   past it.  A reflection's next read files its answer once -- a
   consumed position asked again is a fresh wire round trip -- so each
   read is captured exactly once and asserted by `dual-verify`, never
   re-read."
  [transit-client cbor-client transit-read cbor-read cbor-read-2]
  (when-not @transit-read
    (when-some [r (read-oldest (:handle transit-client))]
      (reset! transit-read r)))
  (when-not @cbor-read
    (when-some [r (read-oldest (:handle cbor-client))]
      (reset! cbor-read r)))
  (when (and @cbor-read (not @cbor-read-2))
    (when-some [r (read-next-ok (:handle cbor-client)
                                (:dao.stream/cursor @cbor-read))]
      (reset! cbor-read-2 r)))
  (and @transit-read @cbor-read @cbor-read-2))


(defn- dual-verify
  "The dual round trips' assertions, then both channels' teardown."
  [fixture server finish transit-ticker cbor-ticker transit-client
   cbor-client transit-read cbor-read cbor-read-2]
  (is (= "text-payload" (:dao.stream/value @transit-read)))
  (is (= "text-payload" (:dao.stream/value @cbor-read)))
  (let [echoed (:dao.stream/value @cbor-read-2)]
    (is (= [:bin-payload] echoed)
        (str "cbor client saw "
             (pr-str (event-kinds (:traffic cbor-client)))))
    (is (= {:line 5} (meta echoed))
        "the CBOR session kept its metadata end to end; a downgrade to
         the text wire would strip it"))
  ;; The negotiated profile rides the channel's own ws handle: the
  ;; dual-profile listener wired the CBOR adapter for the binary session.
  (is (= cbor/profile
         (:ws/codec (ws/adapter (channel-handle cbor-client)))))
  (stream/close! (channel-handle transit-client))
  (stream/close! (channel-handle cbor-client))
  (teardown! fixture [] server transit-ticker cbor-ticker)
  (finish))


(deftest transit-and-cbor-clients-round-trip-through-one-listener
  ;; Dual-client compatibility: one Node listener negotiates both profiles,
  ;; a Transit client and a CBOR client attach concurrently to the same
  ;; served path, and each completes an RPC round trip over its own frame
  ;; kind -- text frames for one, binary frames for the other, no sniffing.
  (async done
         ;; Two handoff slots: both sessions hand off concurrently rather than
         ;; racing one slot's release.
         (let [fixture (server-fixture {:codecs [transit/profile cbor/profile]
                                        :slot-count 2})
               finish (finish-once done)
               server (js/setInterval
                        (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [d (assoc (:descriptor fixture) :ws/port port)
                                transit-client (make-client d)
                                cbor-client (make-client
                                              d {:codec cbor/profile})
                                transit-ticker (client-ticker transit-client)
                                cbor-ticker (client-ticker cbor-client)
                                transit-sent (atom false)
                                cbor-sent (atom false)
                                transit-read (atom nil)
                                cbor-read (atom nil)
                                cbor-read-2 (atom nil)]
                            (after 4000
                                   #(dual-both-sent transit-client cbor-client
                                                    transit-sent cbor-sent)
                                   "append! never accepted on both clients"
                                   fixture server
                                   [transit-client cbor-client] finish
                                   (fn [_appended]
                                     (after 4000
                                            #(dual-both-read transit-client
                                                             cbor-client
                                                             transit-read
                                                             cbor-read
                                                             cbor-read-2)
                                            "both round trips never completed"
                                            fixture server
                                            [transit-client cbor-client] finish
                                            (fn [_]
                                              (dual-verify
                                                fixture server finish
                                                transit-ticker cbor-ticker
                                                transit-client cbor-client
                                                transit-read cbor-read
                                                cbor-read-2)))))))))))


(deftest a-cbor-client-is-refused-by-a-transit-only-endpoint
  ;; Mixed-version handshake failure: a client offering only dao.stream.cbor
  ;; against an endpoint composed with Transit alone fails its handshake —
  ;; an HTTP refusal before the upgrade, never a silent downgrade to text.
  (async done
         (let [fixture (server-fixture) ; Transit-only table
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [raw ^js (new (.-WebSocket ws-package)
                                             (str "ws://127.0.0.1:" port "/yin/repl")
                                             "dao.stream.cbor")
                                errors (atom [])
                                closes (atom [])]
                            (.on raw "error" (fn [e] (swap! errors conj (.-message ^js e))))
                            (.on raw "close" (fn [code _reason] (swap! closes conj code)))
                            (after 4000 (fn [] (seq @errors))
                                   "refused connection never failed" fixture server [] finish
                                   (fn [_]
                                     (is (some #(str/includes? % "400") @errors)
                                         (str "expected a refused handshake, got "
                                              (pr-str @errors)))
                                     (is (not (some #(= 1002 %) @closes))
                                         "no upgraded socket was closed after the fact")
                                     ;; The refusal precedes the endpoint: no handoff slot
                                     ;; or traffic medium was consumed.
                                     (is (empty? (drain (:offer fixture))))
                                     (is (empty? @(:traffic-media fixture)))
                                     (teardown! fixture [] server)
                                     (finish)))))))))

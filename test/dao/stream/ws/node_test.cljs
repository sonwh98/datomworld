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
            [dao.stream.apply :as apply]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.stream.rpc.ws :as rpc-ws]
            [dao.stream.serving :as serving]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.node :as node]))


;; =============================================================================
;; Explicit test composition data
;; =============================================================================


(def admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def echo-handlers
  {:op/echo (fn [value] [:echo value])})


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
    ;; The HTTP upgrade is never a resolution signal: only the three host
    ;; events the boundary translates are subscribed, and 'open' is not among
    ;; them.  The adapter is the socket's sole subscriber.
    (is (= #{"message" "close" "error"} (set (keys @(:handlers fake)))))
    ;; While establishing, append! answers full and nothing is sent.
    (is (= :dao.stream/full (:dao.stream/outcome (stream/append! handle :early))))
    (is (empty? @(:sent fake)))
    ;; The first accept frame resolves the attachment; then append! frames.
    (fire-text! fake {:ws/frame :ws/accept})
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
      (fire-text! fresh {:ws/frame :ws/accept})
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! handle-2))))
      (is (= [1000 "dao.stream/detached"] (last @(:closes fresh)))))))


(deftest rpc-client-over-the-wired-boundary-distinguishes-attachments
  ;; The deposit cursor is minted before attach! so no event can outrun it.
  (let [traffic (buffer)
        cursor (newest traffic)
        fake (fake-socket)
        attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! (fake-connect! fake)})]
    (is (map? cursor))
    (let [attach (attacher (descriptor))
          client (rpc-ws/init-client attach traffic cursor)]
      (fire-text! fake {:ws/frame :ws/accept})
      ;; Another attachment's events on the shared medium are consumed by the
      ;; cursor and never mistaken for this client's responses.
      (stream/append! traffic
                      {:ws/attachment "someone-else" :ws/event :ws/payload
                       :ws/value (apply/success-response 0 :wrong)})
      (let [polled (rpc/poll! client 4)]
        (is (= :dao.stream.rpc/idle (:dao.stream.rpc/outcome polled)))
        (is (nil? (:terminal (:dao.stream.rpc/state polled))))
        (is (empty? (:completed (:dao.stream.rpc/state polled))))
        (let [requested (rpc/request! (:dao.stream.rpc/state polled)
                                      :op/echo ["hi"])]
          (is (= :dao.stream.rpc/requested (:dao.stream.rpc/outcome requested)))
          (is (= {:ws/frame :ws/value
                  :ws/value (apply/request 0 :op/echo ["hi"])}
                 (transit/decode (last @(:sent fake)))))
          (fire-text! fake {:ws/frame :ws/value
                            :ws/value (apply/success-response 0 [:echo "hi"])})
          (let [answered (rpc/poll! (:dao.stream.rpc/state requested) 4)
                [completions next-state] (rpc/take-completed
                                           (:dao.stream.rpc/state answered))]
            (is (= 1 (count completions)))
            (is (= 0 (:dao.stream.rpc/id (first completions))))
            (is (= [:echo "hi"]
                   (apply/response-ok (:dao.stream.rpc/response (first completions)))))
            (is (empty? (:completed next-state)))))))))


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
  (serving/stop! (:composition fixture)))


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
  "Start the fixture's listener and invoke (step port) once the server reports
   its bound port.  A bind that never completes fails, tears down, finishes."
  [fixture ticker finish step]
  (serving/start! (:composition fixture))
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
   (let [served (buffer)
         control (buffer)
         handoffs (mapv (fn [_] {:offer (buffer 1) :ack (buffer 1)}) (range slot-count))
         table-descriptor (assoc (descriptor) :ws/port 1)
         endpoint (ws/make-endpoint
                    (cond-> {:served {"/yin/repl" table-descriptor}
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
         services (atom {})
         listener-errors (atom [])
         listener (atom nil)
         composition (serving/make-serving
                       {:endpoint endpoint
                        :served {"/yin/repl" {:descriptor table-descriptor :stream served}}
                        :control-reader control
                        :control-cursor (newest control)
                        :slots (mapv (fn [{:keys [offer ack]}]
                                       {:offer-reader offer
                                        :offer-cursor (newest offer)
                                        :ack-writer {:dao.stream/handle ack
                                                     :dao.stream/surface #{:writer}}})
                                     handoffs)
                        :make-traffic (fn [offer-event]
                                        (let [traffic (buffer)]
                                          (swap! traffic-media
                                                 assoc (:ws/attachment offer-event) traffic)
                                          {:traffic {:dao.stream/handle traffic
                                                     :dao.stream/surface #{:writer}}
                                           :admission admission
                                           :reader traffic
                                           :cursor (newest traffic)}))
                        :inbound-step (fn [session event]
                                        (when (and (= :ws/payload (:ws/event event))
                                                   (apply/request? (:ws/value event)))
                                          (let [attachment (:ws/attachment event)]
                                            (when-not (get @services attachment)
                                              (let [requests (buffer)]
                                                (swap! services assoc attachment
                                                       {:requests requests
                                                        :service (apply/server-state
                                                                   (newest requests))
                                                        :socket (:socket-handle session)})))
                                            (stream/append!
                                              (:requests (get @services attachment))
                                              (:ws/value event)))))
                        :start-endpoint! (fn [ep]
                                           (let [l (node/listen! ep
                                                                 {:host "127.0.0.1" :port 0
                                                                  :on-error #(swap! listener-errors conj :listener-error)
                                                                  ;; The listener negotiates exactly the
                                                                  ;; endpoint's own codec table.
                                                                  :codecs (:codecs ep)})]
                                             (reset! listener l)
                                             {:dao.stream/outcome :dao.stream/ok}))
                        :stop-endpoint! (fn [_]
                                          (node/stop-listening! @listener)
                                          {:dao.stream/outcome :dao.stream/ok})})]
     {:served served
      :control control
      :offer (:offer (first handoffs))
      :listener listener
      :listener-errors listener-errors
      :traffic-media traffic-media
      :services services
      :composition composition
      :descriptor table-descriptor})))


(defn- server-tick
  "One explicit driver tick: advance the composition, then every registered
   RPC service by one request/response step.  `now` stays in the caller's
   clock domain; the adapter never supplies one."
  [fixture now]
  (serving/step! (:composition fixture) now)
  (doseq [[attachment {:keys [requests socket] :as entry}] @(:services fixture)]
    (let [result (apply/serve-once! echo-handlers requests socket (:service entry))]
      (swap! (:services fixture) assoc-in [attachment :service]
             (:dao.stream.apply/state result)))))


(defn- make-client
  "The R3 client boundary: create the deposit medium, mint its newest cursor,
   compose the boundary, and only then attach.  RPC client state is built from
   the whole attach result, so `:me` is the transport-minted attachment id.
   The optional map arity selects the wire codec profile (default Transit)."
  ([client-descriptor] (make-client client-descriptor {}))
  ([client-descriptor {:keys [codec] :as _options}]
   (let [traffic (buffer)
         cursor (newest traffic)
         attach ((ws/make-attacher
                   (cond-> {:traffic {:dao.stream/handle traffic :dao.stream/surface #{:writer}}
                            :admission admission
                            :connect! node/connect!}
                     codec (assoc :codec codec)))
                 client-descriptor)]
     {:traffic traffic
      :cursor cursor
      :attach attach
      :handle (:dao.stream/handle attach)
      :rpc-atom (atom (rpc-ws/init-client attach traffic cursor))
      :completed (atom [])})))


(defn- client-tick!
  [client]
  (let [st @(:rpc-atom client)]
    (when (:unsent st)
      ;; A pending unsent request retries the exact allocated envelope; the
      ;; op/args arguments are ignored by rpc/request! on this path.
      (reset! (:rpc-atom client)
              (:dao.stream.rpc/state (rpc/request! st ::retry nil))))
    (let [polled (rpc/poll! @(:rpc-atom client) 16)]
      (reset! (:rpc-atom client) (:dao.stream.rpc/state polled))
      (let [[completions next-state] (rpc/take-completed @(:rpc-atom client))]
        (reset! (:rpc-atom client) next-state)
        (swap! (:completed client) into completions)))))


(defn- client-ticker
  [client]
  (js/setInterval (fn [] (client-tick! client)) 10))


;; =============================================================================
;; End to end over real loopback sockets
;; =============================================================================


(deftest rpc-round-trips-through-a-real-node-listener
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [client (make-client (assoc (:descriptor fixture) :ws/port port))
                                ticker (client-ticker client)]
                            ;; The request is made before resolution and stays unsent until
                            ;; the wire accept arrives; the ticker retries it.
                            (let [initial (rpc/request! @(:rpc-atom client) :op/echo ["hello"])]
                              (is (= :dao.stream.rpc/pending-request
                                     (:dao.stream.rpc/outcome initial)))
                              (reset! (:rpc-atom client) (:dao.stream.rpc/state initial)))
                            (after 4000 (fn [] (seq @(:completed client)))
                                   "no completion arrived" fixture server [client] finish
                                   (fn [_]
                                     (let [result (first @(:completed client))]
                                       (is (= 0 (:dao.stream.rpc/id result)))
                                       (is (= [:echo "hello"]
                                              (apply/response-ok (:dao.stream.rpc/response result))))
                                       (is (string? (:dao.stream/attachment (:attach client))))
                                       ;; Every deposited envelope on the client medium is
                                       ;; attributed to this attachment: demultiplexing survived
                                       ;; the real wire.
                                       (is (= [[:ws/opened nil] [:ws/payload nil]]
                                              (event-kinds (:traffic client))))
                                       (is (every? #(= (:dao.stream/attachment (:attach client))
                                                       (:ws/attachment %))
                                                   (drain (:traffic client))))
                                       ;; The server answered from its own acknowledged
                                       ;; per-attachment medium, not from the control path.
                                       (is (= 1 (count @(:traffic-media fixture))))
                                       (is (empty? (drain (:control fixture))))
                                       (teardown! fixture [client] server ticker)
                                       (finish))))))))))


(deftest closing-the-served-stream-ends-rather-than-detaches-clients
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
                                     ;; The served stream's owner closes it; the forwarder reports
                                     ;; source-ended and the composition performs the 4000 close.
                                     (stream/close! (:served fixture))
                                     (after 4000 (fn []
                                                   (= :dao.stream.apply/ended
                                                      (:terminal @(:rpc-atom client))))
                                            "no ended terminal" fixture server [client] finish
                                            (fn [terminal]
                                              (is (= :ws/ended (:ws/event (last (drain (:traffic client))))))
                                              ;; Ended is terminal, never reattachable.
                                              (is (not= :dao.stream.apply/detached terminal))
                                              (teardown! fixture [client] server ticker)
                                              (finish)))))))))))


(deftest unknown-paths-are-authoritatively-disclaimed
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [client (make-client (assoc (:descriptor fixture)
                                                           :ws/port port :ws/path "/yin/absent"))
                                ticker (client-ticker client)]
                            (reset! (:rpc-atom client)
                                    (:dao.stream.rpc/state
                                      (rpc/request! @(:rpc-atom client) :op/echo ["lost"])))
                            (after 4000 (fn []
                                          (= :dao.stream.apply/not-found
                                             (:terminal @(:rpc-atom client))))
                                   "no not-found terminal" fixture server [client] finish
                                   (fn [_]
                                     ;; A disclaimer is one resolution plus one terminal lifecycle
                                     ;; event.  The request stayed unsent until the attachment
                                     ;; closed, so the retry's append is refused with the
                                     ;; transport's closed outcome and the request completes
                                     ;; undelivered exactly once -- reported, never pending.
                                     (is (= [[:ws/not-found nil] [:ws/closed nil]]
                                            (event-kinds (:traffic client))))
                                     (is (= 1 (count @(:completed client))))
                                     (is (= :dao.stream/closed
                                            (:dao.stream.rpc/reason (first @(:completed client)))))
                                     (is (nil? (:unsent @(:rpc-atom client))))
                                     ;; Not-found never occupies a handoff slot and creates no
                                     ;; traffic medium.
                                     (is (empty? (drain (:offer fixture))))
                                     (is (empty? @(:traffic-media fixture)))
                                     (teardown! fixture [client] server ticker)
                                     (finish)))))))))


(deftest unreachable-endpoints-resolve-as-retryable-transport-errors
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               client (make-client (assoc (:descriptor fixture) :ws/port 1))
               ticker (client-ticker client)]
           (reset! (:rpc-atom client)
                   (:dao.stream.rpc/state
                     (rpc/request! @(:rpc-atom client) :op/echo ["unreachable"])))
           (after 4000 (fn []
                         (= :dao.stream.apply/transport-error
                            (:terminal @(:rpc-atom client))))
                  "no transport-error terminal" fixture ticker [client] finish
                  (fn [_]
                    (is (= [[:ws/error :ws/socket-error]
                            [:ws/transport-error nil]
                            [:ws/closed nil]]
                           (event-kinds (:traffic client))))
                    ;; The unsent request is retried into a closed attachment and
                    ;; completes undelivered with the transport's closed outcome;
                    ;; retrying the descriptor is interpreter policy, not the
                    ;; transport's.
                    (is (= 1 (count @(:completed client))))
                    (is (= :dao.stream/closed
                           (:dao.stream.rpc/reason (first @(:completed client)))))
                    (is (nil? (:unsent @(:rpc-atom client))))
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
                                frames (atom [])]
                            (.on raw "message"
                                 (fn [data is-binary]
                                   (swap! frames conj
                                          (if is-binary ::binary (.toString data "utf8")))))
                            (.on raw "close"
                                 (fn [code reason]
                                   (swap! frames conj
                                          [::close code (.toString reason "utf8")])))
                            (after 4000 (fn []
                                          (some (fn [text]
                                                  (and (string? text)
                                                       (= :ws/accept
                                                          (:ws/frame (transit/decode text)))))
                                                @frames))
                                   "raw client never accepted" fixture server [] finish
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
                          {:served {"/yin/repl" table-descriptor}
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


(deftest detachment-reattaches-with-a-new-attachment-and-kept-cursor
  (async done
         (let [fixture (server-fixture)
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [client (make-client (assoc (:descriptor fixture) :ws/port port))
                                ticker (client-ticker client)]
                            (reset! (:rpc-atom client)
                                    (:dao.stream.rpc/state
                                      (rpc/request! @(:rpc-atom client) :op/echo ["first"])))
                            (after 4000 (fn [] (seq @(:completed client)))
                                   "first round trip never completed" fixture server [client] finish
                                   (fn [_]
                                     (let [first-me (:dao.stream/attachment (:attach client))]
                                       (is (= 1 (count @(:completed client))))
                                       ;; The client detaches its own attachment.
                                       (stream/close! (:handle client))
                                       (after 4000 (fn []
                                                     (= :dao.stream.apply/detached
                                                        (:terminal @(:rpc-atom client))))
                                              "no detached terminal" fixture server [] finish
                                              (fn [_]
                                                (is (= :ws/closed
                                                       (:ws/event (last (drain (:traffic client))))))
                                                ;; The server observed the departure on that
                                                ;; attachment's own medium.
                                                (is (= :ws/closed
                                                       (:ws/event (last (drain (first (vals @(:traffic-media fixture))))))))
                                                ;; Reattach: same descriptor, same medium, kept cursor,
                                                ;; new attachment id.
                                                (let [detached-state @(:rpc-atom client)
                                                      attach-2 ((ws/make-attacher
                                                                  {:traffic {:dao.stream/handle (:traffic client)
                                                                             :dao.stream/surface #{:writer}}
                                                                   :admission admission
                                                                   :connect! node/connect!})
                                                                (assoc (:descriptor fixture) :ws/port port))
                                                      rebound (rpc-ws/rebind detached-state attach-2)
                                                      second-me (:dao.stream/attachment attach-2)]
                                                  (is (string? second-me))
                                                  (is (not= first-me second-me))
                                                  ;; The rebind keeps the deposit reader and its
                                                  ;; already-advanced cursor; only the writer and the
                                                  ;; attachment identity change.
                                                  (is (= (:reader detached-state) (:reader rebound)))
                                                  (is (= (:cursor detached-state) (:cursor rebound)))
                                                  (reset! (:rpc-atom client) rebound)
                                                  (reset! (:rpc-atom client)
                                                          (:dao.stream.rpc/state
                                                            (rpc/request! @(:rpc-atom client) :op/echo ["again"])))
                                                  (after 4000 (fn [] (<= 2 (count @(:completed client))))
                                                         "reattached round trip never completed"
                                                         fixture server [] finish
                                                         (fn [_]
                                                           (let [last-done (last @(:completed client))
                                                                 openings (filter #(= :ws/opened (:ws/event %))
                                                                                  (drain (:traffic client)))]
                                                             (is (= 1 (:dao.stream.rpc/id last-done)))
                                                             (is (= [:echo "again"]
                                                                    (apply/response-ok
                                                                      (:dao.stream.rpc/response last-done))))
                                                             ;; The one deposit medium carries both
                                                             ;; attachments' events, each attributed to its
                                                             ;; own.
                                                             (is (= first-me (:ws/attachment (first openings))))
                                                             (is (= second-me (:ws/attachment (second openings))))
                                                             (stream/close! (:dao.stream/handle attach-2))
                                                             (teardown! fixture [] server ticker)
                                                             (finish))))))))))))))))


;; =============================================================================
;; The dual-subprotocol wire: one listener, two codec profiles
;; =============================================================================


(deftest transit-and-cbor-clients-round-trip-through-one-listener
  ;; Dual-client compatibility: one Node listener negotiates both profiles,
  ;; a Transit client and a CBOR client attach concurrently to the same
  ;; served path, and each completes an RPC round trip over its own frame
  ;; kind — text frames for one, binary frames for the other, no sniffing.
  (async done
         ;; Two handoff slots: both sessions hand off concurrently rather than
         ;; racing one slot's release.
         (let [fixture (server-fixture {:codecs [transit/profile cbor/profile]
                                        :slot-count 2})
               finish (finish-once done)
               server (js/setInterval (fn [] (server-tick fixture (.now js/Date))) 10)]
           (after-bound fixture server finish
                        (fn [port]
                          (let [d (assoc (:descriptor fixture) :ws/port port)
                                transit-client (make-client d)
                                cbor-client (make-client d {:codec cbor/profile})
                                transit-ticker (client-ticker transit-client)
                                cbor-ticker (client-ticker cbor-client)]
                            (reset! (:rpc-atom transit-client)
                                    (:dao.stream.rpc/state
                                      (rpc/request! @(:rpc-atom transit-client)
                                                    :op/echo ["text-payload"])))
                            ;; Metadata rides the CBOR profile only: if the
                            ;; binary session were silently downgraded to the
                            ;; Transit text wire, the echoed reader position
                            ;; would come back stripped.
                            (reset! (:rpc-atom cbor-client)
                                    (:dao.stream.rpc/state
                                      (rpc/request! @(:rpc-atom cbor-client) :op/echo
                                                    [(with-meta [:bin-payload] {:line 5})])))
                            (after 4000 (fn []
                                          (and (seq @(:completed transit-client))
                                               (seq @(:completed cbor-client))))
                                   "both round trips never completed"
                                   fixture server [transit-client cbor-client] finish
                                   (fn [_]
                                     (let [response (fn [c]
                                                      (apply/response-ok
                                                        (:dao.stream.rpc/response
                                                          (first @(:completed c)))))]
                                       (is (= [:echo "text-payload"] (response transit-client)))
                                       (is (= [:echo [:bin-payload]] (response cbor-client))
                                           (str "cbor client saw "
                                                (pr-str (event-kinds (:traffic cbor-client)))))
                                       (is (= {:line 5}
                                              (meta (second (response cbor-client))))
                                           "the CBOR session kept its metadata end to end; a
                                            downgrade to the text wire would strip it"))
                                     (is (= cbor/profile
                                            (:ws/codec (ws/adapter (:handle cbor-client)))))
                                     (stream/close! (:handle transit-client))
                                     (stream/close! (:handle cbor-client))
                                     (teardown! fixture [] server transit-ticker cbor-ticker)
                                     (finish)))))))))


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

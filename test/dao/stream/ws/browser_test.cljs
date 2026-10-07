(ns dao.stream.ws.browser-test
  "Node-run tests for the browser DaoStream v2 WebSocket host adapter.

   Node has no DOM socket, so `js/WebSocket` is replaced for the duration of
   this test with a minimal, deterministic shim reproducing exactly the DOM
   surface `dao.stream.ws.browser` uses (`addEventListener`, `send`,
   `close`, a `MessageEvent`-shaped `.data`, a `CloseEvent`-shaped `.code`/
   `.reason`).  This proves the adapter's own translation -- framing, event
   names, synchronous return -- with no real network, exactly as
   `dao.stream.ws.node-test`'s deterministic layer does for Node."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws.browser :as browser]))


(def admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(defn- buffer
  []
  (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type
                                     ring/capacity-key 64})))


(defn- drain
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
  []
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "browser-adapter-service"
   :ws/host "127.0.0.1"
   :ws/port 9183
   :ws/path "/yin/repl"})


;; =============================================================================
;; A deterministic fake DOM WebSocket (a minimal `WebSocket` global shim)
;; =============================================================================


(defn- fake-socket
  "A recording stand-in for the DOM `WebSocket` constructor result:
   `addEventListener` registration plus the `send`/`close` surface
   `browser/raw-socket` wraps.  `fire` replays the DOM events a real socket
   would dispatch, as plain `#js {...}` objects carrying only the properties
   `browser/wire!` reads (`.data`, `.code`, `.reason`)."
  [url protocol]
  (let [handlers (atom {})
        sent (atom [])
        closes (atom [])
        socket (js-obj "url" url
                       "protocol" protocol
                       "addEventListener" (fn [event f]
                                            (swap! handlers update event (fnil conj []) f))
                       "send" (fn [data] (swap! sent conj data) js/undefined)
                       "close" (fn [code reason] (swap! closes conj [code reason])))]
    {:socket socket
     :handlers handlers
     :sent sent
     :closes closes
     :fire (fn [event data]
             (doseq [f (get @handlers event)]
               (f data)))}))


(def ^:private original-websocket (atom ::unset))


(defn- set-global-websocket!
  "`js/WebSocket` is not a declared global in Node, so `set!` on it would
   throw in strict mode; `globalThis` is always assignable."
  [ctor]
  (unchecked-set js/globalThis "WebSocket" ctor))


(use-fixtures :each
  {:before (fn [] (reset! original-websocket (unchecked-get js/globalThis "WebSocket")))
   :after (fn [] (set-global-websocket! @original-websocket))})


;; =============================================================================
;; connect! returns synchronously and never blocks
;; =============================================================================


(deftest connect-returns-send-and-close-synchronously
  (let [fake (fake-socket "ws://127.0.0.1:9183/yin/repl" ws/subprotocol)]
    (set-global-websocket! (fn [url protocol]
                             (is (= "ws://127.0.0.1:9183/yin/repl" url))
                             (is (= ws/subprotocol protocol))
                             (:socket fake)))
    (let [traffic (buffer)
          attach ((ws/make-attacher {:traffic {:dao.stream/handle traffic
                                               :dao.stream/surface #{:writer}}
                                     :admission admission
                                     :connect! browser/connect!})
                  (descriptor))]
      ;; `connect!` starts the connection and returns without waiting for the
      ;; peer: the attach outcome is already known before any socket event
      ;; fires.
      (is (= :dao.stream/ok (:dao.stream/outcome attach)))
      (is (string? (:dao.stream/attachment attach)))
      ;; The four translated DOM events are subscribed.
      (is (= #{"open" "message" "close" "error"} (set (keys @(:handlers fake))))))))


(deftest raw-socket-shape-is-exactly-send-close-and-queued-bytes
  (let [fake (fake-socket "ws://x" ws/subprotocol)
        raw (browser/raw-socket (:socket fake))]
    (is (fn? (:send! raw)))
    (is (fn? (:close! raw)))
    (is (= #{:send! :close! :queued-bytes} (set (keys raw))))
    (set! (.-bufferedAmount ^js (:socket fake)) 42)
    (is (= 42 ((:queued-bytes raw)))
        "the outbound backlog is the DOM socket's bufferedAmount")))


;; =============================================================================
;; Overflow teardown under the DOM close-code contract
;; =============================================================================


(defn- validating-socket
  "A fake whose `close` enforces the WHATWG contract -- only 1000 or
   3000-4999, else `InvalidAccessError` before anything closes -- and, when
   it accepts, dispatches the `close` event a real socket would, echoing the
   code and reason."
  [url protocol]
  (let [fake (fake-socket url protocol)]
    (unchecked-set (:socket fake) "close"
                   (fn [code reason]
                     (when-not (or (= 1000 code) (<= 3000 code 4999))
                       (throw (doto (js/Error. "invalid code")
                                (unchecked-set "name" "InvalidAccessError"))))
                     (swap! (:closes fake) conj [code reason])
                     ((:fire fake) "close" #js {:code code :reason reason})))
    fake))


(defn- attach-validating
  [bounds]
  (let [fake (validating-socket "ws://127.0.0.1:9183/yin/repl" ws/subprotocol)
        traffic (buffer)]
    (set-global-websocket! (fn [_url _protocol] (:socket fake)))
    (let [attach ((ws/make-attacher (merge {:traffic {:dao.stream/handle traffic
                                                      :dao.stream/surface #{:writer}}
                                            :admission admission
                                            :connect! browser/connect!}
                                           bounds))
                  (descriptor))]
      ((:fire fake) "open" #js {})
      {:fake fake :traffic traffic :handle (:dao.stream/handle attach)})))


(deftest dom-close-rejects-the-raw-overflow-codes
  (let [fake (validating-socket "ws://x" ws/subprotocol)]
    (doseq [code [1008 1009 1013]]
      (is (thrown? js/Error (.close ^js (:socket fake) code "r"))
          "the fake enforces the DOM contract the adapter must satisfy"))
    (is (= [] @(:closes fake)))))


(deftest inbound-overflow-closes-the-dom-socket
  (let [{:keys [fake traffic]} (attach-validating {:ws/max-frame-bytes 8})]
    ((:fire fake) "message" #js {:data "a frame well past eight characters"})
    (is (= [[1000 "dao.stream/frame-too-large"]] @(:closes fake))
        "1009 reaches the DOM as 1000 with the transport's reason, so the
         socket closes rather than throwing InvalidAccessError")
    (is (= [[:ws/opened nil] [:ws/error :ws/frame-too-large] [:ws/closed nil]]
           (event-kinds traffic))
        "the qualified diagnostic, then the terminal from the DOM close event")))


(deftest outbound-overflow-closes-the-dom-socket
  (let [{:keys [fake traffic handle]} (attach-validating {:ws/max-outbound-bytes 16})]
    (set! (.-bufferedAmount ^js (:socket fake)) 64)
    (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! handle [:v]))))
    (is (= [[1000 "dao.stream/outbound-overflow"]] @(:closes fake))
        "1008 reaches the DOM as 1000 with the transport's reason")
    (is (= [[:ws/opened nil] [:ws/error :ws/outbound-overflow] [:ws/closed nil]]
           (event-kinds traffic)))))


;; =============================================================================
;; Deterministic wiring through the fake DOM socket
;; =============================================================================


(deftest wiring-deposits-opened-and-closed-through-the-boundary
  (let [traffic (buffer)
        fake (fake-socket "ws://127.0.0.1:9183/yin/repl" ws/subprotocol)]
    (set-global-websocket! (fn [_url _protocol] (:socket fake)))
    (let [attach ((ws/make-attacher {:traffic {:dao.stream/handle traffic
                                               :dao.stream/surface #{:writer}}
                                     :admission admission
                                     :connect! browser/connect!})
                  (descriptor))
          handle (:dao.stream/handle attach)]
      (is (= :dao.stream/ok (:dao.stream/outcome attach)))
      ;; While establishing, append! answers full and nothing is sent -- the
      ;; adapter never blocked waiting for the peer.
      (is (= :dao.stream/full (:dao.stream/outcome (stream/append! handle :early))))
      (is (empty? @(:sent fake)))
      ;; The DOM's own open event resolves the attachment and deposits
      ;; `:ws/opened` through the boundary; there is no admission wire
      ;; frame any more.
      ((:fire fake) "open" nil)
      (is (= [[:ws/opened nil]] (event-kinds traffic)))
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! handle {:a 1}))))
      (is (= [{:ws/frame :ws/value :ws/value {:a 1}}]
             (mapv transit/decode @(:sent fake))))
      ;; A binary DOM message (non-string `.data`) is routed as the fixed
      ;; binary-message marker, which the transport's own codec rejects.
      ((:fire fake) "message" (js-obj "data" (js/ArrayBuffer. 4)))
      (is (= [[:ws/opened nil] [:ws/error :ws/decode-failure]]
             (event-kinds traffic)))
      (is (= [ws/protocol-close-code "dao.stream/protocol-error"] (last @(:closes fake))))
      ;; Host close completion deposits exactly one terminal event, carrying
      ;; the DOM `CloseEvent`'s `.code`/`.reason`.
      ((:fire fake) "close" (js-obj "code" ws/protocol-close-code
                                    "reason" "dao.stream/protocol-error"))
      (is (= [[:ws/opened nil] [:ws/error :ws/decode-failure] [:ws/closed nil]]
             (event-kinds traffic)))
      (is (= :dao.stream/closed (:dao.stream/outcome (stream/append! handle :late)))))))

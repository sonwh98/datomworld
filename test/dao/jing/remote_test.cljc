(ns dao.jing.remote-test
  "Tests for dao.jing.remote, the WebSocket-remote content adapter.

   Server side: dao.stream.rpc.ws serves dao.jing.remote/default-handlers over
   a local dao.jing content handle. Client side:
   dao.jing.remote/connect-content! wraps a dao.stream.rpc.client connection as
   a dao.jing content handle. The synchronous WebSocket constructor is
   JVM-only, so network tests are gated with #?(:clj ...) while the
   in-process content-client unit tests run on all hosts."
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.rpc :as rpc]
            [dao.stream.v2.ws :as ws]
            #?(:clj [dao.jing.file :as jing.file])
            [dao.jing.remote :as remote]
            #?(:clj [dao.stream.rpc.ws :as rpc-ws])))


(defn- local-client
  "Build an in-process handlers + content-client pair over a memory store.
   Returns {:store s :handlers h :client c :close-counter a}."
  ([] (local-client (mem/create-content-mem)))
  ([store]
   (let [handlers (remote/default-handlers store)
         close-counter (atom 0)
         client (remote/content-client ::local
                                       (fn [_ op args]
                                         (apply (get handlers op) args))
                                       (fn [_] (swap! close-counter inc)))]
     {:store store,
      :handlers handlers,
      :client client,
      :close-counter close-counter})))


#?(:clj (defn- with-server
          [f]
          (let [port (+ 20000 (rand-int 30000))
                url (str "ws://localhost:" port)
                backing (mem/create-content-mem)
                server (rpc-ws/start! (remote/default-handlers backing) port)
                _ (Thread/sleep 100)]
            (try (let [client (remote/connect-content! url)]
                   (try (f url client) (finally (jing/close! client))))
                 (finally (rpc-ws/stop! server) (jing/close! backing))))))


(deftest default-handlers-exact-test
  (let [store (mem/create-content-mem)
        handlers (remote/default-handlers store)]
    (is (= #{:jing/put-content :jing/get-content} (set (keys handlers)))
        "server handlers must expose exactly the two content ops")
    (is (every? ifn? (vals handlers)))
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (remote/default-handlers {}))
        "construction must throw when :put-content-fn is missing")
    (jing/close! store)))


(deftest put-automatic-materialize-test
  (let [fx (local-client)
        client (:client fx)
        payload {:hello "world"}
        address (jing/materialize! client payload)]
    (is (= (jing/segment-key payload) address)
        "materialize! must return the payload's segment address")
    (is (= payload (jing/get client address ::miss))
        "materialized content must be readable by its segment-key address")
    (jing/close! client)
    (jing/close! (:store fx))))


(deftest get-nil-opaque-absent-test
  (let [fx (local-client)
        client (:client fx)]
    (let [nil-address (jing/materialize! client nil)]
      (is (= (jing/segment-key nil) nil-address)
          "materialize! must mint the address of a nil payload")
      (is (nil? (jing/get client nil-address ::miss))
          "a stored nil must be returned as nil, not as the absent sentinel"))
    (let [opaque [1 2 3 {:nested true}]
          opaque-address (jing/materialize! client opaque)]
      (is (= (jing/segment-key opaque) opaque-address)
          "materialize! must mint the address of an opaque payload")
      (is (= opaque (jing/get client opaque-address ::miss))
          "opaque values must round-trip"))
    (is (= ::miss
           (jing/get client (jing/segment-key {:never "written"}) ::miss))
        "an absent address must return the not-found sentinel")
    (jing/close! client)
    (jing/close! (:store fx))))


(deftest put-duplicate-reports-present-test
  (let [fx (local-client)
        client (:client fx)
        payload {:dedup "same content"}
        address (jing/materialize! client payload)]
    (is (= (jing/segment-key payload) address)
        "materialize! must return the segment address")
    (is (= :present ((:put-content-fn client) address payload))
        "materializing identical content again must report :present")
    (jing/close! client)
    (jing/close! (:store fx))))


(deftest server-put-rejects-non-keyword-address-test
  (let [fx (local-client)
        handlers (:handlers fx)
        payload {:x 1}]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          ((:jing/put-content handlers) "not-a-keyword" payload))
        "a non-keyword address must be rejected")
    (jing/close! (:client fx))
    (jing/close! (:store fx))))


(deftest server-put-rejects-hash-mismatch-test
  (let [fx (local-client)
        handlers (:handlers fx)
        payload {:x 1}]
    (is (thrown?
          #?(:clj Exception
             :cljd Object
             :cljs js/Error)
          ((:jing/put-content handlers) (jing/segment-key {:x 2}) payload))
        "an address whose hash does not match the payload must be rejected")
    (jing/close! (:client fx))
    (jing/close! (:store fx))))


(deftest backend-invalid-put-result-test
  (let [handlers (remote/default-handlers {:put-content-fn (fn [_ _] :bogus)})]
    (is (thrown?
          #?(:clj Exception
             :cljd Object
             :cljs js/Error)
          ((:jing/put-content handlers) (jing/segment-key {:x 1}) {:x 1}))
        "a backend result outside #{:inserted :present} must throw")))


(deftest client-get-arbitrary-address-test
  (let [fx (local-client)
        client (:client fx)]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (jing/get client :anything-at-all ::miss))
        "client get must reject an arbitrary keyword address")
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (jing/get client "string-address" ::miss))
        "client get must reject a string address")
    (jing/close! client)
    (jing/close! (:store fx))))


(deftest close-idempotent-ops-throw-test
  (let [fx (local-client)
        client (:client fx)
        close-counter (:close-counter fx)]
    (jing/close! client)
    (jing/close! client)
    (is (= 1 @close-counter) "the underlying close must run exactly once")
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (jing/materialize! client {:x 1}))
        "materialize! after close must throw")
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (jing/get client (jing/segment-key {:x 1}) ::miss))
        "get after close must throw")
    (jing/close! (:store fx))))


(deftest close-failure-retry-test
  (let [store (mem/create-content-mem)
        handlers (remote/default-handlers store)
        attempts (atom 0)
        client (remote/content-client
                 ::local
                 (fn [_ op args] (apply (get handlers op) args))
                 (fn [_]
                   (swap! attempts inc)
                   (when (< @attempts 2) (throw (ex-info "close failed" {})))))]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (jing/close! client))
        "a failed close must propagate the error")
    (is (= 1 @attempts))
    (is (false? @(:closed-atom client))
        "the client must stay open after a failed close")
    (is (= (jing/segment-key {:still "open"})
           (jing/materialize! client {:still "open"}))
        "a valid materialize! must still work after a failed close")
    (is (nil? (jing/close! client)) "a retry close must succeed and return nil")
    (is (= 2 @attempts))
    (is (true? @(:closed-atom client))
        "a successful close must mark the client closed")
    (is (nil? (jing/close! client)) "a close after success must be a no-op")
    (is (= 2 @attempts))
    (jing/close! store)))


(deftest two-clients-share-store-test
  (let [store (mem/create-content-mem)
        a (local-client store)
        b (local-client store)
        payload {:from "a"}
        address (jing/materialize! (:client a) payload)]
    (is (= (jing/segment-key payload) address)
        "materialize! must return the segment address")
    (is (= payload (jing/get (:client b) address ::miss))
        "writes through one client must be visible to the other")
    (jing/close! (:client a))
    (jing/close! (:client b))
    (jing/close! store)))


(deftest network-connect-test
  #?(:clj (with-server (fn [_url client]
                         (is (some? client))
                         (is (false? @(:closed-atom client)))
                         (is (ifn? (:put-content-fn client)))
                         (is (ifn? (:get-content-fn client)))
                         (is (ifn? (:close-fn client)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest network-materialize-and-get-test
  #?(:clj (with-server
            (fn [_url client]
              (let [payload {:hello "world"}
                    address (jing/materialize! client payload)]
                (is (= (jing/segment-key payload) address)
                    "materialize! must return the segment address")
                (is (= payload (jing/get client address ::miss)))
                (is (= :present ((:put-content-fn client) address payload))
                    "duplicate content must report :present over the wire"))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest network-two-clients-share-test
  #?(:clj (with-server
            (fn [url client]
              (let [client-b (remote/connect-content! url)
                    payload {:from "a"}
                    address (jing/materialize! client payload)]
                (try (is (= (jing/segment-key payload) address)
                         "materialize! must return the segment address")
                     (is (= payload (jing/get client-b address ::miss))
                         "a second client must see the first client's writes")
                     (finally (jing/close! client-b))))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest network-invalid-url-test
  #?(:clj (is (thrown? Exception
                (remote/connect-content! "ws://localhost:99999")))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest network-file-restart-test
  #?(:clj
     (let [path (str "target/dao-jing-remote-"
                     (java.util.UUID/randomUUID)
                     ".log")
           payload {:persisted "yes"}
           address (jing/segment-key payload)]
       (try
         (let [backing (jing.file/create-content-file path)
               port (+ 20000 (rand-int 30000))
               url (str "ws://localhost:" port)
               server (rpc-ws/start! (remote/default-handlers backing) port)
               _ (Thread/sleep 100)]
           (try (let [client (remote/connect-content! url)]
                  (is (= (jing/segment-key payload)
                         (jing/materialize! client payload))
                      "payload must materialize on the file-backed server")
                  (jing/close! client))
                (finally (rpc-ws/stop! server) (jing/close! backing))))
         (let [backing (jing.file/create-content-file path)
               port (+ 20000 (rand-int 30000))
               url (str "ws://localhost:" port)
               server (rpc-ws/start! (remote/default-handlers backing) port)
               _ (Thread/sleep 100)]
           (try (let [client (remote/connect-content! url)]
                  (is (= payload (jing/get client address ::miss))
                      "content must survive a server restart")
                  (jing/close! client))
                (finally (rpc-ws/stop! server) (jing/close! backing))))
         (finally (try (java.nio.file.Files/deleteIfExists
                         (java.nio.file.Path/of path (make-array String 0)))
                       (catch Exception _)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest local-malformed-envelope-test
  (let [malformed-response (atom nil)
        mock-handlers {:jing/get-content (fn [_address & _]
                                           @malformed-response)}
        client (remote/content-client ::mock
                                      (fn [_ op args]
                                        (apply (get mock-handlers op) args))
                                      (fn [_] nil))
        addr (jing/segment-key {:x 1})]
    (try (doseq [val ["bogus" {:found? "yes", :value nil} {:found? true}
                      {:value 123} {:found? true, :value 123, :extra 4}]]
           (reset! malformed-response val)
           (try (jing/get client addr ::miss)
                (is false
                    (str "should have thrown for malformed response: "
                         (pr-str val)))
                (catch #?(:clj Exception
                          :cljs js/Error
                          :cljd Object)
                       e
                  (let [data (ex-data e)
                        msg #?(:clj (.getMessage e)
                               :cljs (.-message e)
                               :cljd (ex-message e))]
                    (is (= :jing/get-content (:operation data)))
                    (is (= addr (:address data)))
                    (is (= val (:response data)))
                    (is (re-find #"malformed RPC response" msg))))))
         (finally (jing/close! client)))))


(deftest local-present-then-absent-test
  (let [mock-handlers {:jing/put-content (fn [_address _payload & _] :present),
                       :jing/get-content (fn [_address & _]
                                           {:found? false, :value nil})}
        client (remote/content-client ::mock
                                      (fn [_ op args]
                                        (apply (get mock-handlers op) args))
                                      (fn [_] nil))]
    (try
      ;; nil case
      (is (thrown-with-msg?
            #?(:clj Exception
               :cljs js/Error
               :cljd Object)
            #"backend reported :present but the content address is absent"
            (jing/materialize! client nil)))
      ;; non-nil case
      (is (thrown-with-msg?
            #?(:clj Exception
               :cljs js/Error
               :cljd Object)
            #"backend reported :present but the content address is absent"
            (jing/materialize! client {:some "payload"})))
      (finally (jing/close! client)))))


(deftest network-presence-envelope-test
  #?(:clj (with-server
            (fn [_url client]
              (let [nil-addr (jing/materialize! client nil)
                    envelope-like {:found? true, :value "hello"}
                    env-addr (jing/materialize! client envelope-like)
                    local-sentinel (Object.)
                    absent-addr (jing/segment-key {:absent "indeed"})]
                (is (nil? (jing/get client nil-addr ::miss)))
                (is (= envelope-like (jing/get client env-addr ::miss)))
                (is (identical?
                      local-sentinel
                      (jing/get client absent-addr local-sentinel))))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


;; =============================================================================
;; The DaoStream v2 portable core (migration Phase 1)
;; =============================================================================

(defn- ring-handle
  "A fresh ring-buffer stream handle for the in-process media below."
  ([] (ring-handle 16))
  ([capacity]
   (:dao.stream/handle
     (ring/create! {:dao.stream/type ring/transport-type
                    ring/capacity-key capacity}))))


(defn- ring-cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- ring-client
  "rpc/client-state over two ring buffers with no decoder: bare apply
   responses and bare lifecycle values are accepted as-is."
  [request-handle response-handle]
  (rpc/client-state request-handle response-handle (ring-cursor response-handle)))


(defn- serve-request!
  "The hand-turned server step: dispatch `handlers` over the sole request the
   writer carries and append the response to the reader."
  [handlers request-handle response-handle]
  (stream/append!
    response-handle
    (apply/dispatch-request
      handlers
      (:dao.stream/value (stream/next request-handle (ring-cursor request-handle))))))


(deftest content-descriptor-derives-a-servable-descriptor
  (let [derived (remote/content-descriptor "ws://127.0.0.1:7070")]
    (is (= {:dao.stream/type :dao.stream/ws
            :dao.stream/identity "dao.jing.remote/content"
            :ws/host "127.0.0.1"
            :ws/port 7070
            :ws/path "/jing"}
           derived)
        "host, port, the default path and the fixed identity are derived")
    (is (ws/descriptor? derived) "the derived descriptor is servable"))
  (is (= 80 (:ws/port (remote/content-descriptor "ws://example.com")))
      "an absent port means the WebSocket scheme's own")
  (is (= "/jing" (:ws/path (remote/content-descriptor "ws://example.com")))
      "an absent path names the content path")
  (is (= "/" (:ws/path (remote/content-descriptor "ws://example.com:7070/")))
      "an explicit / stays /")
  (is (= "/elsewhere"
         (:ws/path (remote/content-descriptor "ws://example.com:7070/elsewhere")))
      "an explicit path is kept as written")
  (is (ws/descriptor? (remote/content-descriptor "ws://example.com:7070/elsewhere")))
  (is (ws/descriptor? (remote/content-descriptor "ws://example.com?query=1#frag"))
      "query and fragment are ignored, not part of the request target")
  (doseq [url ["wss://example.com"
               "http://example.com"
               "daostream:ws://example.com"
               "example.com:7070"
               "ws:///no-host"
               "ws://:7070"
               "ws://example.com:0"
               "ws://example.com:7zero"
               "ws://[::1]:7070"]]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (remote/content-descriptor url))
        (str "must throw before any socket: " url))))


(deftest call-step-completes-one-request-over-in-process-media
  ;; One request crosses the writer, a hand-turned dispatch over
  ;; default-handlers answers it, and the step completes with the value.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        store (mem/create-content-mem)
        handlers (remote/default-handlers store)
        payload {:hello "world"}
        address (jing/segment-key payload)]
    (try
      (is (= :inserted ((:jing/put-content handlers) address payload)))
      (let [requested (rpc/request! (ring-client request-handle response-handle)
                                    :jing/get-content [address])
            state (:dao.stream.v2.rpc/state requested)]
        (is (= :dao.stream.v2.rpc/requested (:dao.stream.v2.rpc/outcome requested)))
        (is (= 0 (:dao.stream.v2.rpc/id requested)))
        (is (= :pending (:status (remote/call-step state 0 1)))
            "before any response exists the step is pending")
        (serve-request! handlers request-handle response-handle)
        (let [done (remote/call-step state 0 1)]
          (is (= :done (:status done)))
          (is (= {:found? true, :value payload}
                 (remote/completion-value (:completion done)))
              "an ok response yields the presence envelope")
          (is (= [] (:completed (:state done))))
          (is (= [] (:diagnostics (:state done))))))
      (finally (jing/close! store))))
  ;; A handler that throws becomes an error response (H5), which
  ;; completion-value decodes into N9's error.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        handlers {:jing/get-content (fn [_address] (throw (ex-info "boom" {})))}
        address (jing/segment-key {:x 1})
        requested (rpc/request! (ring-client request-handle response-handle)
                                :jing/get-content [address])
        state (:dao.stream.v2.rpc/state requested)]
    (serve-request! handlers request-handle response-handle)
    (let [done (remote/call-step state 0 1)]
      (is (= :done (:status done)))
      (try
        (remote/completion-value (:completion done))
        (is false "an error response must throw")
        (catch #?(:clj Exception
                  :cljd Object
                  :cljs js/Error)
               e
          (is (= {:operation :jing/get-content
                  :error {:dao.stream.v2.apply/code :dao.stream.v2.apply/handler-error
                          :dao.stream.v2.apply/message "Handler failed"}}
                 (ex-data e))
              "the error map is carried under :error")))))
  ;; A terminal lifecycle on the reader, with nothing outstanding, ends the
  ;; step as terminal with the reason.
  (let [response-handle (ring-handle)
        state (ring-client (ring-handle) response-handle)]
    (stream/append! response-handle :dao.stream.v2.apply/detached)
    (let [r (remote/call-step state 0 4)]
      (is (= :terminal (:status r)))
      (is (= :dao.stream.v2.apply/detached (:reason r)))))
  ;; The same lifecycle with a call in flight loses that call on the ordinary
  ;; completion path; completion-value throws N9's loss with the reason.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        address (jing/segment-key {:x 1})
        state (:dao.stream.v2.rpc/state
                (rpc/request! (ring-client request-handle response-handle)
                              :jing/get-content [address]))]
    (stream/append! response-handle :dao.stream.v2.apply/detached)
    (let [r (remote/call-step state 0 4)]
      (is (= :done (:status r))
          "the in-flight call is lost on the completion path, not left pending")
      (try
        (remote/completion-value (:completion r))
        (is false "a lost completion must throw")
        (catch #?(:clj Exception
                  :cljd Object
                  :cljs js/Error)
               e
          (is (= {:operation :jing/get-content
                  :reason :dao.stream.v2.apply/detached}
                 (ex-data e)))))
      (is (= [] (:completed (:state r))))
      (is (= [] (:diagnostics (:state r))))))
  ;; A response for a foreign id is consumed and discarded; the awaited call
  ;; stays pending.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        address (jing/segment-key {:x 1})
        state (:dao.stream.v2.rpc/state
                (rpc/request! (ring-client request-handle response-handle)
                              :jing/get-content [address]))]
    (stream/append! response-handle (apply/success-response 7 :foreign))
    (let [r (remote/call-step state 0 4)]
      (is (= :pending (:status r)))
      (is (= [] (:diagnostics (:state r)))
          "the foreign response is consumed as an unsolicited diagnostic and dropped")
      (is (= [] (:completed (:state r))))
      (is (= {0 {:op :jing/get-content, :args [address]}}
             (:outstanding (:state r)))
          "the awaited call is still outstanding")))
  ;; A writer answering full once leaves the envelope :unsent; the next step
  ;; retries that same envelope and completes.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        address (jing/segment-key {:x 1})
        pending (:dao.stream.v2.rpc/state
                  (rpc/request! (rpc/client-state full-writer response-handle
                                                  (ring-cursor response-handle))
                                :jing/get-content [address]))]
    (is (true? (rpc/unsent? pending)))
    (let [retried (remote/call-step (assoc pending :writer request-handle) 0 1)]
      (is (= :pending (:status retried))
          "the retry delivers the envelope; no response exists yet")
      (stream/append! response-handle (apply/success-response 0 :delivered))
      (let [done (remote/call-step (:state retried) 0 1)]
        (is (= :done (:status done)))
        (is (= :delivered (remote/completion-value (:completion done))))))))


(deftest retire-call-drops-bookkeeping-and-a-late-response-is-unsolicited
  (let [address (jing/segment-key {:payload "content"})
        request-handle (ring-handle)
        response-handle (ring-handle)
        requested (rpc/request! (ring-client request-handle response-handle)
                                :jing/get-content [address])
        state (:dao.stream.v2.rpc/state requested)
        retired (remote/retire-call state 0 :dao.jing.remote/timeout)]
    (is (= 0 (:dao.stream.v2.rpc/id requested)))
    (is (= {} (:outstanding retired)) "the retired id leaves :outstanding")
    (is (= 1 (:next-id retired)) ":next-id never moves back")
    (is (= [] (:completed retired)))
    ;; The response nobody awaits anymore arrives, is classified unsolicited,
    ;; and never reaches the next call.
    (stream/append! response-handle (apply/success-response 0 :stale))
    (let [next-requested (rpc/request! retired :jing/get-content [address])
          next-state (:dao.stream.v2.rpc/state next-requested)]
      (is (= 1 (:dao.stream.v2.rpc/id next-requested)))
      (is (not (contains? (:outstanding next-state) 0)))
      (stream/append! response-handle (apply/success-response 1 :fresh))
      (let [done (remote/call-step next-state 1 8)]
        (is (= :done (:status done)))
        (is (= :fresh (remote/completion-value (:completion done)))
            "id 1 gets id 1's value")
        (is (= [] (:completed (:state done))))
        (is (= [] (:diagnostics (:state done)))
            "id 0's late response was dropped as unsolicited, not delivered"))))
  ;; Retiring while still :unsent abandons the envelope, and the abandonment
  ;; completion is drained by retire-call itself.
  (let [address (jing/segment-key {:payload "content"})
        request-handle (ring-handle)
        response-handle (ring-handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        pending (:dao.stream.v2.rpc/state
                  (rpc/request! (rpc/client-state full-writer response-handle
                                                  (ring-cursor response-handle))
                                :jing/get-content [address]))]
    (is (true? (rpc/unsent? pending)))
    (let [retired (remote/retire-call pending 0 :dao.jing.remote/timeout)]
      (is (= [] (:completed retired))
          "the abandonment completion is drained on return")
      (is (false? (rpc/unsent? retired)))
      (is (= 1 (:next-id retired)))
      (let [next-requested (rpc/request! (assoc retired :writer request-handle)
                                         :jing/get-content [address])
            next-state (:dao.stream.v2.rpc/state next-requested)]
        (is (= 1 (:dao.stream.v2.rpc/id next-requested)))
        (stream/append! response-handle (apply/success-response 1 :second))
        (let [done (remote/call-step next-state 1 8)]
          (is (= :done (:status done)))
          (is (= :second (remote/completion-value (:completion done)))
              "the next call proceeds through a working writer"))))))


(deftest await-established-step-observes-only-lifecycle
  ;; No event yet: pending.
  (is (= :pending
         (:status (remote/await-established-step
                    (ring-client (ring-handle) (ring-handle))))))
  ;; A bare /established establishes.
  (let [response-handle (ring-handle)
        state (ring-client (ring-handle) response-handle)]
    (stream/append! response-handle :dao.stream.v2.apply/established)
    (is (= :established (:status (remote/await-established-step state)))))
  ;; A bare /detached first: terminal with the reason.
  (let [response-handle (ring-handle)
        state (ring-client (ring-handle) response-handle)]
    (stream/append! response-handle :dao.stream.v2.apply/detached)
    (let [r (remote/await-established-step state)]
      (is (= :terminal (:status r)))
      (is (= :dao.stream.v2.apply/detached (:reason r)))))
  ;; A response element before /established is consumed as a diagnostic and
  ;; does not establish; the /established behind it still establishes.
  (let [response-handle (ring-handle)
        state (ring-client (ring-handle) response-handle)]
    (stream/append! response-handle (apply/success-response 0 :early))
    (let [first (remote/await-established-step state)]
      (is (= :pending (:status first))
          "a response element does not establish")
      (is (= [] (:diagnostics (:state first)))
          "the unsolicited response is consumed as a diagnostic and dropped")
      (is (= [] (:completed (:state first))))
      (stream/append! response-handle :dao.stream.v2.apply/established)
      (let [second (remote/await-established-step (:state first))]
        (is (= :established (:status second)))))))


(deftest immediate-refusals-leave-no-payload-behind
  (let [refusing-writer (reify stream/IDaoStreamWriter
                          (append! [_ _] {:dao.stream/outcome :dao.stream/invalid-value}))
        response-handle (ring-handle)
        payload {:secret "payload"}
        address (jing/segment-key payload)
        refused (reduce
                  (fn [state _refusal]
                    (let [result (rpc/request! state :jing/put-content
                                               [address payload])]
                      (is (= :dao.stream.v2.rpc/request-undeliverable
                             (:dao.stream.v2.rpc/outcome result)))
                      (is (= :dao.stream/invalid-value
                             (:dao.stream.v2.rpc/reason result))
                          "the append's refusal reason is reported every time")
                      (let [state (:dao.stream.v2.rpc/state result)]
                        (is (= 1 (count (:completed state)))
                            "the refusal completes at once, carrying the request's args")
                        (is (= [address payload]
                               (get-in state [:completed 0 :dao.stream.v2.rpc/args])))
                        (let [drained (remote/drain-outboxes state)]
                          (is (= 0 (count (:completed drained)))
                              "the stored state keeps no completion after the exit")
                          (is (= 0 (count (:diagnostics drained))))
                          (is (= {} (:outstanding drained)))
                          drained))))
                  (rpc/client-state refusing-writer response-handle
                                    (ring-cursor response-handle))
                  (range 3))]
    (is (= 3 (:next-id refused)) "three ids were allocated and consumed")
    ;; An invalid request appends a diagnostic carrying the op and args; the
    ;; drain clears it the same way.
    (let [result (rpc/request! refused "not-a-keyword" [address payload])
          state (:dao.stream.v2.rpc/state result)]
      (is (= :dao.stream.v2.rpc/invalid-request
             (:dao.stream.v2.rpc/outcome result)))
      (is (= {:op "not-a-keyword", :args [address payload]}
             (:dao.stream.v2.rpc/value (first (:diagnostics state)))))
      (let [drained (remote/drain-outboxes state)]
        (is (= 0 (count (:completed drained))))
        (is (= 0 (count (:diagnostics drained))))))
    ;; A working writer recovers: the fourth request completes normally.
    (let [request-handle (ring-handle)
          store (mem/create-content-mem)
          handlers (remote/default-handlers store)]
      (try
        (let [requested (rpc/request! (assoc refused :writer request-handle)
                                      :jing/get-content [address])
              state (:dao.stream.v2.rpc/state requested)]
          (is (= 3 (:dao.stream.v2.rpc/id requested))
              "the allocator is untouched by the refusals' drain")
          (serve-request! handlers request-handle response-handle)
          (let [done (remote/call-step state 3 8)]
            (is (= :done (:status done)))
            (is (= {:found? false, :value nil}
                   (remote/completion-value (:completion done))))))
        (finally (jing/close! store))))))

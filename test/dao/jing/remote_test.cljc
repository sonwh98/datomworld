(ns dao.jing.remote-test
  "Tests for dao.jing.remote, the WebSocket-remote content adapter.

   Server side: dao.jing.remote/serve-content! serves
   dao.jing.remote/default-handlers over a local dao.jing content handle.
   Client side: dao.jing.remote/connect-content! connects over DaoStream v2
   and wraps the connection as a dao.jing content handle. Both constructors
   are JVM-only, so network tests run their bodies on the JVM and assert
   trivially elsewhere, while the in-process content-client unit tests run
   on all hosts."
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.stream.ws :as ws]
            #?(:clj [dao.jing.file :as jing.file])
            [dao.jing.remote :as remote]
            ;; :cljd first, as in remote.cljc: dao.stream.ws.jvm has no
            ;; Dart twin, so a :clj-first require would become a Dart
            ;; import.  Used only inside :clj test branches.
            #?@(:cljd []
                :clj [[dao.stream.ws.jvm :as jvm]])))


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


#?(:cljd nil
   :clj
   (defn- with-server
     [f]
     (let [port (+ 20000 (rand-int 30000))
           url (str "ws://127.0.0.1:" port)
           backing (mem/create-content-mem)
           server (remote/serve-content! (remote/default-handlers backing) port)]
       (try (let [client (remote/connect-content! url)]
              (try (f url client) (finally (jing/close! client))))
            (finally ((:stop! server)) (jing/close! backing))))))


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
               url (str "ws://127.0.0.1:" port)
               server (remote/serve-content! (remote/default-handlers backing) port)]
           (try (let [client (remote/connect-content! url)]
                  (is (= (jing/segment-key payload)
                         (jing/materialize! client payload))
                      "payload must materialize on the file-backed server")
                  (jing/close! client))
                (finally ((:stop! server)) (jing/close! backing))))
         (let [backing (jing.file/create-content-file path)
               port (+ 20000 (rand-int 30000))
               url (str "ws://127.0.0.1:" port)
               server (remote/serve-content! (remote/default-handlers backing) port)]
           (try (let [client (remote/connect-content! url)]
                  (is (= payload (jing/get client address ::miss))
                      "content must survive a server restart")
                  (jing/close! client))
                (finally ((:stop! server)) (jing/close! backing))))
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
;; The DaoStream v2 JVM host composition (migration Phase 2)
;; =============================================================================

(deftest request-timeout-retires-the-call-and-the-client-recovers-once-released
  #?(:clj
     (let [latch (java.util.concurrent.CountDownLatch. 1)
           port (+ 20000 (rand-int 30000))
           server (remote/serve-content!
                    {:gated/op (fn [] (.await latch) :late)
                     :fast/op (fn [] :now)}
                    port)]
       (try
         (let [handle (remote/connect-content!
                        (str "ws://127.0.0.1:" port)
                        {:request-timeout-ms 50})]
           (try
             (doseq [n (range 3)]
               (let [state
                     (try
                       (remote/call! (:client handle) :gated/op [])
                       (is false "a gated call must throw its deadline")
                       nil
                       (catch Exception e
                         (is (= {:request-id n :timeout-ms 50} (ex-data e))
                             (str "call n=" n " throws its own deadline with "
                                  "its own id (N6)"))
                         @(:rpc (:client handle))))]
                 ;; The first call stalls the server's single driver on the
                 ;; latch (S4); the next two are deposited and never
                 ;; dispatched.  After every exit the stored state is empty
                 ;; (N6, N11).
                 (is (= {} (:outstanding state)))
                 (is (= [] (:completed state)))
                 (is (= [] (:diagnostics state)))))
             (.countDown latch)
             ;; The fourth call buys the default deadline by client value:
             ;; cadence and deadlines are options, and the client is data.
             ;; The only bound is this generous deadline — no narrow timing
             ;; window (the server drains the three stalled requests and
             ;; their late responses are classified unsolicited and dropped).
             (let [patient (assoc (:client handle)
                                  :request-timeout-ms
                                  remote/default-request-timeout-ms)]
               (is (= :now (remote/call! patient :fast/op []))
                   "the client is usable once the driver is released"))
             (finally (jing/close! handle))))
         (finally ((:stop! server)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest an-interrupted-call-retires-and-permits-no-id-reuse
  ;; The interrupt flag is set on the test's own thread after the connect
  ;; and before the call: Thread/sleep throws on entry when the flag is
  ;; already set, so the loop leaves through its interrupted exit at the
  ;; first sleep — deterministically, with no second thread and no race.
  #?(:clj
     (let [latch (java.util.concurrent.CountDownLatch. 1)
           port (+ 20000 (rand-int 30000))
           server (remote/serve-content!
                    {:gated/op (fn [] (.await latch) :gated-late)
                     :fast/op (fn [] :fast-now)}
                    port)]
       (try
         (let [handle (remote/connect-content!
                        (str "ws://127.0.0.1:" port)
                        {:request-timeout-ms 500})]
           (try
             (.interrupt (Thread/currentThread))
             ;; The flag is captured inside the catch, before any
             ;; reporting machinery runs: under the test runner the flag
             ;; does not reliably survive even a passing `is` between the
             ;; catch and a later read, though the fix does preserve it
             ;; (a bare-REPL call confirms; the consumer is in the
             ;; runner's path, not in call!).
             (let [error (try
                           (remote/call! (:client handle) :gated/op [])
                           (is false "an interrupted call must throw")
                           nil
                           (catch Exception e
                             (let [preserved? (Thread/interrupted)]
                               (is (true? preserved?)
                                   "the interrupt flag is preserved for the
                                    caller — re-asserted by the exit before
                                    it throws — and this read clears it")
                               e)))]
               (is (= {:request-id 0
                       :reason :dao.jing.remote/interrupted}
                      (ex-data error))
                   "the interruption surfaces as data, after the retirement"))
             (let [state @(:rpc (:client handle))]
               (is (= 1 (:next-id state))
                   "the allocator advanced: the interrupted call's id is
                    consumed, so the next call cannot allocate it again")
               (is (= {} (:outstanding state))
                   "the interrupted call is retired, so its late response
                    will be classified unsolicited and dropped")
               (is (= [] (:completed state)))
               (is (= [] (:diagnostics state))))
             ;; Release the stall: the server answers the interrupted call
             ;; late, with :gated-late.  If the id had been reused, that
             ;; response would satisfy the next call; it must not.
             (.countDown latch)
             (let [patient (assoc (:client handle)
                                  :request-timeout-ms
                                  remote/default-request-timeout-ms)]
               (is (= :fast-now (remote/call! patient :fast/op []))
                   "a fresh id carries the next call, and the late
                    :gated-late response cannot satisfy it"))
             (finally
               (Thread/interrupted)           ; never leak the flag
               (jing/close! handle))))
         (finally ((:stop! server)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest a-non-portable-handler-result-is-a-correlated-error-not-a-timeout
  #?(:clj
     (let [port (+ 20000 (rand-int 30000))
           server (remote/serve-content!
                    ;; 9007199254740992 is one past the safe-integer bound,
                    ;; outside the portable value domain.
                    {:bad/op (fn [] 9007199254740992)
                     :good/op (fn [] :fine)}
                    port)]
       (try
         (let [handle (remote/connect-content! (str "ws://127.0.0.1:" port))]
           (try
             (let [error (try
                           (remote/call! (:client handle) :bad/op [])
                           (is false "a non-portable handler result must throw")
                           nil
                           (catch Exception e e))]
               (is (= {:operation :bad/op
                       :error {:dao.stream.apply/code
                               remote/non-portable-result-code
                               :dao.stream.apply/message
                               "Handler result is outside the portable value domain"}}
                      (ex-data error))
                   "the refusal is a correlated error response, well inside
                    the deadline (S5), never a timeout"))
             (is (= :fine (remote/call! (:client handle) :good/op []))
                 "the attachment was not retired for a refusal the step
                  could correlate")
             (finally (jing/close! handle))))
         (finally ((:stop! server)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest connect-throws-on-a-refused-endpoint
  #?(:clj
     (let [socket (java.net.ServerSocket. 0)
           port (.getLocalPort socket)
           url (str "ws://127.0.0.1:" port)]
       (.close socket)
       (let [error (try
                     (remote/connect-content! url {:connect-timeout-ms 2000})
                     (is false "a refused endpoint must throw at open")
                     nil
                     (catch Exception e e))]
         ;; The JDK edge deposits the failed establishment as a transport
         ;; error; nothing escaped to the caller (N2).
         (is (= {:url url :reason :dao.stream.apply/transport-error}
                (ex-data error))
             "the establishment failure is the reason N2 names")))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest connect-times-out-against-a-peer-that-never-completes-the-handshake
  ;; This asserts only what the transport guarantees (N2): the deadline
  ;; throw and that no handle escaped.  No cleanup claim, no EOF claim, no
  ;; statement about the JDK-held connection.  The client-side JDK
  ;; connection this test provokes cannot be closed from here and persists
  ;; for the process — the leak N2 names, paid once per run; the test closes
  ;; its own accepted socket and ServerSocket in the finally since nothing
  ;; else will.
  #?(:clj
     (let [server-socket (java.net.ServerSocket. 0)
           port (.getLocalPort server-socket)
           url (str "ws://127.0.0.1:" port)
           ;; Exactly one of the accepter and the finally closes the
           ;; accepted socket — a plain deref races the accepter's
           ;; publication.  nil: the accepter may still publish.  A socket:
           ;; the finally owns closing it.  ::teardown: the finally already
           ;; ran, so the accepter closes what it holds itself.
           slot (atom nil)
           _accepter (future
                       (let [s (.accept server-socket)]
                         (when (= ::teardown
                                  (swap! slot
                                         (fn [old]
                                           (if (= ::teardown old) old s))))
                           (.close ^java.net.Socket s))))
           started (System/currentTimeMillis)]
       (try
         (let [error (try
                       (remote/connect-content! url {:connect-timeout-ms 200})
                       (is false "a stalled handshake must throw at open")
                       nil
                       (catch Exception e e))]
           (is (= {:url url :timeout-ms 200} (ex-data error))
               "the connect deadline is the whole of the claim")
           (is (< (- (System/currentTimeMillis) started) 2000)
               "the throw lands at the deadline, not at some later hang"))
         (finally
           (let [to-close (swap! slot (fn [old] (if (nil? old) ::teardown old)))]
             (when-not (= ::teardown to-close)
               (.close ^java.net.Socket to-close)))
           (.close server-socket))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest connect-throws-on-interruption-with-nothing-escaping
  ;; The ninth exit's proof, with the eighth's deterministic trick: the
  ;; interrupt flag is set on the test's own thread before the connect, so
  ;; the establishment loop's first sleep throws on entry — no second
  ;; thread, no race.
  ;;
  ;; The peer is a scripted raw socket, installed by replacing
  ;; jvm/connect! — a plain function var, so with-redefs reaches it
  ;; (unlike the protocol fn stream/close!, whose compiled call sites
  ;; link straight to the interface method; probed in r4).  The real
  ;; attacher, the real WsHandle, the real protocol dispatch and the real
  ;; stream/close! all stay intact; the scripted socket's :close!
  ;; records invocation, and a pending establishment followed by an
  ;; interrupt must reach it.  No network, no JDK connection, no leak —
  ;; not even the one-per-run cost the raw-socket version of this test
  ;; used to pay.
  #?(:clj
     (let [closes (atom 0)
           url "ws://127.0.0.1:1"]        ; never contacted: the scripted
       ;; socket answers the attach
       (try
         (.interrupt (Thread/currentThread))
         (let [error (try
                       (with-redefs
                         [jvm/connect!
                          (fn [_descriptor _adapter]
                            {:send! (fn [_message] nil)
                             :close! (fn [_code _reason]
                                       (swap! closes inc))})]
                         (remote/connect-content! url {:connect-timeout-ms 2000}))
                       ::returned            ; a client would land here
                       (catch Exception e
                         ;; Captured inside the catch, before any reporting
                         ;; machinery can touch the thread (the runner
                         ;; wrinkle round 2 found).
                         (let [preserved? (Thread/interrupted)]
                           (is (true? preserved?)
                               "the interrupt flag is preserved for the
                                caller — re-asserted by the exit before it
                                throws — and this read clears it")
                           e)))]
           (is (map? (ex-data error))
               "the throw is the composition's interrupted error, not a raw
                InterruptedException — and because the constructor throws,
                no client value escaped to the caller (N2)")
           (is (= {:url url :reason :dao.jing.remote/interrupted}
                  (ex-data error))
               "the interrupted connect matches the loop's other failure
                throws")
           (is (= 1 @closes)
               "the exit closed the handle exactly once before throwing
                (N2) — recorded at the scripted raw socket the real
                stream/close! reaches, so deleting the close from that
                branch fails this test"))
         (finally
           (Thread/interrupted))))         ; never leak the flag
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest invalid-timing-options-throw-before-the-wire-and-leave-no-trace
  ;; The tenth exit: a client is a value, so an assoc can carry a bad
  ;; timing option into call!.  The gate throws before rpc/request!, so
  ;; nothing is appended; thrown after the append instead — from the
  ;; deadline arithmetic, or Thread/sleep's own argument check for a
  ;; negative interval — it would leave past settle! and store nothing.
  ;; The client-side state alone cannot discriminate that (the abandoned
  ;; state exists only in the loop's locals either way), so the proof is
  ;; the wire side: a recording handler sees what actually arrived.
  #?(:clj
     (let [seen (atom [])
           port (+ 20000 (rand-int 30000))
           server (remote/serve-content!
                    {:probe/op (fn [x] (swap! seen conj x) ::served)}
                    port)]
       (try
         ;; The constructor validates its own options the same way, before
         ;; any socket: this throws at the gate, not against the endpoint.
         (is (thrown-with-msg?
               Exception #"must be an integer of milliseconds"
               (remote/connect-content! "ws://127.0.0.1:1"
                                        {:connect-timeout-ms :soon})))
         (let [handle (remote/connect-content! (str "ws://127.0.0.1:" port))]
           (try
             (doseq [broken [(assoc (:client handle) :request-timeout-ms "bad")
                             (assoc (:client handle) :poll-interval-ms -5)]]
               (is (thrown-with-msg?
                     Exception #"must be an integer of milliseconds from 1 to 86400000"
                     (remote/call! broken :probe/op [:refused]))
                   "an invalid timing option is an argument defect"))
             (let [state @(:rpc (:client handle))]
               (is (= 0 (:next-id state)) "the allocator never advanced")
               (is (= {} (:outstanding state)))
               (is (= [] (:completed state)))
               (is (= [] (:diagnostics state))))
             ;; Give any request that might have escaped time to reach the
             ;; handler — the one assertion the mutation cannot dodge on
             ;; timing.
             (Thread/sleep 100)
             (is (= [] @seen)
                 "no request reached the server: the refusals sent nothing")
             (is (= ::served (remote/call! (:client handle) :probe/op [:fresh]))
                 "the intact client's next call answers normally")
             (is (= [:fresh] @seen)
                 "exactly one request crossed the wire — the corrected
                  call's own — so it ran on a fresh id")
             (is (= 1 (:next-id @(:rpc (:client handle))))
                 "the corrected call consumed the first id")
             ;; The gate's upper end (r5).  At the bound the option is
             ;; supported — both paths submit and the deadline arithmetic
             ;; holds.  Past it — one over, Long/MAX_VALUE, and an
             ;; oversized BigInt that integer? would admit — every value
             ;; is rejected with nothing submitted (call path) and nothing
             ;; attached (connect path), allocator unchanged.
             (is (= ::served
                    (remote/call!
                      (assoc (:client handle)
                             :request-timeout-ms remote/max-timing-ms)
                      :probe/op [:at-bound]))
                 "at the bound the call option is supported and its
                  deadline arithmetic is safe")
             (is (= [:fresh :at-bound] @seen)
                 "the at-bound call submitted exactly its own request")
             (let [at-bound (remote/connect-content!
                              (str "ws://127.0.0.1:" port)
                              {:connect-timeout-ms remote/max-timing-ms})]
               (is (map? at-bound)
                   "at the bound the connect option is supported too")
               (jing/close! at-bound))
             (let [before (:next-id @(:rpc (:client handle)))]
               (doseq [too-big [(inc remote/max-timing-ms)
                                Long/MAX_VALUE
                                1234567890123456789012345N]
                       :let [client' (assoc (:client handle)
                                            :request-timeout-ms too-big)]]
                 (is (thrown-with-msg?
                       Exception #"must be an integer of milliseconds"
                       (remote/call! client' :probe/op [:too-big]))
                     (str "rejected at the gate before submission: "
                          (pr-str too-big)))
                 (is (thrown-with-msg?
                       Exception #"must be an integer of milliseconds"
                       (remote/connect-content!
                         (str "ws://127.0.0.1:" port)
                         {:connect-timeout-ms too-big}))
                     (str "rejected before attach!: " (pr-str too-big))))
               (is (= before (:next-id @(:rpc (:client handle))))
                   "the allocator never moved for a rejected value")
               (is (= [:fresh :at-bound] @seen)
                   "no rejected value submitted or attached anything"))
             (finally (jing/close! handle))))
         (finally ((:stop! server)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest close-during-a-blocked-call-makes-the-call-throw-and-closes-once
  #?(:clj
     (let [latch (java.util.concurrent.CountDownLatch. 1)
           port (+ 20000 (rand-int 30000))
           server (remote/serve-content!
                    {:gated/op (fn [] (.await latch) :late)}
                    port)]
       (try
         (let [handle (remote/connect-content!
                        (str "ws://127.0.0.1:" port)
                        {:request-timeout-ms 5000})
               call (future
                      (try
                        (remote/call! (:client handle) :gated/op [])
                        ::returned
                        (catch Exception e e)))]
           ;; Let the call reach the server and stall its driver there.
           (Thread/sleep 100)
           (jing/close! handle)
           (let [result @call]
             (is (instance? Exception result)
                 "the in-flight call throws; it does not return")
             (is (= {:operation :gated/op
                     :reason :dao.stream.apply/detached}
                    (ex-data result))
                 "the boundary's own close surfaces as the terminal
                  /detached (N8, N10)"))
           (is (true? @(:closed-atom handle)) "the client reports itself closed")
           (jing/close! handle)
           (is (true? @(:closed-atom handle))
               "a second close is a no-op; the underlying close ran once (C4)")
           (.countDown latch))
         (finally ((:stop! server)))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest serve-content-refuses-a-bound-port-and-stop-is-idempotent
  #?(:clj
     (let [port (+ 20000 (rand-int 30000))
           backing (mem/create-content-mem)
           server (remote/serve-content! (remote/default-handlers backing) port)]
       (try
         (is (thrown? Exception
               (remote/serve-content! (remote/default-handlers backing) port))
             "a second server on the first's port throws (S3)")
         (is (nil? ((:stop! server))) "stop! returns")
         (is (nil? ((:stop! server))) "a second stop! is a no-op (S2)")
         (finally (jing/close! backing))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))


(deftest repeated-refused-requests-retain-nothing-and-the-client-recovers
  #?(:clj
     (with-server
       (fn [_url client]
         ;; A java.lang.Object payload is outside the portable domain:
         ;; refused at the writer, never encoded, never sent (N7).
         (let [address (jing/segment-key {:public "payload"})
               payload (Object.)
               errors
               (doall
                 (for [_n (range 3)]
                   (try
                     (remote/call! (:client client) :jing/put-content
                                   [address payload])
                     (is false "a non-portable payload must be refused")
                     nil
                     (catch Exception e
                       (let [state @(:rpc (:client client))]
                         (is (= {} (:outstanding state))
                             "no retired id stays in :outstanding (N11)")
                         (is (= [] (:completed state)))
                         (is (= [] (:diagnostics state))))
                       e))))]
           (is (= 3 (count (remove nil? errors))))
           (let [[d0 d1 d2] (mapv ex-data errors)]
             (is (= (dissoc d0 :request-id)
                    (dissoc d1 :request-id)
                    (dissoc d2 :request-id))
                 "the three refusals carry identical error information —
                  unchanged by the leak fix")
             (is (= [0 1 2] [(:request-id d0) (:request-id d1)
                             (:request-id d2)])
                 "only the request id advances, by one each time")
             (is (= :dao.stream/invalid-value (:reason d0))
                 "the refusal reason is the writer's invalid-value (N7)"))
           (let [public {:hello "world"}
                 addr (jing/materialize! client public)]
             (is (= (jing/segment-key public) addr))
             (is (= public (jing/get client addr ::miss))
                 "a portable put and get round-trip after the refusals")))))
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
            state (:dao.stream.rpc/state requested)]
        (is (= :dao.stream.rpc/requested (:dao.stream.rpc/outcome requested)))
        (is (= 0 (:dao.stream.rpc/id requested)))
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
        state (:dao.stream.rpc/state requested)]
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
                  :error {:dao.stream.apply/code :dao.stream.apply/handler-error
                          :dao.stream.apply/message "Handler failed"}}
                 (ex-data e))
              "the error map is carried under :error")))))
  ;; A terminal lifecycle on the reader, with nothing outstanding, ends the
  ;; step as terminal with the reason.
  (let [response-handle (ring-handle)
        state (ring-client (ring-handle) response-handle)]
    (stream/append! response-handle :dao.stream.apply/detached)
    (let [r (remote/call-step state 0 4)]
      (is (= :terminal (:status r)))
      (is (= :dao.stream.apply/detached (:reason r)))))
  ;; The same lifecycle with a call in flight loses that call on the ordinary
  ;; completion path; completion-value throws N9's loss with the reason.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        address (jing/segment-key {:x 1})
        state (:dao.stream.rpc/state
                (rpc/request! (ring-client request-handle response-handle)
                              :jing/get-content [address]))]
    (stream/append! response-handle :dao.stream.apply/detached)
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
                  :reason :dao.stream.apply/detached}
                 (ex-data e)))))
      (is (= [] (:completed (:state r))))
      (is (= [] (:diagnostics (:state r))))))
  ;; A response for a foreign id is consumed and discarded; the awaited call
  ;; stays pending.
  (let [request-handle (ring-handle)
        response-handle (ring-handle)
        address (jing/segment-key {:x 1})
        state (:dao.stream.rpc/state
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
        pending (:dao.stream.rpc/state
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
        state (:dao.stream.rpc/state requested)
        retired (remote/retire-call state 0 :dao.jing.remote/timeout)]
    (is (= 0 (:dao.stream.rpc/id requested)))
    (is (= {} (:outstanding retired)) "the retired id leaves :outstanding")
    (is (= 1 (:next-id retired)) ":next-id never moves back")
    (is (= [] (:completed retired)))
    ;; The response nobody awaits anymore arrives, is classified unsolicited,
    ;; and never reaches the next call.
    (stream/append! response-handle (apply/success-response 0 :stale))
    (let [next-requested (rpc/request! retired :jing/get-content [address])
          next-state (:dao.stream.rpc/state next-requested)]
      (is (= 1 (:dao.stream.rpc/id next-requested)))
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
        pending (:dao.stream.rpc/state
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
            next-state (:dao.stream.rpc/state next-requested)]
        (is (= 1 (:dao.stream.rpc/id next-requested)))
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
    (stream/append! response-handle :dao.stream.apply/established)
    (is (= :established (:status (remote/await-established-step state)))))
  ;; A bare /detached first: terminal with the reason.
  (let [response-handle (ring-handle)
        state (ring-client (ring-handle) response-handle)]
    (stream/append! response-handle :dao.stream.apply/detached)
    (let [r (remote/await-established-step state)]
      (is (= :terminal (:status r)))
      (is (= :dao.stream.apply/detached (:reason r)))))
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
      (stream/append! response-handle :dao.stream.apply/established)
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
                      (is (= :dao.stream.rpc/request-undeliverable
                             (:dao.stream.rpc/outcome result)))
                      (is (= :dao.stream/invalid-value
                             (:dao.stream.rpc/reason result))
                          "the append's refusal reason is reported every time")
                      (let [state (:dao.stream.rpc/state result)]
                        (is (= 1 (count (:completed state)))
                            "the refusal completes at once, carrying the request's args")
                        (is (= [address payload]
                               (get-in state [:completed 0 :dao.stream.rpc/args])))
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
          state (:dao.stream.rpc/state result)]
      (is (= :dao.stream.rpc/invalid-request
             (:dao.stream.rpc/outcome result)))
      (is (= {:op "not-a-keyword", :args [address payload]}
             (:dao.stream.rpc/value (first (:diagnostics state)))))
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
              state (:dao.stream.rpc/state requested)]
          (is (= 3 (:dao.stream.rpc/id requested))
              "the allocator is untouched by the refusals' drain")
          (serve-request! handlers request-handle response-handle)
          (let [done (remote/call-step state 3 8)]
            (is (= :done (:status done)))
            (is (= {:found? false, :value nil}
                   (remote/completion-value (:completion done))))))
        (finally (jing/close! store))))))

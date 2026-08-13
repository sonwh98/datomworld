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

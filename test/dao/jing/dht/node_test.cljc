(ns dao.jing.dht.node-test
  "JVM integration tests for the UDP Kademlia node: real sockets on
  localhost exchanging datagrams. The transport is JVM-only today (see
  docs/design/dao.jing.dht.md, Transport), so every test is :clj-guarded;
  the backend semantics themselves are covered cross-platform in
  dao.jing.dht-test."
  ;; :cljd nil must come FIRST (first matching branch wins and the cljd
  ;; host pass also matches :clj): otherwise the host pass sees the
  ;; deftests, registers a test namespace, and generates a Dart test
  ;; entrypoint with no main. The :namespaces rule is disabled
  ;; project-wide (see root .cljstyle) because it would otherwise flip
  ;; this order back on every `cljstyle fix`.
  #?(:cljd nil
     :clj (:require [clojure.test :refer [deftest is testing]]
                    [dao.jing :as jing]
                    [dao.jing.mem :as mem]
                    [dao.jing.dht :as dht]
                    [dao.jing.dht.node :as node]
                    [dao.stream.transit :as transit])))


#?(:cljd nil
   :clj
   (defn- with-cluster
     "Run f with n DHT content stores over real UDP nodes on localhost, the
      first node acting as the bootstrap peer for the rest. Closes
      everything."
     [n f]
     (let [opts {:host "127.0.0.1", :timeout-ms 300, :tries 2}
           head (node/create-content-dht-udp opts)
           port (:port (dht/self-peer (:net head)))
           tail (doall (repeatedly (dec n)
                                   #(node/create-content-dht-udp
                                      (assoc opts
                                             :bootstrap [{:host "127.0.0.1",
                                                          :port port}]))))
           stores (into [head] tail)]
       (try (f stores) (finally (run! jing/close! stores))))))


#?(:cljd nil
   :clj
   (deftest udp-materialize-then-get-across-nodes
     (testing
       "a payload materialized on one UDP node is fetched by another over the wire"
       (with-cluster 3
         (fn [[a _ c]]
           (let [v {:bytes [1 2 3]}
                 k (jing/materialize! a v)]
             (is (= v (jing/get c k nil)))))))))


#?(:cljd nil
   :clj
   (deftest udp-forces-fetch-over-the-wire
     (testing
       "a value materialized only into the local backend is fetched from the grid"
       (with-cluster 2
         (fn [[a b]]
           (let [v {:bytes [9]}
                 k (jing/materialize! (:local a) v)]
             (is (= v (jing/get b k ::none)))))))))


#?(:cljd nil
   :clj (deftest udp-stored-nil-forces-fetch
          (testing "nil is a legal stored payload and survives the wire fetch"
            (with-cluster 2
              (fn [[a b]]
                (let [k (jing/materialize! (:local a) nil)]
                  (is (= nil (jing/get b k ::none)))))))))


#?(:cljd nil
   :clj
   (deftest store-handler-enforces-address-and-hash
     (testing
       "an incoming :store-content must present the exact strict segment address"
       (let [handle (deref #'node/handle)
             local (mem/create-content-mem)
             table (atom {})
             v {:bytes [1]}
             hash (jing/content-hash v)
             k (jing/segment-key v)]
         (is (true? (:ok (handle local
                                 table
                                 {:op :store-content, :address k, :v v}))))
         (is (false? (:ok (handle local
                                  table
                                  {:op :store-content, :address hash, :v v})))
             "a bare hash string is not a content address")
         (is (false? (:ok (handle local
                                  table
                                  {:op :store-content,
                                   :address (keyword "root" hash),
                                   :v v})))
             "a :root key cannot be planted via :store-content")
         (is (false? (:ok (handle local
                                  table
                                  {:op :store-content,
                                   :address (jing/segment-key {:bytes [2]}),
                                   :v v})))
             "an address that does not hash to the payload is refused")
         (is (= v (jing/get local k ::miss))
             "only the exact-address write was stored")
         (is (= ::miss ((:get-content-fn local) hash ::miss)))
         (is (= ::miss
                ((:get-content-fn local) (keyword "root" hash) ::miss)))
         (is (= ::miss
                (jing/get local (jing/segment-key {:bytes [2]}) ::miss)))))))


#?(:cljd nil
   :clj (deftest root-ops-are-unsupported
          (testing "the wire knows no root or CAS ops"
            (let [handle (deref #'node/handle)
                  local (mem/create-content-mem)]
              (is (thrown? Exception
                    (handle local {} {:op :root-get, :k :root/head})))
              (is (thrown? Exception
                    (handle local
                            {}
                            {:op :root-cas,
                             :k :root/head,
                             :expected 0,
                             :v {:p "x"}})))))))


#?(:cljd nil
   :clj
   (deftest hostile-datagram-does-not-kill-the-receiver
     (testing
       "a datagram with a malformed :from is dropped; the node keeps serving"
       (with-cluster
         2
         (fn [[a b]]
           (let [port (:port (dht/self-peer (:net b)))
                 payload (.getBytes ^String
                          (transit/encode
                            {:op :ping, :rpc 0, :from {:id 42}})
                                    "UTF-8")]
             (with-open [s (java.net.DatagramSocket.)]
               (.send s
                      (java.net.DatagramPacket.
                        payload
                        (alength payload)
                        (java.net.InetAddress/getByName "127.0.0.1")
                        (int port))))
             (Thread/sleep 100)
             ;; b's receiver must survive to deliver the reply to the
             ;; forced fetch, whose value never replicated off a
             (let [v {:bytes [42]}
                   k (jing/materialize! (:local a) v)]
               (is (= v (jing/get b k nil))))))))))


#?(:cljd nil
   :clj
   (deftest rpc-failure-means-unreachable-not-thrown
     (testing
       "a host that cannot be resolved or reached yields nil/false per the
       IDhtNet contract, never an exception"
       (let [net (node/create-node
                   {:host "127.0.0.1", :timeout-ms 100, :tries 1})
             bad {:id (dht/node-id "bad" 1),
                  :host "invalid host name with spaces",
                  :port 1}]
         (try (is (nil? (dht/fetch-content net bad :segment/abc)))
              (is (nil? (dht/find-closer net bad (dht/node-id "t" 0))))
              (is (false?
                    (dht/store-content! net bad :segment/abc {:bytes [1]})))
              (finally (dht/close-net! net)))))))


#?(:cljd nil
   :clj
   (deftest udp-packet-size-variation
     (testing
       "packets of varying sizes can be received sequentially without truncation"
       (with-cluster 2
         (fn [[a b]]
           (let [v-small {:bytes [1]}
                 k-small (jing/materialize! (:local a) v-small)
                 v-large {:bytes (vec (repeat 400 2))}
                 k-large (jing/materialize! (:local a) v-large)]
             ;; 1. Small forced fetch (sets receiver packet
             ;; length to small)
             (is (= v-small (jing/get b k-small nil)))
             ;; 2. Large forced fetch (should not be
             ;; truncated)
             (is (= v-large (jing/get b k-large nil)))))))))


#?(:cljd nil
   :clj
   (deftest udp-oversized-segment-stays-local
     (testing
       "payloads beyond the datagram budget degrade to local-only until DRDS exists"
       (with-cluster
         2
         (fn [[a b]]
           (let [v {:bytes (apply str (repeat 3000 "x"))}
                 k (jing/materialize! a v)]
             (is (= v (jing/get a k nil))
                 "the local write succeeds; replication is best-effort")
             (is (= ::none (jing/get b k ::none))
                 "the payload cannot cross the wire yet")))))))


#?(:cljd nil
   :clj (deftest udp-close-is-idempotent
          (with-cluster 1
            (fn [[a]]
              (is (nil? (jing/close! a)))
              (is (nil? (jing/close! a))
                  "closing twice is safe")))))

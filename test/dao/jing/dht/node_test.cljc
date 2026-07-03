(ns dao.jing.dht.node-test
  "JVM integration tests for the UDP Kademlia node: real sockets on
  localhost exchanging datagrams. The transport is JVM-only today (see
  docs/design/dao.jing.dht.md, Transport), so every test is :clj-guarded;
  the backend semantics themselves are covered cross-platform in
  dao.jing.dht-test."
  #?(:clj
     (:require
       [clojure.test :refer [deftest is testing]]
       [dao.jing :as kv]
       [dao.jing.dht :as dht]
       [dao.jing.dht.node :as node]
       [dao.stream.transit :as transit])
     :cljd
     nil))


#?(:cljd nil
   :clj
   (defn- with-cluster
     "Run f with n KVDht stores over real UDP nodes on localhost, the first
     node acting as the bootstrap peer for the rest. Closes everything."
     [n f]
     (let [opts {:host "127.0.0.1", :timeout-ms 300, :tries 2}
           head (node/create-kv-dht-udp opts)
           port (:port (dht/self-peer (:net head)))
           tail (doall (repeatedly (dec n)
                                   #(node/create-kv-dht-udp
                                      (assoc opts
                                             :bootstrap [{:host "127.0.0.1",
                                                          :port port}]))))
           stores (into [head] tail)]
       (try (f stores) (finally (run! kv/close! stores))))))


#?(:cljd nil
   :clj (deftest udp-put-get-across-nodes
          (testing
            "a segment put! on one UDP node is fetched by another over the wire"
            (with-cluster 3
              (fn [[a _ c]]
                (let [v {:bytes [1 2 3]}
                      k (dht/segment-key v)]
                  (is (true? (kv/put! a k v)))
                  ;; drop c's replica so the read exercises the
                  ;; network path
                  (kv/delete! c k)
                  (is (= {:bytes [1 2 3], :rev 0}
                         (kv/get c k nil)))))))))


#?(:cljd nil
   :clj (deftest udp-root-cas-across-nodes
          (testing "cas! and root reads agree across UDP peers"
            (with-cluster 3
              (fn [[_ b c]]
                (is (true? (kv/cas! b :root/head 0 {:p "1"})))
                (is (= {:p "1", :rev 1} (kv/get c :root/head nil)))
                (is (false? (kv/cas! c :root/head 0 {:p "2"})))
                (is (true? (kv/cas! c :root/head 1 {:p "2"})))
                (is (= {:p "2", :rev 2}
                       (kv/get b :root/head nil))))))))


#?(:cljd nil
   :clj
   (deftest udp-oversized-segment-stays-local
     (testing
       "segments beyond the datagram budget degrade to local-only until DRDS exists"
       (with-cluster
         2
         (fn [[a b]]
           (let [v {:bytes (apply str (repeat 3000 "x"))}
                 k (dht/segment-key v)]
             (is (true? (kv/put! a k v))
                 "the local write succeeds; replication is best-effort")
             (is (= :none (kv/get b k :none))
                 "the segment cannot cross the wire yet")))))))


#?(:cljd nil
   :clj
   (deftest store-handler-enforces-key-discipline
     (testing "an incoming :store must present the exact :segment/<hash> key"
       (let [handle (deref #'node/handle)
             local (kv/create-kv-mem)
             table (atom {})
             v {:bytes [1]}
             hash (dht/content-hash v)]
         (is (true? (:ok (handle
                           local
                           table
                           {:op :store, :k (keyword "segment" hash), :v v}))))
         (is (false? (:ok (handle local table {:op :store, :k hash, :v v})))
             "a bare string key is rejected")
         (is
           (false? (:ok (handle
                          local
                          table
                          {:op :store, :k (keyword "root" hash), :v v})))
           "a :root key cannot be planted via :store; an unconditional
             put! over a cas!-managed key is the ABA hazard the design
             doc names")
         (is (= ::miss (kv/get local hash ::miss)))
         (is (= ::miss (kv/get local (keyword "root" hash) ::miss)))))))


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
             ;; b's receiver must survive to send the fetch and accept
             ;; the reply
             (let [v {:bytes [42]}
                   k (dht/segment-key v)]
               (is (true? (kv/put! a k v)))
               (kv/delete! b k)
               (is (= {:bytes [42], :rev 0} (kv/get b k nil))))))))))


#?(:cljd nil
   :clj
   (deftest rpc-failure-means-unreachable-not-thrown
     (testing
       "a host that cannot be resolved or reached yields nil per the
              IDhtNet contract, never an exception"
       (let [net (node/create-node
                   {:host "127.0.0.1", :timeout-ms 100, :tries 1})
             bad {:id (dht/node-id "bad" 1),
                  :host "invalid host name with spaces",
                  :port 1}]
         (try (is (nil? (dht/fetch-segment net bad :segment/abc)))
              (is (nil? (dht/find-closer net bad (dht/node-id "t" 0))))
              (is (nil? (dht/root-get net bad :root/r)))
              (is (nil? (dht/root-cas! net bad :root/r 0 {:p "x"})))
              (finally (dht/close-net! net)))))))


#?(:cljd nil
   :clj
   (deftest udp-segment-size-variation
     (testing
       "packets of varying sizes can be received sequentially without truncation"
       (with-cluster
         2
         (fn [[a b]]
           (let [v-small {:bytes [1]}
                 k-small (dht/segment-key v-small)
                 v-large {:bytes (vec (repeat 400 2))}
                 k-large (dht/segment-key v-large)]
             ;; 1. Small write & read (sets receiver packet length to
             ;; small)
             (is (true? (kv/put! a k-small v-small)))
             (kv/delete! b k-small)
             (is (= (assoc v-small :rev 0) (kv/get b k-small nil)))
             ;; 2. Large write & read (should not be truncated)
             (is (true? (kv/put! a k-large v-large)))
             (kv/delete! b k-large)
             (is (= (assoc v-large :rev 0) (kv/get b k-large nil)))))))))


#?(:cljd nil
   :clj (deftest udp-close-is-idempotent
          (with-cluster 1
            (fn [[a]]
              (is (nil? (kv/close! a)))
              (is (nil? (kv/close! a)) "closing twice is safe")))))

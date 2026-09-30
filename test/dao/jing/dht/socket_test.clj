(ns dao.jing.dht.socket-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.dht :as dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.stream.datagram :as datagram]
            [dao.stream.datagram.jvm :as jvm]))


(defn- bind-node
  [n]
  (let [traffic (mesh/ring)
        seam (jvm/bind! {:identity (str n) :deposit traffic
                         :bind-host "127.0.0.1" :bind-port 0 :max-bytes 1200})
        local (:dao.stream.datagram/local
                (first (filter #(= :dao.stream.datagram/bound
                                   (:dao.stream.datagram/event %))
                               (mesh/values traffic))))
        port (:dao.stream.datagram/port local)
        descriptor {:dao.stream/type :dao.stream/datagram
                    :dao.stream/identity (str n)
                    :dao.stream.datagram/bind-host "127.0.0.1"
                    :dao.stream.datagram/bind-port port}
        composition (merge (mesh/solo {::dht/id (mesh/node-id n)
                                       ::dht/publish? true})
                           {::dht/secret mesh/secret
                            ::dht/max-inbound-bytes 1048576
                            :traffic traffic
                            :datagrams (datagram/writer seam descriptor 1200)})]
    {:port port :composition composition :seam seam}))


(defn- advance
  [nodes states]
  (reduce (fn [states {:keys [port]}]
            (update states port dht/step 128)) states nodes))


(defn- await-fact
  [nodes states c address]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop [states states]
      (if (some #(and (= ::dht/sent (::dht/fact %))
                      (= address (::dht/address %))) (mesh/facts c))
        states
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 5) (recur (advance nodes states)))
          states)))))


(deftest three-real-loopback-sockets-exchange-chunked-store
  (let [nodes (mapv bind-node [1 2 3])]
    (try
      (let [ports (mapv :port nodes)
            compositions (into {} (map (fn [{:keys [port composition]}]
                                         [port (assoc composition ::dht/bootstrap
                                                      (mapv (fn [p] {:host "127.0.0.1" :port p})
                                                            (remove #{port} ports)))])
                                       nodes))
            nodes (mapv #(assoc % :composition (compositions (:port %))) nodes)
            states (into {} (map (fn [{:keys [port composition]}]
                                   [port (dht/state composition)]) nodes))
            sender (:composition (first nodes))
            value (apply str (repeat 3000 "socket"))
            address (jing/segment-key value)]
        (doseq [{:keys [composition]} nodes] (mesh/tick! composition 0))
        (mesh/request! sender {:jing/request "large" :jing/put address
                               :jing/bytes (jing/bytes->base64 (jing/canonical-bytes value))})
        (await-fact nodes states sender address)
        (is (some #(= ::dht/sent (::dht/fact %)) (mesh/facts sender)))
        (is (= value (jing/get (:local (:composition (second nodes))) address nil))))
      (finally
        (doseq [{:keys [seam]} nodes] ((:close! seam)))))))

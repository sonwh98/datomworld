(ns dao.stream.udp.jvm-test
  "The JVM host edge over a real loopback socket: the receiver thread
   hands each datagram's own bytes to the composed receive-fn, whole.
   The thread reuses one DatagramPacket across receives, so it resets
   the packet's length to the buffer's before every receive -- a
   datagram arriving after a shorter one is not truncated by it."
  (:require [clojure.test :refer [deftest is]]
            [dao.stream.udp.jvm :as udp-jvm])
  (:import (java.net DatagramPacket DatagramSocket InetAddress)))


(defn- eventually
  "Poll `pred` every 10ms until truthy or `ms` have passed; nil on
   timeout."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (> deadline (System/currentTimeMillis))
            (Thread/sleep 10)
            (recur))))))


(deftest short-datagram-then-long-datagram-arrive-whole
  (let [received (atom [])
        receiver (udp-jvm/bind! (fn [host port bytes]
                                  (swap! received conj [host port bytes]))
                                0)
        port (:local-port receiver)
        sender (DatagramSocket.)
        short-bytes (byte-array [1 2 3])
        long-bytes (byte-array (range 1 256))]
    (try
      (doseq [bytes [short-bytes long-bytes]]
        (.send sender
               (DatagramPacket. bytes (alength bytes)
                                (InetAddress/getByName "127.0.0.1")
                                (int port))))
      (is (= 2 (count (eventually #(when (= 2 (count @received))
                                     @received)
                                  5000)))
          "both datagrams arrived")
      (let [[[_ _ got-short] [_ _ got-long]] @received]
        (is (= (seq short-bytes) (seq got-short))
            "the short datagram arrived whole")
        (is (= (seq long-bytes) (seq got-long))
            "the long datagram after the short one arrived whole too,
             not truncated to its predecessor's length"))
      (finally
        ((:close! receiver))
        (.close sender)))))

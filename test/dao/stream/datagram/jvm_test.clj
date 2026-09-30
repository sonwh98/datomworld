(ns dao.stream.datagram.jvm-test
  "The JVM host seam (dao.stream.datagram.md 3) over real loopback
   DatagramSockets: the deposit writer is the seam's only channel -- bound,
   bind-failed, send-failed and closed arrive as events on the traffic
   ring, never as invocations -- and exact bytes with their observed source
   round trip through the writer handle and the ring. The receiver thread
   reuses one DatagramPacket, so it must reset the packet length before
   every receive: a datagram after a short one still arrives whole, which
   the exact-bytes case here drives."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.datagram :as datagram]
            [dao.stream.datagram.jvm :as jvm]
            [dao.stream.ringbuffer :as ringbuffer]))


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- values
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- eventually
  "Poll `pred` every 10ms until truthy or `ms` pass; nil on timeout. The
   pred's answer is tested for truthiness: a counting pred answers a
   number, a comparison answers false while it waits, and both mean what
   they say."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (> deadline (System/currentTimeMillis))
            (Thread/sleep 10)
            (recur))))))


(defn- bind!
  ([id traffic] (bind! id traffic 0 1200))
  ([id traffic port max-bytes]
   (jvm/bind! {:identity id
               :deposit traffic
               :bind-host "127.0.0.1"
               :bind-port port
               :max-bytes max-bytes})))


(defn- bound-local
  "The local address the bound event reports, or nil at the deadline."
  [traffic]
  (eventually
    (fn []
      (when-some [e (some #(when (= :dao.stream.datagram/bound
                                    (:dao.stream.datagram/event %))
                             %)
                          (values traffic))]
        (:dao.stream.datagram/local e)))
    5000))


(defn- writer-for
  "The composition's second move: the writer over the seam and the complete
   descriptor the bound event reported."
  [seam id local]
  (datagram/writer seam
                   {:dao.stream/type :dao.stream/datagram
                    :dao.stream/identity id
                    :dao.stream.datagram/bind-host
                    (:dao.stream.datagram/host local)
                    :dao.stream.datagram/bind-port
                    (:dao.stream.datagram/port local)}
                   1200))


(defn- outbound
  [host port octets]
  {:dao.stream.datagram/destination {:dao.stream.datagram/host host
                                     :dao.stream.datagram/port port}
   :dao.stream.datagram/bytes (base64/encode
                                (byte-array (mapv unchecked-byte octets)))})


(defn- received-datagrams
  [traffic]
  (filter datagram/datagram-event? (values traffic)))


(defn- payload-of
  "One received datagram event's decoded payload as unsigned octets."
  [event]
  (mapv (fn [b] (bit-and 0xff (int b)))
        (seq (base64/decode (:dao.stream.datagram/bytes event)))))


(defn- octets-of
  "Expected octets as the wire wraps them: a datagram is bytes, so an
   octet over 255 rides its low 8 bits."
  [octets]
  (mapv #(mod (int %) 256) octets))


(deftest bound-is-deposited-with-the-actual-local-address
  (let [traffic (ring 16)
        seam (bind! "s1" traffic)
        local (bound-local traffic)]
    (is (some? local) "no bound event within the deadline")
    (is (= "127.0.0.1" (:dao.stream.datagram/host local)))
    (is (pos? (:dao.stream.datagram/port local))
        "the actual port, never 0, though 0 was asked for")
    ((:close! seam))))


(deftest exact-bytes-and-observed-source-round-trip
  (let [traffic-a (ring 16)
        traffic-b (ring 64)
        seam-a (bind! "a" traffic-a)
        seam-b (bind! "b" traffic-b)
        local-a (bound-local traffic-a)
        local-b (bound-local traffic-b)
        writer-a (writer-for seam-a "a" local-a)
        payloads [[1 2 3]
                  (range 1 256)
                  (range 1200)
                  []]
        send! (fn [octets]
                (stream/append!
                  writer-a
                  (outbound "127.0.0.1"
                            (:dao.stream.datagram/port local-b) octets)))]
    (try
      (doseq [octets payloads]
        (is (= {:dao.stream/outcome :dao.stream/ok} (send! octets))))
      (is (eventually (fn []
                        (= (count payloads)
                           (count (received-datagrams traffic-b))))
                      5000)
          "every datagram arrived as one event")
      (let [events (received-datagrams traffic-b)]
        (doseq [[octets event] (map vector payloads events)]
          (is (= (:dao.stream.datagram/host local-a)
                 (get-in event [:dao.stream.datagram/source
                                :dao.stream.datagram/host]))
              "the source host the socket observed")
          (is (= (:dao.stream.datagram/port local-a)
                 (get-in event [:dao.stream.datagram/source
                                :dao.stream.datagram/port]))
              "the source port the socket observed")
          (is (= (octets-of octets) (payload-of event))
              (str "exact payload bytes, " (count octets) " octets"))))
      (finally
        (stream/close! writer-a)
        ((:close! seam-a))
        ((:close! seam-b))))))


(deftest invalid-and-closed-sends-refuse-with-no-send
  (let [traffic-a (ring 16)
        traffic-b (ring 64)
        seam-a (bind! "a" traffic-a)
        seam-b (bind! "b" traffic-b)
        local-a (bound-local traffic-a)
        local-b (bound-local traffic-b)
        writer-a (writer-for seam-a "a" local-a)
        dest-port (:dao.stream.datagram/port local-b)]
    (try
      (testing "hostname destination, bad Base64 and oversize are
                invalid-value, and nothing is sent"
        (is (= :dao.stream/invalid-value
               (:dao.stream/outcome
                 (stream/append! writer-a (outbound "localhost" dest-port
                                                    [1 2 3])))))
        (is (= :dao.stream/invalid-value
               (:dao.stream/outcome
                 (stream/append! writer-a
                                 {:dao.stream.datagram/destination
                                  {:dao.stream.datagram/host "127.0.0.1"
                                   :dao.stream.datagram/port dest-port}
                                  :dao.stream.datagram/bytes "not base64!"}))))
        (is (= :dao.stream/invalid-value
               (:dao.stream/outcome
                 (stream/append! writer-a (outbound "127.0.0.1" dest-port
                                                    (range 1201)))))))
      (testing "one valid canary datagram then arrives, and only it"
        (is (= {:dao.stream/outcome :dao.stream/ok}
               (stream/append! writer-a (outbound "127.0.0.1" dest-port
                                                  [9 9 9]))))
        (is (eventually (fn [] (= 1 (count (received-datagrams traffic-b))))
                        5000))
        (is (= [[9 9 9]] (mapv payload-of (received-datagrams traffic-b)))))
      (testing "a closed socket is closed, and the seam deposits closed"
        (is (= {:dao.stream/outcome :dao.stream/ok} (stream/close! writer-a)))
        (is (= {:dao.stream/outcome :dao.stream/closed}
               (stream/append! writer-a (outbound "127.0.0.1" dest-port
                                                  [1]))))
        (is (eventually (fn []
                          (some #(= :dao.stream.datagram/closed
                                    (:dao.stream.datagram/event %))
                                (values traffic-a)))
                        5000))
        (is (= 1 (count (received-datagrams traffic-b)))
            "the closed send added nothing"))
      (finally
        ((:close! seam-a))
        ((:close! seam-b))))))


(deftest send-failure-is-a-send-failed-event
  (let [traffic (ring 16)
        seam (bind! "s" traffic)
        local (bound-local traffic)]
    (is (some? local))
    ((:close! seam))
    (let [r ((:send! seam) "127.0.0.1" 4100 (byte-array 8))]
      (is (= :dao.stream/transport-error (:dao.stream/outcome r))
          "the host send failed synchronously, cleanly")
      (is (string? (:dao.stream.datagram/reason r))))
    (is (eventually (fn []
                      (some #(= :dao.stream.datagram/send-failed
                                (:dao.stream.datagram/event %))
                            (values traffic)))
                    5000)
        "the failed send is a lifecycle event on the traffic ring: the
         deposit writer is the seam's only channel, no function was
         invoked")))


(deftest live-socket-send-failure-preserves-canary
  (let [traffic (ring 16)
        peer-traffic (ring 16)
        seam (bind! "s" traffic)
        peer (bind! "peer" peer-traffic)
        port (:dao.stream.datagram/port (bound-local peer-traffic))]
    (try
      (let [r ((:send! seam) "127.0.0.1" 65536 (byte-array [1]))]
        (is (= :dao.stream/transport-error (:dao.stream/outcome r)))
        (is (eventually #(some (fn [v]
                                 (= :dao.stream.datagram/send-failed
                                    (:dao.stream.datagram/event v)))
                               (values traffic)) 5000)))
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               ((:send! seam) "127.0.0.1" port (byte-array [42])))))
      (is (eventually #(some (fn [v]
                               (= [42]
                                  (mapv (fn [b] (bit-and 255 b))
                                        (base64/decode
                                          (:dao.stream.datagram/bytes v)))))
                             (received-datagrams peer-traffic)) 5000))
      (finally ((:close! seam)) ((:close! peer))))))


(deftest ip-family-mismatch-is-refused-before-host-send
  (let [traffic (ring 16)
        peer-traffic (ring 16)
        seam (bind! "s" traffic)
        peer (bind! "peer" peer-traffic)
        port (:dao.stream.datagram/port (bound-local peer-traffic))]
    (try
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome
               ((:send! seam) "::1" port (byte-array [1])))))
      (is (some #(= :dao.stream.datagram/send-failed
                    (:dao.stream.datagram/event %))
                (values traffic)))
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               ((:send! seam) "127.0.0.1" port (byte-array [42])))))
      (is (eventually #(some (fn [v] (= "Kg==" (:dao.stream.datagram/bytes v)))
                             (received-datagrams peer-traffic)) 5000))
      (finally ((:close! seam)) ((:close! peer))))))


(deftest wildcard-bound-socket-refuses-ip-family-mismatch
  (let [traffic (ring 16)
        peer-traffic (ring 16)
        seam (jvm/bind! {:identity "wildcard"
                         :deposit traffic
                         :bind-host "0.0.0.0"
                         :bind-port 0
                         :max-bytes 1200})
        peer (bind! "peer" peer-traffic)
        port (:dao.stream.datagram/port (bound-local peer-traffic))]
    (try
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome
               ((:send! seam) "::1" port (byte-array [1])))))
      (is (some #(= :dao.stream.datagram/send-failed
                    (:dao.stream.datagram/event %))
                (values traffic)))
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               ((:send! seam) "127.0.0.1" port (byte-array [42])))))
      (is (eventually #(some (fn [v] (= "Kg==" (:dao.stream.datagram/bytes v)))
                             (received-datagrams peer-traffic)) 5000))
      (finally ((:close! seam)) ((:close! peer))))))


(deftest bind-failure-is-deposited-and-there-is-no-writer
  (let [holder-traffic (ring 8)
        holder (bind! "holder" holder-traffic)
        local (bound-local holder-traffic)]
    (is (some? local))
    (let [traffic (ring 8)
          seam (bind! "loser" traffic (:dao.stream.datagram/port local) 1200)]
      (is (eventually (fn []
                        (some #(= :dao.stream.datagram/bind-failed
                                  (:dao.stream.datagram/event %))
                              (values traffic)))
                      5000)
          "the second bind on the taken port deposited bind-failed")
      (is (every? string? (keep :dao.stream.datagram/reason (values traffic)))
          "the failure carries text")
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome
               ((:send! seam) "127.0.0.1" 4100 (byte-array 4))))
          "a seam that never bound has no working send")
      ((:close! seam)))
    ((:close! holder))))


(deftest inbound-oversize-is-never-deposited
  (let [traffic-a (ring 16)
        traffic-b (ring 16)
        seam-a (bind! "a" traffic-a)
        ;; the receiving socket admits at most 64 bytes of payload
        seam-b (bind! "b" traffic-b 0 64)
        local-a (bound-local traffic-a)
        local-b (bound-local traffic-b)
        writer-a (writer-for seam-a "a" local-a)]
    (try
      (stream/append! writer-a (outbound "127.0.0.1"
                                         (:dao.stream.datagram/port local-b)
                                         (range 100)))
      (stream/append! writer-a (outbound "127.0.0.1"
                                         (:dao.stream.datagram/port local-b)
                                         [7 7]))
      (is (eventually (fn [] (= 1 (count (received-datagrams traffic-b))))
                      5000)
          "only the small datagram became an event")
      (is (= [[7 7]] (mapv payload-of (received-datagrams traffic-b)))
          "the oversize datagram was dropped below the transform, counted,
           never deposited; the socket itself still works")
      (finally
        (stream/close! writer-a)
        ((:close! seam-a))
        ((:close! seam-b))))))


(deftest slow-reader-gaps-on-the-traffic-ring
  (let [traffic-a (ring 16)
        traffic-b (ring 4)
        seam-a (bind! "a" traffic-a)
        seam-b (bind! "b" traffic-b)
        local-a (bound-local traffic-a)
        local-b (bound-local traffic-b)
        writer-a (writer-for seam-a "a" local-a)
        origin (:dao.stream/cursor (stream/cursor traffic-b
                                                  stream/anchor-oldest))
        dest-port (:dao.stream.datagram/port local-b)]
    (try
      (dotimes [i 10]
        (stream/append! writer-a (outbound "127.0.0.1" dest-port [i])))
      (is (eventually (fn []
                        (when-some [newest (last (received-datagrams
                                                   traffic-b))]
                          (= [9] (payload-of newest))))
                      5000)
          "the last datagram landed; capacity 4 evicted the earlier seven
           events and the bound event with them")
      (let [gapped (stream/next traffic-b origin)]
        (is (= :dao.stream/gap (:dao.stream/outcome gapped))
            "the slow reader holding the origin cursor is told it missed
             datagrams")
        (loop [c (:dao.stream/cursor gapped)
               seen []]
          (let [r (stream/next traffic-b c)]
            (if (= :dao.stream/ok (:dao.stream/outcome r))
              (recur (:dao.stream/cursor r)
                     (conj seen (payload-of (:dao.stream/value r))))
              (is (= [[6] [7] [8] [9]] seen)
                  "the recovery cursor is the earliest retained position,
                   and the retained suffix reads whole")))))
      (finally
        (stream/close! writer-a)
        ((:close! seam-a))
        ((:close! seam-b))))))

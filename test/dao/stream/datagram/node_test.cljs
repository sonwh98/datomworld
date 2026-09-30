(ns dao.stream.datagram.node-test
  "The Node host seam (dao.stream.datagram.md 3) over real loopback dgram
   sockets: the deposit writer is the seam's only channel -- bound,
   bind-failed, send-failed and closed arrive as events on the traffic
   ring, never as invocations -- and exact bytes with their observed source
   round trip through the writer handle and the ring."

  (:require [cljs.test :refer [async deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.datagram :as datagram]
            [dao.stream.datagram.node :as node]
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


(defn- wait-for
  "Poll `pred` every 10ms until it returns truthy or `deadline-ms` passes.
   The callback receives the value or nil, exactly once. The pred's answer
   is tested for truthiness: a comparison pred answers false while it
   waits, and false means keep polling."
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


(defn- bind!
  ([id traffic] (bind! id traffic 0 1200))
  ([id traffic port max-bytes]
   (node/bind! {:identity id
                :deposit traffic
                :bind-host "127.0.0.1"
                :bind-port port
                :max-bytes max-bytes})))


(defn- bound-of
  "The bound event on `traffic`, or nil while the host has not answered."
  [traffic]
  (some (fn [v]
          (when (= :dao.stream.datagram/bound
                   (:dao.stream.datagram/event v))
            v))
        (values traffic)))


(defn- writer-for
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
                                (js/Uint8Array.from (to-array octets)))})


(defn- received-datagrams
  [traffic]
  (filter datagram/datagram-event? (values traffic)))


(defn- payload-of
  [event]
  (mapv (fn [b] (bit-and 0xff (int b)))
        (seq (base64/decode (:dao.stream.datagram/bytes event)))))


(defn- octets-of
  "Expected octets as the wire wraps them: a datagram is bytes, so an
   octet over 255 rides its low 8 bits."
  [octets]
  (mapv (fn [o] (mod (int o) 256)) octets))


(defn- close-all!
  [seams finish]
  (doseq [s seams]
    (try ((:close! s)) (catch :default _ nil)))
  (finish))


(defn- after-bounds
  "Wait for both sockets' bound events, then (step [local-a local-b])."
  [traffic-a traffic-b deadline fail step]
  (wait-for (fn [] (and (bound-of traffic-a) (bound-of traffic-b)))
            deadline
            (fn [both]
              (if both
                (step [(:dao.stream.datagram/local (bound-of traffic-a))
                       (:dao.stream.datagram/local (bound-of traffic-b))])
                (do (is false "a socket never reported bound")
                    (fail))))))


(deftest bound-then-exact-bytes-and-observed-source-round-trip
  (async done
         (let [finish (finish-once done)
               traffic-a (ring 16)
               traffic-b (ring 64)
               seam-a (bind! "a" traffic-a)
               seam-b (bind! "b" traffic-b)]
           (after-bounds traffic-a traffic-b 5000 finish
                         (fn [[local-a local-b]]
                           (is (= "127.0.0.1" (:dao.stream.datagram/host local-a)))
                           (is (pos? (:dao.stream.datagram/port local-a))
                               "the actual ephemeral port, never 0, though 0 was asked for")
                           (let [writer-a (writer-for seam-a "a" local-a)
                                 dest (:dao.stream.datagram/port local-b)
                                 payloads [[1 2 3] (range 1 256) (range 1200) []]]
                             (doseq [octets payloads]
                               (is (= {:dao.stream/outcome :dao.stream/ok}
                                      (stream/append! writer-a
                                                      (outbound "127.0.0.1" dest octets)))))
                             (wait-for (fn []
                                         (= (count payloads)
                                            (count (received-datagrams traffic-b))))
                                       5000
                                       (fn [arrived]
                                         (if-not arrived
                                           (is false "not every datagram arrived as an event")
                                           (doseq [[octets event]
                                                   (map vector payloads
                                                        (received-datagrams traffic-b))]
                                             (is (= "127.0.0.1"
                                                    (get-in event
                                                            [:dao.stream.datagram/source
                                                             :dao.stream.datagram/host]))
                                                 "the source host the socket observed")
                                             (is (= (:dao.stream.datagram/port local-a)
                                                    (get-in event
                                                            [:dao.stream.datagram/source
                                                             :dao.stream.datagram/port]))
                                                 "the source port the socket observed")
                                             (is (= (octets-of octets) (payload-of event))
                                                 (str "exact payload bytes, "
                                                      (count octets) " octets"))))
                                         (close-all! [seam-a seam-b] finish)))))))))


(deftest invalid-sends-refuse-with-no-send-and-closed-is-closed
  (async done
         (let [finish (finish-once done)
               traffic-a (ring 16)
               traffic-b (ring 64)
               seam-a (bind! "a" traffic-a)
               seam-b (bind! "b" traffic-b)]
           (after-bounds traffic-a traffic-b 5000 finish
                         (fn [[local-a local-b]]
                           (let [writer-a (writer-for seam-a "a" local-a)
                                 dest (:dao.stream.datagram/port local-b)]
                             (testing "hostname destination, bad Base64 and oversize are
                      invalid-value, and nothing is sent"
                               (is (= :dao.stream/invalid-value
                                      (:dao.stream/outcome
                                        (stream/append! writer-a
                                                        (outbound "localhost" dest [1 2 3])))))
                               (is (= :dao.stream/invalid-value
                                      (:dao.stream/outcome
                                        (stream/append!
                                          writer-a
                                          {:dao.stream.datagram/destination
                                           {:dao.stream.datagram/host "127.0.0.1"
                                            :dao.stream.datagram/port dest}
                                           :dao.stream.datagram/bytes "not base64!"}))))
                               (is (= :dao.stream/invalid-value
                                      (:dao.stream/outcome
                                        (stream/append! writer-a
                                                        (outbound "127.0.0.1" dest
                                                                  (range 1201)))))))
                             ;; one valid canary: the refusals sent nothing, so it is the
                             ;; one and only datagram event that ever lands
                             (stream/append! writer-a (outbound "127.0.0.1" dest [9 9 9]))
                             (wait-for (fn [] (= 1 (count (received-datagrams traffic-b))))
                                       5000
                                       (fn [arrived]
                                         (if-not arrived
                                           (is false "the canary never arrived")
                                           (is (= [[9 9 9]]
                                                  (mapv payload-of
                                                        (received-datagrams traffic-b)))))
                                         (testing "a closed socket is closed; the seam
                                  deposits closed"
                                           (is (= {:dao.stream/outcome :dao.stream/ok}
                                                  (stream/close! writer-a)))
                                           (is (= {:dao.stream/outcome :dao.stream/closed}
                                                  (stream/append!
                                                    writer-a
                                                    (outbound "127.0.0.1" dest [1])))))
                                         (wait-for (fn []
                                                     (some
                                                       (fn [v]
                                                         (= :dao.stream.datagram/closed
                                                            (:dao.stream.datagram/event v)))
                                                       (values traffic-a)))
                                                   5000
                                                   (fn [closed]
                                                     (is closed "no closed event deposited")
                                                     (is (= 1
                                                            (count
                                                              (received-datagrams traffic-b)))
                                                         "the closed send added nothing")
                                                     (close-all! [seam-a seam-b]
                                                                 finish)))))))))))


(deftest send-failure-is-a-send-failed-event
  (async done
         (let [finish (finish-once done)
               traffic (ring 16)
               seam (bind! "s" traffic)]
           (wait-for (fn [] (bound-of traffic))
                     5000
                     (fn [bound]
                       (if-not bound
                         (do (is false "the socket never reported bound")
                             (finish))
                         (do ((:close! seam))
                             (let [r ((:send! seam) "127.0.0.1" 4100
                                                    (js/Uint8Array.from (to-array [1 2 3 4])))]
                               (is (= :dao.stream/transport-error
                                      (:dao.stream/outcome r))
                                   "the host send failed, cleanly")
                               (is (string? (:dao.stream.datagram/reason r))))
                             (wait-for (fn []
                                         (some
                                           (fn [v]
                                             (= :dao.stream.datagram/send-failed
                                                (:dao.stream.datagram/event v)))
                                           (values traffic)))
                                       5000
                                       (fn [failed]
                                         (is failed
                                             "no send-failed event deposited: the
                                         deposit writer is the seam's only
                                         channel, no function was invoked")
                                         (close-all! [seam] finish))))))))))


(deftest live-socket-send-failure-preserves-canary
  (async done
         (let [finish (finish-once done)
               traffic (ring 16)
               peer-traffic (ring 16)
               seam (bind! "s" traffic)
               peer (bind! "peer" peer-traffic)]
           (wait-for #(and (bound-of traffic) (bound-of peer-traffic))
                     5000
                     (fn [ready]
                       (if-not ready
                         (do (is false "sockets did not bind")
                             (close-all! [seam peer] finish))
                         (let [port (-> (bound-of peer-traffic)
                                        :dao.stream.datagram/local
                                        :dao.stream.datagram/port)
                               r ((:send! seam) "127.0.0.1" 0
                                                (js/Uint8Array.from #js [1]))]
                           (is (= :dao.stream/transport-error
                                  (:dao.stream/outcome r)))
                           (wait-for #(some (fn [v]
                                              (= :dao.stream.datagram/send-failed
                                                 (:dao.stream.datagram/event v)))
                                            (values traffic))
                                     5000
                                     (fn [failed]
                                       (is failed)
                                       (is (= :dao.stream/ok
                                              (:dao.stream/outcome
                                                ((:send! seam) "127.0.0.1" port
                                                               (js/Uint8Array.from #js [42])))))
                                       (wait-for #(some (fn [v]
                                                          (= "Kg==" (:dao.stream.datagram/bytes v)))
                                                        (received-datagrams peer-traffic))
                                                 5000
                                                 (fn [canary]
                                                   (is canary)
                                                   (close-all! [seam peer] finish))))))))))))


(deftest ip-family-mismatch-is-refused-before-host-send
  (async done
         (let [finish (finish-once done)
               traffic (ring 16)
               peer-traffic (ring 16)
               seam (bind! "s" traffic)
               peer (bind! "peer" peer-traffic)]
           (wait-for #(and (bound-of traffic) (bound-of peer-traffic))
                     5000
                     (fn [ready]
                       (if-not ready
                         (do (is false "sockets did not bind")
                             (close-all! [seam peer] finish))
                         (let [port (-> (bound-of peer-traffic)
                                        :dao.stream.datagram/local
                                        :dao.stream.datagram/port)]
                           (is (= :dao.stream/transport-error
                                  (:dao.stream/outcome
                                    ((:send! seam) "::1" port
                                                   (js/Uint8Array.from #js [1])))))
                           (is (some #(= :dao.stream.datagram/send-failed
                                         (:dao.stream.datagram/event %))
                                     (values traffic)))
                           (is (= :dao.stream/ok
                                  (:dao.stream/outcome
                                    ((:send! seam) "127.0.0.1" port
                                                   (js/Uint8Array.from #js [42])))))
                           (wait-for #(some (fn [v]
                                              (= "Kg==" (:dao.stream.datagram/bytes v)))
                                            (received-datagrams peer-traffic))
                                     5000
                                     (fn [canary]
                                       (is canary)
                                       (close-all! [seam peer] finish))))))))))


(deftest bind-on-a-taken-port-deposits-bind-failed
  (async done
         (let [finish (finish-once done)
               traffic-a (ring 8)
               seam-a (bind! "holder" traffic-a)]
           (wait-for (fn [] (bound-of traffic-a))
                     5000
                     (fn [bound]
                       (if-not bound
                         (do (is false "the holder never reported bound")
                             (close-all! [seam-a] finish))
                         (let [dest (:dao.stream.datagram/port
                                      (:dao.stream.datagram/local bound))
                               traffic-c (ring 8)
                               seam-c (bind! "loser" traffic-c dest 1200)]
                           (wait-for (fn []
                                       (some
                                         (fn [v]
                                           (= :dao.stream.datagram/bind-failed
                                              (:dao.stream.datagram/event v)))
                                         (values traffic-c)))
                                     5000
                                     (fn [failed]
                                       (is failed "no bind-failed event deposited")
                                       (is (every?
                                             string?
                                             (keep :dao.stream.datagram/reason
                                                   (values traffic-c)))
                                           "the failure carries text")
                                       (is (= :dao.stream/transport-error
                                              (:dao.stream/outcome
                                                ((:send! seam-c)
                                                 "127.0.0.1" 4100
                                                 (js/Uint8Array.from
                                                   (to-array [1])))))
                                           "a seam that never bound has no
                                       working send")
                                       (close-all! [seam-a seam-c]
                                                   finish))))))))))


(deftest inbound-oversize-is-never-deposited
  (async done
         (let [finish (finish-once done)
               traffic-a (ring 16)
               traffic-b (ring 16)
               seam-a (bind! "a" traffic-a)
               ;; the receiving socket admits at most 64 bytes of payload
               seam-b (bind! "b" traffic-b 0 64)]
           (after-bounds traffic-a traffic-b 5000 finish
                         (fn [[local-a local-b]]
                           (let [writer-a (writer-for seam-a "a" local-a)
                                 dest (:dao.stream.datagram/port local-b)]
                             (stream/append! writer-a (outbound "127.0.0.1" dest (range 100)))
                             (stream/append! writer-a (outbound "127.0.0.1" dest [7 7]))
                             (wait-for (fn [] (= 1 (count (received-datagrams traffic-b))))
                                       5000
                                       (fn [arrived]
                                         (if-not arrived
                                           (is false "the small datagram never arrived")
                                           (is (= [[7 7]]
                                                  (mapv payload-of
                                                        (received-datagrams traffic-b)))
                                               "the oversize datagram was dropped below the
                               transform, counted, never deposited"))
                                         (close-all! [seam-a seam-b] finish)))))))))


(deftest slow-reader-gaps-on-the-traffic-ring
  (async done
         (let [finish (finish-once done)
               traffic-a (ring 16)
               traffic-b (ring 4)
               seam-a (bind! "a" traffic-a)
               seam-b (bind! "b" traffic-b)]
           (after-bounds traffic-a traffic-b 5000 finish
                         (fn [[local-a local-b]]
                           (let [writer-a (writer-for seam-a "a" local-a)
                                 dest (:dao.stream.datagram/port local-b)
                                 origin (:dao.stream/cursor
                                          (stream/cursor traffic-b stream/anchor-oldest))]
                             (dotimes [i 10]
                               (stream/append! writer-a (outbound "127.0.0.1" dest [i])))
                             (wait-for (fn []
                                         (let [newest (last (received-datagrams traffic-b))]
                                           (when newest
                                             (= [9] (payload-of newest)))))
                                       5000
                                       (fn [landed]
                                         (if-not landed
                                           (is false "the last datagram never landed")
                                           (let [gapped (stream/next traffic-b origin)]
                                             (is (= :dao.stream/gap
                                                    (:dao.stream/outcome gapped))
                                                 "the slow reader holding the origin cursor
                                 is told it missed datagrams")
                                             (loop [c (:dao.stream/cursor gapped)
                                                    seen []]
                                               (let [r (stream/next traffic-b c)]
                                                 (if (= :dao.stream/ok (:dao.stream/outcome r))
                                                   (recur (:dao.stream/cursor r)
                                                          (conj seen
                                                                (payload-of
                                                                  (:dao.stream/value r))))
                                                   (is (= [[6] [7] [8] [9]] seen)
                                                       "the recovery cursor is the earliest
                                       retained position, and the retained
                                       suffix reads whole"))))))
                                         (close-all! [seam-a seam-b] finish)))))))))

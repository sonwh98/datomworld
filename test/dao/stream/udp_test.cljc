(ns dao.stream.udp-test
  "Portable, host-free protocol tests for dao.stream.udp
   (docs/design/dao.stream.remote.md 3.2): fragmentation and
   reassembly round trip; loss of any part loses the message,
   recovered by resend; oversize refuses transport-error naming
   oversize, unsent; reassembly eviction, oldest partial message
   first; the 48 KiB toy inside the default 64 KiB maximum, and a
   near-limit message delivered on its actual accumulated length;
   malformed fragment envelopes dropped before any partial state
   changes; the deposited event carries the datagram's own source
   address; and keying by [attachment, direction, id] -- two
   attachments, equal ids, no cross-delivery -- including the
   projection that routes the shared traffic medium onto each
   attachment's own channel ring."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.udp :as udp]))


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- spy
  "A :send! seam recording every send as [host port bytes], touching
   no real socket."
  []
  (let [log (atom [])]
    {:log log
     :send! (fn [host port bytes] (swap! log conj [host port bytes]))}))


(defn- values
  "Every retained value of `h`, oldest first."
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- deposited
  "Every deposit event's own carried :dao.stream.udp/value, oldest
   first -- what a projection would forward onto a channel ring."
  [traffic]
  (mapv :dao.stream.udp/value (values traffic)))


(defn- big-string
  [n]
  (apply str (repeat n \a)))


(defn- request
  [identity id v]
  {:dao.stream/identity identity
   :dao.stream.remote/op :dao.stream/append!
   :dao.stream.remote/args [v]
   :dao.stream.remote/id id})


;; =============================================================================
;; descriptor? and address-key
;; =============================================================================

(deftest descriptor-gate
  (is (udp/descriptor? {:dao.stream/type :dao.stream/udp
                        :dao.stream/identity "peer-a"
                        :dao.stream.udp/host "127.0.0.1"
                        :dao.stream.udp/port 9000}))
  (is (not (udp/descriptor? {:dao.stream/type :dao.stream/udp
                             :dao.stream/identity "peer-a"
                             :dao.stream.udp/host "127.0.0.1"
                             :dao.stream.udp/port 0})))
  (is (not (udp/descriptor? {:dao.stream/type :dao.stream/ws
                             :dao.stream/identity "peer-a"
                             :dao.stream.udp/host "h"
                             :dao.stream.udp/port 1}))))


(deftest address-key-format
  (is (= "127.0.0.1:9000" (udp/address-key "127.0.0.1" 9000))))


;; =============================================================================
;; Fragmentation and reassembly round trip
;; =============================================================================

(deftest fragmentation-round-trip
  (testing "a value over the datagram budget arrives whole"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port (udp/make-port {:send! send! :traffic traffic})
          v (request "s" 1 (big-string 4000))
          sent (do (udp/send-value! port "peer" 9000 v)
                   @log)
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic})]
      (is (> (count sent) 1) "the value did not fit one datagram")
      (doseq [[_ _ bytes] sent]
        (udp/receive! receiver "peer" 9000 bytes))
      (is (= [v] (deposited traffic))))))


(deftest fragmentation-round-trip-48kib
  (testing "the 48 KiB toy, inside the default 64 KiB maximum"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port (udp/make-port {:send! send! :traffic traffic})
          v (request "s" 2 (big-string (* 48 1024)))
          sent (do (udp/send-value! port "peer" 9000 v)
                   @log)
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic})]
      (doseq [[_ _ bytes] sent]
        (udp/receive! receiver "peer" 9000 bytes))
      (is (= [v] (deposited traffic))))))


(deftest fragmentation-round-trip-near-limit
  (testing "a message near the default maximum delivers on its actual
            accumulated length"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port (udp/make-port {:send! send! :traffic traffic})
          v (request "s" 22 (big-string 64500))
          sent (do (udp/send-value! port "peer" 9000 v)
                   @log)
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic})]
      (is (> (count sent) 54)
          "declared parts times the datagram budget crosses 64 KiB, so
           a bound on the claim, not the bytes, would have dropped it")
      (doseq [[_ _ bytes] sent]
        (udp/receive! receiver "peer" 9000 bytes))
      (is (= [v] (deposited traffic))))))


;; =============================================================================
;; Malformed fragment envelopes drop, before any partial state changes
;; =============================================================================

(defn- raw-fragment
  "One raw fragment envelope, `overrides` merged over a well-formed
   two-part shape -- whatever a peer might put on the wire."
  [overrides]
  (merge {:dao.stream.remote/id 7
          :dao.stream.udp/part 0
          :dao.stream.udp/parts 2
          :dao.stream.udp/direction :dao.stream.udp/request
          :dao.stream.udp/bytes (cbor/encode "x")}
         overrides))


(deftest malformed-fragments-drop-before-partial-state
  (testing "a fragment failing its own field checks is dropped: no
            throw, no deposit, no partial state"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          sender (udp/make-port {:send! send! :traffic traffic})
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic})
          malformed [(raw-fragment {:dao.stream.udp/parts 0})
                     (raw-fragment {:dao.stream.udp/part -1})
                     (raw-fragment {:dao.stream.udp/part 2})
                     (raw-fragment {:dao.stream.udp/part "0"})
                     (raw-fragment {:dao.stream.udp/parts nil})
                     (raw-fragment {:dao.stream.udp/direction
                                    :dao.stream.udp/sideways})
                     (raw-fragment {:dao.stream.udp/bytes "not bytes"})
                     (dissoc (raw-fragment {})
                             :dao.stream.udp/part)]
          v (request "s" 7 (big-string 4000))
          sent (do (udp/send-value! sender "peer" 9000 v)
                   @log)]
      (doseq [frag malformed]
        (is (nil? (udp/receive! receiver "peer" 9000 (cbor/encode frag)))
            "the malformed datagram is dropped, not thrown"))
      (is (= {} (:partial @receiver))
          "not one malformed fragment grew partial state")
      (is (= [] (deposited traffic)) "and none deposited")
      (testing "a real message interleaved with the malformed set
                completes untouched by its neighbors"
        (doseq [[_ _ bytes] (butlast sent)]
          (udp/receive! receiver "peer" 9000 bytes))
        (doseq [frag malformed]
          (udp/receive! receiver "peer" 9000 (cbor/encode frag)))
        (udp/receive! receiver "peer" 9000 (last (last sent)))
        (is (= [v] (deposited traffic)))))))


;; =============================================================================
;; Loss of any part loses the message, recovered by resend
;; =============================================================================

(deftest loss-of-any-part-loses-the-message
  (testing "dropping one datagram loses the whole message"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port (udp/make-port {:send! send! :traffic traffic})
          v (request "s" 3 (big-string 4000))
          sent (do (udp/send-value! port "peer" 9000 v)
                   @log)
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic})
          dropped (rest sent)]
      (is (> (count sent) 1))
      (doseq [[_ _ bytes] dropped]
        (udp/receive! receiver "peer" 9000 bytes))
      (is (= [] (deposited traffic))
          "the message never completes without every part")
      (testing "the link's resend rule recovers it: re-send every part"
        (doseq [[_ _ bytes] sent]
          (udp/receive! receiver "peer" 9000 bytes))
        (is (= [v] (deposited traffic)))))))


;; =============================================================================
;; Oversize refuses, unsent
;; =============================================================================

(deftest oversize-refuses-unsent
  (testing "a message beyond max-message-bytes is never sent"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port (udp/make-port {:send! send! :traffic traffic
                               :max-message-bytes 1000})
          v (request "s" 4 (big-string 4000))
          result (udp/send-value! port "peer" 9000 v)]
      (is (= {:dao.stream/outcome :dao.stream/transport-error
              :dao.stream.remote/reason :dao.stream.remote/oversize}
             result))
      (is (= [] @log) "nothing was sent, torn or partially sent"))))


;; =============================================================================
;; Reassembly eviction: max-partial-messages, oldest evicted
;; =============================================================================

(deftest reassembly-eviction-evicts-oldest
  (testing "the oldest partial message is evicted over the bound"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port (udp/make-port {:send! send! :traffic traffic
                               :max-partial-messages 2})
          v1 (request "s" 10 (big-string 4000))
          v2 (request "s" 11 (big-string 4000))
          v3 (request "s" 12 (big-string 4000))
          sent! (fn [v]
                  (reset! log [])
                  (udp/send-value! port "peer" 9000 v)
                  @log)
          s1 (sent! v1)
          s2 (sent! v2)
          s3 (sent! v3)
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic
                      :max-partial-messages 2})]
      (is (every? #(> (count %) 1) [s1 s2 s3]))
      ;; withhold the last part of each, so none completes yet, and the
      ;; oldest (v1's) partial is evicted once the third begins
      (doseq [sent [s1 s2 s3]
              [_ _ bytes] (butlast sent)]
        (udp/receive! receiver "peer" 9000 bytes))
      ;; complete v1: evicted, so it never arrives
      (udp/receive! receiver "peer" 9000 (last (last s1)))
      (is (= [] (deposited traffic)) "v1's partial was evicted")
      ;; complete v3: kept, so it arrives
      (udp/receive! receiver "peer" 9000 (last (last s3)))
      (is (= [v3] (deposited traffic))))))


;; =============================================================================
;; The deposited event carries the datagram's own source address
;; =============================================================================

(deftest deposit-carries-source-address
  (testing "the event names the source the socket saw, for replies"
    (let [traffic (ring 64)
          port (udp/make-port
                 {:send! (fn [& _] nil) :traffic traffic})
          v (request "s" 21 "hello")]
      (udp/receive! port "203.0.113.7" 4242 (cbor/encode v))
      (let [r (stream/next traffic
                           (:dao.stream/cursor
                             (stream/cursor traffic
                                            stream/anchor-oldest)))
            event (:dao.stream/value r)]
        (is (= (udp/address-key "203.0.113.7" 4242)
               (:dao.stream.udp/attachment event)))
        (is (= {:dao.stream.udp/host "203.0.113.7"
                :dao.stream.udp/port 4242}
               (:dao.stream.udp/source event)))
        (is (= v (:dao.stream.udp/value event)))))))


;; =============================================================================
;; Keying by [attachment, direction, id]: two attachments, equal ids,
;; no cross-delivery
;; =============================================================================

(deftest keying-prevents-cross-delivery
  (testing "two attachments fragmenting the same id, interleaved"
    (let [traffic (ring 64)
          {:keys [send! log]} (spy)
          port-a (udp/make-port {:send! send! :traffic traffic})
          port-b (udp/make-port {:send! send! :traffic traffic})
          va (request "s" 99 (big-string 4000))
          vb (request "s" 99 (big-string 3000))
          sent-a (do (reset! log [])
                     (udp/send-value! port-a "peer-a" 9001 va)
                     @log)
          sent-b (do (reset! log [])
                     (udp/send-value! port-b "peer-b" 9002 vb)
                     @log)
          receiver (udp/make-port
                     {:send! (fn [& _] nil) :traffic traffic})]
      (is (> (count sent-a) 1))
      (is (> (count sent-b) 1))
      ;; interleave: alternate a and b datagrams
      (loop [as sent-a bs sent-b]
        (when (seq as)
          (udp/receive! receiver "peer-a" 9001 (last (first as))))
        (when (seq bs)
          (udp/receive! receiver "peer-b" 9002 (last (first bs))))
        (when (or (seq as) (seq bs))
          (recur (rest as) (rest bs))))
      (is (= #{va vb} (set (deposited traffic)))))))


;; =============================================================================
;; The projection: one channel ring per attachment
;; =============================================================================

(deftest projection-routes-by-attachment
  (testing "step! forwards only the projected attachment's own events"
    (let [traffic (ring 64)
          port (udp/make-port
                 {:send! (fn [& _] nil) :traffic traffic})
          channel-a (ring 64)
          channel-b (ring 64)
          cursor (:dao.stream/cursor
                   (stream/cursor traffic stream/anchor-oldest))
          att-a (udp/address-key "peer-a" 9001)
          att-b (udp/address-key "peer-b" 9002)
          project-a (udp/projection {:attachment att-a :traffic traffic
                                     :cursor cursor :ring channel-a})
          project-b (udp/projection {:attachment att-b :traffic traffic
                                     :cursor cursor :ring channel-b})]
      (udp/receive! port "peer-a" 9001 (cbor/encode (request "s" 1 "a")))
      (udp/receive! port "peer-b" 9002 (cbor/encode (request "s" 2 "b")))
      (udp/step! project-a)
      (udp/step! project-b)
      (is (= [(request "s" 1 "a")] (values channel-a)))
      (is (= [(request "s" 2 "b")] (values channel-b))))))

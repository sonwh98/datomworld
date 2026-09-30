(ns dao.stream.udp-raw-test
  "The value channel rebuilt on the raw datagram layer
   (docs/design/dao.stream.datagram.md 7): `make-port`'s `:send!` composed
   as the Base64 closure over a real `dao.stream.datagram` writer,
   `send-value!` answering the raw writer's first non-ok outcome, and
   `port-step!` reading the raw traffic ring into `receive!` -- skipping
   lifecycle events and :dao.jing.dht/v maps, bounded by a budget, a raw
   gap adopting the recovery cursor. Everything here is host-free: one
   scripted fake network stands in for the host seam pair, wired exactly as
   the composition wires a real one."

  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.cbor :as cbor]
            [dao.stream.datagram :as datagram]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.udp :as udp]))


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


(defn- deposited
  [traffic]
  (mapv :dao.stream.udp/value (values traffic)))


(defn- request
  [identity id v]
  {:dao.stream/identity identity
   :dao.stream.remote/op :dao.stream/append!
   :dao.stream.remote/args [v]
   :dao.stream.remote/id id})


(defn- big-string
  [n]
  (apply str (repeat n \a)))


;; =============================================================================
;; The fake network: two scripted raw sockets wired as a loopback pair
;; =============================================================================


(defn- descriptor-of
  [id local]
  {:dao.stream/type :dao.stream/datagram
   :dao.stream/identity id
   :dao.stream.datagram/bind-host (:dao.stream.datagram/host local)
   :dao.stream.datagram/bind-port (:dao.stream.datagram/port local)})


(defn- raw-socket
  "One scripted raw datagram socket bound at `local` on the traffic ring
   `own-traffic`, whose send! crosses each datagram to `peer-traffic` as
   one datagram event sourced from this socket's bound address -- exactly
   the transform a real host seam deposits. A send naming any other
   destination than the peer's bound address fails synchronously, the way a
   host send does, so refusal paths are exercisable; `:fail-after` (an
   atom over nil or an integer) makes every send after that many
   successful ones fail."
  [id local own-traffic peer-traffic peer-local]
  (let [sent (atom 0)
        fail-after (atom nil)]
    (stream/append! own-traffic
                    (datagram/bound-event id
                                          (:dao.stream.datagram/host local)
                                          (:dao.stream.datagram/port local)))
    (let [seam {:send!
                (fn [host port bytes]
                  (if (and (or (nil? @fail-after) (< @sent @fail-after))
                           (= host (:dao.stream.datagram/host peer-local))
                           (= port (:dao.stream.datagram/port peer-local)))
                    (do (swap! sent inc)
                        (stream/append!
                          peer-traffic
                          (datagram/datagram-event
                            "peer" (:dao.stream.datagram/host local)
                            (:dao.stream.datagram/port local) bytes))
                        {:dao.stream/outcome :dao.stream/ok})
                    {:dao.stream/outcome :dao.stream/transport-error
                     :dao.stream.datagram/reason "no route to host"}))
                :close!
                (fn []
                  (stream/append! own-traffic (datagram/closed-event id))
                  nil)}]
      {:id id :seam seam :traffic own-traffic :local local
       :writer (datagram/writer seam (descriptor-of id local)
                                datagram/default-max-bytes)
       :fail-after fail-after})))


(defn- fake-network
  []
  (let [traffic-a (ring 64)
        traffic-b (ring 64)
        local-a {:dao.stream.datagram/host "127.0.0.1"
                 :dao.stream.datagram/port 41001}
        local-b {:dao.stream.datagram/host "127.0.0.1"
                 :dao.stream.datagram/port 41002}]
    {:a (raw-socket "sock-a" local-a traffic-a traffic-b local-b)
     :b (raw-socket "sock-b" local-b traffic-b traffic-a local-a)}))


(defn- compose-port
  "The whole composition of dao.stream.datagram.md 7 over one raw socket:
   the writer handle the composition builds after observing bound,
   make-port's :send! as the closure encoding the bytes as Base64 and
   appending one outbound value to that raw writer, and the port's raw
   cursor minted on the raw traffic ring at :oldest. Returns
   [port raw-writer]."
  [{:keys [id seam traffic local]} traffic-out]
  (let [writer (datagram/writer seam (descriptor-of id local)
                                datagram/default-max-bytes)
        send! (fn [host port bytes]
                (stream/append!
                  writer
                  {:dao.stream.datagram/destination
                   {:dao.stream.datagram/host host
                    :dao.stream.datagram/port port}
                   :dao.stream.datagram/bytes (base64/encode bytes)}))]
    [(udp/make-port {:send! send! :traffic traffic-out :raw-traffic traffic})
     writer]))


;; =============================================================================
;; The value channel crosses the raw layer whole
;; =============================================================================


(deftest single-datagram-value-crosses-the-raw-layer
  (let [net (fake-network)
        out-b (ring 64)
        [port-a] (compose-port (:a net) (ring 64))
        [port-b] (compose-port (:b net) out-b)
        v (request "s" 1 "hello")]
    (is (= {:dao.stream/outcome :dao.stream/ok}
           (udp/send-value! port-a "127.0.0.1" 41002 v)))
    (udp/port-step! port-b 64)
    (is (= [v] (deposited out-b)))))


(deftest fragmented-value-crosses-the-raw-layer
  (let [net (fake-network)
        out-b (ring 64)
        [port-a] (compose-port (:a net) (ring 64))
        [port-b] (compose-port (:b net) out-b)
        v (request "s" 2 (big-string 4000))]
    (is (= {:dao.stream/outcome :dao.stream/ok}
           (udp/send-value! port-a "127.0.0.1" 41002 v)))
    (udp/port-step! port-b 64)
    (is (= [v] (deposited out-b))
        "fragments crossed as separate raw datagram events and reassembled
         in receive!, which neither the mirror step nor the link can tell")))


(deftest both-directions-and-multiple-values-cross
  (let [net (fake-network)
        out-a (ring 64)
        out-b (ring 64)
        [port-a] (compose-port (:a net) out-a)
        [port-b] (compose-port (:b net) out-b)
        va (request "s" 3 "a1")
        vb (request "s" 4 "b1")
        vc (request "s" 5 "a2")]
    (udp/send-value! port-a "127.0.0.1" 41002 va)
    (udp/send-value! port-b "127.0.0.1" 41001 vb)
    (udp/send-value! port-a "127.0.0.1" 41002 vc)
    (udp/port-step! port-b 64)
    (udp/port-step! port-a 64)
    (is (= [va vc] (deposited out-b)))
    (is (= [vb] (deposited out-a)))))


;; =============================================================================
;; send-value! answers the raw writer's first non-ok outcome
;; =============================================================================


(deftest send-value-answers-raw-transport-error
  (let [net (fake-network)
        [port-a] (compose-port (:a net) (ring 64))]
    (is (= {:dao.stream/outcome :dao.stream/transport-error
            :dao.stream.datagram/reason "no route to host"}
           (udp/send-value! port-a "127.0.0.1" 41003
                            (request "s" 6 "unroutable")))
        "the raw writer's outcome is answered, not discarded")
    (is (empty? (filter datagram/datagram-event?
                        (values (:traffic (:b net)))))
        "nothing crossed: no datagram event is on the peer's raw ring")))


(deftest send-value-answers-raw-closed-and-sends-nothing
  (let [net (fake-network)
        [port-a writer] (compose-port (:a net) (ring 64))]
    (stream/close! writer)
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (udp/send-value! port-a "127.0.0.1" 41002
                            (request "s" 7 (big-string 4000)))))
    (is (empty? (filter datagram/datagram-event?
                        (values (:traffic (:b net)))))
        "no fragment crossed to the peer's raw ring")))


(deftest send-value-answers-first-fragment-failure-and-sends-no-more
  (let [net (fake-network)
        [port-a] (compose-port (:a net) (ring 64))
        v (request "s" 8 (big-string 4000))]
    (reset! (:fail-after (:a net)) 1)
    (is (= {:dao.stream/outcome :dao.stream/transport-error
            :dao.stream.datagram/reason "no route to host"}
           (udp/send-value! port-a "127.0.0.1" 41002 v)))
    (is (= 1 (count (filter datagram/datagram-event?
                            (values (:traffic (:b net))))))
        "the first datagram crossed, the failed one and every later
         fragment did not: the failure is clean, nothing torn follows")))


;; =============================================================================
;; port-step! skips lifecycle events and DHT datagrams, bounded by budget
;; =============================================================================


(deftest port-step-skips-lifecycle-and-dht-datagrams
  (let [net (fake-network)
        raw (:traffic (:b net))
        out-b (ring 64)
        port-b (udp/make-port {:send! (fn [& _] nil)
                               :traffic out-b
                               :raw-traffic raw})
        dht-request {:dao.jing.dht/v 1 :op :ping :q 11}
        dht-reply {:dao.jing.dht/v 1 :op :reply :q 11 :ok true}
        mine (request "s" 9 "mine")]
    (stream/append! raw (datagram/closed-event "someone-else"))
    (stream/append! raw (datagram/datagram-event "sock-b" "127.0.0.1" 41001
                                                 (cbor/encode dht-request)))
    (stream/append! raw (datagram/datagram-event "sock-b" "127.0.0.1" 41001
                                                 (cbor/encode dht-reply)))
    (stream/append! raw (datagram/datagram-event "sock-b" "203.0.113.9" 40001
                                                 (cbor/encode mine)))
    (stream/append! raw {:dao.stream.datagram/socket "sock-b"
                         :dao.stream.datagram/whatever "not this layer's"})
    (udp/port-step! port-b 64)
    (is (= [mine] (deposited out-b))
        "the lifecycle event, both DHT datagrams and the foreign value were
         skipped; only this interpreter's datagram was deposited")))


(deftest port-step-is-bounded-by-the-budget
  (let [raw (ring 64)
        out (ring 64)
        port (udp/make-port {:send! (fn [& _] nil)
                             :traffic out :raw-traffic raw})]
    (dotimes [i 5]
      (stream/append! raw
                      (datagram/datagram-event "s" "127.0.0.1" 41000
                                               (cbor/encode
                                                 (request "s" (+ 100 i)
                                                          (str "v" i))))))
    (is (= 0 (count (deposited out))) "nothing stepped yet")
    (udp/port-step! port 2)
    (is (= 2 (count (deposited out))) "the budget bounded the step")
    (udp/port-step! port 2)
    (is (= 4 (count (deposited out))))
    (udp/port-step! port 64)
    (is (= 5 (count (deposited out)))
        "a later step picks up what an earlier budget left behind")))


(deftest port-step-budget-counts-skipped-events
  (let [raw (ring 64)
        out (ring 64)
        port (udp/make-port {:send! (fn [& _] nil)
                             :traffic out :raw-traffic raw})]
    (dotimes [i 5]
      (stream/append! raw
                      (if (even? i)
                        (datagram/closed-event (str i))
                        (datagram/datagram-event
                          "s" "127.0.0.1" 41000
                          (cbor/encode {:dao.jing.dht/v 1 :op :ping})))))
    (stream/append! raw
                    (datagram/datagram-event
                      "s" "127.0.0.1" 41000
                      (cbor/encode (request "s" 999 "after-skips"))))
    (udp/port-step! port 2)
    (is (empty? (deposited out)))
    (udp/port-step! port 2)
    (is (empty? (deposited out))
        "skipped events consume the step budget")
    (udp/port-step! port 2)
    (is (= 1 (count (deposited out))))))


(deftest port-step-gap-adopts-the-recovery-cursor
  (let [raw (ring 4)
        out (ring 64)
        port (udp/make-port {:send! (fn [& _] nil)
                             :traffic out :raw-traffic raw})
        vs (mapv (fn [i] (request "s" (+ 200 i) (str "g" i))) (range 8))]
    (doseq [v vs]
      (stream/append! raw
                      (datagram/datagram-event "s" "127.0.0.1" 41000
                                               (cbor/encode v))))
    (udp/port-step! port 64)
    (is (= (subvec vs 4) (deposited out))
        "the raw gap adopted the recovery cursor: the four evicted
         datagrams are lost, exactly as network loss, and the four retained
         ones arrived; the link's resend rule recovers the rest")))


(deftest port-step-without-a-raw-reader-is-a-composition-defect
  (let [out (ring 8)
        port (udp/make-port {:send! (fn [& _] nil) :traffic out})]
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (udp/port-step! port 64)))))

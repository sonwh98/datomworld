(ns dao.stream.ble.mesh-test
  "The flooding mesh over the deterministic radio (docs/design/
   dao.stream.ble.md 7): exchange over links formed by the machines'
   own connect policy, the dedup that eats both-directions redundancy
   and flood loops, the ttl that bounds hops, multi-hop relay through a
   middle node, the seq discipline, foreign frames skipped, oversize
   values refused, and the raw gap adopted. Every fact is pumped; no
   test waits on anything."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ble :as ble]
            [dao.stream.ble.mesh :as mesh]
            [dao.stream.ble.sim :as sim]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ringbuffer])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- octets->bytes
  [octets]
  #?(:cljd (Uint8List.fromList (vec octets))
     :clj (byte-array (mapv unchecked-byte octets))
     :cljs (js/Uint8Array.from (to-array (vec octets)))))


(defn- writer
  "A raw writer handle over a scripted seam: `:sends` records every
   [peer bytes] it was handed, `:fail` refuses the next n sends."
  [& {:keys [fail]}]
  (let [sends (atom [])
        fail (atom (or fail 0))]
    {:handle (ble/writer
               {:send! (fn [p bs]
                         (if (pos? @fail)
                           (do (swap! fail dec)
                               {:dao.stream/outcome :dao.stream/transport-error
                                :dao.stream.ble/reason "scripted refusal"})
                           (do (swap! sends conj [p bs])
                               {:dao.stream/outcome :dao.stream/ok})))
                :close! (fn [])}
               {:dao.stream/type ble/transport-type
                :dao.stream/identity "w"
                :dao.stream.ble/self "w"})
     :sends sends :fail fail}))


(defn- make-mesh
  "A mesh over `node`, replaying the traffic ring from its origin."
  [net node-id & {:keys [identity ttl max-remembered]}]
  (let [n (sim/node net node-id)]
    (mesh/make-mesh
      {:identity (or identity node-id)
       :raw-writer (:writer n)
       :raw-traffic (:traffic n)
       :raw-cursor stream/anchor-oldest
       :deposit (:deposit n)
       :ttl ttl
       :max-remembered max-remembered})))


(defn- delivered
  [net node-id]
  (mapv (fn [ev]
          {:origin (:dao.stream.ble.mesh/origin ev)
           :seq (:dao.stream.ble.mesh/seq ev)
           :value (:dao.stream.ble.mesh/value ev)})
        (sim/deliveries net node-id)))


(defn- link-all!
  "Join every node id and run one advertisement round: the machines'
   connect policy forms what reachability allows."
  [net & ids]
  (doseq [id ids] (sim/join! net id))
  (sim/advertise! net))


;; =============================================================================
;; Exchange
;; =============================================================================


(deftest two-nodes-exchange-one-value-once
  (let [net (sim/radio-net)]
    (link-all! net "a" "b")
    (is (sim/linked? net "a" "b"))
    (let [ma (make-mesh net "a" :identity "origin-a")
          mb (make-mesh net "b" :identity "origin-b")]
      (testing "both meshes learn their neighbors from the traffic rings"
        (is (= :drained (:status (mesh/mesh-step! ma))))
        (is (= :drained (:status (mesh/mesh-step! mb)))))
      (testing "a send answers ok and spends the seq once"
        (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:n 1}))))
        (sim/pump! net)
        (is (= 1 (:next-seq @ma))))
      (testing "b delivers the value once, however many links carried it"
        (mesh/mesh-step! mb)
        (is (= [{:origin "origin-a" :seq 0 :value {:n 1}}]
               (delivered net "b"))))
      (testing "the second send rides the next seq"
        (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:n 2}))))
        (sim/pump! net)
        (mesh/mesh-step! mb)
        (is (= [{:origin "origin-a" :seq 0 :value {:n 1}}
                {:origin "origin-a" :seq 1 :value {:n 2}}]
               (delivered net "b")))))))


(deftest a-send-before-any-link-is-ok-and-unspent
  (let [net (sim/radio-net)
        _a (sim/join! net "a")
        ma (make-mesh net "a" :identity "origin-a")]
    (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:n 1}))))
    (sim/pump! net)
    (is (= 0 (:next-seq @ma)))
    (testing "nobody received any frame"
      (is (empty? (sim/frames net "a"))))))


(deftest both-directions-redundancy-is-eaten-by-the-dedup
  (let [net (sim/radio-net)]
    (link-all! net "a" "b")
    (let [ma (make-mesh net "a" :identity "origin-a")
          mb (make-mesh net "b" :identity "origin-b")]
      (mesh/mesh-step! ma)
      (mesh/mesh-step! mb)
      (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:n 1}))))
      (sim/pump! net)
      ;; Each side connected out to the other: two GATT links, two peer
      ;; addresses per observer, so b's radio received the frame twice.
      (is (= 2 (count (sim/frames net "b"))))
      (mesh/mesh-step! mb)
      (is (= 1 (count (delivered net "b")))))))


;; =============================================================================
;; Flooding, hops and ttl
;; =============================================================================


(deftest a-middle-node-relays-and-the-loop-dies-at-the-dedup
  (let [net (sim/radio-net)]
    (sim/unreachable! net "a" "c")
    (link-all! net "a" "b" "c")
    (testing "the partition holds: a and c never linked"
      (is (not (sim/linked? net "a" "c")))
      (is (sim/linked? net "a" "b"))
      (is (sim/linked? net "b" "c")))
    (let [ma (make-mesh net "a" :identity "origin-a")
          mb (make-mesh net "b" :identity "origin-b")
          mc (make-mesh net "c" :identity "origin-c")]
      (doseq [m [ma mb mc]] (mesh/mesh-step! m))
      (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:hop 1}))))
      (sim/pump! net)
      (mesh/mesh-step! mb)
      (sim/pump! net)
      (mesh/mesh-step! mc)
      (testing "b delivered, c delivered through b"
        (is (= [{:origin "origin-a" :seq 0 :value {:hop 1}}]
               (delivered net "b")))
        (is (= [{:origin "origin-a" :seq 0 :value {:hop 1}}]
               (delivered net "c"))))
      (testing "b relayed back toward a over the redundant link, a deduped
                -- its own send occupies the cache"
        (sim/pump! net)
        (mesh/mesh-step! ma)
        (is (= [] (delivered net "a")))))))


(deftest ttl-zero-delivers-locally-and-relays-nothing
  (let [net (sim/radio-net)]
    (sim/unreachable! net "a" "c")
    (link-all! net "a" "b" "c")
    (let [ma (make-mesh net "a" :identity "origin-a" :ttl 0)
          mb (make-mesh net "b" :identity "origin-b")
          mc (make-mesh net "c" :identity "origin-c")]
      (doseq [m [ma mb mc]] (mesh/mesh-step! m))
      (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:hop 1}))))
      (sim/pump! net)
      (mesh/mesh-step! mb)
      (sim/pump! net)
      (mesh/mesh-step! mc)
      (testing "b heard it, c never did"
        (is (= 1 (count (delivered net "b"))))
        (is (= [] (delivered net "c")))))))


(deftest ttl-one-reaches-exactly-two-hops
  (let [net (sim/radio-net)]
    (sim/unreachable! net "a" "c")
    (sim/unreachable! net "a" "d")
    (sim/unreachable! net "b" "d")
    (link-all! net "a" "b" "c" "d")
    (let [ma (make-mesh net "a" :identity "origin-a" :ttl 1)
          mb (make-mesh net "b" :identity "origin-b")
          mc (make-mesh net "c" :identity "origin-c")
          md (make-mesh net "d" :identity "origin-d")]
      (doseq [m [ma mb mc md]] (mesh/mesh-step! m))
      (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:hop 1}))))
      (sim/pump! net)
      (mesh/mesh-step! mb)
      (sim/pump! net)
      (mesh/mesh-step! mc)
      (mesh/mesh-step! md)
      (is (= 1 (count (delivered net "c"))))
      (is (= [] (delivered net "d"))))))


;; =============================================================================
;; The seq discipline
;; =============================================================================


(deftest the-seq-is-spent-when-the-raw-writer-accepted-the-frame
  (let [net (sim/radio-net)]
    (link-all! net "a" "b")
    (let [ma (make-mesh net "a" :identity "origin-a")
          w (:writer (sim/node net "a"))]
      (mesh/mesh-step! ma)
      (testing "a live send spends exactly one seq, whatever the air did"
        (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:n 1}))))
        (sim/pump! net)
        (is (= 1 (:next-seq @ma)))
        (is (= :dao.stream/ok (:dao.stream/outcome (mesh/send! ma {:n 2}))))
        (sim/pump! net)
        (is (= 2 (:next-seq @ma))))
      (testing "a closed raw writer refuses every append: the seq stays"
        (stream/close! w)
        (is (= :dao.stream/closed (:dao.stream/outcome (mesh/send! ma {:n 3}))))
        (is (= 2 (:next-seq @ma)))))))


(deftest different-origins-never-share-a-dedup-key
  (let [net (sim/radio-net)
        a (sim/join! net "a")
        traffic (:traffic a)
        m (mesh/make-mesh {:identity "me" :raw-writer (:writer a) :raw-traffic traffic
                           :raw-cursor stream/anchor-newest :deposit (:deposit a)})]
    (doseq [origin ["x" "y"]]
      (stream/append! traffic
                      (ble/frame-event "me" "peer-x"
                                       (octets->bytes
                                         (vec (cbor/encode
                                                (mesh/frame origin 0 5 {:o origin})))))))
    (mesh/mesh-step! m)
    (is (= [{:origin "x" :seq 0 :value {:o "x"}}
            {:origin "y" :seq 0 :value {:o "y"}}]
           (delivered net "a")))))


(deftest the-dedup-cache-is-bounded-oldest-evicted
  (let [net (sim/radio-net)
        a (sim/join! net "a")
        traffic (:traffic a)
        m (mesh/make-mesh {:identity "me" :raw-writer (:writer a) :raw-traffic traffic
                           :raw-cursor stream/anchor-newest :deposit (:deposit a)
                           :max-remembered 2})]
    (doseq [i (range 4)]
      (stream/append! traffic
                      (ble/frame-event "me" "peer-x"
                                       (octets->bytes
                                         (vec (cbor/encode
                                                (mesh/frame "x" i 5 {:i i})))))))
    (mesh/mesh-step! m)
    (is (= 4 (count (delivered net "a"))))
    (testing "seq 0 is forgotten: the same frame again delivers again"
      (stream/append! traffic
                      (ble/frame-event "me" "peer-x"
                                       (octets->bytes
                                         (vec (cbor/encode
                                                (mesh/frame "x" 0 5 {:i 0}))))))
      (mesh/mesh-step! m)
      (is (= 5 (count (delivered net "a")))))))


;; =============================================================================
;; Foreign frames and oversize values
;; =============================================================================


(deftest foreign-frames-are-skipped-silently
  (let [net (sim/radio-net)
        a (sim/join! net "a")
        traffic (:traffic a)
        m (mesh/make-mesh {:identity "me" :raw-writer (:writer a) :raw-traffic traffic
                           :raw-cursor stream/anchor-newest :deposit (:deposit a)})
        inject-value! (fn [v]
                        (stream/append! traffic
                                        (ble/frame-event
                                          "me" "peer-x"
                                          (octets->bytes (vec (cbor/encode v))))))
        inject-bytes! (fn [octets]
                        (stream/append! traffic
                                        (ble/frame-event
                                          "me" "peer-x" (octets->bytes octets))))]
    (testing "a decodable value that is not a frame"
      (inject-value! {:dao.jing.dht/v 1 :other "data"})
      (is (= {:processed 1 :delivered 0 :relayed 0 :status :drained}
             (mesh/mesh-step! m))))
    (testing "a frame key with malformed fields"
      (inject-value! {:dao.stream.ble.mesh/v 1 :dao.stream.ble.mesh/origin ""
                      :dao.stream.ble.mesh/seq 0 :dao.stream.ble.mesh/ttl 5
                      :dao.stream.ble.mesh/value {:x 1}})
      (is (= {:processed 1 :delivered 0 :relayed 0 :status :drained}
             (mesh/mesh-step! m))))
    (testing "bytes that are not CBOR at all -- a truncated array header"
      (inject-bytes! [130 1])
      (is (= {:processed 1 :delivered 0 :relayed 0 :status :drained}
             (mesh/mesh-step! m))))
    (is (= [] (delivered net "a")))))


(deftest an-oversize-value-is-refused-by-the-raw-writer
  (let [net (sim/radio-net)]
    (link-all! net "a" "b")
    (let [ma (make-mesh net "a" :identity "origin-a")]
      (mesh/mesh-step! ma)
      (testing "the raw layer owns the budget, both ways"
        (is (= :dao.stream/invalid-value
               (:dao.stream/outcome
                 (mesh/send! ma {:a-very-large-value (range 100)}))))))))


;; =============================================================================
;; Gaps on the raw ring
;; =============================================================================


(deftest a-raw-gap-is-adopted-and-loses-what-the-ring-lost
  (let [traffic (ring 3)
        w (writer)
        m (mesh/make-mesh {:identity "me" :raw-writer (:handle w)
                           :raw-traffic traffic
                           :raw-cursor stream/anchor-oldest
                           :deposit (ring 64)})
        inject! (fn [i]
                  (stream/append! traffic
                                  (ble/frame-event "me" "peer-x"
                                                   (octets->bytes
                                                     (vec (cbor/encode
                                                            (mesh/frame "x" i 5 {:i i})))))))]
    (doseq [i (range 6)] (inject! i))
    (let [r (mesh/mesh-step! m)]
      (is (= :drained (:status r)))
      (testing "fewer than six delivered: the ring evicted the rest, honestly"
        (is (< (:delivered r) 6)))
      (testing "the cursor adopted the recovery position: the next step is clean"
        (is (= {:processed 0 :delivered 0 :relayed 0 :status :drained}
               (mesh/mesh-step! m)))))))


;; =============================================================================
;; Redundancy and loss (review round 1)
;; =============================================================================


(deftest one-dead-neighbor-starves-nothing
  (let [net (sim/radio-net)]
    (link-all! net "a" "b" "c")
    (let [ma (make-mesh net "a" :identity "origin-a")
          mb (make-mesh net "b" :identity "origin-b")
          mc (make-mesh net "c" :identity "origin-c")]
      (doseq [m [ma mb mc]] (mesh/mesh-step! m))
      ;; a's neighbor table still holds b; the link is gone, but a has
      ;; not stepped through the link-down yet. The fan-out must hand
      ;; the frame to c anyway.
      (sim/unlink! net "a" "b")
      (let [r (mesh/send! ma {:n 1})]
        (is (= :dao.stream/transport-error (:dao.stream/outcome r)))
        (sim/pump! net)
        (mesh/mesh-step! mb)
        (mesh/mesh-step! mc)
        (testing "the live neighbor received the frame"
          (is (= [{:origin "origin-a" :seq 0 :value {:n 1}}]
                 (delivered net "c"))))
        (testing "the seq was spent: at least one link accepted"
          (is (= 1 (:next-seq @ma))))))))


(deftest a-gap-leaves-the-neighbor-set-stale-and-usable
  (let [net (sim/radio-net)]
    (link-all! net "a" "b" "c")
    (let [ma (make-mesh net "a" :identity "origin-a")
          mc (make-mesh net "c" :identity "origin-c")]
      (mesh/mesh-step! ma)
      (mesh/mesh-step! mc)
      (is (seq (:neighbors @ma)))
      (sim/unlink! net "a" "b")
      (testing "the link facts arrive, then a gap swallows them"
        (doseq [_ (range 300)]
          (stream/append! (:traffic (sim/node net "a"))
                          (ble/frame-event "a" "peer-x"
                                           (octets->bytes
                                             (vec (cbor/encode
                                                    (mesh/frame "x" 0 5 {}))))))))
      (let [r (loop [r (mesh/mesh-step! ma)]
                (if (= :drained (:status r))
                  r
                  (recur (mesh/mesh-step! ma))))]
        (is (= :drained (:status r)))
        (testing "stale is the honest choice: the swallowed link-down
                  leaves b in the set, because a cleared set would cost
                  the mesh every relay it has and a stable link emits
                  no link-up to refill it"
          (is (seq (:neighbors @ma))))
        (testing "and the mesh still works: the filler frame the gap
                  did not swallow was relayed to c, and origin-a's send
                  reached c through the stale relay's send-failed and
                  the live one's ok"
          (is (= :dao.stream/transport-error
                 (:dao.stream/outcome (mesh/send! ma {:n 1}))))
          (sim/pump! net)
          (mesh/mesh-step! mc)
          (is (= [{:origin "x" :seq 0 :value {}}
                  {:origin "origin-a" :seq 0 :value {:n 1}}]
                 (delivered net "c"))))))))


(deftest a-closed-raw-layer-clears-the-neighbor-set
  (let [net (sim/radio-net)]
    (link-all! net "a" "b")
    (let [ma (make-mesh net "a" :identity "origin-a")]
      (mesh/mesh-step! ma)
      (is (seq (:neighbors @ma)))
      (stream/append! (:traffic (sim/node net "a"))
                      (ble/closed-event "a"))
      (mesh/mesh-step! ma)
      (is (empty? (:neighbors @ma))))))


(deftest a-wire-ttl-above-the-budget-is-clamped
  (let [net (sim/radio-net)]
    (sim/unreachable! net "a" "c")
    (sim/unreachable! net "a" "d")
    (sim/unreachable! net "b" "d")
    (link-all! net "a" "b" "c" "d")
    (let [ma (make-mesh net "a" :identity "origin-a")
          mb (make-mesh net "b" :identity "origin-b")
          mc (make-mesh net "c" :identity "origin-c")
          md (make-mesh net "d" :identity "origin-d")]
      (doseq [m [ma mb mc md]] (mesh/mesh-step! m))
      (testing "a foreign frame claiming ttl 100 relays only as far as
                the receiving mesh's own budget allows"
        (stream/append! (:traffic (sim/node net "b"))
                        (ble/frame-event "b" "peer-x"
                                         (octets->bytes
                                           (vec (cbor/encode
                                                  (mesh/frame "x" 0 100 {:big-ttl true}))))))
        (mesh/mesh-step! mb)
        (sim/pump! net)
        (mesh/mesh-step! mc)
        (mesh/mesh-step! md)
        (testing "c heard it (one hop from b), d never did (the clamp
                  spent the budget at c's relay)"
          (is (= 1 (count (delivered net "c"))))
          (is (= [] (delivered net "d"))))))))

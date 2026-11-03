(ns dao.stream.ble-test
  "Portable, host-free tests for dao.stream.ble
   (docs/design/dao.stream.ble.md): the value shapes of section 2, the
   peer-address rule, the descriptor of section 3, and every
   append!/close! outcome of the writer handle of section 4 over a
   scripted host seam. The seam contract itself is what the link
   machine's tests (dao.stream.ble.links-test) drive and the sim
   exercises end to end; everything here proves the portable layer's own
   decisions without a host."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.ble :as ble])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(def ^:private peer "peer-1")
(def ^:private node-id "ble-node")


(defn- octets->bytes
  "One octet payload as host bytes, the only host dependence here."
  [octets]
  #?(:cljd (Uint8List.fromList (vec octets))
     :clj (byte-array (mapv unchecked-byte octets))
     :cljs (js/Uint8Array.from (to-array (vec octets)))))


(defn- octets-of
  "Any byte payload as a vector of unsigned octets, the one shape that
   compares equal on every host."
  [bs]
  (mapv (fn [b] (bit-and 0xff (int b))) (seq bs)))


(defn- outbound
  "One outbound value of section 2."
  [p octets]
  {:dao.stream.ble/destination p
   :dao.stream.ble/bytes (base64/encode (octets->bytes octets))})


(defn- descriptor
  ([]
   {:dao.stream/type ble/transport-type
    :dao.stream/identity node-id
    :dao.stream.ble/self node-id})
  ([self]
   {:dao.stream/type ble/transport-type
    :dao.stream/identity node-id
    :dao.stream.ble/self self}))


(defn- writer-over
  "The writer handle over a scripted seam and the descriptor."
  ([seam] (ble/writer seam (descriptor)))
  ([seam max] (ble/writer seam (descriptor) max)))


(defn- scripted-seam
  "A fake host seam: `:sends` records every frame as [peer bytes];
   `:fail` answers the next n sends with the clean transport-error of
   section 4 (the deposit side is the composition's, which the links
   tests drive); `:closes` counts close!s."
  [& {:keys [fail]}]
  (let [sends (atom [])
        fail (atom (or fail 0))
        closes (atom 0)
        seam {:send!
              (fn [p bs]
                (if (pos? @fail)
                  (do (swap! fail dec)
                      {:dao.stream/outcome :dao.stream/transport-error
                       :dao.stream.ble/reason "scripted refusal"})
                  (do (swap! sends conj [p bs])
                      {:dao.stream/outcome :dao.stream/ok})))
              :close! (fn [] (swap! closes inc) nil)}]
    {:seam seam :sends sends :fail fail :closes closes}))


;; =============================================================================
;; Section 2: values
;; =============================================================================


(deftest peer-addresses-are-opaque-nonempty-strings
  (testing "any non-empty string is a peer address"
    (is (ble/peer-address? "AA:BB:CC:DD:EE:FF"))
    (is (ble/peer-address? "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
    (is (ble/peer-address? "peer-1")))
  (testing "no structure is assigned and none is checked"
    (is (ble/peer-address? " not an address at all ")))
  (testing "empty and non-string destinations are malformed"
    (is (not (ble/peer-address? "")))
    (is (not (ble/peer-address? nil)))
    (is (not (ble/peer-address? 42)))))


(deftest outbound-and-frame-values
  (let [good (outbound peer [1 2 3])]
    (testing "an outbound value of section 2"
      (is (ble/outbound? good)))
    (testing "a malformed destination or non-Base64 bytes is not"
      (is (not (ble/outbound? {:dao.stream.ble/destination ""
                               :dao.stream.ble/bytes (:dao.stream.ble/bytes good)})))
      (is (not (ble/outbound? (assoc good :dao.stream.ble/bytes "not base64!")))))
    (testing "a frame event of section 2"
      (is (ble/frame-event?
            (ble/frame-event node-id peer (octets->bytes [9 8 7]))))
      (is (not (ble/frame-event?
                 (assoc (ble/frame-event node-id peer (octets->bytes [9]))
                        :dao.stream.ble/bytes "ab cd")))))))


(deftest lifecycle-events-carry-the-node-and-a-declared-event
  (let [id node-id]
    (doseq [ev [(ble/bound-event id id)
                (ble/bind-failed-event id "denied")
                (ble/peer-seen-event id peer -60)
                (ble/peer-lost-event id peer)
                (ble/link-up-event id peer :central)
                (ble/link-down-event id peer)
                (ble/send-failed-event id peer "no link")
                (ble/closed-event id)]]
      (is (ble/lifecycle-event? ev)))
    (testing "an undeclared event keyword is not this layer's"
      (is (not (ble/lifecycle-event?
                 {:dao.stream.ble/node id
                  :dao.stream.ble/event :dao.stream.ble/something-new}))))
    (testing "a frame event is not a lifecycle event and the reverse"
      (is (not (ble/lifecycle-event?
                 (ble/frame-event id peer (octets->bytes [1])))))
      (is (not (ble/frame-event? (ble/closed-event id)))))))


;; =============================================================================
;; Section 3: the descriptor
;; =============================================================================


(deftest descriptor-names-the-local-node
  (is (ble/descriptor? (descriptor)))
  (testing "self may differ from the composition's id"
    (is (ble/descriptor? (descriptor "aa:bb:cc:dd:ee:ff")))
    (is (= "aa:bb:cc:dd:ee:ff"
           (:dao.stream.ble/self (descriptor "aa:bb:cc:dd:ee:ff")))))
  (testing "the type, identity and self are all required"
    (is (not (ble/descriptor? {})))
    (is (not (ble/descriptor? (dissoc (descriptor) :dao.stream/identity))))
    (is (not (ble/descriptor? (dissoc (descriptor) :dao.stream.ble/self))))
    (is (not (ble/descriptor?
               (assoc (descriptor) :dao.stream/type :dao.stream/datagram))))
    (is (not (ble/descriptor?
               (assoc (descriptor) :dao.stream.ble/self ""))))))


;; =============================================================================
;; Section 4: the writer handle
;; =============================================================================


(deftest append-hands-one-frame-to-the-seam
  (let [s (scripted-seam)
        w (writer-over (:seam s))
        r (stream/append! w (outbound peer [1 2 3]))]
    (is (= :dao.stream/ok (:dao.stream/outcome r)))
    (is (= [[peer [1 2 3]]]
           (mapv (fn [[p bs]] [p (octets-of bs)]) @(:sends s))))))


(deftest malformed-outbounds-are-invalid-value-and-never-sent
  (let [s (scripted-seam)
        w (writer-over (:seam s) 8)]
    (testing "a malformed destination"
      (let [r (stream/append! w (assoc (outbound peer [1]) :dao.stream.ble/destination ""))]
        (is (= :dao.stream/invalid-value (:dao.stream/outcome r)))
        (is (string? (:dao.stream.ble/reason r)))))
    (testing "bytes that are not strict Base64"
      (let [r (stream/append! w (assoc (outbound peer [1]) :dao.stream.ble/bytes "ab cd"))]
        (is (= :dao.stream/invalid-value (:dao.stream/outcome r)))))
    (testing "a decoded length over the budget"
      (let [r (stream/append! w (outbound peer [1 2 3 4 5 6 7 8 9]))]
        (is (= :dao.stream/invalid-value (:dao.stream/outcome r)))))
    (testing "not a map at all"
      (is (= :dao.stream/invalid-value (:dao.stream/outcome (stream/append! w 42)))))
    (testing "nothing was sent"
      (is (empty? @(:sends s))))))


(deftest exactly-at-the-budget-is-accepted
  (let [s (scripted-seam)
        w (writer-over (:seam s) 3)]
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! w (outbound peer [1 2 3])))))
    (is (= 1 (count @(:sends s))))))


(deftest synchronous-send-failures-are-clean
  (let [s (scripted-seam :fail 2)
        w (writer-over (:seam s))]
    (dotimes [_ 2]
      (let [r (stream/append! w (outbound peer [1]))]
        (is (= :dao.stream/transport-error (:dao.stream/outcome r)))
        (is (= "scripted refusal" (:dao.stream.ble/reason r)))))
    (testing "the seam was never handed the failed frames"
      (is (empty? @(:sends s))))))


(deftest close-answers-ok-tears-down-and-is-idempotent
  (let [s (scripted-seam)
        w (writer-over (:seam s))]
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! w))))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! w))))
    (testing "each close reaches the seam, which tolerates the repeat"
      (is (= 2 @(:closes s))))
    (testing "a closed handle appends closed and sends nothing"
      (is (= :dao.stream/closed
             (:dao.stream/outcome (stream/append! w (outbound peer [1])))))
      (is (empty? @(:sends s))))
    (testing "the descriptor still answers ok after close"
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/descriptor w)))))))


(deftest descriptor-answers-identity-and-reachability
  (let [s (scripted-seam)
        w (writer-over (:seam s))
        r (stream/descriptor w)]
    (is (= :dao.stream/ok (:dao.stream/outcome r)))
    (is (= node-id (:dao.stream/identity r)))
    (is (= (descriptor) (:dao.stream/descriptor r)))
    (testing "the identity projection and the descriptor's agree"
      (is (= (:dao.stream/identity (:dao.stream/descriptor r))
             (:dao.stream/identity r))))))


(deftest bad-seams-and-descriptors-are-assembly-defects
  (testing "a seam without send!/close! throws before any operation"
    (is (thrown? #?(:cljd Object :clj Throwable :cljs js/Error)
          (ble/writer {:send! (fn [_p _bs])} (descriptor))))
    (is (thrown? #?(:cljd Object :clj Throwable :cljs js/Error)
          (ble/writer nil (descriptor)))))
  (testing "a non-descriptor throws"
    (is (thrown? #?(:cljd Object :clj Throwable :cljs js/Error)
          (ble/writer (:seam (scripted-seam)) {:dao.stream/type :nope})))))


(deftest a-seam-answer-is-taken-at-its-outcome
  (testing "an outcome map outside the declared set answers transport-error"
    (let [seam {:send! (fn [_p _bs] {:dao.stream/outcome :dao.stream/not-mine})
                :close! (fn [])}
          w (writer-over seam)]
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (stream/append! w (outbound peer [1])))))))
  (testing "a seam answering nothing at all is ok -- the frame was handed over"
    (let [seam {:send! (fn [_p _bs] :whatever)
                :close! (fn [])}
          w (writer-over seam)]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/append! w (outbound peer [1]))))))))


;; =============================================================================
;; Section 3: attach!
;; =============================================================================


(deftest attach-resolves-only-what-the-composition-kept
  (let [s (scripted-seam)
        w (writer-over (:seam s))
        attach! (ble/make-attacher {node-id w})]
    (testing "the kept node's descriptor attaches to the same handle"
      (let [r (attach! (descriptor))]
        (is (= :dao.stream/ok (:dao.stream/outcome r)))
        (is (identical? w (:dao.stream/handle r)))))
    (testing "a malformed descriptor is invalid-descriptor"
      (is (= :dao.stream/invalid-descriptor (:dao.stream/outcome (attach! {})))))
    (testing "a node the composition did not keep is not-found"
      (is (= :dao.stream/not-found
             (:dao.stream/outcome
               (attach! (assoc (descriptor) :dao.stream/identity "other"))))))))


;; =============================================================================
;; The default composition data of section 3
;; =============================================================================


(deftest defaults
  (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                  ble/default-service-uuid))
  (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                  ble/default-tx-uuid))
  (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                  ble/default-rx-uuid))
  (is (= 180 ble/default-max-frame-bytes))
  (is (= 8 ble/default-max-links))
  (is (= 2000 ble/default-announce-window-ms)))

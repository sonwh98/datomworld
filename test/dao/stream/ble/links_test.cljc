(ns dao.stream.ble.links-test
  "Host-free tests for the link state machine and the generic adapter
   (docs/design/dao.stream.ble.md 6): the discovery throttle and the
   connect policy, the link and frame deposits, the symmetric frame
   budget, the send rule, close, radio-down, and the seam wiring -- every
   deposit appended to the deposit writer in order, every action
   performed in order, and no policy anywhere else. The machine is pure,
   so every law is stated over explicit states and explicit clocks."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.ble :as ble]
            [dao.stream.ble.links :as links]
            [dao.stream.ringbuffer :as ringbuffer])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(def ^:private peer "peer-1")
(def ^:private peer-b "peer-2")


(defn- octets->bytes
  [octets]
  #?(:cljd (Uint8List.fromList (vec octets))
     :clj (byte-array (mapv unchecked-byte octets))
     :cljs (js/Uint8Array.from (to-array (vec octets)))))


(defn- octets-of
  [bs]
  (mapv (fn [b] (bit-and 0xff (int b))) (seq bs)))


(defn- state
  "The machine over a controllable clock: `!now` is an atom of integer
   milliseconds."
  [!now & {:keys [max-links announce-window-ms max-frame-bytes]}]
  (links/initial-state
    {:identity "ble-node"
     :max-links max-links
     :announce-window-ms announce-window-ms
     :max-frame-bytes max-frame-bytes
     :now-ms (fn [] @!now)}))


(defn- events-of
  [answer]
  (mapv :dao.stream.ble/event (:deposits answer)))


;; =============================================================================
;; The discovery throttle and the connect policy
;; =============================================================================


(deftest first-advertisement-deposits-and-connects
  (let [!now (atom 1000)
        s (state !now)
        {:keys [state deposits actions]}
        (links/handle-radio-event s [:adv peer -60])]
    (is (= [:dao.stream.ble/peer-seen] (events-of {:deposits deposits})))
    (is (= peer (:dao.stream.ble/peer (first deposits))))
    (is (= -60 (:dao.stream.ble/rssi (first deposits))))
    (is (= [[:connect peer]] actions))
    (testing "the window opened"
      (is (contains? (:announced state) peer)))))


(deftest advertisements-within-the-window-deposit-nothing
  (let [!now (atom 1000)
        s (state !now :announce-window-ms 2000)
        s1 (:state (links/handle-radio-event s [:adv peer -60]))
        s2 (:state (links/handle-radio-event s1 [:adv peer -70]))]
    (is (= [] (events-of (links/handle-radio-event s2 [:adv peer -80]))))
    (testing "past the window, the latest RSSI wins"
      (swap! !now + 2001)
      (let [{:keys [deposits]} (links/handle-radio-event s2 [:adv peer -80])]
        (is (= [:dao.stream.ble/peer-seen] (events-of {:deposits deposits})))
        (is (= -80 (:dao.stream.ble/rssi (first deposits))))))))


(deftest a-linked-peer-is-not-reconnected
  (let [!now (atom 1000)
        s (state !now)
        s1 (:state (links/handle-radio-event s [:adv peer -60]))
        s2 (:state (links/handle-radio-event s1 [:link-up peer :central]))]
    (testing "inside the window the advertisement deposits nothing"
      (is (= [] (events-of (links/handle-radio-event s2 [:adv peer -70])))))
    (testing "past the window the latest RSSI is deposited, and the peer
              is not reconnected -- it is linked"
      (swap! !now + 2001)
      (let [{:keys [deposits actions]}
            (links/handle-radio-event s2 [:adv peer -80])]
        (is (= [:dao.stream.ble/peer-seen] (events-of {:deposits deposits})))
        (is (= -80 (:dao.stream.ble/rssi (first deposits))))
        (is (= [] actions))))))


(deftest the-ceiling-refuses-nothing-and-connects-nothing
  (let [!now (atom 1000)
        s (state !now :max-links 1)
        s1 (:state (links/handle-radio-event s [:adv peer -60]))
        s2 (:state (links/handle-radio-event s1 [:link-up peer :central]))
        a3 (links/handle-radio-event s2 [:adv peer-b -60])
        st3 (:state a3)
        {:keys [deposits actions]} a3]
    (is (= [:dao.stream.ble/peer-seen] (events-of {:deposits deposits})))
    (is (= [] actions))
    (testing "the first peer's dial is pending: the ceiling counts it"
      (is (contains? (:connecting s1) peer)))
    (testing "the composition that wants more links raises :max-links,
              and the peer's next advertisement connects"
      (let [s3 (assoc st3 :max-links 2)]
        (swap! !now + 2001)
        (let [{:keys [actions]}
              (links/handle-radio-event s3 [:adv peer-b -60])]
          (is (= [[:connect peer-b]] actions)))))))


(deftest a-dialing-peer-is-not-dialed-again
  (let [!now (atom 1000)
        s (state !now)
        s1 (:state (links/handle-radio-event s [:adv peer -60]))]
    (swap! !now + 2001)
    (testing "the dial is already out; the next window dials nothing"
      (is (= [] (:actions (links/handle-radio-event s1 [:adv peer -60])))))
    (testing "a failed attempt frees the slot; the next window dials again"
      (let [s2 (:state (links/handle-radio-event s1 [:link-failed peer]))]
        (is (empty? (:connecting s2)))
        (swap! !now + 2001)
        (is (= [[:connect peer]]
               (:actions (links/handle-radio-event s2 [:adv peer -60]))))))))


(deftest an-inbound-link-always-deposits-whatever-the-ceiling
  (let [!now (atom 1000)
        s (state !now :max-links 1)
        s1 (:state (links/handle-radio-event s [:adv peer -60]))
        s2 (:state (links/handle-radio-event s1 [:link-up peer :central]))
        s3 (:state (links/handle-radio-event s2 [:link-up peer-b :peripheral]))
        a4 (links/handle-radio-event s3 [:link-up "peer-3" :peripheral])]
    (is (= [:dao.stream.ble/link-up] (events-of a4)))
    (is (= 2 (count (:links s3))))
    (is (= 3 (count (:links (:state a4)))))))


;; =============================================================================
;; Links and frames
;; =============================================================================


(deftest link-up-and-down-deposit-and-track-direction
  (let [!now (atom 0)
        s (state !now)
        s1 (:state (links/handle-radio-event s [:link-up peer :central]))
        s2 (:state (links/handle-radio-event s1 [:link-up peer-b :peripheral]))]
    (is (= :central (:direction (get (:links s1) peer))))
    (is (= :peripheral (:direction (get (:links s2) peer-b))))
    (is (= 2 (count (:links s2))))
    (let [{:keys [deposits]} (links/handle-radio-event s2 [:link-down peer])]
      (is (= [:dao.stream.ble/link-down :dao.stream.ble/peer-lost]
             (events-of {:deposits deposits}))))
    (is (= 1 (count (:links (:state (links/handle-radio-event
                                      s2 [:link-down peer]))))))))


(deftest a-link-down-for-an-unknown-peer-deposits-nothing
  (let [!now (atom 0)
        s (state !now)
        {:keys [deposits actions state]}
        (links/handle-radio-event s [:link-down peer])]
    (is (= [] deposits))
    (is (= [] actions))
    (is (= s state))))


(deftest close-deposits-a-link-down-for-every-live-link
  (let [!now (atom 0)
        s (:state (links/handle-radio-event (state !now) [:link-up peer :central]))
        s (:state (links/handle-radio-event s [:link-up peer-b :peripheral]))
        {:keys [deposits actions]}
        (links/close! s)]
    (is (= [:dao.stream.ble/closed
            :dao.stream.ble/link-down
            :dao.stream.ble/link-down]
           (events-of {:deposits deposits})))
    (is (= [[:stop-radio]] actions))))


(deftest frames-deposit-as-events-with-exact-bytes
  (let [!now (atom 0)
        s (state !now)
        {:keys [deposits]} (links/handle-radio-event s [:frame peer (octets->bytes [1 2 3])])]
    (is (= 1 (count deposits)))
    (is (ble/frame-event? (first deposits)))
    (is (= peer (:dao.stream.ble/source (first deposits))))
    (is (= [1 2 3] (octets-of
                     (base64/decode (:dao.stream.ble/bytes (first deposits))))))))


(deftest inbound-frames-over-the-budget-are-never-deposited
  (let [!now (atom 0)
        s (state !now :max-frame-bytes 4)
        over (octets->bytes [1 2 3 4 5])
        at (octets->bytes [1 2 3 4])]
    (is (= [] (events-of (links/handle-radio-event s [:frame peer over]))))
    (is (= 1 (count (:deposits (links/handle-radio-event s [:frame peer at])))))))


;; =============================================================================
;; The send rule
;; =============================================================================


(deftest a-send-without-a-link-is-the-clean-transport-error
  (let [!now (atom 0)
        s (state !now)
        {:keys [state outcome deposits actions]}
        (links/send! s peer (octets->bytes [1]))]
    (is (= :dao.stream/transport-error (:dao.stream/outcome outcome)))
    (is (= "no link to peer" (:dao.stream.ble/reason outcome)))
    (is (= [:dao.stream.ble/send-failed] (events-of {:deposits deposits})))
    (is (= peer (:dao.stream.ble/peer (first deposits))))
    (is (= [] actions))
    (is (= s state))))


(deftest a-send-over-a-link-is-an-action-and-ok
  (let [!now (atom 0)
        s (:state (links/handle-radio-event (state !now) [:link-up peer :central]))
        {:keys [outcome deposits actions]} (links/send! s peer (octets->bytes [1 2]))]
    (is (= :dao.stream/ok (:dao.stream/outcome outcome)))
    (is (= [] deposits))
    (is (= [[:send-frame peer [1 2]]]
           (mapv (fn [[k p bs]] [k p (octets-of bs)]) actions)))))


(deftest a-malformed-destination-fails-without-a-link-check
  (let [!now (atom 0)
        s (state !now)
        {:keys [outcome deposits actions]} (links/send! s "" (octets->bytes [1]))]
    (is (= :dao.stream/transport-error (:dao.stream/outcome outcome)))
    (is (= "malformed destination" (:dao.stream.ble/reason outcome)))
    (is (= 1 (count deposits)))
    (is (= [] actions))))


(deftest a-send-fail-the-host-reports-later-is-a-deposit
  (let [!now (atom 0)
        s (state !now)
        {:keys [deposits]} (links/handle-radio-event s [:send-fail peer "GATT busy"])]
    (is (= [:dao.stream.ble/send-failed] (events-of {:deposits deposits})))
    (is (= "GATT busy" (:dao.stream.ble/reason (first deposits))))))


;; =============================================================================
;; Close and radio-down
;; =============================================================================


(deftest close-is-idempotent-and-deposits-once
  (let [!now (atom 0)
        s (state !now)
        {:keys [state deposits actions]} (links/close! s)]
    (is (= [:dao.stream.ble/closed] (events-of {:deposits deposits})))
    (is (= [[:stop-radio]] actions))
    (testing "the second close answers without deposits or actions"
      (let [{:keys [deposits actions]} (links/close! state)]
        (is (= [] deposits))
        (is (= [] actions))))
    (testing "a closed machine answers every event with nothing"
      (let [{:keys [deposits actions]}
            (links/handle-radio-event state [:frame peer (octets->bytes [1])])]
        (is (= [] deposits))
        (is (= [] actions))))
    (testing "a send on a closed node answers closed"
      (is (= :dao.stream/closed (:dao.stream/outcome
                                  (:outcome (links/send! state peer (octets->bytes [1])))))))))


(deftest radio-down-loses-every-link-and-is-observably-gone
  (let [!now (atom 0)
        s (:state (links/handle-radio-event (state !now) [:link-up peer :central]))
        s (:state (links/handle-radio-event s [:link-up peer-b :peripheral]))
        {:keys [state deposits actions]}
        (links/handle-radio-event s [:radio-down "bluetooth off"])]
    (is (= [:dao.stream.ble/closed
            :dao.stream.ble/link-down
            :dao.stream.ble/link-down]
           (events-of {:deposits deposits})))
    (is (= [[:stop-radio]] actions))
    (testing "sends answer transport-error from here on"
      (let [{:keys [outcome]} (links/send! state peer (octets->bytes [1]))]
        (is (= :dao.stream/transport-error (:dao.stream/outcome outcome)))))))


(deftest an-unknown-radio-event-is-a-newer-radio-not-a-defect
  (let [!now (atom 0)
        s (state !now)
        {:keys [deposits actions]} (links/handle-radio-event s [:something-new peer 1])]
    (is (= [] deposits))
    (is (= [] actions))))


;; =============================================================================
;; The generic adapter
;; =============================================================================


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


(deftest the-seam-appends-deposits-and-performs-actions
  (let [!now (atom 0)
        deposit (ring 32)
        !actions (atom [])
        s (links/seam {:identity "ble-node"
                       :deposit deposit
                       :announce-window-ms 1000
                       :now-ms (fn [] @!now)
                       :radio {:perform! (fn [a] (swap! !actions conj a))}})]
    (testing "radio-up deposits bound"
      ((:radio! s) [:radio-up "self-id"])
      (is (= [:dao.stream.ble/bound]
             (mapv :dao.stream.ble/event (values deposit))))
      (is (= "self-id" (:dao.stream.ble/self (last (values deposit))))))
    (testing "an advertisement connects through the radio"
      ((:radio! s) [:adv peer -60])
      (is (= [[:connect peer]] @!actions))
      (is (= [:dao.stream.ble/bound :dao.stream.ble/peer-seen]
             (mapv :dao.stream.ble/event (values deposit)))))
    (testing "a send over a link is routed and ok, its late failure deposited"
      ((:radio! s) [:link-up peer :central])
      (let [outcome ((:send! s) peer (octets->bytes [1]))]
        (is (= :dao.stream/ok (:dao.stream/outcome outcome)))
        (is (= [[:connect peer] [:send-frame peer [1]]]
               (mapv (fn [a]
                       (if (= :send-frame (first a))
                         (update a 2 octets-of)
                         a))
                     @!actions)))
        ((:radio! s) [:send-fail peer "GATT busy"])
        (is (= "GATT busy" (:dao.stream.ble/reason (last (values deposit)))))))
    (testing "close runs the stop action, idempotently, and deposits the
              closed node's live links as gone"
      ((:close! s))
      ((:close! s))
      (is (= [[:connect peer] [:send-frame peer [1]] [:stop-radio]]
             (mapv (fn [a]
                     (if (= :send-frame (first a))
                       (update a 2 octets-of)
                       a))
                   @!actions)))
      (is (= [:dao.stream.ble/closed :dao.stream.ble/link-down]
             (->> (values deposit)
                  (filter ble/lifecycle-event?)
                  (mapv :dao.stream.ble/event)
                  (take-last 2)))))))


(deftest the-seam-refuses-a-radio-without-perform
  (is (thrown? #?(:cljd Object :clj Throwable :cljs js/Error)
        (links/seam {:identity "ble-node"
                     :deposit (ring 8)
                     :now-ms (fn [] 0)
                     :radio {}}))))


(deftest the-machine-refuses-a-clock-less-bind
  (is (thrown? #?(:cljd Object :clj Throwable :cljs js/Error)
        (links/initial-state {:identity "ble-node" :now-ms nil}))))

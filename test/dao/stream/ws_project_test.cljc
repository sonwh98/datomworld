(ns dao.stream.ws-project-test
  "The projection (dao.stream.remote.md 3.1's ws-project step) as a
   unit: value forwarding onto the ring, :ws/attachment filtering,
   :ws/error diagnostics dropped, cursor keeping across steps, a gap
   adopting the medium's recovery cursor, the four terminal and
   failure-resolution events closing the ring, and the medium's own
   end closing projection and ring alike -- channel loss then being
   the link's own end observation --, a closed session reaped with its
   slot reused by the next connection, and a second attach on one
   dial rejected."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as project]))


(def ^:private me "att-1")


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- payload
  [attachment v]
  {:ws/attachment attachment :ws/event :ws/payload :ws/value v})


(defn- event
  [attachment kind]
  {:ws/attachment attachment :ws/event kind})


(defn- deposit!
  "Deposit one envelope onto the traffic medium, as the host adapter
   would."
  [medium envelope]
  (stream/append! medium envelope))


(defn- composed
  "A projection over a fresh traffic medium and channel ring, both
   capacity `cap`, with the reading cursor minted before anything is
   deposited -- the composition discipline of dao.stream.ws.md."
  ([cap]
   (let [medium (ring cap)
         channel (ring cap)
         cursor (:dao.stream/cursor
                  (stream/cursor medium stream/anchor-newest))]
     {:medium medium
      :channel channel
      :project (project/projection {:attachment me
                                    :traffic medium
                                    :cursor cursor
                                    :ring channel})}))
  ([] (composed 64)))


(defn- read-all
  "Every retained read of `h`, oldest first, as raw outcome maps, so a
   test can tell a read of nil from no read."
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc r))
        acc))))


(defn- values
  "Every retained value of `h`, oldest first."
  [h]
  (mapv :dao.stream/value (read-all h)))


(defn- newest-cursor
  [h]
  (:dao.stream/cursor (stream/cursor h stream/anchor-newest)))


(defn- blocked?
  "True while `h` reads blocked at its newest position: nothing new."
  [h]
  (let [newest (:dao.stream/cursor (stream/cursor h stream/anchor-newest))]
    (= :dao.stream/blocked (:dao.stream/outcome (stream/next h newest)))))


(defn- remote-descriptor
  "A remote descriptor naming identity `id` over a ws channel
   descriptor -- the dial-attach! input."
  [id]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity id
   :dao.stream/channel {:dao.stream/type :dao.stream/ws
                        :dao.stream/identity "toy"}})


(defn- dialed
  "A dial composed over `medium` and the channel ring with a fake
   attacher: the attach succeeds at once with a fresh ring buffer as
   the socket handle, so the dial-side composition runs without a
   host. The traffic cursor is minted before any attach!, as
   dao.stream.ws.md composes one medium per active client attachment."
  [medium channel-ring]
  (project/dial
    {:attach! (fn [_]
                {:dao.stream/outcome :dao.stream/ok
                 :dao.stream/handle (ring 8)
                 :dao.stream/attachment "sock-1"})
     :traffic {:dao.stream/handle medium
               :dao.stream/surface #{:writer}}
     :cursor (newest-cursor medium)
     :ring channel-ring
     :table {}}))


(deftest forwards-each-payload-value-onto-the-ring-in-order
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :a))
    (deposit! medium (payload me "two"))
    (deposit! medium (payload me nil))
    (project/step! project)
    (is (= [:a "two" nil] (values channel))
        "one ring value per payload, in deposit order")
    (let [third (last (read-all channel))]
      (is (contains? third :dao.stream/value))
      (is (nil? (:dao.stream/value third))
          "nil rides as a value: the event carries :ws/value, so nil
           is forwarded, not dropped"))
    (is (not (project/closed? project))
        "payloads are not terminal")))


(deftest keeps-only-the-events-of-this-channel
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload "att-2" :foreign))
    (deposit! medium (payload me :mine))
    (deposit! medium (event "att-2" :ws/closed))
    (deposit! medium (payload nil :orphan))
    (project/step! project)
    (is (= [:mine] (values channel))
        "another attachment's payloads and events, and an event with no
         attachment at all, are not this channel's")
    (is (not (project/closed? project))
        "another attachment's terminal event does not end this channel")))


(deftest drops-error-diagnostics-and-keeps-projecting
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :before))
    (deposit! medium {:ws/attachment me :ws/event :ws/error
                      :ws/reason :ws/decode-failure})
    (deposit! medium (payload me :after))
    (project/step! project)
    (is (= [:before :after] (values channel))
        "the diagnostic appends nothing, its neighbours both forward")
    (is (not (project/closed? project))
        ":ws/error is never terminal by itself")
    (project/step! project)
    (is (blocked? channel)
        "the cursor advanced past all three events: nothing re-reads")))


(deftest cursor-keeping-spans-steps-without-re-reading
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :one))
    (project/step! project)
    (is (= [:one] (values channel)))
    (deposit! medium (payload me :two))
    (project/step! project)
    (is (= [:one :two] (values channel))
        "each step reads forward from where the last stopped")
    (project/step! project)
    (project/step! project)
    (is (= [:one :two] (values channel))
        "a quiet medium advances nothing")))


(deftest payload-then-terminal-in-one-step-forward-then-close
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :x))
    (deposit! medium (event me :ws/closed))
    (project/step! project)
    (is (= [:x] (values channel))
        "the payload ahead of the terminal event still forwarded")
    (is (project/closed? project))
    (is (= :dao.stream/end
           (:dao.stream/outcome (stream/next channel (newest-cursor channel))))
        "the closed ring reads end at its tail: channel loss is the
         link's observation (2.4)")))


(deftest terminal-lifecycle-events-close-the-ring
  (doseq [kind [:ws/closed :ws/ended]]
    (testing kind
      (let [{:keys [medium channel project]} (composed)]
        (deposit! medium (payload me :last))
        (deposit! medium (event me kind))
        (project/step! project)
        (is (project/closed? project))
        (is (= [:last] (values channel)))
        (deposit! medium (payload me :late))
        (project/step! project)
        (is (= [:last] (values channel))
            "a step past the terminal event projects nothing: the
             projection stopped on it")
        (is (= :dao.stream/closed
               (:dao.stream/outcome (stream/append! channel :x)))
            "the ring refuses further appends")))))


(deftest failure-resolutions-close-the-ring
  (doseq [kind [:ws/not-found :ws/transport-error]]
    (testing kind
      (let [{:keys [medium channel project]} (composed)]
        (deposit! medium (payload me :before))
        (deposit! medium (event me kind))
        (project/step! project)
        (is (project/closed? project))
        (is (= [:before] (values channel)))
        (is (= :dao.stream/end
               (:dao.stream/outcome
                 (stream/next channel (newest-cursor channel)))))))))


(deftest resolution-and-other-kinds-are-not-payload
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (event me :ws/opened))
    (deposit! medium {:ws/attachment me :ws/event :ws/accepted
                      :ws/handle {:dao.stream/handle (ring 1)
                                  :dao.stream/surface #{:writer}}})
    (deposit! medium {:ws/attachment me :ws/event :ws/payload})
    (project/step! project)
    (is (= [] (values channel))
        "openings, offers and a payload without :ws/value append
         nothing")
    (is (not (project/closed? project))
        "none of them is terminal")))


(deftest a-gap-adopts-the-mediums-recovery-cursor
  (let [{:keys [medium channel project]} (composed 1)]
    (deposit! medium (payload me :lost))
    (deposit! medium (payload me :kept))
    (project/step! project)
    (is (= [:kept] (values channel))
        "the evicted event is lost, the retained one forwards")
    (is (not (project/closed? project))
        "a medium gap is not channel loss")))


(deftest the-mediums-own-end-closes-the-projection-and-the-ring
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :only))
    (stream/close! medium)
    (project/step! project)
    (is (= [:only] (values channel))
        "what the medium retained before it ended still forwards")
    (is (project/closed? project)
        "a dead medium gives the projection nothing further to read")
    (is (= :dao.stream/end
           (:dao.stream/outcome (stream/next channel (newest-cursor channel))))
        "the ring is closed with the projection: a link waiting on it
         observes channel loss instead of blocking forever")))


(deftest the-mediums-end-is-channel-loss-for-the-link
  (let [{:keys [medium channel]} (composed)
        dial (dialed medium channel)
        refl (:dao.stream/handle
               (project/dial-attach! dial (remote-descriptor "str-1")))]
    (is (some? refl) "the link attached over the dialed channel")
    (stream/close! medium)
    (project/dial-step! dial)
    (is (project/closed? (:project (project/channel dial)))
        "the medium's end stopped the projection")
    (is (= :dao.stream/end
           (:dao.stream/outcome (stream/next channel (newest-cursor channel))))
        "the ring reads end at its tail, never a blocked wait")
    (let [r (stream/cursor refl stream/anchor-oldest)]
      (is (= :dao.stream/transport-error (:dao.stream/outcome r)))
      (is (= :dao.stream.remote/channel-gone (:dao.stream.remote/reason r))
          "the link observed the channel's loss (2.4): with the medium
           ended, nothing further could ever arrive"))))


(deftest a-second-attach-on-one-dial-is-a-composition-error
  (let [{:keys [medium channel]} (composed)
        dial (dialed medium channel)
        d (remote-descriptor "str-1")]
    (is (= :dao.stream/ok
           (:dao.stream/outcome (project/dial-attach! dial d)))
        "the first attach carries the dial's one active attachment")
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (project/dial-attach! dial d))
        "a further attach! on the same dial is rejected: its ring and
         traffic cursor already belong to the first attachment, and a
         reattachment composes a fresh dial with a fresh cursor")))


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn- acceptor-over
  "An acceptor over a real one-slot endpoint, with `table` as the
   mirror's table. Offers and acknowledgements ride fresh capacity-one
   ring buffers; the offers medium is returned so a test can deposit
   accepted offers as the transport's upgrade callback would.
   :make-media hands out one fresh traffic medium, channel ring and
   cursor per accepted offer, each recorded in `media` for the test to
   drive and observe."
  [media table]
  (let [offers (ring 1)
        acks (ring 1)
        endpoint (ws/make-endpoint
                   {:descriptor
                    {:dao.stream/type :dao.stream/ws
                     :dao.stream/identity "toy"
                     :ws/host "127.0.0.1"
                     :ws/port 1
                     :ws/path "/toy"}
                    :control {:dao.stream/handle (ring 16)
                              :dao.stream/surface #{:writer}}
                    :control-admission
                    {:retention :evict-oldest :capacity 16
                     :value-domain :portable-values}
                    :slots [{:offer {:dao.stream/handle offers
                                     :dao.stream/surface #{:writer}}
                             :offer-admission handoff-admission
                             :ack {:dao.stream/handle acks
                                   :dao.stream/surface #{:writer}}
                             :ack-admission handoff-admission
                             :ack-cursor (newest-cursor acks)}]
                    :expiry-ms nil})]
    {:offers offers
     :acceptor
     (project/make-acceptor
       {:endpoint endpoint
        :slots [{:offer-reader offers
                 :offer-cursor (newest-cursor offers)
                 :ack-writer {:dao.stream/handle acks
                              :dao.stream/surface #{:writer}}}]
        :table table
        :make-media
        (fn [_]
          (let [traffic (ring 64)
                channel-ring (ring 64)
                m {:traffic traffic :ring channel-ring}]
            (swap! media conj m)
            {:traffic {:dao.stream/handle traffic
                       :dao.stream/surface #{:writer}}
             :admission {:retention :evict-oldest :capacity 64
                         :value-domain :portable-values}
             :reader traffic
             :cursor (newest-cursor traffic)
             :ring channel-ring}))})}))


(defn- offer!
  "Deposit one accepted offer for attachment `id` whose socket handle
   is `handle`, as the transport's upgrade callback would."
  [offers id handle]
  (stream/append! offers
                  {:ws/attachment id
                   :ws/event :ws/accepted
                   :ws/handle {:dao.stream/handle handle
                               :dao.stream/surface
                               #{:writer :closable}}}))


(deftest a-closed-session-is-reaped-and-its-slot-is-reused
  (let [media (atom [])
        served (ring 16)
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle served
                                       :surface #{:reader}}})
        sock-1 (ring 8)]
    (offer! offers "att-1" sock-1)
    (project/accept-step! acceptor 1)
    (is (= #{"att-1"} (set (keys (project/sessions acceptor))))
        "the first connection was adopted")
    (let [{:keys [traffic]} (last @media)
          req {:dao.stream/identity "str-1"
               :dao.stream.remote/op :dao.stream/descriptor
               :dao.stream.remote/args []
               :dao.stream.remote/id 7}]
      (deposit! traffic (payload "att-1" req))
      (deposit! traffic (event "att-1" :ws/closed))
      (project/accept-step! acceptor 2)
      (is (empty? (project/sessions acceptor))
          "the closed session was reaped, not stepped on forever")
      (is (= #{7} (set (map :dao.stream.remote/id (values sock-1))))
          "the retained request was answered in the reap tick's last
           mirror pass")
      (let [sock-2 (ring 8)]
        (offer! offers "att-2" sock-2)
        (project/accept-step! acceptor 3)
        (is (= #{"att-2"} (set (keys (project/sessions acceptor))))
            "a second connection was adopted; the first's state is
             gone")
        (let [{:keys [traffic ring]} (last @media)]
          (deposit! traffic (payload "att-2" :works))
          (project/accept-step! acceptor 4)
          (is (= [:works] (values ring))
              "the second session projects onto its own fresh ring"))))))

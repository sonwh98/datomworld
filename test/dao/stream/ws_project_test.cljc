(ns dao.stream.ws-project-test
  "The projection (dao.stream.remote.md 3.1's ws-project step) as a
   unit: value forwarding onto the ring, :ws/attachment filtering,
   :ws/error diagnostics dropped, cursor keeping across steps, a gap
   adopting the medium's recovery cursor, the four terminal and
   failure-resolution events closing the ring, and the medium's own
   end closing projection and ring alike -- channel loss then being
   the link's own end observation --, a closed session reaped with its
   slot reused by the next connection, and a second attach on one
   dial rejected. The resource bounds (dao.stream.remote.md 3.0): a
   step event budget, newcomers rejected at :max-sessions, idle
   sessions reaped with their handle and ring closed, activity
   resetting the idle clock, and one session's failure isolated from
   the rest."
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
   drive and observe. `config`, optional, merges into the acceptor's
   composition (its :max-sessions and :idle-timeout bounds)."
  ([media table] (acceptor-over media table {}))
  ([media table config]
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
      :acks acks
      :acceptor
      (project/make-acceptor
        (merge
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
                :ring channel-ring}))}
          config))})))


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


(deftest step-respects-event-budget
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :a))
    (deposit! medium (payload me :b))
    (deposit! medium (payload me :c))
    (deposit! medium (payload me :d))
    ;; Step with budget 2: reads only 2 events
    (project/step! project 2)
    (is (= [:a :b] (values channel))
        "budget of 2 consumes exactly 2 events even though 4 were deposited")
    ;; Step with budget 1: reads 1 more event
    (project/step! project 1)
    (is (= [:a :b :c] (values channel))
        "budget of 1 consumes the next event")
    ;; Step with nil budget: reads remaining events to blocked
    (project/step! project)
    (is (= [:a :b :c :d] (values channel))
        "unbounded step consumes all remaining events")))


(deftest an-invalid-step-budget-is-a-composition-error
  (let [{:keys [medium channel project]} (composed)]
    (deposit! medium (payload me :a))
    (doseq [bad [0 -1 1.5]]
      (testing bad
        (is (= {:budget bad}
               (try (project/step! project bad) nil
                    (catch #?(:clj Exception :cljs :default :cljd Object) e
                      (ex-data e))))
            "an ex-info carrying the rejected budget")))
    (is (= [] (values channel))
        "a rejected budget reads nothing")))


(deftest max-sessions-rejects-newcomers-at-cap
  (let [media (atom [])
        {:keys [acceptor offers acks]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}}
                       {:max-sessions 1})
        sock-1 (ring 8)
        sock-2 (ring 8)]
    (offer! offers "att-1" sock-1)
    (project/accept-step! acceptor 100)
    (is (= #{"att-1"} (set (keys (project/sessions acceptor))))
        "the first connection is admitted under :max-sessions 1")
    (offer! offers "att-2" sock-2)
    (project/accept-step! acceptor 101)
    (is (= #{"att-1"} (set (keys (project/sessions acceptor))))
        "the newcomer is rejected at the active cap")
    (is (= ["att-1"] (mapv :ws/attachment (values acks)))
        "no accept acknowledgement was written for the newcomer")
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! sock-2 :test)))
        "the rejected newcomer's offered handle was closed")
    (is (= 1 (count @media))
        "no media were composed for the rejected newcomer")))


(deftest idle-sessions-are-reaped-and-free-capacity
  (let [media (atom [])
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}}
                       {:max-sessions 1 :idle-timeout 50})
        sock-1 (ring 8)
        sock-2 (ring 8)]
    (offer! offers "att-1" sock-1)
    (project/accept-step! acceptor 100)
    (is (= #{"att-1"} (set (keys (project/sessions acceptor)))))
    (project/accept-step! acceptor 140)
    (is (= #{"att-1"} (set (keys (project/sessions acceptor))))
        "40ms idle is under the 50ms timeout")
    (offer! offers "att-2" sock-2)
    (project/accept-step! acceptor 160)
    (is (= #{"att-2"} (set (keys (project/sessions acceptor))))
        "the idle session is reaped before admission, freeing the cap
         for the newcomer")
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! sock-1 :test)))
        "the reaped session's handle was closed")
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! (:ring (first @media)) :x)))
        "the reaped session's channel ring was closed")))


(deftest idle-reaping-runs-on-a-tick-without-offers
  (let [media (atom [])
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}}
                       {:idle-timeout-ms 50})
        sock-1 (ring 8)]
    (offer! offers "att-1" sock-1)
    (project/accept-step! acceptor 100)
    (project/accept-step! acceptor 150)
    (is (empty? (project/sessions acceptor))
        ":idle-timeout-ms is honoured and reaping needs no newcomer")
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! sock-1 :test))))))


(deftest session-activity-resets-idle-timeout
  (let [media (atom [])
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}}
                       {:idle-timeout 50})]
    (offer! offers "att-1" (ring 8))
    (project/accept-step! acceptor 100)
    (deposit! (:traffic (last @media))
              (payload "att-1" {:dao.stream/identity "str-1"
                                :dao.stream.remote/op :dao.stream/descriptor
                                :dao.stream.remote/args []
                                :dao.stream.remote/id 1}))
    (project/accept-step! acceptor 130)
    (project/accept-step! acceptor 170)
    (is (= #{"att-1"} (set (keys (project/sessions acceptor))))
        "70ms since admission but 40ms since the activity at 130")
    (project/accept-step! acceptor 180)
    (is (empty? (project/sessions acceptor))
        "50ms since the last activity: reaped")))


(deftest no-bounds-keeps-sessions-unbounded
  (let [media (atom [])
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}})]
    (doseq [[t att] [[1 "att-1"] [2 "att-2"] [3 "att-3"]]]
      (offer! offers att (ring 8))
      (project/accept-step! acceptor t))
    (project/accept-step! acceptor 1000000)
    (is (= #{"att-1" "att-2" "att-3"} (set (keys (project/sessions acceptor))))
        "without :max-sessions or :idle-timeout nothing is rejected or
         expired")))


(deftest invalid-bounds-are-a-composition-error
  (doseq [bad [{:max-sessions 0} {:max-sessions -1} {:max-sessions 1.5}
               {:idle-timeout 0} {:idle-timeout-ms -5} {:idle-timeout 2.5}
               {:step-budget 0} {:step-budget 1.5} {:step-budget -1}
               {:mirror-budget 0} {:mirror-budget 1.5} {:mirror-budget -1}
               {:chase-budget 0} {:chase-budget 1.5} {:chase-budget -1}]]
    (testing bad
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (acceptor-over (atom []) {} bad)))
      (when-some [dial-bad (not-empty (select-keys bad [:step-budget
                                                        :mirror-budget
                                                        :chase-budget]))]
        (let [{:keys [medium channel]} (composed)]
          (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
                (project/dial
                  (merge {:attach! (fn [_] nil)
                          :traffic {:dao.stream/handle medium
                                    :dao.stream/surface #{:writer}}
                          :cursor (newest-cursor medium)
                          :ring channel
                          :table {}}
                         dial-bad)))))))))


(deftest session-error-isolation
  (let [media (atom [])
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}})
        sock-1 (ring 8)
        sock-2 (ring 8)]
    (offer! offers "att-1" sock-1)
    (project/accept-step! acceptor 100)
    (offer! offers "att-2" sock-2)
    (project/accept-step! acceptor 101)
    (is (= #{"att-1" "att-2"} (set (keys (project/sessions acceptor))))
        "both sessions admitted")
    ;; A projection over no traffic medium: its step! throws.
    (swap! acceptor assoc-in [:sessions "att-1" :project]
           (atom {:closed? false :traffic nil :cursor nil :ring nil}))
    (deposit! (:traffic (second @media))
              (payload "att-2" {:dao.stream/identity "str-1"
                                :dao.stream.remote/op :dao.stream/descriptor
                                :dao.stream.remote/args []
                                :dao.stream.remote/id 42}))
    (project/accept-step! acceptor 102)
    (is (= #{"att-2"} (set (keys (project/sessions acceptor))))
        "the failing session is reaped, the healthy one kept")
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! sock-1 :test)))
        "the failing session's handle was closed")
    (is (= #{42} (set (map :dao.stream.remote/id (values sock-2))))
        "the healthy session answered its request in the same tick")))


(deftest a-flooded-session-cannot-starve-its-peers
  (let [media (atom [])
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle (ring 16) :surface #{:reader}}}
                       {:step-budget 2})
        sock-2 (ring 8)]
    (offer! offers "att-1" (ring 8))
    (project/accept-step! acceptor 1)
    (offer! offers "att-2" sock-2)
    (project/accept-step! acceptor 2)
    (is (= #{"att-1" "att-2"} (set (keys (project/sessions acceptor)))))
    (let [[flooded healthy] @media]
      (doseq [v [:a :b :c :d :e]]
        (deposit! (:traffic flooded) (payload "att-1" v)))
      (deposit! (:traffic healthy)
                (payload "att-2" {:dao.stream/identity "str-1"
                                  :dao.stream.remote/op :dao.stream/descriptor
                                  :dao.stream.remote/args []
                                  :dao.stream.remote/id 9}))
      (project/accept-step! acceptor 3)
      (is (= [:a :b] (values (:ring flooded)))
          ":step-budget 2 bounds the flooded session's projection per tick")
      (is (= #{9} (set (map :dao.stream.remote/id (values sock-2))))
          "the other session was answered in the same tick")
      (project/accept-step! acceptor 4)
      (is (= [:a :b :c :d] (values (:ring flooded)))
          "the flood drains two events per tick, its cursor kept"))))


(defn- served-with
  "A served ring holding `vs`, and a next request on it from oldest
   with id `id` asking a chase of `budget`."
  [vs id budget]
  (let [s (ring 16)]
    (doseq [v vs] (stream/append! s v))
    {:served s
     :next-req {:dao.stream/identity "str-1"
                :dao.stream.remote/op :dao.stream/next
                :dao.stream.remote/args
                [(:dao.stream/cursor (stream/cursor s stream/anchor-oldest))]
                :dao.stream.remote/id id
                :dao.stream.remote/budget budget}}))


(defn- descriptor-req
  [id]
  {:dao.stream/identity "str-1"
   :dao.stream.remote/op :dao.stream/descriptor
   :dao.stream.remote/args []
   :dao.stream.remote/id id})


(defn- answers
  "The answers retained on socket handle `h`: values carrying no op."
  [h]
  (remove #(contains? % :dao.stream.remote/op) (values h)))


(deftest mirror-budget-and-chase-budget-reach-the-session-mirror
  (let [media (atom [])
        {:keys [served next-req]} (served-with [:a :b :c :d] 1 5)
        {:keys [acceptor offers]}
        (acceptor-over media {"str-1" {:handle served :surface #{:reader}}}
                       {:mirror-budget 1 :chase-budget 2})
        sock (ring 8)]
    (offer! offers "att-1" sock)
    (project/accept-step! acceptor 1)
    (deposit! (:traffic (last @media)) (payload "att-1" next-req))
    (deposit! (:traffic (last @media)) (payload "att-1" (descriptor-req 2)))
    (project/accept-step! acceptor 2)
    (is (= [1] (mapv :dao.stream.remote/id (answers sock)))
        ":mirror-budget 1 answers one request this tick")
    (is (= 1 (count (:dao.stream.remote/more (first (answers sock)))))
        ":chase-budget 2 clamps the peer's budget of 5")
    (project/accept-step! acceptor 3)
    (is (= [1 2] (mapv :dao.stream.remote/id (answers sock)))
        "the second request is answered on the next tick")))


(deftest dial-bounds-reach-projection-and-mirror
  (let [{:keys [medium channel]} (composed)
        {:keys [served next-req]} (served-with [:a :b :c :d] 1 5)
        dial (project/dial
               {:attach! (fn [_]
                           {:dao.stream/outcome :dao.stream/ok
                            :dao.stream/handle (ring 8)
                            :dao.stream/attachment "sock-1"})
                :traffic {:dao.stream/handle medium
                          :dao.stream/surface #{:writer}}
                :cursor (newest-cursor medium)
                :ring channel
                :table {"str-1" {:handle served :surface #{:reader}}}
                :step-budget 2
                :mirror-budget 1
                :chase-budget 2})]
    (project/dial-attach! dial (remote-descriptor "other"))
    (let [sock (:handle (project/channel dial))]
      (deposit! medium (payload "sock-1" next-req))
      (deposit! medium (payload "sock-1" (descriptor-req 2)))
      (deposit! medium (payload "sock-1" (descriptor-req 3)))
      (project/dial-step! dial)
      (is (= 2 (count (filter #(contains? % :dao.stream.remote/op)
                              (values channel))))
          ":step-budget 2 projects two of the three requests")
      (is (= [1] (mapv :dao.stream.remote/id (answers sock)))
          ":mirror-budget 1 answers one")
      (is (= 1 (count (:dao.stream.remote/more (first (answers sock)))))
          ":chase-budget 2 clamps the peer's budget of 5")
      (project/dial-step! dial)
      (project/dial-step! dial)
      (is (= [1 2 3] (mapv :dao.stream.remote/id (answers sock)))
          "the rest follow one per tick"))))


(deftest dial-mirror-effects-run-once-per-step
  ;; The served writer replaces the dial's mirror cursor with an equal
  ;; but distinct value while its append runs: a mirror step run inside
  ;; swap! would then retry and apply the append a second time.
  (let [{:keys [medium channel]} (composed)
        dial-ref (atom nil)
        applied (atom [])
        target (reify stream/IDaoStreamWriter
                 (append!
                   [_ v]
                   (when (= 1 (count (swap! applied conj v)))
                     (swap! (:mirror-cursor @@dial-ref) #(into {} %)))
                   {:dao.stream/outcome :dao.stream/ok}))
        dial (project/dial
               {:attach! (fn [_]
                           {:dao.stream/outcome :dao.stream/ok
                            :dao.stream/handle (ring 8)
                            :dao.stream/attachment "sock-1"})
                :traffic {:dao.stream/handle medium
                          :dao.stream/surface #{:writer}}
                :cursor (newest-cursor medium)
                :ring channel
                :table {"w" {:handle target :surface #{:writer}}}})]
    (reset! dial-ref dial)
    (project/dial-attach! dial (remote-descriptor "other"))
    (deposit! medium (payload "sock-1" {:dao.stream/identity "w"
                                        :dao.stream.remote/op
                                        :dao.stream/append!
                                        :dao.stream.remote/args [:v]
                                        :dao.stream.remote/id 1}))
    (project/dial-step! dial)
    (is (= [:v] @applied) "the source append ran exactly once")
    (is (= [1] (mapv :dao.stream.remote/id
                     (answers (:handle (project/channel dial)))))
        "and was answered once")))

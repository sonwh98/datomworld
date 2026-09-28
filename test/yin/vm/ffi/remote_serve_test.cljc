(ns yin.vm.ffi.remote-serve-test
  "Slice 3a, the FFI export binding: open! refuses an incomplete
   assembly before anything is published; serve! is stable per live
   handle by reference identity -- the two serve! calls lift-frame makes
   for one handle yield one identity and one table entry -- and refuses
   what the gate, the surface policy, the capacity or a closed binding
   refuses; retire! unpublishes first, is idempotent, and a re-served
   handle gets a new identity while the old one answers not-found;
   close! retires everything and a remote peer's unresolved append ends
   as append-unknown; a refused whole-frame lift leaves no provisional
   export behind.

   Slice 3b, the lease wiring: every served identity is granted to a
   renewal medium served beside it; a remote holder composed with
   dao.lease/make-holder renews and releases through a real
   dao.stream.remote reflection; the judge runs in step on tick data the
   test deposits (no clock); expiry and release reclaim through the same
   retire! transition, unpublishing before :lapsed is recorded; a
   detached channel retires nothing and reattach! keeps identities,
   cursors and leases; an in-flight append at reclaim is answered
   not-found or ends append-unknown, never retried against a new
   tenure; open! refuses each missing or malformed lease option; and
   reclaim is idempotent."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.ffi.remote-serve :as rs]
            [yin.vm.ucf.remote :as ucf.remote]))


;; =============================================================================
;; The toy: two in-process ring buffers as the channel (as
;; dao.stream.remote-test); the binding is peer B
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(def ^:private chan
  {:dao.stream/type :dao.stream.test/channel
   :dao.stream/identity "remote-serve-toy"})


(def ^:private per-author-medium
  {:retention :evict-oldest
   :capacity 64
   :value-domain :portable-values
   :attribution :per-author-media})


(defn- pair
  []
  (let [ab (ring 1024)
        ba (ring 1024)]
    {:ab ab, :ba ba,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba}}))


(defn- toy
  "The channel pair, and the lease wiring's own media: the judge's tick
   stream the host drive deposits readings on, a standing
   lease-proposals medium, and the grantor's writer."
  []
  (let [ticks (ring 256)
        proposals (ring 64)]
    (assoc (pair)
           :ticks ticks
           :proposals proposals
           :grants (ring 256))))


(defn- lease-opts
  [t]
  {::rs/lease-duration {:ms 10}
   ::rs/lease-tolerance {:ms 2}
   ::rs/lease-cadence {:ms 1}
   ::rs/lease-ticks [{:handle (:ticks t) :cursor (oldest (:ticks t))}]
   ::rs/lease-media [{:handle (:proposals t)
                      :cursor (oldest (:proposals t))
                      :source :lease-proposals
                      :medium per-author-medium}]
   ::rs/lease-writer (:grants t)
   ::rs/lease-renewal-capacity 16})


(defn- opts
  "A complete assembly over `t`'s B end, declared exclusive to the
   binding: every handle admitted, served with `surface`, at most
   `capacity` live exports, leased by `lease-opts`."
  ([t] (opts t (constantly #{:reader :writer}) 8))
  ([t surface capacity]
   (merge {::rs/channel (:b-end t)
           ::rs/channel-exclusive? true
           ::rs/channel-descriptor chan
           ::rs/surface surface
           ::rs/admit? (constantly true)
           ::rs/capacity capacity
           ::rs/step-budget 64}
          (lease-opts t))))


(defn- values
  [h]
  (loop [c (oldest h)
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- ask
  "Put one raw wire request for `identity` on A's side, run one binding
   step, and return the answer carrying `id` from B's answers."
  [t b identity id op args]
  (stream/append! (:ab t) {:dao.stream/identity identity
                           :dao.stream.remote/op op
                           :dao.stream.remote/args args
                           :dao.stream.remote/id id})
  (rs/step b)
  (some #(when (= id (:dao.stream.remote/id %)) %) (values (:ba t))))


(defn- served-table
  "The published table's served identities only, without the renewal
   entries beside them."
  [b]
  (select-keys (rs/table b) (map :identity (rs/entries b))))


(defn- tick!
  "The host drive: deposit one reading on the judge's tick stream, as
   data."
  [t ms]
  (stream/append! (:ticks t) (lease/tick {:ms ms})))


(defn- tick-step!
  [t b ms]
  (tick! t ms)
  (rs/step b))


(defn- lapses
  [t]
  (filter #(= :dao.lease/lapsed (:dao.lease/status %)) (values (:grants t))))


(defn- grant-of
  "The grant for served `identity` on the grantor's writer."
  [t identity]
  (some #(when (and (= :dao.lease/accepted (:dao.lease/status %))
                    (= identity (:dao.lease/subject %)))
           %)
        (values (:grants t))))


(defrecord ValueHandle
  [id]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type :dao.stream.test/value
                             :dao.stream/identity id}
     :dao.stream/identity id})


  stream/IDaoStreamReader

  (cursor [_ _] {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})


  (next [_ _] {:dao.stream/outcome :dao.stream/blocked}))


;; the retained call's frame: call-in is written by the remote retry,
;; call-out read at the kept response cursor -- the frame whose lift
;; asks serve! for call-out twice (the response marker, then its cell)
(defn- retained-resources
  [call-in call-out]
  {vm/call-in-stream-key call-in,
   vm/call-out-stream-key call-out,
   vm/call-out-cursor-key
   {:stream-id vm/call-out-stream-key,
    :cursor (:dao.stream/cursor (stream/cursor call-out :dao.stream/oldest))}})


(def ^:private retained-entry
  {:reason :put, :request-sent true, :call-id :call-7,
   :op :op/add, :stream-id vm/call-in-stream-key,
   :datom (assoc (apply2/request :call-7 :op/add [1 2])
                 :producer/nonce "n-1")})


(defn- call-pair-surface
  [call-in]
  (fn [h] (if (identical? h call-in) #{:writer} #{:reader})))


;; =============================================================================
;; The remote peer and its holder
;; =============================================================================

(defn- peer
  "Peer A over end `a-end`: an attacher for the channel and the event
   writer its links emit append outcomes on."
  [a-end]
  (let [events (ring 1024)]
    {:events events
     :attach! (remote/attacher {:dao.stream.remote/channels {chan a-end}
                                :dao.stream.remote/events events})}))


(defn- reflect
  [p identity]
  (:dao.stream/handle
    ((:attach! p) {:dao.stream/type :dao.stream/remote
                   :dao.stream/identity identity
                   :dao.stream/channel chan})))


(defn- poll!
  "Ask `f` until it answers something other than blocked or retry,
   stepping the binding between asks."
  [b f]
  (loop [n 0]
    (let [r (f)]
      (if (and (< n 8)
               (or (= :dao.stream/blocked (:dao.stream/outcome r))
                   (:dao.stream/retry? r)))
        (do (rs/step b) (recur (inc n)))
        r))))


(defn- gone-after-step
  "Append `v` through reflection `r`, step the binding, and append again:
   the second append's local answer, once the first was answered."
  [b r v]
  (stream/append! r v)
  (rs/step b)
  (stream/append! r v))


(defn- events-of
  "The append! outcomes peer `p`'s links emitted for `identity` -- not
   the attach probe's confirmation, which carries the descriptor."
  [p identity]
  (filter #(and (= identity (:dao.stream/identity %))
                (not (contains? % :dao.stream/descriptor)))
          (values (:events p))))


(def ^:private renewal-medium
  {:retention :evict-oldest
   :capacity 16
   :value-domain :portable-values
   :attribution :per-author-media})


(defn- holder
  "The remote holder, composed with dao.lease/make-holder on peer A. Its
   grant reaches its own fact medium by carriage (the composition
   delivers the grantor's grant); its OWN tick stream is fed by the same
   host drive; its outbound medium is a reflection of the served
   renewal medium, the medium that attributes its renewals to it."
  [p grant]
  (let [ticks (ring 256)
        facts (ring 16)
        refl (reflect p (:dao.lease/holder grant))
        composed (lease/make-holder
                   {:self (:dao.lease/holder grant)
                    :grantor rs/grantor
                    :units rs/lease-units
                    :resolver (fn [source _fact] source)
                    :resolver-bindings #{:per-author-media}
                    :renewal-interval (lease/renewal-interval
                                        rs/lease-units
                                        (:dao.lease/duration grant)
                                        {:ms 3}
                                        {:ms 1})
                    :subject (:dao.lease/subject grant)
                    :tick {:handle ticks :cursor (oldest ticks)}
                    :fact {:handle facts
                           :cursor (oldest facts)
                           :source rs/grantor
                           :medium per-author-medium}
                    :writer {:handle refl :medium renewal-medium}})]
    (stream/append! facts grant)
    (atom {:composed composed
           :state (:holder composed)
           :peer p
           :refl refl
           :tick-cursor (oldest ticks)
           :fact-cursor (oldest facts)
           :seen 0
           :pending []
           :renewals 0})))


(defn- drain-newest!
  "The holder's control flow drains its own tick cursor: the newest
   reading, or nil."
  [h]
  (let [ticks (get-in @h [:composed :tick :handle])]
    (loop [c (:tick-cursor @h)
           newest nil]
      (let [r (stream/next ticks c)]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (recur (:dao.stream/cursor r)
                 (:dao.lease/reading (:dao.stream/value r)))
          (do (swap! h assoc :tick-cursor c) newest))))))


(defn- holder-pass!
  "One pass of the holder's own control flow at reading `ms`: observe
   the grant, settle renewals whose SOURCE outcome arrived on the event
   writer (the reflection's ok is outbound acceptance only), and renew
   when due."
  [h ms]
  (stream/append! (get-in @h [:composed :tick :handle]) (lease/tick {:ms ms}))
  (let [reading (drain-newest! h)
        facts (get-in @h [:composed :fact :handle])
        refl (:refl @h)
        rid (get-in @h [:state :self])]
    (when (nil? (get-in @h [:state :grant]))
      (let [r (stream/next facts (:fact-cursor @h))]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (swap! h update :state lease/observe-grant
                 rs/grantor (:dao.stream/value r) reading))))
    (stream/descriptor refl)
    (let [answers (drop (:seen @h) (events-of (:peer @h) rid))]
      (doseq [a answers]
        (let [at (first (:pending @h))]
          (swap! h #(-> %
                        (update :pending (comp vec rest))
                        (update :seen inc)
                        (update :state lease/observe-renewal at
                                (if (:dao.stream.remote/error a)
                                  {:dao.stream/outcome
                                   :dao.stream/transport-error}
                                  a)))))))
    (when (and (lease/due-to-renew? (:state @h) reading)
               (empty? (:pending @h)))
      (let [o (stream/append! refl (lease/renewal
                                     (get-in @h [:state :grant
                                                 :dao.lease/lease])))]
        (when (= :dao.stream/ok (:dao.stream/outcome o))
          (swap! h #(-> %
                        (update :pending conj reading)
                        (update :renewals inc))))))
    reading))


;; =============================================================================
;; Assembly
;; =============================================================================

(deftest open-refuses-an-incomplete-assembly
  (let [t (toy)
        full (opts t)]
    (is (rs/binding? (rs/open! full)))
    (doseq [k [::rs/channel ::rs/channel-exclusive? ::rs/channel-descriptor
               ::rs/surface ::rs/admit? ::rs/capacity ::rs/step-budget]]
      (testing (str "without " k)
        (let [r (rs/open! (dissoc full k))]
          (is (= ::rs/refused (::rs/status r)))
          (is (= [{::rs/option k, ::rs/reason ::rs/missing}]
                 (::rs/refusals r)))
          (is (not (rs/binding? r)) "nothing to serve through"))))
    (testing "exclusivity is a declaration: anything but true refuses"
      (doseq [v [false nil :yes 1]]
        (is (= [{::rs/option ::rs/channel-exclusive?,
                 ::rs/reason ::rs/not-exclusive}]
               (::rs/refusals
                 (rs/open! (assoc full ::rs/channel-exclusive? v)))))))
    (testing "the authority gate has no default: nil is malformed"
      (is (= [{::rs/option ::rs/admit?, ::rs/reason ::rs/malformed}]
             (::rs/refusals (rs/open! (assoc full ::rs/admit? nil))))))
    (testing "a capacity that is not a positive integer"
      (is (= [{::rs/option ::rs/capacity, ::rs/reason ::rs/malformed}]
             (::rs/refusals (rs/open! (assoc full ::rs/capacity 0))))))
    (testing "a step budget that is not a positive integer: no unbounded
              pass"
      (is (= [{::rs/option ::rs/step-budget, ::rs/reason ::rs/malformed}]
             (::rs/refusals (rs/open! (assoc full ::rs/step-budget nil))))))
    (testing "a codec, when given, must be {:encode f :decode f}"
      (is (= [{::rs/option ::rs/codec, ::rs/reason ::rs/malformed}]
             (::rs/refusals (rs/open! (assoc full ::rs/codec {}))))))
    (testing "a channel end without a reader"
      (is (= ::rs/malformed
             (-> (rs/open! (assoc full ::rs/channel {:writer (:ab t)}))
                 ::rs/refusals first ::rs/reason))))
    (testing "a refusal wrote nothing toward the peer"
      (is (empty? (values (:ba t)))))))


(deftest open-refuses-each-missing-or-malformed-lease-option
  (let [t (toy)
        full (opts t)
        refused (fn [o]
                  (mapv #(select-keys % [::rs/option ::rs/reason])
                        (::rs/refusals (rs/open! o))))]
    (doseq [k [::rs/lease-duration ::rs/lease-tolerance ::rs/lease-cadence
               ::rs/lease-ticks ::rs/lease-media ::rs/lease-writer
               ::rs/lease-renewal-capacity]]
      (testing (str "without " k ": no default invents the policy")
        (is (= [{::rs/option k, ::rs/reason ::rs/missing}]
               (refused (dissoc full k))))))
    (doseq [[k v why]
            [[::rs/lease-duration {:ms 0} "not a positive duration"]
             [::rs/lease-duration {:hr 1} "a unit outside S6's table"]
             [::rs/lease-duration 10 "not a duration map"]
             [::rs/lease-tolerance nil "the zero is {:ms 0}, not nil"]
             [::rs/lease-tolerance {:ms -1} "negative"]
             [::rs/lease-tolerance {:hr 1} "a unit outside the table"]
             [::rs/lease-cadence {:ms 0} "a zero cadence"]
             [::rs/lease-cadence {:hr 1} "a unit outside the table"]
             [::rs/lease-ticks [] "no tick stream"]
             [::rs/lease-ticks [{:handle (:ticks t)}] "a tick stream with no cursor"]
             [::rs/lease-media [] "no fact medium"]
             [::rs/lease-media [{:handle (:proposals t)
                                 :cursor (oldest (:proposals t))
                                 :source :p
                                 :medium (assoc per-author-medium
                                                :attribution :envelope-key)}]
              "a medium the per-author attribution cannot resolve"]
             [::rs/lease-media [{:handle (:proposals t)
                                 :cursor (oldest (:proposals t))
                                 :source :p
                                 :medium (dissoc per-author-medium :retention)}]
              "a medium declared neither retaining nor evict-oldest"]
             [::rs/lease-writer nil "no grantor stream"]
             [::rs/lease-writer (->ValueHandle "r") "not a writer"]
             [::rs/lease-renewal-capacity 0 "no renewal medium can be made"]
             [::rs/lease-max {:ms 0} "an optional cap, malformed"]
             [::rs/lease-drain-budget 0 "an optional drain budget, malformed"]]]
      (testing (str k ": " why)
        (let [r (rs/open! (assoc full k v))]
          (is (not (rs/binding? r)))
          (is (= [{::rs/option k, ::rs/reason ::rs/malformed}]
                 (refused (assoc full k v)))))))
    (testing "a standing medium whose source is the grantor: its facts
              would be the grantor's own"
      (let [claiming [{:handle (:proposals t)
                       :cursor (oldest (:proposals t))
                       :source rs/grantor
                       :medium per-author-medium}]
            r (rs/open! (assoc full ::rs/lease-media claiming))]
        (is (not (rs/binding? r)))
        (is (= [{::rs/option ::rs/lease-media, ::rs/reason ::rs/grantor-source}]
               (::rs/refusals r)))
        (is (= [{::rs/option ::rs/lease-media, ::rs/reason ::rs/grantor-source}]
               (refused (assoc full ::rs/lease-media
                               (conj (::rs/lease-media full)
                                     (first claiming)))))
            "among other media too")))
    (testing "a refusal published nothing: no grant, no answer, no tick read"
      (is (empty? (values (:grants t))))
      (is (empty? (values (:ba t)))))))


;; =============================================================================
;; serve! idempotence
;; =============================================================================

(deftest serve-is-stable-per-live-handle
  (let [t (toy)
        b (rs/open! (opts t))
        h (ring 4)
        s1 (rs/serve! b h)
        s2 (rs/serve! b h)
        id (:dao.stream/identity s1)]
    (is (= chan (:dao.stream/channel s1)))
    (is (= s1 s2) "the identical identity and channel")
    (is (= 1 (count (served-table b))) "one table entry")
    (is (= 1 (count (rs/entries b))) "and one lease: no second grant")
    (is (= {:handle h, :surface #{:reader :writer},
            :dao.lease/lease (:dao.lease/lease (rs/lease-of b id))}
           (get (rs/table b) id))
        "the mirror's plain table shape, under its lease")
    (testing "keyed by reference identity, not equality"
      (let [v1 (->ValueHandle "same")
            v2 (->ValueHandle "same")
            b' (rs/open! (opts t (constantly #{:reader}) 8))]
        (is (= v1 v2))
        (is (not= (:dao.stream/identity (rs/serve! b' v1))
                  (:dao.stream/identity (rs/serve! b' v2))))
        (is (= 2 (count (served-table b'))))))))


(deftest lift-frame-serves-one-handle-once-across-both-call-sites
  (let [t (toy)
        call-in (ring 4)
        call-out (ring 4)
        b (rs/open! (opts t (call-pair-surface call-in) 8))
        calls (atom [])
        serve! (fn [h] (swap! calls conj h) (rs/serve! b h))
        lifted (ucf.remote/lift-frame serve!
                                      (retained-resources call-in call-out)
                                      [retained-entry])
        pending (first (:yin.k/pending lifted))
        cell-marker (get-in lifted [:yin.k/cells (:yin.k/response-cell pending)
                                    :yin.k/stream])]
    (is (= :ffi-request (:yin.k/reason pending)))
    (is (< 1 (count (filter #(identical? call-out %) @calls)))
        "lift-frame asked for call-out from lift-one and from mint-cell")
    (is (= (:yin.k/response pending) cell-marker)
        "identical markers: identity and channel")
    (is (= 2 (count (served-table b)))
        "one entry for call-in, one for call-out")
    (is (= 1 (count (filter #(identical? call-out (:handle %))
                            (vals (rs/table b))))))
    (is (= #{:writer}
           (get-in (rs/table b) [(get-in pending [:yin.k/request
                                                  :dao.stream/identity])
                                 :surface]))
        "the declared surface the policy gave")))


;; =============================================================================
;; Retirement
;; =============================================================================

(deftest retire-unpublishes-and-never-reuses-an-identity
  (let [t (toy)
        b (rs/open! (opts t))
        h (ring 4)
        _ (stream/append! h :v)
        id (:dao.stream/identity (rs/serve! b h))]
    (is (= :dao.stream/ok
           (:dao.stream/outcome
             (ask t b id 0 :dao.stream/cursor [:dao.stream/oldest])))
        "served: the mirror answers from the handle")
    (is (= {:dao.stream/outcome :dao.stream/ok, ::rs/retired? true}
           (rs/retire! b id)))
    (is (not (contains? (rs/table b) id)) "unpublished")
    (is (empty? (rs/table b)) "its renewal entry with it")
    (is (empty? (rs/entries b)) "and released")
    (is (= :dao.stream.remote/not-found
           (:dao.stream.remote/error
             (ask t b id 1 :dao.stream/cursor [:dao.stream/oldest])))
        "a remote op on the retired identity answers not-found")
    (is (= {:dao.stream/outcome :dao.stream/ok, ::rs/retired? false}
           (rs/retire! b id))
        "idempotent")
    (let [id' (:dao.stream/identity (rs/serve! b h))]
      (is (some? id'))
      (is (not= id id') "re-served under a NEW identity")
      (is (= :dao.stream.remote/not-found
             (:dao.stream.remote/error
               (ask t b id 2 :dao.stream/next [0])))
          "the old identity stays not-found")
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               (ask t b id' 3 :dao.stream/cursor [:dao.stream/oldest])))))))


(deftest close-retires-everything
  (let [t (toy)
        b (rs/open! (opts t))
        ids (mapv #(:dao.stream/identity (rs/serve! b %)) [(ring 2) (ring 2)])]
    (is (= 2 (count (served-table b))))
    (is (= {:dao.stream/outcome :dao.stream/ok} (rs/close! b)))
    (is (empty? (rs/table b)))
    (is (empty? (rs/entries b)))
    (is (nil? (rs/serve! b (ring 2))) "a closed binding serves nothing")
    (is (= {:dao.stream/outcome :dao.stream/closed} (rs/step b)))
    (is (= {:dao.stream/outcome :dao.stream/ok} (rs/close! b)) "idempotent")
    (is (every? #(false? (::rs/retired? (rs/retire! b %))) ids))))


(deftest an-unresolved-remote-append-ends-as-append-unknown
  (let [t (toy)
        o (opts t (constantly #{:writer}) 8)
        b (rs/open! o)
        target (ring 4)
        id (:dao.stream/identity (rs/serve! b target))
        events (ring 16)
        attach! (remote/attacher {:dao.stream.remote/channels {chan (:a-end t)}
                                  :dao.stream.remote/events events})
        refl (:dao.stream/handle
               (attach! {:dao.stream/type :dao.stream/remote
                         :dao.stream/identity id
                         :dao.stream/channel chan}))]
    (rs/step b)
    (stream/descriptor refl)
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! refl :x)))
        "accepted on the outbound path, unanswered: no step ran")
    (is (true? (::rs/channel-exclusive? o))
        "the binding owns its channel end by declaration")
    (rs/close! b)
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! (:ba t) :after)))
        "close! closed the binding's own channel writer")
    (stream/descriptor refl)
    (let [unknown (filter #(= :dao.stream.remote/append-unknown
                              (:dao.stream.remote/event %))
                          (values events))]
      (is (= 1 (count unknown))
          "the channel's end runs the loss path: append-unknown"))
    (is (empty? (values target)) "the request never reached the mirror")))


;; =============================================================================
;; Refusal
;; =============================================================================

(deftest step-is-a-bounded-pass
  (let [t (toy)
        b (rs/open! (assoc (opts t) ::rs/step-budget 2))
        id (:dao.stream/identity (rs/serve! b (ring 4)))
        answered (fn []
                   (mapv :dao.stream.remote/id
                         (filter #(contains? % :dao.stream.remote/id)
                                 (values (:ba t)))))]
    (doseq [n (range 5)]
      (stream/append! (:ab t) {:dao.stream/identity id
                               :dao.stream.remote/op :dao.stream/cursor
                               :dao.stream.remote/args [:dao.stream/oldest]
                               :dao.stream.remote/id n}))
    (is (true? (::rs/advanced? (rs/step b))))
    (is (= [0 1] (answered)) "exactly the budget, in order")
    (rs/step b)
    (is (= [0 1 2 3] (answered)) "resumed from the kept successor")
    (rs/step b)
    (is (= [0 1 2 3 4] (answered)))
    (is (false? (::rs/advanced? (rs/step b))) "the channel is blocked")
    (is (= [0 1 2 3 4] (answered)) "nothing skipped, nothing answered twice")))


(defn- host-cursor-reader
  "A served reader whose cursors hold a host object -- a function --
   which no channel codec carries. It is a writer too, so a writer-only
   surface policy can serve it with its unportable cursors present."
  []
  (let [host (fn [])]
    (reify
      stream/IDaoStreamDescriptor

      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/host
                                 :dao.stream/identity "host"}
         :dao.stream/identity "host"})


      stream/IDaoStreamReader

      (cursor
        [_ _]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/cursor {:at host}})

      (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


      stream/IDaoStreamWriter

      (append! [_ _] {:dao.stream/outcome :dao.stream/ok}))))


(deftest a-reader-whose-cursors-are-not-portable-is-never-entered
  (let [t (toy)]
    (testing "a host object in the cursor"
      (let [b (rs/open! (opts t (constantly #{:reader}) 8))]
        (is (nil? (rs/serve! b (host-cursor-reader))))
        (is (empty? (rs/table b)))
        (is (empty? (rs/entries b)))))
    (testing "a writer-only surface names no cursors"
      (let [b (rs/open! (opts t (constantly #{:writer}) 8))
            h (host-cursor-reader)
            served (rs/serve! b h)]
        (is (some? served)
            "admitted although its reader cursors are unportable: the
             cursor check applies only to a :reader surface")
        (is (= {:handle h, :surface #{:writer}}
               (dissoc (get (rs/table b) (:dao.stream/identity served))
                       :dao.lease/lease)))))
    (testing "the binding's own codec judges"
      (let [refusing {:encode (fn [_] (throw (ex-info "uncarried" {})))
                      :decode identity}
            b (rs/open! (assoc (opts t (constantly #{:reader}) 8)
                               ::rs/codec refusing))]
        (is (nil? (rs/serve! b (ring 2))))
        (is (empty? (rs/table b)))))))


(deftest serve-refuses-what-policy-refuses
  (let [t (toy)
        h (ring 2)]
    (testing "the authority gate"
      (let [b (rs/open! (assoc (opts t) ::rs/admit? (constantly false)))]
        (is (nil? (rs/serve! b h)))
        (is (empty? (rs/table b)))))
    (testing "the surface policy"
      (let [b (rs/open! (opts t (constantly nil) 8))]
        (is (nil? (rs/serve! b h))))
      (let [b (rs/open! (opts t (constantly #{:closable}) 8))]
        (is (nil? (rs/serve! b h)) "not a served surface")))
    (testing "a surface the handle does not implement"
      (let [b (rs/open! (opts t (constantly #{:writer}) 8))]
        (is (nil? (rs/serve! b (->ValueHandle "r"))))))
    (testing "the capacity of live exports"
      (let [b (rs/open! (opts t (constantly #{:reader}) 1))
            first-id (:dao.stream/identity (rs/serve! b h))]
        (is (nil? (rs/serve! b (ring 2))))
        (is (some? (rs/serve! b h)) "a live handle is still answered")
        (rs/retire! b first-id)
        (is (some? (rs/serve! b (ring 2))) "retirement frees capacity")))))


(deftest a-refused-frame-leaves-no-provisional-export
  (let [t (toy)
        call-in (ring 4)
        call-out (ring 4)
        b (rs/open! (assoc (opts t (call-pair-surface call-in) 8)
                           ::rs/admit? #(not (identical? call-out %))))
        r (rs/lift-frame b (retained-resources call-in call-out)
                         [retained-entry])]
    (is (= :yin.k/unsatisfied (:yin.k/status r)))
    (is (not (contains? r :yin.k/pending)) "no lift is published")
    (is (empty? (rs/table b))
        "call-in, served before call-out refused, was retired")
    (is (empty? (rs/entries b))))
  (testing "a handle served before the lift stays served"
    (let [t (toy)
          call-in (ring 4)
          call-out (ring 4)
          b (rs/open! (assoc (opts t (call-pair-surface call-in) 8)
                             ::rs/admit? #(not (identical? call-out %))))
          id (:dao.stream/identity (rs/serve! b call-in))]
      (is (= :yin.k/unsatisfied
             (:yin.k/status
               (rs/lift-frame b (retained-resources call-in call-out)
                              [retained-entry]))))
      (is (= [id] (keys (served-table b)))))))


;; =============================================================================
;; Leases (3b)
;; =============================================================================

(deftest serving-grants-a-lease-to-a-served-renewal-medium
  (let [t (toy)
        b (rs/open! (opts t))
        id (:dao.stream/identity (rs/serve! b (ring 4)))
        {l :dao.lease/lease renewal ::rs/renewal} (rs/lease-of b id)
        rid (:dao.stream/identity renewal)]
    (is (= chan (:dao.stream/channel renewal)))
    (is (= {:surface #{:writer}, :dao.lease/lease l}
           (dissoc (get (rs/table b) rid) :handle))
        "the renewal medium is served beside its subject, writer-only")
    (is (= [{:source rid, :medium (rs/renewal-declaration 16)}]
           (->> (:facts (rs/judge b))
                (filter #(= rid (:source %)))
                (mapv #(select-keys % [:source :medium]))))
        "wired to the judge once, its declaration kept on the entry")
    (is (empty? (values (:grants t))) "no pass yet: the grant is queued")
    (tick-step! t b 1)
    (is (= (lease/grant l id rid {:ms 10}) (grant-of t id))
        "the grant: subject the served identity, holder the renewal medium")
    (is (= {:ms 1} (get-in (rs/judge b) [:ledger l :tenure-start]))
        "seeded at the pass's now, a reading the host deposited")))


(deftest live-renewal-keeps-the-export-served
  (let [t (toy)
        b (rs/open! (opts t))
        h (ring 4)
        _ (stream/append! h :v)
        id (:dao.stream/identity (rs/serve! b h))
        l (:dao.lease/lease (rs/lease-of b id))
        _ (tick-step! t b 1)
        p (peer (:a-end t))
        hd (holder p (grant-of t id))]
    (doseq [ms (range 2 61)]
      (holder-pass! hd ms)
      (tick-step! t b ms))
    (is (< 10 (:renewals @hd)) "the holder renewed throughout")
    (is (lease/holding? (:state @hd) {:ms 60})
        "its bound advanced only on the source's ok")
    (is (empty? (lapses t)) "no reclaim over 60 lease ticks: 5 durations")
    (is (= l (:dao.lease/lease (rs/lease-of b id))))
    (is (contains? (get-in (rs/judge b) [:ledger]) l))
    (is (<= 58 (get-in (rs/judge b) [:ledger l :last-observation :ms]))
        "the judge counted the latest renewal")
    (let [refl (reflect p id)
          c (poll! b #(stream/cursor refl :dao.stream/oldest))]
      (is (= :v (:dao.stream/value
                  (poll! b #(stream/next refl (:dao.stream/cursor c)))))
          "the export still answers under its one identity"))))


(defn- recording-writer
  "The grantor's writer, noting at each :lapsed append whether the
   reclaimed subject was still in the published table."
  [inner b-ref seen]
  (reify
    stream/IDaoStreamWriter

    (append!
      [_ v]
      (when (= :dao.lease/lapsed (:dao.lease/status v))
        (let [b @b-ref
              subject (some #(when (= (:dao.lease/lease v) (:lease %))
                               (:identity %))
                            @(:entries-at-grant b))]
          (swap! seen conj {:cause (:dao.lease/cause v)
                            :subject-published?
                            (contains? (rs/table (:binding b)) subject)})))
      (stream/append! inner v))))


(defn- recorded-binding
  "A binding whose grantor writer records the table at each :lapsed."
  [t o]
  (let [b-ref (atom nil)
        seen (atom [])
        b (rs/open! (assoc o ::rs/lease-writer
                           (recording-writer (:grants t) b-ref seen)))]
    (reset! b-ref {:binding b :entries-at-grant (atom [])})
    {:binding b
     :seen seen
     :remember! #(swap! (:entries-at-grant @b-ref) into (rs/entries b))}))


(deftest expiry-reclaims-through-retire
  (let [t (toy)
        {b :binding seen :seen remember! :remember!} (recorded-binding t (opts t))
        h (ring 4)
        id (:dao.stream/identity (rs/serve! b h))
        {l :dao.lease/lease renewal ::rs/renewal} (rs/lease-of b id)
        rid (:dao.stream/identity renewal)]
    (remember!)
    (tick-step! t b 1)
    (tick-step! t b 13)
    (is (contains? (rs/table b) id)
        "12 since the grant is duration plus tolerance: not yet past it")
    (is (empty? (lapses t)))
    (tick-step! t b 14)
    (is (= [(lease/lapsed l :silence)] (lapses t)) "reclaimed and recorded")
    (is (= [{:cause :silence, :subject-published? false}] @seen)
        "the identity left the table BEFORE the reclaim was acknowledged")
    (is (not (contains? (rs/table b) id)))
    (is (not (contains? (rs/table b) rid)) "its renewal entry with it")
    (is (empty? (rs/entries b)))
    (is (not (contains? (:ledger (rs/judge b)) l)) "the lease left the ledger")
    (is (not-any? #(= rid (:source %)) (:facts (rs/judge b)))
        "its renewal medium is unwired from the judge")
    (is (= :dao.stream.remote/not-found
           (:dao.stream.remote/error
             (ask t b id 0 :dao.stream/cursor [:dao.stream/oldest])))
        "a fresh remote op answers not-found")
    (is (= :dao.stream.remote/not-found
           (:dao.stream.remote/error
             (ask t b rid 1 :dao.stream/append! [(lease/renewal l)])))
        "a late renewal buys nothing back")
    (let [id' (:dao.stream/identity (rs/serve! b h))]
      (is (not= id id') "re-serving the handle mints a new identity")
      (is (not= l (:dao.lease/lease (rs/lease-of b id'))) "under a new lease")
      (tick-step! t b 15)
      (is (= :dao.stream.remote/not-found
             (:dao.stream.remote/error
               (ask t b id 2 :dao.stream/cursor [:dao.stream/oldest])))
          "the old identity never rebinds")
      (is (= 1 (count (lapses t))) "recorded once"))))


(deftest release-by-the-holder-reclaims
  (let [t (toy)
        {b :binding seen :seen remember! :remember!} (recorded-binding t (opts t))
        h (ring 4)
        id (:dao.stream/identity (rs/serve! b h))
        {l :dao.lease/lease renewal ::rs/renewal} (rs/lease-of b id)
        rid (:dao.stream/identity renewal)
        _ (remember!)
        _ (tick-step! t b 1)
        p (peer (:a-end t))
        hd (holder p (grant-of t id))]
    (doseq [ms (range 2 6)]
      (holder-pass! hd ms)
      (tick-step! t b ms))
    (is (contains? (rs/table b) id))
    (let [{holder' :holder release :release} (lease/stop (:state @hd))]
      (swap! hd assoc :state holder')
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/append! (:refl @hd) release)))
          "the release is appended on the holder's attributed medium"))
    (tick-step! t b 6)
    (is (= [(lease/lapsed l :release)] (lapses t))
        "reclaimed for :release in the pass that read it")
    (is (= [{:cause :release, :subject-published? false}] @seen)
        "unpublished before the reclaim was acknowledged")
    (is (not (contains? (rs/table b) id)))
    (is (not (contains? (rs/table b) rid)))
    (is (= :dao.stream.remote/not-found
           (:dao.stream.remote/error
             (ask t b id :fresh :dao.stream/cursor [:dao.stream/oldest]))))
    (let [id' (:dao.stream/identity (rs/serve! b h))]
      (is (not= id id') "re-serving mints a new identity"))
    (testing "the holder observes the reclaim as not-found"
      (is (= :dao.stream.remote/not-found
             (:dao.stream.remote/reason
               (gone-after-step b (:refl @hd) (lease/renewal l))))))))


(deftest detach-without-expiry-keeps-entry-and-lease
  (let [t (toy)
        b (rs/open! (opts t))
        h (ring 8)
        _ (stream/append! h :v1)
        _ (stream/append! h :v2)
        id (:dao.stream/identity (rs/serve! b h))
        {l :dao.lease/lease renewal ::rs/renewal} (rs/lease-of b id)
        rid (:dao.stream/identity renewal)
        _ (tick-step! t b 1)
        p (peer (:a-end t))
        refl (reflect p id)
        c0 (:dao.stream/cursor (poll! b #(stream/cursor refl :dao.stream/oldest)))
        r1 (poll! b #(stream/next refl c0))
        c1 (:dao.stream/cursor r1)]
    (is (= :v1 (:dao.stream/value r1)))
    (testing "the channel ends: detached, nothing retired"
      (stream/close! (:ab t))
      (is (true? (::rs/detached? (tick-step! t b 2))))
      (doseq [ms (range 3 11)]
        (is (true? (::rs/detached? (tick-step! t b ms)))))
      (is (= l (:dao.lease/lease (rs/lease-of b id))) "the same lease")
      (is (contains? (rs/table b) id))
      (is (contains? (rs/table b) rid))
      (is (contains? (:ledger (rs/judge b)) l))
      (is (empty? (lapses t))))
    (testing "reattach within the tenure: same identity, same cursors"
      (let [t2 (pair)
            p2 (peer (:a-end t2))]
        (is (= {:dao.stream/outcome :dao.stream/ok} (rs/reattach! b (:b-end t2))))
        (is (= :dao.stream/closed
               (:dao.stream/outcome (stream/append! (:ba t) :stale)))
            "the replaced writer is closed: the old link runs its loss path")
        (let [refl2 (reflect p2 id)]
          (is (= :v2 (:dao.stream/value (poll! b #(stream/next refl2 c1))))
              "the source cursor minted before the detach still answers"))
        (let [renew (reflect p2 rid)]
          (stream/append! renew (lease/renewal l))
          (is (false? (::rs/detached? (tick-step! t b 11))))
          (is (= {:ms 11} (get-in (rs/judge b) [:ledger l :last-observation]))
              "a renewal through the new end counts"))
        (tick-step! t b 20)
        (is (empty? (lapses t)))
        (is (contains? (rs/table b) id) "still served past the old bound")))))


(deftest an-in-flight-append-at-reclaim-is-never-retried
  (testing "answered not-found: unread at reclaim, the table already
            without it"
    (let [t (toy)
          b (rs/open! (assoc (opts t (constantly #{:writer}) 8)
                             ::rs/step-budget 1))
          target (ring 8)
          id (:dao.stream/identity (rs/serve! b target))
          l (:dao.lease/lease (rs/lease-of b id))
          _ (tick-step! t b 1)
          p (peer (:a-end t))
          refl (reflect p id)
          _ (rs/step b)
          _ (stream/descriptor refl)
          sent-a (stream/append! refl :a1)
          sent-b (stream/append! refl :a2)]
      (is (= [:dao.stream/ok :dao.stream/ok]
             (mapv :dao.stream/outcome [sent-a sent-b]))
          "both accepted outbound, both unanswered")
      (tick-step! t b 14)
      (is (= [(lease/lapsed l :silence)] (lapses t))
          "reclaimed with :a2 still unread on the channel")
      (is (= [:a1] (values target)) ":a1 was read before the reclaim")
      (tick-step! t b 15)
      (stream/descriptor refl)
      (is (= [:dao.stream/ok nil]
             (mapv :dao.stream/outcome (events-of p id))))
      (is (= [nil :dao.stream.remote/not-found]
             (mapv :dao.stream.remote/error (events-of p id)))
          ":a2's outcome is the terminal not-found, not a delivery")
      (let [before (count (values (:ab t)))]
        (is (= :dao.stream.remote/not-found
               (:dao.stream.remote/reason (stream/append! refl :a3))))
        (is (= before (count (values (:ab t))))
            "the gone reflection sends nothing more"))
      (let [id' (:dao.stream/identity (rs/serve! b target))]
        (doseq [ms (range 16 20)] (tick-step! t b ms))
        (is (some? id'))
        (is (= [:a1] (values target))
            "nothing reached the target under the new tenure"))))
  (testing "append-unknown: the channel goes while the append is unread"
    (let [t (toy)
          b (rs/open! (assoc (opts t (constantly #{:writer}) 8)
                             ::rs/step-budget 1))
          target (ring 8)
          id (:dao.stream/identity (rs/serve! b target))
          l (:dao.lease/lease (rs/lease-of b id))
          _ (tick-step! t b 1)
          p (peer (:a-end t))
          refl (reflect p id)
          _ (stream/append! refl :b1)]
      (tick-step! t b 14)
      (is (= [(lease/lapsed l :silence)] (lapses t))
          "reclaimed: the probe was read, :b1 was not")
      (let [t2 (pair)]
        (rs/reattach! b (:b-end t2))
        (stream/descriptor refl)
        (is (= [:dao.stream.remote/append-unknown]
               (keep :dao.stream.remote/event (values (:events p))))
            ":b1 surfaces as append-unknown")
        (let [p2 (peer (:a-end t2))
              again (reflect p2 id)]
          (rs/step b)
          (is (= :dao.stream.remote/not-found
                 (:dao.stream.remote/reason (stream/append! again :b2)))
              "the old identity answers not-found through the new end"))
        (rs/serve! b target)
        (doseq [ms (range 15 19)] (tick-step! t b ms))
        (is (empty? (values target))
            "nothing was retried: the target never saw :b1")))))


(defn- flaky-writer
  "The grantor's writer, refusing every append with full while
   `refuse?` holds."
  [inner refuse?]
  (reify
    stream/IDaoStreamWriter

    (append!
      [_ v]
      (if @refuse?
        {:dao.stream/outcome :dao.stream/full}
        (stream/append! inner v)))))


(deftest reclaim-is-idempotent
  (testing "a duplicate reclaim of one subject is harmless"
    (let [t (toy)
          b (rs/open! (opts t))
          id (:dao.stream/identity (rs/serve! b (ring 4)))
          other (:dao.stream/identity (rs/serve! b (ring 4)))
          reclaim (:reclaim (rs/judge b))]
      (is (true? (reclaim id)))
      (is (true? (reclaim id)) "reports success again")
      (is (not (contains? (rs/table b) id)))
      (is (= [other] (keys (served-table b))) "nothing else retired")))
  (testing "a reclaim left pending by an unrecorded :lapsed is retried
            harmlessly and recorded once"
    (let [t (toy)
          refuse? (atom false)
          b (rs/open! (assoc (opts t) ::rs/lease-writer
                             (flaky-writer (:grants t) refuse?)))
          id (:dao.stream/identity (rs/serve! b (ring 4)))
          other (:dao.stream/identity (rs/serve! b (ring 4)))
          l (:dao.lease/lease (rs/lease-of b id))]
      (tick-step! t b 1)
      (rs/retire! b other)
      (reset! refuse? true)
      (tick-step! t b 14)
      (is (= {:state :pending :cause :silence :success true}
             (get-in (rs/judge b) [:ledger l :reclaim]))
          "reclaimed, not yet recorded: pending")
      (is (not (contains? (rs/table b) id)))
      (tick-step! t b 15)
      (is (= :pending (get-in (rs/judge b) [:ledger l :reclaim :state])))
      (reset! refuse? false)
      (tick-step! t b 16)
      (is (= #{:silence :policy} (set (map :dao.lease/cause (lapses t)))))
      (is (= 2 (count (lapses t))) "each recorded exactly once")
      (is (empty? (:ledger (rs/judge b))))
      (is (empty? (rs/table b)))))
  (testing "a reclaim after close! is harmless, and the leases still end"
    (let [t (toy)
          b (rs/open! (opts t))
          id (:dao.stream/identity (rs/serve! b (ring 4)))
          l (:dao.lease/lease (rs/lease-of b id))]
      (tick-step! t b 1)
      (rs/close! b)
      (is (true? ((:reclaim (rs/judge b)) id)))
      (is (= {:dao.stream/outcome :dao.stream/closed} (tick-step! t b 2)))
      (is (= [(lease/lapsed l :policy)] (lapses t))
          "the closed binding's lease ends by the grantor's policy")
      (tick-step! t b 3)
      (is (= 1 (count (lapses t))))
      (is (empty? (:ledger (rs/judge b)))))))

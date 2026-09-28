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
   export behind."
  (:require [clojure.test :refer [deftest is testing]]
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


(def ^:private chan
  {:dao.stream/type :dao.stream.test/channel
   :dao.stream/identity "remote-serve-toy"})


(defn- toy
  []
  (let [ab (ring 64)
        ba (ring 64)]
    {:ab ab, :ba ba,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba}}))


(defn- opts
  "A complete assembly over `t`'s B end, declared exclusive to the
   binding: every handle admitted, served with `surface`, at most
   `capacity` live exports."
  ([t] (opts t (constantly #{:reader :writer}) 8))
  ([t surface capacity]
   {::rs/channel (:b-end t)
    ::rs/channel-exclusive? true
    ::rs/channel-descriptor chan
    ::rs/surface surface
    ::rs/admit? (constantly true)
    ::rs/capacity capacity
    ::rs/step-budget 64}))


(defn- values
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
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


;; =============================================================================
;; serve! idempotence
;; =============================================================================

(deftest serve-is-stable-per-live-handle
  (let [t (toy)
        b (rs/open! (opts t))
        h (ring 4)
        s1 (rs/serve! b h)
        s2 (rs/serve! b h)]
    (is (= chan (:dao.stream/channel s1)))
    (is (= s1 s2) "the identical identity and channel")
    (is (= 1 (count (rs/table b))) "one table entry")
    (is (= {:handle h, :surface #{:reader :writer}}
           (get (rs/table b) (:dao.stream/identity s1)))
        "the mirror's plain table shape")
    (testing "keyed by reference identity, not equality"
      (let [v1 (->ValueHandle "same")
            v2 (->ValueHandle "same")
            b' (rs/open! (opts t (constantly #{:reader}) 8))]
        (is (= v1 v2))
        (is (not= (:dao.stream/identity (rs/serve! b' v1))
                  (:dao.stream/identity (rs/serve! b' v2))))
        (is (= 2 (count (rs/table b'))))))))


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
    (is (= 2 (count (rs/table b)))
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
    (is (= 2 (count (rs/table b))))
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
               (get (rs/table b) (:dao.stream/identity served))))))
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
      (is (= [id] (keys (rs/table b)))))))

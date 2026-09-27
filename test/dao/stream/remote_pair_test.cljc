(ns dao.stream.remote-pair-test
  "The pair channel of dao.stream.remote.md (3.3): a gap on `in` ends
   the channel with the source's frames lost, not relayed as a
   source's own gap; the abandoned append! is reported append-
   unknown; attach! on the same pair descriptor resumes with a fresh
   cursor on `in`; two identities riding one pair descriptor share
   its link, one lower attach! each; and a pair descriptor as a
   channel reads a served stream verbatim through a third peer's two
   ring buffers, the relay convention of section 4, proved here at
   the channel layer, independent of the meeting board."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.remote-pair :as pair]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; The toy: a pair over two raw ring buffers, no real lower channel
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- lower-attach!
  "A stub :dao.stream/remote attach entry: identity -> a raw handle
   already given, no reflection semantics of its own. This
   namespace's own contract with `pair/attacher` needs only a
   conforming attach! result, and the raw ring buffer stands in
   honestly for what a real one, over ws or another pair, would
   eventually resolve to: something that gaps when its retention is
   outrun."
  [handles]
  (fn [descriptor]
    (if-some [h (get handles (:dao.stream/identity descriptor))]
      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}
      {:dao.stream/outcome :dao.stream/not-found})))


(defn- counting-lower-attach!
  "As `lower-attach!`, also counting every call per identity in
   `counts`, an atom of {identity -> n}."
  [handles counts]
  (fn [descriptor]
    (swap! counts update (:dao.stream/identity descriptor) (fnil inc 0))
    ((lower-attach! handles) descriptor)))


(defn- remote-descriptor
  [id]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity id
   :dao.stream/channel {:dao.stream/type :dao.stream.test/lower
                        :dao.stream/identity id}})


(defn- pair-descriptor
  [pair-id in-id out-id]
  {:dao.stream/type :dao.stream/pair
   :dao.stream/identity pair-id
   :dao.stream.remote/in (remote-descriptor in-id)
   :dao.stream.remote/out (remote-descriptor out-id)})


(defn- outer-descriptor
  [identity pair-cd]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity
   :dao.stream/channel pair-cd})


(defn- entry
  [h surface]
  {:handle h :surface surface})


(defn- values
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


;; =============================================================================
;; 3.3's own rule: an in gap ends the channel
;; =============================================================================

(deftest pair-gap-ends-the-channel-test
  (let [ab (ring 8)
        ba (ring 2)
        events (ring 16)
        pcd (pair-descriptor "pair-1" "ba-id" "ab-id")
        attach! (pair/attacher
                  {:dao.stream.remote.pair/attach!
                   (lower-attach! {"ba-id" ba "ab-id" ab})
                   :dao.stream.remote/events events})
        r (:dao.stream/handle (attach! (outer-descriptor "svc-1" pcd)))]
    (testing "attach answers ok at once, in's cursor minted at :newest"
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/descriptor r)))))
    (let [sent (stream/append! r :v)]
      (is (= :dao.stream/ok (:dao.stream/outcome sent))
          "the append! request crossed on ab, unanswered -- it is what
           the channel loss below abandons")
      (testing "flooding ba past its retention outruns the cursor
                minted at attach, before any op has read it"
        (dotimes [_ 4] (stream/append! ba :noise))
        (let [ans (stream/next r 0)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
          (is (= :dao.stream.remote/channel-gone
                 (:dao.stream.remote/reason ans))
              "the gap on in is never relayed as a source's own gap:
               the reader answered end, and the link ran its
               channel-loss path")))
      (testing "the abandoned append! is reported append-unknown"
        (is (some #(= :dao.stream.remote/append-unknown
                      (:dao.stream.remote/event %))
                  (values events))))
      (testing "reads answer channel-gone, not retryable, from here on"
        (let [ans (stream/cursor r :dao.stream/oldest)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
          (is (= :dao.stream.remote/channel-gone
                 (:dao.stream.remote/reason ans))))))))


;; =============================================================================
;; attach! on the same pair descriptor resumes
;; =============================================================================

(deftest reattachment-resumes-test
  (let [ab (ring 8)
        ba (ring 2)
        pcd (pair-descriptor "pair-1" "ba-id" "ab-id")
        handles {"ba-id" ba "ab-id" ab}
        attach! (pair/attacher
                  {:dao.stream.remote.pair/attach! (lower-attach! handles)})
        r1 (:dao.stream/handle (attach! (outer-descriptor "svc-1" pcd)))]
    (stream/descriptor r1)
    (dotimes [_ 4] (stream/append! ba :noise))
    (let [gone (stream/next r1 0)]
      (is (= :dao.stream.remote/channel-gone
             (:dao.stream.remote/reason gone))
          "the first attachment is gone: forced past ba's retention"))
    (testing "a fresh attach! on the same pair descriptor value builds
              a fresh channel, minted at ba's :newest"
      (let [r2 (:dao.stream/handle
                 (attach! (outer-descriptor "svc-1" pcd)))]
        (is (not (identical? r1 r2)))
        (let [ans (stream/next r2 0)]
          (is (not= :dao.stream.remote/channel-gone
                    (:dao.stream.remote/reason ans)))
          (is (= :dao.stream/blocked (:dao.stream/outcome ans))
              "the fresh link is alive: nothing filed yet, no gap"))))))


;; =============================================================================
;; Two identities riding one pair descriptor share its link
;; =============================================================================

(deftest two-identities-share-one-pair-link-test
  (let [ab (ring 8)
        ba (ring 8)
        pcd (pair-descriptor "pair-1" "ba-id" "ab-id")
        handles {"ba-id" ba "ab-id" ab}
        counts (atom {})
        attach! (pair/attacher
                  {:dao.stream.remote.pair/attach!
                   (counting-lower-attach! handles counts)})]
    (attach! (outer-descriptor "p" pcd))
    (attach! (outer-descriptor "q" pcd))
    (is (= {"ba-id" 1 "ab-id" 1} @counts)
        "one lower attach! each, for the one pair channel end built
         and shared by both identities -- a second attach! would
         double each count")))


;; =============================================================================
;; The relay convention's channel: a pair over a third peer's buffers
;; =============================================================================

(deftest relay-round-trip-test
  (testing "a peer with no channel of its own reaches another peer's
            served stream through a third peer's two ring buffers:
            requests flow one way, answers the other -- exactly
            dao.stream.remote's own toy, with a pair descriptor as
            the channel instead of one the composition wires
            directly (section 4's relay)"
    (let [requests (ring 8)
          answers (ring 8)
          pcd (pair-descriptor "pair-1" "answers-id" "requests-id")
          handles {"answers-id" answers "requests-id" requests}
          attach! (pair/attacher
                    {:dao.stream.remote.pair/attach!
                     (lower-attach! handles)})
          served (ring 8)
          _ (stream/append! served :hello)
          table (atom {"svc" (entry served #{:reader})})
          mirror (atom (:dao.stream/cursor
                         (stream/cursor requests :dao.stream/oldest)))
          serve! (fn []
                   (swap! mirror
                          #(remote/mirror-step @table requests %
                                               answers)))
          r (:dao.stream/handle (attach! (outer-descriptor "svc" pcd)))]
      (serve!)
      (stream/cursor r :dao.stream/oldest)
      (serve!)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r c0))))
        (serve!)
        (is (= :hello (:dao.stream/value (stream/next r c0)))
            "the reflection, attached only through the pair, reads
             the served stream verbatim -- the relaying peer's mirror
             never interprets what crossed")))))

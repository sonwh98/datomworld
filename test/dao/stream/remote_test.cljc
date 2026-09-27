(ns dao.stream.remote-test
  "The remote slice: the toy over two in-process ring buffers as the
   channel; a gap from an evicting source crossing verbatim with the
   source's cursor; blocked then ok on re-ask; not-found marking the
   reflection gone while no-surface does not; the descriptor answer
   carrying the surface; a kept cursor from a reflection accepted by
   the source handle; the budget chase and its terminal non-ok outcome;
   oversize; resend-after on an unreliable toy channel; append-unknown
   on channel end and at close; refused returned verbatim; close!
   semantics; the mirror's four-step order on the wire, its channel
   context, and malformed input dropped; the remote descriptor
   dispatch; position errors only ever relayed; a send the writer
   refuses with full leaving nothing outstanding, a refused probe
   kept and retried on a later drain; and the installed outcomes kept
   to their own served stream."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.middleware :as middleware]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; The toy: two in-process ring buffers as the channel
;; =============================================================================

(defn- ring
  "An open owner ring-buffer handle with the given capacity."
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- toy
  "The toy channel: buffer `:ab` carries peer A's values toward peer B,
   `:ba` carries B's toward A. Each side's end is {:reader r :writer
   w}: A reads ba and writes ab; B's mirror reads ab and writes ba."
  []
  (let [ab (ring 64)
        ba (ring 64)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "toy-channel"}]
    {:channel cd
     :ab ab
     :ba ba
     :a-end {:reader ba :writer ab}
     :b-end {:reader ab :writer ba}}))


(defn- entry
  "One table entry: the handle and its declared surface."
  [h surface]
  {:handle h :surface surface})


(defn- served-peer
  "Peer B: an atom of the table it serves plus its side of the toy,
   with the mirror step's own cursor held in an atom."
  [table t]
  {:table (atom table)
   :b-reader (:reader (:b-end t))
   :b-writer (:writer (:b-end t))
   :mirror (atom (:dao.stream/cursor
                   (stream/cursor (:reader (:b-end t))
                                  :dao.stream/oldest)))})


(defn- serve!
  "One mirror step over everything pending on the toy's request
   buffer."
  [peer]
  (swap! (:mirror peer)
         #(remote/mirror-step @(:table peer) (:b-reader peer)
                              % (:b-writer peer))))


(defn- attach
  "Attach one reflection for `identity` through `end`, a channel end
   {:reader r :writer w}, with the attacher's composition data `opts`.
   One call builds one attacher, so one call per toy makes one link."
  [end identity opts]
  (let [cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "toy-channel"}
        attach! (remote/attacher
                  (merge {:dao.stream.remote/channels {cd end}} opts))]
    (attach! {:dao.stream/type :dao.stream/remote
              :dao.stream/identity identity
              :dao.stream/channel cd})))


(defn- values
  "Every retained value of `h`, oldest first, by a fresh cursor walk."
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- wire-requests
  "The request values currently retained on wire buffer `h`, oldest
   first."
  [h]
  (filter #(and (map? %) (contains? % :dao.stream.remote/op))
          (values h)))


(defn- op-requests
  "The retained requests on wire buffer `h` whose op is `op`. Ring
   buffers retain what they carried, so counts of the wire's traffic
   are per op."
  [h op]
  (filter #(= op (:dao.stream.remote/op %)) (wire-requests h)))


(defn- capped-writer
  "A channel writer that carries values to `h` but refuses, with
   invalid-value, any carrying a more vector: the toy's frame budget
   stops at one outcome per answer."
  [h]
  (reify stream/IDaoStreamWriter
    (append!
      [_ v]
      (if (contains? v :dao.stream.remote/more)
        {:dao.stream/outcome :dao.stream/invalid-value}
        (stream/append! h v)))))


(defn- dropping-writer
  "A channel writer that carries values to `h` but silently drops the
   first `n` whose op is one of `ops`, answering ok either way: the
   toy's unreliable channel."
  [h n ops]
  (let [left (atom n)]
    (reify stream/IDaoStreamWriter
      (append!
        [_ v]
        (let [drop? (and (pos? @left)
                         (contains? ops (:dao.stream.remote/op v)))]
          (when drop? (swap! left dec))
          (if drop?
            {:dao.stream/outcome :dao.stream/ok}
            (stream/append! h v)))))))


(defn- full-then-forward-writer
  "A channel writer that refuses, with full, the first `n` appends and
   carries every later one to `h`: the toy's back pressure, transient
   and then recovered."
  [h n]
  (let [left (atom n)]
    (reify stream/IDaoStreamWriter
      (append!
        [_ v]
        (if (pos? @left)
          (do (swap! left dec)
              {:dao.stream/outcome :dao.stream/full})
          (stream/append! h v))))))


(defn- list-stream
  "A minimal served reader over a fixed vector, whose cursors are bare
   positions: two of these mint equal cursor values, which two served
   identities on one link can hold at once."
  [identity vs]
  (reify stream/IDaoStreamDescriptor

    (descriptor
      [_]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/descriptor {:dao.stream/type :dao.stream.test/list
                               :dao.stream/identity identity}
       :dao.stream/identity identity})


    stream/IDaoStreamReader

    (cursor
      [_ anchor]
      (case anchor
        :dao.stream/oldest {:dao.stream/outcome :dao.stream/ok
                            :dao.stream/cursor 0}
        :dao.stream/newest {:dao.stream/outcome :dao.stream/ok
                            :dao.stream/cursor (count vs)}
        {:dao.stream/outcome :dao.stream/invalid-anchor}))

    (next
      [_ c]
      (cond
        (not (integer? c))
        {:dao.stream/outcome :dao.stream/invalid-cursor}

        (some? (get vs c))
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/value (get vs c)
         :dao.stream/cursor (inc c)}

        :else
        {:dao.stream/outcome :dao.stream/blocked}))))


;; =============================================================================
;; The proof row
;; =============================================================================

(deftest the-toy-over-two-ring-buffers
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
          res (attach (:a-end t) "str-1" {})
          r (:dao.stream/handle res)]
      (is (= :dao.stream/ok (:dao.stream/outcome res)))
      (is (some? (:dao.stream/attachment res)))
      (testing "attach sends the descriptor probe, wire-shaped"
        (let [[probe] (wire-requests (:ab t))]
          (is (= "str-1" (:dao.stream/identity probe)))
          (is (= :dao.stream/descriptor (:dao.stream.remote/op probe)))
          (is (= [] (:dao.stream.remote/args probe)))
          (is (contains? probe :dao.stream.remote/id))))
      (testing "descriptor is local: the remote descriptor, the identity"
        (let [d (stream/descriptor r)]
          (is (= :dao.stream/ok (:dao.stream/outcome d)))
          (is (= "str-1" (:dao.stream/identity d)))
          (is (= :dao.stream/remote
                 (get-in d [:dao.stream/descriptor :dao.stream/type])))
          (is (= "str-1"
                 (get-in d [:dao.stream/descriptor
                            :dao.stream/identity])))))
      (serve! peer)
      (testing "cursor :oldest is transport-error, retry?, then ok c0"
        (is (= {:dao.stream/outcome :dao.stream/transport-error
                :dao.stream/retry? true}
               (stream/cursor r :dao.stream/oldest))
            "no answer filed, so the link sends the request")
        (serve! peer)
        (is (= (stream/cursor s :dao.stream/oldest)
               (stream/cursor r :dao.stream/oldest))
            "on the next call the drain files the source's own ok"))
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (testing "next c0 is blocked, then ok on re-ask"
          (is (= :dao.stream/blocked
                 (:dao.stream/outcome (stream/next r c0))))
          (serve! peer)
          (is (= (stream/next s c0) (stream/next r c0))
              "the source's own outcome map, the string verbatim"))
        (testing "next past the value is end"
          (let [c1 (:dao.stream/cursor (stream/next r c0))]
            (is (= :dao.stream/blocked
                   (:dao.stream/outcome (stream/next r c1))))
            (serve! peer)
            (is (= (stream/next s c1) (stream/next r c1)))))
        (testing "a kept cursor from the reflection is accepted by the
                  source handle"
          (is (= "hello" (:dao.stream/value (stream/next s c0)))
              "the reflection's cursors are the source's own"))))))


(deftest gap-from-an-evicting-source-crosses-verbatim
  (let [t (toy)
        s (ring 2)
        peer (served-peer {"ev" (entry s #{:reader})} t)
        r (:dao.stream/handle (attach (:a-end t) "ev" {}))]
    (serve! peer)
    (stream/cursor r :dao.stream/oldest)
    (serve! peer)
    ;; the origin cursor, minted through the reflection before the
    ;; source evicts
    (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
      (doseq [v [:v1 :v2 :v3]]
        (stream/append! s v))
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (stream/next r c0))))
      (serve! peer)
      (let [expected (stream/next s c0)
            got (stream/next r c0)]
        (is (= :dao.stream/gap (:dao.stream/outcome got)))
        (is (= expected got)
            "the source's own outcome map, verbatim")
        (is (= (:dao.stream/cursor expected)
               (:dao.stream/cursor got))
            "with the source's own recovery cursor")
        (testing "the walk continues from the recovery cursor"
          (is (= :dao.stream/blocked
                 (:dao.stream/outcome
                   (stream/next r (:dao.stream/cursor got)))))
          (serve! peer)
          (let [ok (stream/next r (:dao.stream/cursor got))]
            (is (= :dao.stream/ok (:dao.stream/outcome ok)))
            (is (= :v2 (:dao.stream/value ok)))))))))


;; =============================================================================
;; Gone, no-surface, and the declared surface
;; =============================================================================

(deftest not-found-marks-gone
  (let [t (toy)
        s (ring 8)]
    (stream/append! s :v1)
    (let [peer (served-peer {"x" (entry s #{:reader})} t)
          r (:dao.stream/handle (attach (:a-end t) "x" {}))]
      (serve! peer)
      (stream/cursor r :dao.stream/oldest)
      (serve! peer)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (swap! (:table peer) dissoc "x")
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r c0))))
        (serve! peer)
        (let [ans (stream/next r c0)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
          (is (= :dao.stream.remote/not-found
                 (:dao.stream.remote/reason ans)))
          (is (not (contains? ans :dao.stream/retry?))))
        (testing "a name outlives what it named"
          (is (= :dao.stream/ok
                 (:dao.stream/outcome (stream/descriptor r)))))
        (testing "once gone, no request is composed any more"
          (let [before (count (wire-requests (:ab t)))]
            (is (= :dao.stream/transport-error
                   (:dao.stream/outcome (stream/next r c0))))
            (is (= :dao.stream/transport-error
                   (:dao.stream/outcome (stream/append! r :v))))
            (is (= before (count (wire-requests (:ab t)))))))))))


(deftest no-surface-marks-nothing
  (testing "before the probe's answer every operation is attempted"
    (let [t (toy)
          s (ring 8)]
      (stream/append! s :v1)
      (let [peer (served-peer {"x" (entry s #{:reader})} t)
            r (:dao.stream/handle (attach (:a-end t) "x" {}))]
        (is (= :dao.stream/ok
               (:dao.stream/outcome (stream/append! r :x)))
            "no surface is learned yet, so the request is sent")
        (serve! peer)
        (let [ans (stream/append! r :x)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
          (is (= :dao.stream.remote/no-surface
                 (:dao.stream.remote/reason ans))
              "the source turns out to lack :writer"))
        (testing "and nothing was marked: reads still work"
          (let [c0 (do (stream/cursor r :dao.stream/oldest)
                       (serve! peer)
                       (:dao.stream/cursor
                         (stream/cursor r :dao.stream/oldest)))]
            (is (= :dao.stream/blocked
                   (:dao.stream/outcome (stream/next r c0))))
            (serve! peer)
            (is (= :dao.stream/ok
                   (:dao.stream/outcome (stream/next r c0)))))))))
  (testing "the learned surface answers no-surface locally"
    (let [t (toy)
          s (ring 8)]
      (stream/append! s :v1)
      (let [peer (served-peer {"x" (entry s #{:reader})} t)
            r (:dao.stream/handle (attach (:a-end t) "x" {}))]
        (serve! peer)
        (stream/descriptor r)
        (let [before (count (wire-requests (:ab t)))
              ans (stream/append! r :x)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
          (is (= :dao.stream.remote/no-surface
                 (:dao.stream.remote/reason ans)))
          (is (= before (count (wire-requests (:ab t))))
              "answered from the declared surface, nothing crossed"))))))


(deftest the-descriptor-answer-carries-the-surface
  (let [t (toy)
        s (ring 8)
        events (ring 16)
        peer (served-peer {"x" (entry s #{:reader})} t)
        r (:dao.stream/handle
            (attach (:a-end t) "x"
                    {:dao.stream.remote/events events}))]
    (is (empty? (values events)))
    (serve! peer)
    (stream/descriptor r)
    (testing "the probe's ok is emitted on the event writer"
      (let [evs (values events)]
        (is (= 1 (count evs)))
        (is (= #{:reader}
               (:dao.stream.remote/surface (first evs)))
            "the descriptor answer carries the declared surface")
        (is (= "x" (:dao.stream/identity (first evs))))
        (is (= :dao.stream/ok
               (:dao.stream/outcome (first evs))))))
    (testing "closable is always declared"
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/close! r)))))))


;; =============================================================================
;; The budget chase and oversize
;; =============================================================================

(deftest the-budget-chase
  (let [t (toy)
        s (ring 8)]
    (doseq [v [:a :b :c :d :e]]
      (stream/append! s v))
    (let [peer (served-peer {"n" (entry s #{:reader})} t)
          r (:dao.stream/handle
              (attach (:a-end t) "n" {:dao.stream.remote/budget 3}))]
      (serve! peer)
      (stream/cursor r :dao.stream/oldest)
      (serve! peer)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r c0))))
        (serve! peer)
        (let [v1 (stream/next r c0)
              c1 (:dao.stream/cursor v1)
              v2 (stream/next r c1)
              c2 (:dao.stream/cursor v2)
              v3 (stream/next r c2)
              served (count (wire-requests (:ab t)))]
          (testing "k outcomes reach the reader, in order, the further
                    ones filed at the cursor that precedes them"
            (is (= :a (:dao.stream/value v1)))
            (is (= :b (:dao.stream/value v2)))
            (is (= :c (:dao.stream/value v3)))
            (is (= :dao.stream/ok (:dao.stream/outcome v3))))
          (testing "past the budget a request is needed again"
            (let [c3 (:dao.stream/cursor v3)]
              (is (= :dao.stream/blocked
                     (:dao.stream/outcome (stream/next r c3))))
              (is (= (inc served) (count (wire-requests (:ab t)))))
              (serve! peer)
              (is (= :d (:dao.stream/value (stream/next r c3)))))))))))


(deftest the-budget-chase-ends-at-the-first-non-ok-outcome
  (let [t (toy)
        s (ring 8)]
    (doseq [v [:a :b]]
      (stream/append! s v))
    (let [peer (served-peer {"n" (entry s #{:reader})} t)
          r (:dao.stream/handle
              (attach (:a-end t) "n" {:dao.stream.remote/budget 5}))]
      (serve! peer)
      (stream/cursor r :dao.stream/oldest)
      (serve! peer)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (stream/next r c0)
        (serve! peer)
        (let [v1 (stream/next r c0)
              c1 (:dao.stream/cursor v1)
              v2 (stream/next r c1)
              c2 (:dao.stream/cursor v2)
              served (count (wire-requests (:ab t)))
              v3 (stream/next r c2)]
          (is (= :a (:dao.stream/value v1)))
          (is (= :b (:dao.stream/value v2)))
          (is (= :dao.stream/blocked (:dao.stream/outcome v3))
              "the source's own blocked, relayed by the chase")
          (is (= served (count (wire-requests (:ab t))))
              "the terminal outcome was filed, not asked"))))))


(deftest without-a-budget-no-chase-runs
  (let [t (toy)
        s (ring 8)]
    (doseq [v [:a :b]]
      (stream/append! s v))
    (let [peer (served-peer {"n" (entry s #{:reader})} t)
          r (:dao.stream/handle (attach (:a-end t) "n" {}))]
      (serve! peer)
      (stream/cursor r :dao.stream/oldest)
      (serve! peer)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (stream/next r c0)
        (serve! peer)
        (let [v1 (stream/next r c0)
              c1 (:dao.stream/cursor v1)
              served (count (wire-requests (:ab t)))]
          (is (= :dao.stream/blocked
                 (:dao.stream/outcome (stream/next r c1))))
          (is (= (inc served) (count (wire-requests (:ab t))))
              "the next position asked the source again"))))))


(deftest installed-outcomes-stay-with-their-own-stream
  (let [t (toy)
        s1 (list-stream "p-src" [:a1 :a2 :a3])
        s2 (list-stream "q-src" [:b1 :b2 :b3])
        peer (served-peer {"p" (entry s1 #{:reader})
                           "q" (entry s2 #{:reader})} t)
        attach! (remote/attacher
                  {:dao.stream.remote/channels {(:channel t) (:a-end t)}
                   :dao.stream.remote/budget 3})
        mk (fn [id]
             (:dao.stream/handle
               (attach! {:dao.stream/type :dao.stream/remote
                         :dao.stream/identity id
                         :dao.stream/channel (:channel t)})))
        ra (mk "p")
        rb (mk "q")]
    (serve! peer)
    (stream/descriptor ra)
    (stream/descriptor rb)
    (stream/cursor ra :dao.stream/oldest)
    (stream/cursor rb :dao.stream/oldest)
    (serve! peer)
    (let [ca (:dao.stream/cursor (stream/cursor ra :dao.stream/oldest))
          cb (:dao.stream/cursor (stream/cursor rb :dao.stream/oldest))]
      (is (= ca cb)
          "two served identities carrying equal cursor values")
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (stream/next ra ca))))
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (stream/next rb cb))))
      (serve! peer)
      (let [va1 (stream/next ra ca)
            vb1 (stream/next rb cb)]
        (is (= :a1 (:dao.stream/value va1))
            "p's own first outcome")
        (is (= :b1 (:dao.stream/value vb1))
            "q's own first outcome")
        (let [va2 (stream/next ra (:dao.stream/cursor va1))
              vb2 (stream/next rb (:dao.stream/cursor vb1))]
          (is (= :a2 (:dao.stream/value va2))
              "p's own installed outcome, not q's")
          (is (= :b2 (:dao.stream/value vb2))
              "q's own installed outcome, not p's")
          (testing "reflections of one stream share its installed
                    outcomes"
            (let [rc (mk "p")]
              (is (= :a3 (:dao.stream/value
                           (stream/next rc (:dao.stream/cursor va2))))
                  "the same stream's other reflection, without a
                   request")
              (is (= :b3 (:dao.stream/value
                           (stream/next rb
                                        (:dao.stream/cursor vb2))))
                  "and q's own installed outcome is untouched"))))))))


(deftest an-uncarryable-answer-is-oversize
  (let [t (toy)
        s (ring 8)]
    (doseq [v [:a :b :c :d :e]]
      (stream/append! s v))
    (let [peer (served-peer {"n" (entry s #{:reader})} t)
          ;; the frame budget bites on the answer path: the writer the
          ;; mirror itself appends to
          serve-capped!
          (fn []
            (swap! (:mirror peer)
                   #(remote/mirror-step
                      @(:table peer) (:b-reader peer) %
                      (capped-writer (:b-writer peer)))))
          r (:dao.stream/handle (attach (:a-end t) "n"
                                        {:dao.stream.remote/budget 4}))]
      (serve-capped!)
      (stream/cursor r :dao.stream/oldest)
      (serve-capped!)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r c0))))
        (serve-capped!)
        (let [ans (stream/next r c0)]
          (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
          (is (= :dao.stream.remote/oversize
                 (:dao.stream.remote/reason ans))
              "the chased answer could not be carried; the error took
               its place")
          (is (some #(= :dao.stream.remote/oversize
                        (:dao.stream.remote/error %))
                    (values (:ba t)))
              "the read was not skipped: the request was answered"))))))


;; =============================================================================
;; Loss and resend
;; =============================================================================

(deftest a-full-refused-send-leaves-nothing-outstanding
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
          ;; four refusals: the attach probe and the one retry of it
          ;; on each of the first two drains consume three, so the
          ;; first cursor ask is still the refused fourth send
          end {:reader (:ba t)
               :writer (full-then-forward-writer (:ab t) 4)}
          r (:dao.stream/handle (attach end "str-1" {}))]
      (testing "attach answers ok at once; the refused probe left
                nothing outstanding"
        (is (= :dao.stream/ok
               (:dao.stream/outcome (stream/descriptor r)))))
      (testing "the read answers as though unanswered"
        (is (= {:dao.stream/outcome :dao.stream/transport-error
                :dao.stream/retry? true}
               (stream/cursor r :dao.stream/oldest)))
        (is (empty? (op-requests (:ab t) :dao.stream/cursor))
            "the refused request never crossed"))
      (testing "when the writer recovers, the next ask sends again"
        (is (= :dao.stream/transport-error
               (:dao.stream/outcome (stream/cursor r :dao.stream/oldest))))
        (is (= 1 (count (op-requests (:ab t) :dao.stream/cursor)))
            "the re-send crossed; the refusal stranded nothing"))
      (serve! peer)
      (testing "and the re-sent request completes"
        (is (= (stream/cursor s :dao.stream/oldest)
               (stream/cursor r :dao.stream/oldest)))))))


(deftest a-refused-probe-is-kept-and-retried-on-a-later-drain
  (let [t (toy)
        events (ring 16)
        peer (served-peer {"str-1" (entry (ring 8) #{:reader})} t)
        end {:reader (:ba t)
             :writer (full-then-forward-writer (:ab t) 2)}
        r (:dao.stream/handle
            (attach end "str-1" {:dao.stream.remote/events events}))]
    (testing "the attach's refused probe is kept on the link, unsent"
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/descriptor r)))
          "attach answers ok at once")
      ;; the descriptor's drain retried the kept probe once: refused
      ;; again, and kept for the next drain
      (is (empty? (op-requests (:ab t) :dao.stream/descriptor))
          "both sends were refused; the probe is not lost")
      (is (empty? (values events))
          "no answer, so no confirmation event"))
    (testing "a later drain retries the kept probe, once"
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/descriptor r))))
      (is (= 1 (count (op-requests (:ab t) :dao.stream/descriptor)))
          "the writer has recovered: the one retry crossed and is the
           outstanding probe now"))
    (serve! peer)
    (testing "the kept probe's answer confirms the normal way"
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/descriptor r)))
          "the drain that reads the answer")
      (let [evs (values events)]
        (is (= 1 (count evs)))
        (is (= "str-1" (:dao.stream/identity (first evs))))
        (is (= :dao.stream/ok (:dao.stream/outcome (first evs))))
        (is (= #{:reader}
               (:dao.stream.remote/surface (first evs)))
            "the confirmation event carries the declared surface")))
    (testing "close! after the refusal sends no probe at all"
      ;; the writer refuses the attach send, then ACCEPTS: under the old
      ;; ordering (drain before forget) close!'s own drain would re-send
      ;; the kept probe and it would cross; under the fix it is
      ;; forgotten before the drain and never crosses
      (let [t2 (toy)
            events2 (ring 16)
            peer2 (served-peer {"str-2" (entry (ring 8) #{:reader})} t2)
            r2 (:dao.stream/handle
                 (attach {:reader (:ba t2)
                          :writer (full-then-forward-writer (:ab t2) 2)}
                         "str-2"
                         {:dao.stream.remote/events events2}))]
        (stream/descriptor r2)
        (is (empty? (op-requests (:ab t2) :dao.stream/descriptor))
            "the attach send and the descriptor drain's one retry were
             both refused; the probe is kept, not sent")
        (stream/close! r2)
        (is (empty? (op-requests (:ab t2) :dao.stream/descriptor))
            "close! is local: its drain does not send the kept probe,
             even though the writer now accepts")))
    (testing "the learned surface answers no-surface locally"
      (let [before (count (wire-requests (:ab t)))
            ans (stream/append! r :x)]
        (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
        (is (= :dao.stream.remote/no-surface
               (:dao.stream.remote/reason ans))
            "answered from the surface the probe's answer taught")
        (is (= before (count (wire-requests (:ab t))))
            "nothing crossed")))))


(deftest resend-after-resends-an-unanswered-request
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
          end {:reader (:ba t)
               :writer (dropping-writer (:ab t) 1
                                        #{:dao.stream/cursor})}
          r (:dao.stream/handle
              (attach end "str-1" {:dao.stream.remote/resend-after 2}))]
      (serve! peer)
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/descriptor r)))
          "the probe crossed; only cursor requests are dropped")
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (stream/cursor r :dao.stream/oldest)))
          "sent, and dropped by the channel")
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (stream/cursor r :dao.stream/oldest)))
          "one further ask, still unanswered")
      (is (empty? (op-requests (:ab t) :dao.stream/cursor))
          "the drop swallowed it")
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (stream/cursor r :dao.stream/oldest)))
          "the second further ask re-sends the same request")
      (is (= 1 (count (op-requests (:ab t) :dao.stream/cursor)))
          "the duplicate crossed")
      (serve! peer)
      (let [ans (stream/cursor r :dao.stream/oldest)]
        (is (= (stream/cursor s :dao.stream/oldest) ans)
            "the duplicate recomputed the same, equally true answer")))))


(deftest channel-loss
  (let [t (toy)
        s (ring 8)
        events (ring 16)]
    (stream/append! s :v1)
    (let [peer (served-peer {"x" (entry s #{:reader :writer})} t)
          r (:dao.stream/handle
              (attach (:a-end t) "x"
                      {:dao.stream.remote/events events
                       :dao.stream.remote/budget 2}))]
      (serve! peer)
      (stream/descriptor r)
      (stream/cursor r :dao.stream/oldest)
      (serve! peer)
      (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
        (stream/next r c0)
        (serve! peer)
        (let [v1 (stream/next r c0)
              c1 (:dao.stream/cursor v1)
              wr (stream/append! r :w2)
              sent (last (wire-requests (:ab t)))]
          (is (= :dao.stream/ok (:dao.stream/outcome wr))
              "acceptance on the outbound path")
          (stream/close! (:ab t))
          (stream/close! (:ba t))
          (testing "the end observation runs the channel-loss path"
            (is (= :dao.stream/blocked
                   (:dao.stream/outcome (stream/next r c1)))
                "a filed answer is still returned")
            (is (= {:dao.stream.remote/event
                    :dao.stream.remote/append-unknown
                    :dao.stream.remote/id
                    (:dao.stream.remote/id sent)}
                   (last (values events)))
                "the abandoned append! is reported append-unknown"))
          (testing "later cursor and next are channel-gone, not
                    retryable"
            (let [ans (stream/next r c1)]
              (is (= :dao.stream/transport-error
                     (:dao.stream/outcome ans)))
              (is (= :dao.stream.remote/channel-gone
                     (:dao.stream.remote/reason ans)))
              (is (not (contains? ans :dao.stream/retry?))))
            (is (= :dao.stream/transport-error
                   (:dao.stream/outcome (stream/cursor r c0)))))
          (testing "append! keeps answering the writer's own outcome"
            (is (= :dao.stream/closed
                   (:dao.stream/outcome (stream/append! r :w3))))))))))


;; =============================================================================
;; Refused, close, and what only the source may say
;; =============================================================================

(deftest refused-is-returned-verbatim-and-marks-nothing
  (let [t (toy)
        s (ring 8)]
    (stream/append! s :v1)
    (let [gate (middleware/gate
                 {:dao.stream.middleware/verify
                  (fn [_d _ctx req]
                    (when (= :dao.stream/cursor
                             (:dao.stream.remote/op req))
                      :remote-test/no-cursor))
                  :dao.stream.middleware/decision (ring 1)})
          gated (middleware/wrap s [gate])
          peer (served-peer {"x" (entry gated #{:reader})} t)
          r (:dao.stream/handle (attach (:a-end t) "x" {}))
          c0 (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))]
      (serve! peer)
      (stream/descriptor r)
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (stream/cursor r :dao.stream/oldest))))
      (serve! peer)
      (testing "the source's refusal comes back verbatim"
        (is (= {:dao.stream/outcome :dao.stream/refused
                :dao.stream.middleware/reason :remote-test/no-cursor}
               (stream/cursor r :dao.stream/oldest))))
      (testing "refusal is per operation: nothing was marked"
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r c0))))
        (serve! peer)
        (let [ans (stream/next r c0)]
          (is (= :dao.stream/ok (:dao.stream/outcome ans)))
          (is (= :v1 (:dao.stream/value ans))))))))


(deftest close-semantics
  (testing "outstanding ids are forgotten; nothing crosses the wire"
    (let [t (toy)
          s (ring 8)]
      (stream/append! s "hello")
      (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
            r (:dao.stream/handle (attach (:a-end t) "str-1" {}))]
        (serve! peer)
        (stream/descriptor r)
        (stream/cursor r :dao.stream/oldest)
        (serve! peer)
        (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
          (stream/next r c0)
          (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! r))))
          (is (= :dao.stream/ok (:dao.stream/outcome (stream/close! r)))
              "idempotent")
          (serve! peer)
          (let [reqs (count (wire-requests (:ab t)))]
            (is (= :dao.stream/end
                   (:dao.stream/outcome (stream/next r c0)))
                "the late answer is dropped with the forgotten id")
            (is (= reqs (count (wire-requests (:ab t))))
                "nothing crossed")
            (is (= :dao.stream/closed
                   (:dao.stream/outcome
                     (stream/cursor r :dao.stream/oldest))))
            (is (= :dao.stream/closed
                   (:dao.stream/outcome (stream/append! r :v)))))))))
  (testing "next answers filed outcomes, then end"
    (let [t (toy)
          s (ring 8)]
      (doseq [v [:a :b]]
        (stream/append! s v))
      (let [peer (served-peer {"x" (entry s #{:reader})} t)
            r (:dao.stream/handle
                (attach (:a-end t) "x" {:dao.stream.remote/budget 2}))]
        (serve! peer)
        (stream/cursor r :dao.stream/oldest)
        (serve! peer)
        (let [c0 (:dao.stream/cursor (stream/cursor r :dao.stream/oldest))]
          (stream/next r c0)
          (serve! peer)
          (let [v1 (stream/next r c0)
                c1 (:dao.stream/cursor v1)]
            (is (= :a (:dao.stream/value v1)))
            (stream/close! r)
            (let [v2 (stream/next r c1)]
              (is (= :b (:dao.stream/value v2))
                  "the filed outcome survives the close")
              (is (= :dao.stream/end
                     (:dao.stream/outcome
                       (stream/next r (:dao.stream/cursor v2))))
                  "then end")))))))
  (testing "an outstanding append! is reported append-unknown at close"
    (let [t (toy)
          s (ring 8)
          events (ring 8)
          peer (served-peer {"x" (entry s #{:reader :writer})} t)
          r (:dao.stream/handle
              (attach (:a-end t) "x"
                      {:dao.stream.remote/events events}))]
      (serve! peer)
      (stream/descriptor r)
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/append! r :w))))
      (let [id (:dao.stream.remote/id
                 (last (wire-requests (:ab t))))]
        (stream/close! r)
        (let [evs (values events)]
          (is (= 2 (count evs))
              "the probe's confirmation, then the report")
          (is (= {:dao.stream.remote/event
                  :dao.stream.remote/append-unknown
                  :dao.stream.remote/id id}
                 (last evs))))))))


(deftest next-relays-position-errors-and-never-judges
  (let [t (toy)
        s (ring 8)
        other (ring 4)
        foreign (:dao.stream/cursor
                  (stream/cursor other :dao.stream/oldest))]
    (stream/append! s :v1)
    (let [peer (served-peer {"x" (entry s #{:reader})} t)
          r (:dao.stream/handle (attach (:a-end t) "x" {}))]
      (serve! peer)
      (stream/descriptor r)
      (testing "a foreign stream's cursor is sent, and cursor-mismatch
                is relayed"
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r foreign))))
        (serve! peer)
        (is (= :dao.stream/cursor-mismatch
               (:dao.stream/outcome (stream/next r foreign)))
            "relayed, not judged"))
      (testing "a non-cursor is sent, and invalid-cursor is relayed"
        (is (= :dao.stream/blocked
               (:dao.stream/outcome (stream/next r :not-a-cursor))))
        (serve! peer)
        (is (= :dao.stream/invalid-cursor
               (:dao.stream/outcome (stream/next r :not-a-cursor)))
            "relayed, not judged")))))


(deftest answers-with-unknown-ids-are-dropped
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
          r (:dao.stream/handle (attach (:a-end t) "str-1" {}))]
      (serve! peer)
      (stream/descriptor r)
      (stream/append! (:ba t)
                      {:dao.stream.remote/id 999
                       :dao.stream/identity "str-1"
                       :dao.stream/outcome :dao.stream/ok
                       :dao.stream/cursor :forged})
      (stream/append! (:ba t) :diagnostic)
      (is (= :dao.stream/transport-error
             (:dao.stream/outcome (stream/cursor r :dao.stream/oldest))))
      (serve! peer)
      (let [ans (stream/cursor r :dao.stream/oldest)]
        (is (= (stream/cursor s :dao.stream/oldest) ans)
            "the real answer, filed under its own id; the rest dropped
             as diagnostics")))))


;; =============================================================================
;; The mirror step on the wire
;; =============================================================================

(deftest the-mirror-step-answers-the-four-ops-in-order
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [table {"str-1" (entry s #{:reader})
                 "writer-only" (entry (ring 4) #{:writer})
                 "unsurfaced" (entry (ring 4) #{})}
          rd (:reader (:b-end t))
          wr (:writer (:b-end t))
          mc (atom (:dao.stream/cursor
                     (stream/cursor rd :dao.stream/oldest)))
          ask (fn [tbl req]
                (let [ba (:ba t)
                      n (count (values ba))]
                  (stream/append! (:ab t) req)
                  (swap! mc #(remote/mirror-step tbl rd % wr))
                  (drop n (values ba))))]
      (testing "descriptor: the entry's answer plus the declared surface"
        (let [[ans] (ask table
                         {:dao.stream/identity "str-1"
                          :dao.stream.remote/op :dao.stream/descriptor
                          :dao.stream.remote/args []
                          :dao.stream.remote/id 1})]
          (is (= :dao.stream/ok (:dao.stream/outcome ans)))
          (is (= #{:reader} (:dao.stream.remote/surface ans)))
          (is (= 1 (:dao.stream.remote/id ans)))
          (is (= "str-1" (:dao.stream/identity ans)))
          (is (contains? ans :dao.stream/descriptor))))
      (testing "absent identity: not-found, before any surface check"
        (let [[ans] (ask table
                         {:dao.stream/identity "nope"
                          :dao.stream.remote/op :dao.stream/append!
                          :dao.stream.remote/args [:v]
                          :dao.stream.remote/id 2})]
          (is (= {:dao.stream.remote/error
                  :dao.stream.remote/not-found
                  :dao.stream.remote/id 2
                  :dao.stream/identity "nope"}
                 ans))))
      (testing "an op outside the declared surface: no-surface"
        (let [[ans] (ask table
                         {:dao.stream/identity "str-1"
                          :dao.stream.remote/op :dao.stream/append!
                          :dao.stream.remote/args [:v]
                          :dao.stream.remote/id 3})]
          (is (= :dao.stream.remote/no-surface
                 (:dao.stream.remote/error ans))))
        (let [[ans] (ask table
                         {:dao.stream/identity "writer-only"
                          :dao.stream.remote/op :dao.stream/next
                          :dao.stream.remote/args [:c]
                          :dao.stream.remote/id 4})]
          (is (= :dao.stream.remote/no-surface
                 (:dao.stream.remote/error ans)))))
      (testing "descriptor needs no surface and is always answerable"
        (let [[ans] (ask table
                         {:dao.stream/identity "unsurfaced"
                          :dao.stream.remote/op :dao.stream/descriptor
                          :dao.stream.remote/args []
                          :dao.stream.remote/id 5})]
          (is (= :dao.stream/ok (:dao.stream/outcome ans)))))
      (testing "cursor and next relay the source's outcome maps verbatim"
        (let [[ans] (ask table
                         {:dao.stream/identity "str-1"
                          :dao.stream.remote/op :dao.stream/cursor
                          :dao.stream.remote/args [:dao.stream/oldest]
                          :dao.stream.remote/id 6})
              c (:dao.stream/cursor ans)]
          (is (= (stream/cursor s :dao.stream/oldest)
                 (dissoc ans :dao.stream.remote/id
                         :dao.stream/identity
                         :dao.stream.remote/surface)))
          (let [[ans2] (ask table
                            {:dao.stream/identity "str-1"
                             :dao.stream.remote/op :dao.stream/next
                             :dao.stream.remote/args [c]
                             :dao.stream.remote/id 7})]
            (is (= "hello" (:dao.stream/value ans2)))
            (is (nil? (:dao.stream.remote/more ans2))
                "no budget, no chase"))))
      (testing "the chase places the further outcomes in order"
        (let [s2 (ring 8)]
          (doseq [v [:a :b :c]]
            (stream/append! s2 v))
          (let [table2 (assoc table "multi" (entry s2 #{:reader}))
                c0 (:dao.stream/cursor
                     (stream/cursor s2 :dao.stream/oldest))
                [ans] (ask table2
                           {:dao.stream/identity "multi"
                            :dao.stream.remote/op :dao.stream/next
                            :dao.stream.remote/args [c0]
                            :dao.stream.remote/id 8
                            :dao.stream.remote/budget 3})
                c1 (:dao.stream/cursor ans)
                c2 (:dao.stream/cursor (stream/next s2 c1))
                [ans1] (ask table2
                            {:dao.stream/identity "multi"
                             :dao.stream.remote/op :dao.stream/next
                             :dao.stream.remote/args [c1]
                             :dao.stream.remote/id 9
                             :dao.stream.remote/budget 1})]
            (is (= [(stream/next s2 c1) (stream/next s2 c2)]
                   (:dao.stream.remote/more ans))
                "k - 1 further outcome maps, in order, under more")
            (is (nil? (:dao.stream.remote/more ans1))
                "a budget of one chases nothing"))))
      (testing "malformed wire input is dropped below the mirror"
        (let [ba (:ba t)
              n (count (values ba))]
          (stream/append! (:ab t) :garbage)
          (stream/append! (:ab t) {:not :a-request})
          (swap! mc #(remote/mirror-step table rd % wr))
          (is (= n (count (values ba))) "nothing was answered"))))))


(deftest the-mirror-applies-requests-with-the-channel-context
  (let [t (toy)
        s (ring 8)
        chan-id (:dao.stream/identity
                  (stream/descriptor (:reader (:b-end t))))]
    (stream/append! s :v1)
    (testing "the gate sees the channel attachment identity"
      (let [seen (atom nil)
            gate (middleware/gate
                   {:dao.stream.middleware/verify
                    (fn [_d ctx _req]
                      (reset! seen (:dao.stream.remote/channel ctx))
                      nil)
                    :dao.stream.middleware/decision (ring 1)})
            gated (middleware/wrap s [gate])
            peer (served-peer {"x" (entry gated #{:reader})} t)
            r (:dao.stream/handle (attach (:a-end t) "x" {}))]
        (serve! peer)
        (stream/descriptor r)
        (stream/cursor r :dao.stream/oldest)
        (serve! peer)
        (stream/cursor r :dao.stream/oldest)
        (is (= chan-id @seen)
            "the mirror supplies the channel it serves the request on")))
    (testing "the trivial allow-list keys on that identity"
      (let [t2 (toy)
            s2 (ring 8)
            chan-id2 (:dao.stream/identity
                       (stream/descriptor (:reader (:b-end t2))))
            gate-on (middleware/gate
                      {:dao.stream.middleware/verify
                       (middleware/channel-allow-list #{chan-id2})
                       :dao.stream.middleware/decision (ring 1)})
            gate-off (middleware/gate
                       {:dao.stream.middleware/verify
                        (middleware/channel-allow-list #{})
                        :dao.stream.middleware/decision (ring 1)})
            on (middleware/wrap s2 [gate-on])
            off (middleware/wrap s2 [gate-off])
            peer (served-peer {"on" (entry on #{:reader})
                               "off" (entry off #{:reader})} t2)
            attach! (remote/attacher
                      {:dao.stream.remote/channels
                       {(:channel t2) (:a-end t2)}})
            mk (fn [id]
                 (attach! {:dao.stream/type :dao.stream/remote
                           :dao.stream/identity id
                           :dao.stream/channel (:channel t2)}))
            ra (:dao.stream/handle (mk "on"))
            rb (:dao.stream/handle (mk "off"))]
        (serve! peer)
        (stream/descriptor ra)
        (stream/descriptor rb)
        (stream/cursor ra :dao.stream/oldest)
        (stream/cursor rb :dao.stream/oldest)
        (serve! peer)
        (is (= :dao.stream/ok
               (:dao.stream/outcome
                 (stream/cursor ra :dao.stream/oldest)))
            "the served channel is on the list")
        (is (= {:dao.stream/outcome :dao.stream/refused
                :dao.stream.middleware/reason
                :dao.stream.middleware/channel-not-allowed}
               (stream/cursor rb :dao.stream/oldest))
            "a channel off the list is refused, verbatim, and the
             refusal stays with its own reflection")))))


;; =============================================================================
;; The remote descriptor dispatch
;; =============================================================================

(deftest the-remote-descriptor-dispatches
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
          attach! (remote/attacher
                    {:dao.stream.remote/channels
                     {(:channel t) (:a-end t)}})
          dispatch {:dao.stream/remote {:dao.stream/attach attach!}}
          rd {:dao.stream/type :dao.stream/remote
              :dao.stream/identity "str-1"
              :dao.stream/channel (:channel t)}]
      (testing "attach! dispatches on :dao.stream/remote"
        (let [res (stream/host-dispatch-attach! dispatch rd)]
          (is (= :dao.stream/ok (:dao.stream/outcome res)))
          (is (some? (:dao.stream/attachment res)))
          (is (stream/descriptor? (:dao.stream/handle res)))
          (is (stream/reader? (:dao.stream/handle res)))))
      (testing "no :dao.stream/create entry: this transport never creates"
        (is (= :dao.stream/not-found
               (:dao.stream/outcome
                 (stream/host-dispatch-create! dispatch rd)))))
      (testing "a malformed remote descriptor is invalid"
        (is (= :dao.stream/invalid-descriptor
               (:dao.stream/outcome
                 (attach! {:dao.stream/type :dao.stream/remote
                           :dao.stream/identity "str-1"})))))
      (testing "an unreachable channel is not-found"
        (is (= :dao.stream/not-found
               (:dao.stream/outcome
                 (attach! {:dao.stream/type :dao.stream/remote
                           :dao.stream/identity "str-1"
                           :dao.stream/channel
                           {:dao.stream/type :dao.stream.test/channel
                            :dao.stream/identity "elsewhere"}})))))
      (testing "two descriptors, one channel descriptor: one link"
        (let [ha (:dao.stream/handle (attach! rd))
              hb (:dao.stream/handle (attach! rd))
              aa (:dao.stream/attachment (attach! rd))
              ab2 (:dao.stream/attachment (attach! rd))]
          (is (not= aa ab2) "each attachment is its own")
          (serve! peer)
          (stream/descriptor ha)
          (stream/descriptor hb)
          (stream/cursor ha :dao.stream/oldest)
          (stream/cursor hb :dao.stream/oldest)
          (serve! peer)
          (is (= (stream/cursor s :dao.stream/oldest)
                 (stream/cursor ha :dao.stream/oldest)))
          (is (= (stream/cursor s :dao.stream/oldest)
                 (stream/cursor hb :dao.stream/oldest))
              "the shared link files each answer for its own
               reflection"))))))

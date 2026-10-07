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
   kept and retried on a later drain; a well-formed answer carrying
   the right id and another identity left outstanding as a
   diagnostic; and the installed outcomes kept to their own served
   stream."
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


(deftest a-wrong-identity-answer-leaves-its-request-outstanding
  (let [t (toy)
        s (ring 8)]
    (stream/append! s "hello")
    (let [peer (served-peer {"str-1" (entry s #{:reader})} t)
          r (:dao.stream/handle (attach (:a-end t) "str-1" {}))]
      (serve! peer)
      (stream/descriptor r)
      ;; the cursor request is outstanding, its answer not yet written
      (stream/cursor r :dao.stream/oldest)
      (let [id (:dao.stream.remote/id
                 (last (wire-requests (:ab t))))]
        (testing "a well-formed answer with the right id and another
                  identity is a diagnostic: nothing filed, nothing
                  marked, no duplicate sent"
          (stream/append! (:ba t)
                          {:dao.stream/identity "impostor"
                           :dao.stream.remote/id id
                           :dao.stream/outcome :dao.stream/ok
                           :dao.stream/cursor :forged})
          (let [sent (count (op-requests (:ab t) :dao.stream/cursor))]
            (is (= {:dao.stream/outcome :dao.stream/transport-error
                    :dao.stream/retry? true}
                   (stream/cursor r :dao.stream/oldest))
                "the forged answer completed nothing")
            (is (= sent (count (op-requests (:ab t) :dao.stream/cursor)))
                "the request remains outstanding: no duplicate crossed"))))
      (serve! peer)
      (testing "the correct answer arriving afterwards completes the
                request normally"
        (is (= (stream/cursor s :dao.stream/oldest)
               (stream/cursor r :dao.stream/oldest))
            "the source's own answer, never the forged one")))))


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


;; =============================================================================
;; Name resolution (2.1, 2.3 step 0, 2.4 resolve)
;; =============================================================================

(defn- serve-named!
  "One mirror step of `peer` with the name map `names` beside its
   table."
  [peer names]
  (swap! (:mirror peer)
         #(remote/mirror-step @(:table peer) names (:b-reader peer)
                              % (:b-writer peer))))


(defn- named-request
  [n op id]
  {:dao.stream.remote/name n
   :dao.stream.remote/op op
   :dao.stream.remote/args []
   :dao.stream.remote/id id})


(defn- answers-to
  "The answers retained on wire buffer `h` carrying the id `id`."
  [h id]
  (filterv #(and (map? %) (= id (:dao.stream.remote/id %))) (values h)))


(def ^:private retry
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream/retry? true})


(deftest a-named-descriptor-request-answers-the-entrys-own-identity
  (let [t (toy)
        s (ring 1)
        id (:dao.stream/identity (stream/descriptor s))
        peer (served-peer {id (entry s #{:reader})} t)
        names {"yin.head/p" id "stale" "no-such-entry"}]
    (doseq [[n op rid] [["yin.head/p" :dao.stream/descriptor 1]
                        ["unmapped" :dao.stream/descriptor 2]
                        ["stale" :dao.stream/descriptor 3]
                        ["yin.head/p" :dao.stream/next 4]
                        ["yin.head/p" :dao.stream/cursor 5]
                        ["yin.head/p" :dao.stream/append! 6]]]
      (stream/append! (:ab t) (named-request n op rid)))
    (stream/append! (:ab t) (assoc (named-request "yin.head/p"
                                                  :dao.stream/descriptor 7)
                                   :dao.stream.remote/args :not-a-vector))
    (serve-named! peer names)
    (testing "a mapped name: the entry's own identity and surface, the
              name echoed"
      (let [[a & more] (answers-to (:ba t) 1)]
        (is (nil? more))
        (is (= :dao.stream/ok (:dao.stream/outcome a)))
        (is (= id (:dao.stream/identity a)) "the ring's own identity")
        (is (= #{:reader} (:dao.stream.remote/surface a)))
        (is (= "yin.head/p" (:dao.stream.remote/name a)))
        (is (= (:dao.stream/descriptor (stream/descriptor s))
               (:dao.stream/descriptor a))
            "the descriptor answer of the entry's handle")))
    (testing "an unmapped name, and a name mapped to an identity the
              table lacks, are not-found carrying the name"
      (doseq [[rid n] [[2 "unmapped"] [3 "stale"]]]
        (is (= [{:dao.stream.remote/id rid
                 :dao.stream.remote/name n
                 :dao.stream.remote/error :dao.stream.remote/not-found}]
               (answers-to (:ba t) rid)))))
    (testing "a named request with any other op is malformed and dropped"
      (doseq [rid [4 5 6 7]]
        (is (= [] (answers-to (:ba t) rid)))))
    (testing "no value on the wire carries the name as an identity"
      (is (not-any? #(contains? #{"yin.head/p" "unmapped" "stale"}
                                (:dao.stream/identity %))
                    (values (:ba t)))))
    (testing "without a name map every name is unmapped"
      (let [t (toy)
            peer (served-peer {id (entry s #{:reader})} t)]
        (stream/append! (:ab t) (named-request "yin.head/p"
                                               :dao.stream/descriptor 1))
        (serve! peer)
        (is (= :dao.stream.remote/not-found
               (:dao.stream.remote/error (first (answers-to (:ba t) 1)))))))))


(deftest an-identity-request-is-answered-as-before
  (let [s (ring 4)
        _ (stream/append! s "hello")
        id (:dao.stream/identity (stream/descriptor s))
        reqs [{:dao.stream/identity id
               :dao.stream.remote/op :dao.stream/descriptor
               :dao.stream.remote/args []
               :dao.stream.remote/id 1}
              {:dao.stream/identity id
               :dao.stream.remote/op :dao.stream/cursor
               :dao.stream.remote/args [:dao.stream/oldest]
               :dao.stream.remote/id 2}
              {:dao.stream/identity "absent"
               :dao.stream.remote/op :dao.stream/descriptor
               :dao.stream.remote/args []
               :dao.stream.remote/id 3}]
        answered (fn [serve reqs]
                   (let [t (toy)
                         peer (served-peer {id (entry s #{:reader})} t)]
                     (doseq [r reqs] (stream/append! (:ab t) r))
                     (serve peer)
                     (values (:ba t))))
        before (answered serve! reqs)]
    (is (= 3 (count before)))
    (is (= before (answered #(serve-named! % {"n" id}) reqs))
        "a name map changes nothing for an identity request")
    (is (= before (answered #(serve-named! % {"n" id})
                            (mapv #(assoc % :dao.stream.remote/name "n")
                                  reqs)))
        "a request carrying an identity is an identity request whatever
         else it carries: a name beside it is ignored")))


(defn- resolving
  "Peer B serving the ring `s` under its own identity and the name
   yin.head/p, and A's links over the toy with `opts`."
  ([s] (resolving s {}))
  ([s opts]
   (let [t (toy)
         id (:dao.stream/identity (stream/descriptor s))
         peer (served-peer {id (entry s #{:reader})} t)
         ls (remote/links (merge {:dao.stream.remote/channels
                                  {(:channel t) (:a-end t)}}
                                 opts))]
     {:t t :id id :peer peer :links ls
      :serve! #(serve-named! peer {"yin.head/p" id})
      :resolve #((:resolve ls) (:channel t) %)})))


(deftest resolve-answers-retry-then-a-descriptor-attach-accepts
  (let [s (ring 1)
        _ (stream/append! s "head")
        {:keys [t id links serve! resolve]} (resolving s)]
    (is (= retry (resolve "yin.head/p"))
        "no answer filed yet: retry, as cursor answers")
    (is (= 1 (count (op-requests (:ab t) :dao.stream/descriptor))))
    (is (= retry (resolve "yin.head/p")))
    (is (= 1 (count (op-requests (:ab t) :dao.stream/descriptor)))
        "outstanding: not sent again")
    (is (not-any? #(contains? % :dao.stream/identity)
                  (op-requests (:ab t) :dao.stream/descriptor))
        "the named request carries no identity")
    (serve!)
    (let [r (resolve "yin.head/p")
          d (:dao.stream/descriptor r)]
      (is (= :dao.stream/ok (:dao.stream/outcome r)))
      (is (= id (:dao.stream/identity r)))
      (is (= #{:reader} (:dao.stream.remote/surface r)))
      (is (= {:dao.stream/type :dao.stream/remote
              :dao.stream/identity id
              :dao.stream/channel (:channel t)}
             d)
          "the remote descriptor of the real stream over the same
           channel")
      (testing "attach! accepts it, through the same link"
        (let [a ((:attach links) d)
              refl (:dao.stream/handle a)]
          (is (= :dao.stream/ok (:dao.stream/outcome a)))
          (is (= id (:dao.stream/identity (stream/descriptor refl)))
              "the reflection reports the ring's own identity")
          (serve!)
          (stream/cursor refl :dao.stream/oldest)
          (serve!)
          (let [c (stream/cursor refl :dao.stream/oldest)]
            (is (= (stream/cursor s :dao.stream/oldest) c))
            (stream/next refl (:dao.stream/cursor c))
            (serve!)
            (is (= "head" (:dao.stream/value
                            (stream/next refl (:dao.stream/cursor c))))))))
      (testing "a resolved answer is forgotten: a name is asked afresh"
        (is (= retry (resolve "yin.head/p")))))))


(deftest resolve-of-an-unmapped-name-is-not-found
  (let [{:keys [t serve! resolve]} (resolving (ring 1))]
    (resolve "yin.head/q")
    (serve!)
    (is (= {:dao.stream/outcome :dao.stream/transport-error
            :dao.stream.remote/reason :dao.stream.remote/not-found}
           (resolve "yin.head/q")))
    (testing "nothing is marked: the name may be asked again"
      (is (= retry (resolve "yin.head/q")))
      (is (= 2 (count (op-requests (:ab t) :dao.stream/descriptor)))))))


(deftest resolve-over-an-unreached-or-lost-channel
  (let [{:keys [t links resolve]} (resolving (ring 1))]
    (is (= {:dao.stream/outcome :dao.stream/not-found}
           ((:resolve links) {:dao.stream/type :dao.stream.test/channel
                              :dao.stream/identity "elsewhere"}
                             "yin.head/p"))
        "a channel this peer does not reach")
    (resolve "yin.head/p")
    (stream/close! (:ba t))
    (is (= {:dao.stream/outcome :dao.stream/transport-error
            :dao.stream.remote/reason :dao.stream.remote/channel-gone}
           (resolve "yin.head/p"))
        "channel loss: not retryable")))


(deftest unexpected-answers-to-a-resolve-are-dropped
  (let [{:keys [t resolve]} (resolving (ring 1))]
    (resolve "yin.head/p")
    (let [rid (:dao.stream.remote/id
                (first (op-requests (:ab t) :dao.stream/descriptor)))]
      (doseq [v [;; the right id, another name
                 {:dao.stream.remote/id rid
                  :dao.stream.remote/name "yin.head/other"
                  :dao.stream.remote/error :dao.stream.remote/not-found}
                 ;; the right id, no name
                 {:dao.stream.remote/id rid
                  :dao.stream/identity "x"
                  :dao.stream.remote/error :dao.stream.remote/not-found}
                 ;; named, no identity, and not the not-found error
                 {:dao.stream.remote/id rid
                  :dao.stream.remote/name "yin.head/p"
                  :dao.stream/outcome :dao.stream/ok}
                 ;; shapes off a channel
                 nil 7 "s" [1 2] (list 1 2 3) {}]]
        (stream/append! (:ba t) v))
      (is (= retry (resolve "yin.head/p"))
          "none completed the request, which stays outstanding")
      (is (= 1 (count (op-requests (:ab t) :dao.stream/descriptor)))))))


(deftest resolve-is-resent-after-k-asks
  (let [s (ring 1)
        t (toy)
        id (:dao.stream/identity (stream/descriptor s))
        peer (served-peer {id (entry s #{:reader})} t)
        end {:reader (:ba t)
             :writer (dropping-writer (:ab t) 1 #{:dao.stream/descriptor})}
        ls (remote/links {:dao.stream.remote/channels {(:channel t) end}
                          :dao.stream.remote/resend-after 2})
        resolve #((:resolve ls) (:channel t) %)]
    (resolve "yin.head/p")
    (is (= 0 (count (op-requests (:ab t) :dao.stream/descriptor)))
        "the channel lost the request")
    (resolve "yin.head/p")
    (resolve "yin.head/p")
    (is (= 1 (count (op-requests (:ab t) :dao.stream/descriptor)))
        "re-sent at the k-th further ask, with the same id")
    (serve-named! peer {"yin.head/p" id})
    (is (= id (:dao.stream/identity (resolve "yin.head/p"))))))


(deftest a-named-request-with-args-is-malformed
  (let [t (toy)
        s (ring 1)
        id (:dao.stream/identity (stream/descriptor s))
        peer (served-peer {id (entry s #{:reader})} t)]
    (doseq [[rid args] [[1 [1]] [2 [1 2]] [3 []]]]
      (stream/append! (:ab t) (assoc (named-request "yin.head/p"
                                                    :dao.stream/descriptor rid)
                                     :dao.stream.remote/args args)))
    (serve-named! peer {"yin.head/p" id})
    (is (= [] (answers-to (:ba t) 1)))
    (is (= [] (answers-to (:ba t) 2)))
    (is (= id (:dao.stream/identity (first (answers-to (:ba t) 3))))
        "empty args: answered")))


(defn- not-found-refusing-writer
  "A channel writer that carries values to `h` but refuses, with
   invalid-value, any not-found error: the frame budget bites on the
   answer to an unmapped name, never on its oversize fallback."
  [h]
  (reify stream/IDaoStreamWriter
    (append!
      [_ v]
      (if (= :dao.stream.remote/not-found (:dao.stream.remote/error v))
        {:dao.stream/outcome :dao.stream/invalid-value}
        (stream/append! h v)))))


(deftest an-uncarryable-not-found-is-an-oversize-resolve
  (let [{:keys [t peer resolve]} (resolving (ring 1))]
    (resolve "yin.head/q")
    (swap! (:mirror peer)
           #(remote/mirror-step @(:table peer) {} (:b-reader peer) %
                                (not-found-refusing-writer (:ba t))))
    (is (= [{:dao.stream.remote/id 0
             :dao.stream.remote/name "yin.head/q"
             :dao.stream.remote/error :dao.stream.remote/oversize}]
           (values (:ba t)))
        "the fallback carries the name and no identity")
    (is (= {:dao.stream/outcome :dao.stream/transport-error
            :dao.stream.remote/reason :dao.stream.remote/oversize}
           (resolve "yin.head/q"))
        "filed and returned, not left outstanding")))


(defn- counting-writer
  "A channel writer that answers `outcome` to every append, counting
   the attempts in `n`."
  [n outcome]
  (reify stream/IDaoStreamWriter
    (append!
      [_ _]
      (swap! n inc)
      {:dao.stream/outcome outcome})))


(defn- link-of
  "The link `ls` keeps for the toy's channel, read through a reflection
   attached on it: the link state is the reflection's :link."
  [ls t]
  (:link @(.-state (:dao.stream/handle
                     ((:attach ls) {:dao.stream/type :dao.stream/remote
                                    :dao.stream/identity "probe"
                                    :dao.stream/channel (:channel t)})))))


(defn- refusing-links
  [t attempts outcome]
  (remote/links {:dao.stream.remote/channels
                 {(:channel t)
                  {:reader (:ba t)
                   :writer (counting-writer attempts outcome)}}}))


(deftest an-uncarryable-name-is-a-terminal-resolve
  (doseq [outcome [:dao.stream/invalid-value :dao.stream/closed]]
    (testing (str outcome)
      (let [t (toy)
            attempts (atom 0)
            ls (refusing-links t attempts outcome)
            link (link-of ls t)
            _ (reset! attempts 0)
            resolve #((:resolve ls) (:channel t) "yin.head/p")]
        (is (= {:dao.stream/outcome outcome} (resolve)))
        (is (= {:dao.stream/outcome outcome} (resolve))
            "terminal, and the writer asked again")
        (is (= {:dao.stream/outcome outcome} (resolve)))
        (is (= 3 @attempts) "each resolve attempts the send")
        (is (= {} (:outstanding @link)))
        (is (= {} (:filed @link))))))
  (testing "a hundred refused names: the link holds none of them"
    (let [t (toy)
          attempts (atom 0)
          ls (refusing-links t attempts :dao.stream/invalid-value)
          link (link-of ls t)
          names (set (map #(str "yin.head/n" %) (range 100)))]
      (doseq [n names]
        (is (= {:dao.stream/outcome :dao.stream/invalid-value}
               ((:resolve ls) (:channel t) n))))
      (is (= {} (:outstanding @link)))
      (is (= {} (:filed @link)))
      (is (not-any? names (tree-seq coll? seq @link))
          "no name in any key of the link")))
  (testing "full is backpressure: retried by the next resolve"
    (let [s (ring 1)
          t (toy)
          id (:dao.stream/identity (stream/descriptor s))
          peer (served-peer {id (entry s #{:reader})} t)
          ls (remote/links {:dao.stream.remote/channels
                            {(:channel t)
                             {:reader (:ba t)
                              :writer (full-then-forward-writer (:ab t) 1)}}})
          resolve #((:resolve ls) (:channel t) "yin.head/p")]
      (is (= retry (resolve)))
      (is (= 0 (count (op-requests (:ab t) :dao.stream/descriptor))))
      (is (= retry (resolve)))
      (is (= 1 (count (op-requests (:ab t) :dao.stream/descriptor))))
      (serve-named! peer {"yin.head/p" id})
      (is (= id (:dao.stream/identity (resolve)))))))


(deftest an-answer-with-no-identity-completes-no-identity-request
  (doseq [e [:dao.stream.remote/not-found :dao.stream.remote/oversize]]
    (testing (str "absent identity, " e)
      (let [t (toy)
            r (:dao.stream/handle (attach (:a-end t) nil {}))
            link (:link @(.-state r))]
        (is (contains? (:outstanding @link) 0) "the probe is outstanding")
        (stream/append! (:ba t) {:dao.stream.remote/id 0
                                 :dao.stream.remote/name "unasked"
                                 :dao.stream.remote/error e})
        (is (= retry (stream/cursor r :dao.stream/oldest))
            "dropped: the reflection is not gone")
        (is (contains? (:outstanding @link) 0) "the probe stays outstanding"))))
  (testing "an explicit nil identity behaves as before"
    (let [t (toy)
          r (:dao.stream/handle (attach (:a-end t) nil {}))
          link (:link @(.-state r))]
      (stream/append! (:ba t) {:dao.stream.remote/id 0
                               :dao.stream/identity nil
                               :dao.stream.remote/error
                               :dao.stream.remote/not-found})
      (is (= {:dao.stream/outcome :dao.stream/transport-error
              :dao.stream.remote/reason :dao.stream.remote/not-found}
             (stream/cursor r :dao.stream/oldest))
          "it matches the nil identity and marks the reflection gone")
      (is (not (contains? (:outstanding @link) 0))))))


;; =============================================================================
;; Answering-side loop budgets (S2a)
;; =============================================================================

(defn- descriptor-request
  [id]
  {:dao.stream/identity "s"
   :dao.stream.remote/op :dao.stream/descriptor
   :dao.stream.remote/args []
   :dao.stream.remote/id id})


(defn- answer-ids
  [h]
  (mapv :dao.stream.remote/id (values h)))


(deftest mirror-budget-bounds-requests-per-step
  (let [table {"s" (entry (ring 4) #{:reader})}
        rd (ring 16)
        wr (ring 16)
        c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))
        bounds {:dao.stream.remote/mirror-budget 2}]
    (doseq [id [1 2 3 4 5]]
      (stream/append! rd (descriptor-request id)))
    (let [c1 (remote/mirror-step table nil rd c0 wr bounds)]
      (is (= [1 2] (answer-ids wr)) "two answers in one step")
      (is (= 3 (:dao.stream.remote/id
                 (:dao.stream/value (stream/next rd c1))))
          "the returned cursor precedes request 3")
      (let [c2 (remote/mirror-step table nil rd c1 wr bounds)]
        (is (= [1 2 3 4] (answer-ids wr)) "the next call continues")
        (remote/mirror-step table nil rd c2 wr bounds)
        (is (= [1 2 3 4 5] (answer-ids wr)))))
    (testing "nil bounds and a nil budget are unbounded"
      (let [wr2 (ring 16)
            wr3 (ring 16)]
        (remote/mirror-step table nil rd c0 wr2 nil)
        (remote/mirror-step table nil rd c0 wr3
                            {:dao.stream.remote/mirror-budget nil})
        (is (= [1 2 3 4 5] (answer-ids wr2)))
        (is (= [1 2 3 4 5] (answer-ids wr3)))))))


(deftest malformed-values-and-gaps-count-against-the-mirror-budget
  (let [table {"s" (entry (ring 4) #{:reader})}
        bounds {:dao.stream.remote/mirror-budget 2}]
    (testing "malformed values each count one"
      (let [rd (ring 16)
            wr (ring 16)
            c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))]
        (stream/append! rd :garbage)
        (stream/append! rd {:not :a-request})
        (stream/append! rd (descriptor-request 1))
        (let [c1 (remote/mirror-step table nil rd c0 wr bounds)]
          (is (= [] (answer-ids wr)) "the budget went to the junk")
          (remote/mirror-step table nil rd c1 wr bounds)
          (is (= [1] (answer-ids wr))))))
    (testing "a gap counts one"
      (let [rd (ring 2)
            wr (ring 16)
            c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))]
        (doseq [v [:evicted (descriptor-request 1) (descriptor-request 2)]]
          (stream/append! rd v))
        (is (= :dao.stream/gap (:dao.stream/outcome (stream/next rd c0))))
        (let [c1 (remote/mirror-step table nil rd c0 wr bounds)]
          (is (= [1] (answer-ids wr)) "gap plus one request")
          (remote/mirror-step table nil rd c1 wr bounds)
          (is (= [1 2] (answer-ids wr))))))))


(deftest an-invalid-mirror-bound-is-a-composition-error
  (let [table {"s" (entry (ring 4) #{:reader})}
        rd (ring 16)
        wr (ring 16)
        c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))]
    (stream/append! rd (descriptor-request 1))
    (doseq [bounds [{:dao.stream.remote/mirror-budget 0}
                    {:dao.stream.remote/mirror-budget 1.5}
                    {:dao.stream.remote/mirror-budget -1}
                    {:dao.stream.remote/chase-budget 0}
                    7]]
      (let [e (try (remote/mirror-step table nil rd c0 wr bounds)
                   nil
                   (catch #?(:clj Exception :cljs :default :cljd Object) e
                     e))]
        (is (some? e) (str "rejects " (pr-str bounds)))
        (is (= {:bounds bounds} (ex-data e)))))
    (is (= [] (answer-ids wr)) "nothing answered")))


(deftest chase-budget-clamps-a-peer-requested-budget
  (let [s (ring 16)
        _ (doseq [v (range 12)] (stream/append! s v))
        table {"s" (entry s #{:reader})}
        c0 (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
        req {:dao.stream/identity "s"
             :dao.stream.remote/op :dao.stream/next
             :dao.stream.remote/args [c0]
             :dao.stream.remote/id 1
             :dao.stream.remote/budget 10}
        answer (fn [bounds]
                 (let [rd (ring 4)
                       wr (ring 4)
                       c (:dao.stream/cursor
                           (stream/cursor rd :dao.stream/oldest))]
                   (stream/append! rd req)
                   (remote/mirror-step table nil rd c wr bounds)
                   (first (values wr))))]
    (is (= 2 (count (:dao.stream.remote/more
                      (answer {:dao.stream.remote/chase-budget 3}))))
        "the first outcome is the answer itself")
    (is (= 9 (count (:dao.stream.remote/more (answer nil))))
        "without a chase-budget the peer's budget stands")
    (is (= 9 (count (:dao.stream.remote/more
                      (answer {:dao.stream.remote/chase-budget 20}))))
        "a larger chase-budget clamps nothing")))


(deftest a-full-writer-rewinds-idempotent-answers-and-drops-append-answers
  (testing "an idempotent answer refused full is re-answered next call"
    (let [table {"s" (entry (ring 4) #{:reader})}
          rd (ring 16)
          out (ring 16)
          wr (full-then-forward-writer out 1)
          c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))]
      (stream/append! rd (descriptor-request 1))
      (stream/append! rd (descriptor-request 2))
      (let [c1 (remote/mirror-step table nil rd c0 wr nil)]
        (is (= c0 c1) "the step stopped before the refused request")
        (is (= [] (answer-ids out)))
        (remote/mirror-step table nil rd c1 wr nil)
        (is (= [1 2] (answer-ids out))
            "same id, answered on the second tick"))))
  (testing "cursor and next answers refused full rewind too"
    (let [s (ring 8)
          _ (stream/append! s :a)
          table {"s" (entry s #{:reader})}
          c-src (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))]
      (doseq [req [{:dao.stream/identity "s"
                    :dao.stream.remote/op :dao.stream/cursor
                    :dao.stream.remote/args [:dao.stream/oldest]
                    :dao.stream.remote/id 1}
                   {:dao.stream/identity "s"
                    :dao.stream.remote/op :dao.stream/next
                    :dao.stream.remote/args [c-src]
                    :dao.stream.remote/id 1}]]
        (testing (:dao.stream.remote/op req)
          (let [rd (ring 16)
                out (ring 16)
                ;; carries the first answer, refuses the second once
                writes (atom 0)
                wr (reify stream/IDaoStreamWriter
                     (append!
                       [_ v]
                       (if (= 2 (swap! writes inc))
                         {:dao.stream/outcome :dao.stream/full}
                         (stream/append! out v))))
                c0 (:dao.stream/cursor
                     (stream/cursor rd :dao.stream/oldest))]
            (stream/append! rd (descriptor-request 0))
            (stream/append! rd req)
            (let [c-req (:dao.stream/cursor (stream/next rd c0))
                  c1 (remote/mirror-step table nil rd c0 wr nil)]
              (is (= [0] (answer-ids out)) "the request was refused")
              (is (= c-req c1) "rewound to the cursor preceding it")
              (remote/mirror-step table nil rd c1 wr nil)
              (is (= [0 1] (answer-ids out))
                  "the same id, answered on the next call")))))))
  (testing "a named descriptor answer refused full rewinds too"
    (let [table {"s" (entry (ring 4) #{:reader})}
          rd (ring 16)
          out (ring 16)
          wr (full-then-forward-writer out 1)
          c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))]
      (stream/append! rd {:dao.stream.remote/name "nm"
                          :dao.stream.remote/op :dao.stream/descriptor
                          :dao.stream.remote/args []
                          :dao.stream.remote/id 1})
      (is (= c0 (remote/mirror-step table {"nm" "s"} rd c0 wr nil)))
      (remote/mirror-step table {"nm" "s"} rd c0 wr nil)
      (is (= [1] (answer-ids out)))))
  (testing "an append! answer refused full is dropped, not re-applied"
    (let [target (ring 8)
          table {"w" (entry target #{:writer})}
          rd (ring 16)
          out (ring 16)
          wr (full-then-forward-writer out 1)
          c0 (:dao.stream/cursor (stream/cursor rd :dao.stream/oldest))]
      (stream/append! rd {:dao.stream/identity "w"
                          :dao.stream.remote/op :dao.stream/append!
                          :dao.stream.remote/args [:v]
                          :dao.stream.remote/id 1})
      (let [c1 (remote/mirror-step table nil rd c0 wr nil)]
        (is (not= c0 c1) "the step moved past the append")
        (remote/mirror-step table nil rd c1 wr nil)
        (is (= [:v] (values target)) "the source append ran once")
        (is (= [] (answer-ids out)) "its answer was not re-sent")))))

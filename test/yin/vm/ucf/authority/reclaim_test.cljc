(ns yin.vm.ucf.authority.reclaim-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb-c6")

(def ^:private occ fx/occurrence)

(def ^:private duration {:s 30})


(defn- fresh-frames
  []
  (atom [(cbor/encode {:dao.stream.journal/header
                       {:version 1 :identity arb}})]))


(defn- backend
  ([frames] (backend frames nil))
  ([frames cut] (journal/memory-backend frames cut)))


(defn- auth
  ([frames] (auth frames nil nil))
  ([frames cut] (auth frames cut nil))
  ([frames cut opts]
   (::authority/authority (authority/open! (backend frames cut) opts))))


(defn- reopen
  "Reopen through grant/reopen!, answering the authority."
  ([frames] (reopen frames nil))
  ([frames opts]
   (::authority/authority (grant/reopen! (backend frames) opts))))


(defn- body
  [n]
  (assoc (get fx/fixtures n)
         :yin.k/arbitration {:dao.stream/identity arb
                             :dao.stream/descriptor
                             {:dao.stream/type :dao.stream/journal}}))


(defn- offer!
  [a]
  (let [bs (cbor/encode (body "successor"))]
    (grant/offer! a (mem/create-content-mem) (fx/segment-address bs) bs
                  "carrier")))


(defn- outcome
  [r]
  (:dao.stream/outcome r))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- record-facts
  "The facts of one record, in entity order."
  [r]
  (let [ds (get-in r [:dao.space/transaction :datoms])]
    (for [e (distinct (map first ds))]
      (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds))))


(defn- kind
  [f]
  (or (:yin.k/custody f) (:dao.lease/status f)))


(defn- facts
  [frames k]
  (filter #(= k (kind %)) (mapcat record-facts (records frames))))


(defn- attributed
  [frames]
  (mapv (fn [r] [arb r]) (records frames)))


(defn- epoch-of
  [a]
  (get-in (authority/projection a) [:occurrences occ :yin.k/epoch]))


(defn- a-grant
  ([] (a-grant "lease-1" "holder-a" "p-1"))
  ([l h pid]
   (lease/grant l (custody/subject occ) h duration {:dao.lease/proposal pid})))


(defn- offered
  "Fresh frames with the successor fixture offered, and its authority."
  ([] (offered nil))
  ([opts]
   (let [frames (fresh-frames)
         a (auth frames nil opts)]
     (offer! a)
     [frames a])))


(defn- granted
  "Offered and granted lease-1 to holder-a through the writer."
  ([] (granted nil))
  ([opts]
   (let [[frames a] (offered opts)]
     (stream/append! (grant/writer a) (a-grant))
     [frames a])))


;; =============================================================================
;; The lapse transaction
;; =============================================================================

(deftest the-writer-commits-a-lapse-with-its-epoch
  (let [[frames a] (granted)
        w (grant/writer a)]
    (is (= {:dao.stream/outcome :dao.stream/ok}
           (stream/append! w (lease/lapsed "lease-1" :silence))))
    (let [r (last (records frames))]
      (is (= [(lease/lapsed "lease-1" :silence)
              (custody/reclaimed occ "lease-1" 1)]
             (vec (record-facts r)))
          "one transaction: the lapse, then the epoch change"))
    (is (= 1 (epoch-of a)))
    (is (nil? (get-in (authority/projection a)
                      [:occurrences occ :dao.lease/lease])))
    (let [before @frames]
      (is (= {:dao.stream/outcome :dao.stream/ok}
             (stream/append! w (lease/lapsed "lease-1" :silence)))
          "the recorded lapse again is a replay")
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (lease/lapsed "lease-1" :policy)))
          "with another cause it is refused")
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (lease/lapsed "lease-9" :policy)))
          "a lease never granted")
      (is (= before @frames)))))


(deftest grants-bind-the-current-epoch
  (let [[frames a] (granted)
        w (grant/writer a)]
    (stream/append! w (lease/lapsed "lease-1" :policy))
    (is (= :dao.stream/ok
           (outcome (stream/append! w (a-grant "lease-2" "holder-b" "p-2")))))
    (stream/append! w (lease/lapsed "lease-2" :release))
    (is (= :dao.stream/ok
           (outcome (stream/append! w (a-grant "lease-3" "holder-a" "p-3")))))
    (is (= [0 1 2]
           (mapv #(:yin.k/epoch (custody/binding-evidence
                                  arb (attributed frames) %))
                 ["lease-1" "lease-2" "lease-3"]))
        "the first grant binds 0, the grant after k reclaims binds k")))


(defn- policy-judge
  "A judge over authority `a` whose policy ends every lease, with the
   config `f` may wrap, and one tick."
  ([a] (policy-judge a identity))
  ([a f]
   (let [ticks (:dao.stream/handle
                 (memory-log/create! {:dao.stream/type
                                      :dao.stream/memory-log}))]
     (stream/append! ticks (lease/tick {:s 1}))
     (lease/wire-tick
       (lease/initial-judge
         (f (merge (grant/judge-config a duration)
                   {:resolver (fn [source _] source)
                    :self arb
                    :policy (constantly true)})))
       ticks (:dao.stream/cursor (stream/cursor ticks :dao.stream/oldest))
       :ticks))))


(defn- seeded
  "The judge with lease-1 seeded in its ledger, as the grant left it."
  [j]
  (lease/author-grant j (a-grant)))


(deftest no-revocation-leaves-the-authority-before-the-lapse-commits
  (let [[frames a] (offered)
        seen (atom [])
        look (fn [where]
               (swap! seen conj
                      {:where where
                       :lease (get-in (authority/projection a)
                                      [:occurrences occ :dao.lease/lease])
                       :epoch (epoch-of a)
                       :lapses (count (facts frames :dao.lease/lapsed))}))
        wrap (fn [config]
               (let [w (:writer config)
                     ready? (:reclaim config)]
                 (assoc config
                        :reclaim (fn [s] (look :reclaim) (ready? s))
                        :writer (reify stream/IDaoStreamWriter
                                  (append!
                                    [_ fact]
                                    (when (= :dao.lease/lapsed
                                             (:dao.lease/status fact))
                                      (look :writer))
                                    (stream/append! w fact))))))
        j (grant/step! a (seeded (policy-judge a wrap)))]
    (is (= [{:where :reclaim :lease "lease-1" :epoch 0 :lapses 0}
            {:where :writer :lease "lease-1" :epoch 0 :lapses 0}]
           @seen)
        "the readiness check and the writer both see the lease live")
    (is (empty? (:ledger j)) "the committed lapse leaves the judge")
    (is (= 1 (epoch-of a)))
    (is (= 1 (count (facts frames :dao.lease/lapsed))))))


(deftest a-non-ok-writer-leaves-the-lease-pending
  (let [[frames a0] (granted)
        bound (:next-e (authority/projection a0))
        _ (authority/close! a0)
        ;; The lapse transaction needs two entity ids; the bound allows
        ;; one, so the writer answers non-ok and nothing is written.
        a (auth frames nil {::authority/max-exact bound})
        before (authority/projection a)
        frames-before @frames
        j (grant/step! a (seeded (policy-judge a)))]
    (is (= :pending (get-in j [:ledger "lease-1" :reclaim :state]))
        "the lease stays pending in the judge")
    (is (= :policy (get-in j [:ledger "lease-1" :reclaim :cause])))
    (is (= before (authority/projection a)) "the projection is unchanged")
    (is (= frames-before @frames))
    (let [j2 (grant/step! a j)]
      (is (= :pending (get-in j2 [:ledger "lease-1" :reclaim :state]))
          "and stays pending on the next pass"))))


(deftest a-poisoned-authority-is-not-ready
  (let [[frames a0] (granted)
        _ (authority/close! a0)
        a (auth frames :before-frame)
        ready? (:reclaim (grant/judge-config a duration))]
    (is (true? (ready? (custody/subject occ))))
    (is (= :dao.stream/transport-error
           (outcome (stream/append! (grant/writer a)
                                    (lease/lapsed "lease-1" :policy)))))
    (is (false? (ready? (custody/subject occ)))
        "after an uncertain append it reports not ready")))


;; =============================================================================
;; Exhaustion
;; =============================================================================

(deftest an-epoch-at-the-bound-exhausts-on-the-next-reclaim
  (let [opts {::authority/max-epoch 1}
        [frames a] (granted opts)
        w (grant/writer a)]
    (stream/append! w (lease/lapsed "lease-1" :policy))
    (is (= 1 (epoch-of a)))
    (is (= :dao.stream/ok
           (outcome (stream/append! w (a-grant "lease-2" "holder-b" "p-2"))))
        "a grant at the bound is valid")
    (is (= 1 (:yin.k/epoch (custody/binding-evidence
                             arb (attributed frames) "lease-2"))))
    (is (= :dao.stream/ok
           (outcome (stream/append! w (lease/lapsed "lease-2" :policy))))
        "the next reclaim records the lapse")
    (is (= [(lease/lapsed "lease-2" :policy)
            (custody/reclaimed occ "lease-2" 1)]
           (vec (record-facts (last (records frames)))))
        "and leaves the epoch unchanged")
    (is (true? (get-in (authority/projection a)
                       [:occurrences occ :yin.k/exhausted])))
    (let [before @frames]
      (is (= :dao.stream/invalid-value
             (outcome (stream/append! w (a-grant "lease-3" "holder-c" "p-3"))))
          "no further grant for the occurrence")
      (is (= before @frames)))
    (let [a2 (reopen frames opts)]
      (is (true? (get-in (authority/projection a2)
                         [:occurrences occ :yin.k/exhausted]))
          "exhaustion is derived from the ledger at reopen")
      (is (= 1 (epoch-of a2))))))


(deftest the-hook-grants-nothing-on-an-exhausted-occurrence
  (let [opts {::authority/max-epoch 0}
        [frames a] (granted opts)
        _ (stream/append! (grant/writer a) (lease/lapsed "lease-1" :policy))
        props (:dao.stream/handle
                (memory-log/create! {:dao.stream/type
                                     :dao.stream/memory-log}))
        _ (stream/append! props (lease/proposal "p-b" (custody/subject occ)))
        j (lease/wire-facts (policy-judge a) props
                            (:dao.stream/cursor
                              (stream/cursor props :dao.stream/oldest))
                            "holder-b")]
    (grant/step! a j)
    (is (= 1 (count (facts frames :dao.lease/accepted))))
    (is (= [{:dao.lease/status :dao.lease/rejected
             :dao.lease/proposal "p-b"
             :yin.k/proposer "holder-b"}]
           (facts frames :dao.lease/rejected))
        "the proposal is refused")))


;; =============================================================================
;; Refusals and grant replay
;; =============================================================================

(defn- proposals
  [& pids]
  (let [h (:dao.stream/handle
            (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))]
    (doseq [pid pids]
      (stream/append! h (lease/proposal pid (custody/subject occ))))
    h))


(defn- wire
  [j & sources]
  (reduce (fn [j [who h]]
            (lease/wire-facts j h (:dao.stream/cursor
                                    (stream/cursor h :dao.stream/oldest))
                              who))
          j
          sources))


(defn- judge
  [a]
  (policy-judge a #(dissoc % :policy)))


(deftest losing-candidates-are-refused-in-the-ledger
  (let [[frames a] (offered)
        j (grant/step! a (wire (judge a)
                               ["holder-a" (proposals "p-a")]
                               ["holder-b" (proposals "p-b")]))]
    (is (= 1 (count (facts frames :dao.lease/accepted))))
    (is (= [{:dao.lease/status :dao.lease/rejected
             :dao.lease/proposal "p-b"
             :yin.k/proposer "holder-b"}]
           (facts frames :dao.lease/rejected))
        "the refusal carries its proposer")
    (is (= {["holder-a" "p-a"] :dao.lease/accepted
            ["holder-b" "p-b"] :dao.lease/rejected}
           (:answered j)
           (:answered (authority/projection a)))
        "the judge and the ledger agree on every answer")))


(deftest the-writer-records-refusals-once
  (let [[frames a] (offered)
        w (grant/writer a)
        r {:dao.lease/status :dao.lease/rejected
           :dao.lease/proposal "p-b"
           :yin.k/proposer "holder-b"}]
    (is (= :dao.stream/ok (outcome (stream/append! w r))))
    (let [before @frames]
      (is (= :dao.stream/ok (outcome (stream/append! w r))) "a replay")
      (is (= :dao.stream/invalid-value
             (outcome (stream/append! w (a-grant "lease-1" "holder-b" "p-b"))))
          "a refused proposal gets no grant")
      (is (= :dao.stream/invalid-value
             (outcome (stream/append! w (lease/refusal "p-c"))))
          "a refusal without its proposer")
      (is (= before @frames)))))


(deftest grant-replay-compares-the-recorded-terms
  (let [[frames a] (granted)
        w (grant/writer a)
        before @frames]
    (is (= :dao.stream/ok (outcome (stream/append! w (a-grant))))
        "the same grant is a replay")
    (is (= :dao.stream/invalid-value
           (outcome (stream/append! w (assoc (a-grant) :dao.lease/duration
                                             {:s 60}))))
        "another duration is refused")
    (is (= :dao.stream/invalid-value
           (outcome (stream/append! w (assoc (a-grant) :dao.lease/proposal
                                             "p-9")))))
    (is (= :dao.stream/invalid-value
           (outcome (stream/append! w (assoc (a-grant) :dao.lease/max
                                             {:s 600})))))
    (is (= before @frames))))


;; =============================================================================
;; Reopen: reclaim live tenures, rebuild the judge
;; =============================================================================

(deftest reopen-reclaims-every-live-tenure-once
  (let [[frames a] (granted)
        _ (authority/close! a)
        r (grant/reopen! (backend frames) nil)
        a2 (::authority/authority r)]
    (is (= :open (:yin.k/status r)))
    (is (= ["lease-1"] (:yin.k/reclaimed r)))
    (is (= [(lease/lapsed "lease-1" :policy)
            (custody/reclaimed occ "lease-1" 1)]
           (vec (record-facts (last (records frames)))))
        "cause :policy, epoch + 1, one transaction")
    (is (= 1 (epoch-of a2)))
    (is (nil? (get-in (authority/projection a2)
                      [:occurrences occ :dao.lease/lease]))
        "nothing is regranted")
    (authority/close! a2)
    (let [before @frames
          r2 (grant/reopen! (backend frames) nil)]
      (is (= [] (:yin.k/reclaimed r2)))
      (is (= before @frames) "a reopen with nothing live writes nothing"))))


(deftest the-epoch-survives-reopen-and-is-never-reused
  (let [[frames a] (granted)
        _ (authority/close! a)
        a2 (reopen frames)
        _ (stream/append! (grant/writer a2)
                          (a-grant "lease-2" "holder-b" "p-2"))
        _ (authority/close! a2)
        a3 (reopen frames)]
    (is (= 2 (epoch-of a3)) "each reopen reclaimed the live tenure")
    (stream/append! (grant/writer a3) (a-grant "lease-3" "holder-c" "p-3"))
    (is (= [0 1 2]
           (mapv #(:yin.k/epoch (custody/binding-evidence
                                  arb (attributed frames) %))
                 ["lease-1" "lease-2" "lease-3"])))))


(deftest the-judge-is-rebuilt-from-the-ledger
  (let [[frames a] (offered)
        _ (grant/step! a (wire (judge a)
                               ["holder-a" (proposals "p-a")]
                               ["holder-b" (proposals "p-b")]))
        [g] (facts frames :dao.lease/accepted)
        l (:dao.lease/lease g)
        _ (authority/close! a)
        a2 (reopen frames)
        j (grant/rebuild-judge a2 (judge a2))]
    (is (= {l #{:dao.lease/accepted :dao.lease/lapsed}} (:seen j))
        ":seen from every recorded grant and lapse")
    (is (= {["holder-a" "p-a"] :dao.lease/accepted
            ["holder-b" "p-b"] :dao.lease/rejected}
           (:answered j))
        ":answered from every recorded grant and refusal")
    (is (= {} (:ledger j)))
    (is (= [] (:queue j)))))


(deftest old-media-establish-nothing-after-reopen
  (let [[frames a] (offered)
        _ (grant/step! a (wire (judge a) ["holder-a" (proposals "p-a")]))
        [g] (facts frames :dao.lease/accepted)
        _ (authority/close! a)
        a2 (reopen frames)
        own (:dao.stream/handle
              (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))
        _ (stream/append! own g)
        before @frames
        j (grant/step! a2 (wire (grant/rebuild-judge a2 (judge a2))
                                [arb own]
                                ["holder-a" (proposals "p-a")]))]
    (is (= {} (:ledger j)) "the old :accepted reads inadmissible")
    (is (pos? (:dropped j)))
    (is (= before @frames)
        "the old proposal, already answered, gets no second grant")
    (is (= 1 (count (facts frames :dao.lease/accepted))))
    (grant/step! a2 (wire (grant/rebuild-judge a2 (judge a2))
                          ["holder-a" (proposals "p-a")]
                          ["holder-c" (proposals "p-c")]))
    (is (= ["holder-a" "holder-c"]
           (mapv :dao.lease/holder (facts frames :dao.lease/accepted)))
        "an answered proposal does not take the occurrence from a fresh one")))


(deftest an-old-unsolicited-grant-reads-inadmissible
  (let [[frames a] (offered)
        g (dissoc (a-grant) :dao.lease/proposal)
        _ (stream/append! (grant/writer a) g)
        _ (authority/close! a)
        a2 (reopen frames)
        own (:dao.stream/handle
              (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))
        _ (stream/append! own g)
        j (grant/step! a2 (wire (grant/rebuild-judge a2 (judge a2))
                                [arb own]))]
    (is (= {} (:ledger j))
        "with no proposal to answer, :seen alone keeps it out")
    (is (= 1 (:dropped j)))))


(deftest a-proposal-whose-grant-failed-stays-answerable
  (let [[frames a0] (offered)
        _ (authority/close! a0)
        a (auth frames :before-frame)
        _ (grant/step! a (wire (judge a) ["holder-b" (proposals "p-b")]))
        _ (is (empty? (facts frames :dao.lease/accepted)) "the append failed")
        a2 (reopen frames)
        _ (grant/step! a2 (wire (grant/rebuild-judge a2 (judge a2))
                                ["holder-b" (proposals "p-b")]))
        [g] (facts frames :dao.lease/accepted)]
    (is (= ["holder-b" "p-b"]
           [(:dao.lease/holder g) (:dao.lease/proposal g)])
        "the retry is granted")
    (is (= 0 (:yin.k/epoch (custody/binding-evidence
                             arb (attributed frames) (:dao.lease/lease g))))
        "no tenure was live, so nothing was reclaimed")))


;; =============================================================================
;; Crash cuts
;; =============================================================================

(deftest a-cut-lapse-commits-zero-or-one-times
  (doseq [[cut persisted] [[:before-frame 0]
                           [:after-frame-before-visible 1]
                           [:torn-frame 0]]]
    (testing (name cut)
      (let [[frames a0] (granted)
            _ (authority/close! a0)
            a (auth frames cut)]
        (is (= :dao.stream/transport-error
               (outcome (stream/append! (grant/writer a)
                                        (lease/lapsed "lease-1" :silence)))))
        (is (nil? (authority/projection a)) "the authority is poisoned")
        (let [a2 (auth frames)]
          (is (= persisted (count (facts frames :dao.lease/lapsed))))
          (is (= persisted (count (facts frames :yin.k/reclaimed)))
              "never a lapse without its epoch change")
          (is (= persisted (epoch-of a2))))))))


(deftest reopen-after-a-cut-between-grant-and-lapse-reclaims-once
  (doseq [[cut persisted] [[:before-frame 0]
                           [:after-frame-before-visible 1]
                           [:torn-frame 0]]]
    (testing (name cut)
      (let [[frames a0] (granted)
            _ (authority/close! a0)
            a (auth frames cut)
            _ (stream/append! (grant/writer a)
                              (lease/lapsed "lease-1" :silence))
            r (grant/reopen! (backend frames) nil)]
        (is (= :open (:yin.k/status r)))
        (is (= (if (zero? persisted) ["lease-1"] []) (:yin.k/reclaimed r)))
        (is (= 1 (count (facts frames :dao.lease/lapsed))) "exactly once")
        (is (= 1 (epoch-of (::authority/authority r))))))))


(deftest a-cut-during-reopen-refuses-the-open
  (let [[frames a] (granted)
        _ (authority/close! a)
        r (grant/reopen! (backend frames :before-frame) nil)]
    (is (= {:yin.k/status :refused :yin.k/defect :unreclaimed
            :dao.lease/lease "lease-1"}
           r)
        "no authority is served with a tenure it could not reclaim")
    (is (= ["lease-1"] (:yin.k/reclaimed (grant/reopen! (backend frames) nil)))
        "the next reopen reclaims it")
    (is (= 1 (count (facts frames :dao.lease/lapsed))))))


;; =============================================================================
;; The whole judge-step holds the authority lock (JVM threads)
;; =============================================================================

#?(:clj
   (deftest step-holds-the-lock-across-the-whole-pass
     (let [[frames a] (offered)
           entered (promise)
           release (promise)
           blocking (fn [config]
                      (let [w (:writer config)]
                        (assoc config
                               :writer (reify stream/IDaoStreamWriter
                                         (append!
                                           [_ fact]
                                           (deliver entered true)
                                           @release
                                           (stream/append! w fact))))))
           j (wire (policy-judge a #(dissoc (blocking %) :policy))
                   ["holder-a" (proposals "p-a")])
           step (future (grant/step! a j))
           _ (is (true? (deref entered 5000 false))
                 "the step reached its writer")
           enroll (future (authority/enroll! a))]
       (is (= ::blocked (deref enroll 200 ::blocked))
           "a concurrent transition waits for the whole pass")
       (deliver release true)
       @step
       (let [e (deref enroll 5000 ::blocked)
             [g] (facts frames :dao.lease/accepted)]
         (is (= :committed (:yin.k/status e)))
         (is (some? g))
         (is (= 2 (:dao.space/t e)) "the enrollment came after the grant")))))


(deftest the-ledger-epoch-bound-is-the-published-one
  (is (= custody/max-exact ledger/max-exact
         (:max-epoch (authority/projection (second (offered)))))))

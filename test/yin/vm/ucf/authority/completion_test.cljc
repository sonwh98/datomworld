(ns yin.vm.ucf.authority.completion-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.seam :as seam]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]))


(def ^:private arb "arb-c8")

(def ^:private duration {:s 30})


;; The root first park, its successors and a stranger occurrence.
(def ^:private r "0a000000-0000-4000-8000-000000000001")

(def ^:private s1 "0a000000-0000-4000-8000-000000000002")

(def ^:private s2 "0a000000-0000-4000-8000-000000000003")

(def ^:private x "0a000000-0000-4000-8000-000000000004")


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
  "Reopen through grant/reopen!, answering the open answer."
  ([frames] (reopen frames nil))
  ([frames opts] (grant/reopen! (backend frames) opts)))


(defn- origin
  [o l]
  {:yin.k/occurrence o :dao.lease/lease l :yin.k/emitter "holder-a"})


(defn- park
  "A blocked root of occurrence `o` with counter `n` and origin `org`,
   nil for a first park."
  ([o n] (park o n nil))
  ([o n org]
   (cond-> (assoc (get fx/fixtures "first-park")
                  :yin.k/occurrence o
                  :yin.k/next-op-seq n
                  :yin.k/arbitration {:dao.stream/identity arb
                                      :dao.stream/descriptor
                                      {:dao.stream/type :dao.stream/journal}})
     (some? org) (assoc :yin.k/origin org))))


(defn- enc
  "The body's canonical bytes and their address."
  [b]
  (let [bs (cbor/encode b)]
    {:address (fx/segment-address bs) :bytes bs}))


(defn- offer!
  ([a b] (offer! a (mem/create-content-mem) b))
  ([a store b]
   (let [{:keys [address bytes]} (enc b)]
     (grant/offer! a store address bytes "carrier"))))


(defn- grant!
  [a o l h]
  (:dao.stream/outcome
    (stream/append! (grant/writer a)
                    (lease/grant l (custody/subject o) h duration
                                 {:dao.lease/proposal (str "p-" l)}))))


(defn- report!
  "Report body `b` as the successor of o under l, from `author`."
  ([a o l b] (report! a "holder-a" o l b))
  ([a author o l b]
   (let [{:keys [address bytes]} (enc b)]
     (completion/report! a author (completion/resumed o l address) bytes))))


(defn- lapse!
  [a l cause]
  (:dao.stream/outcome
    (stream/append! (grant/writer a) (lease/lapsed l cause))))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- record-facts
  [rec]
  (let [ds (get-in rec [:dao.space/transaction :datoms])]
    (vec (for [e (distinct (map first ds))]
           (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds)))))


(defn- kind
  [f]
  (or (:yin.k/custody f) (:dao.lease/status f)))


(defn- facts
  [frames k]
  (filter #(= k (kind %)) (mapcat record-facts (records frames))))


(defn- occ
  [a o]
  (get-in (authority/projection a) [:occurrences o]))


(defn- granted
  "R offered and granted lease-1 to holder-a."
  ([] (granted nil))
  ([opts]
   (let [frames (fresh-frames)
         a (auth frames nil opts)]
     (offer! a (park r 0))
     (grant! a r "lease-1" "holder-a")
     [frames a])))


(def ^:private succ-1
  "R's successor under lease-1: occurrence s1, counter 3."
  (park s1 3 (origin r "lease-1")))


(defn- halted
  "R's halted result under lease `l`."
  ([] (halted "lease-1"))
  ([l] (assoc (get fx/fixtures "halted-root") :yin.k/origin (origin r l))))


(defn- quarantine!
  "Quarantine occurrence `o` as an intent conflict would: a recorded op
   id of o, then its quarantine fact."
  [a o]
  (let [i (:yin.k/target (authority/enroll! a))
        op {:yin.k/occurrence o :yin.k/seq 0}]
    (seam/commit-effect! a i op :v)
    (authority/transition!
      a
      (fn [_]
        {::authority/facts [{:yin.k/custody :yin.k/quarantined
                             :yin.k/occurrence o
                             :yin.k/op-id op}]
         ::authority/reply {:yin.k/status :committed}}))))


(defn- completed
  "R completed into s1 under lease-1."
  ([] (completed nil))
  ([opts]
   (let [[frames a] (granted opts)]
     (report! a r "lease-1" succ-1)
     (lapse! a "lease-1" :release)
     [frames a])))


;; =============================================================================
;; Completion
;; =============================================================================

(deftest a-release-with-a-verified-successor-completes
  (let [[frames a] (granted)
        {:keys [address]} (enc succ-1)]
    (is (= :committed (:yin.k/status (report! a r "lease-1" succ-1))))
    (is (= [(assoc (completion/resumed r "lease-1" address)
                   :yin.k/successor s1)]
           (record-facts (last (records frames))))
        "the authority records the report with the successor it verified")
    (is (= :dao.stream/ok (lapse! a "lease-1" :release)))
    (is (= [(lease/lapsed "lease-1" :release)
            (completion/completed r "lease-1")
            (completion/succeeded r s1)
            (custody/reclaimed r "lease-1" 1)]
           (record-facts (last (records frames))))
        "one transaction: the lapse, the closure, the edge, the epoch")
    (is (= {:dao.lease/lease "lease-1" :yin.k/successor s1}
           (:yin.k/closed (occ a r))))
    (is (nil? (:dao.lease/lease (occ a r))))
    (let [before @frames]
      (is (= :dao.stream/invalid-value (grant! a r "lease-2" "holder-b"))
          "a closed occurrence never grants again")
      (is (= :dao.stream/ok (lapse! a "lease-1" :release))
          "the recorded release is a replay")
      (is (= before @frames)))
    (is (= :committed (:yin.k/status (offer! a succ-1)))
        "the recorded successor is eligible")
    (is (= :replayed (:yin.k/status (offer! a succ-1))) "once")
    (is (= :dao.stream/ok (grant! a s1 "lease-2" "holder-b")))
    (is (= 0 (:yin.k/epoch (occ a s1))) "a successor starts at epoch 0")))


(deftest a-release-without-a-report-reoffers
  (let [[frames a] (granted)]
    (is (= :dao.stream/ok (lapse! a "lease-1" :release)))
    (is (= [(lease/lapsed "lease-1" :release)
            (custody/reclaimed r "lease-1" 1)]
           (record-facts (last (records frames))))
        "a plain reclaim")
    (is (nil? (:yin.k/closed (occ a r))))
    (is (= :dao.stream/ok (grant! a r "lease-2" "holder-b"))
        "the occurrence returns to offered")
    (is (= 1 (:yin.k/epoch (occ a r))))))


(deftest only-release-or-policy-completes
  (doseq [cause [:silence :cap]]
    (testing (name cause)
      (let [[frames a] (granted)]
        (report! a r "lease-1" succ-1)
        (is (= :dao.stream/ok (lapse! a "lease-1" cause)))
        (is (= [(lease/lapsed "lease-1" cause)
                (custody/reclaimed r "lease-1" 1)]
               (record-facts (last (records frames)))))
        (is (nil? (:yin.k/closed (occ a r))))
        (is (= :refused (:yin.k/status (offer! a succ-1)))
            "the reported successor never becomes eligible")))))


(deftest policy-reclaim-completes-the-accepted-report-atomically
  (doseq [terminal? [false true]]
    (let [[frames instance] (granted)
          body (if terminal? (halted) succ-1)
          address (:address (enc body))]
      (is (= :committed (:yin.k/status (report! instance r "lease-1" body))))
      (is (= :dao.stream/ok (lapse! instance "lease-1" :policy)))
      (is (= [(lease/lapsed "lease-1" :policy)
              (completion/completed r "lease-1")
              (if terminal? (completion/terminated r address) (completion/succeeded r s1))
              (custody/reclaimed r "lease-1" 1)]
             (record-facts (last (records frames)))))
      (is (= :policy (get-in (authority/projection instance) [:leases "lease-1" :dao.lease/cause])))
      (is (= address (get-in (authority/projection instance) [:leases "lease-1" :yin.k/result])))
      (is (= "lease-1" (get-in (occ instance r) [:yin.k/closed :dao.lease/lease])))
      (is (= 1 (:yin.k/epoch (occ instance r))))
      (is (nil? (:dao.lease/lease (occ instance r))))
      (let [before @frames]
        (is (= :dao.stream/ok (lapse! instance "lease-1" :policy)))
        (authority/close! instance)
        (let [rep (reopen frames)]
          (is (= [] (:yin.k/reclaimed-leases rep)))
          (is (= before @frames))
          (authority/close! (::authority/authority rep)))))))


;; =============================================================================
;; The successor the report names
;; =============================================================================

(defn- refusal
  [rep]
  (when (= :refused (:yin.k/status rep)) (:yin.k/reason rep)))


(deftest the-report-verifies-its-successor
  (let [[frames a] (granted)
        _ (offer! a (park x 0))
        before @frames]
    (doseq [[what reason rep]
            [["origin naming another occurrence" :wrong-origin
              #(report! a r "lease-1" (park s1 3 (origin x "lease-1")))]
             ["origin naming another lease" :wrong-origin
              #(report! a r "lease-1" (park s1 3 (origin r "lease-9")))]
             ["another arbitration identity" :foreign-arbitration
              #(report! a r "lease-1"
                        (assoc-in succ-1 [:yin.k/arbitration
                                          :dao.stream/identity]
                                  "arb-other"))]
             ["an occurrence the ledger has seen" :seen-occurrence
              #(report! a r "lease-1" (park x 3 (origin r "lease-1")))]
             ["another author" :not-holder
              #(report! a "holder-b" r "lease-1" succ-1)]
             ["another occurrence's lease" :wrong-occurrence
              #(report! a x "lease-1" (park s1 3 (origin x "lease-1")))]
             ["a lease never granted" :unknown-lease
              #(report! a r "lease-9" (park s1 3 (origin r "lease-9")))]]]
      (testing what
        (is (= reason (refusal (rep))))))
    (testing "bytes that do not match the address"
      (let [{:keys [address]} (enc succ-1)
            rep (completion/report! a "holder-a"
                                    (completion/resumed r "lease-1" address)
                                    (cbor/encode (park s2 3)))]
        (is (= :uninspectable (refusal rep)))
        (is (= :yin.k/hash-mismatch
               (get-in rep [:yin.k/inspection :yin.k/status])))))
    (testing "a successor naming itself as origin"
      (is (= :uninspectable
             (refusal
               (report! a r "lease-1" (park r 3 (origin r "lease-1")))))))
    (testing "a malformed report"
      (is (= :malformed-report
             (refusal (completion/report!
                        a "holder-a"
                        (dissoc (completion/resumed r "lease-1" :segment/x)
                                :yin.k/result)
                        (:bytes (enc succ-1)))))))
    (is (= before @frames) "no refused report writes anything")
    (is (= :committed (:yin.k/status (report! a r "lease-1" succ-1))))
    (let [after @frames]
      (is (= :replayed (:yin.k/status (report! a r "lease-1" succ-1))))
      (is (= :report-conflict
             (refusal (report! a r "lease-1" (park s2 3 (origin r "lease-1")))))
          "a second successor for one lease")
      (is (= after @frames)))
    (lapse! a "lease-1" :policy)
    (is (= :ended-lease
           (refusal
             (report! a r "lease-1" (park s2 3 (origin r "lease-1"))))))
    (let [ended @frames]
      (is (= {:yin.k/status :replayed} (report! a r "lease-1" succ-1))
          "the same report retried after its lease ended replays")
      (is (= ended @frames) "and writes nothing"))))


(deftest the-successor-continues-the-counter
  (let [[_ a] (completed)
        _ (offer! a succ-1)
        _ (grant! a s1 "lease-2" "holder-a")]
    (is (= :counter-regression
           (refusal (report! a s1 "lease-2" (park s2 2 (origin s1 "lease-2")))))
        "next-op-seq below the predecessor's")
    (is (= :committed
           (:yin.k/status
             (report! a s1 "lease-2" (park s2 3 (origin s1 "lease-2")))))
        "an equal counter continues it")))


;; =============================================================================
;; One acyclic successor chain
;; =============================================================================

(deftest a-cycle-is-refused
  (let [[frames a] (completed)
        _ (offer! a succ-1)
        _ (grant! a s1 "lease-2" "holder-a")
        before @frames]
    (is (= :seen-occurrence
           (refusal (report! a s1 "lease-2" (park r 3 (origin s1 "lease-2")))))
        "a successor naming an ancestor")
    (is (= :uninspectable
           (refusal (report! a s1 "lease-2" (park s1 3 (origin s1 "lease-2")))))
        "a successor naming itself")
    (is (= before @frames))))


(deftest one-successor-per-predecessor-and-one-predecessor-per-successor
  (let [frames (fresh-frames)
        a (auth frames)
        r2 x]
    (offer! a (park r 0))
    (offer! a (park r2 0))
    (grant! a r "lease-1" "holder-a")
    (grant! a r2 "lease-2" "holder-a")
    (is (= :committed (:yin.k/status (report! a r "lease-1" succ-1))))
    (is (= :committed
           (:yin.k/status
             (report! a r2 "lease-2" (park s1 3 (origin r2 "lease-2")))))
        "two predecessors may both report one successor")
    (lapse! a "lease-1" :release)
    (lapse! a "lease-2" :release)
    (is (= [(completion/succeeded r s1)]
           (facts frames :yin.k/succeeded))
        "only the first completion records the edge")
    (is (some? (:yin.k/closed (occ a r))))
    (is (nil? (:yin.k/closed (occ a r2)))
        "the second release is a plain reclaim")
    (is (= :refused
           (:yin.k/status (offer! a (park s1 3 (origin r2 "lease-2")))))
        "the other predecessor's successor is an orphan")))


;; =============================================================================
;; Successor offers wait for completion; orphans are never grantable
;; =============================================================================

(deftest a-successor-offer-waits-for-its-predecessors-completion
  (let [[frames a] (granted)]
    (is (= :awaiting-completion (refusal (offer! a succ-1)))
        "before the report")
    (report! a r "lease-1" succ-1)
    (is (= :awaiting-completion (refusal (offer! a succ-1)))
        "after the report, before the release")
    (is (= :dao.stream/invalid-value (grant! a s1 "lease-9" "holder-b"))
        "never offered, never grantable")
    (is (= [r] (map :yin.k/occurrence (facts frames :yin.k/offered))))))


(deftest an-orphan-is-never-grantable
  (testing "the predecessor was reclaimed after the report"
    (let [[frames a] (granted)]
      (report! a r "lease-1" succ-1)
      (lapse! a "lease-1" :silence)
      (is (= :orphan (refusal (offer! a succ-1))))
      (testing "and regranted, completing into another successor"
        (grant! a r "lease-2" "holder-b")
        (report! a "holder-b" r "lease-2" (park s2 3 (origin r "lease-2")))
        (lapse! a "lease-2" :release)
        (is (= s1 (:yin.k/successor
                    (first (facts frames :yin.k/resumed)))))
        (is (= [(completion/succeeded r s2)] (facts frames :yin.k/succeeded)))
        (is (= :orphan (refusal (offer! a succ-1))))
        (is (= :committed
               (:yin.k/status (offer! a (park s2 3 (origin r "lease-2"))))))
        (is (= :dao.stream/invalid-value (grant! a s1 "lease-9" "holder-c"))
            "the orphan never grants"))))
  (testing "a stale branch that reused the successor's occurrence"
    (let [[_ a] (granted)
          succ-2 (park s1 3 (origin r "lease-2"))]
      (report! a r "lease-1" succ-1)
      (lapse! a "lease-1" :silence)
      (grant! a r "lease-2" "holder-a")
      (report! a r "lease-2" succ-2)
      (lapse! a "lease-2" :release)
      (is (= {:dao.lease/lease "lease-2" :yin.k/successor s1}
             (:yin.k/closed (occ a r))))
      (is (= :orphan (refusal (offer! a succ-1)))
          "the closing lease decides, not the occurrence id")
      (is (= :committed (:yin.k/status (offer! a succ-2))))))
  (testing "a variant the report did not name"
    (let [[_ a] (completed)
          other (assoc succ-1 :yin.k/id-counter 7)]
      (is (= :orphan (refusal (offer! a other))))
      (is (= :committed (:yin.k/status (offer! a succ-1))))
      (is (= :committed (:yin.k/status (offer! a other)))
          "once admitted, an equal-baseline variant joins it"))))


(deftest a-successor-of-a-predecessor-this-ledger-never-saw-is-an-orphan
  (let [[frames a] (granted)
        before @frames]
    (is (= :orphan (refusal (offer! a (park s2 3 (origin x "lease-x"))))))
    (is (= before @frames))
    (is (= :committed (:yin.k/status (offer! a (park s2 0))))
        "a body with no origin is a first offer")))


;; =============================================================================
;; Exhaustion
;; =============================================================================

(deftest completion-is-blocked-at-exhaustion
  (let [opts {::authority/max-epoch 1}
        [frames a] (granted opts)]
    (lapse! a "lease-1" :policy)
    (grant! a r "lease-2" "holder-a")
    (is (= 1 (:yin.k/epoch (occ a r))) "the grant binds the bound")
    (is (= :committed
           (:yin.k/status
             (report! a r "lease-2" (park s1 3 (origin r "lease-2"))))))
    (is (= :dao.stream/ok (lapse! a "lease-2" :release)))
    (is (= [(lease/lapsed "lease-2" :release)
            (custody/reclaimed r "lease-2" 1)]
           (record-facts (last (records frames))))
        "the lapse is recorded, the epoch stays, nothing completes")
    (is (true? (:yin.k/exhausted (occ a r))))
    (is (nil? (:yin.k/closed (occ a r))))
    (is (= :orphan (refusal (offer! a (park s1 3 (origin r "lease-2")))))
        "no successor is eligible")
    (is (= :dao.stream/invalid-value (grant! a r "lease-3" "holder-b"))
        "nothing is granted")))


;; =============================================================================
;; Crash cuts
;; =============================================================================

(def ^:private cuts
  [[:before-frame 0] [:after-frame-before-visible 1] [:torn-frame 0]])


(defn- lapses-of
  [frames l]
  (filter #(= l (:dao.lease/lease %)) (facts frames :dao.lease/lapsed)))


(defn- before-closure
  "Assert the reopened authority over `frames`: R open and regranted at
   epoch 1, s1 not eligible, lease-1 reclaimed exactly once."
  [frames]
  (let [rep (reopen frames)
        a (::authority/authority rep)]
    (is (= :open (:yin.k/status rep)))
    (is (= 1 (count (lapses-of frames "lease-1"))) "reclaimed exactly once")
    (is (nil? (:yin.k/closed (occ a r))))
    (is (= :refused (:yin.k/status (offer! a succ-1)))
        "no successor is eligible before closure")
    (is (= :dao.stream/ok (grant! a r "lease-2" "holder-b"))
        "the last checkpoint is regranted")
    (is (= 1 (:yin.k/epoch (occ a r))))
    (authority/close! a)))


(defn- after-closure
  "Assert the reopened authority over `frames`: R closed and never
   granted, s1 eligible once, lease-1 reclaimed exactly once."
  [frames]
  (let [rep (reopen frames)
        a (::authority/authority rep)]
    (is (= :open (:yin.k/status rep)))
    (is (= [] (:yin.k/reclaimed-leases rep)) "nothing was live")
    (is (= 1 (count (lapses-of frames "lease-1"))) "reclaimed exactly once")
    (is (some? (:yin.k/closed (occ a r))))
    (is (= :dao.stream/invalid-value (grant! a r "lease-2" "holder-b"))
        "a closed occurrence never grants again")
    (is (= :committed (:yin.k/status (offer! a succ-1))))
    (is (= :replayed (:yin.k/status (offer! a succ-1))))
    (authority/close! a)))


(defn- recover-reported
  [frames]
  (let [rep (reopen frames)
        instance (::authority/authority rep)]
    (is (= :open (:yin.k/status rep)))
    (is (= ["lease-1"] (:yin.k/reclaimed-leases rep)))
    (is (= :policy (get-in (authority/projection instance) [:leases "lease-1" :dao.lease/cause])))
    (is (some? (:yin.k/closed (occ instance r))))
    (authority/close! instance)
    (after-closure frames)))


(deftest a-crash-after-the-successor-append
  (let [[frames a] (granted)]
    ;; the successor is on its carrier; the authority has heard nothing
    (authority/close! a)
    (before-closure frames)))


(deftest a-crash-after-the-resumed-report
  (testing "the report committed"
    (let [[frames a] (granted)]
      (report! a r "lease-1" succ-1)
      (authority/close! a)
      (recover-reported frames)))
  (doseq [[cut persisted] cuts]
    (testing (str "a cut on the report: " (name cut))
      (let [[frames a0] (granted)
            _ (authority/close! a0)
            a (auth frames cut)]
        (is (= :suspended (:yin.k/status (report! a r "lease-1" succ-1))))
        (is (nil? (authority/projection a)) "the authority is poisoned")
        (if (zero? persisted) (before-closure frames) (recover-reported frames))
        (is (= persisted (count (facts frames :yin.k/resumed))))))))


(deftest a-crash-after-the-release-append
  (testing "the release on the holder's medium, not yet recorded"
    (let [[frames a] (granted)]
      (report! a r "lease-1" succ-1)
      (authority/close! a)
      (recover-reported frames)))
  (doseq [[cut persisted] cuts]
    (testing (str "a cut on the completion: " (name cut))
      (let [[frames a0] (granted)
            _ (report! a0 r "lease-1" succ-1)
            _ (authority/close! a0)
            a (auth frames cut)]
        (is (= :dao.stream/transport-error (lapse! a "lease-1" :release)))
        (is (nil? (authority/projection a)))
        (if (zero? persisted)
          (recover-reported frames)
          (after-closure frames))
        (is (every? #(= (count (facts frames %)) 1)
                    [:yin.k/completed :yin.k/succeeded])
            "an accepted report completes exactly once, on release or policy reopen")))))


(deftest a-crash-after-closure
  (let [[frames a] (completed)]
    (authority/close! a)
    (after-closure frames)
    (testing "and again"
      (let [a2 (::authority/authority (reopen frames))]
        (is (= :replayed (:yin.k/status (offer! a2 succ-1))))
        (is (= 1 (count (facts frames :yin.k/succeeded))))))))


;; =============================================================================
;; Through the judge
;; =============================================================================

(defn- log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- judge
  [a medium]
  (let [ticks (log)]
    (stream/append! ticks (lease/tick {:s 1}))
    (-> (lease/initial-judge
          (merge (grant/judge-config a duration)
                 {:resolver (fn [source _] source) :self arb}))
        (lease/wire-tick ticks
                         (:dao.stream/cursor
                           (stream/cursor ticks :dao.stream/oldest))
                         :ticks)
        (lease/wire-facts medium
                          (:dao.stream/cursor
                            (stream/cursor medium :dao.stream/oldest))
                          "holder-a"))))


(deftest the-holders-release-completes-through-the-judge
  (let [frames (fresh-frames)
        a (auth frames)
        medium (log)
        _ (offer! a (park r 0))
        _ (stream/append! medium (lease/proposal "p-a" (custody/subject r)))
        j (grant/step! a (judge a medium))
        [g] (facts frames :dao.lease/accepted)
        l (:dao.lease/lease g)
        succ (park s1 3 (origin r l))]
    (is (= "holder-a" (:dao.lease/holder g)))
    (is (= :committed (:yin.k/status (report! a r l succ))))
    (stream/append! medium (lease/release l))
    (let [j2 (grant/step! a j)]
      (is (empty? (:ledger j2)) "the release left the judge")
      (is (= [(completion/succeeded r s1)] (facts frames :yin.k/succeeded)))
      (is (= :release (:dao.lease/cause (first (facts frames
                                                      :dao.lease/lapsed))))))
    (testing "a proposal for the closed occurrence is refused"
      (let [m2 (log)
            _ (stream/append! m2 (lease/proposal "p-b" (custody/subject r)))
            _ (grant/step! a (judge a m2))]
        (is (= 1 (count (facts frames :dao.lease/accepted))))
        (is (= [(lease/refusal "p-b")] (facts frames :dao.lease/rejected)))
        (is (= [(custody/refused "holder-a" "p-b")]
               (facts frames :yin.k/refused)))))
    (testing "the rebuilt judge has seen the release"
      (authority/close! a)
      (let [a2 (::authority/authority (reopen frames))]
        (is (= {l #{:dao.lease/accepted :dao.lease/released
                    :dao.lease/lapsed}}
               (:seen (grant/rebuild-judge a2 (judge a2 (log))))))))))


;; =============================================================================
;; A halted result completes the occurrence (ruling 1)
;; =============================================================================

(deftest a-halted-result-completes-the-occurrence
  (let [[frames a] (granted)
        {:keys [address]} (enc (halted))]
    (is (= :committed (:yin.k/status (report! a r "lease-1" (halted)))))
    (is (= [(completion/resumed r "lease-1" address)]
           (record-facts (last (records frames))))
        "the recorded report names no successor occurrence")
    (is (= :dao.stream/ok (lapse! a "lease-1" :release)))
    (is (= [(lease/lapsed "lease-1" :release)
            (completion/completed r "lease-1")
            (completion/terminated r address)
            (custody/reclaimed r "lease-1" 1)]
           (record-facts (last (records frames))))
        "the lapse, the closure, the terminal edge, the epoch")
    (is (= {:dao.lease/lease "lease-1" :yin.k/result address}
           (:yin.k/closed (occ a r))))
    (let [before @frames]
      (is (= :dao.stream/invalid-value (grant! a r "lease-2" "holder-b"))
          "the closed occurrence is never granted through the writer")
      (is (= before @frames)))
    (testing "nor through the hook"
      (let [m (log)]
        (stream/append! m (lease/proposal "p-b" (custody/subject r)))
        (grant/step! a (judge a m))
        (is (= 1 (count (facts frames :dao.lease/accepted))))
        (is (= [(custody/refused "holder-a" "p-b")]
               (facts frames :yin.k/refused)))))
    (testing "the result is never offered; no continuation follows it"
      (is (= :not-offerable (refusal (offer! a (halted)))))
      (is (= :orphan (refusal (offer! a succ-1)))))))


(deftest a-halted-body-must-name-this-occurrence-and-lease
  (let [[frames a] (granted)
        _ (offer! a (park x 0))
        before @frames]
    (is (= :wrong-origin
           (refusal (report! a r "lease-1"
                             (assoc (halted) :yin.k/origin
                                    (origin x "lease-1"))))))
    (is (= :wrong-origin (refusal (report! a r "lease-1" (halted "lease-9")))))
    (is (= before @frames))))


(deftest a-second-report-of-another-kind-conflicts
  (let [[_ a] (granted)]
    (report! a r "lease-1" succ-1)
    (is (= :report-conflict (refusal (report! a r "lease-1" (halted))))
        "halted after a continuation"))
  (let [[_ a] (granted)]
    (report! a r "lease-1" (halted))
    (is (= :replayed (:yin.k/status (report! a r "lease-1" (halted)))))
    (is (= :report-conflict (refusal (report! a r "lease-1" succ-1)))
        "a continuation after halted")))


(deftest a-halted-completion-is-blocked-at-exhaustion
  (let [opts {::authority/max-epoch 1}
        [frames a] (granted opts)]
    (lapse! a "lease-1" :policy)
    (grant! a r "lease-2" "holder-a")
    (is (= :committed
           (:yin.k/status (report! a r "lease-2" (halted "lease-2"))))
        "the report at the bound is recorded")
    (lapse! a "lease-2" :release)
    (is (= [(lease/lapsed "lease-2" :release)
            (custody/reclaimed r "lease-2" 1)]
           (record-facts (last (records frames))))
        "its release does not complete")
    (is (true? (:yin.k/exhausted (occ a r))))
    (is (nil? (:yin.k/closed (occ a r))))))


(deftest a-cut-on-the-halted-completion
  (doseq [[cut persisted] cuts]
    (testing (name cut)
      (let [[frames a0] (granted)
            _ (report! a0 r "lease-1" (halted))
            _ (authority/close! a0)
            a (auth frames cut)]
        (is (= :dao.stream/transport-error (lapse! a "lease-1" :release)))
        (let [rep (reopen frames)
              a2 (::authority/authority rep)]
          (is (= :open (:yin.k/status rep)))
          (is (= 1 (count (lapses-of frames "lease-1")))
              "reclaimed exactly once")
          (is (= (if (zero? persisted) :policy :release)
                 (get-in (authority/projection a2) [:leases "lease-1" :dao.lease/cause])))
          (is (every? #(= (count (facts frames %)) 1)
                      [:yin.k/completed :yin.k/succeeded])
              "release or policy reopen records one complete terminal transaction")
          (authority/close! a2)
          (let [rep3 (reopen frames)]
            (is (= [] (:yin.k/reclaimed-leases rep3))
                "the next reopen replays")
            (is (= :dao.stream/invalid-value
                   (grant! (::authority/authority rep3)
                           r "lease-2" "holder-b")))))))))


;; =============================================================================
;; A quarantined occurrence cannot complete (ruling 4)
;; =============================================================================

(deftest a-quarantined-occurrence-cannot-complete
  (let [[frames a] (granted)]
    (report! a r "lease-1" succ-1)
    (quarantine! a r)
    (is (= :dao.stream/ok (lapse! a "lease-1" :release)))
    (is (= [(lease/lapsed "lease-1" :release)
            (custody/reclaimed r "lease-1" 1)]
           (record-facts (last (records frames))))
        "a plain reclaim")
    (is (nil? (:yin.k/closed (occ a r))))
    (is (= :orphan (refusal (offer! a succ-1))))))


(deftest policy-reclaim-cannot-complete-quarantined-or-exhausted-reports
  (doseq [obstruction [:quarantine :exhaustion] terminal? [false true]]
    (let [[frames instance] (granted (when (= :exhaustion obstruction) {::authority/max-epoch 0}))
          body (if terminal? (halted) succ-1)]
      (is (= :committed (:yin.k/status (report! instance r "lease-1" body))))
      (when (= :quarantine obstruction) (quarantine! instance r))
      (is (= :dao.stream/ok (lapse! instance "lease-1" :policy)))
      (is (= [(lease/lapsed "lease-1" :policy)
              (custody/reclaimed r "lease-1" (if (= :exhaustion obstruction) 0 1))]
             (record-facts (last (records frames)))))
      (is (nil? (:yin.k/closed (occ instance r))))
      (is (empty? (facts frames :yin.k/succeeded)))
      (is (= (:address (enc body)) (get-in (authority/projection instance) [:leases "lease-1" :yin.k/result])))
      (is (true? (get (occ instance r) (if (= :exhaustion obstruction) :yin.k/exhausted :yin.k/quarantined))))
      (when-not terminal? (is (= :orphan (refusal (offer! instance body)))))
      (is (= :dao.stream/invalid-value (grant! instance r "lease-2" "holder-b")))
      (authority/close! instance))))


(deftest a-report-on-a-quarantined-occurrence-is-refused
  (let [[frames a] (granted)
        _ (quarantine! a r)
        before @frames]
    (is (= :quarantined (refusal (report! a r "lease-1" succ-1))))
    (is (= before @frames))))

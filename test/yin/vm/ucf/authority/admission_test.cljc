(ns yin.vm.ucf.authority.admission-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb-c7")

(def ^:private occ fx/occurrence)

(def ^:private duration {:s 30})

(def ^:private other-occ "0b6a2f1e-3c4d-4e5f-8a9b-0c1d2e3f4a5b")

(def ^:private ok ledger/ok-result)


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


(defn- a-grant
  [l h pid]
  (lease/grant l (custody/subject occ) h duration {:dao.lease/proposal pid}))


(defn- grant!
  [a l h pid]
  (stream/append! (grant/writer a) (a-grant l h pid)))


(defn- reclaim!
  [a l]
  (stream/append! (grant/writer a) (lease/lapsed l :silence)))


(defn- ring
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key 64})))


(defn- read-all
  "Every value from cursor c to the tail, and the terminal outcome."
  [h c]
  (loop [c c vs []]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r)) (< (count vs) 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)))
        {:values vs :terminal (:dao.stream/outcome r)}))))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- diagnostics
  [h]
  (:values (read-all h (oldest h))))


(defn- op
  ([n] (op occ n))
  ([o n] {:yin.k/occurrence o :yin.k/seq n}))


(defn- env
  ([n v] (env "lease-1" 0 n v))
  ([l e n v]
   {:yin.k/envelope :yin.k/fenced-v1
    :yin.k/incarnation l
    :yin.k/epoch e
    :yin.k/op-id (op n)
    :yin.k/value v}))


(defn- committed
  ([n] (committed "lease-1" n ok))
  ([l n r]
   {:yin.k/admission :committed
    :yin.k/op-id (op n)
    :yin.k/incarnation l
    :yin.k/effect-result r}))


(defn- suspended
  [l n]
  {:yin.k/admission :suspended
   :yin.k/op-id (op n)
   :yin.k/incarnation l
   :yin.k/arbitration {:dao.stream/identity arb}})


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- record-facts
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


(defn- world
  "An offered occurrence granted as lease-1 to holder-a at epoch 0, and
   one enrolled target: the frames, the authority, the target and a ring
   buffer for diagnostics."
  ([] (world nil))
  ([opts]
   (let [frames (fresh-frames)
         a (auth frames nil opts)]
     (offer! a)
     (grant! a "lease-1" "holder-a" "p-1")
     {:frames frames
      :a a
      :i (:yin.k/target (authority/enroll! a))
      :diag (ring)})))


(defn- admit!
  ([w e] (admit! w "holder-a" e))
  ([{:keys [a i diag]} author e]
   (admission/admit! a i author e diag)))


(defn- regranted
  "lease-1 reclaimed (epoch 1) and lease-2 granted to holder-b."
  [{:keys [a] :as w}]
  (reclaim! a "lease-1")
  (grant! a "lease-2" "holder-b" "p-2")
  w)


(defn- poisoned
  "The world reopened with a crash cut armed, then poisoned by an
   admission that hit it."
  [{:keys [frames a] :as w}]
  (authority/close! a)
  (let [a2 (auth frames :before-frame)]
    (admission/admit! a2 (:i w) "holder-a" (env 99 :cut) (:diag w))
    (assoc w :a a2)))


(defn- diagnosed?
  "True when r is the defective answer for defect d, with no admission
   outcome, and one diagnostic was appended."
  [r d]
  (and (= d (get-in r [::admission/diagnostic :yin.k/defect]))
       (= :dao.stream/ok (get-in r [::admission/appended
                                    :dao.stream/outcome]))
       (not (contains? r :yin.k/admission))))


;; =============================================================================
;; Commit
;; =============================================================================

(deftest a-fresh-envelope-commits-once
  (let [{:keys [frames a i] :as w} (world)
        n (count @frames)]
    (is (= (committed 0) (admit! w (env 0 :v))))
    (is (= (inc n) (count @frames)) "one transaction")
    (let [r (last (records frames))]
      (is (= [{:yin.k/custody :yin.k/admitted
               :yin.k/target i
               :yin.k/op-id (op 0)
               :yin.k/intent (ledger/intent i :v)
               :yin.k/value :v
               :yin.k/result ok}
              {:yin.k/custody :yin.k/fenced
               :yin.k/op-id (op 0)
               :yin.k/incarnation "lease-1"
               :yin.k/epoch 0}]
             (vec (record-facts r)))
          "the effect, its result and its dedup record together"))
    (is (= {:values [:v] :terminal :dao.stream/blocked}
           (let [h (authority/target-reader a i)] (read-all h (oldest h)))))
    (is (= {:yin.k/admission :replayed
            :yin.k/op-id (op 0)
            :yin.k/incarnation "lease-1"
            :yin.k/effect-result ok}
           (admit! w (env 0 :v)))
        "a retry replays the recorded result")
    (is (= (inc n) (count @frames)) "and commits nothing")))


(deftest a-closed-target-records-its-closed-result
  (let [{:keys [a i] :as w} (world)]
    (authority/close-target! a i)
    (is (= (committed "lease-1" 0 ledger/closed-result)
           (admit! w (env 0 :v))))
    (is (= {:values [] :terminal :dao.stream/end}
           (let [h (authority/target-reader a i)] (read-all h (oldest h)))))))


;; =============================================================================
;; The check order: one fixture per check, each failing alone
;; =============================================================================

(deftest each-check-fails-alone
  (testing "the control: a fresh world commits the base envelope"
    (is (= (committed 0) (admit! (world) (env 0 :v)))))
  (testing "1. authority: a poisoned authority"
    (let [w (poisoned (world))
          n (count @(:frames w))]
      (is (= (suspended "lease-1" 0) (admit! w (env 0 :v))))
      (is (= n (count @(:frames w))))
      (is (empty? (diagnostics (:diag w))))))
  (testing "1. authority: an exhausted occurrence"
    (let [w (world {::authority/max-epoch 0})]
      (reclaim! (:a w) "lease-1")
      (is (= (suspended "lease-1" 0) (admit! w (env 0 :v))))))
  (testing "1. authority: a quarantined occurrence"
    (let [w (world)]
      (admit! w (env 0 :v))
      (admit! w (env 0 :other))
      (is (= (suspended "lease-1" 1) (admit! w (env 1 :v))))))
  (testing "2. binding: an unbound lease"
    (let [w (world)]
      (is (diagnosed? (admit! w (env "lease-x" 0 0 :v)) :unbound-lease))
      (is (empty? (facts (:frames w) :yin.k/admitted)))))
  (testing "2. binding: another author"
    (let [w (world)]
      (is (diagnosed? (admit! w "mallory" (env 0 :v)) :wrong-author))
      (is (empty? (facts (:frames w) :yin.k/admitted)))))
  (testing "2. binding: no attribution"
    (is (diagnosed? (admit! (world) nil (env 0 :v)) :wrong-author)))
  (testing "3. tenure: a stale epoch"
    (is (= {:yin.k/admission :stale
            :yin.k/op-id (op 0)
            :yin.k/incarnation "lease-1"
            :yin.k/observed-epoch 0
            :yin.k/observed-lease "lease-1"}
           (admit! (world) (env "lease-1" 1 0 :v)))))
  (testing "3. tenure: a stale lease"
    (let [w (regranted (world))]
      (is (= {:yin.k/admission :stale
              :yin.k/op-id (op 0)
              :yin.k/incarnation "lease-1"
              :yin.k/observed-epoch 1
              :yin.k/observed-lease "lease-2"}
             (admit! w (env 0 :v))))
      (is (= (committed "lease-2" 0 ok)
             (admit! w "holder-b" (env "lease-2" 1 0 :v)))
          "the current holder commits the same id")))
  (testing "3. tenure: a reclaimed lease with none active"
    (let [w (world)]
      (reclaim! (:a w) "lease-1")
      (is (= {:yin.k/admission :stale
              :yin.k/op-id (op 0)
              :yin.k/incarnation "lease-1"
              :yin.k/observed-epoch 1}
             (admit! w (env 0 :v))))))
  (testing "4. scope: an id naming another occurrence"
    (let [w (world)]
      (is (diagnosed? (admit! w (assoc (env 0 :v) :yin.k/op-id
                                       (op other-occ 0)))
                      :foreign-op-id))
      (is (empty? (facts (:frames w) :yin.k/admitted)))))
  (testing "5. dedup: equal intent replays"
    (let [w (world)]
      (admit! w (env 0 :v))
      (is (= :replayed (:yin.k/admission (admit! w (env 0 :v)))))))
  (testing "5. dedup: other intent conflicts"
    (let [{:keys [i] :as w} (world)]
      (admit! w (env 0 :v))
      (is (= {:yin.k/admission :intent-conflict
              :yin.k/op-id (op 0)
              :yin.k/incarnation "lease-1"
              :yin.k/recorded-intent (ledger/intent i :v)
              :yin.k/observed-intent (ledger/intent i :w)}
             (admit! w (env 0 :w)))))))


(deftest the-first-failing-check-decides
  (testing "authority before binding"
    (let [w (poisoned (world))]
      (is (= (suspended "lease-1" 0) (admit! w "mallory" (env 0 :v))))
      (is (empty? (diagnostics (:diag w))))))
  (testing "binding before tenure"
    (is (diagnosed? (admit! (world) "mallory" (env "lease-1" 7 0 :v))
                    :wrong-author)))
  (testing "tenure before scope"
    (is (= :stale (:yin.k/admission
                    (admit! (world)
                            (assoc (env "lease-1" 7 0 :v)
                                   :yin.k/op-id (op other-occ 0)))))))
  (testing "scope before dedup"
    (let [w (world)]
      (admit! w (env 0 :v))
      (is (diagnosed? (admit! w (assoc (env 1 :w) :yin.k/op-id
                                       (op other-occ 0)))
                      :foreign-op-id))
      (is (empty? (facts (:frames w) :yin.k/quarantined))))))


(deftest stale-wins-over-a-record
  (let [w (world)]
    (is (= (committed 0) (admit! w (env 0 :v))))
    (regranted w)
    (is (= :stale (:yin.k/admission (admit! w (env 0 :v))))
        "the old holder's equal retry is stale, not replayed")
    (is (= :stale (:yin.k/admission (admit! w (env 0 :w))))
        "and a divergent one is stale, not a conflict")
    (is (empty? (facts (:frames w) :yin.k/quarantined)))
    (is (= {:yin.k/admission :replayed
            :yin.k/op-id (op 0)
            :yin.k/incarnation "lease-2"
            :yin.k/effect-result ok}
           (admit! w "holder-b" (env "lease-2" 1 0 :v)))
        "the current holder's equal intent replays the record")))


;; =============================================================================
;; Intent conflict and quarantine
;; =============================================================================

(deftest an-intent-conflict-quarantines-the-occurrence
  (let [{:keys [frames a] :as w} (world)]
    (admit! w (env 0 :v))
    (let [n (count @frames)
          r (admit! w (env 0 :w))]
      (is (= :intent-conflict (:yin.k/admission r)))
      (is (= (inc n) (count @frames)) "the quarantine commits")
      (is (= [{:yin.k/custody :yin.k/quarantined
               :yin.k/occurrence occ
               :yin.k/op-id (op 0)}]
             (vec (record-facts (last (records frames)))))
          "and only the quarantine: no effect, no second record")
      (is (= 1 (count (facts frames :yin.k/admitted)))))
    (testing "later admissions answer :suspended, before tenure"
      (is (= (suspended "lease-1" 0) (admit! w (env 0 :v))))
      (is (= (suspended "lease-1" 0) (admit! w (env 0 :w))))
      (is (= (suspended "lease-1" 2) (admit! w (env 2 :z))))
      (is (= (suspended "lease-1" 2) (admit! w (env "lease-1" 9 2 :z))))
      (is (= 1 (count (facts frames :yin.k/quarantined))))
      (is (= 1 (count (facts frames :yin.k/admitted)))))
    (testing "the quarantine survives reopen"
      (authority/close! a)
      (let [w2 (assoc w :a (auth frames))]
        (is (= (suspended "lease-1" 2) (admit! w2 (env 2 :z))))
        (testing "and the occurrence is never regranted automatically"
          (is (= {:dao.stream/outcome :dao.stream/ok}
                 (reclaim! (:a w2) "lease-1"))
              "a reclaim is not a grant")
          (is (= {:dao.stream/outcome :dao.stream/invalid-value}
                 (grant! (:a w2) "lease-2" "holder-b" "p-2")))
          (is (empty? (filter #(= "lease-2" (:dao.lease/lease %))
                              (facts frames :dao.lease/accepted)))))))))


(deftest the-hook-refuses-a-quarantined-occurrence
  (let [{:keys [frames a] :as w} (world)
        props (:dao.stream/handle
                (memory-log/create! {:dao.stream/type
                                     :dao.stream/memory-log}))
        ticks (:dao.stream/handle
                (memory-log/create! {:dao.stream/type
                                     :dao.stream/memory-log}))
        _ (stream/append! ticks (lease/tick {:s 1}))
        j (-> (lease/initial-judge
                (merge (grant/judge-config a duration)
                       {:resolver (fn [source _] source) :self arb}))
              (lease/wire-tick ticks (oldest ticks) :ticks)
              (lease/wire-facts props (oldest props) "holder-b"))]
    (admit! w (env 0 :v))
    (admit! w (env 0 :w))
    (reclaim! a "lease-1")
    (stream/append! props (lease/proposal "p-b" (custody/subject occ)))
    (grant/step! a j)
    (is (= 1 (count (facts frames :dao.lease/accepted))))
    (is (= [{:dao.lease/status :dao.lease/rejected
             :dao.lease/proposal "p-b"
             :yin.k/proposer "holder-b"}]
           (facts frames :dao.lease/rejected))
        "the proposal is refused, not left unanswered")))


(deftest an-unauthenticated-conflict-quarantines-nothing
  (let [w (world)]
    (admit! w (env 0 :v))
    (is (diagnosed? (admit! w "mallory" (env 0 :w)) :wrong-author))
    (is (empty? (facts (:frames w) :yin.k/quarantined)))
    (is (= (committed 1) (admit! w (env 1 :v))))))


(deftest a-second-target-conflicts-through-the-one-namespace
  (let [{:keys [frames a] :as w} (world)
        j (:yin.k/target (authority/enroll! a))]
    (is (= (committed 0) (admit! w (env 0 :v))))
    (is (= {:yin.k/admission :intent-conflict
            :yin.k/op-id (op 0)
            :yin.k/incarnation "lease-1"
            :yin.k/recorded-intent (ledger/intent (:i w) :v)
            :yin.k/observed-intent (ledger/intent j :v)}
           (admission/admit! a j "holder-a" (env 0 :v) (:diag w)))
        "the same id and payload to another target is another intent")
    (is (= 1 (count (facts frames :yin.k/quarantined))))
    (is (= {:values [] :terminal :dao.stream/blocked}
           (let [h (authority/target-reader a j)] (read-all h (oldest h)))))))


;; =============================================================================
;; Defective envelopes and diagnostics
;; =============================================================================

(def ^:private malformed-envelopes
  (concat
    (for [k [:yin.k/envelope :yin.k/incarnation :yin.k/epoch :yin.k/op-id
             :yin.k/value]]
      [(str "without " k) (dissoc (env 0 :v) k)])
    [["another dispatch" (assoc (env 0 :v) :yin.k/envelope :yin.k/fenced-v2)]
     ["not a map" [:yin.k/fenced-v1 "lease-1" 0 (op 0) :v]]
     ["a nil incarnation" (assoc (env 0 :v) :yin.k/incarnation nil)]
     ["a float epoch" (assoc (env 0 :v) :yin.k/epoch 0.5)]
     ["a negative epoch" (assoc (env 0 :v) :yin.k/epoch -1)]
     ["an epoch past the bound"
      (assoc (env 0 :v) :yin.k/epoch (inc ledger/max-exact))]
     ["an op id with a third key" (assoc-in (env 0 :v) [:yin.k/op-id :k] 1)]
     ["an op id without its seq"
      (update (env 0 :v) :yin.k/op-id dissoc :yin.k/seq)]
     ["an op id naming no occurrence"
      (assoc (env 0 :v) :yin.k/op-id (op "O" 0))]
     ["a float seq" (assoc-in (env 0 :v) [:yin.k/op-id :yin.k/seq] 1.5)]
     ["the exhausted seq 2^52-1"
      (assoc-in (env 0 :v) [:yin.k/op-id :yin.k/seq] ledger/max-exact)]
     ["a payload outside the canonical domain"
      (assoc (env 0 :v) :yin.k/value (atom 1))]]))


(deftest a-defective-envelope-commits-nothing-and-is-diagnosed-once
  (doseq [[label e] malformed-envelopes]
    (testing label
      (let [{:keys [frames diag] :as w} (world)
            n (count @frames)
            r (admit! w e)]
        (is (diagnosed? r :malformed))
        (is (= n (count @frames)) "nothing commits")
        (is (= 1 (count (diagnostics diag))) "exactly one diagnostic")
        (is (= (::admission/diagnostic r) (first (diagnostics diag))))))))


(deftest the-diagnostic-shape
  (let [{:keys [i diag] :as w} (world)
        e (assoc (env 0 :secret) :yin.k/epoch 0.5)]
    (admit! w "holder-a" e)
    (admit! w "mallory" (env 0 :secret))
    (let [[m wrong] (diagnostics diag)]
      (is (= {:yin.k/diagnostic :yin.k/defective-envelope
              :yin.k/defect :malformed
              :yin.k/target i
              :yin.k/author "holder-a"
              :yin.k/claimed {:yin.k/incarnation "lease-1"
                              :yin.k/epoch 0.5
                              :yin.k/op-id (op 0)}}
             m)
          "claims nested, the payload never echoed")
      (is (= {:yin.k/diagnostic :yin.k/defective-envelope
              :yin.k/defect :wrong-author
              :yin.k/target i
              :yin.k/author "mallory"
              :yin.k/claimed {:yin.k/incarnation "lease-1"
                              :yin.k/epoch 0
                              :yin.k/op-id (op 0)}}
             wrong))
      (is (not-any? #(or (contains? % :yin.k/admission)
                         (contains? % :yin.k/status)
                         (contains? % :yin.k/op-id))
                    [m wrong])))
    (admit! w nil (env 0 :secret))
    (is (not (contains? (last (diagnostics diag)) :yin.k/author))
        "no attribution, no author key")))


(deftest an-envelope-shaped-value-is-payload
  (let [{:keys [a i] :as w} (world)
        inner (env "lease-x" 7 5 :inner)]
    (is (= (committed 0) (admit! w (env 0 inner))))
    (is (= [inner]
           (:values (let [h (authority/target-reader a i)]
                      (read-all h (oldest h))))))))


(deftest a-failed-diagnostic-append-is-data-and-admits-nothing
  (let [{:keys [frames] :as w} (world)
        n (count @frames)
        closed (doto (ring) stream/close!)
        throwing (reify stream/IDaoStreamWriter
                   (append! [_ _v] (throw (ex-info "down" {}))))
        r1 (admit! (assoc w :diag closed) "mallory" (env 0 :v))
        r2 (admit! (assoc w :diag throwing) "mallory" (env 0 :v))]
    (is (= :dao.stream/closed
           (get-in r1 [::admission/appended :dao.stream/outcome])))
    (is (= :dao.stream/transport-error
           (get-in r2 [::admission/appended :dao.stream/outcome])))
    (is (= :wrong-author (get-in r1 [::admission/diagnostic :yin.k/defect])))
    (is (not-any? #(contains? % :yin.k/admission) [r1 r2]))
    (is (= n (count @frames)))))


(deftest an-unenrolled-boundary-runs-no-admission
  (let [{:keys [frames a diag]} (world)
        n (count @frames)
        r (admission/admit! a "nowhere" "holder-a" (env 0 :v) diag)]
    (is (= {::admission/unenrolled "nowhere"} r))
    (is (= n (count @frames)))
    (is (empty? (diagnostics diag)))))


;; =============================================================================
;; The five outcomes are closed
;; =============================================================================

(deftest the-five-outcomes-are-closed
  (is (= #{:committed :replayed :stale :intent-conflict :suspended}
         admission/admissions))
  (let [scripted (let [w (world)]
                   (mapv #(admit! w %)
                         [(env 0 :v) (env 0 :v) (env "lease-1" 1 0 :v)
                          (env 0 :w) (env 1 :v)]))
        w (world)
        envs (for [l ["lease-1" "lease-2" "lease-x"]
                   e [0 1]
                   n [0 1]
                   v [:v :w]]
               (env l e n v))
        rs (into scripted
                 (for [phase [:before :regranted]
                       :let [_ (when (= phase :regranted) (regranted w))]
                       au ["holder-a" "holder-b" "mallory" nil]
                       e envs]
                   (admit! w au e)))
        outcomes (filter :yin.k/admission rs)]
    (is (= [:committed :replayed :stale :intent-conflict :suspended]
           (map :yin.k/admission scripted)))
    (is (seq outcomes))
    (is (every? #(or (contains? % :yin.k/admission)
                     (contains? % ::admission/diagnostic))
                rs)
        "every answer is an outcome or a diagnostic")
    (is (every? #(contains? admission/admissions (:yin.k/admission %))
                outcomes))
    (is (every? #(and (contains? % :yin.k/op-id)
                      (contains? % :yin.k/incarnation)
                      (not (contains? % :yin.k/status))
                      (not (contains? % :dao.space/t)))
                outcomes))
    (is (= #{:committed :replayed :stale :intent-conflict :suspended}
           (set (map :yin.k/admission outcomes)))
        "the run reaches all five")))


;; =============================================================================
;; The outcome projection and result delivery
;; =============================================================================

(deftest the-outcome-projection-contract
  (let [{:keys [frames a] :as w} (world)
        h (admission/outcome-reader a)
        origin (oldest h)
        id (str arb "/outcomes")]
    (testing "identity and surfaces"
      (is (= {:dao.stream/type admission/outcomes-type
              :dao.stream/identity id}
             (:dao.stream/descriptor (stream/descriptor h))))
      (is (= id (:dao.stream/identity (stream/descriptor h))))
      (is (stream/reader? h))
      (is (not (stream/writer? h)))
      (is (not (stream/closable? h))))
    (testing "blocked at the tail"
      (is (= {:values [] :terminal :dao.stream/blocked} (read-all h origin))))
    (admit! w (env 0 :v))
    (admit! w (env 0 :v))
    (admit! w "mallory" (env 1 :v))
    (admit! w (env 1 :v))
    (admit! w (env "lease-1" 3 2 :v))
    (let [after-first (:dao.stream/cursor (stream/next h origin))]
      (is (= {:values [(committed 0) (committed 1)]
              :terminal :dao.stream/blocked}
             (read-all h origin))
          "committed outcomes only, in ledger order")
      (is (= :dao.stream/invalid-cursor
             (:dao.stream/outcome
               (stream/next h (assoc origin ::admission/position 9)))))
      (is (= :dao.stream/invalid-anchor
             (:dao.stream/outcome (stream/cursor h :elsewhere))))
      (authority/close! a)
      (testing "cursors survive reopen and the canonical codec"
        (let [h2 (admission/outcome-reader (auth frames))]
          (is (= origin (oldest h2)))
          (is (= {:values [(committed 1)] :terminal :dao.stream/blocked}
                 (read-all h2 (cbor/decode (cbor/encode after-first))))))))))


(deftest a-poisoned-authority-serves-no-outcomes
  (let [w (world)
        _ (admit! w (env 0 :v))
        w2 (poisoned w)
        h (admission/outcome-reader (:a w2))]
    (is (= :dao.stream/transport-error
           (:dao.stream/outcome (stream/cursor h :dao.stream/oldest))))
    (is (= :dao.stream/transport-error
           (:dao.stream/outcome
             (stream/next h {::admission/outcomes (str arb "/outcomes")
                             ::admission/position 0}))))))


(deftest a-committed-result-is-redelivered-after-reopen
  (let [{:keys [frames a] :as w} (world)
        h (admission/outcome-reader a)
        kept (oldest h)]
    (authority/close! a)
    (let [cut (auth frames :after-frame-before-visible)]
      (is (= (suspended "lease-1" 0)
             (admission/admit! cut (:i w) "holder-a" (env 0 :v) (:diag w)))
          "the reply is lost: the authority poisons"))
    (let [r (grant/reopen! (backend frames) nil)
          a2 (::authority/authority r)]
      (is (= ["lease-1"] (:yin.k/reclaimed r)))
      (is (= {:values [(committed 0)] :terminal :dao.stream/blocked}
             (read-all (admission/outcome-reader a2) kept))
          "the driver's kept cursor reads the committed outcome")
      (is (= :stale (:yin.k/admission (admit! (assoc w :a a2) (env 0 :v))))
          "a retry after the reopen's reclaim cannot replay it")
      (is (= 1 (count (facts frames :yin.k/admitted)))))))


;; =============================================================================
;; Crash cuts on the admission transition
;; =============================================================================

(deftest a-cut-admission-commits-zero-or-one-times
  (doseq [[cut persisted retry] [[:before-frame 0 :committed]
                                 [:after-frame-before-visible 1 :replayed]
                                 [:torn-frame 0 :committed]]]
    (testing (name cut)
      (let [{:keys [frames a] :as w} (world)
            _ (authority/close! a)
            a1 (auth frames cut)]
        (is (= (suspended "lease-1" 0)
               (admission/admit! a1 (:i w) "holder-a" (env 0 :v) (:diag w))))
        (is (= (suspended "lease-1" 0)
               (admission/admit! a1 (:i w) "holder-a" (env 0 :v) (:diag w)))
            "the poisoned authority admits nothing")
        (let [a2 (auth frames)]
          (is (= persisted (count (facts frames :yin.k/admitted))))
          (is (= persisted (count (facts frames :yin.k/fenced))))
          (is (= retry (:yin.k/admission
                         (admit! (assoc w :a a2) (env 0 :v))))
              "the retry finds the recorded result or commits it")
          (is (= 1 (count (facts frames :yin.k/admitted))))
          (is (= {:values [(committed 0)] :terminal :dao.stream/blocked}
                 (let [h (admission/outcome-reader a2)]
                   (read-all h (oldest h))))))))))


(deftest a-transition-past-a-bound-suspends
  (let [frames (fresh-frames)
        a (auth frames nil {::authority/max-exact 20})
        _ (offer! a)
        _ (grant! a "lease-1" "holder-a" "p-1")
        i (:yin.k/target (authority/enroll! a))
        w {:frames frames :a a :i i :diag (ring)}
        n (count @frames)]
    (is (= (suspended "lease-1" 0) (admit! w (env 0 :v))))
    (is (= n (count @frames)))))


;; =============================================================================
;; Exhaustion (7.11.1 clause 9, the effect half)
;; =============================================================================

(deftest an-epoch-at-the-bound-admits-until-exhausted
  (let [w (regranted (world {::authority/max-epoch 1}))]
    (is (= (committed "lease-2" 0 ok)
           (admit! w "holder-b" (env "lease-2" 1 0 :v)))
        "a grant bound at the bound is valid and its effects commit")
    (reclaim! (:a w) "lease-2")
    (is (true? (get-in (authority/projection (:a w))
                       [:occurrences occ :yin.k/exhausted])))
    (is (= (suspended "lease-2" 1)
           (admit! w "holder-b" (env "lease-2" 1 1 :v))))
    (is (= (suspended "lease-1" 1) (admit! w (env 1 :v)))
        "before tenure: even a stale holder hears :suspended")))


;; =============================================================================
;; The ledger arms fail closed
;; =============================================================================

(defn- fold
  "Fold `fs` as the next record of projection p."
  [p fs]
  (let [t (:next-t p)]
    (ledger/fold-record
      p {:dao.space/transaction
         {:t t
          :datoms (mapv (fn [[e a v]] [e a v t datom/default-op])
                        (ledger/facts->datoms (:next-e p) fs))}})))


(defn- defect
  [p fs]
  (::ledger/defect (fold p fs)))


(deftest the-admission-arms-fail-closed
  (let [{:keys [a i] :as w} (world)
        p0 (authority/projection a)
        admitted (fn [n v]
                   {:yin.k/custody :yin.k/admitted :yin.k/target i
                    :yin.k/op-id (op n) :yin.k/intent (ledger/intent i v)
                    :yin.k/value v :yin.k/result ok})
        fenced (fn [n l e]
                 {:yin.k/custody :yin.k/fenced :yin.k/op-id (op n)
                  :yin.k/incarnation l :yin.k/epoch e})
        quarantined (fn [o n]
                      {:yin.k/custody :yin.k/quarantined
                       :yin.k/occurrence o :yin.k/op-id (op n)})
        _ (admit! w (env 0 :v))
        p1 (authority/projection a)]
    (testing "the fenced arm"
      (is (nil? (defect p0 [(admitted 0 :v) (fenced 0 "lease-1" 0)])))
      (is (= [(op 0)] (:outcomes p1)))
      (is (= "lease-1" (get-in p1 [:admitted (cbor/content-key (op 0))
                                   :yin.k/incarnation])))
      (is (= :malformed-fact
             (defect p0 [(admitted 0 :v)
                         (assoc (fenced 0 "lease-1" 0) :yin.k/op-id
                                {:yin.k/occurrence occ :yin.k/seq 0
                                 :k 1})])))
      (is (= :malformed-fact
             (defect p0 [(admitted 0 :v)
                         (fenced 0 "lease-1" (cbor/float64 0))])))
      (is (= :unpaired-fenced (defect p0 [(fenced 0 "lease-1" 0)])))
      (is (= :duplicate-fenced (defect p1 [(fenced 0 "lease-1" 0)])))
      (is (= :unknown-lease
             (defect p0 [(admitted 0 :v) (fenced 0 "lease-x" 0)])))
      (is (= :epoch-mismatch
             (defect p0 [(admitted 0 :v) (fenced 0 "lease-1" 1)]))))
    (testing "the quarantine arm"
      (let [p2 (fold p1 [(quarantined occ 0)])]
        (is (true? (get-in p2 [:occurrences occ :yin.k/quarantined])))
        (is (= :duplicate-quarantine (defect p2 [(quarantined occ 0)])))
        (is (= :quarantined-occurrence
               (defect (fold p2 [(lease/lapsed "lease-1" :silence)
                                 (custody/reclaimed occ "lease-1" 1)])
                 [(a-grant "lease-2" "holder-b" "p-2")
                  (custody/bound occ "lease-2" "holder-b" 1)]))
            "the fold refuses a regrant")))
    (is (= :malformed-fact (defect p1 [(quarantined "O" 0)])))
    (is (= :unknown-occurrence (defect p1 [(quarantined other-occ 0)])))
    (is (= :unrecorded-op (defect p1 [(quarantined occ 5)])))))


;; =============================================================================
;; Serialization with reclaim (JVM threads)
;; =============================================================================

#?(:cljd nil
   :clj
   (deftest admission-and-reclaim-serialize
     (testing "a reclaim holding the lock: the admission after it is stale"
       (let [{:keys [a] :as w} (world)
             entered (promise)
             release (promise)
             rc (future (authority/locked
                          a (fn []
                              (deliver entered true)
                              @release
                              (reclaim! a "lease-1"))))
             _ (is (true? (deref entered 5000 false)))
             ad (future (admit! w (env 0 :v)))]
         (is (= ::blocked (deref ad 200 ::blocked)))
         (deliver release true)
         @rc
         (is (= :stale (:yin.k/admission (deref ad 5000 nil))))))
     (testing "an admission holding the lock: it commits before the reclaim"
       (let [{:keys [frames a] :as w} (world)
             entered (promise)
             release (promise)
             ad (future (authority/locked
                          a (fn []
                              (deliver entered true)
                              @release
                              (admit! w (env 0 :v)))))
             _ (is (true? (deref entered 5000 false)))
             rc (future (reclaim! a "lease-1"))]
         (is (= ::blocked (deref rc 200 ::blocked)))
         (deliver release true)
         (is (= (committed 0) @ad))
         (is (= {:dao.stream/outcome :dao.stream/ok} (deref rc 5000 nil)))
         (is (= [:yin.k/admitted :yin.k/fenced :dao.lease/lapsed
                 :yin.k/reclaimed]
                (map kind (mapcat record-facts
                                  (take-last 2 (records frames))))))))
     (testing "racing: commit-before-reclaim or refusal-after, never both"
       (dotimes [_ 20]
         (let [{:keys [frames a] :as w} (world)
               go (promise)
               ad (future @go (admit! w (env 0 :v)))
               rc (future @go (reclaim! a "lease-1"))
               _ (deliver go true)
               r @ad
               _ @rc
               ts (fn [k]
                    (keep-indexed (fn [t rec]
                                    (when (some #(= k (kind %))
                                                (record-facts rec))
                                      t))
                                  (records frames)))]
           (case (:yin.k/admission r)
             :committed (is (< (first (ts :yin.k/admitted))
                               (first (ts :dao.lease/lapsed))))
             :stale (is (empty? (ts :yin.k/admitted)))
             (is false (str "unexpected " r))))))))

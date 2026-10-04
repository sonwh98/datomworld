(ns yin.vm.ucf.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb")

(def ^:private target (ledger/target-identity arb 0))


(defn- record
  [t datoms]
  {:dao.space/transaction {:t t :datoms datoms}})


(defn- stamp
  "Pad `[e a v]` datoms with t and the assertion op, as the transactor
   does."
  [t datoms]
  (mapv #(conj % t 1) datoms))


(def ^:private enrolled
  {:yin.k/custody :yin.k/enrolled
   :yin.k/target target
   :yin.k/effect-kinds #{:yin.k/append}})


(defn- admitted
  [op value]
  {:yin.k/custody :yin.k/admitted
   :yin.k/target target
   :yin.k/op-id {:yin.k/occurrence "O" :yin.k/seq op}
   :yin.k/intent (ledger/intent target value)
   :yin.k/value value
   :yin.k/result ledger/ok-result})


(defn- fold
  "Fold each transaction's facts in turn, from the empty projection."
  [& txs]
  (reduce (fn [p facts]
            (if (::ledger/defect p)
              p
              (ledger/fold-record
                p
                (record (:next-t p)
                        (stamp (:next-t p)
                               (ledger/facts->datoms (:next-e p) facts))))))
          (ledger/empty-projection arb)
          txs))


(deftest datoms-follow-the-published-attribute-order
  (is (= [[16 :yin.k/custody :yin.k/enrolled]
          [16 :yin.k/target target]
          [16 :yin.k/effect-kinds #{:yin.k/append}]
          [17 :yin.k/custody :yin.k/target-closed]
          [17 :yin.k/target target]]
         (ledger/facts->datoms 16 [(into {} (reverse (seq enrolled)))
                                   {:yin.k/target target
                                    :yin.k/custody :yin.k/target-closed}])))
  (is (= [:yin.k/custody :yin.k/target :yin.k/op-id :yin.k/intent
          :yin.k/result]
         (mapv second
               (ledger/fact-datoms 20 (dissoc (admitted 0 :v) :yin.k/value))))
      "an absent attribute is skipped, the rest keep their order")
  (is (= 4503599627370495 ledger/max-exact)))


(deftest authoring-refuses-a-fact-outside-the-published-order
  (let [defect (fn [facts]
                 (try (ledger/facts->datoms 16 facts)
                      nil
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (::ledger/defect (ex-data e)))))]
    (is (= :unknown-kind (defect [{:yin.k/custody :yin.k/other}])))
    (is (= :unknown-kind (defect [{:yin.k/target target}]))
        "a fact with no dispatch key")
    (is (= :unpublished-attribute
           (defect [(assoc enrolled :yin.k/extra 1)]))
        "an attribute outside the kind's order is never silently dropped")
    (is (= :unknown-kind
           (defect [(assoc enrolled :dao.lease/status :dao.lease/accepted)]))
        "both dispatch keys")))


(deftest the-fold-builds-the-projection
  (let [p (fold [enrolled] [(admitted 0 :a) (admitted 1 :a)]
                [{:yin.k/custody :yin.k/target-closed :yin.k/target target}])]
    (is (= 3 (:next-t p)))
    (is (= 20 (:next-e p)))
    (is (= {target {:closed? true :values [:a :a]}} (:targets p)))
    (is (= ledger/ok-result
           (get-in p [:admitted (cbor/content-key {:yin.k/occurrence "O"
                                                   :yin.k/seq 1})
                      :yin.k/result])))))


(deftest the-fold-refuses-what-the-ledger-cannot-hold
  (testing "transaction time must equal the position"
    (is (= :t-mismatch
           (::ledger/defect
             (ledger/fold-record (ledger/empty-projection arb)
                                 (record 1 (stamp 1 (ledger/facts->datoms
                                                      16 [enrolled]))))))))
  (testing "a datom stamped with another t"
    (is (= :malformed-datom
           (::ledger/defect
             (ledger/fold-record (ledger/empty-projection arb)
                                 (record 0 (stamp 1 (ledger/facts->datoms
                                                      16 [enrolled]))))))))
  (testing "a reused entity id"
    (let [p (fold [enrolled])]
      (is (= :malformed-datom
             (::ledger/defect
               (ledger/fold-record
                 p (record 1 (stamp 1 (ledger/facts->datoms
                                        16 [(admitted 0 :a)])))))))))
  (testing "a target this ledger did not mint at that t"
    (is (= :foreign-target
           (::ledger/defect (fold [(assoc enrolled :yin.k/target "x")])))))
  (testing "attributes out of the published order"
    (is (= :malformed-fact
           (::ledger/defect
             (ledger/fold-record
               (ledger/empty-projection arb)
               (record 0 (stamp 0 (vec (reverse (ledger/facts->datoms
                                                  16 [enrolled]))))))))))
  (testing "a duplicate op id"
    (is (= :duplicate-op-id
           (::ledger/defect (fold [enrolled] [(admitted 0 :a)]
                                  [(admitted 0 :a)])))))
  (testing "an admission to an unknown target"
    (is (= :unknown-target (::ledger/defect (fold [(admitted 0 :a)])))))
  (testing "a fact kind this ledger does not know"
    (is (= :malformed-fact
           (::ledger/defect
             (ledger/fold-record
               (ledger/empty-projection arb)
               (record 0 (stamp 0 [[16 :yin.k/custody :yin.k/other]])))))))
  (testing "a retraction: the ledger is add-only"
    (is (= :malformed-datom
           (::ledger/defect
             (ledger/fold-record
               (ledger/empty-projection arb)
               (record 0
                       (mapv #(conj % 0 0)
                             (ledger/facts->datoms 16 [enrolled]))))))))
  (testing "a non-record value"
    (is (= :malformed-record
           (::ledger/defect
             (ledger/fold-record (ledger/empty-projection arb) :x))))))


;; =============================================================================
;; Custody facts (slice C5)
;; =============================================================================

(def ^:private occ "8f0c6a52-6a1e-4e43-9d55-3f0a4c1b2e01")


(defn- address
  [digit]
  (keyword "segment" (str "blake3-" (apply str (repeat 64 digit)))))


(def ^:private addr-1 (address "1"))

(def ^:private addr-2 (address "2"))


(def ^:private baseline
  {:yin.k/kind :blocked
   :yin.k/occurrence occ
   :yin.k/arbitration {:dao.stream/identity arb :dao.stream/descriptor {}}
   :yin.k/next-op-seq 0
   :yin.k/ops {}})


(defn- offered
  ([] (offered addr-1))
  ([address] (offered address baseline))
  ([address b] (custody/offer occ address "carrier" b)))


(defn- grant
  ([] (grant "lease-1" "holder-a"))
  ([l h]
   {:dao.lease/status :dao.lease/accepted
    :dao.lease/lease l
    :dao.lease/proposal "p-1"
    :dao.lease/subject {:yin.k/occurrence occ}
    :dao.lease/holder h
    :dao.lease/duration {:s 30}}))


(defn- bound
  ([] (bound "lease-1" "holder-a" 0))
  ([l h e] (custody/bound occ l h e)))


(deftest the-fold-records-offers-grants-and-bindings
  (let [p (fold [(offered)]
                [(offered addr-2)]
                [(grant) (bound)])]
    (is (= {occ {:yin.k/baseline baseline
                 :yin.k/variants #{addr-1 addr-2}
                 :yin.k/epoch 0
                 :dao.lease/lease "lease-1"}}
           (:occurrences p))
        "an equal-baseline variant joins its occurrence")
    (is (= {"lease-1" {:yin.k/occurrence occ
                       :dao.lease/holder "holder-a"
                       :yin.k/epoch 0
                       :dao.space/t 2
                       :dao.lease/duration {:s 30}
                       :dao.lease/proposal "p-1"}}
           (:leases p))
        "a lease keeps its grant's terms")
    (is (= {["holder-a" "p-1"] :dao.lease/accepted} (:answered p))
        "the grant answers its holder's proposal")
    (is (= #{:arbitration :max-epoch :next-t :next-e :targets :admitted
             :occurrences :leases :answered}
           (set (keys p)))
        "no transient fold state stays in a projection")))


(defn- raw
  "The defect of folding one record of `[e a v]` datoms after `txs`."
  [txs datoms]
  (let [p (apply fold txs)]
    (::ledger/defect
      (ledger/fold-record p (record (:next-t p) (stamp (:next-t p) datoms))))))


(deftest the-fold-refuses-malformed-custody-facts
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))
        other "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02"]
    (testing "offers"
      (is (= :malformed-fact
             (defect [(assoc (offered) :yin.k/occurrence "O")])))
      (is (= :malformed-fact (defect [(assoc (offered) :yin.k/id "addr")])))
      (is (= :malformed-fact
             (defect [(assoc (offered) :yin.k/policy :yin.k/fork)])))
      (is (= :malformed-fact (defect [(dissoc (offered) :yin.k/medium)])))
      (is (= :malformed-fact
             (defect [(offered addr-1
                               (assoc baseline :yin.k/occurrence other))]))
          "a baseline of another occurrence")
      (is (= :malformed-fact
             (defect [(offered addr-1
                               (assoc baseline :yin.k/kind :halted))])))
      (is (= :foreign-arbitration
             (defect [(offered addr-1
                               (assoc-in baseline
                                         [:yin.k/arbitration
                                          :dao.stream/identity]
                                         "other"))])))
      (is (= :duplicate-offer (defect [(offered)] [(offered)])))
      (is (= :variant-conflict
             (defect [(offered)]
               [(offered addr-2
                         (assoc baseline :yin.k/next-op-seq 1))]))))
    (testing "grants"
      (is (= :unknown-occurrence (defect [(grant) (bound)])))
      (is (= :malformed-fact
             (defect [(offered)]
               [(dissoc (grant) :dao.lease/duration) (bound)])))
      (is (= :malformed-fact
             (defect [(offered)]
               [(assoc (grant) :dao.lease/subject
                       {:yin.k/occurrence occ :x 1})
                (bound)])))
      (is (= :unbound-grant (defect [(offered)] [(grant)]))
          "a grant without its binding in one record")
      (is (= :unbound-grant (defect [(offered)] [(grant)] [(bound)])))
      (is (= :held-occurrence
             (defect [(offered)] [(grant) (bound)]
               [(grant "lease-2" "holder-b")
                (bound "lease-2" "holder-b" 0)])))
      (is (= :duplicate-lease
             (defect [(offered)] [(grant) (bound)] [(grant) (bound)]))))
    (testing "bindings"
      (is (= :unknown-lease (defect [(offered)] [(bound)])))
      (is (= :malformed-fact
             (defect [(offered)]
               [(grant) (bound "lease-1" "holder-a" (cbor/float64 0))]))
          "a float epoch")
      (is (= :epoch-mismatch
             (defect [(offered)] [(grant) (bound "lease-1" "holder-a" 1)]))
          "the first grant binds 0")
      (is (= :binding-mismatch
             (defect [(offered)] [(grant) (bound "lease-1" "holder-b" 0)])))
      (is (= :duplicate-binding
             (defect [(offered)] [(grant) (bound) (bound)]))))
    (testing "a fact carrying both dispatch keys"
      (is (= :malformed-fact
             (raw [[(offered)]]
                  [[20 :yin.k/custody :yin.k/bound]
                   [20 :dao.lease/status :dao.lease/accepted]]))))))


;; =============================================================================
;; Lapses, epochs and refusals (slice C6)
;; =============================================================================

(defn- lapse
  ([] (lapse "lease-1"))
  ([l] {:dao.lease/status :dao.lease/lapsed
        :dao.lease/lease l
        :dao.lease/cause :policy}))


(defn- reclaimed
  ([e] (reclaimed "lease-1" e))
  ([l e] (custody/reclaimed occ l e)))


(defn- rejected
  [pid]
  {:dao.lease/status :dao.lease/rejected :dao.lease/proposal pid})


(defn- refusal
  "The refusal of `proposer`'s proposal pid: the plain lease fact and
   the authority's custody fact naming the proposer, one transaction."
  [proposer pid]
  [(rejected pid) (custody/refused proposer pid)])


(defn- granted
  "A grant of lease l to holder h answering proposal pid, with its
   binding at epoch e."
  [l h pid e]
  [(assoc (grant l h) :dao.lease/proposal pid) (bound l h e)])


(deftest a-lapse-and-its-epoch-commit-together
  (let [p (fold [(offered)] [(grant) (bound)] [(lapse) (reclaimed 1)])]
    (is (nil? (::ledger/defect p)))
    (is (= 1 (get-in p [:occurrences occ :yin.k/epoch]))
        "the reclaim raises the epoch by one")
    (is (nil? (get-in p [:occurrences occ :dao.lease/lease]))
        "and leaves the occurrence with no live lease")
    (is (= :policy (get-in p [:leases "lease-1" :dao.lease/cause]))))
  (let [p (fold [(offered)] [(grant) (bound)] [(lapse) (reclaimed 1)]
                (granted "lease-2" "holder-b" "p-2" 1)
                [(lapse "lease-2") (reclaimed "lease-2" 2)]
                (granted "lease-3" "holder-a" "p-3" 2))]
    (is (nil? (::ledger/defect p)))
    (is (= [0 1 2] (mapv #(get-in p [:leases % :yin.k/epoch])
                         ["lease-1" "lease-2" "lease-3"]))
        "the grant after k reclaims binds k")))


(deftest no-reader-sees-a-lapse-without-its-epoch
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))
        base [[(offered)] [(grant) (bound)]]]
    (is (= :lapse-without-epoch (apply defect (conj base [(lapse)]))))
    (is (= :lapse-without-epoch
           (apply defect (conj base [(lapse)] [(reclaimed 1)])))
        "the epoch fact in a later record is too late")
    (is (= :unpaired-epoch (apply defect (conj base [(reclaimed 1)])))
        "an epoch change without its lapse")
    (is (= :unpaired-epoch
           (apply defect (conj base [(reclaimed 1) (lapse)])))
        "the epoch fact follows its lapse")
    (is (= :epoch-mismatch
           (apply defect (conj base [(lapse) (reclaimed 2)])))
        "exactly one")
    (is (= :epoch-mismatch
           (apply defect (conj base [(lapse) (reclaimed 0)])))
        "never unchanged below the bound")
    (is (= :malformed-fact
           (apply defect (conj base [(lapse) (reclaimed (cbor/float64 1))]))))
    (is (= :unpaired-epoch
           (apply defect (conj base [(lapse) (reclaimed 1) (reclaimed 1)]))))
    (is (= :binding-mismatch
           (apply defect
                  (conj base
                        [(lapse)
                         (assoc (reclaimed 1) :yin.k/occurrence
                                "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02")]))))
    (is (= :unknown-lease (defect [(offered)] [(lapse) (reclaimed 1)])))
    (is (= :duplicate-lapse
           (apply defect (conj base [(lapse) (reclaimed 1)]
                               [(lapse) (reclaimed 2)]))))
    (is (= :malformed-fact
           (apply defect (conj base [(dissoc (lapse) :dao.lease/cause)
                                     (reclaimed 1)]))))))


(deftest a-successor-occurrence-starts-at-epoch-zero
  (let [other "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02"
        p (fold [(offered)] [(grant) (bound)] [(lapse) (reclaimed 1)]
                [(custody/offer other addr-2 "carrier"
                                (assoc baseline :yin.k/occurrence other))])]
    (is (= 1 (get-in p [:occurrences occ :yin.k/epoch])))
    (is (= 0 (get-in p [:occurrences other :yin.k/epoch])))))


(defn- at-epoch
  "The projection after offering occ, its epoch set to `e` as if e
   reclaims had passed."
  [e]
  (assoc-in (fold [(offered)]) [:occurrences occ :yin.k/epoch] e))


(defn- fold-on
  [p & txs]
  (reduce (fn [p facts]
            (if (::ledger/defect p)
              p
              (ledger/fold-record
                p
                (record (:next-t p)
                        (stamp (:next-t p)
                               (ledger/facts->datoms (:next-e p) facts))))))
          p
          txs))


(deftest the-epoch-exhausts-at-the-bound
  (let [top ledger/max-exact
        p (fold-on (at-epoch top) [(grant) (bound "lease-1" "holder-a" top)])]
    (is (nil? (::ledger/defect p)) "a grant at 2^52-1 is valid")
    (is (= top (get-in p [:leases "lease-1" :yin.k/epoch])))
    (let [p' (fold-on p [(lapse) (reclaimed top)])]
      (is (nil? (::ledger/defect p'))
          "the reclaim records the lapse and leaves the epoch")
      (is (= top (get-in p' [:occurrences occ :yin.k/epoch])))
      (is (true? (get-in p' [:occurrences occ :yin.k/exhausted])))
      (is (= :exhausted-occurrence
             (::ledger/defect
               (fold-on p' (granted "lease-2" "holder-b" "p-2" top))))
          "no further grant"))
    (is (= :malformed-fact
           (::ledger/defect (fold-on p [(lapse) (reclaimed (inc top))])))
        "2^52 is refused on the bytes"))
  (is (= :malformed-fact
         (::ledger/defect
           (fold-on (at-epoch 0) [(grant) (bound "lease-1" "holder-a"
                                                 (inc ledger/max-exact))]))))
  (is (= :malformed-fact
         (::ledger/defect
           (fold-on (at-epoch 0) [(grant) (bound "lease-1" "holder-a" -1)]))))
  (is (= :malformed-fact
         (::ledger/defect
           (fold [(offered)] [(grant) (bound)] [(lapse) (reclaimed -1)])))))


(deftest a-lower-epoch-bound-exhausts-earlier
  (let [p (fold-on (ledger/empty-projection arb 1)
                   [(offered)] [(grant) (bound)] [(lapse) (reclaimed 1)]
                   (granted "lease-2" "holder-b" "p-2" 1)
                   [(lapse "lease-2") (reclaimed "lease-2" 1)])]
    (is (nil? (::ledger/defect p)))
    (is (true? (get-in p [:occurrences occ :yin.k/exhausted])))))


(deftest refusals-answer-their-proposer
  (let [p (fold [(offered)] (refusal "holder-b" "p-1"))]
    (is (nil? (::ledger/defect p)))
    (is (= {["holder-b" "p-1"] :dao.lease/rejected} (:answered p))))
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))]
    (is (= :answered-proposal
           (defect (refusal "holder-b" "p-1") (refusal "holder-b" "p-1"))))
    (is (= :answered-proposal
           (defect [(offered)] [(grant) (bound)]
             (refusal "holder-a" "p-1")))
        "a granted proposal is answered")
    (is (= :answered-proposal
           (defect [(offered)] (refusal "holder-a" "p-1") [(grant) (bound)]))
        "a refused proposal gets no grant")
    (is (nil? (defect [(offered)] (refusal "holder-b" "p-1")
                [(grant) (bound)]))
        "another proposer's id is another answer")))


(deftest a-rejection-and-its-refused-fact-commit-together
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))]
    (is (= :unpaired-refusal (defect [(custody/refused "holder-b" "p-1")]))
        "a refused fact without its rejection")
    (is (= :rejection-without-refusal (defect [(rejected "p-1")]))
        "a rejection without its refused fact")
    (is (= :rejection-without-refusal
           (defect [(rejected "p-1")] [(custody/refused "holder-b" "p-1")]))
        "the refused fact in a later record is too late")
    (is (= :unpaired-refusal
           (defect [(custody/refused "holder-b" "p-1") (rejected "p-1")]))
        "the refused fact follows its rejection")
    (is (= :unpaired-refusal
           (defect (conj (refusal "holder-b" "p-1")
                         (custody/refused "holder-c" "p-1"))))
        "a doubled refused fact")
    (is (= :duplicate-rejection
           (defect [(rejected "p-1") (rejected "p-1")
                    (custody/refused "holder-b" "p-1")]))
        "a doubled rejection")
    (is (= :unpaired-refusal
           (defect [(rejected "p-1") (custody/refused "holder-b" "p-2")]))
        "another proposal's refused fact")
    (is (= :malformed-fact
           (defect [(rejected "p-1") (custody/refused nil "p-1")]))
        "a nil proposer")
    (is (= :malformed-fact
           (defect [(rejected "p-1") (custody/refused "holder-b" nil)])))
    (is (= :malformed-fact
           (defect [(dissoc (rejected "p-1") :dao.lease/proposal)
                    (custody/refused "holder-b" "p-1")])))
    (is (= :malformed-fact
           (raw [] [[100 :dao.lease/status :dao.lease/rejected]
                    [100 :dao.lease/proposal "p-1"]
                    [100 :yin.k/proposer "holder-b"]
                    [101 :yin.k/custody :yin.k/refused]
                    [101 :yin.k/proposer "holder-b"]
                    [101 :dao.lease/proposal "p-1"]]))
        "the lease fact carries no proposer")))


(deftest a-grant-with-a-nil-proposal-answers-nothing
  (let [p (fold [(offered)]
                [(assoc (grant) :dao.lease/proposal nil) (bound)])]
    (is (nil? (::ledger/defect p)))
    (is (= {} (:answered p))
        "as dao.lease's judge records no answer for it")))

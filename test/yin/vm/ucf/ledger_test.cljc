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
                       :dao.space/t 2}}
           (:leases p)))
    (is (= #{:arbitration :next-t :next-e :targets :admitted :occurrences
             :leases}
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

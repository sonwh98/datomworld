(ns yin.vm.ucf.custody-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb")

(def ^:private occ "8f0c6a52-6a1e-4e43-9d55-3f0a4c1b2e01")


(defn- address
  [digit]
  (keyword "segment" (str "blake3-" (apply str (repeat 64 digit)))))


(def ^:private addr-1 (address "1"))

(def ^:private lease-id "lease-1")


(defn- grant
  ([] (grant "holder-a"))
  ([holder]
   {:dao.lease/status :dao.lease/accepted
    :dao.lease/lease lease-id
    :dao.lease/proposal "p-1"
    :dao.lease/subject {:yin.k/occurrence occ}
    :dao.lease/holder holder
    :dao.lease/duration {:s 30}}))


(defn- bound
  ([] (bound 0))
  ([epoch] (custody/bound occ lease-id "holder-a" epoch)))


(defn- record
  "The record of transaction t holding `facts`, ids from `e`, stamped
   as the transactor stamps."
  [t e facts]
  {:dao.space/transaction
   {:t t :datoms (mapv #(conj % t 1) (ledger/facts->datoms e facts))}})


(def ^:private offer
  (custody/offer occ addr-1 "carrier"
                 {:yin.k/kind :blocked :yin.k/occurrence occ}))


(defn- evidence
  [& records]
  (custody/binding-evidence arb records lease-id))


(defn- refusal
  [r]
  (::custody/no-evidence r))


(deftest the-occurrence-form
  (let [o (custody/mint-occurrence)]
    (is (custody/occurrence? o))
    (is (not= o (custody/mint-occurrence)) "distinct parks, distinct ids")
    (is (custody/occurrence? occ))
    (is (not (custody/occurrence? (str/upper-case occ)))
        "one canonical spelling, so equal ids have equal bytes")
    (is (not (custody/occurrence? "O")))
    (is (not (custody/occurrence? nil)))
    (is (not (custody/occurrence? {:yin.k/occurrence occ})))))


(deftest the-fact-constructors
  (is (= {:yin.k/custody :yin.k/offered
          :yin.k/occurrence occ
          :yin.k/id addr-1
          :yin.k/policy :yin.k/exclusive
          :yin.k/medium "carrier"
          :yin.k/baseline {:yin.k/kind :blocked :yin.k/occurrence occ}}
         offer))
  (is (= {:yin.k/custody :yin.k/bound
          :yin.k/occurrence occ
          :dao.lease/lease lease-id
          :dao.lease/holder "holder-a"
          :yin.k/epoch 0}
         (bound))))


(deftest a-binding-beside-its-grant-is-evidence
  (let [ok {:yin.k/transaction {:yin.k/arbitration arb :dao.space/t 1}
            :yin.k/occurrence occ
            :dao.lease/lease lease-id
            :dao.lease/holder "holder-a"
            :yin.k/epoch 0}
        records [[arb (record 0 16 [offer])]
                 [arb (record 1 17 [(grant) (bound)])]]]
    (is (= ok (apply evidence records)))
    (testing "identity is the arbitration identity and t"
      (is (= (assoc-in ok [:yin.k/transaction :dao.space/t] 7)
             (evidence [arb (record 7 30 [(grant) (bound)])]
                       [arb (record 1 17 [offer])]))
          "a reader's view may be partial and out of order")
      (is (= 7 (get-in (evidence [arb (record 7 30 [(grant) (bound)])])
                       [:yin.k/transaction :dao.space/t]))))
    (testing "evidence and its records survive the canonical codec"
      (is (= ok (cbor/decode (cbor/encode ok))))
      (is (= ok (apply evidence (cbor/decode (cbor/encode records))))))))


(deftest what-establishes-nothing
  (testing "a binding by another author"
    (is (= :no-binding
           (refusal (evidence [arb (record 0 16 [offer])]
                              ["mallory" (record 1 17 [(grant) (bound)])]))))
    (is (= 1 (get-in (evidence ["mallory" (record 1 17 [(grant) (bound)])]
                               [arb (record 1 17 [(grant) (bound)])])
                     [:yin.k/transaction :dao.space/t]))
        "a forgery beside the authority's binding changes nothing"))
  (testing "a binding in a record other than its grant's"
    (is (= :not-in-grant-transaction
           (refusal (evidence [arb (record 1 17 [(grant)])]
                              [arb (record 2 18 [(bound)])]))))
    (is (= :not-in-grant-transaction
           (refusal (evidence [arb (record 2 18 [(bound)])])))
        "a binding with no grant at all"))
  (testing "a binding duplicated for one lease"
    (is (= :duplicate-binding
           (refusal (evidence [arb (record 1 17 [(grant) (bound)])]
                              [arb (record 2 19 [(bound)])]))))
    (is (= :duplicate-binding
           (refusal (evidence [arb (record 1 17 [(grant) (bound) (bound)])])))))
  (testing "an epoch that is not an exact integer in range"
    (doseq [e [(cbor/float64 0) -1 4503599627370496 "0" nil]]
      (is (= :inexact-epoch
             (refusal (evidence [arb (record 1 17 [(grant) (bound e)])])))
          (str "epoch " (pr-str e))))
    (is (= 4503599627370495
           (:yin.k/epoch
             (evidence [arb (record 1 17 [(grant)
                                          (bound 4503599627370495)])])))
        "2^52-1 is in range"))
  (testing "a grant that does not match its binding"
    (is (= :grant-mismatch
           (refusal (evidence [arb (record 1 17 [(grant "holder-b")
                                                 (bound)])]))))
    (is (= :grant-mismatch
           (refusal (evidence
                      [arb (record 1 17
                                   [(assoc (grant) :dao.lease/subject
                                           {:yin.k/occurrence "other"})
                                    (bound)])])))))
  (testing "no binding at all"
    (is (= :no-binding (refusal (evidence [arb (record 1 17 [(grant)])])))))
  (testing "a malformed record from the authority fails closed"
    (is (= :malformed-record
           (refusal (evidence [arb (record 1 17 [(grant) (bound)])]
                              [arb :x]))))))

(ns yin.vm.ucf.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
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

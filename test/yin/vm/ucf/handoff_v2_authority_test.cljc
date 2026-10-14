(ns yin.vm.ucf.handoff-v2-authority-test
  "Direct version-2 inspector coverage: fork operation ids are refused.
   Authority paths use row packs and are covered by authority.grant-test,
   authority.completion-test and compose-rows-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as jing.cbor]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.v2-support :as s]))


(deftest a-fork-tree-refuses-a-carried-operation-id
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked _] (s/parked-writer engine)
            export (s/lift parked t peer nil #{:reader :writer})
            body (s/decoded export)
            with-id (assoc-in body [:yin.k/frames 0 :yin.k/pending
                                    :yin.k/op-id]
                              {:yin.k/occurrence s/predecessor
                               :yin.k/seq 0})
            bytes (jing.cbor/encode with-id)
            r (checkpoint/inspect (s/segment-address bytes) bytes)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :yin.k/undecodable (:yin.k/status r)) (pr-str r))
        (is (= :fork-op-id (:yin.k/kind r)))))))


(deftest the-old-inspector-distinguishes-fork-and-exclusive-baselines
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked _] (s/parked-reader engine)
            fork (s/lift parked t peer)
            exclusive (s/lift parked t peer (s/header 0 nil #{}))
            bf (checkpoint/inspect (:address fork) (:bytes fork))
            be (checkpoint/inspect (:address exclusive) (:bytes exclusive))]
        (is (= :ok (:status fork)) (pr-str fork))
        (is (= :ok (:status exclusive)) (pr-str exclusive))
        (is (true? (:yin.k/fork bf)) "the baseline names the role")
        (is (empty? (:yin.k/ops bf)) "a fork carries no operation id")
        (is (not (contains? bf :yin.k/occurrence)))
        (is (not (contains? be :yin.k/fork)))
        (is (= s/occurrence (:yin.k/occurrence be)))))))


(deftest the-old-inspector-preserves-a-halted-results-origin
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            fork (s/lift (s/halted-with engine (s/lit 42)) t peer)
            exclusive (s/lift (s/halted-with engine (s/lit 42)) t peer
                              {:yin.k/origin s/origin
                               :yin.k/occurrence s/occurrence
                               :yin.k/arbitration s/arbitration
                               :yin.k/next-op-seq 3
                               :yin.k/enrolled #{}})
            bf (checkpoint/inspect (:address fork) (:bytes fork))
            be (checkpoint/inspect (:address exclusive) (:bytes exclusive))]
        (is (= :halted (:yin.k/kind bf)))
        (is (true? (:yin.k/fork bf)))
        (is (= :halted (:yin.k/kind be)))
        (is (= s/origin (:yin.k/origin be)))))))

(ns yin.vm.ucf.handoff-v2-authority-test
  "UCF version 2, section 3 and 10: roles are structural.  The inspector
   answers a version-2 fork a baseline that says so and carries no
   custody operation id; the authority refuses that fork as a custody
   checkpoint without adding header keys, and admits the version-2
   exclusive body of the same task.  A halted fork has no origin, so an
   authority refuses its report as well."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as jing.cbor]
            [dao.jing.mem :as mem]
            [dao.stream.journal :as journal]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.v2-support :as s]))


(defn- authority-for
  [arbitration-identity]
  (let [frames (atom [(jing.cbor/encode
                        {:dao.stream.journal/header
                         {:version 1 :identity arbitration-identity}})])]
    (::authority/authority
      (authority/open! (journal/memory-backend frames nil)))))


(deftest an-authority-refuses-a-fork-as-a-custody-checkpoint
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked _] (s/parked-reader engine)
            fork (s/lift parked t peer)
            exclusive (s/lift parked t peer (s/header 0 nil #{}))
            a (authority-for (get-in s/arbitration [:dao.stream/identity]))
            store (mem/create-content-mem)
            offered (fn [export]
                      (grant/offer! a store (:address export)
                                    (:bytes export) "carrier"))
            bf (checkpoint/inspect (:address fork) (:bytes fork))
            be (checkpoint/inspect (:address exclusive) (:bytes exclusive))]
        (is (= :ok (:status fork)) (pr-str fork))
        (is (= :ok (:status exclusive)) (pr-str exclusive))
        (is (true? (:yin.k/fork bf)) "the baseline names the role")
        (is (empty? (:yin.k/ops bf)) "a fork carries no operation id")
        (is (not (contains? bf :yin.k/occurrence)))
        (is (not (contains? be :yin.k/fork)))
        (is (= s/occurrence (:yin.k/occurrence be)))
        (is (= {:yin.k/status :refused :yin.k/reason :not-offerable}
               (select-keys (offered fork) [:yin.k/status :yin.k/reason]))
            "no header key is ever added to make a fork admissible")
        (is (= :committed (:yin.k/status (offered exclusive)))
            (pr-str (offered exclusive)))))))


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


(deftest a-halted-fork-result-is-no-reportable-origin-result
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
            be (checkpoint/inspect (:address exclusive) (:bytes exclusive))
            a (authority-for "arbitration-1")]
        (is (= :halted (:yin.k/kind bf)))
        (is (true? (:yin.k/fork bf)))
        (is (= :halted (:yin.k/kind be)))
        (is (= s/origin (:yin.k/origin be)))
        (is (= :refused
               (:yin.k/status
                 (completion/report! a "holder-x"
                                     (completion/resumed s/occurrence
                                                         "lease-1"
                                                         (:address fork))
                                     (:bytes fork)))))))))

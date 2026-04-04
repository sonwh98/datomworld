(ns datomworld.continuation-transport-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [datomworld.continuation-transport :as ct]))


(deftest enqueue-k-logs-summary-and-stores-payload-off-stream-test
  (testing
    "enqueue writes summary datom to transport and stores full k payload off-stream"
    (let [summary {:kind :k,
                   :from :register-vm,
                   :to :stack-vm,
                   :control 42,
                   :k-depth 3}
          k {:control 7,
             :bytecode [1 2 3],
             :stack [:a],
             :env {:x 1},
             :extra :ignored}
          state (ct/init-state [:register-vm :stack-vm])
          next-state (ct/enqueue-k state summary k)
          datoms (vec (ds/->seq nil (:k-stream next-state)))
          [_e a v _t _m] (first datoms)]
      (is (= 1 (count datoms)))
      (is (= :stream/k a))
      (is (= summary v))
      (is (not (contains? v :k))
          "k payload should not be embedded in stream datom")
      (is (= 1 (count (:pending-ks next-state))))
      (is (= k (get-in next-state [:pending-ks 0]))))))


(deftest consume-k-advances-cursor-even-when-no-match-test
  (testing "consume advances cursor past non-matching k entries"
    (let [summary {:from :register-vm,
                   :to :stack-vm,
                   :control 9,
                   :k-depth 1}
          pending {:control 100}
          state (ct/enqueue-k (ct/init-state [:register-vm
                                              :stack-vm])
                              summary
                              pending)
          [next-state message] (ct/consume-k-for state :register-vm)]
      (is (nil? message))
      (is (= 1 (get-in next-state [:cursors :register-vm :position])))
      (is (= 1 (count (:k-stream next-state)))
          "no deliver datom should be appended when recipient does not match")
      (is (= pending (get-in next-state [:pending-ks 0]))))))


(deftest consume-k-delivers-and-clears-pending-test
  (testing
    "consume returns k, appends deliver event, and clears pending payload"
    (let [summary {:from :stack-vm,
                   :to :register-vm,
                   :control 13,
                   :k-depth 2}
          pending {:control 88, :regs [1 2]}
          state (ct/enqueue-k (ct/init-state [:register-vm
                                              :stack-vm])
                              summary
                              pending)
          [next-state message] (ct/consume-k-for state :register-vm)
          datoms (vec (ds/->seq nil (:k-stream next-state)))
          [_e a v _t _m] (last datoms)]
      (is (= {:from :stack-vm,
              :to :register-vm,
              :summary summary,
              :k pending}
             message))
      (is (= 1 (get-in next-state [:cursors :register-vm :position])))
      (is (empty? (:pending-ks next-state)))
      (is (= 2 (count datoms)))
      (is (= :stream/deliver a))
      (is (= 0 (:message-pos v)))
      (is (= :register-vm (:to v)))
      (is (= 13 (:control v))))))

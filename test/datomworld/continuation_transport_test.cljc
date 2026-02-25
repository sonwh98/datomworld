(ns datomworld.continuation-transport-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [datomworld.continuation-transport :as ct]))


(deftest enqueue-continuation-logs-summary-and-stores-payload-off-stream-test
  (testing
    "enqueue writes summary datom to transport and stores full continuation off-stream"
    (let [summary {:kind :continuation,
                   :from :register-vm,
                   :to :stack-vm,
                   :control 42,
                   :continuation-depth 3}
          continuation {:pc 7,
                        :bytecode [1 2 3],
                        :stack [:a],
                        :env {:x 1},
                        :extra :ignored}
          state (ct/init-state [:register-vm :stack-vm])
          next-state (ct/enqueue-continuation state summary continuation)
          datoms (vec (ds/->seq nil (:continuation-stream next-state)))
          [_e a v _t _m] (first datoms)]
      (is (= 1 (count datoms)))
      (is (= :stream/continuation a))
      (is (= summary v))
      (is (not (contains? v :continuation))
          "continuation payload should not be embedded in stream datom")
      (is (= 1 (count (:pending-continuations next-state))))
      (is (= continuation (get-in next-state [:pending-continuations 0]))))))


(deftest consume-continuation-advances-cursor-even-when-no-match-test
  (testing "consume advances cursor past non-matching continuation entries"
    (let [summary {:from :register-vm,
                   :to :stack-vm,
                   :control 9,
                   :continuation-depth 1}
          pending {:pc 100}
          state (ct/enqueue-continuation (ct/init-state [:register-vm
                                                         :stack-vm])
                                         summary
                                         pending)
          [next-state message] (ct/consume-continuation-for state :register-vm)]
      (is (nil? message))
      (is (= 1 (get-in next-state [:cursors :register-vm :position])))
      (is (= 1 (ds/length nil (:continuation-stream next-state)))
          "no deliver datom should be appended when recipient does not match")
      (is (= pending (get-in next-state [:pending-continuations 0]))))))


(deftest consume-continuation-delivers-and-clears-pending-test
  (testing
    "consume returns continuation, appends deliver event, and clears pending payload"
    (let [summary {:from :stack-vm,
                   :to :register-vm,
                   :control 13,
                   :continuation-depth 2}
          pending {:pc 88, :regs [1 2]}
          state (ct/enqueue-continuation (ct/init-state [:register-vm
                                                         :stack-vm])
                                         summary
                                         pending)
          [next-state message] (ct/consume-continuation-for state :register-vm)
          datoms (vec (ds/->seq nil (:continuation-stream next-state)))
          [_e a v _t _m] (last datoms)]
      (is (= {:from :stack-vm,
              :to :register-vm,
              :summary summary,
              :continuation pending}
             message))
      (is (= 1 (get-in next-state [:cursors :register-vm :position])))
      (is (empty? (:pending-continuations next-state)))
      (is (= 2 (count datoms)))
      (is (= :stream/deliver a))
      (is (= 0 (:message-pos v)))
      (is (= :register-vm (:to v)))
      (is (= 13 (:control v))))))

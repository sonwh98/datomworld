(ns datomworld.demo.continuation-handoff-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]
    [datomworld.demo.continuation-handoff :as handoff]
    [yin.vm.register :as register]
    [yin.vm.stack :as stack]))


(deftest register-handoff-preserves-ingress-state-test
  (testing "register vm handoff payload round-trips the ingress stream"
    (let [in-stream (ds/open! {:type :ringbuffer
                               :capacity nil})
          vm (assoc (register/create-vm {:in-stream in-stream})
                    :in-cursor {:position 7}
                    :regs [:r0 :r1]
                    :k {:type :frame, :next nil}
                    :env {'x 1}
                    :control 12
                    :bytecode [[:const 0 1]]
                    :pool [:value]
                    :halted? false
                    :value :ok
                    :store {:s 1}
                    :parked {:p {:type :parked-continuation}}
                    :id-counter 99
                    :blocked? true
                    :run-queue [:resume]
                    :wait-set [:wait])
          payload (handoff/vm-state->handoff :register-vm vm)
          resumed (handoff/handoff->vm-state :register-vm payload)]
      (is (identical? in-stream (:in-stream resumed)))
      (is (= {:position 7} (:in-cursor resumed)))
      (is (= [:r0 :r1] (:regs resumed)))
      (is (= 12 (:control resumed)))
      (is (= [[:const 0 1]] (:bytecode resumed)))
      (is (= {:s 1} (:store resumed)))
      (is (= [:resume] (:run-queue resumed)))
      (is (false? (:halted? resumed)))
      (is (true? (:blocked? resumed))))))


(deftest stack-handoff-preserves-ingress-state-test
  (testing "stack vm handoff payload round-trips the ingress stream"
    (let [in-stream (ds/open! {:type :ringbuffer
                               :capacity nil})
          vm (assoc (stack/create-vm {:in-stream in-stream})
                    :in-cursor {:position 11}
                    :stack [:arg :fn]
                    :env {'y 2}
                    :k {:control 3, :next nil}
                    :control 5
                    :bytecode [[:const 1]]
                    :pool [:const]
                    :halted? false
                    :value 42
                    :store {:s 2}
                    :parked {:p {:type :parked-continuation}}
                    :id-counter 17
                    :blocked? false
                    :run-queue [:resume]
                    :wait-set [:wait])
          payload (handoff/vm-state->handoff :stack-vm vm)
          resumed (handoff/handoff->vm-state :stack-vm payload)]
      (is (identical? in-stream (:in-stream resumed)))
      (is (= {:position 11} (:in-cursor resumed)))
      (is (= [:arg :fn] (:stack resumed)))
      (is (= 5 (:control resumed)))
      (is (= [[:const 1]] (:bytecode resumed)))
      (is (= {:s 2} (:store resumed)))
      (is (= [:resume] (:run-queue resumed)))
      (is (false? (:halted? resumed)))
      (is (false? (:blocked? resumed))))))

(ns yin.vm.ffi-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as ds]
            [dao.stream.apply :as dao.stream.apply]
            [dao.stream.ringbuffer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.ffi :as ffi]))


(deftest normalize-test
  (testing "normalize converts nil, handler maps, or explicit bridge maps"
    (is (nil? (ffi/normalize nil)))
    (is (= {:handlers {:math/add +}, :cursor {:position 0}}
           (ffi/normalize {:math/add +})))
    (is (= {:handlers {:math/add +}, :cursor {:position 5}}
           (ffi/normalize {:handlers {:math/add +}, :cursor {:position 5}})))
    (is (= {:handlers {}, :cursor {:position 0}}
           (ffi/normalize {:handlers nil}))))
  (testing "normalize throws on invalid bridge descriptors"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Bridge must be"
          (ffi/normalize "invalid-bridge")))))


(deftest bridge-from-opts-and-attach-test
  (testing "bridge-from-opts extracts and normalizes :bridge from opts"
    (is (= {:handlers {:inc inc}, :cursor {:position 0}}
           (ffi/bridge-from-opts {:bridge {:inc inc}})))
    (is (nil? (ffi/bridge-from-opts {}))))
  (testing "attach associates explicit bridge state onto a VM"
    (let [vm (ast-walker/create-vm)
          attached (ffi/attach vm {:inc inc})]
      (is (true? (ffi/installed? attached)))
      (is (= {:handlers {:inc inc}, :cursor {:position 0}}
             (:bridge attached)))))
  (testing "installed? returns false for unattached VMs or empty handlers"
    (let [vm (ast-walker/create-vm)]
      (is (false? (ffi/installed? vm)))
      (is (false? (ffi/installed? (ffi/attach vm {})))))))


(deftest bridge-step-and-run-test
  (testing
    "bridge-step handles call-in requests and dispatches to registered handlers"
    (let [call-in (ds/open! {:type :ringbuffer, :capacity 10})
          call-out (ds/open! {:type :ringbuffer, :capacity 10})
          req-id "req-1"
          req (dao.stream.apply/request req-id :math/add [10 20])
          _ (ds/append! call-in req)
          vm (-> (ast-walker/create-vm)
                 (assoc :store {vm/call-in-stream-key call-in,
                                vm/call-out-stream-key call-out,
                                vm/call-out-cursor-key {:position 0}})
                 (assoc :parked {req-id {:id req-id, :continuation :dummy}})
                 (ffi/attach {:math/add +}))
          step-res (ffi/bridge-step vm)]
      (is (true? (:handled? step-res)))
      (is (= 1 (:entry-count step-res)))
      (let [out-datom (:ok (ds/next call-out {:position 0}))]
        (is (some? out-datom))
        (is (= req-id (:dao.stream.apply/id out-datom)))
        (is (= 30 (:dao.stream.apply/value out-datom))))))
  (testing
    "bridge-step returns handled? false when no call-in requests are pending"
    (let [call-in (ds/open! {:type :ringbuffer, :capacity 10})
          call-out (ds/open! {:type :ringbuffer, :capacity 10})
          vm (-> (ast-walker/create-vm)
                 (assoc :store {vm/call-in-stream-key call-in,
                                vm/call-out-stream-key call-out,
                                vm/call-out-cursor-key {:position 0}})
                 (ffi/attach {:math/add +}))
          step-res (ffi/bridge-step vm)]
      (is (false? (:handled? step-res)))
      (is (= :blocked (:stream-result step-res)))))
  (testing "bridge-step throws when dispatching an unregistered op"
    (let [call-in (ds/open! {:type :ringbuffer, :capacity 10})
          call-out (ds/open! {:type :ringbuffer, :capacity 10})
          req (dao.stream.apply/request "req-2" :unknown/op [])
          _ (ds/append! call-in req)
          vm (-> (ast-walker/create-vm)
                 (assoc :store {vm/call-in-stream-key call-in,
                                vm/call-out-stream-key call-out,
                                vm/call-out-cursor-key {:position 0}})
                 (ffi/attach {:math/add +}))]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"No bridge handler"
            (ffi/bridge-step vm)))))
  (testing
    "bridge-step throws synthesizing a resume entry for a call-id with no
     parked continuation and no natural stream wake"
    (let [call-in (ds/open! {:type :ringbuffer, :capacity 10})
          call-out (ds/open! {:type :ringbuffer, :capacity 10})
          req-id "req-3"
          req (dao.stream.apply/request req-id :math/add [1 2])
          _ (ds/append! call-in req)
          vm (-> (ast-walker/create-vm)
                 (assoc :store {vm/call-in-stream-key call-in,
                                vm/call-out-stream-key call-out,
                                vm/call-out-cursor-key {:position 0}})
                 (ffi/attach {:math/add +}))]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"Cannot synthesize"
            (ffi/bridge-step vm))))))


(deftest maybe-run-and-run-with-bridge-test
  (testing "maybe-run delegates directly when no bridge is installed"
    (let [vm (ast-walker/create-vm)
          ran (atom false)
          res (ffi/maybe-run vm (fn [v] (reset! ran true) v))]
      (is (true? @ran))
      (is (= vm res))))
  (testing
    "run-with-bridge on a VM that never blocks: attaches, runs, extracts value"
    (let [vm (vm/eval (ast-walker/create-vm) {:type :literal, :value 42})
          res (ffi/run-with-bridge vm {:inc inc})]
      (is (= 42 res))))
  (testing
    "run-with-bridge drives a real bridge call-in/call-out roundtrip: the VM
     parks on :dao.stream.apply/call, run-with-bridge dispatches the request
     through the attached handler, and returns the final resumed value"
    (let [ast {:type :dao.stream.apply/call,
               :op :math/add,
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          parked-vm (vm/eval (ast-walker/create-vm) ast)]
      (is
        (true? (vm/blocked? parked-vm))
        "sanity check: evaluating the call node parks the VM before the
           bridge handler ever runs")
      (is (= 30 (ffi/run-with-bridge parked-vm {:math/add +}))))))

(ns datomworld.demo.vm-state-keys-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datomworld.demo.continuation-stream :as continuation-stream]
    [yin.vm.register :as register]
    [yin.vm.stack :as stack]))


(deftest continuation-stream-cesk-carries-lifecycle-flags-test
  (testing "vm->cesk projects halted? and blocked? for stack and register VMs"
    (let [stack-state (assoc (stack/create-vm)
                             :control 7
                             :halted? false
                             :blocked? true
                             :stack [:arg :fn]
                             :value :ok)
          register-state (assoc (register/create-vm)
                                :control 11
                                :halted? true
                                :blocked? false
                                :regs [:r0]
                                :value :done)
          stack-cesk (continuation-stream/vm->cesk :stack-vm stack-state [] {7 0})
          register-cesk (continuation-stream/vm->cesk :register-vm register-state [] {11 0})]
      (is (false? (get-in stack-cesk [:control :halted?])))
      (is (true? (get-in stack-cesk [:control :blocked?])))
      (is (true? (get-in register-cesk [:control :halted?])))
      (is (false? (get-in register-cesk [:control :blocked?]))))))


(deftest continuation-stream-window-uses-halted?-status-test
  (testing "vm-window status follows halted? rather than legacy halted"
    (let [original @continuation-stream/app-state]
      (try
        (reset! continuation-stream/app-state
                {:owner nil
                 :stack-asm []
                 :stack-source-map {}
                 :stack-vm (assoc (stack/create-vm)
                                  :control 3
                                  :halted? true
                                  :halted false
                                  :blocked? false)})
        (let [view (continuation-stream/vm-window :stack-vm "Stack VM" "#fff")]
          (is (= "Halted" (get-in view [2 3 2]))))
        (finally
          (reset! continuation-stream/app-state original))))))

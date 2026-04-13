(ns datomworld.demo.continuation-handoff
  (:require
    [yin.vm :as vm]
    [yin.vm.register :as register]
    [yin.vm.stack :as stack]))


(def register-handoff-keys
  [:in-stream :in-cursor
   :regs :k :env :control :bytecode :pool :halted? :value :store :parked
   :id-counter
   :blocked? :run-queue :wait-set])


(def stack-handoff-keys
  [:in-stream :in-cursor
   :control :bytecode :stack :env :k :pool :halted? :value :store :parked
   :id-counter :blocked? :run-queue :wait-set])


(defn stack-vm?
  [vm-key]
  (= vm-key :stack-vm))


(defn vm-state->handoff
  [vm-key vm-state]
  (let [keys* (if (stack-vm? vm-key)
                stack-handoff-keys
                register-handoff-keys)]
    (select-keys vm-state keys*)))


(defn handoff->vm-state
  [vm-key payload]
  (let [base (if (stack-vm? vm-key)
               (stack/create-vm)
               (register/create-vm))
        resumed (reduce-kv (fn [acc k v] (assoc acc k v)) base payload)]
    (assoc resumed :primitives vm/primitives)))

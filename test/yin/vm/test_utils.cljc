(ns yin.vm.test-utils
  (:require [dao.stream :as ds]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.register :as register]
            [yin.vm.semantic :as semantic]
            [yin.vm.stack :as stack]))


(defn vm-factories
  "Returns a map of VM keywords to their creation functions."
  []
  {:ast-walker ast-walker/create-vm,
   :semantic semantic/create-vm,
   :register register/create-vm,
   :stack stack/create-vm})


(defn run-all-vms
  "Evaluates an AST across all VM backends and returns a map of their final values.
   Useful for parity testing."
  [ast]
  (into {}
        (for [[vm-type create-vm] (vm-factories)]
          [vm-type (vm/value (vm/eval (create-vm) ast))])))


(defn queue-vm
  "Convenience for 'pushing' a program into a VM's ingress stream."
  [vm-state datoms]
  (let [in-stream (ds/open! {:type :ringbuffer, :capacity nil})]
    (ds/append! in-stream (vec datoms))
    (assoc vm-state
           :in-stream in-stream
           :in-cursor {:position 0}
           :halted? false)))


(defn queue-ast
  "Compiles AST to datoms and queues them for evaluation."
  [vm-state ast]
  (queue-vm vm-state (vm/ast->datoms ast)))

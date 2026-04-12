(ns yin.vm.stream-listen-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]
    [yin.vm :as vm]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(defn- ast->program
  [ast]
  (let [datoms (vec (vm/ast->datoms ast))
        root-id (apply max (map first datoms))]
    {:node root-id, :datoms datoms}))


(defn- datom-vms
  []
  {:semantic semantic/create-vm
   :register register/create-vm
   :stack stack/create-vm})


(def define-x-program
  (ast->program
    {:type :application
     :operator {:type :variable, :name 'yin/def}
     :operands [{:type :literal, :value 'x}
                {:type :literal, :value 41}]}))


(def read-x-program
  (ast->program
    {:type :application
     :operator {:type :variable, :name '+}
     :operands [{:type :variable, :name 'x}
                {:type :literal, :value 1}]}))


(deftest datom-vms-run-programs-produced-on-ingress-stream-test
  (testing "semantic/register/stack consume datom programs directly from :in-stream"
    (doseq [[vm-type create-vm] (datom-vms)]
      (let [vm0 (create-vm)
            in-stream (:in-stream vm0)]
        (is (some? in-stream) (str vm-type " should expose :in-stream"))
        (ds/put! in-stream define-x-program)
        (ds/put! in-stream read-x-program)
        (let [result (vm/run vm0)]
          (is (= 42 (vm/value result))
              (str vm-type " should preserve store across sequential ingress programs"))
          (is (= {:position 2} (:in-cursor result))
              (str vm-type " should advance ingress cursor after both batches")))))))


(deftest datom-vms-stop-cleanly-when-ingress-stream-ends-test
  (testing "closing :in-stream lets run return the last final VM state"
    (doseq [[vm-type create-vm] (datom-vms)]
      (let [vm0 (create-vm)
            in-stream (:in-stream vm0)]
        (ds/put! in-stream define-x-program)
        (ds/close! in-stream)
        (let [result (vm/run vm0)]
          (is (= 41 (vm/value result))
              (str vm-type " should return the value of the final ingested program"))
          (is (vm/halted? result)
              (str vm-type " should halt once ingress reaches :end"))
          (is (= {:position 1} (:in-cursor result))
              (str vm-type " should advance ingress cursor before stream end")))))))

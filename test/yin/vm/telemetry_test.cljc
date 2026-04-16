(ns yin.vm.telemetry-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]
    #?(:cljs [yin.vm.wasm :as wasm])))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(defn- stream-values
  [stream]
  (vec (ds/->seq nil stream)))


(defn- fact?
  [datoms attr value]
  (boolean (some #(and (= attr (nth % 1))
                       (= value (nth % 2)))
                 datoms)))


(defn- fact-values
  [datoms attr]
  (mapv #(nth % 2)
        (filter #(= attr (nth % 1)) datoms)))


(defn- sample-ast
  []
  {:type :application
   :operator {:type :variable, :name '+}
   :operands [{:type :literal, :value 20}
              {:type :literal, :value 22}]})


(deftest create-vm-emits-init-snapshot-test
  (let [telemetry-stream (make-stream)
        _ (ast-walker/create-vm {:telemetry {:stream telemetry-stream
                                             :vm-id :vm/init}})
        datoms (stream-values telemetry-stream)]
    (is (fact? datoms :vm/phase :init))
    (is (fact? datoms :vm/model :ast-walker))
    (is (fact? datoms :vm/vm-id :vm/init))
    (is (fact? datoms :vm/event :init))))


(deftest eval-emits-step-and-halt-snapshots-across-cljc-vms-test
  (let [constructors {:ast-walker ast-walker/create-vm
                      :semantic semantic/create-vm
                      :stack stack/create-vm
                      :register register/create-vm}]
    (doseq [[model create-vm] constructors]
      (testing (str "Telemetry covers " model)
        (let [telemetry-stream (make-stream)
              vm-result (vm/eval (create-vm {:telemetry {:stream telemetry-stream
                                                         :vm-id model}})
                                 (sample-ast))
              datoms (stream-values telemetry-stream)]
          (is (= 42 (vm/value vm-result)))
          (is (fact? datoms :vm/model model))
          (is (fact? datoms :vm/phase :step))
          (is (fact? datoms :vm/phase :halt)))))))


(deftest telemetry-summaries-hide-raw-host-values-test
  (let [telemetry-stream (make-stream)
        host-fn (fn [x] x)
        vm-instance (ast-walker/create-vm {:env {'host-fn host-fn}
                                           :telemetry {:stream telemetry-stream
                                                       :vm-id :vm/host-values}})
        datoms (stream-values telemetry-stream)
        raw-values (map #(nth % 2) datoms)
        store-values (vals (vm/store vm-instance))]
    (is (fact? datoms :vm.summary/type :host-fn))
    (is (not-any? #(identical? host-fn %) raw-values))
    (is (not-any? (fn [stream-like]
                    (some #(identical? stream-like %) raw-values))
                  (filter #(satisfies? ds/IDaoStreamReader %) store-values)))))


(deftest bridge-step-emits-bridge-phase-and-op-test
  (let [telemetry-stream (make-stream)
        vm-result (vm/eval
                    (ast-walker/create-vm
                      {:bridge {:op/echo (fn [& args] (first args))}
                       :telemetry {:stream telemetry-stream
                                   :vm-id :vm/bridge}})
                    {:type :dao.stream.apply/call
                     :op :op/echo
                     :operands [{:type :literal, :value 7}]})
        datoms (stream-values telemetry-stream)]
    (is (= 7 (vm/value vm-result)))
    (is (fact? datoms :vm/phase :bridge))
    (is (fact? datoms :vm/bridge-op :op/echo))
    (is (= [[:number]]
           (filter vector? (fact-values datoms :vm/arg-shape))))))


#?(:cljs
   (deftest wasm-eval-emits-telemetry-test
     (let [telemetry-stream (make-stream)
           vm-result (vm/eval (wasm/create-vm {:telemetry {:stream telemetry-stream
                                                           :vm-id :vm/wasm}})
                              {:type :literal, :value 42})
           datoms (stream-values telemetry-stream)]
       (is (= 42 (vm/value vm-result)))
       (is (fact? datoms :vm/model :wasm))
       (is (fact? datoms :vm/phase :step))
       (is (fact? datoms :vm/phase :halt)))))

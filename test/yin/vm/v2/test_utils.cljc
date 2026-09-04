(ns yin.vm.v2.test-utils
  "Composition helpers for the v2 VM suite.

   Every v2 test supplies `:make-stream`: the VM has no default transport, so
   a test that omits it gets a VM that cannot create streams. The ring buffer
   is the composition's choice here, not the VM's."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ringbuffer]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]))


(def default-capacity 64)


(defn make-stream
  "A `:make-stream` over the v2 ring buffer. A nil capacity is the VM's
   default, not an unbounded stream: v2 has no unbounded mode."
  [capacity]
  (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type,
                       ringbuffer/capacity-key (or capacity
                                                   vm/default-stream-capacity)}))


(defn new-stream
  "Create one ring buffer handle or throw."
  ([] (new-stream default-capacity))
  ([capacity]
   (let [result (make-stream capacity)]
     (if (= :dao.stream/ok (:dao.stream/outcome result))
       (:dao.stream/handle result)
       (throw (ex-info "Test stream creation failed" {:result result}))))))


(defn create-vm
  "An ast-walker VM wired to the ring buffer."
  ([] (create-vm {}))
  ([opts] (ast-walker/create-vm (merge {:make-stream make-stream} opts))))


(defn queue-ast!
  "Append one AST program batch to the VM's ingress stream, creating and
   minting against one when the VM has none."
  [vm ast]
  (let [in-stream (or (:in-stream vm) (new-stream))
        cursor (or (:in-cursor vm) (vm/mint-oldest in-stream :in-stream))]
    (stream/append! in-stream (vec (vm/ast->datoms ast)))
    (assoc vm
           :in-stream in-stream
           :in-cursor cursor
           :halted? false)))


(defn compile-and-run
  "Run one AST through a fresh VM over its ingress stream and return the
   value."
  [ast]
  (-> (create-vm)
      (queue-ast! ast)
      (vm/run)
      (vm/value)))

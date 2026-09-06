(ns yin.vm.v2.test-utils
  "Composition helpers for the v2 VM suite.

   Every v2 test supplies `:make-stream`: the VM has no default transport, so
   a test that omits it gets a VM that cannot create streams. The ring buffer
   is the composition's choice here, not the VM's.

   Stream-driven tests thread an observer session — a program medium the test
   owns, a unary attacher bound to it, an attached observer, and the VM —
   which is the composition the REPL uses, at test scale. The VM holds no
   program stream of its own."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ringbuffer]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.engine :as engine]
            [yin.vm.v2.stream-observer :as observer]))


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


(defn- run-vm
  "Run the VM through its protocol entry point, as a plain function so it can
   be handed to observer coordination on every host."
  [vm]
  (vm/run vm))


(defn make-observer-session
  "One program medium, an observer attached through the ring buffer's own
   unary attacher, and a VM: the session `run-on-stream` coordinates.
   Readiness, loading, and running are the ast-walker's own functions."
  ([] (make-observer-session (create-vm) default-capacity))
  ([vm] (make-observer-session vm default-capacity))
  ([vm capacity]
   (let [handle (new-stream capacity)
         descriptor (:dao.stream/descriptor (stream/descriptor handle))
         attach! (ringbuffer/make-attacher
                   {(:dao.stream/identity descriptor) handle})]
     {:observer (observer/attach attach! descriptor)
      :vm vm})))


(defn queue-ast!
  "Append one AST program batch to the session's program medium."
  [session ast]
  (stream/append! (:stream (:observer session)) (vec (vm/ast->datoms ast)))
  session)


(defn run-session
  "Drive one session through observer coordination with the ast-walker's own
   readiness predicate, loader, and runner."
  [session]
  (observer/run-on-stream session
                          engine/ready-for-ingress?
                          ast-walker/vm-load-program
                          run-vm))


(defn compile-and-run
  "Run one AST through a fresh observer session and return the value."
  [ast]
  (-> (make-observer-session)
      (queue-ast! ast)
      run-session
      :vm
      vm/value))

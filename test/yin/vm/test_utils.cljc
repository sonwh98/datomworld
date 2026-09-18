(ns yin.vm.test-utils
  "Composition helpers for the v2 VM suite.

   Every v2 test supplies `:make-stream`: the VM has no default transport, so
   a test that omits it gets a VM that cannot create streams. The ring buffer
   is the composition's choice here, not the VM's.

   Stream-driven tests thread an observer session — a medium the test owns, a
   unary attacher bound to it, an attached observer, and a consumer — which
   is the composition the REPL uses, at test scale. The semantic VM's
   composition has two such stages over two media
   (`make-encoder-session`). The VM holds no program stream of its own."
  (:require [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]))


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


(defn make-attachment
  "One owned medium, its descriptor-bound unary attacher, and an attached
   observer: the piece every stream-driven session composes."
  [capacity]
  (let [handle (new-stream capacity)
        descriptor (:dao.stream/descriptor (stream/descriptor handle))
        attach! (ringbuffer/make-attacher
                  {(:dao.stream/identity descriptor) handle})]
    {:writer handle
     :observer (observer/attach attach! descriptor)}))


(defn make-observer-session
  "One medium, an observer attached through the ring buffer's own unary
   attacher, and a VM: the session `run-on-stream` coordinates. Readiness,
   loading, and running are the ast-walker's own functions."
  ([] (make-observer-session (create-vm) default-capacity))
  ([vm] (make-observer-session vm default-capacity))
  ([vm capacity]
   {:observer (:observer (make-attachment capacity))
    :consumer vm}))


(defn queue-ast!
  "Append one AST program batch to the session's program medium."
  [session ast]
  (stream/append! (:stream (:observer session)) (vec (vm/ast->datoms ast)))
  session)


(defn queue-rows!
  "Append one AST program batch to the session's medium as its canonical
   row set (§6.1): the row-lane twin of `queue-ast!`."
  [session ast]
  (stream/append! (:stream (:observer session)) (vm/ast->semantic-bytecode ast))
  session)


(defn make-encoder-session
  "The semantic composition at test scale — the topology the REPL runs (§7.1,
   §9.1): the encoder observer over the program medium, forwarding each
   projected batch to the row medium, and the evaluator observer the VM
   attaches to that row medium independently. `:program` takes map-AST or
   datom batches, `:rows` row sets; `:encoder` and `:evaluator` are the two
   `run-on-stream` sessions."
  ([vm] (make-encoder-session vm default-capacity))
  ([vm capacity]
   (let [program (make-attachment capacity)
         rows (make-attachment capacity)]
     {:program (:writer program)
      :encoder {:observer (:observer program), :consumer (:writer rows)}
      :rows (:writer rows)
      :evaluator {:observer (:observer rows), :consumer vm}})))


(defn run-encoder-session
  "Drive both stages of `make-encoder-session`'s composition in order: the
   encoder projects and forwards every program batch, then the evaluator
   observer loads and runs what arrived on the row medium."
  ([session] (run-encoder-session session (linearize/rows-loader semantic/load-vector)))
  ([session load-program]
   (assoc session
          :encoder (encoder/forward-on-stream (:encoder session))
          :evaluator (observer/run-on-stream (:evaluator session)
                                             engine/ready-for-ingress?
                                             load-program
                                             run-vm))))


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
      :consumer
      vm/value))

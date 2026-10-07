(ns yin.vm.ucf.v2-support
  "The shared harness of the UCF version-2 rows (docs/design/
   yin.vm.universal-continuation-format.v2-amendment.md section 11): one
   machine builder per execution profile, the toy channel and served
   table of the stage-1 suites, and the reference and resumed drivers.
   Every row runs real source machines the engine itself parked, over a
   fresh receiver of the same composition."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as jing.cbor]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.lift-support :as support]))


(defn ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn make-ring-stream
  [capacity]
  (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                       ringbuffer/capacity-key capacity}))


(def composition
  "The composition values every profile's machine shares."
  {:make-stream make-ring-stream
   :capability-secret tu/secret
   :modules (module/default-registry)
   :secret-source (fn [origin] (str tu/secret "/" (name origin)))})


;; =============================================================================
;; AST builders
;; =============================================================================

(defn lit
  [x]
  {:type :literal, :value x})


(defn v
  [n]
  {:type :variable, :name n})


(defn lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn then
  [a b]
  (app (lam ['_] b) a))


(defn cursor-of
  [source]
  {:type :stream/cursor, :source source})


(defn next-of
  [source]
  {:type :stream/next, :source source})


(defn let1
  [param init body]
  (app (lam [param] body) init))


;; =============================================================================
;; One machine builder per profile
;; =============================================================================

(def ^:private semantic-vector
  (comp :vector linearize/lower-rows vm/ast->semantic-bytecode))


(defn new-machine
  "A fresh empty machine of `engine` over the shared composition."
  ([engine] (new-machine engine nil))
  ([engine opts]
   (case engine
     :semantic (semantic/create-vm (merge composition opts))
     :stack (stack/create-vm [] (merge composition
                                       {:primitives vm/primitives}
                                       opts))
     :walker (walker/create-vm (merge composition
                                      {:primitives vm/primitives}
                                      opts))
     :register (register/create-vm {:bodies [] :instructions []}
                                   (merge composition
                                          {:primitives vm/primitives}
                                          opts)))))


(defn load-ast
  "`ast` loaded into `machine` of `engine`."
  [engine machine ast]
  (case engine
    :semantic (semantic/load-vector machine (semantic-vector ast)
                                    vm/semantic-contract)
    :stack (stack/load-image machine
                             (:image (dl/adapt (vm/ast->datoms ast)))
                             vm/stack-contract)
    :register (register/load-image
                machine
                (:image (rc/adapt (vm/ast->datoms ast)))
                vm/register-contract)
    :walker (walker/vm-load-rows machine (vm/ast->semantic-bytecode ast)
                                 vm/ast-contract)))


(defn run-ast
  [engine ast]
  (vm/run (load-ast engine (new-machine engine) ast)))


;; =============================================================================
;; The toy channel, the served table and the drivers
;; =============================================================================

(defn toy
  []
  (let [ab (ring 64)
        ba (ring 64)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "ucf-handoff-toy"}]
    {:channel cd, :ab ab, :ba ba,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba}}))


(defn served-peer
  [t]
  {:table (atom {})
   :b-reader (:reader (:b-end t))
   :b-writer (:writer (:b-end t))
   :mirror (atom (:dao.stream/cursor
                   (stream/cursor (:reader (:b-end t))
                                  :dao.stream/oldest)))})


(defn serve-mirror!
  [peer]
  (swap! (:mirror peer)
         #(remote/mirror-step @(:table peer) (:b-reader peer) %
                              (:b-writer peer))))


(defn server
  "The exporter's `serve!`: a stable identity per handle, entering it in
   the peer's table under the toy channel."
  ([peer chan] (server peer chan #{:reader}))
  ([peer chan surfaces]
   (fn [h]
     (if-let [existing (some (fn [[id e]] (when (= h (:handle e)) id))
                             @(:table peer))]
       {:dao.stream/identity existing, :dao.stream/channel chan}
       (let [id (str (gensym "seg"))]
         (swap! (:table peer) assoc id {:handle h, :surface surfaces})
         {:dao.stream/identity id, :dao.stream/channel chan})))))


(defn attacher
  [t]
  (remote/attacher
    {:dao.stream.remote/channels {(:channel t) (:a-end t)}}))


(defn drive-local
  "Run a blocked machine against its own local streams until it stops
   blocking: the reference run."
  ([m] (drive-local m 64))
  ([m rounds]
   (loop [m (vm/run m), n rounds]
     (if (or (not (vm/blocked? m)) (zero? n))
       m
       (recur (vm/run m) (dec n))))))


(defn drive
  "Run a resumed machine through its reflections, stepping the source's
   mirror between rounds, until it stops blocking."
  ([m peer] (drive m peer 64))
  ([m peer rounds]
   (loop [m (vm/run m), n rounds]
     (if (or (not (vm/blocked? m)) (zero? n))
       m
       (do (serve-mirror! peer)
           (recur (vm/run m) (dec n)))))))


(defn stream-of
  "The stream handle a parked reader's first wait entry polls."
  [parked]
  (let [entry (first (:wait-set parked))]
    (get (:resources parked) (:stream-id entry))))


;; =============================================================================
;; Parked programs, by profile
;; =============================================================================

(defn parked-reader
  "A real machine of `engine` parked on the second read of the stream it
   makes, A consumed.  Answers [parked source-stream]."
  [engine]
  (let [m0 (run-ast engine
                    (let1 's {:type :stream/make, :buffer 4}
                          (let1 'c (cursor-of (v 's))
                                (then (next-of (v 'c))
                                      (next-of (v 'c))))))
        src (stream-of m0)]
    (stream/append! src "A")
    [(vm/run m0) src]))


(defn parked-explicit
  "A real machine at an explicit park; the record's environment holds a
   cursor on a blocked stream."
  [engine]
  (run-ast engine
           (let1 's {:type :stream/make, :buffer 4}
                 (app (lam ['c] {:type :vm/park})
                      (cursor-of (v 's))))))


(defn halted-with
  [engine ast]
  (run-ast engine ast))


;; =============================================================================
;; Custody parts (the version-2 exclusive arm)
;; =============================================================================

(def occurrence "8f0c6a52-6a1e-4e43-9d55-3f0a4c1b2e01")
(def predecessor "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02")


(def origin
  {:yin.k/occurrence predecessor
   :dao.lease/lease "lease-7"
   :yin.k/emitter "peer-a"})


(def arbitration
  {:dao.stream/identity "arbitration-1"
   :dao.stream/descriptor {:dao.stream/type :remote :peer "peer-b"}})


(defn header
  "The exclusive header of an export: counter `n`, the predecessor `o`
   when the task has one, and the enrolled stream identities."
  ([] (header 0 nil))
  ([n o] (header n o #{}))
  ([n o enrolled]
   (cond-> {:yin.k/occurrence occurrence
            :yin.k/arbitration arbitration
            :yin.k/next-op-seq n
            :yin.k/enrolled enrolled}
     (some? o) (assoc :yin.k/origin o))))


(defn segment-address
  [bs]
  (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bs))))


(defn lower-options
  "The D10 lower inputs that satisfy all seven grant checks over `bytes`."
  [bytes]
  (let [body (jing.cbor/decode bytes)
        identities (into #{}
                         (comp (filter #(and (map? %)
                                             (= :yin.k/stream (:yin.k/tag %))))
                               (map :dao.stream/identity))
                         (tree-seq coll? seq body))
        address (segment-address bytes)
        occurrence (:yin.k/occurrence body)]
    {:address address
     :exclusive true
     :protection (zipmap identities (repeat :at-least-once))
     :grant {:checkpoint address
             :dao.lease/lease "lease-new"
             :dao.lease/holder "holder-new"
             :tenure {:now 10 :bound 20 :live true}
             :evidence {:yin.k/status :yin.k/ready
                        :yin.k/binding
                        {:yin.k/occurrence occurrence
                         :dao.lease/lease "lease-new"
                         :dao.lease/holder "holder-new"
                         :yin.k/epoch 2
                         :yin.k/transaction
                         {:yin.k/arbitration
                          (get-in body [:yin.k/arbitration
                                        :dao.stream/identity])}}
                        :yin.k/enrolled #{}
                        :yin.k/prefix {:yin.k/occurrence occurrence
                                       :dao.lease/lease "lease-new"
                                       :yin.k/frontier 0
                                       :yin.k/inputs []}}}}))


;; =============================================================================
;; Parked programs that wait on a write or a call
;; =============================================================================

(defn call-node
  [op arg]
  {:type :dao.stream.apply/call, :op op, :operands [(lit arg)]})


(defn parked-writer
  "A real machine of `engine` parked on a blocked write: the warm
   one-slot stream keeps the value retained.  Answers [parked stream]."
  [engine]
  (let [w (support/one-slot-stream "w")]
    (stream/append! w :warmed)
    (let [m0 (load-ast engine (new-machine engine)
                       {:type :stream/put, :target (v 'w), :val (lit "v")})
          [ref m0'] (engine/attach-resource m0 w)]
      [(vm/run (assoc m0' :store {'w ref})) w])))


(defn calling-machine
  ([engine call-in call-out] (calling-machine engine call-in call-out nil))
  ([engine call-in call-out bridge]
   (new-machine engine
                {:call-in call-in
                 :call-out call-out
                 :call-out-cursor (vm/mint-oldest call-out :test)
                 :bridge bridge})))


(defn parked-sent-caller
  "A machine whose first FFI call was sent and waits for its response;
   answers [parked call-in call-out]."
  [engine]
  (let [call-in (ring 8)
        call-out (ring 8)
        parked (vm/run (load-ast engine
                                 (calling-machine engine call-in call-out)
                                 (then (call-node :op/echo "hello")
                                       (call-node :op/echo "world"))))]
    [parked call-in call-out]))


(defn parked-retained-caller
  "A machine whose first FFI call's request is retained, the call-in
   stream being full; answers [parked call-in call-out]."
  [engine]
  (let [call-in (support/one-slot-stream "call-in")
        call-out (ring 8)
        _ (stream/append! call-in :warmed)
        parked (vm/run (load-ast engine
                                 (calling-machine engine call-in call-out)
                                 (then (call-node :op/echo "hello")
                                       (call-node :op/echo "world"))))]
    [parked call-in call-out]))


;; =============================================================================
;; Link waits and install children
;; =============================================================================

(defn module-image
  "The module image of `ast` the linker response carries, by profile."
  [engine ast]
  (case engine
    :semantic (semantic-vector ast)
    :stack (:image (dl/adapt (vm/ast->datoms ast)))
    :register (:image (rc/adapt (second (vm/ast->datoms-with-root ast))))
    :walker (vm/ast->semantic-bytecode ast)))


(defn module-response
  "The `:ok` link response for module `host.mod` exporting `f`: it
   blocks on one read of its own stream, then defines `f`."
  [engine]
  {:status :ok
   :image {:value (module-image
                    engine
                    (let1 's {:type :stream/make, :buffer 4}
                          (then (next-of (cursor-of (v 's)))
                                (def! 'f (lam [] (lit 42))))))}
   :manifest {:yin.module/name 'host.mod
              :yin.module/exports #{'f}}
   :obligations []})


(defn linking-machine
  [engine request response]
  (new-machine engine {:link-request request :link-response response}))


(defn require-program
  [name]
  (app (v 'require) (lit name)))


(defn parked-link-request
  "A machine whose `:link-request` envelope is retained (the request
   stream is full)."
  [engine]
  (let [request (support/one-slot-stream "link-request")
        response (ring 64)
        _ (stream/append! request :warmed)]
    (vm/run (load-ast engine (linking-machine engine request response)
                      (require-program 'host.the-module)))))


(defn parked-link-response
  "A machine whose `:link-request` was sent and waits for the response."
  [engine]
  (let [request (ring 64)
        response (ring 64)]
    (vm/run (load-ast engine (linking-machine engine request response)
                      (require-program 'host.the-module)))))


(defn parked-installer
  "A real machine parked at `:install` with a live blocked child:
   answers [parked child-stream]."
  [engine]
  (let [request (ring 64)
        response (ring 64)
        m0 (vm/run (load-ast engine (linking-machine engine request response)
                             (then (require-program 'host.mod)
                                   (app (v 'host.mod/f)))))
        entry (first (:wait-set m0))
        _ (stream/append! response
                          (assoc (module-response engine)
                                 :yin.link/id (:link-id entry)))
        parked (vm/run m0)
        child (get-in parked [:installs 'host.mod :vm])
        child-stream (get (:resources child)
                          (:stream-id (first (:wait-set child))))]
    [parked child-stream]))


;; =============================================================================
;; Lift, read and deliver
;; =============================================================================

(def engines [:semantic :stack :register :walker])


(defn lift
  "The version-2 lift of `machine` over a toy's served table: fork with a
   nil header, exclusive with one."
  ([machine t peer] (lift machine t peer nil))
  ([machine t peer header] (lift machine t peer header #{:reader}))
  ([machine t peer header surfaces]
   (handoff/export-task machine (server peer (:channel t) surfaces)
                        (cond-> {:version 2}
                          (some? header) (assoc :header header)))))


(defn read!
  "The version-aware reader over a counting attach!, into a fresh
   `engine` receiver composed with the attach seam.  Answers
   [outcome attaches]."
  [engine t bytes opts]
  (let [attaches (atom 0)
        base (attacher t)
        attach! (fn [descriptor]
                  (swap! attaches inc)
                  (base descriptor))]
    [(handoff/resume-task (new-machine engine {:attach-stream attach!})
                          bytes attach! opts)
     @attaches]))


(defn frame-pending
  [export]
  (get-in export [:body :yin.k/frames 0 :yin.k/pending]))


(defn deliver-next
  "The controlled engine apply of one read outcome to the first wait of
   the gated machine `m`, then normal scheduling to the end."
  [m value]
  (let [entry (first (:wait-set m))
        cid (get-in entry [:cursor-ref :id])
        position (get-in m [:resources cid :cursor])
        applied (engine/apply-next m entry
                                   {:dao.stream/outcome :dao.stream/ok
                                    :dao.stream/value value
                                    :dao.stream/cursor position})]
    (drive-local applied)))


(defn decoded
  "The body the export's canonical bytes decode to."
  [export]
  (jing.cbor/decode (:bytes export)))

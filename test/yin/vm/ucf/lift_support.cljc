(ns yin.vm.ucf.lift-support
  "Test support for the version-1 lift (M-next D9): real machines the
   engine parks, a deterministic exporter, the custody header parts, and
   `lift`, the whole enter / prepare / encode path over one machine.

   Every body the D9 tests and the regenerated C4 fixtures check comes
   out of `lift`, never from a hand-built map.  The one thing set by
   hand is an operation id on a wait entry (`with-op-id`): no code
   assigns ids until D11, whose fenced writer replaces that helper, and
   whose tests must show a real run produces entries equal in shape to
   what it sets."
  (:require [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.values :as values]))


;; =============================================================================
;; Machines
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(declare one-slot-stream)


(defn- make-det-stream
  "A `:make-stream` whose streams carry no random identity, so a body
   that holds their cursors has the same bytes on every run and host."
  []
  (let [n (atom 0)]
    (fn [_capacity]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/handle (one-slot-stream (str "made-" (swap! n inc)))})))


(defn new-machine
  ([] (new-machine {}))
  ([opts]
   (semantic/create-vm
     (merge {:make-stream (make-det-stream)
             :capability-secret tu/secret
             :modules (module/default-registry)
             :secret-source (fn [origin]
                              (str tu/secret "/" (name origin)))}
            opts))))


(def ^:private semantic-vector
  (comp :vector linearize/lower-rows vm/ast->semantic-bytecode))


(defn load-ast
  [machine ast]
  (semantic/load-vector machine (semantic-vector ast)
                        vm/semantic-contract))


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


(defn then
  [a b]
  (app (lam ['_] b) a))


(defn let1
  [param init body]
  (app (lam [param] body) init))


(defn def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn cursor-of
  [source]
  {:type :stream/cursor, :source source})


(defn next-of
  [source]
  {:type :stream/next, :source source})


(defn one-slot-stream
  "A stream that answers `full` while one value sits in it."
  [identity]
  (let [slot (atom :empty)]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok,
         :dao.stream/identity identity,
         :dao.stream/descriptor
         {:dao.stream/type :dao.stream.test/channel,
          :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor 0})

      (next
        [_ cursor]
        (if (and (= 0 cursor) (not= :empty @slot))
          (let [x @slot]
            (reset! slot :empty)
            {:dao.stream/outcome :dao.stream/ok,
             :dao.stream/value x, :dao.stream/cursor 1})
          {:dao.stream/outcome :dao.stream/blocked}))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (if (not= :empty @slot)
          {:dao.stream/outcome :dao.stream/full}
          (do (reset! slot x)
              {:dao.stream/outcome :dao.stream/ok}))))))


(defn parked-reader
  "A real machine parked on the second read of the stream it makes, A
   consumed: one :next frame over one cell."
  []
  (let [m0 (vm/run (load-ast (new-machine)
                             (let1 's {:type :stream/make, :buffer 4}
                                   (let1 'c (cursor-of (v 's))
                                         (then (next-of (v 'c))
                                               (next-of (v 'c)))))))
        entry (first (:wait-set m0))
        src (get (:resources m0) (:stream-id entry))]
    (stream/append! src "A")
    (vm/run m0)))


(defn parked-two-cursors
  "Parks on a read through the cursor held under 'held-a; the store holds
   a cursor on each of two streams, so the waiter's cell is shared with
   the store's and a second cell is reached from the store alone."
  []
  (vm/run
    (load-ast
      (new-machine)
      (let1 'a {:type :stream/make, :buffer 4}
            (let1 'b {:type :stream/make, :buffer 4}
                  (then (def! 'held-a (cursor-of (v 'a)))
                        (then (def! 'held-b (cursor-of (v 'b)))
                              (next-of (v 'held-a)))))))))


(defn parked-writer
  "A real machine parked on a blocked write: the warm one-slot stream
   keeps the value retained.  One :put frame."
  []
  (let [w (one-slot-stream "w")]
    (stream/append! w :warmed)
    (let [m0 (load-ast (new-machine)
                       {:type :stream/put, :target (v 'w), :val (lit "v")})
          [ref m0'] (engine/attach-resource m0 w)]
      (vm/run (assoc m0' :store {'w ref})))))


(defn parked-explicit
  "A real machine at an explicit park, its record's environment holding
   a cursor on a stream."
  []
  (vm/run (load-ast (new-machine)
                    (let1 's {:type :stream/make, :buffer 4}
                          (app (lam ['c] {:type :vm/park})
                               (cursor-of (v 's)))))))


(defn parked-writer-shared
  "A real machine parked on a write whose one stream handle is held
   under two resource ids: the write's own target and the environment's
   second reference.  Prepare must ask `serve!` once and key the answer
   under both."
  []
  (let [w (one-slot-stream "w")]
    (stream/append! w :warmed)
    (let [m0 (load-ast (new-machine)
                       (let1 'again (v 'w2)
                             {:type :stream/put, :target (v 'w)
                              :val (lit "v")}))
          [ra m1] (engine/attach-resource m0 w)
          [rb m2] (engine/attach-resource m1 w)]
      (vm/run (assoc m2 :store {'w ra 'w2 rb})))))


(defn parked-linker
  "A real machine parked on a link request whose response cursor is not
   installed: run under the :running gate, so the mint belongs to the
   driver and the entry carries no :cursor (the D6 gap the rulings
   name).  Its export is refused at entering and at the lift."
  []
  (vm/run (-> (new-machine {:link-request (ring 8) :link-response (ring 8)})
              (load-ast (app (v 'require) (lit 'host.mod)))
              (assoc :yin.k/gate :running))))


(defn halted-machine
  "A halted machine whose result is 42."
  []
  (vm/run (load-ast (new-machine) (lit 42))))


(defn halted-with
  "A halted machine whose result is the literal `value`."
  [value]
  (vm/run (load-ast (new-machine) (lit value))))


(defn- fresh-cursors
  "Two sealed cursor references, `k1` and `k2`, minted over two attached
   one-slot streams the program never names: the first place either cell
   is minted is the walk that reaches the references."
  [machine]
  (let [w (one-slot-stream "k1")
        x (one-slot-stream "k2")
        [wa m1] (engine/attach-resource machine w)
        [xa m2] (engine/attach-resource m1 x)
        [c1 m3] (engine/handle-cursor m2 {:stream wa} :k1)
        [c2 m4] (engine/handle-cursor m3 {:stream xa} :k2)]
    {:c1 c1, :c2 c2, :machine m4}))


(defn parked-cursor-keyed
  "A real machine parked at an explicit park whose task store maps, under
   a name the parked code references, a map whose keys are two cursor
   references nothing else reaches, so the store walk's literal ordering
   is the first place either cell is minted."
  []
  (let [m (vm/run (load-ast (new-machine)
                            (let1 's {:type :stream/make, :buffer 4}
                                  (then (def! 'mapped (lit {}))
                                        (app (lam ['p] {:type :vm/park})
                                             (v 'mapped))))))
        {:keys [c1 c2 machine]} (fresh-cursors m)]
    (assoc machine :store (assoc (:store machine) 'mapped {c1 1, c2 2}))))


(defn- linking-machine
  [request response]
  (new-machine {:link-request request :link-response response}))


(defn- module-response
  []
  {:status :ok,
   :image {:value (semantic-vector
                    (let1 's {:type :stream/make, :buffer 4}
                          (then (next-of (cursor-of (v 's)))
                                (def! 'f (lam [] (lit 42))))))},
   :manifest {:yin.module/name 'host.mod,
              :yin.module/exports #{'f}},
   :obligations []})


(defn parked-installer
  "A real machine parked at :install with a live child blocked on its
   own read."
  []
  (let [request (ring 64)
        response (ring 64)
        m0 (vm/run (load-ast (linking-machine request response)
                             (then (app (v 'require) (lit 'host.mod))
                                   (app (v 'host.mod/f)))))
        entry (first (:wait-set m0))]
    (stream/append! response
                    (assoc (module-response)
                           :yin.link/id (:link-id entry)))
    (vm/run m0)))


(defn parked-module-store-cursors
  "A real machine parked at an explicit park whose module store for
   'host.mod holds two cursor references nothing else reaches, named by
   a module closure in the task store -- the shape a lowered module
   closure has, its environment naming the module whose store it runs
   against -- so the module-store snapshot's walk is the first place
   either cell is minted."
  []
  (let [m (vm/run (load-ast (new-machine)
                            (let1 's {:type :stream/make, :buffer 4}
                                  (then (def! 'mod (lit 1))
                                        (app (lam ['p] {:type :vm/park})
                                             (v 'mod))))))
        {:keys [c1 c2 machine]} (fresh-cursors m)
        seg (:segment (first (vals (:parked machine))))
        clo (values/closure (:owner machine)
                            {:type :closure, :params [], :entry 0,
                             :segment seg,
                             :env {engine/store-of-key 'host.mod}})]
    (assoc machine
           :store (assoc (:store machine) 'mod clo)
           :module-stores {'host.mod {'c c1, 'd c2}})))


(defn halted-with-cursors
  "A halted machine whose result is `(f c1 c2)` for the two fresh cursor
   references nothing else reaches: the result walk is the first place
   either cell is minted."
  [f]
  (let [{:keys [c1 c2 machine]} (fresh-cursors (halted-machine))]
    (assoc machine :value (f c1 c2))))


(defn halted-result-closure
  "A halted machine whose result is a module closure -- its environment
   naming 'host.mod -- and whose 'host.mod module store holds two cursor
   references nothing else reaches: only the result names the module."
  []
  (let [m (vm/run (load-ast (new-machine) (lam [] (lit 1))))
        {:keys [c1 c2 machine]} (fresh-cursors m)
        real (values/payload (:value machine))]
    (assoc machine
           :value (values/closure (:owner machine)
                                  (assoc real
                                         :env {engine/store-of-key
                                               'host.mod}))
           :module-stores {'host.mod {'c c1, 'd c2}})))


(defn parked-writer-shared-late
  "A real machine parked on a write whose one stream handle is held
   under two resource ids, with a third, independent stream reached
   between them -- the environment's bindings put the first alias, the
   independent stream, then the pending's alias -- so a prepare that
   serves the first alias and refuses the third leaves the second alias
   unserved, and the retry must resolve it from the record without
   serving the handle again."
  []
  (let [w (one-slot-stream "w")
        x (one-slot-stream "x")]
    (stream/append! w :warmed)
    (let [m0 (load-ast (new-machine)
                       (let1 'again (v 'w2)
                             (let1 'later-than-again (v 'x2)
                                   {:type :stream/put, :target (v 'w),
                                    :val (lit "v")})))
          [ra m1] (engine/attach-resource m0 w)
          [rb m2] (engine/attach-resource m1 w)
          [rx m3] (engine/attach-resource m2 x)]
      (vm/run (assoc m3 :store {'w ra, 'w2 rb, 'x2 rx})))))


;; =============================================================================
;; Operation ids: set by hand until D11
;; =============================================================================

(defn with-op-id
  "`machine` with `id` as the `:op-id` of its first wait entry whose
   reason is `reason`, in the root or, with `path`, in the install child
   at `path`.  D11 replaces this: its fenced writer assigns ids, and its
   tests must show a real run's wait entries equal in shape to what this
   sets."
  ([machine reason id] (with-op-id machine [] reason id))
  ([machine path reason id]
   (let [at (into [] (mapcat (fn [m] [:installs m :vm])) path)
         waits (get-in machine (conj at :wait-set))
         i (first (keep-indexed #(when (= reason (:reason %2)) %1) waits))]
     (assert (some? i) (str "no wait entry of reason " reason))
     (assoc-in machine (into at [:wait-set i :op-id]) id))))


;; =============================================================================
;; The exporter and the lift
;; =============================================================================

(defn serve-table
  "A `serve!` answering one stable identity per handle, \"s0\", \"s1\"
   ... in the order the handles are first asked about."
  []
  (let [table (atom {})]
    (fn [h]
      (or (get @table h)
          (let [served {:dao.stream/identity (str "s" (count @table))
                        :dao.stream/channel {:dao.stream/type
                                             :dao.stream.test/channel}}]
            (swap! table assoc h served)
            served)))))


(def occurrence "8f0c6a52-6a1e-4e43-9d55-3f0a4c1b2e01")
(def predecessor "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02")


(def origin
  {:yin.k/occurrence predecessor
   :dao.lease/lease "lease-7"
   :yin.k/emitter "peer-a"})


(def arbitration
  {:dao.stream/identity "arbitration-1"
   :dao.stream/descriptor {:dao.stream/type :remote :peer "peer-b"}})


(defn op-id
  ([n] (op-id predecessor n))
  ([occ n] {:yin.k/occurrence occ :yin.k/seq n}))


(defn header
  "The version-1 header argument of `prepare`: a first export when `o` is
   nil."
  ([n] (header n origin #{}))
  ([n o enrolled]
   (cond-> {:yin.k/occurrence occurrence
            :yin.k/arbitration arbitration
            :yin.k/next-op-seq n
            :yin.k/enrolled enrolled}
     (some? o) (assoc :yin.k/origin o))))


(defn prepared
  "Enter exporting from `machine` and prepare it under `header` with a
   fresh deterministic exporter: `{:machine m :record r :prepared p}`.
   A refusal of `enter` is `:prepared`."
  [machine header]
  (let [e (export/enter machine)]
    (if (= :ok (:status e))
      {:machine (:machine e)
       :record (:record e)
       :prepared (export/prepare (:machine e) (:record e) (serve-table)
                                 header)}
      {:prepared e})))


(defn lift
  "The whole path over `machine`: enter, prepare, encode.  Answers the
   encode result, or the refusal prepare (or enter) answered."
  [machine header]
  (let [{m :machine p :prepared} (prepared machine header)]
    (if (= :ok (:status p))
      (export/encode m (:record p))
      p)))

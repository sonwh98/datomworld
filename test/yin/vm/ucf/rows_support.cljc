(ns yin.vm.ucf.rows-support
  "Test support for the row carrier under custody (phase 7a): real
   semantic-register machines the engine parks, the composition's source
   index, a deterministic exporter, the custody header parts and `lift`,
   rows/lift-task over one machine. It owns the shared AST helpers,
   one-slot streams, exporter, header parts and remote stream test seams.

   Every pack the 7a tests and the rows checkpoint fixtures check comes
   out of `lift`, never from a hand-built map. The one thing set by hand
   is an operation id on a wait entry (`with-op-id`), for custody fixtures.
   The streams carry no random identity and `serve-table` answers s0, s1
   ... in first-asked order, so a pack's bytes are the same on every run
   and host."
  (:require [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.remote :as remote]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ledger :as ledger]
            [yin.vm.module :as module]
            [yin.vm.semantic :as sr]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.values :as values]))


;; =============================================================================
;; The composition: code by A and the source index
;; =============================================================================

(def source-index
  "The composition's {A #{tree-address}} (continuation §6.1): every vector
   `project` makes is recorded beside the address of its AST."
  (atom {}))


(defn project
  [ast]
  (let [v (:vector (linearize/project ast))]
    (swap! source-index update (jing/segment-key v) (fnil conj #{}) (jing/segment-key [:yin/tree ast]))
    v))


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
  "The custody header for the row lift: a first export when `o` is
   nil."
  ([n] (header n origin #{}))
  ([n o enrolled]
   (cond-> {:yin.k/occurrence occurrence
            :yin.k/arbitration arbitration
            :yin.k/next-op-seq n
            :yin.k/enrolled enrolled}
     (some? o) (assoc :yin.k/origin o))))


(defn ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type ringbuffer/capacity-key capacity})))


(defn- make-det-stream
  []
  (let [n (atom 0)]
    (fn [_capacity]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/handle (one-slot-stream (str "made-" (swap! n inc)))})))


(def stream-modules (module/register-stream-module (module/default-registry)))


(defn new-machine
  ([] (new-machine {}))
  ([opts]
   (sr/create-vm (merge {:make-stream (make-det-stream)
                         :capability-secret tu/secret
                         :modules stream-modules
                         :secret-source (fn [origin] (str tu/secret "/" (name origin)))}
                        opts))))


(defn load-ast
  [machine ast]
  (sr/load-vector machine (project ast) code/contract))


;; =============================================================================
;; Machines
;; =============================================================================

(defn parked-reader
  "Parked on the second read of the stream it makes, A consumed: one :next
   wait over one cursor cell."
  []
  (let [m0 (vm/run (load-ast (new-machine)
                             (let1 's {:type :stream/make :buffer 4}
                                   (let1 'c (cursor-of (v 's))
                                         (then (next-of (v 'c)) (next-of (v 'c)))))))
        src (get (:resources m0) (:stream-id (first (:wait-set m0))))]
    (stream/append! src "A")
    (vm/run m0)))


(defn parked-writer
  "Parked on a blocked write: the warm one-slot stream keeps the value
   retained. One :put wait."
  []
  (let [w (one-slot-stream "w")]
    (stream/append! w :warmed)
    (let [m0 (load-ast (new-machine) {:type :stream/put :target (v 'w) :val (lit "v")})
          [ref m1] (engine/attach-resource m0 w)]
      (vm/run (assoc m1 :store {'w ref})))))


(defn parked-explicit
  "At an explicit park, its record's environment holding a cursor."
  []
  (vm/run (load-ast (new-machine)
                    (let1 's {:type :stream/make :buffer 4}
                          (app (lam ['c] {:type :vm/park}) (cursor-of (v 's)))))))


(defn parked-linker
  "Parked on a link request under the :running gate, so the entry carries
   no :cursor: the lift refuses it (:link-cursor-not-installed)."
  []
  (vm/run (-> (new-machine {:link-request (ring 8) :link-response (ring 8)})
              (load-ast (app (v 'require) (lit 'host.mod)))
              (assoc :yin.k/gate :running))))


(defn linked-response
  "A link response as the linker answers one (yin.vm.linker/link-manifest):
   the image of ast, the manifest naming its tree and its :yin.semantic/code
   derivation record, and that record, from the tree to the image's A. The
   lower checks that chain (rows/check-install!, continuation §11 Q3)."
  [ast module-name exports]
  (let [image (project ast)
        tree (jing/segment-key [:yin/tree ast])
        record (ledger/derive-record tree (jing/segment-key image))]
    {:status :ok
     :image {:value image}
     :manifest {:yin.module/name module-name :yin.module/exports exports
                :yin.module/tree tree
                :yin.module/derivations {:yin.semantic/code (jing/segment-key record)}}
     :derivation record
     :obligations []}))


(defn module-response
  []
  (linked-response (let1 's {:type :stream/make :buffer 4}
                         (then (next-of (cursor-of (v 's))) (def! 'f (lam [] (lit 42)))))
                   'host.mod #{'f}))


(defn parked-installer
  "Parked at :install with a live child blocked on its own read."
  []
  (let [request (ring 64) response (ring 64)
        m0 (vm/run (load-ast (new-machine {:link-request request :link-response response})
                             (then (app (v 'require) (lit 'host.mod)) (app (v 'host.mod/f)))))
        entry (first (:wait-set m0))]
    (stream/append! response (assoc (module-response) :yin.link/id (:link-id entry)))
    (vm/run m0)))


(defn halted-machine
  "Halted with result 42."
  []
  (vm/run (load-ast (new-machine) (lit 42))))


(defn- fresh-cursors
  [machine]
  (let [w (one-slot-stream "k1") x (one-slot-stream "k2")
        [wa m1] (engine/attach-resource machine w)
        [xa m2] (engine/attach-resource m1 x)
        [c1 m3] (engine/handle-cursor m2 {:stream wa} :k1)
        [c2 m4] (engine/handle-cursor m3 {:stream xa} :k2)]
    {:c1 c1 :c2 c2 :machine m4}))


(defn halted-with-cursors
  "Halted with result (f c1 c2) over two fresh cursor references nothing
   else reaches."
  [f]
  (let [{:keys [c1 c2 machine]} (fresh-cursors (halted-machine))]
    (assoc machine :value (f c1 c2))))


(defn parked-module-store-cursors
  "At an explicit park whose host.mod module store holds two cursor
   references, named by a module closure in the task store."
  []
  (let [ast (app (lam ['p] {:type :vm/park}) (lit 1))
        vec* (project ast)
        m (vm/run (load-ast (new-machine) ast))
        {:keys [c1 c2 machine]} (fresh-cursors m)
        closure (first (filter #(= :closure (first %)) vec*))
        clo (values/closure (:owner machine)
                            {:type :closure :params (nth closure 2) :entry (nth closure 3)
                             :segment (get (:code-aliases machine) (jing/segment-key vec*))
                             :env {engine/store-of-key 'host.mod}})]
    (assoc machine :store {'mod clo} :module-stores {'host.mod {'c c1 'd c2}})))


;; =============================================================================
;; The lift, the lower's attacher
;; =============================================================================

(defn lift-opts
  ([] (lift-opts nil))
  ([header]
   (cond-> {:source-index @source-index}
     (some? header) (assoc :header header))))


(defn lift
  "rows/lift-task of machine under header (nil: the fork) with a fresh
   deterministic exporter: the pack, or the refusal's ex-data."
  [machine header]
  (try (rows/lift-task machine (serve-table) (lift-opts header))
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-data e) (throw e)))))


(defn attacher
  "A receiver's attach!: a fresh one-slot stream per descriptor, counting
   attaches in the atom it answers beside."
  []
  (let [n (atom 0)]
    {:count n
     :attach! (fn [d]
                (swap! n inc)
                {:dao.stream/outcome :dao.stream/ok
                 :dao.stream/handle (one-slot-stream (str "attached-" (:dao.stream/identity d)))})}))


(defn receiver
  ([] (receiver {}))
  ([opts] (new-machine opts)))


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


(defn stream-of
  "The stream handle a parked reader's first wait entry polls."
  [parked]
  (let [entry (first (:wait-set parked))]
    (get (:resources parked) (:stream-id entry))))


(defn call-node
  [op arg]
  {:type :dao.stream.apply/call, :op op, :operands [(lit arg)]})


(defn remote-attacher
  [t]
  (remote/attacher
    {:dao.stream.remote/channels {(:channel t) (:a-end t)}}))


(defn halted-result-closure
  "A result-only module closure reaching two otherwise unreferenced cursors."
  []
  (let [m (vm/run (load-ast (new-machine) (lam [] (lit 1))))
        {:keys [c1 c2 machine]} (fresh-cursors m)
        real (values/payload (:value machine))]
    (assoc machine
           :value (values/closure (:owner machine)
                                  (assoc real :env {engine/store-of-key 'host.mod}))
           :module-stores {'host.mod {'c c1 'd c2}})))

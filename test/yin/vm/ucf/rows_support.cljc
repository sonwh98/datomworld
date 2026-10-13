(ns yin.vm.ucf.rows-support
  "Test support for the row carrier under custody (phase 7a): real
   semantic-register machines the engine parks, the composition's source
   index, a deterministic exporter, the custody header parts and `lift`,
   rows/lift-task over one machine. It mirrors yin.vm.ucf.lift-support,
   whose AST helpers, one-slot streams, exporter and header parts it
   reuses, over yin.vm.semantic-register instead of the old kernel.

   Every pack the 7a tests and the rows checkpoint fixtures check comes
   out of `lift`, never from a hand-built map. The one thing set by hand
   is an operation id on a wait entry (`with-op-id`), as in lift-support.
   The streams carry no random identity and `serve-table` answers s0, s1
   ... in first-asked order, so a pack's bytes are the same on every run
   and host."
  (:require [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.semantic-register :as sr]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.lift-support :as ls]
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


(def lit ls/lit)
(def v ls/v)
(def lam ls/lam)
(def app ls/app)
(def then ls/then)
(def let1 ls/let1)
(def def! ls/def!)
(def cursor-of ls/cursor-of)
(def next-of ls/next-of)
(def one-slot-stream ls/one-slot-stream)
(def serve-table ls/serve-table)
(def with-op-id ls/with-op-id)
(def occurrence ls/occurrence)
(def predecessor ls/predecessor)
(def origin ls/origin)
(def arbitration ls/arbitration)
(def op-id ls/op-id)
(def header ls/header)


(defn- ring
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


(defn module-response
  []
  {:status :ok
   :image {:value (project (let1 's {:type :stream/make :buffer 4}
                                 (then (next-of (cursor-of (v 's))) (def! 'f (lam [] (lit 42))))))}
   :manifest {:yin.module/name 'host.mod :yin.module/exports #{'f}}
   :obligations []})


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

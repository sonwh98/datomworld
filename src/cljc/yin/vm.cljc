(ns yin.vm
  "Defines the canonical data model (Datoms), schema, and primitives for the Yin Abstract Machine.
   This namespace acts as the shared kernel/substrate for all execution engines (stack, register, walker)."
  (:refer-clojure :exclude [eval])
  (:require
    [dao.db :as dao-db]
    [dao.stream :as ds]
    #?(:clj [dao.stream.transport.ringbuffer])
    #?(:cljs [dao.stream.transport.ringbuffer])
    #?(:cljd [dao.stream.transport.ringbuffer :as ringbuffer])))


;; =============================================================================
;; Protocols
;; =============================================================================
;; Common VM interfaces implemented by all execution engines.

(defprotocol IVMStep
  "Single-step VM execution protocol."

  (step
    [vm]
    "Execute one step of the VM. Returns updated VM.")

  (halted?
    [vm]
    "Returns true if VM has halted (completed or error).")

  (blocked?
    [vm]
    "Returns true if VM is blocked waiting for external input.")

  (value
    [vm]
    "Returns the current result value, or nil if not yet computed."))


(defprotocol IVMRun
  "Run VM to completion protocol."

  (run
    [vm]
    "Run VM until halted or blocked. Returns final VM state."))


(defprotocol IVMEval
  "Evaluate an AST to a value."

  (eval
    [vm ast]
    "Load and evaluate an AST, running the VM to completion.
     Returns the final VM state (from which value can be extracted),
     or a blocked VM state if execution parks."))


(defprotocol IVMReset
  "Reset VM execution protocol."

  (reset
    [vm]
    "Reset execution state to a known initial state, preserving loaded program."))


(defprotocol IVMLoad
  "Load program into VM protocol."

  (load-program
    [vm program]
    "Load a program into the VM. Program format is VM-specific:
     - ASTWalkerVM: AST map
     - RegisterVM: {:node root-id :datoms [...]}
     - StackVM: {:node root-id :datoms [...]}
     - SemanticVM: {:node root-id :datoms [...]}
     Macro expansion (if a macro-registry is set on the VM) runs automatically
     inside load-program for register/stack/semantic. No per-call opts are
     exposed; expansion behaviour is a VM-instance invariant, not a call-site
     decision. Returns new VM with program loaded."))


(defprotocol IVMState
  "CESK state accessor protocol.
   Exposes the four components of the CESK machine model.
   Representations are VM-specific but the structure is universal."

  (control
    [vm]
    "Returns the current control state (what is being evaluated).
     VM-specific: AST node, instruction pointer, node ID, etc.")

  (environment
    [vm]
    "Returns the current lexical environment (variable bindings).")

  (store
    [vm]
    "Returns the current store (heap/global state).")

  (continuation
    [vm]
    "Returns the current continuation (what to do next).
     VM-specific: linked frames, stack vector, call-stack, etc."))


;; Primitive operations
;; Arithmetic and comparison ops are n-ary, matching clojure.core.
;; Wrapped in (fn ...) only where we need to normalize VM semantics:
;; - expose macro-like forms (and/or) as callable values,
;; - shape return values/effects for VM contracts (e.g. rest, yin/def).
(def primitives
  {'+ +,
   '- -,
   '* *,
   '/ /,
   '= =,
   '== =,
   '!= not=,
   '< <,
   '> >,
   '<= <=,
   '>= >=,
   'and (fn [a b] (and a b)),
   'or (fn [a b] (or a b)),
   'not (fn [a] (not a)),
   'nil? (fn [a] (nil? a)),
   'empty? (fn [a] (empty? a)),
   'first (fn [a] (first a)),
   'rest (fn [a] (vec (rest a))),
   'conj (fn [coll x] (conj coll x)),
   'assoc (fn [m k v] (assoc m k v)),
   'get (fn [m k] (get m k)),
   'vec (fn [coll] (vec coll)),
   ;; Definition primitive - returns an effect
   'yin/def (fn [k v] {:effect :vm/store-put, :key k, :val v})})


(def call-in-stream-key
  "Store key for the shared dao.stream.apply inbound request stream (caller writes, callee reads)."
  :yin/call-in)


(def call-in-cursor-key
  "Store key for the shared dao.stream.apply inbound stream cursor."
  :yin/call-in-cursor)


(def call-out-stream-key
  "Store key for the shared dao.stream.apply outbound response stream (callee writes, caller reads)."
  :yin/call-out)


(def call-out-cursor-key
  "Store key for the shared dao.stream.apply outbound stream cursor."
  :yin/call-out-cursor)


;; =============================================================================
;; Bytecode Opcodes
;; =============================================================================
;; Shared instruction set for stack and register bytecode VMs.

(def opcode-table
  {:literal 1,
   :load-var 2,
   :move 3,
   :lambda 4,
   :call 5,
   :return 6,
   :branch 7,
   :jump 8,
   :gensym 9,
   :store-get 10,
   :store-put 11,
   :stream-make 12,
   :stream-put 13,
   :stream-cursor 14,
   :stream-next 15,
   :stream-close 16,
   :park 17,
   :resume 18,
   :current-cont 19,
   :tailcall 20,
   :dao.stream.apply/call 21})


#?(:clj
   (defmacro opcase
     "Dispatch on a numeric opcode using keyword mnemonics.
      Resolved at compile-time to a standard 'case' over integers."
     [op-expr & clauses]
     (let [default (when (odd? (count clauses)) (last clauses))
           paired (if default (butlast clauses) clauses)
           pairs (partition 2 paired)
           ;; Keep a local literal map so CLJD macro host compilation does not
           ;; depend on resolving `opcode-table` as a host symbol.
           opcodes {:literal 1,
                    :load-var 2,
                    :move 3,
                    :lambda 4,
                    :call 5,
                    :return 6,
                    :branch 7,
                    :jump 8,
                    :gensym 9,
                    :store-get 10,
                    :store-put 11,
                    :stream-make 12,
                    :stream-put 13,
                    :stream-cursor 14,
                    :stream-next 15,
                    :stream-close 16,
                    :park 17,
                    :resume 18,
                    :current-cont 19,
                    :tailcall 20,
                    :dao.stream.apply/call 21}
           resolved (mapcat (fn [[kw body]] [(get opcodes kw) body]) pairs)]
       `(case (int ~op-expr) ~@resolved ~@(when default [default])))))


(def schema
  "DataScript schema for :yin/ AST datoms.
   Complete data model for the Universal AST as queryable datoms."
  {;; Ref attributes (entity references, tempid resolution)
   :yin/body {:db/valueType :db.type/ref},
   :yin/operator {:db/valueType :db.type/ref},
   :yin/operands {:db/valueType :db.type/ref,
                  :db/cardinality :db.cardinality/many},
   :yin/test {:db/valueType :db.type/ref},
   :yin/consequent {:db/valueType :db.type/ref},
   :yin/alternate {:db/valueType :db.type/ref},
   :yin/source {:db/valueType :db.type/ref},
   :yin/target {:db/valueType :db.type/ref},
   :yin/val-node {:db/valueType :db.type/ref},
   ;; Ground-value attributes
   ;; DataScript only validates :db.type/ref and :db.type/tuple,
   ;; so value types are declared in comments for the data model.
   :yin/type {},        ; keyword (:literal, :variable, :lambda, :yin/macro-expand, ...)
   :yin/value {},       ; polymorphic (number, string, boolean, keyword, ...)
   :yin/name {},        ; symbol
   :yin/op {},          ; keyword (dao.stream.apply operation key)
   :yin/params {},      ; vector of symbols
   :yin/key {},         ; symbol
   :yin/prefix {},      ; string
   :yin/buffer {},      ; long
   :yin/parked-id {},   ; keyword
   :yin/tail? {},       ; boolean (tail-position flag)
   ;; Macro attributes
   :yin/macro? {},          ; boolean — true if lambda is a macro
   :yin/phase-policy {},    ; keyword — :compile | :runtime | :both
   ;; Macro expansion event attributes
   :yin/source-call {:db/valueType :db.type/ref}, ; EID of the :yin/macro-expand call site
   :yin/macro {:db/valueType :db.type/ref},        ; EID of the macro lambda entity
   :yin/expansion-root {:db/valueType :db.type/ref}, ; EID of the top expansion node
   :yin/phase {},      ; keyword — :compile or :runtime
   :yin/error {},      ; structured error (optional)
   :yin/capability {}  ; Shibi capability ref (optional)
   })


(def ^:private cardinality-many-attrs
  "Attributes with :db.cardinality/many — their values are vectors of refs
   that must be expanded into individual :db/add assertions."
  #{:yin/operands})


(defn datoms->tx-data
  "Convert [e a v t m] datoms to DataScript tx-data [:db/add e a v].
   Expands cardinality-many vector values into individual assertions.

   DataScript does not allow nil as a stored value, so nil-valued assertions
   are omitted in this index projection. The canonical datom stream remains
   unchanged and still carries the original nil facts."
  [datoms]
  (mapcat (fn [[e a v _t _m]]
            (cond (and (contains? cardinality-many-attrs a) (vector? v))
                  (->> v
                       (remove nil?)
                       (map (fn [ref] [:db/add e a ref])))
                  (nil? v) []
                  :else [[:db/add e a v]]))
          datoms))


(defn ast->datoms-with-root
  "Convert AST map into a vector of datoms. A datom is [e a v t m].
   Returns [root-id datoms].

   Entity IDs are tempids (negative integers: -1024, -1025, -1026...) that get resolved
   to actual entity IDs when transacted. The transactor assigns real positive IDs.

   Options:
     :t - transaction ID (default 0)
     :m - metadata entity reference (default 0, nil metadata)
     :id-start - starting entity ID for tempids (default -1024)"
  ([ast] (ast->datoms-with-root ast {}))
  ([ast opts]
   (let [id-counter (atom (or (:id-start opts) -1024))
         t (or (:t opts) 0)
         m (or (:m opts) 0)
         gen-id #(swap! id-counter dec)
         datoms (atom [])
         emit! (fn [e attr val] (swap! datoms conj [e attr val t m]))
         ;; Track pre-assigned EIDs already emitted so that a shared lambda-ast
         ;; (same :eid in definition operand and call-site operator) is processed
         ;; exactly once; every reference resolves to the same EID.
         seen-eids (atom #{})]
     (letfn
       [(convert
          [node]
          (let [pre-eid (:eid node)
                e       (or pre-eid (gen-id))
                {:keys [type tail?]} node]
            (if (and pre-eid (contains? @seen-eids pre-eid))
              ;; Shared reference already emitted — return EID without re-emitting.
              e
              (do
                (when pre-eid (swap! seen-eids conj pre-eid))
                (when tail? (emit! e :yin/tail? true))
                (case type
                  :literal (do (emit! e :yin/type :literal)
                               (emit! e :yin/value (:value node)))
                  :variable (do (emit! e :yin/type :variable)
                                (emit! e :yin/name (:name node)))
                  :lambda (do (emit! e :yin/type :lambda)
                              (when (:macro? node)
                                (emit! e :yin/macro? true)
                                (emit! e :yin/phase-policy (or (:phase-policy node) :compile)))
                              (emit! e :yin/params (:params node))
                              (let [body-id (convert (:body node))]
                                (emit! e :yin/body body-id)))
                  :application (do (emit! e :yin/type :application)
                                   (let [op-id (convert (:operator node))
                                         operand-ids (mapv convert
                                                           (:operands node))]
                                     (emit! e :yin/operator op-id)
                                     (emit! e :yin/operands operand-ids)))
                  :dao.stream.apply/call (do (emit! e :yin/type :dao.stream.apply/call)
                                             (emit! e :yin/op (:op node))
                                             (let [operand-ids (mapv convert (:operands node))]
                                               (emit! e :yin/operands operand-ids)))
                  :if (do (emit! e :yin/type :if)
                          (let [test-id (convert (:test node))
                                cons-id (convert (:consequent node))
                                alt-id (convert (:alternate node))]
                            (emit! e :yin/test test-id)
                            (emit! e :yin/consequent cons-id)
                            (emit! e :yin/alternate alt-id)))
                  ;; VM primitives
                  :vm/gensym (do (emit! e :yin/type :vm/gensym)
                                 (emit! e :yin/prefix (or (:prefix node) "id")))
                  :vm/store-get (do (emit! e :yin/type :vm/store-get)
                                    (emit! e :yin/key (:key node)))
                  :vm/store-put (do (emit! e :yin/type :vm/store-put)
                                    (emit! e :yin/key (:key node))
                                    (emit! e :yin/value (:val node)))
                  ;; Stream operations
                  :stream/make (do (emit! e :yin/type :stream/make)
                                   (emit! e :yin/buffer (or (:buffer node) 1024)))
                  :stream/put (do (emit! e :yin/type :stream/put)
                                  (let [target-id (convert (:target node))
                                        val-id (convert (:val node))]
                                    (emit! e :yin/target target-id)
                                    (emit! e :yin/val-node val-id)))
                  :stream/cursor (do (emit! e :yin/type :stream/cursor)
                                     (let [source-id (convert (:source node))]
                                       (emit! e :yin/source source-id)))
                  :stream/next (do (emit! e :yin/type :stream/next)
                                   (let [source-id (convert (:source node))]
                                     (emit! e :yin/source source-id)))
                  :stream/close (do (emit! e :yin/type :stream/close)
                                    (let [source-id (convert (:source node))]
                                      (emit! e :yin/source source-id)))
                  ;; Continuation primitives
                  :vm/park (emit! e :yin/type :vm/park)
                  :vm/resume (do (emit! e :yin/type :vm/resume)
                                 (emit! e :yin/parked-id (:parked-id node))
                                 (let [val-id (convert (:val node))]
                                   (emit! e :yin/val-node val-id)))
                  :vm/current-continuation
                  (emit! e :yin/type :vm/current-continuation)
                  ;; Macro call site — operator is the macro lambda entity ref,
                  ;; operands are unevaluated AST refs (not evaluated runtime values)
                  :yin/macro-expand
                  (do (emit! e :yin/type :yin/macro-expand)
                      (let [op-id (convert (:operator node))
                            operand-ids (mapv convert (:operands node))]
                        (emit! e :yin/operator op-id)
                        (emit! e :yin/operands operand-ids)))
                  ;; Default
                  (throw (ex-info "Unknown AST node type"
                                  {:type type, :node node})))
                e))))]
       (let [root-id (convert ast)]
         [root-id @datoms])))))


(defn ast->datoms
  "Convert AST map into a vector of datoms. A datom is [e a v t m]."
  ([ast] (ast->datoms ast {}))
  ([ast opts]
   (second (ast->datoms-with-root ast opts))))


(declare index-datoms)


(defn datoms->ast
  "Convert a vector of datoms [e a v t m] back into an AST map."
  ([datoms]
   (let [indexed (index-datoms datoms)
         root-id (:root-id indexed)]
     (datoms->ast datoms {:indexed indexed :root-id root-id})))
  ([datoms {:keys [indexed root-id]}]
   (let [{:keys [get-attr]} indexed
         node-type (get-attr root-id :yin/type)
         tail? (get-attr root-id :yin/tail?)
         base (cond-> {:type node-type}
                tail? (assoc :tail? true))
         recur-ast (fn [eid] (datoms->ast datoms {:indexed indexed :root-id eid}))]
     (case node-type
       :literal (assoc base :value (get-attr root-id :yin/value))
       :variable (assoc base :name (get-attr root-id :yin/name))
       :lambda (let [body-eid (get-attr root-id :yin/body)
                     macro? (get-attr root-id :yin/macro?)]
                 (cond-> (assoc base
                                :params (get-attr root-id :yin/params)
                                :body (recur-ast body-eid))
                   macro? (assoc :macro? true
                                 :phase-policy (get-attr root-id :yin/phase-policy))))
       :application (let [op-eid (get-attr root-id :yin/operator)
                          operand-eids (get-attr root-id :yin/operands)]
                      (assoc base
                             :operator (recur-ast op-eid)
                             :operands (mapv recur-ast operand-eids)))
       :dao.stream.apply/call (let [op (get-attr root-id :yin/op)
                                    operand-eids (get-attr root-id :yin/operands)]
                                (assoc base
                                       :op op
                                       :operands (mapv recur-ast operand-eids)))
       :if (let [test-eid (get-attr root-id :yin/test)
                 cons-eid (get-attr root-id :yin/consequent)
                 alt-eid (get-attr root-id :yin/alternate)]
             (assoc base
                    :test (recur-ast test-eid)
                    :consequent (recur-ast cons-eid)
                    :alternate (recur-ast alt-eid)))
       :vm/gensym (assoc base :prefix (get-attr root-id :yin/prefix))
       :vm/store-get (assoc base :key (get-attr root-id :yin/key))
       :vm/store-put (assoc base
                            :key (get-attr root-id :yin/key)
                            :val (get-attr root-id :yin/value))
       :stream/make (assoc base :buffer (get-attr root-id :yin/buffer))
       :stream/put (let [target-eid (get-attr root-id :yin/target)
                         val-eid (get-attr root-id :yin/val-node)]
                     (assoc base
                            :target (recur-ast target-eid)
                            :val (recur-ast val-eid)))
       :stream/cursor (assoc base :source (recur-ast (get-attr root-id :yin/source)))
       :stream/next (assoc base :source (recur-ast (get-attr root-id :yin/source)))
       :stream/close (assoc base :source (recur-ast (get-attr root-id :yin/source)))
       :vm/park base
       :vm/resume (let [val-eid (get-attr root-id :yin/val-node)]
                    (assoc base
                           :parked-id (get-attr root-id :yin/parked-id)
                           :val (recur-ast val-eid)))
       :vm/current-continuation base
       :yin/macro-expand (let [op-eid (get-attr root-id :yin/operator)
                               operand-eids (get-attr root-id :yin/operands)]
                           (assoc base
                                  :operator (recur-ast op-eid)
                                  :operands (mapv recur-ast operand-eids)))
       ;; fallback
       (throw (ex-info "Unknown AST node type in datoms"
                       {:type node-type, :root-id root-id}))))))


(defn index-datoms
  "Index AST datoms by entity.
   Returns {:by-entity map, :get-attr fn, :root-id int}.

   Optional opts:
   - :by-entity precomputed {eid [datom ...]} index
   - :root-id explicit root entity id override"
  ([ast-as-datoms] (index-datoms ast-as-datoms {}))
  ([ast-as-datoms {:keys [by-entity root-id]}]
   (let [datoms (vec ast-as-datoms)
         by-entity (or by-entity (group-by first datoms))
         get-attr (fn [e attr]
                    ;; Use last-write-wins for repeated [e a] assertions in
                    ;; append order within a bounded stream snapshot.
                    (some (fn [[_ a v]] (when (= a attr) v))
                          (rseq (vec (get by-entity e)))))
         root-id (or root-id
                     (when (seq by-entity)
                       (apply max (keys by-entity))))]
     {:by-entity by-entity, :get-attr get-attr, :root-id root-id})))


(defn- dao-datom->tuple
  [d]
  [(:e d) (:a d) (:v d) (:t d) (:m d)])


(defn- ast-dao-datom?
  [d]
  (= "yin" (namespace (:a d))))


(defn- dao-db-ast-datoms
  [db]
  (if (satisfies? dao-db/IDaoStorage db)
    (let [active (set (dao-db/datoms db :eavt))
          entries (dao-db/read-segments db 0 (dao-db/basis-t db))]
      (second
        (reduce (fn [[seen acc] [_t added _retracted]]
                  (reduce (fn [[seen acc] d]
                            (if (or (contains? seen d)
                                    (not (contains? active d))
                                    (not (ast-dao-datom? d)))
                              [seen acc]
                              [(conj seen d) (conj acc (dao-datom->tuple d))]))
                          [seen acc]
                          added))
                [#{} []]
                entries)))
    (mapv dao-datom->tuple
          (filter ast-dao-datom? (dao-db/datoms db :eavt)))))


(defn index-datoms-with-db
  "Index AST datoms from a DaoDB instance by entity.
   This keeps DaoDB as the source of truth while preserving the index-datoms
   contract used by macro expansion and analysis helpers."
  ([dao-db] (index-datoms-with-db dao-db {}))
  ([dao-db opts]
   (index-datoms (dao-db-ast-datoms dao-db) opts)))


(defn empty-state
  "Return an initial immutable VM state map.
   Contains: dao.stream.apply stream wiring in :store, :parked {}, :id-counter 0,
   :primitives map, :run-queue [], :wait-set [], and shared telemetry counters."
  ([] (empty-state {}))
  ([opts]
   (let [open-ringbuffer (fn []
                           #?(:cljd (ringbuffer/make-ring-buffer-stream nil)
                              :default (ds/open! {:transport {:type :ringbuffer
                                                              :mode :create
                                                              :capacity nil}})))
         call-in (or (:call-in opts) (open-ringbuffer))
         call-out (or (:call-out opts) (open-ringbuffer))]
     {:store
      {call-in-stream-key  call-in
       call-in-cursor-key  {:stream-id call-in-stream-key, :position 0}
       call-out-stream-key call-out
       call-out-cursor-key {:stream-id call-out-stream-key, :position 0}},
      :parked {},
      :id-counter 0,
      :run-queue [],
      :wait-set [],
      :primitives (or (:primitives opts) primitives),
      :telemetry (:telemetry opts),
      :telemetry-step 0,
      :telemetry-t 0,
      :vm-model (:vm-model opts)})))

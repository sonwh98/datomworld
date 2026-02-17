(ns yin.vm
  "Defines the canonical data model (Datoms), schema, and primitives for the Yin Abstract Machine.
   This namespace acts as the shared kernel/substrate for all execution engines (stack, register, walker)."
  (:refer-clojure :exclude [eval])
  (:require [datascript.core :as d]))


;; =============================================================================
;; Protocols
;; =============================================================================
;; Common VM interfaces implemented by all execution engines.

(defprotocol IVMStep
  "Single-step VM execution protocol."
  (step [vm]
    "Execute one step of the VM. Returns updated VM.")
  (halted? [vm]
    "Returns true if VM has halted (completed or error).")
  (blocked? [vm]
    "Returns true if VM is blocked waiting for external input.")
  (value [vm]
    "Returns the current result value, or nil if not yet computed."))


(defprotocol IVMRun
  "Run VM to completion protocol."
  (run [vm]
    "Run VM until halted or blocked. Returns final VM state."))


(defprotocol IVMEval
  "Evaluate an AST to a value."
  (eval [vm ast]
    "Load and evaluate an AST, running the VM to completion.
     Returns the final VM state (from which value can be extracted),
     or a blocked VM state if execution parks."))


(defprotocol IVMReset
  "Reset VM execution protocol."
  (reset [vm]
    "Reset execution state to a known initial state, preserving loaded program."))


(defprotocol IVMLoad
  "Load program into VM protocol."
  (load-program [vm program]
    "Load a program into the VM. Program format is VM-specific:
     - ASTWalkerVM: AST map
     - RegisterVM: {:bytecode [...] :pool [...]}
     - StackVM: {:bc [...] :pool [...]}
     - SemanticVM: {:node root-id :datoms [...]}
     Returns new VM with program loaded."))


(defprotocol IVMState
  "CESK state accessor protocol.
   Exposes the four components of the CESK machine model.
   Representations are VM-specific but the structure is universal."
  (control [vm]
    "Returns the current control state (what is being evaluated).
     VM-specific: AST node, instruction pointer, node ID, etc.")
  (environment [vm]
    "Returns the current lexical environment (variable bindings).")
  (store [vm]
    "Returns the current store (heap/global state).")
  (continuation [vm]
    "Returns the current continuation (what to do next).
     VM-specific: linked frames, stack vector, call-stack, etc."))


(defprotocol IVMCompile
  "Bytecode compilation protocol.
   Implemented by VMs that compile AST datoms through an assembly intermediate.
   Not all VMs implement this: AST walker interprets directly, semantic VM
   traverses the datom graph."
  (ast-datoms->asm [vm datoms]
    "Compile AST datoms to assembly instructions for this VM's backend.")
  (asm->bytecode [vm asm]
    "Encode assembly instructions to bytecode (numeric format with constant pool)."))


(defprotocol IDaoDb
  "Data access protocol for DaoDB/DataScript-backed structures."
  (transact! [db datoms]
    "Transact datoms and return {:db ... :tempids ...}.")
  (q [db query inputs]
    "Run a Datalog query in db context.")
  (datoms [db index]
    "Return datoms for index (:eavt/:aevt/:avet/:vaet)."))


;; Primitive operations
;; Wrapped in (fn ...) to normalize VM semantics:
;; - enforce fixed arities (host ops like + are variadic),
;; - expose macro-like forms (and/or) as callable values,
;; - shape return values/effects for VM contracts (e.g. rest, yin/def).
(def primitives
  {'+ (fn [a b] (+ a b)),
   '- (fn [a b] (- a b)),
   '* (fn [a b] (* a b)),
   '/ (fn [a b] (/ a b)),
   '= (fn [a b] (= a b)),
   '== (fn [a b] (= a b)),
   '!= (fn [a b] (not= a b)),
   '< (fn [a b] (< a b)),
   '> (fn [a b] (> a b)),
   '<= (fn [a b] (<= a b)),
   '>= (fn [a b] (>= a b)),
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


(def schema
  "DataScript schema for :yin/ AST datoms.
   Declares ref attributes so DataScript resolves tempids during transaction."
  {:yin/body {:db/valueType :db.type/ref},
   :yin/operator {:db/valueType :db.type/ref},
   :yin/operands {:db/valueType :db.type/ref,
                  :db/cardinality :db.cardinality/many},
   :yin/test {:db/valueType :db.type/ref},
   :yin/consequent {:db/valueType :db.type/ref},
   :yin/alternate {:db/valueType :db.type/ref},
   :yin/source {:db/valueType :db.type/ref},
   :yin/target {:db/valueType :db.type/ref},
   :yin/val {:db/valueType :db.type/ref}})


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


(defn- transact-db!
  "Transact datoms into db. Returns {:db updated-db :tempids tempid-map}."
  [db datoms]
  (let [tx-data (datoms->tx-data datoms)
        conn (d/conn-from-db db)
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:db @conn, :tempids tempids}))


(defn ast->datoms
  "Convert AST map into lazy-seq of datoms. A datom is [e a v t m].

   Entity IDs are tempids (negative integers: -1024, -1025, -1026...) that get resolved
   to actual entity IDs when transacted. The transactor assigns real positive IDs.

   Options:
     :t - transaction ID (default 0)
     :m - metadata entity reference (default 0, nil metadata)"
  ([ast] (ast->datoms ast {}))
  ([ast opts]
   (let [id-counter (atom -1024)
         t (or (:t opts) 0)
         m (or (:m opts) 0)
         gen-id #(swap! id-counter dec)]
     (letfn
       [(emit [e attr val] [e attr val t m])
        (convert [node]
          (let [e (gen-id)
                {:keys [type]} node]
            (case type
              :literal (lazy-seq (list (emit e :yin/type :literal)
                                       (emit e :yin/value (:value node))))
              :variable (lazy-seq (list (emit e :yin/type :variable)
                                        (emit e :yin/name (:name node))))
              :lambda (let [body-datoms (convert (:body node))
                            body-id (first (first body-datoms))]
                        (lazy-cat (list (emit e :yin/type :lambda)
                                        (emit e :yin/params (:params node))
                                        (emit e :yin/body body-id))
                                  body-datoms))
              :application
                (let [op-datoms (convert (:operator node))
                      op-id (first (first op-datoms))
                      operand-results (map convert (:operands node))
                      operand-ids (map #(first (first %)) operand-results)]
                  (lazy-cat (list (emit e :yin/type :application)
                                  (emit e :yin/operator op-id)
                                  (emit e :yin/operands (vec operand-ids)))
                            op-datoms
                            (apply concat operand-results)))
              :if (let [test-datoms (convert (:test node))
                        test-id (first (first test-datoms))
                        cons-datoms (convert (:consequent node))
                        cons-id (first (first cons-datoms))
                        alt-datoms (convert (:alternate node))
                        alt-id (first (first alt-datoms))]
                    (lazy-cat (list (emit e :yin/type :if)
                                    (emit e :yin/test test-id)
                                    (emit e :yin/consequent cons-id)
                                    (emit e :yin/alternate alt-id))
                              test-datoms
                              cons-datoms
                              alt-datoms))
              ;; VM primitives
              :vm/gensym (lazy-seq
                           (list (emit e :yin/type :vm/gensym)
                                 (emit e :yin/prefix (or (:prefix node) "id"))))
              :vm/store-get (lazy-seq (list (emit e :yin/type :vm/store-get)
                                            (emit e :yin/key (:key node))))
              :vm/store-put (lazy-seq (list (emit e :yin/type :vm/store-put)
                                            (emit e :yin/key (:key node))
                                            (emit e :yin/val (:val node))))
              ;; Stream operations
              :stream/make
                (lazy-seq (list (emit e :yin/type :stream/make)
                                (emit e :yin/buffer (or (:buffer node) 1024))))
              :stream/put (let [target-datoms (convert (:target node))
                                target-id (first (first target-datoms))
                                val-datoms (convert (:val node))
                                val-id (first (first val-datoms))]
                            (lazy-cat (list (emit e :yin/type :stream/put)
                                            (emit e :yin/target target-id)
                                            (emit e :yin/val val-id))
                                      target-datoms
                                      val-datoms))
              :stream/take (let [source-datoms (convert (:source node))
                                 source-id (first (first source-datoms))]
                             (lazy-cat (list (emit e :yin/type :stream/take)
                                             (emit e :yin/source source-id))
                                       source-datoms))
              ;; Default
              (throw (ex-info "Unknown AST node type"
                              {:type type, :node node})))))]
       (convert ast)))))


(defn empty-state
  "Return an initial immutable VM state map.
   Contains: :store {}, :parked {}, :id-counter 0, :primitives map."
  ([] (empty-state {}))
  ([opts]
   {:store {},
    :parked {},
    :id-counter 0,
    :primitives (or (:primitives opts) primitives)}))

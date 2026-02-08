(ns yin.vm.semantic
  (:require [datascript.core :as d]
            [yin.vm :as vm]
            [yin.vm.protocols :as proto]))


;; =============================================================================
;; Assembly: Datom Projections
;; =============================================================================
;;
;; The AST as :yin/ datoms (from vm/ast->datoms) is the canonical
;; representation.
;; This namespace provides:
;;   1. Query helpers for :yin/ datoms
;;   2. run-semantic: execute :yin/ datoms by graph traversal
;;
;; All functions consume :yin/ datoms — either 5-tuples [e a v t m] or
;; 3-tuples [e a v]. Vector destructuring [_ a v] handles both.
;; =============================================================================


;; =============================================================================
;; Querying Semantic Bytecode
;; =============================================================================
;; Because bytecode is now datoms, we can query it with Datalog-like patterns.
;; This is the key insight from the blog: low-level form enables high-level
;; queries.

(defn find-by-type
  "Find all nodes of a given type.
   Works with both 3-tuples [e a v] and 5-tuples [e a v t m].

   Example: (find-by-type datoms :lambda) => all lambda nodes"
  [datoms node-type]
  (->> datoms
       (filter (fn [[_ a v]] (and (= a :yin/type) (= v node-type))))
       (map first)
       set))


(defn find-by-attr
  "Find all datoms with a given attribute.
   Works with both 3-tuples and 5-tuples."
  [datoms attr]
  (filter (fn [[_ a _]] (= a attr)) datoms))


(defn get-node-attrs
  "Get all attributes for a node as a map.
   Works with both 3-tuples [e a v] and 5-tuples [e a v t m]."
  [datoms node-id]
  (->> datoms
       (filter (fn [[e _ _]] (= e node-id)))
       (reduce (fn [m [_ a v]] (assoc m a v)) {})))


(defn find-applications
  "Find all function applications in the datoms."
  [datoms]
  (find-by-type datoms :application))


(defn find-lambdas
  "Find all lambda definitions in the datoms."
  [datoms]
  (find-by-type datoms :lambda))


(defn find-variables
  "Find all variable references in the datoms."
  [datoms]
  (find-by-type datoms :variable))


;; =============================================================================
;; VM Records
;; =============================================================================

(defrecord SemanticVM [control    ; current control state {:type
                       ;; :node/:value, ...}
                       env        ; lexical environment
                       stack      ; continuation stack
                       datoms     ; AST datoms
                       halted     ; true if execution completed
                       value      ; final result value
                       store      ; heap memory
                       db         ; DataScript db value
                       parked     ; parked continuations
                       id-counter ; unique ID counter
                       primitives ; primitive operations
                      ])


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================
;; The VM interprets :yin/ datoms by traversing node references (graph walk).
;; No program counter, no constant pool — values are inline in the datoms.

(defn make-semantic-state
  "Create initial state for stepping the semantic (datom) VM."
  [{:keys [node datoms]} env]
  (merge (vm/empty-state)
         {:control {:type :node, :id node},
          :env env,
          :stack [],
          :datoms datoms,
          :halted false,
          :value nil}))


(defn semantic-step
  "Execute one step of the semantic VM."
  [state]
  (let [{:keys [control env stack datoms]} state]
    (if (= :value (:type control))
      ;; Handle return value from previous step
      (let [val (:val control)]
        (if (empty? stack)
          (assoc state
            :halted true
            :value val)
          (let [frame (peek stack)
                new-stack (pop stack)]
            (case (:type frame)
              :if (let [{:keys [cons alt env]} frame]
                    (assoc state
                      :control {:type :node, :id (if val cons alt)}
                      :env env
                      :stack new-stack))
              :app-op (let [{:keys [operands env]} frame
                            fn-val val]
                        (if (empty? operands)
                          ;; 0-arity call
                          (if (fn? fn-val)
                            (assoc state
                              :control {:type :value, :val (fn-val)}
                              :env env
                              :stack new-stack)
                            (if (= :closure (:type fn-val))
                              (let [{:keys [body-node env]} fn-val]
                                (assoc state
                                  :control {:type :node, :id body-node}
                                  :env env ; Switch to closure env
                                  :stack (conj new-stack
                                               {:type :restore-env,
                                                :env (:env frame)})))
                              (throw (ex-info "Cannot apply non-function"
                                              {:fn fn-val}))))
                          ;; Prepare to eval args
                          (let [first-arg (first operands)
                                rest-args (vec (rest operands))]
                            (assoc state
                              :control {:type :node, :id first-arg}
                              :env env
                              :stack (conj new-stack
                                           {:type :app-args,
                                            :fn fn-val,
                                            :evaluated [],
                                            :pending rest-args,
                                            :env env})))))
              :app-args
                (let [{:keys [fn evaluated pending env]} frame
                      new-evaluated (conj evaluated val)]
                  (if (empty? pending)
                    ;; All args evaluated, apply
                    (if (fn? fn)
                      (assoc state
                        :control {:type :value, :val (apply fn new-evaluated)}
                        :env env
                        :stack new-stack)
                      (if (= :closure (:type fn))
                        (let [{:keys [params body-node env]} fn
                              new-env (merge env (zipmap params new-evaluated))]
                          (assoc state
                            :control {:type :node, :id body-node}
                            :env new-env ; Closure env + args
                            :stack (conj new-stack
                                         {:type :restore-env,
                                          :env (:env frame)})))
                        (throw (ex-info "Cannot apply non-function" {:fn fn}))))
                    ;; More args to eval
                    (let [next-arg (first pending)
                          rest-pending (vec (rest pending))]
                      (assoc state
                        :control {:type :node, :id next-arg}
                        :env env
                        :stack (conj new-stack
                                     (assoc frame
                                       :evaluated new-evaluated
                                       :pending rest-pending))))))
              :restore-env (assoc state
                             :control control ; Pass value up
                             :env (:env frame) ; Restore caller env
                             :stack new-stack)))))
      ;; Handle node evaluation
      (let [node-id (:id control)
            node-map (get-node-attrs datoms node-id)
            node-type (:yin/type node-map)]
        (case node-type
          :literal (assoc state
                     :control {:type :value, :val (:yin/value node-map)})
          :variable (assoc state
                      :control {:type :value,
                                :val (get env (:yin/name node-map))})
          :lambda (assoc state
                    :control {:type :value,
                              :val {:type :closure,
                                    :params (:yin/params node-map),
                                    :body-node (:yin/body node-map),
                                    :datoms datoms,
                                    :env env}})
          :if (assoc state
                :control {:type :node, :id (:yin/test node-map)}
                :stack (conj stack
                             {:type :if,
                              :cons (:yin/consequent node-map),
                              :alt (:yin/alternate node-map),
                              :env env}))
          :application (assoc state
                         :control {:type :node, :id (:yin/operator node-map)}
                         :stack (conj stack
                                      {:type :app-op,
                                       :operands (:yin/operands node-map),
                                       :env env}))
          (throw (ex-info "Unknown node type" {:node-map node-map})))))))


(defn semantic-run
  "Run semantic VM to completion."
  [state]
  (loop [s state] (if (:halted s) (:value s) (recur (semantic-step s)))))


(defn run-semantic
  "Execute :yin/ datoms starting from a root node.

   Traverses the datom graph by following entity references.
   Takes {:node root-entity-id :datoms [[e a v ...] ...]} and an env map."
  ([compiled] (run-semantic compiled {}))
  ([compiled env] (semantic-run (make-semantic-state compiled env))))


;; =============================================================================
;; SemanticVM Protocol Implementation
;; =============================================================================

(defn- semantic-vm->state
  "Convert SemanticVM to legacy state map."
  [^SemanticVM vm]
  {:control (:control vm),
   :env (:env vm),
   :stack (:stack vm),
   :datoms (:datoms vm),
   :halted (:halted vm),
   :value (:value vm),
   :store (:store vm),
   :db (:db vm),
   :parked (:parked vm),
   :id-counter (:id-counter vm),
   :primitives (:primitives vm)})


(defn- state->semantic-vm
  "Convert legacy state map back to SemanticVM."
  [^SemanticVM vm state]
  (->SemanticVM (:control state)
                (:env state)
                (:stack state)
                (:datoms state)
                (:halted state)
                (:value state)
                (:store state)
                (:db state)
                (:parked state)
                (:id-counter state)
                (:primitives state)))


(defn semantic-vm-step
  "Execute one step of SemanticVM. Returns updated VM."
  [^SemanticVM vm]
  (let [state (semantic-vm->state vm)
        new-state (semantic-step state)]
    (state->semantic-vm vm new-state)))


(defn semantic-vm-halted?
  "Returns true if VM has halted."
  [^SemanticVM vm]
  (boolean (:halted vm)))


(defn semantic-vm-blocked?
  "Returns true if VM is blocked."
  [^SemanticVM vm]
  (= :yin/blocked (:value vm)))


(defn semantic-vm-value
  "Returns the current value."
  [^SemanticVM vm]
  (:value vm))


(defn semantic-vm-run
  "Run SemanticVM until halted or blocked."
  [^SemanticVM vm]
  (loop [v vm]
    (if (or (semantic-vm-halted? v) (semantic-vm-blocked? v))
      v
      (recur (semantic-vm-step v)))))


(defn semantic-vm-load-program
  "Load datoms into the VM.
   Expects {:node root-id :datoms [...]}."
  [^SemanticVM vm {:keys [node datoms]}]
  (map->SemanticVM (merge (semantic-vm->state vm)
                          {:control {:type :node, :id node},
                           :stack [],
                           :datoms datoms,
                           :halted false,
                           :value nil})))


(defn semantic-vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^SemanticVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn semantic-vm-q
  "Run a Datalog query against the VM's db."
  [^SemanticVM vm args]
  (apply d/q (first args) (:db vm) (rest args)))


(extend-type SemanticVM
  proto/IVMStep
    (step [vm] (semantic-vm-step vm))
    (halted? [vm] (semantic-vm-halted? vm))
    (blocked? [vm] (semantic-vm-blocked? vm))
    (value [vm] (semantic-vm-value vm))
  proto/IVMRun
    (run [vm] (semantic-vm-run vm))
  proto/IVMLoad
    (load-program [vm program] (semantic-vm-load-program vm program))
  proto/IVMDataScript
    (transact! [vm datoms] (semantic-vm-transact! vm datoms))
    (q [vm args] (semantic-vm-q vm args)))


(defn create-semantic-vm
  "Create a new SemanticVM with optional environment."
  ([] (create-semantic-vm {}))
  ([env]
   (map->SemanticVM (merge (vm/empty-state)
                           {:control nil,
                            :env env,
                            :stack [],
                            :datoms [],
                            :halted false,
                            :value nil}))))

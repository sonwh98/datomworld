(ns yin.vm.semantic
  (:require [datascript.core :as d]
            [yin.vm :as vm]))


;; =============================================================================
;; Semantic VM
;; =============================================================================
;;
;; Interprets datom 5-tuples [e a v t m] (from vm/ast->datoms) by graph
;; traversal. Looks up node attributes via entity ID scanning the datom set.
;;
;; Continuations are an explicit stack (vector of frames):
;;   [{:type :app-op}, {:type :app-args}, {:type :if}, {:type :restore-env}]
;; Control is {:type :node, :id eid} or {:type :value, :val v} (two-phase
;; dispatch: handle return values vs evaluate nodes).
;;
;; Core language only: literal, variable, lambda, if, application.
;;
;; Proves the same computation can be recovered purely from the datom stream
;; without reconstructing the original AST maps.
;; =============================================================================


(def ^:private cardinality-many-attrs
  "Attributes materialized as repeated datoms in DataScript."
  #{:yin/operands})


(defn find-by-type
  "Find all nodes of a given type from a DataScript db."
  [db node-type]
  (->> (d/q '[:find ?e :in $ ?node-type :where [?e :yin/type ?node-type]]
            db
            node-type)
       (map first)
       set))


(defn- find-by-attr
  "Find all datoms with a given attribute from a DataScript db."
  [db attr]
  (mapv (fn [[e v]] [e attr v])
    (d/q '[:find ?e ?v :in $ ?attr :where [?e ?attr ?v]] db attr)))


(defn get-node-attrs
  "Get all attributes for a node as a map via DataScript."
  [db node-id]
  (->> (d/q '[:find ?a ?v :in $ ?e :where [?e ?a ?v]] db node-id)
       (reduce (fn [m [a v]]
                 (if (contains? cardinality-many-attrs a)
                   (update m a (fnil conj []) v)
                   (assoc m a v)))
         {})))


(defn- datom-node-attrs
  "Get node attributes directly from raw [e a v t m] datoms.
   Semantic VM control IDs are stream-local tempids, so traversal must stay
   in the same ID space as the source datom stream."
  [datoms node-id]
  (reduce (fn [m [e a v _t _m]]
            (if (= e node-id)
              (if (contains? cardinality-many-attrs a)
                (if (vector? v)
                  (update m a (fnil into []) v)
                  (update m a (fnil conj []) v))
                (assoc m a v))
              m))
    {}
    datoms))


(defn find-applications
  "Find all function applications in the db."
  [db]
  (find-by-type db :application))


(defn find-lambdas
  "Find all lambda definitions in the db."
  [db]
  (find-by-type db :lambda))


(defn find-variables
  "Find all variable references in the db."
  [db]
  (find-by-type db :variable))


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
                       parked     ; parked continuations
                       id-counter ; unique ID counter
                       primitives ; primitive operations
                      ])


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================
;; The VM interprets :yin/ datoms by traversing node references (graph walk).
;; No program counter, no constant pool — values are inline in the datoms.


(defn- handle-return-value
  [vm]
  (let [{:keys [control env stack datoms]} vm
        val (:val control)]
    (if (empty? stack)
      (assoc vm
        :halted true
        :value val)
      (let [frame (peek stack)
            new-stack (pop stack)]
        (case (:type frame)
          :if (let [{:keys [cons alt env]} frame]
                (assoc vm
                  :control {:type :node, :id (if val cons alt)}
                  :env env
                  :stack new-stack))
          :app-op
            (let [{:keys [operands env]} frame
                  fn-val val]
              (if (empty? operands)
                ;; 0-arity call
                (if (fn? fn-val)
                  (assoc vm
                    :control {:type :value, :val (fn-val)}
                    :env env
                    :stack new-stack)
                  (if (= :closure (:type fn-val))
                    (let [{:keys [body-node env]} fn-val]
                      (assoc vm
                        :control {:type :node, :id body-node}
                        :env env ; Switch to closure env
                        :stack (conj new-stack
                                     {:type :restore-env, :env (:env frame)})))
                    (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
                ;; Prepare to eval args
                (let [first-arg (first operands)
                      rest-args (vec (rest operands))]
                  (assoc vm
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
                  (assoc vm
                    :control {:type :value, :val (apply fn new-evaluated)}
                    :env env
                    :stack new-stack)
                  (if (= :closure (:type fn))
                    (let [{:keys [params body-node env]} fn
                          new-env (merge env (zipmap params new-evaluated))]
                      (assoc vm
                        :control {:type :node, :id body-node}
                        :env new-env ; Closure env + args
                        :stack (conj new-stack
                                     {:type :restore-env, :env (:env frame)})))
                    (throw (ex-info "Cannot apply non-function" {:fn fn}))))
                ;; More args to eval
                (let [next-arg (first pending)
                      rest-pending (vec (rest pending))]
                  (assoc vm
                    :control {:type :node, :id next-arg}
                    :env env
                    :stack (conj new-stack
                                 (assoc frame
                                   :evaluated new-evaluated
                                   :pending rest-pending))))))
          :restore-env (assoc vm
                         :control control ; Pass value up
                         :env (:env frame) ; Restore caller env
                         :stack new-stack))))))


(defn handle-node-eval
  [vm]
  (let [{:keys [control env stack datoms]} vm
        node-id (:id control)
        node-map (datom-node-attrs datoms node-id)
        node-type (:yin/type node-map)]
    (case node-type
      :literal (assoc vm :control {:type :value, :val (:yin/value node-map)})
      :variable (assoc vm
                  :control {:type :value, :val (get env (:yin/name node-map))})
      :lambda (assoc vm
                :control {:type :value,
                          :val {:type :closure,
                                :params (:yin/params node-map),
                                :body-node (:yin/body node-map),
                                :datoms datoms,
                                :env env}})
      :if (assoc vm
            :control {:type :node, :id (:yin/test node-map)}
            :stack (conj stack
                         {:type :if,
                          :cons (:yin/consequent node-map),
                          :alt (:yin/alternate node-map),
                          :env env}))
      :application (assoc vm
                     :control {:type :node, :id (:yin/operator node-map)}
                     :stack (conj stack
                                  {:type :app-op,
                                   :operands (:yin/operands node-map),
                                   :env env}))
      (throw (ex-info "Unknown node type" {:node-map node-map})))))


(defn- semantic-step
  "Execute one step of the semantic VM.
   Operates directly on SemanticVM record (assoc preserves record type)."
  [^SemanticVM vm]
  (let [{:keys [control env stack datoms]} vm]
    (if (= :value (:type control))
      ;; Handle return value from previous step
      (handle-return-value vm)
      ;; Handle node evaluation
      (handle-node-eval vm))))


;; =============================================================================
;; SemanticVM Protocol Implementation
;; =============================================================================

(defn- semantic-vm-step
  "Execute one step of SemanticVM. Returns updated VM."
  [^SemanticVM vm]
  (semantic-step vm))


(defn- semantic-vm-halted?
  "Returns true if VM has halted."
  [^SemanticVM vm]
  (boolean (:halted vm)))


(defn- semantic-vm-blocked?
  "Returns true if VM is blocked."
  [^SemanticVM vm]
  (= :yin/blocked (:value vm)))


(defn- semantic-vm-value
  "Returns the current value."
  [^SemanticVM vm]
  (:value vm))


(defn- semantic-vm-run
  "Run SemanticVM until halted or blocked."
  [^SemanticVM vm]
  (loop [v vm]
    (if (or (semantic-vm-halted? v) (semantic-vm-blocked? v))
      v
      (recur (semantic-vm-step v)))))


(defn- semantic-vm-load-program
  "Load datoms into the VM.
   Expects {:node root-id :datoms [...]}."
  [^SemanticVM vm {:keys [node datoms]}]
  (assoc vm
    :control {:type :node, :id node}
    :stack []
    :datoms datoms
    :halted false
    :value nil))


(extend-type SemanticVM
  vm/IVMStep
    (step [vm] (semantic-vm-step vm))
    (halted? [vm] (semantic-vm-halted? vm))
    (blocked? [vm] (semantic-vm-blocked? vm))
    (value [vm] (semantic-vm-value vm))
  vm/IVMRun
    (run [vm] (semantic-vm-run vm))
  vm/IVMLoad
    (load-program [vm program] (semantic-vm-load-program vm program))
  vm/IVMState
    (control [vm] (:control vm))
    (environment [vm] (:env vm))
    (store [vm] (:store vm))
    (continuation [vm] (:stack vm)))


(defn create-vm
  "Create a new SemanticVM with optional opts map.
   Accepts {:env map, :primitives map}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})]
     (map->SemanticVM (merge (vm/empty-state (select-keys opts [:primitives]))
                             {:control nil,
                              :env env,
                              :stack [],
                              :datoms [],
                              :halted false,
                              :value nil})))))

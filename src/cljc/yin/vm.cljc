(ns yin.vm
  (:refer-clojure :exclude [eval])
  (:require [datascript.core :as d]
            [yin.module :as module]
            [yin.stream :as stream]))


;; Counter for generating unique IDs
(def ^:private id-counter (atom 0))


(defn gensym-id
  "Generate a unique keyword ID with optional prefix."
  ([] (gensym-id "id"))
  ([prefix] (keyword (str prefix "-" (swap! id-counter inc)))))


;; Primitive operations
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


(defn make-state
  "Create an initial CESK machine state with given environment."
  [env]
  {:control nil, :environment env, :store {}, :continuation nil, :value nil})


(defn datoms->tx-data
  "Convert [e a v t m] datoms to DataScript tx-data [:db/add e a v]"
  [datoms]
  (map (fn [[e a v _t _m]] [:db/add e a v]) datoms))


(defn ast->datoms
  "Convert AST map into lazy-seq of datoms. A datom is [e a v t m].

   Entity IDs are tempids (negative integers: -1025, -1026, -1027...) that get resolved
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


(defn ast-datoms->stack-bytecode
  "Takes the AST as datoms and transforms it to bytecode.

   Bytecode is a vector of instructions for a stack-based VM:
     [:push v]                  - push literal value onto stack
     [:load name]               - load variable from environment
     [:closure params body-addr]- create closure, body starts at body-addr
     [:call n]                  - pop n args and fn from stack, apply
     [:branch then else]        - pop test, jump to then-addr or else-addr
     [:jump addr]               - unconditional jump
     [:return]                  - return top of stack
     [:gensym prefix]           - generate unique ID
     [:store-get key]           - read from store
     [:store-put key]           - pop value, write to store
     [:stream-make buffer]      - create stream with buffer size
     [:stream-put]              - pop value and stream, put value
     [:stream-take]             - pop stream, take value

   The bytecode uses absolute addresses (indices into the instruction vector)."
  [ast-as-datoms]
  (let [;; Materialize and index datoms by entity
        datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)
        ;; Get attribute value for entity
        get-attr (fn [e attr]
                   (some (fn [[_ a v _ _]] (when (= a attr) v))
                         (get by-entity e)))
        ;; Find root entity (max of negative tempids = -1025)
        root-id (apply max (keys by-entity))
        ;; Bytecode accumulator
        bytecode (atom [])
        emit! (fn [instr] (swap! bytecode conj instr))
        current-addr #(count @bytecode)]
    ;; Compile entity to bytecode
    (letfn
      [(compile-node [e]
         (let [node-type (get-attr e :yin/type)]
           (case node-type
             :literal (emit! [:push (get-attr e :yin/value)])
             :variable (emit! [:load (get-attr e :yin/name)])
             :lambda
               (let [params (get-attr e :yin/params)
                     body-ref (get-attr e :yin/body)
                     ;; Emit closure with placeholder for body address
                     closure-idx (current-addr)]
                 (emit! [:closure params :placeholder])
                 ;; Jump over body (closure creation doesn't execute body)
                 (let [jump-idx (current-addr)]
                   (emit! [:jump :placeholder])
                   ;; Body starts here
                   (let [body-addr (current-addr)]
                     (compile-node body-ref)
                     (emit! [:return])
                     ;; Patch closure and jump addresses
                     (let [after-body (current-addr)]
                       (swap! bytecode assoc-in [closure-idx 2] body-addr)
                       (swap! bytecode assoc-in [jump-idx 1] after-body)))))
             :application (let [op-ref (get-attr e :yin/operator)
                                operand-refs (get-attr e :yin/operands)]
                            ;; Push operands left-to-right
                            (doseq [operand-ref operand-refs]
                              (compile-node operand-ref))
                            ;; Push operator
                            (compile-node op-ref)
                            ;; Call with arity
                            (emit! [:call (count operand-refs)]))
             :if (let [test-ref (get-attr e :yin/test)
                       cons-ref (get-attr e :yin/consequent)
                       alt-ref (get-attr e :yin/alternate)]
                   ;; Compile test expression
                   (compile-node test-ref)
                   ;; Emit branch with placeholders
                   (let [branch-idx (current-addr)]
                     (emit! [:branch :then :else])
                     ;; Consequent branch
                     (let [then-addr (current-addr)]
                       (compile-node cons-ref)
                       (let [jump-idx (current-addr)]
                         (emit! [:jump :end])
                         ;; Alternate branch
                         (let [else-addr (current-addr)]
                           (compile-node alt-ref)
                           ;; End of if
                           (let [end-addr (current-addr)]
                             ;; Patch all addresses
                             (swap! bytecode assoc-in [branch-idx 1] then-addr)
                             (swap! bytecode assoc-in [branch-idx 2] else-addr)
                             (swap! bytecode assoc-in
                               [jump-idx 1]
                               end-addr)))))))
             ;; VM primitives
             :vm/gensym (emit! [:gensym (get-attr e :yin/prefix)])
             :vm/store-get (emit! [:store-get (get-attr e :yin/key)])
             :vm/store-put (do (emit! [:push (get-attr e :yin/val)])
                               (emit! [:store-put (get-attr e :yin/key)]))
             ;; Stream operations
             :stream/make (emit! [:stream-make (get-attr e :yin/buffer)])
             :stream/put (let [target-ref (get-attr e :yin/target)
                               val-ref (get-attr e :yin/val)]
                           (compile-node val-ref)
                           (compile-node target-ref)
                           (emit! [:stream-put]))
             :stream/take (let [source-ref (get-attr e :yin/source)]
                            (compile-node source-ref)
                            (emit! [:stream-take]))
             ;; Unknown type
             (throw (ex-info "Unknown node type in bytecode compilation"
                             {:type node-type, :entity e})))))]
      (compile-node root-id) @bytecode)))


(comment
  ;; ast-datoms->stack-bytecode exploration
  ;; Pipeline: AST map -> datoms -> bytecode
  ;; Simple literal
  (ast-datoms->stack-bytecode (ast->datoms {:type :literal, :value 42}))
  ;; => [[:push 42]]
  ;; Variable reference
  (ast-datoms->stack-bytecode (ast->datoms {:type :variable, :name 'x}))
  ;; => [[:load x]]
  ;; Lambda (fn [x] x) - closure with jump over body
  (ast-datoms->stack-bytecode (ast->datoms {:type :lambda,
                                            :params ['x],
                                            :body {:type :variable, :name 'x}}))
  ;; => [[:closure [x] 2]    ; create closure, body at addr 2
  ;;     [:jump 4]           ; skip body during closure creation
  ;;     [:load x]           ; body: load x
  ;;     [:return]]          ; return from closure
  ;; Application (+ 1 2)
  (ast-datoms->stack-bytecode (ast->datoms
                                {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :literal, :value 1}
                                            {:type :literal, :value 2}]}))
  ;; => [[:push 1]           ; push first operand
  ;;     [:push 2]           ; push second operand
  ;;     [:load +]           ; push operator
  ;;     [:call 2]]          ; call with 2 args
  ;; Conditional (if true 1 0)
  (ast-datoms->stack-bytecode (ast->datoms
                                {:type :if,
                                 :test {:type :literal, :value true},
                                 :consequent {:type :literal, :value 1},
                                 :alternate {:type :literal, :value 0}}))
  ;; => [[:push true]        ; test
  ;;     [:branch 2 4]       ; if true goto 2, else goto 4
  ;;     [:push 1]           ; consequent
  ;;     [:jump 5]           ; skip alternate
  ;;     [:push 0]]          ; alternate
  ;; Nested: ((fn [x] x) 42)
  (ast-datoms->stack-bytecode (ast->datoms
                                {:type :application,
                                 :operator {:type :lambda,
                                            :params ['x],
                                            :body {:type :variable, :name 'x}},
                                 :operands [{:type :literal, :value 42}]}))
  ;; => [[:push 42]          ; operand
  ;;     [:closure [x] 3]    ; operator: closure
  ;;     [:jump 5]
  ;;     [:load x]           ; closure body
  ;;     [:return]
  ;;     [:call 1]]          ; call closure with 1 arg
)


(comment
  ;; ast->datoms exploration. If these examples don't work, DELETE this
  ;; block. Simple literal: produces 2 datoms with negative tempids
  (ast->datoms {:type :literal, :value 42})
  ;; => ([-1 :yin/type :literal 0 0]
  ;;     [-1 :yin/value 42 0 0])
  ;; Lambda with body: parent references child via tempid
  (ast->datoms {:type :lambda, :params ['x], :body {:type :variable, :name 'x}})
  ;; => ([-1 :yin/type :lambda 0 0]
  ;;     [-1 :yin/params [x] 0 0]
  ;;     [-1 :yin/body -2 0 0]      ; tempid -1 references tempid -2
  ;;     [-2 :yin/type :variable 0 0]
  ;;     [-2 :yin/name x 0 0])
  ;; Application (+ 1 2): operator and operands are tempid references
  (ast->datoms {:type :application,
                :operator {:type :variable, :name '+},
                :operands [{:type :literal, :value 1}
                           {:type :literal, :value 2}]})
  ;; => ([-1 :yin/type :application 0 0]
  ;;     [-1 :yin/operator -2 0 0]
  ;;     [-1 :yin/operands [-3 -4] 0 0]
  ;;     [-2 :yin/type :variable 0 0]
  ;;     [-2 :yin/name + 0 0]
  ;;     [-3 :yin/type :literal 0 0]
  ;;     [-3 :yin/value 1 0 0]
  ;;     [-4 :yin/type :literal 0 0]
  ;;     [-4 :yin/value 2 0 0])
  ;; Custom transaction ID and metadata
  (ast->datoms {:type :literal, :value 99} {:t 1000, :m 5})
  ;; => ([-1 :yin/type :literal 1000 5]
  ;;     [-1 :yin/value 99 1000 5])
  ;; Verify datom shape: all datoms are 5-tuples [e a v t m]
  (every? #(= 5 (count %)) (ast->datoms {:type :literal, :value 42}))
  ;; => true. Tempids are negative integers, resolved to positive IDs by
  ;; transactor
  (every? neg-int? (map first (ast->datoms {:type :literal, :value 42})))
  ;; => true
)


(comment
  ;; ast->datoms + DataScript integration
  ;; Requires: [datascript.core :as d]
  ;; Schema: declare ref attributes so DataScript resolves tempids
  (def schema
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
  ;; Example 1: Simple literal
  (let [ast {:type :literal, :value 42}
        datoms (ast->datoms ast)
        tx-data (datoms->tx-data datoms)
        conn (d/create-conn schema)
        {:keys [tempids]} (d/transact! conn tx-data)
        db @conn]
    (println "=== Literal ===")
    (println "Datoms:" (vec datoms))
    (println "Tempids:" tempids)
    (println "Query result:"
             (d/q '[:find ?v :where [?e :yin/type :literal] [?e :yin/value ?v]]
                  db)))
  ;; Example 2: Lambda (fn [x] x)
  (let [ast {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}
        datoms (ast->datoms ast)
        tx-data (datoms->tx-data datoms)
        conn (d/create-conn schema)
        {:keys [tempids]} (d/transact! conn tx-data)
        db @conn]
    (println "\n=== Lambda ===")
    (println "Datoms:" (vec datoms))
    (println "Tempids:" tempids)
    ;; Query: find lambda's body name
    (println "Lambda body:"
             (d/q '[:find ?name . :where [?lambda :yin/type :lambda]
                    [?lambda :yin/body ?body] [?body :yin/name ?name]]
                  db)))
  ;; Example 3: Application (+ 1 2)
  (let [ast {:type :application,
             :operator {:type :variable, :name '+},
             :operands [{:type :literal, :value 1} {:type :literal, :value 2}]}
        datoms (ast->datoms ast)
        tx-data (datoms->tx-data datoms)
        conn (d/create-conn schema)
        _ (d/transact! conn tx-data)
        db @conn]
    (println "\n=== Application (+ 1 2) ===")
    (println "Datoms:" (vec datoms))
    ;; Query: find all literal values in application
    (println "Operand values:"
             (d/q '[:find [?v ...] :where [?app :yin/type :application]
                    [?app :yin/operands ?op] [?op :yin/value ?v]]
                  db)))
  ;; Example 4: If expression with custom t/m
  (let [ast {:type :if,
             :test {:type :variable, :name 'cond},
             :consequent {:type :literal, :value 1},
             :alternate {:type :literal, :value 0}}
        datoms (ast->datoms ast {:t 100, :m 42})
        tx-data (datoms->tx-data datoms)
        conn (d/create-conn schema)
        _ (d/transact! conn tx-data)
        db @conn]
    (println "\n=== If expression ===")
    (println "Datoms with custom t/m:" (vec datoms))
    ;; Query: find all node types
    (println "All node types:"
             (d/q '[:find [?type ...] :where [_ :yin/type ?type]] db))
    ;; Query: find branch values
    (println "Branch values:"
             (d/q '[:find ?v :where [?if :yin/type :if]
                    (or [?if :yin/consequent ?node] [?if :yin/alternate ?node])
                    [?node :yin/value ?v]]
                  db))))


(defn eval
  "Steps the CESK machine to evaluate an AST node.

  State is a map containing:
    :control - current AST node or continuation frame
    :environment - persistent lexical scope map
    :store - immutable memory graph
    :continuation - reified, persistent control context
    :parked - map of parked continuations by ID

  AST is a universal map-based structure with:
    :type - node type (e.g., :literal, :variable, :application, :lambda, :if, etc.)
    :value - node-specific data
    ... other node-specific fields

  Returns updated state after one step of evaluation."
  [state ast]
  (let [{:keys [control environment continuation store]} state
        {:keys [type], :as node} (or ast control)]
    ;; If control is nil but we have a continuation, handle it
    (if (and (nil? node) continuation)
      (let [cont-type (:type continuation)]
        (case cont-type
          :eval-operator (let [frame (:frame continuation)
                               fn-value (:value state)
                               updated-frame (assoc frame
                                               :operator-evaluated? true
                                               :fn-value fn-value)
                               saved-env (:environment continuation)]
                           (assoc state
                             :control updated-frame
                             :environment (or saved-env environment)
                             :continuation (:parent continuation)
                             :value nil))
          :eval-operand
            (let [frame (:frame continuation)
                  operand-value (:value state)
                  evaluated-operands (conj (or (:evaluated-operands frame) [])
                                           operand-value)
                  updated-frame (assoc frame
                                  :evaluated-operands evaluated-operands)
                  saved-env (:environment continuation)]
              (assoc state
                :control updated-frame
                :environment (or saved-env environment)
                :continuation (:parent continuation)
                :value nil))
          :eval-test (let [frame (:frame continuation)
                           test-value (:value state)
                           saved-env (:environment continuation)]
                       (assoc state
                         :control (assoc frame :evaluated-test? true)
                         :value test-value
                         :environment (or saved-env environment)
                         :continuation (:parent continuation)))
          ;; Stream continuation: evaluate source for take
          :eval-stream-source
            (let [stream-ref (:value state)
                  result (stream/vm-stream-take state stream-ref continuation)]
              (if (:park result)
                ;; Need to park - add continuation to takers
                (let [stream-id (:stream-id result)
                      parked-cont {:type :parked-continuation,
                                   :id (gensym-id "taker"),
                                   :continuation (:parent continuation),
                                   :environment environment}
                      store (:store state)
                      new-store
                        (update-in store [stream-id :takers] conj parked-cont)]
                  (assoc state
                    :store new-store
                    :value :yin/blocked
                    :control nil
                    :continuation nil))
                ;; Got value
                (assoc (:state result)
                  :control nil
                  :continuation (:parent continuation))))
          ;; Stream continuation: evaluate target for put
          :eval-stream-put-target (let [frame (:frame continuation)
                                        stream-ref (:value state)
                                        val-node (:val frame)]
                                    (assoc state
                                      :control val-node
                                      :continuation (assoc continuation
                                                      :type :eval-stream-put-val
                                                      :stream-ref stream-ref)))
          ;; Stream continuation: evaluate value for put
          :eval-stream-put-val
            (let [val (:value state)
                  stream-ref (:stream-ref continuation)
                  result (stream/vm-stream-put state stream-ref val)]
              (if-let [taker (:resume-taker result)]
                ;; Taker was waiting - need to resume it. For now, just
                ;; complete the put and let scheduler handle resume
                (assoc (:state result)
                  :control nil
                  :continuation (:parent continuation))
                ;; No taker - value buffered
                (assoc (:state result)
                  :control nil
                  :continuation (:parent continuation))))
          ;; Default for unknown continuation
          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type,
                           :continuation continuation}))))
      ;; Otherwise handle the node type
      (case type
        ;; Literals evaluate to themselves
        :literal (let [{:keys [value]} node]
                   (assoc state
                     :value value
                     :control nil))
        ;; Variable lookup
        :variable (let [{:keys [name]} node
                        ;; Check local environment, then store (global),
                        ;; then module system
                        value (or (get environment name)
                                  (get store name)
                                  (module/resolve-symbol name))]
                    (assoc state
                      :value value
                      :control nil))
        ;; Lambda creates a closure
        :lambda (let [{:keys [params body]} node
                      closure {:type :closure,
                               :params params,
                               :body body,
                               :environment environment}]
                  (assoc state
                    :value closure
                    :control nil))
        ;; Function application
        :application
          (let [{:keys [operator operands evaluated-operands fn-value
                        operator-evaluated?]}
                  node
                evaluated-operands (or evaluated-operands [])]
            (cond
              ;; All evaluated - apply function
              (and operator-evaluated?
                   (= (count evaluated-operands) (count operands)))
                (cond
                  ;; Primitive function
                  (fn? fn-value)
                    (let [result (apply fn-value evaluated-operands)]
                      ;; Check if result is an effect descriptor
                      (if (module/effect? result)
                        ;; Execute effect
                        (case (:effect result)
                          :vm/store-put (let [key (:key result)
                                              value (:val result)
                                              new-store (assoc store key value)]
                                          (assoc state
                                            :store new-store
                                            :value value
                                            :control nil))
                          :stream/make
                            (let [[stream-ref new-state]
                                    (stream/handle-make state result gensym-id)]
                              (assoc new-state
                                :value stream-ref
                                :control nil))
                          :stream/put (let [effect-result
                                              (stream/handle-put state result)]
                                        (assoc (:state effect-result)
                                          :value (:value effect-result)
                                          :control nil))
                          :stream/take
                            (let [effect-result (stream/handle-take state
                                                                    result)]
                              (if (:park effect-result)
                                ;; Need to park
                                (let [stream-id (:stream-id effect-result)
                                      parked-cont {:type :parked-continuation,
                                                   :id (gensym-id "taker"),
                                                   :continuation continuation,
                                                   :environment environment}
                                      new-store (update-in store
                                                           [stream-id :takers]
                                                           conj
                                                           parked-cont)]
                                  (assoc state
                                    :store new-store
                                    :value :yin/blocked
                                    :control nil
                                    :continuation nil))
                                ;; Got value
                                (assoc (:state effect-result)
                                  :value (:value effect-result)
                                  :control nil)))
                          :stream/close
                            (let [new-state (stream/handle-close state result)]
                              (assoc new-state
                                :value nil
                                :control nil))
                          ;; Unknown effect
                          (throw (ex-info "Unknown effect type"
                                          {:effect result})))
                        ;; Regular return value
                        (assoc state
                          :value result
                          :control nil)))
                  ;; User-defined closure
                  (= :closure (:type fn-value))
                    (let [{:keys [params body environment]} fn-value
                          extended-env (merge environment
                                              (zipmap params
                                                      evaluated-operands))]
                      (assoc state
                        :control body
                        :environment extended-env
                        :continuation continuation))
                  :else (throw (ex-info "Cannot apply non-function"
                                        {:fn-value fn-value})))
              ;; Evaluate operands one by one
              operator-evaluated?
                (let [next-operand (nth operands (count evaluated-operands))]
                  (assoc state
                    :control next-operand
                    :continuation {:frame node,
                                   :parent continuation,
                                   :environment environment,
                                   :type :eval-operand}))
              ;; Evaluate operator first
              :else (assoc state
                      :control operator
                      :continuation {:frame node,
                                     :parent continuation,
                                     :environment environment,
                                     :type :eval-operator})))
        ;; Conditional
        :if (let [{:keys [test consequent alternate]} node]
              (if (:evaluated-test? node)
                ;; Test evaluated, choose branch
                (let [test-value (:value state)
                      branch (if test-value consequent alternate)]
                  (assoc state :control branch))
                ;; Evaluate test first
                (assoc state
                  :control test
                  :continuation {:frame node,
                                 :parent continuation,
                                 :environment environment,
                                 :type :eval-test})))
        ;; ============================================================
        ;; VM Primitives for Store Operations
        ;; ============================================================
        ;; Generate unique ID
        :vm/gensym (let [prefix (or (:prefix node) "id")
                         id (gensym-id prefix)]
                     (assoc state
                       :value id
                       :control nil))
        ;; Read from store
        :vm/store-get (let [key (:key node)
                            value (get store key)]
                        (assoc state
                          :value value
                          :control nil))
        ;; Write to store
        :vm/store-put (let [key (:key node)
                            value (:val node)
                            new-store (assoc store key value)]
                        (assoc state
                          :store new-store
                          :value value
                          :control nil))
        ;; Update store (apply function to current value)
        :vm/store-update (let [key (:key node)
                               f (:fn node)
                               args (:args node)
                               current (get store key)
                               new-value (apply f current args)
                               new-store (assoc store key new-value)]
                           (assoc state
                             :store new-store
                             :value new-value
                             :control nil))
        ;; ============================================================
        ;; VM Primitives for Continuation Control
        ;; ============================================================
        ;; Get current continuation as a value
        :vm/current-continuation (assoc state
                                   :value {:type :reified-continuation,
                                           :continuation continuation,
                                           :environment environment}
                                   :control nil)
        ;; Park (suspend) - saves current continuation and halts
        :vm/park (let [park-id (gensym-id "parked")
                       parked-cont {:type :parked-continuation,
                                    :id park-id,
                                    :continuation continuation,
                                    :environment environment}
                       new-parked (assoc (or (:parked state) {})
                                    park-id parked-cont)]
                   (assoc state
                     :parked new-parked
                     :value parked-cont
                     :control nil
                     :continuation nil)) ; Halt execution
        ;; Resume a parked continuation with a value
        :vm/resume (let [parked-id (:parked-id node)
                         resume-value (:val node)
                         parked-cont (get-in state [:parked parked-id])]
                     (if parked-cont
                       (let [new-parked (dissoc (:parked state) parked-id)]
                         (assoc state
                           :parked new-parked
                           :value resume-value
                           :control nil
                           :continuation (:continuation parked-cont)
                           :environment (:environment parked-cont)))
                       (throw (ex-info
                                "Cannot resume: parked continuation not found"
                                {:parked-id parked-id}))))
        ;; ============================================================
        ;; Stream Operations (library-based)
        ;; ============================================================
        :stream/make (let [capacity (or (:buffer node) 1024)
                           [stream-ref new-state]
                             (stream/vm-stream-make state capacity)]
                       (assoc new-state
                         :value stream-ref
                         :control nil))
        :stream/put (assoc state
                      :control (:target node)
                      :continuation {:frame node,
                                     :parent continuation,
                                     :environment environment,
                                     :type :eval-stream-put-target})
        :stream/take (assoc state
                       :control (:source node)
                       :continuation {:frame node,
                                      :parent continuation,
                                      :environment environment,
                                      :type :eval-stream-source})
        ;; Unknown node type
        (throw (ex-info "Unknown AST node type" {:type type, :node node}))))))


;; Helper function to step the VM until completion
(defn run
  "Runs the CESK machine until the computation completes.
  Returns the final state with the result in :value."
  [initial-state ast]
  (loop [state (assoc initial-state :control ast)]
    (if (or (:control state) (:continuation state))
      (recur (eval state nil))
      state)))


;; Helper to check if VM is parked (blocked)
(defn parked?
  "Returns true if the VM has parked continuations waiting."
  [state]
  (boolean (seq (:parked state))))


;; Helper to get parked continuation IDs
(defn parked-ids
  "Returns the IDs of all parked continuations."
  [state]
  (keys (:parked state)))


;; =============================================================================
;; Register-Based Assembly VM
;; =============================================================================
;;
;; A register machine implementation of the CESK model where:
;; - Virtual registers (r0, r1, r2...) hold intermediate values
;; - Registers are a vector that grows as needed (no fixed limit)
;; - Each call frame has its own register space (saved/restored via :k)
;; - Continuation :k is always first-class and explicit
;; - No implicit call stack - continuation IS the stack
;;
;; Assembly is symbolic (keyword mnemonics). Bytecode would be numeric opcodes.
;;
;; Assembly instructions:
;;   [:loadk rd v]           - rd := literal v
;;   [:loadv rd name]        - rd := lookup name in env/store
;;   [:move rd rs]           - rd := rs
;;   [:closure rd params addr] - rd := closure capturing env
;;   [:call rd rf args]      - call fn in rf with args, result to rd
;;   [:return rs]            - return value in rs
;;   [:branch rt then else]  - conditional jump
;;   [:jump addr]            - unconditional jump
;; =============================================================================

(def opcode-table
  {:loadk 0,
   :loadv 1,
   :move 2,
   :closure 3,
   :call 4,
   :return 5,
   :branch 6,
   :jump 7,
   :gensym 8,
   :sget 9,
   :sput 10,
   :stream-make 11,
   :stream-put 12,
   :stream-take 13})


(def reverse-opcode-table (into {} (map (fn [[k v]] [v k]) opcode-table)))


(defn ast-datoms->register-assembly
  "Takes the AST as datoms and transforms it to register-based assembly.

   Assembly is a vector of symbolic instructions (keyword mnemonics, not numeric
   opcodes). A separate assembly->bytecode pass would encode these as numbers.

   Instructions:
     [:loadk rd v]              - rd := literal value v
     [:loadv rd name]           - rd := lookup name in env/store
     [:move rd rs]              - rd := rs
     [:closure rd params addr]  - rd := closure, body at addr
     [:call rd rf args]         - call fn in rf with args (reg vector), result to rd
     [:return rs]               - return value in rs
     [:branch rt then else]     - if rt then goto 'then' else goto 'else'
     [:jump addr]               - unconditional jump
     [:gensym rd prefix]        - rd := generate unique ID
     [:sget rd key]             - rd := store[key]
     [:sput rs key]             - store[key] := rs
     [:stream-make rd buf]      - rd := new stream
     [:stream-put rs rt]        - put rs into stream rt
     [:stream-take rd rs]       - rd := take from stream rs

   Uses simple linear register allocation."
  [ast-as-datoms]
  (let [;; Materialize and index datoms by entity
        datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)
        ;; Get attribute value for entity
        get-attr (fn [e attr]
                   (some (fn [[_ a v _ _]] (when (= a attr) v))
                         (get by-entity e)))
        ;; Find root entity (max of negative tempids = -1025)
        root-id (apply max (keys by-entity))
        ;; Assembly accumulator
        bytecode (atom [])
        emit! (fn [instr] (swap! bytecode conj instr))
        current-addr #(count @bytecode)
        ;; Register allocator (simple linear allocation, unbounded)
        reg-counter (atom 0)
        alloc-reg! (fn []
                     (let [r @reg-counter]
                       (swap! reg-counter inc)
                       r))
        reset-regs! (fn [] (reset! reg-counter 0))]
    ;; Compile entity to bytecode, returns the register holding the result
    (letfn
      [(compile-node [e]
         (let [node-type (get-attr e :yin/type)]
           (case node-type
             :literal (let [rd (alloc-reg!)]
                        (emit! [:loadk rd (get-attr e :yin/value)])
                        rd)
             :variable (let [rd (alloc-reg!)]
                         (emit! [:loadv rd (get-attr e :yin/name)])
                         rd)
             :lambda
               (let [params (get-attr e :yin/params)
                     body-ref (get-attr e :yin/body)
                     rd (alloc-reg!)
                     closure-idx (current-addr)]
                 ;; Emit closure with placeholder
                 (emit! [:closure rd params :placeholder])
                 ;; Jump over body
                 (let [jump-idx (current-addr)]
                   (emit! [:jump :placeholder])
                   ;; Body starts here - fresh register scope
                   (let [body-addr (current-addr)
                         saved-reg-counter @reg-counter]
                     (reset-regs!)
                     (let [result-reg (compile-node body-ref)]
                       (emit! [:return result-reg])
                       ;; Restore register counter
                       (reset! reg-counter saved-reg-counter)
                       ;; Patch addresses
                       (let [after-body (current-addr)]
                         (swap! bytecode assoc-in [closure-idx 3] body-addr)
                         (swap! bytecode assoc-in [jump-idx 1] after-body)))))
                 rd)
             :application (let [op-ref (get-attr e :yin/operator)
                                operand-refs (get-attr e :yin/operands)
                                ;; Compile operands first
                                arg-regs (mapv compile-node operand-refs)
                                ;; Compile operator
                                fn-reg (compile-node op-ref)
                                ;; Result register
                                rd (alloc-reg!)]
                            (emit! [:call rd fn-reg arg-regs])
                            rd)
             :if (let [test-ref (get-attr e :yin/test)
                       cons-ref (get-attr e :yin/consequent)
                       alt-ref (get-attr e :yin/alternate)
                       ;; Compile test
                       test-reg (compile-node test-ref)
                       ;; Result register (shared by both branches)
                       rd (alloc-reg!)
                       branch-idx (current-addr)]
                   ;; Emit branch with placeholders
                   (emit! [:branch test-reg :then :else])
                   ;; Consequent
                   (let [then-addr (current-addr)
                         cons-reg (compile-node cons-ref)]
                     (emit! [:move rd cons-reg])
                     (let [jump-idx (current-addr)]
                       (emit! [:jump :end])
                       ;; Alternate
                       (let [else-addr (current-addr)
                             alt-reg (compile-node alt-ref)]
                         (emit! [:move rd alt-reg])
                         ;; Patch addresses
                         (let [end-addr (current-addr)]
                           (swap! bytecode assoc-in [branch-idx 2] then-addr)
                           (swap! bytecode assoc-in [branch-idx 3] else-addr)
                           (swap! bytecode assoc-in [jump-idx 1] end-addr)))))
                   rd)
             ;; VM primitives
             :vm/gensym (let [rd (alloc-reg!)]
                          (emit! [:gensym rd (get-attr e :yin/prefix)])
                          rd)
             :vm/store-get (let [rd (alloc-reg!)]
                             (emit! [:sget rd (get-attr e :yin/key)])
                             rd)
             :vm/store-put (let [rd (alloc-reg!)]
                             (emit! [:loadk rd (get-attr e :yin/val)])
                             (emit! [:sput rd (get-attr e :yin/key)])
                             rd)
             ;; Stream operations
             :stream/make (let [rd (alloc-reg!)]
                            (emit! [:stream-make rd (get-attr e :yin/buffer)])
                            rd)
             :stream/put (let [target-ref (get-attr e :yin/target)
                               val-ref (get-attr e :yin/val)
                               val-reg (compile-node val-ref)
                               target-reg (compile-node target-ref)]
                           (emit! [:stream-put val-reg target-reg])
                           val-reg)
             :stream/take (let [source-ref (get-attr e :yin/source)
                                source-reg (compile-node source-ref)
                                rd (alloc-reg!)]
                            (emit! [:stream-take rd source-reg])
                            rd)
             ;; Unknown type
             (throw (ex-info
                      "Unknown node type in register assembly compilation"
                      {:type node-type, :entity e})))))]
      (let [result-reg (compile-node root-id)]
        (emit! [:return result-reg])
        @bytecode))))


(comment
  ;; ast-datoms->register-assembly exploration
  ;; Pipeline: AST map -> datoms -> register assembly
  ;; Simple literal
  (ast-datoms->register-assembly (ast->datoms {:type :literal, :value 42}))
  ;; => [[:loadk 0 42]]
  ;; Variable reference
  (ast-datoms->register-assembly (ast->datoms {:type :variable, :name 'x}))
  ;; => [[:loadv 0 x]]
  ;; Application (+ 1 2)
  (ast-datoms->register-assembly (ast->datoms
                                   {:type :application,
                                    :operator {:type :variable, :name '+},
                                    :operands [{:type :literal, :value 1}
                                               {:type :literal, :value 2}]}))
  ;; => [[:loadk 0 1]        ; r0 = 1
  ;;     [:loadk 1 2]        ; r1 = 2
  ;;     [:loadv 2 +]        ; r2 = +
  ;;     [:call 3 2 [0 1]]]  ; r3 = r2(r0, r1)
  ;; Lambda (fn [x] x)
  (ast-datoms->register-assembly
    (ast->datoms
      {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}))
  ;; => [[:closure 0 [x] 2]  ; r0 = closure, body at addr 2
  ;;     [:jump 4]           ; skip body
  ;;     [:loadv 0 x]        ; body: r0 = x
  ;;     [:return 0]]        ; return r0
  ;; Conditional (if true 1 2)
  (ast-datoms->register-assembly (ast->datoms
                                   {:type :if,
                                    :test {:type :literal, :value true},
                                    :consequent {:type :literal, :value 1},
                                    :alternate {:type :literal, :value 2}}))
  ;; => [[:loadk 0 true]     ; r0 = test
  ;;     [:branch 0 2 5]     ; if r0 goto 2 else 5
  ;;     [:loadk 2 1]        ; r2 = 1 (consequent)
  ;;     [:move 1 2]         ; r1 = r2 (result)
  ;;     [:jump 7]           ; skip alternate
  ;;     [:loadk 3 2]        ; r3 = 2 (alternate)
  ;;     [:move 1 3]]        ; r1 = r3 (result)
  ;; Full pipeline: AST -> datoms -> register assembly -> execute
  (let [ast {:type :application,
             :operator {:type :variable, :name '+},
             :operands [{:type :literal, :value 1} {:type :literal, :value 2}]}
        bytecode (ast-datoms->register-assembly (ast->datoms ast))
        state (make-rbc-state bytecode {'+ +})
        result (rbc-run state)]
    (:value result))
  ;; => 3
)


(defn register-assembly->bytecode
  "Convert register assembly (keyword mnemonics) to numeric bytecode.

   Returns {:bc [int...] :pool [value...]}

   The bytecode is a flat vector of integers. The pool holds all
   non-register operands (literals, symbols, param vectors).

   Address fixup: assembly addresses index into the instruction vector
   (instruction 0, 1, 2...). Bytecode addresses index into the flat
   int vector (byte offset 0, 3, 6...). All jump targets are rewritten."
  [asm-instructions]
  (let [;; Phase 1 + 2 combined: build pool while emitting bytecode
        pool (atom [])
        pool-index (atom {})
        intern! (fn [v]
                  (if-let [idx (get @pool-index v)]
                    idx
                    (let [idx (count @pool)]
                      (swap! pool conj v)
                      (swap! pool-index assoc v idx)
                      idx)))
        bytecode (atom [])
        ;; instruction index -> byte offset
        instr-offsets (atom {})
        ;; [byte-position assembly-address] pairs needing fixup
        fixups (atom [])
        emit! (fn [& ints] (swap! bytecode into ints))
        current-offset #(count @bytecode)
        emit-fixup! (fn [asm-addr]
                      (swap! fixups conj [(current-offset) asm-addr])
                      (swap! bytecode conj asm-addr))]
    ;; Phase 2: Emit bytecode
    (doseq [[idx instr] (map-indexed vector asm-instructions)]
      (swap! instr-offsets assoc idx (current-offset))
      (let [[op & args] instr]
        (case op
          :loadk (let [[rd v] args]
                   (emit! (opcode-table :loadk) rd (intern! v)))
          :loadv (let [[rd name] args]
                   (emit! (opcode-table :loadv) rd (intern! name)))
          :move (let [[rd rs] args] (emit! (opcode-table :move) rd rs))
          :closure (let [[rd params addr] args]
                     (emit! (opcode-table :closure) rd (intern! params))
                     (emit-fixup! addr))
          :call (let [[rd rf arg-regs] args]
                  (emit! (opcode-table :call) rd rf (count arg-regs))
                  (doseq [ar arg-regs] (emit! ar)))
          :return (let [[rs] args] (emit! (opcode-table :return) rs))
          :branch (let [[rt then-addr else-addr] args]
                    (emit! (opcode-table :branch) rt)
                    (emit-fixup! then-addr)
                    (emit-fixup! else-addr))
          :jump (let [[addr] args]
                  (emit! (opcode-table :jump))
                  (emit-fixup! addr))
          :gensym (let [[rd prefix] args]
                    (emit! (opcode-table :gensym) rd (intern! prefix)))
          :sget (let [[rd key] args]
                  (emit! (opcode-table :sget) rd (intern! key)))
          :sput (let [[rs key] args]
                  (emit! (opcode-table :sput) rs (intern! key)))
          :stream-make (let [[rd buf] args]
                         (emit! (opcode-table :stream-make) rd buf))
          :stream-put (let [[rs rt] args]
                        (emit! (opcode-table :stream-put) rs rt))
          :stream-take (let [[rd rs] args]
                         (emit! (opcode-table :stream-take) rd rs)))))
    ;; Phase 3: Fix addresses
    (let [offsets @instr-offsets
          fixed (reduce (fn [bc [pos asm-addr]]
                          (assoc bc pos (get offsets asm-addr asm-addr)))
                  @bytecode
                  @fixups)]
      {:bc fixed, :pool @pool})))


(defn make-rbc-state
  "Create initial register-based assembly VM state."
  ([bytecode] (make-rbc-state bytecode {}))
  ([bytecode env]
   {:regs [],           ; virtual registers (grows as needed)
    :k nil,             ; continuation (always explicit)
    :env env,           ; lexical environment
    :store {},          ; global store
    :ip 0,              ; instruction pointer
    :bytecode bytecode, ; instruction vector
    :parked {}}))


(defn rbc-get-reg
  "Get value from register. Returns nil if register not yet allocated."
  [state r]
  (get (:regs state) r))


(defn- rbc-set-reg
  "Set register to value. Grows register vector if needed."
  [state r v]
  (let [regs (:regs state)
        regs (if (> r (dec (count regs)))
               ;; Grow vector to accommodate register
               (into regs (repeat (- (inc r) (count regs)) nil))
               regs)]
    (assoc state :regs (assoc regs r v))))


(defn rbc-step
  "Execute one assembly instruction. Returns updated state."
  [state]
  (let [{:keys [bytecode ip regs env store k]} state
        instr (get bytecode ip)]
    (if (nil? instr)
      ;; End of assembly without :return - should not happen with compiled
      ;; assembly
      (throw (ex-info "Assembly ended without :return instruction" {:ip ip}))
      (let [[op & args] instr]
        (case op
          ;; Load constant into register
          :loadk (let [[rd v] args]
                   (-> state
                       (rbc-set-reg rd v)
                       (update :ip inc)))
          ;; Load variable from environment/store
          :loadv (let [[rd name] args
                       v (or (get env name)
                             (get store name)
                             (module/resolve-symbol name))]
                   (-> state
                       (rbc-set-reg rd v)
                       (update :ip inc)))
          ;; Move register to register
          :move (let [[rd rs] args]
                  (-> state
                      (rbc-set-reg rd (rbc-get-reg state rs))
                      (update :ip inc)))
          ;; Create closure
          :closure (let [[rd params body-addr] args
                         closure {:type :rbc-closure,
                                  :params params,
                                  :body-addr body-addr,
                                  :env env,
                                  :bytecode bytecode}]
                     (-> state
                         (rbc-set-reg rd closure)
                         (update :ip inc)))
          ;; Function call
          :call (let [[rd rf arg-regs] args
                      fn-val (rbc-get-reg state rf)
                      fn-args (mapv #(rbc-get-reg state %) arg-regs)]
                  (cond
                    ;; Primitive function
                    (fn? fn-val)
                      (let [result (apply fn-val fn-args)]
                        (if (module/effect? result)
                          ;; Handle effect
                          (case (:effect result)
                            :vm/store-put (-> state
                                              (assoc-in [:store (:key result)]
                                                        (:val result))
                                              (rbc-set-reg rd (:val result))
                                              (update :ip inc))
                            ;; Other effects...
                            (throw (ex-info "Unhandled effect in rbc-step"
                                            {:effect result})))
                          ;; Regular value
                          (-> state
                              (rbc-set-reg rd result)
                              (update :ip inc))))
                    ;; User-defined closure
                    (= :rbc-closure (:type fn-val))
                      (let [{:keys [params body-addr env bytecode]} fn-val
                            new-frame {:type :call-frame,
                                       :return-reg rd,
                                       :return-ip (inc ip),
                                       :saved-regs regs,
                                       :saved-env (:env state),
                                       :parent k}
                            new-env (merge env (zipmap params fn-args))]
                        (-> state
                            (assoc :regs []) ; fresh register frame (grows
                            ;; as needed)
                            (assoc :k new-frame)
                            (assoc :env new-env)
                            (assoc :ip body-addr)))
                    :else (throw (ex-info "Cannot call non-function"
                                          {:fn fn-val}))))
          ;; Return from function
          :return (let [[rs] args
                        result (rbc-get-reg state rs)]
                    (if (nil? k)
                      ;; Top level - halt with result
                      (assoc state
                        :halted true
                        :value result)
                      ;; Restore caller context
                      (let [{:keys [return-reg return-ip saved-regs saved-env
                                    parent]}
                              k]
                        (-> state
                            (assoc :regs (assoc saved-regs return-reg result))
                            (assoc :k parent)
                            (assoc :env saved-env)
                            (assoc :ip return-ip)))))
          ;; Conditional branch
          :branch (let [[rt then-addr else-addr] args
                        test-val (rbc-get-reg state rt)]
                    (assoc state :ip (if test-val then-addr else-addr)))
          ;; Unconditional jump
          :jump (let [[addr] args] (assoc state :ip addr))
          ;; Unknown instruction
          (throw (ex-info "Unknown assembly instruction"
                          {:op op, :instr instr})))))))


(defn rbc-run
  "Run register assembly VM to completion or until blocked."
  [state]
  (loop [s state]
    (if (or (:halted s) (= :yin/blocked (:value s))) s (recur (rbc-step s)))))


(comment
  ;; Register assembly VM exploration. Simple: load constant
  (let [bytecode [[:loadk 0 42]]
        state (make-rbc-state bytecode)
        result (rbc-run state)]
    (:value result))
  ;; => 42
  ;; Primitive call: (+ 1 2)
  (let [bytecode [[:loadk 0 1] [:loadk 1 2] [:loadv 2 '+] [:call 3 2 [0 1]]]
        state (make-rbc-state bytecode {'+ +})
        result (rbc-run state)]
    (rbc-get-reg result 3))
  ;; => 3
  ;; Closure call: ((fn [x] x) 42)
  (let [bytecode [[:loadk 0 42]       ; r0 = 42
                  [:closure 1 ['x] 4] ; r1 = closure, body at 4
                  [:call 2 1 [0]]     ; r2 = r1(r0)
                  [:jump 6]           ; skip closure body
                  [:loadv 0 'x]       ; body: r0 = x
                  [:return 0]]        ; return r0
        state (make-rbc-state bytecode)
        result (rbc-run state)]
    (:value result))
  ;; => 42
  ;; Closure with arithmetic: ((fn [x] (+ x 1)) 5)
  (let [bytecode [[:loadk 0 5]        ; r0 = 5
                  [:closure 1 ['x] 4] ; r1 = closure
                  [:call 2 1 [0]]     ; r2 = r1(r0)
                  [:jump 9]           ; skip body
                  ;; Closure body at addr 4
                  [:loadv 0 'x]       ; r0 = x
                  [:loadk 1 1]        ; r1 = 1
                  [:loadv 2 '+]       ; r2 = +
                  [:call 3 2 [0 1]]   ; r3 = +(r0, r1)
                  [:return 3]]        ; return r3
        state (make-rbc-state bytecode {'+ +})
        result (rbc-run state)]
    (:value result))
  ;; => 6
  ;; Conditional: (if true 1 2)
  (let [bytecode [[:loadk 0 true] ; r0 = true
                  [:branch 0 2 3] ; if r0 goto 2 else 3
                  [:loadk 1 1]    ; r1 = 1 (then)
                  [:loadk 1 2]]   ; r1 = 2 (else)
        state (make-rbc-state bytecode)
        result (rbc-run state)]
    (rbc-get-reg result 1))
  ;; => 1 (because we branched to addr 2)
)


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================
;;
;; Bytecode is a flat vector of integers produced by
;; register-assembly->bytecode.
;; Opcodes are integers (see opcode-table). Non-integer operands (literals,
;; symbols, param vectors) live in a constant pool referenced by index.
;;
;; State uses :bc and :pool instead of :bytecode.
;; IP indexes into the flat int vector, not an instruction vector.
;; =============================================================================

(defn make-rbc-bc-state
  "Create initial bytecode VM state from {:bc [...] :pool [...]}."
  ([compiled] (make-rbc-bc-state compiled {}))
  ([{:keys [bc pool]} env]
   {:regs [],
    :k nil,
    :env env,
    :store {},
    :ip 0,
    :bc bc,
    :pool pool,
    :parked {}}))


(defn rbc-step-bc
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [{:keys [bc pool ip regs env store k]} state
        op (get bc ip)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:ip ip}))
      (case (int op)
        ;; loadk: rd = pool[const-idx]
        0 (let [rd (get bc (+ ip 1))
                v (get pool (get bc (+ ip 2)))]
            (-> state
                (rbc-set-reg rd v)
                (assoc :ip (+ ip 3))))
        ;; loadv: rd = env/store lookup of pool[const-idx]
        1 (let [rd (get bc (+ ip 1))
                name (get pool (get bc (+ ip 2)))
                v (or (get env name)
                      (get store name)
                      (module/resolve-symbol name))]
            (-> state
                (rbc-set-reg rd v)
                (assoc :ip (+ ip 3))))
        ;; move: rd = rs
        2 (let [rd (get bc (+ ip 1))
                rs (get bc (+ ip 2))]
            (-> state
                (rbc-set-reg rd (rbc-get-reg state rs))
                (assoc :ip (+ ip 3))))
        ;; closure: rd = closure{params=pool[idx], body-addr, env}
        3 (let [rd (get bc (+ ip 1))
                params (get pool (get bc (+ ip 2)))
                body-addr (get bc (+ ip 3))
                closure {:type :rbc-closure,
                         :params params,
                         :body-addr body-addr,
                         :env env,
                         :bc bc,
                         :pool pool}]
            (-> state
                (rbc-set-reg rd closure)
                (assoc :ip (+ ip 4))))
        ;; call: rd = fn(args...)
        4 (let [rd (get bc (+ ip 1))
                rf (get bc (+ ip 2))
                argc (get bc (+ ip 3))
                fn-args (loop [i 0
                               args (transient [])]
                          (if (< i argc)
                            (recur (inc i)
                                   (conj! args
                                          (rbc-get-reg state
                                                       (get bc (+ ip 4 i)))))
                            (persistent! args)))
                fn-val (rbc-get-reg state rf)
                next-ip (+ ip 4 argc)]
            (cond (fn? fn-val)
                    (let [result (apply fn-val fn-args)]
                      (if (module/effect? result)
                        (case (:effect result)
                          :vm/store-put (-> state
                                            (assoc-in [:store (:key result)]
                                                      (:val result))
                                            (rbc-set-reg rd (:val result))
                                            (assoc :ip next-ip))
                          (throw (ex-info "Unhandled effect in rbc-step-bc"
                                          {:effect result})))
                        (-> state
                            (rbc-set-reg rd result)
                            (assoc :ip next-ip))))
                  (= :rbc-closure (:type fn-val))
                    (let [{:keys [params body-addr env bc pool]} fn-val
                          new-frame {:type :call-frame,
                                     :return-reg rd,
                                     :return-ip next-ip,
                                     :saved-regs regs,
                                     :saved-env (:env state),
                                     :saved-bc (:bc state),
                                     :saved-pool (:pool state),
                                     :parent k}
                          new-env (merge env (zipmap params fn-args))]
                      (-> state
                          (assoc :regs [])
                          (assoc :k new-frame)
                          (assoc :env new-env)
                          (assoc :bc bc)
                          (assoc :pool pool)
                          (assoc :ip body-addr)))
                  :else (throw (ex-info "Cannot call non-function"
                                        {:fn fn-val}))))
        ;; return
        5 (let [rs (get bc (+ ip 1))
                result (rbc-get-reg state rs)]
            (if (nil? k)
              (assoc state
                :halted true
                :value result)
              (let [{:keys [return-reg return-ip saved-regs saved-env saved-bc
                            saved-pool parent]}
                      k]
                (-> state
                    (assoc :regs (assoc saved-regs return-reg result))
                    (assoc :k parent)
                    (assoc :env saved-env)
                    (assoc :bc (or saved-bc (:bc state)))
                    (assoc :pool (or saved-pool (:pool state)))
                    (assoc :ip return-ip)))))
        ;; branch
        6 (let [rt (get bc (+ ip 1))
                then-addr (get bc (+ ip 2))
                else-addr (get bc (+ ip 3))
                test-val (rbc-get-reg state rt)]
            (assoc state :ip (if test-val then-addr else-addr)))
        ;; jump
        7 (let [addr (get bc (+ ip 1))] (assoc state :ip addr))
        ;; Unknown opcode
        (throw (ex-info "Unknown bytecode opcode" {:op op, :ip ip}))))))


(defn rbc-run-bc
  "Run bytecode VM to completion or until blocked."
  [state]
  (loop [s state]
    (if (or (:halted s) (= :yin/blocked (:value s)))
      s
      (recur (rbc-step-bc s)))))


(comment
  ;; Bytecode VM exploration. Assembly -> bytecode conversion
  (register-assembly->bytecode [[:loadk 0 42] [:return 0]])
  ;; => {:bc [0 0 0, 5 0], :pool [42]}
  ;; Full pipeline: AST -> datoms -> assembly -> bytecode -> execute
  (let [ast {:type :application,
             :operator {:type :variable, :name '+},
             :operands [{:type :literal, :value 1} {:type :literal, :value 2}]}
        asm (ast-datoms->register-assembly (ast->datoms ast))
        compiled (register-assembly->bytecode asm)
        state (make-rbc-bc-state compiled {'+ +})
        result (rbc-run-bc state)]
    (:value result))
  ;; => 3
)

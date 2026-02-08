(ns yin.vm
  (:refer-clojure :exclude [eval])
  (:require [datascript.core :as d]
            #?@(:cljs [[datascript.db :as db] [datascript.query :as dq]])
            [yin.module :as module]
            [yin.stream :as stream]))


;; Fix DataScript query under Closure advanced compilation.
;; ClojureScript uses .v for protocol bitmaps on all types. Under :advanced,
;; the Closure Compiler renames Datom's value field from .v to avoid collision,
;; but DataScript's query engine accesses it via raw JS bracket notation
;; datom["v"], which returns the protocol bitmap (an integer) instead of the
;; actual value. Fix: convert Datom tuples to JS arrays using nth (which goes
;; through IIndexed and resolves the renamed field correctly).
#?(:cljs (let [original-lookup dq/lookup-pattern-db
               prop->idx {"e" 0, "a" 1, "v" 2, "tx" 3}]
           (set! dq/lookup-pattern-db
                 (fn [context db pattern]
                   (let [rel (original-lookup context db pattern)
                         tuples (:tuples rel)]
                     (if (and (seq tuples) (instance? db/Datom (first tuples)))
                       (let [new-attrs (reduce-kv (fn [m k v]
                                                    (assoc m
                                                      k (get prop->idx v v)))
                                                  {}
                                                  (:attrs rel))
                             new-tuples (mapv (fn [d]
                                                (to-array [(nth d 0) (nth d 1)
                                                           (nth d 2) (nth d 3)
                                                           (nth d 4)]))
                                          tuples)]
                         (dq/->Relation new-attrs new-tuples))
                       rel))))))


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
   Expands cardinality-many vector values into individual assertions."
  [datoms]
  (mapcat (fn [[e a v _t _m]]
            (if (and (contains? cardinality-many-attrs a) (vector? v))
              (map (fn [ref] [:db/add e a ref]) v)
              [[:db/add e a v]]))
    datoms))


(defn transact!
  "Create a DataScript connection, transact datoms, return {:db db :tempids tempids}.
   Encapsulates DataScript as an implementation detail."
  [datoms]
  (let [tx-data (datoms->tx-data datoms)
        conn (d/create-conn schema)
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:db @conn, :tempids tempids}))


(defn q
  "Run a Datalog query against a DataScript db value."
  [query db]
  (d/q query db))


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

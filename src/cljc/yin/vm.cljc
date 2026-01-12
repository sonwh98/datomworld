(ns yin.vm
  (:refer-clojure :exclude [eval])
  (:require [yin.stream :as stream]))

;; Counter for generating unique IDs
(def ^:private id-counter (atom 0))

(defn gensym-id
  "Generate a unique keyword ID with optional prefix."
  ([] (gensym-id "id"))
  ([prefix]
   (keyword (str prefix "-" (swap! id-counter inc)))))

;; Primitive operations
(def primitives
  {'+ (fn [a b] (+ a b))
   '- (fn [a b] (- a b))
   '* (fn [a b] (* a b))
   '/ (fn [a b] (/ a b))
   '= (fn [a b] (= a b))
   '< (fn [a b] (< a b))
   '> (fn [a b] (> a b))
   'not (fn [a] (not a))
   'nil? (fn [a] (nil? a))
   'empty? (fn [a] (empty? a))
   'first (fn [a] (first a))
   'rest (fn [a] (vec (rest a)))
   'conj (fn [coll x] (conj coll x))
   'assoc (fn [m k v] (assoc m k v))
   'get (fn [m k] (get m k))
   'vec (fn [coll] (vec coll))})

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
        {:keys [type] :as node} (or ast control)]

    ;; If control is nil but we have a continuation, handle it
    (if (and (nil? node) continuation)
      (let [cont-type (:type continuation)]
        (case cont-type
          :eval-operator
          (let [frame (:frame continuation)
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
                updated-frame (assoc frame :evaluated-operands evaluated-operands)
                saved-env (:environment continuation)]
            (assoc state
                   :control updated-frame
                   :environment (or saved-env environment)
                   :continuation (:parent continuation)
                   :value nil))

          :eval-test
          (let [frame (:frame continuation)
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
                    parked-cont {:type :parked-continuation
                                 :id (gensym-id "taker")
                                 :continuation (:parent continuation)
                                 :environment environment}
                    store (:store state)
                    new-store (update-in store [stream-id :takers] conj parked-cont)]
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
          :eval-stream-put-target
          (let [frame (:frame continuation)
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
              ;; Taker was waiting - need to resume it
              ;; For now, just complete the put and let scheduler handle resume
              (assoc (:state result)
                     :control nil
                     :continuation (:parent continuation))
              ;; No taker - value buffered
              (assoc (:state result)
                     :control nil
                     :continuation (:parent continuation))))

          ;; Default for unknown continuation
          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type
                           :continuation continuation}))))

      ;; Otherwise handle the node type
      (case type
        ;; Literals evaluate to themselves
        :literal
        (let [{:keys [value]} node]
          (assoc state :value value :control nil))

        ;; Variable lookup
        :variable
        (let [{:keys [name]} node
              value (get environment name)]
          (assoc state :value value :control nil))

        ;; Lambda creates a closure
        :lambda
        (let [{:keys [params body]} node
              closure {:type :closure
                       :params params
                       :body body
                       :environment environment}]
          (assoc state :value closure :control nil))

        ;; Function application
        :application
        (let [{:keys [operator operands evaluated-operands fn-value operator-evaluated?]} node
              evaluated-operands (or evaluated-operands [])]
          (cond
            ;; All evaluated - apply function
            (and operator-evaluated?
                 (= (count evaluated-operands) (count operands)))
            (cond
              ;; Primitive function
              (fn? fn-value)
              (let [result (apply fn-value evaluated-operands)]
                (assoc state :value result :control nil))

              ;; User-defined closure
              (= :closure (:type fn-value))
              (let [{:keys [params body environment]} fn-value
                    extended-env (merge environment (zipmap params evaluated-operands))]
                (assoc state
                       :control body
                       :environment extended-env
                       :continuation continuation))

              :else
              (throw (ex-info "Cannot apply non-function"
                              {:fn-value fn-value})))

            ;; Evaluate operands one by one
            operator-evaluated?
            (let [next-operand (nth operands (count evaluated-operands))]
              (assoc state
                     :control next-operand
                     :continuation {:frame node
                                    :parent continuation
                                    :environment environment
                                    :type :eval-operand}))

            ;; Evaluate operator first
            :else
            (assoc state
                   :control operator
                   :continuation {:frame node
                                  :parent continuation
                                  :environment environment
                                  :type :eval-operator})))

        ;; Conditional
        :if
        (let [{:keys [test consequent alternate]} node]
          (if (:evaluated-test? node)
            ;; Test evaluated, choose branch
            (let [test-value (:value state)
                  branch (if test-value consequent alternate)]
              (assoc state :control branch))
            ;; Evaluate test first
            (assoc state
                   :control test
                   :continuation {:frame node
                                  :parent continuation
                                  :environment environment
                                  :type :eval-test})))

        ;; ============================================================
        ;; VM Primitives for Store Operations
        ;; ============================================================

        ;; Generate unique ID
        :vm/gensym
        (let [prefix (or (:prefix node) "id")
              id (gensym-id prefix)]
          (assoc state :value id :control nil))

        ;; Read from store
        :vm/store-get
        (let [key (:key node)
              value (get store key)]
          (assoc state :value value :control nil))

        ;; Write to store
        :vm/store-put
        (let [key (:key node)
              value (:val node)
              new-store (assoc store key value)]
          (assoc state
                 :store new-store
                 :value value
                 :control nil))

        ;; Update store (apply function to current value)
        :vm/store-update
        (let [key (:key node)
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
        :vm/current-continuation
        (assoc state
               :value {:type :reified-continuation
                       :continuation continuation
                       :environment environment}
               :control nil)

        ;; Park (suspend) - saves current continuation and halts
        :vm/park
        (let [park-id (gensym-id "parked")
              parked-cont {:type :parked-continuation
                           :id park-id
                           :continuation continuation
                           :environment environment}
              new-parked (assoc (or (:parked state) {}) park-id parked-cont)]
          (assoc state
                 :parked new-parked
                 :value parked-cont
                 :control nil
                 :continuation nil)) ;; Halt execution

        ;; Resume a parked continuation with a value
        :vm/resume
        (let [parked-id (:parked-id node)
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
            (throw (ex-info "Cannot resume: parked continuation not found"
                            {:parked-id parked-id}))))

        ;; ============================================================
        ;; Stream Operations (library-based)
        ;; ============================================================

        :stream/make
        (let [capacity (or (:buffer node) 1024)
              [stream-ref new-state] (stream/vm-stream-make state capacity)]
          (assoc new-state :value stream-ref :control nil))

        :stream/put
        (assoc state
               :control (:target node)
               :continuation {:frame node
                              :parent continuation
                              :environment environment
                              :type :eval-stream-put-target})

        :stream/take
        (assoc state
               :control (:source node)
               :continuation {:frame node
                              :parent continuation
                              :environment environment
                              :type :eval-stream-source})

        ;; Unknown node type
        (throw (ex-info "Unknown AST node type"
                        {:type type
                         :node node}))))))

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

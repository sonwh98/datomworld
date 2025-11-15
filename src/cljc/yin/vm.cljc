(ns yin.vm
  (:refer-clojure :exclude [eval]))

;; Primitive operations
(def primitives
  {'+ (fn [a b] (+ a b))
   '- (fn [a b] (- a b))
   '* (fn [a b] (* a b))
   '/ (fn [a b] (/ a b))
   '= (fn [a b] (= a b))
   '< (fn [a b] (< a b))
   '> (fn [a b] (> a b))})

(defn eval
  "Steps the CESK machine to evaluate an AST node.

  State is a map containing:
    :control - current AST node or continuation frame
    :environment - persistent lexical scope map
    :store - immutable memory graph
    :continuation - reified, persistent control context

  AST is a universal map-based structure with:
    :type - node type (e.g., :literal, :variable, :application, :lambda, :if, etc.)
    :value - node-specific data
    ... other node-specific fields

  Returns updated state after one step of evaluation."
  [state ast]
  (let [{:keys [control environment continuation]} state
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

          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type
                           :continuation continuation}))))

      ;; Otherwise handle the node type
      (case type
      ;; Literals evaluate to themselves
        :literal
        (let [{:keys [value]} node]
        ;; Always just return the value, let continuation handling happen in default case
          (assoc state :value value :control nil))

      ;; Variable lookup
        :variable
        (let [{:keys [name]} node
              value (get environment name)]
        ;; Always just return the value, let continuation handling happen in default case
          (assoc state :value value :control nil))

      ;; Lambda creates a closure
        :lambda
        (let [{:keys [params body]} node
              closure {:type :closure
                       :params params
                       :body body
                       :environment environment}]
        ;; Always just return the closure, let continuation handling happen in default case
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

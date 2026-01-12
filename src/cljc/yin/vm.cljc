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

          ;; Stream Continuations
          :eval-stream-source
          (let [stream-ref (:value state)
                store (:store state)
                stream-id (:id stream-ref)
                stream (get store stream-id)]
            (cond
              ;; Validation
              (nil? stream)
              (throw (ex-info "Invalid stream reference" {:ref stream-ref}))

              ;; Case 1: Value in buffer -> Consume
              (not (empty? (:buffer stream)))
              (let [[val & rest-buf] (:buffer stream)
                    new-store (assoc-in store [stream-id :buffer] (vec rest-buf))]
                (assoc state
                       :value val
                       :store new-store
                       :control nil
                       :continuation (:parent continuation)))

              ;; Case 2: Writer waiting (Rendezvous / Handoff) -> Not implemented yet

              ;; Case 3: Empty -> Park (Block)
              :else
              (let [parked-ctx (:parent continuation)
                    new-store (update-in store [stream-id :takers] conj parked-ctx)]
                (assoc state
                       :store new-store
                       :control nil
                       :continuation nil ;; Suspend Execution
                       :value :yin/blocked))))

          :eval-stream-put-target
          (let [frame (:frame continuation)
                stream-ref (:value state)
                val-node (:val frame)]
            (assoc state
                   :control val-node
                   :continuation (assoc continuation
                                        :type :eval-stream-put-val
                                        :stream-ref stream-ref)))

          :eval-stream-put-val
          (let [val (:value state)
                stream-ref (:stream-ref continuation)
                store (:store state)
                stream-id (:id stream-ref)
                stream (get store stream-id)]
            (cond
              (nil? stream) (throw (ex-info "Invalid stream ref" {:ref stream-ref}))

               ;; Check if Takers are waiting (Rendezvous)
               ;; (not (empty? (:takers stream))) -> Not implemented in prototype
               ;; We just buffer.

              :else
              (let [current-buf (or (:buffer stream) [])
                    new-buf (conj current-buf val)
                    new-store (assoc-in store [stream-id :buffer] new-buf)]
                (assoc state
                       :store new-store
                       :value val ;; Put returns the value
                       :control nil
                       :continuation (:parent continuation)))))

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

        ;; Stream OPS
        :stream/make
        (let [id (keyword (str "stream-" #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid))))
              buffer-size (or (:buffer node) 1024)
              new-store (assoc (:store state)
                               id
                               {:type :stream
                                :buffer []
                                :takers []
                                :capacity buffer-size})]
          (assoc state
                 :store new-store
                 :value {:type :stream-ref :id id}
                 :control nil))

        :stream/take
        (assoc state
               :control (:source node)
               :continuation {:frame node
                              :parent continuation
                              :environment environment
                              :type :eval-stream-source})

        :stream/put
        (assoc state
               :control (:target node)
               :continuation {:frame node
                              :parent continuation
                              :environment environment
                              :type :eval-stream-put-target})

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

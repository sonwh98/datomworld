(ns yin.datom-bytecode
  "Yin VM Bytecode represented as Datom Streams.
   Follows the architecture where AST is a structural stream and 
   execution is a linear stream of datoms."
  (:require #?(:clj [datalevin.core :as d]
               :cljs [datascript.core :as d])
            [yin.vm :as vm]
            [clojure.walk :as walk]))

;; --- Schema ---

(def schema
  #?(:clj
     {:ast/node-id {:db/unique :db.unique/identity}
      :ast/type    {:db/valueType :db.type/keyword}
      :ast/operator {:db/valueType :db.type/ref}
      :ast/operands {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
      :ast/body     {:db/valueType :db.type/ref}
      :ast/test     {:db/valueType :db.type/ref}
      :ast/consequent {:db/valueType :db.type/ref}
      :ast/alternate {:db/valueType :db.type/ref}

      :exec/step-id    {:db/unique :db.unique/identity}
      :exec/instruction {:db/valueType :db.type/keyword}
      :exec/node       {:db/valueType :db.type/ref}
      :exec/order      {:db/valueType :db.type/long}
      :exec/target     {:db/valueType :db.type/long}
      :exec/metadata   {:db/valueType :db.type/ref}}
     :cljs
     {:ast/node-id {:db/unique :db.unique/identity}
      :ast/operator {:db/valueType :db.type/ref}
      :ast/operands {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
      :ast/body     {:db/valueType :db.type/ref}
      :ast/test     {:db/valueType :db.type/ref}
      :ast/consequent {:db/valueType :db.type/ref}
      :ast/alternate {:db/valueType :db.type/ref}

      :exec/step-id    {:db/unique :db.unique/identity}
      :exec/node       {:db/valueType :db.type/ref}
      :exec/metadata   {:db/valueType :db.type/ref}}))

;; --- Decomposition (AST Map -> Datoms) ---

(defn- gen-id [] (str (random-uuid)))

(defn decompose-ast
  "Recursively decomposes a nested AST map into flat entities for Datalevin."
  [ast]
  (let [node-id (gen-id)]
    (case (:type ast)
      :literal
      [{:ast/node-id node-id :ast/type :literal :ast/value (:value ast)}]

      :variable
      [{:ast/node-id node-id :ast/type :variable :ast/name (:name ast)}]

      :lambda
      (let [body-datoms (decompose-ast (:body ast))
            body-id (:ast/node-id (first body-datoms))]
        (into [{:ast/node-id node-id
                :ast/type :lambda
                :ast/params (vec (:params ast))
                :ast/body {:ast/node-id body-id}}]
              body-datoms))

      :application
      (let [op-datoms (decompose-ast (:operator ast))
            op-id (:ast/node-id (first op-datoms))
            operand-results (mapv decompose-ast (:operands ast))
            operand-ids (mapv (fn [res] {:ast/node-id (:ast/node-id (first res))}) operand-results)]
        (-> [{:ast/node-id node-id
              :ast/type :application
              :ast/operator {:ast/node-id op-id}
              :ast/operands operand-ids}]
            (into op-datoms)
            (into (mapcat identity operand-results))))

      :if
      (let [test-datoms (decompose-ast (:test ast))
            cons-datoms (decompose-ast (:consequent ast))
            alt-datoms (decompose-ast (:alternate ast))]
        (-> [{:ast/node-id node-id
              :ast/type :if
              :ast/test {:ast/node-id (:ast/node-id (first test-datoms))}
              :ast/consequent {:ast/node-id (:ast/node-id (first cons-datoms))}
              :ast/alternate {:ast/node-id (:ast/node-id (first alt-datoms))}}]
            (into test-datoms)
            (into cons-datoms)
            (into alt-datoms)))

      (throw (ex-info "Unknown AST type" {:ast ast})))))

;; --- Flattening (Structural Datoms -> Execution Stream Datoms) ---

(defn- emit-step
  ([node-id instruction order] (emit-step node-id instruction order nil))
  ([node-id instruction order metadata-id]
   (cond-> {:exec/step-id (gen-id)
            :exec/instruction instruction
            :exec/node [:ast/node-id node-id]
            :exec/order order}
     metadata-id (assoc :exec/metadata [:db/id metadata-id]))))

(defn- flatten-node [db node-id start-order]

  (let [node (d/pull db '[:ast/type

                          {:ast/operator [:ast/node-id]}

                          {:ast/operands [:ast/node-id]}

                          {:ast/body [:ast/node-id]}

                          {:ast/test [:ast/node-id]}

                          {:ast/consequent [:ast/node-id]}

                          {:ast/alternate [:ast/node-id]}]

                     [:ast/node-id node-id])

        type (:ast/type node)]

    (case type

      :literal
      [[(emit-step node-id :push-literal start-order)]
       (inc start-order)]

      :variable
      [[(emit-step node-id :load-var start-order)]
       (inc start-order)]

      :application
      (let [operator-id (:ast/node-id (:ast/operator node))
            operands (:ast/operands node)
            ;; 1. Eval operator
            [op-steps next-order] (flatten-node db operator-id start-order)
            ;; 2. Eval operands in order
            [all-operand-steps final-order]
            (reduce (fn [[steps current-order] operand-map]
                      (let [o-node-id (:ast/node-id operand-map)
                            [o-steps n-order] (flatten-node db o-node-id current-order)]
                        [(into steps o-steps) n-order]))
                    [[] next-order]
                    operands)
            ;; 3. Apply
            apply-step (emit-step node-id :apply final-order)]
        [(-> op-steps (into all-operand-steps) (conj apply-step))
         (inc final-order)])

      :lambda
      ;; For lambda, we emit a step that captures the closure.
      ;; The body is compiled separately or linked.
      ;; For this flat model, we can treat the body as a sub-stream.
      [[(emit-step node-id :push-closure start-order)]
       (inc start-order)]

      :if
      (let [test-id (:ast/node-id (:ast/test node))
            cons-id (:ast/node-id (:ast/consequent node))
            alt-id (:ast/node-id (:ast/alternate node))
            ;; 1. Eval test
            [test-steps next-order] (flatten-node db test-id start-order)
            ;; 2. Jump if false (placeholder for target)
            jz-step (emit-step node-id :jump-if-false next-order)
            ;; 3. Eval consequent
            [cons-steps next-order-2] (flatten-node db cons-id (inc next-order))
            ;; 4. Jump to end
            jmp-step (emit-step node-id :jump next-order-2)
            ;; 5. Eval alternate
            [alt-steps next-order-3] (flatten-node db alt-id (inc next-order-2))

            ;; Link jumps
            jz-step (assoc jz-step :exec/target (:exec/order (first alt-steps)))
            jmp-step (assoc jmp-step :exec/target next-order-3)]
        [(-> test-steps
             (conj jz-step)
             (into cons-steps)
             (conj jmp-step)
             (into alt-steps))
         next-order-3])

      [[] start-order])))

(defn compile-to-stream
  "Compiles structural AST datoms in DB to a linear execution stream."
  [db root-node-id]
  (first (flatten-node db root-node-id 0)))

;; --- Execution (Linear Stream -> Result) ---

(defn run-stream
  "Executes a linear stream of execution datoms."
  [conn exec-stream initial-env]
  (let [db (d/db conn)
        steps (sort-by :exec/order (mapv (fn [s] (d/pull db '[:exec/instruction :exec/order :exec/target {:exec/node [:ast/node-id]}] [:exec/step-id (:exec/step-id s)])) exec-stream))]
    (loop [pc 0
           stack []
           env initial-env
           store {}]
      (if (>= pc (count steps))
        (peek stack)
        (let [step (nth steps pc)
              instr (:exec/instruction step)
              node-id (get-in step [:exec/node :ast/node-id])
              node (d/pull db '[:ast/value :ast/name :ast/params
                                {:ast/body [:ast/node-id]}
                                {:ast/operands [:ast/node-id]}]
                           [:ast/node-id node-id])]
          (case instr
            :push-literal
            (recur (inc pc) (conj stack (:ast/value node)) env store)

            :load-var
            (let [v (or (get env (:ast/name node))
                        (get store (:ast/name node)))]
              (recur (inc pc) (conj stack v) env store))

            :push-closure
            (let [closure {:type :closure
                           :params (:ast/params node)
                           :body-id (:ast/node-id (:ast/body node))
                           :env env}]
              (recur (inc pc) (conj stack closure) env store))

            :apply
            (let [operands (:ast/operands node)
                  argc (count operands)
                  args (subvec stack (- (count stack) argc))
                  stack-minus-args (subvec stack 0 (- (count stack) argc))
                  f (peek stack-minus-args)
                  stack-rest (pop stack-minus-args)]
              (cond
                (fn? f)
                (recur (inc pc) (conj stack-rest (apply f args)) env store)

                (= :closure (:type f))
                (let [{:keys [params body-id env] :as closure} f
                      closure-env env
                      new-env (merge closure-env (zipmap params args))
                      ;; JIT compile body
                      body-stream (compile-to-stream (d/db conn) body-id)
                      _ (d/transact! conn body-stream)
                      res (run-stream conn body-stream new-env)]
                  (recur (inc pc) (conj stack-rest res) env store))

                :else
                (throw (ex-info "Non-primitive application not yet in stream runner" {:f f}))))

            :jump-if-false
            (let [condition (peek stack)
                  new-stack (pop stack)]
              (if condition
                (recur (inc pc) new-stack env store)
                ;; Find the step with the target order
                (let [target-order (:exec/target step)
                      target-pc (some (fn [i] (when (= (:exec/order (nth steps i)) target-order) i)) (range (count steps)))]
                  (recur (or target-pc (count steps)) new-stack env store))))

            :jump
            (let [target-order (:exec/target step)
                  target-pc (some (fn [i] (when (= (:exec/order (nth steps i)) target-order) i)) (range (count steps)))]
              (recur (or target-pc (count steps)) stack env store))

            (recur (inc pc) stack env store)))))))

(defn run-stream-fast

  "Executes a linear stream by pre-resolving AST data to avoid d/pull in the hot loop."

  [conn exec-stream initial-env]

  (let [db (d/db conn)

                    ;; Pre-pull and HYDRATE the steps.

                    ;; We merge the AST node data directly into the step map.

        steps (->> exec-stream

                   (mapv (fn [s]

                           (let [step (d/pull db '[:exec/instruction :exec/order :exec/target {:exec/node [:ast/node-id :ast/value :ast/name :ast/params {:ast/body [:ast/node-id]} {:ast/operands [:ast/node-id]}]}]

                                              [:exec/step-id (:exec/step-id s)])]

                                         ;; Flatten/optimize for quick access

                             (assoc step

                                    :op (:exec/instruction step)

                                    :val (get-in step [:exec/node :ast/value])

                                    :var (get-in step [:exec/node :ast/name])

                                    :params (get-in step [:exec/node :ast/params])

                                    :body-id (get-in step [:exec/node :ast/body :ast/node-id])

                                    :argc (count (get-in step [:exec/node :ast/operands]))))))

                   (sort-by :exec/order)

                   vec)]

    (loop [pc 0

           stack []

           env initial-env

           store {}]

      (if (>= pc (count steps))

        (peek stack)

        (let [step (nth steps pc)

              instr (:op step)]

          (case instr

            :push-literal

            (recur (inc pc) (conj stack (:val step)) env store)

            :load-var

            (let [v (or (get env (:var step))

                        (get store (:var step)))]

              (recur (inc pc) (conj stack v) env store))

            :push-closure

            (let [closure {:type :closure

                           :params (:params step)

                           :body-id (:body-id step)

                           :env env}]

              (recur (inc pc) (conj stack closure) env store))

            :apply

            (let [argc (:argc step)

                  args (subvec stack (- (count stack) argc))

                  stack-minus-args (subvec stack 0 (- (count stack) argc))

                  f (peek stack-minus-args)

                  stack-rest (pop stack-minus-args)]

              (cond

                (fn? f)

                (recur (inc pc) (conj stack-rest (apply f args)) env store)

                (= :closure (:type f))

                (let [{:keys [params body-id env] :as closure} f

                      closure-env env

                      new-env (merge closure-env (zipmap params args))

                                  ;; JIT compile body

                      body-stream (compile-to-stream (d/db conn) body-id)

                      _ (d/transact! conn body-stream)

                      res (run-stream-fast conn body-stream new-env)]

                  (recur (inc pc) (conj stack-rest res) env store))

                :else

                (throw (ex-info "Non-primitive application not yet in stream runner" {:f f}))))

            :jump-if-false

            (let [condition (peek stack)

                  new-stack (pop stack)]

              (if condition

                (recur (inc pc) new-stack env store)

                            ;; Target finding is still O(N) here without pre-calculation, 

                            ;; but let's assume it's not the main bottleneck for this benchmark.

                            ;; Optimization: Pre-calculate jump targets in 'steps'.

                (let [target-order (:exec/target step)

                      target-pc (some (fn [i] (when (= (:exec/order (nth steps i)) target-order) i)) (range (count steps)))]

                  (recur (or target-pc (count steps)) new-stack env store))))

            :jump

            (let [target-order (:exec/target step)

                  target-pc (some (fn [i] (when (= (:exec/order (nth steps i)) target-order) i)) (range (count steps)))]

              (recur (or target-pc (count steps)) stack env store))

            (recur (inc pc) stack env store)))))))


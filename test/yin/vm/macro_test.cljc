(ns yin.vm.macro-test
  "Tests for the unified compile-time and runtime macro expansion engine.
   Covers the spec from docs/macros.md."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.macro :as macro]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- datoms-for
  "Convert an AST map to a {:node root-eid :datoms [...]} program."
  [ast]
  (let [datoms (vec (vm/ast->datoms ast))
        root-id (apply max (map first datoms))]
    {:node root-id, :datoms datoms}))


(defn- run-register
  "Run an AST through the register VM and return the result value."
  ([ast] (run-register ast {}))
  ([ast opts]
   (let [{:keys [node datoms]} (datoms-for ast)
         vm (register/create-vm (select-keys opts [:macro-registry]))]
     (-> vm
         (vm/load-program {:node node, :datoms datoms})
         (vm/run)
         (vm/value)))))


(defn- run-stack
  "Run an AST through the stack VM and return the result value."
  ([ast] (run-stack ast {}))
  ([ast opts]
   (let [{:keys [node datoms]} (datoms-for ast)
         vm (stack/create-vm (select-keys opts [:macro-registry]))]
     (-> vm
         (vm/load-program {:node node, :datoms datoms})
         (vm/run)
         (vm/value)))))


(defn- run-semantic
  "Run an AST through the semantic VM and return the result value."
  ([ast] (run-semantic ast {}))
  ([ast opts]
   (let [{:keys [node datoms]} (datoms-for ast)
         vm (semantic/create-vm (select-keys opts [:macro-registry]))]
     (-> vm
         (vm/load-program {:node node, :datoms datoms})
         (vm/run)
         (vm/value)))))


(defn- make-literal-macro
  "Create a macro function that always expands to a literal value node.
   Returns {:datoms [...] :root-eid eid}."
  [value]
  (fn [{:keys [fresh-eid]}]
    (let [eid (fresh-eid)]
      {:datoms [[eid :yin/type :literal 0 0]
                [eid :yin/value value 0 0]]
       :root-eid eid})))


(defn- make-identity-macro
  "Create a macro function that expands to its first argument's EID
   (passes the first arg through unchanged)."
  []
  (fn [{:keys [arg-eids]}]
    {:datoms []
     :root-eid (first arg-eids)}))


(defn- make-swap-args-macro
  "Create a macro that swaps operands of a binary application.
   Builds (op arg2 arg1) from the input (op arg1 arg2)."
  []
  (fn [{:keys [arg-eids get-attr fresh-eid]}]
    ;; arg-eids = [op-eid arg1-eid arg2-eid]
    (let [[op-eid arg1-eid arg2-eid] arg-eids
          app-eid (fresh-eid)]
      {:datoms [[app-eid :yin/type :application 0 0]
                [app-eid :yin/operator op-eid 0 0]
                [app-eid :yin/operands [arg2-eid arg1-eid] 0 0]]
       :root-eid app-eid})))


;; =============================================================================
;; Macro representation: lambda with :yin/macro? true
;; =============================================================================

(deftest macro-lambda-schema-test
  (testing "Lambda with :yin/macro? true and :yin/phase-policy is valid"
    (let [datoms [[-1025 :yin/type :lambda 0 0]
                  [-1025 :yin/macro? true 0 0]
                  [-1025 :yin/phase-policy :compile 0 0]
                  [-1025 :yin/params [] 0 0]
                  [-1025 :yin/body -1026 0 0]
                  [-1026 :yin/type :literal 0 0]
                  [-1026 :yin/value 42 0 0]]
          {:keys [get-attr]} (vm/index-datoms datoms)]
      (is (= :lambda (get-attr -1025 :yin/type)))
      (is (= true (get-attr -1025 :yin/macro?)))
      (is (= :compile (get-attr -1025 :yin/phase-policy))))))


;; =============================================================================
;; expand-once: single macro-expand node
;; =============================================================================

(deftest expand-once-simple-test
  (testing "Expands a top-level :yin/macro-expand node"
    ;; Program: (my-macro) → expands to literal 99
    ;; macro-lambda-eid = -2000, call-eid = root
    (let [macro-lambda-eid -2000
          call-datoms [[-1025 :yin/type :yin/macro-expand 0 0]
                       [-1025 :yin/operator macro-lambda-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 99)}
          result (macro/expand-once call-datoms -1025 registry)]
      (is (true? (:expanded? result)))
      (is (not= -1025 (:root-eid result)) "Root should have changed")
      ;; New root should be the literal 99 node
      (let [{:keys [get-attr]} (vm/index-datoms (:datoms result) {:root-id (:root-eid result)})]
        (is (= :literal (get-attr (:root-eid result) :yin/type)))
        (is (= 99 (get-attr (:root-eid result) :yin/value))))))

  (testing "No expansion when no :yin/macro-expand nodes"
    (let [datoms [[-1025 :yin/type :literal 0 0]
                  [-1025 :yin/value 42 0 0]]
          result (macro/expand-once datoms -1025 {})]
      (is (false? (:expanded? result)))
      (is (= -1025 (:root-eid result)))
      (is (= datoms (:datoms result))))))


;; =============================================================================
;; Expansion event entity (provenance)
;; =============================================================================

(deftest expansion-event-provenance-test
  (testing "Expansion produces a :macro-expand-event entity"
    (let [macro-lambda-eid -2000
          call-datoms [[-1025 :yin/type :yin/macro-expand 0 0]
                       [-1025 :yin/operator macro-lambda-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 7)}
          {:keys [datoms]} (macro/expand-once call-datoms -1025 registry)
          ;; Find the expansion event entity
          event-datoms (filter (fn [[_e a v]] (and (= a :yin/type) (= v :macro-expand-event)))
                               datoms)
          event-eid (ffirst event-datoms)
          {:keys [get-attr]} (vm/index-datoms datoms)]
      (is (some? event-eid) "An expansion event entity should exist")
      (is (= :macro-expand-event (get-attr event-eid :yin/type)))
      (is (= -1025 (get-attr event-eid :yin/source-call))
          "source-call links to original call site")
      (is (= macro-lambda-eid (get-attr event-eid :yin/macro))
          "macro links to macro lambda EID")
      (is (= :compile (get-attr event-eid :yin/phase)))
      (is (some? (get-attr event-eid :yin/expansion-root))
          "expansion-root points to the new root node")))

  (testing "Original call datoms are immutable after expansion"
    (let [macro-lambda-eid -2000
          original-call-datom [-1025 :yin/type :yin/macro-expand 0 0]
          call-datoms [original-call-datom
                       [-1025 :yin/operator macro-lambda-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 55)}
          {:keys [datoms]} (macro/expand-once call-datoms -1025 registry)]
      ;; Original datom must still be present and unchanged
      (is (some #(= % original-call-datom) datoms)
          "Original :yin/macro-expand datom is immutable and preserved")))

  (testing "Expansion output datoms carry m = expansion-event-eid"
    (let [macro-lambda-eid -2000
          call-datoms [[-1025 :yin/type :yin/macro-expand 0 0]
                       [-1025 :yin/operator macro-lambda-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 3)}
          {:keys [datoms]} (macro/expand-once call-datoms -1025 registry)
          ;; Find event EID
          event-eid (ffirst (filter (fn [[_e a v]] (and (= a :yin/type) (= v :macro-expand-event)))
                                    datoms))
          ;; Find expansion output datoms (m = event-eid)
          exp-output (filter (fn [[_e _a _v _t m]] (= m event-eid)) datoms)]
      (is (seq exp-output)
          "Expansion output datoms have m = event-eid"))))


;; =============================================================================
;; Nested expansion: macro inside a larger program
;; =============================================================================

(deftest nested-expansion-test
  (testing "Macro inside an application is correctly patched up"
    ;; Program: (+ (my-macro) 10) → (+ 99 10)
    (let [macro-lambda-eid -2000
          ;; Build datoms for (+ (my-macro) 10)
          ;; literal 10
          lit10-eid -1029
          ;; macro-expand call (my-macro)
          call-eid -1028
          ;; variable '+'
          plus-eid -1027
          ;; application (+ (my-macro) 10)
          app-eid -1025
          datoms [[app-eid :yin/type :application 0 0]
                  [app-eid :yin/operator plus-eid 0 0]
                  [app-eid :yin/operands [call-eid lit10-eid] 0 0]
                  [plus-eid :yin/type :variable 0 0]
                  [plus-eid :yin/name '+ 0 0]
                  [call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [] 0 0]
                  [lit10-eid :yin/type :literal 0 0]
                  [lit10-eid :yin/value 10 0 0]]
          registry {macro-lambda-eid (make-literal-macro 99)}
          {:keys [datoms root-eid expanded?]} (macro/expand-once datoms app-eid registry)
          {:keys [get-attr]} (vm/index-datoms datoms {:root-id root-eid})]
      (is (true? expanded?))
      ;; New root should be a new application node
      (is (= :application (get-attr root-eid :yin/type)))
      ;; Its operands: one literal (99 from expansion) and the original literal 10
      (let [operands (get-attr root-eid :yin/operands)]
        (is (= 2 (count operands)))
        (let [[first-op second-op] operands]
          (is (= :literal (get-attr first-op :yin/type)))
          (is (= 99 (get-attr first-op :yin/value)))
          (is (= :literal (get-attr second-op :yin/type)))
          (is (= 10 (get-attr second-op :yin/value))))))))


;; =============================================================================
;; expand-all: fixpoint expansion
;; =============================================================================

(deftest fixpoint-test
  (testing "Terminates when no new :yin/macro-expand datoms are emitted"
    (let [macro-lambda-eid -2000
          call-datoms [[-1025 :yin/type :yin/macro-expand 0 0]
                       [-1025 :yin/operator macro-lambda-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 42)}
          {:keys [datoms root-eid]} (macro/expand-all call-datoms -1025 registry)
          {:keys [get-attr]} (vm/index-datoms datoms {:root-id root-eid})]
      (is (= :literal (get-attr root-eid :yin/type)))
      (is (= 42 (get-attr root-eid :yin/value)))))

  (testing "Recursive macro expansion terminates at fixpoint"
    ;; First pass: (macro-a) → (macro-b)
    ;; Second pass: (macro-b) → literal 7
    (let [macro-a-eid -2000
          macro-b-eid -2001
          ;; macro-a expands to a call to macro-b
          macro-a-fn (fn [{:keys [fresh-eid]}]
                       (let [call-eid (fresh-eid)]
                         {:datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                                   [call-eid :yin/operator macro-b-eid 0 0]
                                   [call-eid :yin/operands [] 0 0]]
                          :root-eid call-eid}))
          ;; macro-b expands to literal 7
          macro-b-fn (make-literal-macro 7)
          registry {macro-a-eid macro-a-fn
                    macro-b-eid macro-b-fn}
          call-datoms [[-1025 :yin/type :yin/macro-expand 0 0]
                       [-1025 :yin/operator macro-a-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          {:keys [datoms root-eid]} (macro/expand-all call-datoms -1025 registry)
          {:keys [get-attr]} (vm/index-datoms datoms {:root-id root-eid})]
      (is (= :literal (get-attr root-eid :yin/type)))
      (is (= 7 (get-attr root-eid :yin/value))))))


;; =============================================================================
;; Guard limits
;; =============================================================================

(deftest guard-overflow-test
  (testing "Depth guard throws on infinite recursive expansion"
    (let [macro-eid -2000
          ;; Self-referential macro: always expands to another macro-expand call
          looping-macro (fn [{:keys [fresh-eid]}]
                          (let [call-eid (fresh-eid)]
                            {:datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                                      [call-eid :yin/operator macro-eid 0 0]
                                      [call-eid :yin/operands [] 0 0]]
                             :root-eid call-eid}))
          call-datoms [[-1025 :yin/type :yin/macro-expand 0 0]
                       [-1025 :yin/operator macro-eid 0 0]
                       [-1025 :yin/operands [] 0 0]]
          registry {macro-eid looping-macro}]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                            #"depth guard exceeded"
            (macro/expand-all call-datoms -1025 registry
                              {:max-depth 5}))))))


;; =============================================================================
;; Compile-time macro expansion via register VM
;; =============================================================================

(deftest compile-time-register-test
  (testing "Register VM expands :yin/macro-expand before bytecode compilation"
    (let [macro-lambda-eid -2000
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 77)}
          vm (-> (register/create-vm {:macro-registry registry})
                 (vm/load-program {:node call-eid, :datoms datoms})
                 (vm/run))]
      (is (= 77 (vm/value vm)))))

  (testing "Register VM: macro that returns identity of first arg"
    (let [macro-lambda-eid -2000
          lit-eid -1027
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [lit-eid] 0 0]
                  [lit-eid :yin/type :literal 0 0]
                  [lit-eid :yin/value 123 0 0]]
          registry {macro-lambda-eid (make-identity-macro)}
          vm (-> (register/create-vm {:macro-registry registry})
                 (vm/load-program {:node call-eid, :datoms datoms})
                 (vm/run))]
      (is (= 123 (vm/value vm))))))


;; =============================================================================
;; Compile-time macro expansion via stack VM
;; =============================================================================

(deftest compile-time-stack-test
  (testing "Stack VM expands :yin/macro-expand before bytecode compilation"
    (let [macro-lambda-eid -2000
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 88)}
          vm (-> (stack/create-vm {:macro-registry registry})
                 (vm/load-program {:node call-eid, :datoms datoms})
                 (vm/run))]
      (is (= 88 (vm/value vm))))))


;; =============================================================================
;; Runtime macro expansion via semantic VM
;; =============================================================================

(deftest runtime-semantic-test
  (testing "Semantic VM expands :yin/macro-expand at runtime"
    (let [macro-lambda-eid -2000
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 55)}
          vm (-> (semantic/create-vm {:macro-registry registry})
                 (vm/load-program {:node call-eid, :datoms datoms})
                 (vm/run))]
      (is (= 55 (vm/value vm)))))

  (testing "Semantic VM: runtime macro with arg passthrough"
    (let [macro-lambda-eid -2000
          lit-eid -1027
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [lit-eid] 0 0]
                  [lit-eid :yin/type :literal 0 0]
                  [lit-eid :yin/value 321 0 0]]
          registry {macro-lambda-eid (make-identity-macro)}
          vm (-> (semantic/create-vm {:macro-registry registry})
                 (vm/load-program {:node call-eid, :datoms datoms})
                 (vm/run))]
      (is (= 321 (vm/value vm))))))


;; =============================================================================
;; Compile/runtime expansion parity (same macro + input, same result)
;; =============================================================================

(deftest expansion-parity-test
  (testing "All VMs produce the same result for the same macro expansion"
    (let [macro-lambda-eid -2000
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 42)}
          run-vm (fn [create-fn]
                   (-> (create-fn {:macro-registry registry})
                       (vm/load-program {:node call-eid, :datoms datoms})
                       (vm/run)
                       (vm/value)))]
      (is (= 42 (run-vm register/create-vm)) "Register VM")
      (is (= 42 (run-vm stack/create-vm)) "Stack VM")
      (is (= 42 (run-vm semantic/create-vm)) "Semantic VM"))))


;; =============================================================================
;; Macro that transforms operands
;; =============================================================================

(deftest operand-transform-test
  (testing "Macro receives correct arg-eids and can build new AST from them"
    ;; macro (swap-args op a b) → (op b a)
    ;; Expand (swap-args - 10 3) → (- 3 10) = -7
    (let [macro-lambda-eid -2000
          ;; operand nodes for swap-args call: op=minus, a=10, b=3
          minus-eid -1027
          lit10-eid -1028
          lit3-eid -1029
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [minus-eid lit10-eid lit3-eid] 0 0]
                  [minus-eid :yin/type :variable 0 0]
                  [minus-eid :yin/name '- 0 0]
                  [lit10-eid :yin/type :literal 0 0]
                  [lit10-eid :yin/value 10 0 0]
                  [lit3-eid :yin/type :literal 0 0]
                  [lit3-eid :yin/value 3 0 0]]
          registry {macro-lambda-eid (make-swap-args-macro)}]
      ;; Verify compile-time result
      (let [vm (-> (register/create-vm {:macro-registry registry})
                   (vm/load-program {:node call-eid, :datoms datoms})
                   (vm/run))]
        (is (= -7 (vm/value vm)) "Register VM: (- 3 10) = -7"))
      ;; Verify semantic VM (runtime expansion)
      (let [vm (-> (semantic/create-vm {:macro-registry registry})
                   (vm/load-program {:node call-eid, :datoms datoms})
                   (vm/run))]
        (is (= -7 (vm/value vm)) "Semantic VM: (- 3 10) = -7")))))


;; =============================================================================
;; Expansion event query: source-call -> expansion-event -> expansion-root
;; =============================================================================

(deftest expansion-event-query-contract-test
  (testing "Query chain: source-call -> expansion-event -> expansion-root"
    (let [macro-lambda-eid -2000
          call-eid -1025
          datoms [[call-eid :yin/type :yin/macro-expand 0 0]
                  [call-eid :yin/operator macro-lambda-eid 0 0]
                  [call-eid :yin/operands [] 0 0]]
          registry {macro-lambda-eid (make-literal-macro 5)}
          {:keys [datoms root-eid]} (macro/expand-all datoms call-eid registry)
          {:keys [get-attr by-entity]} (vm/index-datoms datoms {:root-id root-eid})
          ;; Find the event entity
          event-eid (first (keep (fn [[eid]]
                                   (when (= :macro-expand-event (get-attr eid :yin/type))
                                     eid))
                                 by-entity))]
      ;; Query chain must resolve
      (is (some? event-eid))
      (is (= call-eid (get-attr event-eid :yin/source-call))
          "source-call -> original call site")
      (is (= macro-lambda-eid (get-attr event-eid :yin/macro))
          "macro -> macro lambda EID")
      (let [exp-root (get-attr event-eid :yin/expansion-root)]
        (is (some? exp-root) "expansion-root is set")
        (is (= root-eid exp-root) "expansion-root matches final root-eid")
        (is (= :literal (get-attr exp-root :yin/type)) "expansion-root is the literal node")))))


;; =============================================================================
;; Semantic VM: append-program-datoms for boundary-runtime test
;; =============================================================================

(deftest semantic-append-datoms-test
  (testing "semantic-append-datoms adds nodes to the VM index without resetting state"
    (let [vm (semantic/create-vm)
          ;; Load initial program: literal 1
          initial-datoms [[-1025 :yin/type :literal 0 0]
                          [-1025 :yin/value 1 0 0]]
          vm-loaded (vm/load-program vm {:node -1025, :datoms initial-datoms})
          ;; Append new datoms: a new literal node
          new-datoms [[-2000 :yin/type :literal 0 0]
                      [-2000 :yin/value 99 0 0]]
          vm-appended (semantic/semantic-append-datoms vm-loaded new-datoms)]
      ;; Original program still evaluates correctly
      (is (= 1 (vm/value (vm/run vm-loaded))))
      ;; After append, new node is accessible in the index
      (is (contains? (:index vm-appended) -2000)
          "Appended node is in the index"))))

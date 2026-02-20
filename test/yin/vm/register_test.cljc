(ns yin.vm.register-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream]
    [yin.vm :as vm]
    [yin.vm.register :as register]))


;; =============================================================================
;; Bytecode VM tests (full pipeline through numeric bytecode)
;; =============================================================================

(defn compile-and-run-bc
  [ast]
  (let [datoms (vm/ast->datoms ast)
        {:keys [asm reg-count]} (register/ast-datoms->asm datoms)
        compiled (assoc (register/asm->bytecode asm) :reg-count reg-count)
        vm (register/create-vm {:env vm/primitives})]
    (-> vm
        (vm/load-program compiled)
        (vm/run)
        (vm/value))))


(defn- load-ast
  [ast]
  (let [datoms (vm/ast->datoms ast)
        {:keys [asm reg-count]} (register/ast-datoms->asm datoms)
        compiled (assoc (register/asm->bytecode asm) :reg-count reg-count)]
    (-> (register/create-vm {:env vm/primitives})
        (vm/load-program compiled))))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (register/create-vm)]
      (is (= {} (vm/store vm)))
      (is (nil? (vm/continuation vm)))))
  (testing "After load-program, control has bytecode"
    (let [vm (load-ast {:type :literal, :value 42})
          ctrl (vm/control vm)]
      (is (= 0 (:ip ctrl)))
      (is (seq (:bytecode ctrl)))))
  (testing "After run, continuation is nil and store is empty"
    (let [vm (-> (load-ast {:type :literal, :value 42})
                 (vm/run))]
      (is (nil? (vm/continuation vm)))
      (is (= {} (vm/store vm)))
      (is (= 42 (vm/value vm)))))
  (testing "Environment contains primitives when provided"
    (let [vm (register/create-vm {:env vm/primitives})]
      (is (fn? (get (vm/environment vm) '+))))))


;; =============================================================================
;; IVMEval protocol tests
;; =============================================================================

(deftest eval-literal-test
  (testing "vm/eval evaluates a literal AST directly"
    (let [result (vm/eval (register/create-vm {:env vm/primitives})
                          {:type :literal, :value 42})]
      (is (vm/halted? result))
      (is (= 42 (vm/value result))))))


(deftest eval-arithmetic-test-via-eval
  (testing "vm/eval evaluates (+ 10 20) from AST directly"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/eval (register/create-vm {:env vm/primitives}) ast)]
      (is (= 30 (vm/value result))))))


(deftest bytecode-basic-test
  (testing "Literal via bytecode"
    (is (= 42 (compile-and-run-bc {:type :literal, :value 42})))))


(deftest bytecode-arithmetic-test
  (testing "Addition (+ 10 20) via bytecode"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run-bc ast))))))


(deftest bytecode-conditional-test
  (testing "If-else via bytecode"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run-bc ast)))))
  (testing "If-else (false case) via bytecode"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run-bc ast))))))


(deftest bytecode-lambda-test
  (testing "Lambda Application ((fn [x] (+ x 1)) 10) via bytecode"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}]
      (is (= 11 (compile-and-run-bc ast))))))


(deftest lambda-closure-test
  (testing "Lambda creates a closure"
    (let [closure (compile-and-run-bc {:type :lambda,
                                       :params ['x],
                                       :body {:type :variable, :name 'x}})]
      (is (= :closure (:type closure)))
      (is (= ['x] (:params closure))))))


(deftest bytecode-nested-call-test
  (testing "Nested calls (+ 1 (+ 2 3)) via bytecode"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run-bc ast))))))


(deftest multi-param-lambda-test
  (testing "Lambda with two parameters ((fn [x y] (+ x y)) 3 5)"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x 'y],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :variable, :name 'y}]}},
               :operands [{:type :literal, :value 3}
                          {:type :literal, :value 5}]}]
      (is (= 8 (compile-and-run-bc ast))))))


(deftest all-arithmetic-primitives-test
  (testing "All arithmetic primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (= 30 (compile-and-run-bc (binop '+ 10 20))))
      (is (= 5 (compile-and-run-bc (binop '- 15 10))))
      (is (= 50 (compile-and-run-bc (binop '* 5 10))))
      (is (= 4 (compile-and-run-bc (binop '/ 20 5)))))))


(deftest comparison-operations-test
  (testing "Comparison primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (true? (compile-and-run-bc (binop '= 5 5))))
      (is (false? (compile-and-run-bc (binop '= 5 6))))
      (is (true? (compile-and-run-bc (binop '< 3 5))))
      (is (true? (compile-and-run-bc (binop '> 10 5)))))))


(deftest addition-edge-cases-test
  (testing "Addition edge cases"
    (let [add (fn [a b]
                {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value a}
                            {:type :literal, :value b}]})]
      (is (= 0 (compile-and-run-bc (add 0 0))))
      (is (= 0 (compile-and-run-bc (add -5 5))))
      (is (= -10 (compile-and-run-bc (add -3 -7)))))))


(deftest nested-lambda-test
  (testing
    "Nested lambda with closure capture ((fn [x] ((fn [y] (+ x y)) 5)) 3)"
    (let [ast {:type :application,
               :operator
               {:type :lambda,
                :params ['x],
                :body {:type :application,
                       :operator
                       {:type :lambda,
                        :params ['y],
                        :body {:type :application,
                               :operator {:type :variable, :name '+},
                               :operands [{:type :variable, :name 'x}
                                          {:type :variable, :name 'y}]}},
                       :operands [{:type :literal, :value 5}]}},
               :operands [{:type :literal, :value 3}]}]
      (is (= 8 (compile-and-run-bc ast))))))


(deftest compound-expression-test
  (testing "Lambda with compound body ((fn [a b] (+ a (- b 1))) 10 5)"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['a 'b],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands
                                 [{:type :variable, :name 'a}
                                  {:type :application,
                                   :operator {:type :variable, :name '-},
                                   :operands [{:type :variable, :name 'b}
                                              {:type :literal, :value 1}]}]}},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 5}]}]
      (is (= 14 (compile-and-run-bc ast))))))


;; =============================================================================
;; Register bytecode compilation shape tests
;; =============================================================================
;; These test the compilation pipeline: AST -> datoms -> register bytecode

(deftest register-bytecode-shape-test
  (testing "Literal produces loadk + return"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm (vm/ast->datoms
                                                              {:type :literal,
                                                               :value 42}))]
      (is (vector? asm))
      (is (= 2 (count asm)))
      (is (= :loadk (first (first asm))) "First instruction should be :loadk")
      (is (= 42 (nth (first asm) 2)) "Should load the value 42")
      (is (= :return (first (last asm))) "Last instruction should be :return")))
  (testing "Variable produces loadv + return"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm (vm/ast->datoms
                                                              {:type :variable,
                                                               :name 'x}))]
      (is (= 2 (count asm)))
      (is (= :loadv (first (first asm))))
      (is (= 'x (nth (first asm) 2)))
      (is (= :return (first (last asm))))))
  (testing "All instructions are vectors"
    (let [{:keys [asm reg-count]}
          (register/ast-datoms->asm
            (vm/ast->datoms {:type :application,
                             :operator {:type :variable, :name '+},
                             :operands [{:type :literal, :value 1}
                                        {:type :literal, :value 2}]}))]
      (is (every? vector? asm)))))


(deftest register-bytecode-application-test
  (testing "Application produces loadk, loadv, call, and return"
    (let [{:keys [asm reg-count]}
          (register/ast-datoms->asm
            (vm/ast->datoms {:type :application,
                             :operator {:type :variable, :name '+},
                             :operands [{:type :literal, :value 1}
                                        {:type :literal, :value 2}]}))]
      (is (= 5 (count asm)))
      (is (= :loadk (first (nth asm 0))))
      (is (= :loadk (first (nth asm 1))))
      (is (= :loadv (first (nth asm 2))))
      (is (= :call (first (nth asm 3))))
      (is (= :return (first (nth asm 4))))))
  (testing "Call instruction references correct registers"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm
                                    (vm/ast->datoms
                                      {:type :application,
                                       :operator {:type :variable, :name '+},
                                       :operands [{:type :literal, :value 1}
                                                  {:type :literal, :value 2}]}))
          call-instr (nth asm 3)
          [op rd rf arg-regs] call-instr]
      (is (= :call op))
      (is (integer? rd) "Result register should be an integer")
      (is (integer? rf) "Function register should be an integer")
      (is (vector? arg-regs) "Arg registers should be a vector")
      (is (= 2 (count arg-regs)) "Should have 2 arg registers"))))


(deftest register-bytecode-lambda-test
  (testing "Lambda produces closure, jump, body, and return"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm
                                    (vm/ast->datoms {:type :lambda,
                                                     :params ['x],
                                                     :body {:type :variable,
                                                            :name 'x}}))]
      (is (= :closure (first (nth asm 0)))
          "First instruction should be :closure")
      (is (= :jump (first (nth asm 1)))
          "Second instruction should be :jump over body")
      (is (= :loadv (first (nth asm 2))) "Body should start with :loadv")
      (is (= :return (first (nth asm 3))) "Body should end with :return")))
  (testing "Closure body address points to correct instruction"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm
                                    (vm/ast->datoms {:type :lambda,
                                                     :params ['x],
                                                     :body {:type :variable,
                                                            :name 'x}}))
          [_ _rd _params _body-addr _reg-count] (first asm)]
      (is (= 2 _body-addr)
          "Body should start at instruction 2 (after closure + jump)")))
  (testing "Jump skips over body to return"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm
                                    (vm/ast->datoms {:type :lambda,
                                                     :params ['x],
                                                     :body {:type :variable,
                                                            :name 'x}}))
          [_ jump-addr] (nth asm 1)
          jump-target (get asm jump-addr)]
      (is (= :return (first jump-target))
          "Jump should land on the final :return"))))


(deftest register-bytecode-conditional-test
  (testing "If produces branch, both branches, and move instructions"
    (let [{:keys [asm reg-count]} (register/ast-datoms->asm
                                    (vm/ast->datoms
                                      {:type :if,
                                       :test {:type :literal, :value true},
                                       :consequent {:type :literal, :value 1},
                                       :alternate {:type :literal, :value 0}}))]
      (is (= :loadk (first (first asm))) "Should start with test expression")
      (is (some #(= :branch (first %)) asm)
          "Should contain a branch instruction")
      (is (some #(= :move (first %)) asm)
          "Should contain move instructions for result register"))))


(deftest register-bytecode-continuation-is-data-test
  (testing "Continuation frame is created during closure call"
    (let [{:keys [asm reg-count]}
          (register/ast-datoms->asm
            (vm/ast->datoms {:type :application,
                             :operator {:type :lambda,
                                        :params ['x],
                                        :body {:type :variable, :name 'x}},
                             :operands [{:type :literal, :value 42}]}))
          compiled (assoc (register/asm->bytecode asm) :reg-count reg-count)
          vm-inst (register/create-vm)
          vm-loaded (vm/load-program vm-inst compiled)
          states (loop [v vm-loaded
                        acc []]
                   (if (vm/halted? v) acc (recur (vm/step v) (conj acc v))))
          inside-closure (first (filter #(some? (vm/continuation %)) states))]
      (is (some? inside-closure)
          "Should have a state with non-nil continuation")
      (is (= :call-frame (:type (vm/continuation inside-closure)))
          "Continuation should be a :call-frame")
      (is (vector? (:saved-regs (vm/continuation inside-closure)))
          "Continuation should save caller registers")
      (is (integer? (:return-ip (vm/continuation inside-closure)))
          "Continuation should have a return IP"))))


;; =============================================================================
;; Stream operation tests (cursor-based)
;; =============================================================================

(defn- make-stream-vm
  "Create a Register VM with primitives, suitable for stream operations."
  []
  (register/create-vm {:env vm/primitives}))


(deftest stream-make-test
  (testing "stream/make creates a stream reference"
    (let [vm (-> (make-stream-vm)
                 (vm/eval {:type :stream/make, :buffer 10}))]
      (is (= :stream-ref (:type (vm/value vm))))
      (is (keyword? (:id (vm/value vm))))))
  (testing "stream/make with default buffer (unbounded)"
    (let [vm (-> (make-stream-vm)
                 (vm/eval {:type :stream/make}))
          stream-id (:id (vm/value vm))
          stream (get (vm/store vm) stream-id)]
      (is (some? stream))
      (is
        (= 1024 (:capacity stream))
        "Default buffer is 1024 when not specified (via datom compilation)"))))


(deftest stream-put-test
  (testing "stream/put adds value to stream"
    (let [vm-with-stream (-> (make-stream-vm)
                             (vm/eval {:type :stream/make, :buffer 5}))
          stream-ref (vm/value vm-with-stream)
          stream-id (:id stream-ref)
          vm-after-put (-> vm-with-stream
                           (vm/eval {:type :stream/put,
                                     :target {:type :literal,
                                              :value stream-ref},
                                     :val {:type :literal, :value 42}}))
          stream (get (vm/store vm-after-put) stream-id)]
      (is (= 42 (vm/value vm-after-put)))
      (is (= 1 (dao.stream/length stream)))))
  (testing "stream/put multiple values"
    (let [vm-with-stream (-> (make-stream-vm)
                             (vm/eval {:type :stream/make, :buffer 10}))
          stream-ref (vm/value vm-with-stream)
          stream-id (:id stream-ref)
          put-ast (fn [val]
                    {:type :stream/put,
                     :target {:type :literal, :value stream-ref},
                     :val {:type :literal, :value val}})
          vm-after-puts (-> vm-with-stream
                            (vm/eval (put-ast 1))
                            (vm/eval (put-ast 2)))
          stream (get (vm/store vm-after-puts) stream-id)]
      (is (= 2 (dao.stream/length stream))))))


(deftest stream-cursor-next-test
  (testing "cursor+next retrieves value from stream"
    (let [vm-with-stream (-> (make-stream-vm)
                             (vm/eval {:type :stream/make, :buffer 5}))
          stream-ref (vm/value vm-with-stream)
          vm-after-put (-> vm-with-stream
                           (vm/eval {:type :stream/put,
                                     :target {:type :literal,
                                              :value stream-ref},
                                     :val {:type :literal, :value 99}}))
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['c],
                          :body {:type :stream/next,
                                 :source {:type :variable, :name 'c}}},
               :operands [{:type :stream/cursor,
                           :source {:type :literal, :value stream-ref}}]}
          vm-after-next (-> vm-after-put
                            (vm/eval ast))]
      (is (= 99 (vm/value vm-after-next)))))
  (testing "next from empty stream blocks"
    (let [vm-with-stream (-> (make-stream-vm)
                             (vm/eval {:type :stream/make, :buffer 5}))
          stream-ref (vm/value vm-with-stream)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['c],
                          :body {:type :stream/next,
                                 :source {:type :variable, :name 'c}}},
               :operands [{:type :stream/cursor,
                           :source {:type :literal, :value stream-ref}}]}
          vm-after-next (-> vm-with-stream
                            (vm/eval ast))]
      (is (= :yin/blocked (vm/value vm-after-next))))))


(deftest stream-ordering-test
  (testing "stream maintains append order via cursors"
    (let [vm-with-stream (-> (make-stream-vm)
                             (vm/eval {:type :stream/make, :buffer 10}))
          stream-ref (vm/value vm-with-stream)
          put-ast (fn [val]
                    {:type :stream/put,
                     :target {:type :literal, :value stream-ref},
                     :val {:type :literal, :value val}})
          vm-after-puts (-> vm-with-stream
                            (vm/eval (put-ast :first))
                            (vm/eval (put-ast :second))
                            (vm/eval (put-ast :third)))
          read-ast (fn [cursor-ref]
                     {:type :stream/next,
                      :source {:type :literal, :value cursor-ref}})
          vm-with-cursor (-> vm-after-puts
                             (vm/eval {:type :stream/cursor,
                                       :source {:type :literal,
                                                :value stream-ref}}))
          cursor-ref (vm/value vm-with-cursor)
          vm-read1 (-> vm-with-cursor
                       (vm/eval (read-ast cursor-ref)))
          vm-read2 (-> vm-read1
                       (vm/eval (read-ast cursor-ref)))
          vm-read3 (-> vm-read2
                       (vm/eval (read-ast cursor-ref)))]
      (is (= :first (vm/value vm-read1)))
      (is (= :second (vm/value vm-read2)))
      (is (= :third (vm/value vm-read3))))))


(deftest stream-with-lambda-test
  (testing "stream operations within lambda application"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['s],
                          :body {:type :stream/put,
                                 :target {:type :variable, :name 's},
                                 :val {:type :literal, :value 42}}},
               :operands [{:type :stream/make, :buffer 5}]}
          vm (-> (make-stream-vm)
                 (vm/eval ast))]
      (is (= 42 (vm/value vm))))))


(deftest stream-put-cursor-next-roundtrip-test
  (testing "put then cursor+next roundtrip within nested lambdas"
    (let [ast {:type :application,
               :operator
               {:type :lambda,
                :params ['s],
                :body {:type :application,
                       :operator {:type :lambda,
                                  :params ['_],
                                  :body {:type :application,
                                         :operator {:type :lambda,
                                                    :params ['c],
                                                    :body {:type :stream/next,
                                                           :source
                                                           {:type :variable,
                                                            :name 'c}}},
                                         :operands [{:type :stream/cursor,
                                                     :source {:type :variable,
                                                              :name 's}}]}},
                       :operands [{:type :stream/put,
                                   :target {:type :variable, :name 's},
                                   :val {:type :literal, :value 42}}]}},
               :operands [{:type :stream/make, :buffer 5}]}
          vm (-> (make-stream-vm)
                 (vm/eval ast))]
      (is (= 42 (vm/value vm))))))


(deftest stream-channel-mobility-test
  (testing "A stream-ref sent through a stream arrives intact"
    (let [vm0 (-> (make-stream-vm)
                  (vm/eval {:type :stream/make, :buffer 10}))
          ref-a (vm/value vm0)
          vm1 (-> vm0
                  (vm/eval {:type :stream/make, :buffer 10}))
          ref-b (vm/value vm1)
          vm2 (-> vm1
                  (vm/eval {:type :stream/put,
                            :target {:type :literal, :value ref-b},
                            :val {:type :literal, :value 42}}))
          vm3 (-> vm2
                  (vm/eval {:type :stream/put,
                            :target {:type :literal, :value ref-a},
                            :val {:type :literal, :value ref-b}}))
          vm4 (-> vm3
                  (vm/eval {:type :stream/cursor,
                            :source {:type :literal, :value ref-a}}))
          cursor-a (vm/value vm4)
          vm5 (-> vm4
                  (vm/eval {:type :stream/next,
                            :source {:type :literal, :value cursor-a}}))
          recovered-ref (vm/value vm5)]
      (is (= ref-b recovered-ref)
          "Stream-ref passes through a stream unchanged")
      (let [vm6 (-> vm5
                    (vm/eval {:type :stream/cursor,
                              :source {:type :literal, :value recovered-ref}}))
            cursor-b (vm/value vm6)
            vm7 (-> vm6
                    (vm/eval {:type :stream/next,
                              :source {:type :literal, :value cursor-b}}))]
        (is (= 42 (vm/value vm7))
            "Reading from recovered stream-ref yields the original value")))))

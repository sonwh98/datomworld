(ns yin.vm.parity-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.ringbuffer]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.register :as register]
            [yin.vm.semantic :as semantic]
            [yin.vm.space :as space]
            [yin.vm.stack :as stack]
            [yin.vm.test-utils :as vtu]))


(defn- stream-capacity
  [vm-state]
  (let [stream-ref (vm/value vm-state)
        stream-id (:id stream-ref)
        stream #?(:cljs ^dao.stream.ringbuffer/RingBufferStream
                  (get (vm/store vm-state) stream-id)
                  :cljd ^dao.stream.ringbuffer/RingBufferStream
                  (get (vm/store vm-state) stream-id)
                  :default (get (vm/store vm-state) stream-id))]
    (.-capacity stream)))


(deftest queue-vm-clears-halted-state-parity-test
  (testing "queueing a datom program marks every VM backend as runnable"
    (let [datoms (vm/ast->datoms {:type :literal, :value 42})
          states [(vtu/queue-vm (ast-walker/create-vm) datoms)
                  (vtu/queue-vm (stack/create-vm) datoms)
                  (vtu/queue-vm (semantic/create-vm) datoms)
                  (vtu/queue-vm (space/create-vm) datoms)
                  (vtu/queue-vm (register/create-vm) datoms)]]
      (doseq [state states]
        (is (= {:position 0} (:in-cursor state)))
        (is (false? (:halted? state)))
        (is (false? (vm/halted? state)))))))


(defn- ast-walker-stream-make-default-vm
  []
  (-> (ast-walker/create-vm)
      (vtu/queue-ast {:type :stream/make})
      (vm/run)))


(defn- stack-stream-make-default-vm
  []
  (-> (stack/create-vm)
      (vm/eval {:type :stream/make})))


(defn- semantic-stream-make-default-vm
  []
  (-> (semantic/create-vm)
      (vm/eval {:type :stream/make})))


(defn- register-stream-make-default-vm
  []
  (-> (register/create-vm)
      (vm/eval {:type :stream/make})))


(defn- space-stream-make-default-vm
  []
  (-> (space/create-vm)
      (vm/eval {:type :stream/make})))


(deftest stream-make-default-capacity-parity-test
  (testing "All VMs should agree on default stream capacity semantics"
    (let [ast-walker-vm (ast-walker-stream-make-default-vm)
          expected-capacity (stream-capacity ast-walker-vm)
          stack-vm (stack-stream-make-default-vm)
          semantic-vm (semantic-stream-make-default-vm)
          register-vm (register-stream-make-default-vm)
          space-vm (space-stream-make-default-vm)]
      (is (= expected-capacity (stream-capacity stack-vm)))
      (is (= expected-capacity (stream-capacity semantic-vm)))
      (is (= expected-capacity (stream-capacity register-vm)))
      (is (= expected-capacity (stream-capacity space-vm))))))


(deftest canonical-datom-program-parity-test
  (testing "Semantic/Register/Stack agree when loading the same datom program"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 20}
                          {:type :literal, :value 22}]}
          datoms (vec (vm/ast->datoms ast))
          semantic-value (-> (vtu/queue-vm (semantic/create-vm) datoms)
                             (vm/run)
                             (vm/value))
          register-value (-> (vtu/queue-vm (register/create-vm) datoms)
                             (vm/run)
                             (vm/value))
          stack-value (-> (vtu/queue-vm (stack/create-vm) datoms)
                          (vm/run)
                          (vm/value))]
      (is (= semantic-value register-value))
      (is (= semantic-value stack-value)))))


(deftest continuation-nested-park-parity-test
  (testing "All VMs should handle nested parks (arg then body) correctly"
    ;; AST: ((fn [x] (park)) (park))
    ;; 1. Eval (park) -> returns parked-arg
    ;; 2. Resume parked-arg with :ignored -> evaluates lambda, then (park)
    ;; 3. Returns parked-body
    ;; 4. Resume parked-body with 42 -> returns 42
    (let [ast {:type :application,
               :operator {:type :lambda, :params ['x], :body {:type :vm/park}},
               :operands [{:type :vm/park}]}
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type reified-arg] results]
        (is (= :parked-continuation (:type reified-arg))
            (str vm-type " should park in arg"))
        (let [vm (case vm-type
                   :ast-walker (ast-walker/create-vm)
                   :stack (stack/create-vm)
                   :semantic (semantic/create-vm)
                   :space (space/create-vm)
                   :register (register/create-vm))
              ;; First run: evaluates (park) in operand position
              vm-parked-1 (vm/eval vm ast)
              reified-arg-from-vm (vm/value vm-parked-1)]
          (is (= :parked-continuation (:type reified-arg-from-vm))
              (str vm-type " should park in arg"))
          ;; Resume first park
          (let [vm-resumed-1 (vm/eval vm-parked-1
                                      {:type :vm/resume,
                                       :parked-id (:id reified-arg-from-vm),
                                       :val {:type :literal, :value :ignored}})
                reified-body (vm/value vm-resumed-1)]
            (is (= :parked-continuation (:type reified-body))
                (str vm-type " should park in body"))
            ;; Resume second park
            (let [vm-resumed-2 (vm/eval vm-resumed-1
                                        {:type :vm/resume,
                                         :parked-id (:id reified-body),
                                         :val {:type :literal, :value 42}})]
              (is (= 42 (vm/value vm-resumed-2))
                  (str vm-type " should return final value")))))))))


(deftest continuation-in-function-frame-parity-test
  (testing
    "All VMs should handle parking and resuming inside a function call frame"
    ;; AST: ((fn [x] (+ x (park))) 10)
    ;; 1. Eval -> returns parked-cont
    ;; 2. Resume parked-cont with 5 -> returns 15
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :vm/park}]}},
               :operands [{:type :literal, :value 10}]}
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type reified] results]
        (is (= :parked-continuation (:type reified))
            (str vm-type " should park inside function")))
      ;; Resume each with 5
      (let [resume-with-5
            (fn [vm-type]
              (let [vm (case vm-type
                         :ast-walker (ast-walker/create-vm)
                         :stack (stack/create-vm)
                         :semantic (semantic/create-vm {:env vm/primitives})
                         :space (space/create-vm {:env vm/primitives})
                         :register (register/create-vm {:env vm/primitives}))
                    vm-parked (vm/eval vm ast)
                    reified (vm/value vm-parked)
                    vm-resumed (vm/eval vm-parked
                                        {:type :vm/resume,
                                         :parked-id (:id reified),
                                         :val {:type :literal, :value 5}})]
                (vm/value vm-resumed)))]
        (is (= 15 (resume-with-5 :ast-walker)))
        (is (= 15 (resume-with-5 :stack)))
        (is (= 15 (resume-with-5 :semantic)))
        (is (= 15 (resume-with-5 :register)))))))


(defn- k-depth
  [vm-type k]
  (case vm-type
    :ast-walker (loop [k k d 0] (if (nil? k) d (recur (:next k) (inc d))))
    :semantic (count k)
    :space (count k)
    :stack (count k)
    :register (loop [k k d 0] (if (nil? k) d (recur (:next k) (inc d))))))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- call-op
  [op-sym & args]
  {:type :application,
   :operator {:type :variable, :name op-sym},
   :operands (mapv lit args)})


(deftest n-ary-arithmetic-parity-test
  (testing "Arithmetic and comparison primitives accept n-ary args like Clojure"
    (doseq [[ast expected] [;; + : 0-ary, 1-ary, 3-ary, 5-ary
                            [(call-op '+) 0] [(call-op '+ 7) 7]
                            [(call-op '+ 1 2 3) 6] [(call-op '+ 1 2 3 4 5) 15]
                            ;; * : 0-ary, 1-ary, n-ary
                            [(call-op '*) 1] [(call-op '* 5) 5]
                            [(call-op '* 2 3 4) 24]
                            ;; - : 1-ary negate, n-ary subtract
                            [(call-op '- 7) -7] [(call-op '- 10 1 2 3) 4]
                            ;; / : 1-ary reciprocal, n-ary divide
                            [(call-op '/ 2)
                             #?(:clj 1/2
                                :cljs 0.5
                                :cljd 0.5)] [(call-op '/ 100 2 5) 10]
                            ;; = and !=
                            [(call-op '= 1 1 1) true] [(call-op '= 1 1 2) false]
                            [(call-op '!= 1 2 3) true]
                            ;; ordered comparisons
                            [(call-op '< 1 2 3) true] [(call-op '< 1 2 2) false]
                            [(call-op '<= 1 2 2) true] [(call-op '> 3 2 1) true]
                            [(call-op '>= 3 3 2) true]]]
      (let [{:keys [ast-walker semantic stack register]} (vtu/run-all-vms ast)]
        (is (= expected ast-walker) (str "ast-walker: " (pr-str ast)))
        (is (= expected semantic) (str "semantic: " (pr-str ast)))
        (is (= expected stack) (str "stack: " (pr-str ast)))
        (is (= expected register) (str "register: " (pr-str ast)))))))


(deftest tco-depth-parity-test
  (testing "All VMs should run tail-recursive countdown in constant stack/depth"
    (let [self-fn {:type :lambda,
                   :params ['self 'n],
                   :body {:type :if,
                          :test {:type :application,
                                 :operator {:type :variable, :name '<},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal, :value 1}]},
                          :consequent {:type :literal, :value 0},
                          :alternate {:type :application,
                                      :operator {:type :variable, :name 'self},
                                      :operands
                                      [{:type :variable, :name 'self}
                                       {:type :application,
                                        :operator {:type :variable, :name '-},
                                        :operands [{:type :variable, :name 'n}
                                                   {:type :literal,
                                                    :value 1}]}],
                                      :tail? true},
                          :tail? true}}
          ast {:type :application,
               :operator self-fn,
               :operands [self-fn {:type :literal, :value 100}],
               :tail? true}]
      (doseq [vm-type [:ast-walker :semantic :space :stack :register]]
        (let [vm (case vm-type
                   :ast-walker (vtu/queue-ast (ast-walker/create-vm) ast)
                   :semantic (vtu/queue-vm (semantic/create-vm)
                                           (vm/ast->datoms ast))
                   :space (vtu/queue-vm (space/create-vm) (vm/ast->datoms ast))
                   :stack (-> (stack/create-vm)
                              (vm/eval ast))
                   :register (-> (register/create-vm)
                                 (vm/eval ast)))]
          (loop [v vm
                 max-d 0]
            (if (vm/halted? v)
              (do (is (= 0 (vm/value v)) (str vm-type " should return 0"))
                  (is (<= max-d 3)
                      (str vm-type
                           " max continuation depth should be small, got "
                           max-d)))
              (let [v' (vm/step v)
                    d (k-depth vm-type (vm/continuation v'))]
                (recur v' (max max-d d))))))))))


(deftest parameter-does-not-leak-into-store-parity-test
  (testing
    "a function parameter with the same name as a global def
            must not overwrite the global after the function returns
            — evaluated as separate REPL inputs to reproduce the env-leak bug"
    ;; Bug: in the REPL each form is vm/eval'd sequentially on the prior
    ;; state. After (shadow-test 99) the env retains {x → 99} from the call
    ;; frame, so the subsequent lookup of x finds 99 (the param) instead of
    ;; 42 (the store).
    (doseq [[vm-type create-vm] (vtu/vm-factories)]
      (let [vm0 (create-vm)
            vm1 (vm/eval vm0 (yang/compile '(def x 42)))
            vm2 (vm/eval vm1 (yang/compile '(defn shadow-test
                                              [x]
                                              (+ x 1))))
            vm3 (vm/eval vm2 (yang/compile '(shadow-test 99)))
            vm4 (vm/eval vm3 (yang/compile 'x))]
        (is
          (= 42 (vm/value vm4))
          (str
            vm-type
            ": x should still be 42 after shadow-test returns, not the parameter value"))))))


;; ---------------------------------------------------------------------------
;; Consolidated Backend Parity Tests (docs/design/yin.vm.test-consolidation.md)
;; ---------------------------------------------------------------------------

(deftest addition-edge-cases-parity-test
  (testing "addition handles zero-args, identity, and negation across all VMs"
    (let [add (fn [a b]
                {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value a}
                            {:type :literal, :value b}]})
          cases [[(add 0 0) 0] [(add -5 5) 0] [(add -3 -7) -10]]]
      (doseq [[ast expected] cases]
        (let [results (vtu/run-all-vms ast)]
          (doseq [[vm-type value] results]
            (is (= expected value)
                (str vm-type
                     " should compute " (pr-str ast)
                     " => " expected
                     ", got " (pr-str value)))))))))


(deftest nested-lambda-parity-test
  (testing "Nested lambda with closure capture across all VMs"
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
               :operands [{:type :literal, :value 3}]}
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type value] results]
        (is (= 8 value)
            (str vm-type
                 " should evaluate nested-lambda to 8, got "
                 (pr-str value)))))))


(deftest compound-expression-parity-test
  (testing "Lambda with compound body across all VMs"
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
                          {:type :literal, :value 5}]}
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type value] results]
        (is (= 14 value)
            (str vm-type
                 " should evaluate compound-expression to 14, got "
                 (pr-str value)))))))


(deftest multi-param-lambda-parity-test
  (testing "Lambda with two parameters across all VMs"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x 'y],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :variable, :name 'y}]}},
               :operands [{:type :literal, :value 3}
                          {:type :literal, :value 5}]}
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type value] results]
        (is (= 8 value)
            (str vm-type
                 " should evaluate multi-param-lambda to 8, got "
                 (pr-str value)))))))


(deftest stream-ordering-parity-test
  (testing "stream maintains append order via cursors across all VMs"
    (doseq [[vm-type create-vm] (vtu/vm-factories)]
      (let [vm0 (create-vm)
            vm1 (vm/eval vm0 {:type :stream/make, :buffer 10})
            stream-ref (vm/value vm1)
            put (fn [vm val]
                  (vm/eval vm
                           {:type :stream/put,
                            :target {:type :literal, :value stream-ref},
                            :val {:type :literal, :value val}}))
            vm2 (-> vm1
                    (put :first)
                    (put :second)
                    (put :third))
            cursor-vm (vm/eval vm2
                               {:type :stream/cursor,
                                :source {:type :literal, :value stream-ref}})
            cursor-ref (vm/value cursor-vm)
            next-ast {:type :stream/next,
                      :source {:type :literal, :value cursor-ref}}
            v1 (vm/eval cursor-vm next-ast)
            v2 (vm/eval v1 next-ast)
            v3 (vm/eval v2 next-ast)]
        (is (= :first (vm/value v1)) (str vm-type " stream-ordering: first"))
        (is (= :second (vm/value v2)) (str vm-type " stream-ordering: second"))
        (is (= :third (vm/value v3))
            (str vm-type " stream-ordering: third"))))))


(deftest stream-channel-mobility-parity-test
  (testing
    "A stream-ref sent through a stream arrives intact at VM level across all VMs"
    (doseq [[vm-type create-vm] (vtu/vm-factories)]
      (let [vm0 (vm/eval (create-vm) {:type :stream/make, :buffer 10})
            ref-a (vm/value vm0)
            vm1 (vm/eval vm0 {:type :stream/make, :buffer 10})
            ref-b (vm/value vm1)
            vm2 (vm/eval vm1
                         {:type :stream/put,
                          :target {:type :literal, :value ref-b},
                          :val {:type :literal, :value 42}})
            vm3 (vm/eval vm2
                         {:type :stream/put,
                          :target {:type :literal, :value ref-a},
                          :val {:type :literal, :value ref-b}})
            vm4 (vm/eval vm3
                         {:type :stream/cursor,
                          :source {:type :literal, :value ref-a}})
            cursor-a (vm/value vm4)
            vm5 (vm/eval vm4
                         {:type :stream/next,
                          :source {:type :literal, :value cursor-a}})
            recovered-ref (vm/value vm5)]
        (is (= ref-b recovered-ref)
            (str vm-type " stream-channel-mobility: ref-b recovered"))
        (let [vm6 (vm/eval vm5
                           {:type :stream/cursor,
                            :source {:type :literal, :value recovered-ref}})
              cursor-b (vm/value vm6)
              vm7 (vm/eval vm6
                           {:type :stream/next,
                            :source {:type :literal, :value cursor-b}})]
          (is (= 42 (vm/value vm7))
              (str vm-type " stream-channel-mobility: 42 recovered")))))))


(deftest stream-put-cursor-next-roundtrip-parity-test
  ;; Consolidation note (docs/design/yin.vm.test-consolidation.md): this
  ;; scenario's pre-consolidation ast-walker copy used the stream-ingestion
  ;; path (queue-ast!+run), while register/semantic/stack already used
  ;; vm/eval directly. run-all-vms (below) is eval-path for all backends,
  ;; so this is a real path change for ast-walker specifically, verified
  ;; non-divergent by this test asserting the same expected value (42) that
  ;; the stream path asserted before consolidation.
  (testing "put then cursor+next roundtrip within nested lambdas across all VMs"
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
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type value] results]
        (is (= 42 value)
            (str vm-type
                 " should evaluate stream-put-cursor-next-roundtrip to 42, got "
                 (pr-str value)))))))


(deftest stream-with-lambda-parity-test
  ;; Consolidation note: same ast-walker stream-path -> eval-path change as
  ;; stream-put-cursor-next-roundtrip-parity-test above, same verification.
  (testing "stream operations within lambda application across all VMs"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['s],
                          :body {:type :stream/put,
                                 :target {:type :variable, :name 's},
                                 :val {:type :literal, :value 42}}},
               :operands [{:type :stream/make, :buffer 5}]}
          results (vtu/run-all-vms ast)]
      (doseq [[vm-type value] results]
        (is (= 42 value)
            (str vm-type
                 " should evaluate stream-with-lambda to 42, got "
                 (pr-str value)))))))

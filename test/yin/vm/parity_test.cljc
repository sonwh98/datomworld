(ns yin.vm.parity-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(defn- stream-capacity
  [vm-state]
  (let [stream-ref (vm/value vm-state)
        stream-id (:id stream-ref)
        stream (get (vm/store vm-state) stream-id)]
    (:capacity stream)))


(defn- ast-walker-stream-make-default-vm
  []
  (-> (ast-walker/create-vm)
      (vm/load-program {:type :stream/make})
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


(deftest stream-make-default-capacity-parity-test
  (testing "All VMs should agree on default stream capacity semantics"
    (let [ast-walker-vm (ast-walker-stream-make-default-vm)
          expected-capacity (stream-capacity ast-walker-vm)
          stack-vm (stack-stream-make-default-vm)
          semantic-vm (semantic-stream-make-default-vm)
          register-vm (register-stream-make-default-vm)]
      (is (= expected-capacity (stream-capacity stack-vm)))
      (is (= expected-capacity (stream-capacity semantic-vm)))
      (is (= expected-capacity (stream-capacity register-vm))))))


(defn- run-all-vms
  "Evaluate an AST across all VM backends and return their final values."
  [ast]
  {:ast-walker (vm/value (vm/eval (ast-walker/create-vm) ast)),
   :stack (vm/value (vm/eval (stack/create-vm) ast)),
   :semantic (vm/value (vm/eval (semantic/create-vm) ast)),
   :register (vm/value (vm/eval (register/create-vm)
                                ast))})


(deftest continuation-nested-park-parity-test
  (testing "All VMs should handle nested parks (arg then body) correctly"
    ;; AST: ((fn [x] (park)) (park))
    ;; 1. Eval (park) -> returns parked-arg
    ;; 2. Resume parked-arg with :ignored -> evaluates lambda, then (park)
    ;; 3. Returns parked-body
    ;; 4. Resume parked-body with 42 -> returns 42
    (let [ast {:type :application,
               :operator {:type :lambda, :params ['x], :body {:type :vm/park}},
               :operands [{:type :vm/park}]}]
      (let [results (run-all-vms ast)]
        (doseq [[vm-type reified-arg] results]
          (is (= :parked-continuation (:type reified-arg))
              (str vm-type " should park in arg"))
          (let [vm (case vm-type
                     :ast-walker (ast-walker/create-vm)
                     :stack (stack/create-vm)
                     :semantic (semantic/create-vm)
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
                                         :val :ignored})
                  reified-body (vm/value vm-resumed-1)]
              (is (= :parked-continuation (:type reified-body))
                  (str vm-type " should park in body"))
              ;; Resume second park
              (let [vm-resumed-2 (vm/eval vm-resumed-1
                                          {:type :vm/resume,
                                           :parked-id (:id reified-body),
                                           :val 42})]
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
                 :operands [{:type :literal, :value 10}]}]
        (let [results (run-all-vms ast)]
          (doseq [[vm-type reified] results]
            (is (= :parked-continuation (:type reified))
                (str vm-type " should park inside function")))
          ;; Resume each with 5
          (let [resume-with-5
                (fn [vm-type]
                  (let [vm (case vm-type
                             :ast-walker (ast-walker/create-vm)
                             :stack (stack/create-vm)
                             :semantic (semantic/create-vm {:env
                                                            vm/primitives})
                             :register (register/create-vm {:env
                                                            vm/primitives}))
                        vm-parked (vm/eval vm ast)
                        reified (vm/value vm-parked)
                        vm-resumed (vm/eval vm-parked
                                            {:type :vm/resume,
                                             :parked-id (:id reified),
                                             :val 5})]
                    (vm/value vm-resumed)))]
            (is (= 15 (resume-with-5 :ast-walker)))
            (is (= 15 (resume-with-5 :stack)))
            (is (= 15 (resume-with-5 :semantic)))
            (is (= 15 (resume-with-5 :register)))))))))


(defn- k-depth
  [vm-type k]
  (case vm-type
    :ast-walker (loop [k k d 0] (if (nil? k) d (recur (:parent k) (inc d))))
    :semantic (count k)
    :stack (count k)
    :register (loop [k k d 0] (if (nil? k) d (recur (:parent k) (inc d))))))


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
      (doseq [vm-type [:ast-walker :semantic :stack :register]]
        (let [vm (case vm-type
                   :ast-walker (-> (ast-walker/create-vm)
                                   (vm/load-program ast))
                   :semantic (let [datoms (vm/ast->datoms ast)
                                   root-id (apply max (map first datoms))]
                               (-> (semantic/create-vm)
                                   (vm/load-program {:node root-id,
                                                     :datoms datoms})))
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

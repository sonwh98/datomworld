(ns yin.vm.parity-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(defn- stream-capacity
  [vm-state]
  (let [stream-ref (vm/value vm-state)
        stream-id (:id stream-ref)
        stream #?(:cljs ^dao.stream.transport.ringbuffer/RingBufferStream (get (vm/store vm-state) stream-id)
                  :cljd ^dao.stream.transport.ringbuffer/RingBufferStream (get (vm/store vm-state) stream-id)
                  :default (get (vm/store vm-state) stream-id))]
    (.-capacity stream)))


(defn- queue-vm
  [vm-state datoms]
  (let [in-stream (ds/open! {:type :ringbuffer
                             :capacity nil})
        queued-vm (assoc vm-state
                         :in-stream in-stream
                         :in-cursor {:position 0}
                         :halted? false)]
    (ds/put! in-stream (vec datoms))
    queued-vm))


(defn- queue-ast
  [vm-state ast]
  (queue-vm vm-state (vm/ast->datoms ast)))


(deftest queue-vm-clears-halted-state-parity-test
  (testing "queueing a datom program marks every VM backend as runnable"
    (let [datoms (vm/ast->datoms {:type :literal, :value 42})
          states [(queue-vm (ast-walker/create-vm) datoms)
                  (queue-vm (stack/create-vm) datoms)
                  (queue-vm (semantic/create-vm) datoms)
                  (queue-vm (register/create-vm) datoms)]]
      (doseq [state states]
        (is (= {:position 0} (:in-cursor state)))
        (is (false? (:halted? state)))
        (is (false? (vm/halted? state)))))))


(defn- ast-walker-stream-make-default-vm
  []
  (-> (ast-walker/create-vm)
      (queue-ast {:type :stream/make})
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


(deftest canonical-datom-program-parity-test
  (testing "Semantic/Register/Stack agree when loading the same datom program"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 20}
                          {:type :literal, :value 22}]}
          datoms (vec (vm/ast->datoms ast))
          semantic-value (-> (queue-vm (semantic/create-vm) datoms)
                             (vm/run)
                             (vm/value))
          register-value (-> (queue-vm (register/create-vm) datoms)
                             (vm/run)
                             (vm/value))
          stack-value (-> (queue-vm (stack/create-vm) datoms)
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
          results (run-all-vms ast)]
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
          results (run-all-vms ast)]
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
    (doseq [[ast expected]
            [;; + : 0-ary, 1-ary, 3-ary, 5-ary
             [(call-op '+) 0]
             [(call-op '+ 7) 7]
             [(call-op '+ 1 2 3) 6]
             [(call-op '+ 1 2 3 4 5) 15]
             ;; * : 0-ary, 1-ary, n-ary
             [(call-op '*) 1]
             [(call-op '* 5) 5]
             [(call-op '* 2 3 4) 24]
             ;; - : 1-ary negate, n-ary subtract
             [(call-op '- 7) -7]
             [(call-op '- 10 1 2 3) 4]
             ;; / : 1-ary reciprocal, n-ary divide
             [(call-op '/ 2) #?(:clj 1/2 :cljs 0.5 :cljd 0.5)]
             [(call-op '/ 100 2 5) 10]
             ;; = and !=
             [(call-op '= 1 1 1) true]
             [(call-op '= 1 1 2) false]
             [(call-op '!= 1 2 3) true]
             ;; ordered comparisons
             [(call-op '< 1 2 3) true]
             [(call-op '< 1 2 2) false]
             [(call-op '<= 1 2 2) true]
             [(call-op '> 3 2 1) true]
             [(call-op '>= 3 3 2) true]]]
      (let [{:keys [ast-walker semantic stack register]} (run-all-vms ast)]
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
      (doseq [vm-type [:ast-walker :semantic :stack :register]]
        (let [vm (case vm-type
                   :ast-walker (queue-ast (ast-walker/create-vm) ast)
                   :semantic (queue-vm (semantic/create-vm) (vm/ast->datoms ast))
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

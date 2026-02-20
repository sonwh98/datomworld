(ns yin.vm.semantic-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream]
    [yin.vm :as vm]
    [yin.vm.semantic :as semantic]))


;; =============================================================================
;; Semantic VM tests (datom graph traversal via protocols)
;; =============================================================================

(defn compile-and-run
  [ast]
  (let [datoms (vm/ast->datoms ast)
        root-id (ffirst datoms)
        vm (semantic/create-vm {:env vm/primitives})]
    (-> vm
        (vm/load-program {:node root-id, :datoms datoms})
        (vm/run)
        (vm/value))))


(defn- load-ast
  [ast]
  (let [datoms (vm/ast->datoms ast)
        root-id (ffirst datoms)]
    (-> (semantic/create-vm {:env vm/primitives})
        (vm/load-program {:node root-id, :datoms datoms}))))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (semantic/create-vm)]
      (is (= {} (vm/store vm)))
      (is (nil? (vm/control vm)))
      (is (empty? (vm/continuation vm)))))
  (testing "After load-program, control is non-nil"
    (let [vm (load-ast {:type :literal, :value 42})]
      (is (some? (vm/control vm)))))
  (testing "After run, continuation is empty and store is empty"
    (let [vm (-> (load-ast {:type :literal, :value 42})
                 (vm/run))]
      (is (empty? (vm/continuation vm)))
      (is (= {} (vm/store vm)))
      (is (= 42 (vm/value vm)))))
  (testing "Environment contains primitives when provided"
    (let [vm (semantic/create-vm {:env vm/primitives})]
      (is (fn? (get (vm/environment vm) '+))))))


;; =============================================================================
;; IVMEval protocol tests
;; =============================================================================

(deftest eval-literal-test
  (testing "vm/eval evaluates a literal AST directly"
    (let [result (vm/eval (semantic/create-vm {:env vm/primitives})
                          {:type :literal, :value 42})]
      (is (vm/halted? result))
      (is (= 42 (vm/value result))))))


(deftest eval-arithmetic-test-via-eval
  (testing "vm/eval evaluates (+ 10 20) from AST directly"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/eval (semantic/create-vm {:env vm/primitives}) ast)]
      (is (= 30 (vm/value result))))))


(deftest literal-test
  (testing "Literal via semantic VM"
    (is (= 42 (compile-and-run {:type :literal, :value 42})))))


(deftest arithmetic-test
  (testing "Addition (+ 10 20) via semantic VM"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run ast))))))


(deftest conditional-test
  (testing "If-else (true case) via semantic VM"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run ast)))))
  (testing "If-else (false case) via semantic VM"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run ast))))))


(deftest lambda-test
  (testing "Lambda application ((fn [x] (+ x 1)) 10) via semantic VM"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}]
      (is (= 11 (compile-and-run ast))))))


(deftest lambda-closure-test
  (testing "Lambda creates a closure"
    (let [closure (compile-and-run {:type :lambda,
                                    :params ['x],
                                    :body {:type :variable, :name 'x}})]
      (is (= :closure (:type closure)))
      (is (= ['x] (:params closure))))))


(deftest nested-call-test
  (testing "Nested calls (+ 1 (+ 2 3)) via semantic VM"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run ast))))))


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
      (is (= 8 (compile-and-run ast))))))


(deftest all-arithmetic-primitives-test
  (testing "All arithmetic primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (= 30 (compile-and-run (binop '+ 10 20))))
      (is (= 5 (compile-and-run (binop '- 15 10))))
      (is (= 50 (compile-and-run (binop '* 5 10))))
      (is (= 4 (compile-and-run (binop '/ 20 5)))))))


(deftest comparison-operations-test
  (testing "Comparison primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (true? (compile-and-run (binop '= 5 5))))
      (is (false? (compile-and-run (binop '= 5 6))))
      (is (true? (compile-and-run (binop '< 3 5))))
      (is (true? (compile-and-run (binop '> 10 5)))))))


(deftest addition-edge-cases-test
  (testing "Addition edge cases"
    (let [add (fn [a b]
                {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value a}
                            {:type :literal, :value b}]})]
      (is (= 0 (compile-and-run (add 0 0))))
      (is (= 0 (compile-and-run (add -5 5))))
      (is (= -10 (compile-and-run (add -3 -7)))))))


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
      (is (= 8 (compile-and-run ast))))))


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
      (is (= 14 (compile-and-run ast))))))


;; =============================================================================
;; Stream operation tests (cursor-based)
;; =============================================================================

(defn- make-stream-vm
  "Create a Semantic VM with primitives, suitable for stream operations."
  []
  (semantic/create-vm {:env vm/primitives}))


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

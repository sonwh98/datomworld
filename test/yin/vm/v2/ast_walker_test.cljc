(ns yin.vm.v2.ast-walker-test
  "The v1 ast-walker suite, ported.

   Every expectation here is v1's recorded value, not a freshly chosen one: a
   suite that merely 'covers the same programs' passes under silent
   divergence. The one v1 case that required `dao.space.transact` is dropped
   with the rest of `dao.space`; the cases that assert park-on-full,
   close-wakes-writer and take-wakes-reader have no v2 counterpart and are
   named in the divergence register instead."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.test-utils :as tu :refer [compile-and-run create-vm
                                                 queue-ast!]]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


;; =============================================================================
;; CESK state
;; =============================================================================

(deftest cesk-state-test
  (testing "Initial state carries the FFI pair and its cursor"
    (let [vm (create-vm)]
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (contains? (vm/store vm) vm/call-out-cursor-key))
      (is (not (contains? (vm/store vm) :yin/call-in-cursor))
          "call-in-cursor-key is dropped: v1 wrote it and nothing read it")
      (is (nil? (:in-stream vm))
          "program observation state left with V7: the VM holds no stream")
      (is (nil? (vm/control vm)))
      (is (nil? (vm/continuation vm)))))
  (testing "An idle step is identity: queued input waits for the observer"
    (let [session (-> (tu/make-observer-session)
                      (queue-ast! {:type :literal, :value 42}))]
      (is (= (:consumer session) (vm/step (:consumer session))))))
  (testing "A loaded program executes one step"
    (let [vm (ast-walker/vm-load-program
               (:consumer (tu/make-observer-session))
               (vm/ast->datoms {:type :literal, :value 42}))
          vm' (vm/step vm)]
      (is (vm/halted? vm'))
      (is (= 42 (vm/value vm')))))
  (testing "After a session run, continuation is nil and the pair survives"
    (let [vm (:consumer (-> (tu/make-observer-session)
                            (queue-ast! {:type :literal, :value 42})
                            tu/run-session))]
      (is (nil? (vm/continuation vm)))
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (= 42 (vm/value vm)))))
  (testing "Environment is empty by default"
    (is (= {} (vm/environment (create-vm))))))


;; =============================================================================
;; Evaluation
;; =============================================================================

(deftest literal-test
  (is (= 42 (compile-and-run {:type :literal, :value 42}))))


(deftest literal-types-test
  (testing "All value types work as literals"
    (doseq [[desc value] [["string" "hello world"] ["boolean true" true]
                          ["boolean false" false] ["nil" nil] ["float" 3.14159]
                          ["negative" -99] ["vector" [1 2 3]]
                          ["map" {:x 10, :y 20}] ["keyword" :status]
                          ["set" #{1 2}]]]
      (testing desc (is (= value (compile-and-run {:type :literal,
                                                   :value value})))))))


(deftest literal-single-step-test
  (testing "A loaded literal completes in one step"
    (let [vm (ast-walker/vm-load-program
               (:consumer (tu/make-observer-session))
               (vm/ast->datoms {:type :literal, :value 42}))
          vm' (vm/step vm)]
      (is (= 42 (vm/value vm')))
      (is (vm/halted? vm'))
      (is (nil? (vm/control vm')))
      (is (nil? (vm/continuation vm'))))))


(defn- binop
  [op a b]
  {:type :application,
   :operator {:type :variable, :name op},
   :operands [{:type :literal, :value a} {:type :literal, :value b}]})


(deftest arithmetic-test
  (is (= 30 (compile-and-run (binop '+ 10 20)))))


(deftest all-arithmetic-primitives-test
  (is (= 30 (compile-and-run (binop '+ 10 20))))
  (is (= 5 (compile-and-run (binop '- 15 10))))
  (is (= 50 (compile-and-run (binop '* 5 10))))
  (is (= 4 (compile-and-run (binop '/ 20 5)))))


(deftest comparison-operations-test
  (is (true? (compile-and-run (binop '= 5 5))))
  (is (false? (compile-and-run (binop '= 5 6))))
  (is (true? (compile-and-run (binop '< 3 5))))
  (is (true? (compile-and-run (binop '> 10 5)))))


(deftest conditional-test
  (testing "If-else, true case"
    (is (= 100
           (compile-and-run {:type :if,
                             :test (binop '< 1 2),
                             :consequent {:type :literal, :value 100},
                             :alternate {:type :literal, :value 200}}))))
  (testing "If-else, false case"
    (is (= 200
           (compile-and-run {:type :if,
                             :test (binop '< 5 2),
                             :consequent {:type :literal, :value 100},
                             :alternate {:type :literal, :value 200}})))))


(def ^:private inc-lambda-call
  {:type :application,
   :operator {:type :lambda,
              :params ['x],
              :body {:type :application,
                     :operator {:type :variable, :name '+},
                     :operands [{:type :variable, :name 'x}
                                {:type :literal, :value 1}]}},
   :operands [{:type :literal, :value 10}]})


(deftest lambda-test
  (is (= 11 (compile-and-run inc-lambda-call))))


(deftest lambda-closure-test
  (testing "Lambda creates a closure"
    (let [closure (compile-and-run {:type :lambda,
                                    :params ['x],
                                    :body {:type :variable, :name 'x}})]
      (is (= :closure (:type closure)))
      (is (= ['x] (:params closure))))))


(deftest under-arity-call-binds-missing-param-to-nil-test
  (testing "A missing parameter is bound to nil, not left to fall through to an
            enclosing binding of the same name (§7.7.2)"
    (is (nil? (compile-and-run
                {:type :application,
                 :operator {:type :lambda,
                            :params ['y],
                            :body
                            {:type :application,
                             :operator {:type :lambda,
                                        :params ['x 'y],
                                        :body {:type :variable, :name 'y}},
                             :operands [{:type :literal, :value 1}]}},
                 :operands [{:type :literal, :value :outer-y}]})))))


(deftest under-arity-zero-args-call-binds-param-to-nil-test
  (testing "Calling a closure with no arguments binds its declared param to
            nil (exercises apply-function's zero-operand path)"
    (is (nil? (compile-and-run
                {:type :application,
                 :operator {:type :lambda,
                            :params ['x],
                            :body {:type :variable, :name 'x}},
                 :operands []})))))


(deftest over-arity-call-drops-extra-args-test
  (testing "Extra arguments beyond params are still dropped"
    (is (= 1
           (compile-and-run
             {:type :application,
              :operator {:type :lambda,
                         :params ['x],
                         :body {:type :variable, :name 'x}},
              :operands [{:type :literal, :value 1}
                         {:type :literal, :value 2}]})))))


(deftest nested-call-test
  (is (= 6
         (compile-and-run {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 1}
                                      (binop '+ 2 3)]}))))


;; =============================================================================
;; IVM protocol
;; =============================================================================

(deftest eval-literal-test
  (let [result (vm/eval (create-vm) {:type :literal, :value 42})]
    (is (vm/halted? result))
    (is (= 42 (vm/value result)))))


(deftest eval-arithmetic-test
  (is (= 30 (vm/value (vm/eval (create-vm) (binop '+ 10 20))))))


(deftest eval-lambda-test
  (is (= 11 (vm/value (vm/eval (create-vm) inc-lambda-call)))))


(deftest eval-effectful-primitive-single-invocation-test
  (testing "An effectful primitive is invoked exactly once per application"
    (let [calls (atom 0)
          emit! (fn []
                  (let [n (swap! calls inc)]
                    {:effect :vm/store-put, :key :effect/calls, :val n}))
          result (vm/eval (create-vm {:env {'emit! emit!}})
                          {:type :application,
                           :operator {:type :variable, :name 'emit!},
                           :operands []})]
      (is (= 1 @calls))
      (is (= 1 (vm/value result)))
      (is (= 1 (get (vm/store result) :effect/calls))))))


(deftest eval-blocked-effect-preserves-current-env-test
  (testing "A blocked effect keeps the active lexical environment"
    (let [vm0 (vm/eval (create-vm) {:type :stream/make, :buffer 2})
          stream-ref (vm/value vm0)
          vm1 (vm/eval vm0
                       {:type :stream/cursor,
                        :source {:type :literal, :value stream-ref}})
          cursor-ref (vm/value vm1)
          block-next (fn [cursor] {:effect :stream/next, :cursor cursor})
          vm-with-primitive
          (assoc vm1 :env (assoc (vm/environment vm1) 'block-next block-next))
          result (vm/eval vm-with-primitive
                          {:type :application,
                           :operator {:type :lambda,
                                      :params ['x],
                                      :body {:type :application,
                                             :operator {:type :variable,
                                                        :name 'block-next},
                                             :operands [{:type :variable,
                                                         :name 'x}]}},
                           :operands [{:type :literal, :value cursor-ref}]})]
      (is (vm/blocked? result))
      (is (= :yin/blocked (vm/value result)))
      (is (= cursor-ref (get (vm/environment result) 'x)))
      (is (= 1 (count (:wait-set result)))
          "The polling wait set is the only mechanism now"))))


;; =============================================================================
;; Streams
;; =============================================================================

(deftest stream-make-test
  (testing "stream/make returns a stream reference"
    (let [vm (vm/eval (create-vm) {:type :stream/make, :buffer 10})]
      (is (= :stream-ref (:type (vm/value vm))))
      (is (keyword? (:id (vm/value vm))))))
  (testing "stream/make with no declared buffer uses the VM default"
    (let [seen (atom nil)
          make (fn [c] (reset! seen c) (tu/make-stream c))
          vm (vm/eval (ast-walker/create-vm {:make-stream make})
                      {:type :stream/make})]
      (is (= :stream-ref (:type (vm/value vm))))
      (is (= vm/default-stream-capacity @seen))))
  (testing "Without :make-stream the VM says so rather than reaching for one"
    (is (throws? (fn []
                   (vm/eval (ast-walker/create-vm {})
                            {:type :stream/make, :buffer 4}))))))


(deftest stream-put-test
  (testing "stream/put returns the appended value"
    (let [vm0 (vm/eval (create-vm) {:type :stream/make, :buffer 5})
          stream-ref (vm/value vm0)
          vm1 (vm/eval vm0
                       {:type :stream/put,
                        :target {:type :literal, :value stream-ref},
                        :val {:type :literal, :value 42}})]
      (is (= 42 (vm/value vm1))))))


(defn- read-first-ast
  [stream-ref]
  {:type :application,
   :operator {:type :lambda,
              :params ['c],
              :body {:type :stream/next,
                     :source {:type :variable, :name 'c}}},
   :operands [{:type :stream/cursor,
               :source {:type :literal, :value stream-ref}}]})


(deftest stream-cursor-next-test
  (testing "cursor+next retrieves a value"
    (let [vm0 (vm/eval (create-vm) {:type :stream/make, :buffer 5})
          stream-ref (vm/value vm0)
          vm1 (vm/eval vm0
                       {:type :stream/put,
                        :target {:type :literal, :value stream-ref},
                        :val {:type :literal, :value 99}})]
      (is (= 99 (vm/value (vm/eval vm1 (read-first-ast stream-ref)))))))
  (testing "next from an empty stream blocks"
    (let [vm0 (vm/eval (create-vm) {:type :stream/make, :buffer 5})
          stream-ref (vm/value vm0)]
      (is (= :yin/blocked
             (vm/value (vm/eval vm0 (read-first-ast stream-ref))))))))


(deftest stream-close-ends-a-reader-test
  (testing "A closed, drained stream reads nil rather than blocking"
    (let [vm0 (vm/eval (create-vm) {:type :stream/make, :buffer 5})
          stream-ref (vm/value vm0)
          handle (get (vm/store vm0) (:id stream-ref))]
      (stream/close! handle)
      (is (nil? (vm/value (vm/eval vm0 (read-first-ast stream-ref))))))))


;; =============================================================================
;; Program observation, composed beside the VM
;; =============================================================================

(deftest ingress-runs-successive-batches-test
  (testing "Two queued programs both run"
    (let [session (-> (tu/make-observer-session)
                      (queue-ast! {:type :literal, :value 1})
                      (queue-ast! (binop '+ 2 3))
                      tu/run-session)]
      (is (= 5 (vm/value (:consumer session)))))))


(deftest ingress-across-a-gap-test
  (testing "An evicted batch is counted by the observer and the next one
            still runs"
    (let [session (tu/make-observer-session (create-vm) 2)]
      (doseq [ast [{:type :literal, :value 1} {:type :literal, :value 2}
                   {:type :literal, :value 3}]]
        (queue-ast! session ast))
      (let [session' (tu/run-session session)]
        (is (= 1 (:ingress-gaps (:observer session'))))
        (is (= 3 (vm/value (:consumer session')))
            "Evaluation continues from the recovery cursor")))))


(deftest direct-eval-does-not-drain-queued-program-input-test
  (testing "eval runs its supplied program while malformed input sits queued"
    (let [session (tu/make-observer-session)]
      (stream/append! (:stream (:observer session)) [[1 :not/yin 1 0 true]])
      (is (= 7 (vm/value (vm/eval (:consumer session) {:type :literal, :value 7}))))
      (is (throws? (fn [] (tu/run-session session)))
          "Coordination still hands the queued batch to the loader, which
              rejects it"))))


(deftest obsolete-in-stream-option-is-rejected-test
  (testing "A VM no longer accepts :in-stream, and says so before allocating
            FFI streams"
    (let [created (atom 0)
          make (fn [capacity]
                 (swap! created inc)
                 (tu/make-stream capacity))]
      (is (throws? (fn []
                     (ast-walker/create-vm
                       {:make-stream make,
                        :in-stream (tu/new-stream 4)}))))
      (is (zero? @created)
          "The rejection precedes FFI resource allocation"))))


;; =============================================================================
;; Telemetry is a stub
;; =============================================================================

(deftest telemetry-opt-is-rejected-test
  (testing "A supplied telemetry stream would be named and never written"
    (is (throws? (fn [] (create-vm {:telemetry {:stream (tu/new-stream 4)}}))))))

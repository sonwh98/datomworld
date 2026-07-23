(ns yin.vm.register-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as ds]
            [dao.stream.apply :as dao.stream.apply]
            [dao.stream.ringbuffer]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.register :as register]))


;; =============================================================================
;; Bytecode VM tests (full pipeline through numeric bytecode)
;; =============================================================================

(defn- ensure-program-stream
  [vm-state]
  (if (:in-stream vm-state)
    vm-state
    (assoc vm-state
           :in-stream (ds/open! {:type :ringbuffer, :capacity nil})
           :in-cursor {:position 0})))


(defn- queue-program!
  [vm-state {:keys [datoms]}]
  (let [vm-state' (ensure-program-stream vm-state)]
    (ds/append! (:in-stream vm-state') (vec datoms))
    (assoc vm-state' :halted? false)))


(defn- queue-ast!
  [vm-state ast]
  (queue-program! vm-state {:datoms (vm/ast->datoms ast)}))


(defn- bridge-step
  [vm handlers cursor]
  (let [call-in (get (vm/store vm) vm/call-in-stream-key)
        {:keys [ok], :as next-result} (ds/next call-in cursor)
        cursor' (:cursor next-result)]
    (if ok
      (let [{request-id :dao.stream.apply/id,
             request-op :dao.stream.apply/op,
             request-args :dao.stream.apply/args}
            ok
            result (apply (get handlers request-op) (or request-args []))
            call-out (get (vm/store vm) vm/call-out-stream-key)
            ;; ds/append! on RingBufferStream returns woken entries
            put-result (ds/append! call-out
                                   (dao.stream.apply/response request-id
                                                              result))
            woke (:woke put-result)
            ;; Use engine helper to transform woken entries into run-queue
            ;; entries
            entries (engine/make-woken-run-queue-entries vm woke)
            vm' (update vm :ready-queue (fnil into []) entries)]
        [vm' cursor'])
      [vm cursor])))


(defn compile-and-run-bc
  "Compile AST to datoms and run to completion."
  [ast]
  (-> (queue-ast! (register/create-vm) ast)
      (vm/run)
      (vm/value)))


(defn- load-ast
  "Queue an AST on a Register VM ingress stream."
  [ast]
  (queue-ast! (register/create-vm) ast))


(defn- ast->program
  [ast]
  (let [datoms (vec (vm/ast->datoms ast))
        root-id (apply max (map first datoms))]
    {:node root-id, :datoms datoms}))


(defn- step-until-halt
  [vm-state]
  (loop [v vm-state
         ticks 0]
    (cond (vm/halted? v) v
          (> ticks 2000) (throw (ex-info "Step limit exceeded" {:ticks ticks}))
          :else (recur (vm/step v) (inc ticks)))))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (register/create-vm)]
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-in-cursor-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (contains? (vm/store vm) vm/call-out-cursor-key))
      (is (nil? (vm/continuation vm)))))
  (testing "Queued ingress is consumed on run"
    (let [vm (load-ast {:type :literal, :value 42})
          result (vm/run vm)]
      (is (= {:position 1} (:in-cursor result)))
      (is (= 42 (vm/value result)))))
  (testing "After run, continuation is nil and store is empty"
    (let [vm (-> (load-ast {:type :literal, :value 42})
                 (vm/run))]
      (is (nil? (vm/continuation vm)))
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (= 42 (vm/value vm)))))
  (testing "Environment stores lexical bindings only"
    (let [vm (register/create-vm {:env {'x 1}})]
      (is (= 1 (get (vm/environment vm) 'x))))))


;; =============================================================================
;; IVMEval protocol tests
;; =============================================================================

(deftest eval-literal-test
  (testing "vm/eval evaluates a literal AST directly"
    (let [result (vm/eval (register/create-vm) {:type :literal, :value 42})]
      (is (vm/halted? result))
      (is (= 42 (vm/value result))))))


(deftest eval-arithmetic-test-via-eval
  (testing "vm/eval evaluates (+ 10 20) from AST directly"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/eval (register/create-vm) ast)]
      (is (= 30 (vm/value result))))))


(deftest load-program-canonical-stream-test
  (testing "Register VM accepts canonical {:node :datoms} programs"
    (let [program (ast->program {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :literal, :value 10}
                                            {:type :literal, :value 20}]})
          result (-> (register/create-vm)
                     (queue-program! program)
                     (vm/run))]
      (is (vm/halted? result))
      (is (= 30 (vm/value result)))
      (is (= (:node program) (:program-root-eid result)))
      (is (= 1 (:active-compiled-version result)))
      (is (seq (:compiled-by-version result))))))


(deftest dao-call-asm-shape-test
  (testing
    ":dao.stream.apply/call compiles to register :dao.stream.apply/call instruction"
    (let [ast {:type :dao.stream.apply/call,
               :op :op/echo,
               :operands [{:type :literal, :value 42}]}
          {:keys [asm]} (register/ast-datoms->asm (vm/ast->datoms ast))
          ffi-instr (some #(when (= :dao.stream.apply/call (first %)) %) asm)]
      (is (= :op/echo (nth ffi-instr 2)))
      (is (= 1 (count (nth ffi-instr 3)))))))


(deftest dao-call-eval-test
  (testing
    "Register VM executes dao.stream.apply/call via dao.stream.apply streams"
    (let [ast {:type :dao.stream.apply/call,
               :op :op/echo,
               :operands [{:type :literal, :value 42}]}
          vm (register/create-vm)
          result-parked (vm/eval vm ast)]
      (is (vm/blocked? result-parked))
      (let [[vm' _cursor']
            (bridge-step result-parked {:op/echo identity} {:position 0})
            result (vm/eval vm' nil)]
        (is (vm/halted? result))
        (is (= 42 (vm/value result)))))))


(deftest repeated-dao-call-program-test
  (testing
    "Register VM halts after many sequential dao.stream.apply/call cycles via create-vm bridge"
    (let
      [forms
       '[(defn plot-loop
           [i]
           (if
             (> i 199)
             nil
             (do
               (dao.stream.apply/call :plot/point i (* i i))
               (plot-loop (+ i 1))))) (plot-loop 0)]
       ast (yang/compile-program forms)
       program (ast->program ast)
       calls (atom [])
       handlers {:plot/point (fn [x y] (swap! calls conj [x y]) nil)}
       result (-> (register/create-vm {:bridge handlers})
                  (queue-program! program)
                  (vm/run))]
      (is (vm/halted? result))
      (is (nil? (vm/value result)))
      (is (= 200 (count @calls)))
      (is (= [0 0] (first @calls)))
      (is (= [199 39601] (last @calls))))))


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


;; =============================================================================
;; Register bytecode compilation shape tests
;; =============================================================================
;; These test the compilation pipeline: AST -> datoms -> register bytecode

(deftest register-bytecode-shape-test
  (testing "Literal produces literal + return"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms {:type :literal, :value 42}))]
      (is (vector? asm))
      (is (= 2 (count asm)))
      (is (= :literal (first (first asm)))
          "First instruction should be :literal")
      (is (= 42 (nth (first asm) 2)) "Should load the value 42")
      (is (= :return (first (last asm))) "Last instruction should be :return")))
  (testing "Variable produces load-var + return"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms {:type :variable, :name 'x}))]
      (is (= 2 (count asm)))
      (is (= :load-var (first (first asm))))
      (is (= 'x (nth (first asm) 2)))
      (is (= :return (first (last asm))))))
  (testing "All instructions are vectors"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms
                            {:type :application,
                             :operator {:type :variable, :name '+},
                             :operands [{:type :literal, :value 1}
                                        {:type :literal, :value 2}]}))]
      (is (every? vector? asm)))))


(deftest register-bytecode-application-test
  (testing "Application produces literal, load-var, tailcall, and return"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms
                            {:type :application,
                             :operator {:type :variable, :name '+},
                             :operands [{:type :literal, :value 1}
                                        {:type :literal, :value 2}]}))]
      (is (= 5 (count asm)))
      (is (= :literal (first (nth asm 0))))
      (is (= :literal (first (nth asm 1))))
      (is (= :load-var (first (nth asm 2))))
      (is (= :tailcall (first (nth asm 3))))
      (is (= :return (first (nth asm 4))))))
  (testing "Tailcall instruction references correct registers"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms
                            {:type :application,
                             :operator {:type :variable, :name '+},
                             :operands [{:type :literal, :value 1}
                                        {:type :literal, :value 2}]}))
          call-instr (nth asm 3)
          [op rd rf arg-regs] call-instr]
      (is (= :tailcall op))
      (is (integer? rd) "Result register should be an integer")
      (is (integer? rf) "Function register should be an integer")
      (is (vector? arg-regs) "Arg registers should be a vector")
      (is (= 2 (count arg-regs)) "Should have 2 arg registers"))))


(deftest register-bytecode-lambda-test
  (testing "Lambda produces closure, jump, body, and return"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms {:type :lambda,
                                           :params ['x],
                                           :body {:type :variable, :name 'x}}))]
      (is (= :lambda (first (nth asm 0))) "First instruction should be :lambda")
      (is (= :jump (first (nth asm 1)))
          "Second instruction should be :jump over body")
      (is (= :load-var (first (nth asm 2))) "Body should start with :load-var")
      (is (= :return (first (nth asm 3))) "Body should end with :return")))
  (testing "Closure body address points to correct instruction"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms {:type :lambda,
                                           :params ['x],
                                           :body {:type :variable, :name 'x}}))
          [_ _rd _params _body-addr _reg-count] (first asm)]
      (is (= 2 _body-addr)
          "Body should start at instruction 2 (after closure + jump)")))
  (testing "Jump skips over body to return"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms {:type :lambda,
                                           :params ['x],
                                           :body {:type :variable, :name 'x}}))
          [_ jump-addr] (nth asm 1)
          jump-target (get asm jump-addr)]
      (is (= :return (first jump-target))
          "Jump should land on the final :return"))))


(deftest register-bytecode-conditional-test
  (testing "If produces branch, both branches, and move instructions"
    (let [{:keys [asm]} (register/ast-datoms->asm
                          (vm/ast->datoms
                            {:type :if,
                             :test {:type :literal, :value true},
                             :consequent {:type :literal, :value 1},
                             :alternate {:type :literal, :value 0}}))]
      (is (= :literal (first (first asm))) "Should start with test expression")
      (is (some #(= :branch (first %)) asm)
          "Should contain a branch instruction")
      (is (some #(= :move (first %)) asm)
          "Should contain move instructions for result register"))))


(deftest register-bytecode-continuation-is-data-test
  (testing "Continuation frame is created during non-tail closure call"
    ;; (+ ((fn [x] x) 42) 0) — the inner call is NOT in tail position
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :application,
                           :operator {:type :lambda,
                                      :params ['x],
                                      :body {:type :variable, :name 'x}},
                           :operands [{:type :literal, :value 42}]}
                          {:type :literal, :value 0}]}
          vm-inst (register/create-vm)
          vm-loaded (queue-program! vm-inst (ast->program ast))
          states (loop [v vm-loaded
                        acc []]
                   (if (vm/halted? v) acc (recur (vm/step v) (conj acc v))))
          inside-closure (first (filter #(some? (vm/continuation %)) states))]
      (is (some? inside-closure)
          "Should have a state with non-nil continuation")
      (is (= :call-frame (:type (first (vm/continuation inside-closure))))
          "Continuation should be a :call-frame")
      (is (vector? (:regs (first (vm/continuation inside-closure))))
          "Continuation should save caller registers")
      (is (integer? (:control (first (vm/continuation inside-closure))))
          "Continuation should have a return PC"))))


(deftest boundary-recompile-test
  (testing
    "In-flight execution completes on old artifact; reset runs new version"
    (let [program-v1 (ast->program {:type :application,
                                    :operator {:type :variable, :name '+},
                                    :operands [{:type :literal, :value 1}
                                               {:type :literal, :value 2}]})
          program-v2 (ast->program {:type :literal, :value 42})
          vm0 (-> (register/create-vm)
                  (queue-program! program-v1)
                  (vm/step))
          vm1 (register/append-program-datoms vm0
                                              (:datoms program-v2)
                                              (:node program-v2))
          vm2 (step-until-halt vm1)]
      (is (= 3 (vm/value vm2)))
      (is (= 2 (:program-version vm2)))
      (is (= 2 (:active-compiled-version vm2)))
      (let [vm3 (-> (vm/reset vm2)
                    (vm/run))]
        (is (= 42 (vm/value vm3)))))))


(deftest compiled-cache-retention-test
  (testing "Compiled cache keeps newest versions plus pinned versions"
    (let [program-v1 (ast->program {:type :literal, :value 1})
          program-v2 (ast->program {:type :literal, :value 2})
          program-v3 (ast->program {:type :literal, :value 3})
          vm0 (register/create-vm {:compiled-cache-limit 1})
          vm1 (-> (queue-program! vm0 program-v1)
                  (vm/step))
          ;; Pin version 1 through a runnable continuation entry.
          vm1 (assoc vm1
                     :ready-queue [{:type :call-frame, :compiled-version 1}])
          vm2 (register/maybe-recompile-at-boundary
                (register/append-program-datoms vm1
                                                (:datoms program-v2)
                                                (:node program-v2)))
          vm3 (register/maybe-recompile-at-boundary
                (register/append-program-datoms vm2
                                                (:datoms program-v3)
                                                (:node program-v3)))]
      (is (= #{1 2} (set (keys (:compiled-by-version vm2)))))
      (is (= #{1 3} (set (keys (:compiled-by-version vm3))))))))


;; =============================================================================
;; Stream operation tests (cursor-based)
;; =============================================================================

(defn- make-stream-vm
  "Create a Register VM with primitives, suitable for stream operations."
  []
  (register/create-vm))


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
        (= 1024
           (.-capacity #?(:cljs ^dao.stream.ringbuffer/RingBufferStream stream
                          :cljd ^dao.stream.ringbuffer/RingBufferStream stream
                          :default stream)))
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
      (is (= 1 (count stream)))))
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
      (is (= 2 (count stream))))))


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


(deftest continuation-park-resume-parity-test
  (testing
    "Register VM should support vm/park and vm/resume like other backends"
    (let [vm0 (register/create-vm)
          vm1 (vm/eval vm0 {:type :vm/park})
          parked-cont (vm/value vm1)
          parked-id (:id parked-cont)
          vm2 (vm/eval vm1
                       {:type :vm/resume,
                        :parked-id parked-id,
                        :val {:type :literal, :value 42}})]
      (is (= :parked-continuation (:type parked-cont)))
      (is (= 42 (vm/value vm2)))
      (is (nil? (get-in vm2 [:parked parked-id]))))))


(deftest current-continuation-test
  (testing "Register VM supports :vm/current-continuation"
    (let [vm0 (register/create-vm)
          ;; Reify current continuation, then return 42
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['k],
                          :body {:type :literal, :value 42}},
               :operands [{:type :vm/current-continuation}]}
          vm1 (vm/eval vm0 ast)
          _ (vm/value vm1)
          ;; In this specific AST, the value returned is 42, but we can
          ;; inspect the VM state to see if a continuation was reified
          ;; during execution. Better test: a lambda that captures the
          ;; continuation and returns it.
          ast-capture {:type :vm/current-continuation}
          vm2 (vm/eval vm0 ast-capture)
          reified (vm/value vm2)]
      (is (= :reified-continuation (:type reified)))
      (is (vector? (:regs reified)))
      (is (integer? (:control reified))))))


;; =============================================================================
;; Tail Call Optimization tests
;; =============================================================================

(deftest tco-tail-recursive-countdown-test
  (testing
    "Tail-recursive countdown runs in constant stack via self-application"
    ;; ((fn [self n] (if (< n 1) 0 (self self (- n 1)))) <same-fn> 10000)
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
                                                    :value 1}]}]}}}
          ast {:type :application,
               :operator self-fn,
               :operands [self-fn {:type :literal, :value 10000}]}]
      (is (= 0 (compile-and-run-bc ast))))))


(deftest tco-accumulator-test
  (testing "Tail-recursive accumulator computes sum correctly"
    ;; ((fn [self n acc] (if (< n 1) acc (self self (- n 1) (+ acc n))))
    ;; <same> 100 0)
    (let [self-fn {:type :lambda,
                   :params ['self 'n 'acc],
                   :body {:type :if,
                          :test {:type :application,
                                 :operator {:type :variable, :name '<},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal, :value 1}]},
                          :consequent {:type :variable, :name 'acc},
                          :alternate
                          {:type :application,
                           :operator {:type :variable, :name 'self},
                           :operands [{:type :variable, :name 'self}
                                      {:type :application,
                                       :operator {:type :variable, :name '-},
                                       :operands [{:type :variable, :name 'n}
                                                  {:type :literal, :value 1}]}
                                      {:type :application,
                                       :operator {:type :variable, :name '+},
                                       :operands
                                       [{:type :variable, :name 'acc}
                                        {:type :variable, :name 'n}]}]}}}
          ast {:type :application,
               :operator self-fn,
               :operands [self-fn {:type :literal, :value 100}
                          {:type :literal, :value 0}]}]
      (is (= 5050 (compile-and-run-bc ast))))))


(deftest tco-non-tail-regression-test
  (testing "Non-tail calls still work correctly (fibonacci)"
    ;; ((fn [self n] (if (< n 2) n (+ (self self (- n 1)) (self self (- n
    ;; 2))))) <same> 10)
    (let [self-fn
          {:type :lambda,
           :params ['self 'n],
           :body {:type :if,
                  :test {:type :application,
                         :operator {:type :variable, :name '<},
                         :operands [{:type :variable, :name 'n}
                                    {:type :literal, :value 2}]},
                  :consequent {:type :variable, :name 'n},
                  :alternate
                  {:type :application,
                   :operator {:type :variable, :name '+},
                   :operands
                   [{:type :application,
                     :operator {:type :variable, :name 'self},
                     :operands [{:type :variable, :name 'self}
                                {:type :application,
                                 :operator {:type :variable, :name '-},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal, :value 1}]}]}
                    {:type :application,
                     :operator {:type :variable, :name 'self},
                     :operands [{:type :variable, :name 'self}
                                {:type :application,
                                 :operator {:type :variable, :name '-},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal,
                                             :value 2}]}]}]}}}
          ast {:type :application,
               :operator self-fn,
               :operands [self-fn {:type :literal, :value 10}]}]
      (is (= 55 (compile-and-run-bc ast))))))


(deftest tco-continuation-depth-test
  (testing "Tail calls do not grow continuation depth"
    ;; Run a tail-recursive program, inspect k depth at the end
    ;; ((fn [self n] (if (< n 1) 0 (self self (- n 1)))) <same> 100)
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
                                                    :value 1}]}]}}}
          ast {:type :application,
               :operator self-fn,
               :operands [self-fn {:type :literal, :value 100}]}
          vm-inst (register/create-vm)
          vm-loaded (queue-program! vm-inst (ast->program ast))
          ;; Step through, collecting k depth at each step
          k-depth (fn [k]
                    (loop [k k d 0] (if (nil? k) d (recur (:k k) (inc d)))))
          max-depth (loop [v vm-loaded
                           max-d 0]
                      (if (vm/halted? v)
                        max-d
                        (let [v' (vm/step v)
                              d (k-depth (vm/continuation v'))]
                          (recur v' (max max-d d)))))]
      ;; With TCO, the max continuation depth should be 1 (the initial
      ;; call frame from the outer application, never growing beyond that)
      (is (<= max-depth 1)
          (str "Max continuation depth should be <= 1 with TCO, got "
               max-depth)))))

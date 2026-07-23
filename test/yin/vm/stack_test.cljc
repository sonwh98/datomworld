(ns yin.vm.stack-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as ds]
            [dao.stream.apply :as dao.stream.apply]
            [dao.stream.ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.stack :as stack]))


;; =============================================================================
;; Stack VM tests (full pipeline through numeric bytecode via protocols)
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


(defn compile-and-run
  "Compile AST to datoms and run to completion."
  [ast]
  (-> (queue-ast! (stack/create-vm) ast)
      (vm/run)
      (vm/value)))


(defn- load-ast
  "Queue an AST on a Stack VM ingress stream."
  [ast]
  (queue-ast! (stack/create-vm) ast))


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


(defn- linked-depth
  [frame]
  (loop [k frame depth 0] (if (map? k) (recur (:next k) (inc depth)) depth)))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (stack/create-vm)]
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-in-cursor-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (contains? (vm/store vm) vm/call-out-cursor-key))
      (is (nil? (:call-stack vm)))
      (is (empty? (vm/continuation vm)))))
  (testing "Queued ingress is consumed on run"
    (let [vm (load-ast {:type :literal, :value 42})
          result (vm/run vm)]
      (is (= {:position 1} (:in-cursor result)))
      (is (= 42 (vm/value result)))))
  (testing "After run, continuation is empty and store is empty"
    (let [vm (-> (load-ast {:type :literal, :value 42})
                 (vm/run))]
      (is (nil? (:call-stack vm)))
      (is (empty? (vm/continuation vm)))
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (= 42 (vm/value vm)))))
  (testing "Environment stores lexical bindings only"
    (let [vm (stack/create-vm {:env {'x 1}})]
      (is (= 1 (get (vm/environment vm) 'x))))))


;; =============================================================================
;; IVMEval protocol tests
;; =============================================================================

(deftest eval-literal-test
  (testing "vm/eval evaluates a literal AST directly"
    (let [result (vm/eval (stack/create-vm) {:type :literal, :value 42})]
      (is (vm/halted? result))
      (is (= 42 (vm/value result))))))


(deftest eval-arithmetic-test-via-eval
  (testing "vm/eval evaluates (+ 10 20) from AST directly"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/eval (stack/create-vm) ast)]
      (is (= 30 (vm/value result))))))


(deftest load-program-canonical-stream-test
  (testing "Stack VM accepts canonical {:node :datoms} programs"
    (let [program (ast->program {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :literal, :value 10}
                                            {:type :literal, :value 20}]})
          result (-> (queue-program! (stack/create-vm) program)
                     (vm/run))]
      (is (vm/halted? result))
      (is (= 30 (vm/value result)))
      (is (= (:node program) (:program-root-eid result)))
      (is (= 1 (:active-compiled-version result)))
      (is (seq (:compiled-by-version result))))))


(deftest dao-call-asm-shape-test
  (testing
    ":dao.stream.apply/call compiles to [:dao.stream.apply/call op argc] in stack asm"
    (let [ast {:type :dao.stream.apply/call,
               :op :op/echo,
               :operands [{:type :literal, :value 42}]}
          asm (stack/ast-datoms->asm (vm/ast->datoms ast))
          ffi-instr (some #(when (= :dao.stream.apply/call (first %)) %) asm)]
      (is (= [:dao.stream.apply/call :op/echo 1] ffi-instr)))))


(deftest dao-call-eval-test
  (testing
    "Stack VM executes dao.stream.apply/call via dao.stream.apply streams"
    (let [ast {:type :dao.stream.apply/call,
               :op :op/echo,
               :operands [{:type :literal, :value 42}]}
          vm (stack/create-vm)
          result-parked (vm/eval vm ast)]
      (is (vm/blocked? result-parked))
      (let [[vm' _cursor']
            (bridge-step result-parked {:op/echo identity} {:position 0})
            result (vm/eval vm' nil)]
        (is (vm/halted? result))
        (is (= 42 (vm/value result)))))))


(deftest literal-test
  (testing "Literal via stack VM"
    (is (= 42 (compile-and-run {:type :literal, :value 42})))))


(deftest arithmetic-test
  (testing "Addition (+ 10 20) via stack VM"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run ast))))))


(deftest conditional-test
  (testing "If-else (true case) via stack VM"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run ast)))))
  (testing "If-else (false case) via stack VM"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run ast))))))


(deftest lambda-test
  (testing "Lambda application ((fn [x] (+ x 1)) 10) via stack VM"
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
  (testing "Nested calls (+ 1 (+ 2 3)) via stack VM"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run ast))))))


(deftest continuation-linked-list-projection-test
  (testing
    "Active non-tail calls use linked frames internally and a vector externally"
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
               :operands [self-fn {:type :literal, :value 6}]}
          vm-loaded (load-ast ast)]
      (loop [v vm-loaded
             ticks 0]
        (cond
          (> ticks 400)
          (is false "Expected to observe a linked continuation before halt")
          (>= (linked-depth (:k v)) 2)
          (let [head (:k v)
                projected (vm/continuation v)]
            (is (map? head))
            (is (contains? head :next))
            (is (not (contains? head :call-stack)))
            (is (vector? projected))
            (is (= (linked-depth head) (count projected)))
            (is (= head (first projected)))
            (is (= (:next head) (second projected))))
          (vm/halted? v)
          (is
            false
            "Program halted before a nested continuation frame was observed")
          :else (recur (vm/step v) (inc ticks)))))))


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


(deftest boundary-recompile-test
  (testing
    "In-flight execution completes on old artifact; reload runs new version"
    (let [program-v1 (ast->program {:type :application,
                                    :operator {:type :variable, :name '+},
                                    :operands [{:type :literal, :value 1}
                                               {:type :literal, :value 2}]})
          program-v2 (ast->program {:type :literal, :value 42})
          vm0 (-> (stack/create-vm)
                  (queue-program! program-v1)
                  (vm/step))
          vm1 (stack/append-program-datoms vm0
                                           (:datoms program-v2)
                                           (:node program-v2))
          vm2 (step-until-halt vm1)
          vm3 (queue-program! vm2
                              {:node (:program-root-eid vm2),
                               :datoms (:datoms vm2)})
          vm4 (vm/run vm3)]
      (is (= 3 (vm/value vm2)))
      (is (= 2 (:program-version vm2)))
      (is (= 2 (:active-compiled-version vm2)))
      (is (= 42 (vm/value vm4))))))


(deftest compiled-cache-retention-test
  (testing "Compiled cache keeps newest versions plus pinned versions"
    (let [program-v1 (ast->program {:type :literal, :value 1})
          program-v2 (ast->program {:type :literal, :value 2})
          program-v3 (ast->program {:type :literal, :value 3})
          vm0 (stack/create-vm {:compiled-cache-limit 1})
          vm1 (-> (queue-program! vm0 program-v1)
                  (vm/step))
          ;; Pin version 1 through a runnable continuation entry.
          vm1 (assoc vm1
                     :ready-queue [{:type :call-frame, :compiled-version 1}])
          vm2 (stack/maybe-recompile-at-boundary (stack/append-program-datoms
                                                   vm1
                                                   (:datoms program-v2)
                                                   (:node program-v2)))
          vm3 (stack/maybe-recompile-at-boundary (stack/append-program-datoms
                                                   vm2
                                                   (:datoms program-v3)
                                                   (:node program-v3)))]
      (is (= #{1 2} (set (keys (:compiled-by-version vm2)))))
      (is (= #{1 3} (set (keys (:compiled-by-version vm3))))))))


;; =============================================================================
;; Stream operation tests (cursor-based)
;; =============================================================================

(defn- make-stream-vm
  "Create a Stack VM with primitives, suitable for stream operations."
  []
  (stack/create-vm))


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


(deftest continuation-park-resume-test
  (testing "Resuming a parked continuation continues after park"
    (let [vm0 (stack/create-vm)
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


;; =============================================================================
;; Tail Call Optimization tests
;; =============================================================================

(deftest tco-asm-tailcall-test
  (testing "Top-level application compiles to :tailcall"
    (let [asm (stack/ast-datoms->asm
                (vm/ast->datoms {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :literal, :value 1}
                                            {:type :literal, :value 2}]}))]
      (is (= :tailcall (first (last asm)))))))


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
      (is (= 0 (compile-and-run ast))))))


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
      (is (= 5050 (compile-and-run ast))))))


(deftest tco-non-tail-regression-test
  (testing "Non-tail calls still work correctly (fibonacci)"
    ;; ((fn [self n] (if (< n 2) n (+ (self self (- n 1)) (self self (- n
    ;; 2)))))
    ;; <same> 10)
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
      (is (= 55 (compile-and-run ast))))))


(deftest tco-continuation-depth-test
  (testing "Tail calls do not grow continuation depth"
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
          vm-loaded (load-ast ast)
          max-depth (loop [v vm-loaded
                           max-d 0]
                      (if (vm/halted? v)
                        max-d
                        (let [v' (vm/step v)
                              d (count (or (vm/continuation v') []))]
                          (recur v' (max max-d d)))))]
      (is (<= max-depth 1)
          (str "Max call-stack depth should be <= 1 with TCO, got "
               max-depth)))))

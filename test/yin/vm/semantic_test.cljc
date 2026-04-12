(ns yin.vm.semantic-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as dao-db]
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [dao.stream.transport.ringbuffer]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.semantic :as semantic]))


;; =============================================================================
;; Semantic VM tests (datom graph traversal via protocols)
;; =============================================================================

(defn- queue-vm
  [vm-state datoms]
  (let [in-stream (ds/open! {:transport {:type :ringbuffer
                                         :capacity nil}})
        queued-vm (assoc vm-state
                         :in-stream in-stream
                         :in-cursor {:position 0}
                         :halted false)]
    (ds/put! in-stream (vec datoms))
    queued-vm))


(defn- bridge-step
  [vm handlers cursor]
  (let [call-in (get (vm/store vm) vm/call-in-stream-key)
        {:keys [ok] :as next-result} (ds/next call-in cursor)
        cursor' (:cursor next-result)]
    (if ok
      (let [{request-id :dao.stream.apply/id
             request-op :dao.stream.apply/op
             request-args :dao.stream.apply/args} ok
            result (apply (get handlers request-op) (or request-args []))
            call-out (get (vm/store vm) vm/call-out-stream-key)
            ;; ds/put! on RingBufferStream returns woken entries
            put-result (ds/put! call-out (dao.stream.apply/response request-id result))
            woke (:woke put-result)
            ;; Use engine helper to transform woken entries into run-queue entries
            entries (engine/make-woken-run-queue-entries vm woke)
            vm' (update vm :run-queue (fnil into []) entries)]
        [vm' cursor'])
      [vm cursor])))


(defn compile-and-run
  [ast]
  (let [datoms (vm/ast->datoms ast)]
    (-> (queue-vm (semantic/create-vm) datoms)
        (vm/run)
        (vm/value))))


(defn- load-ast
  [ast]
  (queue-vm (semantic/create-vm) (vm/ast->datoms ast)))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (semantic/create-vm)]
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-in-cursor-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (contains? (vm/store vm) vm/call-out-cursor-key))
      (is (nil? (vm/control vm)))
      (is (empty? (vm/continuation vm)))))
  (testing "Queued ingress is consumed on run"
    (let [vm (load-ast {:type :literal, :value 42})
          result (vm/run vm)]
      (is (= {:position 1} (:in-cursor result)))
      (is (= 42 (vm/value result)))))
  (testing "After run, continuation is empty and store is empty"
    (let [vm (-> (load-ast {:type :literal, :value 42})
                 (vm/run))]
      (is (empty? (vm/continuation vm)))
      (is (contains? (vm/store vm) vm/call-in-stream-key))
      (is (contains? (vm/store vm) vm/call-out-stream-key))
      (is (= 42 (vm/value vm)))))
  (testing "Environment stores lexical bindings only"
    (let [vm (semantic/create-vm {:env {'x 1}})]
      (is (= 1 (get (vm/environment vm) 'x))))))


(deftest dao-backed-ast-test
  (testing "load-program transacts AST datoms into a queryable DaoDB"
    (let [ast {:type :application
               :operator {:type :variable, :name '+}
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          [_root-tempid datoms] (vm/ast->datoms-with-root ast)
          loaded (-> (queue-vm (semantic/create-vm) datoms)
                     (vm/run))
          ast-db (:db loaded)
          root-eid (ffirst (dao-db/q '[:find ?e
                                       :where [?e :yin/type :application]]
                                     ast-db))]
      (is (satisfies? dao-db/IDaoDB ast-db))
      (is (= :application (:yin/type (dao-db/entity-attrs ast-db root-eid))))
      (is (= #{['+]}
             (dao-db/q '[:find ?name
                         :where [_ :yin/name ?name]]
                       ast-db)))
      (is (= 3 (-> loaded vm/run vm/value)))))
  (testing "nil literal values remain explicit AST facts after DaoDB load"
    (let [[_root-tempid datoms] (vm/ast->datoms-with-root {:type :literal, :value nil})
          loaded (-> (queue-vm (semantic/create-vm) datoms)
                     (vm/run))
          root-eid (ffirst (dao-db/q '[:find ?e
                                       :where [?e :yin/type :literal]]
                                     (:db loaded)))
          attrs (dao-db/entity-attrs (:db loaded) root-eid)
          result loaded]
      (is (contains? attrs :yin/value))
      (is (vm/halted? result))
      (is (nil? (vm/value result))))))


;; =============================================================================
;; IVMEval protocol tests
;; =============================================================================

(deftest eval-literal-test
  (testing "vm/eval evaluates a literal AST directly"
    (let [result (vm/eval (semantic/create-vm)
                          {:type :literal, :value 42})]
      (is (vm/halted? result))
      (is (= 42 (vm/value result))))))


(deftest eval-arithmetic-test-via-eval
  (testing "vm/eval evaluates (+ 10 20) from AST directly"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/eval (semantic/create-vm) ast)]
      (is (= 30 (vm/value result))))))


(deftest eval-dao-call-test
  (testing "Semantic VM executes dao.stream.apply/call via dao.stream.apply streams"
    (let [ast {:type :dao.stream.apply/call,
               :op :op/echo,
               :operands [{:type :literal, :value 42}]}
          vm (semantic/create-vm)
          result-parked (vm/eval vm ast)]
      (is (vm/blocked? result-parked))
      (let [[vm' _cursor'] (bridge-step result-parked {:op/echo identity} {:position 0})
            result (vm/eval vm' nil)]
        (is (vm/halted? result))
        (is (= 42 (vm/value result)))))))


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
  (semantic/create-vm))


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
        (= 1024 (.-capacity #?(:cljs ^dao.stream.transport.ringbuffer/RingBufferStream stream
                               :cljd ^dao.stream.transport.ringbuffer/RingBufferStream stream
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


(deftest continuation-park-resume-test
  (testing "Semantic VM handles vm/resume nodes emitted from AST conversion"
    (let [vm0 (semantic/create-vm)
          vm1 (vm/eval vm0 {:type :vm/park})
          parked-cont (vm/value vm1)
          parked-id (:id parked-cont)
          vm2 (vm/eval vm1 {:type :vm/resume, :parked-id parked-id, :val {:type :literal, :value 42}})]
      (is (= :parked-continuation (:type parked-cont)))
      (is (= 42 (vm/value vm2)))
      (is (nil? (get-in vm2 [:parked parked-id]))))))


(deftest semantic-multi-load-test
  (testing
    "SemanticVM should handle multiple program loads without ID collisions"
    (let [vm (semantic/create-vm)
          ;; First load: a simple literal
          vm-1 (vm/eval vm {:type :literal, :value 1})
          _ (is (= 1 (vm/value vm-1)))
          ;; Second load: a variable reference
          vm-2 (vm/eval vm-1 {:type :variable, :name '+})
          _ (is (fn? (vm/value vm-2)))
          ;; Third load: an application
          vm-3 (vm/eval vm-2
                        {:type :application,
                         :operator {:type :variable, :name '+},
                         :operands [{:type :literal, :value 2}
                                    {:type :literal, :value 3}]})
          _ (is (= 5 (vm/value vm-3)))
          ;; Verify that user AST nodes are preserved and resolved into DaoDB IDs.
          datoms (:datoms vm-3)
          node-ids (->> datoms
                        (keep (fn [[e a _v _t _m]]
                                (when (= :yin/type a) e)))
                        set)]
      (is (satisfies? dao-db/IDaoDB (:db vm-3)))
      (is (every? #(and (integer? %) (pos? %)) node-ids)
          "All accumulated AST node tempids should resolve to permanent DaoDB IDs")
      ;; Total nodes: 1 + 1 + 4 = 6 nodes.
      (is (= 6 (count node-ids))
          "Should have 6 unique node IDs across 3 loads"))))

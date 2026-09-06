(ns datomworld.demo.continuation-handoff-v2-test
  "The v2 handoff payload: one key list, one evaluator, and no program medium."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2.ringbuffer :as ringbuffer]
            [datomworld.demo.continuation-handoff-v2 :as handoff]
            [datomworld.demo.continuation-transport-v2 :as transport]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]))


(defn- make-stream
  [capacity]
  (ringbuffer/create!
    {:dao.stream/type ringbuffer/transport-type,
     ringbuffer/capacity-key (or capacity vm/default-stream-capacity)}))


(defn- make-vm
  [_vm-key]
  (ast-walker/create-vm {:primitives vm/primitives, :make-stream make-stream}))


(deftest one-payload-shape-for-both-endpoints-test
  (testing "both vm keys name the same evaluator and the same key list"
    (is (= :ast-walker (handoff/vm-key->model :vm-a)))
    (is (= :ast-walker (handoff/vm-key->model :vm-b)))
    (is (= handoff/handoff-keys
           [:program :control :env :k :store :parked :id-counter :ready-queue
            :wait-set :halted? :blocked? :value]))))


(deftest handoff-carries-cesk-and-scheduler-not-composition-test
  (testing "the payload round-trips execution state and drops composition"
    (let [vm (assoc (make-vm :vm-a)
                    :program {:type :literal, :value 1}
                    :control {:type :variable, :name 'x}
                    :env {'x 1}
                    :k {:type :eval-operand, :next nil}
                    :store {'step :closure}
                    :parked {:parked-0 {:type :parked-continuation}}
                    :id-counter 99
                    :ready-queue [:resume]
                    :wait-set [:wait]
                    :halted? false
                    :blocked? true
                    :value :ok)
          payload (handoff/vm-state->handoff :vm-a vm)
          resumed (handoff/handoff->vm-state :vm-a payload make-vm)]
      (is (= {:type :variable, :name 'x} (:control resumed)))
      (is (= {'x 1} (:env resumed)))
      (is (= {:type :eval-operand, :next nil} (:k resumed)))
      (is (= {'step :closure} (:store resumed)))
      (is (= 99 (:id-counter resumed)))
      (is (= [:resume] (:ready-queue resumed)))
      (is (= [:wait] (:wait-set resumed)))
      (is (false? (:halted? resumed)))
      (is (true? (:blocked? resumed)))
      (is (= :ok (:value resumed)))
      ;; The payload carries no :in-stream — the v2 VM rejects the option —
      ;; and no function-valued composition field.
      (is (not (contains? payload :in-stream)))
      (is (not (contains? payload :in-cursor)))
      (is (not (contains? payload :make-stream)))
      (is (not (contains? payload :primitives)))
      (is (not (contains? payload :bridge)))
      ;; The receiving VM supplies its own composition.
      (is (fn? (:make-stream resumed)))
      (is (= vm/primitives (:primitives resumed))))))


(deftest handoff-resumes-mid-computation-test
  (testing "a continuation lifted out mid-run resumes on the other endpoint"
    (let [define-ast {:type :application,
                      :operator {:type :variable, :name 'yin/def},
                      :operands
                      [{:type :literal, :value 'step}
                       {:type :lambda,
                        :params ['n 'acc],
                        :body {:type :if,
                               :test {:type :application,
                                      :operator {:type :variable, :name '=},
                                      :operands [{:type :variable, :name 'n}
                                                 {:type :literal, :value 0}]},
                               :consequent {:type :variable, :name 'acc},
                               :alternate
                               {:type :application,
                                :operator {:type :variable, :name 'step},
                                :operands
                                [{:type :application,
                                  :operator {:type :variable, :name '-},
                                  :operands [{:type :variable, :name 'n}
                                             {:type :literal, :value 1}]}
                                 {:type :application,
                                  :operator {:type :variable, :name '+},
                                  :operands [{:type :variable, :name 'acc}
                                             {:type :variable, :name 'n}]}]}}}]}
          call-ast {:type :application,
                    :operator {:type :variable, :name 'step},
                    :operands [{:type :literal, :value 5}
                               {:type :literal, :value 0}]}
          ;; 'step is defined in vm-a's store; the call is loaded onto the
          ;; same VM so the store carries over.
          vm-a (vm/eval (make-vm :vm-a) define-ast)
          loaded (ast-walker/vm-load-program vm-a (vec (vm/ast->datoms
                                                         call-ast)))
          ;; Step part of the way, then lift the live state out.
          mid (nth (iterate vm/step loaded) 9)
          payload (handoff/vm-state->handoff :vm-a mid)
          vm-b (handoff/handoff->vm-state :vm-b payload make-vm)
          resumed (vm/run vm-b)]
      (is (not (vm/halted? mid)))
      ;; step(5, 0) = 5 + 4 + 3 + 2 + 1
      (is (= 15 (vm/value resumed)))
      (is (= 15 (vm/value (vm/run (vm/reset vm-b))))))))


(deftest handoff-carries-program-so-reset-re-runs-test
  (testing "the payload's :program lets the receiving VM reset and re-run"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          vm (ast-walker/vm-load-program (make-vm :vm-a)
                                         (vec (vm/ast->datoms ast)))
          payload (handoff/vm-state->handoff :vm-a vm)
          resumed (handoff/handoff->vm-state :vm-b payload make-vm)]
      (is (= {:type :application,
              :operator {:type :variable, :name '+},
              :operands [{:type :literal, :value 1}
                         {:type :literal, :value 2}]}
             (:program payload)))
      (is (= 3 (vm/value (vm/run resumed)))))))


(deftest transport-correlates-by-seq-not-position-test
  (testing "events round-trip over a v2 medium without any cursor arithmetic"
    (let [state (transport/init-state [:vm-a :vm-b])
          state' (transport/enqueue-k state
                                      {:from :vm-a, :to :vm-b, :control :if,
                                       :k-depth 3}
                                      {:the :continuation})
          [state'' message] (transport/consume-k-for state' :vm-b)]
      (is (= :vm-a (:from message)))
      (is (= :vm-b (:to message)))
      (is (= 3 (:k-depth (:summary message))))
      (is (= {:the :continuation} (:k message)))
      ;; The off-stream payload is consumed with the event.
      (is (= {} (:pending-ks state''))))
    (testing "events addressed elsewhere are skipped, not consumed"
      (let [state (transport/init-state [:vm-a :vm-b])
            state' (transport/enqueue-k state
                                        {:from :vm-a, :to :vm-a, :k-depth 1}
                                        {:for :a})
            state'' (transport/enqueue-k state'
                                         {:from :vm-a, :to :vm-b, :k-depth 2}
                                         {:for :b})
            [state''' message] (transport/consume-k-for state'' :vm-b)]
        (is (= {:for :b} (:k message)))
        ;; vm-a's event is still pending for vm-a.
        (is (= {0 {:for :a}} (:pending-ks state''')))
        (let [[_ message-a] (transport/consume-k-for state''' :vm-a)]
          (is (= {:for :a} (:k message-a))))))
    (testing "a drained medium answers nil and persists the cursor"
      (let [state (transport/init-state [:vm-a :vm-b])
            [state' message] (transport/consume-k-for state :vm-a)]
        (is (nil? message))
        (is (= (:cursors state) (:cursors state')))))))

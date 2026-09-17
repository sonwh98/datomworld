(ns yin.vm.semantic-stream-observer-test
  "Program observation composed beside the semantic VM.

   The medium carries `:yin/*` AST datoms, as the REPL's does, so the session
   hands `dao.stream.observer/run-on-stream` the §3.1 composition
   `(linearize/ast-loader semantic/vm-load-program)` beside
   `engine/ready-for-ingress?` and `vm/run`. The observer, the readiness
   predicate, and the runner are the ones the ast-walker suite uses."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(def ^:private load-ast (linearize/ast-loader semantic/vm-load-program))


(defn- run-vm
  [vm]
  (vm/run vm))


(defn- make-session
  ([] (make-session tu/default-capacity))
  ([capacity]
   (tu/make-observer-session (semantic/create-vm {:make-stream tu/make-stream})
                             capacity)))


(defn- run-session
  ([session] (run-session session load-ast))
  ([session load-program]
   (observer/run-on-stream session engine/ready-for-ingress? load-program run-vm)))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- binop
  [op a b]
  {:type :application,
   :operator {:type :variable, :name op},
   :operands [a b]})


(defn- read-first
  [capacity]
  {:type :application,
   :operator {:type :lambda,
              :params ['s],
              :body {:type :stream/next,
                     :source {:type :stream/cursor,
                              :source {:type :variable, :name 's}}}},
   :operands [{:type :stream/make, :buffer capacity}]})


;; =============================================================================
;; Coordination
;; =============================================================================

(deftest a-queued-program-runs-test
  (let [vm (:consumer (-> (make-session)
                          (tu/queue-ast! (lit 42))
                          run-session))]
    (is (vm/halted? vm))
    (is (= 42 (vm/value vm)))
    (is (nil? (vm/continuation vm)))
    (is (= 1 (count (:code vm))) "One batch lowered to one segment")))


(deftest an-idle-step-waits-for-the-observer-test
  (testing "Queued input is not loaded by the VM itself"
    (let [session (tu/queue-ast! (make-session) (lit 42))]
      (is (= (:consumer session) (vm/step (:consumer session))))
      (is (empty? (:code (:consumer session)))))))


(deftest successive-batches-run-test
  (testing "Two queued programs both run"
    (is (= 5 (vm/value (:consumer (-> (make-session)
                                      (tu/queue-ast! (lit 1))
                                      (tu/queue-ast! (binop '+ (lit 2) (lit 3)))
                                      run-session))))))
  (testing "Programs of the same shape lower to distinct segments"
    (let [vm (:consumer (-> (make-session)
                            (tu/queue-ast! (lit 1))
                            (tu/queue-ast! (lit 2))
                            run-session))]
      (is (= 2 (vm/value vm)))
      (is (= 2 (count (:code vm)))))))


(deftest definitions-persist-across-batches-test
  (testing "A closure stored by one segment is called from the next"
    (let [define {:type :application,
                  :operator {:type :variable, :name 'yin/def},
                  :operands [(lit 'inc1)
                             {:type :lambda,
                              :params ['x],
                              :body (binop '+ {:type :variable, :name 'x} (lit 1))}]}
          vm (:consumer (-> (make-session)
                            (tu/queue-ast! define)
                            (tu/queue-ast! {:type :application,
                                            :operator {:type :variable, :name 'inc1},
                                            :operands [(lit 10)]})
                            run-session))]
      (is (= 11 (vm/value vm))))))


(deftest ingress-across-a-gap-test
  (testing "An evicted batch is counted and the next one still runs"
    (let [session (make-session 2)]
      (doseq [v [1 2 3]] (tu/queue-ast! session (lit v)))
      (let [session' (run-session session)]
        (is (= 1 (:ingress-gaps (:observer session'))))
        (is (= 3 (vm/value (:consumer session'))))))))


(deftest a-blocked-program-holds-the-next-batch-test
  (let [session (-> (make-session)
                    (tu/queue-ast! (read-first 4))
                    (tu/queue-ast! (lit 9))
                    run-session)
        parked (:consumer session)]
    (testing "A parked read suspends coordination before the next batch"
      (is (vm/blocked? parked))
      (is (= :ok (:status (observer/observe-next (:observer session))))
          "The second batch is still ahead of the observer's cursor"))
    (let [entry (first (:wait-set parked))
          handle (get (vm/store parked) (:stream-id entry))]
      (stream/append! handle :woken)
      (let [done (:consumer (run-session session))]
        (testing "The next round wakes the reader, then loads what waited"
          (is (vm/halted? done))
          (is (= 9 (vm/value done)))
          (is (empty? (:wait-set done)))
          (is (= 2 (count (:code done)))))))))


(deftest a-malformed-batch-is-rejected-by-the-lowering-loader-test
  (let [session (make-session)]
    (stream/append! (:stream (:observer session)) [[1 :not/yin 1 0 true]])
    (let [data (throws-ex-data #(run-session session))]
      (is (some? data))
      (is (= (:cursor (:observer session))
             (:cursor (:observer (:session data))))
          "The carried session names the cursor before the failing batch"))))


(deftest a-code-medium-needs-no-lowering-test
  (testing "Which form travels is the composition's choice, not the VM's"
    (let [session (make-session)]
      (stream/append! (:stream (:observer session))
                      (linearize/lower-ast (binop '* (lit 6) (lit 7))))
      (is (= 42 (vm/value (:consumer (run-session session
                                                  semantic/vm-load-program))))))))

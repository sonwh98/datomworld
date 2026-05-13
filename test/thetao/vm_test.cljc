(ns thetao.vm-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [thetao.vm.algebra-vm :as alg-vm]
    [thetao.vm.bytecode-vm :as bc-vm]
    [thetao.vm.events :as events]))


(def vm-apis
  [{:label :bytecode
    :create-vm bc-vm/create-vm
    :load-program bc-vm/load-program
    :enqueue-root bc-vm/enqueue-root
    :step bc-vm/step
    :run bc-vm/run
    :resume-continuation bc-vm/resume-continuation
    :execution-stream bc-vm/execution-stream
    :event-stream bc-vm/event-stream
    :fact-stream bc-vm/fact-stream
    :effect-stream bc-vm/effect-stream
    :effect-response-stream bc-vm/effect-response-stream
    :content-store bc-vm/content-store
    :quiescent? bc-vm/quiescent?}
   {:label :algebra
    :create-vm alg-vm/create-vm
    :load-program alg-vm/load-program
    :enqueue-root alg-vm/enqueue-root
    :step alg-vm/step
    :run alg-vm/run
    :resume-continuation alg-vm/resume-continuation
    :execution-stream alg-vm/execution-stream
    :event-stream alg-vm/event-stream
    :fact-stream alg-vm/fact-stream
    :effect-stream alg-vm/effect-stream
    :effect-response-stream alg-vm/effect-response-stream
    :content-store alg-vm/content-store
    :quiescent? alg-vm/quiescent?}])


(defn- stream-values
  [stream]
  (vec (ds/->seq nil stream)))


(defn- event-projection
  [api vm]
  (->> (stream-values ((:event-stream api) vm))
       (filter #(= :thetao/event (nth % 1 nil)))
       (mapv events/project-d3)))


(defn- run-program
  [api ast]
  (-> ((:create-vm api))
      ((:load-program api) ast)
      ((:enqueue-root api))
      ((:run api))))


(deftest pure-evaluation-parity-test
  (let [ast {:type :application
             :operator {:type :lambda
                        :params ['f 'x]
                        :body {:type :if
                               :test {:type :application
                                      :operator {:type :variable, :name '<}
                                      :operands [{:type :variable, :name 'x}
                                                 {:type :literal, :value 10}]}
                               :consequent {:type :application
                                            :operator {:type :variable, :name 'f}
                                            :operands [{:type :variable, :name 'x}]}
                               :alternate {:type :literal, :value :too-large}}}
             :operands [{:type :lambda
                         :params ['n]
                         :body {:type :application
                                :operator {:type :variable, :name '+}
                                :operands [{:type :variable, :name 'n}
                                           {:type :literal, :value 35}]}}
                        {:type :literal, :value 7}]}]
    (doseq [{:keys [label] :as api} vm-apis]
      (testing (name label)
        (let [vm (run-program api ast)]
          (is (= 42 (:result vm)))
          (is ((:quiescent? api) vm))
          (is (= :halted (:status vm))))))))


(deftest current-continuation-materializes-hash-test
  (doseq [{:keys [label content-store] :as api} vm-apis]
    (testing (name label)
      (let [vm (run-program api {:type :vm/current-continuation})
            captured (:result vm)
            event-datoms (stream-values ((:event-stream api) vm))
            captured-hash-datom (first (filter (fn [[e a _v _t _m]]
                                                 (and (= e (:local-id captured))
                                                      (= a :thetao/hash)))
                                               event-datoms))]
        (is (= :thetao/continuation (:type captured)))
        (is (string? (:hash captured)))
        (is (= (:hash captured) (nth captured-hash-datom 2)))
        (is (contains? @(content-store vm) (:hash captured)))))))


(deftest stream-next-blocks-and-resumes-explicitly-test
  (let [ast {:type :application
             :operator {:type :lambda
                        :params ['s]
                        :body {:type :application
                               :operator {:type :lambda
                                          :params ['c]
                                          :body {:type :stream/next
                                                 :source {:type :variable
                                                          :name 'c}}}
                               :operands [{:type :stream/cursor
                                           :source {:type :variable
                                                    :name 's}}]}}
             :operands [{:type :stream/make, :buffer 4}]}
        expected-prefix [[:thetao/vm :thetao/event :continuation/enqueued]
                         [:thetao/vm :thetao/event :continuation/started]
                         [:thetao/vm :thetao/event :continuation/blocked]]]
    (doseq [{:keys [label run] :as api} vm-apis]
      (testing (name label)
        (let [vm0 (-> ((:create-vm api))
                      ((:load-program api) ast)
                      ((:enqueue-root api)))
              vm1 (run vm0)
              blocked-entry (-> vm1 :blocked vals first)
              stream-id (get-in blocked-entry [:blocked-on :stream-id])
              cursor-id (get-in blocked-entry [:blocked-on :cursor-id])
              cursor (get-in vm1 [:cursors-by-id cursor-id])
              stream (get-in vm1 [:streams-by-id stream-id])
              _ (is ((:quiescent? api) vm1))
              _ (ds/put! stream 42)
              vm2 (run vm1)]
          (is (= :blocked (:status vm1)))
          (is (= :stream/next (get-in blocked-entry [:blocked-on :kind])))
          (is (= 0 (:position cursor)))
          (is (= 42 (:result vm2)))
          (is (= expected-prefix
                 (subvec (event-projection api vm2) 0 3)))
          (is (empty? (:blocked vm2))))))))


(deftest park-and-runtime-resume-test
  (let [ast {:type :application
             :operator {:type :variable, :name '+}
             :operands [{:type :literal, :value 1}
                        {:type :vm/park}]}]
    (doseq [{:keys [label resume-continuation run] :as api} vm-apis]
      (testing (name label)
        (let [vm0 (-> ((:create-vm api))
                      ((:load-program api) ast)
                      ((:enqueue-root api)))
              vm1 (run vm0)
              parked (-> vm1 :blocked vals first :continuation)
              vm2 (-> vm1
                      (resume-continuation parked 41)
                      (run))]
          (is (= :blocked (:status vm1)))
          (is (= :park (get-in vm1 [:blocked (:local-id parked) :blocked-on :kind])))
          (is (= 42 (:result vm2)))
          (is (empty? (:blocked vm2))))))))


(deftest dao-stream-apply-call-roundtrip-test
  (let [ast {:type :dao.stream.apply/call
             :op :op/echo
             :operands [{:type :literal, :value 42}]}
        expected-trace [[:thetao/vm :thetao/event :continuation/enqueued]
                        [:thetao/vm :thetao/event :continuation/started]
                        [:thetao/vm :thetao/event :effect/requested]
                        [:thetao/vm :thetao/event :continuation/blocked]
                        [:thetao/vm :thetao/event :continuation/resumed]
                        [:thetao/vm :thetao/event :continuation/started]
                        [:thetao/vm :thetao/event :continuation/halted]]]
    (doseq [{:keys [label effect-stream effect-response-stream run] :as api}
            vm-apis]
      (testing (name label)
        (let [vm0 (-> ((:create-vm api))
                      ((:load-program api) ast)
                      ((:enqueue-root api)))
              vm1 (run vm0)
              effect-request (first (stream-values (effect-stream vm1)))
              request-id (nth effect-request 0)
              _ (ds/put! (effect-response-stream vm1)
                         [request-id :thetao/effect-response 42 0 0])
              vm2 (run vm1)]
          (is (= :blocked (:status vm1)))
          (is (= :dao.stream.apply/call
                 (get-in vm1 [:blocked (first (keys (:blocked vm1))) :blocked-on :kind])))
          (is (= 42 (:result vm2)))
          (is (= expected-trace (event-projection api vm2))))))))


(deftest cross-vm-event-projection-parity-test
  (let [ast {:type :application
             :operator {:type :lambda
                        :params ['x]
                        :body {:type :application
                               :operator {:type :variable, :name '+}
                               :operands [{:type :variable, :name 'x}
                                          {:type :literal, :value 2}]}}
             :operands [{:type :literal, :value 40}]}
        results (mapv (fn [{:keys [label] :as api}]
                        (let [vm (run-program api ast)]
                          {:label label
                           :vm vm
                           :events (event-projection api vm)}))
                      vm-apis)]
    (is (= 42 (get-in results [0 :vm :result])))
    (is (= 42 (get-in results [1 :vm :result])))
    (is (= (get-in results [0 :events])
           (get-in results [1 :events])))))

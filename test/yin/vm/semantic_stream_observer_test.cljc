(ns yin.vm.semantic-stream-observer-test
  "Program observation composed beside the semantic VM, over the row lane
   (§7.1).

   The row medium carries each program as the canonical row set of one tree
   — `queue-rows!`'s shape — and the evaluator observer hands
   `dao.stream.observer/run-on-stream` the row-fed loader
   `(linearize/rows-loader semantic/load-vector)` beside
   `engine/ready-for-ingress?` and `vm/run`. The encoder stage that
   forwards to that medium is exercised end to end beside it
   (`tu/make-encoder-session`, the two-stage topology the REPL runs):
   projection of map-AST and datom batches, independent cursors and
   attachments, and gap and blocked-program behavior across both stages.
   The observer, the readiness predicate, and the runner are the ones the
   ast-walker suite uses."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.malformed-rows :as malformed]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(deftest source-envelope-projects-occurrence-side-tables
  (let [ast (with-meta
              {:type :application
               :operator (with-meta {:type :variable
                                     :name (with-meta 'f {:role :callee})
                                     :yang/scope :lexical}
                           {:file "program.clj" :line 4 :column 2})
               :operands [{:type :literal :value 1}]
               :tail? false}
              {:file "program.clj" :line 4 :column 1 :form :call})
        batch #(encoder/project {:yin/source-medium :program
                                 :yin/batch-token %
                                 :yin/batch [ast]
                                 :yin/root 0})
        projected-a (batch "a")
        projected-b (batch "b")
        tree (first (:yin/batch projected-a))
        root (:root tree)
        source-a (set (:yin/source-positions projected-a))
        metadata-a (set (:yin/frontend-metadata projected-a))]
    (is (= (:yin/batch projected-a) (:yin/batch projected-b))
        "occurrence facts do not enter content identity")
    (is (contains? source-a
                   [[:source :program "a" 0] root [] "program.clj" 4 1]))
    (is (contains? source-a
                   [[:source :program "a" 0] root [2] "program.clj" 4 2]))
    (is (contains? metadata-a
                   [[:source :program "a" 0] root [] :form :call]))
    (is (contains? metadata-a
                   [[:source :program "a" 0] root [2] :yang/scope :lexical]))
    (is (contains? metadata-a
                   [[:source :program "a" 0] root [2]
                    [:name :role] :callee]))
    (is (not= (:yin/source-positions projected-a)
              (:yin/source-positions projected-b)))))


(deftest datom-adapter-records-every-path-of-a-shared-entity
  (let [datoms [[-1 :yin/type :application 0 1]
                [-1 :yin/operator -2 0 1]
                [-1 :yin/operands [-3 -3] 0 1]
                [-1 :yin/root true 0 1]
                [-2 :yin/type :variable 0 1]
                [-2 :yin/name 'f 0 1]
                [-3 :yin/type :literal 0 1]
                [-3 :yin/value 1 0 1]]
        projected (encoder/project {:yin/source-medium :program
                                    :yin/batch-token "shared"
                                    :yin/batch [datoms]
                                    :yin/root 0})]
    (is (= #{[0 [] -1]
             [0 [2] -2]
             [0 [[3 0]] -3]
             [0 [[3 1]] -3]}
           (set (:yin/entity-occurrences projected))))))


(def ^:private load-rows (linearize/rows-loader semantic/load-vector))


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
  ([session] (run-session session load-rows))
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

(deftest a-queued-row-set-runs-test
  (let [vm (:consumer (-> (make-session)
                          (tu/queue-rows! (lit 42))
                          run-session))]
    (is (vm/halted? vm))
    (is (= 42 (vm/value vm)))
    (is (nil? (vm/continuation vm)))
    (is (= 1 (count (:code vm))) "One batch lowered to one segment")
    (is (every? :address (vals (:code vm)))
        "The row lane loads through load-vector, so every image is aliased
          by its vector's address")))


(deftest an-idle-step-waits-for-the-observer-test
  (testing "Queued input is not loaded by the VM itself"
    (let [session (tu/queue-rows! (make-session) (lit 42))]
      (is (= (:consumer session) (vm/step (:consumer session))))
      (is (empty? (:code (:consumer session)))))))


(deftest successive-batches-run-test
  (testing "Two queued row sets both run"
    (is (= 5 (vm/value (:consumer (-> (make-session)
                                      (tu/queue-rows! (lit 1))
                                      (tu/queue-rows! (binop '+ (lit 2) (lit 3)))
                                      run-session))))))
  (testing "Programs of the same shape lower to distinct segments"
    (let [vm (:consumer (-> (make-session)
                            (tu/queue-rows! (lit 1))
                            (tu/queue-rows! (lit 2))
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
                            (tu/queue-rows! define)
                            (tu/queue-rows! {:type :application,
                                             :operator {:type :variable, :name 'inc1},
                                             :operands [(lit 10)]})
                            run-session))]
      (is (= 11 (vm/value vm))))))


(deftest ingress-across-a-gap-test
  (testing "An evicted batch is counted and the next one still runs"
    (let [session (make-session 2)]
      (doseq [v [1 2 3]] (tu/queue-rows! session (lit v)))
      (let [session' (run-session session)]
        (is (= 1 (:ingress-gaps (:observer session'))))
        (is (= 3 (vm/value (:consumer session'))))))))


(deftest a-blocked-program-holds-the-next-batch-test
  (let [session (-> (make-session)
                    (tu/queue-rows! (read-first 4))
                    (tu/queue-rows! (lit 9))
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


;; =============================================================================
;; The encoder stage and the row medium
;; =============================================================================

(deftest the-two-observer-stages-compose-end-to-end-test
  (let [new-vm #(semantic/create-vm {:make-stream tu/make-stream})]
    (testing "The encoder projects and forwards; the VM's own observer runs"
      (let [session (tu/make-encoder-session (new-vm))]
        (stream/append! (:program session) (binop '* (lit 6) (lit 7)))
        (let [run (tu/run-encoder-session session)
              vm (:consumer (:evaluator run))]
          (is (= 42 (vm/value vm)))
          (is (every? :address (vals (:code vm)))
              "the rows reached load-vector, not the datom lane")
          (testing "Two attachments, two cursors, one per medium"
            (let [enc (:observer (:encoder run))
                  evl (:observer (:evaluator run))]
              (is (not= (:stream enc) (:stream evl))
                  "each stage is attached to its own medium")
              (is (not= (:cursor enc) (:cursor evl))
                  "each stage's cursor is its own medium's")
              (is (= :blocked (:status (observer/observe-next enc)))
                  "the program medium is drained behind the encoder")
              (is (= :blocked (:status (observer/observe-next evl)))
                  "the row medium is drained behind the evaluator"))))))
    (testing "A gap on the program medium is the encoder's to count"
      (let [session (tu/make-encoder-session (new-vm) 2)]
        (doseq [v [1 2 3]] (stream/append! (:program session) (lit v)))
        (let [run (tu/run-encoder-session session)]
          (is (= 1 (:ingress-gaps (:observer (:encoder run)))))
          (is (zero? (:ingress-gaps (:observer (:evaluator run))))
              "the row medium lost nothing; the loss never crossed it")
          (is (= 3 (vm/value (:consumer (:evaluator run))))))))
    (testing "A datom batch rides the same stages through the projection adapter"
      (let [session (tu/make-encoder-session (new-vm))]
        (stream/append! (:program session)
                        (vec (vm/ast->datoms (binop '+ (lit 2) (lit 3)))))
        (is (= 5 (vm/value (:consumer (:evaluator (tu/run-encoder-session session))))))))
    (testing "A source envelope carries its occurrence identity through lowering"
      (let [session (tu/make-encoder-session (new-vm))
            batch {:yin/source-medium :program-medium
                   :yin/batch-token "admission-1"
                   :yin/batch [(binop '+ (lit 2) (lit 3))]
                   :yin/root 0}]
        (stream/append! (:program session) batch)
        (let [run (tu/run-encoder-session session)
              vm (:consumer (:evaluator run))]
          (is (= 5 (vm/value vm))))))
    (testing "A blocked program holds the next batch behind the row medium"
      (let [session (tu/make-encoder-session (new-vm))]
        (stream/append! (:program session) (read-first 4))
        (stream/append! (:program session) (lit 9))
        (let [run (tu/run-encoder-session session)
              parked (:consumer (:evaluator run))]
          (is (vm/blocked? parked))
          (is (= :blocked (:status (observer/observe-next
                                     (:observer (:encoder run)))))
              "the encoder drained the program medium")
          (is (= :ok (:status (observer/observe-next
                                (:observer (:evaluator run)))))
              "the second batch waits on the row medium, ahead of the
                evaluator's cursor")
          (let [entry (first (:wait-set parked))
                handle (get (vm/store parked) (:stream-id entry))]
            (stream/append! handle :woken)
            (let [done (:consumer (:evaluator (tu/run-encoder-session run)))]
              (is (vm/halted? done))
              (is (= 9 (vm/value done)))
              (is (= 2 (count (:code done)))))))))))


(deftest the-datom-lane-stays-a-legal-composition-test
  (testing "A datom medium with the datom-lane loader still runs"
    (is (= 5 (vm/value (:consumer (-> (make-session)
                                      (tu/queue-ast! (binop '+ (lit 2) (lit 3)))
                                      (run-session load-ast))))))))


(deftest a-malformed-row-set-is-rejected-by-the-row-fed-loader-test
  (doseq [[name [_expected bc]] malformed/malformed-row-sets]
    (testing (str name)
      (let [session (make-session)]
        (stream/append! (:stream (:observer session)) bc)
        (let [data (throws-ex-data #(run-session session))]
          (is (some? data))
          (is (= (:cursor (:observer session))
                 (:cursor (:observer (:session data))))
              "The carried session names the cursor before the failing batch"))))))


(deftest a-code-medium-needs-no-lowering-test
  (testing "Which form travels is the composition's choice, not the VM's"
    (let [session (make-session)]
      (stream/append! (:stream (:observer session))
                      (linearize/lower-ast (binop '* (lit 6) (lit 7))))
      (is (= 42 (vm/value (:consumer (run-session session
                                                  semantic/vm-load-program))))))))

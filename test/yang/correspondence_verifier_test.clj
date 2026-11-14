(ns yang.correspondence-verifier-test
  (:require [clojure.test :refer [deftest is]]
            [yang.correspondence-verifier :as verifier]
            [yin.vm :as vm]
            [dao.jing.mem]))


(def literal-args {:type :literal :value [1 2]})


(def stamped-call
  {:type :application :operator {:type :variable :name 'dsg__intra_call}
   :operands [{:type :variable :name 'f}
              {:type :application :operator {:type :variable :name 'dsg__intra_call}
               :operands [{:type :variable :name 'gate-site!}
                          {:type :application :operator {:type :variable :name 'conj}
                           :operands [{:type :literal :value []}
                                      {:type :literal :value 1} literal-args]}]}]})


(deftest independent-site-declarations-reject-mutation
  (let [expected {1 {:site 1 :callee 'f :arg-shapes [:literal :literal]
                     :def nil :delegation? false}}
        derived (verifier/site-declarations stamped-call #{})]
    (is (= expected derived))
    (is (true? (verifier/check-declarations! stamped-call #{} expected)))
    (is (thrown? Exception
          (verifier/check-declarations!
            stamped-call #{} (assoc-in expected [1 :arg-shapes] [:variable :literal]))))))


(deftest independently-canonicalizes-source-symbols
  (let [a '[(fn [x__123#] x__123#)]
        b '[(fn [x__999#] x__999#)]]
    (is (= (verifier/source-hash a '(x)) (verifier/source-hash b '(x))))
    (is (not= (verifier/source-hash '[(def x 1)] 'x)
              (verifier/source-hash '[(def x 2)] 'x)))))


(deftest independent-stamped-binding-translation
  (let [pre {:type :application :operator {:type :variable :name 'dsg__intra_call}
             :operands [{:type :variable :name 'f} literal-args]}
        origin [:source :program ["real-admission" 0] 0]
        root (:root (vm/ast->semantic-bytecode pre))
        actual (verifier/reconstruct-bindings pre stamped-call origin)]
    (is (= {1 [origin root []]} actual))
    (is (thrown? Exception
          (verifier/reconstruct-bindings pre stamped-call [:ephemeral :x :y 0])))
    (is (thrown? Exception
          (verifier/reconstruct-bindings
            (assoc-in pre [:operands 0 :name] 'other) stamped-call origin)))))


(deftest scoped-attribution-requires-both-complete-lanes
  (let [table {1 {:site 1 :callee 'carrier :arg-shapes [:ambient-literal] :delegation? false}}
        binding {1 [[:source :program ["run" 0] 0] :segment/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa []]}
        lane {:status :completed :complete? true :executed-calls [['carrier 2 1]]}
        data {:site-table table :bindings binding
              :lanes {:c-walker lane :c-semantic lane}
              :origin [:source :program ["run" 0] 0]
              :root (second (get binding 1))
              :guest-symbol 'guest :requested-arity 1
              :carriers [['carrier 2 :ambient-delegation]]}]
    (is (true? (verifier/attributed? data)))
    (is (false? (verifier/attributed? (assoc-in data [:lanes :c-semantic :complete?] false))))
    (is (false? (verifier/attributed? (assoc-in data [:lanes :c-semantic :executed-calls] []))))
    (is (false? (verifier/attributed? (assoc-in data [:site-table 1 :arg-shapes] [:variable]))))
    (is (false? (verifier/attributed? (assoc data :bindings {}))))
    (is (false? (verifier/attributed? (assoc data :origin [:source :program ["other" 0] 0]))))
    (is (false? (verifier/attributed? (assoc data :root :segment/sha256-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb))))
    (is (false? (verifier/attributed? (assoc-in data [:site-table 1 :delegation?] true))))))


(deftest per-fixture-static-gate-refuses-table-and-binding-mutations
  (let [pre {:type :application :operator {:type :variable :name 'dsg__intra_call}
             :operands [{:type :variable :name 'f} literal-args]}
        origin [:source :program ["admission-run" 0] 0]
        declarations {1 {:site 1 :callee 'f :arg-shapes [:literal :literal]
                         :def nil :delegation? false}}
        input {:fixture :pilot :before pre :stamped stamped-call :origin origin
               :constructors #{} :forms '[(def x 1)] :call 'x
               :source-hash (verifier/source-hash '[(def x 1)] 'x)
               :site-table declarations
               :metadata [[origin (:root (vm/ast->semantic-bytecode pre)) [] :yang/call-site 1]]
               :bindings (verifier/reconstruct-bindings pre stamped-call origin)}]
    (is (= {:fixture :pilot :status :static-pass} (verifier/check-fixture! input)))
    (doseq [[bad stage] [[(assoc-in input [:site-table 1 :arg-shapes] [:forged]) :site-declarations]
                         [(assoc input :bindings {}) :bindings]
                         [(assoc-in input [:stamped :operands 1 :operands 1 :operands 1 :value] -1) :site-declarations]
                         [(assoc input :source-hash "wrong") :source-hash]
                         [(update input :metadata conj [:malformed]) :metadata]
                         [(update input :metadata conj (first (:metadata input))) :metadata]]]
      (let [failure (try (verifier/check-fixture! bad) nil
                         (catch Exception e (ex-data e)))]
        (is (= :pilot (:fixture failure)))
        (is (= stage (:stage failure)))))))


(deftest verified-row-closure-retrieval
  (let [tree (vm/ast->semantic-bytecode stamped-call)
        handle (dao.jing.mem/create-content-mem)
        root (vm/materialize-tree! handle tree)]
    (is (= tree (verifier/fetch-tree! handle root 100)))
    (is (thrown? Exception (verifier/fetch-tree! handle root 1)))
    (is (thrown? Exception (verifier/fetch-tree! (dao.jing.mem/create-content-mem) root 100)))))


(deftest replay-root-must-match-retained-root
  (let [root (:root (vm/ast->semantic-bytecode stamped-call))]
    (is (true? (verifier/check-replay-root! :pilot stamped-call root)))
    (let [failure (try (verifier/check-replay-root! :pilot stamped-call
                                                    :segment/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)
                       nil (catch Exception e (ex-data e)))]
      (is (= :pilot (:fixture failure)))
      (is (= :root (:stage failure))))))

(ns yang.correspondence-verification-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.test-utils :as tu]
            [yang.clojure :as yang]
            [yang.correspondence-lane :as lane]
            [yang.correspondence-measure :as measure]
            [yang.correspondence-verification :as verification]
            [yang.correspondence-verifier :as verifier]
            [yang.correspondence-verifier-test :as fixtures]))


(defn- input
  []
  (let [pre {:type :application :operator {:type :variable :name 'dsg__intra_call}
             :operands [{:type :variable :name 'f} fixtures/literal-args]}
        origin [:source :program ["recorded" 0] 0]
        root (:root (vm/ast->semantic-bytecode pre))]
    {:fixture :pilot :before pre :stamped fixtures/stamped-call :origin origin
     :constructors #{} :forms '[(def x 1)] :call 'x
     :source-hash (verifier/source-hash '[(def x 1)] 'x)
     :site-table {1 {:site 1 :callee 'f :arg-shapes [:literal :literal]
                     :def nil :delegation? false}}
     :metadata [[origin root [] :yang/call-site 1]]
     :bindings (verifier/reconstruct-bindings pre fixtures/stamped-call origin)}))


(def sample-claims [{:guest-symbol 'f :requested-arity 2 :carriers []}])


(def limits
  {:steps 10 :elapsed-ms 100 :heap-bytes 100 :trace-records 4
   :trace-record-bytes 100 :trace-bytes 200 :trace-heap-bytes 1000})


(def default-options
  {:fixture-limit-ms 100 :trace-size-fn (constantly 1)
   :tree-limits {:rows 1000 :occurrences 1000 :applications 1000
                 :depth 1000 :application-path-bytes 100000}})


(defn- gate
  [input claims lane-limits modules clock heap & [options]]
  (verification/verify-fixture! input claims lane-limits modules clock heap
                                (merge default-options options)))


(deftest fixture-gate-runs-both-scoped-lanes-and-checks-mutation
  (let [calls (atom [])
        runner (fn [_ _ backend _ {:keys [check!]}]
                 (let [h (tu/new-stream 4)
                       m {:store {'gate-execution-trace {:id :trace}}
                          :resources {:trace h}}]
                   (swap! calls conj backend)
                   (check! 0 m)
                   (stream/append! h ['f 2 1])
                   (check! 1 m)
                   {:machine m :steps 1}))
        claims [{:guest-symbol 'f :requested-arity 2 :carriers []}]]
    (with-redefs [lane/run-measured! runner vm/halted? (constantly true)]
      (let [result (gate
                     (input) claims {:walker limits :semantic limits} {} (constantly 0) (constantly 1))]
        (is (= [:walker :semantic] @calls))
        (is (= :pass (:status result)))
        (is (= :site-declarations (:mutation-refusal result)))
        (is (= [true] (:attributed result)))
        (is (true? (:not-a-d1-verification-result result)))
        (is (= 0 (:elapsed-ms result)))
        (is (= 0 (get-in result [:lanes :c-walker :run-ms])))
        (is (= {:tree-check-ms 0 :static-check-ms 0 :mutation-check-ms 0 :query-ms 0}
               (:gate-phases result)))))))


(deftest fixture-gate-preserves-exceedance
  (let [runner (fn [_ _ _ _ {:keys [check!]}] (check! 11 {}) {:machine {} :steps 11})]
    (with-redefs [lane/run-measured! runner]
      (let [result (gate
                     (input) sample-claims {:walker limits :semantic limits} {} (constantly 0) (constantly 1))]
        (is (= :failed-bound (:status result)))
        (is (= :steps (:quantity result)))
        (is (= :walker (:lane result)))
        (is (= 11 (:observed result)))
        (is (= 10 (:ceiling result)))))))


(deftest fixture-gate-refuses-mutated-input-before-any-lane
  (let [calls (atom 0)
        changed (assoc-in (input) [:site-table 1 :arg-shapes] [:forged])]
    (with-redefs [lane/run-measured! (fn [& _] (swap! calls inc))]
      (is (= :site-declarations
             (try (gate changed sample-claims {:walker limits :semantic limits}
                        {} (constantly 0) (constantly 1))
                  nil (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
      (is (zero? @calls)))))


(deftest fixture-gate-rejects-missing-completed-lane
  (let [runner (fn [_ _ _ _ _] {:machine {} :steps 0})]
    (with-redefs [lane/run-measured! runner vm/halted? (constantly false)]
      (is (= :lane-completion
             (try (gate (input) sample-claims {:walker limits :semantic limits}
                        {} (constantly 0) (constantly 1))
                  nil (catch clojure.lang.ExceptionInfo e (:stage (ex-data e)))))))))


(deftest fixture-gate-enforces-post-halt-trace-heap-limit
  (let [runner (fn [_ _ _ _ {:keys [check!]}]
                 (let [h (tu/new-stream 4)
                       m {:store {'gate-execution-trace {:id :trace}} :resources {:trace h}}]
                   (check! 0 m)
                   {:machine m :steps 0}))
        bounded (assoc limits :trace-heap-bytes 10)]
    (with-redefs [lane/run-measured! runner vm/halted? (constantly true)]
      (let [result (gate
                     (input) sample-claims {:walker bounded :semantic bounded} {} (constantly 0) (constantly 1)
                     {:trace-size-fn (constantly 11)})]
        (is (= :failed-bound (:status result)))
        (is (= :trace-heap-bytes (:quantity result)))
        (is (= 11 (:observed result)))
        (is (= 10 (:ceiling result)))))))


(deftest fixture-gate-refuses-missing-trace-heap-sizer
  (let [bounded (assoc limits :trace-heap-bytes 10)]
    (is (= :trace-memory
           (try (gate (input) sample-claims {:walker bounded :semantic bounded}
                      {} (constantly 0) (constantly 1)
                      {:trace-size-fn nil})
                nil (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))))


(deftest fixture-gate-preserves-total-wall-time-exceedance
  (let [clock-nanos (atom 0)
        clock #(swap! clock-nanos + 1000000)
        result (gate
                 (input) sample-claims {:walker limits :semantic limits} {} clock (constantly 1)
                 {:fixture-limit-ms 0})]
    (is (= :failed-bound (:status result)))
    (is (= :fixture-ms (:quantity result)))
    (is (pos? (:observed result)))
    (is (= 0 (:ceiling result)))))


(deftest fixture-gate-refuses-empty-query-set
  (let [calls (atom 0)]
    (with-redefs [lane/run-measured! (fn [& _] (swap! calls inc))]
      (is (= :claims
             (try (gate (input) [] {:walker limits :semantic limits}
                        {} (constantly 0) (constantly 1))
                  nil (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
      (is (zero? @calls)))))


(deftest fixture-gate-checks-tree-bounds-before-running-lanes
  (let [calls (atom 0)
        trees {:rows 100 :occurrences 100 :applications 0 :depth 100 :application-path-bytes 10000}]
    (with-redefs [lane/run-measured! (fn [& _] (swap! calls inc))]
      (let [result (gate (input) sample-claims
                         {:walker limits :semantic limits} {}
                         (constantly 0) (constantly 1)
                         {:tree-limits trees})]
        (is (= :failed-bound (:status result)))
        (is (= :applications (:quantity result)))
        (is (zero? @calls))))))


(deftest fixture-gate-rejects-incomplete-bound-declaration-before-lanes
  (let [calls (atom 0)]
    (with-redefs [lane/run-measured! (fn [& _] (swap! calls inc))]
      (is (= :bound-declaration
             (try (verification/verify-fixture! (input) sample-claims
                                                {:walker limits :semantic limits} {}
                                                (constantly 0) (constantly 1))
                  nil (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
      (is (zero? @calls)))))


(deftest retained-fixture-gate-replays-root-and-runs-both-lanes
  (let [original (input)
        store ((requiring-resolve 'dao.jing.mem/create-content-mem))
        put! (requiring-resolve 'dao.jing/materialize!)
        root (vm/materialize-tree! store (vm/ast->semantic-bytecode (:before original)))
        stamped-root (vm/materialize-tree! store (vm/ast->semantic-bytecode (:stamped original)))
        origin (:origin original)
        captured {:fixture {:id :pilot} :admission-origin origin
                  :forms-edn (pr-str (:forms original)) :call-edn (pr-str (:call original))
                  :program-edn "[:synthetic]" :options-edn "{}"}
        snapshot (assoc (select-keys original [:fixture :origin :constructors :metadata :bindings])
                        :root root :stamped-root stamped-root)
        evidence {:version 1 :fixtures {:pilot {:canonical-source-hash (:source-hash original)
                                                :site-table (:site-table original)}}}
        profile @(requiring-resolve 'yang.correspondence-text/profile)
        addresses {:input (put! store captured) :snapshot (put! store snapshot)
                   :evidence (put! store evidence) :profile (put! store profile)
                   :root root :stamped-root stamped-root}
        outcome {:status :pass :admission-origin origin :addresses addresses}
        runner (fn [_ _ _ _ {:keys [check!]}]
                 (let [h (tu/new-stream 4)
                       m {:store {'gate-execution-trace {:id :trace}} :resources {:trace h}}]
                   (check! 0 m) (stream/append! h ['f 2 1]) (check! 1 m)
                   {:machine m :steps 1}))]
    (with-redefs [lane/run-measured! runner vm/halted? (constantly true)
                  yang/compile-program (fn [& _] (:before original))]
      (let [result (verification/verify-retained-fixture!
                     store :pilot outcome sample-claims {:walker limits :semantic limits} {}
                     (constantly 0) (constantly 1) default-options)]
        (is (= :pass (:status result)))
        (is (= [true] (:attributed result)))
        (is (= origin (:admission-origin result)))
        (is (= 0 (:fixture-elapsed-ms result)))
        (is (= {:fetch-rows-ms 0 :fetch-content-ms 0 :replay-ms 0 :gate-ms 0}
               (:phases result)))))))


(deftest retained-fixture-gate-preserves-row-traversal-bound
  (with-redefs [measure/check-retained-trees!
                (fn [& _]
                  (throw (ex-info "row bound" {:stage :bound-exceeded
                                               :quantity :rows :observed 4 :ceiling 3
                                               :tree :stamped
                                               :observed-kind :distinct-required-addresses})))]
    (let [result (verification/verify-retained-fixture!
                   nil :f {} sample-claims {:walker limits :semantic limits} {}
                   (constantly 0) (constantly 1) default-options)]
      (is (= :failed-bound (:status result)))
      (is (= :rows (:quantity result)))
      (is (= 4 (:observed result)))
      (is (= 3 (:ceiling result)))
      (is (= :distinct-required-addresses (:observed-kind result))))))

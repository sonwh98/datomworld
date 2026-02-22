(ns yin.vm.state-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.semantic :as semantic]
            [yin.vm.state-projection :as proj]))


;; =============================================================================
;; State Projection Tests
;; =============================================================================

(deftest halted-vm-projection-test
  (testing "Halted VM projection shows result value"
    (let [vm (-> (semantic/create-vm {:env vm/primitives})
                 (vm/eval {:type :literal, :value 42}))
          datoms (proj/state->datom-stream vm)
          ctrl-datoms (filter (fn [[_e a _v _t _m]] (= a :cont/type)) datoms)
          result-datoms (filter (fn [[_e a _v _t _m]] (= a :cont/result))
                          datoms)]
      (is (seq datoms) "Projection produces datoms")
      (is (some #(= :control (nth % 2)) ctrl-datoms) "Has control state datom")
      (is (some #(= 42 (nth % 2)) result-datoms) "Shows halted value 42"))))


(deftest mid-execution-projection-test
  (testing "Mid-execution SemanticVM shows continuation frames"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          datoms (vm/ast->datoms ast)
          root-id (ffirst datoms)
          vm (-> (semantic/create-vm {:env vm/primitives})
                 (vm/load-program {:node root-id, :datoms datoms}))
          ;; Step once: evaluates operator node, pushes :app-op frame
          vm-stepped (vm/step vm)
          proj-datoms (proj/state->datom-stream vm-stepped)
          frame-types (keep (fn [[_e a v _t _m]] (when (= a :cont/type) v))
                            proj-datoms)]
      (is (seq proj-datoms) "Projection produces datoms")
      (is (some #{:app-op} frame-types)
          "Shows app-op continuation frame from stepping into application"))))


(deftest projection-entity-ids-test
  (testing "Projected datoms use entity IDs in -2048 range"
    (let [vm (-> (semantic/create-vm {:env vm/primitives})
                 (vm/eval {:type :literal, :value 42}))
          datoms (proj/state->datom-stream vm)
          eids (map first datoms)]
      (is (every? neg-int? eids) "All entity IDs are negative")
      (is (every? #(<= % -2048) eids) "All entity IDs are <= -2048"))))


(deftest projection-with-env-test
  (testing "Environment bindings appear in projection"
    (let [;; Use a lambda that captures a variable
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :variable, :name 'x}},
               :operands [{:type :literal, :value 99}]}
          vm (-> (semantic/create-vm {:env vm/primitives})
                 (vm/eval ast))
          datoms (proj/state->datom-stream vm)]
      (is (seq datoms) "Produces datoms for completed lambda application"))))


(deftest parked-continuation-projection-test
  (testing "Parked continuations appear in projection"
    (let [vm (-> (semantic/create-vm {:env vm/primitives})
                 (vm/eval {:type :vm/park}))
          datoms (proj/state->datom-stream vm)
          parked-types (keep (fn [[_e a v _t _m]]
                               (when (and (= a :cont/type) (= v :parked)) v))
                             datoms)]
      (is (seq parked-types) "Parked continuation appears in projection"))))

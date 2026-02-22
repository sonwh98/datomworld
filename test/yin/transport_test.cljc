(ns yin.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.content :as content]
            [yin.transport :as transport]
            [yin.vm :as vm]
            [yin.vm.semantic :as semantic]))


;; =============================================================================
;; AST Transport Tests
;; =============================================================================

(defn- root-hash-of
  [datoms]
  (let [hashes (content/compute-content-hashes datoms)]
    (get hashes (apply max (keys hashes)))))


(deftest export-import-roundtrip-test
  (testing "Export/import roundtrip produces equivalent AST datoms"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          original-datoms (vm/ast->datoms ast)
          exported (transport/export-ast original-datoms)
          {:keys [datoms root-eid]} (transport/import-ast exported -3000 {})
          ;; Verify the imported datoms produce the same content hash
          imported-hashes (content/compute-content-hashes datoms)
          imported-root-hash (get imported-hashes root-eid)]
      (is (some? root-eid) "Root entity ID should be assigned")
      (is (= (:root-hash exported) imported-root-hash)
          "Imported datoms produce same root content hash"))))


(deftest import-evaluate-test
  (testing "Import into SemanticVM, evaluate, get same result"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          original-datoms (vm/ast->datoms ast)
          exported (transport/export-ast original-datoms)
          {:keys [datoms root-eid]} (transport/import-ast exported -3000 {})
          vm (semantic/create-vm {:env vm/primitives})
          result (-> vm
                     (vm/load-program {:node root-eid, :datoms datoms})
                     (vm/run)
                     (vm/value))]
      (is (= 30 result) "Imported AST evaluates to same result"))))


(deftest structural-dedup-test
  (testing "Import same bundle twice: second import reuses existing entities"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :literal, :value 2}]}
          datoms (vm/ast->datoms ast)
          exported (transport/export-ast datoms)
          ;; First import
          first-import (transport/import-ast exported -3000 {})
          ;; Second import with existing hashes
          second-import
            (transport/import-ast exported -4000 (:hash->eid first-import))]
      (is (seq (:datoms first-import)) "First import produces datoms")
      (is (empty? (:datoms second-import))
          "Second import produces no new datoms (all deduplicated)"))))


(deftest partial-overlap-test
  (testing "Shared subtree (+ 2 3) is deduplicated across two imports"
    (let [;; First AST: (+ 2 3)
          shared {:type :application,
                  :operator {:type :variable, :name '+},
                  :operands [{:type :literal, :value 2}
                             {:type :literal, :value 3}]}
          ;; Second AST: (+ 1 (+ 2 3))
          outer {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value 1} shared]}
          export-1 (transport/export-ast (vm/ast->datoms shared))
          export-2 (transport/export-ast (vm/ast->datoms outer))
          ;; Import shared first
          import-1 (transport/import-ast export-1 -3000 {})
          ;; Import outer with shared already present
          import-2 (transport/import-ast export-2 -4000 (:hash->eid import-1))
          ;; Count entities in second import: should skip shared subtree
          ;; nodes
          new-entity-count (count (group-by first (:datoms import-2)))]
      (is (pos? (count (:datoms import-1))))
      ;; Outer has: application(root), variable(+), literal(1), and the
      ;; subtree Subtree nodes (application, variable(+), literal(2),
      ;; literal(3)) are shared But variable(+) also appears in the outer
      ;; application So only literal(1) and application(root) should be new
      ;; Actually, variable(+) has the same hash in both, so it's also
      ;; deduplicated
      (is (< new-entity-count 4)
          "Second import creates fewer entities due to shared subtree"))))


(deftest materialized-cardinality-many-test
  (testing
    "Export/import with materialized (repeated scalar) :yin/operands datoms"
    (let [;; Build datoms for (+ 1 2) manually using repeated scalar rows
          ;; instead of vector-valued :yin/operands
          datoms
            [[-1025 :yin/type :application 0 0] [-1026 :yin/type :variable 0 0]
             [-1026 :yin/name '+ 0 0] [-1027 :yin/type :literal 0 0]
             [-1027 :yin/value 1 0 0] [-1028 :yin/type :literal 0 0]
             [-1028 :yin/value 2 0 0] [-1025 :yin/operator -1026 0 0]
             ;; Materialized: two separate datoms instead of one vector
             [-1025 :yin/operands -1027 0 0] [-1025 :yin/operands -1028 0 0]]
          exported (transport/export-ast datoms)
          {:keys [datoms root-eid]} (transport/import-ast exported -3000 {})
          ;; Verify imported datoms produce same content hash as canonical
          ;; form
          canonical-datoms (vm/ast->datoms
                             {:type :application,
                              :operator {:type :variable, :name '+},
                              :operands [{:type :literal, :value 1}
                                         {:type :literal, :value 2}]})
          canonical-hashes (content/compute-content-hashes canonical-datoms)
          canonical-root (get canonical-hashes
                              (apply max (keys canonical-hashes)))
          imported-hashes (content/compute-content-hashes datoms)
          imported-root (get imported-hashes root-eid)]
      (is (some? root-eid) "Root entity assigned")
      (is (= canonical-root imported-root)
          "Materialized datoms produce same hash as canonical vector form")
      ;; Verify the imported datoms actually evaluate correctly
      (let [vm (semantic/create-vm {:env vm/primitives})
            result (-> vm
                       (vm/load-program {:node root-eid, :datoms datoms})
                       (vm/run)
                       (vm/value))]
        (is (= 3 result) "Imported materialized datoms evaluate correctly")))))


;; =============================================================================
;; Continuation Transport Tests
;; =============================================================================

(deftest continuation-transport-test
  (testing "Park mid-computation, export, import, resume on fresh VM"
    (let [;; ((fn [x] (+ x 1)) (vm/park))
          ;; Parks mid-application: stack captures the pending lambda call
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :vm/park}]}
          vm0 (semantic/create-vm {:env vm/primitives})
          vm1 (vm/eval vm0 ast)
          parked-cont (vm/value vm1)
          ;; Get the AST datoms and content hashes
          ast-datoms (:datoms vm1)
          hash-cache (content/compute-content-hashes ast-datoms)
          ;; Export the parked continuation
          exported (transport/export-continuation parked-cont hash-cache)
          ;; Export and import the AST to a fresh VM
          ast-exported (transport/export-ast ast-datoms)
          ast-imported (transport/import-ast ast-exported -5000 {})
          ;; Import the continuation
          imported-cont (transport/import-continuation exported
                                                       (:hash->eid ast-imported)
                                                       (:datoms ast-imported))]
      (is (= :exported-continuation (:type exported)))
      (is (= :parked-continuation (:type imported-cont)))
      ;; Resume on a fresh VM with the imported continuation and datoms
      ;; Inject the continuation state directly and resume with value 42
      ;; Expected: (+ 42 1) = 43
      (let [fresh-vm (semantic/create-vm {:env vm/primitives})
            loaded (vm/load-program fresh-vm
                                    {:node (:root-eid ast-imported),
                                     :datoms (:datoms ast-imported)})
            resumed (assoc loaded
                      :stack (:stack imported-cont)
                      :env (:env imported-cont)
                      :control {:type :value, :val 42}
                      :halted false)
            result-vm (vm/run resumed)]
        (is (= 43 (vm/value result-vm))
            "Resumed continuation computes (+ 42 1) = 43")))))

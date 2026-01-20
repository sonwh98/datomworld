(ns yin.datom-bytecode-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.datom-bytecode :as dbc]
            [datalevin.core :as d]
            [yin.vm :as vm]))

(defn- gen-id [] (str (random-uuid)))

(deftest test-end-to-end-datom-bytecode
  (testing "Decompose, Compile, and Run (+ 10 32)"
    (let [db-path (str "/tmp/datom-bytecode-test-" (gen-id))
          conn (d/get-conn db-path dbc/schema)
          ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 32}]}
          datoms (dbc/decompose-ast ast)]

      (println "Transacting" (count datoms) "structural datoms")
      ;; 1. Transact structural datoms
      (d/transact! conn datoms)

      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))

            ;; 2. Compile to execution stream
            exec-stream (dbc/compile-to-stream db root-id)]

        (println "Compiled to" (count exec-stream) "execution steps")
        (is (= 4 (count exec-stream)) "Should have 4 steps: load +, push 10, push 32, apply")

        ;; 3. Transact execution datoms
        (d/transact! conn exec-stream)

        (let [final-db (d/db conn)
              result (dbc/run-stream final-db exec-stream vm/primitives)]

          (is (= 42 result) "10 + 32 should be 42")

          ;; Cleanup
          (d/close conn))))))

(deftest test-if-datom-bytecode
  (testing "Compile and Run (if (= 1 1) 10 20)"
    (let [db-path (str "/tmp/datom-bytecode-if-test-" (gen-id))
          conn (d/get-conn db-path dbc/schema)
          ast {:type :if
               :test {:type :application
                      :operator {:type :variable :name '=}
                      :operands [{:type :literal :value 1}
                                 {:type :literal :value 1}]}
               :consequent {:type :literal :value 10}
               :alternate {:type :literal :value 20}}
          datoms (dbc/decompose-ast ast)]

      (d/transact! conn datoms)

      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))
            exec-stream (dbc/compile-to-stream db root-id)]

        (d/transact! conn exec-stream)

        (let [final-db (d/db conn)
              result (dbc/run-stream final-db exec-stream vm/primitives)]

          (is (= 10 result) "Should return consequent (10)")

          (d/close conn))))))

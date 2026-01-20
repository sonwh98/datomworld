(ns yin.datom-bytecode-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.datom-bytecode :as dbc]
            #?(:clj [datalevin.core :as d]
               :cljs [datascript.core :as d])
            [yin.vm :as vm]))

(defn- gen-id [] #?(:clj (str (java.util.UUID/randomUUID)) :cljs (str (random-uuid))))

(defn- get-test-conn []
  #?(:clj (d/get-conn (str "/tmp/datom-bytecode-test-" (gen-id)) dbc/schema)
     :cljs (d/create-conn dbc/schema)))

(defn- close-test-conn [conn]
  #?(:clj (d/close conn)))

(deftest test-end-to-end-datom-bytecode
  (testing "Decompose, Compile, and Run (+ 10 32)"
    (let [conn (get-test-conn)
          ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 32}]}
          datoms (dbc/decompose-ast ast)]

      ;; 1. Transact structural datoms
      (d/transact! conn datoms)

      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))

            ;; 2. Compile to execution stream
            exec-stream (dbc/compile-to-stream db root-id)]

        (is (= 4 (count exec-stream)) "Should have 4 steps: load +, push 10, push 32, apply")

        ;; 3. Transact execution datoms
        (d/transact! conn exec-stream)

        (let [result (dbc/run-stream conn exec-stream vm/primitives)]
          (is (= 42 result) "10 + 32 should be 42")
          (close-test-conn conn))))))

(deftest test-if-datom-bytecode
  (testing "Compile and Run (if (= 1 1) 10 20)"
    (let [conn (get-test-conn)
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

        (let [result (dbc/run-stream conn exec-stream vm/primitives)]
          (is (= 10 result) "Should return consequent (10)")
          (close-test-conn conn))))))

(deftest test-lambda-app-datom-bytecode
  (testing "Compile and Run ((fn [x] x) 42)"
    (let [conn (get-test-conn)
          ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :variable :name 'x}}
               :operands [{:type :literal :value 42}]}
          datoms (dbc/decompose-ast ast)]

      (d/transact! conn datoms)
      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))
            exec-stream (dbc/compile-to-stream db root-id)]
        (d/transact! conn exec-stream)
        (let [result (dbc/run-stream conn exec-stream vm/primitives)]
          (is (= 42 result) "Identity lambda should return 42")
          (close-test-conn conn))))))
(ns yin.benchmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.bytecode :as bc]
            [yin.datom-bytecode :as dbc]
            [datalevin.core :as d]
            [yang.clojure :as yang]))

(defn- gen-id [] (str (random-uuid)))

(def deep-expr
  (loop [i 0 expr 1]
    (if (>= i 12) ;; 2^12 operations
      expr
      (recur (inc i) (list '+ expr expr)))))

(println "Deep Expr depth:" (count (str deep-expr)))
(println "Deep Expr start:" (subs (str deep-expr) 0 100))

(def ast (yang/compile deep-expr))

(deftest benchmark-bytecode-vs-datom-stream
  (testing "Performance Comparison: Array Bytecode vs Datom Stream"

    ;; --- 1. Yin.Bytecode (Array/Stack) ---
    (try
      (let [[bytes pool] (bc/compile ast)
            start (System/nanoTime)
            result (bc/run-bytes bytes pool vm/primitives)
            end (System/nanoTime)
            duration-ms (/ (- end start) 1000000.0)]

        (is (= 4096 result))
        (println (format "Yin.Bytecode (Array): %.4f ms" duration-ms)))
      (catch Exception e
        (println "Yin.Bytecode (Array): FAILED -" (.getMessage e))))

                ;; --- 2. Yin.Datom-Bytecode (Datalevin/Datom Stream) ---
    (let [db-path (str "/tmp/benchmark-" (gen-id))
          conn (d/get-conn db-path dbc/schema)
          datoms (dbc/decompose-ast ast)]

      (d/transact! conn datoms)

      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))

                        ;; Compile to stream
            exec-stream (dbc/compile-to-stream db root-id)]

        (d/transact! conn exec-stream)

                    ;; Measure Execution Only
        (let [start (System/nanoTime)
              result (dbc/run-stream conn exec-stream vm/primitives)
              end (System/nanoTime)
              duration-ms (/ (- end start) 1000000.0)]

          (is (= 4096 result))
          (println (format "Yin.Datom-Bytecode (Stream): %.4f ms" duration-ms))

          (d/close conn))))))


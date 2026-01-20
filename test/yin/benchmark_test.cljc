(ns yin.benchmark-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]
            [yin.bytecode :as bc]
            [yin.datom-bytecode :as dbc]
            [datascript.core :as d]
            [yang.clojure :as yang]))

(defn now []
  #?(:clj (System/nanoTime)
     :cljs (if (exists? js/performance)
             (js/performance.now)
             (js/Date.now))))

(defn ms-diff [start end]
  #?(:clj (/ (- end start) 1000000.0)
     :cljs (- end start))) ;; JS performance.now is already in ms (float)

(def deep-expr
  (loop [i 0 expr 1]
    (if (>= i 12) ;; 2^12 operations
      expr
      (recur (inc i) (list '+ expr expr)))))

(println "Deep Expr depth:" (count (str deep-expr)))

(def ast (yang/compile deep-expr))

(deftest benchmark-bytecode-vs-datom-stream
  (testing "Performance Comparison: Array Bytecode vs Datom Stream"

    ;; --- 1. Yin.Bytecode (Array/Stack) ---
    (try
      (let [[bytes pool] (bc/compile ast)
            start (now)
            result (bc/run-bytes bytes pool vm/primitives)
            end (now)
            duration-ms (ms-diff start end)]

        (is (= 4096 result))
        #?(:clj (println (format "Yin.Bytecode (Array): %.4f ms" duration-ms))
           :cljs (println "Yin.Bytecode (Array):" duration-ms "ms")))
      (catch #?(:clj Exception :cljs js/Error) e
        (println "Yin.Bytecode (Array): FAILED -" (ex-message e))))

    ;; --- 2. Yin.Datom-Bytecode (DataScript/Datom Stream) ---
    (let [conn (d/create-conn dbc/schema)
          datoms (dbc/decompose-ast ast)]

      (d/transact! conn datoms)

      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))
            exec-stream (dbc/compile-to-stream db root-id)]

        (d/transact! conn exec-stream)

        ;; Measure Execution Only
        (let [start (now)
              result (dbc/run-stream conn exec-stream vm/primitives)
              end (now)
              duration-ms (ms-diff start end)]

          (is (= 4096 result))
          #?(:clj (println (format "Yin.Datom-Bytecode (Stream): %.4f ms" duration-ms))
             :cljs (println "Yin.Datom-Bytecode (Stream):" duration-ms "ms")))))

    ;; --- 3. Yin.Datom-Bytecode (Fast/Hydrated) ---
    (let [conn (d/create-conn dbc/schema)
          datoms (dbc/decompose-ast ast)]

      (d/transact! conn datoms)

      (let [db (d/db conn)
            root-id (:ast/node-id (first datoms))
            exec-stream (dbc/compile-to-stream db root-id)]

        (d/transact! conn exec-stream)

        (let [start (now)
              result (dbc/run-stream-fast conn exec-stream vm/primitives)
              end (now)
              duration-ms (ms-diff start end)]

          (is (= 4096 result))
          #?(:clj (println (format "Yin.Datom-Bytecode (Fast):   %.4f ms" duration-ms))
             :cljs (println "Yin.Datom-Bytecode (Fast):" duration-ms "ms")))))))

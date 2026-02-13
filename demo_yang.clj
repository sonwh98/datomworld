#!/usr/bin/env clj

(require '[yang.clojure :as yang])
(require '[yin.vm :as vm])
(require '[clojure.pprint :as pp])


(defn make-state
  [env]
  {:control nil, :environment env, :store {}, :continuation nil, :value nil})


(println "=== Yang Compiler Demo ===\n")

(println "Example 1: Simple literal")
(def ast1 (yang/compile 42))
(pp/pprint ast1)
(def result1 (vm/run (make-state {}) ast1))
(println "Result:" (:value result1) "\n")

(println "Example 2: Simple addition")
(def ast2 (yang/compile '(+ 10 20)))
(pp/pprint ast2)
(def result2 (vm/run (make-state vm/primitives) ast2))
(println "Result:" (:value result2) "\n")

(println "Example 3: Lambda and application")
(def code3 '((fn [x] (* x 2)) 21))
(println "Source:" code3)
(def ast3 (yang/compile code3))
(println "Compiled AST:")
(pp/pprint ast3)
(def result3 (vm/run (make-state vm/primitives) ast3))
(println "Result:" (:value result3) "\n")

(println "Example 4: Let bindings")


(def code4 '(let [x 5 y 3] (+ x y)))


(println "Source:" code4)
(def ast4 (yang/compile code4))
(println "Compiled AST (desugared to lambda applications):")
(pp/pprint ast4)
(def result4 (vm/run (make-state vm/primitives) ast4))
(println "Result:" (:value result4) "\n")

(println "Example 5: Higher-order functions")


(def code5
  '(let
    [double (fn [x] (* x 2)) triple (fn [x] (* x 3)) apply-both
     (fn [f g x] (+ (f x) (g x)))]
    (apply-both double triple 5)))


(println "Source:" code5)
(def ast5 (yang/compile code5))
(def result5 (vm/run (make-state vm/primitives) ast5))
(println "Result:" (:value result5))
(println "Explanation: (double 5) + (triple 5) = 10 + 15 = 25\n")

(println "Example 6: Conditional")
(def code6 '(if (< 3 5) (* 2 10) (+ 1 1)))
(println "Source:" code6)
(def ast6 (yang/compile code6))
(pp/pprint ast6)
(def result6 (vm/run (make-state vm/primitives) ast6))
(println "Result:" (:value result6) "\n")

(println "=== Demo Complete ===")
(println "\nThe Yang compiler successfully transforms Clojure code into")
(println "Universal AST that the Yin VM can execute!")

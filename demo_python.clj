#!/usr/bin/env clj

(require '[yang.python :as py])
(require '[yin.vm :as vm])
(require '[clojure.pprint :as pp])


(defn make-state
  [env]
  {:control nil, :environment env, :store {}, :continuation nil, :value nil})


(println "=== Yang Python Compiler Demo ===\n")

(println "Example 1: Simple literal")
(def code1 "42")
(println "Python:" code1)
(def ast1 (py/compile code1))
(pp/pprint ast1)
(def result1 (vm/run (make-state {}) ast1))
(println "Result:" (:value result1) "\n")

(println "Example 2: Simple addition")
(def code2 "10 + 20")
(println "Python:" code2)
(def ast2 (py/compile code2))
(pp/pprint ast2)
(def result2 (vm/run (make-state vm/primitives) ast2))
(println "Result:" (:value result2) "\n")

(println "Example 3: Lambda expression")
(def code3 "(lambda x: x * 2)(21)")
(println "Python:" code3)
(def ast3 (py/compile code3))
(println "Compiled AST:")
(pp/pprint ast3)
(def result3 (vm/run (make-state vm/primitives) ast3))
(println "Result:" (:value result3) "\n")

(println "Example 4: Python def statement")
(def code4 "def double(x): return x * 2")
(println "Python:" code4)
(def ast4 (py/compile code4))
(println "Compiled to lambda:")
(pp/pprint ast4)


;; To execute, we need to apply it
(def app4
  {:type :application, :operator ast4, :operands [{:type :literal, :value 21}]})


(def result4 (vm/run (make-state vm/primitives) app4))
(println "Result of double(21):" (:value result4) "\n")

(println "Example 5: Operator precedence")
(def code5 "2 + 3 * 4")
(println "Python:" code5)
(def result5 (vm/run (make-state vm/primitives) (py/compile code5)))
(println "Result:" (:value result5))
(println "Explanation: 2 + (3 * 4) = 2 + 12 = 14\n")

(println "Example 6: If expression")
(def code6 "100 if 3 < 5 else 200")
(println "Python:" code6)
(def ast6 (py/compile code6))
(pp/pprint ast6)
(def result6 (vm/run (make-state vm/primitives) ast6))
(println "Result:" (:value result6) "\n")

(println "Example 7: Higher-order function")
(def code7 "(lambda f: f(5))(lambda x: x * 2)")
(println "Python:" code7)
(println "Explanation: Apply (lambda x: x * 2) to the argument 5")
(def result7 (vm/run (make-state vm/primitives) (py/compile code7)))
(println "Result:" (:value result7) "\n")

(println "Example 8: Multi-language - Python and Clojure together!")
(require '[yang.clojure :as clj])
(println "Python: lambda x: x * 3")
(def py-triple (py/compile "lambda x: x * 3"))
(println "Clojure: (fn [x] (+ x 10))")
(def clj-add10 (clj/compile '(fn [x] (+ x 10))))
(println "Composing: clj-add10(py-triple(5))")


(def composed
  {:type :application,
   :operator clj-add10,
   :operands [{:type :application,
               :operator py-triple,
               :operands [{:type :literal, :value 5}]}]})


(def result8 (vm/run (make-state vm/primitives) composed))
(println "Result: add10(triple(5)) = add10(15) = 25")
(println "Actual:" (:value result8) "\n")

(println "=== Demo Complete ===")
(println "\nThe Yang Python compiler successfully transforms Python code into")
(println "Universal AST that the Yin VM can execute!")
(println "\nPython and Clojure code can be composed together because they")
(println "both compile to the same Universal AST format!")

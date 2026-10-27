;; J5 budget "below" legs, on the JVM and under jolt (src/dev/jolt_j5_run.clj
;; compares the two): each carrier shape at depth 32 through
;; yang.python.antlr.parser. Prints `BELOW <shape> <edn packet>`.
(ns jolt-j5-below
  (:require [yang.python.antlr.parser :as parser]))


(defn- rep
  [n s]
  (apply str (repeat n s)))


(def shapes
  {"parens" (fn [n] (str "x = " (rep n "(") "1" (rep n ")") "\n"))
   "list" (fn [n] (str "x = " (rep n "[") "1" (rep n "]") "\n"))
   "call" (fn [n] (str "x = " (rep n "f(") "1" (rep n ")") "\n"))
   "unary" (fn [n] (str "x = " (rep n "-") "1\n"))
   "not" (fn [n] (str "x = " (rep n "not ") "1\n"))
   "power" (fn [n] (str "x = " (rep n "2 ** ") "2\n"))
   "lambda" (fn [n] (str "x = " (rep n "lambda: ") "1\n"))
   "ternary" (fn [n] (str "x = " (rep n "1 if 1 else ") "1\n"))
   "indent" (fn [n]
              (str (apply str (for [i (range n)] (str (rep i " ") "if x:\n")))
                   (rep n " ") "pass\n"))})


(doseq [shape (sort (keys shapes))]
  (println "BELOW" shape (pr-str (parser/parse-source ((shapes shape) 32)))))


(flush)
(System/exit 0)

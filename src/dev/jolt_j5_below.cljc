;; J5 budget "below" legs, on the JVM and under jolt (src/dev/jolt_j5_run.clj
;; compares the two): each carrier shape at depth 32 through
;; yang.python.antlr.parser, plus (J5b, R4a) the flat `1+1+...` chain whose
;; tree is as deep as it is long, at 20,000 operators (the JVM's packet is
;; the oracle; the native walkers are iterative). Prints
;; `BELOW <shape> <edn packet>`. Self-contained (no src/dev requires), so the
;; JVM runs it with plain `clojure -M`.
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


(def deep
  ;; shape, its source (J5b round 2, C1'): R4a at 20,000 operators; R1c, the
  ;; backslash-joined chained assignment, at 10,000 lines; `**` at 100,000,
  ;; a 100,000-deep tree in linear time (the R4b replacement)
  {"r4-20000" (str (rep 20000 "1+") "1\n")
   "r1c-10000" (str (rep 10000 "x = 1 if 1 else \\ \n") "1\n")
   "power-100000" (str "x = " (rep 100000 "2 ** ") "2\n")
   ;; the flat not chain under the bound (C1' parse witness, 1,038 frames)
   "not-1024" (str "x = " (rep 1024 "not ") "1\n")})


(doseq [shape (sort (keys shapes))]
  (println "BELOW" shape (pr-str (parser/parse-source ((shapes shape) 32)))))


(doseq [[shape src] (sort deep)]
  (let [t0 (System/nanoTime)
        packet (parser/parse-source src)]
    (println "TIME" shape (long (/ (- (System/nanoTime) t0) 1e6)))
    (println "BELOW" shape (pr-str packet))))


(flush)
(System/exit 0)

(ns jolt-j5-shapes
  "J5b (known-gaps row 59): the nesting shapes the budget gates and the
   calibration record use, as functions of a depth n, shared by the jolt
   probes and the JVM comparison. The nine r5 stack shapes
   (src/dev/jolt_j0c_stack_child.clj), the four the ruling adds (an SLL
   lookahead nest, an open lookahead nest at EOF, a comprehension chain, a
   backslash-continued ternary), and the review's repros, each as named in
   docs/design/yin.repl-jolt-implementation.md §7.6.")


(defn rep
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
                   (rep n " ") "pass\n"))
   ;; the ruling's four
   "sll-nest" (fn [n] (str "f(" (rep n "(") "1" (rep n ")") ")\n"))
   "open-nest" (fn [n] (str "f(" (rep n "(")))
   "comp-for" (fn [n] (str "[x" (rep n " for x in x") "]\n"))
   "bs-ternary" (fn [n] (str "x = " (rep n "1 if 1 else \\ \n") "1\n"))
   ;; the review's repros (R1a..R4b), n the repeat count it names
   "r1a" (fn [n] (str "#\r" (rep n "(") "1" (rep n ")") "\n"))
   "r1b" (fn [n] (str "#\f" (rep n "(") "1" (rep n ")") "\n"))
   "r1c" (fn [n] (str (rep n "x = 1 if 1 else \\ \n") "1\n"))
   "r2a" (fn [n] (str "[x" (rep n " for x in x") "]\n"))
   "r2b" (fn [n] (str "[x" (rep n " for x in x if x") "]\n"))
   "r3" (fn [n] (str (rep n (str (rep n "not ") "(0 or ")) "0" (rep n ")") "\n"))
   "r3-not" (fn [n] (str "x = " (rep n "not ") "1\n"))
   "r4" (fn [n] (str (rep n "1+") "1\n"))
   ;; a full-LL decision under a deep outer stack (800 brackets)
   "deep-unary" (fn [n] (str "x = " (rep 800 "(") (rep n "-") "1" (rep 800 ")") "\n"))
   ;; time controls: flat on one line, flat over lines, a chain over lines
   "flat-list" (fn [n] (str "x = [" (rep n "1,") "1]\n"))
   "flat-lines" (fn [n] (rep n "1\n"))
   "r4-lines" (fn [n] (str "x = (" (rep n "1+\n") "1)\n"))
   "r4-mul" (fn [n] (str (rep n "1*") "1\n"))
   ;; an error at the deepest point: recovery walks the rule stack
   "parens-error" (fn [n] (str "x = " (rep n "(") "1 1" (rep n ")") "\n"))
   "not-error" (fn [n] (str "x = " (rep n "not ") "\n"))})


;; C2: per nesting shape, the guard that bounds it, its count per level from
;; the grammar (base + per x n, the measured slopes of `bb
;; src/dev/jolt_j5_calibrate.clj --slopes`), and the largest n that parses:
;; the largest with base + per x n <= bound. power is not here: the
;; generated expr loop makes `2 ** 2 ** ...` iterative (no frame or link per
;; level), so it is bounded by the input axis only.
(def bounds {:rule 8192, :look 4096, :full 256})


(def predicted
  {"parens" {:kind :rule, :base 14, :per 9}
   "list" {:kind :rule, :base 14, :per 9}
   "call" {:kind :look, :base 9, :per 10}
   "not" {:kind :rule, :base 14, :per 1}
   "lambda" {:kind :rule, :base 14, :per 2}
   "ternary" {:kind :rule, :base 14, :per 1}
   "indent" {:kind :rule, :base 9, :per 4}
   "unary" {:kind :full, :base 3, :per 1}
   "sll-nest" {:kind :look, :base 19, :per 9}
   ;; unclosed at EOF: at the bound it is a syntax error, not a refusal
   "open-nest" {:kind :look, :base 19, :per 9, :at :yang.cst/syntax-error}
   "comp-for" {:kind :rule, :base 22, :per 2}
   "bs-ternary" {:kind :rule, :base 14, :per 1}})


(defn largest-parsing
  "The largest n of `shape` the bounds admit, from the predicted table."
  [shape]
  (let [{:keys [kind base per]} (predicted shape)]
    (quot (- (bounds kind) base) per)))

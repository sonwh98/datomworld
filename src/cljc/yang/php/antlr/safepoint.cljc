(ns yang.php.antlr.safepoint
  "The PHP `:loop` and `:call` hooks. A bounded run charges one safepoint to
   its per-run counter; exhaustion unwinds past guest handlers and raises the
   operational fatal, not a guest Throwable."
  (:require
    [yang.core.uast :as u]))


(def profile
  {:loop 'php.sp/loop, :call {:hook 'php.sp/call, :sequence true}})


(def fatal-text
  "Maximum execution time of 30 seconds exceeded")


(def ^:private function-definitions
  '[[php.sp/exhaust!
     (fn []
       (do (php/unwind-to 1)
           (php/raise {:php/operational true, :message
                       "Maximum execution time of 30 seconds exceeded"})))]
    [php.sp/tick
     (fn []
       (let [n (cell/get php.sp/remaining)]
         (if (= n 0)
           (php.sp/exhaust!)
           (do (cell/set! php.sp/remaining (- n 1)) :php/null))))]
    [php.sp/loop (fn [] (php.sp/tick))]
    [php.sp/call (fn [] (php.sp/tick))]])


(defn- definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(defn program
  "The bounded run: hook definitions and a counter holding `budget`, then the
   safepoint-derived program `ast`."
  [budget ast]
  (u/mark-tails
    (u/then (definitions->uast function-definitions)
            (u/then (u/def! 'php.sp/remaining (u/sexp->uast (list 'cell/new budget)))
                    ast))))

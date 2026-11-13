(ns yang.php.antlr.safepoint
  "The PHP `:loop` and `:call` hooks. A bounded run charges one safepoint to
   its per-run counter; exhaustion unwinds past guest handlers and raises the
   operational fatal, not a guest Throwable."
  (:require
    [yang.core.uast :as u]
    [yang.core.module :as module]))


(def profile
  {:loop 'php.sp/loop, :call {:hook 'php.sp/call, :sequence true}})


(def ticks-profile
  "Statement completion hooks for PHP's lexical ticks directives. This is
   separate from the execution-budget profile so ticks do not charge it."
  {:line {:hook 'php.sp/line, :operands true, :after true}})


(def fatal-text
  "Maximum execution time of 30 seconds exceeded")


(def ^:private function-definitions
  '[[php.sp/exhaust!
     ;; the terminal root escape: guest handlers, returns and finalizers
     ;; cannot replace the operational fatal
     (fn []
       (php/root-fatal "Maximum execution time of 30 seconds exceeded"))]
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
   safepoint-derived program `ast`. `budget` must be a non-negative integer;
   anything else is a named host-side error, not a guest diagnostic."
  [budget ast]
  (when-not (and (integer? budget) (not (neg? budget)))
    (throw (ex-info "php.sp/remaining budget must be a non-negative integer"
                    {:yang.php.antlr.safepoint/error :invalid-budget
                     :budget budget})))
  (u/mark-tails
    (u/then (definitions->uast function-definitions)
            (u/then (u/def! 'php.sp/remaining (u/sexp->uast (list 'cell/new budget)))
                    ast))))


(defn module-spec
  "The linked ticks hook delegates to the shared PHP runtime."
  [registry php-address]
  (let [ast (module/module-tree
              [(module/require-node 'php)
               (u/def! 'line (u/sexp->uast '(fn [line site] (php/tick-line line site))))])
        requires {'php php-address}]
    {:name 'php.sp, :ast ast, :exports '#{line}, :requires requires,
     :primitives (module/free-primitives :yang.php/error :yang.php/undeclared
                                         registry (set (keys requires)) ast)}))

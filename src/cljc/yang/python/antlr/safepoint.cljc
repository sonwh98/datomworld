(ns yang.python.antlr.safepoint
  "The Python hook prelude for `yang.safepoint` (Architect safepoint
   design, slice 1: signals). Universal AST, like the base prelude; the
   stage only places `(py.sp/loop)` and `(py.sp/call)`, and what they mean
   is written here.

   Signals. The composition hands the task a signal stream, a sealed
   reference in the store under `signals-key`; a host adapter appends one
   plain value per delivered signal. Each hook reads it with `stream/poll!`,
   which answers `:dao.stream/blocked` rather than parking, so a program
   with no signal pending never waits at a safepoint. On a signal (or a
   gap: values were lost, so at least one arrived) the registered Python
   handler is called with the signal and None, as CPython calls
   `handler(signum, frame)`; with none registered, `KeyboardInterrupt` is
   raised there, through `py/raise`: an explicit continuation invoke at an
   explicit program point. Delivery is not journalled (owner decision 8).

   Load order. The base prelude is bundled into every program tree, so the
   hook prelude loads before it and may not touch a base definition while
   loading: it allocates only a cell and a cursor. `KeyboardInterrupt` is a
   base-prelude builtin class; `py.sp/deliver` names it only inside its
   body, so the name resolves when a signal arrives, after the base prelude
   has loaded. Every key this prelude defines is in `py.sp`."
  (:require
    [yang.python.antlr.uast :as u]))


(def profile
  "The `yang.safepoint` profile this prelude implements: site kind -> hook."
  {:loop 'py.sp/loop, :call 'py.sp/call})


(def signals-key
  "The store key the composition binds the signal stream's reference to."
  'py.sp/signals)


(def host-names
  "Module exports this prelude calls: the cell module, and the stream
   module's `cursor` and `poll!`."
  '#{cell/new cell/get cell/set! stream/cursor stream/poll!})


(def ^:private function-definitions
  '[[py.sp/set-handler! (fn [h] (cell/set! py.sp/handler h))]
    [py.sp/deliver
     (fn [sig]
       (let [h (cell/get py.sp/handler)]
         (if (= h :py/None)
           (py/raise (py/call py.b/KeyboardInterrupt []))
           (do (py/call h (py/conj (py/conj [] sig) :py/None))
               :py/None))))]
    [py.sp/poll
     (fn []
       (let [s (stream/poll! py.sp/cursor)]
         (if (= s :dao.stream/blocked)
           :py/None
           (if (nil? s) :py/None (py.sp/deliver s)))))]
    [py.sp/loop (fn [] (py.sp/poll))]
    [py.sp/call (fn [] (py.sp/poll))]])


(def ^:private state-definitions
  '[[py.sp/handler (cell/new :py/None)]
    [py.sp/cursor (stream/cursor py.sp/signals)]])


(defn- definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(def uast
  "The hook prelude: functions, then its cells and the signal cursor."
  (u/mark-tails (u/then (definitions->uast function-definitions)
                        (definitions->uast state-definitions))))


(def noop-uast
  "Hooks that do nothing: the derived program under them is the naive one."
  (u/mark-tails (definitions->uast '[[py.sp/loop (fn [] :py/None)]
                                     [py.sp/call (fn [] :py/None)]])))


(defn program
  "The derived run's tree: `hooks` (a hook prelude), then the derived
   program `ast`."
  [hooks ast]
  (u/then hooks ast))

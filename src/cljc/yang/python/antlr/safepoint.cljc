(ns yang.python.antlr.safepoint
  "The Python hook prelude for `yang.safepoint` (Architect safepoint
   design, slice 1: signals; slice 2: recursion). Universal AST, like the
   base prelude; the stage only places `(py.sp/loop)`, `(py.sp/call)` and
   `(py.sp/return body)`, and what they mean is written here.

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

   Recursion (slice 2). Depth measures the current continuation: it is
   `:base` plus `:depth` of the base prelude's dynamic-context record
   `py.rt/ctx`, the one cell that also holds the handler stack (`:base` is
   0 outside generators; each crossing into a generator sets it to the
   resumer's absolute depth plus one). `py.sp/call`, at the head of every
   Python function body, increments `:depth` and raises `RecursionError`
   through `py/raise` when the absolute depth would exceed the limit;
   `py.sp/return`, which the stage applies to the body's value, decrements
   it on normal exit. An escape (an exception caught above, a `return`
   through `finally`) runs no exit hook: it restores the record its
   capture point saved, current `:base` kept, so the depth is restored,
   never decremented. The limit counts Python function frames: with limit
   n, n nested calls run and the next raises. It lives in the base
   prelude's cell `py.rt/limit`, 1000 by default, outside the
   escape-restored record, so a change survives escapes; the base prelude
   reads it to admit generator starts and resumes, which no hook site
   sees. `py.sp/set-recursion-limit!` sets it as `sys.setrecursionlimit`
   would (the Python surface is not reachable yet: the lowering refuses
   `import`).

   Load order. The base prelude is bundled into every program tree, so the
   hook prelude loads before it and may not touch a base definition while
   loading: it allocates only a cell and a cursor. `KeyboardInterrupt`,
   `RecursionError`, `py.rt/ctx` and `py.rt/limit` are base-prelude
   definitions; the
   hooks name them only inside their bodies, so the names resolve when a
   hook runs, after the base prelude has loaded. Every key this prelude
   defines is in `py.sp`."
  (:require
    [yang.python.antlr.uast :as u]))


(def profile
  "The `yang.safepoint` profile this prelude implements: site kind -> hook."
  {:loop 'py.sp/loop, :call 'py.sp/call, :return 'py.sp/return})


(def signals-key
  "The store key the composition binds the signal stream's reference to."
  'py.sp/signals)


(def host-names
  "Module exports this prelude calls: the cell module, the stream module's
   `cursor` and `poll!`, and the data module's `str-concat`."
  '#{cell/new cell/get cell/set! stream/cursor stream/poll! data/str-concat})


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
    [py.sp/enter
     (fn []
       (let [c (cell/get py.rt/ctx)
             d (+ 1 (get c :depth))]
         (if (< (cell/get py.rt/limit) (+ (get c :base) d))
           (py/raise-new py.b/RecursionError
                         {:py/str "maximum recursion depth exceeded"})
           (cell/set! py.rt/ctx (assoc c :depth d)))))]
    [py.sp/loop (fn [] (py.sp/poll))]
    [py.sp/call (fn [] (do (py.sp/poll) (py.sp/enter)))]
    [py.sp/return
     (fn [v]
       (let [c (cell/get py.rt/ctx)]
         (do (cell/set! py.rt/ctx (assoc c :depth (- (get c :depth) 1)))
             v)))]
    [py.sp/recursion-limit (fn [] (cell/get py.rt/limit))]
    [py.sp/set-recursion-limit!
     ;; sys.setrecursionlimit: an int (bool included), at least 1, and above
     ;; the current absolute depth; a refused limit leaves the old one
     (fn [n]
       (if (py/int? n)
         (if (< (py/num n) 1)
           (py/raise-new py.b/ValueError
                         (py/str (data/str-concat "recursion limit must be "
                                                  "greater or equal than 1")))
           (if (<= (py/num n) (py/abs-depth (cell/get py.rt/ctx)))
             (py/raise-new py.b/RecursionError
                           (py/str (data/str-concat
                                     "cannot set the recursion limit: "
                                     "the limit is too low")))
             (do (cell/set! py.rt/limit (py/num n)) :py/None)))
         (py/type-error {:py/str "an integer is required"})))]])


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
                                     [py.sp/call (fn [] :py/None)]
                                     [py.sp/return (fn [v] v)]])))


(defn program
  "The derived run's tree: `hooks` (a hook prelude), then the derived
   program `ast`."
  [hooks ast]
  (u/then hooks ast))

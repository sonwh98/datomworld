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

   Two profiles, one source (C4 slice P3). `uast` is the bundled hook
   prelude: it loads before the base prelude and may not touch a base
   definition while loading, so it allocates only the handler cell, a cursor and the trace cell, and
   the hooks name base keys only inside their bodies. `module-uast` is the
   same definition list as the linker module `pysp`, which requires `py`,
   inverting the bundled load order: its install child defines lambdas
   only, for a cell or a cursor is runtime state a lift refuses, and a
   module closure cannot read the task's ambient signal stream; the linked
   wrapper passes the stream's reference to `(pysp/attach! signals)`,
   which allocates the handler cell and mints the cursor. The hooks reach
   the cells the base prelude owns through the exports `py/rt-ctx` and
   `py/rt-limit` and the builtin classes through `py.sp/class` and the
   builtins dict, for `py.rt/*` and `py.b/*` are internal store keys
   invisible across a module boundary. Every key this prelude defines is
   in `py.sp`."
  (:require
    [clojure.walk :as walk]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.module :as module]))


(def profile
  "The `yang.safepoint` profile this prelude implements under the bundled
   prelude: site kind -> hook."
  {:loop 'py.sp/loop, :call 'py.sp/call, :return 'py.sp/return})


(def linked-profile
  "The `yang.safepoint` profile under the linked prelude: the hooks are the
   `pysp` module's exports, so `A'` applies `pysp/loop` and its siblings
   and a run without the module fails closed on the unresolved hook name."
  {:loop 'pysp/loop, :call 'pysp/call, :return 'pysp/return})


(def tracing-profile
  "The `yang.safepoint` profile with tracing (slice 3), under the bundled
   prelude: the recursion hooks plus the trace-aware `tcall`/`treturn`
   and `tline`. `tcall` is applied to the function's name and its `def`
   line, `tline` to the statement's line."
  {:loop 'py.sp/loop,
   :call {:hook 'py.sp/tcall, :operands true},
   :return 'py.sp/treturn,
   :line {:hook 'py.sp/tline, :operands true}})


(def linked-tracing-profile
  "`tracing-profile` under the linked prelude."
  {:loop 'pysp/loop,
   :call {:hook 'pysp/tcall, :operands true},
   :return 'pysp/treturn,
   :line {:hook 'pysp/tline, :operands true}})


(def signals-key
  "The store key the composition binds the signal stream's reference to."
  'py.sp/signals)


(def module-name
  "The linker module the hook prelude is published as (the reserved name
   of the C4 naming table)."
  'pysp)


(def host-names
  "Module exports this prelude calls: the cell module, the stream module's
   `cursor` and `poll!`, and the data module's `str-concat`."
  '#{cell/new cell/get cell/set! stream/cursor stream/poll! data/str-concat})


(def function-definitions
  "The single definition list, `[store-key form]` in definition order:
   every hook function, then `attach!`. Defining them runs nothing. Both
   emitters read it; the tests assert the module's shape against it."
  '[[py.sp/set-handler! (fn [h] (cell/set! py.sp/handler h))]
    [py.sp/class
     ;; a builtin class by name, through the builtins dict the base prelude
     ;; seeds: `py.b/*` are its internal store keys, invisible across a
     ;; module boundary, and this read resolves whenever a hook runs
     (fn [n] (py/global-get (py/dict-new) n))]
    [py.sp/deliver
     (fn [sig]
       (let [h (cell/get py.sp/handler)]
         (if (= h :py/None)
           (py/raise (py/call (py.sp/class {:py/str "KeyboardInterrupt"}) []))
           (do (py/call h (py/vconj (py/vconj [] sig) :py/None))
               :py/None))))]
    [py.sp/poll
     (fn []
       (let [s (stream/poll! py.sp/cursor)]
         (if (= s :dao.stream/blocked)
           :py/None
           (if (nil? s) :py/None (py.sp/deliver s)))))]
    [py.sp/enter
     (fn []
       (let [cell (py/rt-ctx)
             c (cell/get cell)
             d (+ 1 (get c :depth))]
         (if (< (cell/get (py/rt-limit)) (+ (get c :base) d))
           (py/raise-new (py.sp/class {:py/str "RecursionError"})
                         {:py/str "maximum recursion depth exceeded"})
           (cell/set! cell (assoc c :depth d)))))]
    [py.sp/loop (fn [] (py.sp/poll))]
    [py.sp/call (fn [] (do (py.sp/poll) (py.sp/enter)))]
    [py.sp/return
     (fn [v]
       (let [cell (py/rt-ctx)
             c (cell/get cell)]
         (do (cell/set! cell (assoc c :depth (- (get c :depth) 1)))
             v)))]
    [py.sp/recursion-limit (fn [] (cell/get (py/rt-limit)))]
    [py.sp/set-recursion-limit!
     ;; sys.setrecursionlimit: an int (bool included), at least 1, and above
     ;; the current absolute depth; a refused limit leaves the old one
     (fn [n]
       (if (py/int? n)
         (if (< (py/num n) 1)
           (py/raise-new (py.sp/class {:py/str "ValueError"})
                         (py/str (data/str-concat "recursion limit must be "
                                                  "greater or equal than 1")))
           (if (<= (py/num n) (py/abs-depth (cell/get (py/rt-ctx))))
             (py/raise-new (py.sp/class {:py/str "RecursionError"})
                           (py/str (data/str-concat
                                     "cannot set the recursion limit: "
                                     "the limit is too low")))
             (do (cell/set! (py/rt-limit) (py/num n)) :py/None)))
         (py/type-error {:py/str "an integer is required"})))]
    ;; ------------------------------------------------------------ tracing
    ;; The trace state is one cell, {:global :profile :busy}: the functions
    ;; sys.settrace and sys.setprofile installed (None when unset), and a
    ;; flag that is true while one runs, so trace code is never traced.
    ;; A traced frame is a record {:name :line :obj :back :local :d} in the
    ;; dynamic-context record's :frame, so an escape restores the frame
    ;; with the depth: :obj is the Python-visible frame object (f_code,
    ;; f_lineno, f_back), :back the caller's record, :local the frame's
    ;; local trace function (None: none), :d the absolute depth the frame
    ;; was opened at, which tells the frame of the running function from
    ;; one a caller opened. Frames are opened only while a trace or profile
    ;; function is set, so a function entered before settrace has none.
    [py.sp/trace-state
     (fn []
       (cell/new (assoc (assoc (assoc {} :global :py/None) :profile :py/None)
                        :busy false)))]
    [py.sp/trace-on?
     (fn []
       (let [t (cell/get py.sp/trace)]
         (if (get t :busy) false (not (= (get t :global) :py/None)))))]
    [py.sp/profile-on?
     (fn []
       (let [t (cell/get py.sp/trace)]
         (if (get t :busy) false (not (= (get t :profile) :py/None)))))]
    [py.sp/fire
     ;; f(frame, event, arg) with tracing suppressed; its answer is the
     ;; value. A raise out of it clears both functions, as CPython does,
     ;; and propagates
     (fn [f frame ev arg]
       (let [res (cell/new :py/None)]
         (do (cell/set! py.sp/trace (assoc (cell/get py.sp/trace) :busy true))
             (py/try
               (fn []
                 (cell/set! res (py/call f (py/vconj (py/vconj (py/vconj [] frame)
                                                               (py/str ev))
                                                     arg))))
               (fn [e]
                 (do (cell/set! py.sp/trace
                                (assoc (assoc (assoc (cell/get py.sp/trace)
                                                     :global :py/None)
                                              :profile :py/None)
                                       :busy false))
                     (py/raise e)))
               (fn []
                 (do (cell/set! py.sp/trace
                                (assoc (cell/get py.sp/trace) :busy false))
                     (cell/get res)))))))]
    [py.sp/frame-new
     (fn [name line back]
       (let [code (py/module-new (py/str "code") :py/None)
             obj (py/module-new (py/str "frame") :py/None)]
         (do (py/dict-set (py/module-dict code) (py/str "co_name") (py/str name))
             (py/dict-set (py/module-dict obj) (py/str "f_code") code)
             (py/dict-set (py/module-dict obj) (py/str "f_lineno") line)
             (py/dict-set (py/module-dict obj) (py/str "f_back")
                          (if (nil? back) :py/None (get back :obj)))
             obj)))]
    [py.sp/set-local!
     ;; the frame record the context holds now, with local trace function f
     (fn [f]
       (let [cell (py/rt-ctx)
             c (cell/get cell)]
         (cell/set! cell (assoc c :frame (assoc (get c :frame) :local f)))))]
    [py.sp/open-frame
     (fn [name line]
       (let [cell (py/rt-ctx)
             c (cell/get cell)
             back (get c :frame)
             obj (py.sp/frame-new name line back)
             fr (assoc (assoc (assoc (assoc (assoc (assoc {} :name name) :line line)
                                            :obj obj)
                                     :back back)
                              :local :py/None)
                       :d (py/abs-depth c))]
         (do (cell/set! cell (assoc c :frame fr))
             (if (py.sp/trace-on?)
               (py.sp/set-local!
                 (py.sp/fire (get (cell/get py.sp/trace) :global) obj "call"
                             :py/None))
               :py/None)
             (if (py.sp/profile-on?)
               (do (py.sp/fire (get (cell/get py.sp/trace) :profile) obj "call"
                               :py/None)
                   :py/None)
               :py/None))))]
    [py.sp/tcall
     (fn [name line]
       (do (py.sp/poll)
           (py.sp/enter)
           (if (py.sp/trace-on?)
             (py.sp/open-frame name line)
             (if (py.sp/profile-on?) (py.sp/open-frame name line) :py/None))))]
    [py.sp/own-frame
     ;; the running function's frame record, or nil
     (fn []
       (let [c (cell/get (py/rt-ctx))
             fr (get c :frame)]
         (if (nil? fr)
           nil
           (if (= (get fr :d) (py/abs-depth c)) fr nil))))]
    [py.sp/tline
     (fn [line]
       (do (py.sp/loop)
           (let [fr (py.sp/own-frame)]
             (if (nil? fr)
               :py/None
               (if (py.sp/trace-on?)
                 (do (py/dict-set (py/module-dict (get fr :obj))
                                  (py/str "f_lineno") line)
                     (if (= (get fr :local) :py/None)
                       :py/None
                       (py.sp/set-local!
                         (py.sp/fire (get fr :local) (get fr :obj) "line"
                                     :py/None))))
                 :py/None)))))]
    [py.sp/treturn
     (fn [v]
       (let [fr (py.sp/own-frame)]
         (do (if (nil? fr)
               :py/None
               (do (if (py.sp/trace-on?)
                     (if (= (get fr :local) :py/None)
                       :py/None
                       (py.sp/fire (get fr :local) (get fr :obj) "return" v))
                     :py/None)
                   (if (py.sp/profile-on?)
                     (py.sp/fire (get (cell/get py.sp/trace) :profile)
                                 (get fr :obj) "return" v)
                     :py/None)
                   (let [cell (py/rt-ctx)]
                     (cell/set! cell (assoc (cell/get cell) :frame (get fr :back))))))
             (py.sp/return v))))]
    [py.sp/settrace
     (fn [f tracing?]
       (if tracing?
         (do (cell/set! py.sp/trace (assoc (cell/get py.sp/trace) :global f))
             :py/None)
         (py/raise-new
           (py.sp/class {:py/str "NotImplementedError"})
           {:py/str "sys.settrace is not supported without a tracing profile"})))]
    [py.sp/setprofile
     (fn [f tracing?]
       (if tracing?
         (do (cell/set! py.sp/trace (assoc (cell/get py.sp/trace) :profile f))
             :py/None)
         (py/raise-new
           (py.sp/class {:py/str "NotImplementedError"})
           {:py/str "sys.setprofile is not supported without a tracing profile"})))]
    [py.sp/install-sys!
     ;; the composition's call, after the base prelude has run: puts
     ;; settrace, setprofile, gettrace and getprofile in the sys module.
     ;; With `tracing?` false the setters raise NotImplementedError; a
     ;; composition that never calls this leaves sys without them
     (fn [tracing?]
       (let [d (py/module-dict (py/modules-ref (py/str "sys")))]
         (do (py/dict-set d (py/str "settrace")
                          (py/make-function "settrace"
                                            {:params ["function"], :no-kw true} [] []
                                            (fn [args]
                                              (py.sp/settrace (py/arg args 0) tracing?))))
             (py/dict-set d (py/str "setprofile")
                          (py/make-function "setprofile"
                                            {:params ["function"], :no-kw true} [] []
                                            (fn [args]
                                              (py.sp/setprofile (py/arg args 0) tracing?))))
             (py/dict-set d (py/str "gettrace")
                          (py/make-function "gettrace" {:params [], :no-kw true} [] []
                                            (fn [args]
                                              (get (cell/get py.sp/trace) :global))))
             (py/dict-set d (py/str "getprofile")
                          (py/make-function "getprofile" {:params [], :no-kw true} [] []
                                            (fn [args]
                                              (get (cell/get py.sp/trace) :profile))))
             :py/None)))]
    [py.sp/attach!
     ;; the linked wrapper's call: a module closure cannot read the task's
     ;; ambient signal stream, so the wrapper passes its reference, and the
     ;; cells and the cursor it allocates are runtime state, never
     ;; install-time state. Idempotent, like `py/init!`
     (fn [signals]
       (if (= py.sp/cursor :py/uninit)
         (do (yin/def (quote py.sp/handler) (cell/new :py/None))
             (yin/def (quote py.sp/cursor) (stream/cursor signals))
             (yin/def (quote py.sp/trace) (py.sp/trace-state))
             :py/None)
         :py/None))]])


(def ^:private state-definitions
  "The bundled profile's runtime state, allocated while loading: the
   handler cell, the cursor and the trace cell, nothing else."
  '[[py.sp/handler (cell/new :py/None)]
    [py.sp/cursor (stream/cursor py.sp/signals)]
    [py.sp/trace (py.sp/trace-state)]])


(defn- definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(def uast
  "The bundled hook prelude: functions, then its cells and the signal cursor."
  (u/mark-tails (u/then (definitions->uast function-definitions)
                        (definitions->uast state-definitions))))


(def noop-uast
  "Hooks that do nothing: the derived program under them is the naive one."
  (u/mark-tails (definitions->uast '[[py.sp/loop (fn [] :py/None)]
                                     [py.sp/call (fn [] :py/None)]
                                     [py.sp/return (fn [v] v)]])))


(defn program
  "The bundled derived run's tree: `hooks` (a hook prelude), then the derived
   program `ast`."
  [hooks ast]
  (u/then hooks ast))


(defn linked-program
  "The linked safepointed run's tree: `py` required and initialized, `pysp`
   required, the signal stream the composition bound under `signals-key`
   attached, then the derived linked program `a'`, whose own require and
   init are idempotent re-entries. The load order is the linked one, the
   inverse of the bundled hook-prelude-first order."
  [a']
  (u/mark-tails
    (u/seq-nodes
      [(u/sexp->uast (list 'require (list 'quote prelude/module-name)))
       (u/sexp->uast (list 'require (list 'quote module-name)))
       (u/sexp->uast '(py/init!))
       (u/app (u/v (symbol (name module-name) "attach!")) (u/v signals-key))
       a'])))


;; =============================================================================
;; The module emitter: the same definition list as the linker module `pysp`
;; =============================================================================

(def runtime-keys
  "Every key `pysp/attach!` defines, sorted: the handler cell, the
   signal cursor and the trace cell. Each is a runtime key of the module, held at install
   time by a `:py/uninit` placeholder so the linker's scanners discharge
   the bodies that read it."
  '[py.sp/handler py.sp/cursor py.sp/trace])


(defn strip
  "`sym` without the module's own namespace `py.sp`; any other value as it
   is. `py/*` stays qualified: those are the base prelude's exports,
   resolved through the registry once `py` is linked, and stripping them
   would collide with this module's own bare keys."
  [sym]
  (if (and (symbol? sym) (= "py.sp" (namespace sym)))
    (symbol (name sym))
    sym))


(defn- strip-form
  "A prelude-notation form with every symbol `strip`ped: references,
   definition keys and quoted keys alike. Keywords, strings and the
   keys of literal maps are untouched."
  [form]
  (walk/postwalk strip form))


(def ^:private module-definitions
  "The module's definition list: a `:py/uninit` placeholder for every
   runtime key, so each read of one is discharged by an unconditional
   module-level definition, then every definition, stripped."
  (into (mapv (fn [k] [(strip k) :py/uninit]) runtime-keys)
        (map (fn [[k form]] [(strip k) (strip-form form)]))
        function-definitions))


(def module-uast
  "The module tree: one application whose operands define hooks, then
   deliver the pinned base prelude, applied to a lambda returning nil.
   Every definition precedes every call, including require, so the
   linker's scanners discharge sibling and runtime-key reads. A `then`
   chain would nest definitions in closure bodies. Defining runs nothing;
   delivery never initializes either runtime."
  (let [nodes (conj (mapv (fn [[k form]] (u/def! k (u/sexp->uast form)))
                          module-definitions)
                    (u/sexp->uast '(require (quote py))))
        params (mapv (fn [i] (symbol (str "%d" i))) (range (count nodes)))]
    (u/mark-tails (apply u/app (u/lam params (u/lit nil)) nodes))))


(def module-exports
  "The module's exports: every `py.sp/` key of the definition list,
   stripped. The two runtime keys are internal."
  (set (keep (fn [[k _]] (when (= "py.sp" (namespace k)) (strip k)))
             function-definitions)))


(def ^:private module-free-names
  "The names the module tree reads and does not define, sorted: the
   primitives, the host exports and the base prelude's `py/*` exports its
   manifest declares."
  (vec (sort-by str
                (remove (conj (prelude/defined-keys module-uast) 'yin/def)
                        (prelude/free-names module-uast)))))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for the module `pysp`
   over the host registry `registry`, requiring the base prelude `py` at
   manifest address `py-address`. A free name of the module tree is
   declared one of three ways: a name qualified by `py` is covered by the
   requirement, discharged by a linked `py` of equal manifest address; a
   bare name is a primitive declared by its `vm/primitives` profile; a
   `ns/name` is a host export declared by the profile address the
   registry's host module `ns` publishes and the effect set its
   `:callable-effects` index holds for the export's function. A free name
   none of these supplies is refused before anything is published. This
   namespace emits; it never publishes."
  [registry py-address]
  {:name module-name,
   :ast module-uast,
   :exports module-exports,
   :requires {prelude/module-name py-address},
   :primitives
   (into {}
         (keep (fn [n]
                 (cond
                   ;; the requirement declares these; `into` skips the nil.
                   ;; `module-name` is unqualified, so its name is the
                   ;; namespace the requirement covers
                   (= (name prelude/module-name) (namespace n)) nil
                   :else (if-let [profile (vm/profile-of vm/primitives n)]
                           [n profile]
                           (if-let [address (module/host-export-profile registry n)]
                             (let [entry (module/module-entry (:modules registry)
                                                              (symbol (namespace n)))
                                   f (get-in entry [:slice (symbol (name n))])]
                               [n {:yin.k/profile address,
                                   :yin.k/effects
                                   (get (:callable-effects registry) f #{})}])
                             (throw (ex-info
                                      "the pysp module reads an undeclared name"
                                      {:yang.python.antlr/refusal
                                       :yang.python.antlr/undeclared-free,
                                       :name n})))))))
         module-free-names)})

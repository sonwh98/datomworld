(ns yang.typescript.antlr.safepoint
  "The `jssp` safepoint hook prelude (docs/design/yang.typescript.md
   section 13; T1 plan section 8): `js.sp/loop` and `js.sp/call` for the
   bundled profile and the linked `jssp` module. Universal AST, like the
   base prelude; the `yang.safepoint` stage only places `(js.sp/loop)` and
   `(js.sp/call)` at the `:loop` and `:call` sites the lowering marks, and
   what they mean is written here.

   Signals. The composition hands the task a signal stream, a sealed
   reference in the store under `signals-key`; a host adapter appends one
   plain value per delivered signal. Each hook reads it with `stream/poll!`,
   which answers `:dao.stream/blocked` rather than parking, so a program
   with no signal pending never waits at a safepoint. On a signal (or a
   gap: values were lost, so at least one arrived) the hook delivers an
   interrupt: it unwinds the handler stack to its outermost frame, the one
   `js/run-main` installs, running every `finally` thunk on the way, and
   throws the guest value `js.sp/interrupt` builds (an Error object named
   `Interrupt`) through `js/throw` from there. No guest `catch` frame is
   left to see it, so a `try`/`catch` around the loop does not catch it;
   the run ends with it uncaught. Delivery is not journalled.

   There is no depth accounting in T1: JavaScript has no RecursionError by
   default (section 13), so `js.sp/call` is a poll only, and there is no
   `:return` hook.

   Two profiles, one source. `uast` is the bundled hook prelude: it loads
   before the base prelude and may not touch a base definition while
   loading, so it allocates only the signal cursor, and the hooks name base
   keys only inside their bodies. `module-uast` is the same definition list
   as the linker module `jssp`, which requires `js`, inverting the bundled
   load order: its install child defines lambdas only, for a cursor is
   runtime state a lift refuses, and a module closure cannot read the
   task's ambient signal stream; the linked wrapper passes the stream's
   reference to `(jssp/attach! signals)`, which mints the cursor. The hooks
   reach the base prelude through its exports (`js/unwind-to`, `js/throw`,
   `js/global-get`, ...), for `js.rt/*` is an internal store key invisible
   across a module boundary. Every key this prelude defines is in `js.sp`."
  (:require
    [yang.core.prelude :as core]
    [yang.core.uast :as u]
    [yang.typescript.antlr.prelude :as prelude]))


(def profile
  "The `yang.safepoint` profile this prelude implements under the bundled
   prelude: site kind -> hook."
  {:loop 'js.sp/loop, :call 'js.sp/call})


(def linked-profile
  "The `yang.safepoint` profile under the linked prelude: the hooks are the
   `jssp` module's exports, so `A'` applies `jssp/loop` and its sibling and
   a run without the module fails closed on the unresolved hook name."
  {:loop 'jssp/loop, :call 'jssp/call})


(def signals-key
  "The store key the composition binds the signal stream's reference to."
  'js.sp/signals)


(def module-name
  "The linker module the hook prelude is published as."
  'jssp)


(def host-names
  "Module exports this prelude calls: the stream module's `cursor` and
   `poll!`."
  '#{stream/cursor stream/poll!})


(def function-definitions
  "The single definition list, `[store-key form]` in definition order: every
   hook function, then `attach!`. Defining them runs nothing. Both emitters
   read it; the tests assert the module's shape against it."
  '[[js.sp/interrupt
     ;; the value an interrupt throws: an Error named Interrupt, built from
     ;; the exported intrinsics (the error prototype is `Error.prototype`)
     (fn []
       (let [e (js/make-error
                 (js/get-prop (js/global-get {:js/str "Error"})
                              {:js/str "prototype"})
                 {:js/str "interrupted"})]
         (do (js/define-own! e
                             {:js/str "name"}
                             (js/slot {:js/str "Interrupt"} true false true))
             e)))]
    [js.sp/deliver
     ;; down to the outermost handler frame (the run's own), then throw:
     ;; the guest handlers above it never see the interrupt
     (fn [sig] (do (js/unwind-to 1) (js/throw (js.sp/interrupt))))]
    [js.sp/poll
     (fn []
       (let [s (stream/poll! js.sp/cursor)]
         (if (= s :dao.stream/blocked)
           :js/undefined
           (if (nil? s) :js/undefined (js.sp/deliver s)))))]
    [js.sp/loop (fn [] (js.sp/poll))]
    [js.sp/call (fn [] (js.sp/poll))]
    [js.sp/attach!
     ;; the linked wrapper's call: a module closure cannot read the task's
     ;; ambient signal stream, so the wrapper passes its reference, and the
     ;; cursor it mints is runtime state, never install-time state.
     ;; Idempotent, like `js/init!`
     (fn [signals]
       (if (= js.sp/cursor :js/uninit)
         (do (yin/def (quote js.sp/cursor) (stream/cursor signals))
             :js/undefined)
         :js/undefined))]])


(def ^:private state-definitions
  "The bundled profile's runtime state, allocated while loading: the signal
   cursor, nothing else."
  '[[js.sp/cursor (stream/cursor js.sp/signals)]])


(defn- definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(def uast
  "The bundled hook prelude: functions, then the signal cursor."
  (u/mark-tails (u/then (definitions->uast function-definitions)
                        (definitions->uast state-definitions))))


(def noop-uast
  "Hooks that do nothing: the derived program under them is the naive one."
  (u/mark-tails (definitions->uast '[[js.sp/loop (fn [] :js/undefined)]
                                     [js.sp/call (fn [] :js/undefined)]])))


(defn program
  "The bundled derived run's tree: `hooks` (a hook prelude), then the derived
   program `ast`."
  [hooks ast]
  (u/then hooks ast))


(defn linked-program
  "The linked safepointed run's tree: `js` required and initialized, `jssp`
   required, the signal stream the composition bound under `signals-key`
   attached, then the derived linked program `a'`, whose own require and
   init are idempotent re-entries. The load order is the linked one, the
   inverse of the bundled hook-prelude-first order."
  [a']
  (u/mark-tails
    (u/seq-nodes
      [(u/sexp->uast (list 'require (list 'quote prelude/module-name)))
       (u/sexp->uast (list 'require (list 'quote module-name)))
       (u/sexp->uast '(js/init!))
       (u/app (u/v (symbol (name module-name) "attach!")) (u/v signals-key))
       a'])))


;; =============================================================================
;; The module emitter: the same definition list as the linker module `jssp`
;; =============================================================================

(def runtime-keys
  "Every key `jssp/attach!` defines, sorted: the signal cursor. It is held
   at install time by a `:js/uninit` placeholder so the linker's scanners
   discharge the bodies that read it."
  '[js.sp/cursor])


(defn strip
  "`sym` without the module's own namespace `js.sp`; any other value as it
   is. `js/*` stays qualified: those are the base prelude's exports,
   resolved through the registry once `js` is linked, and stripping them
   would collide with this module's own bare keys."
  [sym]
  (core/strip "js.sp" sym))


(def ^:private module-definitions
  "The module's definition list: a `:js/uninit` placeholder for every
   runtime key, so each read of one is discharged by an unconditional
   module-level definition, then every definition, stripped."
  (core/module-definitions "js.sp" :js/uninit runtime-keys function-definitions))


(def module-uast
  "The module tree: one application whose operands define hooks, then
   deliver the pinned base prelude, applied to a lambda returning nil.
   Every definition precedes every call, including require, so the linker's
   scanners discharge sibling and runtime-key reads. Defining runs nothing;
   delivery never initializes either runtime."
  (core/module-tree module-definitions [(u/sexp->uast '(require (quote js)))]))


(def module-exports
  "The module's exports: every `js.sp/` key of the definition list,
   stripped. The cursor is internal."
  (core/exports "js.sp" function-definitions))


(def ^:private module-free-names
  "The names the module tree reads and does not define, sorted: the
   primitives, the host exports and the base prelude's `js/*` exports its
   manifest declares."
  (core/free-name-vec module-uast))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for the module `jssp` over
   the host registry `registry`, requiring the base prelude `js` at manifest
   address `js-address`. A free name of the module tree is declared one of
   three ways: a name qualified by `js` is covered by the requirement,
   discharged by a linked `js` of equal manifest address; a bare name is a
   primitive declared by its `vm/primitives` profile; a `ns/name` is a host
   export declared by the profile address the registry's host module `ns`
   publishes. A free name none of these supplies is refused before anything
   is published. This namespace emits; it never publishes."
  [registry js-address]
  (core/module-spec {:module-name module-name,
                     :ast module-uast,
                     :exports module-exports,
                     :requires {prelude/module-name js-address},
                     :free-names module-free-names,
                     ;; the requirement declares names qualified by `js`
                     :skip? (fn [n] (= (name prelude/module-name) (namespace n))),
                     :registry registry,
                     :message "the jssp module reads an undeclared name",
                     :refusal-ns "yang.typescript.antlr"}))

(ns yang.clojure.prelude
  "The `clj` runtime module (docs/design/yang.antlr.md section 8.10):
   the published linker module every linked clojure program requires, as
   `js`, `php` and `py` are for their frontends. It is written in prelude
   notation and emitted with the same core machinery those preludes use;
   it never runs on the host.

   Three surfaces, mirroring the other runtimes:

   - the entry, `clj/run-main`: initialize the runtime state, run the
     unit — the thunk `yang.clojure/lower-unit` lowered — and answer the
     program's value with the outcome model's `[:yin.k/ok value]`
     wrapper removed, since that wrapper is frontend-internal. The
     entry does not reset the global namespace: the namespace lives for
     the task, so a projection the composition bound between two
     entries and a definition an earlier unit published both survive
     into the next unit's run (as `js/run-main` leaves `globalThis`
     standing);
   - the runtime global namespace, `clj/define-global!` and
     `clj/global-get`: a task-local map in a cell (`clj.rt/globals`,
     allocated once per task by `clj/init!`), keyed by export name. A
     program's top-level definitions land here through its unit's own
     publishing, and the conformance helper's export projection reads
     and writes it — the one binding a linked unit reads a projected or
     defined name through. A definition a unit runs publishes after
     any projection of the same name and shadows it. The JS
     `globalThis` table, the PHP named-function table and the Python
     module dictionary are the same seam;
   - admission, `admit`: the registry surface a linked clojure composition
     needs beside the others — the `kind` module and the `portable`
     adapters of the data exports the guest library's collection
     predicates read.

   The module pins the shared calling runtime (`yang.call`) at the
   convention's call-address, as the other runtimes do, and refuses before
   anything is published when a free name nothing supplies is read."
  (:require [yang.core.prelude :as core]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]))


(def module-name
  "The linker module name of the clojure runtime."
  'clj)


(def runtime-keys
  "Every runtime key, each with an `:clj/uninit` placeholder at module
   level so every read is discharged before `clj/init!` allocates the
   real cell. They stay qualified: internal store keys, never guest
   names."
  '[clj.rt/state clj.rt/globals])


(def definitions
  "The module's definition list, in prelude notation (the module's own
   `clj/` prefix is stripped on emit)."
  '[;; ------------------------------------------------- state and namespace
    [clj/init!
     (fn []
       (if (= clj.rt/state :clj/uninit)
         (do (yin/def (quote clj.rt/globals) (cell/new {}))
             (yin/def (quote clj.rt/state) :clj/ready))
         :clj/ready))]
    ;; the runtime global namespace: one task-local map in a cell, keyed by
    ;; export name. `clj/define-global!` answers the value, so a publish
    ;; sequence can chain through it.
    [clj/define-global!
     (fn [name value]
       (do (cell/set! clj.rt/globals
                      (assoc (cell/get clj.rt/globals) name value))
           value))]
    [clj/global-get
     (fn [name]
       (if (data/contains? (cell/get clj.rt/globals) name)
         (get (cell/get clj.rt/globals) name)
         :clj/unbound))]

    ;; ------------------------------------------------------------- entry
    ;; The outcome model's `[:yin.k/ok value]` wrapper is frontend-
    ;; internal, so the entry removes one wrapper level from the
    ;; program's value (a program returning that vector as data was
    ;; wrapped again, so one level is exact). An error outcome passes
    ;; through as the failure value for the caller to render.
    [clj/unwrap-outcome
     (fn [value]
       (if (if (data/vector? value) (= (data/count value) 2) false)
         (if (= (data/nth value 0) :yin.k/ok)
           (data/nth value 1)
           value)
         value))]
    ;; The global namespace is the task's, not the entry's: no reset
    ;; here, so projections and earlier units' definitions survive.
    [clj/run-main
     (fn [body]
       (do (clj/init!)
           (clj/unwrap-outcome (body))))]])


(def module-definitions
  "The module's definition list: an `:clj/uninit` placeholder for every
   runtime key, then every definition, stripped."
  (core/module-definitions "clj" :clj/uninit runtime-keys definitions))


(def module-uast
  "The module tree: one wide application over the definitions, so every
   sibling and runtime key read is discharged at module level. C1
   finalized, like every production `yang.clojure` tree."
  (yang/annotate-call-sites (core/module-tree module-definitions [])))


(def module-exports
  "The module's exports: every `clj/` key of the definition list, stripped."
  (core/exports "clj" definitions))


(def ^:private module-free-names
  "The names the module tree reads and does not define, sorted: the host
   exports its manifest must declare."
  (core/free-name-vec module-uast))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for the module `clj` over
   the host registry `registry`, pinned to the published `yang.call`
   module at `call-address` — the convention's call-address, as every
   runtime's publication carries. A free name nothing supplies is refused
   before anything is published. This namespace emits; it never publishes."
  [registry call-address]
  (core/module-spec {:module-name module-name,
                     :ast module-uast,
                     :exports module-exports,
                     :requires {'yang.call call-address},
                     :free-names module-free-names,
                     :skip? #(= "yang.call" (namespace %)),
                     :registry registry,
                     :message "the clj module reads an undeclared name",
                     :refusal-ns "yang.clojure"}))


(defn register-library-module
  "Return `registry` with host module `name` installed over `entries`
   (`[export function]` pairs) at `arities`, each export pure."
  [registry name entries arities]
  (module/register-host-module
    registry name
    (into {} entries)
    (into {} (map (fn [[op _]] [op (vm/primitive-profile op :pure (get arities op [1]) #{} :none)]))
          entries)))


(defn admit
  "Return `registry` with the guest library's host surface installed: the
   `kind` module and the `portable` adapters of the data exports the
   collection predicates read (yang.clojure's `library-adapters`). A
   linked clojure composition registers them beside the other runtimes'
   registry surfaces; a composition without a clojure program reads none
   of them."
  [registry]
  (let [portable (into {}
                       (map (fn [[source _]]
                              (let [op (symbol (name source))]
                                [op (yang/outcome-adapter data/refusal-message
                                                          (get data/data-module op))])))
                       yang/library-adapters)]
    (-> registry
        (register-library-module 'kind yang/kind-module yang/kind-arities)
        (register-library-module
          'portable portable
          (into {} (map (fn [op] [op (:yin.k/arities (get data/data-profiles op))]))
                (keys portable))))))

# defmacro - design spec

## Summary

`defmacro` is implemented as a split between two layers:

- **yin.vm** owns the canonical macro model: what a macro *is* (`:yin/macro? true` lambda), how macro bodies execute, expansion provenance, and guard limits. This is language-agnostic.
- **yang.clojure** owns `defmacro` syntax recognition and macro call-site lowering: it knows, in sequential lexical order, which names are macros, and emits `:yin/macro-expand` nodes directly. There is no global datom-stream scan.

Non-negotiable invariant:

- **User-defined macros are executed by `yin.vm` during expansion.**
- **Host `macro-registry` is bootstrap-only** for core seed macros such as `defmacro`. It is not the steady-state execution path for user-authored macros.

This split is necessary because:

- Whole-stream discovery (scanning all `yin/def` applications for macro lambdas) loses causality: a later `defmacro` can appear to affect earlier forms, local bindings become ambiguous, and dead-branch defs become globally visible. This violates "explicit causality > implicit assumptions."
- Application-node mutation via last-write-wins overrides conflicts with the provenance model (`docs/macros.md`): original call datoms must stay unchanged.
- Homoiconicity lives at the `yin.vm` layer: macros ultimately generate AST datoms, so the canonical execution model belongs in `yin.vm`, not in host Clojure callbacks.

## Clojure analogy

```clojure
;; Clojure:
(defmacro my-macro [x]
  `(println ~x))

;; compiles to:
(def ^:macro my-macro
  (fn [&form &env x]
    (list 'println x)))
```

```clojure
;; yin.vm:
(defmacro my-macro [x] body)

;; lowers to datoms for:
(def my-macro (lambda [x] body))   ; lambda carries :yin/macro? true
```

`defmacro` is a special form in `yang.clojure` (like `def` and `fn`), not a macro. Recognizing macro call-sites requires ordered compile-time state that a pure datom-stream pre-pass cannot provide safely.

The key difference from the earlier draft is execution: once lowered, the macro lambda body is executed by `yin.vm` itself during expansion. No parallel host-language function is required for user macros.

## Architecture

```text
yang source
  -> yang.clojure compiler
       compile-time macro-env: {'my-mac <lambda-eid>}
       (defmacro my-mac ...) -> emit def + lambda (with :yin/macro? true)
                             -> add my-mac to macro-env
       (my-mac arg)          -> emit :yin/macro-expand (fresh EID, not a rewrite)
  -> datom stream
  -> yin.vm macro expander (expand-all)
       bootstrap macro?      -> dispatch through default-macro-registry
       user-defined macro?   -> execute macro lambda body in yin.vm
       macro inputs          -> AST entity refs + explicit expansion context
       macro result          -> {:datoms [...], :root-eid eid}
       provenance            -> :macro-expand-event, m = event-eid on outputs
       original call node    -> untouched
  -> expanded datom stream
  -> register/stack consume expanded stream
  -> semantic VM can execute expanded stream directly
```

Ownership is explicit:

- `yang.clojure` decides whether a source call-site is a macro invocation.
- `yin.vm` decides how macro execution works.
- The expander, not the macro body, owns provenance datoms and guard enforcement.

## Macro Execution Contract

User-defined macro lambdas are executed inside `yin.vm`, not in host Clojure.

Execution contract:

- Macro parameters are bound to **AST entity refs**, not evaluated runtime values.
- The macro receives explicit expansion context for structural introspection and node allocation.
- The macro returns **structural expansion output** in the shape `{:datoms [...], :root-eid eid}`.
- The expander validates that output, appends the `:macro-expand-event`, and sets `m` on emitted datoms.

Bootstrap macros can continue using the existing host-side function shape, but that is an implementation seed, not the general model:

```clojure
(defn invoke-macro
  [ctx macro-eid macro-registry]
  (if-let [bootstrap-fn (get macro-registry macro-eid)]
    (bootstrap-fn ctx)
    (invoke-macro-lambda ctx macro-eid)))
```

`invoke-macro-lambda` is the canonical path. It evaluates the macro lambda against the current bounded datom stream using `yin.vm` semantics and returns expansion datoms.

## Non-Lisp frontends

Non-homoiconic languages (`yang.python`, `yang.js`) should not write macros in their native syntax. Instead they:

1. Discover macros by querying DaoDB: find lambdas with `:yin/macro? true`, inspect `:yin/params` and `:yin/phase-policy`.
2. Emit `:yin/macro-expand` nodes explicitly, passing their own AST entity refs as arguments.
3. Let `yin.vm` execute the macro lambda body and append expansion provenance.

Macros are a queryable service in DaoDB. Lisp uses them transparently via the `yang.clojure` compile-time env; non-Lisp uses them via explicit AST-level calls.

## Files to Modify

| File | Change |
|------|--------|
| `src/cljc/yin/vm/macro.cljc` | Add `defmacro-fn`, `defmacro-eid`, `default-macro-registry`, VM-native macro invocation, and expansion-output validation |
| `src/cljc/yang/clojure.cljc` | Add `defmacro` to `special-form?`; add `compile-defmacro`; thread ordered `macro-env` through `compile-program` and `compile-form` |
| `src/cljc/yin/vm.cljc` | Emit macro metadata on lambda nodes and support compiler-provided EIDs / direct operator refs for `:yin/macro-expand` |
| `test/yin/vm/macro_test.cljc` | Tests for bootstrap `defmacro`, ordered lowering, VM-native execution of user macros, and provenance |

No register-specific or stack-specific macro semantics are introduced. Those VMs consume the already-expanded bounded stream.

## Implementation

### 1. `yin.vm.macro` - bootstrap plus VM-native execution

Add bootstrap definitions in `src/cljc/yin/vm/macro.cljc`:

```clojure
(def defmacro-eid
  "Reserved bootstrap EID for the defmacro macro lambda."
  -1)

(def defmacro-fn
  "Bootstrap macro function for defmacro.
   Receives arg-eids = [name-eid params-eid body-eid].
   Produces datoms for: (def name (lambda params body))
   with :yin/macro? true."
  (fn [{:keys [arg-eids get-attr fresh-eid]}]
    (let [macro-name (get-attr (nth arg-eids 0) :yin/value)
          params     (get-attr (nth arg-eids 1) :yin/value)
          body-eid   (nth arg-eids 2)
          lambda-eid (fresh-eid)
          def-eid    (fresh-eid)
          key-eid    (fresh-eid)
          op-eid     (fresh-eid)]
      {:datoms
       [[lambda-eid :yin/type         :lambda      0 0]
        [lambda-eid :yin/macro?       true         0 0]
        [lambda-eid :yin/phase-policy :compile     0 0]
        [lambda-eid :yin/params       params       0 0]
        [lambda-eid :yin/body         body-eid     0 0]
        [op-eid     :yin/type         :variable    0 0]
        [op-eid     :yin/name         'yin/def     0 0]
        [key-eid    :yin/type         :literal     0 0]
        [key-eid    :yin/value        macro-name   0 0]
        [def-eid    :yin/type         :application 0 0]
        [def-eid    :yin/operator     op-eid       0 0]
        [def-eid    :yin/operands     [key-eid lambda-eid] 0 0]]
       :root-eid def-eid})))

(def default-macro-registry
  "Bootstrap macro-registry. Merge with user registries in create-vm opts."
  {defmacro-eid defmacro-fn})
```

Then extend macro invocation:

- If `macro-registry` contains a host function for the operator EID, call it. This path is only for bootstrap macros.
- Otherwise, if the operator refers to a lambda with `:yin/macro? true`, execute that lambda body inside `yin.vm`.
- Validate that the result is `{:datoms [...], :root-eid eid}` before adding provenance.

This makes the execution model explicit: user-defined macros do not need a host-language mirror.

### 2. `yang.clojure` - `defmacro` special form and ordered lowering

Add `defmacro` to `special-form?`:

```clojure
(contains? #{'fn 'if 'let 'quote 'do 'def 'dao.stream.apply/call 'defmacro} (first form))
```

Add `compile-defmacro`:

```clojure
(defn compile-defmacro
  "Lower (defmacro name params body) to:
   (def name (lambda params body))
   where the lambda carries :macro? true."
  [name params body env tail? lambda-eid]
  (compile-def name
               (-> (compile-lambda params body env false)
                   (assoc :macro? true
                          :phase-policy :compile
                          :eid lambda-eid))
               env tail?))
```

Thread ordered compiler state through `compile-program` and `compile-form`:

- `macro-env`: `{symbol -> lambda-eid}`
- `next-eid`: compiler-assigned tempids for macro lambdas and explicit `:yin/macro-expand` nodes

Key invariant:

- A macro name is visible only to forms that appear **after** its `defmacro`.

Known macro call-sites lower directly to `:yin/macro-expand`:

```clojure
{:type         :yin/macro-expand
 :eid          call-eid
 :operator-eid (get macro-env operator)
 :operands     (mapv #(compile-form % false env compiler-state) operands)}
```

There is no post-hoc rewrite step. `yang.clojure` emits the correct node shape up front.

### 3. `yin.vm` AST emission support

Update `ast->datoms` in `src/cljc/yin/vm.cljc` so it respects compiler-provided node IDs:

```clojure
(let [e (or (:eid node) (gen-id))]
  ...)
```

Extend the `:lambda` case to emit macro metadata:

```clojure
:lambda
(do (emit! e :yin/type :lambda)
    (when (:macro? node)
      (emit! e :yin/macro? true)
      (emit! e :yin/phase-policy (or (:phase-policy node) :compile)))
    (emit! e :yin/params (:params node))
    (let [body-id (convert (:body node))]
      (emit! e :yin/body body-id)))
```

Extend the `:yin/macro-expand` case to support direct operator refs:

```clojure
:yin/macro-expand
(do (emit! e :yin/type :yin/macro-expand)
    (let [op-id (or (:operator-eid node)
                    (convert (:operator node)))
          operand-ids (mapv convert (:operands node))]
      (emit! e :yin/operator op-id)
      (emit! e :yin/operands operand-ids)))
```

### 4. Expansion provenance stays in the expander

The macro body should return only structural output. It must **not** create its own expansion-event datoms.

The expander continues to own:

- `:macro-expand-event`
- `:yin/source-call`
- `:yin/macro`
- `:yin/expansion-root`
- `m = event-eid` on expansion output
- depth / datom-count guards

That keeps execution and provenance separate.

## Tests

1. `defmacro-fn` unit: correct datoms for name/params/body arg-eids.
2. `compile-defmacro`: lambda node carries `:macro? true`, `:phase-policy :compile`, and compiler-provided `:eid`.
3. `ast->datoms` with explicit `:eid`: emitted datoms preserve that ID.
4. `compile-program` with `defmacro` then later call: the later call emits `:yin/macro-expand`.
5. Ordering test: a macro name is not visible to forms before its `defmacro`.
6. Bootstrap path test: `defmacro` itself still expands via `default-macro-registry`.
7. VM-native execution test: a macro defined via `defmacro` expands successfully **without** adding a host function for that macro to `macro-registry`.
8. Provenance test: original macro call datoms stay unchanged; expansion outputs carry `m = event-eid`.
9. Backend test: register/stack/semantic all observe the same result after expansion.

## Deferred

- **Quasiquote / syntax-quote in yang**: without this, authoring non-trivial AST-producing macro bodies in yang syntax is still awkward even though the execution model is VM-native.
- **Hygiene / gensym ergonomics**: the initial model can be explicit and non-hygienic, with hygiene improvements added later.
- **Runtime capability enforcement**: keep the `docs/macros.md` capability model, but treat it as separate from `defmacro` itself.

## Verification

```bash
clj -M:test
```

Check:

- `defmacro` lowers correctly
- macro call-sites emit `:yin/macro-expand`
- macro names are not visible before their definition
- user-defined macros execute in `yin.vm` without host-function registration
- provenance datoms are intact on expansion output

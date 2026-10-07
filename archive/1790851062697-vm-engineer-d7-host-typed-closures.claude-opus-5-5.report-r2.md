Completed-GMT: 2026-10-01 11:40:24 GMT
Completed-Local: 2026-10-01 18:40:24 +07
Coding-Agent: claude
Session-ID: a991361e-3716-4327-84f0-1a35a3a5a57b

# D6/D7 slice A: host-typed closures and continuations on all four VMs

Slice A is built on all four VMs. Every check passes except cljstyle, which the permission layer blocked. Nothing is staged or committed, and the worktree is on branch `vm-host-typed-closures`.

**Deviation you must know about: the namespace is `yin.vm.values`, not `yin.vm.value`.** On CLJS, `yin.vm.value` clashes with the `yin.vm/value` protocol method. `bb build:yin-repl-node` raised `:ns-var-clash` ("Namespace yin.vm.value clashes with var yin.vm/value"). After the rename the build has 0 warnings.

## Results

| Check | Outcome |
|---|---|
| clj-kondo, every changed file as a separate argument | 0 errors. 5 warnings, all present on `HEAD`; I linted the `HEAD` copies to confirm. |
| cljstyle check | **Blocked.** Every `cljstyle check` invocation needed approval and was not run. I fixed by hand the one indentation slip I knew of (the walker hot loop). |
| `bb gen:python-antlr` | ok. 4 java files generated. |
| `bb build:yin-repl-node` | ok. 174 files, 0 warnings. |
| Focused JVM: the 13 VM suites plus the new namespace | 244 tests, 3102 assertions, 0 failures. The new namespace alone: 14 tests, 188 assertions, 0 failures. |
| Full `clj -M:test` | **2688 tests, 187682 assertions, 0 failures, 0 errors.** Includes the 6 `yang.python.antlr.*` namespaces. |
| `bb test:cljs` | **2535 tests, 53440 assertions, 0 failures, 0 errors.** "Testing yin.vm.host-typed-values-test" and "Testing yin.vm.data-test" both appear in the output. |
| `bb test:cljd` | Not run, as the brief says. |

## Changed files

**New**
- `src/cljc/yin/vm/values.cljc`
  - `Closure` and `Continuation` deftypes `[owner payload]`, with no `ILookup` and no `IFn`.
  - Structural equality and hash in per-host blocks, `:cljd` first, `[_ other]` parameters.
  - Also: `owner-tag`, the trusted constructors, `closure?`/`continuation?`/`host-typed?`, `payload`, `owner`, `owned-by?`, and `kind-of` (the coarse kind a refusal carries).
- `test/yin/vm/host_typed_values_test.cljc`: the acceptance tests, on all four VMs.

**Engine and model**
- `src/cljc/yin/vm.cljc`
  - `empty-state` derives `:owner` once from the secret.
  - `check-params!` is shared by every kernel and lowering: Rule R plus `:non-symbol-parameter`.
  - New `machine-data?`, explained under concerns.
- `src/cljc/yin/vm/engine.cljc`
  - `operator-kind` is the one classifier. It answers `:host-fn`, `:closure` or `:continuation`, or refuses with `:not-applicable` or `:foreign-value`.
  - `continuation-argument` now refuses with `:continuation-arity`; `reified-continuation?` is removed.
  - Encoder: host types are tested before the `map?` arm. A foreign value refuses as `:yin.k/non-portable :foreign-value`, and a plain `{:type :closure}` map is now a literal.
  - Two-mode `trace` and a moded `gc-roots`.
- `src/cljc/yin/vm/module.cljc`: the docs for the `gc-roots`/`gc-children` contract, which is now moded (below).

**Kernels**
- `ast_walker.cljc`, `semantic.cljc`, `debruijn/stack.cljc`, `debruijn/register.cljc`:
  - construction wraps the payload with `(:owner vm)`;
  - application goes through `engine/operator-kind`;
  - `lower-closure` mints with the receiver's owner;
  - `gc-roots` and `gc-children` are moded.
- The walker gains an `owner` record field.
- The positional VMs now carry `:owner`. Their `create-vm` picks keys out of `empty-state`, so the owner was dropped until the tests caught it.
- The positional refusals now carry `:reason :foreign-format`, alongside the old `:rule :continuation-format`.

**Lowering paths and value checks**
- `linearize.cljc` and `debruijn_resolve.cljc`: the binder check uses `vm/check-params!`.
- `debruijn_register_effects.cljc`: the payload, datom and request gates use `machine-data?`.

**Consumers**
- `completion.cljc`: type tests over the payload.
- `repl.cljc`: printed the payload, never the owner tag. Superseded in Round 2: it now prints an opaque kind marker.
- `data.cljc`: adds `number?` and `callable?`, both `:pure`.
- `yang/python/antlr/prelude.cljc`: only `py/numeric?` changed, now `(data/number? x)` after the bool and float arms.
  - I briefly added `data/number?` to `host-names`, then reverted it: nothing reads that set, and the brief limits this file to `py/numeric?`.
- `datomworld/demo/continuation_handoff.cljc`: the semantic handoff ships registers as EDN. Its tag encoding gains `:closure` and `:continuation` tags, whose decode mints with the receiver's owner (a trusted host boundary, like `lower`). The sender's owner tag is never shipped.

**Docs**
- `docs/ast.md`: the closure structure and its properties, the binder check, the refusal table, the two-mode tracing, and the continuation value.
- `docs/state.md`: a new `:owner` section and the reclamation modes.
- `docs/co-routines.md`: two lines updated.

**Existing tests updated**
These tests read payload fields or hand-lower closures. They now unwrap with `values/payload` or re-mint with the receiver's owner.
- The B0 and parity normalizers unwrap host types, so their frozen normal forms are unchanged.
- `error-normalization-test` now expects `{:reason :not-applicable, :kind :number}` instead of `{:fn 1}`. This is the intended D6 change.
- Also changed: `ast_walker_test`, `attach_image_test`, `completion_test`, `data_test`, `register_test`, `stack_effects_test`, `heap_reclamation_test`, `linker_require_test`, `semantic_test`, `yang/clojure_test`.
- `semantic_test` now round-trips the continuation's payload through EDN, not the continuation itself.

## Acceptance tests (`yin.vm.host-typed-values-test`, four VMs)

- Forged `{:type :closure …}` and `{:type :reified-continuation …}` maps refuse with `{:reason :not-applicable, :kind :map}`.
- Guest `get` of `:env`, `:frames` and `:type` on a closure or continuation answers nil. `assoc` on a closure throws, so it yields nothing applicable.
- A `:yin.k/store-of` keyword binder refuses `:non-symbol-parameter` on each VM's own path: the walker on its map AST, semantic through the lowering, and the positional VMs through the resolver. A symbol binder still runs.
- A closure or continuation put raw on an in-memory stream by task A and applied by task B refuses `{:reason :foreign-value, :kind …}`. Inside one task, a closure that goes through a stream still applies.
- The same closure through `lift-slice` and `receive-module` applies in B (7 → 8), and the lowered closure carries B's owner. B lifting A's raw closure refuses as `:foreign-value`.
- `=` on two closures from one lambda is true, and hash agrees (they make one set member). Guest `=` agrees. A closure never equals its look-alike map, a closure with another owner, or a continuation over the same payload.
- Two runs of one program give equal VM states, with a closure and a continuation in the store.
- A guest frame-shaped map holding a cell ref keeps the cell alive across a collection. It is checked in five places: the store, a module store, the value register, a parked entry, and a closure's environment. There is also an in-run variant.
- Applying 5 refuses `{:reason :not-applicable, :kind :number}` and does not carry the value. A continuation applied to 2 arguments refuses `{:reason :continuation-arity, :argc 2}`.
- `data_test` adds direct and four-VM checks of `number?` and `callable?`.

## Mutation proof

I applied each mutation alone, ran the relevant namespace, and restored the file from a backup. A byte comparison against the backups confirmed every restore.

| Mutation | Result |
|---|---|
| `closure?`/`continuation?` back to keyword compares on maps | 8 failures: forged maps on all four VMs, both kinds. |
| Add `ILookup` to both types | 18 failures: `get` on all four VMs, plus `nil? (get c :env)` on the walker and semantic VMs. My first version probed only `:env` and missed the positional VMs; I strengthened it before this run. |
| Remove the non-symbol binder refusal | 4 failures, one per VM. |
| Remove the owner check at application | 8 failures: raw cross-task use, four VMs, both kinds. |
| Remove the owner check in the encoder | 4 failures: foreign lift, four VMs. |
| Semantic `lower-closure` mints with owner nil | 1 failure and 1 error, both on the semantic VM: the owner assertion, and the application refused as foreign. |
| Remove `equals`/`hashCode`/`hasheq` | 20 failures: equality, guest `=` and two-runs, four VMs each. |
| Revert to one-mode trace (kernel shapes read anywhere) | 15 failures: placed maps on the walker, stack and register VMs × 5 places, plus 2 errors in the in-run test (walker, register). |
| `:not-applicable` also carries `:fn f` | 16 failures: the forged-map test and the applying-5 test, four VMs each. |
| `data/number?` by elimination (`nil? (get x :type)`) | 12 failures in `data_test`. |

The tests cannot cover two places, by construction:

- **The semantic VM's two-mode trace.** Its `gc-children` is always nil, so it prunes nothing and there is nothing to revert.
- **The semantic VM's run-time binder check (opcode 4).** The semantic code loader already refuses a non-symbol binder by operand kind (`:syms` → `:operand-kind`), so no loader reaches the check. It is defence in depth. The semantic acceptance test instead discriminates the lowering's check: with that check removed, the loader's `:operand-kind` reason appears and the test fails.

The positional VMs have no named parameters at run time, so their refusal is at the resolver, their only binder site.

## Unresolved concerns

1. **CLJD is unverified** (not run, per the brief). Three things are unchecked there:
   - the `cljd.core/IEquiv`/`IHash` blocks;
   - `instance?`/`.-field` on the new types;
   - the design's open parity item: does `get` on a non-lookup deftype answer nil on CLJD?
2. **The protocol contract changed.** `gc-roots` and `gc-children` now answer `{:kernel [...] :values [...]}` instead of a flat vector. All four kernels and the test kernel that delegates to them are updated, but any out-of-tree kernel would break.
3. **`machine-data?` is new.** It is plain data in which a closure or continuation counts as its payload, which is exactly what the old maps passed. It replaces `plain-data?` in the register VM's payload, `:datom`, request-argument and resume-value gates. Without it, any parked register payload holding a closure was refused as corrupt. A foreign closure that arrives as a resume value is accepted there, then refused `:foreign-value` if applied.
4. **Raw-state transfer outside lift.** The walker handoff in the demo merges one VM's state into another raw. That works only because both demo VMs share a secret, and so an owner. Any composition that moves VM state between tasks with different secrets, other than through lift, will now see `:foreign-value`. That is the point of D7, but it is a behaviour change.
5. **Applying a closure in the wrong task is caught only across secrets.** Two VMs built with the *same* secret are one owner, so a closure moved raw between them still applies against the receiver's code space. I first wrote a positive test that assumed otherwise and it failed; it is now an in-task stream test.
6. **`py/numeric?` semantics narrowed.** It used to answer true for nil and for any keyword other than `:py/None`/`:py/unbound`; now only booleans, float boxes and host numbers are numeric. The e2e suites pass.
7. **Pre-existing dead code.** `ast-walker-run-active-continuation` in the walker is unused (kondo flags it on `HEAD` too). I converted it to the classifier anyway. Before this change it would have thrown on applying a continuation.
8. **Gemini concern 2 stays a rule, not code**, per the design: primitives must return realized data.

## Incomplete work

- Slice B (the marker origin-versus-store linker check at lower) and slice C (moving the store context off the lexical environment) are out of scope and not done.
- cljstyle was not run; see above.

## Round 2 (gate REQUEST CHANGES, gpt-6.1-sol)

All three findings are fixed and every check passes, except cljstyle, which is still blocked by permissions. Every new test was proven by mutation. Nothing is staged or committed.

### Fix 1 (P1): printing is opaque

Closures and continuations now print only as `{:type :closure}` and `{:type :continuation}`. They never show the payload, the environment, frames, the stack, or the owner tag. The marker follows the shape of the existing `{:type :host-fn}` marker.

- **`values.cljc`.** One `marker-text` table feeds every host:
  - CLJ: `toString` and a `print-method` for each type, inside `#?(:cljd nil :clj …)`;
  - CLJS: `IPrintWithWriter` and `Object toString`;
  - CLJD: `cljd.core/IPrint`.
- **CLJD `str`.** I added no `toString` on CLJD because the repo has no precedent for it there. The default Dart `toString` is `Instance of 'Closure'`, which carries no payload.
- **`repl.cljc` `quote-symbols`.** A closure or continuation now renders as the kind marker instead of its payload. This covers the REPL display, guest `print`, `println` and `prn`, and values nested inside collections.
- **Docs.** `ast.md` replaces "Printing renders the payload" with an "Opaque in print" bullet. `state.md` corrects its example.

### Fix 2 (P1): a lower takes its binders from the attached lambda

- **Named kernels** (`ast_walker.cljc`, `semantic.cljc`). Before minting, `lower-closure` runs `vm/check-params!` on the marker's `:yin.k/params`. The params must then equal the attached lambda's:
  - on the walker, the `:lambda` row's params (`(nth row 2)`);
  - on the semantic VM, the params of a `:closure` instruction (opcode 4) in the attached image whose entry is the marker's entry.
  - This also refuses a marker whose entry is in range but is not a lambda entry.
- **Positional kernels** (`stack.cljc`, `register.cljc`) had the same gap, in arity form. A positional marker carries `:yin.k/arity`, not names, so `[:yin.k/store-of]` cannot plant a key there. A mismatched arity, though, would have minted a closure that binds the wrong number of arguments. The lower now requires a `:closure` instruction in the attached code whose body is the marker's pc and whose arity is the marker's.
- **Refusal.** A mismatch refuses with `{:reason :marker-mismatch, :segment s}`, raised by the new `engine/marker-mismatch!`.
- Documented in `ast.md`.

### Fix 3 (P2): falsy binders

`vm/check-params!` now boxes the bad binder (`(some #(when-not (symbol? %) [%]) params)`), so a `nil` or `false` binder no longer reads as "no bad binder".

### New tests (`yin.vm.host-typed-values-test`)

- **`a-non-symbol-binder-is-refused-test`** replaces the keyword-only test. It covers `:yin.k/store-of`, `nil` and `false` on all four VMs, each through its own path, and expects `{:reason :non-symbol-parameter}` with `:kind` `:keyword`, `:nil` or `:boolean`.
- **`a-lower-takes-its-binders-from-the-attached-lambda-test`.** It lifts task A's `f`, tampers with the marker, and lowers it into task B:
  - walker and semantic: params `[:yin.k/store-of]`, `[nil]` and `[false]` refuse `:non-symbol-parameter`; `[y]`, `[x y]` and `[]` refuse `:marker-mismatch`;
  - stack and register: arity 0 or 2 refuses `:marker-mismatch`;
  - an untampered marker lowers on all four VMs.
- **`a-closure-or-continuation-prints-opaquely-test`.** On all four VMs, a closure and a continuation that both capture `"s3cr3t-42"` are rendered through `str`, `pr-str`, nested `pr-str` and nested `str`, and REPL `format-value` alone and nested. None shows the value, `:env`, `:frames`, `:stack` or the owner. Exact strings are asserted, for example `"[{:type :closure} {:k {:type :continuation}}]"`. A sanity check confirms the payloads really do hold the value.
- **`guest-printing-of-a-closure-is-opaque-test`.** For every REPL VM type, it evaluates real guest input: `f` closes over the value, `(f 1)` returns it, and `f` displays as `{:type :closure}`. Then `(println f)`, `(print (conj [] f (assoc {} :k f)))` and `(prn f)` each print the marker and never the value.

### Mutation proof (Round 2)

Each mutation was applied alone, run against the namespace, and restored. `cmp` against the backup confirmed every restore.

| Mutation | Result |
|---|---|
| REPL `quote-symbols` renders the payload again | 48 failures: both printing tests, all four VMs. |
| CLJ `toString`/`print-method` render the payload | 24 failures: host `str` and `pr-str`, all four VMs. |
| Walker lower without `check-params!` | 3 failures: `[:yin.k/store-of]`, `[nil]` and `[false]` now come back as `:marker-mismatch`. |
| Walker lower without the params equality | 3 failures: `[y]`, `[x y]` and `[]` lower. |
| Semantic lower without `check-params!` | 3 failures. |
| Semantic lower without the params equality | 3 failures. |
| Stack lower without the arity check | 2 failures: arity 0 and arity 2. |
| Register lower without the arity check | 2 failures. |
| `check-params!` without the box (falsy binders escape) | 12 failures: `nil` and `false` on all four VMs, plus 4 lower cases. |

### Checks (Round 2, all run in the foreground)

| Check | Outcome |
|---|---|
| clj-kondo, each file changed this round as a separate argument | 0 errors. The 5 warnings are all present on `HEAD` (see above). |
| cljstyle check | **Blocked**: the invocation required approval again and was not run. |
| `bb gen:python-antlr` | ok. 4 java files generated. |
| `bb build:yin-repl-node` | ok. 174 files, 0 warnings. |
| Focused JVM: new namespace, repl, continuation-invoke, heap-reclamation, cell, data, attach-image, linker-require, ast-walker and semantic suites | 222 tests, 2169 assertions, 0 failures. |
| Full `clj -M:test` | **2691 tests, 187798 assertions, 0 failures, 0 errors.** The 6 `yang.python.antlr.*` namespaces ran. |
| `bb test:cljs` | **2538 tests, 53552 assertions, 0 failures.** "Testing yin.vm.host-typed-values-test" appears, so the CLJS printing path is verified. |
| `bb test:cljd` | Not run, per the brief. |

**Changed this round:**
- `src/cljc/yin/vm.cljc`, `values.cljc`, `repl.cljc`, `engine.cljc`;
- the four kernels: `ast_walker.cljc`, `semantic.cljc`, `debruijn/stack.cljc`, `debruijn/register.cljc`;
- `docs/ast.md`, `docs/state.md`;
- `test/yin/vm/host_typed_values_test.cljc`.

### Round 2 concerns

- **CLJD is still unverified.** This round adds the `IPrint` blocks to that list, alongside the earlier items.
- **The semantic lower is stricter than the gate asked.** It requires the marker's entry to be the entry of a `:closure` instruction carrying the same params. The existing semantic lower test (`semantic-closure-entry-outside-its-image-is-refused-test`) still passes.
- **Slice B is still open.** The marker origin-versus-store check remains deferred, so, as the gate notes, isolation is incomplete until it lands.

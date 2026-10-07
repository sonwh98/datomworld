Completed-GMT: 2026-09-30 14:50:13 GMT
Completed-Local: 2026-09-30 21:50:13 +07
Coding-Agent: claude
Session-ID: 34a43a41-4291-4c0a-ba31-296bdadeab7a

# D4 report: effects are an unforgeable host type (F1 fix)

Status: all five parts are implemented and all lanes I was allowed to run are green. I made no code changes in Round 2. Round 2 re-ran every check in the foreground. Nothing is staged or committed.

## What changed

### The mechanism

**Parts 1 and 2: the host type.**
- New namespace `src/cljc/yin/vm/effect.cljc` defines `deftype Effect [descriptor]`.
  - It supports keyword lookup (ILookup) on CLJ, CLJS and CLJD. The reader conditional puts `:cljd` first.
  - It is not a map: `map?` is false, and guest `assoc` cannot extend it.
- `effect/make` is the one constructor.
- `effect/effect?` is an `instance?` test.
- `effect/descriptor` returns the plain map.
- `effect/from-descriptor` is the named trusted conversion from a plain descriptor to an effect.
- `module/effect?` and `module/make-effect` are aliases of these. The signature is now `(make-effect kind)` or `(make-effect kind params-map)`. The old kwargs version had no callers.
- The constructor is not in `vm/primitives` or in any module export. A test asserts this for `vm/primitives` and `stream-module`.
- `engine/handle-effect` refuses any value that is not an `Effect` ("Not an effect", `:reason :not-an-effect`). A missed literal producer therefore fails loudly instead of being treated as data by accident.

**Part 5: the layered profile check.**
- `vm/callable-effects` builds an identity-keyed map from host function to declared effect set. It is built:
  - once, in `vm/empty-state`, as `:callable-effects` over the installed primitives and profiles;
  - in `module/register-host-module`, into the registry's `:callable-effects`, merged with `merge-with into`.
- `engine/check-callee-effect!` runs only when a callee's result is already an `Effect`. It does one or two hash lookups; ordinary calls pay nothing, and nothing scans a registry.
- If the callee has a profile and the effect's kind is not declared, it throws "Effect outside the callee's declared profile" with `{:yin.k/status :yin.k/undeclared-effect, :yin.k/effect kind, :yin.k/effects declared}`.
- The check is wired into every place a VM applies a host function:
  - AST walker: `handle-primitive-result`, which now takes `f`; 3 call sites.
  - Semantic VM: `apply-call`.
  - Stack VM: the `fn?` call arm.
  - Register VM: the `fn?` call arm.
- The walker's `cesk-return` rebuilds the record positionally, which dropped the new key. I added a `callable-effects` field to `ASTWalkerVM` to fix this. The stack and register VMs copy `:callable-effects` from `base`; the semantic VM already merges `base`.

### Producers migrated to the constructor (part 4)

| File | Sites |
|---|---|
| `yin/vm/module.cljc` | 5 stream-module constructors, plus docstrings |
| `yin/vm.cljc` | the `require` primitive (uses `yin.vm.effect` directly; `module` requires `vm`, so a cycle blocks the alias) |
| `dao/await.cljc` | 3 |
| `yin/repl/query.cljc` | `q` |
| `ast_walker.cljc` | 5 |
| `semantic.cljc` | 5 |
| `debruijn/stack.cljc` | 5 |
| `debruijn/register.cljc` | 5 |

There are 30 source producers in total. `debruijn_register_effects.cljc/effect-descriptor` stays plain data on purpose (see boundary 5); only its docstring changed. The `docs/ast.md` code snippets were updated to match.

### Tests

- **New:** `test/yin/vm/effect_forgery_test.cljc`. Each program runs on all four VMs.
- **Migrated** to `make-effect`, `effect/descriptor` or `from-descriptor`: `module_test`, `engine_test` (only the `handle-effect` call sites), `rule_r_test`, `ucf_test`, `ast_walker_test`, `debruijn/stack_effects_test`, `debruijn_register_effects_test`.
- `debruijn_register_effects_test` also gained an assertion that `handle-effect` refuses the raw descriptor.
- `engine_test` tests that call `handle-put` directly with maps are unchanged. They test handler internals and never go through `handle-effect`.

## Stream and wire boundaries

In every case the wrapper stays inside the machine. What crosses a boundary is plain data.

1. **Wait-set and park entries** (serializable).
   - Builders read fields: `:datom` comes from `(:val effect)`; registers and resource ids are plain.
   - The `:module/require` builders ignore the effect.
   - No entry stores the `Effect`.
2. **`dao.stream.apply` call-in** (`repl/query.cljc` `call-handler`). It writes `apply2/request call-id op (vec (:args effect))`, which is plain data.
3. **Link request stream** (`module/require-handler`). The envelope is built from `(:module effect)` and holds plain symbols and keywords.
4. **Telemetry effect snapshot.** Only `:effect-type` (a keyword) is emitted. The state's `:value` is the handler's result, never the effect.
5. **Portable descriptor** (`debruijn_register_effects/effect-descriptor`).
   - It stays a plain map.
   - The trusted conversion at dispatch is `effect/from-descriptor`, and it is used there in tests.
   - A raw descriptor is refused by `handle-effect`.
6. **Compilation streams.** No effect is emitted onto them. Code carries stream node types and opcodes, and effects are minted at run time by VM transitions. Literal maps in code stay data, which the tests show.
7. **Error ex-data.** "Unknown effect" and the refusal carry kind keywords and sets, never the wrapper.
8. **Lift and encode** (engine lift, completion). Effects are consumed at the application that returns them and never become guest values, so none reaches the encoder. This follows from reading the code; I did not test it.

## Test outcomes (Round 2, all foreground)

- `clj -M:kondo --lint` on all 19 changed or new source and test files: 0 errors, 5 warnings, 1 info.
  - The warnings are `yin.vm` `semantic-bytecode-child-refs` and three unused bindings, plus `ast-walker-run-active-continuation`. The info is stack `Unused excluded var: eval`.
  - In Round 1 I linted master copies from `e3cf971b` and got the same warnings.
- `clj -M:test` (full): **Ran 2420 tests containing 185018 assertions. 0 failures, 0 errors.** Exit 0. Log: `collab/d4-clj-test.log`.
- `bb test:cljs`: **Ran 2325 tests containing 51462 assertions. 0 failures, 0 errors.** Build: 375 files, 0 warnings. `Testing yin.vm.effect-forgery-test` appears in the Node output. Log: `collab/d4-cljs-test.log`.
- Focused JVM run in Round 1 (10 namespaces: forgery, module, engine, rule-r, ucf, ast-walker, stack-effects, register-effects, continuation-invoke, dao.await): Ran 176 tests, 1080 assertions, 0 failures, 0 errors.
- **cljstyle check: NOT RUN.** The permission layer blocked `cljstyle check` in both rounds, and the `:fmt` alias rewrites files rather than checking them.
  - Known cosmetic issue for the formatter: some rewritten `make-effect` call sites are long or misindented, at `semantic.cljc` around 413-425 and `debruijn/stack.cljc` around 663-684.
  - Round 2 forbids code changes unless a check fails, so I left them. Please run `cljstyle fix` on the changed files.
- `bb test:cljd`: not run, as the brief assigns it to the orchestrator.

## Mutation proof (Round 1, this session; code unchanged since)

I ran each mutation against `yin.vm.effect-forgery-test` (6 tests, 53 assertions), then reverted it. A grep for `MUTATION` in src and test now returns 0.

- **M1: part 1 reverted.** `module/effect?` goes back to also accepting any map with `:effect` (shape detection).
  - Result: 2 failures and 24 errors.
  - The two failures are the host-type assertions.
  - The errors are every F1 and literal `:stream/make` assertion on all four VMs. Those programs now throw instead of returning data, because layer 5 refuses the forged effect from the `:pure` `assoc`/`get`.
- **M1 + M2: both layers reverted.** Result: 34 failures.
  - The store-write assertions (lines 122 and 136) fail on all four VMs. `k` and `k2` are written, which reproduces F1 exactly.
  - The literal `:stream/make` map is executed.
- **M2 only: the profile check disabled** (`(and false …)` in `check-callee-effect!`).
  - Result: 8 failures, exactly the two part-5 tests (host module and profiled primitive) on 4 VMs each.
  - The part-1 tests pass, so each layer is proven on its own.

## Unresolved concerns

1. **Unprofiled callables pass layer 5.** These are bare primitive entries, host functions supplied through the environment or `:free-env`, and the like. They are treated as the composition's trusted values. Refusing them instead would break legacy bare registries. This is a policy choice the Architect should confirm.
2. **One function published under several profiles** gets the union of its declared sets, which is the permissive choice.
3. **`:callable-effects` can go stale.** It is built at `empty-state` or registration. A later `assoc :primitives` that adds a new function leaves that function unprofiled, so it passes. No function is ever wrongly refused.
4. **CLJS and CLJD identity keys.** CLJS behaviour is verified by the green lane: function keys hash by `goog/getUid`. CLJD relies on Dart closure identity for `hashCode` and `==` and is unverified until the orchestrator runs `bb test:cljd`. `effect.cljc` is new CLJD code (a deftype with `cljd.core/ILookup`, `:cljd` branch first).
5. **Printing.** CLJ prints `#yin/effect {...}` through `toString`. CLJS and CLJD print the default deftype form, which only matters for debugging.

## Incomplete work

- cljstyle check was not run (blocked; see above).
- `bb test:cljd` is the orchestrator's lane.
- Nothing else is outstanding.

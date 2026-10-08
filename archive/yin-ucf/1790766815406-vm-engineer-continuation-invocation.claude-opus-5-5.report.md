Completed-GMT: 2026-09-30 11:49:11 GMT
Completed-Local: 2026-09-30 18:49:11 +07
Coding-Agent: claude
Session-ID: 0dd55e3e-bfc9-48ba-ba2a-5e307baeb120

# Report: Captured continuations are invocable (apply a :reified-continuation)

Status: done. Nothing is staged or committed. All work is in /Users/sto/workspace/datomworld-k-invoke (branch vm-continuation-invoke). No AST grammar change, no new tag. yin.repl/*, dao.jing/* and dao.stream/* are untouched, and completion.cljc is unchanged.

All five required semantics work on all four VMs without choosing any different semantics, so there was no STOP.

## Changed files

- `src/cljc/yin/vm/engine.cljc` (+17). Two shared helpers:
  - `reified-continuation?` recognizes a continuation value.
  - `continuation-argument` returns the one argument. For any other arity it throws `ex-info "Continuation expects exactly one argument"` with `{:continuation-type :argc}`. This is the single source of the identical cross-VM message.
- `src/cljc/yin/vm/ast_walker.cljc` (+7). `apply-function` has a new clause that calls `(cesk-return state nil (:env f) (:k f) arg)`. The current `k` is dropped, and the captured `k`/`env` get the argument.
- `src/cljc/yin/vm/semantic.cljc` (+7). `apply-call` has a new clause that returns `{:goto [(:segment f) (:pc f) arg (:stack f) (:env f) (:k f) vm (get code seg')]}`. That is exactly the `val` register the capture opcode (19) leaves at pc+1. It is the same for `:call` (5) and `:tailcall` (20).
- `src/cljc/yin/vm/debruijn/stack.cljc` (+7). The `:call` handler has a new clause that calls `(stack-restore vm f arg)`. It reuses the machine's one restore path: the format/image identity check, then pc/frames/continuation/stack/store-of restored and the argument conjed onto the stack.
- `src/cljc/yin/vm/debruijn/register.cljc` (+56/-34, mostly a behaviour-preserving split). The body of `register-restore` is split into two helpers:
  - `check-format!`: format tag plus offset-table image row.
  - `write-back`: register write-back via `:write-result`/`:return-result`.

  `register-restore` still runs, in order, the format check, `continuation-defect`, the plain-data gate, the FFI two-step and write-back, so its behaviour is unchanged. The `:call` handler has a new clause that runs `continuation-argument`, `check-format!` and `write-back`.
- `test/yin/vm/continuation_invoke_test.cljc` (new). It runs every program on all four VMs through their existing loaders, with the same runner setup as `debruijn/register_test.cljc`: ast-walker via `tu/compile-and-run`, semantic, stack via `dl/adapt`, register via `rc/adapt`. No existing test namespace had a single four-VM harness I could extend without touching `yin.repl`. Tests:
  - `escape-discards-pending-frames-test`: `(+ 1 ((fn [r] (if (= r 7) (+ r 1) (+ 1000 (r 7)))) (cc)))` gives 9. The pending +1000 frame is discarded and the captured +1 frame is kept.
  - `tail-position-invocation-test`: the same shape with `(r 7)` marked `:tail? true`, which gives 8.
  - `re-entry-after-return-test`: the continuation is captured inside `((fn [] (cc)))`, which returns before any invocation. It is stored under the store key `saved` by `yin/def`, then invoked 3 times with a store counter `count` bounded at 3. The result is `[30 3]`, which shows multi-shot re-entry after return, including the popped return frame on the bytecode VMs.
  - `store-not-rolled-back-test`: `(yin/def mark :written)` runs before `(r 1)`, and the re-entered branch reads `mark`. The result is `[1 :written]`.
  - `arity-error-identical-across-vms-test`: 0 and 2 arguments each give `[:thrown "Continuation expects exactly one argument"]` on every VM.
- `src/cljc/yin/vm/docs/ast.md` (+23):
  - Application semantics now lists the reified-continuation case.
  - The `apply-function` snippet is updated.
  - Part 7 has a new "Invocation" paragraph: abortive, tail and non-tail, store not rolled back, multi-shot, and the arity message.
- `src/cljc/yin/vm/docs/co-routines.md` (+4/-2):
  - The "Currently ... throws" text is replaced with the current behaviour.
  - A note explains how to write the call/cc pattern with `:vm/current-continuation`.
  - The now-done item is removed from the "Optional ergonomic improvements" list.

## Mutation proof (each test must fail when the change is reverted)

For each VM file, I replaced its `(engine/reified-continuation? …)` clause test with `(false? …)`, ran `clj -M:test -n yin.vm.continuation-invoke-test`, then restored the file:

| mutated file | result |
|---|---|
| ast_walker.cljc | 6 failures, all `:ast-walker` (re-run against the final code) |
| semantic.cljc | 6 failures, all `:semantic` |
| debruijn/stack.cljc | 6 failures, all `:stack` |
| debruijn/register.cljc | 6 failures, all `:register` |

Six is every assertion for that VM: 4 behaviour tests plus 2 arity cases. No other VM's assertions failed under any mutation. All files were restored, and the final unmutated run is 5 tests, 24 assertions, 0 failures.

## Checks

- `clj -M:kondo --lint` on the 5 changed .cljc sources and the new test: 0 errors, 1 warning. The warning is "Unused private var yin.vm.ast-walker/ast-walker-run-active-continuation". It predates this change: that fn is already defined and never referenced at HEAD dac64b41. There is also 1 existing info note in stack.cljc (unused excluded var `eval`).
- `cljstyle check`: **blocked**. The harness required approval for the command and it was not granted in this session, so it did not run. Please run `cljstyle check` on the 5 changed .cljc sources and the new test.
- Focused JVM run with `clj -M:test -n` over 7 namespaces: continuation-invoke-test, ast-walker-test, semantic-test, debruijn.stack-test, debruijn.register-test, engine-test and parity-test. Result: 131 tests, 596 assertions, 0 failures, 0 errors.
- Full `clj -M:test`: 2408 tests, 184780 assertions, 0 failures, 0 errors.
- `bb test:cljs`: 2313 tests, 51231 assertions, 0 failures, 0 errors. The output contains `Testing yin.vm.continuation-invoke-test`, and the shadow build had 0 warnings.
- `bb test:cljd`: not run, as instructed (orchestrator lane). The test's catch has `:cljd Object`. The runners use only public vars, with no `#'` private access.

## Decisions to review (Architect)

1. **Register VM identity check only.** Invocation runs `check-format!` (format tag plus offset-table image row, the same identity check as `stack-restore`). It does not run `effects/continuation-defect` or the plain-data gate that `register-restore` applies.
   - The first attempt did run the full `register-restore` checks. It failed with `{:rule :continuation-registers}` on the escape and tail tests.
   - The cause is that `continuation-defect` requires every live register in the payload to hold plain data. A capture made while a closure or primitive is live, as in `((fn [r] …) (current-continuation))`, is a perfectly valid in-machine value, but it is not a portable payload.
   - Those gates guard continuations and values *entering* the machine. In-machine invocation has neither concern.
   - Consequences:
     - A program-forged `{:type :reified-continuation …}` map with the right format and image but bad registers is not structurally validated on invocation. It would fail as a host error (for example an index out of range), not as the qualified defect. The stack VM already behaves this way for `:resume`.
     - A continuation invoked on the register VM may deliver a non-plain value, such as a closure, as on the other three VMs. `:vm/resume` on the register VM still refuses non-plain values.
   - If the Architect wants the defect gate on invocation, the escape and tail tests show it is incompatible with the required semantics unless `continuation-defect` is relaxed. That would be a separate decision.
2. **Dead AST walker hot loop.** `ast-walker-run-active-continuation` in `ast_walker.cljc` is unused at HEAD, as kondo reports. I first added the clause to its two application arms too, then reverted those edits because the code is unreachable and would be untested. If that loop is ever revived, it will throw "Cannot apply non-function" for continuations until it gets the same clause.
3. **completion.cljc unchanged.** `abstract-value` already treats a `:reified-continuation` as reaching its address, pc, env context, K frames and stack values. Invoking one reaches only code that its capture already made reachable, so the abstract analysis needs no new case.

## Unresolved concerns / incomplete work

- cljstyle was not run (see Checks).
- The CLJD lane was not run (orchestrator-owned).
- Capture on the bytecode VMs holds whole-machine registers. The register VM payload includes the segment by reference. Invocation is O(1) on every VM, with no hashing, so break/continue lowering pays no per-invoke cost.
- No conflict with the DHT work: nothing under dao.stream, dao.jing or yin.repl changed.

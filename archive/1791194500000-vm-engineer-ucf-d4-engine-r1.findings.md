Completed-GMT: 2026-10-05 09:57:50 GMT
Completed-Local: 2026-10-05 16:57:50 +07
Coding-Agent: claude (sonnet-5-5)

# D4 engine round 1 (re-scoped, option 2): landed for put, next and poll. Close and cursor are deferred.

## Changed files

- `src/cljc/yin/vm.cljc`: adds `yin.vm/gate-mode`, which reads `:yin.k/gate`.
- `src/cljc/yin/vm/engine.cljc`: the split, the gate, the `:observe` entry, the sweep skip and `apply-observation`.
- `test/yin/vm/engine_gate_test.cljc` (new): 6 tests, 46 assertions.

`git status` outside `collab/` shows only those three. No kernel, `module.cljc`, `ffi.cljc` or `handoff.cljc` change, and no wire change.

## Evidence

- **Red:** with the test file written first, the run failed at compile with `No such var: vm/gate-mode`.
- **Green:** `clojure -M:test -n yin.vm.engine-gate-test` gives 6 tests, 46 assertions, 0 failures and 0 errors.
- **Existing suites, no gate:** the JVM run over `yin.vm.*` (engine, FFI, link, handoff, all four kernels, ucf, with `:slow` excluded) gives 1221 tests, 12873 assertions, 0 failures and 0 errors. The run over `yin.repl.*` and `datomworld.*` gives 306 tests, 2410 assertions, 0 failures and 0 errors. The orchestrator still owns the three-lane run.
- **kondo:** 0 errors on the three changed files. There are 4 warnings, all in `yin/vm.cljc` on lines I did not touch (l.1330, l.1486, l.1494, l.1508).
- **cljstyle:** not run.

## Observe/apply pairs split

| Effect | Observe | Apply |
|---|---|---|
| `:stream/put` | `observe-put` (private): `stream/append!` | `apply-put` (private): outcome to value, park on full, error or shaped refusal |
| `:stream/next` | `stream/next` in `handle-next`; `next-target` resolves the cell and handle | `apply-next` (private): outcome to value, cursor advance, park on blocked, error or refusal |
| `:stream/poll` | the same as next | `apply-observation` (public) |

`handle-put` and `handle-next` are now `apply ∘ observe`, unchanged ungated.

## The gate

- **`gate-refuse!` (private):** in `:exporting` and `:ended`, put, next, poll and close fail with `{:yin.k/gate mode :effect kind}` before any handle call. It returns the mode otherwise.
- **`:running`:**
  - Put parks as the retained `:put` entry it would build on `full`.
  - Next parks as the `:next` entry it would build on `blocked`.
  - Neither touches a handle.
  - Poll parks as `{:reason :observe :op :poll :cursor-ref :stream-id ...}`, built with the caller's `:stream/poll` builder or else its `:stream/next` builder. Nothing is held until the driver observes.
- **Sweep skip:** `check-wait-set` adds `observe-entry?` to `engine-polled?`, beside link and FFI-response entries. A test shows an ungated sweep over an `:observe` entry makes zero calls and keeps the entry.
- **`engine/apply-observation [state entry outcome]`:**
  - It takes the same outcome map `stream/next` returns.
  - It refuses in `:exporting`, in `:ended`, and for any entry that is not an `:observe` poll.
  - It advances the cell exactly as the ungated poll does and leaves `:dao.stream/blocked` and `:end` cells alone.
  - It removes the entry from the wait set and queues a ready entry carrying the poll's value.
  - It makes zero stream calls.
- **Equivalence tests:** for `ok`, `blocked` and `gap`, `apply-observation` gives the same `:resources` and the same ready value as the ungated `handle-effect` given that outcome.

## Unresolved concerns

1. **Close is not gated under `:running`.**
   - It has the same builder problem as cursor. The semantic kernel's close site passes `nil` as park-entry-fns, the walker passes no builders and ignores `blocked?`, and `:stream-close` is not in `boundary-opcodes` for the register kernel. Only the stack kernel passes builders.
   - Close would need a machine-only entry with no kernel registers, so it moves to D5 with cursor.
   - In `:exporting` and `:ended` close is already refused with no handle call. Under `:running` it still calls `close!`. This is unfenced until D5, which is acceptable only because nothing outside tests may set the gate before D6.
   - The D4 zero-call list loses the close row, as it lost cursor.
2. **`:observe` supports only `:op :poll`.** The ruling's `:cursor` shape (`:origin`, `:id`) is not built. The D5 cursor ruling should decide whether it uses the same entry or whether close does.
3. **`:yin.k/held`.** `apply-observation` strips it from the entry it queues, but nothing in D4 sets it. The driver (D12) owns that.
4. **The `:observe` entry resumes as a plain ready entry.** `:reason :observe` and `:op` are dropped. Whether `yin.vm.engine/resume-from-run-queue` restores it correctly for the semantic and walker kernels is not covered by any test, because no kernel emits poll. D5 or D12 should cover it when a kernel does.
5. **Poll under a gate with a caller that has no builder.** The result is `Parked entry required for blocking stream`, the same as put or next with no builder.

## Incomplete work

Gated `:stream/close` and `:stream/cursor` are not implemented, per your decision (cursor) and concern 1 (close).

Completed-GMT: 2026-09-29 04:32:50 GMT
Completed-Local: 2026-09-29 11:32:50 +07
Coding-Agent: claude
Session-ID: 08d36f7d-b33d-404e-adcb-dc04d5b907ef

# Report: Slice 3d (caller-scoped FFI correlation, response router, caller readiness, portable loss error)

**Status: complete.** All five scope items are implemented, and acceptance items a to g are covered by tests. Every load-bearing mechanism was proved by a temporary mutation, then reverted and grepped clean. The focused JVM lane, the full `clj -M:test` and `bb test:cljs` all pass. I did not stage or commit anything. I edited no file outside the allowed list, and `yin.vm.ucf.remote`, `dao.stream.*`, `dao.lease` and `yang.*` are untouched.

## Changed files

**Source**
- `src/cljc/yin/vm.cljc`
  - New `ffi-caller-id?`: a non-blank string or a keyword.
  - New `ffi-call-id?`: a keyword, or `[caller-token keyword]`.
  - `empty-state` has two new options:
    - `:call-out-cursor`: trusted and installed as given. It is **required** with a supplied `:call-out` and **refused** without one.
    - `:ffi-caller-id`: validated, and stored in state.
  - Locally made pairs keep their `:oldest` mint.
- `src/cljc/yin/vm/engine.cljc`
  - `park-id`, which answers the next local `:parked-N`.
  - `park-continuation` has a new 3-arity with an explicit id. That id is both the `:parked` key and `:id`, and the counter still advances.
  - **The FFI response router**: `poll-ffi-responses` and `poll-ffi-cell`, beside `poll-links`.
  - `take-ffi-diagnostics`.
  - `check-wait-set` excludes FFI response readers from the generic sweep, the same way it excludes link entries.
  - `terminal-resume-outcome` and `throw-terminal-resume!` raise the two loss statuses.
  - The engine now requires `yin.vm.ffi` and `dao.stream.apply`. The graph stays acyclic: `ffi` requires only `vm` and `telemetry`.
- `src/cljc/yin/vm/ffi.cljc`
  - `call-id` builds the composite id, or the bare local id when there is no token.
  - `response-call-id` and `request-call-id` match both entry shapes: `:call-id` at the top level (semantic, stack, register), or nested in the walker's `:k` (`eval-call` / `request-sent`).
  - `response-loss-statuses`, `response-lost` and `throw-response-lost!`.
  - Namespace docstring updated.
- `src/cljc/yin/vm/semantic.cljc`, `ast_walker.cljc`, `debruijn/stack.cljc`, `debruijn/register.cljc`
  - All four FFI call sites compute `(ffi/call-id vm (engine/park-id vm))` and park under that id.
  - All four `create-vm`s pass `:call-out-cursor` and `:ffi-caller-id` through to `empty-state`.
  - The stack and register records copy `:ffi-caller-id` from base.
  - **The walker record gains the `ffi-caller-id` and `ffi-diagnostics` fields.** `cesk-return` rebuilds the record positionally and would otherwise drop both.
  - `create-vm` docstrings updated.
- `src/cljc/yin/vm/debruijn_register_effects.cljc`: both validators (writer at about line 412, reader at about 432) use `vm/ffi-call-id?` instead of `keyword?`.
- `src/cljc/yin/vm/ffi/remote_serve/caller.cljc` (**new**, `yin.vm.ffi.remote-serve.caller`): the production readiness step. It has `open`, `step`, `ready?`, `call-out-cursor` and `vm-opts`.
- `src/cljc/yin/vm/ffi/remote_serve/responder.cljc`: docstring only. The VM now raises the portable loss, not an error from `call-result`.

**Tests**: `ffi_test`, `semantic_ffi_test`, `debruijn/stack_effects_test`, `debruijn_register_effects_test`, `ucf/remote_test`, `ffi/remote_serve/responder_test`. The details are below.

## Test outcomes (exact)

- **Lint.** `clj -M:kondo --lint` on all 10 changed source files and 6 test files gives `errors: 0, warnings: 5`.
  - All 5 warnings are in forms I did not touch: `yin.vm` unused private and unused bindings at about lines 1280 to 1458, and `ast-walker-run-active-continuation`.
  - `git show HEAD:src/cljc/yin/vm.cljc` linted alone shows the same class of warnings.
- **cljstyle check: blocked.** The command needs approval in this session, so it did not run.
  - I kept the indentation of existing code. The `ffi_test` helper is named `create-vm-for-caller`, the same length as `ast-walker/create-vm`, so its call sites keep their alignment.
- **Focused JVM lane.** `clj -M:test` over `yin.vm-test`, `yin.vm.engine-test`, `yin.vm.ffi-test`, `yin.vm.semantic-test`, `yin.vm.semantic-ffi-test`, `yin.vm.parity-test`, `yin.vm.debruijn-register-effects-test`, `yin.vm.ucf.remote-test`, `yin.vm.ffi.remote-serve-test`, `yin.vm.ffi.remote-serve.responder-test`, `dao.stream.apply-test` and `yin.vm.debruijn.stack-effects-test`: **Ran 224 tests containing 1996 assertions. 0 failures, 0 errors.**
- **Full `clj -M:test`: Ran 2341 tests containing 184033 assertions. 0 failures, 0 errors.**
  - The known `yin.repl.main-test` cross-process flake did not occur in this run.
  - An earlier accidental full run while editing showed 16 failures. They came from a tree that did not yet carry the token through the stack, register and walker records, and they were fixed by that change.
- **`bb test:cljs`: Ran 2247 tests containing 50560 assertions. 0 failures, 0 errors.**
  - The log shows "Testing" for `yin.vm.ffi-test`, `yin.vm.semantic-ffi-test`, `yin.vm.debruijn-register-effects-test`, `yin.vm.debruijn.stack-effects-test`, `yin.vm.ucf.remote-test`, `yin.vm.ffi.remote-serve-test` and `yin.vm.ffi.remote-serve.responder-test`.
  - The log was not saved to `collab/`, because the `tee` redirect needed approval.
- `bb test:cljd` was not run, as instructed.

## Acceptance coverage

| # | Test(s) |
|---|---|
| a | `responder_test/two-callers-on-one-pair-each-receive-their-own-result`. Two VMs, `caller-1` and `caller-2`, are on ONE exported pair, each through the production readiness step. Both mint local `:parked-0`. The requests land in order 1, 2; the responses are appended 2, 1. Each VM receives its own result (3 and 30), and VM 1's diagnostics show it skipped VM 2's response. |
| b | `responder_test/an-unrelated-response-leaves-the-waiter-parked`. A stray response (`["caller-9" :parked-0]`, no live waiter) and VM 2's response arrive first. VM 1 stays blocked, its wait-set keeps its id, and both were diagnosed, never delivered. VM 2 is woken by its own response past the stray. VM 1 is woken later by its own, and `:parked` is empty. `semantic_ffi_test/a-response-for-another-call-does-not-resume-this-one-test` and `stack_effects_test/error-responses-surface-as-errors-test` check the same locally. |
| c | `responder_test/the-readiness-step-yields-then-returns-a-real-cursor`. The first `step` is `::pending` (retry, one attempt, no `vm-opts`). After drives it is `::ready` with the minted cursor, and `vm-opts` is exactly `{:call-in :call-out :call-out-cursor :ffi-caller-id}`. A VM built from it completes a remote call (7) past a stale response already on the shared call-out, filed even under the same composite id: the cursor is `:newest`. A supplied pair without `:call-out-cursor` raises at construction. The step is bounded (`::exhausted`), and `open` refuses a malformed token, an unportable token (with `::codec`) and an unattachable reflection. Also `ffi_test/a-supplied-call-out-needs-a-composition-cursor-test`: refused without the cursor, refused with a cursor on no supplied call-out, installed as given, and local pairs still mint their own. Every existing remote test now builds its VM through the production step, which replaces the test-only pre-poll. |
| d | `ffi_test/a-lost-response-raises-the-portable-ended-error-test` (walker, nested `:k` shape). **End** gives exactly `{:call-id ["tenure-1" :parked-0], :error {:dao.stream.apply/code :dao.stream.apply/ended, :dao.stream.apply/message "FFI response stream ended before this call was answered", :yin.vm.ffi/loss :dao.stream/end}}`. **Gap** has the same code and call id, a gap-specific message and `:yin.vm.ffi/loss :dao.stream/gap`. Both pass `apply/error?`, and neither is "malformed envelope". Remote end: `responder_test/a-call-in-gap-is-reported-as-loss` asserts the exact loss data. |
| e | `responder_test/a-migrated-composite-call-is-routed-on-its-carried-cell`. An emitter with `:ffi-caller-id "emitter-b"` retains a full call. `rs/lift-frame` carries `["emitter-b" :parked-0]` as `:yin.k/call-id`. It is lowered at A into a receiver with its own token, where the lowered writer carries a `:response-cursor` that is not `vm/call-out-cursor-key`. A foreign response lands first on the carried stream. The receiver halts with 15, and its diagnostics show the foreign one skipped, so the carried cell was routed. |
| f | `ffi_test/every-vm-kind-parks-and-resumes-under-a-composite-id-test` covers the ast-walker, semantic, de Bruijn stack and de Bruijn register VMs. For each: `:parked` keys are `[["tenure-1" :parked-0]]`, the reader waits for that id, the request carries it, and the VM resumes with 7 and empty `:parked`. A vector key read back through `pr-str`/`edn` is still `contains?` in the parked map and works as a map-key lookup (the CLJD-relevant check; it runs on the CLJD lane when that lane runs). `ffi_test/a-vm-without-a-caller-token-keeps-its-local-ids-test` covers the legacy path. `ffi_test/a-caller-token-is-plain-portable-data-test` covers the predicates. `debruijn_register_effects_test/wait-entry-defect-test` has a new case: both validators accept the composite id, and reject `["tenure-1" 0]`, `["" :parked-0]`, a 3-vector and a string. |
| g | All existing suites pass. The modified tests are listed below. |

Also `semantic_ffi_test/a-response-for-a-woken-writer-is-left-for-its-reader-test` (a router rule of my own; see design choice 3).

**Mutation proofs.** Each was applied temporarily, run over `ffi-test`, `semantic-ffi-test` and `responder-test`, then reverted. Afterwards `grep -rn "MUTATED\|and false" src/cljc/yin test/yin/vm` found only a pre-existing test string.

| Mutation | Result |
|---|---|
| M1: router disabled (FFI readers go back to the positional sweep) | 14 FAIL, 4 ERROR (a, b, d, e, stall) |
| M2: `call-id` ignores the token | 29 FAIL (a, b, e, f)\* |
| M3: router wakes the first waiter on a cell regardless of id | 3 FAIL, 4 ERROR (a, b, e, stall) |
| M4: stop-at-pending-writer rule disabled | 3 FAIL (stall test) |
| M5 + M6: end wakes as the old `:end`; supplied-pair refusal removed | 7 FAIL (d end, remote end, c refusals) |
| M7 + M8: gap wakes as end; readiness anchors `:oldest` | 4 FAIL (d gap key and message, c "past the history", and the pair-channel probe) |

\*M2 was written as `(and false token)`. `if-some` treats `false` as a value, so the no-token test also failed. That failure is an artefact of the mutation; the regression that matters, the two-caller test, failed as it should.

## Existing tests modified, and why

1. **`ffi_test`**: 8 supplied-pair constructions now go through a local `create-vm-for-caller`, which adds `:call-out-cursor (vm/mint-oldest call-out :test)`.
   - Why: the owner's refuse decision.
   - The oldest mint preserves each test's original read position, for example `history-before-the-cursor-is-not-skipped-test`.
   - The `ns` form gained requires for the new tests.
2. **`semantic_ffi_test`**:
   - The `make-vm` helper adds the oldest cursor when `:call-out` is supplied (refusal).
   - `a-response-for-another-call-does-not-resume-this-one-test` asserted the old post-consumption raise ("does not correlate"). The router now skips the foreign response, so the test asserts: still blocked, one `:unmatched` diagnostic, then its own response resumes it.
   - The now-unused `throws-ex-data` helper was removed.
3. **`debruijn/stack_effects_test`**:
   - The `make-vm` helper adds the oldest cursor (refusal).
   - The "a response for another call does not resume this one" case in `error-responses-surface-as-errors-test` asserted the old raise. It now asserts parked, then resumed by its own response.
4. **`ucf/remote_test`**: the `calling-machine` helper passes `:call-out-cursor (vm/mint-oldest call-out :test)` (refusal). No assertion changed.
5. **`ffi/remote_serve/responder_test`**:
   - `remote-vm` now uses the production readiness step instead of the test-only pre-poll. It takes an optional caller id.
   - `a-call-in-gap-is-reported-as-loss` asserted the generic "FFI response envelope is malformed". It now asserts the exact portable ended loss, and asserts that the malformed message is absent.
   - `a-pair-channel-gap-ends-the-link` probed channel-gone with `stream/cursor out :newest`. The readiness step now files the `:newest` answer, so that anchor is covered by a filed answer. The probe now uses `:oldest`, which nothing filed.
   - The test-8 emitter (a local B pair) passes `:call-out-cursor (oldest (:call-out pb))` (refusal).

## Design choices where the rulings left latitude

1. **Id construction and ownership.**
   - `engine/park-id` exposes the next local id.
   - `ffi/call-id` composes the call id. It lives in `ffi` because `ffi` cannot require `engine` once `engine` requires `ffi`, so each call site computes the id and passes it to `park-continuation`.
   - The predicates live in `yin.vm`, because `empty-state` and the register validators need them without a cycle.
2. **Token domain: a non-blank string or a keyword.** glm listed uuid, but a uuid fails `vm/plain-data?`, which the prompt also requires. I chose plain data. `vm.cljc` validates the format at construction. `caller/open` also checks a round-trip of `[token :parked-0]` through an optional `::codec`. **Uniqueness per tenure is the composition's duty.** Nothing can verify it locally.
3. **Router details.**
   - Waiters are grouped per cursor cell, in wait-set order. Carried cells are included, because grouping is by the entry's `:cursor-ref` id.
   - Each cell reads at most `engine/ffi-response-budget` (64) values per check.
   - Woken entries carry no `:resource-updates`. The router writes the cell's final cursor into `:resources` itself, so resuming one co-waiter cannot rewind a cell another co-waiter already advanced.
   - Reads fold a throw or an uninterpretable answer into `:dao.stream.waitset/invalid-answer`, exactly as the waitset does. `cursor-mismatch`, `invalid-cursor` and `transport-error` raise "Stream read failed" as before. A `refused` or out-of-contract outcome is shaped into the refusal value as before.
   - **One rule of my own:** if a response names the id of a request writer that already woke and sits in `:ready-queue` but is not yet restored as a reader, the run stops *without consuming* it. Otherwise that response would be discarded as having "no live waiter" in the window between the writer waking and its restore. That window opens when another entry is resumed first and blocks, which triggers a check before the writer's restore. The M4 test proves this rule.
4. **Diagnostics.**
   - `{:kind :unmatched :response-id id :cell k}`, or `{:kind :malformed :cell k}` when the value carries no id. A telemetry `:ffi-skip` snapshot is emitted for each.
   - They are **capped at 64, oldest dropped**. On a shared call-out, other callers' responses are ordinary traffic, and an uncapped vector would grow for the life of the VM.
   - `take-ffi-diagnostics` mirrors `take-link-diagnostics`.
5. **Loss.**
   - The router wakes the affected waiters with a status: `:yin.vm.ffi/response-ended` or `:yin.vm.ffi/response-gap`.
   - `throw-terminal-resume!` raises `ffi/throw-response-lost!` before any restore, the same path a refused link takes. The message is `"FFI call failed: <message>"`, matching `call-result`'s apply-error raise.
   - The machine key is `:yin.vm.ffi/loss`, set to `:dao.stream/end` or `:dao.stream/gap`, inside the open error map.
6. **Refusal scope.**
   - A supplied `:call-out` without `:call-out-cursor` is refused, even when `:call-in` is local. The out stream is the shared read side.
   - `:call-out-cursor` without a supplied `:call-out` is also refused.
   - A supplied `:call-in` with a locally created `:call-out` keeps the local mint.
7. **The budget is an engine constant, not a per-VM option.** A per-state override would be silently dropped by the walker's positional `cesk-return`, and the prompt did not ask for a construction option.
8. **The readiness step** returns a plain threaded value. Its statuses are `::pending`, `::ready`, `::exhausted` and `::refused`. The default is 16 asks (`::max-attempts`). It never drives the channel itself: the owner drives between steps.

## Unresolved concerns

1. **A gap wakes every live waiter on that cell as lost.** Some of their answers may still lie ahead of the recovery cursor, but the router cannot know which calls the gap swallowed. The ruling says "wakes the affected waiters", and I read "affected" as all of them. A `:newest` cursor and adequate capacity make this unlikely. glm's point stands: this is why supplied pairs must not scan from `:oldest`.
2. **The stall rule depends on the ready-queue entry being restored.** If a composition drops a VM's ready queue, or never resumes it, its cell stops at that response. It stalls only that VM, never another caller, so I judged it acceptable. The Architect may prefer discarding in that case.
3. **A pre-existing latent issue in the generic sweep, not changed:** co-waiters on one shared cursor that wake in the same round each carry their own successor in `:resource-updates`, so resuming them in order can move the cell backwards. FFI readers no longer go through that path, and ordinary stream readers are unchanged. I only flag it.
4. `caller/open` does not close its reflections when `step` ends `::refused` or `::exhausted`. Closing them is the composition's call.
5. The owner policy items from 3c are unchanged: per-request handler gate, renewal authority, capacities and cadences.

## Incomplete work

None against the brief. Three things did not happen:
- cljstyle was blocked by the approval gate.
- The CLJS log was not written to `collab/`: the `tee` redirect needed approval.
- CLJD was excluded by instruction. The vector-key round-trip test is in place for when that lane runs.

Completed-GMT: 2026-09-29 04:45:25 GMT
Completed-Local: 2026-09-29 11:45:25 +07
Coding-Agent: claude
Session-ID: 08d36f7d-b33d-404e-adcb-dc04d5b907ef (resumed)

# Report: Slice 3d fix round 1 (gate P2: caller readiness leaks reflections)

**Status: fixed.** Every failure path of the readiness step now closes each reflection it acquired, and there is an explicit, idempotent public teardown. The success path hands the reflections on unchanged. I changed only `caller.cljc` and `responder_test.cljc`, and did not stage or commit anything.

## Changes

`src/cljc/yin/vm/ffi/remote_serve/caller.cljc`:
- **`open`**: when call-out fails to attach, the call-in reflection that already attached is closed before the refusal is returned.
- **`step`**: both terminal failures, `::exhausted` and `::refused`, return through `close!`. `::pending` and `::ready` close nothing.
- **New public `close!`**: the teardown for a caller whose readiness ends without a VM.
  - It closes both reflections once and marks the caller `::closed? true`.
  - It is idempotent: a caller that is already closed, or one refused at `open` with no reflections, is returned unchanged.
  - Each close uses a quiet local close. A reflection's `close!` is local: it releases the reflection's pending and outstanding work, reports any outstanding `append!` as `append-unknown`, and never crosses the wire.
- **`ready?`**: now also requires the caller not to be closed, so `call-out-cursor` and `vm-opts` answer nil after teardown.
- The namespace docstring states the ownership rule: every acquired reflection is either handed to the VM through `vm-opts` or closed.

`test/yin/vm/ffi/remote_serve/responder_test.cljc`: new test `a-readiness-attempt-without-a-vm-leaves-no-open-reflection`, with a tracking attacher that records every reflection handed out. A reflection counts as closed when both `cursor` and `append!` answer `:dao.stream/closed`. Its cases:

| Case | What it checks |
|---|---|
| Call-out fails to attach | The refusal names `::call-out ::unattached`. The one acquired reflection (call-in) is closed. |
| `::exhausted` | Max attempts is 1 and nothing drives B. The caller is `::closed? true` and `vm-opts` is nil. Both acquired reflections are closed. |
| `::refused` | The endpoint names an unserved call-out identity, and B is driven until the step settles. The caller is `::refused` with an `::outcome` and `::closed? true`. Both reflections are closed. |
| Ready caller with no VM | Readiness alone closes nothing. `close!` closes both reflections; afterwards `ready?` is false and `vm-opts` is nil. `close!` twice and three times returns the same value. |
| Caller refused at `open` | `close!` is a no-op. |
| Success path unchanged | A VM built from `vm-opts` completes a call (8). `vm-opts` holds exactly the reflections the attacher handed out, in order. |

## Verification

- `clj -M:kondo --lint` on `caller.cljc` and `responder_test.cljc`: `errors: 0, warnings: 0`.
- Focused JVM, `clj -M:test -n yin.vm.ffi.remote-serve.responder-test -n yin.vm.ffi.remote-serve-test -n yin.vm.ffi-test`: **Ran 55 tests containing 553 assertions. 0 failures, 0 errors.** There is no separate caller test namespace; the caller tests live in `responder_test`.
- **Mutation proofs.** Each mutation was applied temporarily, run over `responder-test`, then reverted. `grep -rn "MUTATED\|or false\|::closes" src/cljc/yin/vm/ffi test/yin/vm/ffi` found nothing afterwards.

| Mutation | Result |
|---|---|
| M1: `open` no longer closes call-in when call-out fails to attach; applied together with M2 | 3 FAIL in the new test (1 from the attach case, 2 from exhausted) |
| M2: `::exhausted` returned without `close!` | counted with M1 |
| M3: `::refused` returned without `close!` | 2 FAIL (the refused case: `::closed?` and the closed reflections) |
| M4: `close!`'s already-closed guard removed | **0 FAIL.** This is expected. Re-closing a reflection is idempotent, and the result value is identical, so dropping the guard changes nothing observable. The guard is not load-bearing. |
| M4′: `close!` result changes on each call (a counter) | 2 FAIL (both idempotency assertions) |

## Design notes

- `close!` is a teardown for a caller that ends **without** a VM. Once `vm-opts` has handed the pair to a VM composition, that composition owns the reflections, and the docstring says so. `close!` does not verify this, because nothing in the caller value records whether a VM was built.
- A quiet close swallows a throw from a handle's close: that handle then has nothing left for this step to release. Remote reflections never throw on close.

## Unresolved concerns / incomplete work

None against the brief. cljstyle and the CLJS and CLJD lanes were not part of this round's verification list, and I did not run them.

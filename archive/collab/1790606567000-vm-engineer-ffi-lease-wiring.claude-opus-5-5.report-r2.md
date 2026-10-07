Completed-GMT: 2026-09-28 15:33:59 GMT
Completed-Local: 2026-09-28 22:33:59 +07
Coding-Agent: claude
Session-ID: 7d111381-2e90-40d2-9519-0a15d8ccca19 (resumed)

# Report: Slice 3b fix round 1 (gate P1 and P2)

Role: Yin.VM Runtime Engineer. Model: claude-opus-5-5. Base: master at fd3f0edd, with the round-1 changes still uncommitted on top. Nothing is staged or committed.

## Changed files (this round)

Only the four allowed files changed:

- `src/cljc/dao/lease.cljc`: additive only, under the owner's authorization.
- `test/dao/lease_composition_test.cljc`: 2 new deftests.
- `src/cljc/yin/vm/ffi/remote_serve.cljc`: the P1 and P2 fixes, plus the Q6 docstring text.
- `test/yin/vm/ffi/remote_serve_test.cljc`: the P1 refusal test and a check that the renewal declaration is kept.

## P1: grantor-source collision

- `open!` now refuses any `::lease-media` entry whose `:source` is `rs/grantor`. The refusal is `{::option ::lease-media, ::reason ::grantor-source}`.
- The check sits in `refusals`, so it runs before `make-judge`, before the mirror cursor is minted, and before anything is published.
- The `open!` docstring documents the refusal.
- Tested in `open-refuses-each-missing-or-malformed-lease-option`: a lone claiming medium is refused, and so is a claiming medium listed among valid ones. The existing assertions that nothing was published still hold.

## P2: `dao.lease` public declared-wire and unwire (owner-authorized)

**Changes to `dao.lease`:**

- **Shared per-entry check.** `make-judge`'s per-entry check (entry shape plus `check-medium-declaration!`) is moved unchanged into the private `check-medium-entry!`. `make-judge` now calls it, so its behaviour and refusal order are unchanged, and the validation is shared rather than copied.
- **`make-judge` records two more keys** on the judge state it returns: `:resolver-bindings` and `:durable?`. This is purely additive. It lets a medium wired later be validated against the same declarations the judge was assembled with.
- **New `wire-declared-facts judge entry`.** It runs the same three validations `make-judge` runs for each medium, using the same helpers:
  1. `check-medium-entry!`
  2. `check-derived-medium-refusals!` (a durable judge refuses a host-values medium)
  3. `check-resolver-compatibility!`, checked against the judge's recorded `:resolver-bindings`

  It then wires the medium with the private `wire-declared-medium`, so the declaration stays on the wired entry. A refusal throws ex-info with `:refused` and wires nothing. A judge that was not built by `make-judge` has no recorded bindings and is refused under `:resolver-bindings`.
- **New `unwire-facts judge handle`.** It removes exactly the fact medium whose handle is `identical?` to `handle`. It is idempotent. The ledger, the tick cursors and the other media are untouched.

**Changes to remote_serve:**

- **`serve!`** wires each renewal medium through `lease/wire-declared-facts`, with the declaration from the new public `renewal-declaration capacity`: evict-oldest, the renewal capacity, portable values, per-author media. The new judge value is computed before the state swap, so a refusal would publish nothing.
- **`prune-renewals`** now removes finished media with `lease/unwire-facts`. The `:renewals` map now holds `{rid {:lease l :handle R}}`.
- **Grep check.** Searching `remote_serve.cljc` for `:facts|wire-facts` finds only `lease/unwire-facts` (line 698) and its docstring (line 683). No remote_serve code reads or edits the judge's `:facts` any more. The remote_serve tests still read `:facts` to make assertions, which is test-only.

**New `dao.lease` tests, in `dao.lease-composition-test`:**

- `wire-declared-facts-test`:
  - A valid medium is wired, its declaration is kept on the entry, and a renewal on that later-wired medium counts for its holder.
  - Each invalid wiring is refused under its seam. `:medium-entry` covers a missing cursor or source. `:medium` covers a missing declaration or retention, capacity 0, or a bad value domain. `:attribution` covers the envelope-key case.
  - A durable judge refuses a host-values medium with `:host-values`.
  - A judge built with `initial-judge` alone is refused with `:resolver-bindings`.
- `unwire-facts-test`:
  - Unwiring removes exactly that medium and keeps the others in order.
  - A second unwire gives an equal judge.
  - The ledger is untouched.
  - A renewal appended to the unwired medium does not reach the next `judge-step`: last-observation stays at `{:ms 1}`.

## Notes requested (no code change)

- **Q3 (open renewal).** The gate accepted this as the documented S6 capability-agnostic seam. A renewal gate remains an owner policy decision.
- **Q6 (tolerance and the mirror's read budget).** The gate accepted this as the composition owner's sizing duty. Both the `open!` docstring (under `::lease-tolerance`) and the `step` docstring now state it. A renewal or release left unread on the channel, because it is past `::step-budget` or has not arrived yet, only counts at a later pass. So tolerance has to cover that backlog at the deployment's request rate, and no option can check that.

## Verification (exact)

| Check | Result |
|---|---|
| `clj -M:kondo --lint` on the 4 changed files | errors 0, warnings 1 |
| `cljstyle check` on the 4 changed files | **not run: blocked by the permission gate** ("This command requires approval") |
| `clj -M:test -n yin.vm.ffi.remote-serve-test -n dao.lease-test -n dao.lease-composition-test -n dao.stream.remote-test -n yin.vm.ucf.remote-test` | 142 tests, 1243 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2224 tests, 50312 assertions, 0 failures, 0 errors; "Testing yin.vm.ffi.remote-serve-test" and "Testing dao.lease-composition-test" both appear |
| `bb test:cljd` | not run, as instructed |

The kondo warning is at `test/dao/lease_composition_test.cljc:627:11` ("unused binding traffic"). It is pre-existing: linting HEAD's copy of that file reports the same warning at the same line. It is in the served-connection sketch, which I did not touch.

The new `dao.lease` code uses only CLJC-portable forms. It adds no reader conditionals, and the test catches use `#?(:cljd Object :clj Exception :cljs :default)` with `:cljd` first.

## Unresolved concerns and incomplete work

- **`:self` is not checked on later wiring.** `wire-declared-facts`, like `make-judge`, does not refuse a medium whose source equals the judge's `:self`. The binding refuses that case itself for its standing media (P1), and renewal sources are fresh UUIDs. Adding the check inside `dao.lease` would change `make-judge`'s assembly behaviour, which was out of scope for an additive round.
- **No mutation proof this round.** The prompt did not ask for one, and the round-1 mutations still apply to the unchanged reclaim paths.
- **Not done:** cljstyle (blocked), the CLJD lane (the orchestrator owns it), and 3c.

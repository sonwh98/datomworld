Completed-GMT: 2026-09-29 10:27:25 GMT
Completed-Local: 2026-09-29 17:27:25 +07
Coding-Agent: claude
Session-ID: e2ea0334-6c5f-4339-bd39-f3b0c39625b8

# Report: dao.stream.apply is independent of rpc (one breaking change, not committed)

I implemented all five steps of the ruling (collab/1790675432000-architect-apply-independent-of-rpc.gpt-6-sol.findings.md) on master 643b1ba6. Nothing is staged or committed, and I did not touch docs/orchestrator-log.md.

## Changed files

Source:
- `src/cljc/dao/stream/rpc.cljc`:
  - Renamed the reasons.
  - Added a public `cursor-pending?` predicate. `poll!` now uses it for its anchor branch.
  - `request!` now checks `cursor-pending?` after `:terminal` and before `:unsent`, so the check also applies before an unsent envelope is retried. When the anchor is unresolved it returns `:dao.stream.rpc/cursor-pending` with the identical state.
  - The docstring explains the check.
- `src/cljc/yin/repl/adapter.cljc`: `request-outcome` maps `:dao.stream.rpc/cursor-pending` to `:yin.repl.adapter/cursor-pending`.
- `src/cljc/yin/repl/driver.cljc`:
  - Removed `response-cursor-unminted?`, the driver's own send-safety guard from dce6282c, from both send paths (`handle-line` and `release-queue`).
  - `submit-remote` now takes a `hold` function, which handles `:yin.repl.adapter/cursor-pending`:
    - `handle-line` queues the line at the back (`queue-line`).
    - `release-queue` puts the line back at the front (`requeue-line`), so line order is unchanged.
    - `retry-unsent` leaves `:retrying` as it is.
  - `pending-write?` keeps its cadence behaviour, now through `rpc/cursor-pending?`.
  - `queueable-terminals` is renamed.
- `src/cljc/yin/repl/connect.cljc`: `reattachable?` and `terminal-transition` use the renamed reasons.
- `src/cljc/yin/repl/serve.cljc`: description text only. The `stop!` docstring said `:dao.stream.apply/ ended`, split across a line break so the grep missed it. It now says `:dao.stream.rpc/ended`, translated by the RPC client.
- `src/cljc/yin/vm/ffi.cljc`: `response-lost` code is now `::response-lost` (= `:yin.vm.ffi/response-lost`). `::loss` stays `:dao.stream/end` or `:dao.stream/gap`. Docstring updated.
- `src/cljc/yin/vm/ffi/remote_serve/responder.cljc`: the namespace doc now names `:yin.vm.ffi/response-lost` with `:yin.vm.ffi/loss :dao.stream/end`.
- `src/cljc/dao/stream/apply.cljc`:
  - Removed "transport-neutral" from the namespace doc.
  - `correlation-id?` doc now reads: "any non-nil opaque value. Its allocation policy belongs to whoever uses the envelope, not to this namespace."
  - One code comment changed from "malformed transport implementation" to "malformed stream implementation".
  - No behaviour change. apply's own words, including `gap` and `request-gap`, are unchanged.

Docs:
- `docs/design/dao.stream.md` (~923): after the "Stepped" bullet I added a short boundary statement: rpc owns the client lifecycle and the `:dao.stream.rpc/*` reasons, while apply is independent of RPC and runs over a framebuffer as well as a socket.
- `docs/design/dao.jing.remote.implementation-plan.md`: changed lines 749 and 879 (the two stale apply-qualified examples) to `:dao.stream.rpc/detached` and `:dao.stream.rpc/transport-error`.

Tests (all were on the allowed list; I found no other file that asserts a renamed word):
- `test/dao/stream/apply_test.cljc`
- `test/dao/stream/rpc_test.cljc`
- `test/yin/repl/adapter_test.cljc`
- `test/yin/repl/connect_test.cljc`
- `test/yin/repl/serve_connect_wire_test.clj`
- `test/yin/vm/ffi_test.cljc`
- `test/yin/vm/ffi/remote_serve/responder_test.cljc`
- `test/yin/repl/driver_test.cljc` is unchanged: the race regression passes as written under the new mechanism.

## Every renamed word and where

| Old | New | Sites |
|---|---|---|
| `:dao.stream.apply/not-found` | `:dao.stream.rpc/not-found` | rpc.cljc `transport-error-reason`; connect.cljc `terminal-transition`; rpc_test, connect_test |
| `:dao.stream.apply/detached` | `:dao.stream.rpc/detached` | rpc.cljc `transport-error-reason`, `rebind` (both arities); driver.cljc `queueable-terminals`; connect.cljc `reattachable?`, `terminal-transition`; rpc_test, connect_test, serve_connect_wire_test; implementation-plan:749 |
| `:dao.stream.apply/ended` (rpc) | `:dao.stream.rpc/ended` | rpc.cljc `terminal-lost`; connect.cljc `terminal-transition`; serve.cljc `stop!` doc; rpc_test, connect_test, adapter_test |
| `:dao.stream.apply/no-surface` | `:dao.stream.rpc/no-surface` | rpc.cljc `transport-error-reason`; rpc_test |
| `:dao.stream.apply/oversize` | `:dao.stream.rpc/oversize` | rpc.cljc `transport-error-reason`; rpc_test |
| `:dao.stream.apply/transport-error` | `:dao.stream.rpc/transport-error` | rpc.cljc `transport-error-reason`, `terminal-lost`; connect.cljc `terminal-transition`; rpc_test, connect_test; implementation-plan:879 |
| `:dao.stream.apply/ended` (VM FFI loss) | `:yin.vm.ffi/response-lost` | ffi.cljc `response-lost`; responder.cljc doc; ffi_test, responder_test |
| (new) | `:dao.stream.rpc/cursor-pending`, `:yin.repl.adapter/cursor-pending` | rpc.cljc `request!`; adapter.cljc `request-outcome` |

`:dao.stream.remote/*` and `:dao.stream/gap` are unchanged.

## Test outcomes

- **clj-kondo** on all 15 changed .clj/.cljc files: `errors: 0, warnings: 0`.
- **cljstyle check: blocked.** The command needs permission approval that this session does not have, so it was not run.
- **Focused JVM run:** dao.stream.apply-test, dao.stream.rpc-test, dao.stream.observe-test, yin.repl.adapter-test, yin.repl.driver-test, yin.repl.connect-test, yin.repl.serve-connect-wire-test, yin.repl.serve-test, yin.repl.main-test, yin.vm.ffi-test, yin.vm.ffi.remote-serve.responder-test, yin.vm.ffi.remote-serve-test. All 12 namespaces ran: **160 tests, 1151 assertions, 0 failures, 0 errors.**
- **Full `clj -M:test`:** **2362 tests, 184307 assertions, 0 failures, 0 errors.**
- **`bb test:cljs`:** **2267 tests, 50789 assertions, 0 failures, 0 errors.** The node output shows "Testing" lines for dao.stream.apply-test, dao.stream.rpc-test, yin.repl.adapter-test, yin.repl.connect-test and yin.repl.driver-test.
- **`bb test:cljd`:** not run, as instructed.

**The driver changed, so build/yin-repl-peer is now stale for cross-host tests; the orchestrator needs to rebuild it.**

### Mutation proofs (each applied temporarily, then reverted; the full suites above ran after reverting)
1. rpc `request!` gate replaced by `false`: 18 failures.
   - rpc_test: `request-on-an-unresolved-anchor-...`, `an-unsent-envelope-is-not-retried-...` and `a-terminal-mint-prevents-sending`.
   - adapter_test: `an-unminted-response-cursor-...`.
   - **driver_test `no-request-crosses-before-its-response-cursor-is-minted` (lines 225, 228).** This shows the driver race regression now depends on the rpc gate, since the driver's own guard is gone.
2. "transport-neutral" put back in apply's namespace doc: `apply-has-no-rpc-dependency-or-vocabulary` fails (apply_test.cljc:225).
3. rpc remote `oversize` mapped to `/no-surface`: `no-surface-and-oversize-keep-their-own-terminal-words` fails (rpc_test 503, 506).

### Final grep
```
$ grep -rn ':dao.stream.apply/\(not-found\|detached\|ended\|no-surface\|oversize\|transport-error\)' src test docs
docs/orchestrator-log.md:8317:cells, bounded 64, wakes only the matching waiter); :dao.stream.apply/ended loss error with :yin.vm.ffi/loss end|gap;
```
The only remaining hit is in docs/orchestrator-log.md, which the prompt says not to touch. src, test and the design docs return nothing. I also grepped for the aliased form `apply2?/<word>` and found no hits.

## Acceptance → tests

- **No rpc/transport vocabulary in apply and no rpc dependency:** `dao.stream.apply-test/apply-has-no-rpc-dependency-or-vocabulary`. JVM only, written as `#?(:cljd nil :clj ...)`.
  - It reads apply.cljc's `ns` form from the classpath and asserts the required libs are exactly `#{dao.stream}`.
  - It checks the namespace doc and every public docstring (more than 10 of them) against `rpc|transport|client|remote|socket|wire|network`.
- **Apply request, response and serve-once! over a framebuffer-like medium, with opaque non-numeric ids:** `apply-serves-over-a-framebuffer-with-opaque-ids`.
  - The medium is a test-local fixed-cell reader/writer that answers `full` when the frame is full.
  - The id is the map `{:frame 3 :pixel [10 20]}`.
  - The test also shows an application-owned error code is carried unchanged.
  - The test namespace does not require any rpc namespace.
- **Each remote reason maps to its own rpc word; gap stays distinct; only detached rebinds:**
  - `rpc-test/reflection-transport-error-translates-...`
  - `a-reasoned-mint-failure-reaches-the-terminal-...`
  - `no-surface-and-oversize-keep-their-own-terminal-words`, extended to assert detached and not-found and that all four terminals are distinct.
  - `gap-loss-is-conservative-...` (gap stays `:dao.stream/gap`).
  - `rebind-clears-only-a-detached-terminal-...`
- **`cursor-pending` changes nothing; retryable mint stays pending; successful mint allows exactly one request; terminal mint prevents sending; REPL race still holds:**
  - `rpc-test/request-on-an-unresolved-anchor-is-cursor-pending-and-changes-nothing`: identical state, no id, nothing appended, retryable mint stays pending, then a successful mint allows one request whose answer is read.
  - `an-unsent-envelope-is-not-retried-before-the-cursor-is-minted`
  - `a-terminal-mint-prevents-sending`
  - `adapter-test/an-unminted-response-cursor-is-a-cursor-pending-outcome`
  - `driver-test/no-request-crosses-before-its-response-cursor-is-minted` (unchanged)
- **VM end and gap raise `:yin.vm.ffi/response-lost` with distinct loss facts; remote serving still carries the original request and correlated response:**
  - `ffi-test` loss test at ~470/485.
  - `responder-test` ~589.
  - The existing remote-serve and responder suites pass unchanged.

## Existing tests I modified, and why

- **rpc_test `a-mint-failure-loses-an-already-outstanding-request-too`** is replaced by **`a-terminal-mint-prevents-sending`**.
  - The old test called `request!` on a `:dao.stream/newest` anchor and expected the request to go out before the mint. The new gate forbids exactly that, so the old test's premise can no longer happen through `request!`.
  - The new test asserts what the ruling asks for: cursor-pending, then a terminal `not-found` with nothing outstanding or completed, then `request!` answers `terminal` and nothing was ever appended.
- **rpc_test `at-the-outstanding-limit-...`**: its inline loop that reads appended ops is now the shared `appended-ops` helper. Behaviour is unchanged.
- **rpc_test `no-surface-and-oversize-...`**: added assertions that detached and not-found map correctly and that the four terminals are distinct.
- **ffi_test gap case**: the assertion message changed from "the same terminal code" to "the same VM-owned loss code".
- Everything else is the literal keyword rename.

## Concerns

1. **Terminal mint with outstanding work has no test through `request!`.** The ruling says a terminal mint completes outstanding work through the existing terminal path. After this change `request!` can never create outstanding work while the anchor is unresolved, so that path (`terminal-lost` → `lose-outstanding`) is reachable only from a hand-built state. The code is unchanged and still covered by the read-failure tests.
2. **`docs/design/dao.stream.apply.md` still uses "transport" vocabulary** ("transport-independent request/response layer", "Transport Independence", "Transport Examples"). It is not on the allowed list, so I left it. The owner invariant suggests it should be reworded later.
3. **apply.cljc still handles `:dao.stream/transport-error` as a handle outcome** (lines ~274 and ~351). The ruling says to keep it: it is the `dao.stream` contract's word, not an apply error code. The vocabulary test checks only docstrings, which is what acceptance asks for.
4. **Pre-existing, not introduced here:** a line typed in the same tick in which a held queue becomes sendable is submitted by `handle-line` before `release-queue` sends the queued lines. My hold functions keep the previous ordering exactly, neither better nor worse.

## Incomplete work
- cljstyle check was not run (permission blocked).
- build/yin-repl-peer needs a rebuild by the orchestrator before any cross-host test run.
- Nothing else is outstanding.

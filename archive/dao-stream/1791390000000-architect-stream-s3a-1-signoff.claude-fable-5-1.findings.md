Completed-GMT: 2026-10-07 18:05:00 GMT
Completed-Local: 2026-10-08 01:05:00 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-fable-5-1)

# Lead System Architect: Slice S3a-1 sign-off

Verdict: **PENDING** (Dart lane still running; see §5)

Branch `stream-crossmachine-s3a`, worktree `datomworld-stream-s3a`, based on `01138591`. Reviewed against my own S3a specification (§1, §2.1, §4, §5.1, §6, §7, §8 S3a-1), the engineer report, the adversarial review, and the working tree itself (`git diff`, the three untracked files).

## 1. Verification I ran

| check | result |
|---|---|
| `clj -M:kondo --lint` over the 3 sources and 5 test files | 0 errors, 0 warnings |
| `clojure -M:test -n` remote-channel, ws, ws-project, head-ws | 77 tests, 436 assertions, 0 failures |
| F1 probe (scratch test, run from the test classpath, then deleted) | 8 assertions, 0 failures |
| grep for clock/scheduler reads in changed sources and the fixture | none |
| `bb test:cljd` | see §5 |
| `bb test:cljs` | NOT run: `npm ci` was denied in this session; the worktree has no `node_modules` |

The F1 probe: a bind host that drops `:bind-succeeded`, one dial, one `serve-step` while `:starting` (session adopted), 70 `:listener-error` facts, one more `serve-step`. Result `:refused ::lifecycle-lost`, `unbind!` called once, every session `:closed?`, its ring closed, the client connection closed. The reviewer's leak is gone.

## 2. Invariants and boundaries

- **dao.stream is the sole boundary; no `:ws/` above it.** `remote-channel` formats the descriptor itself (`descriptor-of`); the spec a caller passes carries `:host :port :path` only, asserted by `serve-formats-the-descriptor-below-the-boundary`. No `yin.*` source file changed. The only mention of yin in the new namespace is a docstring naming the `yin.repl.host` seam shape, which is what the spec itself says.
- **No ambient clock, no scheduler.** Every time is the driver's `now`: `give-up-after` through `dial-step!`, `idle-timeout` through `accept-step!`, `stop-grace-ms` as `(- now since)`. The lifecycle grace is driver-paced, never a sleep.
- **No new core stream operation, no public `closed?` on handles.** `channel-lost?` reads `ws-project/closed?` of the projection (an S1 function) and the new `:gone?` field on the channel atom, both below the boundary. The `:gone?` addition is sound: a dial attaches once (`dial-attach!` throws on a second attachment), so the flag can never be stale, and it is the only signal when `:ws/closed` was evicted by a traffic-medium gap (§5.2 row 4). I accept it as the implementation of that row.
- **Release bookkeeping never depends on a callback (D3).** `begin-stop` closes sessions and pending connections before `unbind!`; a throwing or absent `unbind!` is `::unbind-failed` and `:stopped` at once; a silent one completes at the grace.
- **A channel drop is never a source gap (D2).** No path appends to or closes the served table's handles; `close-sessions!` closes the session's socket handle and channel ring only.
- **Stop is explicit, driver-paced, bounded (rule 5).** `stop!` does no I/O (asserted: `unbind!` call count 0 after `stop!`). The first stopping tick is the §4.3 order: `accept-step!` (last bounded answering pass, offers rejected), `close-sessions!`, `endpoint-stop!`, `unbind!`. Completion is the host's `:stopped` fact, a lifecycle gap or end, or the grace.
- **Lifecycle gap: recoverable while serving, terminal while starting or stopping (rule 6).** `lifecycle-lost` matches the §5.1 table, now with release in both starting refusals (F1).
- **`endpoint-stop!`** mirrors the expiry branch of `endpoint-step` exactly and touches only `:pending` slots; acknowledged connections stay the composition's. 1001 `dao.stream/endpoint-stopped` is the code the ws design names.

## 3. Acceptance criteria (§7, S3a-1 column of §8)

All 15 remote-channel cases are present and assert what §7 asks, including the D5 evidence: idle healthy versus blackholed request, flood cannot defer expiry (end to end over S2c), lost close event recovered by the deadline, missing close callback, lifecycle gap while serving and while starting, host stopped under us, session cap and idle expiry under the profile, newcomer during stop, refusals as data. The `ws_test` and both `ws_project_test` cases are present. The fixture move keeps `head_ws_test` green on the JVM. `production-bounds` has the 20 keys of the §1 table (my brief's "17" was the miscount; the table is authoritative). Both design amendments of §6 for this sub-slice are in place and say what the code does.

## 4. Engineer deviations and reviewer findings

All nine engineer deviations are accepted, for the reviewer's reasons. Two I want on the record:

- Deviation 3 (a pending connection at stop is closed by `adopt!` rejection with 1000 `detached`, not by `endpoint-stop!` with 1001): the client sees the same reattachable `:ws/closed` either way, and the 1001 path is covered where it is reachable. The spec's test prose over-specified the code; the behaviour is right.
- Deviation 4 (`continue-stop` keeps running `accept-step!` and `endpoint-stop!`): required so closed sessions are reaped and late pending connections do not outlive the stop. This is the spec's intent.

Reviewer findings: F1 fixed and verified (§1). F4 fixed: the reader conditional at remote_channel_test.cljc:228 now reads `#?(:cljd Object :clj Exception :cljs :default)`. F2, F3, F5 are notes I agree with and carry forward:

- F2: `::unbind-failed` when the host assembly has no `:unbind!` is honest; S3b documents it when it widens the host assembly.
- F3: an `::invalid-spec` refusal for a malformed `:path`/`:host` belongs to S4, where callers other than the board appear.
- F5: cosmetic session reporting after host-stopped; no resource is held.

One small gap of my own, non-blocking: `a-lifecycle-gap-while-starting-is-terminal` does not adopt a session before the gap, so the F1 fix has no committed regression test. S3a-2's reviewer should ask for the probe of §1 to be folded into that case (one extra `serve-step` after the dial and an `every? :closed?` assertion).

## 5. Three-host lanes

The spec (§8) requires green three-lane `bb test` per sub-slice before commit. JVM is green (engineer: 3,699 tests / 237,960 assertions; my independent run of the four namespaces above). Dart and Node status is recorded below.

- `bb test:cljd`: PENDING at the time of this draft.
- `bb test:cljs`: not runnable in this session (`npm ci` denied). The orchestrator must run `npm ci` then `bb test:cljs` in this worktree before commit. The new code uses no host interop, so the only Node-specific risk is `loopback-literal?`'s char handling, which is a verbatim move of code already green on Node in `head.ws`.

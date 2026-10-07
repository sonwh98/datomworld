Completed-GMT: 2026-10-07 17:30:00 GMT
Completed-Local: 2026-10-08 00:30:00 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-opus-5-5)

# Reviewer: Slice S3a-1 adversarial review

Verdict: **ACCEPT** (conditions: green three-host `bb test` at landing; F1 recommended before commit)

## Verification run by the reviewer
- `clojure -M:test -n dao.stream.remote-channel-test -n dao.stream.ws-test -n dao.stream.ws-project-test -n yin.vm.linker.head-ws-test`: 77 tests, 436 assertions, 0 failures.
- `clj -M:kondo --lint` over the 3 sources + 5 test files: 0 errors, 0 warnings.
- Node and Dart lanes NOT run: the worktree has no node_modules. Still owed at landing (§8).
- One scratch probe (run from /tmp, not committed) confirmed F1.

## Findings

### F1 (should-fix, low reachability): a refusal while :starting leaks adopted sessions
`serve-step` runs `accept-step!` while `:starting` (remote_channel.cljc:483), so sessions can be adopted before `:bind-succeeded` is read. Two paths refuse without releasing them: `lifecycle-lost` `:starting` (l.398–400: `unbind!` + `endpoint-stop!` only) and `observe` `:bind-failed` (l.379–380). Neither stops the acceptor or closes sessions. A refused server is never stepped again, and `stop!` on it is a no-op. That contradicts spec §4.3, "a refused server holds nothing".
Probe: a bind host that drops `:bind-succeeded`, one dial, one tick (1 session adopted), then 70 `:listener-error` facts and another tick. Result: `:refused ::lifecycle-lost`, 1 session with `:closed? nil`, its ring still accepts appends, and the client connection is still open.
Reachability: a conforming host deposits `:bind-succeeded` before any connection can arrive, so the session must be adopted before that fact is read. That means a late or out-of-order deposit, which is exactly the "lost bind-succeeded" case §0 rule 6 is meant to cover.
Fix: call `(release! server)` in both refusal paths, i.e. `(-> (release! server) …)` before `refused` in `lifecycle-lost :starting` and `observe :bind-failed`. Extend `a-lifecycle-gap-while-starting-is-terminal` to tick once after the dial (so a session is adopted) and assert every session is `:closed?`. The spec's §5.1 table names only unbind + endpoint-stop, so this is a spec gap the engineer followed literally. Not blocking.

### F2 (note): `::unbind-failed` and a missing `:unbind!`
`unbind!` (l.347–356) answers false when the host assembly has no `:unbind!`, so stopping is `::unbind-failed` on the first stopping tick. That is honest, but neither the `serve-step` nor the `stop!` docstring says so, and the spec's host shape says any of the three seams may be absent. Document it. Also, in this outcome the listener may still be live and nothing drives the endpoint any more, so a connection that arrives later sits pending until the host closes it. This follows the spec ("no later completion can arrive") and is recorded for S3b/S4.

### F3 (note): a bad spec `:path` or `:host` throws instead of refusing
`serve` validates transport, port and table, but a spec with no `:path` or a non-string `:host` reaches `ws/make-endpoint`, which throws "invalid served descriptor". The spec only lists bounds as rethrown composition errors. The board always passes "/head", so nothing breaks today. Consider a `::invalid-spec` refusal (or `ws/descriptor?` on `descriptor-of`) when S3b/S4 widen the callers.

### F4 (note): Dart lane risk in the new test
remote_channel_test.cljc:228 uses `#?(:clj Exception :cljs :default :cljd Object)`, with `:clj` before `:cljd`. The repo has a known trap where the cljd host-eval pass takes `:clj` branches. Other repo tests use both orders, so this may be fine, but it has not been run on Dart. Prefer the `#?(:cljd Object :clj Exception :cljs :default)` order the source file already uses, and confirm in the three-host run.

### F5 (note): host-stopped while :starting/:serving leaves closed sessions un-reaped
When `drain-lifecycle` turns the server `:stopped` (host-stopped), `accept-step!` is skipped (l.483), so `sessions` keeps reporting the closed-but-not-reaped sessions. The resources are already closed, so only the reporting is affected. Cosmetic.

## Item-by-item
1. `endpoint-stop!` (ws.cljc:762): mirrors the existing expiry branch of `endpoint-step` exactly (phase → `:closed`, `invoke-close!` 1001 `dao.stream/endpoint-stopped`, `terminal!` guarded by `:terminal?`, `release-slot!`). It only touches `:pending` slots; a `:reserving` slot claimed concurrently by a host thread is skipped and handled on the next tick by `continue-stop`. Idempotent. The `ws_test` case covers 1001 on both seams, both terminals, freed slots, a stale late ack, and a second call as a no-op. Correct.
2. `ws-project` `stop!` / `close-sessions!` / stopping `adopt!`: the rejection reuses the cap path (handle closed, no ack, no media). `close-sessions!` runs outside `swap!`, like `reap-sessions!`. The `:gone?` addition to `dial-step!` is justified: it is the only signal when `:ws/closed` was evicted, and `a-lost-close-event-is-recovered-by-the-deadline` exercises exactly that path (the ring stays open and the dial goes `:lost` through `:gone?`). Correct.
3. `remote_channel.cljc`: all 20 profile keys are present and match §1, and every layer receives its keys (asserted). Bounds are validated by their own layers before `bind!`. Refusals are returned as data. Stop order is accept pass → stop/close sessions → endpoint-stop → unbind; completion comes from the host's `:stopped` fact, a lifecycle gap/end, or the grace measured against the driver's `now`. The lifecycle table matches §5.1 apart from F1. `dial-step` (resolving/attached) matches §2.1. `close!` is idempotent.
4. Engineer deviations 1–9: all accepted.
   - 1: there are 20 keys and the §1 table is authoritative.
   - 2: `make-acceptor` never checked host-object cursors, so the spec's mechanism did not exist; a structural check is the right substitute.
   - 3: a pending connection at stop is closed with 1000 `detached` through adopt rejection, not 1001. The client sees the same reattachable `:ws/closed`. The 1001 path is covered elsewhere.
   - 4, 5: the extra work in `continue-stop` and the host-stopped release are needed for reaping and closing late arrivals.
   - 6: a newcomer's resolution is the honest `transport-error`/`channel-gone`.
   - 7: the double poll is required by the prefetched `blocked`.
   - 8: `unlisten!` depositing `:stopped` is a fixture-only change.
   - 9: head.ws is left for S3a-2.
5. Tests: §7's 15 remote-channel cases, the `ws_test` case and both `ws_project_test` cases are present and meaningful. The fixture move into `loopback_net.cljc` keeps `head_ws_test` green. Gaps: F1 has no test, and no test asserts that the 1001 close reaches a peer through `remote-channel` stop (by design, deviation 3).
6. Invariants: no clock read and no scheduler in any changed source (grep: no currentTimeMillis/js/Date/DateTime.now). Every time is the driver's `now`; the endpoint's `:opened-at` comes from the host's injected clock. No new core stream operation and no public `closed?` on handles. Transport neutrality holds: `:ws/` vocabulary stays below dao.stream in `remote-channel`, and no `yin.*` file was touched except the test fixture import.
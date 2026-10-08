Completed-GMT: 2026-10-07 08:16:30 GMT
Completed-Local: 2026-10-07 15:16:30 Asia/Ho_Chi_Minh
Coding-Agent: cmd (qwen/qwen3.8-max)
Session-ID: 527bfcf0-1e91-4a7b-b28b-48a7ed7100b8

Write permissions are blocked in this session (both the plan file and `collab/`), so I'll deliver the complete sign-off report here for saving to `collab/1791360593148-architect-stream-s2c-signoff.qwen3.8-max.findings.md`. The review itself is complete — I verified the implementation firsthand, read-only.

---

Completed-GMT: 2026-10-07 08:10:00 GMT
Completed-Local: 2026-10-07 15:10:00 Asia/Ho_Chi_Minh
Coding-Agent: cmd (qwen/qwen3.8-max)

# Lead System Architect: Slice S2c sign-off — request liveness and channel expiry

Worktree `/Users/sto/workspace/datomworld-stream-s2`, branch `stream-crossmachine-s2`, base `91ab84f7`, uncommitted S2c diff.

Read firsthand for this sign-off: `remote.cljc` (`new-deadline`, `register!`, `send-request!`, `count-ask!`, `channel-loss!`, `drain!`, `refl-append`, `send-named!`, `link-step!`, `links`), `ws_project.cljc` (`dial` policy select-keys, `establish!`, `dial-step!` both arities), `dao.stream.remote.md` §2.4 Channel loss + Expiry, §2.5, §3.0 Request-liveness bullet, the S2 architectural spec (`collab/1791353752491-...claude-fable-5-1.findings.md` §0 rule 5, §1.2–§1.3, §2, §3, §4 S2c row), the author report (`collab/1791358000000-...claude-opus-5-5.findings.md`) and the independent review (`collab/1791359934597-...qwen3.8-max.findings.md`). Shell execution was blocked in this session; the cited local verification (83 tests / 609 assertions / 0 failures, cljstyle and kondo clean) is taken from the author report and the dispatch evidence, and is made a landing condition below.

## 1. Expiry contract and behavior

**Ruling: upheld.**

**Drain-then-scan ordering.** `link-step!` records `:now`, runs the budgeted `drain!`, then scans `(concat outstanding pending)` for the least id with `:deadline <= now` (remote.cljc:1113–1121). Draining first is the correct ordering: an answer arriving in the deadline's own tick clears its entry before the scan, minimizing false loss without weakening the bound. The scan is independent of the drain budget, so a flood saturating the budget cannot defer expiry — it can only cause a false loss, exactly the D3 trade-off the mob consensus accepted and `flood-cannot-defer-expiry` demonstrates. No valid concurrent answer is dropped by the ordering itself: answers already drained are filed before the scan runs.

**Whole-channel loss on one expired request.** Architecturally correct and the only response consistent with the design stance. `give-up-after` is a liveness statement about the *channel*, not the request (spec §1.3; doc §2.4): on an ordered reliable channel, the least-id request unanswered past its deadline means the peer is not serving — later in-flight requests are either equally unserved or their answers were lost with the channel. Reusing the existing `channel-loss!` path (remote.cljc:698–711) adds no new loss semantics:

- `append-unknown` per abandoned append is the honest 2.5 report: `append!` is never re-sent, so an unanswered append's effect must be reported unknown, never guessed. Abandoning *unexpired* concurrent appends reports them the same way — correct, since the channel that would carry their answers is declared dead.
- Non-retryable `channel-gone` for `cursor`/`next`/`resolve` is right: a retry over a dead channel is meaningless; redial is the caller's/driver's policy (S3a). `descriptor` still answering `ok` preserves "a name outlives what it named".
- Idempotent requests lost with the channel are recomputable after redial; ids are asker-minted per link, so a fresh dial mints fresh ids — no stale-id hazard.
- Yin observes only the `channel-gone`/`transport-error` it already handles; the new `channel-expired` event goes to the composition's event writer for observability, distinguishing expiry from `end`. No new head value (D3 preserved).

The `channel-gone?` guard on the scan (remote.cljc:1116) prevents a double report when an `end`-loss and an expiry coincide in one step. Verified.

## 2. Dial teardown

**Ruling: sound and idempotent.**

Ownership follows the stance rule: whatever owns the connection closes it on loss, and the dial owns the ws handle. `dial-step!`'s 2-arity orders projection → link step → mirror → close (ws_project.cljc:600–619): the projection runs before the link step so freshly deposited answers reach the link before the expiry scan; the close is guarded by `(:dao.stream.remote/channel-gone? stepped)` and `(stream/closable? handle)`, and `close!` is idempotent — repeated post-loss ticks are safe (a nil `stepped` is falsy; after loss, `link-step!` still answers `channel-gone? true` with `expired nil`, and `closable?` is false once closed). The teardown then flows through the host's `:ws/closed` deposit → projection closes the ring → the composition observes ordinary closed state. Tested end-to-end in `dial-step-with-now-closes-the-handle-on-expiry`.

The 1-arity delegation preserves every existing consumer (`yin/repl/connect.cljc`, `yin/vm/linker/head/ws.cljc`) unchanged — zero-breakage by construction, per the S2 spec's nil-default discipline.

`establish!` stepping the new link at the dial's recorded `now` before the attach probe is sent (ws_project.cljc:513–514) is the one beyond-the-letter behavior. **Ratified.** Without it, the probe `dial-attach!` sends immediately would be unstamped and the establishment stall Expiry exists to bound (dao.stream.ws.md Deferred, first bullet; spec §1.3's deliberate kept-probe deviation) would go unbounded on the dial path. The mechanism is minimal: `:step` creates the link via `link-for!` and pre-records `:now`, so the later `:attach` reuses the same link. A clock-less dial is untouched.

## 3. Findings ruling

**Finding 1 (P3, `(step cd nil)`).** Confirmed in code: `link-step!` unconditionally records `:now nil` (erasing a previously stepped `now`) and the scan's `(some-> (:deadline e) (<= now))` throws on `(<= deadline nil)` once any entry carries a deadline — on all three hosts. Unreachable via `dial-step!` (guarded by `some? now` at ws_project.cljc:608), but `:step` is public API of `links`. **Ruling: acceptable for S2c; required fix before any composition outside ws-project consumes `links` `:step` directly — track as an S3a follow-up.** The guard should *skip*, not throw, on nil `now`: skipping matches the documented "without a stepped `now`, nothing expires" and keeps `:step` total; throwing would make `(step cd nil)` a hazard the 1-arity `dial-step!` delegates into by design.

**Finding 2 (P3, requests existing before the first timed step).** Confirmed: stamping happens at first existence on the link; nothing backfills at the first timed step. This follows the spec's letter ("before any step with `now`, nothing is stamped"); the spec is silent on backfill. **Ruling: acceptable as an S3a follow-up, resolved by driver contract, not by backfill.** The S3a driver must step with `now` before first attach/resolve — the dial path already enforces this via `establish!`; the direct-`links` path must document it (S3a doc note in §2.4 Expiry). Backfilling at the first timed step is rejected for now: stamping a long-standing request `now + give-up-after` at driver start conflates driver-start with send time, and changing deadline semantics is a spec amendment requiring its own review. If S3a finds a driver that genuinely cannot step-before-attach, revisit backfill as an explicit spec amendment then.

**Findings 3–5 (P4).** Double `new-deadline` computation in `send-named!` (pure; let-bind as S3a hygiene), `:pending` surviving `channel-loss!` (inert: `drain!` wraps everything including `retry-pending!` in `when-not channel-gone?`, remote.cljc:747; optional hygiene), and the missing named-request expiry test (add alongside finding 1's guard test in S3a). All non-blocking.

## 4. Verdict

**SIGN-OFF GRANTED** for staging and committing Slice S2c as
`feat(dao.stream.remote): request deadlines, channel-expired and link step (cross-machine stream slice S2c)`,
under these landing conditions:

1. The committer re-runs `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test` (expect 83 tests / 609 assertions / 0 failures / 0 errors), `cljstyle check` and `clj -M:kondo` on the four changed files, plus the full `bb test` three-host lane before the sub-slice commit, per `docs/agents/build-n-test.md` — neither the reviewer nor this sign-off could execute them in-session.
2. S2c lands alone as one commit; S2d does not rebase over unlanded S2c assumptions.
3. Tracked as S3a follow-ups (record in the S3a dispatch): finding 1's nil-`now` guard on `link-step!`; finding 2's driver-obligation doc note in §2.4; finding 5's named-request expiry test; findings 3–4 as hygiene.

The slice implements the S2 spec §1.2/§1.3 S2c rows, the §2 failure-table expiry rows, the §3 S2c test plan and the §4 doc amendments faithfully. Deadline discipline is verified on every path: stamped at first existence (accepted or kept), kept probes carry their original deadline (the pending lookup precedes the cond in `send-request!`, so a retry never restamps), waiting probes unstamped until `admit-probe!`, `count-ask!` never moves a deadline, answers clear entries deadline-included. The reflection still reads no clock; `now` is the driver's alone. Expiry is whole-channel loss through the existing path, and the connection owner tears down. D1/D2/D3, S0 and S1 invariants are preserved.

## 5. Verification of this pass

Read-only. No source, test, doc or collab file was modified by the review itself; shell execution and file writes were blocked in-session, so no tests were run and no `git diff` executed — verification was against the full working-tree files, cross-checked line by line against the author report and the independent review, whose finding locations all reproduce.

---

To land this report at `collab/1791360593148-architect-stream-s2c-signoff.qwen3.8-max.findings.md`, re-run me with write permissions (or exit plan mode) and I'll save it.

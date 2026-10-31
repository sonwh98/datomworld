# Lead System Architect — Slice S2a sign-off

**Architectural Sign-Off: GRANTED.** The reviewed S2a diff is architecturally approved for staging and committing to `stream-crossmachine-s2`, subject to the existing pre-commit verification gate below. No architectural changes are required. This sign-off does not certify that the remaining landing checks have passed.

## Scope and evidence

Reviewed the five-file working-tree diff against HEAD `65f635d0869f848d590924bf6319f9b125861cd9` in `/Users/sto/workspace/datomworld-stream-s2`; confirmed branch `stream-crossmachine-s2`. The implementation is uncommitted atop that HEAD.

Read the master foundations, `docs/design/dao.stream.remote.md`, the relevant core stream contract, architect specification `1791353752491-architect-stream-s2-spec.claude-fable-5-1.findings.md` §1.1, §1.2, §3 and §4, both implementation reports, and both independent review reports. Inspected the implementation and all changed tests directly. Round 2's ACCEPT is consistent with this architectural review.

## Contract assessment

| Checkpoint | Finding |
|---|---|
| Sole abstraction boundary | PASS. `dao.stream` remains the cross-machine boundary. The diff adds composition policy and enforcement in `remote` and `ws-project`; it adds no core operation, outcome, transport knowledge in higher interpreters, registry, ambient clock, scheduler, or application callback. Requests and answers remain stream values. |
| Mirror bounds and continuation | PASS. `remote.cljc`'s six-argument `mirror-step` validates bounds before touching handles. Nil is unbounded; non-nil bounds must be a map whose values are nil or positive integers, matching the specification. Each successful wire read, including malformed input, and each gap consumes one unit. Exhaustion returns the current opaque cursor before another read; blocked/end terminate without inventing progress. Four- and five-argument forms delegate with nil bounds. |
| Chase clamp | PASS. `answer!` uses `min(peer-budget, local-chase-budget)` only for a valid peer-budgeted next with an initial ok. The first outcome counts toward that allowance, so at most k−1 further outcomes are chased. Missing peer budget never enables a chase. `chase` continues through middleware and stops at the first non-ok outcome, preserving source recovery data. |
| Full-writer rewind | PASS. `write-answer!` propagates the actual final writer outcome, including an oversize replacement's outcome. A full answer for descriptor, named descriptor, cursor or next stops at the cursor preceding that request. Earlier accepted answers are not replayed. Recomputing an equally true answer follows §2.5; it need not be byte-identical if the source changes. |
| Append semantics | PASS. A full append-answer write advances past the request, preventing reapplication of the source append through this rewind mechanism. The answer remains unknown to the asker under existing loss semantics; this introduces no exactly-once guarantee. Other refusals retain the existing unanswered-request behavior. |
| Surfaces and source authority | PASS. Identity lookup and declared-surface checks precede execution. Cursor, next, append and chased next operations retain `middleware/apply-request`; descriptors retain the explicitly specified direct descriptor path. No new path constructs or rewrites source cursors, anchors, gaps or outcomes. |
| Acceptor and dial wiring | PASS. Both constructors validate and retain their new bounds. Acceptor passes mirror/chase bounds per session. Dial passes step-budget to projection and mirror/chase bounds to the mirror. Nil defaults retain prior unbounded policy; full-writer rewind is the intentional behavior change. |
| Single-driver ownership and purity | PASS. `dial-step!` evaluates mirror effects once outside an atom update and stores the returned cursor with `reset!`. Acceptor likewise computes effects before its state-only update. These are effectful stream interpreters with explicit ownership, not referentially pure functions; the relevant purity checkpoint is that retryable atom functions do not execute stream effects. Concurrent or reentrant stepping of the same composition is not supported by this sign-off. |
| Design alignment | PASS. The changes to remote design §2.3 and §3.0 describe the new arity, validation, counting, clamp, rewind/drop rule and composition wiring. They preserve the core contract and the separation between host transport and interpretation. |

The mirror-budget × chase-budget bound describes served-operation work when both bounds are configured. It does not count channel reads/writes, descriptor bookkeeping, middleware's internal work, or wall-clock duration; budgets are per session, not an aggregate acceptor tick quota. Nil defaults do not establish a finite production resource profile. These limits follow the approved S2 specification and are not blockers for S2a.

## Review reconciliation and coverage

R1 is resolved: the old effectful `swap!` in the dial path is gone. The regression changes cursor object identity during a source append and checks one source append and one answer. Round 2 independently restored the old behavior in memory and observed both assertions fail, establishing that the regression detects the retry defect. Replacing `swap!` with `reset!` is sufficient under single-driver ownership; it does not serialize overlapping ticks.

The changed tests cover budget continuation, malformed reads and gaps, invalid bounds, chase clamping, all four idempotent rewind cases, append non-reapplication, acceptor/dial propagation, and the atom-retry regression. Cursor/next rewind tests use a noninitial request position and verify that a preceding accepted answer is not duplicated. Optional clamp-one, absent-peer-budget and combined chase/full cases remain useful future coverage, not architectural blockers.

The four asking-side policy keys and timed `dial-step!` arity mentioned in specification §1.2 depend on S2b/S2c and are correctly deferred under §4's slice boundaries. S2a does not certify link retention bounds, deadlines, outbound/frame bounds, adoption isolation, or production non-loopback readiness.

## Verification and landing disposition

Accepted evidence, rather than rerun in this architectural pass:

- Implementer and independent Round 2 reviewer report `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test`: **64 tests, 487 assertions, 0 failures, 0 errors**.
- Reconciliation report records clean cljstyle and kondo with **0 errors**, plus one preexisting unused-binding warning at `remote_test.cljc:661`.
- Round 2 records the successful in-memory regression mutation check described above.

The supplied reports explicitly do not establish the full JVM/Node/Dart landing gate. Architect specification §3 requires a full `bb test` before each sub-slice commit; `docs/agents/build-n-test.md` likewise states that the full three-lane run is the gate before a commit. **Architectural approval is granted; an unconditional “ready to commit now” claim is not supported by the supplied evidence.** The landing owner must complete and record the prescribed green lanes before committing. This is an existing verification requirement, not a new architectural change request.

Only this findings file was written. No implementation, design, test, index, or commit was changed; no runtime tests were executed in this pass.

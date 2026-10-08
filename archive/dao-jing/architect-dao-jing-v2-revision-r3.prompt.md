Created-GMT: 2026-09-06 17:13:57 GMT
Created-Local: 2026-09-07 00:13:57 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing.v2 migration plan — revision 3
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 00:13:57 +07 | Status: active | Rationale: resumed author session; final corrective pass after the confirmation round

The confirmation round is closed. Both original reviewers were resumed in their
own sessions and confirmed **all thirteen** prior findings addressed:

- collab/reviewer-dao-jing-v2-confirm.gpt-5.6-sol.findings.md
- collab/adversarial-dao-jing-v2-confirm.glm-5.3.findings.md

Read both. `glm-5.3` accepts the Decision 2 rejection and independently
verified its anchors. `gpt-5.6-sol` dissents on it — that dissent stands as a
recorded dissent, not as a reopened question; do not re-argue Decision 2's
conclusion.

Produce the **complete revised plan document** (revision 3) as the body of your
final response. Do not edit any file. Your revision 2 is now at
`docs/design/dao.jing.v2.implementation-plan.md`; the orchestrator will
overwrite it with this one. Write the whole document.

Five items, and nothing else.

## 1. J3c's verify hop has no owner — the one real defect (both reviewers)

"On a `:present`, issues a second correlated `:jing/get-content`" is
subject-less. `glm-5.3` states the bind precisely: either `step` issues it,
which means the client retains an unfulfilled issuance as state and retries it
each step — an obligation inside a client your Decision 3 deliberately kept
queue-free (round-1 contested item 6) — or the driver re-drives it, and
re-calling `request-materialize` mints a fresh put and id, orphaning the
original per-id record. As written, a `:present` arriving while the writer
answers `full` leaves the materialization with no completion, no `busy` the
driver can clear (`step`'s unsent re-attempt covers RPC-layer unsent envelopes,
not an unissued verify hop), and no specified exit.

**Decide it, and say which principle you are spending.** If `step` owns the
issuance, say plainly that this is the one obligation the client carries, why
it is not the queue you refused, and how it is bounded. If you expose
`resume-materialize` instead, say what the driver owes. Add the test both
reviewers ask for: put completes `:present` while the writer is scripted
`full` → record at `:verify`, no completion; next step issues the get; exactly
two ids at completion. State that the per-id record registers under both the
put id and the verify-get id.

## 2. The move-out inventory is wrong: nine test files, not seven

This was the orchestrator's error, propagated into the round-2 notes as
verified fact and built into your C2 cost argument. The correct set of test
files calling `observer-state`/`observe-step!` is **nine**: the five
`test/dao/space/` files, `jing/mem_test.cljc:213`, `jing/dht_test.cljc:402`,
**`jing/file_test.cljc:113-135`** (`observer-convergence-test`), and
`jing_test.cljc`'s own observer section. Also correct the per-file cost claim:
"one require and one alias each" understates it — `mem_test.cljc` uses `jing/`
for both core (`segment-key`) and observer functions, so the repoint adds a
second alias plus per-call-site edits, and `mem_test`/`dht_test` carry their
own `open-stream` helpers. Per `glm-5.3`: rewrite the inventory as nine
affected files — seven repoints, the `jing_test.cljc` section move (the shared
`mem-handle` helper at :18 is used by both the moving and staying sections and
must be extracted or duplicated), and the `file_test.cljc` excision — and
require J1.1 and J1.2 to land as one change, since J1.1 before J1.2 is a
compile hazard. The conclusion — move-out beats extraction — survives; the
cost does not.

## 3. Propagate the documentation-route fallback

Under the fallback, J2's deliverable ("no namespace under `dao.jing.v2*`
requires `dao.stream` directly or transitively") and Decision 4's `dao.jing`
row ("none, direct or transitive") are false as written. The end condition
carries the alternative clause; J2's gate and the table do not. Attach the same
clause to both, and make the `dao.jing` row read `dao.stream` (observer only)
under that route.

## 4. Decision 2's rationale contains a checkable overstatement

"Portable code never receives a handle to that stream" is false today:
`dao.jing.file`'s handle exposes the log under `:log` (`file.cljc:197`) and
`file_test.cljc:131` reads it via `count-records`. The conclusion stands —
exposing the handle still made the put no emission — but a contested
decision's rationale must not contain a false checkable claim. Qualify it: no
caller of `materialize!`/`get` ever reaches the log; the `:log` key was
test-visible; J1 removes it.

## 5. Correct the no-wait argument — it was overstated, in your favour

The orchestrator's N-a note, which you relied on, said a conforming v2
append-log "could not simply wrap synchronous file writes". `gpt-5.6-sol`'s
dissent establishes the sharper and correct version, which `glm-5.3`
independently reached: such a transport **is** constructible, because
`append!`'s `:dao.stream/ok` "implies neither local readability nor remote
delivery" (`dao.stream.md`, Writing). What it cannot do is support
`materialize!`'s `:inserted` meaning "durably stored now". So option (a)'s cost
lands on `materialize!`'s contract and its synchronous consumers, not on the
transport's constructibility. Restate Decision 2's rationale accordingly — the
weaker, true argument, not the stronger, false one.

Additionally: record `gpt-5.6-sol`'s dissent in the *Divergence register* by
name, with what it holds and what the plan holds, so a later reader sees the
decision was contested and by whom.

Change nothing else. Everything else in revision 2 was confirmed by both
reviewers. End with a short list of what changed from revision 2.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

Created-GMT: 2026-09-06 17:44:34 GMT
Created-Local: 2026-09-07 00:44:34 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: dao.jing.v2 plan — revision 2 to 3 delta review
Role: Adversarial Code Reviewer and Security Auditor
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 00:44:34 +07 | Status: active | Rationale: resumed original adversarial session; it raised the J3c gap and framed the principle question this revision answers

Narrow scope. Review **only what changed** between revision 2, which you
confirmed, and revision 3, which nobody has reviewed.

- `collab/dao-jing-v2-r2-r3.delta.diff` — the unified diff, 542 lines
- `docs/design/dao.jing.v2.implementation-plan.md` — revision 3 in full,
  for context around any hunk
- your own confirmation findings:
  `collab/adversarial-dao-jing-v2-confirm.glm-5.3.findings.md`

Revision 3 exists to answer the one gap and four precision defects you and
`gpt-5.6-sol` filed against revision 2. 358 lines differ.

## Settled — do not reopen

The seven contested items (C1-C6, N1) were settled by a two-round consensus
between `claude-fable-5-1` and `gpt-6-astra` and are not in scope: Decision 2
stands, the observer moves out, the materializer belongs in this plan, the
three-namespace end state, abandon-on-terminal, pass-through totality, and
retiring the orphaned transport with its replacement. `gpt-5.6-sol`'s Decision
2 dissent is recorded in the plan by name and is closed. Everything you and
`gpt-5.6-sol` confirmed as ADDRESSED in revision 2 is confirmed; do not
re-verify it.

## What to judge

**1. The verify-hop ownership decision — the question you raised, answered.**
You wrote that the two consistent resolutions "differ on a stated design
principle": `step` issuing the hop means the client retains an unfulfilled
issuance as state, which is the queue Decision 3 refused in round 1, and the
revision never acknowledged that consequence. Revision 3 chooses `step`, adds
it as step order 4, and argues at plan:414-427 that a verify hop is not the
queue that was refused — second half of one already-submitted operation,
bounded by materializations in flight, consumes no new caller work, visible as
`:verify-pending` in state the driver owns. It also rejects a public
`resume-materialize`.

Judge the argument, not the fact that one was made. Is the distinction real or
is it a rationalisation? Does the mechanism actually work: a hop that becomes
the RPC layer's `:unsent` and is re-attempted by order 1, registration of the
per-id record under both the put id and the verify-get id, ordering between
order 1, order 3 and order 4, and terminal completing `:verify-pending`
records exactly once? Can a materialization still be left with no completion
and no exit, which is the defect you originally found? Check the ordering
interactions specifically — order 4 issues a hop, and order 3 abandons on
terminal; a hop issued at order 4 that fails could interact with the next
step's order 1.

**2. Did your four precision defects land correctly?** Nine test files with
the second-alias and per-call-site cost stated; the documentation-route
fallback propagated to Decision 4's rows, J2's deliverable, J1's deliverable
and J5; the `:log` overstatement qualified; the no-wait argument restated in
its weaker true form with the dissent recorded. Say ADDRESSED or not, briefly
— these are corrections, not designs.

**3. New defects in the 358 changed lines.** This is the third draft by an
author who has been told twice it was wrong, and revision 2's own new material
is where the last gap came from. Report as
`P0-P3 | plan section or diff hunk | evidence | concrete fix`, or "none found".

Verified facts, settled, do not re-derive: `rpc/request!` is the sole unsent
retry path and `poll!` only reads; `abandon-unsent` exists and
`lose-outstanding` covers `:outstanding` only; `allocator-error` exists;
`materialize!` derives the address and verifies read-back on `:present`; nine
test files call the v1 observer; `log_test.cljc:104` writes a non-decodable
fixture; `:append-log` has one non-test consumer. Suite green per the user;
you have no authority to run tests.

Do not edit any file. Produce the complete response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac

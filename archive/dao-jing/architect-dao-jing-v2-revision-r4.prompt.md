Created-GMT: 2026-09-06 17:49:57 GMT
Created-Local: 2026-09-07 00:49:57 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing.v2 migration plan — revision 4
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 00:49:57 +07 | Status: active | Rationale: resumed author session; closing the one execution gap in J3c's new mechanism

`glm-5.3` reviewed the revision-2-to-3 delta. Read its findings:
`collab/adversarial-dao-jing-v2-delta.glm-5.3.findings.md`.

**Your verify-hop decision was accepted.** It ruled the principle argument
"real, not a rationalisation", and found a precedent you did not cite that
strengthens it: `dao.stream.rpc`'s own `:unsent` and
`apply/serve-once!`'s `:pending-response` are already machine-internal
obligations retained as data and driven by the caller's next call, so a client
carrying one is pattern-consistent rather than exception-carved. Consider
citing that. All four precision corrections landed. Do not re-argue any of it.

Two items, and nothing else. Produce the **complete revised plan document**
(revision 4) as the body of your final response; the orchestrator overwrites
`docs/design/dao.jing.v2.implementation-plan.md` with it. Do not edit files.

## 1. P1 — the verify hop's loss path has no route home

Registration under the verify-get id happens only "on `requested`"
(plan:404-406). A hop that is allocated but lost *before* `requested` produces
a completion whose id no record knows. Verified in `rpc.cljc`:
`attempt-unsent`'s `closed`/`invalid-value`/`transport-error` branch calls
`append-completion` immediately (rpc.cljc:211-218) and answers
`request-undeliverable` — the request completes with its id, never having been
`requested`. Three ordinary paths reach the window: the writer answers
`closed`/`transport-error` at issue; a `full`-deferred hop whose order-1
re-attempt comes back undeliverable; the driver calls `abandon` while a
deferred hop holds `:unsent`. In each, the wrapper publishes a phantom `:lost`
get completion for an id the driver never received, and the record stays
`:verify-pending` forever — not in `:unsent`, not terminal, swept by nothing.
Your own plan:395-396 promises "a `:lost` on either hop completes the
materialization as `:lost` with the reason"; the mechanism cannot deliver it
in exactly the loss paths.

Apply the fix: **register the record under the get id as soon as
`rpc/request!` returns one** — `requested`, `pending-request` and
`request-undeliverable` all carry `:dao.stream.rpc/id` (rpc.cljc:198, 203,
211, 223), so this is available. State that any `:lost`/`:error` completion on
a registered hop id completes the materialization exactly once, with the
record's phase transition as the guard. **Assign the terminal sweep to a step
order** — it is currently assigned to none (plan:410-412); order 4 completing
every `:verify-pending` as `:lost` instead of issuing, when terminal, is the
natural home. Add the three tests: undeliverable at issue (scripted `closed`
writer); undeliverable on the order-1 re-attempt of a deferred hop; driver
`abandon` during a deferred hop — each asserting exactly one materialize
`:lost` completion, no phantom get completion, and no residual
`:verify-pending`.

**Then do the thing that stops this recurring.** This is the third iteration
of one defect: A1 was `busy` that could never clear; r2's verify hop had no
owner; r3's hop has an owner but no loss route. Each fix specified the exits
it was thinking about and missed one. So do not just patch this exit. Add a
short **explicit lifecycle table for the verify hop** — every state it can be
in (`:phase :put`, `:verify-pending` unissued, issued-and-outstanding), every
event that can reach it (`ok`, `full`, undeliverable, abandon, terminal, each
completion kind), and for each cell: what happens to the record, what
completion is published, and under which id it routes. A reader should be able
to check exhaustiveness against the table instead of trusting prose. If a cell
is impossible, say why.

## 2. P3 — the documentation-route sentence is wrong for two of four

plan:572-576 says "Under that route the four in-scope edits — `mem_test`,
`dht_test`, the `jing_test` section move, the `file_test` excision — still
happen." Under the documentation route the observer never leaves `dao.jing`,
so `dao.jing.observer` does not exist and the `mem_test`/`dht_test` repoints
cannot be performed — executed literally, that sentence requires a nonexistent
namespace. Only the section move (requiring `dao.jing`) and the excision
happen under that route; the seven repoints exist only on the move-out route.
The paragraph's own justification already says this correctly of those two
alone, and contradicts the list it defends.

Change nothing else. End with a short list of what changed from revision 3.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

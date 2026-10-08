Created-GMT: 2026-09-06 18:28:40 GMT
Created-Local: 2026-09-07 01:28:40 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing.v2 migration plan — revision 5 (final splice)
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 01:28:40 +07 | Status: active | Rationale: resumed author session; applying its own agreed disposition

The disposition is settled. `gpt-6-astra` ruled independently and reached the
same conclusion you did: disposition (a), no compensating logic in
`dao.jing.v2.remote`, the RPC defect filed against `dao.stream`, and
explicit no-op rows rather than a footnote. Its findings are at
`collab/consensus-dao-jing-v2-allocator.gpt-6-astra.findings.md`. Two points
where it agrees with you in different words, resolved your way because yours
is narrower and justified: it gates "J3's completion criterion" while you gate
**J3c specifically** with the bystander test as the gate — yours, because only
J3c holds a record across an allocation; and it asks that *impossible*,
*no-op* and *unhandled* be made distinguishable, which your four-way
disposition vocabulary does better than its grouped rows.

Produce the **complete revised plan document** (revision 5) as the body of
your final response. Do not edit files. Apply exactly the splice you
specified, and nothing else:

1. **Decision 3, lifecycle table.** Preamble amended with the four-way
   disposition vocabulary — transition, no-op (record unchanged, stated),
   impossible (with why), dependent (with the dependency named) — and the
   "routes by … or **synthesized** where no RPC completion exists and the
   materializer publishes under the put id directly" clause. Add the one
   `dependent` row and the four no-op rows exactly as you drafted them. Edit
   rows 14 and 15's "routes by" to "synthesized; published under the put id",
   and add to row 14 that every *other* record with an outstanding hop is the
   dependent row.
2. **J3c.** One named dependency: the `dao.stream.rpc`
   `allocation-failure`/`lose-outstanding` fix, filed by the orchestrator
   against `dao.stream`, with `yin.repl` (`v2_adapter.cljc:117`) listed
   as the second affected consumer. The bystander test is J3c's gate — one
   materialization in `:put` with its put outstanding, a forced allocation
   failure on a second `request-materialize`, asserting the bystander
   completes `:lost :dao.stream.rpc/allocator-error` exactly once by put
   id with no residual record — and J3c is not complete before it is green.
   State that J1, J2, J3a and J3b proceed independently and why.
3. **Boundary of this plan**, *Intentionally deferred*: the RPC fix itself, to
   `dao.stream.rpc`'s owner, with the note that `dao.jing.v2.remote`
   compensates for nothing.
4. **End condition**: J3c's bullet reflects the gate.

Nothing else changes. Do not revisit any settled decision, do not re-argue the
disposition, and do not improve prose you were not asked to touch — this is
the final pass and every unrequested change costs another review round.

End with a short list of what changed from revision 4.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

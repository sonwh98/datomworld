Created-GMT: 2026-09-06 18:18:47 GMT
Created-Local: 2026-09-07 01:18:47 +07 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0779a-7a9d-79e0-930a-ac19af7164b4
# Task: dao.jing.v2 — allocator-error disposition
Role: Lead System Architect (independent seat)
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-07 01:18:47 +07 | Status: active | Rationale: resumed independent session; the only architect seat that has not seen revisions 2-4, so its read of the layering question is uncontaminated by having written them

## The question

`glm-5.3`'s delta review of revision 4 closed the plan with one disposition
owed. The orchestrator verified every element against the tree:

- `dao.stream.rpc/allocation-failure` (rpc.cljc:155-161) sets
  `:terminal :dao.stream.rpc/allocator-error` and appends a diagnostic —
  and does **not** call `lose-outstanding`. It is the only terminal path in
  the namespace that does not; the other five terminal/loss paths do
  (rpc.cljc:371, 377, 403, 410, 415).
- Once terminal, `poll!` returns without reading (rpc.cljc:430), and `rebind`
  refuses any terminal that is not `/detached` (rpc.cljc:465-476).
- Therefore every request already in `:outstanding` when an allocation fails
  is stranded: no completion ever arrives for its id.
- `test/dao/stream/rpc_test.cljc` has **no coverage** of `allocator-error`,
  `id-exhausted` or `id-collision`.
- `yin/repl_adapter.cljc:117` maps `allocator-error` to its own terminal,
  so the shipped v2 REPL has the same stranding today.
- Practical reachability is very low: id exhaustion needs ~9e15 allocations,
  and `ids-in-use?` plus monotonic `:next-id` make collision unreachable in
  single-driver use.

For `dao.jing.v2.remote`, the consequence is that a materialization record in
`:phase :put` (awaiting its put response) or `:verify-issued` (awaiting its
get) has no exit on an allocator-error terminal: order 3 has no unsent to
abandon, the order-4 sweep touches only `:verify-unissued`, and `abandon` is a
no-op on an outstanding envelope. Revision 4's lifecycle table has no row for
it, while claiming exhaustiveness.

`glm-5.3` offered two dispositions and did not choose:

**(a) Disclaim and route it out.** The plan records a named RPC-layer defect —
"allocator-error strands outstanding requests at the RPC layer; recorded for
`dao.stream`, not cured here" — adds the row saying so, and the orchestrator
files it separately against `dao.stream`. `dao.jing.v2.remote` grows no
compensating logic.

**(b) Compensate in the client.** Generalize the materializer's terminal sweep:
on a terminal whose arrival did not lose outstanding, complete every remaining
record `:lost` with that reason by put id. The plan's own surface then answers
completely regardless of the RPC layer's behaviour.

## What to decide

1. **(a), (b), or something else** — and say what principle decides it. The
   orchestrator's provisional reading is (a): a consumer compensating for a
   transport-layer stranding is the layering error this plan spent four
   revisions avoiding, and (b) would leave `dao.jing.v2.remote` carrying logic
   whose only purpose is to paper over a defect in the namespace beneath it.
   That reading is provisional and you should overturn it if it is wrong.
   Consider specifically: does (a) leave the plan's exhaustiveness claim
   honest, given the table asserts every event has a row?
2. **Is the RPC defect a dependency of this plan's J3, or independent?** If
   `dao.stream.rpc` were fixed first — `allocation-failure` calling
   `lose-outstanding` before going terminal — the plan's cell resolves itself
   and needs only a normal row. Say whether J3 should be gated on that fix,
   proceed independently, or proceed with the disclaimer and drop it when the
   fix lands.
3. **`glm-5.3`'s P3, briefly**: the table preamble claims "every event that can
   reach it" while three benign no-op events have no rows (driver `abandon`
   during `:verify-unissued`; `abandon` during `:verify-issued` with the get
   outstanding; a response-medium `gap` while `:verify-unissued`), and two
   cells route by a synthesized completion rather than an RPC-carried id.
   Editorial, but say whether footnote or explicit no-op rows.

Read:
- `docs/design/dao.jing.v2.implementation-plan.md` (revision 4; Decision 3 and
  its lifecycle table are the relevant part)
- `collab/adversarial-dao-jing-v2-delta-r2.glm-5.3.findings.md` (the review
  that raised this)
- `src/cljc/dao/stream/rpc.cljc`

Settled, not in scope: the seven consensus items, the verify-hop ownership
decision, the phase split, and everything both reviewers have confirmed. Do not
reopen them. This is one disposition and two small questions.

Do not edit any file. Answer directly and completely in this run; a response
that promises an answer rather than stating one is an unfinished turn.

Specific to you: you last saw revision 1. Revisions 2-4 implemented the
consensus you and the author reached, plus fixes for findings you never saw.
You are not being asked to review them — only this disposition. Your value
here is that you did not write the lifecycle table and have no stake in
defending it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0779a-7a9d-79e0-930a-ac19af7164b4

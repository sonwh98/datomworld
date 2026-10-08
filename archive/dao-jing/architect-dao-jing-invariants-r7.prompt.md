Created-GMT: 2026-09-07 08:40:34 GMT
Created-Local: 2026-09-07 15:40:34 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing on dao.stream — revision 7, reframed around invariants
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 15:40:34 +07 | Status: active | Rationale: resumed author session; owns every decision this revision keeps

Your revision 6 is on disk at
`collab/architect-dao-jing-cutover-r6.claude-fable-5-1.findings.md`. Its
decisions are right. Its **framing** is wrong, and the user has corrected it.

Produce revision 7 complete, as the body of your final response. Do not edit
files.

## The correction

You wrote revision 6 as a migration: "rewritten in place", "ported", phases
sequenced so an existing suite stays green. The user's words:

> the tests are there to make sure invariants and symmetry are kept. the same
> invariants test can be rewritten. since dao.stream v2 is implemented and
> stable, that is all that matters. new tests and new code can be built on top
> of it without regards to old implementation

So **the v1 implementation has no authority**, and neither does the existing
test code. Two things have authority: `dao.stream`'s contract, and the
invariants DaoJing must satisfy. Everything else is disposable.

This is not a migration document. It is: **build `dao.jing` on
`dao.stream`, and delete what is there.**

## The centrepiece: an explicit invariants list

Revision 7's contract is a stated list of what must be true, replacing
revision 6's file-by-file port lists. An implementer should be able to write
the code and its tests from that list without reading a line of v1.

Derive it from two sources and **mark each invariant with which**:

- **`dao.jing.md`** — the design, which is authority. Its Definition, intake
  pool, publication, materialization rule, canonical encoding, storage
  ignorance, reads, cursor tracking, resource lifecycle sections.
- **The existing tests** — evidence of what was actually pinned, including
  things the design never wrote down. `test/dao/jing_test.cljc` (especially
  the observer section, 322–560, and the handle-API section), `mem_test`,
  `file_test`, and the pool-drain helpers in the five `test/dao/space/`
  files.

Where a test pins something the design does not state, say so and judge it:
is it a real invariant that belongs in `dao.jing.md`, or an accident of the
old implementation that the rewrite is free to drop? That judgement is the
most valuable thing in this document — it is the difference between
preserving what matters and reimplementing habits.

Cover at least: content addressing and convergence, the source stream never
entering an address, materialization idempotence and the `:present`
read-back, the pool's fairness and non-starvation, cursor discipline and
outcome totality, gap reporting without auto-resync, the file backend's
append-only replay, incomplete-tail truncation and fail-closed decode, and
handle lifecycle. Group them so each maps to a test the implementer will
write.

## What revision 7 keeps

- **Decision 1** — the observer's entry shape, cursor discipline, and outcome
  totality. Unchanged.
- **Decision 2's reasoning** — the durable log was never a stream, with the
  no-wait analysis and the recorded `gpt-5.6-sol` dissent. Drop any remaining
  compatibility clause.
- **Decision 3, recorded and deferred** — the stepped client,
  `request-materialize`, the step order and the verify-hop lifecycle table.
  **Restate why it is deferred, correctly**: not legacy. `jing/get` answers
  synchronously; `dao.stream.rpc` cannot answer synchronously on any host,
  by contract. So a remote content store cannot present a local store's
  interface — true of code written from scratch today with no v1 anywhere.
  It is a design question about what a remote store *is*
  (`dao.data.btree.md` §5.4), and it is the one thing here that a free
  rewrite does not dissolve.
- **The Host-Boundaries open item** — "the content write path as an effect
  stream". Unchanged; it predates all of this.
- **`dao.space`'s two writer seams** — `index/append-ok!` (`index.cljc:526-531`)
  and the transactor's intake validation (`transactor.cljc:197-201`) move to
  `dao.stream`, because the pool is one thing with two ends. Now framed as
  small work rather than as a scope incursion. Everything else in `dao.space`
  stays for its own plan.

## What revision 7 drops

Every remaining migration artifact: "port", "rewritten in place", the
divergence register comparing v1 to v2, phase sequencing justified by keeping
an old suite green, and any table of what v1 callers lose. The old code is
deleted, not translated.

## Phasing

Still needed — work must be ordered — but ordered by **what is buildable and
checkable**, not by what protects an old suite. State for each phase what is
built, which invariants it satisfies, and what proves it on clj, cljs (Node)
and cljd. Deleting the old code and its tests is part of the phase that
replaces them, not a later cleanup.

Be shorter than revision 6. End with a short list of what changed from
revision 6 and why.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

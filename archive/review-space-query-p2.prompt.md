Created-GMT: 2026-09-08 12:42:57 GMT
Created-Local: 2026-09-08 19:42:57 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review P2 — the dao.space.query.md rewrite
Role: Architect review (routing: Architect primary claude-fable-5-1 is the
same family as the author, so this goes to the Architect fallback)

P1 is committed (`96ec78f`, plan at `1e5f0ad`). **P2 is the plan's second
phase and is uncommitted in the working tree**: the design doc rewritten to
say what the code now does. I wrote it; you are the independent reader.

**The workspace is read-only. Do not write any file. Print your review to
stdout as your final message.**

## What P2 owes, from the plan

`docs/design/dao.space.query.implementation-plan.md` lines 438-449 specify it:
rewrite *Source polymorphism* to Decision 1's input list; rewrite *The read
coordinate* so the coordinate is opened by the caller and borrowed by `q`;
state L4 under *Index realization*; add S1-S3 as a *Snapshots* section naming
`snapshot` and `dao.stream.observe/step`; move the after-close loudness
bullet to the caller; update the status line and the executable contract
pointer; add two *Decisions* — bounded realizations are values not streams,
and the relation transport is eliminated; *Open items* unchanged except the
K-way merge note.

The end condition (plan line 486-493) requires the four `[T→D]` invariants —
**L4, S1, S2, S3** — to be *in the design*, not merely pinned by tests. That
promotion is the whole point of the phase.

## Review this

`git diff -- docs/design/dao.space.query.md docs/design/dao.stream.md`

1. **Truth.** Every claim must match the committed code. The doc is the
   design; if it now says something `query.cljc` does not do, that is the
   defect this phase exists to prevent. Check especially the input list, the
   ownership story, the snapshot outcomes, and the lazy path.
2. **Completeness against the plan.** Each of the seven items above, and each
   of L4/S1/S2/S3 stated as a property rather than alluded to.
3. **Residue.** Any surviving v1 language — "descriptor is opened into a
   realization", "result DaoStream", "q opens", bound/closedness talk — that I
   missed.
4. **The `dao.stream.md` edit**, outside the plan: its v1-consumer list said
   `dao.space (index, query, schema, transactor)`, which is now false. I
   changed it to name the three that remain and record that
   `dao.stream.relation` went with query. Verify that against the tree.
5. **Overreach.** I should have changed nothing the plan did not ask for.
   Flag anything I rewrote beyond its scope, or any decision I recorded that
   the code does not actually make.

No tests to run — this phase is prose. State plainly whether P2 is complete
and whether the plan is now fully consumed (P1 and P2 both done), or what
remains.

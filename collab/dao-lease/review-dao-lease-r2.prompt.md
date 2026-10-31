Created-GMT: 2026-09-02 06:37:16 GMT
Created-Local: 2026-09-02 13:37:16 Asia/Ho_Chi_Minh

# Task: review the rewritten `docs/design/dao.lease.md`

Role: Lead Systems Architecture Reviewer

## What changed

You reviewed the first draft of `dao.lease.md`. Four reviewers returned
findings; the document has been rewritten against all of them. The substantive
changes:

- **The judge is now an interpreter over two streams** — lease facts and
  **ticks** deposited by a host timer adapter. The claim that judgment points
  are reached free is gone. The bound is duration + tolerance + cadence.
- **The window is seeded by the grant.** A never-renewed lease is now
  judgeable; previously it could never lapse.
- **Three claims retracted**: that judge state is rebuildable from the stream;
  that authorship is an ambient capability DaoStream supplies; and that an
  ungoverned grantor is refused at assembly. A new section, *Attribution is a
  composition duty*, replaces the second.
- **Reclaim ordering fixed**: judge → reclaim (idempotent) → append
  `:lapsed`. A failed append leaves the resource reclaimed and unrecorded.
- **Precedent restructured**: two structural divergences, with three others
  derived from the first. The invariant against collapsing interpretation and
  execution is no longer claimed for the judge/reclaim split.
- **Added**: `:dao.lease/subject`; duration units, validity and a strict `>`
  boundary; identity unique across the judged medium; a holder rule to *stop
  acting* at its bound; durable-resource scoping; rate-vs-offset skew;
  partition-outlasting-duration as the hard limit; `:max` scoped to one judge
  lifetime; the crash-loop leak stated.
- **No close code is assigned.** Reclaim surfaces as an ordinary `:ws/closed`.

## Scope

Read:

- docs/design/dao.lease.md          — under review, rewritten
- docs/design/datom.world.md        — governing authority
- docs/design/yin.vm.jit.md         — the precedent it claims to follow
- docs/design/dao.stream.md         — the contract it claims not to touch
- docs/design/dao.stream.ws.md      — where carriage is said to live

**No source code.** No ADRs, no `collab/` logs.

## What to test

1. **Did the fixes land, or did they move the problem?** A correction that
   relocates a defect is worse than the defect, because it now looks handled.
   Check each change above against the text.
2. **Did the fixes introduce new contradictions?** The document grew by a
   quarter. Section against section, especially: the tick stream against "never
   fires into lease code"; the grant-seeded window against the restart rules;
   the durable-terms requirement against "never a registry".
3. **Are the retractions complete?** Each retracted claim had consequences
   elsewhere. Find any place still resting on a premise now withdrawn.
4. **Is the precedent mapping honest now?** Two structural divergences with
   three derived. Verify the derivation actually holds rather than being
   asserted.
5. **What would an implementer still have to invent?**
6. **What is now overstated in the other direction?** The rewrite was
   conservative under review pressure. Say where it is too cautious to be
   useful, or where a stated limit is not real.

## Output

Findings ranked most severe first: severity | section or line | the claim | the
exact correction. Then: **what the rewrite made worse**, if anything.

State plainly if you find nothing at a given severity. Read-only; make no
edits.

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```

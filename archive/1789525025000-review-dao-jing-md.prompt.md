Created-GMT: 2026-09-15 21:57:05 GMT
Created-Local: 2026-09-16 04:57:05 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: deepseek
Session-ID: 451ddbb7-8ad4-475b-8bf5-21c263a59e70
# Task: Routine review of dao.jing.md canonical encoder doc amendment
Role: Routine Review
Implementers:
- Model: deepseek-flash | Assigned: 2026-09-16 04:57:05 +07 | Status: active | Rationale: read-only doc-accuracy review, cheap/fast model appropriate for a bounded doc-consistency check

## Context

`src/cljc/dao/jing.cljc` (the canonical content-addressing encoder) just
had a P0 fix committed (`0cafb2d`: metadata now address-significant, set
tag lives in `#{}` braces not the value domain, records throw, comparators
use `canonical-print`). `docs/design/dao.jing.md` has an uncommitted
working-tree diff (`git diff -- docs/design/dao.jing.md`) the orchestrator
wrote directly to record this in the Canonical encoding section and Open
Items. It has already had one Architect pass (as part of reviewing the
code fix) which found it "accurate, with two low-severity wording items"
— both already fixed in the current diff (a "for any non-pathological
scalar" qualifier, and naming ambient print-var bindings as a third
deferred residual in the Open Items).

## Task

Read the full current `docs/design/dao.jing.md` and its diff. Verify:

1. Does the new Canonical encoding paragraph accurately describe what
   `src/cljc/dao/jing.cljc` (current committed state, `0cafb2d`) actually
   does? Check each specific claim against the code: collection metadata
   address-significant except reader-position keys stripped; empty
   metadata dropped; scalar metadata not address-significant; lists and
   seqs share an address; records throw; `materialize!`'s `:present`
   read-back verified by hash not `=`.
2. Do the three new/amended Open Items (canonical encoding residuals,
   byte-array identity hashing, backend/wire fail-closed metadata checks)
   accurately describe real, currently-true limitations, without
   overclaiming or underclaiming severity?
3. Is anything else in the document now stale or contradicted by this
   diff (read the whole file, not just the diff hunks)?

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report: pass/fail per the three checks, exact citations for anything
flagged, and an explicit verdict — safe to commit as-is. Produce the
complete deliverable now.

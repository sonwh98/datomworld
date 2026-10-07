Created-GMT: 2026-10-05 09:40:00 GMT
Created-Local: 2026-10-05 16:40:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D3 — the authenticated ledger reader (holder evidence)
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 16:40:00 +0700 | Status: active | Rationale: implementation role, continuity with the C/D slice engineers

Implement D3 in /Users/sto/workspace/datomworld-d3 (worktree, branch
ucf-d3-evidence, based on master 14d9f750). Read first: the D plan r2
(collab/1791191725340-architect-m-next-d-plan-r2.claude-fable-5-1
.findings.md — section 1.5 and the D3 test contract) and the review it
folded (collab/1791191261015-architect-m-next-d-plan-review
.gpt-6-astra.findings.md, finding 5); then src/cljc/yin/vm/ucf/ledger.cljc
(`fold-record`, the projection fold), src/cljc/yin/vm/ucf/custody.cljc
(`binding-evidence`), src/cljc/yin/vm/ucf/authority/input.cljc
(`frontier`, `inputs`, `replay-input`), src/cljc/yin/vm/ucf/authority.cljc
(target/outcome reader shapes), and src/cljc/dao/stream/remote.cljc (the
reflection used in C5's evidence test).

The contract (r2 1.5 item 3): a new namespace
src/cljc/yin/vm/ucf/holder/evidence.cljc. The holder reads the
authority's ledger stream, attributed by the composition's resolver to
the arbitration identity, and folds it with `ledger/fold-record` into
its own reader projection. From that projection it derives: binding
evidence (`custody/binding-evidence`), enrollment, and the replay prefix
(`input/inputs`). The namespace takes attributed records (data), not an
authority value, and holds no authority reference.

Complete history or nothing (r2 1.5): the fold must start at the
stream's origin, with dense t, and reach the grant's record. A gap, a
start past the origin, a transport error, or a fold defect makes the
evidence unavailable. Unavailable evidence is `:yin.k/unsatisfied` or
`:yin.k/awaiting-grant` (say which, where); it is never an empty prefix
and never a missing enrollment.

Test contract (r2):
- A fold from the origin yields the same binding, enrollment and prefix
  as the authority's own projection (the test may build the authority
  with the landed memory seam and compare — the holder's runtime path
  itself must never read `authority/projection`).
- A gap, a start past the origin, a transport error and a fold defect
  each answer unavailable; none answers an empty prefix.
- Records from another author establish nothing.
- The evidence survives a `dao.stream.remote` reflection (mirror the
  C5 binding-evidence test's approach).

Acceptance criteria:
- Test-first: red tests for the contract rows, then the implementation,
  then green. Portable `.cljc`; JVM during iteration; the orchestrator
  runs the three lanes at landing.
- The namespace is pure over attributed record data and the fold; no
  transport vocabulary, no apply vocabulary, no authority handle.

Constraints:
- New file src/cljc/yin/vm/ucf/holder/evidence.cljc and its test file
  only. If a landed namespace must change (a fold or evidence function
  is missing something), stop and report instead of editing it.
- No git writes. kondo you may run; cljstyle is the orchestrator's.
- Lessons: a 0.0 literal is the integer 0 on JS (use `(cbor/float64 0)`
  or 0.5); `#?(:cljd nil :clj ...)` order for JVM-only test branches
  (:cljd first).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.

Created-GMT: 2026-09-21 07:25:23 GMT
Created-Local: 2026-09-21 14:25:23 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 2495507c-4154-4edf-9fe2-bf36cb023729 (resumed — your D4 review session)
# Task: debruijn-d5 — independent review of the D5 pipeline integration
Role: Routine Review (Compiler & AST)
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-21 14:25:23 +07 | Status: active | Rationale: cross-family reviewer for glm-5.3's D5 code; resumes the D4 review conversation (same subsystem, glm author); owner routing — spend the claude pool before its 2026-09-22 04:00 +07 expiry

Read-only review. Work only in /Users/sto/workspace/worktree-debruijn-impl
(all files below are inside it). Plan mode: produce the complete verdict now
as your final response; do not wait for approval and do not promise a verdict.

## Subject

The glm-5.3 author's D5 phase (untracked, not yet committed):
- src/cljc/yin/vm/pipeline.cljc (new, ~139 lines)
- test/yin/vm/pipeline_test.cljc (new, ~246 lines)

## Contract (binding decisions to check the code against)

Read collab/1789967942071-compiler-engineer-debruijn-d5.prompt.md — its bullets
restate the gpt-5.6-sol D5 pre-clearance. In short: persist-compiled! calls
yin.vm/ast->datoms-with-root, projects the COMPLETE root-framed batch via
debruijn/project-datoms, persists the NAMED side first, persists the projected
envelope {:yin.debruijn/fingerprint fp :yin.debruijn/datoms ...} ONLY after
named persistence succeeds, and returns {:named ... :projected ...}
independently. Projection failure never fails the named side; named failure
means projected persistence is not attempted. Dedupe is dao.jing
materialize! write-idempotence (:inserted/:present, read-back verified,
never overwrites), returning both the physical address and the semantic
fingerprint, with no pre-write lookup and NEVER deduping named artifacts by
fingerprint. Box: pipeline.cljc, pipeline_test.cljc, and debruijn.cljc only
for a tiny pure envelope constructor; nothing else touched.

The governing design (§1 invariants, §6, §7-D5, §9) is the CURRENT copy:
collab/1789975149204-compiler-engineer-debruijn-epicfix-claude.ref-design-master-37dfbf54.md
(the tree's docs/design copy is stale — do not use it).

## Scope and caveats

- debruijn.cljc is being edited RIGHT NOW by a concurrent implementer for a
  separate audit-fix round (sets merge, reader slot checks, hashing memo,
  budget). Do NOT review that file or its tests; treat its public API
  (project-datoms, projected->datoms, fingerprint) as the fixed surface D5
  consumes. Do not report findings about its internals.
- Already verified by the orchestrator on the pre-edit state: pipeline_test
  8 tests / 39 assertions / 0 failures; JVM full 1629 tests / 169074
  assertions / 0 failures. NOT yet run: CLJS and CLJD lanes on the two D5
  files — so portability of a .cljc file that touches dao.jing is a
  legitimate static-analysis target (reader conditionals, host APIs, io/atom
  use). Do not spend budget rerunning suites; you cannot in plan mode.

## What to look for (rank by severity)

1. Contract violations: any path where projection failure can fail or roll
   back the named side, where projected persistence is attempted after a
   named failure, where the ordering is violated, where named artifacts are
   deduped by fingerprint, or where a partial/mid-emission prefix can be
   projected.
2. Tests that do not prove the contract: for each bullet in the
   Tests list of the D5 brief, say whether the test would FAIL if the
   behaviour were broken (mutate mentally). Vacuous or order-insensitive
   assertions are findings.
3. Result-shape and error-classification issues (diagnostic vs :internal
   failures, swallowed exceptions, outcomes that lie about what was persisted).
4. Cross-host portability of the new .cljc files.
5. Scope: any edit outside the three-file box.

## Deliverable

Final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2495507c-4154-4edf-9fe2-bf36cb023729
Then a verdict line — READY or NOT READY — then findings as P1/P2/P3, each
with file:line, the failing scenario, and the smallest fix. Say explicitly
what you checked and found clean. Findings only; do not edit anything.

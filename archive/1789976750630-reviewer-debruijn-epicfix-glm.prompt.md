Created-GMT: 2026-09-21 07:45:50 GMT
Created-Local: 2026-09-21 14:45:50 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: b59d50ce-369e-4fef-be0c-eaaf01aeb580
# Task: debruijn-epicfix-glm — independent review of the epic-audit fix round and the D5 fixes
Role: Adversarial Review (Compiler & AST)
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 14:45:50 +07 (dispatch held until its 5-hour cap resets 17:26:16 +07) | Status: active | Rationale: cross-family reviewer — the code under review was written by claude-opus-5 (Claude family); glm is the only non-Claude family with budget (gpt is reserved for sign-off)

Read-only review. Work only in /Users/sto/workspace/worktree-debruijn-impl
(every file named below is inside it). Plan mode: produce the complete
verdict as your final response now; do not wait for approval and do not
promise a verdict — a response that promises rather than states is an
unfinished turn.

## Subject

Uncommitted work by claude-opus-5 in the worktree (branch debruijn-impl,
HEAD 8ed66e3a = D0-D4 committed and green):
- Modified, visible via `git diff`: src/cljc/yin/vm/debruijn.cljc (~+380/-220)
  and test/yin/vm/debruijn_test.cljc (~+230)
- Untracked (Read them; git diff won't show them): src/cljc/yin/vm/pipeline.cljc
  and test/yin/vm/pipeline_test.cljc — the D5 pipeline (originally yours,
  glm; opus has since applied an independent review's findings to them)

Two units to judge together:
1. **Epic-audit fixes** for the opus-5 audit that returned NOT READY. The full
   audit with REPL evidence:
   collab/1789975149204-compiler-engineer-debruijn-epicfix-claude.ref-epic-audit-full.md
   Findings: F1 sets merge silently under canonicalization; F2 the reader
   checks hashes but not slot names/node-type grammar/canonical spelling;
   F3 exponential hashing on shared subgraphs; F4 known attribute on the wrong
   node type ignored; F6 internal defects surfacing as :invalid-input; F7 a
   batch budget of 0 returning :continue forever; F9/D6 gap tests.
   The brief given to the implementer:
   collab/1789975149204-compiler-engineer-debruijn-epicfix-claude.prompt.md
   (r2 there is a path-only correction).
2. **D5 fixes** from a claude-sonnet-5 review (NOT READY, then applied):
   findings in collab/1789975523112-reviewer-debruijn-d5.claude-sonnet-5.findings.md,
   brief in collab/1789975739942-compiler-engineer-debruijn-d5fix-claude.prompt.md.

The governing design is the CURRENT copy:
collab/1789975149204-compiler-engineer-debruijn-epicfix-claude.ref-design-master-37dfbf54.md
(the tree's docs/design copy is stale). Every sentence in §1, §2, §4, §5, §6,
§8, §9 is a rule.

## Already verified by the orchestrator (do not rerun; you cannot in plan mode)

On the final tree: focused JVM (Java 17) 88 tests / 412 assertions / 0 failures;
FULL JVM 1646 / 169198 / 0; kondo 0 errors 0 warnings; cljstyle clean. CLJS and
CLJD full lanes are being run by the orchestrator and are reported separately —
do not spend budget on them, but DO judge cross-host portability of the
changed code statically.

## What to look for (rank by severity; each finding: file:line, failing
## scenario, smallest fix)

1. **Hash neutrality.** F2/F3 must not change any minted hash or the
   descriptor digest (`dimension-hash` 11954e46…, essay fingerprint 095c83f7…,
   the D3 byte fixtures). Look for any way the new code alters preimages, and
   for any path where the writer emits something the new reader gate rejects
   (over-rejection: the writer's own output must always read back). Pay
   particular attention to the new `check-record`: canonical-spelling checks
   on scalars (opus reports that a stored `:macro? false` is diagnosed as
   :noncanonical-value — is `false` ever a legitimate emitted value? and does
   the CLJS "every in-range number is canonical" rule hold?), the per-node-type
   slot table, and the required-slot rules.
2. **F3 memo soundness.** Records are now minted once per `[eid stack]` memo
   entry via `mint-record`, with `project-datoms-counted` as the counter seam.
   Is the memo key complete (the full frame-vector stack, per the D1 lesson —
   `=` merges lists with vectors and 0.0 with -0.0 on some hosts; identity
   must never depend on Clojure equality of values)? Can the same eid under
   different binding scopes wrongly share a record? Does it still equal the
   tree-built equivalent's fingerprint?
3. **F1 / encode-value set rule** on every host, including the count-shrink
   detection and the CLJS/CLJD differences opus documents.
4. **F6 / F7 classification and budget rules** — is `exception-diagnostic`'s
   new rule (an :invalid-input only when ex-data has a keyword non-internal
   :rule) sound, and can any genuine input error now be misreported as
   :internal-error? Is the `:invalid-budget` gate complete?
5. **D5 pipeline.cljc**: the `framing-defect` gate (exactly one complete
   frame, before ANY write), named-before-projected ordering, projection
   failure never failing the named side, the store-failure/`:invalid-writer-answer`
   shapes, and whether the new pipeline_test.cljc tests would actually FAIL
   under mutation (event-log dedupe sequence, gate cases, the with-redefs
   internal-error test).
6. **Naming quirk**: a :variable carrying both :bound and :free is reported as
   :missing-slot. Does that merit a fix or is it acceptable?
7. **Portability** of the changed code: `:cljd` listed first in every
   reader conditional that needs it; pipeline_test.cljc now requires
   `dao.jing.file` and `dart:io` — are those requires guarded correctly for
   CLJS and CLJD? Anything in tests that assumes host behavior it can't have?
8. **Scope**: only debruijn.cljc, its test, pipeline.cljc and its test may
   change; confirm with `git diff --stat` and `git status`.

Say explicitly what you checked and found clean. Findings only; do not edit
anything.

## Deliverable

Final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: b59d50ce-369e-4fef-be0c-eaaf01aeb580
Then a verdict line — READY or NOT READY — then findings as P1/P2/P3.

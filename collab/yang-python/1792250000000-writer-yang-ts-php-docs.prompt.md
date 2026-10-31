Created-GMT: 2026-10-16 19:35:00 GMT
Created-Local: 2026-10-17 02:35:00 +07 (+0700)
Coding-Agent: claude
Session-ID: f7548325-47f8-44bf-855a-244c5e56a1d2

# Task: Consolidate the reviewed TS+PHP frontend designs into docs/design/yang.typescript.md and yang.php.md

Role: Scoped Writer (docs)

WORK TREE: /Users/sto/workspace/datomworld-yangdocs (branch docs-yang-ts-php, based on master). Create EXACTLY
two new files: docs/design/yang.typescript.md and docs/design/yang.php.md. Do not touch any other file. Do not
stage or commit.

SOURCE MATERIAL (this worktree's collab/ — the complete nine-round design loop, all reviewed):
- 1792197000000-architect-yang-ts-php-designs.claude-cloud-fable.findings.md — Fable's initial designs
- 1792204000000-architect-ts-php-revision.claude-cloud-fable.findings.md — round 2 (the 5 P1 rulings)
- 1792214000000-architect-ts-php-r3.claude-cloud-fable.findings.md — round 3 (the unified lifecycle mechanism)
- 1792219000000-architect-ts-php-r5.claude-cloud-fable.findings.md — round 5 (the zend-derived reference model)
- 1792226000000-architect-ts-php-r8.claude-cloud-fable.findings.md — round 8 (the PHP 8.3 oracle-grounded fixes)
- 1792229000000-architect-ts-php-r9.claude-cloud-fable.findings.md — round 9 (THE CURRENT CONSOLIDATED TEXT:
  where it restates, it wins)
- The codex review rounds: 1792200000000-...gpt-6.1-sol.findings.md (round 1) and
  1792224000000-...r7.gpt-6.1-sol.findings.md (the accepted corner mechanism + landing rule)

TASK: consolidate the nine rounds into TWO STANDALONE design documents. Where rounds conflict, the LATEST
ruling wins (r9 is final; codex r2-fresh granted the sign-off on it). Requirements:
- Each doc: title, status header (Designed 2026-10-17; Fable design, codex adversarial review READY after
  nine rounds — cite the loop), the paradigm specification, the grammar route, CST->UAST, scope analysis,
  the lowering-arms inventory, the prelude inventory, value encoding + paradigm-table rows, the import/module
  story, safepoints, diagnostics, the differential-corpus plan, the slice sequencing with acceptance
  contracts, the yang.core lift enumeration, the Known engine-fidelity corners subsection (PHP) / the
  equivalent tracked-fragment statement (TS), and the Owner-decisions list (from the converged text).
- TS doc: the type-layer design (parse-and-erase vs checking profiles, null/undefined, the type surface per
  slice, the boundary story) integrated per the converged rulings.
- Both docs follow docs/agents/format.md (ASCII, no em dashes, <= 80 columns), cross-reference
  yang.antlr.md and each other, and mark implementation as pending (nothing of either frontend is built).
- Provenance: a short "Design provenance" section per doc citing the nine-round loop and the two codex
  verdicts (round-1 REQUEST CHANGES with 5 P1s; round-2-fresh READY after the r9 fixes).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
Report the structure of each doc and any ruling you could not consolidate cleanly.

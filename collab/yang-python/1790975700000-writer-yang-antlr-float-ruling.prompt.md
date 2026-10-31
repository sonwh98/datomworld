Created-GMT: 2026-10-02 20:35:00 GMT
Created-Local: 2026-10-03 03:35:00 +07 (+0700)
Coding-Agent: claude
Session-ID: <to-fill>

# Task: Record the converged float-address ruling in yang.antlr.md (+ cbor cross-reference)

Role: Scoped Writer (docs)

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-03 03:35:00 +07 (+0700) | Status: active | Rationale: same writer line as the earlier yang.antlr.md ruling records; doc-only change

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-yang-doc (branch docs-yang-antlr-float, based on origin
master 69e58662). Edit ONLY docs/design/yang.antlr.md and docs/design/dao.jing.cbor.md (the latter: a short
cross-reference only — the codec contract itself does NOT change). Do not touch any other file, the main tree, or
other worktrees. Do not stage or commit.

SOURCE MATERIAL (this worktree's collab/):
1. 1790968830636-architect-float-address-mob.gpt-6-astra.findings.md — the ruling: the codec conforms; the
   producer loses float kind; the mechanism is Jing's EXISTING dao.jing/float64 carrier (tag 27) inserted at the
   producer (literals while syntax still identifies float; quoted prelude constants marked before JS collapses
   them; runtime floats in addressed images); no new tag/row shape; the execution bridge; Node rows re-minted;
   never alias integer addresses to float addresses; the release gate for float-bearing address acceptance.
2. 1790968830647-architect-float-address-mob.claude-fable-5-1.findings.md — the concurrence with three
   corrections, all binding: (1) the normative text binds RUNTIME float values in addressed images (snapshots,
   continuations), not just literals; (2) the bridge covers the row and machine-payload gates at vm.cljc:962 and
   :981 in addition to engine.cljc:632; (3) "float-free" covers the ENTIRE addressed payload including the bundled
   prelude; C2-S5 must not pin cross-host goldens on float-bearing trees; cbor.cljc and its fixtures are not to
   be edited.

TASK: record the converged ruling in yang.antlr.md where the float-tagging decision lives (around the value
encoding / §8 rulings; find the right home following the document's conventions), including: the root cause
(conforming codec, defective producer), the carrier mechanism and its exact constraints, the bridge scope (both
gates), the re-mint rule, the aliasing prohibition, the float-free gate wording (whole addressed payload incl.
prelude), and what may proceed meanwhile (C2-S5 heap/collection work, C3-S2 integer-only work; slice 1's
float-free golden stands). Add the short cross-reference in dao.jing.cbor.md pointing at the Python ruling (the
codec contract text itself is unchanged — do not restate it). Mark the implementation slice as pending (the
engineer is in flight; nothing is landed). ASCII, <= 80 columns, consistent table style. If something cannot be
stated without forcing it, note it as an Open: question.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report the sections touched with one-line summaries and the ruling-to-section mapping.

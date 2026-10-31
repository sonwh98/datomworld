Created-GMT: 2026-10-10 19:00:00 GMT
Created-Local: 2026-10-11 02:00:00 Asia/Bangkok

# Task: design phase 6 of the semantic-register-vm rebuild -- the continuation format and the row carrier (continuing your session)

Role: Lead System Architect, author. Worktree `/Users/sto/workspace/datomworld-srvm-p6-design`, branch `srvm-phase6-design` (from master 8f0b9e38). Deliverable: a new design document `docs/design/yin.vm.semantic-register-vm.continuation.md` (same shape and rigor as your evaluator and derivations companions: numbered findings, frozen-document amendment text for every change you propose, numbered mechanically checkable acceptance lists, slices, owner questions only if genuinely owner-level) plus a report. You may also write proposed amendment TEXT inside your document for the other documents, but edit NO other design document, no source, no test. No git add/commit.

## Scope (frozen §10 phase 6, verbatim)

"Continuation format. §4.2 frames and both state shapes, the full frame validator, the safepoint harness of §4.1 (bidirectional lift/lower rows for every kind and both delivery modes, pending waits, nested and reified captures, module contexts, refusals); the row carrier amended. Gate: UCF §7.11's harness green for every engine intended to reconstruct canonical frames (the semantic kernel, the stack and register kernels through their layouts), not the semantic profile alone."

## Read first

1. `docs/design/yin.vm.semantic-register-vm.md` (frozen, as amended): §2.1-2.4 (configuration, windows, transitions), §4 (safepoints and the UCF frame: §4.1 table, §4.2 frame, §4.3 foreign engines), §6 (stamps, versions), §10, §12.
2. `docs/design/yin.vm.universal-continuation-format.md` (UCF): §7.3 (esp. §7.3.2, §7.3.4), §7.4 (safepoints, §7.4.1 rewritten by the frozen design, §7.4.2), §7.6 (dependency-closure scanners), §7.11 (harness), the v2 amendment and its profiles.
3. `docs/design/yin.vm.ucf-transport-tuples.md`, the row carrier draft (body version 3): its §5.2 frame rows (`:regs` with `stack-cref`, `:stack`, `:kframe` with `stack-base`), §8 step 7, the "v3 stamp does not move" statements, §2 and §10.4. These are STALE against the register design and are yours to replace.
4. `docs/design/yin.vm.semantic-register-vm.evaluator.md` (landed evaluator: the restore seam, `register-restore`, act/tail states, K frame kind `:return`) and `docs/design/yin.vm.semantic-register-vm.derivations.md` (merged: §7.6 the segment-to-tree association and `:yin.k/trees`, the unitarity amendments, the retrievability rules).
5. `docs/design/yin.vm.code-as-tuples.md` §7.2 (the vector-to-datoms mapping the owner has retired) and `docs/design/datom.world.md` (invariants, including the new unitarity invariant).
6. The landed code: `src/cljc/yin/vm/semantic_register.cljc` (4a on master; 4b Stage 1 on branch `srvm-phase4b` in `/Users/sto/workspace/datomworld-srvm-p3`, read-only for you), `yin.vm.ucf` and the existing safepoint harness and tests (grep `ucf`, `safepoint`).

## Owner rulings that bind this design (do not re-litigate)

- A, the semantic vector's content address, is the ONLY code identity; digests use Jing's default (BLAKE3), never SHA-256, for any new identity.
- No legacy: no deployed stores, no compat shims, no version-bump or migration paths; default to clean breaks. The carrier's body version stays a single number you define, with no support for older bodies.
- Unitarity: the AST is never lost and is always retrievable wherever code is lowered; no design assumes a tree-less receiver. The association from a segment/A to its source tree (phase-5 §7.6) is a given.
- The vector-to-datoms mapping is retired; the carrier carries rows/positional tuples, not datoms-from-vector.
- dao.stream is the complexity boundary: below it is swappable plumbing; do not design transport mechanics.
- "One truth, many interpretations": the register machine's `C = <seg, pc, W>` is the canonical frame shape; stack and register kernels reconstruct it through their layouts.
- This phase must not touch phase 4b's or 5's files (see their slice file lists) so it can run concurrently.

## Decide and specify

1. The register-shaped frame rows replacing the stale `:regs`/`:stack`/`:kframe` rows: exact row shapes, the sparse window W encoding (nil-valued keys must survive), the saved-window and K `:return` frames, pending waits, retained requests, the `{:deliver ...}` record, nested and reified captures, module contexts; Merkle ids and the cell census where they apply.
2. The full frame validator: every rule, with refusal reasons, over canonical frames independent of any engine.
3. The §4.1 safepoint harness: bidirectional lift/lower rows for every safepoint kind and both delivery modes; which engines must pass (semantic-register kernel; stack and register kernels through their layouts); how a layout reconstructs a canonical frame; refusals.
4. The row carrier amendment: replace the stale sections with the register design; a single body version; what the carrier carries for code (A and the tree association `:yin.k/trees`) versus state.
5. The UCF amendments (§7.3.4, §7.4, §7.6, §7.11) and code-as-tuples §7.2 retirement: exact replacement text, minimal.
6. Slices (6a/6b/...) with file lists, numbered acceptance lists a reviewer who has not read the design can check, and independence from 4b and phase 5 (name any dependency). Phase 7's entry conditions (what it needs from phase 6).
7. Verification honesty: for every golden and hand-derived number, say whether you derived it twice and verified it against code; no unproven universal claims; no private-var access in any specified test; portability traps (Dart/Node: nil-valued map keys, `for` over large seqs, reader whitespace, duplicate protocol params).

Report to `collab/1791730000000-architect-srvm-phase6-design.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then: the design's decisions, what you verified in code (cite file:line), what is unverified, owner questions (or "none"), and the list of documents needing amendment with the amendment text location.

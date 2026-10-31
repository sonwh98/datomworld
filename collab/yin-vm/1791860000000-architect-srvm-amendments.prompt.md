Created-GMT: 2026-10-11 05:10:00 GMT
Created-Local: 2026-10-11 12:10:00 Asia/Bangkok

# Task: apply the accepted amendments to the frozen design and the dependent documents

Role: Lead System Architect. Worktree `/Users/sto/workspace/datomworld-srvm-amend`, branch `srvm-amend` (from master 993d5411). You edit DOCUMENTS ONLY (no source, no test). No git add/commit.

The two designs now on master propose exact amendment text, reviewed and ruled by codex over several rounds:
- `docs/design/yin.vm.semantic-register-vm.derivations.md` §9: F8-F14 against the frozen design (`yin.vm.semantic-register-vm.md`), and §9.2 the unitarity amendments to `yin.vm.linker.md` and UCF.
- `docs/design/yin.vm.semantic-register-vm.continuation.md` §10: F1-F4 (frozen design §4.2, §4.3, §10 phase 6), U1-U7 (UCF §7.3.4, §7.4.1-7.4.3, §7.6, §7.11, plus the historical-reader scope U7), R1-R11 incl. R1a, R6a (the row carrier draft `yin.vm.ucf-transport-tuples.md`), C1 (`yin.vm.code-as-tuples.md` §7.2).
Apply EVERY amendment, exactly as the design text specifies (replacement text is given; do not reword substantively, adjust only for context and cross-references), in these target documents: `yin.vm.semantic-register-vm.md`, `yin.vm.universal-continuation-format.md`, `yin.vm.ucf-transport-tuples.md`, `yin.vm.code-as-tuples.md`, `yin.vm.linker.md`. Codex's rulings (ACCEPT / ACCEPT WITH CHANGES) are recorded in the two designs' review files under `collab/` of `/Users/sto/workspace/datomworld-srvm-p5-design` and `/Users/sto/workspace/datomworld-srvm-p6-design`; where a ruling says "with changes", the design text already incorporates the change (verify).

Rules:
- Also add the amendment banner to the carrier draft stating it supersedes its stale §5.2/§8-step-7/"v3 stamp does not move" text; remove stale `:segment/sha256-…` spellings in UCF prose (A uses Jing's default digest, BLAKE3); keep the unitarity invariant wording consistent with `docs/design/datom.world.md`.
- Where two amendments touch the same passage, reconcile and say so in the report. Do not weaken either.
- Update each amended document's header/status record (what changed, which design proposed it, date 2026-10-11).
- Do not touch the two companion designs (they remain the record), the other design documents, `docs/design/datom.world.md`, or any code.
- Verify, by grep, that no stale statement named in the designs' amendment inventories survives (legacy-reader requirements, semantic-only gates, unchanged-profile claims, no-kernel-translation, vector-to-datoms loading, `stack-cref`/`:regs`/`:kframe` stack-base rows, "v3 stamp does not move").
- If an amendment's anchor no longer matches (line numbers drifted), find the passage by its text; if the text itself is ambiguous or conflicts with something else, STOP on that item and report it rather than guessing.

Report to `collab/1791860000000-architect-srvm-amendments.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then a table: amendment id | target document and section | applied (yes/partial/no) | notes; the grep verification; reconciliations; anything unapplied and why.

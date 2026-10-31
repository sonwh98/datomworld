Created-GMT: 2026-10-11 07:55:00 GMT
Created-Local: 2026-10-11 14:55:00 Asia/Bangkok

# Task: adversarial review of slice 6a (the register frame validator and the row codec)

Role: Adversarial Reviewer / Lead System Architect. Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 4b0e735e (diff `git diff master...HEAD`). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally; foreground only. Before applying any mutant take a verified byte backup of the file you mutate into `collab/` and restore from it (`cmp` and `git diff --quiet HEAD -- src/`).

Author: codex (gpt-6.1-sol); you are the non-author family. Read the brief `collab/1791920000000-vm-engineer-srvm-phase6a.prompt.md`, the report `collab/1791920000000-vm-engineer-srvm-phase6a.report.md`, and the governing design `docs/design/yin.vm.semantic-register-vm.continuation.md`: §0 rulings, §1 (canonical frame), §2 (rows, census, bijection), §3 (the 24-rule validator and its pure input contract, validation order), §5.5 (re-emission for equality), §8.1 (slice 6a acceptance items 1-11), §9, §12. Orchestrator verified on JVM: frame-test + rows-test 21 tests / 481 assertions, 0 failures; kondo 0/0. Dart not run by anyone yet; Node by the author only.

Do, adversarially:
1. For each of the eleven acceptance items, is there a test that can FAIL? Apply at least fifteen mutants to `frame.cljc` and `rows.cljc` yourself: skip or reorder a validator rule; drop the nil-membership case; E2 not requiring lexical presence; X1 free-env reserved names unchecked; tail bookkeeping unchecked; structural check after a dependent read; row id built without a child; census disagree not refused; refinement ties broken by input order; window nil key dropped on encode; `[:lit nil]` confused with absence; two cells swapped in numbering; search bound removed; hash digest changed from the Jing default; `rows->frame` accepting an extra key; the `task-defect` order vs frame order; plus your own. KILLED (which test) or SURVIVED. The author reports nine mutation logs; compare.
2. Rule coverage: all 24 rules S1-S4, C1-C3, A1-A6, T1, K1-K3, E1-E2, X1, P1-P4 each have a tamper row (25 rows) reachable with its OWN refusal kind; the validator's published input contract is plain data (no engine); `frame.cljc`'s ns form requires no kernel and no `yin.vm.engine`.
3. The row codec: bijection with the keyed frame for every frame the evaluator mints (4a/4b); canonical numbering (refinement plus bounded exhaustive search) deterministic and renaming-invariant; BLAKE3 only (no SHA-256 use); re-emission procedure (§5.5) correspondence-based, not traversal-order dependent; unordered-container permutation tests.
4. Independently derive at least two of the design's worked rows (e.g. the x=99 free-env program's root, a nil-valued register encoding) and compare with the tests' pinned values.
5. Scope: only the four new files; nothing of 4b/5 touched; no private-var access; portability (nil-valued keys, `for` over >32-element seqs, reader whitespace, protocol param names; JVM-only branches `#?(:cljd nil :clj ...)` with :cljd first).
6. Honest claims: unproven universal claims in test names or comments; anything the author resolved by reading the design differently.

Write `collab/1791940000000-reviewer-srvm-phase6a.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-6, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.

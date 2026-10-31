Created-GMT: 2026-10-11 06:30:00 GMT
Created-Local: 2026-10-11 13:30:00 Asia/Bangkok

# Task: adversarial review of slice 5a (the resolved vector, addresses, A -> H)

Role: Adversarial Reviewer / Lead System Architect. Worktree `/Users/sto/workspace/datomworld-srvm-p5a`, branch `srvm-phase5a`, HEAD 8df1d117 (diff against master: `git diff master...HEAD`). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally (zsh does not word-split a variable); foreground only. Before applying any mutant, take a verified byte backup of `src/cljc/yin/vm/semantic_register/derive.cljc` into `collab/` and restore from it; verify with `git diff --quiet HEAD -- src/`.

Author: codex (gpt-6.1-sol); you are the non-author family. Read: the brief `collab/1791850000000-vm-engineer-srvm-phase5a.prompt.md`, the author's report `collab/1791850000000-vm-engineer-srvm-phase5a.report.md`, the governing design `docs/design/yin.vm.semantic-register-vm.derivations.md` (§0 rulings, §1, §3, §4, §5, §6, §10.1 acceptance items 1-10, §12) and the frozen design §3, §8.3. Owner rulings that bind: R and H lowered from A alone; no adapter; no version bump; the old compilers are an execution oracle only; H need not equal the old bytes.

Orchestrator verified: JVM derive-test + code-test + vm-test 35 tests / 3187 assertions, 0 failures; kondo 0/0. Dart not yet run (another lane busy); Node by the author only (356 tests / 7067).

Do, adversarially:
1. For each of the ten acceptance items, is there a test that can FAIL for a broken implementation? Apply at least twelve mutants yourself to `derive.cljc` (wrong de Bruijn depth/position; shadowing order for duplicate parameters; `:load-bound`/`:load-free` swapped; closure arity off by one; body order not FIFO; label off by one; a tail flag lost; push omitted for an operand; operator pushed after operands; `:define` handling; conditional join pc; `:names` side table wrong; a stage-value leak of an identity function; `register-image` not throwing; plus your own). Each: KILLED (which test) or SURVIVED. Compare with the author's 14-mutant table; judge any survivor.
2. Independently derive at least four of the §6.3 H goldens (including the conditional-operand row with pcs 6/7 and the tail-call closure row with body pc 6) from the emitter rules and compare with the pinned vectors; recompute at least two pinned checksum strings with the public hash function.
3. The execution oracle (5a item 7): corpus membership, counts 26/12/19/1, the `native-normalize` of closure results, the explicit `:op/ping` bridge; does it run the OLD pipeline only as an oracle, with no `dl/adapt` in any H comparison?
4. The lexical-address law (item 8): the four legs, the non-vacuity assertions, the corrected `resolved-addresses` walk that skips the definition operator.
5. Scope: only `derive.cljc` and `derive_test.cljc` created, nothing else changed; no private-var access; no hidden dependency on `yin.vm.code` (the retired stack table); portability traps (nil-valued map keys, `for` over >32-element seqs, `(- x)`, protocol param names, reader whitespace).
6. Anything the author resolved by reading the design differently; unproven universal claims in test names or comments.

Write `collab/1791870000000-reviewer-srvm-phase5a.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-6, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.

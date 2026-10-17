Created-GMT: 2026-10-11 10:40:00 GMT
Created-Local: 2026-10-11 17:40:00 Asia/Bangkok

# Task: adversarial review of slice 5b (A -> R, linear-scan register allocation over A's liveness)

Role: Adversarial Reviewer / Lead System Architect. Worktree `/Users/sto/workspace/datomworld-srvm-p5b`, branch `srvm-phase5b`, HEAD b2eadc83 (diff `git diff master...HEAD`). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally; foreground only (long runs: do not run the slow C4 row; the author already ran it on three hosts). Before applying any mutant take a verified byte backup of `src/cljc/yin/vm/semantic_register/derive.cljc` into `collab/` and restore from it (`cmp` and `git diff --quiet HEAD -- src/`).

Author: codex (gpt-6.1-sol) drafted it; claude-opus-5-5 verified the lanes and ran the mutation table without editing. You are a different Claude model but the review must still be adversarial and independent; do not trust either report. Read the brief `collab/1791950000000-vm-engineer-srvm-phase5b.prompt.md`, the report `collab/1791950000000-vm-engineer-srvm-phase5b.report.md`, the governing design `docs/design/yin.vm.semantic-register-vm.derivations.md` (§2 A -> R, §4, §5, §6.1-6.3, §8, §10.2 items 1-11, §12). Owner rulings that bind: R lowered from A alone; no adapter; no version bump; R need not equal the old bytes. Orchestrator verified on JVM: derive-test + debruijn-register-code-test (fast) 22 tests / 6200 assertions, 0 failures; kondo 0/0. Author reports: JVM slow C4 row 1 test / 90 assertions; Node (subsystem, slow on) 1258 / 57700; Dart (subsystem, slow on) +1187; all 0 failures.

Do, adversarially:
1. For each of the eleven acceptance items, is there a test that can FAIL? Apply at least fifteen mutants to `register-image` and its helpers: expiry `<` vs `<=`; lowest-free vs highest-free; free set unsorted; interval end not widened over layout syntax; structural slot `locals + peak` off by one; parameter offset; live point missing `defs(p)`; `live` filled from the wrong analysis or not sorted; tail call `live` non-empty; a conditional join result not sharing the slot; `:define` result slot; `:resume`/park destination; body descriptor `registers`/`locals` wrong; `:ffi-call`/`:stream-put` operands; a `:call` rd/operator slot; closure arity; plus your own. KILLED (which test) or SURVIVED; compare with the author's 10-mutant table (the author reports one survivor: judge it).
2. Independently derive at least five of the §6.3 R goldens (including `(f (g x) y)` with `[:call 3 1 [2] false [0]]`, `((f))`, `(f (if c 1 2) y)`, the tail-call closure body with `live []`, `(f (resume :p x) y)` with the structural slot) from design §2 and compare with the pinned vectors; recompute two `register-hash` strings with the public hash function.
3. The two-liveness agreement (item 3): actual successors, tail `live []`; the structural-slot test (item 4); determinism; the execution oracle (counts 26/12/19/1, `native-normalize`, `:op/ping` bridge) runs the OLD lowerers only as an oracle; the lexical-address law (all legs).
4. Scope: only the three allowed paths; no private-var access; no dependency on the old lowerers or kernels from `derive.cljc` (only `analysis` and `rcode`); portability traps (nil-valued keys, `for` over >32-element seqs, sorted-set on Dart, reader whitespace).
5. Honest claims: unproven universal claims in test names or comments; anything resolved by reading the design differently; the report's departure from "foreground only" for the slow lanes (acceptable?).

Write `collab/1792000000000-reviewer-srvm-phase5b.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-5, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.

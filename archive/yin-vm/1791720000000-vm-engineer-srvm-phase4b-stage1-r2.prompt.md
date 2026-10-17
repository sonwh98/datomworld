Created-GMT: 2026-10-10 18:45:00 GMT
Created-Local: 2026-10-11 01:45:00 Asia/Bangkok

# Task: 4b Stage 1, round 2 -- close the reviewer's test gaps (continuing your thread)

Worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4b`, HEAD e47fcbd6 (your Stage 1, committed by the orchestrator). Do NOT `git add` or commit. TEST-ONLY changes: `src/cljc/yin/vm/semantic_register.cljc` must stay byte-identical (check `git diff --quiet HEAD -- src/` at the end). Read the independent review: `collab/1791710000000-reviewer-srvm-phase4b-stage1.claude-fable-5-1.findings.md` (14 mutants; it found no source defect).

Fix, in `test/yin/vm/semantic_register/effects_test.cljc` (and `slice-4a-extra-rows` if the walker-compared row belongs there):
1. M-G row: add `((fn [x] (list ((fn [r] (if (= r 7) r ((fn [x] (r 7)) 9))) (current-continuation)) x)) 1)` -> `(7 1)`, compared with the walker (not just register). Derive the trace and check the walker yields `(7 1)`; apply M-G yourself (continuation invoke keeps the invoker's `E` instead of `(:env restored)`), confirm the row FAILS with `(7 9)`, restore byte for byte.
2. M-I: in `park-resume`, assert `(nil? (:control parked))` and `(nil? (:k parked))` on both parked machines. Apply M-I (park leaves `:control` set, clears only `:k`), confirm a named test fails, restore.
3. M-O: in `tail-effect-returns-through-k` assert `(nil? (:control blocked))` and `(nil? (:k blocked))` in both the one-frame and the empty-K cases. Apply M-O (run-effect's blocked stop clears `:control` not `:k`), confirm the non-main case fails, restore.
4. Hang risk (M-K: continuation resumes at its own capture pc loops forever in an unbounded `vm/run`): run the five continuation value programs under a step bound (as `tail-countdown` does), so a looping mutant FAILS instead of hanging. Apply M-K, confirm it fails fast, restore.
5. Rename `every-vm=` (it runs the register machine only) to an accurate name, or make it compare with the walker.
Do NOT fix M-P (undeclared-effect profile row) or the `not-in-slice!` naming: they carry to Stage 2.

Verify (foreground, literal `-n` flags, never a variable): JVM `effects-test vm-test walker-trace-test parity-test`; Node lane for those four (confirm "Testing <ns>"); Dart `bb src/dev/cljd_agg.clj --only ...` if the sandbox allows, else record the exact error and say NOT RUN. kondo and cljstyle on changed files.

Report to `collab/1791720000000-vm-engineer-srvm-phase4b-stage1-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: change, evidence it can fail (mutant killed by which test), exact lane counts. Do not claim runs that did not occur.

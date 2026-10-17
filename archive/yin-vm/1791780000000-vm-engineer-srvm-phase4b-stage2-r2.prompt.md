Created-GMT: 2026-10-10 19:45:00 GMT
Created-Local: 2026-10-11 02:45:00 Asia/Bangkok

# Task: 4b Stage 2, round 2 -- resolve acceptance item 26's one failure (continuing your thread)

Worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4b`. Do NOT `git add`/commit. Your Stage 2 report: `collab/1791750000000-vm-engineer-srvm-phase4b-stage2.report.md`.

ORCHESTRATOR RULING on the conflict you reported: `a-body-read-applied-before-its-definition-keeps-its-obligation-test` in `test/yin/vm/linker_require_test.cljc` iterates `(keys backends)` and needs a REAL dependency scanner to retain `y`. The register fixture's `(constantly [])` obligations (the design's own install-mechanics fixture until phase 5 supplies the register-vector scanners) cannot satisfy it; this is exactly the "obligations-fixture follow-up" that the phase-5 design (docs/design/yin.vm.semantic-register-vm.derivations.md, slice 5c) assigns to whichever slice lands second. Therefore: in THAT ONE test (and any other test in the file that you find asserts retained scanner obligations), exclude the `:semantic-register` backend with a minimal expression such as `(remove #{:semantic-register} (keys backends))` and a comment: "excluded until phase 5c supplies the register-vector scanners; install mechanics are covered by link-test". This is the only modification of an existing line in the four exception files allowed, and it must be exactly this exclusion. Keep every other existing entry and assertion untouched. Report which tests you excluded it from (expect exactly one; if more, list them and why).

Then re-run, foreground, literal `-n` flags: the full requested JVM regression group (the 8 namespaces of your report) plus the semantic-register namespaces; kondo and cljstyle on every changed file. Node lane as in your report. Dart: leave it, the orchestrator runs it. Do not change source `semantic_register.cljc` (check `git diff` of it is unchanged from your Stage 2 state). Also record the one equivalent mutant (decoder/table-removal local-binding swap) plainly.

Report to `collab/1791780000000-vm-engineer-srvm-phase4b-stage2-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Exact lane counts, the exclusion sites, and an honest 26-item list (item 26 PASS only if every requested lane you can run is green; Dart NOT RUN by you).

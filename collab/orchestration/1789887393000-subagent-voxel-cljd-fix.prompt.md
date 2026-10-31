Created-GMT: 2026-09-20 07:01:00 GMT
Created-Local: 2026-09-20 14:01:00 +07 (Indochina Time)
Session-ID: 40ba35cb-3cda-4f40-958b-24a904991e6a
# Task: diagnose and fix the 29 CLJD voxel test failures

Role: Scoped / Subagent (bounded debug)

You are on glm-5.3-flash on the flat plan — be economical. Master's CLJD
lane has been red since PR #44 (voxel input through dao.gui.event): 23
failures in `datomworld.demo.voxel-input-test` and 6 in
`datomworld.demo.voxel-runner-test`. They fail identically on master and
have never been diagnosed. This working tree (`voxel-test-fix`, off
master@d90f5ec6) exists for your fix.

**DIAGNOSE-FIRST GATE:** read the failing tests and the code under test
BEFORE editing anything, and form a root-cause hypothesis. Read:
- `test/cljd-out/datomworld/demo/voxel-input-test_test.dart` output from a
  run (run the lane first: `clojure -M:cljd test`, capture the failure
  text)
- `src/cljc/datomworld/demo/voxel_input.cljc` (the input wiring under
  test)
- `src/cljc/datomworld/demo/voxel_runner.cljc`
- `src/cljc/dao/gui/event.cljc` only as far as the wiring uses it
- `test/datomworld/demo/voxel_input_test.cljc` + `voxel_runner_test.cljc`
  (the shared tests — do the CLJD failures mirror CLJS behavior, or are
  they Dart-host-specific?)

Then:
- If the root cause is BOUNDED (one wiring defect, a host-specific
  nil/shape difference, a missing cljd branch): fix it, run the lanes,
  done.
- If it looks STRUCTURAL (the dao.gui.event contract itself, multiple
  independent causes, or anything that would touch `dao.stream` or
  `dao.gui.event`'s signed surface): STOP. Do not edit. Report the
  diagnosis and stop — the orchestrator escalates.

Scope: `src/cljc/datomworld/demo/voxel_*.cljc`, the two shared test
files, and their cljd adapters if any. Touch nothing under `src/cljc/dao/`
— the dao.gui.event surface is signed and out of scope; if the defect is
there, that is a STOP-and-report finding.

Verification, one single simple command per step (no chaining):
1. `clojure -M:cljd test` — your target: the 29 failures gone, and zero
   failures anywhere (the rest of the lane is green on master).
2. `clojure -M:test` — the JVM lane must stay green (1527/168550).
3. `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` — the
   CLJS lane must stay green (the shared tests must still pass on Node).
Environment: the mise.toml here is identical to the main repo's; run
`mise trust` on it if needed, or use the direct install paths under
`~/.local/share/mise/installs/` (java 21, flutter 3.47.4, node 25.6.1,
clojure 1.12.4.1602). One simple command per step; no chaining. No
staging, no commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the ROOT CAUSE in two sentences, the fix, files changed,
exact lane outcomes, and whether you hit the structural STOP gate.

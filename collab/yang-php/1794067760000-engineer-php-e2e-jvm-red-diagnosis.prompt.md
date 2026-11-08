Created-GMT: 2026-11-07 13:1x GMT
Created-Local: 2026-11-07 20:1x +07
Coding-Agent: qwen
Session-ID: 8707f420-722f-4c30-bed2-c47c1f2f0bde

# Task: diagnose the yang.php.antlr.e2e-test JVM red (timeout at 30 min on jupiter @ master 3d2f20db)

Role: DaoStream and Distributed Protocol Engineer (DIAGNOSIS FIRST; implement only if
the fix is contained to the php test surface)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-07 20:1x +07 | Status: active | Rationale: boundary-neutral red on master; owner directive to green the non-DHT failing tests

## Verified facts (do not re-derive)

- master 3d2f20db, jupiter ~/ci/datomworld-nightly (clean, detached at 3d2f20db):
  DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n yang.php.antlr.e2e-test → killed
  by a 30-minute timeout with NO test summary (log /tmp/jvm-yang.php.antlr.e2e-test.log,
  534 bytes: Timbre init + "Running tests in #{\"test\"}" only — it may have produced
  no output before the kill, i.e. it could be hanging EARLY, even before the first test).
- The QA brief (collab/general/1794033000000-qa-orchestrator-nightly-reds-20261106.brief.md,
  item 5) says: still red after the P8 manifest regeneration landed on master; the
  pre-regeneration reds were gc_023.phpt and string-offset-write pins
  (collab/yang-php/1793765688000 handoff). If the same pins, the yang.php seat's
  regeneration is incomplete; if new, re-file.
- The measured fast-lane note (build-n-test.md): Python e2e tests sit on a ~3s floor
  each; the yang.python e2e slow set is large. yang.php.antlr.e2e-test may simply be
  LONG — the 30-min cap was the inventory script's, not a measured limit.

## Your job

1. DETERMINE hang-vs-long: on jupiter (own the lane — kill any orphan java from the
   earlier inventory first: pgrep -af clojure to LIST, then kill the exact PID),
   rerun the single namespace with NO timeout but with progress capture:
   cd ~/ci/datomworld-nightly (verify clean at 3d2f20db); eval "$(mise env -s bash)";
   DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n yang.php.antlr.e2e-test with output
   to a log you tail every 60s for up to 15 minutes. Record: does it emit test progress
   (clojure.test prints per-assert only on failure — if silent, use -v
   yang.php.antlr.e2e-test/<first-var> or a jstack/pstree snapshot to see where it is).
2. If it is genuinely slow-but-progressing: report the measured wall time per phase and
   STOP — the fix is then a lane-scheduling question (slow-tier budget), not a code
   bug; the orchestrator will rule.
3. If it is HUNG (no progress for 10+ min): capture jstack of the java process twice
   60s apart, identify the stuck frames, and diagnose the hang locus. If the fix is
   contained to yang.php test/ or src/cljc/yang/php/ files, implement it on branch
   nightly-reds-fix (worktree /tmp/nrf-branch-wt @ e0fa8b0a — pull latest first;
   commit nothing), verify per the scoped ruling (the single namespace, longer window),
   and report the diff. If it requires touching anything else, STOP and report.
4. Compare with the pre-regeneration pins (gc_023.phpt, string-offset-write): state
   explicitly whether the current failure matches those signatures (regeneration
   incomplete) or is new (re-file).

Constraints: your lane is jupiter's JVM lane only; the node clone on shiva is owned by
another seat right now — do not touch it. No suite-scale runs; single namespace only.
No commits. Do not weaken tests. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: hang-vs-long verdict with evidence (timestamps, jstack excerpts if hung), the
measured wall time or hang locus, the signature comparison vs the pre-regeneration
pins, and (if you implemented) the diff --stat and verification output. Do not claim
runs that did not occur.

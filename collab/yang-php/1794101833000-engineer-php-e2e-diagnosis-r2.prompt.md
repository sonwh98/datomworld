Created-GMT: 2026-11-08 01:5x GMT
Coding-Agent: muse
Session-ID: 2df799f0-416f-4183-800d-c449a3ecf2db

# Task: php e2e JVM red diagnosis r2 (hang vs long; prior round killed in relaunch collateral)

Role: DaoStream and Distributed Protocol Engineer (DIAGNOSIS; implement only if contained to the php surface)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 | Status: active | Rationale: qwen quota exhausted to 12-04; muse lane; jupiter JVM lane exclusive
- Status-Event: 2026-11-08 21:11:36 +07 | Model: muse-wonder-1.0 | Status: timed-out | Rationale: 12h+ stalled on laptop with no log growth (559 bytes); killed by orchestrator seat takeover

## Facts (do not re-derive)

- master 001b8844 (the nightly-reds-fix merge just landed — php frontend now on
  node via 28bdc3d0; the php e2e JVM red may have CHANGED character: re-check).
- Prior inventory run: 30-min timeout, 534-byte log, zero failure lines.
- Nightly 20261106-0944 slow-yang.php.antlr.e2e-test.log COMPLETED: 12 tests /
  1046 assertions / 1 failure — phpt-bug40809-stress-test, a STALE GAP FIXTURE:
  expected {:timeout ">5400 s ..."} actual {:out "ok"} — the engine now completes
  the bug40809 stress loop (fixture measured 2026-11-04 on shiva).
- So the likely full failure set = that one stale gap pin, and the namespace
  completes within a nightly window. The 30-min cap was the inventory script's.

## Your job

1. On jupiter ~/ci/datomworld-nightly (fetch, checkout --detach origin/master,
   verify 001b8844): kill any orphan java from earlier inventory runs by exact
   PID first (pgrep -af to LIST). Then DATOM_SLOW_TESTS=1 clojure -M:test -i :slow
   -n yang.php.antlr.e2e-test, no cap, tail progress every 60s (the namespace may
   take ~1h+). Report the measured wall time, the full failure list, and whether
   the only red is the bug40809 gap pin.
2. If the ONLY failure is the stale gap pin: the fix is measure-then-pin — rerun
   JUST that stress test once (with str_repeat) to measure its actual wall time on
   jupiter, update the gap record (expected engine-result) to the measured value,
   on branch nightly-reds-fix via a scratch worktree (git worktree add --detach
   /tmp/php-e2e-fix origin/nightly-reds-fix; commit nothing), and verify the
   single namespace green. Report the measured number and the diff.
3. If other failures appear: diagnose and report; implement only if contained to
   test/yang/php/ or src/cljc/yang/php/ files; otherwise STOP and report.

Constraints: single namespace only; jupiter JVM lane yours exclusively tonight;
no commits; no suite-scale runs. Begin the final response exactly with
Completed-GMT / Completed-Local. Do not claim runs that did not occur.

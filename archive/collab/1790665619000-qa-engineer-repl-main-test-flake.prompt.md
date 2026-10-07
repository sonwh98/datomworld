Created-GMT: 2026-09-29 07:06:59 GMT
Created-Local: 2026-09-29 14:06:59 +07 (+0700)
Coding-Agent: claude
Session-ID: f9fe29ab-85ec-4a4d-8667-ca972a237a16
# Task: Diagnose and fix the yin.repl.main-test cross-process flake

Role: QA / Yin.REPL Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 14:06:59 +07 (+0700) | Status: active | Rationale: owner chose the post-epic queue ("5"); root-cause diagnosis of a timing flake in the landed wire path

Work in /Users/sto/workspace/datomworld (master 3cf3c6de). Edits authorized only in the files named below. Do not stage
or commit. docs/orchestrator-log.md is modified by the orchestrator; do not touch it.

The flake (evidence, orchestrator-verified this session):
- test/yin/repl/main_test.cljc cross-process tests fail intermittently, a different one each time, always the same
  shape: remote-value (~558) / await-event (~497) / await-server-notice returns nil where "7" or a notice is expected.
  Observed failing: killing-the-connection-is-observable-and-requests-are-lost (~603, :608),
  reattaching-resumes-the-served-stream-and-the-deposit-medium (~633, :638),
  stopping-the-endpoint-ends-the-served-stream-not-closes-it (~669, :675),
  evaluation-round-trips-and-two-clients-get-their-own-answers (~585, :592).
- Rate: ~1 in 4 namespace runs on a clean master worktree (1a52b61c, before this session's changes); in one idle
  period 5 of 6 failed, later 4/4 passed — bursty, so not purely CPU load. event-ms is 15000 (~217) and the test's
  own cold first eval takes ~86 ms, so this is very unlikely to be a slow machine hitting the budget: suspect a MISSED
  event (publication race, reply read before subscription, pump ordering, a stdout line split/interleaved and dropped
  as non-Transit noise in start-peer!'s pump, process B not ready when A sends), not a slow one.
- Prior log note: "remote-value's await-event occasionally misses event-ms" — an earlier seat's hypothesis, unproven.

Task:
1. Reproduce reliably first (loop the namespace or the single test; instrument temporarily if needed). Record the
   failure rate before the fix.
2. Find the ROOT CAUSE with evidence (logs/instrumentation/trace of the event that was expected and what happened to
   it). Do not just raise timeouts or add sleeps; a timeout change is acceptable only if the evidence shows the event
   does arrive but late, and you must say so.
3. Fix it at the cause. Allowed files: test/yin/repl/main_test.cljc; the peer entry used by the test
   (src/cljc/yin/repl/slice_peer.cljc or wherever yin.repl.slice-peer lives); src/cljc/yin/repl/*.cljc on the served
   wire path (serve, connect, adapter, driver, link) if the race is in production code. If the fix needs dao.stream.*
   or any other namespace, STOP and report the exact change needed.
4. Prove it: loop the namespace enough times to make the old failure rate statistically visible (e.g. 20+ runs) and
   report before/after counts. Remove all temporary instrumentation (grep).

Verify and report exactly: clj -M:kondo --lint <changed files>; cljstyle check (say if blocked); the loop counts;
clj -M:test -n yin.repl.main-test plus any yin.repl test ns you touched; the full clj -M:test once; bb test:cljs.
Do NOT run bb test:cljd. If the cross-host (Dart peer) variant is affected, say so; build/yin-repl-peer may need
bb build:yin-repl-peer if you touch the peer.

Write the report to collab/1790665619000-qa-engineer-repl-main-test-flake.claude-opus-5-5.report.md and give it as your final
response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f9fe29ab-85ec-4a4d-8667-ca972a237a16
Report the root cause with evidence, the fix, before/after rates, changed files, exact test outcomes, and anything
unresolved. Do not claim a root cause you did not demonstrate.

- Status-Event: 2026-09-29 14:56:31 +07 | Model: claude-opus-5-5 | Status: failed | Rationale: run exited 1 on "API Error: Can't reach the API server — check your internet or DNS (ENOTFOUND)"; network, not task failure; left src/cljc/yin/repl/driver.cljc modified, main_test.cljc clean
- Model: claude-opus-5-5 | Assigned: 2026-09-29 14:56:31 +07 | Status: active | Rationale: owner said "retry"; same session resumed (log -r2)

## Round 3 addendum (2026-09-29 15:07:20 +07, orchestrator)
Root cause and driver fix accepted for verification. Scope extended by the orchestrator to ONE more file:
test/yin/repl/driver_test.cljc — add the deterministic regression test you proposed (rpc client with a :newest cursor
whose mint answers retry; submit a remote line -> nothing appended, line queued; mint succeeds -> line sent). Prove it
bites by temporarily reverting the driver guard (test must fail), restore, grep. Do not touch dao.stream.rpc (its
cursor-pending suggestion goes to the Architect). Do not rebuild build/yin-repl-peer (the orchestrator does). Run kondo
on driver_test.cljc and clj -M:test -n yin.repl.driver-test -n yin.repl.main-test. Append to your report as
report-r3.md.

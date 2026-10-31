Created-GMT: 2026-09-29 08:52:06 GMT
Created-Local: 2026-09-29 15:52:06 +07 (+0700)
Coding-Agent: claude
Session-ID: c2557c35-ba40-4377-b51a-aad2152a95cb (resumed)
# Task: q on require fix round 1 — gate P2s (rollback eviction, unbounded query drive)
Role: Yin.VM Runtime Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 15:52:06 +07 (+0700) | Status: active | Rationale: same session

Same worktree (/Users/sto/workspace/datomworld-q-require), same allowed files, no staging/committing. Gate
(/Users/sto/workspace/datomworld/collab/1790671839000-reviewer-repl-q-on-require-gate.gpt-6-sol.findings.md) accepted
Q1-Q4 and Q7 (incl. the :yin.repl.query/query-failed code) and withheld on two findings the orchestrator verified and
accepts:

P2 | src/cljc/yin/repl.cljc ~1217, ~1458 | Both rollback paths preserve the query interpreter's cursor but restore the
VM's older response cursor; after more than 64 answered calls in one failed round the next call hits an evicted-response
gap. Fix: advance the rolled-back VM's response cursor past those abandoned answers, or otherwise discard them while
preserving unique call ids. Test: a failed round making MORE than 64 q calls, followed by a successful q.

P2 | src/cljc/yin/repl.cljc ~975 | query-serve-budget bounds one serve invocation, but query-only progress never
increments the drive loop's i, so a program that repeatedly calls q can keep one input round in the loop indefinitely.
Fix: a cumulative query budget for the drive with a defined, bounded, observable outcome when exhausted (state what
the user/host sees and how the round continues or ends). Test: a program looping on q terminates the drive within the
budget with that outcome.

Prove both by temporary mutation, revert, grep. Run kondo; cljstyle check (say if blocked); focused JVM
(yin.repl.query-test, yin.repl-test, yin.repl.index-test, yin.vm.ffi-test); bb test:cljs. Not bb test:cljd. The
worktree base predates the master driver-race fix, so yin.repl.main-test may still flake there; report, don't fix.
Report both new constants/outcomes. Write
/Users/sto/workspace/datomworld/collab/1790669559000-vm-engineer-repl-q-on-require.claude-opus-5-5.report-r2.md and give
it as your final response, beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.

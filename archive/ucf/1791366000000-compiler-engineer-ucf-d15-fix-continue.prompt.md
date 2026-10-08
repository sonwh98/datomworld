Created-GMT: 2026-10-07 18:00:00 GMT
Created-Local: 2026-10-08 01:00:00 +0700
Coding-Agent: deepseek (deepseek-v4-pro)
Session-ID: c62476bd-835e-47fd-b793-272ff80cdceb

# Task: UCF M-next D15 — complete the interrupted fix round
Role: Yang Compiler and Universal AST Engineer

Continue an interrupted fix round in /Users/sto/workspace/datomworld-d10b
(worktree, branch ucf-d15-compose, base 2cf99c13 with D13/D14/D15a
landed). A glm-5.3 session was mid-way through this exact fix round and
died at its usage cap, leaving a PARTIAL uncommitted diff: compose.cljc
(durable-backend work), yin/repl/main.cljc + yin/repl.cljc (the
shutdown-drain rewrite), store_write_audit_test.clj (one allowlist
entry), compose_test.cljc (restructured), and a new fixture
test/resources/yin/vm/ucf/handoff-v2.txt.

Read first, in the main tree's collab/:
- The gate r1 findings (NOT READY): collab/1791389000000-reviewer
  -d15-compose-gate.opus.stdout.log — findings 1-4 plus the
  non-blocking notes.
- The architect sign-off r1 (CHANGES): collab/1791389000000-architect
  -d15-signoff-astra.gpt-6-astra.findings.md — items 1-3.
- The reply-transport research the fix implements:
  collab/1791376000000-research-reply-transport-replayability
  .findings.md.
- The engineer's own findings so far (in the worktree):
  collab/1791365499405-compiler-engineer-ucf-d15-compose.findings.md.
Then `git diff` in the worktree for the partial state, and the landed
code the fix composes: src/cljc/yin/vm/ucf/holder/driver.cljc (the
D15a control/program split), holder/inbox.cljc, holder/export.cljc,
authority/front.cljc, dao/stream/journal.cljc +
dao/stream/journal/file.cljc (the durable backend).

The five fixes the round must complete (from the two reviews, verbatim
in intent):
1. Durable holder storage: composition-supplied reopenable backends
   for the per-holder progress journal and reply journal (file backend
   for production, memory for tests), kept so `restart` reopens them
   by identity (the D15a driver refuses a changed inbox identity — a
   restarted process must not mint a fresh reply journal). Pin
   reconstruction without the original composition/handles/atoms,
   including a read-before-retention crash and a pending release, via
   the fresh-journal/reopened-journal crash pattern from
   driver_test.cljc:490-524.
2. The shutdown drain independent of the endpoint: enter the bounded
   drain when there is an endpoint OR custody owes a write
   (shutdown-enter?); keep stepping while the endpoint has not stopped
   OR custody owes a write (shutdown-drained?), within the existing
   stop-ticks budget; exercise each host loop's real exit condition on
   all three hosts; pin absent/already-stopped servers, releases
   blocked until a later tick, and blocked throughout the budget.
3. Journal close before exit (r3 1.12): compose/close! called on every
   host's exit path.
4. The composition stop latch for late additions: add-holder after
   stop refuses or stops the new driver; composition-level program
   stepping is a no-op after stop; pin both holder and source.
5. Fork cannot run an exclusive export: close the path and pin it.

Then the ten-row audit table in the partial findings (the
whole-task contract audit) — each row's "remaining defects" column is
your worklist; the ordering clarification (audit's final section) is
ruled: the landed completion flow is the contract (offer attempt,
report, release/closure, retry the offer until admitted).

Acceptance criteria:
- Test-first per row; portable .cljc; JVM during iteration; the
  three-lane gate is the orchestrator's.
- The disclosed store_write_audit allowlist entry stays (justified).
- The permitted diff: compose.cljc, yin/repl/main.cljc,
  yin/repl.cljc, their tests, and the allowlist entry already in the
  tree. Anything else: stop and report.
- No git writes.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

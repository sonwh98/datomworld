Created-GMT: 2026-10-27 20:57:25 GMT
Created-Local: 2026-10-28 03:57:25 +07 (+0700)
Coding-Agent: claude
Session-ID: cdbb393f-cb95-4334-8e07-0ae689df1e64
# Task: J6a — re-pin the Autobahn case policy to 298/3/216 (amendments 1-3)
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-28 03:57:25 +07 (+0700) | Status: active | Rationale: implementation per routing; reviewer will be codex (different family)

Implement in /Users/sto/workspace/datomworld-jolt-j6a (branch jolt-j6a; harness under tools/jolt-autobahn/). Read first: collab/architect-verdict.md (the Architect's ratification; owner words "ask the architect" delegated the decision), collab/astra-mob.md (the 298/3/216 reconciliation), docs/design/yin.repl-jolt-implementation.md §9.5-§9.6 and §13.5, docs/design/dao.stream.ws.md (Host wire codes), tools/jolt-autobahn/*. Treat prior reports as claims; verify against the files.
Acceptance (amendments 1-3 only; 4-6 are for the later J6 engine brief, do not implement them):
1. 7.1.6 becomes exception-listed for client and server rows: behavior INFORMATIONAL, behaviorClose INFORMATIONAL, remoteCloseCode 1000, plus a new evaluator assertion that the fuzzer received no Pong after its Close. Confirm the exact report field against the pinned report schema / pinned upstream (pin.json) BEFORE locking it; if the schema cannot express it, stop and report. Counts become 298 must-pass / 3 exception-listed / 216 N/A, six exception rows, 301 executions per role, 1,204 total. Update cases.json, exceptions.json, pin.json hashes, triage.md header, README, the design-doc note, and the evaluator tests; add negative fixtures for 7.1.6 (a Pong after Close fails; wrong close code fails).
2. Add a jolt row to the Host wire codes section of dao.stream.ws.md: any inbound close code outside 1000-1003, 1007-1011, 3000-4999 is answered with a 1002 close frame and exactly one terminal closed event; label it a host-profile choice, distinct from application-level 4002. Make the 7.13.x exception reasons cite that row.
3. Reword "reject extension offers" to "ignore offers, negotiate none" (client never offers, fails an unsolicited extension in the 101; RSV frames close 1002) and add the handshake gate row to §9.6. No 12.x/13.x sentinels in the locked policy.
Work only in tools/jolt-autobahn/, docs/design/yin.repl-jolt-implementation.md and docs/design/dao.stream.ws.md. Do not stage or commit anything (commit authority is the orchestrator's). Run the harness unit tests (python3 -I tools/jolt-autobahn/test_harness.py) and any doc/format check, in the FOREGROUND only, never background a lane. Do not terminate or touch other worktrees.
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: cdbb393f-cb95-4334-8e07-0ae689df1e64
Report changed files, exact test outcomes with counts, unresolved concerns, incomplete work.

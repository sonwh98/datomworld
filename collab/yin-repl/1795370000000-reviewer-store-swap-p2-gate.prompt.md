Created-GMT: 2026-11-23 00:05:00 GMT
Created-Local: 2026-11-23 07:05:00 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: 29f740e7-285e-4b94-a559-27455325c321 (resume of your design conversation)

# Task: store-swap Slice 2 — adversarial implementation review

Role: Review (adversarial)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-23 07:05 ICT | Status: active | Rationale: different family from the GPT author (reviewer independence); you authored the design record and reviewed Slice 1

Slice 2 (DHT composition store swap) is implemented by gpt-6.2-sol on branch
store-swap-p1 in /Users/sto/workspace/datomworld-store-swap, UNCOMMITTED, on
top of the APPROVED Slice 1. Review the ACTUAL DIFF, focusing on what changed
since Slice 1 (git diff covers both slices; Slice 1 was already reviewed and
approved — concentrate on src/cljc/yin/shell/cli.cljc, src/cljc/yin/shell/dht.cljc,
test/yin/repl/dht_test.cljc, and any main.cljc hook changes).

Read first (all in the worktree):
- collab/yin-repl/1795360785000-architect-store-swap-design.gemini-4-argon-high.findings.md
  (your design record; Slice 2 = "DHT Composition Store Swap")
- collab/yin-repl/1795364600000-engineer-store-swap-p2.gpt-6.2-sol.findings.md
  (implementer's report with declared decisions)

Adjudicate specifically:
1. parse-store-command fidelity: option validation through the existing
   startup validators; :manifest refusal with the load-index hint; DHT opts
   refused on non-dht: targets; trailing opts refused on mem/file; key
   loading after spec validation.
2. The synchronous bind-failed detection in yin.shell.dht/open: does the
   refusal truly fire during open (open-first intact), does the new node's
   cleanup scope close socket + durable dir without touching the old store,
   and does the delayed (async-host) path still behave per the design?
3. The implementer's four declared decisions — accept or flag each.
4. Defect hunt: resource leaks on swap paths involving a live ring (old
   session teardown, follower dials, head board, UDP socket, dir lock),
   double-close hazards, disclosure banner correctness, token/round handling.
5. Test adequacy vs your Extended Verification Bar item 2 (ring join and
   departure) and item 3 (failed-swap controls with dht targets).

Orchestrator independent verification: yin.repl.dht-test green on JVM
(50 tests/572 assertions, 0 failures) and Jolt/Chez (50/572, 0 failures,
0 load failures) run by me; Node and Dart green per the implementer's report
(50/572 with guards enabled on Node; 50 tests on Dart). kondo 0/0 on the
touched files; Gate A zero added matches; Gate B fresh boot audit clean. Do
not re-run suites.

## Deliverable

Write findings to
collab/yin-repl/1795370000000-reviewer-store-swap-p2-gate.gemini-4-argon-high.findings.md
in the worktree (stage at the worktree root if the sandbox blocks it, and say so).
Format: severity | file:line | evidence | recommended correction. End with an
explicit verdict line: APPROVED or CHANGES-REQUIRED.

Do not edit any file. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: findings summary and the verdict line.

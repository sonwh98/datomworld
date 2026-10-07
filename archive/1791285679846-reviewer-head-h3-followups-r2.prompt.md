Created-GMT: 2026-10-06 11:21:19 GMT
Created-Local: 2026-10-06 18:21:19 +07
Coding-Agent: codex
Session-ID: 01a1103a-2d0e-7f82-b9a3-8f50d00edd21

# Task: reviewer-head-h3-followups (round 2: confirm your two P3s)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-06 14:59:28 +07 | Status: active | Rationale: same reviewer, resumed to confirm its two P3s are closed

Read-only, in /Users/sto/workspace/datomworld/.claude/worktrees/head-h3-followups (still
UNCOMMITTED). Do not edit. Engineer's report (untrusted):
`collab/1791284684337-repl-engineer-head-h3-followups-r2.claude-opus-5-5.stdout.log`.

Your two P3s (`collab/1791283601886-reviewer-head-h3-followups.gpt-6.1-sol.findings.md`):

1. Quadratic BOM strip: `without-boms` now scans the leading U+FEFF run by index and cuts
   once with `(subs text i)`; test: 349,525 marks (1,048,575 bytes) refuse in under 3 s,
   349,000 marks then a record read in under 3 s (the engineer measured 9.3 s and 8.1 s with
   the old code).
2. Non-leading BOM host-dependent: after the leading run is removed, any U+FEFF left anywhere
   is refused before the EDN reader sees it, on every host, with
   `heads file <path>: it is not valid: a byte order mark (U+FEFF) follows its start`; tests
   with explicit bytes for a BOM between tokens, before a closer, inside a string, and after
   a leading mark plus the record.

Verified by the orchestrator: kondo clean; JVM `yin.repl.dht-head-test`, `fs-test`,
`store-write-audit-test`, `query-test` 50 tests / 697 assertions; the engineer reports
`bb test:clj` 3394 tests / 233191 assertions, a focused Node run of the head test (18 tests,
190 assertions) and a focused Dart run (18 tests) passing. The full three-lane `bb test`
runs now.

Attack: is the scan really linear on JVM, Node and Dart (any hidden O(n^2) in the loop, in
the refusal scan for remaining marks, or in how the text is bound)? Does the 3 s deadline
test flake on a loaded machine or pass vacuously? Any way a remaining U+FEFF passes (a BOM
produced by decoding a different byte sequence; U+FEFF as part of a surrogate pair;
U+FFFE)? Is the refusal order unchanged relative to the other `heads.edn` refusals? Any new
P0 to P2.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a1103a-2d0e-7f82-b9a3-8f50d00edd21

Findings as `P0-P3 | file:line | evidence | concrete fix`, or "No actionable findings". End
with one line: ready to commit once the Node and Dart lanes pass, or the blockers.

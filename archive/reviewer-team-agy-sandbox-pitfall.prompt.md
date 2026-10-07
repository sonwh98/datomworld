Created-GMT: 2026-09-04 10:54:12 GMT
Created-Local: 2026-09-04 17:54:12 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: c910c09f-b9b9-40b2-9fd8-2f3c956ad2c5

# Task: Review the AGY sandbox pitfall added to TEAM.md

Role: Routine Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-04 17:54:12 Asia/Ho_Chi_Minh | Status: active | Rationale: Deliberately NOT routed to gemini/AGY. The claim under review is about AGY's own limits, and the AGY reviewer previously asserted the opposite and was wrong. A different family is the only way to get independent scrutiny here. GLM is eligible: the change is Claude-authored, and it is off GLM peak hours.

Review an uncommitted addition to `docs/agents/team/TEAM.md` in
/Users/sto/workspace/datomworld. Run `git diff docs/agents/team/TEAM.md`. It adds
8 lines to the invocation-reference pitfalls paragraph (the last paragraph).

BACKGROUND. A `gemini-3.1-pro` run performed poorly in the Orchestrator seat.
The hypothesis was that its harness, not the model, was the cause. An earlier
AGY review of this repo asserted that AGY in `--mode plan` "retains full shell
access, read permissions, and the ability to execute test suites; it simply
pauses for user approval before modifying files." That assertion was tested and
is false in two respects.

EVIDENCE. A fresh AGY session (conversation
0466149a-ac29-4d74-80af-152339be60b7, deliberately not resumed so no value could
be parroted) was run under `--mode plan --sandbox` — the exact flags TEAM.md
prescribes for review — and asked to attempt four probes and report literal
output. The orchestrator established ground truth BEFORE the run and verified
every result afterwards:

- read a file: EXECUTED; returned `common.cljc` line 19 verbatim.
- read-only shell (`shasum`): EXECUTED; returned
  `a584b73d44a6b00a13a53ef535d828371484b941`, matching the orchestrator's own
  shasum exactly. This value is unguessable, so it proves real execution.
- run a test suite (`clojure -M:test -n yin.repl.host.jvm-test`): BLOCKED,
  with `/Users/sto/.local/share/mise/installs/java/21.0.2/bin/java: Operation
  not permitted`. The orchestrator ran the same suite successfully (3 tests,
  24 assertions).
- write a file outside the repo: EXECUTED with no approval step; the
  orchestrator confirmed the file on disk, 26 bytes, exact content.

The full probe and verification table is in
`collab/harness-probe-agy-plan-mode.gemini-3.1-pro-high.findings.md`.

Assess the ADDED TEXT ONLY:
1. Is every claim in it supported by the evidence above? Flag anything stated
   more strongly or more generally than the probe actually establishes — in
   particular whether one probe on one machine justifies the unqualified "a
   sandboxed AGY also cannot execute the JVM".
2. Is the prescription ("give it static analysis; never a deliverable that
   depends on running tests, and never the Orchestrator seat") the right
   conclusion, or too broad?
3. Redundancy: the same paragraph already covers Codex/Claude `Operation not
   permitted` as host diagnostics with a host-escalation remedy. Does the new
   text conflict with or duplicate that? Note the existing remedy is for the
   orchestrator rerunning a command itself, whereas a delegated headless agent
   cannot self-escalate — confirm that distinction is clear in the text.
4. Placement and wording within the paragraph's dense style.

Do not edit files. No tests are involved; do not run any.

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
Coding-Agent: glm
Session-ID: c910c09f-b9b9-40b2-9fd8-2f3c956ad2c5

Then a severity-ranked table (severity | file:line | evidence | correction),
then a final line reading exactly `SIGN-OFF: GRANTED` or `SIGN-OFF: WITHHELD`.

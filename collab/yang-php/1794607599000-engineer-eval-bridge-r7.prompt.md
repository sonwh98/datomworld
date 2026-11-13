Created-GMT: 2026-11-14 (see dispatch time)
Created-Local: staged 2026-11-13 ~23:5x +0700
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 7 — implement the eval-unit emitter + runtime import/hook bindings. Use ABSOLUTE PATHS: worktree /Users/sto/workspace/datomworld-evalb.

Role: Engineer. Branch eval-bridge. SPECS: the astra decision table, design of record
(collab/orch-lane/1794400000000-architect-eval-bridge-design.gpt-6.1-sol.stdout.log, committed on
this branch), part 6 findings (collab/yang-php/1794607460000-engineer-eval-bridge-r6.gpt-6.1-terra.findings.md
— its Remaining list is your scope, verbatim): eval-unit emitter + runtime import/hook bindings;
compile request/response transport and artifact publication; installed manifest-pinned code/entry;
php/eval-run in caller task; both expression paths; atomic redeclaration through guest fatal;
caller scope and budget integration. Plus the configured eval compiler service in the e2e harness
(r6: "the current e2e harness has no configured eval compiler service, so simply changing the
refusal would not constitute a working bridge").

## Working mode
Edit in /Users/sto/workspace/datomworld-evalb (branch eval-bridge). Red-first per fixture. Tests
sequential, explicit timeouts (600s for lower-portable). Commit + PUSH after each green unit —
your origin access works from this worktree (verified for prior sessions). NO docker. Fixtures
:oracle :pending. Design gap -> stop + record. Findings:
collab/yang-php/${TS}-engineer-eval-bridge-r7.gpt-6.1-terra.findings.md.

Created-GMT: 2026-11-13 23:5x GMT
Created-Local: 2026-11-14 06:5x +0700
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge r12 — php/eval-run in the caller task + both expression paths + atomic redeclaration

Role: Engineer. Branch eval-bridge @ the r11c tip (origin/eval-bridge, freshly pulled; the stream service + resumption semantics are landed). READ FIRST: part 6 findings (collab/yang-php/1794607460000-engineer-eval-bridge-r6.gpt-6.1-terra.findings.md — its Remaining list), the design of record (committed collab/orch-lane/1794400000000-...stdout.log §1/§4), the astra ruling, the lifecycle oracle record (collab/orch-lane/p7-lifecycle-probes.txt). NOTE: this sandbox has a READ-ONLY .git — edit files, leave dirty, NO commit/push; the orchestrator recovers and commits (established pattern). The r11 brief is absent here; the r11c WIP commit + service/transport tests carry the context.

## Implement
1. php/eval-run in the caller task: the caller's runtime supplies the stream-effect request; the installed entry executes IN THE CALLER TASK against caller cells/scope/budget; the outcome correlates back through the stream; fall-through maps to :php/null.
2. Both expression paths: replace prefix-refusals + the plain-call eval refusal with the supported bridge expression (design §1's substitution).
3. Atomic redeclaration: the collision fatal through the guest compile-fatal terminal path (lifecycle record: shutdown runs, dtors suppressed, 255, eval-unit atomic) — via the service path this time (request -> coordinator -> collision -> terminal), not the local prelude path.
4. Red-first fixtures: eval in caller task (caller cells visible, run budget consumed, output emitted), both expression paths end-to-end through the service, redeclaration fatal shape.

## Gates
All eval suites stay green (service/transport/admission/coordinator/emitter/publication/scope-cleanup/retention); new fixtures green; kondo/cljstyle clean on touched files; sequential + explicit timeouts. Findings: collab/yang-php/${TS}-engineer-eval-bridge-r12.gpt-6.1-terra.findings.md.

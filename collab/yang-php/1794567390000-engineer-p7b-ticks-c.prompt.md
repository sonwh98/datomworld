Created-GMT: 2026-11-13 10:56:33 GMT
Created-Local: 2026-11-13 17:56:33 +0700 (+0700)
Coding-Agent: codex
Session-ID: pending (fresh thread; part B's thread ended with B unimplemented — that is YOUR scope)

# Task: P7-b part C — IMPLEMENT the PHP ticks integration (turn the staged red fixtures green)

Role: Engineer. Branch p7b-ticks @ 411c2b08 (pushed). Worktree is yours; ANTLR already generated.

## Read first
1. Your scope's spec: the original brief B item + slice-3 design (both in-repo; cited below).
2. Part B's findings (collab/yang-php/1794566777000-engineer-p7b-ticks-b.gpt-6.1-terra.findings.md):
   staged-red fixtures (programs_p7b.cljc, ticks_test.clj), the oracle p7 group (D), and the
   environment/gate lessons — CRITICAL: its concurrent 120s lane runs all timed out; run
   SEQUENTIALLY with generous timeouts (300-600s each, the prelude compile chain is minutes).
3. Design authority: docs/design/yang.php.md §12 + §15 P7-b row + collab/orch-lane/1794700000000-architect-safepoint-slice3-design.gpt-6.1-sol.findings.md (the :after profile shape is ALREADY implemented in the shared core at src/cljc/yang/safepoint.cljc:153 — part A; do not re-litigate it).

## Implement (B from the original brief)
- declare(ticks=N) pragma: lowering recognizes the directive, marks statement sites under the ticks profile using the NEW :after capability {:line {:hook php.sp/line :operands true :after true}} (site selection per the design; remember derivation validates invalid combinations).
- register_tick_function / unregister_tick_function builtins (registry + the tick firing rule: every N marked statements on NORMAL completion; a count is per declare-site, PHP semantics; generator resume fires the pending after-hook exactly when its statement completes — the design's table).
- include site-set handoff: canonical published rows carry no marks; PHP receives the unit explicitly (design's landing requirement). The include red currently TIMES OUT — if your implementation doesn't fix the timeout in a reasonable budget, mark it in findings with the exact command + durations rather than leaving it hanging; the orchestrator reruns it remote if needed.

## Gates (all sequential, generous timeouts)
- ticks_test + yang.php tick/lowering/prelude focused namespaces green; yang.safepoint-test 9/20 stays green; python trace-basic + linked-tracing-profile green (additivity); affected e2e fast set; kondo 0/0 + cljstyle clean on touched files. Fixtures stay :oracle :pending — do NOT run docker.

## Rules
- Conventional commits on p7b-ticks; push as you go. No merges. Design gap -> stop that item + report.
- Findings: collab/yang-php/1794567390000-engineer-p7b-ticks-c.gpt-6.1-terra.findings.md (per-fixture red->green chronology, file:line, gate table with sequential timings, include-handoff status).

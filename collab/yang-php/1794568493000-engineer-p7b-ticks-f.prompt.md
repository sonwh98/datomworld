Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part F — EXECUTE the existing edit map. Zero re-analysis budget; edits + the 37 s loop.

Role: Engineer. Everything is decided; the plan EXISTS — do not re-derive it:
1. THE EDIT MAP (part E findings, collab/yang-php/1794568408000-engineer-p7b-ticks-e.gpt-6.1-terra.findings.md): the exact seams — prelude.cljc:5796/:6078 (request counter + increment/reset/empty-registry + dispatch), lower.cljc:2294/:3554/:3686 (lexical N, zero, block-vs-semicolon syntax, site selection, adjacent suppression, excluded categories), safepoint.cljc:153 shared wrapper (functions/closures body sites, generator pending-hook, nonlocal exit bypass), php/antlr/safepoint.cljc:13 (profile separation from budget), registry + per-entry reentry builtins, AND the integration seam: e2e_harness.clj:163 executes the lowered AST directly while run-vms:104 derives only the budget profile — derive the TICK profile into the executed image with access to the PHP request state.
2. THE SEMANTICS (binding, every row): collab/yang-php/1794567570000-architect-ticks-counter-ruling.gpt-6-astra.findings.md decision table. Counter: request-owned, `add(count,1)`; `count >= site-N` → reset-zero BEFORE dispatch; integer/add/sub arithmetic (exception-track rule); never snapshot across generator context; empty registry still counts.

## Working mode (mandatory)
EDIT FIRST per the map, then loop: `clojure -M:test -n yang.php.antlr.ticks-test -v yang.php.antlr.ticks-test/tick-fixtures-test` (37 s, sequential). Commit + PUSH after EVERY fixture goes green (message: feat(php): tick <name> green — <what>), so partial progress survives sessions. Do not run the include test. Do NOT re-read the full slice-3 design; the map + table suffice.

## Definition of done (this session)
tick-fixtures-test: 8/8 green (or push the maximum reached with exact remaining diagnosis). Additivity: yang.safepoint-test 9/20 green, python trace-basic + linked-tracing-profile green. kondo 0/0 + cljstyle clean on touched files. Fixtures stay :oracle :pending. Findings: collab/yang-php/${TS}-engineer-p7b-ticks-f.gpt-6.1-terra.findings.md (green chronology, per-seam file:line, remaining gaps).

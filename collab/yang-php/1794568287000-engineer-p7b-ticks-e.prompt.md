Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part E — IMPLEMENT ONLY. Turn the 8 red tick fixtures green. Nothing else.

Role: Engineer. Branch p7b-ticks (pushed). Fixtures, ruling, profile, shared :after all EXIST:
- THE SPEC: collab/yang-php/1794567570000-architect-ticks-counter-ruling.gpt-6-astra.findings.md — decision table is binding, every row.
- Red fixtures (8): test/yang/php/antlr/programs_p7b.cljc (:10-:27) run via yang.php.antlr.ticks-test/tick-fixtures-test — ~6 s per run, iterate freely.
- Existing scaffolding: src/cljc/yang/php/antlr/safepoint.cljc (ticks-profile), shared core src/cljc/yang/safepoint.cljc:153 (:after wrapper), lowering hooks at lower.cljc (see 411c2b08 profile decl).

## Implement (the whole of this session's scope)
1. Request-owned tick counter in existing guest execution state (cell in the request context; no host global).
2. php.sp/line: on selected statement-normal-completion, `count = add(count,1)`; if `count >= site-N`: reset to zero BEFORE dispatching registered tick callbacks (integer/add per the exception-track's arithmetic rule); callbacks dispatched with reentry guard per the table.
3. Lowering: declare(ticks=N) block + semicolon forms, site selection with Zend's excluded categories + adjacent suppression, per-site threshold operands, empty-registry counting.
4. register_tick_function / unregister_tick_function builtins + registry semantics per the table.
5. Generator: pending after-hook on eventual normal completion with then-current counter; never snapshot counts.

## Definition of done
All 8 fixtures green in one sequential run of tick-fixtures-test; yang.safepoint-test 9/20 stays green; python trace-basic + linked-tracing-profile green; kondo 0/0 + cljstyle clean on touched files. Include-handoff fixture STAYS deferred (part F). Fixtures remain :oracle :pending.

## Rules
Conventional commits, push as you go; if the table conflicts with what you find, STOP that item and record. Findings: collab/yang-php/${TS}-engineer-p7b-ticks-e.gpt-6.1-terra.findings.md (file:line per table row; green chronology; gate timings).

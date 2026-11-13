Created-GMT: 2026-11-13 10:46:17 GMT
Created-Local: 2026-11-13 17:46:17 +0700 (+0700)
Coding-Agent: codex
Session-ID: pending (fresh thread on the laptop; the jupiter predecessor ran in a read-only-.git sandbox and stopped at its ceiling — its part A landed as 6ed8f768 with its findings)

# Task: P7-b part B — PHP ticks integration (parts A done; C/D/E remain)

Role: Engineer (implementation, yang.php)

## State
- Branch p7b-ticks @ 6ed8f768 (pushed). Part A (shared core :after extension in yang.safepoint) is COMMITTED and verified (shared safepoint-test 9/20 green, kondo clean). READ the predecessor's findings first: collab/yang-php/1794565785000-engineer-p7b-ticks.gpt-6.1-terra.findings.md — it documents exactly what A did and what it could not.
- ANTLR parsers are PRE-BUILT in this worktree (bb gen:antlr ran; the generation needs no network from you).
- Environment note: you are on the laptop with a working .git. Commit and push as you go; if a tool is blocked by your sandbox, say so in findings rather than stopping silently (that is what happened jupiter-side).

## The remaining scope (from the original brief — collab/yang-php/1794565785000-engineer-p7b-ticks.prompt.md — read it; the slice-3 design 1794700000000 remains the spec)
B. PHP integration: declare(ticks=N) pragma in the lowering (statement-site marking under a ticks profile, using the NEW :after capability from part A), register_tick_function/unregister_tick_function in the prelude/builtins, the php.sp/line hook with PHP's tickable-statement semantics (count parity per the design's after-normal-completion rule; the generator-resume pending-after-hook case), and the include site-set handoff (canonical published rows carry no marks; PHP receives the unit explicitly) per the design's landing requirement.
C. Fixtures TDD: test/yang/php/antlr/programs_p7b.cljc — hand-derived expectations with :oracle :pending covering: simple statement sequence, loop body statements, function-call statement, generator (resume-pending tick), include handoff. Red-first each.
D. Oracle p7 group: extend src/dev/yang/php/oracle.clj with a run-p7 group + prologue modeled on the p5/p5b2 pattern. DO NOT run docker (the shiva pass is the orchestrator's at gate time) — fixtures stay :pending.
E. Gates (laptop-legal, foreground, explicit timeouts): focused namespaces — yang.php tick/lowering/prelude + yang.safepoint (both sides) + affected e2e fast set; kondo 0/0 + cljstyle clean on touched files. Python's before-hook pins must stay green (additivity regression line).

## Rules
- Design gaps: STOP that item, record it, do not invent semantics.
- Commits: conventional, on p7b-ticks, push at first commit and keep pushing. No merges.
- Findings: collab/yang-php/1794566777000-engineer-p7b-ticks-b.gpt-6.1-terra.findings.md (per-item B/C/D/E status, file:line, red-first chronology per fixture, gap list, environment notes).

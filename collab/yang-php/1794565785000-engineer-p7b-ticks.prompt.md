Created-GMT: 2026-11-13 03:2x GMT
Created-Local: 2026-11-13 10:2x +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (codex thread_id; executed as a remote agent on jupiter)

# Task: P7-b — statement ticks (declare(ticks), register_tick_function) on the shared :line mechanism

Role: Engineer (implementation, yang.php + shared safepoint core)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 | Status: active | Rationale: moderate-complexity codex tier fits a design-complete integration round; the round executes as a remote agent on jupiter so implementation and lane runs live in one loop

## Read first (authority chain, in order)
1. docs/design/yang.php.md — §12 (ticks semantics), §15 rows P7-a/P7-b (the split rationale), §4.7 (oracle gates before landing)
2. collab/orch-lane/1794700000000-architect-safepoint-slice3-design.gpt-6.1-sol.findings.md — **THE SPEC**. Everything it proposes is settled: the profile shape {:line {:hook '<ns>/line :operands true :after true}}, the value-preserving after-hook lowering, the semantics (runs ONCE on normal completion; a pending after-hook runs when its statement completes after a generator resume; an escape bypassing completion skips it; it is NOT a finally hook), derivation validation of invalid combinations, default stays hook-before, Python keeps its before-hook behavior, the include site-set handoff (canonical published rows carry no marks; PHP receives the unit explicitly), the stripping rule, and the consumer cost table.
3. docs/design/yang.php.md §11 (include/require/declare rows) and the P7-b acceptance bar: "tick counts equal PHP's for the statement fixture".

## The work
A. **Shared core (src/cljc/yang/safepoint.cljc)** — implement the `:after` timing extension exactly as the slice-3 design specifies. Additive only: Python's existing before-hook tracing behavior and pins must not change.
B. **PHP integration** — `declare(ticks=N)` pragma handling in the lowering (statement site marking under a ticks profile), `register_tick_function`/`unregister_tick_function` in the prelude/builtins, the `php.sp/line` tick hook counting per PHP's tickable-statement rules, and the include site-set handoff per the design's landing requirement.
C. **Fixtures (TDD)** — new `test/yang/php/antlr/programs_p7b.cljc` tick fixtures: hand-derived expectations marked `:oracle :pending` FIRST (red), covering at minimum: a simple statement sequence, a loop, a function call statement, a generator (the resume-pending-after-hook case), and the include handoff.
D. **Oracle p7 group** — extend src/dev/yang/php/oracle.clj with a run-p7-style group + prologue for the tick fixtures, modeled exactly on the existing p5/p5b2 pattern (the orchestrator runs the shiva php:8.3 docker pass at gate time and records the folds — you write the machinery and the pending fixtures; do NOT run docker yourself).
E. Gates you run (you are on jupiter with the full toolchain): focused namespaces with explicit foreground timeouts — yang.php tick/lowering/prelude namespaces, the shared safepoint namespaces BOTH sides (PHP after-hook + Python before-hook pins green proving additivity), affected e2e fast; clj-kondo 0/0 and cljstyle clean on touched files. Suite-scale/full-lane runs: NOT yours — the orchestrator's gates.

## Hard rules
- The design is the spec: if you find a real GAP (a shape the design leaves open), STOP that item and report it in findings rather than inventing semantics.
- Python's before-hook behavior is a regression line: any change there stops the round.
- One writer: branch p7b-ticks, worktree /home/sto/ci/datomworld-p7b-j on jupiter. Commit early and PUSH the branch at first commit (the steward rule: unpushed branches cannot be tested remotely). Do not merge anything.
- conventional commit messages (docs/agents/format.md); findings to collab/yang-php/<dispatch-ts>-engineer-p7b-ticks.gpt-6.1-terra.findings.md (header fields; per-item A-E status with file:line; red-first chronology for each fixture; the gap list, empty or not).

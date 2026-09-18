---
description: Yin.VM & Continuation Runtime Engineer role definition for datom.world
---

# ROLE: Yin.VM Runtime Engineer

## Domain Scope

- CESK semantics, evaluator implementations, and execution parity
- Serializable continuations, suspension, transport, and resumption
- Instruction dispatch, frames, environments, stores, and continuations
- Runtime and compile-time macro boundaries
- Effect scheduling, stream integration, REPL execution, and telemetry

This role owns no permanent file list. Each task defines the artifacts it may
inspect or change and any permitted expansion.

## Core Responsibilities

1. **CESK Machine Integrity**: Maintain formal correspondence between Control, Environment, Store, and Kontinuation states.
2. **Continuation Portability**: Ensure computations serialize as portable datoms that can pause, travel across DaoStream boundaries, and resume seamlessly.
3. **Execution Parity**: Guarantee that semantic interpreter, AST walker, register VM, and stack VM produce identical output across identical inputs.
4. **Performance & Benchmarking**: Optimize VM dispatch loops and register frames without violating functional purity.

## Implementation Prompt Template

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

# Task: <Task Name>

Role: Yin.VM Runtime Engineer

Implementers:
- Model: <model-name> | Assigned: <local timestamp> | Status: active | Rationale: <why>

Implement <task> in <repository-root>. Read <governing-design-file>,
<source-files>, and <test-files> first. Acceptance criteria:
- <criterion-1>
- <criterion-2>
- <criterion-3>

Work only in named files. If a required dependency demands expansion, stop and
request authorization before editing it. Preserve unrelated changes, do not
weaken tests, and preserve CESK and execution-parity invariants. Run focused
tests and lint, and inspect the diff.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any
incomplete work. Do not claim edits or tests that did not occur.
```

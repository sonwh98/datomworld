Created-GMT: 2026-10-30 14:45:00 GMT
Created-Local: 2026-10-30 21:45:00 +0700 (Indochina Time)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang.python and yang.clojure — the yang.call S1 frontend wiring (Python) and the Clojure adoption
Role: Orchestrator dispatch (two parallel lanes, one per language)
The yang.call S0 core is landed on master (64f2885a — merge: yang-call). The yang.python and yang.clojure orchestrators can begin their integration.

## The two dispatches

1. **yang.python S1 wiring** — the Python prelude's callable constructors gain :yang.call/entry, the source-resolution call sites route through the convention, the named/reference handling routes through the convention where applicable, and the bundled/linked emission carries the yang.call helpers. Worktree per the python orchestrator's own. The spec: yang.antlr.md section 8.10; the S0 plan: collab/1793292371000-architect-call-runtime-plan.findings.md (the API surface, the semantics, the dependency graph).
2. **yang.clojure adoption** — the Clojure frontend consumes the shared runtime (consumer of the same shared runtime, not a second implementation). The Clojure orchestrator owns the adoption and its source-semantic compatibility work.

## The dependency handoff artifact

The yang.call API surface and dependency addresses publish when both S1 rounds land. The handoff artifact is the signal the yang.python and yang.clojure orchestrators need.

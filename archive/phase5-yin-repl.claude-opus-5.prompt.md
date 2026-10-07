Created-GMT: 2026-09-03 21:53:52 GMT
Created-Local: 2026-09-04 04:53:52 ICT
Coding-Agent: claude
Session-ID: none (new implementation)

# Task: Phase 5 Yin REPL v2 core, driver, and host entry points

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-04 04:53:52 ICT | Status: active | Rationale: high-credit Yin/runtime implementation lane

Implement the next bounded phase in /Users/sto/workspace/datomworld using the
already committed v2 stream/apply/rpc layers and the v2 adapter. Read first:
- docs/design/yin.repl.implementation-plan.md
- docs/design/yin.vm.implementation-plan.md
- src/cljc/yin/repl_adapter.cljc
- src/cljc/dao/stream/apply.cljc
- src/cljc/dao/stream/rpc.cljc
- src/cljc/yin/repl.cljc (behavioral reference only; do not edit)
- existing Yin REPL tests and build aliases

Add the smallest implementation-ready v2 REPL slice in new files only:
- src/cljc/yin/repl/core.cljc
- src/cljc/yin/repl/driver.cljc
- src/cljc/yin/repl.cljc
- src/clj/yin/repl/runner.clj
- focused tests under test/yin/repl/

The core/driver must own explicit immutable state and step transitions, use
v2 RPC/adapter surfaces, keep local commands local, forward ordinary source,
publish completions exactly once, and never use promises/futures/callbacks,
namespace globals, hidden cursors, or v1 stream protocols. Implement only the
ast-walker/evaluation boundary explicitly supported by the plan; do not invent
the full v2 VM if it is not present. If build aliases or Shadow configuration
are required for a compiling entry point, add only the plan’s additive entries
and corresponding focused smoke tests; otherwise report the exact prerequisite
instead of modifying unrelated configuration.

Use TDD. Do not edit legacy yin.repl, dao.stream v1, docs, collab, or unrelated
files. Do not stage/commit. Run focused JVM tests, scoped lint, and any
available CLJS/CLJD compile for the new namespaces; avoid full-suite reruns.

Final response must begin with actual Completed-GMT/Completed-Local,
Coding-Agent: claude, and Session-ID, then files, tests, unresolved
prerequisites, and phase readiness.

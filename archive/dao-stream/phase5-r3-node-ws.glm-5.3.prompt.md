Created-GMT: 2026-09-03 22:12:03 GMT
Created-Local: 2026-09-04 05:12:03 ICT
Coding-Agent: glm
Session-ID: none (new implementation)

# Task: Phase 5 R3 Node WebSocket host adapter

Role: Stream & Network Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-04 05:12:03 ICT | Status: active | Rationale: high-credit transport lane

Implement the next bounded phase in /Users/sto/workspace/datomworld: a Node
host adapter that connects the existing `dao.stream.ws` callback boundary
to the existing `dao.stream.rpc.ws` decoder without changing v1 APIs.

Read first:
- docs/design/dao.stream.ws.md
- docs/design/yin.repl.implementation-plan.md (R3/R4)
- src/cljc/dao/stream/ws.cljc
- src/cljc/dao/stream/rpc/ws.cljc
- src/cljc/dao/stream/rpc.cljc
- existing v1 Node WebSocket host implementation for behavioral reference only

Add only new host-specific files/tests needed for a synchronous step-driven
Node client/server boundary (likely under src/cljs or src/cljc host branches
and test/dao/stream). The adapter must use `ws`/Node APIs only at the host
edge, keep all application state explicit, mint the deposit cursor before
attach, preserve attachment demultiplexing, and expose no Promise/Future to the
v2 RPC core. Do not hide readers, cursors, registries, or retry loops in the
adapter. If a real Node package is unavailable, implement a deterministic
loopback host seam and an end-to-end test with injected socket primitives,
reporting the package prerequisite rather than faking network success.

Use TDD. Do not edit legacy v1 namespaces, Yin REPL files, docs, deps/shadow
configuration, collab, or unrelated files. Do not stage/commit. Run focused
JVM/CLJS tests and lint; compile CLJS Node output if applicable.

Final response must begin with actual Completed-GMT/Completed-Local,
Coding-Agent: glm, and Session-ID, then files, tests, limitations, and phase
readiness.

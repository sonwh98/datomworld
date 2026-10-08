Created-GMT: 2026-09-03 21:53:52 GMT
Created-Local: 2026-09-04 04:53:52 ICT
Coding-Agent: glm
Session-ID: none (new implementation)

# Task: Phase 5 v2 WebSocket RPC decoder

Role: Stream & Network Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-04 04:53:52 ICT | Status: active | Rationale: high-credit stream/network implementation lane

Implement the next bounded phase in /Users/sto/workspace/datomworld:
`dao.stream.rpc.ws` in `src/cljc/dao/stream/rpc/ws.cljc`, with focused
tests in `test/dao/stream/rpc_ws_test.cljc`.

Read first:
- docs/design/datom.world.md
- docs/design/dao.stream.ws.md
- docs/design/yin.repl.implementation-plan.md
- src/cljc/dao/stream/ws.cljc
- src/cljc/dao/stream/apply.cljc
- src/cljc/dao/stream/rpc.cljc
- their existing tests

The decoder is the only layer allowed to know `:ws/*` events. It must map
opened/accepted/disclaim/error/closed and value frames into the lifecycle and
response events consumed by `dao.stream.rpc`, preserve unknown events as
diagnostics, reject malformed frames as data, and never introduce sockets,
callbacks, promises, futures, loops, globals, or hidden cursors. Keep all state
explicit and host-neutral. Follow CLJD reader-conditional ordering.

Use TDD and add exhaustive focused tests for lifecycle mapping, value/apply
response decoding, malformed and unknown frames, close/reason classification,
and id/attachment preservation. Do not edit existing namespaces, docs,
deps.edn, shadow-cljs.edn, collab, or unrelated files. Do not stage/commit.
Run only the focused JVM test and scoped lint; targeted CLJD compile if needed.

Final response must begin with actual Completed-GMT/Completed-Local,
Coding-Agent: glm, and Session-ID, then files, tests, and remaining risks.

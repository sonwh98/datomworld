Created-GMT: 2026-09-03 22:12:03 GMT
Created-Local: 2026-09-04 05:12:03 ICT
Coding-Agent: claude
Session-ID: none (new implementation)

# Task: Phase 5 R3/R4 Yin REPL connect and serve wiring

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-04 05:12:03 ICT | Status: active | Rationale: high-credit Yin/runtime lane

Implement the next bounded phase in /Users/sto/workspace/datomworld using the
committed `yin.repl` core/driver and v2 stream/apply/rpc/ws layers. Read:
- docs/design/yin.repl.implementation-plan.md (R3/R4/R5)
- docs/design/dao.stream.ws.md
- src/cljc/yin/repl.cljc
- src/cljc/yin/repl/core.cljc
- src/cljc/yin/repl/driver.cljc
- src/cljc/dao/stream/ws.cljc
- src/cljc/dao/stream/rpc/ws.cljc
- src/cljc/dao/stream/serving.cljc

Wire `connect` and `serve!` at the v2 REPL boundary as far as the available
host adapter permits. Keep local state explicit and step-driven: canonical URL
path normalization, one capacity-bounded deposit medium reused across
reconnects, cursor minted before attach, `attach!` + rpc-ws client construction,
neutral lifecycle observation, disconnect/reattach via close/rebind, and a
server composition that owns endpoint/service/lifecycle state. Do not put
Promises/Futures, hidden cursors, namespace globals, or v1 protocols into the
core/driver. If actual host sockets are not yet available, make the seam
explicit and add deterministic injected-host tests rather than claiming a real
network path.

Add focused tests for connect URL normalization, lifecycle transitions,
disconnect/reattach state preservation, server start/stop data ownership, and
one request/response round trip through injected host functions. Preserve all
legacy files. Add only additive build configuration if required for the v2
entry points; do not edit docs or collab and do not stage/commit.

Run focused JVM/CLJS tests, scoped lint, and relevant Node compilation. Report
exactly which R3/R4/R5 acceptance facts are proven versus still blocked by a
host package or missing yin.vm.

Final response must begin with actual Completed-GMT/Completed-Local,
Coding-Agent: claude, and Session-ID, followed by files, tests, and readiness.

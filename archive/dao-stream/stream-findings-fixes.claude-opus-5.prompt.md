Created-GMT: 2026-09-03 21:14:49 GMT
Created-Local: 2026-09-04 04:14:49 ICT
Coding-Agent: claude
Session-ID: none (new implementation task)

# Task: Fix GLM architect findings in staged DaoStream v2 implementation

Role: Stream & Network Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-04 04:14:49 ICT | Status: active | Rationale: stream/network implementation owner; independent of GLM reviewer

Work in /Users/sto/workspace/datomworld. The user authorizes implementation
of the following GLM findings. Read the complete report first:
- /Users/sto/workspace/datomworld/collab/architect-staged-v2-implementation-review.glm-5.3.findings.md

Read governing docs and source before editing:
- docs/design/datom.world.md
- docs/design/dao.stream.md
- docs/design/dao.stream.ws.md
- docs/design/dao.stream.implementation-plan.md
- src/cljc/dao/stream/ringbuffer.cljc
- src/cljc/dao/stream/forward.cljc
- src/cljc/dao/stream/ws.cljc
- src/cljc/dao/stream/rpc.cljc
- src/cljc/yin/repl_adapter.cljc
- relevant tests under test/dao/stream and test/yin/repl

Authorized edits are limited to these implementation/test files:
- src/cljc/dao/stream/ringbuffer.cljc
- src/cljc/dao/stream/forward.cljc
- src/cljc/dao/stream/ws.cljc
- src/cljc/dao/stream/rpc.cljc
- src/cljc/yin/repl_adapter.cljc
- test/dao/stream/ringbuffer_test.cljc
- test/dao/stream/forward_test.cljc
- test/dao/stream/ws_test.cljc
- test/dao/stream/rpc_test.cljc
- test/yin/repl_adapter_test.cljc

Required fixes:
1. Make ringbuffer frozen-attachment gap recovery advance to the earliest
   retained position (or tail when empty), never the already-evicted frozen
   position.
2. Make forward-step total under repeated gap recovery; gap-resume must be
   bounded by the batch budget and must not spin forever.
3. Release pending WebSocket handoff slots on pre-accept peer close and other
   pending terminal paths, while preserving the existing bounded admission
   semantics. Add focused regression tests.
4. Preserve an explicit `:ws/value nil` key for nil payload events.
5. Reorder mixed reader conditionals to `:cljd`-first wherever the staged v2
   files currently violate the documented CLJD rule, without changing host
   behavior.
6. Add bounded concurrent JVM linearizability histories for the required
   append/append, append/next, append/close, cursor/close, and independent
   reader cases, plus a seeded invalid history proving the oracle can reject.
7. If cheap and local, add a bounded diagnostics publication/clearing helper
   in rpc and make the adapter’s telemetry/read-eval behavior match the v2
   plan; otherwise report these as explicitly deferred rather than broadening
   scope.

Use TDD: add or adjust focused failing tests before implementation where
practical. Do not edit docs, deps.edn, shadow-cljs.edn, collab history, or
unrelated files. Do not stage or commit. The focused suites already pass before
your changes; run only the relevant tests and lint after editing, plus a
targeted CLJD compile if reader-conditional code changes.

Begin the final report exactly with:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and timezone>
Coding-Agent: claude
Session-ID: <exact id or none>

Then list files changed, each GLM finding resolved/deferred, focused test/lint
results, and remaining risks.

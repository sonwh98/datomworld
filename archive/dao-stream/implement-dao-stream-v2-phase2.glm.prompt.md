Created-GMT: 2026-09-03 10:15:00 GMT
Created-Local: 2026-09-03 17:15 Asia/Ho_Chi_Minh
Coding-Agent: glm-5.3
Session-ID: phase2-glm-20260903-r2

You are the Phase 2 DaoStream v2 implementer. Work exclusively in /private/tmp/datom-phase2.
Read AGENTS.md, docs/design/datom.world.md, docs/design/dao.stream.md, and
docs/design/dao.stream.implementation-plan.md before editing. Phase 1's reviewed
API is already present in src/cljc/dao/stream.cljc plus its conformance harness/tests.

Implement Phase 2 completely: a pure .cljc bounded ring-buffer transport under
src/cljc/dao/stream/ringbuffer.cljc, with focused tests under test/dao/stream/.
Honor the v2 protocols and outcome maps, portable descriptors carrying logical identity,
reader cursors with cursor-mismatch/gap semantics, writer full/closed semantics, and
idempotent close. Provide host-composed create!/attach! functions; no global registries,
multimethods, callbacks, waiters, closed? or take!. Add a declaration manifest and run
the conformance harness against the transport where appropriate. Keep all values plain
data and preserve open outcome maps. Do not edit legacy files, registries, or unrelated
docs. Do not stage or commit. Run focused tests and lint; report exact commands/results.

At completion, print these headers with actual timestamps:
Completed-GMT:
Completed-Local:
Coding-Agent: glm-5.3
Session-ID: phase2-glm-20260903-r2
Files Changed:
Tests / Commands and Results:
Unresolved Risks:
Readiness for Integration:

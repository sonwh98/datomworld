Created-GMT: 2026-09-03 11:00:00 GMT
Created-Local: 2026-09-03 18:00 Asia/Ho_Chi_Minh
Review-Target: staged changes (DaoStream v2 implementation)

Review the currently staged changes in /Users/sto/workspace/datomworld. Read
AGENTS.md, docs/design/datom.world.md, docs/design/dao.stream.md,
docs/design/dao.stream.implementation-plan.md, and docs/design/dao.stream.ws.md.
Inspect only the staged implementation and tests, including:
src/cljc/dao/stream.cljc,
src/cljc/dao/stream/ringbuffer.cljc,
src/cljc/dao/stream/transit.cljc,
src/cljd/dao/stream/transit.cljd,
test/dao/stream/conformance.cljc,
test/dao/stream_test.cljc,
test/dao/stream/ringbuffer_test.cljc,
test/dao/stream/transit_test.cljc.

Perform an adversarial correctness, concurrency, API-contract, portability,
security, performance, and test-quality review. Compare behavior to the design
invariants, especially attachment freeze/close, cursor identity and gaps,
outcome exhaustiveness, descriptor identity gates, host dispatch, and cljd
codec independence from legacy code. Run focused tests or static checks where
useful, but do not edit, stage, or commit anything.

Report findings by severity P0/P1/P2/P3 with exact file and line references,
including concrete fixes. Clearly distinguish confirmed defects from risks or
design questions. End with APPROVE or REQUEST CHANGES and a brief test report.

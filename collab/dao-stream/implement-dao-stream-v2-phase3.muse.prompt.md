Created-GMT: 2026-09-03 10:25:00 GMT
Created-Local: 2026-09-03 17:25 Asia/Ho_Chi_Minh
Coding-Agent: muse-spark-1.3-contributor
Session-ID: phase3-muse-20260903-r2

You are the Phase 3 DaoStream v2 implementer. Work exclusively in /private/tmp/datom-phase3.
Read AGENTS.md, docs/design/datom.world.md, docs/design/dao.stream.md,
docs/design/dao.stream.implementation-plan.md, and docs/design/dao.stream.ws.md.
Phase 1's reviewed API is present in src/cljc/dao/stream.cljc.

Implement the codec portion of Phase 3: create src/cljc/dao/stream/transit.cljc
with host-specific Transit implementations for clj/cljs (Node) and cljd as prescribed,
one shared portable value codec, structural encode/decode round trips, and focused
tests under test/dao/stream/. Keep descriptor values plain data and preserve the
fixed :dao.stream/identity key gate. Do not edit legacy transit namespaces, ws code,
registries, or unrelated docs. Do not stage or commit. Add only tests that do not require
Phase 2's ringbuffer; leave its final descriptor→encode→decode→attach test clearly
identified for later integration. Run focused tests/lint available in this worktree and
report exact commands/results.

At completion, print actual timestamps under:
Completed-GMT:
Completed-Local:
Coding-Agent: muse-spark-1.3-contributor
Session-ID: phase3-muse-20260903-r2
Files Changed:
Tests / Commands and Results:
Unresolved Risks:
Readiness for Integration:

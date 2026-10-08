Completed-GMT: 2026-09-26 21:28:18 GMT
Completed-Local: 2026-09-27 04:28:18 Asia/Ho_Chi_Minh

1. **Decision reads:** Specified initial `:oldest` cursor acquisition and retry, every `next` outcome, one recovery read after `gap`, repeated-gap handling, and composition-serialized calls without middleware waits or locks. [dao.stream.middleware.md:107–147](docs/design/dao.stream.middleware.md)

2. **ShiBi fit:** Chose the bounded adapter contract: the index publishes an immutable tuple-space snapshot, and `verify` runs the query with the request as bindings. General request-specific decisions over streams are explicitly deferred, with the reasons and limits stated. [dao.shibi.md:23–63](docs/design/dao.shibi.md) [dao.stream.remote.md:580–589](docs/design/dao.stream.remote.md)

3. **Spec length:** Moved path fates and the amendment inventory into the implementation plan; the remote spec keeps a short unification and contract-boundary summary. Final `dao.stream.remote.md` length: **599 lines**. [dao.stream.remote.md:593–599](docs/design/dao.stream.remote.md) [implementation-plan.md:56–62, 178–219](docs/design/dao.stream.remote.implementation-plan.md)

4. **Historical content path:** Marked the old `default-handlers` / `connect-content!` instructions historical and pointed to planned `dao.jing.content`. [docs/dao.space.stigmergy.md:242–247](docs/dao.space.stigmergy.md)

5. **Pair-channel gap:** Specified that an `in` gap terminates that link; the link reports abandoned append effects as unknown, and reattachment uses the same pair descriptor with a fresh `:newest` cursor. [dao.stream.remote.md:400–410](docs/design/dao.stream.remote.md)

`git diff --check` passed; added lines checked for ASCII and an 80-column maximum. No tests were run.

Status: COMPLETE
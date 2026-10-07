Created-GMT: 2026-09-08 12:47:48 GMT
Created-Local: 2026-09-08 19:47:48 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the P2 corrections (r2)
Role: Architect review

All four documentation defects accepted and fixed. **Read-only workspace —
print your confirmation to stdout, write nothing.**

1. **Coordinate comment.** You were right: `open-published!` does check
   `:dao.stream/type` (query.cljc:236-238). Now reads: the keys belong to
   `dao.space.index`; `open-published!` validates that exact shape before
   opening anything; `q` dispatches on neither key.
2. **Datom view source list.** Confirmed against `db-source` — it accepts the
   snapshot *result* and takes its `:relation`. Both the numbered list and the
   bullet now say: a relation value, a `snapshot` result, or an opened
   published index.
3. **v1 residue.** "which bounded streams a reader names" → "which relation
   values and opened indexes"; "published index descriptor names a manifest" →
   "coordinate"; the generic positional-index item no longer requires an exact
   stream bound — "a relation value's tuples together with their explicitly
   requested positional indexes form an indexed snapshot".
4. **Shareability.** Rewritten: query values carry no reopenable transport
   identity — a result names nothing another runtime could resolve — but their
   *contents* are ordinary data and can travel through a stream that carries
   data. The categorical "not serializable" and "by definition not shared" are
   gone.

5. **`dao.stream.md` — I took your first option.** It is out of the P2 commit
   entirely. Both its changes (the "v2 namespace is transient" section, written
   earlier this session, and the consumer-list correction) will land in their
   own documentation commit, reviewed on its own terms. P2 is now scoped to
   `docs/design/dao.space.query.md` alone.

Review `git diff -- docs/design/dao.space.query.md`. Confirm each correction
closes its finding, flag anything they newly broke, and state whether P2 is
complete and the plan fully consumed.

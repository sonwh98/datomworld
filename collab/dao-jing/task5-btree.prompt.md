Created-GMT: 2026-09-18 14:15:00 GMT
Created-Local: 2026-09-18 21:15:00 +07
Coding-Agent: claude (opus-5)
Session-ID: 4c283fda-e2ef-4b8c-9116-b2e4b06f6959
# Task: dao.jing.remote B-Tree Consumer Side
Role: Implementer
Assigned: 2026-09-18 21:15:00 +07

You are the Implementer.
Your task is to finish the remaining `dao.jing.remote` work: wiring the B-Tree consumers to use the async stepped client.
**Instructions:**
1. Read `docs/design/dao.jing.md` and `docs/design/dao.data.btree.md`. Note that the `dao.jing.remote.step` async client was recently built.
2. Implement `hydrate-async` and `store-tree-async` in the B-Tree consumers so they use the new async client instead of the legacy blocking client.
3. Run the JVM and cross-platform tests to verify.
4. Output a clear Markdown report. Do not commit.

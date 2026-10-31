I’m applying the code-review checklist to the documentation-only correction. I’ll verify the two failure scopes against the committed transport lifetimes and scan the phase documents once more for contradictory promises; no tests or writes.

Completed-GMT: 2026-09-09 13:02:06 GMT  
Completed-Local: 2026-09-09 20:02:06 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Phase 2 correction confirmation

No findings. The failure-scope split is now correct.

- A stopped writer task in a live process leaves its handles available; readers and observers can reach `:dao.stream/blocked`, and publication can be retried from the retained memory-log.
- A failed process loses its in-memory local and intake handles. The document explicitly says nothing tails those dead handles and recovery is limited to content already materialized in DaoJing.
- Publication retry safety is correctly scoped to the period while the memory-log remains available.
- The checkpoint is accurately described as an O(history) replay optimization. This matches `dao.space.transactor.md`’s open item and makes no durability or crash-recovery promise.
- The previously stale query, write-operation, schema-wrapper, and stigmergy descriptions remain corrected.
- No contradictory append-only-file, file-reopen, v1 gap, result-stream, or transactor-as-stream claim remains in the living phase documents.
- `git diff --check` is clean.

There is no fourth failure-scope conflation. **Phase 2 is ready to commit.**

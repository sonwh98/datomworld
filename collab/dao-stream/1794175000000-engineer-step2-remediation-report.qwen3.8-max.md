Completed-GMT: 2026-11-09 20:58:22 GMT
Completed-Local: 2026-11-10 03:58:22 +07
Engineer: qwen3.8-max
Role: DaoStream and Distributed Protocol Engineer
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition

# Step 2 Remediation Report — Architect Findings 1–6

## Summary of Completed Remediations:
1. **Finding 1 (Dependency Closure, §2):**
   - Extracted `yin.vm.linker.pair` (676 lines). Contains generic link machinery without DHT imports.
   - `yin.vm.linker.interpreter` (309 lines) retains DHT serving and re-exports generic vars for compatibility.
   - `yin.repl` imports `yin.vm.linker.pair`.
2. **Finding 2 (In-Engine HEAD Execution, §3, §4):**
   - Removed `service-local!` and in-engine callback hooks from `yin.repl.index`.
   - Indexer emits pure data publication request `{:op :publish :id :round :manifest}` to `:out`.
   - Local runner services requests outside the indexer and feeds stream results back.
3. **Finding 3 (Query Deferral Wiring, §6, §7):**
   - Plumbed `:registered-ops` from `host-modules` into `query/serve` in `yin.repl.cljc`.
   - Registered composition queries are properly deferred, while unregistered unknown operations refuse with `:dao.stream.apply/unknown-operation`.
4. **Finding 4 (Parked VM Resumption, §7):**
   - Updated `yin.shell.session/recheck-on-tick` and added 3-arity `recheck-pending` to supply `initial-progress? true`, immediately resuming parked VM upon composition response.
5. **Finding 5 (Pending Publication Queueing & Serialization, §3, §4):**
   - Serialized queue (`:pending-publications`) maintains independent frontiers across consecutive commits.
   - `settle-publication` consumes oldest results first without clobbering unconsumed frontiers.
6. **Finding 6 (Signed Publication Completion, §7):**
   - Updated `link-answer` in `yin.shell.query` to await durable settlement before returning `:ok`.

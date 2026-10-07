# Brief: durable index store, slice 3 — indexer rehydration and (reset) continuity

Role: Storage Engineer. Worktree: /Users/sto/workspace/datomworld-durable-index, branch repl-durable-index. Slice 2 is at fbc30186. Do not commit.

Authority:
- The Architect ruling is collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md. §3 (the rehydration paragraph and the session-token paragraph) and §5 slice (3) bind this work.
- The slice-2 handoff seam is `(:recovery store)` and `(:index-recovery state)`: `{:manifest <addr|nil> :datoms <vector|nil>}`, already walked and validated.

Build (test first; show each new test FAILS on the current code):
1. **Rehydrate before evaluation.** Before any evaluation is admitted, build a fresh complete-retention dao.space memory log from the recovered datoms.
   - Group the datoms into transaction records by their original `t`.
   - Preserve each datom and its `t`.
   - The next entity id is one greater than the greatest restored entity or metadata id, but never below `datom/first-user-id`.
   - The transactor keeps deriving its next `t` from that restored log.
2. **Seed the indexer.** Initialize the indexer's manifest address, transaction counts and log from the recovered snapshot, so `q` answers before any new evaluation.
3. **Session tokens.** Mint a new shell token on every process start. Restored facts keep their original tokens and root/round metadata; new facts carry the new token.
4. **`(reset)` and VM selection in durable mode** rebuild the VM and observers but keep the published index and the rehydrated log. They must NOT reset `t` or entity allocation. With the default mem store, today's reset behaviour is unchanged.
5. **Docs.** Remove the interim-hazard text from the design doc and the `yin.repl.store` docstring, and describe the slice-3 behaviour.

Acceptance: the restart sequence from the ruling's §5, as real REPL input lines, on JVM, Node and Dart where the host supports the file store:
1. Evaluate code, then stop.
2. Restart against the same directory and require `dao.space.query`.
3. `q` sees the old facts.
4. Evaluate more code.
5. `q` sees both old and new facts, with increasing `t` and distinct session provenance.
Also verify:
- `(reset)` keeps those facts in durable mode.
- Mem mode's reset is unchanged.
- Restored entity ids never collide with new ones.
- HEAD after the first post-restart publication covers old plus new facts.

Portability:
- In mixed reader conditionals, `:cljd` goes FIRST.
- No array-map. No cross-namespace #'private.
- Refusal helpers return the error object; the call site applies ex-message.

Run every check in the FOREGROUND:
- kondo on the changed files
- focused JVM
- full `clj -M:test`
- `bb test:cljs` (confirm "Testing <ns>" appears)
- `bb build:yin-repl-peer`
- `bb test:cljd`
The machine is heavily loaded. If a lane is cut off, say so; never report an unseen result.

Report: collab/1790768800000-storage-engineer-repl-durable-index-slice3.<model>.report.md, mapping each acceptance item to its evidence, with at least one mutation per core property.

## Fix round 1 (2026-09-30 19:50 +07, orchestrator): sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790768800000-architect-repl-durable-index-slice3-signoff.gpt-6-sol.findings.md.
1. MEDIUM | store_test.cljc ~1160 | The first run has one transaction and no retraction, so the tests would still pass if recovery changed an older `t`, regrouped transactions or dropped retractions. Fix: restart from a snapshot with several `t` values and a retraction. Assert the exact restored transaction groups, the history rows (`:view :history`) and the current view, before and after a new publication.
2. MEDIUM | store_test.cljc ~1204 | The id test does not isolate the metadata-id rule. Fix: add a recovered fixture whose greatest id occurs only in `m`, and assert that the next allocated id exceeds it.
3. LOW | repl.cljc ~1562 | VM selection says "store cleared" in durable mode, although the index is carried over. Fix: make the message reflect the store mode, and test it.
Show that each strengthened test FAILS under a matching mutation. If a test exposes a real defect, fix it. Run every lane one at a time in the FOREGROUND, per the brief. Never report an unseen result.
Report: ...claude-opus-5-5.report-r2.md

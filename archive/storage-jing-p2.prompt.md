Created-GMT: 2026-09-08 06:30:00 GMT
Created-Local: 2026-09-08 13:30:00 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 1dc98117-5d7f-4b0e-b0f3-be45f09dc186
# Task: implement P2 — The pool, its two ends, and the in-memory backend
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-08 13:30:00 +07 | Status: active | Rationale: Storage & Indexing primary per team.md

**This is an implementation task with write authority**, bounded to the files
named below. Work in `/Users/sto/workspace/datomworld` on branch
`dao.stream-redesign-v2`.

Read first:
- `docs/design/dao.jing.implementation-plan.md` — P2 and the invariants (A-E)
- `src/cljc/dao/jing.cljc`, `src/cljc/dao/jing/mem.cljc`
- `src/cljc/dao/space/index.cljc`, `src/cljc/dao/space/transactor.cljc`
- `test/dao/jing_test.cljc`, `test/dao/jing/mem_test.cljc`, `test/dao/jing/dht_test.cljc`
- The five `dao.space` test files: `test/dao/space/agent_test.cljc`, `test/dao/space/datom_test.cljc`, `test/dao/space/index_test.cljc`, `test/dao/space/stigmergy_test.cljc`, `test/dao/space/transactor_test.cljc`

## What to build

Exactly P2 from `docs/design/dao.jing.implementation-plan.md`:

**Build** in `dao.jing.cljc`: `observer-state`, `observe-step!`, `adopt-cursor` per Decision 1 over `dao.stream.observe/step`.
Keep the content-addressing core (A, B, C, D) intact.

Keep `dao.jing.mem.cljc` (it satisfies B and D), but drop the D4 pins (remove the `:state` atom shape requirement and `:stream` key expectations from mem if any).

Move the pool's writer end to v2:
- `dao.space.index/append-ok!` (`index.cljc:526-531`) appends with `dao.stream/append!` and requires `:dao.stream/ok`.
- The transactor's intake validation (`transactor.cljc:197-201`) uses `dao.stream/writer?`.

**Delete**:
- The old observer functions and their `dao.stream` require from `dao.jing.cljc`.
- The observer section of `jing_test.cljc` and its v1 fixtures.
- The v1 `open-stream` helpers in `mem_test.cljc` and `dht_test.cljc`.
- The `open-intake` helpers in the five `test/dao/space/` files.

**Prove** with rewritten tests over v2:
- `jing_test.cljc` rewritten from A-E over v2 ring buffers (`ringbuffer/create!`, cursors from `stream/cursor … :dao.stream/oldest`).
- `mem_test.cljc` rewritten from B, D, D5 and one pool-convergence case.
- `dht_test.cljc:402`'s pool case and the five `dao.space` drains rewritten over v2 intakes with `:dao.stream/…` signals, `gap` fatal as before (E11).
- Confirm `Testing dao.jing-test` in the Node output.

## Special instructions
- Ensure the `dao.jing` rewrite preserves exactly the invariants defined in the plan.
- For `dao.jing`, implement the reporting of unrecognized outcomes per Decision 1 ("unrecognized answer is reported, not folded... raw answer under `:result`").

## Proof required, all three hosts
Run and report:
```sh
bb test:clj
bb test:cljs
bb test:cljd
clj -M:kondo --lint src/cljc/dao/jing.cljc src/cljc/dao/jing/mem.cljc src/cljc/dao/space/index.cljc src/cljc/dao/space/transactor.cljc test/dao/jing_test.cljc test/dao/jing/mem_test.cljc test/dao/jing/dht_test.cljc test/dao/space/agent_test.cljc test/dao/space/datom_test.cljc test/dao/space/index_test.cljc test/dao/space/stigmergy_test.cljc test/dao/space/transactor_test.cljc
```
Remember `bb test:cljd` regenerates `test/cljd-out/` and only one process may own that lane — do not run it concurrently with anything. Confirm `Testing dao.jing-test` in the Node output.

## Bounds
Change only the files specified above. Do not stage or commit anything; leave the work in the tree for review.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 1dc98117-5d7f-4b0e-b0f3-be45f09dc186

Then report: what changed, test suite counts, kondo results, and any P2 items you could not complete with the reason.

Created-GMT: 2026-09-06 05:26:00 GMT
Created-Local: 2026-09-06 12:26:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: pending (provider-generated)
# Task: Implement Phase R1 of dao.runtime
Role: Implementer

Your task is to implement Phase R1 as described in `docs/design/dao.runtime.implementation-plan.md`.

Files to modify:
- `src/cljc/dao/runtime.cljc`
- `test/dao/runtime_test.cljc`

Requirements for Phase R1:
1. **Split `run-once` and `check-wait-set`**: `run-once` pops and resumes the head of the ready queue if it has `:resume`, else returns `nil`. It never calls `check-wait-set`. `run-loop` drains the ready queue, then polls once; if the poll moved anything it continues, else returns. It must return the state whenever the ready queue's head is host-owned.
2. **Regression test**: Write a test for the discarded poll with a fake writer handle that answers `full` once and `ok` thereafter, counting appends.
3. **Classification tests**: Iterate over `dao.stream/outcomes-next` and `outcomes-append` in the tests instead of hardcoded doseq.
4. **Adapter tests**: Verify that a reader woken with `:status :dao.stream/gap` has its stored cursor replaced by the recovery cursor in `test/yin/vm/runtime_adapter_test.cljc`.
5. **Docstring**: Copy the architectural contract into the namespace docstring of `v2.cljc`.

Testing:
You must verify your changes by running `bb test:clj` and `bb test:cljs`. Do not run `bb test:cljd`.
If tests fail, fix them.

When you are finished, output your exact completion log starting with `Completed-GMT:`.

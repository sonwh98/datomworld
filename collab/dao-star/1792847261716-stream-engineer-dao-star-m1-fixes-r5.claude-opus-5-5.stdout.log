Completed-GMT: 2026-10-24 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-24, +07 (clock not read)

The deferred-grant defect and the pending-storage residual are both fixed. All ten `dao.star` namespaces and `dao.stream.http-test` pass, and kondo is clean. I did not write the findings report to `collab/`, so the report is below.

## Checks
- `clojure -M:test` on the ten `dao.star` namespaces (`facts`, `signals`, `router`, `driver`, `loopback`, `peer-close`, `r2`, `r3`, `r4`, `r5`) plus `dao.stream.http-test`: 69 tests, 1967 assertions, 0 failures, 0 errors.
- `clj -M:kondo --lint src/cljc/dao/star test/dao/star`: 0 errors, 0 warnings.
- I did not run `bb test:sub:clj` or the three-lane gate, as instructed.
- Process: I changed the source before writing `r5_test.clj`, so this wasn't strict test-first.

## The fix (`lease.cljc`)
I took the smaller change the reviewer offered: the holder's fact cursor now advances only once its reading is usable. Facts stay unread on the medium, in order, until a usable reading can observe them. A grant is therefore never skipped, and a lapse that follows it can't be read ahead of it. The `:pending-facts` retention from r4 is removed.

- **Usability check:** `usable-reading?` mirrors `observe-grant`'s own requirement. The reading must be a well-shaped duration, its unit must be in the holder's unit table, and its magnitude must be within the per-unit bound.
- **Foreign grants:** since nothing is retained, there is no pending storage to grow when the holder's own grant never arrives.
- **Tests:**
  - `a-lapse-behind-a-deferred-grant-is-read-in-order`: the holder's first reading is `{:ms 0}`. A grant is published, the grantor then records a lapse, and a usable reading arrives. The holder is not holding, `:lapsed?` is set, `renew!` appends nothing, and no begin page is projected.
  - `foreign-grants-are-not-retained`: after 30 foreign grants, the agent has no pending storage.

## Concern
- `usable-reading?` duplicates the private `reading-ok?` check inside `dao.lease`. That is correct today, but if `dao.lease` changes its own check, the two could drift apart.

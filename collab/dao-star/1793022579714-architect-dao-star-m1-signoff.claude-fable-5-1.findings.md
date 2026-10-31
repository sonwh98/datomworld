Completed-GMT: 2026-10-26 13:50:27 GMT
Completed-Local: 2026-10-26 20:50:27 +0700

I edited, staged and committed nothing. This was a targeted review, not a full read of the 3781-line commit. I read the commit stat, the amended design-doc diffs, `dao/star/lease.cljc`, the session-lease and publish/step parts of `driver.cljc`, `router.cljc`'s assembly checks, and the reviewer's round-5 findings. I did not re-run any suite. The test numbers below are the orchestrator's. I did not open the HTTP boundary files (`http.cljc`, `jvm.clj`) or the tests beyond `r5_test.clj`.

## 1. Does the work implement Mode 1 and my three rulings?

Yes, on what I traced.

**Phase B scoping rulings:**
- The layer is split as ruled: `facts`, `signals`, `router`, `driver` and `lease`, under `src/cljc/dao/star/`. The `lease` namespace is the optional split I allowed.
- The parked continuation uses the CESK path. `driver.cljc` creates per-session ring buffers, and the stated design is `dao.await` run/resume with the driver doing the fact append and projection.
- `publish!` (`driver.cljc:282-318`) enforces fact-before-UI. It appends the fact, and only on `ok` does it set `:latest`, call `answer!`, and patch subscriptions. A holder past its bound or lapsed drops the staged transition. A holder that is not holding for another reason, such as a gap, keeps it staged and publishes nothing. That is fail-closed behavior I did not specify, and I accept it.
- The router's co-location check refuses at assembly with `:refused :co-located` unless both handles are direct and declared (`router.cljc:58-65`). The facts declaration is checked first.

**Client-close ruling:** `jvm.clj` is not what changed. The ruling's result is the cap, covered under the r2 ruling below.

**r2 reconcile ruling:**
- *Finding 4, complete history.* `lease.cljc:256-264` defines the session-facts declaration as `{:retention :complete :value-domain :portable-values}` and refuses anything that is not `:complete` or that carries a `:capacity`, throwing `:refused`. The router calls it at assembly. This is what I ruled.
- *Finding 3, cap.* `subscription-max` is required, and `lease.cljc:288-290` throws `:refused :subscription-cap` without it. The exchange grant carries `{:dao.lease/max ...}` (`lease.cljc:323`). The driver checks `lease/past-bound?` in `observe-subscriptions!` and drops a subscription at its bound. `past-bound?` uses `lease/at-bound?` (`lease.cljc:164-174`), so renewal cannot extend tenure past the cap.
- I flagged last time that I had not traced whether the exchange grant accepts `:dao.lease/max`. It does: `dao/lease.cljc:516` validates `(duration? (get opts :dao.lease/max))` on the grant author.

**The reviewer.** Round 5 traced the deferred-grant and lapse ordering (`lease.cljc:114-133`), the regression test (`r5_test.clj`), and foreign-grant non-retention, found no new defects, and signed off. The reviewer is a different family from the authors, as the routing rules require. I rely on its trace for the paths I did not read.

## 2. Do the two amended docs state what the code does?

Yes. The `dao.star.md` and `dao.stream.http.md` diffs match the r2 ruling text I supplied, with the ruling cited. The grant paragraph now says subscription exchanges are required to carry `:dao.lease/max`, and the code refuses assembly without it. The Keep-alive paragraph names the buffered-send host case. The Sizing paragraph names the cap as the dead-peer bound. The http doc adds the Honest Limit.

Two small things:
- The Sizing text still says "each session medium's window exceeds its driver's." Session-facts is now complete-history and has no window. The sentence is true for the per-session input media, which remain ring buffers (`driver.cljc:37`), but it is now ambiguous. This is a wording fix, not a blocker.
- The doc says "required" for subscription exchanges. The code requires the cap on the config (`:subscription-max`), applied to the grants the judge authors for subscriptions. I did not verify whether non-subscription exchanges also receive the cap. The doc's wording permits either, so there is no mismatch.

## 3. Any architectural defect that blocks the merge?

No.

I checked against the invariants I own:
- **Fact before UI:** enforced in `publish!`.
- **Router reads classification media directly:** enforced at assembly.
- **Complete history for session-facts:** enforced at assembly.
- **The cap bounds a subscription that keeps renewing:** structural, via `at-bound?`.
- **No clock in the boundary:** the boundary files were not changed by the r2 fixes, and the doc says so.

## Residual risks I accept

1. **Dead-peer detection is bounded by the cap, not by duration.** With a large `subscription-max`, a dead subscription holds a socket and a driver slot until the cap. This is recorded in the Honest Limit and the Sizing text.
2. **JVM only.** There are no CLJS or CLJD dao.star tests, which matches the ratified criteria. The `.cljc` files may contain host assumptions I did not check.
3. **Duplicated validation.** `usable-reading?` mirrors the private `reading-ok?` in `dao.lease`. The reviewer accepted it. It can drift if `dao.lease` changes, so a public `dao.lease` predicate would be the later fix.
4. **Process-scoped leases.** Durable-judge duties from `dao.lease.md` are not addressed, as the spec says.
5. **Unread surfaces.** I did not read the HTTP boundary's code, the signals decode, or the 11 test files beyond `r5_test.clj`. For those I depend on the reviewer's five rounds and the orchestrator's runs: 69 tests / 1961 assertions in the focused set, `dao.stream` 516, three-lane changed tests, and kondo clean. One discrepancy: the commit message says 1938 assertions and the prompt says 1961. Both say 0 failures. It looks like the final post-formatter count versus an earlier one, but I did not verify.
6. **Known open items from your list.** G3 on master pending the merge, and host-boundary gate classification pending. Neither is an architectural defect in this work.

SIGNED OFF

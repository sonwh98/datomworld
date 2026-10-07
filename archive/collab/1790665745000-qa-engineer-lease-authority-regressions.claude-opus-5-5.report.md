Completed-GMT: 2026-09-29 07:12:43 GMT
Completed-Local: 2026-09-29 14:12:43 +07
Coding-Agent: claude
Session-ID: 067152df-fd04-4aff-859e-8aa524ba524d

# Report: Lease source-authority regression tests (Architect post-epic ruling, item 2)

Only one test was new. The other two acceptance items already had tests, and I confirmed with mutations that those tests catch the failure they guard against. The change is tests only: `git diff -- src` is empty, and nothing is staged or committed. I didn't touch the yin.repl files, main_test.cljc or orchestrator-log.md.

Changed: `test/dao/lease_composition_test.cljc` (+28 lines, one deftest). Nothing changed in lease_test.cljc or remote_serve_test.cljc.

## Acceptance items

| # | Invariant | Status | Test |
|---|---|---|---|
| 1 | A judge can wire and read its own grantor-authored medium | **Partly covered before; now fully covered (NEW test)** | `a-judge-wires-and-reads-its-own-grantor-authored-medium-test` (lease_composition_test.cljc) |
| 2 | remote-serve refuses an inbound medium with `:source` = `::grantor` | **Already covered** | `open-refuses-each-missing-or-malformed-lease-option`, the testing block "a standing medium whose source is the grantor" (remote_serve_test.cljc:407-421): alone and among other media, both give `::rs/grantor-source` |
| 3 | Renewal media remain attributed to their holder | **Already covered** | `serving-grants-a-lease-to-a-served-renewal-medium` (remote_serve_test.cljc:735): the renewal medium is wired with `:source rid` (the renewal identity), and the grant names `rid` as holder. The renewal and release e2e tests depend on it too (see mutation D) |

### Item 1 detail

What was already tested: in `grantor-authored-facts-establish-terms-test` (lease_test.cljc:1398), a `:grantor`-sourced fact seeds tenure. But that test works at the `initial-judge`/`wire-facts` level. No test assembled a judge with a `:self`-sourced medium through the composition APIs `make-judge` or `wire-declared-facts`. Those are exactly where the ruling says a blanket refusal must *not* be added. The line-115 medium the ruling cites is the *holder's* `:fact` medium (`holder-config`), not a judge medium.

The new test does three things:
- It builds a judge (`:self :grantor`) whose media include a `:source :grantor` ledger medium, once through `make-judge` and once through `wire-declared-facts` after assembly.
- It checks that both paths wire the medium.
- It appends a grantor grant to that ledger, ticks, and steps each judge. Tenure is seeded at the pass's `now`, with the grant's holder.

## Proof that each test catches a regression (mutations, each reverted)

| Mutation | Where | Result |
|---|---|---|
| A. Blanket refusal in `make-judge` + `wire-declared-facts`: throw when `:source` = `:self` | src/cljc/dao/lease.cljc | Only the new test fails (ERROR, "MUTATION"). No existing composition test caught it |
| A'. Same, but only in `wire-declared-facts` | lease.cljc | The new test still fails on its own |
| B. Silent read-side refusal: `resolve-author` returns nil for self-sourced media | lease.cljc | The new test fails (both paths, :864/:865), and so do the existing `grantor-authored-facts-establish-terms-test` and `r3-grant-drained-then-holder-medium-truncated-test` |
| C. Drop the guard: `claims-grantor?` always returns false | src/cljc/yin/vm/ffi/remote_serve.cljc | Item 2 test fails (:414, :415, :417) |
| D. Renewal medium wired with `:source grantor` instead of `rid` | remote_serve.cljc | Item 3 test fails (:735); `live-renewal-…`, `release-by-the-holder-reclaims` and `detach-without-expiry-…` fail too (17 failures, 1 error) |

After reverting, `git diff --stat -- src` is empty and `grep -rn MUTATION src` finds nothing.

## Verification

- kondo (`clj -M:kondo --lint test/dao/lease_composition_test.cljc`): 0 errors, 1 warning. The warning is an unused binding `traffic` at :627, in `served-connection-lifetime-sketch-test`, which was already there and which I didn't change.
- JVM (`clj -M:test -n dao.lease-test -n dao.lease-composition-test -n yin.vm.ffi.remote-serve-test`): 100 tests, 888 assertions, 0 failures, 0 errors.
- `bb test:cljs`: 2249 tests, 50589 assertions, 0 failures, 0 errors. The output includes "Testing dao.lease-composition-test".
- I didn't run `bb test:cljd`, as the task instructed.

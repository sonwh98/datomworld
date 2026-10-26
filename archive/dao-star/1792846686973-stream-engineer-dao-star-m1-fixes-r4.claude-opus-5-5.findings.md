Completed-GMT: 2026-10-24 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-24, +07 (clock not read)

All three gaps and the `signals.cljc` docstring are fixed and the suites are green. I did not write the findings report to `collab/`, so the full report is below.

## Checks
- `clojure -M:test` on all nine `dao.star` namespaces (`facts`, `signals`, `router`, `driver`, `loopback`, `peer-close`, `r2`, `r3`, `r4`) plus `dao.stream.http-test`: 67 tests, 1947 assertions, 0 failures, 0 errors.
- `clj -M:kondo --lint` is clean on `src/cljc/dao/star` and `test/dao/star`.
- I did not run `bb test:sub:clj` or the three-lane gate, as instructed.
- Process: I made the source changes before writing `r4_test.clj`, so this wasn't strict test-first.
- One test failure along the way was a test-arithmetic slip, not a code defect. Jumping the driver clock to 260 put the lineage exactly at its bound, so I moved it to 240.

## What changed
- **Gap 1 (tenure on projection paths):**
  - A new `observe-subscriptions!` steps every subscription holder against the current ticks before any projection. It runs at the start of each session pass and again before `publish!` patches subscriptions.
  - `patch!` now requires each subscription's holder to be holding. `flush-subscriptions!` requires both the exchange holder and the lineage holder to be holding.
  - Two tests cover this:
    - Input-triggered patch: only the driver's clock is advanced past the exchange cap while the lineage stays live. The input is consumed and answered, but no patch reaches the subscription.
    - Staged-subscription flush: the driver's clock is advanced past the lineage bound before the exchange grant lands. The exchange holder observes its grant, but nothing is projected.
- **Gap 2 (exchange-holder gap):** an exchange-holder gap now calls the session-wide `forget!`. Every subscription is cleared and the lineage holder is marked gapped, so nothing renews. The test uses two subscriptions whose lease cursors are at different positions, and the sibling with the newer cursor is cleared too.
- **Gap 3 (grants lost to unusable readings):** `lease/step!` keeps an accepted grant in `:pending-facts` when `observe-grant` leaves the holder ungranted, and retries it on each later pass. The test gives the holder a first reading of `{:ms 0}`, then a usable reading. The grant is observed afterwards and the begin page is answered.
- **Docstring:** `signals.cljc` now says `decode` is total on the JVM and ClojureScript, with the ClojureDart seam as the one exception.

## Concern
- A rejected accepted-grant fact addressed to another holder stays in that holder's pending list until its own grant is observed. The list is small and cleared on the first grant.

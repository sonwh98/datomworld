Completed-GMT: 2026-10-24 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-24, +07 (clock not read)

Both rulings are implemented and the tests pass. I did not write the findings report to `collab/`, so the full report is below for you to promote.

## Checks
- `clojure -M:test` on `dao.star.facts-test`, `signals-test`, `router-test`, `driver-test`, `loopback-test`, `peer-close-test`, `r2-test`, the new `r3-test`, and `dao.stream.http-test`: 63 tests, 1932 assertions, 0 failures, 0 errors.
- kondo is clean on `src/cljc/dao/star` and `test/dao/star`.
- I did not run `bb test:sub:clj dao.stream` or the three-lane gate, as instructed.
- **Process:** I changed the source before writing the new tests, so this was not strict test-first. The new tests passed on their first run.

## Ruling 3: the exchange lease tenure cap
- **Gate:** it holds with no `dao.lease` edit. `lease/grant` takes `{:dao.lease/max d}` in its opts, `at-bound?` measures the cap from the grant and renewals don't move it, and the judge reclaims with cause `:cap`.
- **Cap on grants:**
  - `make-exchange-lease` takes a required `:subscription-max` duration. A missing or non-duration value throws `ex-info` with `:refused :subscription-cap`.
  - Requests whose path starts with `/s/` get a grant carrying `:dao.lease/max`.
  - Exchanges granted during gap reconciliation also get the cap, since their path is unknown.
  - Other exchanges are uncapped, so the never-answered exchange still lapses by `:silence`.
- **Driver:** in `tick-subscriptions!`, a subscription is now dropped when its holder is past its bound, as well as on lapse or gap. Past the bound the holder renews nothing, so the lease lapses and reclaim runs `close-exchange`.
- **Test:** `a-subscription-the-driver-keeps-renewing-ends-at-its-cap` runs a subscription whose driver renews on every tick, with cap 200 ms against a 100 ms duration. It asserts:
  - only the subscription's grant carries the cap;
  - renewals happen before the cap and stop after it;
  - `close-exchange` ran, `:http/closed` was deposited, and `:lapsed` was recorded with cause `:cap`;
  - the never-answered exchange still lapses by `:silence`.

## Ruling 4: complete-history session facts
- **Constructors:**
  - `lease/facts-declaration` returns `{:retention :complete :value-domain :portable-values}`.
  - `lease/check-facts-declaration!` refuses anything not `:retention :complete`, or any declaration carrying a capacity, with `:refused :session-facts`. The check is on the declaration, not the handle.
  - `driver/make-driver`, `router/make-router` and `lease/make-lineage-lease` all take `:facts-declaration` and run that check.
- **Lease declarations:** the facts-medium declarations given to the lease constructors are derived from it with attribution `:envelope-key`. The driver's `:facts-capacity` option is replaced by `:lease-capacity`, which sizes only the lease media.
- **Tests:** all `dao.star` tests now create session facts with `memory-log/create!`.
  - `constructors-refuse-a-facts-medium-that-is-not-complete-history` covers the refusal.
  - `session-facts-keep-the-root-past-any-ring-capacity` appends 512 more facts, past the old 256 capacity. It asserts there is no gap, the root is still first when read from the origin, and the router still routes the lineage as live.
- **Leftover grep:** the remaining `ringbuffer` and `:evict-oldest` hits are intended:
  - the per-session routed-input and view rings in `driver.cljc`;
  - the lease media declarations;
  - the boundary's ingress admission in the loopback test;
  - the ingress, lease and begin mediums in the tests.
- **Gap coverage:** the existing gap tests already used the ingress, lease, tick, view and begin media, none of the facts medium, so none needed moving.
- **Resolved concern:** the roots-lost-in-a-facts-gap concern from r2 no longer applies.

## Concerns
1. The `:subscription-max` value is a composition choice. The tests use 200 ms against a 100 ms duration. Real sizing is the composition's call, as the ruling says.
2. The router and driver still carry the facts-medium gap-handling code. It is now unreachable for session facts, and I left it in place.

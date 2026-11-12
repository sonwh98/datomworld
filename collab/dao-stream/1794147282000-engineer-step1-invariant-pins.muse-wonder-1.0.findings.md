# Step 1 Findings: Invariant Pins & Non-DHT Fixture Parameterization

Created-GMT: 2026-11-08 14:38:30 GMT
Created-Local: 2026-11-08 21:38:30 +07
Coding-Agent: muse
Model: muse-wonder-1.0
Session-ID: e8c6e6a5-b58b-45ac-914b-a696f4512f9e

## Summary of Deliverable
Step 1 of the refactor sequence (docs/design/yin.repl.dht-composition.md §9) is complete. Existing invariants are green and publication-result expectations are explicit. All changes are strictly isolated to `test/yin/repl/` without touching `src/`.

## Changes Made
- `test/yin/repl/fixture.cljc` (new, 212 lines): Common test constructor providing explicitly injected non-DHT storage backends (`:memory` via `dao.jing.mem`, `:file` via `dao.space.store/open`), publication interpreter wrappers (`observed-backend`, `failable-backend`), and `frontier` extraction.
- `test/yin/repl/index_test.cljc`: Decisive publication/frontier/recovery tests parameterized over both backends, plus 6 new pins: frontier-gate agreement after every round, correlated settle-once publication requests, failed-HEAD never advancing the frontier, count-cap mismatch forcing full reconstruction, exact suffix length, and no double settlement.
- `test/yin/repl/require_test.cljc`: `shell` and `require-and-apply` parameterized over injected backend; causality/policy tests use explicit memory injection via `prompt-state`.
- `test/yin/repl/query_test.cljc`: All shells built through fixture; settlement tests and basic answer tests run across VMs and backends.
- `test/yin/repl/store_test.cljc`: Cross-backend proof verifying agreeing frontiers and identical history with `:dht` and `:index-store-spec` both nil.

## Verified Test Assertions (JVM Focused)
- `yin.repl.index-test`: 27 tests, 569 assertions, 0 failures (baseline: 21 tests, 242 assertions).
- `yin.repl.require-test`: 20 tests, 209 assertions, 0 failures (baseline: 20 tests, 172 assertions).
- `yin.repl.query-test`: 18 tests, 279 assertions, 0 failures (baseline: 18 tests, 246 assertions).
- `yin.repl.store-test`: 28 tests, 145 assertions, 0 failures (baseline: 27 tests, 132 assertions).

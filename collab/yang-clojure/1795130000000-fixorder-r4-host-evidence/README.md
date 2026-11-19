# r4 host execution evidence — the sign-off r2 B4 gap

Tested revision: fix/constructed-collection-order @ bd462723 (post-format, the pushed tip).

- node-order.log: Node targeted selector --test=yang.clojure-collection-order-test → 15 tests / 91,500 assertions / 0 failures 0 errors (rc 0). Includes legacy-fixture-custody-test and set-effect-order-and-collision-trace-test.
- dart-r4-targeted.log: Dart targeted selector cljd_agg --only yang.clojure-collection-order-test/set-effect-order-and-collision-trace-test → All tests passed (rc 0).
- An earlier node-order.log attempt ran from the wrong cwd (the orchestrator's shell cwd reset to the main checkout), so legacy-fixture-custody-test errored ENOENT on the relative fixture path; the raw log was overwritten by the correct rerun above — the failed invocation is recorded here, not in the evidence log. The test itself is cwd-correct when invoked from the repo root as the lanes do.

Node full namespace count 15/91,500 matches JVM 15/91,500 (same file, same assertions).

Created-GMT: 2026-11-19 (resume time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a1f443)

# dart-leg r2 — the lane ran unsandboxed; one real defect to fix

Your seam and shared rows WORK: the lane executed 4665 tests green, and the new
yang.php.antlr.dart-linked-test/linked-fixture-rows ran on the dart backend. The sandbox
block is gone (the seat ran it). One failure remains — yours to fix:

  Module link refused: absent
  {:link-module php, :address :segment/blake3-19571d04..., :status :refused, :reason :absent}

The linked composition's dart path does not make the `php` prelude module resolvable at its
derived address at link time — the JVM harness path publishes it (site-set publication -> the
module store), the dart path evidently skips or mis-derives that step. Diagnose the publication
side on dart (the site-set store backend — dao.jing-backed on JVM — and/or the runtime
composition seeding) and fix so the row publishes and links on dart.

Constraints: no manifest changes; no new VM surface; the JVM rows stay green; the eval
coordinator dart boundary (the PHP-parser dependency) stays deferred as recorded.
Then re-run the dart lane rows (bb src/dev/cljd_agg.clj --slow-regex is not needed — the
fixture rows are in shard_1; a focused --only is fine per the focus rule) and report the
per-row evidence. Log custody target/dartleg-r2/. No commits.

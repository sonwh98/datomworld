# Frontier r1: Python → PHP finally replacement

The requested carrier diagnosis does not match this fixture's source. The PHP
`finally` throws its own `$replacement`; it does not rethrow the incoming
Python wrapper. `php.rt/mint-retained` already mints a PHP identity cell with
the complete incoming condition as provenance, and `php.rt/retained-condition`
reads that cell through `cell/dsg__provenance`. No mint or retention change was
made; changing it would not repair this observation.

The fixture creates `$replacement` at unit initialization, then the PHP
`run-main` releases globals before the observation calls `replacement_payload`
and `probe`. The proposed fixture repair creates the replacement in a
`seed_replacement` call immediately before the observation, matching the
existing runtime-seeded PHP donor fixture. The assertion remains equality with
the exact replacement returned before the probe. Only the Python-to-PHP observation invokes the seed. The PHP caller source
adds the helper for all PHP finally-replacement legs; other observations keep
their existing setup, and no matrix rows were edited. This is a source-based
diagnosis pending execution evidence, not a demonstrated green result.

## Verification

- `bb gen:antlr`: first attempt failed because the sandbox denied the network;
  `target/frontier-r1/antlr.log`. Retried after copying upstream helper sources
  into this worktree's cache from a neighboring checkout; the generator checked
  pinned digests and succeeded: `target/frontier-r1/antlr-retry.log`.
- `git diff --check`: passed.
- Single-leg baseline: `target/frontier-r1/focused-red.log`; interrupted after
  several minutes without a result.
- Seeded single-leg: `target/frontier-r1/focused-seeded.log` (see final status
  below).
- `clojure -M:kondo --lint test/yang/call/exception_legs.clj test/yang/call/exception_source_test.clj`: 0 errors, 0 warnings; `target/frontier-r1/kondo.log`.
- The first seeded iteration was interrupted when the fixture was narrowed to
  this direction; `target/frontier-r1/focused-seeded.log`. The final focused
  invocation (`clojure -M:test -n yang.call.exception-source-test -v
  yang.call.exception-source-test/python-php-finally-replacement-leg-test`)
  was interrupted after several minutes without a summary;
  `target/frontier-r1/focused-final.log`. No register, semantic, or stack verdict
  was produced. The focused suite and steward run were not run. Acceptance is
  **not met**; the fixture change remains a hypothesis for the seat to verify.

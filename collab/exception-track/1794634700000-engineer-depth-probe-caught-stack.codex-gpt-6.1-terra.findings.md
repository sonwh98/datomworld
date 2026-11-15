# Depth probe caught-failure discriminator

The nightly failure is a measurement budget error, not evidence of a VM regression. The fixture is a loop that throws and catches each iteration, not a recursive call at depth n. The probe measures maximum VM continuation depth for a completed unlinked run; its contract still requires completion, the correct result, no pending work and constant depth. It must not treat budget exhaustion as completion or weaken those assertions.

| Revision | Measurement / result |
| --- | --- |
| `4d0d3e70` | Probe did not yet exist. Injected the later unmodified `.clj` harness: Python caught-failure n=256 completed on stack in 1,191,276 steps, depth 13; semantic and register completed in 679,230 steps, depth 13. Row command rc=0. Full deftest rc=1: Ran 1 test containing 61 assertions, 0 failures, 1 error. It stopped before the Python row because that older Clojure lowering rejects `try/finally`; this is a harness/engine compatibility limit, not a Python result. |
| `23de41a9` | The original 1,000,000-step probe reproduces the Python stack completion and result failures. Full deftest rc=1; Ran 1 test containing 960 assertions, 26 failures, 0 errors. Direct row: stack stops at exactly 1,000,000, `:budget-exhausted true`, `:blocked? false`, `:pending? false`, depth 13; semantic/register complete at 679,230 steps. Row command rc=0. |
| `7320de98` | Python caught-failure n=256 completes on stack in 1,191,276 steps, depth 13, with no block or pending work; semantic/register 679,230, depth 13. Row command rc=0. Full deftest rc=0; Ran 1 test containing 960 assertions, 0 failures, 0 errors. The later harness sets the budget to 10,000,000. |
| `3d271099` (origin/master at branch creation) | Full namespace rc=0; Ran 3 tests containing 1347 assertions, 0 failures, 0 errors. The later probe is present with the 10,000,000 budget and the same completion/result/depth assertions. |

The diff from the nightly's `.cljc` probe to `7320de98` changes the per-measurement limit from 1M to 10M and renames the JVM-only test to `.clj`; it does not alter the assertion or VM path. The recorded completed PHP stack n=256 case takes 6,783,191 steps, so a ceiling exceeding that cost is necessary for the whole matrix. The limit still reports exhaustion as failure. This strict measurement correction has already landed on master; no further code change is warranted. Neither this observation nor the loop matrix proves arbitrary recursive exception unwinding.

`docs/agents/build-n-test.md` directs scoped reproduction; it has no CESK/recursion-specific notes at this revision; the probe's namespace documentation and `collab/exception-track/1794590000000-consult-depth-probe-scope.prompt.md` distinguish unlinked constant-case measurements from recursive characterization and linked follow-up.

Verification on jupiter:

- `clojure -M:test -n yang.call.conformance-test`: rc=0; Ran 3 tests containing 18 assertions; 0 failures, 0 errors.
- `bb test:sub:clj dao.stream`: rc=0; 41 JVM namespaces, Ran 589 tests containing 5732 assertions; 0 failures, 0 errors.

The current namespace run generated `test/yang/call/depth_probe.edn`; its incidental revision/measurement rewrite was discarded. The full namespace was run at `3d271099`; later master changes are listed in the branch history, not claimed as tested. An initial accidental broad runner invocation was interrupted (rc=130); no full-suite result is claimed.

Branch base after rebase: `f94649e3` (origin/master); the findings commit is the branch HEAD.

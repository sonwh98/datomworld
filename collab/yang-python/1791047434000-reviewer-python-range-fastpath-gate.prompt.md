Created-GMT: 2026-10-03 17:10:34 GMT
Created-Local: 2026-10-04 00:10:34 +07 (+0700)
Coding-Agent: glm (session 3b092157-65fe-4729-bf0c-531a6ee274e2) and codex (gpt-6.1-sol, fresh thread, ID pending)
Session-ID: 3b092157-65fe-4729-bf0c-531a6ee274e2 (glm); pending (provider-generated) (codex)

# Task: Python py/range-at guarded fast path, independent gate

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-04 00:10 +07 | Status: active | Rationale: routing-status 2026-10-03 (glm via CLI); different family from the Claude-family author; static pass
- Model: gpt-6.1-sol | Assigned: 2026-10-04 00:10 +07 | Status: active | Rationale: standing rule, gate reviews are gpt-6.1-sol fresh threads; independent second reviewer. Neither reviewer sees the other's report.

Perform a read-only review of the UNCOMMITTED change in /Users/sto/workspace/datomworld-py-rangefix (branch yang-python-rangefix, base
master eaf7d6f0). Use `git diff` there. Files: src/cljc/yang/python/antlr/prelude.cljc (the range region, ~+21/-6),
test/yang/python/antlr/prelude_parity_test.cljc (+44: 16 new rows and a guard test),
test/yang/python/antlr/float_address_test.cljc (five content-address goldens re-minted).

## What the change is for (measured)

Commit 93e83213 replaced the range element lookup `(+ start (* i step))` with a recursive `py/range-elem` (halve i, double step, so
the product i*step, up to 2^54, is never formed: JS rounds past 2^53). That made yang.python.antlr.e2e-test/long-loops-test 65 s -> 403 s
(834 s on master). The converged architect ruling (fable-5.1 and gpt-6-astra agree), which the change must implement exactly:

> Add a guarded O(1) fast path in the PRELUDE ONLY (no lowering change, no iterator state, no new range field). Take
> `start + i*step` only when `0 <= i <= 2^26`, `-2^26 <= step <= 2^26` and `-2^52 <= start <= 2^52`, using literal comparisons
> (no int-mod, int-floordiv or recursive bound calculation). Then |i*step| <= 2^52 and the sum is within +-2^53, exact on every
> host, INCLUDING the one-past candidate. Outside the guard fall back to the EXISTING recursive helper, unchanged and kept
> separate. Preserve the exclusive-stop test and the C1 domain restriction; do NOT precompute a checked length; do not touch
> py/range-count, membership or py/float-mod. Correct the range-len comment (CPython's limit is sys.maxsize; 2^53 is this
> profile's restriction). Keep long-loops-test ^:slow. Regression guard must be deterministic (no wall-clock budget).

## Check, with file:line evidence

1. The guard and the fast-path arithmetic: are all three bounds exactly as ruled (inclusive 2^26 on i and |step|, +-2^52 on start)?
   Prove or refute that every value it computes is exactly representable on JS (doubles), including negative steps, negative
   start, start at -2^52, i at 2^26, and the one-past element. Is `py/abs` safe at the bound (no overflow)? Are the comparisons
   literal numbers that are exact on every host (JVM long, JS double, Dart int), with no reader or host-number pitfalls (for
   example 67108864 vs 2^26 written as a float, or a CLJS integer literal collapsing)?
2. The fallback: is the recursive `py/range-elem` byte-for-byte unchanged and still a separate function, so its recursive calls do
   not re-run the guard? Does every caller of range-elem now go through the guarded entry, or can a caller still reach the
   slow path with a small i and step? Is the stop test / one-past behavior unchanged on both paths?
3. The new tests: do the 16 parity rows really sit at the guard edges (i and |step| at the edge and one past; start at +-2^52 and
   +-(2^52+1); both signs; `range(-5, 5)`; `range(5, -5, -3)`; the one-past index returning :py/stop)? Are the expected values
   right (compute a few by hand)? Can each row fail? The regression guard `range-fast-path-on-every-host-test` stubs `py/range-elem`
   with a sentinel by rebuilding the prelude from `prelude/function-definitions` and iterates `range(3000)` on all four VMs: does the
   stub really replace the function the fast path would otherwise call, so a regression (fast path never taken) would make the test
   FAIL? Any way the test passes even if the guard were broken?
4. The goldens: only the five content-address goldens changed, in float_address_test.cljc (lines ~221, 241, 243, 245, 249), in the
   same `:segment/blake3-...` form. The engineer proved the cause by reverting the fast path (old goldens then pass) and restoring it.
   Confirm nothing else in that file changed and no semantic assertion was weakened.
5. Cross-host portability: ClojureDart reader-conditional order (:cljd FIRST), unary minus on floats (CLJD compiles `(- x)` as
   0 - x), cljs keyword identity. Anything in the added code or tests that differs across hosts?
6. Scope: the diff must contain nothing beyond the ruling. Flag anything extra.

Already verified by the orchestrator's tooling (untrusted by you, do not re-run): the engineer reports focused runs green
(float-address-test + prelude-parity-test 23 tests/149 assertions/0 failures), long-loops-test 2 min 14 s (was 834 s), kondo 0 errors,
cljstyle clean. The orchestrator is re-running the full JVM and Node lanes now and Dart later. Do NOT run suites.

Do not edit. Treat prior reports as untrusted. Complete in one turn.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate. End with an explicit ready-to-commit verdict.

Created-GMT: 2026-11-24 (resume time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a20de0)

# remint r2 — the countersign found ONE more stale golden; re-mint it

Your amendment held under the countersign EXCEPT one leg: descriptor-profile-test RED 1/107:
`descriptor_profile_test.cljc:414` profile-program-root-goldens-test, `:yang.python/py-v1
:bundled`: expected :segment/blake3-e7c446de2caa4c73b2b7d7c82f1c025ac57885290c65585c13fd542e449a0517,
actual :segment/blake3-140a94441b89ecc490336d8df2c5c0d191cb9ff8a2f774f81e5f27265f6ba7fd.

(The countersign ran on the union tree 71263348 = your branch + current master, fresh gen:antlr.
Everything else verified GREEN twice: the manifest golden, the 5 float pins, the profiles.cljc
locks, zipmap. Do not disturb those.)

## The work
1. Re-mint the py-v1 `:bundled` profile-program-root golden from the same fresh-generation path
   (the new expected value should be 140a9444... or a fresh two-run determinism value if it
   varies — record which), with a provenance comment per convention.
2. Audit the other mode rows in that test (:linked passed in the countersign run — confirm; any
   other modes re-derive the same way).
3. Determinism: two runs recorded per changed golden.

## The FULL gate re-run (the countersign's mission spec)
- -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test (green again)
- -n descriptor-profile-test (green with the new golden)
- yin.repl.python-antlr-test TWICE (the legs the interrupted chain deferred)
- The two provisional slow rows: yang.call.python-exception-test and the
  yang.php.antlr.e2e-test slow row — run and record them either way (the ledger records them).
Log custody target/remint-r2/. Findings update per convention. No commits.

Created-GMT: 2026-11-24
Coding-Agent: codex
Re: remint r2, d32c8179 baseline

Fresh `bb gen:antlr` was forced by removing the worktree-local cache stamp; it completed successfully with pinned digest checks. Logs and scripts are in `target/remint-r2/`.

`profile-program-root-goldens-test`, `:yang.python/py-v1 :bundled`, changed `:segment/blake3-e7c446de2caa4c73b2b7d7c82f1c025ac57885290c65585c13fd542e449a0517` to `:segment/blake3-140a94441b89ecc490336d8df2c5c0d191cb9ff8a2f774f81e5f27265f6ba7fd`, exactly the countersign actual. Independent fresh JVM derivations of every one of the eight roots produced byte-identical logs, each SHA-256 `b4fd0552fa43f2c7cfb3f069724e3499650e2c88f6858b3bc55ea21a74a7a38f` (`derive-1.log`, `derive-2.log`). The py-v1 linked root remains `:segment/blake3-40df036d1e1f6d2681d451d9c632fa73924c1ff20a0ed46abdaef4f26ded0d59`; all six clojure-core-v1 bundled/linked roots match their existing pins. The focused golden test passed 1 test / 8 assertions / 0 failures / 0 errors.

Full JVM gates:
- `yang.python.antlr.float-address-test` and `yang.python.antlr.linked-prelude-test`: 26 tests / 230 assertions / 0 failures / 0 errors.
- `yang.python.antlr.descriptor-profile-test`: 11 tests / 107 assertions / 0 failures / 0 errors.
- `yin.repl.python-antlr-test` runs 1 and 2: each 22 tests / 201 assertions / 0 failures / 0 errors (target/remint-r2/repl-{1,2}.log and .rc; after the r1 incomplete run, with the r2 test edit present).
- `yang.call.python-exception-test`: 10 tests / 127 assertions / 3 failures / 0 errors. `python-malformed-condition-test` expected payload `[nil]`, got `[]`, once per register, semantic, and stack backend; see python-exception.log.
- `yang.php.antlr.e2e-test`: no completed result in this custody. The full and slow-only logs lack summaries and exit-code files; the full log records gc_023 and gc_013 engine-gap diagnostics. Neither run is claimed green.

At r2, only the descriptor test golden and this findings file were changed. The r2 custody does not contain a dao.stream gate; the dao.stream assertion in commit d32c8179 is not substantiated here.

## R3 — independent review findings

The review file named in the dispatch was absent in this checkout; the five findings below are taken from the dispatch summary. `bb gen:antlr` verified the worktree's previously fresh-generated inputs and outputs (cache hit; gen-antlr.log). Custody is under `target/remint-r3/`.

1. Regenerated `dht-import-v1.edn` with its committed generator in two fresh JVMs. Both full output files have SHA-256 `6875e6f2ebcf0efa81b34633ec4a07131c1ff88bf2dc7998cab456e36b4fa1c2` and compare byte-identically; the py manifest is `6e60da0c73eb89861bcdc4c5c53f59bdf5e8ae6ba88b5b09c65eab543497259e`. DHT test: pending.
2. Re-minted pysp manifest `5f1ba8b4a18fb4ebfd072521936bef31a8cad0fe99bf24d1e888925ab9f9b7d8` → `d33e3a2cb77640a13f043d116d8faa16595b2419ab5d1fbe7b0f3c72e42d16f5`. Two fresh JVM logs SHA-256 `c7f538c911863435d2594113a5aad672038ed7d55d0fa2d1c3e9386827ff4b8e`. `pysp-manifest-address-test`: 1 test / 5 assertions / 0 failures / 0 errors.
3. `git log --all -S 'py.b/AssertionError'` and `git show 60cda9b6` establish that 60cda9b6 introduced the runtime class. Corrected the key-count provenance and added explicit membership in `runtime-keys`; combined pin gate passed 26 tests / 231 assertions / 0 failures / 0 errors. The added membership assertion accounts for the increase from 230 to 231.
4. Corrected r1 and r2 custody descriptions: r1's interrupted REPL run is superseded by the two documented r2 passes, not retroactively green; no dao.stream gate is documented in these custody logs. Removed stale running status for the two PHP logs, which lack final summaries and exit codes.
5. Added 60cda9b6, 392746bd, and 906586c5 to the manifest and float-golden provenance riders alongside 7692d347. The combined pin gate passed 26 tests / 231 assertions / 0 failures / 0 errors.

Descriptor-profile gate: 11 tests / 107 assertions / 0 failures / 0 errors. REPL gate: pending.

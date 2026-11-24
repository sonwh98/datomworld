Created-GMT: 2026-11-24
Coding-Agent: codex
Re: remint r2, d32c8179 (uncommitted)

Fresh `bb gen:antlr` was forced by removing the worktree-local cache stamp; it completed successfully with pinned digest checks. Logs and scripts are in `target/remint-r2/`.

`profile-program-root-goldens-test`, `:yang.python/py-v1 :bundled`, changed `:segment/blake3-e7c446de2caa4c73b2b7d7c82f1c025ac57885290c65585c13fd542e449a0517` to `:segment/blake3-140a94441b89ecc490336d8df2c5c0d191cb9ff8a2f774f81e5f27265f6ba7fd`, exactly the countersign actual. Independent fresh JVM derivations of every one of the eight roots produced byte-identical logs, each SHA-256 `b4fd0552fa43f2c7cfb3f069724e3499650e2c88f6858b3bc55ea21a74a7a38f` (`derive-1.log`, `derive-2.log`). The py-v1 linked root remains `:segment/blake3-40df036d1e1f6d2681d451d9c632fa73924c1ff20a0ed46abdaef4f26ded0d59`; all six clojure-core-v1 bundled/linked roots match their existing pins. The focused golden test passed 1 test / 8 assertions / 0 failures / 0 errors.

Full JVM gates:
- `yang.python.antlr.float-address-test` and `yang.python.antlr.linked-prelude-test`: 26 tests / 230 assertions / 0 failures / 0 errors.
- `yang.python.antlr.descriptor-profile-test`: 11 tests / 107 assertions / 0 failures / 0 errors.
- `yin.repl.python-antlr-test` runs 1 and 2: each 22 tests / 201 assertions / 0 failures / 0 errors.
- `yang.call.python-exception-test`: 10 tests / 127 assertions / 3 failures / 0 errors. `python-malformed-condition-test` expected payload `[nil]`, got `[]`, once per register, semantic, and stack backend; see python-exception.log.
- `yang.php.antlr.e2e-test`: RUNNING, no final counts yet. Full namespace in php-e2e.log and explicitly slow-tagged selection in php-e2e-slow.log; their commands write php-e2e.rc and php-e2e-slow.rc on completion. Full log has recorded gc_023 and gc_013 engine-gap diagnostics, no reported assertion failure so far. The nightly ledger records this single-JVM slow namespace as approximately eight hours; these runs were left active, not claimed green.

Only the descriptor test golden and this findings file are changed; no commit made.

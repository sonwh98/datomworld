# PHP siteset architectural lint

Base: origin/master `3d2710994b59d64486e0fdd4f9de185a00972943`.
Fix commit (tested source HEAD): `f27e643db1339a77c27d7e32b220650e88a5c080`.

`derive-verified` compared each row ID to `jing/segment-key` of its body. That mints with the default algorithm, so it rejects valid addresses carrying another registered algorithm and violates the equality-validation lint. Replaced that comparison with `jing/segment-matches?`, which validates the body using the algorithm carried by the address. The row ID consistency check remains in place; the lint was not changed.

Verification on jupiter:

- Before fix: `clojure -M:test -n yang.php.antlr.siteset-test` — rc=0; Ran 7 tests containing 30 assertions; 0 failures, 0 errors.
- `clojure -M:test -n dao.jing.hash-registry-contract-test` — rc=0; Ran 15 tests containing 6011 assertions; 0 failures, 0 errors.
- After fix: `clojure -M:test -n yang.php.antlr.siteset-test` — rc=0; Ran 7 tests containing 30 assertions; 0 failures, 0 errors.
- `bb test:sub:clj dao.stream` — rc=0; Ran 589 tests containing 5717 assertions; 0 failures, 0 errors. The first invocation returned rc=127 because `bb` was not on PATH; the successful invocation used the installed mise shims.

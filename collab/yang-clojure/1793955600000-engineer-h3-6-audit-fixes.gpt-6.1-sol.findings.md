Fixed both findings in [latte_oracle_test.clj](/Users/sto/workspace/datomworld/test/yang/latte_oracle_test.clj:2404). [Applied diff](/private/tmp/yang-oracle-audit-fix/fix.patch).

- Hashing now renames generated symbols by first occurrence across `[forms call]`, preserving shared references. Regression tests cover repeated NBE reads, symbol identity, and ordinary source changes.
- Case 6 uses `:nbe-beta`, requires a dynamic site executed in both lanes, and runs the refusal assertion unconditionally.

Verification passed:

- **5 tests, 1,393 assertions; zero failures/errors.**
- Fresh JVM with advanced reader counter: **155/155 hashes identical**, **165/165 arities credited**.
- All **20,805 site rows unchanged**; 60 hashes normalized.
- Lint: zero errors, two existing warnings. Whitespace check passed.

Only the requested source file changed. Artifacts were regenerated in a temporary directory. To refresh the committed EDN files:

```sh
clojure -Sdeps \
  '{:aliases {:refresh {:main-opts ["-e" "(require (quote yang.latte-oracle-test)) (yang.latte-oracle-test/write-kernel-gate-evidence!)"]}}}' \
  -M:test:refresh
```
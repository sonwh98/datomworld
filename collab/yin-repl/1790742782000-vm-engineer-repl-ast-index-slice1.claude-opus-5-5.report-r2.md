Completed-GMT: 2026-09-30 05:31:12 GMT
Completed-Local: 2026-09-30 12:31:12 +07
Coding-Agent: claude
Session-ID: 771d73b8-33cb-4277-815e-2b72fe699c1c

# Report r2: fix round 1 (gate findings P1, P2)

Both gate findings are fixed. The tests were written first and reproduced both defects, and every required lane is green. Nothing is staged or committed, and every check ran in the foreground. Changed files: `src/cljc/yin/repl/ast_index.cljc` and `test/yin/repl/ast_index_test.cljc`. `yin.vm` and `dao.jing` are unchanged.

## P1: forged row ids (address verification)
- **Fix:** after `vm/validate-rows`, each row's id is checked against its body with the existing `dao.jing/segment-matches?`. This is the same check `yin.vm.macro/valid-tree?` uses; no second hash scheme was added.
  - A mismatch records `{:stage :packet :reason :address-mismatch :id <addr>}`, and nothing from that packet is merged.
  - The check runs after structure validation, so a structural defect is still reported as `:invalid-rows`.
- **Test, written first:** "a well-formed row under a forged address" uses the `[:literal 1]` row under the address of `[:literal 3]`.
  - Before the fix it failed three ways: no failure was recorded, the relations were not nil and the forged row appeared in `$ast`, and later packets were indexed (`:programs` 3, not 1).
- **Consequence:** the previous "address disagrees with the one already held" (`:address-conflict`) branch could no longer be reached.
  - If an address matches its body, any other row under that address has an equal body (`=`), so no conflict is possible.
  - I removed the branch and the now-unused `held` argument.
  - The existing test for that case is renamed "a row forged under an address already held" and now expects `:address-mismatch`. It still proves such a row is refused.
  - The namespace docstring is updated to match.

## P2: identical duplicate rows
- **Fix:** repeated ids are detected by comparing the number of packet rows with the number of distinct addresses, before anything else uses the map. The first repeated id is reported with `:reason :duplicate-address`.
- **Test, written first:** "one address, the same row twice" (`[root (conj rows (first rows))]`).
  - Before the fix it had the same three failures: no failure, relations not nil, and `:programs` 3.

Before the fix the new tests produced 6 failures (3 per finding). After it: 9 tests, 114 assertions, 0 failures.

## Mutation proof (each applied, tests run, source restored and confirmed by grep)
- **Address check forced to pass** (`(constantly true)` in place of `segment-matches?`): 5 failures, in "a well-formed row under a forged address" and "a row forged under an address already held".
- **Old duplicate check restored** (compare each row with the final map value): 3 failures, in "one address, the same row twice".
- One earlier attempt at the first mutation did not compile, so it proved nothing. It was redone as above.

## Verification (foreground)
| Check | Result |
|---|---|
| kondo (`ast_index.cljc`, `ast_index_test.cljc`) | 0 errors, 0 warnings |
| Focused JVM (`yin.repl.ast-index-test`, `yin.repl-test`) | 49 tests, 337 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2299 tests, 51084 assertions, 0 failures, 0 errors; "Testing yin.repl.ast-index-test" appears |
| `bb test:cljd` | "+2261: All tests passed!" |

**Did `ast-index-test` actually run under CLJD?** The Dart reporter does not print every test: only 104 of the 138 compiled test files appear in the log, and no `ast-index-test` line appears at all. So I checked directly:
- I temporarily added a deftest that always fails to `ast_index_test.cljc` and ran `bb test:cljd`. The result was "+2261 -1: Some tests failed", with the added test named as the one failure. That shows the namespace is compiled and run in the Dart lane.
- I then removed that test (confirmed by grep in the source and the compiled `.dart` file) and ran `bb test:cljd` once more: "+2261: All tests passed!".
- Temporary logs and backup files are deleted.

`dart test` on the single file could not be run because this session's permission settings did not allow it without approval. cljstyle was not part of this round's check list and was not run.

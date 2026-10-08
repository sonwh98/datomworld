Completed-GMT: 2026-09-30 05:59:03 GMT
Completed-Local: 2026-09-30 12:59:03 +07
Coding-Agent: claude
Session-ID: 771d73b8-33cb-4277-815e-2b72fe699c1c

# Report r4: fix round 3 (Architect finding: a metadata-distinct row replacing a held row)

The finding is fixed and every required lane is green. The test was written first and reproduced the bug. Nothing is staged or committed, and every check ran in the foreground. Changed files: `src/cljc/yin/repl/ast_index.cljc` and `test/yin/repl/ast_index_test.cljc`. `src/cljc/yin/repl.cljc` was not touched this round.

## Fix
- **Held-row conflict check restored.** It runs after the content-address check (`dao.jing/segment-matches?`).
  - An incoming row whose address is already held must equal the held row, metadata included at every depth. Otherwise the indexer fails with `{:stage :packet :reason :address-conflict :id <addr>}` and nothing from the packet is merged.
  - Identical rows observed again still merge.
- **Within a packet** no new code was needed. The r2 duplicate check refuses any repeated address in one packet, so a metadata-distinct pair is refused there as `:duplicate-address`. A new assertion pins this.
- **The comparison is a private `same-value?` in `ast_index.cljc`.** It mirrors `yin.vm.macro`'s private `same-value?`.
  - I did not reuse that function because it is private, the round is limited to these files, and cross-namespace `#'` private access fails at runtime on CLJD.
  - **Duplication:** there are now three copies of this comparison (`yin.vm/same-meta?`, `yin.vm.macro/same-value?`, and this one). Making `yin.vm.macro/same-value?` public would be a one-word change outside this round's files and would let this copy go.
- The r2 content-address (`:address-mismatch`) and duplicate (`:duplicate-address`) checks are unchanged. The namespace and function docstrings are updated.

## Test (written first)
The new case "an address already holding a metadata-distinct row" in `a-malformed-packet-leaves-the-relations-unavailable` uses `yin.vm.macro-test`'s fixture: `[:literal x]` with `{:line 1}` versus `{:line 2}` metadata, which share one address.
- **On the old code, 4 failures:**
  - no failure was recorded
  - the relations were not nil
  - the held row had been **silently replaced**: its metadata read `{:line 2}` instead of `{:line 1}`
- **After the fix:**
  - `:address-conflict` names the address
  - the relations are nil
  - the held row keeps `{:line 1}`
  - the same row observed twice merges
  - the same pair inside one packet is refused
- **Added after the mutation run below:** a nested-metadata case (`x` whose `{:doc d}` metadata carries `d` with `{:line 1}` versus `{:line 2}`). The rows share one address and are refused as `:address-conflict`.

After the fix: 13 tests, 138 assertions, 0 failures.

## Mutation proof (each applied, tests run, source restored and confirmed by grep)
| Mutation | Result |
|---|---|
| plain `=` instead of `same-value?` | 4 failures |
| held-row check disabled | 4 failures |
| recursion into metadata-of-metadata removed | first run: **survived**, 0 failures (the fixture differs only in top-level metadata). After adding the nested case: 1 failure, in that case |

## Verification (foreground)
| Check | Result |
|---|---|
| kondo (the 3 slice files) | 0 errors, 0 warnings |
| Focused JVM (`yin.repl.ast-index-test`, `yin.repl-test`) | 53 tests, 361 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2303 tests, 51108 assertions, 0 failures, 0 errors; "Testing yin.repl.ast-index-test" appears |
| `bb test:cljd` | "+2265: All tests passed!" |

The CLJD total is the same as r3 because this round added assertions to an existing deftest, not new deftests. The freshly compiled `ast-index-test_test.dart` contains the new cases, and r2's added-failing-test run already showed this namespace runs under CLJD.

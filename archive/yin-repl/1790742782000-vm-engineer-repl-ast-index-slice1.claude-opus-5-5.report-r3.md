Completed-GMT: 2026-09-30 05:41:13 GMT
Completed-Local: 2026-09-30 12:41:13 +07
Coding-Agent: claude
Session-ID: 771d73b8-33cb-4277-815e-2b72fe699c1c

# Report r3: fix round 2 (owner addition: per-round AST-index warning)

The warning is added and every required lane is green. The tests were written first and failed on the old code. Nothing is staged or committed, and every check ran in the foreground.

Changed files: `src/cljc/yin/repl/ast_index.cljc`, `src/cljc/yin/repl.cljc` and `test/yin/repl/ast_index_test.cljc`. No existing `yin.repl` test needed changes, because healthy rounds print exactly what they did before.

## What changed
- **`ast-index/notice [indexer]`** returns nil when the indexer is healthy. Otherwise it returns one line:
  - lost (reader gap): `Warning: the AST index lost a program batch; $ast and $occ are unavailable until (reset)`
  - failed: `Warning: the AST index refused a program (<cause>); $ast and $occ are unavailable until (reset)`
    - `<cause>` is the failure `:reason`: `shape`, `duplicate-address`, `invalid-rows` or `address-mismatch`.
    - A thrown derivation error names its stage instead, `derive`.
    - The text never includes a host exception message, so it is the same on every host and VM.
- **`yin.repl`:** `with-index-notice` now takes a list of notices and joins the non-nil ones with newlines. `eval-program` passes the code-index notice first, then the AST-index notice.
  - When both indexers are unhealthy, the code-index line always comes first.
  - The warning uses the same placement as the code-index warning: it is added to rounds that forwarded a program, after the evaluation's answer.
  - Because the warning comes from the indexer's state, not from what changed this round, it repeats every round until `(reset)` or `(vm ...)` rebuilds the session.

**Judgement call to confirm:** a round whose macro expansion fails forwards no program, so it carries no AST warning. The code-index warning behaves the same way, and I followed that placement. If "each round" should include failed-expansion rounds, that is a one-line change.

## Tests (ast_index_test): written first; 10 failures on the old code
- **Gap test:** both rounds read `"3\n<lost warning>"` and `"5\n<lost warning>"`, and the first round after `(reset)` is plain `"3"`.
- **Malformed packet in the session:** two consecutive rounds each carry `refused a program (shape)`, and `(reset)` then gives plain `"3"`.
- **New `a-refused-packet-names-its-cause-in-the-warning`:** an identical-duplicate packet gives `(duplicate-address)`.
- **New `healthy-rounds-carry-no-warning-on-every-vm`:** `["3" "5"]` on all four VMs.
- **New `the-lost-warning-is-identical-on-every-vm`:** the exact same text on all four VMs.
- **New `both-indexers-unhealthy-warn-code-index-first`:** the lines are the answer, then the code-index warning, then the AST-index warning, and nothing else.

After the fix: 13 tests, 129 assertions, 0 failures.

## Mutation proof (each applied, tests run, source restored and confirmed with `git diff --stat`)
| Mutation | Result |
|---|---|
| no warning when lost | 7 failures |
| no warning when failed | 3 failures |
| AST warning placed before the code-index warning | 2 failures, in the ordering test |

## Verification (foreground)
| Check | Result |
|---|---|
| kondo (the 3 files) | 0 errors, 0 warnings |
| Focused JVM (`yin.repl.ast-index-test`, `yin.repl-test`, `yin.repl.index-test`) | 63 tests, 450 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2303 tests, 51099 assertions, 0 failures, 0 errors; "Testing yin.repl.ast-index-test" appears |
| `bb test:cljd` | "+2265: All tests passed!" |

The CLJD total is 2261 from r2 plus exactly the 4 new deftests, and the freshly compiled `ast-index-test_test.dart` contains them. So the new tests ran under CLJD, including the check that the warning text matches across VMs.

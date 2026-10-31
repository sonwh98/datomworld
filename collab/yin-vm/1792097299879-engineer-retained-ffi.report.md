Completed-GMT: 2026-10-15 20:48:19 GMT
Completed-Local: 2026-10-16 03:48:19 +07
Coding-Agent: codex
Session-ID: 01a14147-dc34-77a0-9910-87b58a635f1f

# G2: retained FFI refused before backend attach

Implemented, uncommitted and unstaged on `srvm-retained-ffi`.

## Diff summary

- `src/cljc/yin/vm/semantic/layout.cljc`: `lower-defect` scans the decoded task's wait rows (task slot 13) for a pending row whose reason is `:ffi-request`. It returns `{:yin.k/status :yin.k/unsupported :yin.k/kind :retained-ffi}`; the existing `lower-task` gate throws that refusal before invoking any attacher.
- The predicate follows `rows/pending-node`: a `:put` wait with `:request-sent true` becomes `:ffi-request`, carrying the retained envelope and response stream/cursor. `rows` restores those fields, and the semantic kernel honours the carried pair when re-parking. The backends still use their own pair; no seam was built and their fragment was not widened. An ordinary `:ffi` response pending is admitted.
- `test/yin/vm/semantic/layout_test.cljc`: one refusal test for each backend, each exercising row-4-shaped tasks lifted from all three lanes. Tests assert the runtime wait shape, decoded pending kind, exact `lower-defect` and `lower-task` refusals, and zero attachments. Positive controls check that a non-retained FFI response wait has no lower defect, lowers, attaches, and completes with `"echo!"` on each backend.
- `test/yin/vm/semantic/safepoint_harness_test.cljc`: asserts the exact refusal and zero attachments for all four ordered transfers excluded by row 4's `unlowerable` entries. The table and its meaning are unchanged; the quick matrix remains 16 law-4 cases and 92 law-5 transfers. Slow matrix counts were not rerun.
- Documentation: one sentence updated in continuation §4.6 (explicitly referring to limit 5), and one in UCF §7.11, recording the new retained-FFI gate while preserving the remaining harness-only exclusions.

Tracked diff: 5 files, 72 insertions, 6 deletions. This report is an additional untracked file.

## Validation

| Check | Result |
| --- | --- |
| `clojure -M:kondo --lint src/cljc/yin/vm/semantic/layout.cljc test/yin/vm/semantic/layout_test.cljc test/yin/vm/semantic/safepoint_harness_test.cljc` | 0 errors, 0 warnings |
| Requested five-namespace focused JVM quick run | 123 tests, 4,041 assertions; 0 failures, 0 errors; exit 0 |
| Same focused run after restoring the mutant | 123 tests, 4,041 assertions; 0 failures, 0 errors; exit 0 |
| `clojure -M:test -e :slow` with unrestricted loopback | 4,011 tests, 249,720 assertions; 0 failures, 0 errors; exit 0 |
| `git diff --check` | clean |

Focused command:

```sh
clojure -M:test -e :slow -n yin.vm.semantic.layout-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.debruijn.register-test -n yin.vm.debruijn.stack-test -n yin.vm.semantic.effects-test
```

The first whole-tier attempt stopped at namespace loading with missing generated `yang.python.antlr.gen.Python3Lexer` classes. Ran `clojure -M:antlr-gen` to generate only build artifacts under `build/antlr/python3`; its existing source and generated digest checks passed. The subsequent whole-tier run above passed. No manifest or pinned digest was changed. No standalone Node or Dart lane was run.

Logs: `/tmp/retained-ffi-kondo.log`, `/tmp/retained-ffi-focused.log`, `/tmp/retained-ffi-focused-restored.log`, `/tmp/retained-ffi-quick.log`, `/tmp/retained-ffi-antlr.log`.

## Mutant proof

Before implementation, the new backend tests failed: 14 layout tests, 2,444 assertions, 18 failures, 0 errors. Each backend accepted the retained task and attached two streams instead of refusing.

After implementation, temporarily removed exactly the four-line retained-FFI condition/result branch from `lower-defect` and ran:

```sh
clojure -M:test -e :slow -n yin.vm.semantic.layout-test -n yin.vm.semantic.safepoint-harness-test
```

Result: 38 tests, 3,665 assertions, 26 failures, 0 errors; exit 1. Eighteen failures were the six source/receiver cases' missing lower defect, missing thrown refusal, and nonzero attachments; eight were the harness's four excluded transfers accepting and attaching. The positive controls did not fail. Log: `/tmp/retained-ffi-mutant.log` (initial red: `/tmp/retained-ffi-red.log`). Restored the exact saved source before the successful focused and whole-tier runs.

No changes under `src/yang` or `test/yang`; no pinned hash, golden, or fixture moved. No stage, commit, or backend kernel change. Pre-existing untracked collaboration inputs/log were left in place.

READY

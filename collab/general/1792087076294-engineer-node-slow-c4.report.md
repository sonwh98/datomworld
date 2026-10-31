Completed-GMT: 2026-10-15 17:57:56 GMT
Completed-Local: 2026-10-16 00:57:56 +07
Coding-Agent: codex
Session-ID: 01a140b5-23ed-7e51-9533-80695cb4c2a8

# Node slow-C4 float64 fixture fix

Task: `collab/1793340000000-engineer-node-slow-c4.prompt.md`.
Architect ruling: `collab/1793330000000-architect-node-slow-c4.report.md` (RULING-READY).
Completion timestamps and report filename use the observed host clock, which is earlier than the brief's creation timestamp.

## Completed steps 1–4

1. Confirmed the existing worktree is on `srvm-node-slow-c4-floats` at `47249651`, the requested starting revision. No branch reset was needed.
2. Applied F1 exactly: the three large float-mod rows now call `py/mod` with `{:py/float ...}` operands and expectations. Removed the reducer's float-mod exception; every row now passes through `(ops/exact-literals (with-float64 f))`.
3. Applied F2: added the `dao.jing.cbor` require and the non-slow `float64-literal-beyond-2-53-loads-on-every-host` test, including the `#?(:cljs ... :default nil)` unsafe bare Number refusal assertion. The JVM confirmed the supplied address `:segment/blake3-d034b2a44fee49a5f13bf6d2a6d186cb265f567d932d144939d3587461ed46b6`; no re-pin was needed.
4. Ran the foreground JVM checks below. Used `-i :slow` per build-n-test.md so the tagged, guarded fixture body actually ran.

## Verification

| Command | Result |
| --- | --- |
| `clojure -M:kondo --lint test/yang/python/antlr/prelude_parity_test.cljc test/yin/vm/semantic/load_paths_test.cljc` | Exit 0; 0 errors, 0 warnings (0 new findings). |
| `clojure -M:test -n yin.vm.semantic.load-paths-test` | Exit 0; 3 tests, 130 assertions, 0 failures, 0 errors. |
| `DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n yang.python.antlr.prelude-parity-test` | Exit 0; 9 tests, 116 assertions, 0 failures, 0 errors. |
| `git diff --check` | Exit 0. |

The slow run includes `prelude-semantics-on-every-vm-test`, whose four result assertions cover `:ast-walker`, `:semantic`, `:stack`, and `:register`; all passed on the JVM host. Counts above are namespace totals, not per-VM totals.

## Diff and handoff

Tracked diff: two test files, 24 insertions and 7 deletions. No `src/` or docs edits. No other `test/yang` edits; the optional int_ops_test docstring trim was omitted. No Node or Dart commands were run; those checks remain with the orchestrator. No staging or commits performed.

Notice to the yang.python orchestrator: `test/yang/python/antlr/prelude_parity_test.cljc`, one of the ten C4 files, was edited under the owner freeze as authorized by this task: three fixture rows and one reducer branch only. The uncommitted diff is ready for review and the orchestrator's Node/Dart validation.

READY

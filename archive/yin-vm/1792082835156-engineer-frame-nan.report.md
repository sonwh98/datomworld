Completed-GMT: 2026-10-15 16:55:12 GMT
Completed-Local: 2026-10-15 23:55:12 +07
Coding-Agent: codex
Session-ID: 01a14071-bca0-7112-900c-0bf7253bbc7b
Branch: srvm-frame-nan @ 6dceede9 (uncommitted)
Prompt: ./collab/1793310000000-engineer-frame-nan.prompt.md

# Frame NaN equality follow-up

## Exact changes

- `src/cljc/yin/vm/semantic/frame.cljc`: require cbor and values; add private `bindings=`. Compare symbol key sets, then each value using cbor/content=, with identical-value shortcut and the same fn?/closure?/continuation?/stream-ref/cursor-ref/cell-ref host-equality guards as private data/data-content=. Replace the free-env map comparison only. Lexical-name subtraction and required-name checks remain intact.
- `src/cljc/yin/vm/ucf/rows.cljc`: the other same-bug site is decode*'s row-order check (original line 928). Ordered slots contain literal runtime data; whole-vector host equality can reject NaN in reordered map/binding/set rows. Compare corresponding slots with cbor/content=; ordered-slots preserves length by construction. All wire slots are encodable data here; no callable/ref guard is needed.
- `test/yin/vm/semantic/frame_test.cljc`: one portable test, 30 assertions across wait/captured/parked. Fresh (* 1.0 ##NaN) versus literal NaN and nested NaN validate; NaN versus 1.0, different symbol key, and +0.0 versus -0.0 refuse. Four opaque fn/reference cases per context remain accepted.
- `test/yin/vm/ucf/rows_test.cljc`: one portable test, six assertions, exercising binding/map/set rows carrying NaN; successful decode and stable re-emission addresses. Collection rows carry cursor references to meet the existing reference-free-row rule.
- `docs/agents/known-gaps.md`: close only the frame equality row with this report's evidence (uncommitted slice; no commit fabricated).

Tracked diff: 5 files, 71 insertions, 5 deletions (plus this new report). Per-file +/-: frame.cljc 21/3; rows.cljc 3/1; frame_test.cljc 32/0; rows_test.cljc 14/0; known-gaps.md 1/1.

## Remaining comparisons: not the same bug

Survey: host `=`/`not=` in frame.cljc, rows.cljc and rows/checkpoint.cljc.

- frame: state/context/op/frame-type/slot keywords, body indices, register destinations and closed delivery maps containing only keywords/register ids, presence booleans, and symbol key sets. The guarded opaque-value host comparison intentionally matches data-content=.
- rows: byte comparison at original 113–115 compares encoded bytes, not runtime values. Descriptor/kind/tag/reason/policy/class/status/profile/version checks, row counts, work counters, cursor-cell numbering sets, row reachability sets, row ids/addresses, module names/binder vectors, link ids, parked ids and custody role/occurrence/lease/holder/arbitration identities are not runtime payload comparisons. In particular original 1853 compares ledger/module content addresses; 1957–1974 checks custody identity and sequence fields.
- rows original 811 checks cardinality of host-semantic decoded map/set keys; 931–932 checks wire-key cardinality plus encoded-key byte cardinality. These duplicate-key rules are not an unchanged value's canonical re-projection equality check; left intact. Other distinct/set uses concern graph labels, cursor cells, ordering, or reachability.
- rows/checkpoint.cljc: only host equality is the :task kind check (line 77).
- Custody payloads are already digested from canonical bytes in custody-baseline; origin and op-id occurrence comparisons already use cbor/content= (original 1191/1237).

## Verification

- Focused requested JVM command: `clojure -M:test -e :slow -n yin.vm.semantic.frame-test -n yin.vm.semantic.vm-test -n yin.vm.semantic.effects-test -n yin.vm.ucf.rows-test`: **116 tests, 1380 assertions, 0 failures, 0 errors**, exit 0. Log: /tmp/frame-nan-focused-final.log.
- Earlier development runs exposed malformed new fixtures (missing/unused register inputs, non-task root, reference-free collection row); corrected before the successful run. No production workaround for those fixture errors.
- Mutant: a temporary /tmp script used with-redefs-fn on private bindings= to restore host `=` for exactly the same post-dissoc maps; ran only free-env-compares-values-by-content. **1 test, 30 assertions: 21 pass, 9 fail, 0 errors**. In each context, fresh NaN acceptance and nested NaN acceptance fail, and signed-zero refusal fails. Mutant is scoped/restored in memory; source remains fixed. Log: /tmp/frame-nan-mutant.log.
- This JVM did reproduce the fresh-box failures. Do not infer a shared-box cross-host guarantee: shared-box shortcuts can mask host NaN defects on JVM paths. A separate shared-box map probe here returned false; Node/Dart are the authoritative portable lanes and are assigned to the orchestrator. Neither lane was run in this task.
- Full requested JVM quick tier `clojure -M:test -e :slow`: **4007 tests, 249662 assertions, 0 failures, 0 errors**, exit 0. Initial attempt stopped before tests with missing generated Python3Lexer; generated pinned parser using `bb gen:python-antlr` (build/ only) and built the required Node REPL peer with `bb build:yin-repl-node` (exit 0; existing namespace/var clash warning in linker/head/board.cljc). The exact quick command passed after both builds; /tmp/frame-nan-quick-final.log. Five documented JVM codec N/A cases were skipped, plus two Dart-peer checks because build/ws-project-peer and build/yin-repl-peer are absent. No Node/Dart lane result is claimed.
- Final kondo on all four changed .cljc files: **0 errors, 0 warnings**, exit 0 (4732 ms). Log: /tmp/frame-nan-kondo.log. `git diff --check`: passed.

No docs/design, src/yang, or test/yang edits. Nothing staged or committed.

READY

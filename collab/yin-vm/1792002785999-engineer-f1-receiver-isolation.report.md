Completed-GMT: 2026-10-14 18:33:05 GMT
Completed-Local: 2026-10-15 01:33:05 +07
Coding-Agent: codex
Session-ID: 01a13baa-4e66-71b2-8355-62da1bad118f
# F1 receiver isolation — QA & Verification

Implemented UNCOMMITTED on `srvm-phase8-f1`, base `f9d15d36`. Nothing staged or committed. No production change remains; no `test/yang/` file touched.

## Test

Added one portable `.cljc` deftest, `lowering-isolates-task-state-from-a-populated-receiver`, in `test/yin/vm/ucf/rows_test.cljc:1089` (88 assertions).

- Blocked reader built with rows-support AST/machine helpers: defines `reader-owned` as 7, then blocks on its first source read. After feeding the source, it returns "B".
- Install parent from `support/parked-installer`: its live `host.mod` child waits on a source read, then defines the exported function. After feeding the child source, the parent finishes with 42.
- Both packs come from `support/lift`. Each is lowered into a fresh receiver and into a receiver with its own loaded code, store, module store, parked record, install record, wait entry, ready queue and stream resource.
- Assert exact equality of `:code`, `:store`, `:module-stores`, `:parked`, `:installs`, `:wait-set`, and `:ready-queue`, plus explicit absence of receiver-only entries. The install comparison includes the whole child VM; the seven fields are also compared directly at the child's path. The receiver fields are asserted different from the clean lower before checking replacement.
- Shared descriptor attachments make the install VM records directly comparable. `:make-stream nil` prevents construction of unrelated call streams during lowering; `:attach-stream` supplies the same composition attachment operation for subsequent module completion. No normalization, relift, private var access, or host-specific branch masks field differences.
- Resume both clean and populated lowers after feeding their waiting source, assert halted state and the expected value. An additional reader lower occupies the exact resource key the clean task uses, asserts allocation around it, and also resumes to "B".

## Resource contract

Resources are merged by design, not replaced wholesale. The tested populated receiver retains its exact resource handle at `vm/call-in-stream-key`. The reader collision receiver retains its handle at the clean task's usual stream key. The lowered reader's stream and cursor-cell keys are present in its resource table and disjoint from the receiver's keys; the collision case proves that a key already occupied by a receiver stream is neither reused nor overwritten by the task's fresh allocations. The install child likewise has its own restored stream/cursor resources and clean task state.

More precisely, `restore-task` reserves the fixed composition keys and the existing keys of the receiver resource table for each task it restores, then allocates fresh stream and cursor-cell keys in that task's resource scope. Child tasks are spawned into fresh VM state; this does not promise global key uniqueness across parent and child tables. Fixed link request/response aliases may be assigned carried handles when link waits are restored. Other receiver composition keys remain according to the existing implementation. This test does not claim that lowering removes all resources, resets every receiver key, or replaces its entire module registry.

No receiver-isolation defect found; production code was not changed.

## Verification (foreground JVM)

`clj -M:kondo --lint test/yin/vm/ucf/rows_test.cljc`: 0 errors, 0 warnings.

`clojure -M:test -e :slow -n yin.vm.ucf.rows-test -n yin.vm.ucf.rows-custody-test -n yin.vm.ucf.rows-checkpoint-test -n yin.vm.semantic-register.safepoint-harness-test`: 88 tests, 2,136 assertions, 0 failures, 0 errors.

Focused command: `clojure -M:test -n yin.vm.ucf.rows-test -v yin.vm.ucf.rows-test/lowering-isolates-task-state-from-a-populated-receiver`: 1 test, 88 assertions, 0 failures, 0 errors, including a rerun after all mutants were restored.

Node/Dart execution was not run; this task requested the JVM gate. The added test uses portable builders and public APIs, `(assoc {} ...)` for constructed maps, short vector iterations and no new reader conditionals.

## Mutants

A `cp` backup of `src/cljc/yin/vm/ucf/rows.cljc` was taken at `/tmp/datom-f1-rows.cljc.backup`. Each mutant was applied separately to `restore-task`, run with the focused command, and restored with `cp` in a `finally` block. After every restoration, both `diff -u` against the backup and `git diff --quiet -- src/cljc/yin/vm/ucf/rows.cljc` passed.

| Mutation | Result | Failing isolation assertion |
|---|---|---|
| Keep `(:parked recv)` instead of `parked'` | exit 1; 5 failures, 0 errors | `:parked equals a fresh receiver's lower`; stale receiver parked record survives |
| Keep `(:store recv)` instead of `store'` | exit 1; 5 failures, 0 errors | `:store equals a fresh receiver's lower`; `receiver-only :wrong` survives |
| Keep `(:installs recv)` instead of `installs'` | exit 1; 12 failures, 2 errors | `:installs equals a fresh receiver's lower`; receiver install survives and the required child is lost |

The install mutant's two follow-on errors are attempts to feed the now-missing child's stream; direct field assertions already fail independently. Mutant logs: `/tmp/datom-f1-mutant-parked.log`, `/tmp/datom-f1-mutant-store.log`, `/tmp/datom-f1-mutant-installs.log`.

`git diff --check` passed; the index is unchanged. The final tracked diff is confined to `rows_test.cljc`; this report is a new untracked file. Pre-existing prompt, sign-off and session stdout files were left alone.

READY

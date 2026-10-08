I’ll read the architectural contracts, inspect the migration against the stated master baseline, and verify the descriptor hash and host boundaries. The review will be read-only.

The migration is four uncommitted files on top of `1275a5df`. The numeric classifier now keeps integers and doubles distinct on JVM and native Dart. I’ll check two remaining boundary questions: whether JS policy still enters the hashed descriptor, and how the reader handles foreign integral-double records.

The JVM suite passed: 78 tests, 350 assertions. Two concerns remain: JS safe-integer policy is still embedded in the canonical descriptor, and the reader has no explicit numeric type tag for foreign literals. I’m checking the latter with a JVM-generated double record in Node.

Node also passed: 78 tests, 333 assertions. The cross-host probe found a contract gap: Node rejects a valid JVM `1.0` record as `:hash-mismatch`, rather than the documented `:unsupported-value`, because it reclassifies the value as `:int64`. The record is safely rejected. I could not rerun Dart within the read-only constraints because its test launcher attempts to update `pubspec.lock`.

Completed-GMT: 2026-09-23 17:31:38 GMT
Completed-Local: 2026-09-24 00:31:38 Asia/Ho_Chi_Minh

Reviewed the four-file working-tree migration against `1275a5df`. No files changed.

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| Medium | [debruijn.cljc:91](/Users/sto/workspace/worktree-debruijn-type-preservation/src/cljc/yin/vm/debruijn.cljc:91) | **Retained host-boundary violation:** `canonical-value-table` still contains `:numbers :javascript` safe-integer policy. The descriptor embeds this table, so JS adapter policy participates in every host’s dimension identity. Isolation is therefore incomplete despite moving classification into `js-number-class`. | Move JS admission policy outside the canonical descriptor. Recompute the dimension hash and fixtures, retaining contract version **1**. |
| Medium | [debruijn.cljc:1339](/Users/sto/workspace/worktree-debruijn-type-preservation/src/cljc/yin/vm/debruijn.cljc:1339) | **Foreign-type refusal contract is unmet:** a JVM-generated literal-double `1.0` record becomes `:int64` when read in Node and produces `:hash-mismatch`, rather than the promised `:unsupported-value`. Reproduced directly. Rejection prevents corrupted acceptance, but loses the distinction between unsupported representation and invalid content. | Preserve the incoming numeric class through host decoding/admission; reject unsupported classes there with `:unsupported-value`. Add JVM-to-Node fixtures for integral doubles, including nested values. |
| Low | [yin.vm.debruijn-projection.md:196](/Users/sto/workspace/worktree-debruijn-type-preservation/docs/design/yin.vm.debruijn-projection.md:196) | Documentation names `:yin.debruijn/contract-version`; the descriptor correctly uses `:dim/contract-version`. | Correct the documented attribute. |

Properties that passed review:

- The descriptor contains `[:yin.debruijn/dimension :dim/contract-version 1]`.
- Its current canonical encoding correctly produces `90a5235794c9eac968490d343633e30c68d1a96093125df7ad1ac3c7398a43d4`.
- `:integral-double-folding` is `false`; `:int64-integral-double-collision` is removed. JVM and native Dart classification preserve integer/double distinctions.
- Architecture B remains intact: execution derives from named datoms and lossless resolved tuples. Projected records remain identity/cache data; binder names and tail flags remain excluded.
- The migration introduces no new shared state, callbacks, scheduling, or execution coupling.

Validation: JVM **78 tests / 350 assertions**, Node **78 tests / 333 assertions**, all passing. Node’s cached source differs only in docstrings. Dart implementation was inspected, but runtime parity remains unverified in this review: its launcher requires a prohibited `pubspec.lock` timestamp update.

Final architectural verdict: **[REQUEST CHANGES]**.
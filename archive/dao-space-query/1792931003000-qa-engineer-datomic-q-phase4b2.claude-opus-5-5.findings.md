Completed-GMT: 2026-10-25 13:23:21 GMT
Completed-Local: 2026-10-25 20:23:21 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

The three cursor docstring notes and 4b-2 (single-node reader plus open-time descriptor) are done, and everything passes. As asked, the cursor is not wired into query evaluation and admission is unchanged, so bounded calls still refuse published sources. `cursor.cljc` changed in its docstring only.

## Cursor docstring notes
1. **Strictness premise.** The cross-leaf strictly-increasing check is strict because persisted trees come from sorted sets, which cannot hold comparator-equal elements, so comparator-equal neighbors are unrepresentable. The docstring says a future editor must not weaken it to non-strict, and must not extend it to formats (such as multiset blobs) where it would falsely reject.
2. **Branching factor.** The docstring now says `start` does not retain it at all (accepted in the descriptor and ignored), not "retained metadata only".
3. **Satisfied address.** It documents that only the most recent satisfied address is remembered. A stale response naming an earlier one, when nothing is pending, classifies as `:unexpected-response` rather than `:already-satisfied`; both reject, so the distinction is diagnostic only.

## 4b-2
- **`read-node-blob` (`storage.cljc`).** It performs exactly one `jing/get`, which hash-verifies and decodes, and returns the decoded blob. An absent address throws "missing index segment" with `{:address a}`. Any failure `jing/get` raises propagates unchanged. It builds no node, traverses nothing, caches nothing, opens nothing and retries nothing. The stale "optional verification" docstring is corrected: `jing/get` always verifies, and `:verify?` is the separate, optional re-serialization check that is not host-stable.
- **Descriptor (`query.cljc`).**
  - **Shape:** `published-db` now returns `:dao.space.query/published-read {:version 1 :store store :manifest-address a :manifest m}`. The manifest is the one the constructor already read, so there is no extra read.
  - **`open-published!`:** it explicitly carries the field through its map reconstruction, the hazard the design flagged. It sits beside `:store` and `:close-guard`, which are unchanged.
  - **Unchanged:** the serializable coordinate, the persisted manifest and the borrowing and ownership behavior.

## Tests
- **`storage_read_test.cljc`** (5 tests, 17 assertions):
  - One get per call, counted by a wrapper store.
  - The blob equals what the storage restores.
  - A second call is a second get, and children are read only when asked.
  - A missing segment throws with its address after one get.
  - A forged read propagates `jing/get`'s failure after one get.
  - A non-segment address is refused before any get.
- **`published_read_test.cljc`** (5 tests, 34 assertions):
  - Descriptor shape on `published-db`, with the store identical to the one passed in.
  - Exactly one manifest get and zero node reads at construction.
  - The coordinate and persisted manifest are unchanged, and the deferred rows are not forced.
  - `open-published!` preserves the descriptor; its store is the opened handle.
  - Results and error data never mention the descriptor, including core refusals and the bounded adapter refusal, which reads nothing.
  - A value with the descriptor removed still queries identically unbounded.

## Verification
| Command | Outcome |
|---|---|
| `clj -M:test -n dao.data.btree.cursor-test` | 17 tests, 1227 assertions, 0 failures |
| `clj -M:test -n dao.data.btree.storage-read-test` | 5 tests, 17 assertions, 0 failures |
| `clj -M:test -n dao.space.published-read-test` | 5 tests, 34 assertions, 0 failures |
| `clj -M:test -n dao.space.query-test` | 72 tests, 282 assertions |
| `clj -M:test -n dao.space.index-test` | 56 tests, 531 assertions |
| `clj -M:test -n dao.data.btree-durability-test` | 22 tests, 773 assertions |
| dht / transact / transactor tests | 46/36764, 8/26, 16/100 tests/assertions |
| conformance / datomic-execution tests | 17/41 and 22/190 |
| Node lane `bb test:sub:cljs dao.data dao.space` | 25 namespaces, 526 tests, 0 failures |
| Dart lane via `cljd_agg --only` (cursor, storage-read, durability, published-read, query, index, datomic-execution) | `All tests passed!` (+198) |
| kondo on all changed and new files | 0 errors, 0 warnings |
| `bb check:node-discovery` | `Node discovery OK: 229 source files outside test/` |

All the counts above are from this seat. `git status` for my work shows `storage.cljc` and `query.cljc` modified and four new files: the cursor, its test, and the two tests above. The other entries (docs, collab, ble, blog) are not mine.

## Carried forward to 4b-3
The reviewer's P2 condition belongs in 4b-3's acceptance. A malformed-node throw from the cursor in the middle of a traversal must surface as the adapter's `:source-failed`, with the cursor error preserved as `ex-cause`. That proves the interpreter brackets `step` and `supply` with the same preserve-cause discipline as `read-step`.

## Concern
The Dart lane for the whole `dao.data` subsystem still fails because of the JVM-only `sorted-user-type-test` namespace. That predates this work, and I used `--only` again.

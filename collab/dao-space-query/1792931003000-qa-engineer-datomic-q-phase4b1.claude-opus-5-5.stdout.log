Completed-GMT: 2026-10-25 13:04:51 GMT
Completed-Local: 2026-10-25 20:04:51 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

The pure stepped cursor is implemented and its tests pass on all three hosts. The one failure is `bb test:sub dao.data` on Dart, which fails before running anything because that subsystem selects a JVM-only test namespace. I worked around it by running the Dart lane directly with `--only`. I touched no existing B-tree traversal, blob format, storage or dependency, and nothing is staged or committed.

## Files (exactly two new)
- `src/cljc/dao/data/btree/cursor.cljc`
- `test/dao/data/btree/cursor_test.cljc`

## Cursor, per r7 as amended by r8
- **Interface.** `(start descriptor comparator)` pins the comparator for the cursor's life, `(step cursor)` takes the cursor alone, and `(supply cursor response)` takes the response. Outcomes are `:continue`, `:need-node`, `:item` and `:done`. `start` does no I/O. An empty tree is `:done` with zero requests.
- **Protocol.**
  - **Single pending request:** a waiting cursor re-steps to the same request with no progress.
  - **Supply rejections:** `supply` throws a structured error for no pending node (`:unexpected-response`), a different address (`:address-mismatch`), and a second response to a satisfied request (`:already-satisfied`).
- **Traversal.**
  - **Transitions:** root request, chunked validation, enter (seek by child maxima, else leftmost), one-key yield, frame ascent with one `:continue` per pop, next-sibling request, upper-bound stop, and a full walk when no bounds are given.
  - **Bounded work per step:** each step does constant work. Validation examines at most 32 keys (`validation-chunk`), and a seek is a binary search of at most 32 comparisons.
  - **No implicit reads:** the cursor holds no storage handle and never calls `resident-root`, `node-child`, `slice` or `seq` on a set. The namespace has no `:require`.
  - **Path frames:** frames are immutable maps of branch blob, address, level and selected position. They hold no storage handle, execution state or clock.
- **Validation: the r8 list only.**
  - **Shape and addresses:** leaf and branch shapes, key/address length match, and every child address present.
  - **Ordering and levels:** keys strictly ordered under the pinned comparator, and child levels strictly decreasing (leaves are 0).
  - **Separators:** child maximum equals the parent separator under the comparator, not by content identity.
  - **Added check:** yielded keys must also be strictly increasing across leaves.
  - **Occupancy:** I did not validate occupancy, fill or key counts.
  - **Failures:** all failures are `ex-info` with `:dao.data.btree.cursor/error` set to `:malformed-node`, `:inconsistent-level`, `:unordered-keys` or `:separator-mismatch`.
- **Docstring.** It carries the r8 sentinel table in canonical `[e a v t m]` positions, the caller-owned prefix-stop rule, continuing across comparator-tied values, content-strict filtering afterwards, and the ban on nil-ing fields to invent an upper key.

## Tests (17 tests, 1227 assertions on the JVM)
- **Shapes and bounds.**
  - **Shapes:** empty, single-leaf and multi-level trees at bf 4/5/8 across 11 sizes.
  - **Seeks:** before the minimum, at a key, between keys and beyond the maximum, with inclusive/exclusive combinations and empty ranges.
  - **Selective seek:** it reads fewer than a quarter of the nodes a full walk does.
  - **Nil bound:** a `{:key nil}` bound is a genuine bound.
- **Oracles.** Exact equivalence with `bt/slice` over persisted fixtures, and with an explicit sorted-filter oracle for every bound shape, across 40 deterministic random trees.
- **Protocol and no-I/O.**
  - **Protocol:** idempotent waiting, all supply rejections, and invalid descriptors.
  - **No-I/O:** the driver's read count equals the requests made, with zero reads from `start`, `step` and `supply`.
- **Malformed nodes.** Shape, ordering, level, separator, a nil node, and keys that are ordered within each leaf but not across leaves. Also a cycle, which fails the level check, and chunked validation of a 1000-key node.
- **Occupancy parity.** Roots, a hand-built single-child spine, one-child branches, and partial final chunks all read fine.
- **Kind-tie fixtures.**
  - **Coverage:** 1 vs 1.0, 1.0M vs 1.00M and 0.0 vs -0.0, across EAVT, AEVT, AVET and VAET at bf 4 and 5. A 41-row tied run spans leaf and subtree boundaries.
  - **Placement:** a tied candidate sits before a matching one, and matching candidates sit immediately before the first out-of-prefix key.
  - **Explicit expectations:** candidates are ids 1..41, the matching set is the odd ids for kind one and the even ids for kind two, and the stop key is id 201.
  - **Cross-checks:** the same output is checked against `bt/slice` plus a prefix stop, and generic inclusive/exclusive bounds around the run are checked too.
  - **Meaningful ties:** a separate test confirms each pair really ties in comparator order and is content-distinct.

## Verification
| Command | Outcome |
|---|---|
| `clj -M:test -n dao.data.btree.cursor-test` | 17 tests, 1227 assertions, 0 failures, 0 errors |
| `bb test:sub dao.data`, JVM lane | 9 namespaces, 106 tests, 0 failures (existing btree suites green) |
| `bb test:sub dao.data`, Node lane | 9 namespaces, 102 tests, 0 failures |
| Dart lane via `bb src/dev/cljd_agg.clj --only` (8 namespaces) | `All tests passed!` (+102) |
| kondo on both files | 0 errors, 0 warnings |
| `bb check:node-discovery` | `Node discovery OK: 229 source files outside test/` |

## Concerns
- **`bb test:sub dao.data` Dart lane fails for an unrelated reason.** The subsystem includes `dao.data.sorted-user-type-test`, a JVM-only namespace that produces no Dart test, and `cljd_agg` aborts with "No generated Dart test". The code compiled ("Compilation succeeded"). The cause is a pre-existing lane-selection issue, not the cursor. The `--only` run above is my Dart evidence, so the orchestrator may want to reproduce it that way or exclude that namespace from the subsystem.
- **Validation granularity.** Chunked validation means a node of n keys costs about n/32 extra `:continue` steps, each of which the query layer will charge as `:tree-step`. That follows the "no hidden loop" rule, but budgets will need recalibrating, as r8 already says.
- **Error shape.** Failures are thrown rather than returned as an outcome, because the four-outcome set has no failure status. If 4b-3 prefers a returned `:failed` status, that is a small change.
- **Git status.** `public/chp/blog/type-blindness-yin-vm-dao-space.blog` is untracked and is not mine.

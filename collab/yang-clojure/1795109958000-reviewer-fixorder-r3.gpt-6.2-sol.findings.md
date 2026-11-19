Completed-GMT: 2026-11-19 17:48:12 GMT
Completed-Local: 2026-11-20 00:48:12 Asia/Ho_Chi_Minh
Role: Reviewer (compiler provenance)
Coding-Agent: codex

# fixorder r3 review

**REMAINING-FINDINGS**

B2 and the JVM/Node/Dart portion of B3 are satisfied. B4 remains open for the set collision trace. B1 remains open with the J2 owner. The Jupiter mirror also needs a correction to its full-suite lane claim.

## Actionable findings

### P2 — B4 set trace cannot distinguish the colliding occurrences

Both colliding operands emit the same `0` event because the test's `tap` logs and returns its argument. The expected trace therefore identifies two zero events in aggregate. In a JVM probe, I used the actual compiled set AST and ran the original, a variant with the two colliding operands swapped, and a variant with one of them duplicated in place of the other. All three produced `[:s1 0 0]` and `#{:s1 0 2}` on the walker, semantic, stack, and register routes. The assertions accept both altered executions. The architect specifically required canonical effect order and exactly-once evaluation of each colliding occurrence. [Set trace](../../test/yang/clojure_collection_order_test.cljc#L1045)

Give each colliding expression its own tap label while keeping both return values equal to `0`. For example, use `(do (tap :left) (- n 1))` and `(do (tap :right) (- 1 n))`. Derive the expected label sequence from the existing independent element-root sort, assert it on all four routes, and retain the final set assertion. I checked this alternative in memory on the JVM. Every route produced `[:s1 :left :right]` for the original, `[:s1 :right :left]` for the swap, and `[:s1 :left :left]` for the duplicate. The result set stayed `#{:s1 0 2}` in each case.

### P3 — The fixture docstring drops a digit from the SHA-256

After removing separators, the docstring yields 63 hex digits. It has `…493784a14525c7cf…` where the independently verified digest has `…493784a145251c7cf…`. The binary and the engineer findings carry the correct digest. Copy the full 64-digit SHA-256 into the docstring. [Fixture docstring](../../test/yang/clojure_collection_order_test.cljc#L1221)

### P3 — Jupiter mirror overstates full-suite lane coverage

The mirror README labels `bbtest.out` as a full quick tri-host run with Node and Dart green. The saved command ends at `Error while executing task: test:clj`, after 5,181 tests, 400,386 assertions, and 11 failures. `bb test` lists the JVM test task before the Node and Dart test tasks, and this log contains no results for those later tasks. The separate `dao.stream` log shows all three lanes passing. Correct the README to scope the full quick result to the JVM attempt, or attach any separately saved full Node and Dart results with their tested revision. [Mirror README](1795110000000-jupiter-g2-gate-evidence/README.md#L8) · [Full quick log](1795110000000-jupiter-g2-gate-evidence/bbtest.out#L1366) · [Task definition](../../bb.edn#L343)

## Dispositions and evidence

- **Scope verified.** `HEAD` is `7e6d720c` and the tracked r3 diff contains only the ordering test (+436/−27) and the restored fixture (11 to 5,403 bytes). The emitter, frontend manifest, callsite suite, cc4 fixtures, J2 inventory and tracked r1/r2 findings match `HEAD`. The active cc4 snapshot still hashes to `093f903a62e780a665ee2da107a1630126385f53b028d14e236031d48a70e5c1`. `git diff --check` passes. The historical raw red log also matches its recorded digest.
- **B2 satisfied.** I decoded the base64 text independently from `HEAD` and the worktree. Both decode to the exact file bytes, SHA-256 `9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2`. On the JVM the decoded payload identifies `c1-1`, root `:segment/blake3-49d9ef3191b305b26e67a803c9e74104dc57d1c5f036147b3fd2352d256f5e64`, and old value `{0 :b}`. The custody test uses NIO, Node `fs`, and Dart I/O behind a `:cljd`-gated require. It compares the bytes, validates the decoded root row, and loads the file's rows to assert the old result without recompiling. [Custody test](../../test/yang/clojure_collection_order_test.cljc#L1322)
- **B3 satisfied on the three evidenced hosts.** I recomputed all 16 pinned shuffle vectors using both unnormalized exact integer arithmetic and normalized Node `Number` arithmetic. The vectors matched at sizes 8, 9, 16, and 32 across all recorded seeds. I also ran the extracted JVM generator for seed `1795017757000` at all four sizes. Its size-8 vector is `[3 0 1 2 5 7 6 4]`, matching the Node recomputation and the architect's JVM column. The first reduction limits every product to at most `103661183027795`, below `2^53`. The 18-case literal bundle pins each root, application count, digest of the full canonical-sorted C1 relation and occurrence-key vector, and digest of the semantic, stack, and register provenance tuple. Every host checks that same bundle and the existing permutation and representation properties still run. [Generator](../../test/yang/clojure_collection_order_test.cljc#L262) · [Reference check](../../test/yang/clojure_collection_order_test.cljc#L1520)
- **Float dependency handling accepted.** The saved first Node run fails precisely at the `0.0`-bearing case with different root and relation/provenance digests. The CBOR profile assigns portable float-kind preservation to the producer. A bare integral JavaScript Number such as positive `0.0` encodes as integer content, while a JVM `Double` encodes as float64. Archiving the failure and keeping non-integral float cases in the shared gate is transparent and within the collection-order bounds. The documented dependency remains open. [Numeric identity contract](../../docs/design/dao.jing.cbor.md#L387) · [Archived Node failure](1795104744000-fixorder-r3-evidence/fixorder-r3-node-order-floats-divergence.log)
- **Other B4 controls satisfied.** The metadata fixture holds the source form constant apart from `{:sem true}` and checks that `{}` gives the plain root. The failure fixtures derive the expected taps using standalone child roots and the canonical comparator. I independently checked that the fixtures exercise `[]` and `[:pre :v]`. Both cases require exact `{:order-test :boom}` on every route. The raw AST fixture captures at lambda entry and invokes the captured continuation from an operand of an assoc-over-seed application. Every route must return `:done` and log exactly `[:early :early-v]`, excluding `:never`. [Metadata](../../test/yang/clojure_collection_order_test.cljc#L673) · [Failure](../../test/yang/clojure_collection_order_test.cljc#L892) · [Transfer](../../test/yang/clojure_collection_order_test.cljc#L1003)
- **B1 remains open.** Both historical Jolt logs show the same four `UNLISTED` namespaces and stop at inventory validation. The r3 delivery records zero Jolt children and contains no simulated comparison. J2 owns inventory reconciliation and the targeted Jolt reruns. [Jolt ordering refusal](1795028800000-fixorder-evidence/fixorder-jolt-order.log) · [Jolt callsite refusal](1795028800000-fixorder-evidence/fixorder-jolt-callsite.log)

## Gate counts

| Evidence | Verified result |
| --- | --- |
| Independent JVM rerun of both namespaces | 61 tests / 92,082 assertions, zero failures or errors, exit 0 |
| Archived JVM ordering and callsite | 15 tests / 91,477 assertions and 46 / 605. Both green, for 61 tests / 92,082 assertions in total |
| Archived Node ordering | Final 15 / 91,477, zero failures or errors. Earlier 15 / 91,477, one float divergence |
| Archived Dart ordering | All 15 named tests passed. The runner reports test count |
| Archived clj-kondo | Zero errors and one existing callsite unused-require warning |
| Jupiter `dao.stream` | JVM 589, Node 579, Dart 509 tests. All three exit 0 |
| Jupiter focused comparison | Detached master 18 / 6,192 and merged branch 18 / 6,193. Both contain the same two failure signatures. The store-write audit expectation and actual maps are equal across the two logs when compared as parsed data |

The archived JVM, Node, Dart and lint logs are under [r3 evidence](1795104744000-fixorder-r3-evidence/). The Jupiter evidence covers pre-r3 revisions. The focused master and branch comparison reproduces both lint failures on master, and the nine arm-violation failures from the earlier JVM quick run are absent in the merged branch comparison. [Jupiter mirror](1795110000000-jupiter-g2-gate-evidence/)

The JVM rerun used the cached test classpath with `java -cp "$(cat .cpcache/3644903311.cp)" clojure.main -m cognitect.test-runner -n yang.clojure-collection-order-test -n yang.clojure-callsite-test`. Node and Dart counts come from the archived ordering runs. This review authored only this findings file.

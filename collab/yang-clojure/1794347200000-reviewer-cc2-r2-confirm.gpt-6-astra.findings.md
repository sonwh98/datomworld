Completed-GMT: 2026-11-10 23:06:46 GMT  
Completed-Local: 2026-11-11 06:06:46 Asia/Ho_Chi_Minh

Reviewed the current files against commit `2d170926`; the six tracked files match. No edits or tests run.

**F1: CONFIRMED-FIXED.**  
[Safepoint:262](/Users/sto/workspace/datomworld-cc2/src/cljc/yang/safepoint.cljc:262) correctly uses `(count (:operands exit))` in both exit branches. Recursive traversal composes the shifts for nested marks. [Output verification:348](/Users/sto/workspace/datomworld-cc2/src/cljc/yang/safepoint.cljc:348) explicitly emits `:unmapped` with `:output-not-an-application`, rather than merely suppressing a row. Thus the erroneous literal coordinate `[3 [3 0]]` would be refused. The tests independently assert the correct coordinates and a positive refusal entry at [test:1560](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1560) and [1620](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1620).

**F2: OBJECTION — P0 remains: transaction-number equality is not admission identity.**  
[Publish:582](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:582) matches only root and `(:t program)` against the origin’s batch number. It ignores source medium and member index. A sole foreign table with the same root and batch number therefore passes.

The drift behavior is also **not reliably fail-closed**. Consider one failed expansion followed by two admissions of identical root R:

- Admission A has batch number `b`, but index transaction `b−1`.
- Admission B has batch number `b+1`, but index transaction `b`.
- Publication of B selects A’s table at batch `b`.

This follows because expansion failures still advance the batch counter ([macro:1662](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/macro.cljc:1662)), while only successful outputs are staged ([macro:1877](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/macro.cljc:1877)). Independently, ordinary name publication consumes an index transaction ([index:513](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl/index.cljc:513)), so divergence is not exceptional.

Claim-free publication when association is genuinely unavailable is acceptable under the stated refusal policy. This implementation cannot establish that association. **Required fix:** retain an explicit indexed-program-to-full-admission mapping and resolve `[admission-origin final-root]` exactly; never infer it from counter equality.

**F3: CONFIRMED-FIXED for the original acceptance holes.**  
[Encoder:325](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/encoder.cljc:325) now rejects malformed row length, invalid origins, unsupported versions, duplicates, multiple origins, and incomplete/extra coverage.

The diagnostic vocabulary is **not fully identical** to `validate-call-site-coverage!`: conflicting versions receive `unsupported-version` rather than `conflict`; nonexistent and non-application targets receive `incomplete-coverage` rather than their dedicated diagnostics ([reference validator:196](/Users/sto/workspace/datomworld-cc2/src/cljc/yang/clojure.cljc:196)). These inputs nevertheless refuse; they do not retain the original acceptance vulnerability.

**F4: CONFIRMED-FIXED within the revised scope.**  
[Query:815](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl/query.cljc:815) forwards the tables, and [DHT:66](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/dht.cljc:66) passes those options into `module-from-index`. The forwarding test captures the actual downstream options.

Deferring the unchanged custody gap to cc3 is acceptable under the explicitly revised scope. [DHT:70](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/dht.cljc:70) still discards `:call-sites`, so this confirms forwarding—not completed end-to-end custody.

**F5: OBJECTION — meaningful improvements, but the admission blocker remains untested.**  
The new safepoint refusal, encoder rejection, and claim-free publication assertions are substantive. However, [the stale-admission test:1696](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1696) changes the batch number to `99`; it cannot detect acceptance of another medium/member with the same number, or the drift collision described in F2. Add those cases and the publication-boundary regression below.

**New finding — P0 | [publish.cljc:659](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:659) | Missing recovered rows are silently reassigned publication origin.**

`recovered-call-site-claim` treats **every uncovered application** as a synthesized wrapper. Removing one retained user-call row from a valid recovered spec therefore passes validation and replaces that occurrence’s origin with publication admission. Setting `:call-site-rows` to `[]` or nil likewise claims every application; presence of the key selects this branch at [289](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:289).

**Required fix:** carry and validate the explicit wrapper occurrence set from assembly. Require recovered rows plus those wrappers to cover the tree exactly, rejecting every other missing occurrence. Test deleted recovered rows and empty/nil supplied tables.

CONFIRMATION VERDICT: REQUEST CHANGES
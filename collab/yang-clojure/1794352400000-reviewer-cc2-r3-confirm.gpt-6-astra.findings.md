Completed-GMT: 2026-11-10 23:22:23 GMT  
Completed-Local: 2026-11-11 06:22:23 Asia/Ho_Chi_Minh

Static review only; no edits or tests run.

**F2: OBJECTION — P0 remains in construction of the admission mapping.**

The lookup at [publish.cljc:585](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:585) now correctly uses the full `[origin root]`. However, [record-call-site-admissions:1263](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1263) searches **all unmapped programs in the published index**, not programs newly committed this round.

Concrete failure:

1. The published index contains an older unmapped program A with root R—for example, after rehydration.
2. New admission B produces R and retains its table.
3. Index commit or publication fails, leaving the old manifest.
4. A is the sole candidate, so the recorder assigns B’s origin to A.

This requires no concurrency. Recording is gated only on `forwarded`, not successful indexing/publication ([repl.cljc:1745](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1745)); publication failure preserves the prior manifest ([index.cljc:448](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl/index.cljc:448)).

There is also asymmetric ambiguity handling: two new table entries sharing R and one unmapped indexed candidate cause the first reduced entry to claim it, rather than refusing both.

**Required fix:** establish correspondence from successful packet-to-index commit evidence. At minimum, exclude pre-existing indexed entities, verify the relevant indexing/publication succeeded, and require uniqueness on both sides. Account explicitly for pending packets and partial failures.

The mapping does **not** survive rebuilding/rehydration: session creation initializes it empty, while rebuilding can retain the index ([repl.cljc:1921](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1921)). Claim-free recovery is acceptable under the stated policy; assigning a new origin to an old unmapped entity is not.

**F6: OBJECTION — the previous new P0’s deletion-only case is fixed, but wrapper provenance remains unchecked.**

Assembly emits the correct wrapper coordinates at [publish.cljc:843](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:843). With that set unchanged, deleting a recovered row correctly produces incomplete-coverage refusal. Empty/nil rows correctly publish claim-free.

However, the publication boundary validates only coverage, disjointness, and application membership ([publish.cljc:676](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:676)). A partially valid spec can:

- Remove a real user-call row, leaving other recovered rows.
- Add that deleted `[root path]` to `:call-site-wrappers`.

The union remains exact and disjoint, so publication succeeds and assigns publication origin to that user call. Adding a still-covered user call is rejected; moving it between the two sets is accepted.

**Required fix:** validate the wrapper set against retained assembly evidence, rather than treating the independently supplied set as proof of construction. Otherwise explicitly limit this API to trusted composition declarations; it does not provide the adversarial provenance validation requested here.

**F5: OBJECTION — the new assertions are meaningful, but do not cover the new mapping failure.**

The foreign-table, deleted-row, missing-wrapper, and empty/nil cases have substantive assertions at [test:1791](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1791) and [1824](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1824).

The “drift collision” test at [1800](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1800) manually removes the admission mapping. It proves lookup refuses an absent mapping; it does not exercise whether `record-call-site-admissions` constructs a wrong mapping.

Add regression cases for stale published snapshots after index failure, multiple new admissions competing for one candidate, rehydrated unmapped programs, and moving a recovered user occurrence into the wrapper set.

The new r3 blocker is the admission recorder described under F2. The four tracked changed files remain within the permitted bounds.

CONFIRMATION VERDICT: REQUEST CHANGES
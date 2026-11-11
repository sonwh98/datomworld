Completed-GMT: 2026-11-10 23:33:49 GMT  
Completed-Local: 2026-11-11 06:33:49 Asia/Ho_Chi_Minh

**F2: CONFIRMED-FIXED.**  
[repl.cljc:1796](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1796) captures fresh evidence before indexing. The recorder rejects unsuccessful rounds, excludes previously published entities, and requires exactly one admission and one candidate per root ([1255](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1255), [1306](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1306)). This closes the stale-snapshot and first-wins defects.

The transient evidence is removed when consumed; ordinary evaluation replaces it with a fresh snapshot. Reusing a completed state without new index progress cannot satisfy the transaction-progress gate. A retained failure can suppress legitimate claims, but does not authorize a different origin.

Rehydration policy is implemented: session creation initializes an empty mapping, rehydration restores only the indexer, and rebuilding replaces the mapping while carrying the durable index ([788](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:788), [970](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:970), [1978](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/repl.cljc:1978)). Pre-existing programs therefore remain claim-free.

**F6: CONFIRMED-FIXED.**  
Assembly retains its computed wrapper set at [publish.cljc:905](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:905). Publication requires equality with that evidence, rejects missing evidence and duplicates, and then checks disjoint, exact coverage ([672](/Users/sto/workspace/datomworld-cc2/src/cljc/yin/vm/linker/publish.cljc:672)). Moving a recovered user occurrence into the wrapper set now refuses with `:yin.link.publish/wrapper-provenance`.

This verifies consistency with the retained assembly witness. It does not authenticate against a caller fabricating both the witness and the claim; the composition remains the provenance authority.

**F5: CONFIRMED-FIXED.**  
The requested regressions are present with substantive assertions:

- Failed publication: empty mapping and successful claim-free publication.
- Rehydrated entity: empty mapping and claim-free publication.
- Ambiguity on either side: empty mapping.
- Moved user occurrence or missing wrapper evidence: explicit `:wrapper-provenance` refusal.

See [recorder tests:1873](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1873) and [wrapper tests:1937](/Users/sto/workspace/datomworld-cc2/test/yang/clojure_callsite_test.cljc:1937). These use crafted recorder states; they are not full restart tests.

No additional landing blockers found in r4. The tracked changes remain within the four permitted files, and `git diff --check` passes. Review was static; no files edited or suites rerun. The previously accepted cc3 custody deferral remains unchanged.

CONFIRMATION VERDICT: APPROVE
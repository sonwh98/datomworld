Completed-GMT: 2026-11-07 15:35:28 GMT
Completed-Local: 2026-11-07 22:35:28 Asia/Ho_Chi_Minh

**Final ruling: approve the proposal.** Remove `:manifest-address` from the `repl-state` summary’s `:index-publication`. Retain `:cursor` and `:transactions`. This adjudication terminates the unit.

The diagnosis is supported by the code:

- [repl.cljc:926](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:926) creates a random shell token, passed to the indexer at line 790. [index.cljc:116](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:116) records it as `:yin.repl/session` provenance.
- [index.cljc:407](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:407) derives the manifest address from the manifest; lines 431–445 verify publication and retain its address internally. Different provenance therefore legitimately produces different addresses.
- Commit `3c301b65` introduced the summary’s three-field projection. Its accompanying docstring promised only the verified cursor and covered transaction count.
- [stream_eval_test.cljc:417](/Users/sto/workspace/datomworld/test/yang/clojure/stream_eval_test.cljc:417) normalizes VM names and UUID identities, but cannot normalize provenance embedded in a content hash. The supplied diagnosis log records the shiva reproduction: 1 failure, 309 assertions.

The architectural distinction is between publication identity and diagnostic status. [The indexing design:45](/Users/sto/workspace/datomworld/docs/design/yin.repl.dao.space-index.md:45) requires session provenance, and line 210 requires its preservation across recovery. [The status contract:536](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:536) explicitly promises evaluator/token independence. The summary’s [documented publication contract:2180](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:2180) exposes coverage diagnostics. The available [yin.repl.md](/Users/sto/workspace/datomworld/src/cljc/yin/vm/docs/yin.repl.md) adds no requirement to expose the manifest address.

Accordingly:

- **Test-side normalization:** reject for this fix; it would accommodate an unnecessary token-dependent summary field.
- **Content-equality witness:** reject as unnecessary new semantics. Cursor and transaction count report publication progress; they do **not** prove content equality. Existing index read-back and incremental/full-reconstruction tests provide that verification.
- **Removing indexed provenance:** reject; it contradicts the explicit provenance and recovery contracts.

Exactly two edits are required:

1. [src/cljc/yin/repl.cljc:2209](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:2209):
   ```clojure
   :index-publication (select-keys (get-in state [:indexer :materialization])
                                   [:cursor :transactions])
   ```

2. [test/yin/repl/index_test.cljc:288](/Users/sto/workspace/datomworld/test/yin/repl/index_test.cljc:288), replace the pinned assertion with:
   ```clojure
   (is (= (select-keys (:materialization ix)
                       [:cursor :transactions])
          (:index-publication (repl/repl-state state))))
   ```
   This exact-map comparison also rejects reintroducing the address.

Searching `test/` found **only this one summary-address pin**. Internal address assertions—including the retained-key assertion at line 272—remain valid. The transcript fixture named `:index-publication` checks `:published?`, not address presence.

Searching `src/` found **no consumer of the summary’s manifest address**. [jolt_workload_w.clj:190](/Users/sto/workspace/datomworld/src/dev/jolt_workload_w.clj:190) consumes only its cursor. Query, publication, recovery, and HEAD/DHT paths use internal addresses and require no edits.

Acceptance: the revised pin passes, and the existing four-VM parity assertion at line 444 passes unchanged, with no additional normalization. Existing publication/provenance tests remain unchanged.

Read-only review completed; no files changed and no suites run.
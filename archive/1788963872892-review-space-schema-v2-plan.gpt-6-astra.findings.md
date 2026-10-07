Completed-GMT: 2026-09-09 14:28:23 GMT
Completed-Local: 2026-09-09 21:28:23 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
# Routine review — dao.space.schema v2 migration plan
Role: Routine Review | Model: gpt-6-astra

I’ll read the plan and repository review guidance, then check its decisions, deletion accounting, and phase boundaries against the code. I’ll keep the review read-only and report it here.

The rationale for rejecting incomplete snapshots is sound: losing schema declarations can change the view’s answer silently. The receipt collapse also looks straightforward. I’m checking the claimed surviving tests and phase counts next; those need to be exact for an implementer to use them as completion checks.

I found a blocking flaw in the snapshot test: `query/snapshot` mints a fresh `oldest` cursor, so overflowing a ring buffer before snapshotting returns the retained suffix with `:blocked`, not `:gap`. That also means the proposed status check cannot guarantee complete history. Separately, D1’s claimed validation ordering differs from `tx/transact!`, and the source-side residue count does not close.

**Request changes before implementation.** D7 and the D10 receipt collapse are sound. D4’s rationale is sound, but its proposed completeness guarantee and test contradict the stream contract.

1. **[P1 — blocking] D4, §5 Tests, §8: a successful snapshot status does not establish complete history.**

   [`query/snapshot`](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:275) mints a fresh `:oldest` cursor. After a ring buffer has overflowed, that cursor starts at the retained suffix. The planned V13 fixture therefore returns `:blocked`, **not `:gap`**, and its assertion fails.

   More seriously, `schema/current` would accept that suffix—the exact case D4 says it prevents—and could silently lose cardinality declarations. The stream design explicitly distinguishes retained history from complete history at [`dao.stream.md:496`](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:496).

   **Change:** retain rejection of reported `:gap`/`:defect`, but state that it detects observed read failures, not prior prefix loss. Require complete-retention provenance from the caller for the completeness claim. Use a scripted reader that gaps after cursor minting, following [`snapshot-of-a-gap-is-data`](/Users/sto/workspace/datomworld/test/dao/space/query_test.cljc:359), and add the missing `:defect` case. If automatic detection of earlier eviction is required, the plan must explicitly address origin-cursor/provenance support; the current snapshot API cannot provide it.

2. **[P2 — must resolve before briefing] D1, §2.3 L10, §4: the claimed transactor ordering is false.**

   [`tx/transact!`](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:233) checks empty `tx-data` **before** acquiring its lock and checking closedness. Thus a closed inner transactor given `[]` throws, while the proposed schema wrapper returns `closed`.

   Schema’s proposed ordering is coherent, but “exactly as `tx/transact!` does” is incorrect. Likewise, “every argument defect still throws” needs the qualification “when the wrapper is open.”

   **Change:** explicitly adopt this as a schema-specific precedence rule and document the difference. Alternatively, align with the existing transactor ordering. Changing the transactor too would require naming that additional scope rather than retiring T20 as though ordering already matched.

3. **[P2 — blocking the stated proof procedure] §4 Prove, §5 Prove, D10, §7: the accounting and closure checks are inconsistent.**

   From the plan’s own edit lists:

   - **Source:** Phase 1 removes two `ds/` lines: `SchemaWrapper`’s protocol declaration and `transact!`’s `ds/closed?` call. Its protocol methods are unqualified. Therefore **16 − 2 = 14**, not 11.
   - **Tests:** **68 − 61 − 1 = 6** is correct. More precisely, 60 wrapper closes are rewritten, one disappears with the publication-race test, and `ds/closed?` disappears. The require and require-macros lines contain no `ds/` and should not be listed as count contributors.
   - Two of the 24 receipt lines disappear with the race test; 22 are rewritten. The T19 test needs both `:1062` and `:1071` changed, contradicting “only its `:1071` literal.”
   - The plan deletes **five deftests**, renames one, and removes one assertion—not seven deftests. With five additions, the deftest count is unchanged.
   - Phase 2’s `grep -n "dao.stream\|dao.jing" schema.cljc → nothing` is impossible: the required `:dao.stream/outcome`, `:dao.stream/ok`, and `:dao.stream/closed` keywords remain.

   **Change:** correct the inventories and distinguish namespace dependencies from qualified outcome keywords. Keep the final `ds/` zero check, but make the dependency check target require forms.

4. **[P2 — coverage improvement] §4’s new close test and D6/V14 do not prove the ownership properties attributed to them.**

   The proposed idempotent-close test would pass if `schema/close!` merely set its own flag and never called `tx/close!`: subsequent wrapper transactions stop at that flag. Similarly, V14 would pass if `schema/current` eagerly read **and prematurely closed** the published source; the owner’s later idempotent close would hide that mistake.

   **Change:** after wrapper close, directly assert that its inner transactor answers `closed`. Before the caller closes a published source, assert that schema has not closed it, using an ownership counter or equivalent observable pin. Keep V14’s post-close query assertions for self-containment.

The deletion audit otherwise closes:

| Planned removal | Disposition |
|---|---|
| `borrowed-and-descriptor-paths-agree` | Route equivalence becomes obsolete when both routes disappear. |
| `inner-stream-closed-after-descriptor-path` | Schema no longer opens that resource; query’s existing ownership tests cover the surviving opener. |
| `borrowed-path-does-not-close-again` | Obsolete for plain relations; the closable published-value replacement needs the stronger pin in finding 4. |
| `published-descriptor-validation` | All four properties survive: three in [`published-index-constructor-validates-its-arguments`](/Users/sto/workspace/datomworld/test/dao/space/index_test.cljc:592), and extra-key rejection in query’s named test. Cite the constructor **test**, not just its implementation. |
| `publish-serializes-against-close` | Guard/serialization assertions intentionally retire. The named transactor test really pins publication count and enqueue behavior, though it is not a concurrent-close test. |
| `closed-wrapper-throws` | Renamed and changed, not deleted; the borrowed-local-stream assertion survives. |
| W15’s `closed?` assertion | One assertion removed; successful transaction remains an operational openness pin. |

**D7 is reasoned and explicitly changes behavior.** Closing the wrapper closes neither the local stream nor the intake pool, and publication does not consume wrapper validation state. Removing the closed-publication policy removes the need to serialize that policy against close. The new publish-after-close test appropriately records the decision.

**The D10 receipt collapse is also sound.** The wrapper already copies the inner timestamp and emitted datoms, including repairs. Returning the receipt directly preserves those properties; I found no additional caller-visible dependency requiring a replacement envelope.

Phase 1 can stand alone without a read-side compile dependency. W38–W41 are indeed the four existing deftests touched across both phases, although both design documents also span phases. In Phase 2, specify nested `try/finally` scopes for published values: the current `opened` binding would otherwise be inside the `let` and unavailable to the existing outer `finally`.

Finally, the direct-dependency claim that all `dao.space.*` namespaces leave v1 is supported by the tree. No additional schema consumer needs migration. The unresolved obligation is D4’s completeness contract; §8 cannot claim closure until that is corrected.

Read-only inspection; no files written or implementation tests run.

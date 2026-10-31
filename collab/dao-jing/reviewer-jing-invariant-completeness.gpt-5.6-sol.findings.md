I’ll enumerate all 54 tests directly from the three files, cross-reference each against the plan’s A–G invariant list and `dao.jing.md`, and call out only missing or weakened coverage. I won’t run tests or edit files.
Completed-GMT: 2026-09-07 10:33:14 GMT
Completed-Local: 2026-09-07 17:33:14 Asia/Bangkok
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7

| Test | Invariant | Verdict |
|---|---|---|
| `sha256-known-answer` | A2 | mapped |
| `segment-key-is-content-addressed` | A1, A2, A3 | partially mapped — A3 only states cross-type distinction; the same-type `{:a 1}` vs `{:a 2}` assertion is not explicit |
| `segment-keys-are-readable-edn` | A4 | mapped |
| `segment-hash-recovers-the-content-hash` | A5 | mapped |
| `segment-hash-rejects-invalid-keys` | A5 | mapped |
| `content-hash-is-order-insensitive` | A2, A3 | partially mapped — same-type distinctness is again absent from A3 |
| `materialize-hashes-and-retrieves` | A1, B1, B4 | mapped |
| `put-receives-the-derived-address-and-payload` | B1 | mapped |
| `materialize-is-idempotent` | B3 | mapped |
| `get-absent-returns-not-found` | C2 | mapped |
| `get-rejects-arbitrary-addresses` | C1 | mapped |
| `get-rejects-arbitrary-addresses-before-touching-the-backend` | C1 | mapped |
| `forced-collision-is-an-integrity-failure` | B3 | mapped |
| `present-without-readable-content-is-an-integrity-failure` | B3 | mapped |
| `content-missing-keyword-is-a-legal-payload` | B7 | partially mapped — B4 is universal in prose, but the specific sentinel-regression property is dropped |
| `invalid-backend-result-is-rejected` | B2 | mapped |
| `close-delegates-and-is-optional` | D2 | mapped |
| `observer-state-is-plain-data` | E1, E2 | partially mapped — plain explicit state is retained, but fabricated `{:position 0}` and initial `:pending` representation are intentionally replaced |
| `observer-empty-pool-is-blocked` | E8 | mapped |
| `observer-all-blocked` | — | **UNMAPPED** — pins “when every member is currently blocked, signal `blocked`”; this is a real pool invariant missing from E8, despite appearing in Decision 1 prose |
| `observer-automatic-hashing-and-retrieval` | B1, B4, E10 | mapped |
| `observer-equal-payloads-from-two-streams-converge` | B4, E10 | mapped |
| `observer-blocked-before-ready-does-not-prevent-later-members` | E6 | mapped |
| `observer-ended-before-ready-does-not-starve-active-members` | E6, E8 | mapped |
| `observer-all-ended` | E8 | mapped |
| `observer-drains-then-reports-end` | E8 | mapped |
| `observer-fair-round-robin-and-independent-cursors` | E2, E6 | mapped |
| `observer-independent-cursors-interleave` | E7 | mapped as an explicitly dropped scheduling accident |
| `observer-gap-is-reported-and-never-auto-resynced` | E9 | mapped |
| `observer-rejects-malformed-stream-maps` | E4 | partially mapped — v2 correctly classifies malformed reads as data, but the old test’s throw behavior is neither preserved nor explicitly marked as a `[T✗]` drop |
| `observer-cursor-advances-only-after-successful-materialization` | E5 | mapped |
| `create-content-mem-returns-a-stream-free-content-handle` | D1, B4, D4 | partially mapped — functional handle and no source identity remain; `:state` shape and `:stream` absence are intentionally dropped |
| `materialize-mints-the-address-automatically` | A1, B1, B4 | mapped |
| `opaque-payloads-round-trip-including-nil` | B4, B5 | mapped |
| `insert-is-idempotent` | B3 | mapped |
| `put-rejects-invalid-content-addresses` | B6 | mapped |
| `put-rejects-address-payload-hash-mismatch` | B6 | mapped |
| `collision-preserves-the-existing-value` | B3, B6 | mapped |
| `close-is-idempotent-and-throws-after` | D3 | mapped |
| `get-distinguishes-absence-from-stored-nil` | B5, C2 | mapped |
| `observer-pool-equal-payloads-converge-to-one-entry` | B4, E10 | mapped |
| `concurrent-inserts-report-exactly-one-inserted` | D5 | mapped |
| `codec-test` | F1, B5, F7 | partially mapped — tuple/nil round-trip is covered semantically, but direct `encode-record`/`decode-record` and exact framing are intentionally dropped |
| `handle-shape-test` | D1, D4, F7 | partially mapped — required backend functions remain; `:state`, `:log`, `:write-lock`, and exact shape are intentionally dropped |
| `materialize-idempotence-test` | B3, F5 | mapped |
| `close-reopen-durability-test` | F5 | mapped |
| `nil-and-absence-test` | B5, F5 | mapped |
| `observer-convergence-test` | B4, E10, F5 | partially mapped — convergence and persisted record count remain; direct `:state`/`:log` inspection is dropped |
| `raw-duplicate-recovery-test` | F2 | mapped |
| `corrupt-record-categories-test` | F4 | mapped |
| `collision-test` | B3, F2 | mapped |
| `close-semantics-test` | D3, F6 | mapped |
| `acknowledged-insert-survives-close-test` | F5 | mapped |
| `file-content-contention-test` | D5, F6 | mapped |

The unmapped/weak areas are therefore:

- E8 must explicitly include “all non-ended members blocked ⇒ `blocked`.”
- A3 should cover distinct supported values generally, not only cross-type values.
- The old malformed-read throw behavior should be explicitly classified as a dropped implementation behavior, even though v2’s data outcome is correct.
- B7 is safe to drop only if the new B4 test exercises a representative keyword/sentinel value or a genuinely broad arbitrary-payload property.

Secondary checks

- B7 `[T✗]`: not wholly safe as currently justified. The specific keyword is not an implementation accident if the old sentinel can regress; it is covered only indirectly by universal B4 prose. Add a keyword case to the replacement test or broaden B5/B4’s executable test.
- D4 `[T✗]`: right to drop. Atom shape and absence of `:stream` are implementation details; D1/B4 preserve the externally meaningful handle and payload separation.
- E7 `[T✗]`: right to drop. Strict `A B A B` is one scheduling trace; E6’s fairness guarantee is the real requirement.
- F7 `[T✗]`: right to drop for the new private framing. Prefix width, `pr-str`, `:log`, and `:write-lock` are implementation details, assuming F1/F3/F5 recovery and durability tests remain.

The `[T→D]` promotions are genuinely absent from the current `docs/design/dao.jing.md`: A3, A4, B5, B6, D3, and F6 are not stated there with the plan’s specificity. The document mentions hashing, opaque payloads, collision handling, and delegated close, but not these complete requirements.

The list is not yet complete enough to delete the old tests: add the all-blocked invariant, strengthen A3’s distinctness coverage, and explicitly account for the malformed-read throw drop; preserve B7 through a general executable arbitrary-payload test.

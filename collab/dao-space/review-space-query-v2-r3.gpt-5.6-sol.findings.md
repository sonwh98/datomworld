I’m using the repository’s review checklist again, scoped only to the corrections and their consumer impact. I’ll inspect the changed query/schema/test regions and dependency usages without rerunning any suite.

Both fixes are present in the implementation. The borrowed schema test still exercises a real property: it enters `schema/current`’s realization branch, validates the already-closed bounded reader, drains it in `interpret-view`, and proves the close count remains unchanged. I’m doing the final repository-wide classification scan now.

# Correction Review — r3

Verdict: **both P1 findings are closed. Ready to commit.**

## Finding 1 — Closed

`legacy-v1-realization?` is gone. `current` and `history` now uniformly use `db-source` at `src/cljc/dao/space/query.cljc:204-220`. Arbitrary objects consequently receive I1’s informative rejection, and query no longer reaches v1 traversal through `index/snapshot-datoms`.

`unrecognized-host-objects-are-rejected` meaningfully covers atoms and delays across hosts and a JVM `Date`.

The updated schema tests remain substantive:

- `borrowed-and-descriptor-paths-agree` compares schema interpretation through the query-value path and the closed v1-realization path.
- `borrowed-path-does-not-close-again` passes a manually closed `RecordingStream` directly to `schema/current`. It enters the realization branch, validates closedness/boundedness, and drains through `interpret-view`. The unchanged close count proves neither draining nor interpretation closes the borrowed source again.

## Finding 2 — Closed

Query relations and views no longer contain `:dao.stream/type`:

- `relation` returns only `{:dao.space.query/relation ...}`.
- `view-value` carries only the query-owned view tag, source, and optional `as-of`.

`schema/current` now correctly distinguishes:

- query values through `query/value?`;
- legacy descriptors through keyword `:dao.stream/type`;
- nested query views through `:dao.space.query/view`;
- nested legacy schema views through `:dao.space.schema/current`.

The repository-wide consumer scan found no remaining consumer expecting query values to advertise a stream type. The published-index coordinate correctly retains its transport-owned `:dao.stream/type`.

The replacement `schema-current-returns-a-fact-relation-value` test is non-vacuous: it verifies both query-value classification and the `:fact?` marker required for fact-index construction.

## Regression check

No correction-induced breakage found. In particular:

- schema query-value and legacy descriptor paths remain distinct;
- nested current/history views are still rejected;
- schema `:as-of`/`:schema-as-of` interpretation is unchanged;
- published-index classification and ownership are unchanged;
- `git diff --check` is clean;
- CLJ/CLJS/CLJD portability remains intact.

No suites were rerun, as requested. The supplied full verification is consistent with the corrected code.

**Final decision: ready to commit.**

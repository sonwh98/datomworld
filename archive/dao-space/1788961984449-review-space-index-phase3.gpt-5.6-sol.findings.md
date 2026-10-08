I’ll check each removed adapter property against its surviving assertion, then review schema’s replacement reader and the failed-open cleanup test. I’ll use the reported test results and keep this review read-only.

The assertion decrease reconciles exactly: 36 old assertions are removed and 21 are added, giving the reported net −15. The failed-open test checks the close count before fixture teardown, so teardown cannot hide a missing cleanup call. The schema replacement also preserves the forced-row reader behavior while closing the store before returning it.

Completed-GMT: 2026-09-09 13:54:02 GMT  
Completed-Local: 2026-09-09 20:54:02 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

No blocking findings. Phase 3 is ready from review, subject to your pending CLJD result.

The assertion decrease is accounted for: **36 removed, 21 added, net −15**. Seven adapter deftests disappear, seven query deftests are added, and the structural `covered-indexes` assertions remain. The count reflects removal of obsolete transport properties and migration of the surviving properties.

The eleven adapter calls are grouped into nine entries in §5.4; I checked each group:

| §5.4 | Property | Judgment |
|---|---|---|
| #1 | Unsupported coordinate and extra-key rejection | Both preserved in `query_test.cljc:936`, with specific exception-message assertions. |
| #2 | Missing and invalid manifests | Preserved by `index_test.cljc:675`. These assert the actual failures in `read-manifest`, which `open-published!` calls synchronously and whose errors it rethrows. |
| #3 | Protocol/lifecycle vocabulary, independent cursors, ordering | Protocol and cursor assertions deliberately retire with the adapter. Ordering survives in #7; exact-once, idempotent close remains pinned at `query_test.cljc:716`. |
| #4 | Empty published input | `index_test.cljc:399` retains publication/readback coverage; `query_test.cljc:964` additionally asserts empty rows and an empty query result through the opened value. |
| #5 | Exactly one fetch at open | `query_test.cljc:977` counts the newly opened value’s store accesses before any row read. The fixture’s earlier opening is outside that counter. |
| #6 | Four covered sets and structural recognition | Structural assertions remain at `index_test.cljc:626`; `query_test.cljc:997` checks the opened value’s exact four keys and each set’s count. |
| #7 | Eager/read parity and EAVT ordering | `query_test.cljc:1014` compares with `read-datoms` **and independently with sorted input**. The second assertion prevents shared implementation from making the comparison vacuous. |
| #8 | Portable coordinate and carrier round-trip | `query_test.cljc:1032` checks EDN equality, the complete coordinate map, successful v2 carrier append/read, and reopening with the expected rows. |
| #9 | Stigmergy `sources` | `stigmergy_test.clj:184` preserves `current-state-seq`, row transformation, and `try`/`finally` ownership around the new query opener. |

P5 is effective. At `query_test.cljc:1076`, the injected manifest fetch throws a specific exception after store acquisition. The test checks exception identity and `closes = 1` **before outer fixture cleanup**. Removing query’s failure-path close would produce zero and fail the assertion. Strictly, query uses a `catch` cleanup; removing schema’s separate `finally` would require a schema-specific test. The new test satisfies the plan’s query-side P5 requirement.

`PublishedSchemaRows` at `schema.cljc:1180` faithfully supplies schema’s required behavior: eager EAVT rows, the same positional `next` results, `closed? = true`, and non-erasing `close!` returning `{:woke []}`. The opener forces all rows while the store is open and closes it on success or failure. Schema no longer retains unused indexes or a store handle.

W38–W41 and the entire `schema_test.cljc` are unchanged. The §5.3 protected regions are untouched. No Phase 3 dependency remains on the deleted adapter.

The documentation records the new ownership and read paths accurately. Schema’s published opener and `PublishedSchemaRows` remain explicitly owned by schema’s forthcoming migration. The fetch-count and P5 spies run on JVM/Node and explicitly skip Dart; that limitation is disclosed, not hidden.

I reran no suites. `git diff --check` is clean.

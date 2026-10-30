Created-GMT: 2026-10-20 01:03:40 GMT
Created-Local: 2026-10-20 08:03:40 +07
Coding-Agent: claude
Model: claude-fable-5-1
Role: Architect

# dao.stream.codec design, revision r3: disposition of round-2 findings A1-r2 through A4-r2

Edited: `docs/design/dao.stream.codec.md` only. No `src/` or `test/` file touched. Nothing executed
beyond static reads of the tree and grep/regex checks on the document itself.

Source facts re-read for A1-r2 (V, 2026-10-20):
- `src/cljc/dao/stream/cbor.cljc:63`: the predicate's identifier branch is `(portable-identifier? x)` and
  never looks at a symbol's metadata; `portable-meta?` (`:42-49`) is applied to collections only.
- `src/cljc/dao/stream/cbor/boring.cljc:99-102,160-162`: both the rewrite and convert passes recurse into
  symbol metadata (`(with-meta x (rewrite m))`). A host object in that metadata is handed to Boring's own
  writer; its outcome is not asserted in the design.
- `src/cljd/dao/stream/cbor/cljd.cljd:158-166`: the Dart encoder emits a `clojure/with-meta` frame for a
  symbol with metadata and recurses with `to-cbor`; `:186-187` throws for any value with no branch, so a
  host object inside symbol metadata throws there.

The review's counterexample is therefore confirmed: `(with-meta 'x {:k <host-object>})` was in D as
written, both legacy predicates admit it, and the Dart CBOR encoder rejects it, so membership in both
predicates did not establish the round-trip law.

| Id | Disposition | Where in the document | What changed |
|---|---|---|---|
| A1-r2 (Blocking) | Fixed | §4.4 today-table (new row), D definition, the paragraph after D, law corpus; §6.1 point 3; §7.1 C0; §11 | D's identifier clause now requires a symbol's metadata, if any, to be itself in D, checked recursively under the same metadata rule as collections (cljd constructor metadata stripped first; the strip arm moves to the contract and is applied to symbols too). Both legacy predicates stay unchanged (CD3). The claim "CBOR's domain is exactly D plus bytes" is now stated only with the qualification that CBOR's predicate also admits symbols with any metadata, as a recorded predicate/encoder gap with line cites, not fixed here and named as a one-line follow-up for the CBOR namespace's owner outside this track. C0's adversarial corpus gains the symbol-metadata cases: direct, nested in a vector, as a map value, as a map key, and inside another symbol's metadata, each with a host object and again with a record instance; the test asserts D's rejection only and deliberately does not pin CBOR's answer where its predicate and encoders disagree. The in-D side gains symbols with portable metadata direct, nested, and in another symbol's metadata. |
| A2-r2 (Major) | Fixed | §3.2 (`profile?` paragraph and the migration paragraph); §7.1 C1 and C4; §10 OQ3; §11 | `profile?` is specified as structural only: name a token, frame kind one of two keywords, three functions present, `bytes?` an explicit boolean; it checks nothing about the name's value, and a legacy-only map answers `bytes?` false rather than gaining a capability by omission. C1 now rejects "malformed" profiles (missing key, absent or non-boolean `bytes?`, non-token name) and positively tests a complete profile under an unfamiliar name twice, with `bytes?` true and false, asserting the bytes and octets branches respectively. C4 is rewritten with both OQ3 paths spelled out, each with its own grep gate; under "keep", no absence of `:ws/*` is asserted and a `:ws/bytes?` boolean is added so the §3.3 capability survives either answer. C0's dual-key gate tests are specified as rewritten, not deleted, at C4, with the before/after gate-test count recorded. The migration paragraph now says the intermediate slices are feasible under the stated acceptance, not guaranteed green until each slice report records them so. |
| A3-r2 (Minor) | Fixed | §1 (the "non-problem" paragraph); §7.3 (bidirectional peers paragraph, new three-claim table, closing paragraph); §11 | The absolute "changes no byte on the wire" is replaced by three claims of different strength: wire contract unchanged (names, negotiation, envelope, lifecycle; proved by fixtures plus old/new runs), CBOR bytes unchanged (byte-level, canonical wire, fixture corpus), and Transit interoperable (semantic only; Transit text is not canonical and is not byte-compared, so "same text as before" is not claimed). A frozen Transit encoder comparison is stated as not required because no slice touches the Transit encoder, and as added for any slice that does. A Dart lane whose old/new run cannot be hosted is reported as absent coverage by name, never as covered by the jvm or node runs; §11 requires slice reports to state the wire result in the three-claim form. |
| A4-r2 (Minor) | Fixed | §5 frame-kind row | The row now says one WebSocket message carries one self-contained codec payload, which under restriction (iii) contains several IPC messages (schema, any dictionary batches, one record batch), each a separate encapsulated message in Arrow's stream format, while one WebSocket message is still one stream value. |
| A5-r2 (Informational) | No change needed | §2 | Resolved in r2 per the review. |

Also touched: the status paragraph at the top records r3 and points to this report.

Verification on the edited document:
- `grep -ci jolt docs/design/dao.stream.codec.md`: 0.
- Em dashes: 0 occurrences.
- Lines over 170 columns: none.
- ASCII tables: all 354 table rows (lines starting with `|` or `+`) fall into exactly one width class per table; an off-width search over every other length returns no rows. Three misalignments introduced during editing (C0 continuation row, two cells in the new §7.3 claims table, one cell in the OQ3 continuation row) were found by this check and corrected.

Not verified: no lane was run; no fixture generated; the cited Boring and Dart encoder behaviour on a host object inside symbol metadata is read from source, not executed. The recorded CBOR predicate gap (`cbor.cljc:63`) is left in the tree by design, since this track changes no legacy predicate; whether to close it is the CBOR namespace owner's call.

Ready for a round-3 independent review by a non-Claude architect, per §11's sign-off rule.

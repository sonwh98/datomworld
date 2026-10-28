Created-GMT: 2026-10-20 01:05 GMT
Created-Local: 2026-10-20 08:05 +07
Role: Architect (claude-fable-5-1)
Task: revise `docs/design/dao.stream.codec.md` (r2) to address review findings A1-A5 in
`collab/1792456650000-architect-stream-codec-design-review.gpt-6-astra.findings.md`

# Summary

All five findings are addressed by editing the design document in place. No `src/` or
`test/` file was touched. Every review claim about the tree was re-verified by reading
the cited lines before the design was changed; all of them held.

Checks requested by the brief:
- `grep -ci jolt docs/design/dao.stream.codec.md` = 0.
- Every table is an ASCII box table, every row closes, every row in a table has the
  same width as its borders, no line exceeds 170 columns, no em-dashes (checked with a
  throwaway script, not committed).

# Per-finding disposition

+-----+----------+-----------------------------------------------------------------------------------------------------------------------------+
| Id  | Verdict  | What changed                                                                                                                |
+=====+==========+=============================================================================================================================+
| A1  | accepted | §4.4 rewritten. Re-verified: `transit/portable-value?` (`transit.cljc:55-76`) admits every keyword and symbol, every map    |
|     |          | including records on clj/cljs, and never inspects metadata; `cbor/portable-value?` (`cbor.cljc:25-70`) rejects `/`-bearing  |
|     |          | or empty-part identifiers, non-portable metadata and records (tests `cbor_test.cljc:166-183`). Neither predicate is a       |
|     |          | subset of the other, so the r1 "move Transit's predicate and call it the base domain" is withdrawn. The common swappable    |
|     |          | domain D is now a **new third predicate** in `dao.stream.codec`, defined as a subset of both (CBOR's structural rules       |
|     |          | without host bytes). Neither profile's own admission predicate changes, so wire acceptance is preserved under both existing |
|     |          | names. Equality/projection rules are stated separately: the law holds under `=` plus the numeric rule, which is blind to    |
|     |          | metadata and list-hood; what a codec preserves beyond `=` is a codec property. §6.1 point 3 corrected (Transit admits lists |
|     |          | and metadata, drops metadata on the wire; asymmetry runs both ways). Adversarial identifier and metadata cases listed for   |
|     |          | the law test. `transit-domain-stays-a-subset-of-the-cbor-domain` identified as overstating and replaced by the law test.    |
|     |          | Only `safe-number?` and its bounds move out of Transit (no test references them through Transit, V); the r1 "forwarding     |
|     |          | vars" and the move of `valid-descriptor?` are withdrawn.                                                                    |
+-----+----------+-----------------------------------------------------------------------------------------------------------------------------+
| A2  | accepted | §7.1 restructured as expand / migrate / contract: F0 (freeze fixtures), C0 (contract ns, dual-lookup accessors, both key    |
|     |          | sets on both profiles, `bytes?` added here, `cbor.cljc` and `data.cljc` switch requires), C1 (ws, four adapters, Jing by    |
|     |          | `profile?` + `bytes?`, remote-channel accepts optional codecs; every default kept), C2 (every caller and test names its     |
|     |          | profile; nothing reaches a default), C3 (delete defaults, Transit requires, fallbacks; `::no-codec`), C4 (delete old keys;  |
|     |          | gated on OQ3), C5 (owner-gated, unchanged). A paragraph argues why each intermediate state is green. §3.2 adds the          |
|     |          | accessors and notes that the socket **seam** key `:ws/subprotocol` is not a profile key and is not renamed (it is read at   |
|     |          | `ws.cljc:645`, `jvm.clj:359`, `node.cljs:192`, `dart.cljd:108`). CD6 records the sequence.                                  |
+-----+----------+-----------------------------------------------------------------------------------------------------------------------------+
| A3  | accepted | New §7.3: frozen pre-change fixtures recorded at a tagged F0 commit (negotiation, descriptors, envelopes for the law        |
|     |          | corpus plus each profile's extras, canonical rejection, Jing wrapping) and checked on every slice; bidirectional old/new    |
|     |          | peer runs (old serves/new dials and the reverse, both profiles, jvm and node lanes, dart where the harness allows, gaps     |
|     |          | recorded by name) at C2 and C4. Predicate/API changes documented separately from wire compatibility. On the review's        |
|     |          | "C0 tightens record admission, including `dao.data`": re-verified `data.cljc:52-60` applies the predicate to numbers only,  |
|     |          | where D and Transit's predicate coincide, so no answer `dao.data` gives changes; and no admission predicate is replaced in  |
|     |          | r2, so the tightening does not occur. §6.2 and §7.3 say so with the cite.                                                   |
+-----+----------+-----------------------------------------------------------------------------------------------------------------------------+
| A4  | accepted | §5 Arrow column qualified: stateless per frame only for dictionary-free batches, immutable precomposed dictionaries         |
|     |          | identified in the profile and name, or self-contained per-frame IPC payloads; in-band delta dictionaries are per-attachment |
|     |          | state and would need the §8 `open` hook. Envelope and D mappings stated as unimplemented future-codec obligations; "peer"   |
|     |          | not claimed for Arrow until a codec passes the law test. Protobuf column labelled plausible, not demonstrated conformance.  |
|     |          | §5 conclusion and §8 amended to match.                                                                                      |
+-----+----------+-----------------------------------------------------------------------------------------------------------------------------+
| A5  | accepted | §2 second bullet: mandatory canonical CBOR is limited to the wasm **import surface**; `dao.stream.wasm.md:22-27` (V) is     |
|     |          | cited for co-resident ring buffers serving operations with no serialization, which stay implementation-local. Appendix A    |
|     |          | cite updated.                                                                                                               |
+-----+----------+-----------------------------------------------------------------------------------------------------------------------------+

Reviewer's owner/orchestrator note on OQ3 (undecided yet CD5's default): resolved in §10
and CD5 by making CD5 the provisional direction for C0-C3, which are correct under either
answer because they add keys beside the old ones; only C4 commits, and C4 is not
commissioned until the owner records the OQ3 choice.

# Other edits made while addressing the findings

- Header: r2 provenance line pointing at the review and this file.
- §1 privilege table: "Hosts the portable domain" became "Hosts the numeric domain",
  because only `safe-number?` is shared in fact; the `dao.data` cite now says numbers only.
- §3.1 table: Transit keeps its own predicate trio; codec ns owns D, numerics,
  accessors, `host-bytes?`. `valid-descriptor?` is not moved (no external consumer, V).
- §6.4: the CBOR recommendation now also lists the honest downside that Transit admits
  identifiers and metadata CBOR refuses.
- §8: added a reversal condition for a consumer that needs D to be an existing predicate.
- §11: successor starts at F0; the reviewer's check order now begins with "no slice
  removes what a later slice still reads".
- §7.2: `transit-test` no longer splits (its predicate is unchanged); `cbor-test` loses
  the overstated subset test to the law test.
- Appendix A: cites added for `cbor_test.cljc:161-210`, `cbor_test.cljd:186-190`,
  `dao.stream.wasm.md:22-27`, the seam-key sites, `ws.md:698-720`, and the grep showing
  no test references `transit/safe-number?`.

# Not verified / limits

- No lane was run; all labels remain V (read), R (inferred), P (proposed). Transit
  dropping metadata on the wire is R (standard cognitect writer behaviour, not executed).
- Whether list-hood survives Transit's wire on each host is deliberately not asserted.
- The fixture directory layout under `test/` is proposed (P); the slice that lands F0
  picks the exact path by the repo's test-resource convention.
- Table alignment was repaired mechanically for the whole file (the r1 tables were also
  off by one or two columns on continuation rows); content was not changed by that pass
  except one CD6 row where a missing space before a pipe was corrected.

# Recommendation to the orchestrator

Send r2 back to a non-Claude reviewer (the standing rule for Claude-authored design) with
the five dispositions above as the review brief. Nothing is commissioned; if the review
signs off, the first commissionable slice is F0, which changes no source.

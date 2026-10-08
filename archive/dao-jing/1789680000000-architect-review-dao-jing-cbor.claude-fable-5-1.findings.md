Coding-Agent: claude
Model: claude-fable-5-1
Role: Lead System Architect

# Review: docs/design/dao.jing.cbor.md (first-ever architecture review)

Completed-GMT: 2026-09-17 12:00:00 GMT
Completed-Local: 2026-09-17 19:00:00 +07 (Asia/Ho_Chi_Minh)

## Findings

1. **medium** | `docs/design/dao.jing.cbor.md:16-19` vs `:280-289` | The Objective says backends "need no knowledge of CBOR," but the file-backend frame format is specified as "one definite two-element CBOR array of byte strings" (`[digest payload-bytes]`), and replay must split the two and compare the digest to the payload's SHA-256. The doc never says who parses that array — if it's `dao.jing.file` itself (the natural reading, since today's `file.cljc` already owns equivalent frame decoding), that contradicts the stated invariant. Recommend naming the owner of this frame codec explicitly.

2. **informational — corrects the D3 evidentiary reading** | `yin.vm.code-as-tuples.implementation-plan.md` D3 vs `dao.jing.cbor.md:70-84` | The absence of a batch/pack write primitive in this plan is not evidence for "individual rows" over "pack per tree." A pack needs no new write primitive — it's just one ordinary CBOR-encodable value (vector/map) materialized through the existing single-value contract. `dao.jing.cbor.md` is architecturally neutral on D3's row-grain question and shouldn't be cited as supporting either option.

3. **informational — metadata-carry is only half closed** | `dao.jing.md:447-464` vs `dao.jing.cbor.md:139-147, 300-336, 375-380` | The storage/transport half (file/remote/DHT) is soundly resolved: metadata rides inside canonical CBOR bytes, so opaque-byte-copying backends carry it automatically. But the plan explicitly leaves the intake-stream half open: `dao.stream` Transit still carries no metadata, so metadata-bearing rows must go through direct `materialize!` calls, not the ordinary observer/stream pool. If code-as-tuples routes rows through the normal intake pool, metadata is lost upstream of Jing regardless of this plan. The ClojureDart `list`-producer metadata trap is also explicitly carried forward, not retired. D3 should state which ingestion path it actually uses.

4. **informational — intentional boundary widening, needs explicit sign-off** | `dao.jing.cbor.md:213-234` | Pushing portable `=`/`hash`/`compare` into `dao.space.index`'s datom comparators and `dao.space.query`'s builtins is the plan's only change outside `dao.jing*`. Not a violation (interpreter consuming a storage-adjacent utility, not the reverse), and well test-covered, but it's the widest blast radius in the plan and merits explicit review from `dao.space` owners.

## Confirmed properties (passed review)

- No hidden global state, no ambient registries — consistent with `datom.world.md`.
- Every integrity path fails loud, not silent (collisions, malformed CBOR, surrogates, oversized datagrams, mixed-version peers).
- Host-boundary discipline preserved: the function-shaped byte-store contract sits above the real stream-shaped I/O boundary, inheriting rather than newly violating the existing `dao.jing.remote` pattern; explicitly flagged as provisional, not a commitment.
- Backend/codec layering is otherwise clean — memory, PostgreSQL, S3 need zero CBOR knowledge (file backend's frame envelope is the one ambiguity, see finding 1).
- Numeric identity design is thorough and internally consistent, correctly cross-referenced against Boring's documented behavior.
- Migration risk (clean break, coordinated peer upgrade, full store rebuild) is named, not hidden; mixed-version failures are loud, never silent reinterpretation.
- Completion criteria are concrete and testable (frozen fixtures, cross-host byte identity, injectivity over the pathological-identifier class).

## Disposition

No blocking findings. Findings 1 and 4 integrated into
`docs/design/dao.jing.cbor.md` directly (commit `cb09b53`): finding 1 by
naming `dao.jing.file` as the explicit owner of its frame codec, finding
4 by flagging the `dao.space` boundary widening as needing separate
explicit sign-off. Finding 3 required no doc edit — the intake-metadata
caveat was already present in the plan's "Addressing and clean break"
section; it corrected the orchestrator's spoken summary, not the
document. Finding 2 corrected the orchestrator's D3 reasoning in
conversation; no `yin.vm.code-as-tuples.implementation-plan.md` edit was
made as a result (D3 remains open, un-re-answered).

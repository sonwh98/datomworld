# ADR 0001: DaoSpace is the Storage Boundary; Query is a Library

**Status:** Accepted (2026-06-25); ruling 4's mediator framing amended by
[ADR 0002](0002-share-governed-computation-not-data.md).

**Supersedes:** the "DaoSpace is the query engine" / "pure streaming bus" framings in
`docs/design/dao.space.v0.md` and earlier revisions of `docs/design/dao.space.md`.

> **Naming note (post-2026-06-30).** The storage boundary this ADR calls `dao.space` has
> since been renamed **`dao.jing`** (井, "well" — streams flow into it); the name `dao.space`
> now denotes the *tuple space* that emerges when the `dao.space.query` library matches over
> the store. Read every "`dao.space` (storage)" below as `dao.jing`. The decision is
> unchanged — only the names split. See `docs/design/dao.jing.md` (storage) and
> `docs/design/dao.space.md` (tuple space).

## Context

The triggering question was whether `dao.db` (a four-component Datomic-style store:
Storage, Transactor, Query engine, Database-as-value) is redundant once `dao.space` is "an
n-tuple space with pattern matching and Datalog."

Resolving it required deciding what `dao.space` *is*. Several framings were tried and
rejected:

- **DaoSpace = the query engine** (`dao.space/q`/`match` as space verbs, folding member
  logs into a `dao.db` value). Conflates the medium with the reader.
- **DaoSpace = a pure streaming bus / set of streams**, with no query at all and matching
  pushed to ad-hoc downstream agents. Drops the tuple-space invariant down into "run the
  right agent," and "set of streams" mistakes the transport for the space.

The fixed point that survived scrutiny: the only non-negotiable property of a tuple space
is that **agents can match tuples and query them with Datalog.** Match is the
single-clause case of Datalog, so this reduces to **unification over an indexed set of
datoms.** Streams, scoping, and content-addressing are *realization*, not definition.

## Decision

Adopt Datomic's **Transactor / Storage / Query** separation as *abstraction boundaries*
(interfaces, co-locatable in one process or distributed), mapped onto streams:

| Boundary | Realization |
|----------|-------------|
| **Transactor (write)** | **Decentralized** — every agent appending to its own `dao.stream` is its own transactor. No central writer, no global commit. |
| **Storage** | **`dao.space`** — the decentralized, durable, append-only datom log: a dynamic collection of autonomous member streams. Holds and serves datoms; does **not** match or query. |
| **Query (read)** | **`dao.space.query`** — an embeddable library (a Peer) any interpreter links. It reads `dao.space`, builds an in-memory index, and runs `match` / Datalog / `as-of`. Pure, per-interpreter, in-process. |

Consequent rulings:

1. **`dao.space` is *strictly* the Storage boundary.** Its read surface is a datom source
   (`members` / `datoms`), not a query API. There is no `dao.space/q`.
2. **`dao.db` is eliminated as a concept.** Its read half *is* `dao.space.query`; its
   write half (own transactor + storage backend) is replaced by streams. The query *code*
   survives as a library; `dao.db`-as-a-database does not.
3. **The tuple space = Storage + Query library together.** `dao.space` holds the tuples;
   `dao.space.query` makes them matchable. Neither alone is "the tuple space."
4. **Global associative match is the default.** The library reads the *global* store, so
   any interpreter matches everything. Scoping is a **predicate over content**, never an
   enumeration of producers (which would reintroduce the spatial coupling a tuple space
   exists to abolish), and is *not* a security mechanism — it is a trusted reader
   choosing to look at less. **Capability security** over dumb storage cannot be
   enforced by the embedded query library (an embedded reader has raw access to the
   bytes): it is enforced either per-stream at storage access (coarse, native) or, for
   fine-grained filtering, via a trusted query mediator that locally un-embeds query.
   See `dao.space.md`, "Global match and scoping."
5. **Indexing may be decentralized (proven correct; not yet the implementation).** The
   monoid homomorphism (below) proves that each agent indexing its own stream and peers
   **merging** per-stream indexes yields the same index as one agent indexing everything
   — so decentralized per-stream indexing is correct and permitted. Whether the
   implementation persists and merges per-stream indexes, or readers rebuild in memory
   per query (as the current `fold` sketch in `dao.space.md` does), is a realization
   choice deferred to Open Question 1.

## Why decentralized indexing is correct

`index` is a **monoid homomorphism**: an index is a sorted set of datoms, sorted-set union
(`merge`) is associative, commutative, and idempotent, so

```
index(S₁ ⊎ … ⊎ Sₙ) = merge(index(S₁), …, index(Sₙ))
```

— i.e. N agents indexing their own streams and merging produces the same *logical* index
as one agent indexing everything. This is a **G-Set CRDT** join (peers converge regardless
of order/grouping/redelivery), and structurally it is **external merge-sort** (per-stream
run formation + k-way merge) in its idempotent set variant, run continuously — a
**decentralized LSM-tree**.

Conditions: the index stores immutable datoms (cardinality-one and retraction resolved at
*query* time, not destructively at index time); stamping is the same deterministic
per-stream function (namespace = stream kickoff hash); "same" means logical content —
physical segment/tree layout may differ unless segmentation is canonicalized.

This homomorphism is the formal justification for "no central transactor": centralization
in Datomic is an efficiency choice (index once, share to many), not a correctness one.

## Consequences

**Positive**
- Storage stays dumb and swappable; all index/Datalog machinery sits above the boundary.
- Query is embedded, not a service — reads scale by adding readers (Peer model).
- The `dao.db` four-component design collapses cleanly: Storage→`dao.space`,
  Transactor→streams, Query→`dao.space.query`.
- Namespace tree mirrors the boundaries: `dao.stream(.file)` (transport) → `dao.space`
  (storage) → `dao.space.query` (query).

**Negative / costs**
- Log-only storage means a cold reader re-folds the whole log; recovering Datomic's
  "index once, reuse by many" requires persisting per-stream indexes (see Open Questions).
- Per-interpreter embedding can mean redundant indexing across readers unless per-stream
  indexes are published and merged.

## Open questions (deferred, non-blocking)

1. **Rebuild-per-query vs persisted per-stream index.** Whether `dao.space` persists each
   stream's index segments (owner builds once, peers merge, available when authors are
   offline) or readers rebuild in memory.
2. **Cross-stream `as-of`.** No global clock (`t` is per-stream); a cross-stream time
   bound needs a wall-clock instant or a vector of cursors.
3. **Content-addressed identity not yet load-bearing.** `yin/content.cljc` hashes via
   `pr-str` not canonical bytes; the index uses integer eids not `[namespace offset]`;
   content-addressable indexes would need canonical segmentation.

## References

- `docs/design/dao.space.md` — the storage-boundary design (current, canonical)
- `docs/design/dao.space.v0.md` — earlier framing (superseded; resource/geometry material still useful)
- `docs/design/dao.stream.md`, `docs/design/dao.stream.file.md` — transport
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, gauge/base framing

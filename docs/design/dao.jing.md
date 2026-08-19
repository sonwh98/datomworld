# DaoJing: The Content-Addressed Storage Observer

Status: implemented. The observer (`observer-state` / `observe-step!`) and the
plain-data content-store handles described here are the current
`src/cljc/dao/jing*.cljc` code. What remains open — the final canonical
encoding, durable observer checkpoints, explicit materialization
acknowledgement, garbage collection, and async hydration — is listed under
*Open items and current limitations*.

**Related documents:**

- `docs/design/dao.space.md` — the tuple space built by interpreters above
  storage
- `docs/design/dao.space.index.md` — the owner-side covered-index publisher
- `docs/design/dao.space.query.md` — the reader-side index consumer
- `docs/design/dao.jing.dht.md` — the DHT distribution backend
- `docs/design/dao.stream.md` — the append-only stream primitive
- `docs/design/dao.data.btree.md` — the covered-index node format
- `docs/agents/datom-spec.md` — the datom and tuple specification
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the storage-boundary
  decision
- `docs/datomic.md` — the Datomic storage architecture that informs the
  separation of storage and interpretation

## Definition

A `dao.jing` observes an explicit pool of intake `dao.stream` values and
materializes their elements into content-addressed key-value storage.

For every opaque payload `x` arriving through any stream in the pool, DaoJing
performs the same storage operation:

```text
bytes = canonical-encode(x)
key   = content-hash(bytes)
KV[key] = x                 ; insert if absent
```

DaoJing knows how to:

- observe the streams in its configured pool;
- retain the operational cursor needed to continue reading each stream;
- canonically encode an element;
- derive its content address;
- insert it idempotently into the materialization target; and
- retrieve content by its address.

DaoJing does not know whether an element is a datom, B-tree node, covered
index, manifest, program, image, or any other kind of value. Meaning belongs
to the interpreter that produced or consumes the content.

This makes DaoJing suitable storage for **any point in Datom.world's moduli
space of database interpreters**. DaoSpace can store covered indexes and
indexed snapshots in it; DaoField can store metric indexes; another interpreter
can store graph, document, columnar, model, or other materializations. All are
opaque values at this boundary.

DaoJing is itself an interpretation of DaoStream, but only at the
representation level: it interprets each emitted value as content to encode,
hash, and materialize in a key/value store. That operation assigns content
identity and retrieval semantics while remaining devoid of domain semantics.
DaoJing is therefore shared storage beneath the database moduli space, not a
semantic database point within it.

DaoJing maintains no membership registry, no mutable roots, no CAS records,
and no delete operation. Its only object of discourse is the strict content
address `:segment/sha256-<hash>`, and its only semantics are insert-if-absent
materialization plus content reads.

## The intake pool

The pool contains the streams through which content is submitted for
materialization. It is supplied explicitly when the observer state is
constructed. DaoJing performs no stream registration or discovery and
maintains no shared membership record.

The pool is an intake topology, not a semantic namespace. DaoJing does not use
the identity of the source stream when deriving an element's key, and it does
not attach source identity to the stored value:

```text
stream A emits x ─┐
                  ├── hash(canonical-encode(x)) ──► the same KV entry
stream B emits x ─┘
```

Identical content therefore converges on the same address regardless of which
pool member carried it. Pool order, source identity, and arrival order do not
change content identity.

DaoJing must distinguish pool members operationally long enough to maintain a
cursor for each one. That association is observer state only. It is not
written into the content address or materialized payload and has no semantic
meaning. In the implemented observer each member entry is plain data —
`{:stream <ref>, :cursor {:position n}, :status s}` — an operational record,
nothing more.

If a storage backend partitions content into physical buckets or shards, that
placement is a backend concern, normally derived from the content hash. It is
not derived from the source stream.

## Publication from an agent

An agent writes ordinary working data to its local `dao.stream`. That local
stream is not automatically a member of DaoJing's intake pool.

When the agent calls `dao.space.index/publish-index!`, the index publisher:

1. reads the agent's local stream;
2. constructs the covered indexes;
3. selects an intake stream from the DaoJing pool;
4. appends the immutable index payloads, including the top-level manifest, to
   that intake stream; and
5. returns the manifest and its content address required by the publishing
   layer.

```text
agent-local dao.stream
        │
        │ dao.space.index/publish-index!
        ▼
selected DaoJing intake stream
        │
        │ opaque payloads
        ▼
DaoJing observer ── canonical encode + hash ──► content-addressed KV
```

Selecting an intake stream is an operational routing decision. It does not
become part of an index payload's identity. The same index payload published
through a different intake stream receives the same address.

Publication acknowledges that every payload was appended to the selected
intake stream. It does not acknowledge that an asynchronous observer has yet
materialized those payloads; see *Open items and current limitations*.

Publication changes representation and access cost, not the meaning of the
covered data. Consumers whose explicit read coordinates name the published
manifest address can consume the persisted covered indexes.

## Materialization rule

The materialization is an idempotent union of content-addressed values. If
`H(x)` is the address of the canonical encoding of `x`, observing `x` means:

```clojure
(assoc-if-absent target (H x) x)
```

Re-observing the same value is a no-op. Observing equal values through
different pool streams is also a no-op after the first insertion. Consequently:

- replay is safe;
- pool streams may be consumed in any interleaving;
- duplicate publication is harmless;
- immutable content writes do not require cross-stream coordination; and
- a materialization can be reconstructed by replaying the available pool
  streams.

The implemented write is `dao.jing/materialize!`: it derives the address from
the payload alone (`segment-key`) and asks the backend's `:put-content-fn`
for an explicit verdict. `:inserted` means the value is durably stored now;
`:present` means an equal value is already stored there, in which case the
stored value is read back and verified. A collision in which an existing
address contains different canonical bytes is an integrity failure, not an
overwrite, and throws loudly.

### Canonical encoding

Content addressing is meaningful only when equal supported values produce the
same bytes on every participating platform. The canonical encoder is therefore
part of the storage contract even though the meaning of the encoded value is
not.

Canonicalization may understand representation-level structure such as maps,
sets, numbers, strings, and byte arrays. It must not understand domain concepts
such as datoms, index orders, manifests, or any notion of a root.

The target encoding is a canonical flat byte representation suitable for
cross-platform hashing and in-place reading. The current implementation uses
an order-normalized `pr-str` as a transitional encoder — deterministic and
order-insensitive, but not yet the pinned canonical byte encoding. This is the
first open item under *Open items and current limitations*.

## Storage ignorance

DaoJing assigns identity to opaque content but does not interpret the data
structure stored at that identity. In particular, it does not:

- traverse B-trees;
- distinguish EAVT, AEVT, AVET, or VAET nodes;
- merge indexes;
- evaluate Datalog;
- infer authorship or provenance from the intake stream;
- discover which streams or published indexes a query should read;
- construct a tuple space; or
- maintain a root, CAS, or delete surface.

Those responsibilities remain above the storage boundary. This allows a new
immutable data structure to be introduced without changing DaoJing.

## Physical intake versus semantic composition

Two forms of multi-stream work must remain separate:

- **Physical intake:** DaoJing observes every stream in its configured pool and
  content-addresses their opaque elements into the KV materialization.
- **Semantic composition:** `dao.space.query` receives an explicit collection
  of published sources and decides how their covered datoms are folded and
  interpreted.

DaoJing owns the first operation. It has no knowledge of the second. Observing
many intake streams does not mean that DaoJing semantically merges their
contents.

## Reads

The storage read operation resolves a strict content address to the exact
opaque value stored at that address. `dao.jing/get` accepts only
`:segment/sha256-<64 lowercase hex>` addresses; arbitrary keys and mutable
roots are outside DaoJing and throw before a backend is consulted.

A reader may access the target locally or through a remote transport
(`dao.jing.remote`, `dao.jing.dht`). Location changes how bytes are obtained,
not how their identity or meaning is determined.

Higher layers expose the semantic compositions the stream participates in. A
query reads a published index descriptor containing a serializable
content-store coordinate plus an immutable manifest address.
`dao.jing.coordinate/open!` interprets coordinates such as
`{:dao.jing/type :dao.jing/file :path ...}` or,
on the JVM, `{:dao.jing/type :dao.jing/remote :url ...}` into local handles;
unsupported coordinates fail closed. These coordinates are caller-supplied
values, not state inferred from storage. DaoJing does not infer a source or
coordinate from the stream that carried a payload, and no content address is
ever treated as a mutable root.

## Cursor tracking and recovery

An observer retains one cursor per active pool member and repeatedly calls
`ds/next`:

- `{:ok payload, :cursor next-cursor}`: materialize the payload and advance
  that stream's cursor;
- `:blocked`: no payload is currently available from that stream;
- `:end`: that stream is closed; and
- `:daostream/gap`: the cursor is behind the retention boundary and that
  stream requires resynchronization.

The implemented observer is `dao.jing/observer-state` (build the immutable
state for an explicit pool, one entry per member) and
`dao.jing/observe-step!` (poll the pool round-robin and process at most one
payload; on success it materializes the payload before advancing the member
cursor). The `:next` scheduling index keeps a continuously ready member from
starving another. Scheduling does not affect the resulting content set,
because materialization is content-addressed, commutative, and idempotent.

A durable checkpoint records operational progress — a cursor per pool entry —
separately from the content-addressed KV data. Source identity may be needed
by a checkpoint mechanism to resume the correct transport, but it must not
enter the materialized content identity. Persisting such checkpoints and
running the observer loop over time are open items (see below).

## Resource lifecycle

The observer itself is a value: `observe-step!` owns no resources and has
nothing to close — streams and storage handles retain responsibility for
releasing their transport, file, database, or network resources.
`dao.jing/close!` delegates to a handle's optional `:close-fn`; a handle
without one has nothing to release. A long-running runner that drives the pool
loop, and its resource policy, are open items (see below).

## Implemented surface

The current `src/cljc/dao/jing*.cljc` code implements the architecture above
directly.

**Content-store handles are plain data.** A handle is a map
`{:put-content-fn f, :get-content-fn g, :close-fn c?}`; the backend effects
are explicit functions, not a protocol or hidden state. `dao.jing/materialize!`
and `dao.jing/get` dispatch through the handle, and `close!` through its
optional `:close-fn`.

**Content-store coordinates are transportable data.**
`dao.jing.coordinate/open!` is the explicit interpretation boundary from a
coordinate to a live handle. Its closed dispatch recognizes
`:dao.jing/file` on every supported platform and `:dao.jing/remote` on the
JVM. There is no name-to-handle registry; adding a backend is an explicit code
change to the coordinate interpreter.

Implemented backends:

- `dao.jing.mem/create-content-mem` — an ephemeral, thread-safe,
  content-addressed in-memory store. Put is an atomic insert-if-absent; an
  address already holding the same payload reports `:present` and is never
  overwritten.
- `dao.jing.file/create-content-file` — a content-addressed store backed by an
  append-only log stream. Each log record is `[address payload]`, written
  through a write lock, acknowledged only after the log is flushed, and
  replayed on open to rebuild the in-memory content map. The framing layer
  truncates an incomplete tail before replay; Jing then fails closed on any
  complete record that cannot be decoded, validated, or matched to its content
  address.
- `dao.jing.remote` — exposes a local content handle as the `:jing/put-content`
  and `:jing/get-content` RPC ops (`default-handlers`) and wraps an RPC client
  as a content handle (`content-client`); `connect-content!` is the
  synchronous WebSocket constructor, JVM-only. `:jing/get-content` returns the
  exact wire envelope `{:found? boolean, :value value}`; caller-local
  not-found sentinels never cross the RPC boundary.
- `dao.jing.dht/create-content-dht` and
  `dao.jing.dht.node/create-content-dht-udp` — the distributed backend over an
  `IDhtNet` transport; see `docs/design/dao.jing.dht.md`.

**The observer is implemented.** `observer-state` and `observe-step!` provide
the explicit intake-pool walk described in *Cursor tracking and recovery*,
with no atoms, globals, registration, or discovery. The source stream never
enters an address or a stored value.

**Content addressing is implemented, transitionally.** `content-hash` hashes
the order-normalized print of a value; `segment-key` mints
`:segment/sha256-<hash>` addresses; `segment-address?` is the strict address
test the backend layer enforces. As recorded in *Canonical encoding*, the
encoder is transitional until the pinned canonical byte encoding lands.

## Open items and current limitations

- **Canonical encoding.** The order-normalized `pr-str` encoder must be
  replaced by a pinned, cross-platform canonical byte encoding. Until then,
  content addresses are portable only between implementations sharing the
  exact print rule; when the encoding lands, `content-hash`, `segment-key`,
  and every minted address change together.
- **Durable observer checkpoints / long-running runner.** `observer-state`
  and `observe-step!` are single-step and in-process. The checkpoint format
  for resuming pool cursors across restarts, and a runner that drives the
  loop over time, remain to be built.
- **Explicit materialization acknowledgement.** A publisher observes only
  that its payloads were appended to an intake stream. The mechanism by which
  it observes that those payloads have been materialized must be expressed
  explicitly, potentially as a response stream.
- **Garbage collection.** Content reachability and reclamation belong to a
  higher-level retention policy; immutable content otherwise accumulates
  forever.
- **Async hydration.** Readers of remote or async B-tree content use the
  hydration adapter (`dao.data.btree.storage/hydration-storage` and
  `hydrate!`), but the async variants (`hydrate-async`, `store-tree-async`)
  are deferred until an async DaoJing backend exists. See
  `docs/design/dao.data.btree.md` §5.4.

## Lineage

DaoJing combines two constraints:

- **Datomic:** storage retains immutable content while an embeddable reader
  interprets indexes and queries above it.
- **Plan 9:** location and transport are properties of streams and handles,
  not of the values carried through them.

The result is a deliberately restricted observer: a pool of streams carries
opaque values in, canonical content addresses identify them, and a KV
materialization makes them retrievable. All semantic structure remains in the
layers that publish and consume those values.

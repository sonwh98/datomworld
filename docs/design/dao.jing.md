# DaoJing: The Content-Addressed Storage Observer

Status: target architecture. The current implementation is transitional; see
*Implementation status* below.

**Related documents:**

- `docs/design/dao.space.md` — the tuple space built by interpreters above
  storage
- `docs/design/dao.space.index.md` — the owner-side covered-index publisher
- `docs/design/dao.space.query.md` — the reader-side index consumer
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

## The intake pool

The pool contains the streams through which content is submitted for
materialization. It is supplied explicitly when the observer is constructed or
run. DaoJing performs no stream registration or discovery and maintains no
shared membership root such as `:root/members`.

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
cursor for each one. That association is observer/checkpoint state only. It is
not written into the content address or materialized payload and has no
semantic meaning.

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
5. returns the content addresses required by the publishing layer.

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

Publication changes representation and access cost, not the meaning of the
covered data. Consumers whose explicit sources include the published address
can consume the persisted covered indexes.

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

A collision in which an existing address contains different canonical bytes
is an integrity failure, not an overwrite. DaoJing must report it loudly.

### Canonical encoding

Content addressing is meaningful only when equal supported values produce the
same bytes on every participating platform. The canonical encoder is therefore
part of the storage contract even though the meaning of the encoded value is
not.

Canonicalization may understand representation-level structure such as maps,
sets, numbers, strings, and byte arrays. It must not understand domain concepts
such as datoms, index orders, roots, or manifests.

The target encoding is a canonical flat byte representation suitable for
cross-platform hashing and in-place reading. The current implementation's
order-normalized `pr-str` hashing is transitional and is not yet that final
encoding.

## Storage ignorance

DaoJing assigns identity to opaque content but does not interpret the data
structure stored at that identity. In particular, it does not:

- traverse B-trees;
- distinguish EAVT, AEVT, AVET, or VAET nodes;
- merge indexes;
- evaluate Datalog;
- infer authorship or provenance from the intake stream;
- discover which streams or published indexes a query should read; or
- construct a tuple space.

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

The storage read operation resolves a content address to the exact opaque value
stored at that address. A reader may access the target locally or through a
remote transport. Location changes how bytes are obtained, not how their
identity or meaning is determined.

Higher layers may expose mutable names, explicit query roots, capabilities, or
discovery services. Those mechanisms refer to published content, but they are
not part of DaoJing's content materialization rule. In particular, DaoJing does
not infer a mutable root from the stream that carried a payload.

## Cursor tracking and recovery

An observer retains one cursor per active pool member and repeatedly calls
`ds/next`:

- `{:ok payload, :cursor next-cursor}`: materialize the payload and advance
  that stream's cursor;
- `:blocked`: no payload is currently available from that stream;
- `:end`: that stream is closed; and
- `:daostream/gap`: the cursor is behind the retention boundary and that
  stream requires resynchronization.

The observer may poll pool members round-robin or use a multiplexed iterator.
Scheduling does not affect the resulting content set because materialization
is content-addressed, commutative, and idempotent.

A durable checkpoint records operational progress, for example a cursor per
pool entry, separately from the content-addressed KV data. Source identity may
be needed by the checkpoint mechanism to resume the correct transport, but it
must not enter the materialized content identity.

## Resource lifecycle

The observer owns the lifecycle of its pool-reading loop. Individual stream
and storage handles retain responsibility for releasing their transport,
file, database, or network resources. Closing the observer stops intake and
closes resources according to the ownership policy supplied at construction.

## Implementation status

The architecture above is not fully implemented by the current
`src/cljc/dao/jing*.cljc` code.

The current implementation is a transitional handle-map KV facade:

- `jing/cas!`, `jing/get`, `jing/delete!`, and `jing/close!` dispatch on
  functions stored in a handle map;
- the generic and file paths interpret `[k :cas expected value]` records;
- memory and file handles each expose one `:stream` rather than an observer
  over an explicit pool;
- `segment-key` hashes a value only when a caller invokes it; observation does
  not automatically hash every stream element;
- the remote adapter delegates KV operations through RPC; and
- the DHT adapter routes keys and mutable-root operations directly.

Migration to the architecture specified here requires:

1. an explicit pool observer with one operational cursor per pool member;
2. automatic canonical encoding and content hashing for every observed
   payload;
3. an idempotent content insertion operation;
4. publication through a selected intake stream rather than direct mutation
   of a Jing KV handle; and
5. removal of source-stream identity from materialized keys and values.

The existing `absent`/CAS machinery may remain useful inside a concrete KV
target as an implementation technique for insert-if-absent. It is not the
semantic record language of the intake streams in the target architecture.

## Current limitations and open decisions

- **Canonical bytes:** order-normalized `pr-str` must be replaced by a pinned,
  cross-platform canonical encoding.
- **Pool lifecycle:** the construction, update, and checkpoint format of an
  explicit intake pool remains to be implemented.
- **Publication acknowledgement:** the mechanism by which a publisher observes
  that submitted content has been materialized must be expressed explicitly,
  potentially as a response stream.
- **Garbage collection:** content reachability and reclamation belong to a
  higher-level retention policy; immutable content otherwise accumulates.
- **Remote hydration:** readers of remote B-tree content need an explicit
  hydration or asynchronous retrieval boundary.

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

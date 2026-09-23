# DaoJing: The Content-Addressed Storage Observer

Named for 井 (jǐng), the well: shared storage everyone draws from, holding whatever is poured in and giving it back unchanged. Not 经, the canon, and not a reference to any scripture.

Status: implemented. The observer (`observer-state` / `observe-step!`) and the
plain-data content-store handles described here are the current
`src/cljc/dao/jing*.cljc` code. What remains open — the final canonical
encoding, byte-array addressing, metadata-carrying backends and transports,
durable observer checkpoints, explicit materialization acknowledgement, the
content write path as an effect stream, garbage collection, and async
hydration — is listed under *Open items and current limitations*.

**Related documents:**

- `docs/design/dao.space.md` — the tuple space built by interpreters above
  storage
- `docs/design/dao.space.index.md` — the owner-side covered-index publisher
- `docs/design/dao.space.query.md` — the reader-side index consumer
- `docs/design/dao.jing.dht.md` — the DHT distribution backend
- `docs/design/dao.stream.md` — the append-only stream primitive
- `docs/design/dao.data.btree.md` — the covered-index node format
- `docs/design/datom.md` — the datom and tuple specification
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
address `:segment/<algorithm>-<digest>`, and its only semantics are
insert-if-absent materialization plus content reads.

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
`{:stream <v2 reader handle>, :cursor <opaque>, :status s}` — an operational
record, nothing more. `observer-state` rejects a member that lacks a cursor
or a v2 reader before any operation. Besides successful observation, the only
way a member's cursor changes is through `adopt-cursor`.

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
for an explicit verdict. A backend validates before it writes: the address must
be a segment address and must hash to the payload, else it throws and stores
nothing. `:inserted` means the value is durably stored now;
`:present` means an equal value is already stored there, in which case the
stored value is read back and verified. A collision in which an existing
address contains different canonical bytes is an integrity failure, not an
overwrite, and throws loudly. `nil` is a legal payload distinct from absence:
`get` takes a caller-supplied not-found and returns stored `nil` as `nil`.

### Canonical encoding

Content addressing is meaningful only when equal supported values produce the
same bytes on every participating platform. Distinct values must address
distinctly, both across types (`42` and `"42"` do not collide) and within one
type (`{:a 1}` and `{:a 2}` do not). Minted addresses are readable EDN: the
name never starts with a digit, and a key survives `pr-str` → `read-string`.
The canonical encoder is therefore part of the storage contract even though the
meaning of the encoded value is not.

Canonicalization may understand representation-level structure such as maps,
sets, numbers, strings, and byte arrays. It must not understand domain concepts
such as datoms, index orders, manifests, or any notion of a root.

The target encoding is a canonical flat byte representation suitable for
cross-platform hashing and in-place reading. The current implementation uses
an order-normalized, metadata-aware hand printer (`dao.jing/order-normalize`
and `canonical-print`) as a transitional encoder — deterministic and
order-insensitive, but not yet the pinned canonical byte encoding. This is the
first open item under *Open items and current limitations*.

The transitional encoder's current contract, precisely: collection metadata
(on maps, sets, vectors, lists, and seqs) is address-significant, except
reader-position keys (`:line`, `:column`, `:end-line`, `:end-column`), which
are stripped before hashing, and empty metadata, which is dropped rather than
treated as distinct from no metadata. Scalar metadata (on symbols — no
portable host lets a keyword carry metadata) is not address-significant
— it is silently ignored, a known
residual pending the pinned canonical byte encoding. Lists and seqs of equal
content share one address (`=` calls them equal and both print the same way);
vectors, sets, and maps are each their own type and never collide with
another, for any non-pathological scalar (see the pathological-symbol
residual under *Open items and current limitations*). Records are not a
supported payload: `content-hash` throws rather
than silently addressing a record as its equal plain map, since the
participating hosts cannot agree on how to print one. `materialize!`'s
`:present` read-back is verified by `segment-matches?`, ensuring that
a metadata-only mismatch is caught as a real collision.

## Multihash Content Addressing & Algorithm Registry

DaoJing implements a closed, immutable algorithm registry providing
multi-algorithm content-addressing across JVM, Node/CLJS, and ClojureDart.

Six core concepts are strictly distinguished:

1. **Algorithm:** A registered cryptographic hash function identified by a
   keyword (`:blake3` or `:sha256`). The registry is closed and immutable:
   - `:blake3`: default minting algorithm; produces 32-byte (256-bit) digests.
   - `:sha256`: permanent first-class selectable algorithm; produces 32-byte
     digests.
2. **Digest:** The lowercase hexadecimal representation of the raw digest
   bytes computed over `canonical-bytes(payload)`.
3. **Address:** The namespace-qualified keyword
   `:segment/<algorithm-id>-<digest-hex>` (81-character keyword
   beginning with `:segment/blake3-` or `:segment/sha256-`).
   Validated by `dao.jing/segment-address?` and parsed by
   `dao.jing/parse-segment-address`.
4. **Minting:** Deriving a new address for content (`dao.jing/content-hash`,
   `dao.jing/segment-key`, `dao.jing/materialize!`). Implicit minting defaults
   to `:blake3`. Explicit minting supports `{:algorithm :sha256}` across public
   APIs.
5. **Verification:** Validating that an opaque payload matches an existing
   address using `dao.jing/segment-matches?`.
   - **Verification must be address-directed; minting primitives are not
     validators.**
   - The address carries its own algorithm identifier. `segment-matches?` parses
     the address, computes canonical bytes, digests them using the algorithm
     indicated by the address, and compares. It never consults
     `default-hash-algorithm` and never throws on encoder refusal or
     malformed addresses.
6. **Copying:** Transferring or caching addressed content across layers (such as
   DHT caching, B-tree hydration, and `store-tree-async` flushing).
   - **Copying addressed content preserves the address-carried algorithm.** An
     address minted under `:sha256` remains under `:sha256` when cached or
     hydrated.

### Host Dependencies and Licenses

Pinned BLAKE3 provider dependencies across all three hosts:
- **JVM:** `io.github.rctcwyvrn/blake3 1.3` (MIT License)
- **Node/CLJS:** `@noble/hashes 2.4.0` (MIT License)
- **ClojureDart:** `blake3_dart 1.0.0` (MIT License)

SHA-256 uses native host implementations on JVM (`java.security.MessageDigest`),
Node/CLJS (`goog.crypt.Sha256`), and pure ClojureDart.

### Maintenance Obligations: Call-Site Classification & AST Lint Guard

Future maintenance across the repository carries two explicit obligations
governed by `docs/design/dao.jing.call-site-classification.md`:

1. **Call-Site Classification:** Any new call site dealing with content
   addresses must be classified into one of the four established classes:
   - **Class 1 (Minting):** Fresh content addressing via `content-hash` or
     `segment-key` (defaulting to `:blake3` or explicit `{:algorithm ...}`).
   - **Class 2 (Wire / Handshake):** Protocol framing or manifest candidate
     entries where schema addresses are stored.
   - **Class 3 (Validation):** Must ALWAYS use `dao.jing/segment-matches?`
     directed by the existing address. Never re-mint with default minting
     primitives and compare with `=`.
   - **Class 4 (Copy / Hydration):** Must ALWAYS preserve the address-carried
     algorithm when copying, caching, or flushing content.
2. **Architectural AST Lint Guard:** The active test
   `architectural-lint-sweeps-production-sources` in
   `test/dao/jing/hash_registry_contract_test.cljc` sweeps all production
   `.cljc` sources to forbid equality validation (`=`) against
   `dao.jing/content-hash` or `dao.jing/segment-key`. This test runs as part of
   the standard suite (`bb test:clj`) to prevent regressions to implicit
   mint-and-compare validation.

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
registered `:segment/<algorithm>-<digest>` addresses; arbitrary keys and mutable
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

The observation step is `dao.stream.observe`, shared with `forward` and
the VM. `dao.jing/observe-step!` polls the pool round-robin and processes at
most one payload per pool walk.

The `dao.stream.observe/step` core runs the materialization effect before
advancing the cursor. If materialization throws, the exception propagates
before the cursor is advanced, so the caller's state is untouched and the
same payload is reprocessed from the same cursor once the backend succeeds.

The pool's signals and cursor disciplines are:

| signal                                                    | when                                                       | extra keys                                      | member cursor            |
| --------------------------------------------------------- | ---------------------------------------------------------- | ----------------------------------------------- | ------------------------ |
| `:dao.stream/ok`                                          | a payload was materialized                                 | `:address`                                      | successor, from the step |
| `:dao.stream/blocked`                                     | empty pool, or every non-ended member blocked              | —                                               | unchanged                |
| `:dao.stream/end`                                         | every member has ended                                     | —                                               | unchanged                |
| `:dao.stream/gap`                                         | member `i`'s position was evicted                          | `:member i`, `:cursor` (the step's `:recovery`) | unchanged                |
| `/cursor-mismatch`, `/invalid-cursor`, `/transport-error` | member `i`'s read failed, or answered outside the contract | `:member i`, `:result` (the step's `:read`)     | unchanged                |

An unrecognized transport answer is reported as a defect signal under
`:dao.stream/transport-error`, carrying the classified read under `:result`
with the raw answer retained inside it, and no action is taken
beyond declining to advance. Recovery is the caller's decision, made by
`adopt-cursor` with the reported `:cursor`.

The `:next` scheduling index keeps a continuously ready member from
starving another. Scheduling does not affect the resulting content set,
because materialization is content-addressed, commutative, and idempotent.

## Resource lifecycle

The observer itself is a value: `observe-step!` owns no resources and has
nothing to close — streams and storage handles retain responsibility for
releasing their transport, file, database, or network resources.
`dao.jing/close!` delegates to a handle's optional `:close-fn`; a handle
without one has nothing to release. A backend's close is idempotent; after
close, every entry point throws, and stored content is neither cleared nor
rewritten. A long-running runner that drives the pool loop, and its resource
policy, are open items (see below).

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
- `dao.jing.file/create-content-file` — a content-addressed store backed by a
  private framed append-only file. Each log record is `[address payload]`, written
  through a write lock, acknowledged only after the log is flushed, and
  replayed on open to rebuild the in-memory content map. The framing layer
  truncates an incomplete tail before replay; Jing then fails closed on any
  complete record that cannot be decoded, validated, or matched to its content
  address. The store guarantees idempotent close, throws after close, and
  serializes concurrent puts with exactly one record written.
- `dao.jing.remote` — over DaoStream v2, both halves JVM-only: the
  constructor `connect-content!` returns a content handle over a live v2
  attachment, and `serve-content!` serves `default-handlers` — or any
  `{op fn}` map — at a WebSocket endpoint. The synchronous client is a
  blocking driver as host policy over the portable non-waiting call step:
  a JVM thread polls and sleeps between advances, and the cadence
  (`:poll-interval-ms`), the connect deadline (`:connect-timeout-ms`) and
  the request deadline (`:request-timeout-ms`) are options of the
  constructor, not constants of the step. The constructor returns only
  after the attachment reports `/established`; a failed open — a terminal
  lifecycle, the deadline, or an interruption of the establishment loop's
  sleep — guarantees only that the handle is closed and nothing escapes to
  the caller — on
  the JVM a close before the socket opens does not tear down the JDK's
  establishment, so a peer that accepts TCP and never completes the
  upgrade costs one held connection per attempt for the process lifetime
  (the establishment-cancel gap `dao.stream.ws.md` records). The traffic
  cursor is minted at `:dao.stream/newest` **before** `attach!`, because
  the host may deposit the establishment event at any moment after
  attaching and a cursor minted later would sit past it, hanging every
  connect to its timeout. A URL is `ws://host[:port][/path]`, port
  defaulting to 80 and an absent path naming `/jing`; `wss://`, a missing
  host, a port that is not a positive integer, and a bracketed IPv6
  authority throw before any socket. The wire vocabulary is
  `:jing/put-content` answering `:inserted`/`:present` and
  `:jing/get-content` answering exactly `{:found? boolean :value v}`, so
  a stored `nil` is distinguishable from absence. One client carries one
  locally awaited call — calls serialize under the client's lock — while
  `close!` runs outside that lock and is safe during an in-flight call,
  which then throws the terminal reason. A timed-out call retires its
  bookkeeping (the id leaves the outstanding table and its late response
  is dropped as unsolicited) but not the remote execution, which the
  server may still be running. Bookkeeping is bounded by the one call in
  flight: every exit of the blocking driver — return, timeout, terminal,
  an immediate refusal at the writer, or an interruption of its poll sleep
  — drains its completions and diagnostics before storing state, because an
  exit that stored nothing would let the next call reuse the interrupted
  call's request id and take its late response as an answer. An interrupted
  call retires the same way a timed-out one does, and the thread's interrupt
  flag is re-asserted before the throw. The timing options themselves are
  validated at the driver's entry, before anything is sent: an argument
  defect must throw before the wire, or it strands a request whose id the
  next call would reuse. The portable value domain binds in
  both directions: a payload outside it is refused before anything is
  sent, and a handler result outside it is answered as a correlated
  `:dao.jing.remote/non-portable-result` error rather than a timeout.
  After a terminal lifecycle every call throws with that reason and the
  handle is never rebound; reattachment is the caller's, by opening a new
  coordinate. Diagnostics have no outlet under a blocking driver and are
  drained and dropped at every step. Server `stop!` detaches every
  session, releases the listener, and is idempotent; handlers run in the
  server's single driver thread, so a handler that never returns stalls
  every session.
- `dao.jing.dht/create-content-dht` and
  `dao.jing.dht.node/create-content-dht-udp` — the distributed backend over an
  `IDhtNet` transport; see `docs/design/dao.jing.dht.md`.

**The observer is implemented.** `observer-state` and `observe-step!` provide
the explicit intake-pool walk described in *Cursor tracking and recovery*,
with no atoms, globals, registration, or discovery. The source stream never
enters an address or a stored value.

**Content addressing is implemented via the closed multihash registry.**
`content-hash` digests canonical bytes under the selected algorithm (:blake3
default, :sha256 selectable); `segment-key` mints
`:segment/<algorithm>-<digest>` addresses; `segment-address?` is the strict
address test the backend layer enforces; `segment-matches?` is the total
address-directed verification predicate. As recorded in *Canonical encoding*,
the encoder is transitional until the pinned canonical CBOR encoding lands.

## Open items and current limitations

- **Canonical encoding.** The order-normalized hand-printer encoder must be
  replaced by a pinned, cross-platform canonical byte encoding. Until then,
  content addresses are portable only between implementations sharing the
  exact print rule; when the encoding lands, `content-hash`, `segment-key`,
  and every minted address change together. Three residuals of the
  transitional encoder are deferred to that landing: scalar (symbol)
  metadata is not address-significant; pathological symbols whose print text
  mimics another value's print (e.g. `(symbol "42")` vs `42`) can collide;
  and ambient print-var bindings (`*print-readably*` and similar) still
  reach scalar bytes, since `canonical-print` delegates scalars to `pr-str`
  — collection structure and order are rendered by `canonical-print`
  itself, so only scalar leaves reach the host printer.
- **ClojureDart's `list` mints metadata.** On ClojureDart, `(list ...)` and
  `(apply list ...)` return a list carrying `cljd.core`'s own reader metadata
  (`{:line … :column … :end-line … :end-column … :tag PersistentList}`).
  `order-normalize`, `yin.vm`'s semantic-bytecode projection, and the
  ClojureDart Transit decoders (`dao.stream.transit.cljd` behind
  `dao.stream.ws`'s incoming frames, and the older `dao.stream.transit`)
  clear metadata on the lists they mint, so neither normalization nor a list
  decoded off the wire fabricates it. Any other Dart code that builds a
  payload with `list` still hands `dao.jing` that metadata, and its `:tag`
  survives the reader-position strip, so the payload addresses differently
  from the equal list built on another host. Each such constructor must clear
  it the same way (`(with-meta (apply list xs) nil)`) until the ClojureDart
  defect is fixed upstream or the pinned canonical encoding decides the fate
  of metadata.
- **Byte arrays are hashed by identity, not content.** `dao.jing.md` lists
  byte arrays as a supported representation-level type, but the transitional
  encoder's scalar branch falls through to `pr-str`, which on the JVM prints
  a byte array as an identity-bearing object literal (`#object["[B" 0x...
  "..."]`); other hosts print their own identity-bearing form. Two
  content-equal byte arrays currently mint different addresses. No
  current producer emits byte-array payloads, so this is latent; it must be
  fixed (a proper byte-array print rule, or promotion into the pinned
  canonical byte encoding) before any producer relies on byte-array content
  addressing.
- **Backends and transports must fail closed on metadata they cannot carry.**
  Metadata is now address-significant in the transitional encoder, but no
  durable backend or wire codec in the system carries metadata today: the
  file backend (`dao/jing/file.cljc`) writes payloads with plain `pr-str`,
  and the transit codec (`dao/stream/transit.cljc`) states metadata is not
  on the wire and its portable-value check admits metadata-bearing
  collections without complaint. A metadata-bearing payload therefore passes
  `materialize!`'s put validation, is written with its metadata silently
  dropped, and fails loudly — the whole store becomes unopenable — on replay,
  because the replayed frame no longer hashes to its claimed address. This is
  not reachable today (no producer emits collection metadata yet), and the
  failure mode is loud rather than silently corrupting, so it is not a
  blocker for the encoder fix itself. It becomes blocking the moment any
  producer (the code-as-tuples pipeline's row/metadata-bearing content, once
  that work starts emitting metadata-bearing literals) begins emitting
  metadata-bearing payloads. Every backend and transport must, before that
  point, either carry metadata through or explicitly refuse a payload whose
  round trip through its own codec would not hash back to its address.

  **Status 2026-09-18 (U10):** the storage half is closed. The file
  backend's put now applies exactly this rule — a payload whose
  `pr-str`/EDN round trip does not hash back to its address (any
  metadata-bearing payload, since `pr-str` drops collection metadata the
  address keeps) is refused before a byte is written; the memory backend
  carries values verbatim, so metadata round-trips there. The transport
  half is answered differently: Jing content never crosses a transport as
  a bare payload value. `dao.jing.stream`'s boundary adapter wraps the
  canonical bytes (`dao.jing/canonical-bytes`, the exact bytes the address
  digests) as a CBOR byte string on the `dao.stream.cbor` profile and as a
  named vector of octets on `dao.stream.transit-json`, so no transport's
  value domain — metadata-blind or not — re-encodes the content.
- **Durable observer checkpoints / long-running runner.** `observer-state`
  and `observe-step!` are single-step and in-process. The checkpoint records,
  per member, the stream coordinate plus the transport-minted cursor; the
  coordinate is operational and never enters an address (serializability TBD in
  the contract). A runner that drives the loop over time remains to be built.
- **Explicit materialization acknowledgement.** A publisher observes only
  that its payloads were appended to an intake stream. The mechanism by which
  it observes that those payloads have been materialized must be expressed
  explicitly, potentially as a response stream.
- **The content write path as an effect stream.** A synchronous handle that
  accepts a write and blocks until durable keeps the storage coupling. The
  content write path as an effect stream (durability as data, `materialize!`
  no longer returning an address synchronously) is deferred to a larger
  write-path redesign.
- **Garbage collection.** Content reachability and reclamation belong to a
  higher-level retention policy; immutable content otherwise accumulates
  forever.
- **Async hydration.** Readers of remote or async B-tree content use the
  hydration adapter (`dao.data.btree.storage/hydration-storage` and
  `hydrate!`), but the async variants (`hydrate-async`, `store-tree-async`)
  are deferred until an async DaoJing backend exists. See
  `docs/design/dao.data.btree.md` §5.4.

  The remote half of that deferral is the **stepped client**: a
  non-blocking remote handle with the `request-put` / `request-get` /
  `request-materialize` / `step` / `abandon` shape — a client the caller
  steps on a host that cannot wait. It is owed to the async hydration
  work (`docs/design/dao.data.btree.md` §5.4), not to the v1 deletion: it
  needs multi-id dispatch and per-materialization records rather than the
  blocking driver's per-id step, and it forces a consumer change on
  B-tree hydration.

  **Status 2026-09-18 (stepped client):** the client itself is built —
  `dao.jing.remote.step` wraps one `dao.stream.rpc` client state with
  per-materialization records and id routes, `step` advancing in the
  recorded fixed order (retry unsent, poll, terminal-abandon, issue
  verify reads in put-id order, drain-and-route), the `:present` verify
  hop hashing the read-back against the address exactly as
  `materialize!` does, one published completion per materialization
  carrying its put id, and no payload retained or published anywhere.
  What remains owed to `dao.data.btree.md` §5.4 is the consumer side:
  an async backend over this client, `hydrate-async`, and
  `store-tree-async`.

  **Status 2026-09-18 (consumer side):** built.
  `dao.jing.remote.async/async-content` is the async backend: it owns one
  stepped state as its single step owner, queues requests from any
  caller, and drives `step` with a self-rescheduling pump (setTimeout /
  Dart `Timer` / the JVM delayed executor, or an injected `:schedule`),
  answering each request's callback exactly once with its published
  completion. `dao.data.btree.storage/hydrate-async` and
  `store-tree-async` consume it through a `hydration-storage` whose
  source is that handle; see `dao.data.btree.md` §6 Phase 4 notes.

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

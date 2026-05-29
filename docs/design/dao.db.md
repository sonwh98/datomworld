# DaoDB: Four-Component Architecture

## Overview

DaoDB is the native tuple store for datom.world. It stores immutable 5-tuple datoms
`[e a v t m]`, maintains five sorted-set indexes (EAVTM, AEVTM, AVETM, VAETM, MEAVT), and provides
a Datalog query engine (`d/q`) over those indexes.

The architecture cleanly separates four concerns: **Storage**, **Transactor**, **Query Engine**,
and **Database-as-a-Value** — each a distinct logical component defined by protocol boundaries.
"Logical" means the boundary is a protocol interface, not a deployment constraint. All four
can run in one process (in-memory, no serialization overhead) or be deployed across separate
machines communicating via network. Same protocols, different topology.

---

## Data Model

Datoms are 5-tuples: `[e a v t m]`.

- `e` — entity ID (negative = tempid, positive = committed)
- `a` — attribute (namespaced keyword)
- `v` — value (ground value or entity reference)
- `t` — transaction ID (monotonic integer, stream-local)
- `m` — metadata entity reference (always integer, never nil; `0` = no metadata)

Schema is itself datoms. System entities `0–1024` carry universal meaning. User entities
start at `1025`. See `CLAUDE.md` for the full spec.

**Five indexes**, each a sorted set of datoms sorted by a different leading-component order:

| Index | API keyword | Sort order          | Insertion condition          | Primary use                        |
|-------|-------------|---------------------|------------------------------|------------------------------------|
| EAVTM | `:eavt`     | e, a, v, t, m       | always                       | All attributes of an entity        |
| AEVTM | `:aevt`     | a, e, v, t, m       | always                       | All entities with a given attribute|
| AVETM | `:avet`     | a, v, e, t, m       | indexed or ref attr          | Find entity by attribute + value   |
| VAETM | `:vaet`     | v, a, e, t, m       | ref attr only                | Reverse reference lookup           |
| MEAVT | `:meat`     | m, e, a, v, t       | m != 0                       | Provenance and derivation queries  |

EAVTM and AEVTM are unconditional. AVETM, VAETM, and MEAVT use conditional insertion to
keep their sizes bounded: AVETM only for attributes marked `:db/index true` or typed as
`:db.type/ref`; VAETM only for `:db.type/ref` attributes; MEAVT only when `m != 0`
(m=0 means "no metadata" — not useful to index).

MEAVT includes `v` so distinct datoms with the same `(m, e, a, t)` do not collide
inside the sorted set. Provenance queries still use `(m)` and `(m, e)` as prefix
seeks.

**Seek helpers for MEAVT:**

```clojure
(seek-m  [meat m])     ; all datoms tagged with m=X
(seek-me [meat m e])   ; all datoms tagged with m=X on entity e
```

### Provenance Queries

The `m` component is first-class in DaoDB. Two hot query patterns depend on it:

- **Exclude derived datoms** (`m=1`, `:db/derived`): every content-hash computation
  must filter out derived datoms before hashing. Without MEAVT this would be a full
  scan of all indexes on every hash operation.
- **Provenance lookup** (`m=event-eid`): given a macro-expansion event or a
  transaction provenance entity, find every datom it caused. Used for audit trails,
  incremental recomputation, and access-control enforcement.

MEAVT makes both patterns O(log n) instead of O(n).

---

## Four-Component Model

DaoDB's architecture cleanly separates concerns via four protocol boundaries:

### Storage (`IDaoStorage`)

Append-only log of datom segments, keyed by transaction range.

```clojure
(defprotocol IDaoStorage
  (write-segment! [this segment])      ; segment = [t added retracted]; returns updated db
  (read-segments  [this t-min t-max])  ; returns seq of [t added retracted]
  (latest-t       [this]))             ; highest committed t
```

Conceptual durable storage is dumb: no schema awareness, no query capability. The only
invariant is that segments are immutable once written (append-only log). The current
`InMemoryDaoDB` implementation wraps storage and index cache in one value, so
`write-segment!` also applies the segment and rebuilds derived caches before returning
the updated db. Pluggable backends:

- **In-memory sorted-set** — current implementation, used in tests and development
- **File** — segments written to disk, indexed by t-range
- **Remote DB** — PostgreSQL, DynamoDB, etc.
- **Cloud object store** — S3-compatible, segments as blobs

Swapping the storage backend requires no changes to the transactor or query logic.

### Transactor (`IDaoTransactor`)

Single writer. Serializes all transactions to preserve monotonic `t`.

```clojure
(defprotocol IDaoTransactor
  (transact! [this tx-data])   ; returns {:db-after db :tempids {...} :tx-data [...]}
  (with [this tx-data]))       ; apply without committing; same return as transact!
```

Responsibilities:

- **Tempid resolution** — assigns permanent entity IDs to negative tempids
- **Schema effects** — derives ref, cardinality, unique, and index behavior from schema datoms
- **Cardinality checks** — enforces `:db.cardinality/one` and `:db.cardinality/many`
- **Index building** — updates EAVTM/AEVTM always; AVETM/VAETM/MEAVT conditionally after each transaction
- **Monotonic `t`** — guarantees every committed transaction has a strictly greater `t`

When distributed: one transactor per DaoDB; peers submit transaction requests via
channel or RPC. When in-process: an immutable record value carries the transaction
counter and index state, and each transaction returns the next db value.

### Query Engine (`IDaoQueryEngine`)

Executes Datalog queries and provides index access.

```clojure
(defprotocol IDaoQueryEngine
  (run-q        [this query inputs])
  (index-datoms [this index components]))
```

Runs Datalog queries against cached indexes and returns raw index lookups. No high-level
view operations; that's the responsibility of `IDaoDB`.

### Database as a Value (`IDaoDB`)

Immutable snapshots and views of database state. No write access.

```clojure
(defprotocol IDaoDB
  (entity          [this eid])
  (pull            [this pattern eid])
  (pull-many       [this pattern eids])
  (basis-t         [this])
  (as-of           [this t])
  (since           [this t])
  (entity-attrs    [this eid])
  (find-eids-by-av [this a v]))
```

Provides high-level view operations over the query engine. A new db value is obtained by
calling `(:db-after result)` after a transaction. When in-process, views run against
in-memory sorted-set indexes directly. When distributed, a peer maintains a local index
cache and fetches segments from Storage on miss.

---

## Deployment Topologies

The protocol boundary is the same in both topologies. The logical split enables:

- Multiple read peers sharing one transactor (Datomic-style read scaling).
- Hot standby: a second transactor can take over if the primary fails.
- Storage can be swapped without changing transactor or query logic.

### Single-process (current use case)

All four components share the same memory space. No serialization overhead.

```
[App]
  |
  +-- query  --> [QueryEngine] <-- indexes (in-memory sorted sets)
  |                                     ^
  +-- transact -> [Transactor] ---------+
                       |
                  [Storage (in-memory log)]
```

### Split-process (future)

```
[App / Peer]
  |
  +-- query  --> [QueryEngine (in-process, local index cache)]
  |                     |
  |               fetch segments on miss
  |                     |
  +-- transact -> [Transactor (remote)] --> [Storage (remote)]
```

The Peer runs the Query Engine in-process for low-latency reads. Writes go to the remote
Transactor, which updates Storage and broadcasts the new segment to connected peers. Each
peer refreshes its local cache on receipt.

---

## In-Process Implementation: `InMemoryDaoDB`

The current implementation in `src/cljc/dao/db/in_memory.cljc` is a single record that
wraps all four logical components:

```clojure
(defrecord InMemoryDaoDB
  [eavt aevt avet vaet meat
   log
   schema next-t next-eid
   ref-attrs card-many unique-attrs indexed-attrs])
```

- `eavt/aevt/avet/vaet/meat` — five sorted-set indexes
- `log` — vector of `[t added retracted]`, one entry per committed transaction (including
  bootstrap at t=0). This is the temporal index: `as-of`/`since` rebuild the five
  sorted-set indexes from a filtered log slice rather than O(n×5) scan across all sets.
- `schema` — cached `{attr-kw {:db/valueType ... :db/cardinality ...}}` map
- `next-t` / `next-eid` — monotonic counters for transaction IDs and entity IDs

This is the current in-process implementation. The four logical components are defined by
protocol boundaries and fully implemented. The physical record unifies all four in one
data structure for simplicity, but the protocol separation enables distributing them
across separate processes.

`InMemoryDaoDB` satisfies all four protocols:
- `IDaoStorage` — manages the transaction log and indexes
- `IDaoTransactor` — executes transactions
- `IDaoQueryEngine` — runs Datalog queries and index lookups
- `IDaoDB` — provides high-level views (entity, pull, as-of, etc.)

The same record is storage + writer + reader in the single-process topology. In the
split-process topology:
- Each **Peer** would satisfy `IDaoQueryEngine` and `IDaoDB` (read-only)
- A **Transactor** would satisfy `IDaoTransactor` and delegate to a `IDaoStorage` backend
- A **Storage** backend (file, remote DB) would satisfy `IDaoStorage` only

### Datomic-Compatible API

The `dao.db` namespace exposes a Datomic-compatible public API. These wrapper functions
dispatch to the appropriate protocol based on the operation:

| Function | Protocol | Notes |
|----------|----------|-------|
| `q` | `IDaoQueryEngine` | `(q query & inputs)` — `$` binds to first input |
| `datoms` | `IDaoQueryEngine` | `(datoms db index & components)` — optional filter |
| `entity` | `IDaoDB` | `(entity db eid)` — returns map with `:db/id`; card-many = sets |
| `pull` | `IDaoDB` | `(pull db pattern eid)` — supports `[*]`, keywords, ref maps |
| `pull-many` | `IDaoDB` | `(pull-many db pattern eids)` — returns vector of maps |
| `with` | `IDaoTransactor` | `(with db tx-data)` — returns `{:db-after ... :db-before ... :tx-data ... :tempids ...}` |
| `transact` | `IDaoTransactor` | `(transact db tx-data)` — commits and returns `{:db-after ... :db-before ... :tx-data ... :tempids ...}` |
| `basis-t` | `IDaoDB` | `(basis-t db)` — last committed transaction ID |
| `as-of` | `IDaoDB` | `(as-of db t)` — snapshot at or before t |
| `since` | `IDaoDB` | `(since db t)` — snapshot strictly after t |
| `entity-attrs` | `IDaoDB` | `(entity-attrs db eid)` — raw attribute map |
| `find-eids-by-av` | `IDaoDB` | `(find-eids-by-av db attr val)` — lookup by attribute+value |

Factory function: each backend namespace exposes `create` for that backend. For example,
`dao.db.in-memory/create` returns an `InMemoryDaoDB`.

---

## Transaction Pipeline (Transactor internals)

```
tx-data (seq of assertions/retractions)
  |
  v
1. Parse        — normalize [:db/add e a v] and [:db/retract e a v] forms
2. Tempid       — resolve negative e to permanent IDs; build tempid->eid map
3. Schema effects — derive same-tx ref, cardinality, unique, and index behavior
4. Cardinality  — for :one attributes, retract existing value before asserting new one
5. Stamp t      — assign the next monotonic transaction ID
6. Index update — insert datoms into EAVTM and AEVTM (always); conditionally into AVETM, VAETM, and MEAVT
7. Write segment— append the committed datom batch to Storage
8. Return       — {:db-after <new db value> :tempids <map> :tx-data <committed datoms>}
```

Each step is a pure function from `(state, input)` to `(state, output)`. No hidden
mutation outside the returned db value.

---

## Implementation Status

**Phase 1 (done):** `InMemoryDaoDB` implemented in `dao.db.in-memory` with all four
protocols: `IDaoStorage`, `IDaoTransactor`, `IDaoQueryEngine`, `IDaoDB`.

**Phase 2 (done):** Backend-specific namespaces expose `create` as the canonical
constructor. `dao.db.in-memory/create` returns `InMemoryDaoDB`.

**Phase 3 (planned):** Distributed topology: separate processes for Storage, Transactor,
and QueryEngine, all communicating via the protocol boundaries.


## Using DaoDB for Higher-Level Systems

DaoDB provides the foundation for systems that need queryable, immutable state:

- **DaoSpace** — see `docs/design/dao-space-design.md` for stigmergic agent coordination via Datalog queries over a shared datom stream
- **AST Storage** — the Yin VM's Universal AST is stored as datoms in DaoDB, queryable for capability policies and static analysis
- **Event Sourcing** — domain events as datoms, queryable at any historical point

---

## What This Design Does Not Cover

- **Durable storage backends** — file and remote backends are placeholders; their
  implementations will have separate design docs.
- **Peer synchronization protocol** — how peers receive segment broadcasts from the
  Transactor in the split-process topology.
- **Cross-namespace queries** — described in `CLAUDE.md`; not specific to DaoDB
  internals.
- **Content addressing** — DaoDB stores datoms; content hash computation is done by the
  caller (the AST layer) before assertion.

# DaoDB: Three-Component Architecture

## Overview

DaoDB is the native tuple store for datom.world. It stores immutable 5-tuple datoms
`[e a v t m]`, maintains five sorted-set indexes (EAVT, AEVT, AVET, VAET, MEAT), and provides
a Datalog query engine (`d/q`) over those indexes.

The architecture follows Datomic's logical split: **Storage**, **Transactor**, and
**Query Engine** are three distinct logical components defined by protocol boundaries.
"Logical" means the boundary is a protocol interface, not a deployment constraint. The
same three components can run in one process (in-memory, no serialization overhead) or
be deployed across separate machines communicating via network. Same protocol, different
topology.

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

| Index | Sort order       | Insertion condition          | Primary use                        |
|-------|------------------|------------------------------|------------------------------------|
| EAVT  | e, a, v, t       | always                       | All attributes of an entity        |
| AEVT  | a, e, v, t       | always                       | All entities with a given attribute|
| AVET  | a, v, e, t       | indexed or ref attr          | Find entity by attribute + value   |
| VAET  | v, a, e, t       | ref attr only                | Reverse reference lookup           |
| MEAT  | m, e, a, t       | m != 0                       | Provenance and derivation queries  |

EAVT and AEVT are unconditional. AVET, VAET, and MEAT use conditional insertion to
keep their sizes bounded: AVET only for attributes marked `:db/index true` or typed as
`:db.type/ref`; VAET only for `:db.type/ref` attributes; MEAT only when `m != 0`
(m=0 means "no metadata" — not useful to index).

`v` is excluded from MEAT because provenance queries resolve to `(m, e, a)` — the
value is retrieved after the fact from EAVT. Including `v` for large blob/string
datasets would inflate the index for no lookup benefit.

**Seek helpers for MEAT:**

```clojure
(seek-m  [meat m])     ; all datoms tagged with m=X
(seek-me [meat m e])   ; all datoms tagged with m=X on entity e
```

### Provenance Queries

The `m` component is first-class in DaoDB. Two hot query patterns depend on it:

- **Exclude derived datoms** (`m=1`, `:db/derived`): every content-hash computation
  must filter out derived datoms before hashing. Without MEAT this would be a full
  scan of all indexes on every hash operation.
- **Provenance lookup** (`m=event-eid`): given a macro-expansion event or a
  transaction provenance entity, find every datom it caused. Used for audit trails,
  incremental recomputation, and access-control enforcement.

MEAT makes both patterns O(log n) instead of O(n).

---

## Three-Component Model

### Storage (`IDaoStorage`)

Append-only log of datom segments, keyed by transaction range.

```clojure
(defprotocol IDaoStorage
  (write-segment! [this segment])      ; segment = seq of datoms; returns t
  (read-segments  [this t-min t-max])  ; returns seq of datom seqs
  (latest-t       [this]))             ; highest committed t
```

Storage is dumb: no schema awareness, no query capability. The only invariant is that
segments are immutable once written (append-only log). Pluggable backends:

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
  (current-db [this]))         ; returns current db value (the four indexes)
```

Responsibilities:

- **Tempid resolution** — assigns permanent entity IDs to negative tempids
- **Schema enforcement** — validates attribute types and value types
- **Cardinality checks** — enforces `:db.cardinality/one` and `:db.cardinality/many`
- **Index building** — updates EAVT/AEVT always; AVET/VAET/MEAT conditionally after each transaction
- **Monotonic `t`** — guarantees every committed transaction has a strictly greater `t`

When distributed: one transactor per DaoDB; peers submit transaction requests via
channel or RPC. When in-process: a function/record that holds the mutable transaction
counter and index state.

### Query Engine (`IDaoQueryEngine`)

Read-only. Holds a cached snapshot of the database value (the four indexes).

```clojure
(defprotocol IDaoQueryEngine
  (q               [this query inputs])
  (datoms          [this index pattern])
  (entity-attrs    [this eid])
  (find-eids-by-av [this a v]))
```

No write access. A new db value is obtained by calling `(:db-after result)` after a
transaction. When in-process, queries run against in-memory sorted-set indexes directly.
When distributed, a peer maintains a local index cache and fetches segments from Storage
on miss.

---

## Deployment Topologies

The protocol boundary is the same in both topologies. The logical split enables:

- Multiple read peers sharing one transactor (Datomic-style read scaling).
- Hot standby: a second transactor can take over if the primary fails.
- Storage can be swapped without changing transactor or query logic.

### Single-process (current use case)

All three components share the same memory space. No serialization overhead.

```
[App]
  |
  +-- query  --> [QueryEngine] <-- indexes (in-memory sorted sets)
  |                                     ^
  +-- transact -> [Transactor] ---------+
                       |
                   [Storage (in-memory atom)]
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
wraps all three logical components:

```clojure
(defrecord InMemoryDaoDB
  [eavt aevt avet vaet meat
   log
   schema next-t next-eid
   ref-attrs card-many unique-attrs indexed-attrs])
```

- `eavt/aevt/avet/vaet/meat` — five sorted-set indexes
- `log` — vector of `[t [Datom ...]]`, one entry per committed transaction (including
  bootstrap at t=0). This is the temporal index: `as-of`/`since` rebuild the five
  sorted-set indexes from a filtered log slice rather than O(n×5) scan across all sets.
- `schema` — cached `{attr-kw {:db/valueType ... :db/cardinality ...}}` map
- `next-t` / `next-eid` — monotonic counters for transaction IDs and entity IDs

This is the current implementation. The conceptual framing (three logical components
defined by protocols) is the goal. The physical record can remain a single unit for the
in-process case.

`InMemoryDaoDB` satisfies all three protocols. The same record is both writer and reader
in the single-process topology. In the split-process topology, the Peer record would
satisfy only `IDaoQueryEngine`; a separate Transactor process would satisfy
`IDaoTransactor` and delegate to a `IDaoStorage` backend.

### Datomic-Compatible API

The `dao.db` namespace exposes a Datomic-compatible public API:

| Function | Signature | Notes |
|----------|-----------|-------|
| `q` | `(q query & inputs)` | `$` binds to first input |
| `datoms` | `(datoms db index & components)` | optional leading-component filter |
| `entity` | `(entity db eid)` | returns map with `:db/id`; card-many = sets |
| `pull` | `(pull db pattern eid)` | supports `[*]`, keywords, ref maps |
| `pull-many` | `(pull-many db pattern eids)` | returns vector of maps |
| `with` | `(with db tx-data)` | returns `{:db-after ... :tempids ...}` |
| `transact` | `(transact db tx-data)` | same as `with`; also returns `:db-after` |
| `basis-t` | `(basis-t db)` | last committed transaction ID |
| `as-of` | `(as-of db t)` | snapshot at or before t |
| `since` | `(since db t)` | snapshot strictly after t |

Factory functions: `create-in-memory` (no DataScript dependency); `create` (DataScript-backed, legacy).

---

## Transaction Pipeline (Transactor internals)

```
tx-data (seq of assertions/retractions)
  |
  v
1. Parse        — normalize [:db/add e a v] and [:db/retract e a v] forms
2. Tempid       — resolve negative e to permanent IDs; build tempid->eid map
3. Schema check — validate a is a known attribute; validate v type
4. Cardinality  — for :one attributes, retract existing value before asserting new one
5. Stamp t      — assign the next monotonic transaction ID
6. Index update — insert datoms into EAVT and AEVT (always); conditionally into AVET, VAET, and MEAT
7. Write segment— append the committed datom batch to Storage
8. Return       — {:db-after <new db value> :tempids <map> :tx-data <committed datoms>}
```

Each step is a pure function from `(state, input)` to `(state, output)`. No hidden
mutation outside the index atoms.

---

## Migration Path

**Phase 1 (done):** `InMemoryDaoDB` implemented in `dao.db.in-memory` alongside the
DataScript-backed `DaoDbDataScript`. Both extend `IDaoDb`.

**Phase 2 (done):** `create-in-memory` added as the canonical constructor for
`InMemoryDaoDB`. `create-native` is a deprecated alias pointing to `create-in-memory`.
`create` still returns `DaoDbDataScript` for backward compatibility.


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

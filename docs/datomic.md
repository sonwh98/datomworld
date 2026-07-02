# Datomic Architecture Deep Dive

## Overview

A traditional database like PostgreSQL is a **single integrated process** that bundles three distinct responsibilities — query planning, write serialization, and storage/indexing — into one binary running on one machine:

```
┌─────────────────────────────────────────┐
│           PostgreSQL Server             │
│                                         │
│  ┌─────────┐  ┌────────┐  ┌──────────┐  │
│  │  Query  │  │ Writer │  │  Storage │  │
│  │ Planner │──│/Tx Log │──│  /Index  │  │
│  │Execute  │  │/Locks  │  │  Manager │  │
│  └─────────┘  └────────┘  └──────────┘  │
│         all on one box, one process     │
└─────────────────────────────────────────┘
        ▲           ▲            ▲
        │           │            │
     clients    clients       clients
   (connect to the same server)
```

Datomic's central architectural bet is that this bundling is not a law of nature. It **separates those responsibilities into three independent components** that can be placed on different machines, scaled independently, and reasoned about separately:

```
   ┌──────────────┐   submit tx   ┌──────────────┐
   │   PEERS      │──────────────▶│  TRANSACTOR  │
   │ (Query Engine)│◀──novelty────│  (Serializer) │
   │              │               │   single     │
   │ runs IN the  │               │   writer     │
   │ application  │               └──────┬───────┘
   │ process      │                      │ write
   │              │                      ▼
   │              │   pull segments ┌──────────────┐
   │              │────────────────▶│   STORAGE    │
   └──────┬───────┘  (cache miss)   │ (dumb KV)    │
          │                         │  SQL/Dynamo/ │
          └─────────────────────────│  Cassandra/  │
                  read              │  filesystem  │
                                    └──────────────┘
```

| Component | Responsibility | Count | Where it runs |
| :--- | :--- | :--- | :--- |
| **Transactor** | Serialize all writes; append the transaction log; swing the root pointer via CAS | **Exactly one** (active) | Dedicated process |
| **Storage** | Hold opaque immutable index segments + a tiny mutable root | One, shared | Existing DB / KV store / filesystem |
| **Peers** | Run queries; cache index segments; hold novelty | **Many** — one per application process | **Inside the application JVM** |

### How each component works

**Transactor — the single writer.** The transactor is the *only* component allowed to write. It serializes transactions one at a time: receive a tx from a Peer, durable-append it to the **log**, fold new datoms into its **memory index** (novelty), then **CAS-swing the root pointer** to publish the new database value. Because it is the sole writer, there are **no locks, no lock manager, no deadlock detection** — concurrency control collapses to a single conditional write on the root pointer. The trade-off is accepted by design: **write throughput is bounded by one process.** Datomic bets that most applications are read-heavy and that single-writer throughput is enough, and in return eliminates an entire class of distributed-coordination problems.

**Storage — the "dumb store."** Storage is treated as a **dumb key-value store of opaque blobs**. All intelligence — indexing, query planning, transaction processing — lives *outside* storage. The store sees only opaque keyed bytes and need only support `put` (with CAS folded in, gated on a `rev` counter), `get`, `delete`, and `close`. It does not know what a "table" or an "index" or a "join" is, which is why **storage is swappable**: the same Datomic code runs against Postgres, DynamoDB, Cassandra, or a local directory of files. Only the small set of `meta` keys (root pointer, lease) needs strong consistency; the bulk of data — immutable index and log segments written once under never-reused keys — can sit in an eventually-consistent store without harm. A property Postgres cannot exploit, because its data pages are mutable. See §1–§3 for the full contract and realizations.

**Peers — the query engine runs in your app.** There is **no query server.** Queries execute **inside the application process** as a library. Each Peer holds a direct connection to storage and resolves queries by pulling only the index segments it needs. A Peer holds two things: a **persistent index**, fetched lazily into a bounded LRU object cache (a query pulls only the few B-tree segments it traverses); and **novelty**, recent datoms pushed to it by the transactor on every commit. A `db` value is thus a cheap immutable snapshot — a root pointer (a basis-t) plus current novelty — so a Peer's footprint is only `novelty + hot working set`, letting it query a database far larger than its heap. See §4–§5.

### What is a "row"?

| | Postgres | Datomic |
| :--- | :--- | :--- |
| Unit of data | A row, mutated in place | An immutable **datom** (5-tuple): `(entity, attribute, value, tx, added)` |
| Updates | Overwrite the row | Assert a new datom; the old value is still there |
| History | Opt-in via MVCC, eventually vacuumed | **First-class and permanent** by default — the log *is* history |

Datomic stores facts, not states. "Alice's email changed from A to B" is not a destructive update — it is a new datom at a new transaction time `t`, and the old datom remains queryable forever. Writes never overwrite, they only accumulate. (Background indexing compacts segments, but never loses logical history.)

### Responsibility placement

| Responsibility | Postgres | Datomic |
| :--- | :--- | :--- |
| Query planning & execution | Server process | **Application process (Peer)** |
| Transaction serialization | Server (lock manager + WAL) | **Transactor** (sole writer, CAS) |
| Storage / indexing | Server (buffer manager, B-tree, WAL) | **External store** (dumb KV) |
| Caching | Server shared buffers | **Peer-local** object cache + optional Memcached |

In Postgres these are one process. In Datomic they are three *independently deployable* components — and crucially, **the read path does not include the transactor at all.** Peers read storage directly.

### Concurrency model

| | Postgres | Datomic |
| :--- | :--- | :--- |
| Multiple writers? | Yes, with row/table locks, deadlock detection, MVCC | **No.** One transactor serializes all writes |
| Lock manager? | Yes, complex | **None** |
| Deadlocks? | Possible, detected | **Impossible** (single writer) |
| Write scaling | Add more clients | Bounded by one transactor's throughput |
| Read scaling | Add read replicas | Add Peers (no replication lag — they read storage directly) |

### Scaling profile

| | Postgres | Datomic |
| :--- | :--- | :--- |
| **Reads** scale by | Read replicas (async, replication lag, consistency caveats) | Adding Peers — each reads storage directly, no lag, no replicas to sync |
| **Writes** scale by | Sharding, more hardware | You **cannot scale writes horizontally.** One transactor. |
| **Storage** scales by | Bigger server / sharding | Swap the backend — a config change, not a rewrite |

This produces Datomic's signature shape: **horizontal read scaling for free, capped write throughput by design** — the deliberate mirror image of Postgres, which scales writes better (multi-writer) but pays for it with locking, MVCC overhead, and read-replica lag.

### ACID and consistency

Postgres achieves ACID through its WAL + lock manager + MVCC within one server. Datomic achieves it through a different route:

* **Atomicity** = the single root-pointer CAS either publishes the whole tx or none of it.
* **Consistency** = the transactor validates the tx (schema, cardinality) before committing.
* **Isolation** = transactions are processed **serially** by the one writer, each applied against the latest state, so *transaction execution* is serializable and deadlocks are impossible. **Caveat:** a read-then-write performed *on a Peer* (read an immutable snapshot, compute, then submit blind `:db/add`s) is only **snapshot isolation** and is vulnerable to **write skew**. To get serializable read-write behavior, guard the write with a `:db/cas` assertion or move the logic into a transactor-side **transaction function** (which runs against current state).
* **Durability** = the transactor appends to the durable log before swinging the root.

The single-writer model trades away horizontal write scaling in exchange for **serial write execution and an impossible-to-deadlock system** (serializable *read-write* logic still requires `:db/cas` or a transaction function).

### Indexing

| | Postgres | Datomic |
| :--- | :--- | :--- |
| Indexes | Per-column, created explicitly | **Covering indexes** — EAVT and AEVT are always present. AVET and VAET are conditionally maintained based on schema. |
| When indexed | Synchronously on write | **Asynchronously** in background indexing jobs; recent datoms served from in-memory novelty meanwhile |
| Cost on write | Update every relevant index inline | Append to log + grow novelty (cheap); heavy indexing happens later |

Postgres pays index-maintenance cost on every write. Datomic defers it: writes are cheap (log append + novelty), and the heavy lifting of merging novelty into B-tree segments happens in background indexing jobs. This is why Datomic writes can stay fast even while maintaining several covering indexes — the index work is batched and deferred.

### The payoff, and the price

**What Datomic gains by splitting the monolith:**

* **Reads scale horizontally for free** — every Peer reads storage directly; no read replicas, no replication lag.
* **The query engine lives in your process** — no network hop to a query server; query results are already in-process objects.
* **Storage is swappable** — laptop dev (`datomic:dev://`) to production cluster (DynamoDB) is a config change, because the contract is four KV operations.
* **No locks, no deadlocks** — the single writer serializes all commits, eliminating the lock manager and deadlocks (serializable *read-write* logic still needs `:db/cas` or a transaction function).
* **Immutable, queryable history** — the database *is* a log of facts; "as-of" and "since" queries are free.

**What it pays:**

* **Write throughput is capped by one transactor.** This is the defining limitation. If you need multi-writer horizontal write scaling, Datomic is the wrong tool.
* **Operational topology is more spread out** — you run a transactor, a storage backend, and Peers, vs. one Postgres server.
* **Best when reads ≫ writes**, and when immutable history + in-process queries matter more than raw write parallelism.

The architecture is not "Postgres but distributed." It is a **different decomposition**: take the integrated database, pull storage out as a dumb swappable layer, collapse the write path to a single serializer so coordination vanishes, and push the query engine into the application so reads scale by adding processes. Every property — good and bad — flows from those three decisions.

---

The rest of this document is the storage deep dive: **§1** the `KVStore` contract · **§2** SQL realization · **§3** local/file backends · **§4** how Peers query · **§5** segment-key discovery.

---

## 1. Storage Abstraction and KV Store Requirements

The Overview's "dumb store" is reached through one small, pluggable storage protocol: every backend — DynamoDB, Cassandra, a JDBC SQL database, a local directory of files — sits behind the same narrow contract. This section specifies that contract (the `KVStore` protocol) and the properties a backend must satisfy to support it. The store holds mostly-immutable segments plus a small mutable root (the `meta` keys updated by CAS); all indexing, query, and transaction intelligence lives above it, in the Peers and Transactor.

### Storage Abstraction
At the JVM code level, Datomic interacts with storage through the `KVStore` protocol in the `datomic.kv-store` namespace (these are internal, not public API; the binaries have been Apache 2.0 since 1.0.6726, April 2023, so this inspection is unambiguously permitted). The method *names and arities* below are disassembled with `javap` from `peer-1.0.7482.jar` (`datomic/kv_store/KVStore.class`, compiled from `kv_store.clj`); the *parameter names* are illustrative, since `javap` recovers arity but not argument names. `get` and `delete` genuinely take **two** arguments (confirmed: `get(Object, Object)` / `delete(Object, Object)` in the bytecode). The trailing argument's role is recovered from the DynamoDB backend: it is a **consistent-read flag**. `KVDynamo.get` branches on it and, when truthy, adds `:consistentRead` to the `datomic.ddb/get-item` request (the keyword is visible in the class's constant pool and static initializer). `KVMem` ignores it (bytecode references only `this.m` and the key) and `KVSql` binds only the key in its `WHERE id = ?` — both are read-your-writes by construction. This is how the "strong consistency for `meta` keys" requirement below is actually implemented: per-read, on the backends that need it, not per-store. The protocol is deliberately tiny — just four operations over opaque keyed blobs:

```clojure
;; datomic.kv-store — the actual storage protocol (decompiled from datomic-pro)
(defprotocol KVStore
  (put    [this val-map])         ;; Write an entry (id, rev, map, val); also does the CAS — see below
  (get    [this k consistent?])   ;; Read an entry's bytes by key; consistent? requests a strongly
                                  ;;   consistent read (honored by KVDynamo, no-op for KVMem/KVSql)
  (delete [this k arg2])          ;; Remove an entry by key; trailing arg mirrors get's, disregarded
                                  ;;   in the inspected backends
  (close  [this]))                ;; Release the backend connection

;; Sibling protocol used to classify transient backend failures for retry
(defprotocol Retryable
  (retryable? [this]))
```

Concrete implementations that directly `implement datomic.kv_store.KVStore` (confirmed in disassembled bytecode): `KVSql`, `KVMem`, `KVDynamo`, `KVHotRod`, and the Cassandra drivers `KVCassandra`, `KVCassandra2`, `KVCassandra3` — all seven present in the peer jar (the Cassandra v2/v3 classes also ship in the transactor jar). `KVCluster` is a layer *above* raw KV: it implements `datomic.cluster.ClusteredStore` (the value/reference layer, next subsection) and composes shards — it is **not** itself a `KVStore`. The peer jar also carries a second-generation storage SPI, `datomic.core2.*` — a val-store SPI (`Get`/`Put`/`Delete`) with fs, S3, and DynamoDB implementations, a log SPI (`Append`/`Scan`/`Delete`), and durable/logged atoms — the Cloud-style architecture living alongside, not inside, the `KVStore` stack described here.

Note there is **no separate compare-and-swap method**: CAS is folded into `put`. Each backend realizes it with whatever conditional-write primitive it has, gated on the entry's `rev`. In the SQL store that is literally `update datomic_kvs set rev=?, map=?, val=? where id=? and rev=?` (the `UPDATE ... WHERE id = ? AND rev = ?` shown in §2 below), so the conditional compares the revision, not the old payload bytes; a `put` that updates zero rows lost the race.

A thin stack sits **above** this raw byte store. `datomic.cluster/ClusteredStore` adds value/reference semantics on top of `KVStore` — `create-val`/`get-val` for immutable, content-keyed segments and `set-ref`/`get-ref` for the small set of mutable named references (the "root pointer") — with `datomic.cluster-stack/ValStoreOnKvCache` providing an in-process value/reference cache over the raw KV store. This is the concrete realization of the "mostly-immutable segments + a small mutable root" model: *segments* are vals, the *root* is a ref. (This caching layer is distinct from the Peer's L1/L2/L3 *object-cache* stack in §4, which is what "L1/L2/L3" refers to throughout this document.)

The full `ClusteredStore` surface (recovered from the interface bytecode and its surviving docstrings) is richer than val/ref:

* **Refs are indirections, and the CAS contract is rev-monotonic.** A ref read returns `{:rev nnn, :key k}` — the *key* of an immutable val, not bytes. `set-ref`'s docstring: *"Makes vkey the new value of ref, iff rev is **higher** than existing rev. You must have obtained rev from a prior read and incremented it. Returns a reference to :ok or :conflict. set-ref with a rev of 0 can create a ref."* The caller increments; rev 0 creates. (The SQL `... WHERE id=? AND rev=?` in §2 is the backend realization of the same guard, phrased against the expected prior rev.)
* **Pods.** Alongside vals and refs there is a third kind: `get-pod` / `get-pod-meta` / `update-pod*` operate on **pods**, mutable byte containers returning `{:rev nnn, :etag xxx, :buf buf}` plus metadata; the docstrings imply pods can chain ("without walking entire linked list a la get-pod"). The database **catalog** — the name→id map behind `create-database` / `rename-database` / `delete-database` — is stored in pods (`datomic.catalog`, `pod->catalog`).
* **Consistency plumbing.** `datomic.cluster.Get2/get-val2` is "like ClusteredStore/get-val, but takes an opts map that flows to underlying implementations" — the path by which a consistent read request reaches `KVStore/get`'s trailing argument. The interface docstring also notes that any operation "might throw an exception on deref if no quorum is available."
* **Key naming is class-prefixed and shard-prefixed.** Keys are minted by `new-val-key` / `new-ref-key` / `new-pod-key` as UUIDs under literal `"ref-"` / `"pod-"` prefixes, so an entry's kind is recoverable from its key. Storage paths are built by `datomic.cluster/path` as `tenant/db/%03X/key` — a three-hex-digit (4096-bucket) partition prefix that spreads keys across backend key space. This is the concrete form of §2's "category is a convention encoded into the key."

By keeping the bottom contract this narrow, Datomic remains entirely backend-agnostic. The query engine and indexing system function the same way regardless of the underlying driver.

### The minimal implementation: `KVMem`
The contract is small enough that an in-memory map satisfies it — which is exactly what the `mem://` backend is. In the jar, `datomic.kv-mem/KVMem` holds a single field `m` and implements all four methods:

```clojure
;; datomic.kv-mem (sketch) — the whole store is one mutable map
(deftype KVMem [m]            ; m: a java.util.concurrent.ConcurrentMap of id -> {id rev map val}
  KVStore
  (put    [_ v]   (put-when m v))   ; conditional insert/replace gated on rev (the CAS)
  (get    [_ k _] (.get m k))
  (delete [_ k _] (.remove m k))
  (close  [_]     nil))
```

Two things make this work — and they are the same two everywhere:

* **A mutable, shared cell.** The *values* are plain Clojure maps (`{id rev map val}`), but the *store* must be a map inside a mutable, atomic container so a `put` is observable by a later `get`. `KVMem` uses a `ConcurrentMap`; an `atom` wrapping a persistent map would do equally well. A bare immutable map cannot be a `KVStore` — it has no identity-preserving mutation.
* **CAS folded into `put`.** The `put-when` helper conditionally writes only if the stored `rev` still matches — the in-memory analog of `... WHERE id=? AND rev=?`, provided by `ConcurrentMap.replace` (or `compare-and-set!` on an atom).

`KVMem` clears §1's CAS, strong-consistency, and linearizability requirements **trivially**, because a single in-process container is linearizable for free. What it cannot provide is *sharing across JVMs* or *durability* — so it backs testing and ephemeral in-memory databases, not production peer clusters (the same single-process trade-off discussed in §3).

### Required Properties of a Backing KV Store
This list is a synthesis of what a backend must provide, not a verbatim Datomic spec. Properties 1–4 are **correctness** requirements for ACID transactions; property 5 is a **performance** requirement (the engine still works without it, just slowly).

1. **Atomic Compare-And-Swap (CAS)**:
   * *Mandatory Requirement*: The store must support atomic, conditional single-key writes.
   * *Purpose*: Datomic relies on CAS to commit transaction roots atomically (`id='root'`) and to handle failover leases for transactor leadership.
2. **Strong Consistency for Metadata**:
   * The store must guarantee read-your-writes (read-after-write) consistency for keys in the `"meta"` category.
   * *Purpose*: If a Peer or transactor reads a stale root pointer, it can lead to transaction conflicts or stale query snapshots. Datomic can tolerate eventual consistency for segments in the `"index"` and `"log"` categories (as they are immutable: each is written once under a never-reused key), but metadata keys must be strongly consistent.
3. **Blob Support**:
   * The store must handle binary payloads (compressed segment blobs) ranging from **10KB to 1MB** efficiently.
4. **Linearizable Single-Key Writes**:
   * Writes to a single key must execute and become visible in the exact sequence they were requested.
5. **High Point-Lookup Throughput**:
   * The engine relies on high-speed point reads (`get`) to fetch index segments. Low point-lookup latency is crucial to keep peers responsive when local caches are cold.

---

## 2. Using Relational Databases as a "Dumb Store"

A SQL database is one concrete backend for the contract in §1. Datomic uses it purely as a key-value store: all intelligence stays in the Transactor and Peers, and SQL sees only opaque keyed blobs.

### SQL Schema
Instead of mapping domain models to tables, Datomic creates a single key-value table (Postgres types shown; other backends use equivalents such as `CLOB`/`BLOB`):

```sql
CREATE TABLE datomic_kvs (
  id   TEXT NOT NULL,   -- segment key
  rev  INTEGER,         -- revision counter, used for optimistic concurrency (CAS)
  map  TEXT,            -- metadata
  val  BYTEA,           -- compressed, serialized segment payload
  PRIMARY KEY (id)
);
```

* **`id`**: The unique segment identifier and sole primary key. Datomic generates these (UUID-style opaque strings); the data category (`"index"` for B-tree nodes, `"log"` for transaction logs, `"meta"` for database metadata) is a convention encoded into the key, not a separate column.
* **`rev`**: A revision counter used as the basis for atomic compare-and-swap on a key.
* **`map`**: Metadata associated with the entry.
* **`val`**: Compressed, serialized binary payload of the segment (encoded in Fressian, Datomic's internal serialization format).

### Key-Value Operations
The `KVStore` operations from §1 map directly onto SQL, all keyed by the single `id` column (these are the literal statements emitted by `datomic.sql`):
* **`put`** (new key): `INSERT INTO datomic_kvs (id, rev, map, val) VALUES (?, ?, ?, ?);`
* **`put`** (CAS update): `UPDATE datomic_kvs SET rev=?, map=?, val=? WHERE id=? AND rev=?;` — see *Concurrency* below.
* **`get`**: `SELECT id, rev, map, val FROM datomic_kvs WHERE id = ?;`
* **`delete`**: `DELETE FROM datomic_kvs WHERE id = ?;`
* **`close`**: releases the JDBC connection (no statement).

`datomic.sql` emits the `insert` and `update` as two *distinct* statements (there is no `ON CONFLICT` upsert); a given `put` issues just one of them — `insert` to create a key, the rev-gated `update` to overwrite one. No read-before-write is needed to choose: the caller already knows which case it is, because immutable segments are always written under a fresh, content-derived key (§1's `create-val`) while only the small set of mutable references is ever overwritten (`set-ref`).

### Concurrency and the Root Pointer
Datomic coordinates transactions via a single **Root Pointer** stored under a well-known `meta` key (e.g., `id='root'`).
When a transaction is processed:
1. The Transactor durably appends the transaction to the **log** and incorporates the new datoms into its **memory index** (novelty). It does **not** write new persistent index B-tree segments on every transaction — those are produced later, in batches, by periodic background **indexing jobs** (see §4). Index and log segments, once written, are immutable.
2. It attempts to atomically swing the database's root pointer from the old state to the new state using **Optimistic Concurrency Control (OCC)** — the SQL realization of the rev-gated `put` (the CAS folded into `KVStore/put`, §1). The conditional update is gated on the entry's `rev` counter, not on comparing the old payload bytes:
   ```sql
   UPDATE datomic_kvs 
   SET val = :new_root_bytes, rev = :new_rev, map = :new_map 
   WHERE id = 'root' AND rev = :old_rev;
   ```
3. If this query updates **1 row**, the transaction commits. If it updates **0 rows**, it means the Transactor has lost its High Availability lease (e.g., a standby transactor took over; see *Transactor High Availability* below). The Transactor does *not* retry; it steps down and aborts the transaction. It is the application/peer's responsibility to retry the transaction against the new active Transactor.

   This is the *root-pointer* CAS internal to the Transactor's commit; it fails only on failover, because the active Transactor is the sole writer of the root. It is **not** how an ordinary application transaction fails. A Peer does **not** submit against a basis-t that the Transactor checks — the Transactor applies the submitted datoms to its *current* state regardless of how stale the Peer's read was. An application-level conflict surfaces only when the transaction's *own* precondition fails: a `:db/cas` assertion whose expected value no longer holds, or a transaction function that throws. That is the conflict a Peer catches and retries.

### Transactor High Availability (HA)
Transactor leadership is coordinated using SQL-level leases. Transactors heartbeat their status using Compare-And-Swap (CAS) updates against a well-known `meta` key, gated on its `rev` counter:
```sql
UPDATE datomic_kvs 
SET val = :heartbeat_with_my_id, rev = :new_rev
WHERE id = 'transactor-lease' 
  AND (rev = :old_rev OR rev IS NULL);
```

### Indexing: Novelty → Segments
The two preceding subsections leave a gap: per transaction the Transactor only appends to the log and grows the in-memory novelty — so where do the persistent B-tree segments (the ones queries traverse in §4–§5) come from? From **background indexing jobs**. When accumulated novelty crosses a threshold (or on a periodic/explicit trigger), the Transactor merges novelty into the durable index tiers: it writes **new immutable segments** via `put` (new keys, never overwriting old ones), then CAS-swings the index root — the same rev-gated root update as above — to publish the new tree. This is the mechanism that connects the write path (§2) to the read path (§4–§5): transactions feed novelty, indexing turns novelty into the segments Peers query. Indexing cadence is a tunable that trades **write amplification** (frequent indexing rewrites more segments) against **Peer/Transactor memory** (infrequent indexing lets novelty grow).

Three mechanics of this pipeline, recovered from the bytecode:

* **Garbage is recorded, not discovered.** When a root swing makes old segments unreachable, their ids are written into a persisted **garbage tree** (`datomic.garbage.fressian/GarbageRoot` → `GarbageDir` → `GarbageLeaf`, each a record of `children`), which a later storage-GC pass consumes. Datomic never scans storage for liveness; it writes down what became garbage at the moment it became garbage.
* **Segment publication is paced.** Writes flow through a queueing writer (`datomic.cluster/QueueingWriter`, `AsyncWriter`) with explicit priorities (`PRIORITY_HIGH`/`MID`/`LOW`), a `segment-pacing-msec` throttle, and retry/backoff — indexing competes with transactions for storage bandwidth and is deliberately rate-limited.
* **The durable log has the same shape as the index.** `datomic.log/LogValue` holds a persisted tree root (`root_id`) plus a `Tail` of `{txes, bufs}` — recent transactions not yet merged into the log tree. The index/novelty duality repeats one level down: everything durable in Datomic is a persistent tree plus a small recent tail.

---

## 3. Local File Storage

Local file storage is not a different engine — it is the same `KVStore` contract from §1, satisfied by an embedded backend that writes to the local filesystem instead of talking to a remote database. Two flavors are common:

* **Embedded SQL (the `dev` protocol).** Datomic's `dev` storage runs an embedded H2 database *inside the transactor process*, persisting to `.db` files in a local data directory. Structurally it is exactly §2: the same single `datomic_kvs(id, rev, map, val)` table, the same `get`/`put`/`delete` keyed by `id`, the same `rev`-gated CAS. The only change from a remote SQL backend is co-location — the SQL engine is in-process and its files sit on local disk rather than on a separate server. A connection URI looks like `datomic:dev://localhost:4334/mydb`.
* **Datomic Local.** A distinct single-process implementation that persists each database as files under a configured storage directory (`{:storage-dir "/path"}` in `~/.datomic/local.edn`). It uses its own on-disk format rather than embedded SQL, but it implements the same KV contract.

### How the abstraction is satisfied
Because the storage protocol is so narrow (§1), a local backend only has to honor those four operations and the five properties — and in a single process most of them become trivial:

* **CAS (folded into `put`)** — provided by the embedded engine's transactional conditional update (for `dev`, an H2 row-level transaction realizing the `... WHERE id=? AND rev=?` gate). No distributed coordination is needed because there is exactly one writer.
* **Strong consistency / linearizable single-key writes** — automatic: a single process serializing writes to local files is trivially read-after-write consistent. The distributed-storage concern raised in §1 simply does not arise.
* **Blob support & high point-lookup throughput** — the embedded engine's primary-key index on `id` serves segment `get`s straight from local files; the L1/L2/L3 cache hierarchy of §4 collapses toward "L1 heap + local disk."

### Trade-off
Local file storage co-locates the transactor, storage, and (usually) the peer in one process. That is ideal for development, testing, and embedded or redistributable apps, but it forgoes the horizontal scaling and transactor high availability of §2: there is no standby transactor and no shared store that multiple peers can reach over the network. Crucially, the query engine and indexing code are **identical** either way — only the `KVStore` implementation changes. Moving from a laptop to a cluster is a storage-configuration change, not a rewrite.

---

## 4. Pulling Datom Segments Locally for Querying

Datomic queries are executed **locally inside the Peer process**. Peers resolve queries by pulling only the necessary B-tree index segments from storage.

### How Peers Interact with Storage
Reads and writes take different paths — this asymmetry is what lets queries run locally:

* **Reads go Peer → storage directly.** Each Peer holds its own connection to the storage backend and pulls index segments straight from it by key (`KVStore/get` → `ClusteredStore/get-val`, §1). The **transactor is not in the read path**; a read only touches storage on a cache miss, through the L1 → L2 → L3 stack shown below.
* **Writes go Peer → transactor.** A Peer submits a transaction to the single-writer transactor, which serializes it, writes the log and (eventually) index segments to storage, and CAS-swings the root (§2).
* **Novelty is pushed transactor → all Peers (Datomic Pro).** On each commit the transactor broadcasts the transaction's datoms — over its messaging transport (ActiveMQ Artemis, shipped in the distro) — to every connected Peer, which folds them into its **memory index** — also called **novelty**: the set of datoms committed since the last indexing job, not yet in the persistent tree. This keeps each Peer's `db` value current without re-reading storage. This live push is **Pro-specific**: **Datomic Cloud** has no peer broadcast — its query nodes pull transaction-log updates from storage (DynamoDB) instead.

**A Peer does not load the complete index.** Two things live in a Peer, and only one is bounded by total data size — and it isn't:

* The **persistent index** is fetched **lazily and partially**: a query navigates the covering tree from its root and pulls only the few segments it traverses, into a **bounded LRU object cache** (configurable, e.g. `datomic.objectCacheMax`). Cold segments come from storage on first touch, then stay cached; different Peers cache different working sets.
* The **in-memory novelty** is held in full, but it is only the datoms since the last indexing job (emptied periodically by background indexing, §2), so it is bounded by indexing cadence, not by database size. The bound is soft: under sustained write load, if indexing falls behind, novelty grows and raises Peer/Transactor heap pressure — which is why indexing cadence is a tunable (§2) and why ingest is sometimes throttled to let indexing catch up. Structurally, novelty lives in `datomic.btset/BTSet` — Datomic's own persistent B-tree set (`{cmp, cnt, root}` with seek/rseek iterators), the in-heap counterpart of the durable index tree, and the direct ancestor of the open-source `persistent-sorted-set` library.

A `db` value is thus a cheap immutable snapshot — a pointer to a persistent index root (a **basis-t**, the transaction index `t` the snapshot is anchored at) plus the current novelty — and a Peer's footprint is roughly `novelty + hot working set`. This is why a Peer can query a database far larger than its heap: it never needs the whole index resident.

> **Where these segments come from.** The persistent index trees are produced by Datomic's periodic background **indexing jobs**, which empty the accumulated memory index (novelty) and merge it into the durable index tiers. Between indexing jobs, recent datoms are served from the in-memory novelty plus the log, not from these segments. The traversal below assumes a query reaching into that already-indexed history.

```
       [ Query Engine (Peer JVM) ]
                  │
                  ▼ (Check L1 Cache)
          [ warm segment? ] ──Yes──► [ Query Locally ]
                  │ No
                  ▼ (Check L2 Cache)
       [ Memcached ] ──Yes──► [ Cache in L1 & Query ]
                  │ No
                  ▼ (Fetch L3 Storage)
  [ SELECT val FROM datomic_kvs WHERE id = ? ]
```

The **L2 (Memcached) tier is optional and Pro-specific**. For the `dev` protocol and Datomic Local (§3) there is no shared L2 — the stack collapses to **L1 (heap object cache) → L3 (local disk)**. **Datomic Cloud** does not use Memcached at all; it caches on SSD-backed query nodes over DynamoDB/S3/EFS. (This document otherwise describes Datomic **Pro**, the architecture in the inspected jar.)

### Execution Steps
Datomic maintains four covering indexes, each a sorted B-tree over the same datoms in a different component order: **EAVT** (row-like, entity-first), **AEVT** (column-like, attribute-first), **AVET** (value lookups; maintained for `:db/index`/`:db/unique` attributes), and **VAET** (reverse-reference, maintained for `:db.type/ref` attributes). The planner picks whichever fits the clause's bound components.

1. **Index Selection**: The query planner analyzes the query clauses. For instance, `[?e :user/email "alice@example.com"]` has the attribute and value bound, prompting the planner to select the **`AVET` (Attribute-Value-Entity-Tx)** index.
2. **Root Fetch**: The Peer loads the root segment of the selected index. Since root nodes are highly read, they are almost always warm in the Peer's local L1 heap memory.
3. **B-tree Traversal**: The Peer traverses down the B-tree:
   * It binary searches the root segment keys to locate the pointer/UUID of the appropriate child segment.
   * It fetches the child segment (from L1, L2, or L3 SQL store) and searches it.
   * Because Datomic's index trees have a high branching factor (~1000), the tree depth is small, requiring very few segment fetches even for massive datasets. The bytecode makes the depth exact: the persistent tree defines **three named levels** — `datomic.index/RootNode` → `DirNode` → leaf segments. Leaf segments typically hold from a few thousand up to tens of thousands of datoms each.
4. **Leaf Processing**: Once the leaf segment is loaded, it is parsed into memory. Datomic deserializes segments into flattened, primitive arrays (to avoid JVM object overhead, achieving a contiguous, cache-friendly layout). The bytecode names the structure: a leaf is `datomic.index/TransposedData` `{cnt, eas, vs, ts, ops}` — a **column-transposed** (struct-of-arrays) datom block with primitive accessors (`getE → long`, `getA → int`, `getT → long`, `isAssertion → boolean`, and typed `getIntV`/`getLongV`/`getDoubleV`/... for values), entity and attribute sharing the packed `eas` array. The Peer runs a fast binary search or scan over the arrays to locate matching datoms and extract the Entity ID `?e`.
5. **Local Joins**: Subsequent clauses (e.g. joining `?e` to look up `:user/name`) are resolved by traversing the `EAVT` index in the same manner. Joins are performed entirely in-memory using merge-joins or index-nested-loop joins inside the Peer.

---

## 5. Discovered Keys in B-tree Traversal

The Peer does not guess or compute segment keys like `uuid-abc` from the query text. Keys are dynamically discovered because **they are stored inside the parent segments themselves**.

### Parent Segment Layout
The node records themselves (from the bytecode): `RootNode` is `{keydata, dirids, dirs}` and `DirNode` is `{keydata, segids, offsets, counts, segs}` — each level holds its children's *ids* (`dirids`/`segids`, the storage keys to fetch) alongside lazily-populated child references (`dirs`/`segs`), and `DirNode` additionally carries per-child `offsets` and `counts`, so positional access and datom counting resolve without touching leaf segments. Conceptually, an intermediate index node contains a sorted sequence of splitting keys (ranges) and their associated child segment UUIDs:

| Range Start (Attribute + Value) | Child Segment Key (UUID Pointer) |
| :--- | :--- |
| `[-infinity]` | `uuid-x11` |
| `[:user/age, 20]` | `uuid-y22` |
| `[:user/email, "alice@example.com"]` | `uuid-abc` |
| `[:user/name, "Bob"]` | `uuid-z33` |

### Key Extraction Workflow
1. The Peer reads and deserializes the parent segment.
2. It binary-searches the range list for `[:user/email, "alice@example.com"]`.
3. The search matches the range starting at `[:user/email, "alice@example.com"]` (but ending before `[:user/name, "Bob"]`).
4. It extracts the associated child pointer value: **`"uuid-abc"`**.
5. The Peer uses this extracted string to fetch the child segment:
   `SELECT val FROM datomic_kvs WHERE id = 'uuid-abc';`

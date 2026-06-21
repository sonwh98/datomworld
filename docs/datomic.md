# Datomic Architecture Deep Dive

This document details the architectural mechanics of Datomic storage: what a backing key-value store must provide, how a SQL database realizes that contract as a "dumb store," how a local embedded backend realizes the same contract against the filesystem, how queries pull index segments locally, and how segment keys are discovered during B-tree traversals.

---

## 1. Storage Abstraction and KV Store Requirements

Datomic decouples logical index representations (B-tree segments) and transactions from physical database drivers using a simple, pluggable storage protocol. All database intelligence (indexing, querying, transaction processing, and caching) is decoupled from storage and pulled into the application processes (**Transactor** and **Peers**); the store itself is a pluggable key-value store of mostly-immutable segments with a small mutable root (the `meta` keys updated by CAS). Whether the backing store is DynamoDB, Cassandra, or a JDBC SQL database, it is reached through the same narrow contract below.

### Storage Abstraction
At the JVM code level, Datomic interacts with storage through the `KVStore` protocol in the `datomic.kv-store` namespace (these are internal, not public API — the signatures below are taken from the compiled `datomic-pro` peer jar). The protocol is deliberately tiny — just four operations over opaque keyed blobs:

```clojure
;; datomic.kv-store — the actual storage protocol (decompiled from datomic-pro)
(defprotocol KVStore
  (put    [this val-map])   ;; Write an entry (id, rev, map, val); also does the CAS — see below
  (get    [this k _])       ;; Read an entry's bytes by key
  (delete [this k _])       ;; Remove an entry by key
  (close  [this]))          ;; Release the backend connection

;; Sibling protocol used to classify transient backend failures for retry
(defprotocol Retryable
  (retryable? [this]))
```

Concrete implementations in the jar — `KVSql`, `KVDynamo`, `KVCassandra` / `KVCassandra2` / `KVCassandra3`, `KVMem`, `KVHotRod`, `KVCluster` — each `implement datomic.kv_store.KVStore`.

Note there is **no separate compare-and-swap method**: CAS is folded into `put`. Each backend realizes it with whatever conditional-write primitive it has, gated on the entry's `rev`. In the SQL store that is literally `update datomic_kvs set rev=?, map=?, val=? where id=? and rev=?` (the `UPDATE ... WHERE id = ? AND rev = ?` shown in §2 below), so the conditional compares the revision, not the old payload bytes; a `put` that updates zero rows lost the race.

A thin stack sits **above** this raw byte store. `datomic.cluster/ClusteredStore` adds value/reference semantics on top of `KVStore` — `create-val`/`get-val` for immutable, content-keyed segments and `set-ref`/`get-ref` for the small set of mutable named references (the "root pointer") — with `datomic.cluster-stack/ValStoreOnKvCache` providing the L1/L2 caching tiers. This is the concrete realization of the "mostly-immutable segments + a small mutable root" model: *segments* are vals, the *root* is a ref.

By keeping the bottom contract this narrow, Datomic remains entirely backend-agnostic. The query engine and indexing system function the same way regardless of the underlying driver.

### Required Properties of a Backing KV Store
For Datomic to function correctly and guarantee ACID transactions, any backing storage driver or database must satisfy the following five properties:

1. **Atomic Compare-And-Swap (CAS)**:
   * *Mandatory Requirement*: The store must support atomic, conditional single-key writes.
   * *Purpose*: Datomic relies on CAS to commit transaction roots atomically (`id='root'`) and to handle failover leases for transactor leadership.
2. **Strong Consistency for Metadata**:
   * The store must guarantee Read-After-Write consistency for keys in the `"meta"` category.
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
Instead of mapping domain models to tables, Datomic creates a single key-value table (PostgreSQL types shown; other backends use equivalents such as `CLOB`/`BLOB`):

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
* **`val`**: Compressed, serialized binary payload of the segment (encoded in Transit or Fressian).

### Key-Value Operations
The `KVStore` operations from §1 map directly onto SQL, all keyed by the single `id` column (these are the literal statements emitted by `datomic.sql`):
* **`put`** (new key): `INSERT INTO datomic_kvs (id, rev, map, val) VALUES (?, ?, ?, ?);`
* **`put`** (CAS update): `UPDATE datomic_kvs SET rev=?, map=?, val=? WHERE id=? AND rev=?;` — see *Concurrency* below.
* **`get`**: `SELECT id, rev, map, val FROM datomic_kvs WHERE id = ?;`
* **`delete`**: `DELETE FROM datomic_kvs WHERE id = ?;`
* **`close`**: releases the JDBC connection (no statement).

### Concurrency and the Root Pointer
Datomic coordinates transactions via a single **Root Pointer** stored under a well-known `meta` key (e.g., `id='root'`).
When a transaction is processed:
1. The Transactor durably appends the transaction to the **log** and incorporates the new datoms into its in-memory **memory index** (novelty). It does **not** write new persistent index B-tree segments on every transaction — those are produced later, in batches, by periodic background **indexing jobs** (see §4). Index and log segments, once written, are immutable.
2. It attempts to atomically swing the database's root pointer from the old state to the new state using **Optimistic Concurrency Control (OCC)** — the SQL realization of the rev-gated `put` (the CAS folded into `KVStore/put`, §1). The conditional update is gated on the entry's `rev` counter, not on comparing the old payload bytes:
   ```sql
   UPDATE datomic_kvs 
   SET val = :new_root_bytes, rev = :new_rev 
   WHERE id = 'root' AND rev = :old_rev;
   ```
3. If this query updates **1 row**, the transaction commits. If it updates **0 rows**, it means the Transactor has lost its High Availability lease (e.g., a standby transactor took over; see *Transactor High Availability* below). The Transactor does *not* retry; it steps down and aborts the transaction. It is the application/peer's responsibility to retry the transaction against the new active Transactor.

### Transactor High Availability (HA)
Transactor leadership is coordinated using SQL-level leases. Transactors heartbeat their status using Compare-And-Swap (CAS) updates against a well-known `meta` key, gated on its `rev` counter:
```sql
UPDATE datomic_kvs 
SET val = :heartbeat_with_my_id, rev = :new_rev
WHERE id = 'transactor-lease' 
  AND (rev = :old_rev OR rev IS NULL);
```

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
* **Novelty is pushed transactor → all Peers.** On each commit the transactor broadcasts the transaction's datoms to every connected Peer, which folds them into its in-memory **memory index** (novelty). This keeps each Peer's `db` value current without re-reading storage.

**A Peer does not load the complete index.** Two things live in a Peer, and only one is bounded by total data size — and it isn't:

* The **persistent index** is fetched **lazily and partially**: a query navigates the covering tree from its root and pulls only the few segments it traverses, into a **bounded LRU object cache** (configurable, e.g. `datomic.objectCacheMax`). Cold segments come from storage on first touch, then stay cached; different Peers cache different working sets.
* The **in-memory novelty** is held in full, but it is only the datoms since the last indexing job (emptied periodically by background indexing), so it is bounded by indexing cadence, not by database size.

A `db` value is thus a cheap immutable snapshot — a pointer to a persistent index root (a basis-t) plus the current novelty — and a Peer's footprint is roughly `novelty + hot working set`. This is why a Peer can query a database far larger than its heap: it never needs the whole index resident.

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

### Execution Steps
1. **Index Selection**: The query planner analyzes the query clauses. For instance, `[?e :user/email "alice@example.com"]` has the attribute and value bound, prompting the planner to select the **`AVET` (Attribute-Value-Entity-Tx)** index.
2. **Root Fetch**: The Peer loads the root segment of the selected index. Since root nodes are highly read, they are almost always warm in the Peer's local L1 heap memory.
3. **B-tree Traversal**: The Peer traverses down the B-tree:
   * It binary searches the root segment keys to locate the pointer/UUID of the appropriate child segment.
   * It fetches the child segment (from L1, L2, or L3 SQL store) and searches it.
   * Because Datomic's index trees have a high branching factor (~1000), the tree depth is small (3 or 4 levels), requiring very few segment fetches even for massive datasets. Leaf segments typically hold a few thousand datoms each.
4. **Leaf Processing**: Once the leaf segment is loaded, it is parsed into memory. Datomic deserializes segments into flattened, primitive arrays (to avoid JVM object overhead, achieving a contiguous, cache-friendly layout). The Peer runs a fast binary search or scan over the arrays to locate matching datoms and extract the Entity ID `?e`.
5. **Local Joins**: Subsequent clauses (e.g. joining `?e` to look up `:user/name`) are resolved by traversing the `EAVT` index in the same manner. Joins are performed entirely in-memory using merge-joins or index-nested-loop joins inside the Peer.

---

## 5. Discovered Keys in B-tree Traversal

The Peer does not guess or compute segment keys like `uuid-abc` from the query text. Keys are dynamically discovered because **they are stored inside the parent segments themselves**.

### Parent Segment Layout
An intermediate index node contains a sorted sequence of splitting keys (ranges) and their associated child segment UUIDs:

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

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

**Storage — the "dumb store."** Storage is treated as a **dumb key-value store of opaque blobs**. All intelligence — indexing, query planning, transaction processing — lives *outside* storage. The store sees only opaque keyed bytes and need only support `put` (with the conditional write folded in: creates are unconditional, overwrites are gated on an `:ensure` map carrying the expected `:rev`), `get`, `delete`, and `close`. It does not know what a "table" or an "index" or a "join" is, which is why **storage is swappable**: the same Datomic code runs against Postgres, DynamoDB, Cassandra, or a local directory of files. Only the small set of `meta` keys (root pointer, lease) needs strong consistency; the bulk of data — immutable index and log segments written once under never-reused keys — can sit in an eventually-consistent store without harm. A property Postgres cannot exploit, because its data pages are mutable. See §1–§3 for the full contract and realizations.

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
At the JVM code level, Datomic interacts with storage through the `KVStore` protocol in the `datomic.kv-store` namespace (these are internal, not public API; the binaries have been Apache 2.0 since 1.0.6726, April 2023, so this inspection is unambiguously permitted).

**Provenance of everything in §1–§2.** Three techniques against
`~/app/datomic/peer-1.0.7482.jar` and `~/app/datomic/datomic-transactor-pro-1.0.7482.jar`:

1. `javap -cp peer-1.0.7482.jar datomic.kv_store.KVStore` — gives method names and arities from `datomic/kv_store/KVStore.class` (compiled from `kv_store.clj`).
2. Loading the namespace and reading the protocol map — `:sigs` gives **`:arglists` and `:doc` verbatim** (parameter names and docstrings survive compilation in the protocol var's metadata even though `javap` cannot see them), and `:on-interface` / `:method-map` / `:impls` give the rest of its shape. `ns-publics` gives everything else in the namespace. The signature blocks below are quoted, not reconstructed:
   ```sh
   java -cp "peer-1.0.7482.jar:lib/*" clojure.main -e "
   (require 'datomic.kv-store)
   (doseq [[_ s] (:sigs datomic.kv-store/KVStore)]
     (println (:name s) (pr-str (:arglists s)) (pr-str (:doc s))))"
   ```
3. Constant-pool string extraction (`javap -v`, or `strings` over the class bytes) — recovers the literal SQL of §2 and the keyword names each backend references.
4. **Running the thing.** `KVMem` is directly constructible — `(KVMem. (ConcurrentHashMap.))` from the transactor jar — so `put`/`get` semantics can be exercised rather than inferred. The `:ensure` rules below were established this way; the docstring alone does not disclose that overwrites specifically require `:rev`.

`get` and `delete` genuinely take **two** arguments (confirmed: `get(Object, Object)` / `delete(Object, Object)` in the bytecode), and both trailing parameters are named `consistent?` in the arglists. The trailing argument's role is recovered from the DynamoDB backend: it is a **consistent-read flag**. `KVDynamo.get` branches on it and, when truthy, adds `:consistentRead` to the `datomic.ddb/get-item` request (the keyword is visible in the class's constant pool and static initializer). `KVMem` ignores it (bytecode references only `this.m` and the key) and `KVSql` binds only the key in its `WHERE id = ?` — both are read-your-writes by construction. This is how the "strong consistency for `meta` keys" requirement below is actually implemented: per-read, on the backends that need it, not per-store.

The whole namespace is **four methods, one predicate, and one dynamic var.** Reconstructed exactly from the protocol's `:sigs` — arglists and docstrings verbatim, including the ragged indentation inside `put`'s docstring, which is the original source formatting:

```clojure
;; datomic.kv-store — the actual storage protocol (decompiled from datomic-pro)
(defprotocol KVStore
  (put [_ val-map]
    "(put {:id key :v buf :ensure check-map :other-keys ...}) -> :ok or nil
          optional :ensure {:akey aval ...}
          special treatment of {:id nil} == exists false")
  (get [_ key consistent?]
    "returns {:id key :v buf :other keys} or nil")
  (delete [_ key consistent?]
    "returns :ok")
  (close [_]
    "Closes resources opened for KVStore"))

;; Sibling protocol used to classify transient backend failures for retry
(defprotocol Retryable
  (retryable? [_]))          ; no docstring

(def ^:dynamic *retry*)      ; no docstring, and unbound by default
```

`*retry*` being unbound rather than defaulted means any caller reading it must bind it first — consistent with it being a retry policy the calling layer installs per call, paired with `Retryable/retryable?` deciding which backend failures are worth retrying.

The generated JVM interface, for comparison:

```
public interface datomic.kv_store.KVStore {
  public abstract java.lang.Object put(java.lang.Object);
  public abstract java.lang.Object get(java.lang.Object, java.lang.Object);
  public abstract java.lang.Object delete(java.lang.Object, java.lang.Object);
  public abstract java.lang.Object close();
}

public interface datomic.kv_store.Retryable {
  public abstract java.lang.Object retryable_QMARK_();
}
```

Everything is `Object` in and out — every `:tag` in `:sigs` is `nil`, so there are no type hints to erase. Protocol metadata:

| field | value |
| :--- | :--- |
| protocol var `:doc` | `nil` — the protocol itself carries no docstring |
| `:on` / `:on-interface` | `datomic.kv_store.KVStore` |
| `:method-map` | `{:put :put, :get :get, :delete :delete, :close :close}` |
| `:sigs` `:tag`, all four | `nil` |
| `:impls` | `()` — **empty** |

The empty `:impls` is worth knowing: every backend implements the *interface* directly via `deftype`, not through `extend-protocol`, so none of them register in the protocol's impl map. You cannot enumerate backends by interrogating the protocol at runtime; you have to scan the jar for classes implementing `datomic.kv_store.KVStore` (which is how the list below was obtained).

Three things the docstrings pin down that the arities alone do not. **`put` takes
a single argument** — there is no separate key parameter; the key is the `:id`
field *inside* `val-map`, which makes `put` asymmetric with `get`. **The payload
key is `:v`**, holding an opaque buffer (`:val` is the SQL *column* name of §2,
not the map key). And **the entry map is open**: `:other-keys` on write and
`:other keys` on read, so a backend round-trips fields the protocol does not
name.

Concrete implementations that directly `implement datomic.kv_store.KVStore` (confirmed in disassembled bytecode): `KVSql`, `KVMem`, `KVDynamo`, `KVHotRod`, and the Cassandra drivers `KVCassandra`, `KVCassandra2`, `KVCassandra3` — all seven present in the peer jar (the Cassandra v2/v3 classes also ship in the transactor jar). `KVCluster` is a layer *above* raw KV: it implements `datomic.cluster.ClusteredStore` (the value/reference layer, next subsection) and composes shards — it is **not** itself a `KVStore`. The peer jar also carries a second-generation storage SPI, `datomic.core2.*` — the Cloud-style architecture living alongside, not inside, the `KVStore` stack described here. It is a substantially simpler contract and is specified in full below (*The second generation*).

Note there is **no separate compare-and-swap method**: the conditional write is folded into `put`, which returns `:ok` on success and `nil` when the condition fails.

**The guard is `:ensure`, and `:rev` is privileged inside it.** The behaviour below was established by exercising `KVMem` directly (construct it over a `ConcurrentHashMap` and call `put`/`get`), not inferred from the docstring:

* **Create is unconditional.** `put` on an *absent* key succeeds with or without `:ensure`. Only `:id` is structurally required — a `val-map` without it throws `NullPointerException`. `:v` is optional; `{:id "b"}` alone stores fine.
* **Overwrite requires `:ensure` carrying a matching `:rev`.** `put` on a *present* key is refused (returns `nil`, stored entry untouched) unless `:ensure` contains a `:rev` equal to the stored entry's `:rev`. No `:ensure` at all is refused; `:ensure {}` is refused; an `:ensure` naming only non-`:rev` fields is refused **even when those fields match**. `:rev` is not one guard field among equals — it is the one that licenses an overwrite.
* **Extra `:ensure` keys are conjunctive.** Alongside a matching `:rev`, additional caller-defined keys are checked by equality and can veto the write: `{:rev 0, :mine 42}` succeeds where `{:rev 0, :mine 99}` fails.
* **`{:id nil}` is the exists-false assertion** the docstring mentions. `:ensure {:id nil}` succeeds on an absent key and fails on a present one — compare-and-create.
* **No monotonicity at this layer.** The *new* `:rev` is unconstrained: writing rev 1 over stored rev 5 succeeds as long as `:ensure {:rev 5}` matches, and so does rewriting the same rev. "Must be higher" is `set-ref`'s rule (next subsection), layered on top.

`KVMem` realizes this in `put-when` (`datomic/kv_mem/KVMem$put_when__30075.class`, constant pool: `select-keys`, `keys`, `ensure`, `dissoc`, `ok`) — the `:ensure` map is compared against the stored entry and then `dissoc`ed before storing, so it never persists. `KVSql` has the same shape at the SQL level, which is why §2 emits two distinct statements: a plain `insert` for the create case and the rev-gated `update datomic_kvs set rev=?, map=?, val=? where id=? and rev=?` for the overwrite case, where zero rows updated means the write lost the race.

#### What `:rev` is for

The `:rev` field in a stored entry is **not consumed by the write that puts it there**. It is published for whoever writes *next*: an overwrite must quote it back in `:ensure`. Each entry therefore carries the precondition for its own replacement, and that is the whole of its job.

```clojure
(put s {:id "a" :v "v1" :rev 0})                    ; publishes token 0
(put s {:id "a" :v "v2" :rev 1 :ensure {:rev 0}})   ; quotes 0, publishes 1
(put s {:id "a" :v "v3" :rev 2 :ensure {:rev 1}})   ; quotes 1, publishes 2
```

Four properties fix what `:rev` is, all established by exercising `KVMem`:

* **The store never mints, increments, or validates it — the caller owns it end to end.** `put` compares the value the caller *claims* is current against the stored one, then stores whatever new `:rev` the caller supplies, unexamined. Hence the absence of any monotonicity check noted above: rev 1 over stored rev 5 is accepted. Contrast `ClusteredStore/set-ref`, whose docstring makes the caller's ownership explicit ("You must have obtained rev from a prior read and incremented it") and adds the "must be higher" rule the store itself does not enforce.
* **The key name is hardcoded, not a convention.** An entry holding both `:rev 7` and a twin field `:myrev 7` accepts `:ensure {:rev 7}` and refuses `:ensure {:myrev 7}` — an identical value under a different name will not license the write. Other `:ensure` keys can only add further conditions on top of `:rev`; none can substitute for it.
* **Publishing `:rev` is what makes an entry replaceable in place.** An entry stored without one cannot be overwritten by any `put`: no `:ensure` matches a missing revision, and `:ensure {:rev nil}` is refused too. (`delete` followed by a fresh `put` does succeed — so a rev-less entry is not immutable in the absolute sense, it is *unreplaceable atomically*. Destroying and recreating it is a two-step sequence during which the key is observably absent, which is exactly what a CAS exists to avoid.) The freeze can also be applied mid-life: because `put` is a wholesale replace, an overwrite whose *new* map omits `:rev` succeeds and leaves the entry unreplaceable thereafter:
  ```clojure
  (put s {:id "a" :v "v1" :rev 0})                    ; => :ok,  {:id "a" :v "v1" :rev 0}
  (put s {:id "a" :v "v2" :ensure {:rev 0}})          ; => :ok,  {:id "a" :v "v2"}   <- no :rev
  (put s {:id "a" :v "v3" :rev 1 :ensure {:rev 0}})   ; => nil
  (put s {:id "a" :v "v3" :rev 1 :ensure {:rev nil}}) ; => nil
  (put s {:id "a" :v "v3" :rev 1})                    ; => nil   no put can replace it
  (delete s "a" nil)                                  ; => :ok   ...but delete can remove it,
  (put s {:id "a" :v "v3"})                           ; => :ok   and a fresh put recreates it
  ```
* **It does not survive a rewrite unless re-supplied.** `put` replaces the entry wholesale rather than merging, so `:rev` (like any other field) persists only because the caller wrote it into the new map.

**`:rev` is therefore never mandatory — it is what you supply when you need compare-and-swap.** Create, read and delete all work without it; only atomic in-place replacement requires it. Presence or absence of `:rev` *is* an entry's in-place mutability: not a versioning scheme bolted onto a mutable cell, but the thing that makes the cell replaceable at all. This is what the val/ref split of the next subsection rests on — `create-val` writes segments with no rev, because content-addressed data is written once and never updated, while `set-ref [cs ref-key rev vkey]` writes refs with one, because a root pointer is precisely the thing that must be swung under contention. Datomic never "stamps" a revision onto anything; the caller supplies `:rev` exactly when it wants a cell to remain atomically writable.

A thin stack sits **above** this raw byte store. `datomic.cluster/ClusteredStore` adds value/reference semantics on top of `KVStore` — `create-val`/`get-val` for immutable, content-keyed segments and `set-ref`/`get-ref` for the small set of mutable named references (the "root pointer") — with `datomic.cluster-stack/ValStoreOnKvCache` providing an in-process value/reference cache over the raw KV store. This is the concrete realization of the "mostly-immutable segments + a small mutable root" model: *segments* are vals, the *root* is a ref. (This caching layer is distinct from the Peer's L1/L2/L3 *object-cache* stack in §4, which is what "L1/L2/L3" refers to throughout this document.)

The full surface, with arglists and docstrings verbatim from `(:sigs datomic.cluster/ClusteredStore)`:

```clojure
(create-val [cs val-key buf] [cs priority val-key buf])
  ;; "Creates a new value in the store. Returns a reference to :created or nil"
(get-val    [cs val-key])
  ;; "Gets the value at a key. Returns a reference to {:buf buf} or nil if not found"
(set-ref    [cs ref-key rev vkey])
  ;; "Makes vkey the new value of ref, iff rev is higher than existing
  ;;  rev. You must have obtained rev from a prior read and incremented
  ;;  it. Returns a reference to :ok or :conflict. set-ref with a rev of 0
  ;;  can create a ref."
(get-ref    [cs ref-key])
  ;; "Returns a reference to {:rev nnn, :key k} or nil if not found"
(get-pod      [cs pod-key])
  ;; "Gets the value in a pod. Returns a reference to {:rev nnn, :etag xxx, :buf buf}
  ;;  and any metadata keys. or nil if not found."
(get-pod-meta [cs pod-key])
  ;; "Returns reference to pod-meta, without walking entire linked list a la get-pod."
(update-pod*  [cs pod-key rev etag buf metamap])
  ;; "With nil etag, creates or resets pod to be supplied value. When
  ;;  etag is non-nil, appends a non-nil buf to the current value of the
  ;;  pod (a nil buf just 'touches' the pod, incrementing rev and leaving
  ;;  the value intact), iff etag matches.  In all cases rev must be one
  ;;  higher than existing rev. You must obtain rev and etag from a prior
  ;;  get/update, and increment rev. Keys in metamap must be
  ;;  namespaced. Returns a reference to {:rev nnn, :etag xxx, :buf buf}
  ;;  or {:failed :conflict}."
(delete           [cs key])   ;; "Soft delete. Returns a reference to :ok"
(delete-reference [cs key])   ;; "Delete a reference (pod or ref).  Returns a reference to :ok"
```

Note that `ClusteredStore/delete` is a **soft** delete, unlike the raw `KVStore/delete` (which §2 realizes as an unconditional `delete from datomic_kvs where id = ?`). Note also that pods carry a *stricter* guard than refs: `set-ref` accepts any rev "higher than existing", while `update-pod*` requires rev to be "one higher than existing" and additionally matches an `etag`.

Two structural facts fall straight out of those signatures, and they are the reason this layer — not the raw SPI — is what Datomic's own engine calls:

* **The val write path takes a key and a bare buffer.** `(create-val cs val-key buf)` — no map, no envelope, no revision. The `{:id ... :v ... :ensure ...}` envelope exists only at the raw `KVStore` boundary *below* this, where it is the store's own bookkeeping row. Symmetrically, `get-val` returns `{:buf buf}`, a one-field wrapper with no revision in it. Immutable content-keyed values never touch a rev at any layer.
* **A ref cannot hold data.** `set-ref`'s third parameter is `vkey` — *the key of a val*, not bytes. The signature itself forbids inline payloads in the mutable cell, so the entire mutable surface of a Datomic database is a set of two-field `{:rev nnn, :key k}` pointers.

The rest of the surface is richer than val/ref:

* **The ref CAS contract is caller-driven and rev-monotonic.** Per the `set-ref` docstring above, the *caller* obtains the rev from a prior read and increments it — the store does not mint it — and a rev of 0 creates the ref. Note the guard is "higher than existing", not equality, so it tolerates a caller that skips ahead. (The SQL `... WHERE id=? AND rev=?` in §2 is `KVSql` realizing the same idea against an expected *prior* rev, via `:ensure`.)
* **Pods are the third kind, alongside vals and refs.** Mutable byte containers with an append mode and their own `etag`+rev guard (docstrings above); `get-pod-meta` exists to avoid walking the chain, confirming pods link. The database **catalog** — the name→id map behind `create-database` / `rename-database` / `delete-database` — is stored in pods (`datomic.catalog`, `pod->catalog`).
* **Consistency plumbing.** `datomic.cluster.Get2/get-val2` is "like ClusteredStore/get-val, but takes an opts map that flows to underlying implementations" — the path by which a consistent read request reaches `KVStore/get`'s trailing argument. The interface docstring also notes that any operation "might throw an exception on deref if no quorum is available."
* **Key naming differs by kind, and only refs are nameable.** Keys are minted by `new-val-key` / `new-ref-key` / `new-pod-key`, verified by calling them:
  ```clojure
  (new-val-key)        ;=> "6a69c633-bb7f-441f-902e-d7014411c5a4"   bare squuid, no prefix
  (new-ref-key "root") ;=> "ref-root"                               prefix + caller-supplied name
  (new-pod-key)        ;=> "pod-6a69c633-92b6-4d9d-81b1-2bb613f57d17"
  ```
  Vals and pods get random keys (the shared leading hex is the squuid time component); **only `new-ref-key` takes an argument**, producing `"ref-" + name` rather than a UUID. That asymmetry is load-bearing: a val key is never known in advance, only discovered by traversing a pointer that holds it (§5), whereas refs must be nameable without having been found first — which is what makes `ref-root` a well-known bootstrap key. An entry's kind is recoverable from its key only in the weak sense that vals are the ones carrying *no* prefix. Storage paths are built by `datomic.cluster/path` as `tenant/db/%03X/key` — a three-hex-digit (4096-bucket) partition prefix that spreads keys across backend key space. This is the concrete form of §2's "category is a convention encoded into the key."

By keeping the bottom contract this narrow, Datomic remains entirely backend-agnostic. The query engine and indexing system function the same way regardless of the underlying driver.

### The minimal implementation: `KVMem`
The contract is small enough that an in-memory map satisfies it — which is exactly what the `mem://` backend is. `datomic.kv-mem/KVMem` (in the **transactor** jar, `datomic/kv_mem/KVMem.class`; it is not in the peer jar) holds a single field `m` and implements all four methods:

```clojure
;; datomic.kv-mem (sketch) — the whole store is one mutable map
(deftype KVMem [m]            ; m: a java.util.concurrent.ConcurrentMap of id -> {:id key :v buf ...}
  KVStore
  (put    [_ v]   (put-when m v))   ; conditional insert/replace gated on :ensure
  (get    [_ k _] (.get m k))
  (delete [_ k _] (.remove m k))
  (close  [_]     nil))
```

Two things make this work — and they are the same two everywhere:

* **A mutable, shared cell.** The *values* are plain Clojure maps (`{:id key :v buf ...}`), but the *store* must be a map inside a mutable, atomic container so a `put` is observable by a later `get`. `KVMem` uses a `ConcurrentMap`; an `atom` wrapping a persistent map would do equally well. A bare immutable map cannot be a `KVStore` — it has no identity-preserving mutation.
* **The conditional write folded into `put`.** The `put-when` helper creates unconditionally on an absent key, and on a present key writes only if `:ensure` carries a `:rev` matching the stored one (plus any additional `:ensure` fields), then strips `:ensure` before storing. Its constant pool (`select-keys`, `keys`, `ensure`, `dissoc`, `ok`) spells out the comparison; `ConcurrentMap.replace` (or `compare-and-set!` on an atom) supplies the atomicity. This is the in-memory analog of `... WHERE id=? AND rev=?`.

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

### The second generation: `datomic.core2.*`

The same peer jar ships a later storage SPI for the Cloud-style architecture. It is worth reading against §1 above, because it is the same team's second attempt at the same problem and it discards nearly every complication the original `KVStore` accumulated. Recovered the same way (`:sigs` reflection plus `javap`); arglists and docstrings verbatim.

**It is not one protocol.** Where v1 had a single four-method `KVStore`, core2 has three separate concerns, each split into **one protocol per operation** — nine in total:

```clojure
;; datomic.core2.val-store.spi — immutable, content-keyed values
(defprotocol Put    (-put    [_ k v opts]))  ;; "SPI for datomic.core2.val-store/put."
(defprotocol Get    (-get    [_ k opts]))    ;; "SPI for datomic.core2.val-store/get."
(defprotocol Delete (-delete [_ k opts]))    ;; "SPI for datomic.core2.val-store/delete."

;; datomic.core2.log.spi — the append-only log
(defprotocol Append (-append [_ header body]))
(defprotocol Scan   (-scan   [_ opts]))      ;; "SPI for datomc.core2.log/scan. Return value ignored."
(defprotocol Delete (-delete [_ t]))
(defprotocol Item   (-item-header [_ item])
                    (-item-body   [_ item]))

;; datomic.core2.atom.spi — the mutable cell
(defprotocol DurableAtom
  (-swap-vals! [_ f ch])   ;; "Like atom swap-vals! but puts result on channel"
  (-sync       [_ ch]))    ;; "Puts latest value from server on channel"
```

The public layers over them:

```clojure
;; datomic.core2.val-store
(put    [val-store k v] [val-store k v opts])
(get    [val-store k]   [val-store k opts])
(delete [val-store k]   [val-store k opts])

;; datomic.core2.log
(append [log header] [log {:keys [t next-t] :as header} body])
(scan   [log opts])                ;; opts: {:keys [direction t ch limit]}
(ensure-tombstone [log tombstone])

;; datomic.core2.atom — Clojure's own atom API, made durable
(swap!      [a f & args])
(swap-vals! [a f & args])
(reset!     [a v])
(sync       [a])

;; datomic.core2.atom.logged — the implementation: an atom on top of a log
(create [{:keys [log header value serialize]}])
(load   [args])
```

Supporting fns: `partition-key [s]`, `splice-partition-key [k pk]`, `val-op-succeeded? [store-api-result]`, `no-val-error [k v]` (val-store); `normalize-scan-opts`, `result` (log); `-read-latest`, `-validated-v` (logged atom).

#### Backends, and why the two SPIs take different ones

The complete set shipped in the jars. The transactor jar carries no `core2` at all, so this is everything:

| SPI | implementations |
| :--- | :--- |
| val-store | `fs`, `s3` (both an aws-api and an sdkv1 client), `double-store` |
| log | `ddb`, `mem` |

**The sets are disjoint, and that is the design.** The val store has no DynamoDB implementation; the log has no S3 implementation. `mem` is the log's testing backend, the way `KVMem` is for v1 — which leaves DynamoDB as the only production log.

The asymmetry follows from what each SPI asks of a backend:

* **A val store needs no coordination at all.** Keys are content-derived, so every write goes to a fresh, never-reused key and two writers racing on the same key are writing *identical bytes*. The write is idempotent by construction. Plain `put`/`get`/`delete` suffices, which is why object storage fits — and why the 10KB–1MB segments of §1's property 3 can sit on the cheapest durable tier available.
* **A log must totally order appends.** Its public arity is `(append [log {:keys [t next-t] :as header} body])` — the *caller* supplies `t`, so the backend must reject an append at a `t` already taken. Otherwise two writers claim the same position and one transaction vanishes with no error raised. That is a conditional write, and the DynamoDB backend's constant pool contains exactly that: `conditional-put-request`. It is the only conditional-write vocabulary anywhere in the `core2` tree.

So S3 was not merely a slower log — before it gained conditional writes it could not implement `append` *correctly* at all, because last-write-wins on a contended `t` loses data silently. This is the same argument as §1's property 1, but confined: instead of demanding conditional writes from **every** backend, `core2` demands them from **one component**, and lets everything else run on storage that has never had them.

Note why the obvious cheaper fix does not work. Keeping v1's protocol and giving an S3 backend a **no-op `:ensure`** would be a silent lie: the transactor's commit is a single conditional root swing (§2), so under a fake guard both writers succeed, both believe they committed, and the loser is never told — no exception, no `nil`, no zero row count. Nothing in the protocol lets a caller detect this, either; `datomic.kv-store` exposes only `KVStore`, `Retryable`, and `*retry*`, with no capability query. It is worse than it sounds because the conditional write is *folded into* `put`: ignoring `:ensure` also disables the `{:id nil}` exists-false assertion, turning create-if-absent into unconditional overwrite. Throwing instead of ignoring is honest but makes `put` partial — legal on some backends, fatal on others, discoverable only at runtime. And neither variant removes the need for real coordination somewhere, so the split exists either way; the only question is whether it is explicit in the contract or implicit and unchecked. `core2` makes the invalid state unrepresentable: there is no conditional write in the val store to fake, because there is no replace operation at all.

Two notes on the current state:

* **The capability constraint has since lifted, and the split survived it.** S3 gained conditional writes (`If-None-Match`) in late 2024; this jar was built 2025-10-31, a year later, and still ships no S3 log. The likely reason is workload rather than capability — the log is the hot path on every commit, where a single-digit-millisecond conditional put beats an S3 PUT by an order of magnitude, and `scan` wants cheap indexed range reads; the val store is the opposite profile, large and cold and read-mostly. That reading is inference, not something the artifact states.
* **Tiering is a val-store-only luxury.** `double-store` takes `{:keys [near-store far-store repair-metric get-fallback-msec]}` (fallback default 20ms): a fast near tier over a durable far tier, with read fallback and self-repair. That composition is only available because the value store has no coordination duties to preserve across the tiers.

#### What changed, point by point

| | v1 `KVStore` (§1) | core2 |
| :--- | :--- | :--- |
| Write signature | `(put val-map)` — key buried in the map | `(put store k v opts)` — positional |
| Conditional write | `:ensure` map, with `:rev` privileged inside it | none: values are immutable |
| Revision | `:rev`, required for any overwrite | absent from the value path entirely |
| Mutation | folded into `put` | its own `DurableAtom`; `swap!` takes a **function** |
| Log | shares one keyspace with segments, by key convention | its own SPI |
| Granularity | one protocol, four methods | nine single-method protocols |
| Async | synchronous return | core.async channels throughout (`ch`) |

Three of those are the substantive ones. **The envelope is gone** — `put` takes `k` and `v` positionally, so v1's asymmetry (a `put` taking one map while `get` takes a key) disappears, and with it the `{:id nil}` exists-false overload. **The revision is gone from the value path**, because content-keyed immutable values have no replace operation for a guard to protect; §1's whole `:ensure`/`:rev` mechanism exists only to make *overwriting* safe, and core2 removes overwriting instead. **Mutation moved from a guard to a function**: `swap!` takes `f` and retries internally, so the read-modify-CAS loop that every v1 caller had to write correctly now lives inside the abstraction.

The per-operation split is the remaining move. `:impls` is empty on all nine protocols — backends implement the generated interfaces directly via `deftype`, exactly as in §1 — but the segregation means a read-only store implements `Get` alone rather than stubbing a `put` it cannot honor.

---

## 2. Using Relational Databases as a "Dumb Store"

A SQL database is one concrete backend for the contract in §1. Datomic uses it purely as a key-value store: all intelligence stays in the Transactor and Peers, and SQL sees only opaque keyed blobs.

### SQL Schema
Instead of mapping domain models to tables, Datomic creates a single key-value table (Postgres types shown; other backends use equivalents such as `CLOB`/`BLOB`):

This is the literal DDL `datomic.sql` emits (recovered from the class constant pool), not a reconstruction — hence lowercase, `varchar` rather than `TEXT`, and an inline primary key:

```sql
create table if not exists datomic_kvs (id varchar primary key, rev integer, map varchar, val bytea)
```

Other backends substitute equivalents such as `CLOB`/`BLOB`. Column by column:

* **`id`**: The unique segment identifier and sole primary key. Datomic generates these (UUID-style opaque strings); the data category (`"index"` for B-tree nodes, `"log"` for transaction logs, `"meta"` for database metadata) is a convention encoded into the key, not a separate column. Corresponds to the `:id` field of §1's `val-map`.
* **`rev`**: A revision counter, and `KVSql`'s conditional-write guard. It is the caller-supplied token a *later* writer must quote to overwrite the row — see §1, *What `:rev` is for*, for the full semantics; note in particular that the store never increments it, and that a row written with `rev` null can never be updated.
* **`map`**: Metadata associated with the entry. The protocol's entry map is open (`:other-keys`/`:other keys`, §1), so the natural reading is that non-`:id`/`:v` fields serialize into this varchar — **but that is inference, not verified**; the surviving docstrings say nothing about this column.
* **`val`**: Compressed, serialized binary payload of the segment (encoded in Fressian, Datomic's internal serialization format). **Note the naming mismatch:** the SQL column is `val`, but the corresponding Clojure entry key is `:v`.

### Key-Value Operations
The `KVStore` operations from §1 map directly onto SQL, all keyed by the single `id` column. These are the literal statements emitted by `datomic.sql`, quoted as they appear in the constant pool:
* **`put`** (new key): `insert into datomic_kvs (id, rev, map, val) values (?, ?, ?, ?)`
* **`put`** (conditional update): `update datomic_kvs set rev=?, map=?, val=? where id=? and rev=?` — see *Concurrency* below.
* **`get`**: `select id, rev, map, val from datomic_kvs where id = ?` — all four columns, so the whole entry map is reconstructed on read.
* **`delete`**: `delete from datomic_kvs where id = ?`
* **`close`**: releases the JDBC connection (no statement).

Two more statements ship alongside these: `grant all on datomic_kvs to datomic` / `to public` for provisioning, and `select 1 from dual` as the connection-validation query (`datomic.kv-sql-ext/validation-query`).

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

---

## Appendix: Discovered Datoms-Reading Functions (JAR Analysis)

This section documents the actual functions found via bytecode analysis of `peer-1.0.7482.jar` that Datomic Peers use to read datoms from storage. These are internal implementation details, not public API.

### Public API Layer (datomic.Database interface)

The Peer exposes three methods for reading datoms, all returning `Iterable<datomic.Datom>`:

| Method | Signature | Purpose |
|--------|-----------|---------|
| `datoms` | `datoms(Object index, Object... components)` | Returns datoms from specified index (EAVT, AEVT, AVET, VAET) matching component pattern |
| `seekDatoms` | `seekDatoms(Object index, Object... components)` | Seeks to position in index, returns all datoms from that point |
| `indexRange` | `indexRange(Object attrId, Object start, Object end)` | Returns datoms in range for given attribute |

These methods are implemented by `datomic.db.Db` — a record holding `id`, `memidx`, `indexing`, `mid_index`, `index`, `history`, `memlog`, and basis-t tracking fields (`basisT`, `nextT`, `indexBasisT`).

### Implementation Layer (datomic.db namespace)

The core datom-reading implementations (Clojure functions compiled to classes):

* **`datomic.db$datoms`** — Core implementation of `datoms()` API method
* **`datomic.db$seek_datoms`** — Core implementation of `seekDatoms()` 
* **`datomic.db$rseek_datoms`** — Reverse seek implementation
* **`datomic.db$get_eidx`** — Gets entity index
* **`datomic.db$get_entity`** — Gets entity by ID

### Index Tree Layer (datomic.index namespace)

The persistent index structure that organizes datoms into B-tree segments:

* **`datomic.index.Index`** — Main index class implementing `IIndex`
  - `seek()` → returns `datomic.iter.Iter` for iterating datoms
  - `seek_seg()` — seeks to specific segment
  - `seekLast()` — seek to end of index

* **`datomic.index.TreeIter`** — Iterator over index tree
  - `next()` / `prev()` — navigate datoms
  - `get()` — get current datom
  - Fields: `lookup`, `root`, `ridx`, `dir`, `didx`, `seg`, `sidx`

* **`datomic.index.RootNode`** — Root of index tree: `{keydata, dirids, dirs}`
* **`datomic.index.DirNode`** — Directory node: `{keydata, segids, offsets, counts, segs}`
* **`datomic.index.TransposedData`** — Leaf segment (columnar datom storage): `{cnt, eas, vs, ts, ops}`

### Storage Backend Layer

The path from index traversal to actual storage read:

* **`datomic.kv_store.KVStore`** — Protocol/interface for storage backends
  - `get(key, consistent?)` — Read entry by key, returning `{:id key :v buf ...}`; `consistent?` flag for strong consistency
  - `put(val-map)` — Write `{:id key :v buf :ensure check-map ...}`; the optional `:ensure` map is the conditional-write guard (§1), returns `:ok` or `nil`
  - `delete(key, consistent?)` — Remove entry, returns `:ok`
  - Implementations: `KVSql`, `KVMem`, `KVDynamo`, `KVHotRod`, `KVCassandra`/`KVCassandra2`/`KVCassandra3`

* **`datomic.core2.val_store.spi.Get`** — Second-gen value store protocol (full SPI in §1, *The second generation*)
  - `_get(k, opts)` — Fetch a value by key; `Put`/`Delete` are separate protocols, one per operation

* **`datomic.cluster_stack.ValStoreOnKvCache`** — L1 cache layer over KV store
* **`datomic.cluster_stack.ValStoreOnCluster`** — Cluster storage implementation

### Iterator Infrastructure (datomic.iter / datomic.btset)

* **`datomic.iter.Iter`** — Base iterator interface
* **`datomic.iter.Iterator`** — Iterator wrapper
* **`datomic.iter.MapIter`** — Map-transforming iterator
* **`datomic.iter.IterCat`** — Concatenated iterator
* **`datomic.iter.MergeIter`** — Merge-sorted iterator (for joining indexes)
* **`datomic.iter.ReversedIter`** — Reverse iterator
* **`datomic.btset.BTSet`** — In-memory B-tree set for novelty
* **`datomic.btset.BTSetIter`** — Iterator over BTSet

### Data Flow Summary

```
datoms() / seekDatoms()                    [Public API]
    ↓
datomic.db$datoms / $seek_datoms           [Implementation]
    ↓
datomic.index.Index.seek()                [Index traversal]
    ↓
datomic.index.TreeIter                    [Tree navigation]
    ↓
datomic.index.RootNode / DirNode          [Node lookup]
    ↓
datomic.core2.val_store$get               [Value store read]
    ↓
datomic.kv_store.KVStore.get()            [Storage backend]
    ↓
Storage (SQL/DynamoDB/Cassandra/files)    [Dumb KV store]
```

Datoms are stored in sorted segments (immutable B-trees). The Peer traverses the tree lazily, pulling only the segments it needs from storage. Segments are cached in the Peer's LRU object cache (L1); cold segments are fetched via `KVStore/get` on cache miss. The index tree has exactly three levels: RootNode → DirNode → leaf segments, with high branching factor (~1000) keeping tree depth small even for large datasets.

---

## Appendix B: Datomic Public API Reference

This section documents the public Java API that applications use to interact with Datomic. These interfaces and classes are the stable, supported API surface (recovered from `datomic-transactor-pro-1.0.7482.jar`).

### datomic.Peer — Entry Point

The `Peer` class provides static methods for database lifecycle, querying, and utility functions. It is the primary entry point for Datomic applications.

| Method | Returns | Description |
|--------|---------|-------------|
| `connect(Object uri)` | `Connection` | Connect to a database at the given URI (e.g., `datomic:sql://dbname?jdbc:postgresql://...`) |
| `createDatabase(Object uri)` | `boolean` | Create a new database; returns true if created, false if already exists |
| `deleteDatabase(Object uri)` | `boolean` | Delete a database and all its data |
| `renameDatabase(Object uri, String newName)` | `boolean` | Rename a database |
| `getDatabaseNames(Object uri)` | `List<String>` | List all database names in a storage |
| `administerSystem(Map params)` | `Object` | Perform administrative operations |
| `q(Object query, Object... args)` | `Collection<List<Object>>` | Execute a Datalog query |
| `query(Object query, Object... args)` | `<T> T` | Execute a query with typed result |
| `qseq(Object query, Object... args)` | `Stream<Object>` | Execute query returning a lazy stream |
| `tempid(Object partition)` | `Object` | Create a temporary ID for a partition |
| `tempid(Object partition, long num)` | `Object` | Create a temporary ID with explicit number |
| `resolveTempid(Database db, Object tempids, Object tempid)` | `Object` | Resolve a tempid to a permanent entity ID after transaction |
| `toT(Object tx)` | `long` | Convert transaction identifier to transaction number (t) |
| `toTx(long t)` | `Object` | Convert transaction number to transaction entity ID |
| `part(Object entityId)` | `Object` | Get the partition of an entity ID |
| `squuid()` | `UUID` | Generate a sequential UUID (SQUUID) for better indexing locality |
| `squuidTimeMillis(UUID squuid)` | `long` | Extract timestamp from a SQUUID |
| `function(Map params)` | `Fn` | Create a transaction function from code |
| `shutdown(boolean shutdownCaffeine)` | `void` | Shut down the Peer (release resources) |
| `cancel(Object fut)` | `void` | Cancel a running future/query |

### datomic.Connection — Database Connection

A `Connection` represents a connection to a specific database. It is the interface for submitting transactions and accessing the database value.

| Method | Returns | Description |
|--------|---------|-------------|
| `db()` | `Database` | Get the current database value (immutable snapshot) |
| `log()` | `Log` | Get the transaction log for this database |
| `transact(List txData)` | `ListenableFuture<Map>` | Submit a transaction synchronously; returns future with result map |
| `transact(List txData, Object timeout)` | `ListenableFuture<Map>` | Submit transaction with explicit timeout |
| `transactAsync(List txData)` | `ListenableFuture<Map>` | Submit transaction asynchronously |
| `transactAsync(List txData, Object timeout)` | `ListenableFuture<Map>` | Submit async with timeout |
| `sync()` | `ListenableFuture<Database>` | Wait for all transactions up to current time to complete |
| `sync(long t)` | `ListenableFuture<Database>` | Wait for database to reach at least transaction t |
| `syncIndex(long t)` | `ListenableFuture<Database>` | Wait for index to include transactions up to t |
| `syncSchema(long t)` | `ListenableFuture<Database>` | Wait for schema changes up to t to be visible |
| `syncExcise(long t)` | `ListenableFuture<Database>` | Wait for excision (deletion) up to t to complete |
| `requestIndex()` | `boolean` | Request background indexing job to run |
| `txReportQueue()` | `BlockingQueue<Map>` | Subscribe to transaction report queue (receives all committed transactions) |
| `removeTxReportQueue()` | `void` | Unsubscribe from transaction report queue |
| `gcStorage(Date olderThan)` | `void` | Trigger garbage collection of storage older than date |
| `release()` | `void` | Release connection resources |

**Transaction Result Map Keys:**
- `:db-before` — Database value before transaction
- `:db-after` — Database value after transaction
- `:tx-data` — List of datoms asserted/retracted by transaction
- `:tempids` — Map of tempids to permanent entity IDs

### datomic.Database — Database Value

A `Database` is an immutable value representing a snapshot of the database at a specific point in time. It provides methods for querying and traversing datoms.

**Constants (Index Identifiers):**
| Constant | Description |
|----------|-------------|
| `EAVT` | Entity-Attribute-Value-Tx index (row-like, entity-first) |
| `AEVT` | Attribute-Entity-Value-Tx index (column-like, attribute-first) |
| `AVET` | Attribute-Value-Entity-Tx index (value lookups, requires `:db/index` or `:db/unique`) |
| `VAET` | Value-Attribute-Entity-Tx index (reverse references, for `:db.type/ref`) |

**Metadata Methods:**
| Method | Returns | Description |
|--------|---------|-------------|
| `id()` | `String` | Database ID |
| `basisT()` | `long` | Transaction number (t) of this database value |
| `nextT()` | `long` | Next available transaction number |
| `asOfT()` | `Long` | If this is an as-of view, the basis t; null otherwise |
| `sinceT()` | `Long` | If this is a since view, the basis t; null otherwise |
| `isHistory()` | `boolean` | True if this is a history database (includes retractions) |
| `isFiltered()` | `boolean` | True if this database has a filter applied |

**Navigation Methods:**
| Method | Returns | Description |
|--------|---------|-------------|
| `entity(Object entityId)` | `Entity` | Get entity by ID, ident, or lookup ref |
| `attribute(Object attrId)` | `Attribute` | Get attribute by ID or ident |
| `ident(Object entityId)` | `Object` | Get the ident keyword for a schema entity |
| `entid(Object ident)` | `Object` | Get the entity ID for an ident |
| `entidAt(Object ident, Object t)` | `Object` | Get entity ID for ident at a specific time |

**Datom Access Methods:**
| Method | Returns | Description |
|--------|---------|-------------|
| `datoms(Object index, Object... components)` | `Iterable<Datom>` | Get datoms matching pattern from index |
| `seekDatoms(Object index, Object... components)` | `Iterable<Datom>` | Seek to position, return all datoms from there |
| `indexRange(Object attrId, Object start, Object end)` | `Iterable<Datom>` | Get datoms in value range for attribute |

**Query Methods:**
| Method | Returns | Description |
|--------|---------|-------------|
| `pull(Object pattern, Object eid)` | `Map` | Pull entity data using pull pattern |
| `pull(Object pattern, Object eid, Object opts)` | `Object` | Pull with options |
| `pullMany(Object pattern, List eids)` | `List<Map>` | Pull multiple entities |
| `pullMany(Object pattern, List eids, Object opts)` | `Object` | Pull many with options |
| `indexPull(Object opts)` | `Stream<Object>` | Streaming index pull |

**Database View Methods:**
| Method | Returns | Description |
|--------|---------|-------------|
| `asOf(Object point)` | `Database` | View database as of a specific time/point |
| `since(Object point)` | `Database` | View database since a specific time/point |
| `history()` | `Database` | View full history (includes retractions) |
| `filter(Predicate<Datom> pred)` | `Database` | Create filtered view with custom predicate |
| `filter(Object filterFn)` | `Database` | Create filtered view with filter function |
| `with(List txData)` | `Map` | Simulate transaction (returns db-with-tx, does not commit) |
| `with(List txData, Object txMeta)` | `Map` | Simulate with transaction metadata |
| `invoke(Object fn, Object... args)` | `Object` | Invoke a database function |
| `dbStats()` | `Map` | Get database statistics |

### datomic.Entity — Entity Interface

Represents a single entity in the database.

| Method | Returns | Description |
|--------|---------|-------------|
| `get(Object attr)` | `Object` | Get attribute value (returns set for cardinality-many) |
| `touch()` | `Entity` | Eagerly load all attributes (returns self) |
| `keySet()` | `Set<String>` | Set of attribute idents this entity has |
| `db()` | `Database` | Database this entity belongs to |

### datomic.Attribute — Schema Attribute Interface

Represents a schema attribute definition.

| Constant | Description |
|----------|-------------|
| `CARDINALITY_ONE` | Single value per entity |
| `CARDINALITY_MANY` | Multiple values per entity (set) |
| `UNIQUE_IDENTITY` | Unique identity constraint (upsert) |
| `UNIQUE_VALUE` | Unique value constraint (no duplicates) |
| `TYPE_REF` | Reference type |
| `TYPE_STRING`, `TYPE_LONG`, `TYPE_DOUBLE`, etc. | Value types |

| Method | Returns | Description |
|--------|---------|-------------|
| `id()` | `Object` | Entity ID of this attribute |
| `ident()` | `Object` | Keyword ident (e.g., `:user/email`) |
| `valueType()` | `Object` | Value type keyword |
| `cardinality()` | `Object` | Cardinality (`:db.cardinality/one` or `/many`) |
| `unique()` | `Object` | Uniqueness constraint or null |
| `isComponent()` | `boolean` | True if this is a component attribute |
| `isIndexed()` | `boolean` | True if AVET index is maintained |
| `hasAVET()` | `boolean` | True if AVET index is maintained |
| `hasNoHistory()` | `boolean` | True if history is not retained |
| `hasFulltext()` | `boolean` | True if fulltext index is maintained |

### datomic.Datom — Single Datum Interface

A datom is a single 5-tuple fact: `[entity attribute value tx added]`.

| Method | Returns | Description |
|--------|---------|-------------|
| `e()` | `Object` | Entity ID (e) |
| `a()` | `Object` | Attribute ID (a) |
| `v()` | `Object` | Value (v) |
| `tx()` | `Object` | Transaction entity ID (tx) |
| `added()` | `boolean` | True if assertion, false if retraction |
| `get(int n)` | `Object` | Get component by index (0=e, 1=a, 2=v, 3=tx, 4=added) |

### datomic.Log — Transaction Log Interface

Access to the immutable transaction log.

| Constant | Description |
|----------|-------------|
| `T` | Key for transaction number in log entries |
| `DATA` | Key for transaction data in log entries |

| Method | Returns | Description |
|--------|---------|-------------|
| `txRange(Object start, Object end)` | `Iterable<Map>` | Get transactions in range (inclusive start, exclusive end) |

Each log entry map contains:
- `:t` — Transaction number
- `:data` — Collection of datoms in that transaction

### datomic.functions.Fn — Transaction Function Interface

For defining functions that run inside the transactor.

| Method | Returns | Description |
|--------|---------|-------------|
| `lang()` | `String` | Language (`"clojure"` or `"java"`) |
| `params()` | `List<String>` | Parameter names |
| `code()` | `String` | Function source code |

Transaction functions are created via `Peer.function(Map)` with keys:
- `:lang` — `"clojure"` (default) or `"java"`
- `:params` — Vector of parameter symbols
- `:code` — Function body as string

### datomic.QueryRequest — Query Configuration

Builder for configuring complex queries with timeouts.

| Constant | Description |
|----------|-------------|
| `QUERY` | Query key |
| `ARGS` | Arguments key |
| `TIMEOUT` | Timeout milliseconds key |

| Method | Returns | Description |
|--------|---------|-------------|
| `create(Object query, Object... args)` | `QueryRequest` | Static factory method |
| `timeout(long ms)` | `QueryRequest` | Set query timeout |
| `asData()` | `Map` | Convert to map representation |

### datomic.ListenableFuture — Async Result Interface

Returned by async operations; extends `java.util.concurrent.Future`.

| Method | Returns | Description |
|--------|---------|-------------|
| `addListener(Runnable listener, Executor executor)` | `void` | Add callback to run when future completes |

### Usage Summary: Transactor Interaction Flow

The transactor is a background process; applications interact with it through the Peer API:

```
// 1. Connect (establishes connection to transactor)
Connection conn = Peer.connect("datomic:sql://mydb?jdbc:postgresql://localhost/datomic");

// 2. Query current database value (reads directly from storage, no transactor involved)
Database db = conn.db();
Iterable<Datom> datoms = db.datoms(Database.AVET, 
                                   db.entid("user/email"), 
                                   "alice@example.com");

// 3. Submit transaction (goes to transactor for serialization)
List tx = Arrays.asList(
    Peer.tempid(":db.part/user"),
    ":db/add",
    ":user/name",
    "Alice"
);
ListenableFuture<Map> future = conn.transact(tx);
Map result = future.get();  // Wait for completion

// 4. Access transaction result
Database dbAfter = (Database) result.get(Connection.DB_AFTER);
Map tempids = (Map) result.get(Connection.TEMPIDS);
```

The transactor handles:
- Transaction serialization and validation
- Schema enforcement
- Unique constraint checking
- Transaction function execution
- Root pointer CAS for atomic commits
- Broadcasting novelty to all connected Peers

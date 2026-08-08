# DaoJing: The Storage Boundary

**Related documents:**
- `docs/design/dao.space.md` — the tuple space that emerges when interpreters match over `dao.jing`
- `docs/design/dao.stream.md` — the append-only log primitive datoms are written through
- `docs/design/dao.stream.apply.md` — the request/response protocol for stream-native function application and queries
- `docs/design/dao.stream.file.md` — the file-backed byte stream member logs use
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing
- `docs/datomic.md` — the deep dive on the Datomic storage architecture this design maps to
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the decision this design records
- `docs/postgres.md` — the deep dive on the PostgreSQL architecture this design defines itself against
- `docs/design/dao.space.v0.md` — superseded framing; still the reference for resources, typed streams, and the geometry/gauge material
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md`, `dao.space.discrete-to-continuous.md` — the geometry/locality cluster: theoretical justification (gauge, spectral, locality) the spec defers to, not required to read it
- `docs/design/yin.vm.ffi.md` — Yin.VM's generic `dao.stream.apply` bridge (`yin/vm/ffi.cljc`, implemented)
- `docs/design/dao.space.security.md`, `docs/design/adr/0002-share-governed-computation-not-data.md` — the controlled-mode model that motivates exposing storage through a mediated bridge rather than a direct binding

## What DaoJing Is

`dao.jing` is a **stream observer**: a conceptual fold (`reduce`) over a single `dao.stream` that projects an append-only log into a materialization target you provide. It is **pure syntax** — it holds *form*, never *meaning*. It does not know the difference between a "segment" and a "root". That knowledge is entirely contained within the semantic layer above it (e.g., `dao.space.address`, planned extraction; currently in `dao.jing.cljc`).

Here, **"fold" is the design concept** (the reduction of stream events into state), while **`reduce` is the function** used to execute it (or an incremental loop using `ds/next`).

In code, `dao.jing` is not a protocol or object hierarchy; it is a namespace (`dao.jing`) providing:
- The sentinel constant `absent` (`::absent`).
- The pure step function `materialize-step` (for map targets) and `materialize-mutable-step!` (for external storage).
- The query evaluator constructor `evaluator` (`(evaluator target)` or `(evaluator target read-fn)`).
- The query service step `step-service!` (which connects `evaluator` to a `dao.stream.apply` stream pair).

There is no fixed storage API protocol. Writers append events to `dao.stream` using `ds/append!`; `dao.jing` observes the stream and calls your step function to update the materialization target via `reduce`. Readers read from that target using either direct native lookups (e.g., `clojure.core/get`, SQL, S3) or a `dao.stream.apply` request/response stream pair.

It leaves all interpretation — matching, querying, segment addressing, and root mutability — to the readers above it, and whatever structures those interpreters build are, to the observer, just more bytes. This is the Datomic discipline taken literally: **storage is a dumb log of blobs; all intelligence lives in the embeddable reader on top.** Keeping the boundary this thin is what lets storage scale and be swapped without touching query logic.

## The Stream as Primitive

The fundamental abstraction is the `dao.stream` append-only log. There is no key-value store.

By modeling storage as a stream, we completely sidestep the distributed systems problems of consensus, leader election, and distributed CAS.
- **Writes are local appends**: An agent only ever appends to its own stream via `ds/append!`. It never writes to a shared, global store. Because each stream has a single writer, there is no write contention and no need for distributed consensus.
- **Distribution is stream replication**: To distribute the database, we simply replicate the stream of appends. Readers subscribe to the stream (e.g., via TCP/WebSocket/QUIC) and process the ordered log of facts.
- **Reads are local projections or stream queries**: Readers consume the replicated stream and fold it into a materialization target (in-memory map, file, SQL, S3) or issue queries over `dao.stream.apply`.

### Multi-Stream Composition

`dao.jing` is explicitly a **single-stream observer**: one `dao.stream` -> one target projection. 

Multi-stream composition (such as `dao.space` folding multiple agent member logs into one unified tuple space) is a caller concern, not a `dao.jing` concern. Higher semantic layers accomplish multi-stream composition by either:
1. Running an independent `dao.jing` fold per member stream and merging their local projections at query time, or
2. Multiplexing multiple streams into a single composite fold using a multi-stream iterator.

## Write Path: Stream Record Format & Step Functions

`dao.jing`'s stream record format has a single, unified operation vocabulary: **`:cas`** (compare-and-swap).

```clojure
[k :cas expected v]     ; write/assoc v at key k ONLY IF current value = expected
```

All storage operations — writing segment blobs, updating mutable roots, and deleting keys — are unified under this single `:cas` tuple shape:
- **Minting an Immutable Segment:** `[k :cas absent v]` (write `v` only if `k` does not yet exist).
- **Updating a Mutable Root:** `[k :cas expected-root-hash new-root-hash]` (advance pointer only if it matches expected).
- **Deleting a Key:** `[k :cas current-v absent]` (remove entry by passing `absent` as `v`).

Because every write is a `:cas`, **deleting a key requires the writer to supply the key's current value**. In a single-writer stream, this is a natural design constraint: the single writer tracks its own appends in local memory and knows the current state of any key it modifies.

### Serialization Boundary

While `dao.jing` treats records conceptually as 4-element vectors `[k :cas expected v]`, `dao.stream` carries opaque byte arrays. Serialization between vectors and byte arrays is a boundary concern handled by `dao.stream` codecs (e.g. EDN or Transit for structured Clojure values, with Eve Flat planned for zero-copy canonical flat encoding).

### The `absent` Sentinel

The sentinel `absent` (`::absent`) is a reserved namespaced keyword exported by `dao.jing`. 

- **Purpose:** It serves as the explicit sentinel representing key non-existence. Passing `absent` as `expected` requires that the key does not yet exist; passing `absent` as `v` unambiguously signals key deletion. Passing `absent` as the default value to `get` (e.g. `(get target k absent)`) distinguishes stored `nil` from true key non-existence.
- **Restriction:** `absent` is reserved by the storage boundary and **must never be stored as a value**. If a caller attempts to store `absent` as a value (`[k :cas expected absent]`), the step function interprets the record as a deletion request.
- **Edge Case (`[k :cas absent absent]`):** Attempting to delete a key only if it is already absent is a no-op that deletes nothing and leaves the target unchanged.

### Error Handling & Corrupted Records

- **Malformed Tuples:** If a stream record is not a 4-element vector matching `[k :cas expected v]`, the fold skips the malformed record, logs a warning, and continues folding.
- **`:cas` Mismatches:** If `current != expected`, the step function skips the mutation (noop) and returns the target unchanged. Because each stream has a single writer, a `:cas` mismatch indicates a writer bug, out-of-order replay, or stream corruption rather than concurrent write contention.

### Step Function Variants: Pure vs Mutable Targets

The step function's implementation depends on whether the materialization target is an immutable Clojure value (e.g. an in-memory map) or a mutable external system (e.g. SQL, file storage). The `(= op :cas)` check is included for defensive validation and forward compatibility with potential future record tags:

#### 1. Pure Step Function (In-Memory Map)
For in-memory projections, the step function is a pure function `(f map record) -> new-map`:

```clojure
(defn materialize-step [m record]
  (if (and (vector? record) (= (count record) 4) (= (second record) :cas))
    (let [[k _ expected v] record
          current (get m k absent)]
      (if (= current expected)
        (if (= v absent)
          (dissoc m k)
          (assoc m k v))
        m))
    m))
```

#### 2. Side-Effectful Step Function (External / Mutable Target)
For mutable storage targets (SQL, disk log), the step function performs target-specific I/O helper calls (`read-target`, `write-target!`, `delete-target!`) and always returns the target reference:

```clojure
(defn materialize-mutable-step! [target record]
  (if (and (vector? record) (= (count record) 4) (= (second record) :cas))
    (let [[k _ expected v] record
          current (read-target target k absent)]
      (if (= current expected)
        (do (if (= v absent)
              (delete-target! target k)
              (write-target! target k v))
            target)
        target))
    target))
```

### In-Memory Example

```clojure
;; The stream is the sole source of truth
(ds/append! stream [k :cas expected v])

;; dao.jing is a fold over the stream — executed via Clojure's reduce
(def projection (reduce materialize-step {} (ds/->seq stream)))
(get projection :root/my-db absent)
```

*(Note: passing `(ds/->seq stream)` directly to `reduce` is a pedagogical shorthand for single-batch in-memory testing. Production long-lived materializers use the incremental `step-incremental!` loop below.)*

For the in-memory case, the result is a plain Clojure map (or an atom holding one) that readers query with `clojure.core/get`.

The storage distinction splits across two orthogonal concerns:
- **Log storage** (where appends go): lives entirely in `dao.stream` — e.g. `:ringbuffer` (`dao.stream.ringbuffer`), `:file` (`dao.stream.file`), or `:websocket` (`dao.stream.ws`).
- **Materialization target** (where the fold projects to): lives in the step function you provide to `dao.jing` — in-memory map, file, SQL, S3, etc.

`dao.jing` itself is the same fold regardless of either concern. It simply walks the stream and calls your step function.

### Incremental Materialization & Cursor Tracking

In production, long-lived observers do not re-fold from position 0 on every read. A materializer incrementally advances its projection by retaining a stream `cursor` and pulling batch updates via `(ds/next stream cursor)`.

`ds/next` returns one of four explicit stream signals (matching `dao.stream.md`'s canonical return contract):
- `{:ok record, :cursor next-cursor}`: a valid record and updated cursor.
- `:blocked`: an active stream with no new records currently available.
- `:end`: the stream is closed.
- `:daostream/gap`: the cursor fell behind the stream's retention boundary, requiring a resync.

A production observer loop handles these stream signals explicitly:

```clojure
(defn step-incremental! [target-atom cursor-atom stream]
  (let [res (ds/next stream @cursor-atom)]
    (cond
      (map? res)
      (let [record (:ok res)]
        ;; Loop-level fast-reject before swap!
        (when (and (vector? record) (= (count record) 4) (= (second record) :cas))
          (swap! target-atom materialize-step record))
        (reset! cursor-atom (:cursor res))
        :ok)

      (= res :blocked)
      :wait ; active stream, no new records available

      (= res :end)
      :complete ; stream closed

      (= res :daostream/gap)
      :resync))) ; cursor fell behind retention boundary, requires resync
```

*(Note: Loop-level validation acts as a fast-reject before `swap!`, while `materialize-step` retains self-contained validation for standalone `reduce` calls.)*

**Handling `:resync`:** When `:resync` is returned due to a `:daostream/gap` signal, the caller re-initializes its target state from the last persisted checkpoint and resumes folding from the checkpoint cursor (or re-folds from position 0 if no checkpoint exists).

### Checkpointing, Crash Recovery & Replay Idempotency

If a materializer process crashes mid-fold, it must resume without reprocessing the entire stream log from index 0.

- **Atomic Checkpointing:** For file/SQL targets, persistence and crash recovery are managed by storing `{:target target-state :cursor stream-cursor}` atomically in a single atom or transaction.
- **Replay Idempotency:** Even if crash recovery resumes from an earlier cursor position and replays previously processed records, re-application is completely safe across all operation types:
  - **Segments (`[k :cas absent v]`):** Minting content-addressed segments is idempotent. On replay, `current` equals `v`, so `current != absent` causes the CAS check to skip silently.
  - **Roots (`[k :cas old-root new-root]`):** Advancing mutable roots is guarded by the expected root hash. On replay, `current` equals `new-root`, so `current != old-root` causes the CAS check to skip silently without clobbering state.
  - **Deletions (`[k :cas current-v absent]`):** On replay, key is already absent (`current = absent`), so `current != current-v` causes the CAS check to skip silently without modifying state.

### Resource Lifecycle & Cleanup

Resource management (closing SQL database connection pools, file handles, or network sockets) belongs entirely to the materialization target or stream object (`ds/close!`), not to `dao.jing`'s fold concept. The fold is a pure iteration loop that operates on whatever target handles the step function provides.

### Target-Provided Snapshot Semantics

Isolation and concurrency guarantees belong to the materialization target, not the fold itself:
- **In-Memory Atom Target**: Thread-safe snapshot isolation is provided by Clojure's `deref` (`@projection`), ensuring concurrent stream writes never disturb an in-flight read.
- **External Database Target**: Snapshot isolation is guaranteed by the target's native concurrency controls (e.g. SQL MVCC transaction isolation levels or S3 object versioning).

## Read Path: Direct Lookups & Stream-Native Queries (`dao.stream.apply`)

While readers can query projected targets directly via native APIs (e.g. `clojure.core/get` on an in-memory map or SQL `SELECT`), the read path can also be expressed natively as a stream via **`dao.stream.apply`** (`docs/design/dao.stream.apply.md`).

A `dao.stream.apply` read endpoint is created and owned by the **reader** (caller), consisting of a pair of stream descriptors:
```clojure
{:dao.stream.apply/request  <request-stream>
 :dao.stream.apply/response <response-stream>}
```

The reader appends queries to `:request` and reads answers from `:response`. The query evaluator service consumes `:request` and appends answers to `:response`.

### The Query Evaluator & Service Loop

The `dao.jing/evaluator` function accepts a materialization target and an optional read function (`read-fn`, defaulting to `clojure.core/get`). It returns a packet handler `(fn [{:dao.stream.apply/keys [id op args]}] response-packet)` that processes incoming `dao.stream.apply` requests:

```clojure
(defn evaluator
  ([target] (evaluator target get))
  ([target read-fn]
   (fn [{:dao.stream.apply/keys [id op args]}]
     (let [val (case op
                 :op/get (let [[k] args] (read-fn target k absent))
                 (throw (ex-info "Unknown query op" {:op op})))]
       {:dao.stream.apply/id id
        :dao.stream.apply/value val}))))
```

A long-lived **query evaluator service** (`step-service!`) uses the same platform-agnostic `ds/next` cursor loop to process query requests. It receives the target state (or the atom wrapping it, e.g. `@target-atom` or `target-conn`):

```clojure
(defn step-service! [target cursor-atom endpoint-descriptor]
  (let [{:dao.stream.apply/keys [request response]} endpoint-descriptor
        handler (evaluator target)
        res (ds/next request @cursor-atom)]
    (cond
      (map? res)
      (let [req (:ok res)
            resp (handler req)]
        ;; Best-effort response delivery: if response stream is full/closed, request is dropped
        (ds/append! response resp)
        (reset! cursor-atom (:cursor res))
        :ok)

      (= res :blocked) :wait
      (= res :end) :complete
      (= res :daostream/gap) :resync)))
```

*(Note: `step-incremental!` and `step-service!` are single-step functions. In a single-threaded process, a caller loop interleaves them `(step-incremental! ...) (step-service! ...)`. In multi-threaded runtimes, they run on separate caller worker loops sharing the target object/atom. Scheduling and async execution are platform-dependent caller concerns.)*

1. **Request:** A reader creates the endpoint pair, appends a request packet to `:request`:
   ```clojure
   {:dao.stream.apply/id   :read-101
    :dao.stream.apply/op   :op/get
    :dao.stream.apply/args [:root/my-db]}
   ```
2. **Evaluation:** `step-service!` reads the request via `ds/next`, evaluates it via `(handler req)`, and appends a response packet to `:response`:
   ```clojure
   {:dao.stream.apply/id    :read-101
    :dao.stream.apply/value :segment/sha256-a1b2c3d4...}
   ```
3. **Response:** The reader observes the matching `:read-101` response on `:response` and continues.

### Unified Capabilities & Stream Selection

Expressing reads via `dao.stream.apply` unifies storage access across all runtime boundaries:
- **Cross-Process / RPC:** Remote reads use `dao.stream.rpc` over WebSockets/TCP seamlessly without custom RPC protocol adapters.
- **Confined VM / FFI (`yin.vm`):** Sandboxed VMs access storage through their existing FFI capability bridges (`yin.vm.ffi`), governed by capability tokens.
- **Live Queries / Subscriptions (Future Enhancement):** Read streams can emit an initial query snapshot packet followed by continuous delta update packets as subsequent write-stream appends occur.

**Stream Selection as an Implementation Detail:**
Whether the request/response stream pair is an **ephemeral in-memory stream** (`:ringbuffer` via `dao.stream.ringbuffer` for zero-overhead, ultra-low latency) or a **durable, auditable log** (`:file` via `dao.stream.file` or `:websocket` via `dao.stream.ws` for auditable access and agent observation) is an orthogonal choice of `dao.stream` backend. `dao.jing` and `dao.stream.apply` remain identical across both.

## Keyspace Ignorance

The stream records describe changes to a keyspace governed by a strict discipline, but `dao.jing` itself is blind to this discipline. The fold simply processes stream records via your step function, which updates the materialization target.

While higher-level semantics (like `dao.space`) divide the keyspace into:
- **Segment keys** (content addresses holding immutable data)
- **Root keys** (mutable references advanced via optimistic concurrency)

`dao.jing` treats both simply as opaque keys in a plain map. It does not enforce namespaces, nor does it perform content hashing. The concepts of roots and segments belong entirely in `dao.space.address` (planned extraction; currently in `dao.jing.cljc`), a higher semantic layer for `dao.space` that manages the content addressing mechanism (`canonical`, `content-hash`, `segment-key`).

## Structural Ignorance: Format Stability Without the Engine

The deliberate dumbness above has a cost question hanging over it: PostgreSQL couples its storage engine to its data structures precisely because that coupling makes it fast. The property doing the work there is **format stability** — the on-disk page layout *is* the in-memory layout, so reads of resident pages need no parse.

`dao.jing` is built on the decoupling: **in-place readability is a property of the byte layout, not of the storage engine.** A flat, self-describing layout (like Eve slabs) can be traversed in place by the *reader* — the interpreters above this boundary — while the observer remains the dumb fold over opaque blobs specified above.

In exchange for banning in-place updates, fine-grained retrieval, and storage-level MVCC, `dao.jing` buys what a structurally aware engine cannot offer: invent a new data structure without touching storage code, and swap a local disk for a peer-to-peer network without touching query code — because a fold that never knew your structures never needs to learn new ones.

## Current Scope

While higher layers structure their keyspace into segments, roots, and index manifests, to `dao.jing` these are all just `:cas` records folded into the materialization target.

- **Storage roots today:** Higher layers store one mutable key per stream and ship segments into the store. Each stream's semantic root holds either the stream's full datom vector wholesale or an owner-built index manifest whose values point at immutable, content-addressed B-Tree node segments.
- **Member layout and discovery.** A stream owner publishes its semantic root by appending a `:cas` record to its stream via `ds/append!`. Discovery happens via a membership root, written once per stream at `open!`.
- **Querying (reader side).** A read resolves keys either directly from the materialization target (e.g. `clojure.core/get` on a map) or via `dao.stream.apply` request/response streams. Concurrent writes to the stream never disturb an in-flight read against a target with snapshot isolation.
- **Compaction / GC.** In the file stream implementation, compaction is a local garbage-collection concern. Dead records in the append-only log are filtered out and a new log is written, reclaiming space.
- **Encoding: canonical bytes are unbuilt.** Where a higher layer mints a content-derived segment key, today's implementation hashes an order-normalized `pr-str` print. Moving to a true canonical flat encoding (like Eve Flat) is a planned semantic follow-up.

## Lineage

`dao.jing` is the meeting point of two traditions, one for what it holds and one for what it is built from:

- **Datomic** gives the storage discipline: a dumb log of immutable segments under a strict Transactor / Storage / Query separation, with content-addressed identity and the Peer-as-library read model.
- **Plan 9** gives the *substrate*: the logs are independent, location-transparent, append-only streams.

(The associative-matching, generative-communication behavior built on top of these bytes is **not** here; that lineage—Linda 1986—belongs to `dao.space` above this boundary.)

The synthesis: **`dao.jing` is a generic fold over `dao.stream` — an observer concept that projects an append-only log of opaque bytes into a materialization target you provide.** The write path is a stream fold driven solely by `:cas` tuples using `reduce`; the read path is either direct target access or a `dao.stream.apply` request/response stream. Datoms, matching, querying, segment addressing, and roots are semantics an embeddable reader library projects onto the materialized state via the interpreters above.

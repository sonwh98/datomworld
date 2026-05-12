# Content-Addressable Filesystem on Datoms

## Context

A content-addressable filesystem (CAS-FS) layered on the existing datom infrastructure.

- **Files are not stored as blobs.** They are chunked, and each chunk is identified by its SHA256 hash.
- **Datoms are the metadata layer.** File structure, chunk hashes, offsets, sizes expressed as 5-tuple datoms in DaoDB.
- **Chunk bytes are stored separately** in a `CasStore` keyed by content hash. No raw bytes in DaoDB.
- **Datoms diffuse like heat.** `:cas.peer/want` and `:cas.peer/have` datoms propagate through the peer stream medium. No routing tables, no coordinators. A "want" is a cold region; a "have" is hot. Chunk data flows along the gradient.

Existing infrastructure to reuse:
- `dw/sha256` (`src/cljc/datomworld.cljc`) — string to hex hash
- `yin.content/compute-content-hashes` — Merkle hashing of datom graphs
- `dao.stream` protocols (ringbuffer, websocket, link) — the diffusion medium
- `dao.stream.link` — peer-to-peer datom sync protocol (`:datom/put`, `:datom/sync-*`)
- `dao.db` / `dao.db.in-memory` — DaoDB for storing metadata datoms

---

## Architecture: Three Layers

### Layer 1 — Chunk Store (`src/cljc/dao/cas.cljc`)

Protocol and in-memory implementation.

```
ICasStore
  (put-chunk!  [store hash bytes] → store')
  (get-chunk   [store hash]       → bytes | nil)
  (has-chunk?  [store hash]       → bool)
  (chunk-keys  [store]            → seq of hashes)

MemoryCasStore — atom {hash → bytes}
```

Utilities (CLJC, platform guards for byte arrays):

```
(hash-bytes [bytes]) → "sha256:hex..."
  ;; CLJ: MessageDigest on byte[]
  ;; CLJS: goog.crypt.Sha256 on Uint8Array

(chunk-seq [bytes chunk-size]) → [{:index :offset :size :hash :bytes} ...]
  ;; Splits bytes into fixed-size chunks, hashes each
```

---

### Layer 2 — File Metadata (`src/cljc/dao/cas/fs.cljc`)

Translates between bytes and datoms. No bytes enter DaoDB.

**Schema** (asserted as datoms into DaoDB):

```
:cas.file/size     {:db/valueType :db.type/long}
:cas.file/name     {:db/valueType :db.type/string}
:cas.file/chunks   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
:cas.chunk/hash    {:db/valueType :db.type/string :db/index true}
:cas.chunk/index   {:db/valueType :db.type/long}
:cas.chunk/offset  {:db/valueType :db.type/long}
:cas.chunk/size    {:db/valueType :db.type/long}
```

**API:**

```
(ingest! cas-store db bytes opts)
  → {:db-after db' :file-eid eid :chunk-hashes [hash ...]}
  ;; 1. chunk-seq bytes → [{:index :offset :size :hash :bytes}]
  ;; 2. put-chunk! each chunk into cas-store
  ;; 3. transact chunk entities + file entity into db
  ;; opts: {:chunk-size 65536 :name "filename"}

(fetch cas-store db file-eid)
  → bytes
  ;; 1. q db for chunk entities ordered by :cas.chunk/index
  ;; 2. get-chunk each hash from cas-store
  ;; 3. concat byte arrays in order

(schema-tx)
  → tx-data (vector of [:db/add ...] for schema attrs)
```

---

### Layer 3 — Diffusion Protocol (`src/cljc/dao/cas/diffusion.cljc`)

Pure functions operating on datom streams. No routing. No addresses.

**Diffusion datom schema:**

```
:cas.peer/want  — string (chunk hash), cardinality-many
                  "I need this chunk; send it if you have it"
:cas.peer/have  — string (chunk hash), cardinality-many
                  "I have this chunk; come get it or push it"
```

**API:**

```
(want! stream peer-eid hash)
  ;; Emits [peer-eid :cas.peer/want hash t 0] into stream

(have! stream peer-eid hash)
  ;; Emits [peer-eid :cas.peer/have hash t 0] into stream

(wants [datoms]) → #{hash ...}
  ;; Extracts :cas.peer/want values from a datom batch

(haves [datoms]) → #{hash ...}
  ;; Extracts :cas.peer/have values from a datom batch

(satisfy [cas-store wants-set]) → [{:hash h :bytes b} ...]
  ;; Returns chunks we can fulfill from local store

(receive-chunks! [cas-store stream peer-eid chunks])
  ;; Stores received chunks, announces :cas.peer/have for each
  ;; chunks: [{:hash h :bytes b}]
```

**Diffusion loop (how heat flows):**

1. Read datoms from peer stream
2. Extract `:cas.peer/want` hashes from peer
3. For each want we can satisfy: deliver chunk bytes back and emit `:cas.peer/have`
4. Extract `:cas.peer/have` hashes from peer
5. For each have we are missing and want: emit `:cas.peer/want` in response

Chunk bytes travel as datom values `[peer-eid :cas.chunk/data {:hash h :bytes b} t 0]` within the
stream. Everything is data in the medium.

---

## Key Design Decisions

**Datoms as metadata, not data.** Raw bytes never enter DaoDB. Chunk hashes in datoms are the link
between the metadata graph and the content store. This preserves datom purity and keeps DaoDB fast.

**Gauge-invariant chunk identity.** A chunk's identity is its content hash, independent of which
peer holds it, which entity ID references it, or when it was ingested. Two peers who independently
chunk the same file will produce identical hashes for identical chunks.

**Diffusion over routing.** There is no "send chunk to peer X", only "announce want/have into the
medium." The medium (stream graph) propagates signals. Chunks flow toward demand like heat toward
cold. This enables epidemic distribution without coordination.

**Chunk bytes as datom values.** When a chunk transfers between peers it travels as a datom value in
the stream. Everything is data.

---

## Files

```
src/cljc/dao/cas.cljc              ICasStore, MemoryCasStore, hash-bytes, chunk-seq
src/cljc/dao/cas/fs.cljc           ingest!, fetch, schema-tx
src/cljc/dao/cas/diffusion.cljc    want!, have!, wants, haves, satisfy, receive-chunks!
test/dao/cas_test.cljc
test/dao/cas/fs_test.cljc
test/dao/cas/diffusion_test.cljc
```

---

## Implementation Order (TDD)

0. This design doc
1. `test/dao/cas_test.cljc` then `src/cljc/dao/cas.cljc`
2. `test/dao/cas/fs_test.cljc` then `src/cljc/dao/cas/fs.cljc`
3. `test/dao/cas/diffusion_test.cljc` then `src/cljc/dao/cas/diffusion.cljc`

---

## Verification

```bash
clj -M:test
```

Functional:
1. Ingest a file, inspect DaoDB entities for chunk metadata
2. Fetch the file, compare bytes with original (round-trip = identity)
3. Simulate two-peer diffusion: peer A has chunk, peer B wants it, verify chunk arrives via datom stream

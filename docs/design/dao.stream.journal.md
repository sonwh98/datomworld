# DaoStream Journal: The Durable Complete-Retention Log

Status: C1 (memory backend) and C2 (file backend) implemented, slices of
the linker M-next C plan. Subordinate to [`dao.stream.md`](./dao.stream.md),
which is the contract and wins on any disagreement. Implementation:
`src/cljc/dao/stream/journal.cljc`.

## 1. Purpose

`dao.stream.journal` is a general complete-retention DaoStream whose history
survives the process. Every appended value is one canonical CBOR frame on a
backend; the visible log is a `dao.stream.memory-log` rebuilt from those
frames when the journal is opened. Its first consumer is an arbitration
ledger: a `dao.space` transactor over a journal handle, as the transactor's
durable local stream.

The journal decides nothing about its values. It carries them, numbers them
and keeps them.

## 2. Frames

All keys are under `:dao.stream.journal/`. Each frame is the canonical CBOR
encoding (`dao.jing.cbor/encode`) of one map.

```clojure
;; frame 0
{:dao.stream.journal/header {:version 1 :identity "<uuid string>"}}

;; frame p + 1, for position p
{:dao.stream.journal/position p
 :dao.stream.journal/value    v}
```

- Positions are dense from 0, and frame `p + 1` holds position `p`.
- Two equal values at different positions are different frames. Repeated
  equal appends are kept, each at its own position.
- `v` is any value the canonical codec accepts, `nil` included.
- A position is an exact integer no greater than 2^52 - 1
  (`max-position`). It never wraps.

## 3. Identity, descriptor and cursors

- The identity is a string minted once, when the header is written to an
  empty backend. It is read from the header at every later open, so it is
  the same after a reopen.
- `descriptor` answers `{:dao.stream/type :dao.stream/journal
  :dao.stream/identity i}` with the same `i` as the sibling identity.
- Cursors are the visible memory log's cursors over that identity. A
  position is a frame position, so a cursor kept from before a reopen is
  accepted by the reopened handle and reads the same value.
- There is no `attach!`. A journal is reached by opening its backend, and
  no registry maps a descriptor to a backend.

## 4. Opening

`open!` takes a backend and is a host act, like binding a datagram socket.
It is not `create!`: it either creates or reopens, depending on what the
backend holds.

1. Read the frames.
2. A final frame that does not decode is a torn write. It is dropped.
3. If no frame remains, write a header with a fresh identity and answer an
   empty journal. A medium whose only frame was torn is this case: under
   the failure model a crash cuts only the tail, so no value was ever
   acknowledged, and no `open!` ever answered the lost identity.
4. Otherwise frame 0 must be a version 1 header, and every later frame
   must be an entry whose position is its index minus one.
5. If a torn frame was dropped, truncate the backend to the frames that
   stand, before the handle exists.
6. Answer `ok` with the handle and the identity.

`open!` refuses with `:dao.stream/transport-error` and a
`:dao.stream.journal/defect` naming why, writing nothing:

| Defect              | Cause                                                  |
|---------------------|--------------------------------------------------------|
| `:missing-header`   | frame 0 is not a version 1 header                      |
| `:position-gap`     | an entry's position is not its index minus one         |
| `:malformed-frame`  | a later frame is not an entry map                      |
| `:unreadable-frame` | a frame before the last does not decode                |
| `:read-failed`      | the backend could not read its frames                  |
| `:truncate-failed`  | the backend could not drop a torn frame                |
| `:write-failed`     | the header could not be written to an empty backend    |

An undecodable final frame is a tear, because a crash can only cut the last
write. An undecodable frame anywhere else is corruption, and the journal
does not guess past it.

## 5. Operations

| Operation    | Outcomes                                                     |
|--------------|--------------------------------------------------------------|
| `descriptor` | `ok`                                                         |
| `cursor`     | `ok`, `invalid-anchor`                                       |
| `next`       | `ok`, `blocked`, `cursor-mismatch`, `invalid-cursor`         |
| `append!`    | `ok`, `invalid-value`, `transport-error`                     |

`append!` runs three steps in order, under one lock on the JVM:

1. **Encode** the entry frame. A value the codec refuses answers
   `invalid-value`, writes nothing and leaves the handle healthy.
2. **Persist** the frame through the backend.
3. **Make it visible**: append to the visible log the value decoded from
   the persisted bytes, so a reader sees exactly what a reopen will see.
   That value is the normalized one: on JavaScript a non-integral double
   reads back as the `Float64` carrier and on the JVM a `Float` reads back
   as a `Double`, so compare with `dao.jing.cbor` equality, not host `=`.

`ok` means the frame is durable as far as the backend declares, and
visible.

### Poison

A backend failure or throw in step 2, or any failure in step 3, answers
`transport-error` and poisons the handle for appends. Whether the frame
persisted is unknown, so every later append on that handle answers
`transport-error` and writes nothing. Reads keep serving the visible log,
which does not include the failed value. Only a reopen clears the poison,
because only a reopen reads what the backend actually holds.

### The position bound

An append whose position would exceed `max-position` answers
`transport-error` and writes nothing. It is not `full`: nothing about the
journal is transient, and a consumer that reaches the bound (an authority)
treats it as its own poison.

### Excluded outcomes

| Outcome                  | Why it cannot occur                              |
|--------------------------|--------------------------------------------------|
| `gap`                    | every persisted frame is replayed at open        |
| `full`                   | no capacity; the bound is a transport-error      |
| `end`, `closed`          | there is no close surface                        |
| `refused`                | no policy is composed on a journal handle        |
| `transport-error` (read) | reads touch only the visible in-memory log       |

There is no close surface. Closing a durable log would have to persist, and
the frame grammar has no close frame; every handle is the logical stream's
owner.

## 6. The backend seam

A backend is a map of three functions, each answering an outcome map. A
throw is a failure like any non-`ok` answer.

```clojure
{:dao.stream.journal/frames       (fn [])   ; ok with ::frames, byte arrays
 :dao.stream.journal/write-frame! (fn [bs]) ; ok once the frame is durable
 :dao.stream.journal/truncate!    (fn [n])} ; ok once n frames remain
```

The journal owns the grammar, positions, identity, poison and tear rule.
The backend owns only bytes, order and durability.

### Memory backend

`(memory-backend frames cut)` keeps frames in `frames`, an atom holding a
vector the caller owns and passes again to reopen. It is never durable
beyond that atom. `cut`, when not nil, is a crash the first frame write
through that backend value suffers, followed by a throw:

| Cut                           | Persisted        | After reopen          |
|-------------------------------|------------------|-----------------------|
| `:before-frame`               | nothing          | the value is absent   |
| `:after-frame-before-visible` | the whole frame  | the value is present  |
| `:torn-frame`                 | its first half   | the tear is truncated |

### File backend

`dao.stream.journal.file` (slice C2). `(backend! dir)` creates `dir` when
absent, takes its lock (`dao.space.store.fs/lock!`), and opens
`<dir>/journal.jing` with `dao.jing.file`. It answers `ok` with
`:dao.stream.journal.file/backend`, or `transport-error` with defect
`:locked` (another owner holds `dir`) or `:open-failed` (the content file
refuses to open). `(close! backend)` closes the content file and releases
the lock.

- **Frames.** Each frame is one content frame, put at the address of its
  bytes. The content file owns framing, the fsync before a put answers,
  and dropping a torn tail at open; the journal sees only whole frames.
- **Replay.** `dao.jing.file/records` order. Each record's value is
  re-encoded and checked against its address, so decode then encode is
  the identity on every accepted frame.
- **`:present`.** A put that answers `:present` found a frame with the
  same bytes already stored. Frames embed a dense position, so equal
  values never make equal frames; `:present` is a defect, and the
  journal poisons.
- **Truncate.** Always a failure: the content file already dropped any
  tear, so the journal never asks.
- **Durability.** The backend carries
  `:dao.stream.journal/durability`, a function answering data, its keys
  under `:dao.stream.journal/`:

| Key              | JVM           | Node          | Dart             |
|------------------|---------------|---------------|------------------|
| `backend`        | `:file`       | `:file`       | `:file`          |
| `failure-model`  | `:process-crash` | `:process-crash` | `:process-crash` |
| `lock-kind`      | `:os-lock`    | `:claim-file` | `:os-lock`       |

`persisted` is `#{:identity :content-references}` on every host. Every
host declares only a process crash: `dao.jing.file` syncs each frame but
never the directory holding the new file (Dart cannot sync a directory at
all), so a power cut right after the first open can lose the file and its
identity. A directory sync at creation would let the JVM and Node declare
`:power-loss`. Node's claim file refuses on pid reuse (see
`dao.space.store.fs`).

A medium whose only frame is a torn header does not open: `dao.jing.file`
refuses it, so the journal answers `transport-error` and the directory
needs clearing by hand. The memory backend instead starts a fresh empty
journal; the file backend is stricter because it cannot tell a crash in
the first write from damage.

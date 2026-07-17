# dao.stream.file — Design / Plan

A read/write, **live-tail** file transport for DaoStream. `open!` positions at
the current end of file; the in-memory ringbuffer holds only writes made after
open, so reads are a true `tail -f`. Writes append to disk asynchronously;
durability is reconciled at `close!`.

```
{:type :file
 :path "/data/events.log"
 :capacity 1024                  ;; optional, defaults to 1024 segments; nil = unbounded (risky, opt-in)
 :eviction-policy :evict-oldest} ;; optional, defaults to :evict-oldest
```

- clj, cljd, cljs (Node), and cljs (browser via OPFS).
- **Tail-from-now:** reads see only writes made after `open!`. To read existing
  file history, use `file-input-stream` (a discardable snapshot).
- **Bounded by default:** the ringbuffer retains the `:capacity` most-recent
  segments; older ones are evicted. A reader whose cursor falls behind gets
  `:daostream/gap` and resyncs at the current tail. Evicted bytes are gone from
  memory but safe on disk.
- Non-blocking: `next`/`put!` never block the caller. (`close!` reconciles the
  final flush — blocking on clj; on Node/cljd/browser it returns promptly and
  the flush completes asynchronously, awaitable via `flushed`.)

---

## Separation of concerns

Three file transports, three jobs — `dao.stream.file` deliberately does **not**
duplicate the history-reader job, and that is precisely what lets its buffer be
bounded: it never loads the file's history.

- `file-input-stream` — read an existing file as a snapshot (clj + cljd).
- `file-output-stream` — append-only durable sink, sync per `put!` (clj + cljd).
- `dao.stream.file` — live-tail bridge: post-open reads see post-open writes, a
  bounded in-memory buffer, and async disk appends flushed at `close!`.

---

## Read/write non-blocking contract (all hosts)

dao.stream `next` and `put!` return immediately and never block waiting for I/O.

- **`open!`** — cheap: creates the file if missing and opens it for append at
  EOF. It does **not** read existing contents (tail-from-now). On clj/cljd/Node
  this is a metadata + open call. *Browser:* OPFS has no sync API, so `open!`
  returns immediately and sets the writable up in the background; the first
  `next` returns `:blocked` until writes arrive.
- **`next`** — never blocks. Returns `{:ok segment :cursor ...}`, or `:blocked`
  (open, caught up), `:daostream/gap` (cursor fell behind the eviction window),
  or `:end` (closed, drained).
- **`put!`** — never blocks. First checks the writer's poison flag (see below):
  if a prior async write has failed, `put!` **throws that write's real cause
  immediately** rather than accepting a segment the disk will silently drop.
  Otherwise pushes the byte array as one segment to the ringbuffer (visible to
  reads immediately, regardless of size; evicts the oldest segment if at
  capacity), schedules an async disk write, and returns `{:result :ok :woke
  [...]}`. **Eventual durability:** a crash before `close!` can lose unflushed
  bytes (but not silently — a *write* failure is surfaced at the next `put!`).
- **`close!`** — reconciles the final flush: closes the ringbuffer (readers get
  `:end`) and drains the async writer. On **clj** it **blocks** until the flush
  completes and surfaces any write error. On **Node/cljd/browser** it returns
  promptly with the flush in flight (these hosts cannot block without freezing a
  single-threaded runtime); await `(flushed fs)` and confirm it resolves for
  durability. If any async write failed, the cause is surfaced — `put!` fails
  fast, and `close!`/`flushed` carry it. Does not erase data.

---

## Why async writes (a new pattern)

The existing `file-output-stream` performs **synchronous** writes under
`locking` and calls `.flush` inside `put!` (`file_output_stream.cljc:39-40`),
so its `put!` blocks. There is no `agent`/`send`/`future` usage anywhere in
`src/cljc/dao/stream/` today.

`dao.stream.file` departs from this deliberately: `put!` honors the
non-blocking stream contract (so it can participate in the async runtime
without parking on disk I/O), and durability is reconciled at `close!`, which
blocks on the final flush. This is a behavioral change relative to
`file-output-stream`, made intentionally — not a mirror of an existing
transport.

---

## Internal data model (uniform across hosts)

A `FileStream` record wrapping two pieces:

### 1. A bounded ringbuffer stream (`dao.stream.ringbuffer`, capacity N, :evict-oldest)

Holds **segments** — each `put!` byte array is pushed as-is, one segment per
write, preserving logical write boundaries. When `capacity` is reached,
`:evict-oldest` drops the oldest segment and advances `head`; `next` on a
cursor behind `head` returns `:daostream/gap` (`ringbuffer.cljc:110-119`).

`next`, `register-reader-waiter!`, `register-writer-waiter!`, and `closed?`
delegate to the ringbuffer. Multiple cursors read non-destructively within the
retained window.

Defaults: `:capacity 1024`, `:eviction-policy :evict-oldest`. `dao.stream.file`
overrides the ringbuffer's `:reject` default deliberately — backpressure
(`:full`) is inappropriate for a disk-backed tail, where the file is the real
sink and the buffer is only a read cache. `:capacity nil` opts back into
unbounded retention for callers who explicitly accept the memory risk and
guarantee a consumer that keeps up.

### 2. A host-specific async disk writer

Each `put!` schedules an append of its raw value bytes (the buffer is a read
concern; the disk gets raw bytes). `close!` drains the writer and blocks until
the file handle is flushed and closed. See the per-host table.

**Poison flag (fail-fast on async write error).** The writer carries an error
cell (an atom holding the first failed write's cause, set by the agent
action / Promise rejection handler / `Future` `catch`). A live-tail stream may
never `close!`, so deferring error discovery to `close!` alone means a writer can
silently stop persisting forever while `put!` keeps returning `:ok` and readers
keep seeing in-memory bytes. To bound this, **`put!` reads the cell first** and
throws the stored cause once the writer is poisoned (a non-blocking read), so a
doomed write is never accepted after the first failure. `close!` still surfaces
the same cause for the close-time path (see the durability caveat); the cell is
the single source of truth both consult.

### Resync after eviction

The `:daostream/gap` sentinel is a bare keyword — it carries no `head`/`tail`,
so a gapped reader cannot self-locate the live window. This is **inherited from
the ringbuffer contract** (`ringbuffer.cljc:110-119` returns the bare keyword for
every transport built on it), not invented here, and `FileStream` keeps it
rather than introducing a richer self-locating gap value that would diverge from
the shared read model.

The cost: resync is **not** expressible through `IDaoStreamReader` alone. A
gapped reader must locate the live tail out-of-band, so `FileStream` exposes a
transport-specific accessor (outside the `IDaoStreamReader`/`IDaoStreamWaitable`
protocols), e.g. a `(tail-position fs)` fn reading the ringbuffer's `:tail`. A
reader that receives `:daostream/gap` sets its cursor to `(tail-position fs)` and
resumes; the evicted segments themselves are unrecoverable from memory (read them
via a separate `file-input-stream`).

This leaks the transport through the stream boundary: a gap-handling reader
written against `FileStream` is not reusable against another transport without
knowing about `tail-position`. We accept that **deliberately** here because the
alternatives are worse for this transport:

- **Auto-resync inside `next`** (FileStream silently jumps a gapped cursor to the
  tail and returns the next live segment) hides data loss — the reader never
  learns it skipped evicted writes, which for a durable log is the one thing it
  must know. Rejected.
- **A self-locating gap value** (`{:daostream/gap {:tail n}}`) would force a
  contract change on the shared ringbuffer and every transport that delegates to
  it, for a benefit only this transport consumes. Out of scope; revisit only if a
  second transport needs the same resync.

So the bare keyword + `tail-position` accessor is the minimal local choice, not a
free one — the leak is the price of not perturbing the shared read model.

---

## `next` decision tree (delegated to the ringbuffer)

```
ringbuffer.next(cursor)
  -> {:ok segment :cursor {:position (inc p)}}   ; byte-array (one put! value)
  -> :blocked                                     ; open, caught up (reader parks via waiter)
  -> :daostream/gap                               ; cursor fell behind the eviction window
  -> :end                                         ; closed, drained
```

Cursor is the plain map `{:position n}`, constructed inline by the caller.
Segment sizes are whatever `put!` received (variable); readers must not assume
a fixed chunk size.

---

## Per-host realization

| Host | `open!` (cheap, no content read) | `put!` async disk write | `close!` blocking flush |
|---|---|---|---|
| `:clj` | create-if-missing + `io/output-stream file :append true` (positions at EOF) | `clojure.core/agent` + `send-off`: serialized append to the `OutputStream` on the expandable I/O pool (`send-off`, not `send` — disk writes are blocking and must not run on the bounded CPU pool); `put!` returns immediately | `(await agent)` (throws opaque "needs restart" on failure), rethrow `(agent-error a)` for the real cause, `.close` the stream in `finally` (see durability note) |
| `:cljd` | `File.createSync` + open `RandomAccessFile` in `FileMode.append` | `Future.microtask` (or `Future(() => …)`) wrapping `writeFromSync`; **not** `Future.sync`, which runs synchronously and would block `put!`. Microtask FIFO ordering preserves write order | **cannot synchronously block** on the Dart event loop; drain pending microtasks via the async runtime's parking (like the browser), then `closeSync` |
| `:cljs` Node | `fs.openSync(path, "a")` (append mode, creates if missing) — retain the fd | `fs.write(fd, …)` chained on the previous write's Promise (a single serialized chain, so appends cannot interleave/reorder); the fd from `open!` is honored. (Do **not** use `fs.promises.appendFile(path, …)`: it opens/closes by path per call, ignores the fd, and gives no ordering guarantee across overlapping calls) | await the tail of the write chain, `fsync` the fd, then close it |
| `:cljs` browser (OPFS) | **non-blocking**: `navigator.storage.getDirectory()` -> `getFileHandle(:path {:create true})` -> `getFile()` (learn size) -> `createWritable({keepExistingData:true})` -> `.seek(size)`; `next` returns `:blocked` until writes arrive | held `FileSystemWritableFileStream`; `.write(val)` (Promise, not awaited) | await pending `.write` Promises, then `await writable.close()` |

- `open!` selects Node vs browser on `js/process.versions.node` (more robust
  than `(exists? js/process)`, which bundlers such as webpack shim into
  browsers).
- Node/clj/cljd use `:path` as a real filesystem path.
- Browser treats `:path` as the OPFS file name (origin-private, no picker, no
  user gesture).

### Browser OPFS writable lifecycle (specified)

- One `createWritable({keepExistingData: true})` at `open!` (after the size
  probe), held for the stream's lifetime. `.seek(size)` once at creation so
  appends go to the existing EOF (a fresh writable starts at offset 0 even with
  `keepExistingData`).
- Sequential `.write(val)` calls advance the offset automatically; no per-write
  seek.
- The held writable takes an OPFS lock; a second `open!` of the same file
  fails until `close!` releases it.
- `put!`s arriving before the writable resolves are buffered in the ringbuffer
  as usual; the disk-write scheduler queues them until the writable is ready,
  then drains in order. Readers see them via the ringbuffer immediately,
  regardless of disk state.
- `close!` awaits all pending `.write` Promises, then `await writable.close()`.
- *Single-thread caveat:* on the browser main thread `close!` cannot
  synchronously block on a Promise; it must integrate with the async runtime's
  parking machinery (analogous to how `next`/`put!` park via waiters). The
  durability guarantee ("once `close!` returns, data is durable") holds, but on
  browser "returns" means the runtime unparks the caller after the flush. Pin
  down the exact close-parking mechanism during implementation.

---

## Protocols implemented

`FileStream` implements:

- `IDaoStreamReader` (`next`) — delegates to the internal ringbuffer.
- `IDaoStreamWriter` (`put!`) — throws the stored cause if the writer is
  poisoned (a prior async write failed); otherwise pushes the byte array as one
  segment to the ringbuffer (evicting oldest if at capacity, waking readers),
  schedules an async disk append, and returns `{:result :ok :woke [...]}`. Throws
  if closed. Throws if `val` is not a byte array. **Host note:** `bytes?` (used by
  `file_output_stream`) exists only on clj/cljd; on cljs there is no core
  byte-array predicate, so the cljs branch must validate explicitly with
  `(instance? js/Uint8Array val)` (both Node and browser). Without this the
  "throws on non-byte-array" contract silently degrades on the two new hosts.
- `IDaoStreamBound` (`close!`, `closed?`) — closes the ringbuffer (readers get
  `:end`), then blocks on the final disk flush; `closed?` delegates to the
  ringbuffer.
- `IDaoStreamWaitable` (`register-reader-waiter!`, `register-writer-waiter!`) —
  delegates to the internal ringbuffer so the async runtime parks instead of
  polls. Note `register-writer-waiter!` is **inert for this transport**: with
  `:evict-oldest`, `put!` never returns `:full`, so no writer parks; and
  writer-waiters are only woken inside `drain-one!`, which `FileStream`
  deliberately does not implement. Delegating it is harmless but exercises no
  path here.

`FileStream` intentionally does **not** implement `IDaoStreamDrainable`
(`-drain-one!`, `stream.cljc:85-92`): multi-cursor reads are non-destructive,
and destructive draining would conflict with that invariant.

Note: the `IDaoStreamWriter` docstring in `stream.cljc` loosely says "Returns
:ok / :full"; the real contract (followed here and ns-documented at
`stream.cljc:12`) is the map `{:result :ok :woke [...]}` / `{:result :full}`.
Fixing that docstring is a passing cleanup.

---

## Files

1. **`src/cljc/dao/stream/file.cljc`** — a `FileStream` defrecord wrapping a
   bounded ringbuffer + a host-specific disk writer. Host-specific code is
   confined to the disk writer and `open!`/`close!`; protocol methods that
   delegate to the ringbuffer (`next`, waiters, `closed?`) are host-agnostic
   and should be shared to minimize defrecord duplication. Host blocks:
   `:clj`, `:cljd`, `:cljs` with a Node / OPFS split on
   `js/process.versions.node`.
   Carries `#?(:cljs (:require-macros [dao.stream]))` for cljs `defopen`.
   Registers via `(ds/defopen :file [descriptor] ...)` under each host, reading
   `:path`, `:capacity`, and `:eviction-policy` from the descriptor.

2. **`test/dao/stream/file_test.cljc`** (`.cljc`, not `.clj`) — 8 cases,
   host-conditional temp-dir / byte helpers (cljs Node: `os.tmpdir()` +
   `path.join`, per `test/dao/db/file_test.cljc`; note that file deals in EDN,
   so byte helpers must be adapted: `js/Uint8Array.` / `.length` / `.subarray`
   on cljs, `byte-array` / `alength` / `System/arraycopy` on clj — see the
   existing `.clj`-only helpers in `test/dao/stream/file_input_stream_test.clj`
   and `file_output_stream_test.clj`):
   1. **tail-from-now** — pre-existing file content is NOT visible; a reader at
      position 0 gets `:blocked` until a post-open `put!`.
   2. multiple cursors on post-open writes advance independently and
      non-destructively.
   3. **live tail** — `put!` a value of any size, then `next` sees it
      immediately.
   4. `:blocked` while open and caught up; `:end` after `close!`.
   5. **eviction** — with a small `:capacity`, after more than capacity `put!`s
      a cursor at an old position gets `:daostream/gap`; the tail keeps
      advancing.
   6. `put!` is non-blocking (returns `:ok` immediately).
   7. `put!` after `close!` throws; `put!` of a non-byte-array throws; **a
      poisoned writer fails fast** — force an async write failure (e.g. an
      unwritable handle), then assert the next `put!` throws the write's *real*
      cause (not the opaque agent restart error) and that `close!` surfaces the
      same cause.
   8. creates the file if missing; appends persist to disk; `close!` blocks
      until the disk flush completes (for a newly-created file, reopen and
      verify the post-open writes are present). Note this "only post-open
      writes" check holds because the file starts empty — tail-from-now is a
      memory/read property, not a disk one; appends to a pre-existing file land
      after its prior bytes, which remain on disk.

3. **`src/cljs/test_runner.cljs`** — add `[dao.stream.file-test]` to `:require`
   and to the `run-tests` list. (The `run-tests` list is not strictly
   alphabetical — slot it next to `dao.stream.http-test` or `dao.stream-test`.)

4. **`src/cljc/yin/io/file.cljc`** — Yin VM effect wrapper mirroring
   `yin.io.file-output-stream`:
   - descriptor fn `file` -> `{:effect :io/file :path path :capacity n :eviction-policy p}`.
   - `module/register-module! 'yin.io {'file file}`.
   - effect handler under `#?(:default ...)` (all hosts — clj, cljd, and cljs;
     `:default` also covers cljd, which the underlying `FileStream` supports — a
     deliberate **divergence** from the existing `yin.io.file-*-stream`
     handlers, which are `#?(:clj ...)`-only; lifting those is listed as future
     work) — opens
     the stream, stores under `[:store stream-id]`, returns
     `{:value {:type :stream-ref :id stream-id} :state (assoc-in state [:store stream-id] stream)}`.
   - `module/register-effect-handler! :io/file handle`.

5. **`docs/design/dao.stream.md`** — add a `:file` subsection after the existing
   "File Input / Output" block (around line 556) and an entry in the
   "Current Implementation Status" list (~line 642). Document the tail-from-now,
   bounded-eviction, and eventual-durability (resolved at `close!`) semantics,
   plus the browser async caveats.

---

## Verification

```bash
# clj
clj -M:test

# lint
clj -M:kondo --lint src/cljc/dao/stream/file.cljc src/cljc/yin/io/file.cljc

# Node cljs
clj -M:cljs -m shadow.cljs.devtools.cli compile test && node target/node-tests.js

# ClojureDart
clj -M:cljd compile
```

Implementation follows TDD: write the test first (red), implement to pass
(green), refactor.

---

## Caveats

- **Eventual durability until `close!` (all hosts)** — `put!` returns `:ok`
  before the disk flush completes; a crash before `close!` can lose unflushed
  bytes. `close!` **blocks** on the final flush, so once `close!` returns
  without error all written bytes are durable. A future explicit `flush!` /
  `sync!` would grant on-demand durability without closing.
- **Async write errors poison the writer and must be surfaced with their real
  cause** — because `put!` returns `:ok` before its write runs, a disk error
  appears asynchronously and **stalls every subsequent write**: on clj a failed
  agent stops executing queued actions (all writes after the failure are
  silently dropped from disk — verified empirically); on Node a rejected write
  breaks the serialized Promise chain (same effect); on OPFS a rejected
  `.write` does likewise. `close!` must detect this and throw the *actual*
  error. On clj `(await agent)` **does** throw on a failed agent — but only an
  opaque `IllegalStateException` ("Agent is failed, needs restart"); the real
  cause is in `(agent-error a)` (or the error cell set by the agent action;
  equivalently a rejection handler on Node/OPFS). `close!` rethrows that cause
  and closes the file handle in a `finally`. **`put!` fails fast on the same
  cell** (see "Poison flag" above): once poisoned, the next `put!` throws the
  stored cause instead of accepting a write the disk would drop. This is part of
  the contract, not optional — without it a never-closed live-tail stream would
  hide write failures indefinitely. The read is non-blocking.
- **In-memory visibility precedes on-disk persistence** — a reader can observe
  bytes via `next` before they reach disk. Write order is preserved on disk
  (the agent / Promise / Future queue serializes appends), but durability lags
  visibility. This is by design.
- **Bounded retention, not unbounded** — the ringbuffer retains at most
  `:capacity` segments (`:evict-oldest` by default); a reader that falls
  behind gets `:daostream/gap` and must resync at the current tail. Evicted
  bytes are gone from memory but safe on disk (read history via
  `file-input-stream`). `:capacity nil` recovers unbounded retention for
  callers who explicitly accept the risk and guarantee a keeping-up consumer.
- **Capacity is segment-count, not bytes** — segments are variable-sized
  (`put!` values), so `:capacity` bounds the *count* of retained writes, not a
  byte budget. Callers wanting a strict memory ceiling should `put!` fixed-size
  chunks. A byte-bounded capacity is a possible future refinement.
- **Browser `open!` cannot block** — OPFS setup is async, so `open!` returns
  immediately and the first `next` returns `:blocked` until writes arrive
  (each pushed segment wakes parked readers via the ringbuffer). Fine in the
  async runtime, which parks via waiters.
- **Browser `close!` cannot synchronously block** — see the OPFS lifecycle
  note above. `close!`'s durability guarantee holds, but on the main thread it
  is realized through the async runtime's parking rather than a synchronous
  await.
- **Only clj `close!` synchronously blocks** — cljd and the browser cannot block
  (event-loop/isolate), and **Node is async too**: its `close-writer!` returns a
  Promise of `write`/`fsync`/`close` (not the `*Sync` variants) to keep the
  single-threaded event loop free. So the "`close!` blocks" guarantee is
  host-asymmetric: a synchronous block **only on clj**; on Node/cljd/browser,
  durability is obtained by awaiting `(flushed fs)` and confirming it resolves
  rather than rejects.
- **No JVM type hints in the `:clj` branch** — ClojureDart host-eval reads the
  `:clj` form and cannot resolve JVM types as Dart types. Use interop on
  untyped receivers inside the `:clj` form only (same constraint as
  `file_output_stream`, documented at `file_output_stream.cljc:3-8`).
- **Scope** — `dao.stream.file` only. The existing `file_input_stream` /
  `file_output_stream` remain **clj + cljd** (not clj-only); only the
  `agent.tools` `file_read` / `file_write` handlers are clj-only.

---

## Open / future work

- Explicit `flush!` / `sync!` for guaranteed durability on demand (without
  `close!`).
- Byte-bounded capacity (a memory budget) instead of, or in addition to,
  segment-count.
- Pin down the browser `close!` parking mechanism (async-runtime integration
  for the final flush + `writable.close()`).
- Retrofit `file_input_stream` / `file_output_stream` with `:cljs` branches.
- Lift `agent.tools` `file_read` / `file_write` from `:clj` to `:default`
  (replacing JVM `byte-array` / `System/arraycopy` with `js/Uint8Array` /
  `.subarray`).
- Lift the existing `yin.io.file-*-stream` effect handlers from `:clj` to
  `:default`.
- Fix the `IDaoStreamWriter` docstring in `stream.cljc` to state the map
  contract.

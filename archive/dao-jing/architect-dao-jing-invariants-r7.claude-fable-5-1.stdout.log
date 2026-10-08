Warning: no stdin data received in 3s, proceeding without it. If piping from a slow command, redirect stdin explicitly: < /dev/null to skip, or wait longer.
Completed-GMT: 2026-09-07 08:41:31 GMT
Completed-Local: 2026-09-07 15:41:31 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

# dao.jing on DaoStream v2

Status: implementation plan, revision 7, subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract) and
[`dao.jing.md`](./dao.jing.md) (the design). `dao.stream` is implemented
and stable on clj, cljs (Node) and cljd. This document says how to **build
`dao.jing` on it and delete what is there**. The existing implementation and
its tests have no authority; two things do: the contract, and the invariants
listed below. Revisions 1–6 are the record of how those invariants were
argued out (`cdb871c` is revision 5). This document is transient.

## What DaoJing is, in one paragraph

A `dao.jing` observes an explicit pool of `dao.stream` reader handles and
materializes every payload into content-addressed storage:
`key = :segment/sha256-<hash(canonical-encode(x))>`, `KV[key] = x`,
insert-if-absent. It knows nothing about what a payload means. Content-store
handles are plain maps of three backend effects, `{:put-content-fn
:get-content-fn :close-fn}`; `materialize!`, `get` and `close!` dispatch
through them. Two backends: `dao.jing.mem` and `dao.jing.file`. That is the
whole of `dao.jing.md`'s Definition, and nothing here changes it.

## The invariants

Each is marked with its source: **[D]** stated in `dao.jing.md`; **[T]** pinned
by an existing test; **[T→D]** pinned by a test, not stated in the design, and
judged a real invariant that J3 adds to `dao.jing.md`; **[T✗]** pinned by a
test, judged an accident of the old implementation, and dropped. Every
**[T✗]** is named so the implementer knows it was seen, not missed. Each group
maps to one test namespace.

### A. Content addressing — `jing_test`

- A1 [D] The address is derived from the payload alone:
  `(segment-key x)` is `:segment/sha256-<64 lowercase hex>` and nothing else
  enters it — not the source stream, not arrival order, not pool position.
- A2 [D] Equal values address equally on every host. `content-hash` is
  order-insensitive over maps and sets and total over every value, `nil`
  included. Pinned by SHA-256 known-answer vectors on all three hosts
  (`jing_test.cljc:56-77`), which is the only check that cljd's hand-rolled
  digest mints the same addresses as the JVM and Node.
- A3 [T→D] Distinct values address distinctly across types: `42` and `"42"`
  do not collide. The design's "equal supported values produce the same
  bytes" leaves the converse implicit; it belongs in *Canonical encoding*.
- A4 [T→D] Minted addresses are readable EDN: the name never starts with a
  digit, and a key survives `pr-str` → `read-string`. Addresses cross every
  EDN boundary in the system; the `sha256-` prefix is load-bearing for that,
  and the design should say so.
- A5 [D] `segment-address?` is the strict test, and `segment-hash` throws on
  anything that is not a valid address — foreign namespace, wrong length,
  non-hex, uppercase.
- A6 [D] The encoder is transitional (order-normalized `pr-str`), and
  `content-hash`, `segment-key` and every address change together when the
  canonical byte encoding lands. Unchanged, still open.

### B. Materialization — `jing_test`, `mem_test`

- B1 [D] `materialize!` derives the address itself, calls
  `(put-content-fn address payload)`, and returns the address only after the
  backend reports success.
- B2 [D] A backend answers exactly `:inserted` or `:present`; any other
  answer — `true`, `nil`, `:ok`, `"inserted"`, a map — is an invalid backend
  result and throws.
- B3 [D] On `:present` the stored value is read back and compared. An absent
  read-back or an unequal value is an integrity failure that throws; nothing
  is ever overwritten. Re-materializing an equal value is a no-op that
  returns the same address.
- B4 [D] What is stored is exactly the payload. No provenance, no source
  identity, no stamp. Equal payloads arriving through different pool members
  land on exactly one entry.
- B5 [T→D] `nil` is a legal payload distinct from absence: `get` takes a
  caller-supplied not-found and returns stored `nil` as `nil`. The design
  says payloads are opaque; it should say this consequence.
- B6 [T→D] A backend validates before it writes: the address must be a
  segment address and must hash to the payload, else it throws and stores
  nothing. The design describes the backend as an effect; this is the
  integrity precondition every backend, including a future remote server,
  enforces at its own door.
- B7 [T✗] `:dao.jing/content-missing` is a legal payload. This test exists
  because an earlier sentinel was that keyword. The general rule — any value
  is a payload, and the not-found sentinel is an opaque per-host object no
  payload can equal — is B5; the specific keyword case is dropped.

### C. Reads — `jing_test`

- C1 [D] `get` accepts only segment addresses; arbitrary keys and mutable
  roots throw **before the backend is consulted**.
- C2 [D] `get` returns the caller-supplied not-found for an absent address
  and only for that address.

### D. Handle lifecycle — `jing_test`, `mem_test`, `file_test`

- D1 [D] A handle is plain data: `:put-content-fn`, `:get-content-fn`, an
  optional `:close-fn`. `materialize!` and `get` throw on a handle missing
  the function they need.
- D2 [D] `close!` delegates to `:close-fn` and returns `nil`; a handle without
  one has nothing to release.
- D3 [T→D] A backend's close is idempotent; after close, every entry point —
  `materialize!`, `get`, and the raw fns — throws, and stored content is
  neither cleared nor rewritten. The design says lifecycle belongs to the
  backend; this is the contract every backend meets.
- D4 [T✗] The handle carries a `:state` atom of shape `{:closed? :content}`,
  and carries no `:stream` key. Tests reach into `(:content @(:state h))` to
  assert "exactly one entry, exactly the payload". The observable is B4; the
  atom shape is implementation. The rewrite exposes what B4 needs — for the
  mem backend a test-visible content view is fine, for the file backend
  `records` (F5) is the view — and pins neither the atom nor the absence of
  a key that guarded a design that no longer exists.
- D5 [T] Under JVM contention, N concurrent puts of one payload yield exactly
  one `:inserted`, N−1 `:present`, and one stored entry — for both backends.

### E. The intake pool and the observer — `jing_test`, plus the pool drains in `mem_test`, `file_test`, `dht_test` and the five `dao.space` tests

- E1 [D] The pool is supplied explicitly as `{:stream :cursor}` members; the
  observer registers nothing, discovers nothing, and holds no atom. Its state
  is plain data, `{:members [...] :next i}`, and `(observer-state (:members
  st))` rebuilds it.
- E2 [D] The cursor is minted by the composition from an anchor of its
  choosing and retained exactly as the stream returns it. No arithmetic, no
  inspection of its shape, no fabrication. `adopt-cursor` is the only other
  way a member's cursor changes, and it takes a value the stream handed out.
- E3 [D] A member without a cursor, or whose stream lacks the reader surface,
  is a composition defect: `observer-state` throws before any operation.
- E4 [D] Signals are exactly `dao.stream/outcomes-next` — the seven
  keywords — and a `next` outcome outside that set throws as a transport
  defect. Checked declaration-driven: iterate the set, every outcome has a
  branch.
- E5 [D] `materialize!` runs before the cursor advances; if it throws, the
  exception propagates and the caller's state is untouched, so the same
  payload is reprocessed from the same cursor once the backend succeeds.
- E6 [D] Round-robin fairness: `observe-step!` starts at `:next`, walks the
  pool once, processes at most one payload, and yields that member's turn.
  A continuously ready member cannot starve another; blocked and ended
  members never prevent later members from being checked.
- E7 [T✗] Two equal-length ready members drain in strict `A B A B` order.
  That is one schedule that satisfies E6, and the design says scheduling
  never affects the content set. The test may keep it as an illustration of
  E6; it is not an invariant the implementation owes.
- E8 [D] Empty pool → `blocked`. All members ended → `end`. A closed member
  still yields its retained payloads, then `end`.
- E9 [D] `gap` and the three defect outcomes (`cursor-mismatch`,
  `invalid-cursor`, `transport-error`) are reported immediately with
  `:member i` — `gap` with its recovery `:cursor`, the defects with the
  outcome map under `:result` — leave the member's cursor unchanged, move
  the scheduling index past the member, and are reported again on that
  member's next turn. Nothing is auto-resynchronized: adopting the recovery
  cursor is safe (materialization is idempotent) but whether loss is
  acceptable only the composition knows.
- E10 [D] The observer never interprets a payload. It hashes and stores;
  datoms, B-tree nodes and manifests are opaque to it.
- E11 [T] What the `dao.space` drains pin, and nothing more: drain until
  `blocked` or `end`; a `gap` is fatal to that test composition. That is a
  composition policy, correctly placed in the test.

### F. The file backend — `file_test`

- F1 [D] Each record is `[address payload]` appended to a file; a put is
  acknowledged `:inserted` only after the write is flushed.
- F2 [D] On open, the file is replayed into the content map. An equal
  duplicate record is tolerated; an unequal record at an existing address
  is a collision and the open fails.
- F3 [D] An incomplete tail is truncated before replay — a tail shorter than
  the length prefix, a negative length, a length reaching past end of file.
  After truncation the surviving records replay and a subsequent put lands
  clean. This is correctness evidence for the truncation code; there is no
  stored content anywhere to be compatible with, and the framing is free.
- F4 [D] A complete record that cannot be decoded, is not a two-element
  vector, carries a non-segment address, or does not hash to its payload
  fails the open — closed, loudly.
- F5 [T] Durability: acknowledged inserts survive close and immediate
  reopen; a `:present` put writes no record (the file's record count is
  unchanged). The backend exposes `records`, the decoded record vector of a
  path, as the test's view of the file.
- F6 [T→D] Same as D3 and D5 for this backend: idempotent close, throw after
  close, serialized concurrent puts with exactly one record written.
- F7 [T✗] The 4-byte big-endian length prefix, the `pr-str` encoding, and the
  handle's `:log` and `:write-lock` keys. Implementation. Kept where
  convenient, owed nowhere.

### G. Deferred: the remote store

Invariants for a remote content store are Decision 3's; see there. None is
built in this plan.

## Decisions

### Decision 1 — The observer

Entry shape `{:stream <v2 reader handle> :cursor <opaque> :status s}`,
composition-minted cursors, successor-only advancement after successful
materialization, `adopt-cursor`, totality over `outcomes-next`, defects as
data — invariants E1–E10 above, stated once there. Signal table:

| signal | when | extra keys | member cursor |
| --- | --- | --- | --- |
| `:dao.stream/ok` | a payload was materialized | `:address` | successor |
| `:dao.stream/blocked` | empty pool, or every non-ended member blocked | — | unchanged |
| `:dao.stream/end` | every member has ended | — | unchanged |
| `:dao.stream/gap` | member `i`'s position was evicted | `:member i`, `:cursor` (recovery) | unchanged |
| `/cursor-mismatch`, `/invalid-cursor`, `/transport-error` | member `i`'s read failed | `:member i`, `:result` | unchanged |

### Decision 2 — The durable log is not a stream

`dao.jing.file` writes its own framed file. It does not use a stream
transport, and none is built for it.

**Why.** The file has one reader — the backend itself, once, at open — and
one writer — the backend's own put. No cursor is ever handed out, no
descriptor projected, nothing attaches, and the file never sits in a pool.
Every property the contract exists to guarantee is unused, and a transport
with one consumer would owe the conformance suite for nobody. The parallel is
`dao.jing.mem`: a private atom behind the backend effects; `dao.jing.md`
already says "backend effects are explicit functions, not a protocol or
hidden state." Wrapping the file as a public `:dao.stream/type` also made
DaoJing's storage format a transport anyone could open.

**Why a v2 append-log is not an alternative.** It is constructible —
`append!`'s `ok` "implies neither local readability nor remote delivery"
(`dao.stream.md`, Writing) — but it cannot supply what F1 needs: `:inserted`
means "durably stored now", and an `ok` that disclaims durability cannot
honestly become it. Either the transport grows a transport-owned durability
signal contract-generic code may not reach for, or `materialize!`'s contract
changes so durability arrives later as data, which reaches
`dao.data.btree.storage` and `dao.space.index`. That is the write-path
redesign, not a transport choice.

**The pre-existing question.** `datom.world.md:66-68`: an adapter that exposes
a function for portable code to call "keeps the coupling, and the effect
never appears as an emission." `:put-content-fn` is such a function, whatever
sits beneath it. Whether DaoJing's synchronous content handle conforms to Host
Boundaries is real, is not created here, and is cured by no transport. J3
records it in `dao.jing.md`'s open items as **"the content write path as an
effect stream"** — durability as data, `materialize!` no longer returning an
address synchronously — out of scope here because of its reach. The design
here is compatible with that future: `datom.world.md:57-59` puts raw host
operations inside the host interpreter, which is where this leaves the file
IO.

**Recorded dissent.** `gpt-5.6-sol` holds that direct file IO behind
`:put-content-fn` violates `datom.world.md:53-68` and a v2 append-log should
be built. The plan holds the violation, if any, is the synchronous handle
itself. `glm-5.3` and `gpt-6-astra` accept the plan's position.

### Decision 3 — The remote store: deferred, design recorded

**Why it is deferred, and why that is not legacy.** `jing/get` answers
synchronously; `dao.stream.rpc` cannot answer synchronously on any host —
by contract, no operation waits, and on cljs and cljd there is nothing to wait
with. So a remote content store cannot present a local store's interface,
and that is true of code written from scratch today with no v1 anywhere. What
a remote store *is* — a client the caller steps, whose answers arrive as
completions — changes what `dao.space.index` does to restore a B-tree from a
remote coordinate, which is the async hydration `dao.data.btree.md` §5.4
defers, and belongs to `dao.space`'s plan. It is the one thing here that a
free rewrite does not dissolve. The current `dao.jing.remote`, its tests, and
`dao.jing.coordinate`'s JVM `:dao.jing/remote` branch therefore stay as they
are until that plan; nothing else in `dao.jing*` depends on them.

**The design, for that plan.** Server side: `default-handlers` — `:jing/put-content`
returns `:inserted`/`:present`, `:jing/get-content` returns the exact envelope
`{:found? boolean :value v}` — served by `dao.stream.apply/serve-once!`;
B6 holds at the server's door. Client side:

```clojure
(remote/client-state rpc-state)
(remote/request-put state address payload)   ; => {:outcome k :state s' :id n?}
(remote/request-get state address)
(remote/request-materialize state payload)
(remote/step state budget)                   ; => {:state s' :attempt k? :completions [...] :diagnostics [...]}
(remote/abandon state reason)
```

`request-*` validate as C1 and return the RPC layer's outcomes verbatim plus
`busy` while `rpc/unsent?` holds. `step` is the only path that clears `busy`,
in fixed order: (1) re-attempt `:unsent` through `rpc/request!` if not
terminal, folding the outcome under `:attempt`; (2) `rpc/poll!`; (3) if
terminal and `:unsent` remains, `rpc/abandon-unsent` with the terminal
reason; (4) issue unissued verify hops in put-id order until `full`, or if
terminal complete them `:lost`; (5) take completions and diagnostics exactly
once, routing each whose id a materialization record knows. Completion decode
is total: responses by op, malformed as `:error /malformed-response`, any
reason as `{:lost reason}` pass-through. `request-materialize` is B1–B3 over
the wire: derive the address, put, register a per-id record at any id-bearing
outcome, on `:present` issue the correlated get and register under its id
too, complete `:present` only on equality, else `:error /integrity-failure`
or `/present-but-absent`; one completion per materialization, carrying the
put id; the record's removal is the exactly-once guard. The verify hop is the
one obligation the client carries, in the shape `rpc`'s own `:unsent` and
`apply`'s `:pending-response` already sanction. Its lifecycle:

| record phase | event | record | published | routes by |
| --- | --- | --- | --- | --- |
| *(none)* | `request-materialize`: `requested` / `pending-request` | created `:put`, registered under put id | nothing | — |
| *(none)* | `request-undeliverable` | created, registered; completion already outboxed | next drain: `:lost reason`; removed | put id |
| *(none)* | `allocator-error` / `terminal` | not created | nothing; no id | — |
| `:put` | order 1 re-attempt: `requested` / `pending-request` | unchanged | nothing | — |
| `:put` | order 1 undeliverable, `abandon` while unsent, order 3, or `lose-outstanding` (any terminal — `allocator-error` included since `39ad69e` — or non-terminal `gap` on an outstanding put) | removed | `:lost reason` | put id |
| `:put` | `:inserted` | removed | `:result :inserted` | put id |
| `:put` | `:present` | `:verify-unissued` | nothing | put id |
| `:put` | `:error` | removed | `:error e` | put id |
| `:put` | a get-id completion | impossible — none allocated | — | — |
| `:put` / `:verify-issued`, envelope `:unsent` | non-terminal `gap` | no-op; order 1 re-attempts | nothing | — |
| `:verify-unissued` | order 4, `rpc/unsent?` true | unchanged; retried next step | nothing | — |
| `:verify-unissued` | order 4, get `requested` | `:verify-issued`, registered under get id | nothing | — |
| `:verify-unissued` | order 4, get `pending-request` | `:verify-issued`, registered; issuing stops this step | nothing | — |
| `:verify-unissued` | order 4, get `request-undeliverable` | `:verify-issued`, registered; completion outboxed | order 5, same step: `:lost reason`; removed | get id |
| `:verify-unissued` | order 4, get `allocator-error` | removed; other outstanding records complete via `lose-outstanding` | `:lost /allocator-error` | synthesized; put id |
| `:verify-unissued` | order 4, state terminal | removed | `:lost terminal-reason` | synthesized; put id |
| `:verify-unissued` | `abandon` / non-terminal `gap` | no-op: no envelope or outstanding id of this record | nothing | — |
| `:verify-unissued` | any RPC completion | impossible | — | — |
| `:verify-issued` | order 1 re-attempt: `requested` / `pending-request` | unchanged | nothing | — |
| `:verify-issued` | order 1 undeliverable, `abandon` while unsent, order 3, or `lose-outstanding` | removed | `:lost reason` | get id |
| `:verify-issued`, get outstanding | `abandon` | no-op | nothing | — |
| `:verify-issued` | get `:found? true`, `=` payload | removed | `:result :present` | get id |
| `:verify-issued` | get `:found? true`, `≠` payload | removed | `:error /integrity-failure` | get id |
| `:verify-issued` | get `:found? false` | removed | `:error /present-but-absent` | get id |
| `:verify-issued` | get `:error` | removed | `:error e` | get id |
| `:verify-issued` | order 4, or a put-id completion | impossible | — | — |
| *removed* | any completion for a former id | impossible — each id completes once | — | — |

Its tests: one per reachable row, plus `busy` clears only through `step`,
unsent-at-detach, `abandon` before rebind, reason pass-through by
declaration, diagnostics once, and the wire cases over `dao.stream.ws` on
all three hosts.

## What is built, in three phases

Each phase builds something checkable on clj, cljs (Node) and cljd, deletes
what it replaces, and leaves the suite green. The order is by dependency: the
file backend depends on nothing but B and D; the observer depends on the pool
having two v2 ends; the prose depends on both being real.

### P1 — The file backend

**Build** `dao.jing.file` from F1–F6: `create-content-file` returning
`{:put-content-fn :get-content-fn :close-fn}` over a private framed file —
open or create, truncate an incomplete tail, replay, append-and-sync — with
`records` for F5. Three host branches (`RandomAccessFile`, Node synchronous
`fs`, `dart:io` `RandomAccessFile`), `#?(:cljd … :clj … :cljs …)` with `:cljd`
first; `#?(:clj …)` alone does not exclude code from the cljd build.

**Delete** `src/cljc/dao/stream/log.cljc` and `test/dao/stream/log_test.cljc`
— its only consumer was this backend, and its `defopen :append-log` is a
load-time registration into the ambient v1 registry. `dao.stream.file`
(live-tail `:file`, consumed by `yin.io.file`) is a different transport and
is untouched. Delete the old `file_test.cljc`.

**Prove** with a new `file_test.cljc` written from F1–F6 and D1–D5: round trip
of every payload kind including `nil`; `:inserted` then `:present` with an
unchanged record count; durability across close and reopen; acknowledged
insert survives immediate close; equal duplicate records recover; collision
on replay fails the open; each fail-closed category (malformed EDN, wrong
shape, invalid address, hash mismatch) fails the open; truncation of an
overlong-length tail, a sub-prefix tail, and a negative length, each followed
by a clean put and reopen, with the torn bytes hand-written per host; put and
get throw after close, close idempotent; JVM contention writes exactly one
record. `btree_durability_test`'s `file-close-reopen-recovery-test` runs
unchanged as a consumer check (one stale comment fixed).

### P2 — The observer, the pool's two ends, and the in-memory backend

**Build** in `dao.jing`: `observer-state`, `observe-step!`, `adopt-cursor`
per Decision 1 and E1–E10, requiring `dao.stream`; the content-addressing
core (A, B, C, D) is already what the invariants say and is kept. Build
`dao.jing.mem` from B, D — it already satisfies them; keep it, drop the
`:stream`-absence and atom-shape pins (D4). Move the pool's writer end to
v2: `dao.space.index/append-ok!` (`index.cljc:526-531`) appends with
`dao.stream/append!` and requires `:dao.stream/ok`; the transactor's
intake validation (`transactor.cljc:197-201`) uses `dao.stream/writer?`.
The pool is one thing with two ends, and `dao.jing.md` §Publication names
`publish-index!` as its writer; the rest of `dao.space` — the agent-local
stream, `snapshot-datoms`, the transactor's own protocols, the published-index
`defopen`, `dao.space.query` — is its own plan's.

**Delete** the old observer functions and their `dao.stream` require from
`dao.jing`; the observer section of `jing_test.cljc` and its v1 fixtures
(`open-stream`, `MalformedResultStream`); the v1 `open-stream` helpers in
`mem_test.cljc` and `dht_test.cljc`; the `open-intake` helpers in the five
`test/dao/space/` files.

**Prove** with `jing_test.cljc` rewritten from A–E over v2 ring buffers
(`ringbuffer/create!`, cursors from `stream/cursor … :dao.stream/oldest`):
known-answer digests on every host; address determinism, order-insensitivity,
totality, cross-type distinctness, EDN readability, `segment-hash`
strictness; `materialize!` derivation, verdict vocabulary, `:present`
read-back with the absent and unequal failures, idempotence, no stamp; `get`
strictness before the backend and not-found; handle-fn presence and `close!`
delegation; observer: plain-data state, missing cursor and non-reader throw,
empty pool blocked, all blocked, hashing and retrieval, convergence from two
members, blocked and ended members do not prevent later ones, all ended,
drains then end, fairness (a continuously ready member yields), materialize
before advance with the poison-payload case, `gap` on a capacity-2 buffer
with three appends (reported with `:member` and recovery cursor, cursor
unchanged, other member progresses, reported again next turn, `adopt-cursor`
then materializes the live value), the three defect outcomes from a scripted
reader, declaration-driven totality over `outcomes-next` with an out-of-set
outcome throwing, members round-trip through `observer-state`. `mem_test.cljc`
rewritten from B, D, D5 and one pool-convergence case; `dht_test.cljc:402`'s
pool case and the five `dao.space` drains rewritten over v2 intakes with
`:dao.stream/…` signals, `gap` fatal as before (E11). Confirm `Testing
dao.jing-test` in the Node output.

### P3 — `dao.jing.md`

Write the [T→D] invariants into the design — A3, A4 into *Canonical
encoding*; B5, B6 into *Materialization rule*; D3 into *Resource lifecycle*;
F6 into the file backend's entry. Rewrite *The intake pool* and *Cursor
tracking and recovery* to Decision 1's entry shape, cursor discipline and
signal table. Rewrite *Implemented surface*: the file backend as "a
content-addressed store backed by a private framed append-only file"
(Decision 2's design-document change, routed as such); `dao.jing.remote` as
awaiting `dao.space`'s plan with Decision 3 as its target. *Open items*:
durable checkpoints store whatever cursor value the transport minted,
serializability TBD in the contract; add **"the content write path as an
effect stream"** per Decision 2. Untouched: *Definition*, *Publication*,
*Storage ignorance*, *Physical intake versus semantic composition*, *Reads*,
*Lineage*, `dao.jing.dht.md`.

## Host matrix

| phase | clj | cljs (Node) | cljd | notes |
| --- | --- | --- | --- | --- |
| P1 | ✓ | ✓ | ✓ | three host branches in `dao.jing.file`; torn-tail bytes hand-written per host |
| P2 | ✓ | ✓ | ✓ | pure `.cljc`; the cljd lane regenerates `test/cljd-out/`, one process at a time |
| P3 | — | — | — | prose |

## Invariants of the whole, checked

No hidden global state: `dao.jing` holds no atom; backend state is
per-handle; P1 removes a `defopen` from the ambient registry. No implicit
control flow: `observe-step!` returns; the composition calls again. No
callbacks in any surface touched. No shared mutable state: a handle is owned
by whoever created it. Interpretation and execution separate: the observer
interprets the pool, the backend executes the write. No assumed graphs.

## Boundary

**Built or deleted here:** `dao.jing`, `dao.jing.file`, `dao.jing.mem` (kept),
`dao.stream.log` (deleted), the two writer seams in `dao.space.index` and
`dao.space.transactor`, `jing_test`, `mem_test`, `file_test`, `log_test`
(deleted), the pool cases in `dht_test` and the five `dao.space` tests, one
comment in `btree_durability_test`, `dao.jing.md`.

**Not here, each with its home:** `dao.jing.remote`, `remote_test` and
`dao.jing.coordinate`'s remote branch — Decision 3, `dao.space`'s plan;
`dao.jing.dht*` — the UDP transport, deferred by the stream plan; everything
else in `dao.space*` — its own plan; the content write path as an effect
stream — a DaoJing architecture item; a content-serving endpoint — whatever
product surface needs it; canonical encoding, durable checkpoints and a
runner, materialization acknowledgement, garbage collection — `dao.jing.md`'s
open items, unchanged.

## End condition

On clj, cljs (Node) and cljd: `dao.jing`, `dao.jing.file` and `dao.jing.mem`
require no `dao.stream*` namespace; every invariant A–F has a test that
exercises it, and the [T→D] ones are in `dao.jing.md`; `dao.stream.log` is
gone; `dao.space`'s pool writers accept v2 writers and every `dao.space`
suite is green over v2 intakes; `dao.jing.remote`, `dao.jing.coordinate`'s
remote branch and `dao.jing.dht*` are the only things under `dao.jing*` still
on v1, and this document says why.

---

## What changed from revision 6, and why

- **Authority.** Revision 6 was a migration: it ported code and sequenced
  phases to keep an old suite green. The user ruled that the old
  implementation and its tests have no authority; the contract and the
  invariants do. This revision replaces the file-by-file port lists with an
  explicit invariants list, each marked [D], [T], [T→D] or [T✗], and builds
  from it.
- **Judgements made.** Six things tests pinned that the design never stated
  are promoted to the design (A3, A4, B5, B6, D3, F6); four are dropped as
  accidents of the old implementation (B7, D4, E7, F7), named so they are
  seen to be dropped rather than missed.
- **Dropped.** The divergence register, the "what v1 callers lose" framing,
  "rewritten in place" and "ported" throughout, the byte-compatibility
  remnants, and phase ordering justified by protecting an old suite. Old
  code and tests are deleted in the phase that replaces them.
- **Kept.** Decisions 1 and 2 in substance; the Host-Boundaries open item;
  the `gpt-5.6-sol` dissent; Decision 3 in full, with its deferral restated
  correctly — a remote store cannot present a local store's interface under
  the contract, on any host, in code written from scratch — and its
  `dependent` row folded into the ordinary loss row since `39ad69e`.
- **Phasing.** Three phases by buildability — file backend first because it
  depends only on B and D, then the observer with both ends of the pool,
  then the design prose — each green on three hosts, each deleting what it
  replaces.

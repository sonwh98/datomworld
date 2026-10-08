Completed-GMT: 2026-09-06 15:46:07 GMT
Completed-Local: 2026-09-06 22:46:07 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

# dao.jing on DaoStream v2 — the content-observer slice

Status: draft migration plan, derived from and subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract), [`dao.jing.md`](./dao.jing.md)
(the storage-observer design) and [`datom.world.md`](./datom.world.md). Its
transport prerequisite is
[`dao.stream.implementation-plan.md`](./dao.stream.implementation-plan.md),
Phases 1–5, which are implemented on clj, cljs (Node) and cljd. It follows the
shape of the `dao.runtime` and `yin.repl` plans and is the fifth consumer
migration. This document is transient: it is consumed as its phases complete.
Drafted 2026-09-06. No phase has started.

## Context

`dao.jing` is next because `dao.space.index` and `dao.space.schema` require it,
and `dao.space` on the v2 contract is what ends ADR-0003's time-boxed
exception. The v1 surface inside `dao.jing*` is small — one `ds/next` in the
observer walk (`jing.cljc:391`) and five sites in the file backend
(`jing/file.cljc:83,149,175,190,207`) — with no `closed?`, `strict-vec`,
`drain-one!` or `take!!` anywhere. A mechanical port is not what makes it hard.
What makes it hard is that two of the three stream-touching pieces have no v2
counterpart to port *to*: there is no v2 append-log transport, and there is no
v2 RPC that returns a value synchronously. Each of those is a boundary decision,
settled below before any phase runs.

## The problem, by piece

| piece                                                    | v1 mechanism                                                                                               | v2 counterpart                                                            |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `dao.jing/observer-state`, `observe-step!`               | `ds/next` on any v1 reader; inline `{:position 0}` cursors; bare `:ok`/`:blocked`/`:end`/`:daostream/gap`  | reader surface: `cursor`, `next`; opaque cursors; outcome maps            |
| `dao.jing.file/create-content-file`                      | `ds/open!` of `{:dao.stream/type :append-log}`; `ds/next` to replay; `ds/append!` + fsync; `ds/close!`     | **none** — the stream plan defers file/log transports                     |
| `dao.jing.remote/connect-content!`, `content-client`     | `dao.stream.rpc.client/call!` — synchronous on the JVM, Promise/Future elsewhere; JVM-only constructor     | `dao.stream.rpc` — poll-shaped, state-machine-valued, all three hosts  |
| `dao.jing.remote/default-handlers`                       | handler map `{op (fn [& args] value)}`                                                                     | same shape; `dao.stream.apply/dispatch-request` applies it unchanged   |
| `dao.jing.mem`, `dao.jing.coordinate`, hashing, `materialize!`, `get`, `close!` | stream-free                                                                         | unchanged                                                                 |

Consumers outside `dao.jing*` that reach these pieces, named so the boundary is
visible: `dao.space.index` (`jing-coordinate/open!` inside its `defopen`, and
v1 `ds/append!` into the intake pool), `dao.space.schema`, `dao.space.transactor`,
`dao.data.btree.storage` (handle API only), `yin.vm.space`, and the tests under
`test/dao/space/` and `test/dao/data/` that drive the v1 observer over v1 intakes
and open remote clients through `connect-content!`. None of them is migrated
here.

## Strategy

The same two narrowings as the sibling plans, with one deliberate difference in
where the v2 namespace boundary falls.

1. **Parallel where a v1 consumer still needs the v1 shape; in place where
   nothing is being replaced.** The four prior consumers were rebuilt wholesale
   beside their v1 twins because their entire surface was stream-shaped. Most
   of `dao.jing` is not: content addressing, `materialize!`, `get`, `close!`
   and `dao.jing.mem` never touch a stream. Duplicating them into a `dao.jing.v2`
   would create a second minting path for content addresses — two sources of
   truth for identity, which is worse than an unusual namespace layout. So:
   - `dao.jing` core stays as it is. Its v1 observer functions stay untouched
     until `dao.space` migrates, because `dao.space`'s tests drive them over v1
     intakes today.
   - The v2 observer is a new namespace, **`dao.jing.v2`**, requiring `dao.jing`
     for `materialize!` and `dao.stream` for the reader surface. It is the
     part of DaoJing that lives on the stream contract, and nothing else.
   - The remote adapter is a new namespace, **`dao.jing.v2.remote`**, beside
     the untouched v1 `dao.jing.remote`, which `dao.jing.coordinate` and the
     `dao.space` tests keep using until their own plan.
   - `dao.jing.file` is rewritten **in place**, because its migration is a
     removal of a coupling, not a port (see *Decision 2*). There is no v2
     transport to sit beside; its consumers use only `create-content-file` and
     the handle API, which do not change.
2. **The content-observer slice, and nothing else.** One observer, one durable
   backend, one remote client state machine with its handlers. Not the
   canonical encoding, not durable checkpoints, not a content-serving endpoint
   as production infrastructure, not `dao.space`'s intake appends, not the DHT.

No compatibility facade, alias, or dual protocol. The v1 observer and v1
remote are evidence about behavior, not constraints on the v2 shape.

## Decisions

Settled here so no phase decides them alone.

### Decision 1 — The observer: entry shape, cursor discipline, gap

**Entry shape.** A pool member is `{:stream <v2 reader handle>, :cursor <opaque>,
:status s}`. The composition mints the cursor and hands both in:

```clojure
(jing.v2/observer-state [{:stream h1 :cursor c1} {:stream h2 :cursor c2}])
;; => {:members [{:stream h1 :cursor c1 :status :pending} ...] :next 0}
```

`observer-state` no longer takes bare streams and fabricates `{:position 0}`.
It cannot: every valid cursor comes from the stream, and the anchor is the
composition's policy — `:dao.stream/oldest` for a fresh pool, a kept cursor for
a resumed one — not the observer's. A member without a cursor, or whose stream
does not satisfy the reader surface, is a composition defect detected before
any operation runs and throws, which is the one place the contract permits an
exception. `observer-state` accepts exactly the member shape it stores, so
`(observer-state (:members st))` rebuilds a pool from a previous state; that is
how a composition drops a member without touching another member's cursor.

**Cursor discipline.** The observer retains exactly the successor `next`
returns, after `materialize!` succeeds, and never anything else. There is no
arithmetic on a cursor anywhere in `dao.jing.v2`, and no test inspects a
cursor's shape — the conformance harness's own rule. The only other way a
member's cursor changes is `adopt-cursor`:

```clojure
(jing.v2/adopt-cursor state i cursor) ; cursor came from the stream: minted, successor, or gap recovery
```

which is the composition passing back a value the stream handed out.

**Signals are the contract's outcome set, total.** `observe-step!` returns
`{:state s' :signal k ...}` where `k` is one of the seven keywords in
`dao.stream/outcomes-next`, and no other:

| signal                                                   | when                                                                   | extra keys                          | member cursor |
| -------------------------------------------------------- | ---------------------------------------------------------------------- | ----------------------------------- | ------------- |
| `:dao.stream/ok`                                         | a payload was materialized                                             | `:address`                          | successor     |
| `:dao.stream/blocked`                                    | the pool is empty, or every non-ended member is blocked                | —                                   | unchanged     |
| `:dao.stream/end`                                        | every member has ended                                                 | —                                   | unchanged     |
| `:dao.stream/gap`                                        | member `i`'s position was evicted                                      | `:member i`, `:cursor` (recovery)   | unchanged     |
| `:dao.stream/cursor-mismatch`, `/invalid-cursor`, `/transport-error` | member `i`'s read failed                                   | `:member i`, `:result` (the outcome map) | unchanged |

The last four are reported immediately, the member's `:status` records the
outcome, the scheduling index moves past that member, and the member is polled
again on its next turn — and reports again. That is v1's gap rule ("never
auto-resynchronized") applied to every non-`ok`/`blocked`/`end` outcome, one
rule instead of four. The reason it is right for an intake pool: a `gap` on a
content intake means payloads were appended, acknowledged to their publisher,
and never materialized. Materialization is idempotent and replay-safe, so
adopting the recovery cursor is *safe*; whether it is *acceptable* is a question
only the composition can answer, since it knows whether the publisher will
re-append. The observer must not answer it. `cursor-mismatch` and
`invalid-cursor` are composition defects; `transport-error` may be transient.
All three are resolved the same way: the composition adopts a cursor it obtained
from the stream, or rebuilds the pool without that member. A test iterates
`dao.stream/outcomes-next` and asserts every outcome produces a branch, so a
future contract outcome fails the test rather than falling into a default.

**Materialization failure is unchanged.** `materialize!` throwing propagates
and leaves the caller-owned state untouched. The storage backend is not a
stream and is not under the stream contract; its failures stay exceptions, as
`dao.jing.md` §Materialization rule already specifies.

**What changes in `dao.jing.md`, and what stays.** Changes: *The intake pool*
— the entry shape sentence gains "the cursor is minted by the composition from
an anchor of its choosing, and is retained exactly as the stream returns it";
*Cursor tracking and recovery* — the four bare results become the seven
`:dao.stream/…` outcomes with the table above, and the sentence about resync
being the caller's decision is generalized to the three defect outcomes;
*Implemented surface* — names `dao.jing.v2` for the observer and the
stream-free file backend; *Open items* — durable checkpoints gain the sentence
that a checkpoint stores whatever cursor value the transport minted, and that
whether such a value survives serialization is transport-owned and TBD in the
contract. Stays, untouched: *Definition*, *Publication from an agent*,
*Materialization rule*, *Canonical encoding*, *Storage ignorance*, *Physical
intake versus semantic composition*, *Reads*, *Resource lifecycle*, *Lineage*.
Nothing semantic about DaoJing moves; the migration changes how the observer
reads, not what it is.

### Decision 2 — `dao.jing.file`: the durable log was never a stream (option b)

**Decision.** The file backend's durable log is a storage implementation
detail. The migration removes the `dao.stream` coupling; it does not build a
v2 append-log transport, and it does not use one.

**Why, against §Storage ignorance and the passive well.** Four reasons, in
order of weight:

1. **The log has no second reader and no second writer, ever.** Inside
   `create-content-file` the stream is opened, replayed once by its own
   handle, appended by its own `put`, and closed by its own `close`. No
   cursor is handed out, no descriptor is projected, nothing attaches, and
   the log never sits in any pool. Every property the contract exists to
   guarantee — multiple independent cursors, non-destructive reads, portable
   reachability, attachment lifecycle — is unused. A stream with exactly one
   reader who is also its only writer is a file with a protocol wrapped
   around it.
2. **Making the on-disk layout a `dao.stream` transport made DaoJing's storage
   format a public transport type.** `{:dao.stream/type :append-log}` is a
   name any composition may open, so the record framing of a content file
   became something other code could depend on. §Storage ignorance is about
   the well not interpreting what it stores; the symmetric discipline is that
   nothing outside the well interprets *how* it stores. Removing the transport
   restores that: the framing is private to `dao.jing.file`.
3. **A transport with one consumer owes the conformance suite for nobody.**
   Option (a) would have this plan own a v2 append-log, its manifest, its
   exclusion reasons, its inducers for every declared outcome, and its reader
   laws on three hosts — for a transport whose sole consumer never mints a
   second cursor. The stream plan deferred "file, UDP, log" transports until a
   consumer needed them; DaoJing's file backend is not that consumer.
4. **The parallel is `dao.jing.mem`.** The in-memory backend holds its content
   in a private atom behind `:put-content-fn`/`:get-content-fn`; nobody
   proposes that atom should be a stream. The file backend is the same handle
   with the addition of durability. `dao.jing.md` already says "backend
   effects are explicit functions, not a protocol or hidden state"; a hidden
   stream inside the effect is the thing that sentence excludes.

**Against Axiom 1.** "All IO and data flow through append-only streams" governs
flow across boundaries — the intake pool is the stream boundary of DaoJing, and
it is where every payload enters. The write behind `:put-content-fn` is the
backend effect at the far side of interpretation, the KV in Datomic's picture,
not a flow anyone observes. Nothing crosses a host boundary here that a second
party could read; there is no perspective on the log that is not DaoJing's own.

**What it costs.** `dao.jing.file` gains a private framed-file section — open
or create, truncate an incomplete tail, append a length-prefixed record and
sync, replay every record — with a `#?(:cljd … :clj … :cljs …)` branch per host
(`RandomAccessFile`, Node's synchronous `fs`, `dart:io`'s `RandomAccessFile`),
lifted from `dao.stream.log` by copy, never by require. Roughly one hundred
lines that currently live in a namespace slated for deletion with v1. The
on-disk format is kept byte-for-byte — 4-byte big-endian length prefix, UTF-8
`pr-str` of `[address payload]` — so every existing content file stays
readable, and a test proves it against a golden record.

**What it defers.** A v2 durable-log transport, to whichever plan brings its
first real consumer: durable observer checkpoints, or a `dao.space` local
stream that must survive a restart. If and when one exists, `dao.jing.file`
*may* be re-based on it as a private choice; nothing in this plan requires or
forbids that.

**Option (c), considered and rejected:** a v2 file transport kept internal to
`dao.jing.file` — never in a host dispatch table, never with a manifest. A
transport with no manifest and no conformance evidence is worse than no
transport: it wears the contract's interface without owing its laws.

### Decision 3 — `dao.jing.remote`: remote content needs a driver its caller owns

**The honest answer is yes.** A synchronous `get`/`put` over a polling RPC is
not implementable without waiting: on cljs and cljd there is nothing to wait
with, and on the JVM a spin-poll inside `:get-content-fn` would be `take!!`
reintroduced by another name, diverging per host exactly where the contract
was chosen to make hosts agree. So the v2 remote adapter does **not** produce
a content handle. `jing/materialize!` and `jing/get` cannot be called against
a remote store under v2. What replaces them is a client state machine the
caller steps, in the exact shape `yin.repl` already drives.

**Server side — `dao.jing.v2.remote/default-handlers`.** The handler map is
already the v2 shape: `dao.stream.apply/dispatch-request` calls
`(apply handler args)` and wraps the value in a correlated response. The two
ops stay `:jing/put-content` (returns `:inserted` or `:present`) and
`:jing/get-content` (returns the exact presence envelope
`{:found? boolean, :value value}`; local not-found sentinels never cross the
wire). The address is validated against the payload hash before the local
store is touched, as today. A serving composition runs `apply/serve-once!` per
attachment with this map. DaoJing owns the handlers; it does not own an
endpoint — see *Boundary*.

The v1 `default-handlers` is duplicated (about twenty lines), not required:
v1 `dao.jing.remote` requires `dao.stream.rpc.*` on the JVM, and pulling that
into a v2 namespace would make the new namespace v1-dependent. The duplication
ends at Phase J5.

**Client side — a state machine, stepped by the composition.**

```clojure
(jing.v2.remote/client-state rpc-state)      ; wraps dao.stream.rpc client state; no cursor is fabricated
(jing.v2.remote/request-put state address payload) ; => {:outcome k :state s' :id n?}
(jing.v2.remote/request-get state address)         ; => {:outcome k :state s' :id n?}
(jing.v2.remote/step state budget)                 ; => {:state s' :completions [...]}
```

- `request-put` and `request-get` validate the address as `jing/get` does —
  a non-segment address is a defect in the caller and throws before any
  request is allocated — then call `rpc/request!`. Their `:outcome` is the
  RPC layer's: `requested`, `pending-request` (the writer answered `full`;
  the envelope is retained and re-attempted on the next request or step),
  `request-undeliverable`, or `terminal`. One addition: while `rpc/unsent?`
  holds, both return `:dao.jing.v2.remote/busy` and allocate nothing, because
  `rpc/request!` would silently retry the unsent envelope and ignore the new
  operation. A caller must observe `busy` and try again after a step.
- `step` calls `rpc/poll!` with the budget, takes completions and diagnostics
  exactly once, and decodes each completion into DaoJing vocabulary:
  `{:id n :op :jing/put-content :result :inserted}` or `:present`;
  `{:id n :op :jing/get-content :found? true :value v}` or `:found? false`;
  `{:id n :op … :error {code message}}` for a handler error or unknown
  operation; `{:id n :op … :lost reason}` for a request the RPC layer reported
  lost (`gap`, `/detached`, `/ended`, `/not-found`, `/transport-error`, or an
  append that could not be delivered). A response whose value is not the exact
  presence envelope, or not one of `:inserted`/`:present`, is a malformed
  response and completes as `:error` with a `dao.jing.v2.remote/malformed-response`
  code — the v1 rule that presence envelopes are exact, kept, but as data
  rather than an exception, because it now surfaces inside a driver step.
- Nothing here loops on `blocked`, holds a clock, retries on its own, or
  touches a socket. Timeouts, reattachment (`rpc.ws/rebind` on `/detached`),
  cadence, and what to do with a lost request are the driver's.
- The namespace is pure `.cljc` with no host branch at all. The v1 adapter's
  network client was JVM-only; the v2 client runs on all three hosts by
  construction. That widening is the concrete gain of the polling shape.

**The `:dao.jing/remote` coordinate.** `dao.jing.coordinate/open!` turns
`{:dao.jing/type :dao.jing/remote :url …}` into a synchronous handle that
`dao.space.index`'s published-index `defopen` then reads through `jing/get` to
restore B-tree nodes. Under v2 that coordinate cannot yield a handle; it would
yield a client state plus an `attach!` result plus a driver obligation — a
composition, not a value. This plan leaves `dao.jing.coordinate` and its
`:dao.jing/remote` branch **untouched on v1**. The v2 remote client has no
coordinate here. Reading remote content on the query side is the async
hydration `dao.data.btree.md` §5.4 defers "until an async DaoJing backend
exists" — this client is that backend, and the hydration and the coordinate
that opens into it belong to `dao.space`'s migration plan, whose index
`defopen` is the only consumer.

### Decision 4 — Namespace layout, in one table

| namespace               | this plan                                          | v1 dependency after this plan | consumer that keeps v1 alive                          |
| ----------------------- | -------------------------------------------------- | ----------------------------- | ----------------------------------------------------- |
| `dao.jing`              | untouched; v1 observer stays until J5              | `dao.stream` (observer only)  | `dao.space.*` tests via `observe-step!` on v1 intakes |
| `dao.jing.v2`           | **new** — the observer on v2                       | none                          | —                                                     |
| `dao.jing.file`         | **in-place**: stream coupling removed              | none                          | —                                                     |
| `dao.jing.mem`          | untouched                                          | none                          | —                                                     |
| `dao.jing.remote`       | untouched                                          | `dao.stream.rpc.*` (clj)      | `dao.jing.coordinate`, `dao.space` tests              |
| `dao.jing.v2.remote`    | **new** — handlers + client state machine          | none                          | —                                                     |
| `dao.jing.coordinate`   | untouched                                          | via `dao.jing.remote` (clj)   | `dao.space.index`                                     |
| `dao.jing.dht*`         | untouched                                          | `dao.stream.transit`, UDP     | out of scope (see *Boundary*)                         |

**No registry, restated.** v1's `defopen`/`open!` is gone in v2 and nothing in
`dao.jing*` reinvents it. `dao.jing.coordinate/open!` is already the precedent:
a closed `case` over coordinate types, an explicit code change to add one. The
v2 observer takes handles; the v2 remote takes an RPC state built from handles
the composition attached; neither looks anything up by name.

**Reader-conditional discipline.** `dao.jing.file` keeps three-branch
`#?(:cljd … :clj … :cljs …)` forms with `:cljd` first, as it does today.
`dao.jing.v2` and `dao.jing.v2.remote` contain no host branch. No new file may
contain a bare `#?(:clj …)` form — `#?(:clj …)` does not exclude code from the
cljd build; `#?(:cljd nil :clj …)` with `:cljd` first is the only safe spelling,
and `:cljd` in tail position silently fails. Full cljd namespace compilation
gates every phase.

## Divergence register

Every place the v2 pieces deliberately differ from v1, with the reason.

| v1 behavior                                                                                      | v2                                                                                                   | why                                                                                                   |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `observer-state` takes streams; cursor is `{:position 0}`                                        | takes `{:stream :cursor}` members; the composition mints the cursor                                  | every valid cursor comes from the stream; the anchor is the composition's policy                       |
| signals `:ok`, `:blocked`, `:end`, `:daostream/gap`; malformed results throw                     | the seven `:dao.stream/…` outcomes; three defect outcomes reported as data with `:member` and `:result` | the result convention; an outcome outside the closed set still throws, as a transport defect         |
| a gap is reported, cursor unchanged, never resynced                                              | same rule, extended to `cursor-mismatch`, `invalid-cursor`, `transport-error`; `adopt-cursor` is the resync | one rule for every non-progress outcome; resync is a composition decision                         |
| `dao.jing.file` opens `{:dao.stream/type :append-log}`; handle carries `:log`                    | private framed file; handle carries `:path :state :write-lock` and the three fns, no `:log`          | Decision 2                                                                                            |
| `file_test` counts records with `ds/next` over the raw log                                       | `dao.jing.file/records` returns the decoded records of a path; tests use it                          | the framing is private; the test reads through the backend's own reader                              |
| `content-client` is a handle; `:get-content-fn` returns the value                                | `client-state` / `request-*` / `step`; results are completions correlated by id                       | no operation waits; unimplementable otherwise on cljs/cljd                                            |
| `connect-content!` — synchronous, JVM-only                                                       | gone; the composition attaches through `dao.stream.ws` and builds the client from the whole `attach!` result | the constructor waited; attachment outcome arrives as deposited data                         |
| malformed presence envelope throws inside `get`                                                  | completes as an `:error` with a malformed-response code                                              | it surfaces inside a driver step, where an exception has nowhere to go                                |
| `close-fn` guards concurrent closes with a lock and retries on failure                           | gone with the handle; `close!` on the ws handle is idempotent and answers `ok`                       | the client holds no host resource; the handle does                                                    |
| `:dao.jing/remote` coordinate opens to a handle                                                  | unchanged on v1; no v2 coordinate in this plan                                                       | it would open into a composition, which is `dao.space`'s to design with hydration                     |

**v1-only tests deliberately not mirrored:** `file_test`'s direct
`{:dao.stream/type :append-log}` opens and `count-records` (replaced by
`records`); `remote_test`'s `network-invalid-url-test` (a bad URL is
`invalid-descriptor` or a deposited `/transport-error`, tested in Phase J3b as
data), `close-failure-retry-test` (no close-fn to fail), and every
`with-server` case built on `rpc-ws/start!` (replaced by the J3 compositions).

## Phase J0 — What exists

Recorded so the phases start from evidence.

- `dao.stream` (protocols, outcome sets, `validate-outcome`), the ring
  buffer with `make-attacher`, `dao.stream.apply` (envelope, `serve-once!`),
  `dao.stream.rpc` (`client-state`, `request!`, `poll!`, `take-completed`,
  `take-diagnostics`, `unsent?`, `rebind`), `dao.stream.rpc.ws`
  (`decoder`, `init-client`, `rebind`), `dao.stream.ws` with host
  `connect!` seams on clj, cljs and cljd, `dao.stream.serving`, and the
  conformance harness. All green per the user on this revision.
- `dao.jing` core and `dao.jing.mem` are stream-free.
- `test/dao/jing_test.cljc` observer cases (lines 322–561) are the behavioral
  evidence for J2; `test/dao/jing/file_test.cljc` for J1;
  `test/dao/jing/remote_test.cljc` for J3; `test/dao/stream/rpc_test.cljc`
  is the shape precedent for the socket-free J3 tests.

## Phase J1 — `dao.jing.file` without a stream

- Replace the five `ds/*` sites with a private framed-file section:
  `open-frames!` (create parent dirs, open read/write, truncate an incomplete
  tail exactly as `dao.stream.log/make-log-stream` does), `append-frame!`
  (length prefix + bytes at the current end, then sync), `replay-frames`
  (every complete record from offset 0), `close-frames!`. Three host branches,
  `:cljd` first.
- `put` is unchanged in order: validate, lock, closed check, presence check,
  append and sync, then swap the content map, then `:inserted`. `close!` stays
  idempotent. Both drop `dao.stream` and `dao.stream.log` from the requires.
- Public `records`: `(records path)` returns the decoded, validated
  `[address payload]` vector of a file, for tests and diagnostics; it opens,
  replays and closes.
- **On-disk compatibility test.** A golden byte sequence for one known record
  is written to a temp path by hand; `create-content-file` must read it and
  `records` must return it. This is what proves the framing did not drift.
- Port `file_test`: `handle-shape-test` asserts no `:log` and no `:stream`;
  every `count-records` becomes `(count (records path))`; the corrupt-record
  and raw-duplicate cases write their raw frames through `append-frame!`.
  `observer-convergence-test` moves to J2 (it needs the v2 observer).

Deliverable: `dao.jing.file` requires no `dao.stream*` namespace;
`file_test` green on clj, cljs (Node) and cljd with `dao.stream` absent from
its requires. Consumers (`dao.space.*`, `dao.data.btree_durability_test`) pass
unchanged, confirmed by running them rather than by inspection.

## Phase J2 — `dao.jing.v2`, the observer

`src/cljc/dao/jing/v2.cljc`: `observer-state`, `observe-step!`, `adopt-cursor`,
per *Decision 1*. Requires `dao.jing` and `dao.stream` only.

Tests, `test/dao/jing/v2_test.cljc`, over v2 ring buffers created with
`ringbuffer/create!` and cursors minted with `stream/cursor … :dao.stream/oldest`:

- every observer case in `jing_test.cljc` lines 322–561, ported: plain-data
  state, empty pool blocked, all blocked, hashing and retrieval, equal
  payloads from two streams converge, blocked/ended members do not starve
  others, all ended, drains then end, fair round-robin, strict interleave,
  cursor advances only after successful materialization. Cursor assertions
  become successor-equality assertions (`(= (:dao.stream/cursor res) …)`)
  never position arithmetic.
- **gap**: a capacity-2 ring buffer with three appends; the signal is
  `:dao.stream/gap` with `:member 0` and a `:cursor`; the member cursor is
  unchanged; the other member still progresses; the gap reports again on the
  next turn; after `adopt-cursor` with the recovery cursor the member
  materializes the live value.
- **defect outcomes**: a reified reader handle scripted to answer
  `cursor-mismatch`, `invalid-cursor`, `transport-error` (and, for the
  totality test, each outcome in `dao.stream/outcomes-next`); assert the
  signal, `:member`, `:result`, unchanged cursor, and that an outcome outside
  the declared set throws.
- **members round-trip**: `(observer-state (remove-nth (:members st) i))`
  keeps the remaining members' cursors.
- `observer-convergence-test` from `file_test`, over the J1 file backend.

Deliverable: `dao.jing.v2` on clj, cljs (Node) and cljd; `Testing
dao.jing.v2-test` confirmed in the Node output; no namespace under
`dao.jing.v2*` requires `dao.stream`.

## Phase J3 — `dao.jing.v2.remote`

**J3a — socket-free, all three hosts.** `src/cljc/dao/jing/v2/remote.cljc`
per *Decision 3*. Tests, `test/dao/jing/v2/remote_test.cljc`, mirror
`rpc_test.cljc`: request and response media are two ring buffers; the server
side is `apply/server-state` + `apply/serve-once!` over `default-handlers`
on a `dao.jing.mem` store; the client is `rpc/client-state` over the same
media, wrapped by `client-state`.

- put then get round-trips through `step`; duplicate put completes `:present`;
  absent get completes `:found? false`; a stored `nil` and a stored
  `{:found? true :value "hello"}` round-trip exactly (the envelope-shaped
  payload case from v1's `network-presence-envelope-test`).
- `request-*` on a non-segment address throws before allocation; a handler
  rejecting a hash-mismatched address completes as `:error`.
- a scripted writer answering `full` once: `pending-request`, then `busy` on a
  second request, then `requested` after a step; exactly one id allocated.
- gap on the response medium and `/detached` complete every outstanding
  request as `:lost`; malformed responses complete as `:error` with the
  malformed-response code and never as `:found?`.
- completions publish exactly once; `:completed` empty after `step`.
- `default-handlers` on a handle without `:put-content-fn` throws at
  construction.

**J3b — over `dao.stream.ws`, same process, all three hosts.** A serving
composition assembled in the test from `dao.stream.serving` and the host
`connect!`/`bind!` seams, exactly as `test/dao/stream/ws_test.cljc` and
`serving_test.cljc` do; the served stream is a capacity-1 identity anchor as
the REPL's D3 settled. The client mints its `:dao.stream/newest` cursor on
its traffic medium *before* `attach!`, builds the RPC state with
`rpc.ws/init-client` from the whole `attach!` result, and wraps it with
`client-state`. Prove: put and get round-trip over the wire; two clients see
each other's writes; the file backend survives a stop, restart and reattach
(v1's `network-file-restart-test`); killing the connection completes the
outstanding request as `:lost` with `/detached` and `rebind` after a fresh
`attach!` resumes with the same cursor; a descriptor naming a path nothing
serves completes as `:lost` with `/not-found`.

Deliverable: J3a green on clj, cljs (Node) and cljd; J3b green on clj and cljs
(Node), and on cljd through the cljd ws lane. The cljd test regenerates
`test/cljd-out/`; one process owns it at a time.

## Phase J4 — Design prose

`dao.jing.md`, exactly the sections *Decision 1* names, plus: *Implemented
surface* describes `dao.jing.file` as "a content-addressed store backed by a
private framed append-only file" and `dao.jing.v2.remote` as handlers plus a
client state machine the composition drives, and states that `jing/materialize!`
and `jing/get` are local operations that do not apply to a remote store. The
*Reads* paragraph that says a reader "may access the target … through a remote
transport (`dao.jing.remote`, `dao.jing.dht`)" gains "as a request whose answer
arrives as a completion". `dao.jing.dht.md` is not edited.

The v1 observer's docstrings in `dao.jing` are not edited; they go with it.

## Phase J5 — Deletion and the naming decision

Gated on `dao.space`'s migration: it is the last consumer of the v1 observer
(through its tests) and of v1 `dao.jing.remote` (through `dao.jing.coordinate`
and its tests). When that gate opens:

- delete `observer-state` and `observe-step!` from `dao.jing` and its
  `dao.stream` require; delete the observer section of `jing_test.cljc`;
- delete `src/cljc/dao/jing/remote.cljc` and `test/dao/jing/remote_test.cljc`;
  `dao.jing.coordinate`'s `:dao.jing/remote` branch is deleted or re-pointed
  under `dao.space`'s plan, not here;
- take **one explicit decision**, mirroring the stream plan's end condition.
  The recommendation is to **fold back**: move the v2 observer into `dao.jing`
  and rename `dao.jing.v2.remote` to `dao.jing.remote`, in the same change as
  `dao.stream`'s rename if that is the decision there. DaoJing's namespace
  names appear on no wire and in no coordinate — coordinate types are
  keywords like `:dao.jing/file`, unaffected — so only requires change. An
  undecided coexistence is a defect of the migration, not a steady state.

## Host matrix

| phase | clj | cljs (Node) | cljd | notes                                                                                     |
| ----- | --- | ----------- | ---- | ----------------------------------------------------------------------------------------- |
| J1    | ✓   | ✓           | ✓    | three host branches in `dao.jing.file`, `:cljd` first; Node uses synchronous `fs`         |
| J2    | ✓   | ✓           | ✓    | pure `.cljc`, no host branch                                                              |
| J3a   | ✓   | ✓           | ✓    | pure `.cljc`, no host branch                                                              |
| J3b   | ✓   | ✓           | ✓    | composition uses the per-host `connect!` seam; cljd through the cljd ws lane              |
| J4    | —   | —           | —    | prose                                                                                     |
| J5    | ✓   | ✓           | ✓    | deletions; confirm every `dao.jing*` test namespace still appears in each host's output   |

Per the standing rule, confirm `Testing dao.jing.v2-test` and `Testing
dao.jing.v2.remote-test` appear in the Node output rather than assuming
discovery.

## Invariants, checked

- **No hidden global state.** `dao.jing.v2` holds no atom. `dao.jing.file`'s
  state atom and lock are per-handle and explicit, as `dao.jing.mem`'s are.
  No `defopen`, no dispatch table, no `defonce`.
- **No implicit control flow.** `observe-step!` and `step` return; the
  composition calls again. Nothing self-schedules.
- **No callbacks.** None in any new public surface. The ws adapter map is the
  transport's, confined to J3b's composition.
- **No shared mutable state.** A content handle is owned by whoever created
  it; the RPC state is a value threaded by one driver.
- **Interpretation and execution stay separate.** The observer interprets the
  pool; the backend executes the write; the remote client interprets
  completions; the serving composition executes the socket.
- **No assumed graphs.** Nothing here relates content addresses to each other.

## Boundary of this plan

**Untouched:** `dao.jing` core and its v1 observer until J5; `dao.jing.mem`;
`dao.jing.remote` until J5; `dao.jing.coordinate`; `dao.jing.dht`,
`dao.jing.dht.kad`, `dao.jing.dht.node`; every `dao.space*` and
`dao.data.btree*` namespace and test; `dao.stream` v1 in every form.

**Architectural decisions**, made here: the durable log is not a stream
(Decision 2); remote content is a driven state machine, not a handle
(Decision 3); the observer's cursor and non-progress discipline (Decision 1);
parallel-versus-in-place by whether a v1 consumer remains (Decision 4).

**Implementation gaps**, left open knowingly: the v2 remote client has no
coordinate and cannot be reached from `dao.jing.coordinate/open!`; the
serving side exists only as test compositions.

**Intentionally deferred**, each to a named home:

- `dao.jing.dht` and `dao.jing.dht.node` — UDP transport, deferred by the
  stream plan; `dht.cljc` itself requires no `dao.stream`, and `node.cljc`'s
  `dao.stream.transit` require goes with the UDP transport's own migration.
- `dao.space`'s own migration: `publish-index!`'s v1 `ds/append!` into the
  intake pool, `snapshot-datoms`' `{:position 0}` walk, the published-index
  `defopen`, `DaoStreamLog`'s writer face, and switching its tests to
  `dao.jing.v2`.
- The `:dao.jing/remote` coordinate under v2 and async B-tree hydration
  (`dao.data.btree.md` §5.4) — `dao.space`'s query-side plan; this client is
  the "async DaoJing backend" that section waits for.
- A content-serving endpoint as production infrastructure — the composition
  that starts, serves and stops a content server belongs to whatever product
  surface needs it, as the REPL's R4 did for the REPL.
- Canonical encoding; durable observer checkpoints and a long-running runner;
  explicit materialization acknowledgement; garbage collection — the four
  open items of `dao.jing.md`, unchanged by this plan. Checkpoints gain only
  the shape constraint that the persisted value is the transport's own cursor,
  whose serializability the contract leaves TBD.
- A v2 file or append-log transport — until a consumer needs cursors over a
  durable file.

## End condition

Complete when all of the following hold on clj, cljs (Node) and cljd:

- `dao.jing.file` requires no `dao.stream*` namespace, reads every content
  file v1 wrote, and its suite runs with no stream fixture.
- `dao.jing.v2` observes v2 reader handles with stream-minted cursors, is
  total over `dao.stream/outcomes-next` by a declaration-driven test,
  retains only successors and adopted cursors, and requires only `dao.jing`
  and `dao.stream`.
- `dao.jing.v2.remote` round-trips put and get through `dao.stream.apply`
  and `dao.stream.rpc` over ring buffers on all three hosts and over
  `dao.stream.ws` on each, completing lost requests as data.
- `dao.jing.md` describes the v2 observer, the stream-free file backend and
  the driven remote client.
- No namespace under `dao.jing.v2*` requires `dao.stream` or
  `dao.stream.rpc.*`.
- Once `dao.space` has migrated: the v1 observer and v1 `dao.jing.remote` are
  deleted, and the naming decision has been taken explicitly.

---

## Decisions most likely to be contested in review

1. **Decision 2 (b)** — removing the stream from the file backend rather than
   building a v2 log transport. The counter-argument is Axiom 1 read
   literally; the answer is that the log has one reader who is its only
   writer, and that `dao.jing.mem` already sets the precedent.
2. **Keeping the on-disk framing** byte-for-byte instead of taking the chance
   to change it. Chosen for compatibility with files that exist; the format
   is now private, so it can change later without a transport version.
3. **A `dao.jing.v2` that contains only the observer**, rather than a
   wholesale parallel namespace like the four prior consumers. Chosen to
   avoid two address-minting paths.
4. **Rewriting `dao.jing.file` in place** while every prior plan promised "no
   existing implementation is modified". Chosen because there is nothing to be
   beside and its consumers' surface does not change; the risk is confined to
   its own suite plus the `dao.space` and btree suites, which J1 runs.
5. **Non-progress outcomes are re-reported every turn** and never resolved by
   the observer, including `transport-error`. A reviewer may prefer
   auto-adopting the gap cursor since materialization is idempotent; the plan
   holds that acceptability of loss is the composition's call.
6. **`busy` on `request-*` while an envelope is unsent**, rather than queuing
   inside the client. Chosen so the client owns no queue and the driver
   decides; the REPL's driver already works this way.
7. **Leaving `dao.jing.coordinate`'s `:dao.jing/remote` on v1** and deferring
   the v2 remote coordinate to `dao.space`'s plan. The alternative — a
   coordinate that opens into a composition — would have this plan designing
   the query side's hydration.

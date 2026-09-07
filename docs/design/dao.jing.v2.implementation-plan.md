# dao.jing on DaoStream v2 — the content-observer slice

Status: migration plan, revision 5, derived from and subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract), [`dao.jing.md`](./dao.jing.md)
(the storage-observer design) and [`datom.world.md`](./datom.world.md). Its
transport prerequisite is
[`dao.stream.v2.implementation-plan.md`](./dao.stream.v2.implementation-plan.md),
Phases 1–5, implemented on clj, cljs (Node) and cljd. It follows the shape of
the `dao.runtime.v2` and `yin.repl.v2` plans and is the fifth consumer
migration. This document is transient: it is consumed as its phases complete.
Drafted 2026-09-06; revised the same day against the `gpt-5.6-sol` routine
review, the `glm-5.3` adversarial review, and a two-round consensus with
`gpt-6-astra`; revised on 2026-09-07 after both reviewers confirmed the prior
findings addressed, again after `glm-5.3`'s delta review closed the verify
hop's loss path, and finally after the `allocator-error` disposition, ruled
the same way by both architects. No phase has started.

Two decisions in this document are **scope-contingent**: they are the plan's
position and are taken when implementation is authorized, each with its named
alternative. Neither blocks any phase. They are marked where they occur (J1,
under C2 and N1) and collected in *Boundary of this plan*.

## Context

`dao.jing` is next because `dao.space.index` and `dao.space.schema` require it,
and `dao.space` on the v2 contract is what ends ADR-0003's time-boxed
exception. The v1 surface inside `dao.jing*` is small — one `ds/next` in the
observer walk (`jing.cljc:391`) and five sites in the file backend
(`jing/file.cljc:83,149,175,190,207`) — with no `closed?`, `strict-vec`,
`drain-one!` or `take!!` anywhere. A mechanical port is not what makes it hard.
Two of the three stream-touching pieces have no v2 counterpart to port *to*:
there is no v2 append-log transport, and there is no v2 RPC that returns a
value synchronously. Each is a boundary decision, settled below before any
phase runs.

One fact that shaped the revision: the v1 observer (`dao.jing/observer-state`,
`observe-step!`) has **no consumer under `src/`**. Every caller is a test. The
`dao.stream` require at `jing.cljc:19` exists solely for `ds/next` at
`jing.cljc:391`, which only tests reach.

## The problem, by piece

| piece                                                    | v1 mechanism                                                                                               | v2 counterpart                                                            |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `dao.jing/observer-state`, `observe-step!`               | `ds/next` on any v1 reader; inline `{:position 0}` cursors; bare `:ok`/`:blocked`/`:end`/`:daostream/gap`  | reader surface: `cursor`, `next`; opaque cursors; outcome maps            |
| `dao.jing.file/create-content-file`                      | `ds/open!` of `{:dao.stream/type :append-log}`; `ds/next` to replay; `ds/append!` + fsync; `ds/close!`     | **none** — the stream plan defers file/log transports                     |
| `dao.jing.remote/connect-content!`, `content-client`     | `dao.stream.rpc.client/call!` — synchronous on the JVM, Promise/Future elsewhere; JVM-only constructor     | `dao.stream.v2.rpc` — poll-shaped, state-machine-valued, all three hosts  |
| `dao.jing.remote/default-handlers`                       | handler map `{op (fn [& args] value)}`                                                                     | same shape; `dao.stream.v2.apply/dispatch-request` applies it unchanged   |
| `dao.jing.mem`, `dao.jing.coordinate`, hashing, `materialize!`, `get`, `close!` | stream-free                                                                         | unchanged                                                                 |

Consumers outside `dao.jing*` that reach these pieces, named so the boundary is
visible. Nine `src/` namespaces require `dao.jing` — `dao.data.btree.storage`,
`dao.space.index`, `dao.space.schema`, `dao.jing.{file,mem,remote,dht,dht.node}`
and `src/dev/psset_fixtures.clj` — **none for the observer**. `dao.space.index`
additionally reaches `jing-coordinate/open!` inside its `defopen` and appends
to the intake pool with v1 `ds/append!`. **Nine test files** call the v1
observer: five under `test/dao/space/` (`transactor`, `stigmergy`, `index`,
`schema`, `query`), `test/dao/jing/mem_test.cljc:213`,
`test/dao/jing/dht_test.cljc:402`, `test/dao/jing/file_test.cljc:113-135`
(`observer-convergence-test`), and `test/dao/jing_test.cljc`'s own observer
section (lines 322–560). `test/dao/space/` also opens remote clients through
`connect-content!`. No production consumer is migrated here.

## Strategy

The same two narrowings as the sibling plans, with one deliberate difference in
where the v2 namespace boundary falls.

1. **Parallel where a v1 consumer still needs the v1 shape; in place where
   nothing is being replaced.** The four prior consumers were rebuilt wholesale
   beside their v1 twins because their entire surface was stream-shaped. Most
   of `dao.jing` is not: content addressing, `materialize!`, `get`, `close!`
   and `dao.jing.mem` never touch a stream. Duplicating them would create a
   second minting path for content addresses — two sources of truth for
   identity, which is worse than an unusual namespace layout. So:
   - `dao.jing` becomes the **stream-free core** in J1, by moving the v1
     observer out rather than by moving the core out (Decision 4, C2).
   - The v1 observer moves, unchanged, to **`dao.jing.observer`** (v1), and
     stays until `dao.space`'s tests migrate.
   - The v2 observer is **`dao.jing.v2.observer`**, requiring `dao.jing` for
     `materialize!` and `dao.stream.v2` for the reader surface.
   - The remote adapter is **`dao.jing.v2.remote`**, beside the untouched v1
     `dao.jing.remote`, which `dao.jing.coordinate` and the `dao.space` tests
     keep using until their own plan.
   - `dao.jing.file` is rewritten **in place**: its migration is a removal of a
     coupling, not a port (Decision 2). Its consumers use only
     `create-content-file` and the handle API, which do not change.
2. **The content-observer slice, and nothing else.** One observer, one durable
   backend, one remote client with its handlers and a stepped materializer.
   Not the canonical encoding, not durable checkpoints, not a content-serving
   endpoint as production infrastructure, not `dao.space`'s intake appends,
   not the DHT, not the content write path as an effect stream.

No compatibility facade, alias, or dual protocol. The v1 observer and v1
remote are evidence about behavior, not constraints on the v2 shape.

## Decisions

Settled here so no phase decides them alone.

### Decision 1 — The observer: entry shape, cursor discipline, gap

**Entry shape.** A pool member is `{:stream <v2 reader handle>, :cursor <opaque>,
:status s}`. The composition mints the cursor and hands both in:

```clojure
(observer/observer-state [{:stream h1 :cursor c1} {:stream h2 :cursor c2}])
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
arithmetic on a cursor anywhere in `dao.jing.v2.observer`, and no test inspects
a cursor's shape — the conformance harness's own rule. The only other way a
member's cursor changes is `adopt-cursor`:

```clojure
(observer/adopt-cursor state i cursor) ; cursor came from the stream: minted, successor, or gap recovery
```

which is the composition passing back a value the stream handed out.

**Signals are the contract's outcome set, total.** `observe-step!` returns
`{:state s' :signal k ...}` where `k` is one of the seven keywords in
`dao.stream.v2/outcomes-next`, and no other:

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
rule instead of four. A `gap` on a content intake means payloads were appended,
acknowledged to their publisher, and never materialized. Materialization is
idempotent and replay-safe, so adopting the recovery cursor is *safe*; whether
it is *acceptable* only the composition can answer, since it knows whether the
publisher will re-append. The observer must not answer it. `cursor-mismatch`
and `invalid-cursor` are composition defects; `transport-error` may be
transient. All three are resolved the same way: the composition adopts a cursor
it obtained from the stream, or rebuilds the pool without that member. A test
iterates `dao.stream.v2/outcomes-next` and asserts every outcome produces a
branch, so a future contract outcome fails the test rather than falling into a
default.

**Materialization failure is unchanged.** `materialize!` throwing propagates
and leaves the caller-owned state untouched. The storage backend is not a
stream and is not under the stream contract; its failures stay exceptions, as
`dao.jing.md` §Materialization rule already specifies.

**What changes in `dao.jing.md`, and what stays.** Changes: *The intake pool*
— the entry-shape sentence gains "the cursor is minted by the composition from
an anchor of its choosing, and is retained exactly as the stream returns it";
*Cursor tracking and recovery* — the four bare results become the seven
`:dao.stream/…` outcomes with the table above, and the resync-is-the-caller's
sentence is generalized to the three defect outcomes; *Implemented surface* —
names `dao.jing.observer` and the stream-free file backend (see Decision 2 for
the design-document change that entails); *Open items* — durable checkpoints
gain the sentence that a checkpoint stores whatever cursor value the transport
minted, and that whether such a value survives serialization is transport-owned
and TBD in the contract. Stays, untouched: *Definition*, *Publication from an
agent*, *Materialization rule*, *Canonical encoding*, *Storage ignorance*,
*Physical intake versus semantic composition*, *Reads* (one clause added, see
J4), *Resource lifecycle*, *Lineage*. Nothing semantic about DaoJing moves; the
migration changes how the observer reads, not what it is.

### Decision 2 — `dao.jing.file`: the durable log was never a stream (option b)

**Decision.** The file backend's durable log is a storage implementation
detail. The migration removes the `dao.stream` coupling; it does not build a
v2 append-log transport, and it does not use one.

**This amends a design document.** `dao.jing.md:297-299` currently describes
the file backend as "a content-addressed store backed by an append-only log
stream." Decision 2 changes that description, and J4 rewrites it. That is a
design-document change routed and reviewed as such, not an incidental edit
made in passing while updating the implemented-surface list. The superior
documents `datom.world.md` and `dao.stream.md` are not amended.

**Why, against §Storage ignorance and the passive well.** Four reasons, in
order of weight:

1. **The log has no second reader and no second writer, ever.** Inside
   `create-content-file` the stream is opened, replayed once by its own
   handle, appended by its own `put`, and closed by its own `close`. No
   cursor is handed out, no descriptor is projected, nothing attaches, and
   the log never sits in any pool. Every property the contract exists to
   guarantee — independent cursors, non-destructive reads, portable
   reachability, attachment lifecycle — is unused.
2. **Making the on-disk layout a `dao.stream` transport made DaoJing's storage
   format a public transport type.** `{:dao.stream/type :append-log}` is a
   name any composition may open, so the record framing of a content file
   became something other code could depend on. Removing the transport makes
   the framing private to `dao.jing.file`.
3. **A transport with one consumer owes the conformance suite for nobody.**
   Option (a) would have this plan own a v2 append-log, its manifest, its
   exclusion reasons, its inducers, and its reader laws on three hosts — for a
   transport whose sole consumer never mints a second cursor.
4. **The parallel is `dao.jing.mem`.** The in-memory backend holds its content
   in a private atom behind `:put-content-fn`/`:get-content-fn`; the file
   backend is the same handle with durability added. `dao.jing.md:278-282`
   already says "backend effects are explicit functions, not a protocol or
   hidden state."

**Why option (a) is not a like-for-like alternative.** A conforming v2
append-log **is** constructible: `append!`'s `:dao.stream/ok` means the value
is accepted at the next position and "implies neither local readability nor
remote delivery" (`dao.stream.md`, Writing), so a transport may accept a
synchronous file write and answer `ok` without claiming anything about
durability. What such a transport cannot do is give `dao.jing.file` what it
needs from it: the file backend acknowledges `:inserted` only after the log is
flushed, and `materialize!` returns the address only on that acknowledgement —
`:inserted` means "durably stored now" (`jing.cljc:255`). An `ok` that
disclaims durability cannot honestly be turned into an `:inserted` that
asserts it. Either the transport grows a transport-owned durability signal
that is not a DaoStream operation and that contract-generic code may not reach
for (`dao.stream.md`, Surfaces), or `materialize!`'s contract changes so that
durability arrives later as data. So option (a)'s cost lands on
`materialize!`'s contract and its synchronous consumers — `dao.data.btree.storage`
and `dao.space.index` — not on the transport's constructibility. It is the
write-path redesign in disguise, mis-costed as transport work.

**The pre-existing question, recorded and routed.** `datom.world.md:66-68`
says: "An adapter that exposes a function for portable code to call is not an
interpreter. It isolates the host library but keeps the coupling, and the
effect never appears as an emission." `:put-content-fn` is such a function,
today, on v1, with the log stream beneath it. No caller of `materialize!` or
`get` ever reaches that stream; the handle's `:log` key (`file.cljc:197`) was
test-visible — `file_test.cljc:131` reads it — and J1 removes it. Exposing the
key never made the put an emission: the effect was performed inside the
synchronous function either way. A v2 append-log beneath the same function
changes nothing about that. So whether DaoJing's synchronous content handle
conforms to Host Boundaries is a real question that **this plan does not
create and no transport can cure**. J4 records it in `dao.jing.md`'s open
items by name, **"the content write path as an effect stream"**, with its
consequence stated: durability would arrive as data, and `materialize!`'s
return value would change. That redesign is **out of scope for this plan**
because it reaches `dao.data.btree.storage` and `dao.space.index`, both of
which call `materialize!` synchronously, and the brief's boundary is
`dao.jing*`. Decision 2 removes an unused internal transport; it does not
claim to solve Host Boundaries. It is compatible with the future design:
`datom.world.md:57-59` places raw host operations inside the host interpreter
that consumes the effect stream, which is exactly where option (b) leaves the
file IO.

**What it costs.** `dao.jing.file` gains a private framed-file section — open
or create, truncate an incomplete tail, append a length-prefixed record and
sync, replay every record — with a `#?(:cljd … :clj … :cljs …)` branch per host
(`RandomAccessFile`, Node's synchronous `fs`, `dart:io`'s `RandomAccessFile`),
lifted from `dao.stream.log` by copy, never by require. Roughly one hundred
lines. The on-disk format is kept byte-for-byte — 4-byte big-endian length
prefix, UTF-8 `pr-str` of `[address payload]` — so every existing content file
stays readable, proven by a golden record **and** by the ported torn-tail
recovery cases (J1). The copy creates no second owner of the truncation logic
because J1 retires `dao.stream.log` in the same phase (N1).

**What it defers.** A v2 durable-log transport, to whichever plan brings its
first real consumer: durable observer checkpoints, or a `dao.space` local
stream that must survive a restart. The content write path as an effect
stream, to a DaoJing architecture item of its own.

**Option (c), considered and rejected:** a v2 file transport kept internal to
`dao.jing.file` — never in a host dispatch table, never with a manifest. A
transport with no manifest and no conformance evidence wears the contract's
interface without owing its laws.

### Decision 3 — `dao.jing.remote`: remote content needs a driver its caller owns

**The honest answer is yes.** A synchronous `get`/`put` over a polling RPC is
not implementable without waiting: on cljs and cljd there is nothing to wait
with, and on the JVM a spin-poll inside `:get-content-fn` would be `take!!`
reintroduced by another name. So the v2 remote adapter does **not** produce a
content handle. `jing/materialize!` and `jing/get` are local operations and
cannot be called against a remote store under v2. What replaces them is a
client state machine the caller steps, in the shape `yin.repl.v2` already
drives — plus a stepped materializer over it, because a remote client that can
put but cannot materialize is a raw adapter to a KV, not a DaoJing backend.

**Two layers, kept distinct.** The local backend interface receives an address
and returns a verdict (`jing.cljc:277`); `materialize!` supplies the integrity
guarantee above it — address derivation, and on `:present` a read-back that
must equal the payload (`jing.cljc:281-293`). The remote client mirrors that
layering: J3a is the backend primitives, J3c is the materializer.

**Server side — `dao.jing.v2.remote/default-handlers`.** The handler map is
already the v2 shape: `dao.stream.v2.apply/dispatch-request` calls
`(apply handler args)` and wraps the value in a correlated response. The two
ops stay `:jing/put-content` (returns `:inserted` or `:present`) and
`:jing/get-content` (returns the exact presence envelope
`{:found? boolean, :value value}`; local not-found sentinels never cross the
wire). The address is validated against the payload hash before the local
store is touched, as today. A serving composition runs `apply/serve-once!` per
attachment with this map. DaoJing owns the handlers; it does not own an
endpoint. The v1 `default-handlers` is duplicated (about twenty lines), not
required: v1 `dao.jing.remote` requires `dao.stream.rpc.*` on the JVM. The
duplication ends at J5.

**Client side, J3a — backend primitives, stepped by the composition.**

```clojure
(remote/client-state rpc-state)                ; wraps dao.stream.v2.rpc client state; no cursor is fabricated
(remote/request-put state address payload)     ; => {:outcome k :state s' :id n?}
(remote/request-get state address)             ; => {:outcome k :state s' :id n?}
(remote/step state budget)                     ; => {:state s' :attempt k? :completions [...] :diagnostics [...]}
(remote/abandon state reason)                  ; => s'
```

- `request-put` and `request-get` validate the address as `jing/get` does —
  a non-segment address is a defect in the caller and throws before any
  request is allocated — then call `rpc/request!`. Their `:outcome` is the
  RPC layer's outcome set **verbatim**: `requested`, `pending-request` (the
  writer answered `full`; the envelope is retained), `request-undeliverable`,
  `allocator-error` (mints no id; its diagnostic is retained), and `terminal`.
  One addition: while `rpc/unsent?` holds, both return
  `:dao.jing.v2.remote/busy` and allocate nothing, because `rpc/request!`
  would silently retry the unsent envelope and ignore the new operation.
- **`step` is the only path that clears `busy`.** Its order is fixed:
  1. if `:unsent` is held and the state is not terminal, re-attempt it through
     `rpc/request!` (whose unsent branch ignores op/args, `rpc.cljc:243-244`)
     and fold that outcome — `requested`, `pending-request`, or
     `request-undeliverable`, the last surfacing as a `:lost` completion —
     into the step result under `:attempt`;
  2. `rpc/poll!` with the budget;
  3. if the returned state is terminal and still holds `:unsent`,
     `rpc/abandon-unsent` with the terminal reason, so the never-accepted
     request completes as `:lost` through the ordinary outbox rather than
     being carried silently across a later `rebind`;
  4. **the materializer's turn** (J3c below): if the state is not terminal,
     issue unissued verify hops in put-id order until the writer answers
     `full` or none remain; if the state is terminal, complete every
     unissued verify hop as `:lost` with the terminal reason;
  5. take completions and diagnostics exactly once, routing each completion
     whose id a materialization record knows into that record (order 4's
     issuance registers ids before this drain, so an undeliverable hop's
     completion, already in the outbox, routes).

  A driver that only re-calls `request-*` can never clear a `busy`; it must
  step. Nothing here loops on `blocked`, holds a clock, retries on its own, or
  touches a socket. Timeouts, reattachment (`rpc.ws/rebind` on `/detached`),
  cadence, and what to do with a lost request are the driver's.
- **`abandon state reason`** is the composition's tool for its own
  disconnect-before-rebind — an operator disconnect, a rebind onto a fresh
  attachment — mirroring `yin.repl.v2.driver` (`driver.cljc:220,258,293,305`),
  which abandons before every rebind. It calls `rpc/abandon-unsent`; the
  completion carries `:dao.stream.v2.rpc/abandoned` when no terminal reason
  exists. Ids stay monotonic. The abandoned envelope's completion routes into
  a materialization record if its id is registered there (see the lifecycle
  table), on the next `step`'s drain.
- **Completion decode is total by construction.** A completion carrying
  `:dao.stream.v2.rpc/response` decodes by op: `{:id n :op :jing/put-content
  :result :inserted}` or `:present`; `{:id n :op :jing/get-content :found?
  true :value v}` or `:found? false`; a response that is not the exact
  presence envelope, or not one of `:inserted`/`:present`, or an error
  response, becomes `{:id n :op … :error {code message}}` — the malformed
  cases under `:dao.jing.v2.remote/malformed-response`, as data, because it
  surfaces inside a driver step. A completion carrying
  `:dao.stream.v2.rpc/reason` becomes `{:id n :op … :lost reason}` for **any**
  reason keyword, pass-through, qualified keyword unchanged: `:dao.stream/gap`,
  `:dao.stream/end`, `:dao.stream/cursor-mismatch`, `:dao.stream/invalid-cursor`,
  `:dao.stream/transport-error`, the lifecycle `:dao.stream.v2.apply/detached`,
  `/ended`, `/not-found`, `/transport-error`, an undeliverable append's
  outcome, and `:dao.stream.v2.rpc/abandoned` are examples, not a closed
  vocabulary. Reader `:dao.stream/end` and lifecycle `/ended` stay distinct
  because the keyword is never rewritten.
- **`:diagnostics`** is part of the step result and is published exactly once:
  decode errors, unsolicited responses, events after terminal, transport
  diagnostics. A caller that discards them discards them knowingly.
- The namespace is pure `.cljc` with no host branch. The v1 network client was
  JVM-only; the v2 client runs on all three hosts by construction.

**Client side, J3c — the stepped materializer.**

```clojure
(remote/request-materialize state payload)     ; => {:outcome k :state s' :id n?}
```

Derives the address locally with `jing/segment-key`, issues `:jing/put-content`
through `request-put`, and — **as soon as `rpc/request!` returns an id**,
whatever the outcome — records `{:payload p :address a :phase :put}` under
that put id. `requested`, `pending-request` and `request-undeliverable` all
carry `:dao.stream.v2.rpc/id` (`rpc.cljc:198,203,211`); only `allocator-error`
and `terminal` mint no id, and then no record is created and the outcome is
returned as-is. On an `:inserted` completion, completes `{:id n :op
:materialize :address a :result :inserted}`. On `:present`, the materialization
needs a second correlated `:jing/get-content` for the same address, and the
record moves to `:verify-unissued`. When that get completes, the
materialization completes `:present` with the address only if the returned
value equals the payload; otherwise an `:error` completion carrying
`:dao.jing.v2.remote/integrity-failure` (unequal) or `/present-but-absent`
(`:found? false`) — the two failures `jing.cljc:283-293` throws on locally, as
data here. A `:lost` on either hop completes the materialization as `:lost`
with the reason. **One completion per materialization**: hop completions are
consumed by the record and never republished as put or get completions. The
driver holds the put id and correlates by it. Read-side hydration needs only
J3a; `store-tree-async` depends on J3c.

**Who issues the verify hop: `step` does, and this is the one obligation the
client carries.** The `:present` completion is decoded inside `step`, and the
get it calls for cannot always be issued at that moment — the writer may
answer `full`, or an unsent envelope may already hold the slot. So `step`
retains the unissued hop as data in the per-id record (`:verify-unissued`) and
issues it in step order 4 on this or a later step, when `rpc/unsent?` is
false: it calls `rpc/request!` for `:jing/get-content` and, **as soon as an id
is returned — on `requested`, `pending-request` or `request-undeliverable`
alike** — registers the same record under that get id, moving the record to
`:verify-issued`. From then on the record is reachable by **both** the put id
and the get id, and every completion carrying either id routes to the
materialization rather than to a caller's own `request-get`, including a
completion that was already sitting in the outbox when the id was returned
(an undeliverable hop) and one that arrives later (a `full`-deferred hop
abandoned or lost). If the writer answers `full`, the get becomes the RPC
layer's `:unsent`, which step order 1 re-attempts next time. On a terminal
state, order 4 completes every `:verify-unissued` record as `:lost` with the
terminal reason; `:verify-issued` records need no sweep, because their get is
either outstanding — completed by `lose-outstanding` — or `:unsent` —
completed by order 3's `abandon-unsent` — and either completion routes by the
registered get id. **The record's phase transition to done is the
exactly-once guard**: the first completion that reaches a record completes the
materialization and removes the record from both indices; a later completion
for a removed id is impossible from the RPC layer, which completes each id
once, and would otherwise be published as an ordinary unrouted completion,
never a second materialization completion.

**The lifecycle, as a table.** Every state the record can be in, every event
that can reach it, what happens to the record, what is published, and under
which id it routes. A reader checks exhaustiveness against this table, not
against prose. "Routes by" names the id the RPC completion carries, or
**synthesized** where no RPC completion exists and the materializer publishes
under the put id directly; the published materialization completion always
carries the **put id**. Every cell is one of four dispositions: a
**transition**, a **no-op** (record unchanged, stated), **impossible** (with
why), or **dependent** (with the dependency named).

| record phase | event | record | published | routes by |
| --- | --- | --- | --- | --- |
| *(none)* | `request-materialize`: `rpc/request!` returns `requested` or `pending-request` | created, `:phase :put`, registered under put id | nothing (outcome returned to caller) | — |
| *(none)* | `request-materialize`: `rpc/request!` returns `request-undeliverable` | created, `:phase :put`, registered under put id; the completion is already in the outbox | on the next drain: `{:id put :op :materialize :lost reason}`; record removed | put id |
| *(none)* | `request-materialize`: `allocator-error` or `terminal` | not created | nothing; outcome returned to caller, no id | — |
| `:put` | order 1 re-attempts the put (it was `:unsent`): `requested` / `pending-request` | unchanged | nothing | — |
| `:put` | order 1 re-attempt undeliverable, or driver `abandon` while the put is `:unsent`, or order 3 abandons it on terminal, or `lose-outstanding` loses it | removed | `{:id put :op :materialize :lost reason}` | put id |
| `:put` | put completion `:inserted` | removed | `{:id put :op :materialize :address a :result :inserted}` | put id |
| `:put` | put completion `:present` | `:phase :verify-unissued` | nothing | put id |
| `:put` | put completion `:error` (handler error, malformed response) | removed | `{:id put :op :materialize :error e}` | put id |
| `:put` | a get-id completion | **impossible** — no get id has been allocated | — | — |
| `:put` (put `:unsent`) or `:verify-issued` (get `:unsent`) | response-medium `gap` (non-terminal) | **no-op** for the unsent envelope: `lose-outstanding` does not touch `:unsent`; the envelope is re-attempted by order 1 next step | nothing | — |
| `:put` (put outstanding) or `:verify-issued` (get outstanding) | RPC state goes terminal by `allocator-error` — another materialization's or caller's allocation failed | **dependent** on the `dao.stream.v2.rpc` fix filed with this plan: `allocation-failure` must call `lose-outstanding` as every other terminal does (`rpc.cljc:155-161` does not today). With the fix, this is row 5 / row 18: removed | with the fix: `{:id put :op :materialize :lost :dao.stream.v2.rpc/allocator-error}`; without it: **none — the record is stranded**, which is why J3c is gated on the fix | put id / get id, carried by `lose-outstanding`'s completion |
| `:verify-unissued` | order 4, not terminal, `rpc/unsent?` true (another envelope holds the slot) | unchanged; retried next step | nothing | — |
| `:verify-unissued` | order 4, not terminal, `rpc/request!` for the get returns `requested` | `:phase :verify-issued`, registered under get id | nothing | — |
| `:verify-unissued` | order 4, `rpc/request!` returns `pending-request` (writer `full`) | `:phase :verify-issued`, registered under get id; the envelope is the RPC `:unsent`; order 4 stops issuing further hops this step | nothing | — |
| `:verify-unissued` | order 4, `rpc/request!` returns `request-undeliverable` | `:phase :verify-issued`, registered under get id; the completion is already in the outbox | on order 5's drain, same step: `{:id put :op :materialize :lost reason}`; record removed | get id |
| `:verify-unissued` | order 4, `rpc/request!` returns `allocator-error` | removed (the RPC state is now terminal; no id exists to route). Every *other* record with an outstanding hop is the dependent row above | `{:id put :op :materialize :lost :dao.stream.v2.rpc/allocator-error}` | synthesized; published under the put id |
| `:verify-unissued` | order 4, state terminal (the sweep) | removed | `{:id put :op :materialize :lost terminal-reason}` | synthesized; published under the put id |
| `:verify-unissued` | driver `abandon` | **no-op**: this record owns no envelope; `abandon-unsent` completes whatever unrelated envelope is `:unsent` | nothing for this record | — |
| `:verify-unissued` | response-medium `gap` (non-terminal) | **no-op**: `gap` loses outstanding ids only, and this record has none | nothing | — |
| `:verify-unissued` | any RPC completion | **impossible** — the put id already completed (`:present`) and no get id exists | — | — |
| `:verify-issued` | order 1 re-attempts the get (it was `:unsent`): `requested` / `pending-request` | unchanged | nothing | — |
| `:verify-issued` | order 1 re-attempt undeliverable, or driver `abandon` while the get is `:unsent`, or order 3 abandons it on terminal, or `lose-outstanding` loses it | removed | `{:id put :op :materialize :lost reason}` | get id |
| `:verify-issued` (get outstanding, not `:unsent`) | driver `abandon` | **no-op**: `abandon-unsent` touches only `:unsent`; an outstanding get completes through its response or `lose-outstanding` | nothing | — |
| `:verify-issued` | get completion `:found? true`, value `=` payload | removed | `{:id put :op :materialize :address a :result :present}` | get id |
| `:verify-issued` | get completion `:found? true`, value `≠` payload | removed | `{:id put :op :materialize :error /integrity-failure}` | get id |
| `:verify-issued` | get completion `:found? false` | removed | `{:id put :op :materialize :error /present-but-absent}` | get id |
| `:verify-issued` | get completion `:error` (handler error, malformed response) | removed | `{:id put :op :materialize :error e}` | get id |
| `:verify-issued` | order 4 issuance or terminal sweep | **impossible** — order 4 acts only on `:verify-unissued`; an issued hop completes through its registered get id | — | — |
| `:verify-issued` | a put-id completion | **impossible** — the put id completed when the record left `:put` | — | — |
| *removed* | any completion for a formerly registered id | **impossible** from the RPC layer, which completes each id once; defensively, published as an ordinary unrouted completion, never a second materialization completion | — | — |

Every RPC completion carries either a response or a reason (`rpc.cljc:128-134`),
so the "completion" rows above are exhaustive over what `poll!`, `request!`,
`abandon-unsent` and `lose-outstanding` can emit; every `rpc/request!` outcome
(`requested`, `pending-request`, `request-undeliverable`, `allocator-error`,
`terminal`, `invalid-request`) is covered, `invalid-request` being unreachable
because the materializer always supplies a keyword op and a vector of args.

This spends a principle knowingly. Round 1 refused to queue caller work inside
the client (contested item 6): a queue of *submitted operations* awaiting a
slot would hide the driver's ordering decisions and grow without bound. The
verify hop is neither. It is the second half of one operation the caller
already submitted and holds an id for; it is bounded by the number of
materializations in flight, each of which already consumed an id; it admits no
new caller work; and it is data in the state the driver owns, visible as
`:verify-unissued` or `:verify-issued`, not a hidden buffer. It is also the
shape the RPC layer already sanctions twice: `dao.stream.v2.rpc`'s own
`:unsent` and `apply/serve-once!`'s `:pending-response` are machine-internal
obligations retained as data and driven by the caller's next call
(`rpc.cljc:259-264`, `apply.cljc:301-315`), so a client carrying one is
pattern-consistent, not exception-carved. The alternative — a public
`resume-materialize` the driver must call — would make the driver responsible
for re-driving an intent it never expressed and cannot see, and re-calling
`request-materialize` mints a fresh put and orphans the original record. The
client carries this one obligation so that the driver's contract stays
"submit, step, read completions." A caller's `request-*` while a verify hop
holds the slot as `:unsent` sees `busy`, as for any unsent envelope.

**The `:dao.jing/remote` coordinate.** `dao.jing.coordinate/open!` turns
`{:dao.jing/type :dao.jing/remote :url …}` into a synchronous handle that
`dao.space.index`'s published-index `defopen` reads through `jing/get` to
restore B-tree nodes. Under v2 that coordinate cannot yield a handle; it would
yield a client state plus an `attach!` result plus a driver obligation — a
composition, not a value. This plan leaves `dao.jing.coordinate` and its
`:dao.jing/remote` branch **untouched on v1**. The v2 client has no coordinate
here. The promise-shaped `hydrate-async`/`store-tree-async` that
`dao.data.btree.md` §5.4 specifies is a driver over this client — the
"async DaoJing backend" that section waits for is not delivered by J3 alone —
and that driver, with the coordinate that opens into it, belongs to
`dao.space`'s migration plan, whose index `defopen` is the only consumer.

### Decision 4 — Namespace layout and the end state, decided now

| namespace                 | this plan                                                              | v1 dependency after this plan                | consumer that keeps v1 alive                                   |
| ------------------------- | ---------------------------------------------------------------------- | -------------------------------------------- | -------------------------------------------------------------- |
| `dao.jing`                | **in-place**: v1 observer moved out in J1; stream-free core            | none, direct or transitive — or, under the documentation route, `dao.stream` (observer only) until J5 | — (documentation route: the nine observer test files) |
| `dao.jing.observer` (v1)  | **new in J1**: the v1 observer, unchanged code and `dao.stream` require | `dao.stream`                                 | nine test files until `dao.space`'s tests migrate              |
| `dao.jing.v2.observer`    | **new** — the observer on v2                                           | none, direct or transitive — or, under the documentation route, transitive via `dao.jing` until J5 | — |
| `dao.jing.file`           | **in-place**: stream coupling removed                                  | none                                         | —                                                              |
| `dao.jing.mem`            | untouched                                                              | none                                         | —                                                              |
| `dao.jing.remote` (v1)    | untouched                                                              | `dao.stream.rpc.*` (clj)                     | `dao.jing.coordinate`, `dao.space` tests                       |
| `dao.jing.v2.remote`      | **new** — handlers, client primitives, materializer                    | none, direct or transitive — or, under the documentation route, transitive via `dao.jing` until J5 | — |
| `dao.jing.coordinate`     | untouched                                                              | transitive via `dao.jing.remote` (clj)       | `dao.space.index`                                              |
| `dao.jing.dht*`           | untouched                                                              | `dao.stream.transit`, UDP                    | out of scope                                                   |

**The end state, decided now, independent of `dao.stream.v2`'s naming
decision:** three namespaces — `dao.jing` (stream-free content-addressing
core), `dao.jing.observer` (the observer, on v2), `dao.jing.remote` (handlers,
primitives, materializer). The `.v2.` segment is the transient marker and
nothing else; bare `dao.jing.v2` does not exist. J5 deletes the v1
`dao.jing.observer` and v1 `dao.jing.remote`, then renames the two
`dao.jing.v2.*` namespaces into those slots. This follows from the C2 remedy:
moving the v1 observer out is what makes `dao.jing` stream-free, and folding
the v2 observer back in at J5 would make the address core stream-facing again
for all nine of its production requirers, which is the coupling that made the
round-1 dependency table false. DaoJing's namespace names appear on no wire and
in no coordinate — coordinate types are keywords like `:dao.jing/file` — so
only requires change.

**No registry, restated.** v1's `defopen`/`open!` is gone in v2 and nothing in
`dao.jing*` reinvents it. `dao.jing.coordinate/open!` is the precedent: a
closed `case` over coordinate types, an explicit code change to add one. The
v2 observer takes handles; the v2 remote takes an RPC state built from handles
the composition attached; neither looks anything up by name.

**Reader-conditional discipline.** `dao.jing.file` keeps three-branch
`#?(:cljd … :clj … :cljs …)` forms with `:cljd` first, as it does today.
`dao.jing.v2.observer` and `dao.jing.v2.remote` contain no host branch. No new
file may contain a bare `#?(:clj …)` form — `#?(:clj …)` does not exclude code
from the cljd build; `#?(:cljd nil :clj …)` with `:cljd` first is the only safe
spelling, and `:cljd` in tail position silently fails. Full cljd namespace
compilation gates every phase.

## Divergence register

Every place the v2 pieces deliberately differ from v1, with the reason.

| v1 behavior                                                                                      | v2                                                                                                   | why                                                                                                   |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `observer-state` takes streams; cursor is `{:position 0}`                                        | takes `{:stream :cursor}` members; the composition mints the cursor                                  | every valid cursor comes from the stream; the anchor is the composition's policy                       |
| signals `:ok`, `:blocked`, `:end`, `:daostream/gap`; malformed results throw                     | the seven `:dao.stream/…` outcomes; three defect outcomes reported as data with `:member` and `:result` | the result convention; an outcome outside the closed set still throws, as a transport defect         |
| a gap is reported, cursor unchanged, never resynced                                              | same rule, extended to `cursor-mismatch`, `invalid-cursor`, `transport-error`; `adopt-cursor` is the resync | one rule for every non-progress outcome; resync is a composition decision                         |
| observer lives in `dao.jing`                                                                     | v1 observer moves to `dao.jing.observer`; v2 observer is `dao.jing.v2.observer`                      | `dao.jing` must be stream-free so nine production requirers do not carry a stream dependency          |
| `dao.jing.file` opens `{:dao.stream/type :append-log}`; handle carries `:log`                    | private framed file; handle carries `:path :state :write-lock` and the three fns, no `:log`          | Decision 2. **Recorded dissent:** `gpt-5.6-sol` holds that direct file IO behind `:put-content-fn` violates `datom.world.md:53-68` (a host boundary is a stream boundary; callable adapters are rejected) and that a v2 append-log is constructible under the no-wait rule and should be built. The plan holds that the violation, if it is one, is the synchronous content handle itself, present today and unchanged by any transport beneath it, and that a constructible append-log cannot supply `:inserted`'s durability meaning without redesigning `materialize!` and its consumers. `glm-5.3` and `gpt-6-astra` accept the plan's position. |
| `file_test` counts records with `ds/next` over the raw log                                       | `dao.jing.file/records` returns the decoded records of a path; tests use it                          | the framing is private; the test reads through the backend's own reader                              |
| `content-client` is a handle; `:get-content-fn` returns the value                                | `client-state` / `request-*` / `step`; results are completions correlated by id                       | no operation waits; unimplementable otherwise on cljs/cljd                                            |
| `jing/materialize!` over a remote handle                                                         | `request-materialize`: derive, put, verify on `:present`, complete with the address; `step` issues the verify hop and owns its lifecycle | the integrity behavior of `jing.cljc:276-293` must survive the loss of the handle |
| collision or unreadable-present throws                                                           | `:error` completion with `/integrity-failure` or `/present-but-absent`                              | inside a driver step an exception has nowhere to go                                                   |
| `connect-content!` — synchronous, JVM-only                                                       | gone; the composition attaches through `dao.stream.v2.ws` and builds the client from the whole `attach!` result | the constructor waited; attachment outcome arrives as deposited data                         |
| malformed presence envelope throws inside `get`                                                  | completes as an `:error` with a malformed-response code                                              | same                                                                                                  |
| `close-fn` guards concurrent closes with a lock and retries on failure                           | gone with the handle; `close!` on the ws handle is idempotent and answers `ok`                       | the client holds no host resource; the handle does                                                    |
| `:dao.jing/remote` coordinate opens to a handle                                                  | unchanged on v1; no v2 coordinate in this plan                                                       | it would open into a composition, which is `dao.space`'s to design with hydration                     |

**v1-only tests deliberately not mirrored:** `file_test`'s direct
`{:dao.stream/type :append-log}` opens and `count-records` (replaced by
`records`); `log_test.cljc`'s `log-round-trip`, `log-positional-read`,
`multi-cursor`, `reopen-replay`, `close` and `fd-lock-concurrency` cases,
which test the stream surface of a transport that no longer exists (only
`torn-tail-test` is ported, as recovery evidence); `remote_test`'s
`network-invalid-url-test` (a bad URL is `invalid-descriptor` or a deposited
`/transport-error`, tested in J3b as data), `close-failure-retry-test` (no
close-fn to fail), and every `with-server` case built on `rpc-ws/start!`
(replaced by the J3 compositions).

## Phase J0 — What exists

Recorded so the phases start from evidence.

- `dao.stream.v2` (protocols, outcome sets, `validate-outcome`), the ring
  buffer with `make-attacher`, `dao.stream.v2.apply` (envelope, `serve-once!`),
  `dao.stream.v2.rpc` (`client-state`, `request!`, `poll!`, `take-completed`,
  `take-diagnostics`, `unsent?`, `abandon-unsent`, `rebind`),
  `dao.stream.v2.rpc.ws` (`decoder`, `init-client`, `rebind`),
  `dao.stream.v2.ws` with host `connect!` seams on clj, cljs and cljd,
  `dao.stream.v2.serving`, and the conformance harness. All green per the user
  on this revision.
- `dao.jing` core is stream-free except for the observer; `dao.jing.mem` is
  stream-free. The v1 observer has no `src/` caller.
- `test/dao/jing_test.cljc` observer cases (lines 322–560) are the behavioral
  evidence for J2; `test/dao/jing/file_test.cljc` and
  `test/dao/stream/log_test.cljc:100-135` (`torn-tail-test`) for J1;
  `test/dao/jing/remote_test.cljc` for J3; `test/dao/stream/v2/rpc_test.cljc`
  is the shape precedent for the socket-free J3 tests.

## Phase J1 — `dao.jing` stream-free; `dao.jing.file` without a stream

Three deliverables in one phase, because the second and third retire the same
v1 namespace and the first is what makes the transitive gate true. **J1.1 and
J1.2 land as one change**: J1.1 alone leaves `file_test.cljc:113-135` calling
`jing/observer-state` on a namespace that no longer has it, a compile failure.

**J1.1 — Move the v1 observer out.** `observer-state` and `observe-step!`
move verbatim, with their docstrings and the `dao.stream` require, from
`dao.jing` to a new `src/cljc/dao/jing/observer.cljc`. `dao.jing` drops
`dao.stream` from its requires. No `src/` file changes a require. **Nine test
files** are affected, in three kinds:

- **Seven repoints.** `test/dao/space/{transactor,stigmergy,index,schema,query}_test`,
  `test/dao/jing/mem_test.cljc`, `test/dao/jing/dht_test.cljc`: each gains a
  `[dao.jing.observer :as observer]` require and rewrites its
  `jing/observer-state` / `jing/observe-step!` call sites. In the five
  `dao.space` files that is the require plus the call sites inside their
  drain helpers. In `mem_test` and `dht_test` the `jing/` alias also serves
  core functions (`segment-key`, `materialize!`, `get`), so the alias stays,
  a second alias is added, and each observer call site is edited
  individually; both files keep their own `open-stream` helpers
  (`dht_test.cljc:130`), which open v1 ring buffers and are unaffected.
- **One section move.** `jing_test.cljc`'s observer section (lines 322–560)
  moves to `test/dao/jing/observer_test.cljc`. The `mem-handle` helper at
  `jing_test.cljc:18` is used by both the moving section and the staying
  handle-API section (lines 154–316), so it is **duplicated** into
  `observer_test.cljc` — fifteen lines that die with the file at J5 — rather
  than extracted into a shared test namespace that would outlive its reason.
  `open-stream` and `MalformedResultStream` (lines 35–49) are used only by the
  moving section and move with it.
- **One excision.** `file_test.cljc:113-135` (`observer-convergence-test`) is
  removed in J1: it drives the v1 observer over v1 ring buffers into the file
  backend, and J1.2 removes `dao.stream` from `file_test`'s requires. J2
  recreates it over the v2 observer and v2 ring buffers.

*Scope-contingent.* The five `test/dao/space/` files are outside `dao.jing*`.
If the orchestrator holds the `dao.jing*` scope closed at authorization, the
alternative is the **documentation route**: the observer stays in `dao.jing`
until J5, `dao.jing` keeps its `dao.stream` require; Decision 4's `dao.jing`
row reads "`dao.stream` (observer only)" and both `dao.jing.v2.*` rows read
"transitive via `dao.jing`"; J2's deliverable and the end condition state, as
their own bullet and not a footnote, that `dao.jing.v2.*` is transitively v1
until J5 removes the observer from `dao.jing`. Under that route
`dao.jing.observer` does not exist, so none of the seven repoints happens —
`mem_test`, `dht_test` and the five `dao.space` files keep calling
`jing/observer-state` unchanged; only the `jing_test` section move (with
`observer_test.cljc` requiring `dao.jing` for the observer) and the
`file_test` excision happen, because J1.2 and J2 need them regardless. The
move-out is the plan's position because the project's standard for a v2
namespace is transitive v1-freeness — the REPL plan built a second VM to make
"requiring no v1 namespace" true rather than aspirational — and because the
cost is nine bounded test-file edits against nine production namespaces made
v1-free at J1 instead of J5.

**J1.2 — `dao.jing.file` without a stream.**

- Replace the five `ds/*` sites with a private framed-file section:
  `open-frames!` (create parent dirs, open read/write, truncate an incomplete
  tail exactly as `dao.stream.log/make-log-stream` does: a tail shorter than
  four bytes, a negative length, or a length reaching past EOF), `append-frame!`
  (length prefix + bytes at the current end, then sync), `replay-frames`
  (every complete record from offset 0), `close-frames!`. Three host branches,
  `:cljd` first.
- `put` is unchanged in order: validate, lock, closed check, presence check,
  append and sync, then swap the content map, then `:inserted`. `close!` stays
  idempotent. The requires drop `dao.stream` and `dao.stream.log`. The handle
  no longer carries `:log`.
- Public `records`: `(records path)` returns the decoded, validated
  `[address payload]` vector of a file, for tests and diagnostics; it opens,
  replays and closes.
- **Golden-record compatibility test.** A hand-written byte sequence for one
  known record; `create-content-file` reads it and `records` returns it.
- **Torn-tail recovery tests, ported from `log_test.cljc:100-135` on all three
  hosts, with one adaptation.** That test's payload `(->bytes [11 22])` is not
  a decodable `[address payload]` record, so `create-content-file` would fail
  closed on it before reaching the torn tail. The ported test therefore:
  writes one valid encoded Jing record through `append-frame!`; hand-writes
  the overlong-length frame (int32 length 999, two payload bytes) as raw host
  bytes exactly as the original does; asserts that `create-content-file`
  truncates, that `records` returns exactly the valid record, and that a
  subsequent `materialize!` lands clean and survives reopen. A second case
  hand-writes a sub-four-byte tail (two bytes) and asserts the same. A third
  asserts a negative length is truncated. This is the coverage the copied
  truncation loops otherwise lose.
- Port the rest of `file_test`: `handle-shape-test` asserts no `:log` and no
  `:stream`; every `count-records` becomes `(count (records path))`; the
  corrupt-record and raw-duplicate cases write their raw frames through
  `append-frame!`.

**J1.3 — Retire `dao.stream.log`.** Delete `src/cljc/dao/stream/log.cljc` and
`test/dao/stream/log_test.cljc`, and any test-runner reference to them. After
J1.2 the transport has no consumer (`:append-log` had exactly one non-test
consumer, `file.cljc:190`; `dao.stream.log` was required only there and by
the two test files), its `defopen :append-log` is a load-time registration
into the ambient registry v2 retires, and leaving it would make the copied
truncation loops a second owner of the same logic with a drift window lasting
until wholesale v1 deletion. `dao.stream.file` (`:file`, live-tail, consumed
by `yin.io.file`) is a different transport and is untouched.

*Scope-contingent.* This is the one deletion outside `dao.jing*`. The plan
claims it because the orphaning is caused here, and because the stream plan's
end condition deletes v1 "when the last consumer has migrated (under its own
plan)" — for this transport the last consumer is `dao.jing.file`, migrating
here, so per-transport retirement at the last consumer's migration is the
stream plan's own rule at its natural granularity. If the orchestrator rules
that a consumer plan may not delete a v1 file, the alternative is a
stream-plan-owned dependency named **"legacy append-log retirement"**,
coordinated **atomically with J1** — one change, two owners signing it — so
that no window exists in which two copies of the framing are both live.
Leaving `log.cljc` standing until wholesale v1 deletion is not an option
under either ruling.

Deliverable: `dao.jing` and `dao.jing.file` require no `dao.stream*`
namespace (under the documentation route: `dao.jing.file` does not, and
`dao.jing` requires `dao.stream` for the observer only); `file_test` green on
clj, cljs (Node) and cljd with `dao.stream` absent from its requires and the
three recovery cases present; the nine affected test files green;
`dao.stream.log` gone. Consumers (`dao.space.*`,
`dao.data.btree_durability_test`) pass, confirmed by running them rather than
by inspection.

## Phase J2 — `dao.jing.v2.observer`

`src/cljc/dao/jing/v2/observer.cljc`: `observer-state`, `observe-step!`,
`adopt-cursor`, per *Decision 1*. Requires `dao.jing` and `dao.stream.v2`
only, and after J1 `dao.jing` requires no `dao.stream`, so the namespace is
v1-free directly and transitively.

Tests, `test/dao/jing/v2/observer_test.cljc`, over v2 ring buffers created
with `ringbuffer/create!` and cursors minted with
`stream/cursor … :dao.stream/oldest`:

- every observer case from the former `jing_test.cljc` lines 322–560, ported:
  plain-data state, empty pool blocked, all blocked, hashing and retrieval,
  equal payloads from two streams converge, blocked/ended members do not
  starve others, all ended, drains then end, fair round-robin, strict
  interleave, cursor advances only after successful materialization. Cursor
  assertions become successor-equality assertions, never position arithmetic.
- **gap**: a capacity-2 ring buffer with three appends; the signal is
  `:dao.stream/gap` with `:member 0` and a `:cursor`; the member cursor is
  unchanged; the other member still progresses; the gap reports again on the
  next turn; after `adopt-cursor` with the recovery cursor the member
  materializes the live value.
- **defect outcomes**: a reified reader handle scripted to answer
  `cursor-mismatch`, `invalid-cursor`, `transport-error` (and, for the
  totality test, each outcome in `dao.stream.v2/outcomes-next`); assert the
  signal, `:member`, `:result`, unchanged cursor, and that an outcome outside
  the declared set throws.
- **members round-trip**: `(observer-state (remove-nth (:members st) i))`
  keeps the remaining members' cursors.
- `observer-convergence-test`, recreated from the excised `file_test` case,
  over the J1 file backend and two v2 ring buffers.

Deliverable: `dao.jing.v2.observer` on clj, cljs (Node) and cljd; `Testing
dao.jing.v2.observer-test` confirmed in the Node output; no namespace under
`dao.jing.v2*` requires `dao.stream` **directly or transitively** — or, under
the documentation route, `dao.jing.v2.observer` requires `dao.stream`
transitively through `dao.jing` until J5, and this deliverable says so.

## Phase J3 — `dao.jing.v2.remote`

**J3a — backend primitives, socket-free, all three hosts.**
`src/cljc/dao/jing/v2/remote.cljc`: `default-handlers`, `client-state`,
`request-put`, `request-get`, `step`, `abandon`, per *Decision 3*. Tests,
`test/dao/jing/v2/remote_test.cljc`, mirror `rpc_test.cljc`: request and
response media are two ring buffers; the server side is `apply/server-state`
+ `apply/serve-once!` over `default-handlers` on a `dao.jing.mem` store; the
client is `rpc/client-state` over the same media, wrapped by `client-state`.

- put then get round-trips through `step`; duplicate put completes `:present`;
  absent get completes `:found? false`; a stored `nil` and a stored
  `{:found? true :value "hello"}` round-trip exactly.
- `request-*` on a non-segment address throws before allocation; a handler
  rejecting a hash-mismatched address completes as `:error`.
- **`busy` clears only through `step`**: a scripted writer answering `full`
  once: `pending-request`; a second request returns `busy` and allocates
  nothing; `step` re-attempts and its result carries `:attempt requested`;
  exactly one id allocated; then a case where the driver only re-calls
  `request-*` and asserts `busy` persists.
- **unsent at detach**: a request left `pending-request`, then `/detached`
  deposited on the response medium; `step` produces exactly one `:lost`
  completion for it with `/detached` as the reason, and `:unsent` is empty in
  the returned state; after `rebind` the old envelope is never sent.
- **`abandon`** before an explicit rebind completes the unsent request with
  `:dao.stream.v2.rpc/abandoned`; ids remain monotonic.
- gap on the response medium completes every outstanding request as `:lost`
  with `:dao.stream/gap`; reader `cursor-mismatch`, `invalid-cursor` and
  `end` complete as `:lost` with those exact keywords.
- `allocator-error` on an exhausted or colliding allocator returns that
  outcome, mints no id, and its diagnostic appears in the next `step`'s
  `:diagnostics`.
- malformed responses complete as `:error` with the malformed-response code
  and never as `:found?`; unsolicited responses and decode errors appear in
  `:diagnostics`.
- **declaration-driven totality**: every keyword `dao.stream.v2.rpc` can emit
  as an outcome or reason — enumerated from that namespace's public vars and
  its documented reason set — has a branch; completions and diagnostics
  publish exactly once and are empty in the state returned after `step`.
- `default-handlers` on a handle without `:put-content-fn` throws at
  construction.

**J3b — over `dao.stream.v2.ws`, same process, all three hosts.** A serving
composition assembled in the test from `dao.stream.v2.serving` and the host
`connect!`/`bind!` seams, exactly as `test/dao/stream/v2/ws_test.cljc` and
`serving_test.cljc` do; the served stream is a capacity-1 identity anchor as
the REPL's D3 settled. The client mints its `:dao.stream/newest` cursor on
its traffic medium *before* `attach!`, builds the RPC state with
`rpc.ws/init-client` from the whole `attach!` result, and wraps it with
`client-state`. Prove: put and get round-trip over the wire; two clients see
each other's writes; the file backend survives a stop, restart and reattach;
killing the connection completes an *accepted* outstanding request as `:lost`
with `/detached`, and **a kill with an unsent request** produces exactly one
`:lost` completion and an empty `:unsent` after `rebind` on a fresh `attach!`,
with the same deposit cursor resumed; a descriptor naming a path nothing
serves completes as `:lost` with `/not-found`.

**J3c — the stepped materializer.** `request-materialize` per *Decision 3*,
over the J3a primitives, in the same namespace; `step` order 4 issues pending
verify hops and sweeps them on terminal.

**One named dependency.** J3c depends on a fix in `dao.stream.v2.rpc`, filed
by the orchestrator against `dao.stream.v2` and owned there:
`allocation-failure` (`rpc.cljc:155-161`) must call `lose-outstanding` with
reason `:dao.stream.v2.rpc/allocator-error` before going terminal, as every
other terminal path in that namespace does (`rpc.cljc:371,377,403,410,415`),
keeping its diagnostic; `:terminal` is set as today and `rebind` still
refuses. Without it, every request already in `:outstanding` when an
allocation fails is stranded — `poll!` returns without reading once terminal
(`rpc.cljc:430`) — and a materialization record in `:put` or `:verify-issued`
has no exit (the `dependent` row of the lifecycle table). `yin.repl.v2` is the
second affected consumer today (`v2_adapter.cljc:117` maps `allocator-error`
to its own terminal and inherits the stranding). `rpc_test.cljc` currently has
no coverage of `allocator-error`, `id-exhausted` or `id-collision`; the fix
carries its own test there. `dao.jing.v2.remote` grows no compensating logic.

**The bystander test is J3c's gate.** One materialization in `:put` with its
put outstanding; a forced allocation failure on a second `request-materialize`
(the test drives `:next-id` onto an in-use id); assert the bystander completes
`:lost :dao.stream.v2.rpc/allocator-error` exactly once, by put id, with no
residual record. That test is written in J3c and goes green only when the RPC
fix is in; **J3c is not complete before it is green.** J1, J2, J3a and J3b
proceed independently, because none of them holds a record across an
allocation: only J3c depends on what the RPC layer does with `:outstanding` at
an allocation failure. The trigger is practically unreachable — id exhaustion
needs ~9e15 allocations, and collision requires `:next-id` to name an in-use
id, which monotonic allocation plus per-step drained completions prevents —
which is why the plan is not gated as a whole on two lines in another owner's
namespace, and why J3c is.

Tests, socket-free, on all three hosts, one per row-group of the lifecycle
table:

- fresh payload: one put, completes `:inserted` with the derived address.
- present payload: put answers `:present`, `step` issues the correlated get in
  the same step, equality holds, completes `:present` with the address;
  exactly two ids; the per-id record is reachable by both.
- **verify hop deferred by `full`**: the put completes `:present` while the
  writer is scripted `full`; after that step the record is `:verify-issued`
  with the get as `:unsent`, no completion has been published, and a caller's
  `request-get` answers `busy`; the next step's order 1 re-attempts the get
  (writer `ok`); the completion arrives on the following step; exactly two
  ids at completion.
- **unrelated unsent request occupying the slot**: a caller's `request-get`
  is left `pending-request` by a `full`; then the `:present` completion
  arrives; the record stays `:verify-unissued` that step because
  `rpc/unsent?` holds; the next step re-attempts the caller's unsent first
  (order 1), then issues the verify hop (order 4); both complete; ids are
  monotonic and the verify get's completion routes to the materialization,
  not to the caller's get.
- **undeliverable at issue**: the writer is scripted `closed` when order 4
  issues the get; exactly one `{:op :materialize :lost :dao.stream/closed}`
  completion is published in that same step's drain, carrying the put id; no
  get completion is published; no record remains.
- **undeliverable on the order-1 re-attempt of a deferred hop**: the get was
  deferred by `full`; the writer answers `transport-error` on the next
  step's order 1; exactly one materialize `:lost` completion, no phantom get
  completion, no residual record.
- **driver `abandon` during a deferred hop**: the get is `:unsent`; the
  driver calls `abandon`; the next step's drain publishes exactly one
  materialize `:lost :dao.stream.v2.rpc/abandoned`; no phantom, no residual.
- **terminal with an unissued hop**: `/detached` arrives in the same poll as
  the `:present` (both in one budget), so order 4 finds the state terminal
  with the record `:verify-unissued`; exactly one materialize `:lost
  /detached`; no residual.
- **terminal with an issued hop**: the get is outstanding when `/detached`
  arrives; `lose-outstanding`'s completion routes by the get id; exactly one
  materialize `:lost /detached`; the sweep does not double-complete it.
- present but the server's get returns `:found? false`: completes `:error`
  with `/present-but-absent`.
- present but unequal (a scripted server store seeded with a different value
  at the same address): completes `:error` with `/integrity-failure`.
- `allocator-error` on the verify hop's allocation: exactly one materialize
  `:lost :dao.stream.v2.rpc/allocator-error`, carrying the put id.
- **the bystander (the gate)**: as specified above.
- a caller's own `request-get` for the same address during a verify hop is
  not confused with it (per-id record under both ids).
- **table exhaustiveness**: a test enumerates every `(phase, event)` cell the
  lifecycle table marks as reachable and asserts each is exercised by one of
  the cases above, so a row added to the table without a test fails.

Deliverable: J3a green on clj, cljs (Node) and cljd; J3b green on clj and
cljs (Node), and on cljd through the cljd ws lane; J3c green on all three
hosts including the bystander test, which requires the named RPC fix. The
cljd test regenerates `test/cljd-out/`; one process owns it at a time.

## Phase J4 — Design prose

`dao.jing.md`, exactly the sections *Decision 1* names, plus:

- *Implemented surface* — the file backend becomes "a content-addressed store
  backed by a private framed append-only file", replacing "backed by an
  append-only log stream" (`dao.jing.md:297-299`). This is the design-document
  change Decision 2 records; the commit says so. The remote entry describes
  `dao.jing.v2.remote` as handlers, stepped primitives, and a stepped
  materializer the composition drives, and states that `jing/materialize!`
  and `jing/get` are local operations that do not apply to a remote store.
- *Reads* — "may access the target … through a remote transport" gains "as a
  request whose answer arrives as a completion".
- *Open items* — a new item, **"The content write path as an effect
  stream."** `datom.world.md:66-68` classifies a function exposed for portable
  code to call as an adapter that keeps the coupling; `:put-content-fn` is
  such a function, and `materialize!` returns its address synchronously after
  the backend acknowledges. This predates the v2 migration, is not cured by
  placing any transport beneath the function, and its resolution is a
  redesign in which the content put is an effect on a stream, durability
  arrives later as data, and `materialize!`'s return value changes — reaching
  every consumer that calls it as a value-returning operation
  (`dao.data.btree.storage`, `dao.space.index`). Out of scope for the v2
  migration; recorded so it is not mistaken for something the migration
  settled.

`dao.jing.dht.md` is not edited. The v1 observer's docstrings travel with it to
`dao.jing.observer` unedited.

## Phase J5 — Deletion and the naming decision, executed

Gated on `dao.space`'s migration: its tests are the last consumer of v1
`dao.jing.observer`, and `dao.jing.coordinate` with its tests the last of v1
`dao.jing.remote`. When that gate opens:

- delete `src/cljc/dao/jing/observer.cljc` and
  `test/dao/jing/observer_test.cljc` (under the documentation route: delete
  the observer functions and the `dao.stream` require from `dao.jing`, and
  `observer_test.cljc`);
- delete `src/cljc/dao/jing/remote.cljc` and `test/dao/jing/remote_test.cljc`;
  `dao.jing.coordinate`'s `:dao.jing/remote` branch is deleted or re-pointed
  under `dao.space`'s plan, not here;
- rename `dao.jing.v2.observer` → `dao.jing.observer` and `dao.jing.v2.remote`
  → `dao.jing.remote`, with their tests, executing Decision 4. Whatever
  `dao.stream.v2` decides about its own name is not a prerequisite.

An undecided coexistence is a defect of the migration, not a steady state;
this plan has decided, and J5 executes.

## Host matrix

| phase | clj | cljs (Node) | cljd | notes                                                                                     |
| ----- | --- | ----------- | ---- | ----------------------------------------------------------------------------------------- |
| J1    | ✓   | ✓           | ✓    | three host branches in `dao.jing.file`, `:cljd` first; torn-tail fixtures hand-written per host as in `log_test.cljc` |
| J2    | ✓   | ✓           | ✓    | pure `.cljc`, no host branch                                                              |
| J3a   | ✓   | ✓           | ✓    | pure `.cljc`, no host branch                                                              |
| J3b   | ✓   | ✓           | ✓    | composition uses the per-host `connect!` seam; cljd through the cljd ws lane              |
| J3c   | ✓   | ✓           | ✓    | pure `.cljc`, no host branch                                                              |
| J4    | —   | —           | —    | prose                                                                                     |
| J5    | ✓   | ✓           | ✓    | deletions and renames; confirm every `dao.jing*` test namespace still appears per host    |

Per the standing rule, confirm `Testing dao.jing.v2.observer-test` and
`Testing dao.jing.v2.remote-test` appear in the Node output rather than
assuming discovery.

## Invariants, checked

- **No hidden global state.** `dao.jing.v2.observer` holds no atom.
  `dao.jing.file`'s state atom and lock are per-handle and explicit, as
  `dao.jing.mem`'s are. No `defopen`, no dispatch table, no `defonce`; J1
  removes one `defopen` from the ambient v1 registry.
- **No implicit control flow.** `observe-step!` and `step` return; the
  composition calls again. Nothing self-schedules. The materializer's pending
  verify hop is data in the returned state, issued only by a step the driver
  calls.
- **No callbacks.** None in any new public surface. The ws adapter map is the
  transport's, confined to J3b's composition.
- **No shared mutable state.** A content handle is owned by whoever created
  it; the RPC state is a value threaded by one driver.
- **Interpretation and execution stay separate.** The observer interprets the
  pool; the backend executes the write; the remote client interprets
  completions; the serving composition executes the socket.
- **No assumed graphs.** Nothing here relates content addresses to each other.

## Boundary of this plan

**Untouched:** `dao.jing.mem`; v1 `dao.jing.remote` until J5;
`dao.jing.coordinate`; `dao.jing.dht`, `dao.jing.dht.kad`, `dao.jing.dht.node`;
every `dao.space*` and `dao.data.btree*` `src/` namespace; `dao.stream` v1 in
every form except `dao.stream.log`.

**Touched outside `dao.jing*`, both scope-contingent, both decided at
authorization:**

- five `test/dao/space/` files, a require, an alias, and their observer call
  sites (J1.1); alternative: the documentation route, with transitive v1
  stated in Decision 4's table, J2's deliverable, and the end condition.
- `src/cljc/dao/stream/log.cljc` and `test/dao/stream/log_test.cljc`, deleted
  (J1.3); alternative: stream-plan-owned "legacy append-log retirement",
  coordinated atomically with J1.

**Architectural decisions**, made here: the durable log is not a stream and
the write-path redesign is a separate item (Decision 2); remote content is a
driven state machine with a stepped materializer whose verify hop `step`
issues and whose lifecycle is tabulated (Decision 3); the observer's cursor
and non-progress discipline (Decision 1); the three-namespace end state and
the observer move-out (Decision 4).

**Implementation gaps**, left open knowingly: the v2 remote client has no
coordinate and cannot be reached from `dao.jing.coordinate/open!`; the
serving side exists only as test compositions; the promise-shaped
`hydrate-async`/`store-tree-async` of `dao.data.btree.md` §5.4 is a driver
over this client that this plan does not build.

**Intentionally deferred**, each to a named home:

- **The `dao.stream.v2.rpc` `allocation-failure` fix** — to
  `dao.stream.v2.rpc`'s owner, filed against `dao.stream.v2` with
  `yin.repl.v2` as the second affected consumer. J3c depends on it and
  `dao.jing.v2.remote` compensates for nothing: a consumer that reached into
  `:outstanding` to synthesize the completions the layer beneath owed would
  be the layering error this plan has refused throughout.
- **The content write path as an effect stream** — a DaoJing architecture
  item recorded in J4; it reaches `dao.data.btree.storage` and
  `dao.space.index` and is not a migration.
- `dao.jing.dht` and `dao.jing.dht.node` — UDP transport, deferred by the
  stream plan; `dht.cljc` itself requires no `dao.stream`, and `node.cljc`'s
  `dao.stream.transit` require goes with the UDP transport's own migration.
- `dao.space`'s own migration: `publish-index!`'s v1 `ds/append!` into the
  intake pool, `snapshot-datoms`' `{:position 0}` walk, the published-index
  `defopen`, `DaoStreamLog`'s writer face, and switching its tests from
  `dao.jing.observer` (v1) to the v2 observer.
- The `:dao.jing/remote` coordinate under v2 and the async hydration driver
  (`dao.data.btree.md` §5.4) — `dao.space`'s query-side plan; read hydration
  needs J3a, `store-tree-async` needs J3c.
- A content-serving endpoint as production infrastructure — the composition
  that starts, serves and stops a content server belongs to whatever product
  surface needs it, as the REPL's R4 did for the REPL.
- Canonical encoding; durable observer checkpoints and a long-running runner;
  explicit materialization acknowledgement; garbage collection — the open
  items of `dao.jing.md`, unchanged. Checkpoints gain only the shape
  constraint that the persisted value is the transport's own cursor, whose
  serializability the contract leaves TBD.
- A v2 file or append-log transport — until a consumer needs cursors over a
  durable file.

## End condition

Complete when all of the following hold on clj, cljs (Node) and cljd:

- `dao.jing` requires no `dao.stream*` namespace. `dao.jing.file` requires no
  `dao.stream*` namespace, reads every content file v1 wrote including a
  torn one, and its suite runs with no stream fixture. `dao.stream.log` is
  gone, or its retirement is a named, atomically coordinated stream-plan
  dependency landed with J1.
- `dao.jing.v2.observer` observes v2 reader handles with stream-minted
  cursors, is total over `dao.stream.v2/outcomes-next` by a
  declaration-driven test, retains only successors and adopted cursors, and
  requires only `dao.jing` and `dao.stream.v2`.
- `dao.jing.v2.remote` round-trips put, get and **materialize** through
  `dao.stream.v2.apply` and `dao.stream.v2.rpc` over ring buffers on all three
  hosts and over `dao.stream.v2.ws` on each; clears `busy` only through
  `step`; issues every deferred verify hop through `step`; completes every
  materialization exactly once through every reachable cell of the lifecycle
  table, with no phantom hop completion and no residual record; publishes
  diagnostics exactly once; and its J3c bystander test is green, which
  requires the named `dao.stream.v2.rpc` `allocation-failure` fix to have
  landed — the table's one `dependent` row is then an ordinary row.
- `dao.jing.md` describes the v2 observer, the stream-free file backend, the
  driven remote client with its materializer, and records the write-path
  question as an open item.
- No namespace under `dao.jing.v2*` requires `dao.stream` or
  `dao.stream.rpc.*`, **directly or transitively** — or, under the
  documentation route, `dao.jing.v2*` is transitively v1 until J5, stated as
  this bullet.
- Once `dao.space` has migrated: v1 `dao.jing.observer` and v1
  `dao.jing.remote` are deleted, and `dao.jing.v2.observer` and
  `dao.jing.v2.remote` are renamed to `dao.jing.observer` and
  `dao.jing.remote`.

---

## What changed from revision 4

- **Lifecycle table.** Preamble gains the four-way disposition vocabulary —
  transition, no-op (record unchanged, stated), impossible (with why),
  dependent (with the dependency named) — and the "or synthesized where no
  RPC completion exists and the materializer publishes under the put id
  directly" clause for the "routes by" column. One `dependent` row added for
  the `allocator-error` terminal with a record in `:put` or `:verify-issued`
  outstanding. Four no-op rows added: driver `abandon` during
  `:verify-unissued`; driver `abandon` during `:verify-issued` with the get
  outstanding; response-medium `gap` while `:verify-unissued`; `gap` while
  the put or get is `:unsent`. Rows 14 and 15 (verify-hop `allocator-error`;
  terminal sweep) now read "synthesized; published under the put id", and
  row 14 says every other record with an outstanding hop is the dependent
  row.
- **J3c.** One named dependency — the `dao.stream.v2.rpc`
  `allocation-failure`/`lose-outstanding` fix, filed against `dao.stream.v2`
  with `yin.repl.v2` (`v2_adapter.cljc:117`) as the second affected consumer
  — and the bystander test as J3c's gate; J1, J2, J3a and J3b proceed
  independently, with the reason stated.
- **Boundary, *Intentionally deferred*.** The RPC fix itself, to
  `dao.stream.v2.rpc`'s owner, with the note that `dao.jing.v2.remote`
  compensates for nothing.
- **End condition.** J3c's bullet reflects the gate.
- Status line updated to revision 5. Nothing else changed.

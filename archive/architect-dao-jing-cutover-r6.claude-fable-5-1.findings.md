Warning: no stdin data received in 3s, proceeding without it. If piping from a slow command, redirect stdin explicitly: < /dev/null to skip, or wait longer.
Completed-GMT: 2026-09-07 08:32:06 GMT
Completed-Local: 2026-09-07 15:32:06 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

# dao.jing on DaoStream v2 — the in-place cutover

Status: migration plan, revision 6, derived from and subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract), [`dao.jing.md`](./dao.jing.md)
(the storage-observer design) and [`datom.world.md`](./datom.world.md). Its
transport prerequisite is
[`dao.stream.implementation-plan.md`](./dao.stream.implementation-plan.md),
Phases 1–5, implemented on clj, cljs (Node) and cljd. It is the fifth consumer
migration and the first done **in place**: revision 5 (`cdb871c`) is the
historical record of the parallel-namespace shape; this revision replaces it
under the ruling below. This document is transient: it is consumed as its
phases complete. No phase has started.

## The ruling

`datom.world` has nothing in production. There is no stored content anyone
depends on, no compatibility constraint, and no user to protect during a
migration. The `.v2.` namespace shape exists, in the stream plan's own words,
"to protect the running system during migration"; with no running system it
protects only the test suite, and it is not worth its cost. So: **`dao.jing*`
is cut over in place, with no `.v2.` namespace anywhere in it**, and the tests
that depend on what changes migrate in the same change. What cannot cut over
here is deferred with its reason, not parked beside a twin.

## Context

`dao.jing` is next because `dao.space.index` and `dao.space.schema` require it,
and `dao.space` on the v2 contract is what ends ADR-0003's time-boxed
exception. The v1 surface inside `dao.jing*` is one `ds/next` in the observer
walk (`jing.cljc:391`), five sites in the file backend
(`jing/file.cljc:83,149,175,190,207`), and the remote adapter's JVM-only
`dao.stream.rpc.*` client. No `closed?`, `strict-vec`, `drain-one!` or
`take!!` anywhere. Two facts shape the phasing:

- The v1 observer has **no consumer under `src/`**. Every caller is a test:
  five under `test/dao/space/` (`transactor`, `stigmergy`, `index`, `schema`,
  `query`), `test/dao/jing/mem_test.cljc:213`, `test/dao/jing/dht_test.cljc:402`,
  `test/dao/jing/file_test.cljc:113-135`, and `test/dao/jing_test.cljc`'s
  observer section (lines 322–560).
- The intake pool has two ends. The observer reads it; `dao.space.index/append-ok!`
  (`index.cljc:526-531`, v1 `ds/append!`) and the transactor's intake-pool
  validation (`transactor.cljc:197-201`, `satisfies? ds/IDaoStreamWriter`)
  write it. The five `dao.space` tests publish through those sites and then
  drain through the observer. An observer that reads only v2 handles over a
  pool that only v1 can write is red by construction, so the writer end of the
  pool cuts over with the reader end (J1b).

Nine `src/` namespaces require `dao.jing` — `dao.data.btree.storage`,
`dao.space.index`, `dao.space.schema`, `dao.jing.{file,mem,remote,dht,dht.node}`,
`src/dev/psset_fixtures.clj` — none for the observer; the content-addressing
core they use does not change.

## Decisions

### Decision 1 — The observer: entry shape, cursor discipline, gap

**Entry shape.** A pool member is `{:stream <v2 reader handle>, :cursor <opaque>,
:status s}`. The composition mints the cursor and hands both in:

```clojure
(jing/observer-state [{:stream h1 :cursor c1} {:stream h2 :cursor c2}])
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
arithmetic on a cursor anywhere in `dao.jing`, and no test inspects a cursor's
shape — the conformance harness's own rule. The only other way a member's
cursor changes is `adopt-cursor`:

```clojure
(jing/adopt-cursor state i cursor) ; cursor came from the stream: minted, successor, or gap recovery
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
rule instead of four. A `gap` on a content intake means payloads were appended,
acknowledged to their publisher, and never materialized. Materialization is
idempotent and replay-safe, so adopting the recovery cursor is *safe*; whether
it is *acceptable* only the composition can answer, since it knows whether the
publisher will re-append. The observer must not answer it. `cursor-mismatch`
and `invalid-cursor` are composition defects; `transport-error` may be
transient. All three are resolved the same way: the composition adopts a cursor
it obtained from the stream, or rebuilds the pool without that member. A test
iterates `dao.stream/outcomes-next` and asserts every outcome produces a
branch, so a future contract outcome fails the test rather than falling into a
default.

**Materialization failure is unchanged.** `materialize!` throwing propagates
and leaves the caller-owned state untouched. The storage backend is not a
stream and is not under the stream contract; its failures stay exceptions, as
`dao.jing.md` §Materialization rule already specifies.

### Decision 2 — `dao.jing.file`: the durable log was never a stream

**Decision.** The file backend's durable log is a storage implementation
detail. The cutover removes the `dao.stream` coupling; it does not build a v2
append-log transport, and it does not use one. `dao.jing.md:297-299` currently
describes the file backend as "backed by an append-only log stream"; J2
rewrites that line, and the change is routed as a design-document change.

**Why.** Four reasons, in order of weight:

1. **The log has no second reader and no second writer, ever.** Inside
   `create-content-file` the stream is opened, replayed once by its own
   handle, appended by its own `put`, and closed by its own `close`. No
   cursor is handed out, no descriptor is projected, nothing attaches, and
   the log never sits in any pool. Every property the contract exists to
   guarantee — independent cursors, non-destructive reads, portable
   reachability, attachment lifecycle — is unused.
2. **Making the on-disk layout a `dao.stream` transport made DaoJing's storage
   format a public transport type.** `{:dao.stream/type :append-log}` is a
   name any composition may open. Removing the transport makes the framing
   private to `dao.jing.file`.
3. **A transport with one consumer owes the conformance suite for nobody.**
   A v2 append-log would owe its manifest, exclusion reasons, inducers and
   reader laws on three hosts — for a transport whose sole consumer never
   mints a second cursor.
4. **The parallel is `dao.jing.mem`.** The in-memory backend holds its content
   in a private atom behind `:put-content-fn`/`:get-content-fn`; the file
   backend is the same handle with durability added. `dao.jing.md:278-282`
   already says "backend effects are explicit functions, not a protocol or
   hidden state."

**Why a v2 append-log is not a like-for-like alternative.** Such a transport
**is** constructible: `append!`'s `:dao.stream/ok` "implies neither local
readability nor remote delivery" (`dao.stream.md`, Writing), so it may accept
a synchronous file write and answer `ok` without claiming durability. What it
cannot do is give `dao.jing.file` what it needs: the backend acknowledges
`:inserted` only after flush, and `:inserted` means "durably stored now"
(`jing.cljc:255`). An `ok` that disclaims durability cannot honestly become an
`:inserted` that asserts it. Either the transport grows a transport-owned
durability signal that contract-generic code may not reach for
(`dao.stream.md`, Surfaces), or `materialize!`'s contract changes so that
durability arrives later as data. The cost lands on `materialize!` and its
synchronous consumers — `dao.data.btree.storage`, `dao.space.index` — not on
the transport. It is the write-path redesign in disguise.

**The pre-existing question, recorded and routed.** `datom.world.md:66-68`:
"An adapter that exposes a function for portable code to call is not an
interpreter. It isolates the host library but keeps the coupling, and the
effect never appears as an emission." `:put-content-fn` is such a function
today, on v1, with the log stream beneath it — no caller of `materialize!` or
`get` ever reaches that stream; the handle's `:log` key (`file.cljc:197`) was
test-visible, and J1a removes it. A v2 append-log beneath the same function
changes nothing about the emission. So whether DaoJing's synchronous content
handle conforms to Host Boundaries is a real question that **this plan does
not create and no transport can cure**. J2 records it in `dao.jing.md`'s open
items as **"the content write path as an effect stream"**, with its
consequence stated — durability would arrive as data and `materialize!`'s
return value would change — and out of scope here because it reaches
`dao.data.btree.storage` and `dao.space.index`. Decision 2 removes an unused
internal transport; it does not claim to solve Host Boundaries. It is
compatible with the future design: `datom.world.md:57-59` places raw host
operations inside the host interpreter that consumes the effect stream, which
is exactly where this leaves the file IO.

**What it costs.** A private framed-file section in `dao.jing.file` — open or
create, truncate an incomplete tail, append a length-prefixed record and sync,
replay every record — one `#?(:cljd … :clj … :cljs …)` branch per host, lifted
from `dao.stream.log` by copy. Roughly one hundred lines. The framing is free:
with no stored content there is nothing to stay compatible with, so the
4-byte-length-prefix layout is kept only because it is already written and
tested on three hosts, not because it is owed. The torn-tail recovery tests
stay as **correctness evidence for the truncation code**, not as compatibility
evidence. `dao.stream.log` dies in the same phase, so the copy never has a
second owner.

**Recorded dissent.** `gpt-5.6-sol` holds that direct file IO behind
`:put-content-fn` violates `datom.world.md:53-68` and that a constructible v2
append-log should be built. The plan holds that the violation, if it is one,
is the synchronous content handle itself, present today and unchanged by any
transport beneath it. `glm-5.3` and `gpt-6-astra` accept the plan's position.

### Decision 3 — `dao.jing.remote`: deferred, with its design recorded

**Why it does not cut over here.** `dao.jing.remote`'s `connect-content!` is
the synchronous handle that `dao.space.index:389` reaches through
`jing-coordinate/open!` to restore B-tree nodes with `jing/get`, and that
`test/dao/space/stigmergy_test.clj:80,150,405,411` and
`test/dao/space/index_test.cljc:571-574` drive directly. A synchronous
`get`/`put` over a polling RPC is not implementable without waiting — on cljs
and cljd there is nothing to wait with, and a JVM spin-poll would be `take!!`
by another name — so the v2 remote is a client state machine the caller steps,
not a handle. Replacing the handle therefore forces the async B-tree hydration
that `dao.data.btree.md` §5.4 defers, and the `:dao.jing/remote` coordinate
that opens into a composition rather than a value. Both belong to `dao.space`'s
migration plan, whose index `defopen` is the only consumer. Until then
`dao.jing.remote`, its tests, and `dao.jing.coordinate`'s JVM `:dao.jing/remote`
branch stay on v1 `dao.stream.rpc.*`, untouched — the last v1 dependency inside
`dao.jing*`, and named as such in the end condition.

**The design, recorded for that migration.** It cost four review rounds and
is correct; it is carried here so the future work implements it rather than
rediscovers it. Home: `dao.space`'s plan, in place, under `dao.jing.remote`.

*Server side.* `default-handlers` keeps its shape — `:jing/put-content` returns
`:inserted`/`:present`; `:jing/get-content` returns the exact presence envelope
`{:found? boolean, :value value}` — and is served by `dao.stream.apply/serve-once!`
per attachment. DaoJing owns the handlers, not an endpoint.

*Client side, primitives.*

```clojure
(remote/client-state rpc-state)                ; wraps dao.stream.rpc client state; no cursor is fabricated
(remote/request-put state address payload)     ; => {:outcome k :state s' :id n?}
(remote/request-get state address)             ; => {:outcome k :state s' :id n?}
(remote/step state budget)                     ; => {:state s' :attempt k? :completions [...] :diagnostics [...]}
(remote/abandon state reason)                  ; => s'
```

`request-*` validate the address as `jing/get` does and throw on a non-segment
address before allocation; their outcome is the RPC layer's set verbatim —
`requested`, `pending-request`, `request-undeliverable`, `allocator-error`
(mints no id), `terminal` — plus `:dao.jing.remote/busy` while `rpc/unsent?`
holds, because `rpc/request!` would silently retry the unsent envelope. `step`
is the only path that clears `busy`, in fixed order: (1) if `:unsent` is held
and the state is not terminal, re-attempt through `rpc/request!` and fold the
outcome under `:attempt`; (2) `rpc/poll!` with the budget; (3) if terminal and
`:unsent` is still held, `rpc/abandon-unsent` with the terminal reason; (4) the
materializer's turn — if not terminal, issue unissued verify hops in put-id
order until the writer answers `full`; if terminal, complete every unissued hop
`:lost`; (5) take completions and diagnostics exactly once, routing each whose
id a materialization record knows into that record. `abandon` is the
composition's disconnect-before-rebind tool, mirroring `yin.repl.driver`.
Completion decode is total by construction: response completions decode by
op, malformed ones become `:error` under `/malformed-response`, and any
completion carrying a reason becomes `{:lost reason}` with the qualified keyword
unchanged — examples, not a closed vocabulary.

*Client side, materializer.* `(remote/request-materialize state payload)`
derives the address locally, issues the put, and registers a per-id record
`{:payload :address :phase :put}` as soon as `rpc/request!` returns an id. On
`:inserted` it completes with the address. On `:present` the record moves to
`:verify-unissued`; `step` issues the correlated get in order 4 and registers
the record under the get id as soon as an id is returned — `requested`,
`pending-request` or `request-undeliverable` alike — so every completion
carrying either id routes to the materialization. The get completes `:present`
only on equality, else `:error` with `/integrity-failure` or
`/present-but-absent`, the two failures `jing.cljc:283-293` throws on locally.
One completion per materialization, carrying the put id; the record's removal
is the exactly-once guard. This is the one obligation the client carries, and
it is the shape `dao.stream.rpc`'s `:unsent` and `apply/serve-once!`'s
`:pending-response` already sanction: bounded by materializations in flight,
admitting no new caller work, visible as data in the state the driver owns.

*Lifecycle table.* "Routes by" is the id the RPC completion carries, or
*synthesized* where none exists and the materializer publishes under the put
id directly. Every cell is a transition, a no-op, or impossible (with why).
The `dependent` row revision 5 carried is now an ordinary loss row: the
`dao.stream.rpc` fix it waited on landed in `39ad69e` — `allocation-failure`
discharges `:outstanding` through `lose-outstanding` — so an `allocator-error`
terminal completes a `:put` or `:verify-issued` record through its registered
id like any other terminal.

| record phase | event | record | published | routes by |
| --- | --- | --- | --- | --- |
| *(none)* | `request-materialize`: `requested` / `pending-request` | created `:put`, registered under put id | nothing | — |
| *(none)* | `request-materialize`: `request-undeliverable` | created `:put`, registered; completion already in outbox | next drain: `:lost reason`; removed | put id |
| *(none)* | `request-materialize`: `allocator-error` / `terminal` | not created | nothing; outcome returned, no id | — |
| `:put` | order 1 re-attempts the put: `requested` / `pending-request` | unchanged | nothing | — |
| `:put` | order 1 undeliverable, driver `abandon` while unsent, order 3 abandon, or `lose-outstanding` (any terminal, `allocator-error` included since `39ad69e`, or non-terminal `gap` on an outstanding put) | removed | `:lost reason` | put id |
| `:put` | put completion `:inserted` | removed | `:address a :result :inserted` | put id |
| `:put` | put completion `:present` | `:verify-unissued` | nothing | put id |
| `:put` | put completion `:error` | removed | `:error e` | put id |
| `:put` | a get-id completion | impossible — no get id allocated | — | — |
| `:put` / `:verify-issued` with the envelope `:unsent` | non-terminal `gap` | no-op: `lose-outstanding` does not touch `:unsent`; order 1 re-attempts | nothing | — |
| `:verify-unissued` | order 4, not terminal, `rpc/unsent?` true | unchanged; retried next step | nothing | — |
| `:verify-unissued` | order 4, get `requested` | `:verify-issued`, registered under get id | nothing | — |
| `:verify-unissued` | order 4, get `pending-request` | `:verify-issued`, registered; envelope is `:unsent`; order 4 stops issuing this step | nothing | — |
| `:verify-unissued` | order 4, get `request-undeliverable` | `:verify-issued`, registered; completion already in outbox | order 5, same step: `:lost reason`; removed | get id |
| `:verify-unissued` | order 4, get `allocator-error` | removed; the RPC state is now terminal and every other outstanding record completes through `lose-outstanding` | `:lost :dao.stream.rpc/allocator-error` | synthesized; put id |
| `:verify-unissued` | order 4, state terminal (the sweep) | removed | `:lost terminal-reason` | synthesized; put id |
| `:verify-unissued` | driver `abandon` | no-op: this record owns no envelope | nothing | — |
| `:verify-unissued` | non-terminal `gap` | no-op: no outstanding id of this record | nothing | — |
| `:verify-unissued` | any RPC completion | impossible — put id already completed, no get id exists | — | — |
| `:verify-issued` | order 1 re-attempts the get: `requested` / `pending-request` | unchanged | nothing | — |
| `:verify-issued` | order 1 undeliverable, driver `abandon` while unsent, order 3 abandon, or `lose-outstanding` | removed | `:lost reason` | get id |
| `:verify-issued`, get outstanding | driver `abandon` | no-op: `abandon-unsent` touches only `:unsent` | nothing | — |
| `:verify-issued` | get `:found? true`, value `=` payload | removed | `:address a :result :present` | get id |
| `:verify-issued` | get `:found? true`, value `≠` payload | removed | `:error /integrity-failure` | get id |
| `:verify-issued` | get `:found? false` | removed | `:error /present-but-absent` | get id |
| `:verify-issued` | get `:error` | removed | `:error e` | get id |
| `:verify-issued` | order 4 issuance or sweep | impossible — order 4 acts only on `:verify-unissued` | — | — |
| `:verify-issued` | a put-id completion | impossible — the put id completed when the record left `:put` | — | — |
| *removed* | any completion for a formerly registered id | impossible — the RPC layer completes each id once; defensively, an ordinary unrouted completion | — | — |

*Tests owed by that migration, in brief:* socket-free over two ring buffers
with `apply/serve-once!` — round trips, `busy` clears only through `step`,
unsent at detach, `abandon` before rebind, reason pass-through totality by
declaration, diagnostics published once; over `dao.stream.ws` on all three
hosts — round trip, two clients, restart, kill with an accepted and with an
unsent request, `/not-found`; and for the materializer, one case per reachable
row of the table including the deferred-by-`full` hop, an unrelated unsent
occupying the slot, undeliverable at issue and on re-attempt, `abandon` during
a deferred hop, terminal with an unissued and with an issued hop, the
`allocator-error` bystander, and a table-exhaustiveness test.

### Decision 4 — In place, and what stays on v1

`dao.jing`, `dao.jing.file`, and the intake-writer seam in `dao.space` are
rewritten in place. No `dao.jing.observer`, no `dao.jing.v2.*`, no rename
phase, no naming decision, no transitive-dependency gate: after J1b,
`dao.jing` and `dao.jing.file` require no `dao.stream*` namespace directly or
transitively, and that is checked by the requires, not declared.

**Remaining on v1 after this plan, per namespace:**

| namespace | v1 dependency | why it stays | migrates under |
| --- | --- | --- | --- |
| `dao.jing.remote` | `dao.stream.rpc.client`, `dao.stream.rpc.ws` (clj) | Decision 3: replacing the synchronous handle forces async hydration | `dao.space`'s plan |
| `dao.jing.coordinate` | via `dao.jing.remote` (clj, `:dao.jing/remote` branch) | opens the handle above | `dao.space`'s plan |
| `dao.jing.dht`, `dao.jing.dht.node` | `dao.stream.transit`, `dao.stream.udp` | UDP transport deferred by the stream plan | the UDP transport's migration |
| `dao.space.*` beyond the intake-writer seam | `dao.stream` throughout | its own migration | `dao.space`'s plan |

**No registry.** v1's `defopen`/`open!` is gone in v2 and nothing here
reinvents it; `dao.jing.coordinate/open!` is the precedent — a closed `case`,
an explicit code change to add a backend. The observer takes handles.

**Reader-conditional discipline.** `dao.jing.file` keeps three-branch
`#?(:cljd … :clj … :cljs …)` forms with `:cljd` first. `dao.jing`'s observer
gains no host branch. `#?(:clj …)` does not exclude code from the cljd build;
`#?(:cljd nil :clj …)` with `:cljd` first is the only safe spelling, and
`:cljd` in tail position silently fails. Full cljd namespace compilation gates
every phase.

## Divergence register

| v1 behavior | v2 | why |
| --- | --- | --- |
| `observer-state` takes streams; cursor is `{:position 0}` | takes `{:stream :cursor}` members; the composition mints the cursor | every valid cursor comes from the stream |
| signals `:ok`, `:blocked`, `:end`, `:daostream/gap`; malformed results throw | the seven `:dao.stream/…` outcomes; three defect outcomes as data with `:member` and `:result` | the result convention; an outcome outside the closed set still throws |
| a gap is reported, cursor unchanged, never resynced | same rule for every non-progress outcome; `adopt-cursor` is the resync | one rule; resync is a composition decision |
| `dao.jing.file` opens `{:dao.stream/type :append-log}`; handle carries `:log` | private framed file; handle carries `:path :state :write-lock` and the three fns | Decision 2 |
| `file_test` counts records with `ds/next` over the raw log | `dao.jing.file/records` returns the decoded records of a path | the framing is private |
| `dao.space.index/append-ok!` requires `{:result :ok}` from v1 `ds/append!` | requires `:dao.stream/ok` from `dao.stream/append!`; any other outcome throws with the outcome attached | the pool's writer end moves with its reader end |
| transactor intake validation: `satisfies? ds/IDaoStreamWriter` | `dao.stream/writer?` | same |
| `dao.jing.remote` | unchanged on v1 | Decision 3 |

**v1-only tests deliberately not mirrored:** `file_test`'s direct `:append-log`
opens and `count-records`; `log_test.cljc`'s `log-round-trip`,
`log-positional-read`, `multi-cursor`, `reopen-replay`, `close` and
`fd-lock-concurrency` cases, which test the stream surface of a transport that
no longer exists — only `torn-tail-test` is ported, as recovery evidence.

## Phase J1a — `dao.jing.file` without a stream; `dao.stream.log` retired

One change. The observer is untouched here, so `file_test`'s
`observer-convergence-test` still runs the v1 observer over v1 ring buffers
into the new file handle, which is why this phase is green on its own.

- Replace the five `ds/*` sites in `dao.jing.file` with a private framed-file
  section: `open-frames!` (create parent dirs, open read/write, truncate an
  incomplete tail — shorter than four bytes, negative length, or length past
  EOF), `append-frame!` (length prefix + bytes, then sync), `replay-frames`,
  `close-frames!`. Three host branches, `:cljd` first. `put` keeps its order:
  validate, lock, closed check, presence check, append and sync, swap the
  content map, `:inserted`. The requires drop `dao.stream` and
  `dao.stream.log`; the handle drops `:log`.
- Public `records`: `(records path)` returns the decoded, validated
  `[address payload]` vector of a file.
- Delete `src/cljc/dao/stream/log.cljc` and `test/dao/stream/log_test.cljc`
  and any runner reference. Its only consumer was `file.cljc:190`; its
  `defopen :append-log` was a load-time registration into the ambient v1
  registry. `dao.stream.file` (`:file`, live-tail, `yin.io.file`) is a
  different transport, untouched.
- **`file_test`:** `handle-shape-test` asserts no `:log`; every `count-records`
  becomes `(count (records path))`; corrupt-record and raw-duplicate cases
  write raw frames through `append-frame!`; `observer-convergence-test` is
  left as is (it migrates in J1b). **Torn-tail recovery, ported from
  `log_test.cljc:100-135` on all three hosts**, as correctness evidence for
  the truncation loops: write one valid encoded record through
  `append-frame!` first — the original's `(->bytes [11 22])` is not a
  decodable record and `create-content-file` would fail closed on it — then
  hand-write the overlong-length frame as raw host bytes; assert
  `create-content-file` truncates, `records` returns exactly the valid record,
  and a subsequent `materialize!` lands clean and survives reopen. Two more
  cases: a sub-four-byte tail, and a negative length.

Green on clj, cljs (Node), cljd: `file_test`, `btree_durability_test`
(`file-close-reopen-recovery-test` reads back through the new backend; its
comment mentioning "append-log replay" is updated, nothing else), and every
`dao.space` suite, run rather than inspected.

## Phase J1b — The observer on v2, in place, with its nine tests and the pool's writer end

One change, because every piece breaks the others if landed alone.

- **`dao.jing`:** `observer-state`, `observe-step!` rewritten per Decision 1;
  `adopt-cursor` added; the `dao.stream` require replaced by `dao.stream`.
  The content-addressing core is unchanged code.
- **The intake-writer seam in `dao.space`**, the one `src/` change outside
  `dao.jing*`, required for this phase to be green: `index/append-ok!`
  (`index.cljc:526-531`) calls `dao.stream/append!` and requires
  `:dao.stream/ok`; the transactor's intake validation
  (`transactor.cljc:197-201`) uses `dao.stream/writer?`. Nothing else in
  `dao.space` changes: the agent-local stream, `snapshot-datoms`, the
  transactor's own v1 protocols, the published-index `defopen`, and
  `dao.space.query` stay on v1 for `dao.space`'s plan. The pool is one thing
  with two ends, and `dao.jing.md` §Publication names `publish-index!` as its
  writer; cutting one end without the other is not a phase.
- **Nine test files.** `jing_test.cljc`'s observer section (322–560) ported in
  place over v2 ring buffers (`ringbuffer/create!`, cursors minted with
  `stream/cursor … :dao.stream/oldest`), its `open-stream` helper and
  `MalformedResultStream` double rewritten for v2, `mem-handle` unchanged.
  `mem_test.cljc` and `dht_test.cljc`: their own `open-stream` helpers
  (`dht_test.cljc:130`) switch to v2 ring buffers and their observer call
  sites hand in `{:stream :cursor}` members. `file_test.cljc:113-135`: same,
  over the J1a backend. The five `test/dao/space/` files: their `open-intake`
  helpers (`index_test.cljc:72-75`, `query_test.cljc:640-642`, and the
  equivalents in `schema`, `transactor`, `stigmergy`) create v2 ring buffers
  of the same capacity; their observer drain helpers hand in members and
  switch on the `:dao.stream/…` signals; their `open-local` helpers and every
  v1 use unrelated to the pool are untouched.
- **Observer tests, in `jing_test.cljc`:** every existing case ported — plain
  data, empty pool blocked, all blocked, hashing and retrieval, convergence
  from two streams, blocked and ended members do not starve others, all
  ended, drains then end, fair round-robin, strict interleave, cursor advances
  only after successful materialization — with successor-equality assertions,
  never position arithmetic. **gap**: a capacity-2 ring buffer with three
  appends; `:dao.stream/gap` with `:member 0` and a recovery `:cursor`; the
  member cursor unchanged; the other member still progresses; the gap reports
  again next turn; after `adopt-cursor` the member materializes the live
  value. **defect outcomes**: a reified reader scripted to answer
  `cursor-mismatch`, `invalid-cursor`, `transport-error`, and for totality
  each outcome in `dao.stream/outcomes-next`; assert signal, `:member`,
  `:result`, unchanged cursor, and that an outcome outside the set throws.
  **members round-trip**: `(observer-state (remove-nth (:members st) i))`
  keeps the remaining cursors. Missing-cursor and non-reader members throw at
  `observer-state`.

Green on clj, cljs (Node), cljd: the nine files, `btree_durability_test`, and
the full suite; `dao.jing` and `dao.jing.file` require no `dao.stream*`
namespace, checked by grep over their `ns` forms. Confirm `Testing dao.jing-test`
still appears in the Node output.

## Phase J2 — Design prose

`dao.jing.md`:

- *The intake pool* — the entry-shape sentence gains "the cursor is minted by
  the composition from an anchor of its choosing, and is retained exactly as
  the stream returns it".
- *Cursor tracking and recovery* — the four bare results become the seven
  `:dao.stream/…` outcomes with Decision 1's table; resync-is-the-caller's is
  generalized to the three defect outcomes.
- *Implemented surface* — the file backend becomes "a content-addressed store
  backed by a private framed append-only file", replacing "backed by an
  append-only log stream" (the design-document change Decision 2 records; the
  commit says so); `dao.jing.remote` is described as remaining on v1 pending
  `dao.space`'s migration, with Decision 3's client shape named as its target.
- *Open items* — durable checkpoints gain that a checkpoint stores whatever
  cursor value the transport minted, serializability TBD in the contract; a
  new item, **"The content write path as an effect stream"**, per Decision 2,
  out of scope and recorded so it is not mistaken for something settled.

Untouched: *Definition*, *Publication from an agent*, *Materialization rule*,
*Canonical encoding*, *Storage ignorance*, *Physical intake versus semantic
composition*, *Reads*, *Resource lifecycle*, *Lineage*; `dao.jing.dht.md`.

## Host matrix

| phase | clj | cljs (Node) | cljd | notes |
| --- | --- | --- | --- | --- |
| J1a | ✓ | ✓ | ✓ | three host branches in `dao.jing.file`, `:cljd` first; torn-tail fixtures hand-written per host as `log_test.cljc` did |
| J1b | ✓ | ✓ | ✓ | pure `.cljc`; the cljd lane regenerates `test/cljd-out/`, one process at a time |
| J2 | — | — | — | prose |

## Invariants, checked

- **No hidden global state.** `dao.jing` holds no atom; `dao.jing.file`'s
  state and lock are per-handle, as `dao.jing.mem`'s. J1a removes one
  `defopen` from the ambient v1 registry.
- **No implicit control flow.** `observe-step!` returns; the composition calls
  again.
- **No callbacks.** None in any surface touched.
- **No shared mutable state.** A content handle is owned by whoever created it.
- **Interpretation and execution stay separate.** The observer interprets the
  pool; the backend executes the write.
- **No assumed graphs.** Nothing relates content addresses to each other.

## Boundary of this plan

**Touched:** `dao.jing`, `dao.jing.file`, `dao.stream.log` (deleted), the two
intake-writer sites in `dao.space.index` and `dao.space.transactor`, the nine
observer test files, `log_test.cljc` (deleted), one comment in
`btree_durability_test`, `dao.jing.md`.

**Untouched, on v1, per Decision 4's table:** `dao.jing.remote` and
`remote_test`, `dao.jing.coordinate`, `dao.jing.dht*`, everything else in
`dao.space*`, `dao.stream` v1 in every other form.

**Deferred, each to a named home:** the v2 `dao.jing.remote` (Decision 3's
recorded design), the `:dao.jing/remote` coordinate and async hydration
(`dao.data.btree.md` §5.4) — `dao.space`'s plan; the content write path as an
effect stream — a DaoJing architecture item; a content-serving endpoint as
production infrastructure — whatever product surface needs it; the UDP
transport and `dao.jing.dht*` — the stream plan's deferral; canonical
encoding, durable checkpoints and a runner, materialization acknowledgement,
garbage collection — `dao.jing.md`'s open items, unchanged; a v2 file or
append-log transport — until a consumer needs cursors over a durable file.

## End condition

Complete when, on clj, cljs (Node) and cljd:

- `dao.jing` and `dao.jing.file` require no `dao.stream*` namespace, and no
  `dao.jing.v2*` or `dao.jing.observer` namespace exists.
- `dao.jing.file` recovers a torn tail on every host, and `dao.stream.log` is
  gone.
- `dao.jing`'s observer observes v2 reader handles with composition-minted
  cursors, is total over `dao.stream/outcomes-next` by a declaration-driven
  test, retains only successors and adopted cursors, and reports every
  non-progress outcome as data.
- `dao.space.index/append-ok!` and the transactor's intake validation accept
  v2 writers, and every `dao.space` suite is green over v2 intakes.
- `dao.jing.md` describes the v2 observer and the stream-free file backend,
  names `dao.jing.remote` as remaining on v1 with its target design, and
  records the write-path open item.
- `dao.jing.remote`, `dao.jing.coordinate`'s remote branch, and `dao.jing.dht*`
  remain on v1, named here, and nothing else in `dao.jing*` does.

---

## What changed from revision 5, and why

- **Shape.** Parallel `dao.jing.v2.*` namespaces, `dao.jing.observer`, the J5
  rename phase, the naming decision, the transitive-dependency gate, the
  documentation-route fallback, both scope-contingent items, and the
  byte-for-byte compatibility guarantee with its golden-record test are gone.
  The user ruled that with nothing in production the parallel shape protects
  only the test suite; the cutover is in place and the dependent tests move
  with the code that breaks them.
- **Phasing.** Two implementation phases instead of five: J1a (file backend,
  `dao.stream.log` retired) is green with the v1 observer still in place;
  J1b (observer, nine tests, the pool's writer end) is one change because
  none of its pieces is green alone. J3a/J3b/J3c are retired as phases.
- **The intake-writer seam** in `dao.space.index` and `dao.space.transactor`
  is now in scope, as the one `src/` change outside `dao.jing*`. Revision 5
  never faced this because the v1 observer stayed alive for the `dao.space`
  tests; an in-place observer over a v1-written pool is red by construction.
- **`dao.jing.remote` is deferred, not migrated**, with the reason stated:
  its synchronous handle is what `dao.space.index` and the `dao.space` tests
  reach through the coordinate, and replacing it is `dao.space`'s async
  hydration. Decision 3 — the stepped client, `request-materialize`, the step
  order, the lifecycle table — is carried in full as the recorded design for
  that migration, with its `dependent` row converted to an ordinary loss row
  because `39ad69e` landed the `dao.stream.rpc` fix.
- **Torn-tail tests reclassified** from compatibility evidence to correctness
  evidence for the copied truncation loops; the framing is kept because it is
  written and tested, not because it is owed.
- Decisions 1 and 2, the Host-Boundaries open item, and the recorded
  `gpt-5.6-sol` dissent survive unchanged in substance.

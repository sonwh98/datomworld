# dao.stream.waitset — the multiplexed wait-set library

Status: implementation plan, derived from and subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract) and
[`datom.world.md`](./datom.world.md). It picks up the extraction that
`dao.runtime.v2.implementation-plan.md` (completed and deleted in `8c4b7b85`)
explicitly deferred — "If a second consumer appears with the same need, the
resolver becomes a phase then" — after commit `a99f246f` (2026-09-18) deleted
`dao.runtime` and refactored `yin.vm.engine` to poll V2 streams directly,
leaving the multiplexed sweep welded to the VM and no library answer to
cadence. This document is transient: it is consumed as its phases complete.
Drafted 2026-09-19. Phase W0 (census) complete 2026-09-20. Revised
2026-09-20 to the closed two-architect consensus (see *Revision — 2026-09-20
consensus reconciliation* at the end); where a W0 census cell and a later
section disagree, *Census reconciliation* under W0 says which governs.

## The problem

`dao.stream` is total and non-blocking: `next` answers `blocked` as data and
the contract schedules nothing. [`dao.stream.md`](./dao.stream.md) names the
cost in *What it costs* — "polling without readiness notification is O(n) in
streams observed … This is `select` without `select`" — and places the
payment above the stream: "cadence belongs to the runtime driving the
interpreters, and a readiness mechanism belongs there too." Today that
runtime exists exactly once, inside the wrong consumer:

- `yin.vm.engine` carries the only multiplexed sweep: `augment-wait-entry`
  (`engine.cljc:266`), `poll-wait-entry` (`engine.cljc:283`), and
  `check-wait-set` (`engine.cljc:316`). Its entry shape is VM-private —
  parked continuations plus `:reason`/`:cursor-ref` resolved through the
  VM's `:store`, woken entries rebuilt into ready-queue entries. A non-VM
  consumer cannot use any of it.
- Every other consumer hand-rolls its loop: `dao.stream.observer/run-on-stream`
  coordinates one stream for one consumer and returns the moment it sees
  `blocked`; `dao.stream.rpc/poll!`, `dao.stream.apply/serve-once!`,
  `dao.stream.forward`'s callers, `dao.jing`, and the GUI event pump each
  keep their own.
- The per-host cadence containers (`dao.runtime.driver` ×3 — clj
  `LinkedBlockingQueue` take-with-timeout, cljs/cljd microtask plus one
  armed timer) were deleted with `dao.runtime`. The library answer to "what
  calls the loop again, and when" no longer exists for anyone.

The result is the worst of both: consumers that need many waiters must
either become the VM or reimplement the sweep, and consumers that need
cadence must invent a driver. Naive per-consumer polling — one loop per
consumer, all burning CPU on idle media — is the default outcome.

## Strategy

Three moves, each verifiable against a suite that already exists:

1. **Extract the sweep into `dao.stream.waitset`**, a host-branch-free
   `.cljc` namespace requiring only `dao.stream`. Entries are host-owned
   data, a host-composed resolver maps each whole entry to a live handle
   and cursor, and the sweep partitions waiting from woken. No `:resume`,
   no ready queue, no task semantics: the library multiplexes readiness; it
   does not schedule work.
2. **Make `yin.vm.engine` its first consumer.** The engine supplies a
   store-resolver, keeps `engine/check-wait-set` as its public integration
   function, and keeps its machine relation; the library owns the poll
   loop. The private helpers `augment-wait-entry` and `poll-wait-entry` and
   the inline sweep body are deleted, not wrapped.
3. **Restore cadence as `dao.stream.waitset.driver`**: a pure cadence
   function in `.cljc` plus one sleep-or-nudge primitive per host, labelled
   host policy. The host loop is the sole owner of interpreter state — no
   atom, no retained disposition function, no namespace-global state — and
   a contentless `nudge!` lets a composition end an idle sleep early
   instead of at the next armed interval.

The substrate is untouched in every phase. No transport changes, no
readiness protocol, no wake tokens: `blocked` and `full` remain the only
waitable outcomes, discovered by polling, exactly as the contract's *The
readiness extension* requires ("no consumer of this contract may depend on
[a readiness extension] existing").

## What `dao.stream.waitset` is

Stated so no phase has to infer it from the engine's code.

**The threaded state is a value, and it holds only what waits.** The
waitset is `{:waiting []}` from `empty-waitset`. `check` takes it and
returns its successor beside — not inside — that call's results:

```clojure
(check waitset resolver store)
;; => {:waitset {:waiting [...]}   ; thread this into the next check
;;     :woken   [...]              ; this call's results only; host-owned
;;     :store   store'}            ; the host commits this
```

`:woken` never accumulates: a second `check` of the returned `:waitset`
with nothing newly ready returns `:woken []`, whatever the first returned.
No atom, no `defonce`, no host branch in the namespace; it requires only
`dao.stream`.

**An entry is the host's data; the library reads one key.** The library
interprets `:reason` — `:next` or `:put` — and nothing else. Every other
field is opaque, stored and returned exactly as the host gave it: the VM's
readers carry `:cursor-ref` and find their stream through the cursor's store
record, its writers carry `:stream-id` and `:datom`, and both carry
registers (`engine.cljc:272-279,308`); another host names its own keys. Two
reasons exist because only two outcomes can change on their own: `blocked`
for a reader, `full` for a writer (`engine.cljc:286-293`). The engine's
discipline is kept in the form the library can actually guarantee: **the
library never adds a resolved handle or polling cursor to an entry it
retains or returns.** It cannot vouch for what a host put in its own opaque
fields, nor promise that an arbitrary stream payload is serializable.

**A resolver is host composition: two synchronous functions over whole
entries.**

```clojure
{:resolve (fn [store entry] ...)          ; => {:stream h :cursor c :value v} or nil
 :advance (fn [store entry cursor] ...)}  ; => store'
```

`:resolve` maps an entry to the live handle, plus the cursor to read from
(`:next`) or the value to append (`:put`). `:advance` returns the store with
that entry's cursor moved to `cursor`; it is pure over an immutable store.
Both are invoked immediately during `check` and never retained — the shape
of `reduce`'s `f`, not an event callback. The store is opaque to the
library: the VM's `:store` map is one representation
(`augment-wait-entry` becomes its `:resolve` almost verbatim, and the
`assoc` at `engine.cljc:355-359` becomes its `:advance`); a test fake, a
host directory, or a vector are others. The library never dereferences
anything itself.

**The sweep is one complete ordered pass.** `check` is a synchronous,
state-threaded interpreter step with explicit stream effects — it calls
`next` on live media and a parked writer's `append!` — so it is never
described as pure; what is immutable is its store and waitset threading.
Each entry is resolved, polled once, and partitioned: a still-waiting entry
is retained exactly as stored; a resolved entry moves to `:woken` as
`{:entry <the stored entry> :status … :value … :cursor …}`. Every entry is
polled every call: there is no budget and no scan position (see
*Decisions*). Two properties are preserved verbatim from the engine, because
suites depend on them:

- **Shared-cursor write-back between entries.** Whenever a reader's poll
  returns a cursor — the successor on `ok`, the recovery cursor on `gap`
  (`engine.cljc:299-306`) — `check` calls `:advance` and resolves every
  later entry against the advanced store, so co-waiters on one cursor read
  distinct values and a co-waiter behind a `gap` reads from the recovery
  position (`engine.cljc:325-331,350-356`). Wait-set order is therefore
  wake order among co-waiters, and nothing may reorder `:waiting`.
- **A parked writer retries by appending.** The append is an effect of the
  poll; `check` returns the state that records it, and no caller may drop
  a returned `:waitset` or `:store` (the old R1 defect, restated as a law).

**Classification is total, per operation, and declaration-driven.** For a
`:next` entry: `ok` resolves with the value and successor; `end` resolves as
`{:value nil :status :end}`; `gap` resolves with its recovery cursor;
`cursor-mismatch`, `invalid-cursor` and `transport-error` resolve under
their own qualified keywords; only `blocked` waits. For a `:put` entry: `ok`
resolves with the appended value and no cursor; `closed`, `invalid-value`
and `transport-error` resolve under their own qualified keywords; only
`full` waits. Writer `:dao.stream/closed` is a terminal append failure and
is **never** mapped to reader `:end`: the VM raises it as "Stream append
failed" on the immediate path (`engine.cljc:178-191`) and on the woken path
(`engine.cljc:308-312,397-410`), and whether the first attempt parked must
not change that. An outcome outside the operation's declared set is
terminal under its own keyword, never a wait — waiting on an answer the
library cannot interpret would spin.

**Two diagnostics are the library's own, and they also never wait.** An
entry whose `:resolve` answers nil, or answers without a `:stream`, wakes
with `:status :dao.stream.waitset/unresolved`; an entry whose `:reason` is
neither `:next` nor `:put` wakes with
`:status :dao.stream.waitset/unsupported-reason`. In both cases no stream
operation is performed, `:advance` is not called, the store is unchanged,
the original entry is returned intact, and the entry leaves `:waiting`.
These are waitset diagnostics, not additions to DaoStream's outcome sets.

**Woken entries are returned, never dispatched.** The library invokes
nothing beyond the two resolver functions: no callbacks, no continuations,
no queues of its own, and no disposition function accepted anywhere — in
the sweep or in the driver. What the host does with `:woken` — feed a ready
queue, run a consumer step — is the host's own code in the host's own loop.
This is the invariant "do not introduce callbacks" held at the seam.

**Whether anything moved is derived, not stored.** `(seq (:woken result))`
is the single bit a host loop hands its cadence function.

**A non-advancing readiness probe is a composition, not a feature.** A
consumer whose read is one half of a compound commit — `forward-step`
advances its cursor only after the destination append answers `ok`
(`forward.cljc:113-116`); `serve-once!` commits `:request-cursor` only when
the response append answers `ok` (`apply.cljc:252-264`) — must not let the
waitset commit that read. It parks a `:next` entry whose `:resolve` supplies
the consumer's own current cursor and whose `:advance` is identity. The
probe observes a live medium (so it is a probe, never "pure"), commits
nothing, and promises only "run your step again": the woken `:value` and
`:cursor` are advisory and discarded, and the compound step remains the sole
authority, re-reading from its own cursor and applying its own gap policy if
the position was evicted in between. The cost is one redundant `next` per
wake. Three rules bind every probe:

1. **Retire on terminal.** The owner re-parks its probe after a
   *non-terminal* step that still needs observation; a consumer whose step
   ended terminally (completed, failed, `end`) retires the probe and parks
   nothing.
2. **Pending writes stay on host cadence.** A retained response or a
   destination-`full` retry is retried by the consumer's own step every
   round the host runs; source readiness is never a prerequisite for that
   retry, and the write is never handed to a `:put` entry.
3. **Cursor-state isolation.** A probe must not alias cursor *state* that
   any other waitset entry's `:advance` moves — not merely avoid the same
   reference spelling. An identity `:advance` beside an advancing co-waiter
   would wake both on one value or read a position another entry moved.

## Decisions

Settled here so no phase decides them alone.

**The name is `dao.stream.waitset`.** `dao.runtime` was considered and
rejected: it was deleted one commit before this plan (`a99f246f`), the
library deliberately has none of what made the runtime a runtime (no
`:resume`, no ready queue, no tasks), and "name things after what they do
to streams" puts it beside `dao.stream.forward` and `dao.stream.observer`
as another interpreter over the medium. Prose that still names
`dao.runtime` as live is repaired in W5 to point here.

**The resolver seam is adopted now.** The runtime plan refused it for a
single consumer; the census in W0 is the evidence a second consumer class
exists. Adopting it re-opens no review of valid-entry behaviour: for every
entry the VM actually parks, the engine's semantics are preserved
branch-for-branch, and W2's deliverable is that the engine's suite passes
*unchanged*. The one intentional change — malformed entries wake with a
diagnostic instead of waiting forever — is recorded in the *Divergence
register*.

**No budget, no shared queue, no fairness, no priorities, no wait-set
index.** Every `check` is a complete pass in wait-set order, O(n) in parked
entries, which the contract's *What it costs* accepts. A per-round entry
budget was considered and deleted: a fixed prefix starves every entry
behind it, and the obvious repair — rotating polled entries to the back —
reorders co-waiters on a shared cursor and breaks the wake-order law above.
Bounded traversal is deferred until someone specifies its ordering and
interleaving semantics; a scan-position field alone does not establish
equivalence to the full sweep. Consumers here park dozens of entries, not
thousands. If a composition parks thousands, it needs the readiness
extension, not a smarter library.

**The host loop owns everything; the library owns nothing between calls.**
One owner — a thread's loop locals on the JVM, the composition root's own
tick on cljs/cljd, exactly as `yin.repl`'s `poll-loop!` is "the sole owner
… nothing is shared with the reader" (`repl.cljc:194`) — holds the waitset,
the store and the cadence state. It calls `check`, consumes `:woken`
itself, then asks the pure cadence function how long to sleep. No atom
holds interpreter state, no producer on another thread mutates it, and the
driver accepts no function to call with results. Private timer bookkeeping
(a timer id, a wake flag) is host sleep machinery, not interpreter state.

**There are no external parks.** Only the owner parks into its own
waitset. W0 found no consumer that parks into another owner's waitset, and
a host queue carrying entries would be a second, unobservable,
destructive-take medium with no cljs/cljd equivalent. If a composition ever
needs cross-owner submission, the existing pieces already express it: the
submitter appends a park command to a control stream, the owner holds an
ordinary `:next` entry on that stream in its own waitset, and the
submitter's composition may `nudge!`.

**The nudge is a contentless scheduling hint, wired by the composition.**
`nudge!` asks the host's sleep primitive to end the current sleep early.
It carries no entry, outcome, continuation or function; repeated nudges
coalesce into one wake; it is non-reentrant — it never runs `check` or any
interpreter work inline in the caller, only requests a later tick of the
owner. Adapters never hold a driver reference: an adapter "is handed only
the deposit operation and its own host resource" (`datom.world.md`, *Host
Boundaries*), so the *composition* wraps the deposit operation it hands the
adapter with the nudge, and the adapter stays ignorant of cadence. Any
composition may nudge after any transition it caused that can change a
waiter's answer — an append, a `close!` (which turns `blocked` into `end`),
a transport- or host-level transition that frees capacity (a read never
does: reads are non-destructive) — and none is required to. Periodic
polling continues regardless and is the only truth: a lost nudge costs at
most the currently armed interval — which may be the backoff ceiling, not
`:poll-ms` — and never lost work. The library registers nothing with a
transport and `append!`'s outcome set is unchanged.

**Cadence is a pure function; its parameters are data the host supplies.**
`(cadence-step cadence-state moved?) → {:cadence-state … :sleep-ms …}`
computes the idle curve — `:poll-ms` base interval, optional `:backoff` up
to a ceiling, reset by any wake — and nothing else. It never calls `check`,
never reads a clock and never touches a stream, so it is testable on all
three hosts with no fixture at all. The right cadence for a REPL is not the
right cadence for a serving endpoint; neither is the library's to know.

**Fixtures are v2 ring buffers or reified handles.** Where a test needs an
outcome the ring buffer never produces — `full`, `transport-error`,
`invalid-value` — a reified handle returns the scripted outcome, the same
rule the runtime plan set. No v1 fixture returns from the deleted test
tree.

## Phase W0 — Census

Record the evidence the resolver decision rests on. One table in this
document: every loop in the tree that polls a dao.stream medium — caller,
file, what it waits on, single-stream or multiplexed, what a waitset
adoption would change or why nothing should change. Expected rows:
`yin.vm.engine` (inline sweep → W2), `dao.stream.rpc/poll!`,
`dao.stream.apply/serve-once!`, `dao.stream.observer/run-on-stream`
(single-stream; expected to stay), `dao.stream.forward` callers inside
`dao.stream.serving`, `dao.jing`'s materializer, `dao.gui.event`'s pump,
`yin.repl`'s host shell. The table decides W4's adoption list and records,
for the consumers that stay on `observe/step`, why that remains correct.

Deliverable: the table, plus this plan's status line updated.

### The census

Recorded 2026-09-20 against the working tree at `79cea49e`; every row was
verified against the source named, and the line references are that tree's.
Revised same day (r2) after the architect's sign-off: two multiplexed
production waiters added (`dao.stream.ws`'s ack sweep, `yin.repl.serve`'s
per-session step), two r1 no-loop claims corrected (`forward-step`,
`connect/observe`), and the re-check added the FFI bridge loop,
`snapshot-datoms`, and the stepped client's issue loop.

| Loop (caller) | Where | Waits on | Scope | What a waitset adoption changes |
| --- | --- | --- | --- | --- |
| `yin.vm.engine`'s inline sweep, run by `run-loop` on every parked machine (`engine.cljc:381`) | `augment-wait-entry` `src/cljc/yin/vm/engine.cljc:266`, `poll-wait-entry` `:283`, `check-wait-set` `:316` | Reader `blocked` and writer `full` on streams resolved through the VM's `:store`; shared-cursor write-back between entries (`:350-356`) | Multiplexed (N parked entries, polled in wait-set order) | → W2. The engine is the library's first consumer; the three inline functions are deleted, not wrapped |
| `dao.stream.rpc/poll!` — cadence is its caller's (the REPL driver, `dao.jing.remote`, `dao.jing.remote.step`) | `src/cljc/dao/stream/rpc.cljc:426` | Its session's one response medium; `blocked` answers `:dao.stream.rpc/idle` at once — the internal loop is only the `budget` drain | Single-stream | Nothing inside `poll!`: it is already the one-medium read step a waitset entry polls. Only its cadence callers could adopt a W3 driver |
| `dao.stream.apply/serve-once!` — no production caller in src; named only by the delegate `dao.stream.rpc/serve-once!` (`rpc.cljc:106`) | `src/cljc/dao/stream/apply.cljc:301` | Request-medium `blocked`, then response-medium `full` (the pending response stays in state, so a retry cannot invoke the handler twice) | One session per call — two mediums, strictly sequential; not multiplexed | Nothing today: nothing in src loops over it. A host serving many sessions is what turns each session's request medium into a wait-set entry; that host, not this step, is the adoption site |
| `dao.stream.observer/run-on-stream` — `yin.repl.core`'s evaluator stage (`core.cljc:543`), `yin.vm.macro`'s expander (`macro.cljc:1341`), `yin.vm.encoder` (`encoder.cljc:296`), `dao.space.index`'s walk sessions (`index.cljc:601-627`) | `src/cljc/dao/stream/observer.cljc:217` | Its one observer's medium; returns the session the moment `observe/step` answers `blocked` or `end`, looping only while the consumer is ready | Single-stream, one consumer | Nothing, as expected. Every caller coordinates exactly one medium per session — the correct single-stream shape W4 refuses to convert |
| `dao.stream.serving`'s tick `step!` — driven by `yin.repl.serve/step` (`serve.cljc:701`) and `dao.jing.remote`'s daemon ticker (`remote.cljc:981-988`) | `src/cljc/dao/stream/serving.cljc:330` — `poll-offers!` `:260` (one reader per configured slot), `poll-control!` `:272`, per-session `poll-traffic!` `:286` and `advance-forwarder!` `:310`, the `forward-step` caller (`src/cljc/dao/stream/forward.cljc:67` — itself a bounded budget step, its `:batch-budget` and resume-allowance loop at `:101-140`; `:retry` covers both source `blocked` and destination `full`) | Every offer slot's reader, the control reader, and each established session's traffic reader and forwarder | Multiplexed (slots plus sessions, one pass per tick) | → W4 candidate. Each per-medium poll is exactly a wait-set entry — `:next` for the readers, `:put` for a destination-full forwarder; adoption deletes the tick's inline sweep and hands cadence to the W3 driver |
| `dao.stream.ws`'s acknowledgement-slot sweep `endpoint-step` — invoked by `serving/step!` inside the same tick (`serving.cljc:339-342`, composed as `:endpoint-step`) | `src/cljc/dao/stream/ws.cljc:565` (slot polls `:574-591`, expiry `:595-603`) | Each pending slot's acknowledgement reader (`stream/next` `:580`), plus dead-slot release and clock-explicit expiry | Multiplexed (one poll per configured slot) | Recorded, **deliberately unchanged**: adopting it would change the `dao.stream.ws` transport, which this plan's boundary forbids (see *Boundary* — "Any change to `dao.stream` … transports"; the same exclusion W4's git-diff check enforces). Identifying the loop does not require adopting it; if a later transport-scoped plan takes it up, each pending slot's ack medium is a `:next` entry |
| `dao.jing`'s intake materializer `observe-step!` — no production caller in src; driven by `test/dao/jing_test.cljc` | `src/cljc/dao/jing.cljc:505` | Every non-ended intake-pool member's stream, one round-robin pass, at most one payload materialized per call; blocked members never hide later members | Multiplexed (N pool members) | Nothing until a host composes a pool. When one does, the pool is the multi-waiter class W4's "dao.jing if its materializer holds more than one waiter" names — the structure is already a wait set in everything but name |
| `dao.gui.event`'s pump `advance` — driven append-side by `datomworld.demo.voxel_input` (`voxel_input.cljc:235-238`, pumped after each append) and `datomworld.demo.artifact_runner` (`artifact_runner.cljc:168-184`, bounded, host-progress-driven) | `src/cljc/dao/gui/event.cljc:762` | The binding's single `:runtime-input` medium (`blocked` → `:blocked`) and its pending output writes (`full` → `:parked`, retried before the next read) | Single input stream per binding, plus bounded pending `:put` retries | The demos never idle-poll, so nothing burns CPU today, and `advance` is already one-medium-per-call. A waitset pays only if a composition holds several live bindings or arms idle cadence; W4's "GUI event pump" expectation resolves to these host drivers, not to `advance` itself |
| `yin.repl.serve`'s per-session step — `advance-sessions` (`serve.cljc:617`) reducing `session-step` (`:586`) over every adopted session, called by `serve/step` (`:704`) inside the shell's tick | `src/cljc/yin/repl/serve.cljc:586-619` | Per session: a retained response's append retry (`:pending-response` → `deliver-response`, `:592`) or the session's request medium (`stream/next` `:595`; `blocked` keeps the session parked) | Multiplexed (every adopted session, one bounded step each) — production multi-waiter | → W4 candidate. Each session holds **one active waiter, not two**: a pending response's `:put` retry runs before another request `:next` is permitted (`session-step`'s branch order, `:592-595`), so the alternatives are mutually exclusive and that ordering must be preserved in any adoption. The session's waiter is exactly a wait-set entry, stepped today by the shell's fixed timer |
| `yin.repl`'s host shell — one tick owner per host: clj `poll-loop!` (`repl.cljc:194`, `Thread/sleep`), cljs `run-node!` (`repl.cljc:267`, `setInterval`), cljd `run-dart!` (`repl.cljc:352`, `Timer.periodic`) — all stepping `step-all` (`repl.cljc:93`) | `src/cljc/yin/repl.cljc:21` (`tick-millis`, 25 ms); `repl-step` `src/cljc/yin/repl/driver.cljc:536`, whose `poll-remote` (`:480`) → `adapter/poll-responses` (`src/cljc/yin/repl/adapter.cljc:202`) → `rpc/poll!`; `serve/step` (`src/cljc/yin/repl/serve.cljc:684`) | Local input drain, the remote session's response medium, and the served endpoint's whole serving tick — every 25 ms whether or not anything moved | Multiplexed (one tick owner over all of it) | This is the fixed-timer cadence W3's driver replaces: the tick owner becomes a driver with `:poll-ms`/`:budget`, and the append-side readers (the per-host read-line handlers, `request-stop!`) become `nudge!` callers. The endpoint's multiplexing itself moves to `waitset/check` via the serving row above |

Loops found beyond the expected rows:

| Loop (caller) | Where | Waits on | Scope | What a waitset adoption changes |
| --- | --- | --- | --- | --- |
| `dao.jing.remote`'s `connect-content!` establishment wait — JVM-only host policy, named as such by `dao.stream.md` OD-5 | `src/cljc/dao/jing/remote.cljc:733-747`, stepping `await-established-step` (`:363`, one `rpc/poll!` at `:373`) per round, then `Thread/sleep` | The attachment's `/established` lifecycle element on one response medium, to a deadline | Single-stream | A driver plus waitset would replace the sleep-poll, and a nudge would end the sleep at establishment instead of at the next interval. Deliberately out of scope: this is the documented blocking host policy |
| `dao.jing.remote`'s `call!` request wait | `src/cljc/dao/jing/remote.cljc:587-636`, stepping `call-step` (`:294`, `rpc/poll!` at `:308`) per round, then `Thread/sleep` under a deadline | One awaited call's completion; interruption is a first-class exit | Single-stream (one awaited call per thread) | As above — one sleeping JVM thread per in-flight call is precisely the idle cost the nudge halves |
| `yin.repl.connect`'s lifecycle observation `observe` — stepped once per tick by the shell driver's `observe-connection` (`driver.cljc:489`) | `src/cljc/yin/repl/connect.cljc:525` (budgeted loop `:537-564`) | The connection's one lifecycle medium, up to `lifecycle-budget` elements per call; `blocked` ends the round | Single-stream | Nothing: one medium, budget-bounded, cadence already the shell tick's. Reclassified in r2 from r1's wrong no-loop claim |
| `dao.jing.remote.step`'s client `step` — cadence its driver's; no production driver in src | `src/cljc/dao/jing/remote/step.cljc:471` (one `rpc/poll!` at `:492`; the `issue-pending-verifies` loop `:385-463`, looping `:396-457`, issues verify requests until the writer answers `full`) | Its session's response medium (`rpc/poll!` budget) and the writer's room for unsent verify requests | Single-stream, bounded budget step | Nothing by itself: it is the stepped shape OD-5 holds up as the portable interface. Only a driver stepping many clients would turn their media into wait-set entries |
| `yin.vm.ffi`'s bridge loop `maybe-run` — the VM runner composition wraps its raw runner with it | `src/cljc/yin/vm/ffi.cljc:229` (loop `:237-244` over `bridge-step`, `:173`, whose read of the call-in stream answers `blocked` at `:219`) | The VM's one FFI call-in medium: run until blocked, bridge one request or retry a pending response, re-run; blocked with nothing handled returns the parked VM | Single-medium (one bridge stream per VM) | Nothing while a VM bridges one medium — the loop already interleaves run and poll. A host multiplexing several VMs' bridge media is where wait-set entries would come from |
| `dao.postgraphics.terminal`'s viewer pump `step-until-blocked` — driven by the demo, web, and flutter hosts after renders or frames (`src/cljd/dao/postgraphics/flutter.cljd:339`, `src/cljs/dao/postgraphics/web.cljs:106`) | `src/cljc/dao/postgraphics/terminal.cljc:169`, over `step` (`:125`, at most one frame read per call) | Its one frame medium; a bounded drain-until-blocked, after which the host retries at its next opportunity | Single-stream | Nothing should change. Same drain-on-progress shape as the GUI pump; no idle loop exists to replace |
| One-shot catch-up drains: `dao.space.query/snapshot`, `dao.space.index/snapshot-datoms` (callers `yin.vm.ledger`, `dao.space.transactor`, `dao.space.schema`), and `datomworld.demo.continuation_transport` | `src/cljc/dao/space/query.cljc:299`; `src/cljc/dao/space/index.cljc:428` (drain `:445-452`, over a complete-retention medium, so `gap` cannot occur); `src/cljc/datomworld/demo/continuation_transport.cljc:121,153` | Catch one cursor up to `blocked`/`end` once, for a relation, a ledger base, or a demo handoff | Single-stream | Nothing. A snapshot is a read that ends, not a waiter; there is no cadence across rounds to hand a driver |

Reclassified in r2 — r1 wrongly called these loop-free, and the audit was
right: `dao.stream.forward/forward-step` (`forward.cljc:101-140`),
`yin.repl.connect/observe` (`connect.cljc:537-564`), and
`dao.jing.remote.step` (`step.cljc:396-457`) each contain a bounded in-call
loop. They are bounded budget steps, not waiters: they poll within one call
up to a budget, a resume allowance, or a pending list, and cadence stays
with their callers, so they appear above as rows rather than as wait-set
candidates. Still loop-free, so no row: `dao.stream.observe/step`
(`observe.cljc:58` — one read, one effect, total classification) and
`dao.await`, which only emits `:stream/next`/`:stream/put` effects
(`await.cljc:66`) — its `resume` just re-runs the VM (`await.cljc:227`), so
its waiting is the engine row above. Every expected row was found in the
source; none was invented.

### Census reconciliation

The table rows above are the committed W0 record and are kept verbatim.
Their *Loop*, *Where*, *Waits on* and *Scope* columns stand. Three
*adoption* cells predate the 2026-09-20 consensus; where they disagree with
the sections below, the sections govern:

- **Engine row** — "the three inline functions are deleted, not wrapped":
  the two private helpers and the inline sweep body are deleted;
  `engine/check-wait-set` stays public as the VM integration function (W2).
- **`dao.stream.serving` row** — "`:put` for a destination-full forwarder":
  withdrawn. No forwarder or serve-once write migrates to a `:put` entry;
  the reader handoff is the non-advancing probe and pending writes stay on
  host cadence (W4).
- **`yin.repl` host shell row** — "a driver with `:poll-ms`/`:budget`":
  `:budget` is deleted; the tick owner adopts `cadence-step` and the
  sleep-or-nudge primitive (W3).

Two cells are load-bearing for W4 and are restated there, not altered: the
`dao.stream.ws` acknowledgement sweep is **deliberately unchanged** under
the transport exclusion, and each `yin.repl.serve` session holds **one
active waiter, not two**, with the pending-response retry ordered before
the next request read.

## Phase W1 — The sweep

`src/cljc/dao/stream/waitset.cljc`, no host branch, requiring only
`dao.stream`:

- `empty-waitset`, `park` (append an entry to `:waiting`; total — it
  rejects nothing, because `check` classifies everything), and `check` as
  specified above.
- **Classification, pinned per operation.** Two literal expected maps —
  one for `:next`, one for `:put` — each asserted to have a key set *equal*
  to `dao.stream/outcomes-next` and `dao.stream/outcomes-append`
  respectively, so a future contract outcome fails the suite on domain
  inequality instead of passing through a default branch. The maps are
  separate because the operations differ: reader `ok` carries a successor
  and writer `ok` does not; `closed` exists only on the append side and is
  terminal there. Each map's entries are then driven through a reified
  handle: exactly `blocked` and `full` wait. A keyword outside the declared
  set is asserted terminal under its own keyword, for both operations.
- **The preserved properties.** Shared cursor state wakes co-waiters on
  distinct values in one call, in wait-set order. A scripted `full`-once
  handle shows exactly one append per poll, the resolved writer in `:woken`
  with its host value, and no polled state discarded.
- **Gap recovery between co-waiters.** Two readers share a cursor over an
  evicting ring buffer: the first wakes with `gap`; the second resolves
  against the recovery cursor in the same call and reads the earliest
  retained value. Assert wake order, both statuses, and the final store
  cursor.
- **A non-VM store.** The resolver suite runs against a store that is not a
  map of `{:id …}` records (a vector or an association list with its own
  ref shape), so the implementation cannot depend on the VM's store
  structure; and against entries using host key names the library has never
  heard of, returned intact.
- **Diagnostics.** A nil `:resolve` and an unknown `:reason` each wake with
  their qualified status, leave `:waiting`, perform no stream operation
  (a counting handle proves it), and leave the store unchanged.
- **Result lifecycle.** A repeated `check` of the returned `:waitset`
  returns only that call's `:woken`; no returned or retained entry contains
  a key the host did not put there (in particular no `:stream` and no
  polling cursor injected by the library); with plain-data fixtures, the
  returned `:waitset` and `:woken` round-trip through `pr-str`/`read-string`.
- **The probe composition.** An identity-`:advance` `:next` entry wakes on
  readiness and leaves the store identical; re-parking it after the
  consumer's own step advances the consumer's cursor wakes on the next
  value, not the same one.

Deliverable: suite green on clj, cljs (Node) and cljd.

## Phase W2 — The engine becomes a consumer

`engine/check-wait-set` stays: it is public, and `semantic.cljc:476`,
`ast_walker.cljc:665` and `engine_test.cljc:257-324` call it directly. Its
body becomes the VM integration — invoke `waitset/check` with the engine's
store-resolver, commit the returned `:store` and `:waiting`, raise any
waitset diagnostic, and build ready entries with
`make-woken-run-queue-entries` (which stays the engine's; the integration
stamps each woken result's `:status` onto its ready entry, where
`terminal-resume-outcome` already reads it). The private
helpers `augment-wait-entry` and `poll-wait-entry` and the inline `loop`
body are deleted; their logic lives once, in the library and in the
engine's `:resolve`/`:advance`. Wait-entry key names (`:cursor-ref`,
`:stream-id`, `:datom`, `:stream`) do not change: the resolver understands
them, the library does not. The run loop, park semantics, and
terminal-outcome raising for `:next`/`:put` entries are unchanged.

One new path, handled before restoration: `terminal-resume-outcome` checks
status only for `#{:next :put}` reasons (`engine.cljc:397`), so a woken
`:dao.stream.waitset/unsupported-reason` or `…/unresolved` entry would
otherwise fall through to `restore-fn`. The engine raises a waitset
diagnostic as an error naming the status and the entry — in
`check-wait-set` or ahead of the restore in `resume-from-run-queue`, but
never after a continuation is restored. This is the only behaviour change,
it affects only malformed entries (no park site in `yin.vm` produces one,
and no existing test parks one), and it gets one focused new test. Existing
suites staying green does not prove this path; the new test does.

Deliverable: the full `yin.vm` suite — engine, walker, FFI, macro, parity —
green on all three hosts **without edits to existing tests**, plus the one
added diagnostic test; `grep` confirming `augment-wait-entry` and
`poll-wait-entry` are gone and `check-wait-set` contains no `stream/next`
or `stream/append!` call of its own. Any existing test that had to change
is a defect in W1, not in the test.

## Phase W3 — Cadence containers (`dao.stream.waitset.driver`)

Two layers, split so that everything decidable as data is decided in
`.cljc` and the host files hold only what a host alone can do — sleep, and
be woken.

**The pure layer** — `src/cljc/dao/stream/waitset/cadence.cljc`:
`(cadence-step cadence-state moved?) → {:cadence-state … :sleep-ms …}` over
`{:poll-ms … :backoff {:factor … :ceiling-ms …}}`. A wake resets the curve
to `:poll-ms`; an idle round climbs it to the ceiling and stays there. It
never calls `check`, reads no clock, touches no stream. Tested on all three
hosts with no fixture.

**The host layer** — one namespace name, three host files, each docstring
labelled *host policy*: `src/clj/dao/stream/waitset/driver.clj`,
`src/cljs/dao/stream/waitset/driver.cljs`,
`src/cljd/dao/stream/waitset/driver.cljd`. Each provides a *wake source*
and nothing that holds interpreter state:

- **clj**: the wake source wraps a `LinkedBlockingQueue` carrying
  contentless wake tokens only. `(sleep! wake sleep-ms)` polls it with the
  timeout and drains surplus tokens, so repeated nudges coalesce;
  `(nudge! wake)` offers one token. The owner is an ordinary `loop` on one
  thread with the waitset, store and cadence state as loop locals:
  `check` → consume `:woken` → `cadence-step` → `sleep!` → recur. This is
  `select` with the wakeup pipe; the sweep is the syscall.
- **cljs/cljd**: there is no sleeping loop, so the wake source arms
  exactly one timer at `:sleep-ms` (cancel-and-rearm; Node timers
  `unref`ed) whose firing calls the **composition root's own tick** — the
  function the composition handed `arm!`, exactly as `run-node!` hands its
  step to `setInterval` today (`repl.cljc:267`). That tick owns the state
  box and performs `check` → consume → `cadence-step` → `arm!`. `nudge!`
  cancels the timer and schedules that same tick once on a microtask; a
  pending-flag makes repeated nudges coalesce and keeps a nudge issued
  from inside the tick from re-entering it. An idle composition costs one
  timer, zero busy loops.
- The tick is the composition root arranging its own execution — the
  bottom of the stack, which on these hosts only a host timer can be. It
  is not a disposition callback: the driver never sees `:woken`, the
  waitset, or the store, and passes the tick no arguments.
- No `make-driver` container, no atom holding a waitset, no `:budget`, no
  external-entry submission, no `run-loop!` that dispatches results.
- Tests, over v2 ring buffers and an owner loop written in the test: a
  parked wait set keeps being polled with no nudges; `nudge!` beats the
  timer; repeated nudges coalesce into one tick; a nudge from inside a tick
  does not re-enter it; an idle owner stays alive at the backoff ceiling
  and a wake resets the curve; a composition-wrapped deposit nudges while
  the adapter it was handed to holds no driver reference.

Deliverable: the cadence namespace, three drivers, and suites on their
hosts. No production
consumer is switched in this phase, and the plan says so rather than
inventing one: the drivers exist because a sweep without cadence has no
answer to "what calls `check` again" on any host, and because `a99f246f`
deleted the only answer that existed.

## Phase W4 — Consumer adoption

The adoption list is the committed W0 census, not a forecast. Three
assignments exist. An **advancing entry** lets the waitset commit the read
— correct only where the read is the whole step (the VM). A
**non-advancing probe** is the reader handoff for a compound step, under
the three probe rules. **Host cadence** means the consumer's own step keeps
every poll and the owner only adopts W3's `cadence-step` and
sleep-or-nudge. No site migrates a write: a forwarder's destination retry,
a retained response, and the GUI pump's pending output all stay in their
own steps, retried every round the host runs, never gated on source
readiness.

| W0 site | Assignment | What changes, and what must survive |
| --- | --- | --- |
| `yin.vm.engine` sweep | Advancing entries | Done in W2 |
| `yin.repl.serve` per-session step (`serve.cljc:586-619`) | Probe on the session's request medium **or** host cadence for its pending response — never both | **One active waiter per session**: while `:pending-response` is set the session parks no probe and `deliver-response` is retried every round; only a session with nothing pending parks a probe. A woken probe selects the session for `session-step`, whose own `stream/next` re-reads and whose `:pending-successor` still commits the cursor only when the response append answers `ok`. A `:terminal` session retires its probe. `advance-sessions`'s unconditional read of every session is what the adoption deletes |
| `dao.stream.serving/step!` — offers, control, traffic, forwarder (`serving.cljc:330`) | Host cadence | Unchanged as a step. The tick carries the clock-explicit `:endpoint-step` that must run every round, already polls each medium exactly once, and orders acceptance before observation; a probe sweep would double every read and delete nothing. The forwarder stays `forward-step`; the census's `:put` migration is withdrawn. Its tick owners adopt W3 cadence |
| `dao.stream.ws` acknowledgement sweep `endpoint-step` (`ws.cljc:565`) | **Deliberately unchanged** | The transport exclusion in *Boundary*; recorded in W0, not adopted here |
| `yin.repl` host shell tick owners (`repl.cljc:194,267,352`) | W3 cadence adoption | The fixed 25 ms tick becomes `cadence-step` plus `sleep!`/`arm!`; the per-host read-line handlers and `request-stop!` are composition-wired `nudge!` callers. `moved?` is the owner's to compute: any woken probe, any progress a host-cadence step reports, **or any outstanding pending write** — a retained response must never wait out a backoff ceiling |
| `dao.jing.remote`'s daemon ticker (`remote.cljc:981-988`) | Host cadence; W3 adoption optional | JVM host policy already named as such; it may swap `Thread/sleep` for `sleep!` and gain nothing else |
| `dao.stream.apply/serve-once!` | None today | No production loop exists. A future host serving many sessions parks one probe per session under the three rules; the retained response never becomes a `:put` entry |
| `dao.jing` intake `observe-step!` | None today | No production caller. A future adopter first decides whether its read is one half of a compound commit (then probe) or the whole step (then advancing entry); the plan does not pre-decide it |
| `dao.gui.event/advance` and its demo drivers | Unchanged | Single input medium per binding, append-side pumped, never idle-polls; pending output retries stay in `advance` |
| `rpc/poll!`, `observer/run-on-stream`, `connect/observe`, `remote.step/step`, `ffi/maybe-run`, the postgraphics pump, the one-shot snapshots, `connect-content!`, `call!` | Unchanged | Single-stream or bounded budget steps, recorded in W0 with the reason each stays |

Single-stream consumers stay on `observe/step` and
`observer/run-on-stream`: the waitset is for owners holding many waiters,
and converting a one-stream loop onto it is the over-adoption failure mode
this phase exists to avoid. Each waitset adoption deletes the unconditional
per-waiter poll it replaces, and each cadence adoption deletes the fixed
timer it replaces; an adoption that deletes nothing has wrapped, not
migrated, and is reverted.

Deliverable: `yin.repl.serve` and the `yin.repl` shell suites green on all
three hosts with the unconditional session sweep and the fixed tick gone;
focused tests that a session with a pending response is retried while its
source is `blocked`, that a terminal session leaves no probe parked, and
that no probe shares cursor state with an advancing entry.

## Phase W5 — Design prose and stale references

Scoped by a check, not a guess. `grep -rn "dao\.runtime" src docs
--exclude-dir=cljd-out` at `79cea49e` finds no occurrence in `src` — the
`dao.stream.observe` docstring this plan's first draft named is already
clean, so that bullet is dropped — and these in `docs`:

- **Live claims, rewritten in this phase.**
  [`dao.await.md`](./dao.await.md) names `dao.runtime` as live at some
  fifteen sites: the stack diagram, *The Scheduler* mapping, the layering
  rules, the park/resume walkthrough ("wrapped as a `dao.runtime` task",
  "invokes the task's `:resume` function"), the non-goals, and the
  verification list ("task maps with `:resume`"). All are rewritten to the
  relation that exists: Yin owns parking and restoration;
  `engine/check-wait-set` calls `dao.stream.waitset/check`, which
  classifies; the host cadence layer decides when to check; and the VM —
  not a `:resume` callback — turns woken data into ready-machine state.
  `yin.vm.universal-continuation-format.md` (`:535`, `:1026`) and
  `yin.vm.v2.divergence-register.md` (`:79`) each attribute the writer
  retry to `dao.runtime`; they name `dao.stream.waitset` instead.
- **One sentence added.** [`dao.stream.md`](./dao.stream.md) *The
  readiness extension* points at this library as the standing answer to
  *What it costs*: poll-based, substrate-untouched, and the reason no
  consumer needs the reserved extension to sleep between rounds.
- **Historical mentions, left alone and excluded from the end-condition
  check.** `docs/handoff.md`, `docs/jolt-experiment.md`,
  `docs/orchestrator-log.md`, `dao.jing.remote.implementation-plan.md`'s
  not-planned list, and this plan itself describe `dao.runtime` as a thing
  that existed or was deleted. That is true and stays.
- **Housekeeping.** The generated `test/cljd-out/dao/runtime-test_test.dart`
  is a stale artifact cleared by the next cljd lane regeneration, not by
  hand.

Deliverable: the four docs updated and the scoped grep clean — every
remaining `dao.runtime` hit is in the historical list above.

## Divergence register

Every place the library deliberately differs from what it replaces, with
the reason.

| Prior form | `dao.stream.waitset` | Why |
| --- | --- | --- |
| Engine entries carry VM registers and ride the machine relation | Unchanged for the VM: entries are opaque host data and the library reads only `:reason`; disposition is host-owned | A VM entry is a continuation; the library multiplexes readiness. Carrying registers opaquely couples nothing; inspecting or restoring them would |
| Engine resolves refs against its `:store` internally (`engine.cljc:266`) and `assoc`s the successor into it (`:355-359`) | The resolver is a parameter — `:resolve` and `:advance` over whole entries — and `check` returns the new store | The deferred decision, adopted: a second consumer class appeared (W0), and a generic library cannot write to a store whose shape it does not know |
| Engine threads `:wait-set` and appends to `:ready-queue` in one state map | `check` returns `{:waitset :woken :store}`; `:woken` is per-call and never threaded | A result the host has consumed is the host's; threading it invites accumulation |
| A nil handle reached `stream/next` and failed as a host dispatch error | Wakes as `:dao.stream.waitset/unresolved`, no stream operation performed | An answer the library cannot interpret is terminal data, never an exception and never a wait |
| `dao.runtime` had `:resume`, a ready queue, and task semantics | None of the three | Task scheduling is why the old runtime was coupled to its consumers; readiness multiplexing is the reusable core. A library with `:resume` is a scheduler, and schedulers are per-host policy |
| Deleted R2 drivers polled on a fixed timer only | A contentless `nudge!` ends an idle sleep early | The timer-only shape pays the full armed interval on externally-appended data; the nudge cuts that latency without touching the substrate, and losing one costs only that interval |
| Deleted R2 drivers held a container (state, queue, external work submission) and ran the loop | The host loop owns all state; the library supplies a pure `cadence-step` and a sleep-or-nudge primitive; no external parks, no budget | One owner, no shared mutable state, no retained callback. Cross-owner submission is a control stream plus a nudge, which needs no new mechanism |
| `run-once`/`run-loop` combined ready work and polling | `check` polls; the host's own loop consumes `:woken` | The R1 split, restated: a polled state is never discarded, and the sweep owns no downstream work to drop |
| Unknown wait `:reason` kept waiting forever (`engine.cljc:313`, the old runtime default) | **Intentional divergence:** wakes as `:dao.stream.waitset/unsupported-reason` and leaves `:waiting`; the VM raises it before restoration | An entry the library can never poll is a permanent spin, not a wait. No `yin.vm` park site produces one and no existing test parks one, so the unchanged-suite condition holds; W2 adds the focused test |
| An answer that failed the outcome-map convention reached the classifier and woke with `:status nil` | An answer that fails `stream/validate-outcome`'s shape validation — a non-map, a missing or unqualified `:dao.stream/outcome`, a declared outcome missing its required keys — wakes as `:dao.stream.waitset/invalid-answer`: terminal, out of `:waiting`, no `:advance` | "An answer the library cannot interpret is terminal data, never an exception and never a wait"; a `:status nil` wake is unnameable by any host branch. A well-formed outcome outside the declared set stays terminal under its own keyword, as before (W1 review, P2) |
| A poll-time throw — a `:stream` resolved from a stale store record that fails its protocol, a defective transport — escaped `check` as a host dispatch error and voided the sweep | The per-entry poll is wrapped in a catch-all and the throw folds into `:dao.stream.waitset/invalid-answer`; the entry leaves `:waiting`, because an entry whose handle throws cannot be trusted to poll again | The engine let the throw escape; a library serving several consumers returns data instead, and re-parking such an entry would spin the sweep into the same throw every round (W1 review, P2) |
| A resolver throw escaped the engine's sweep and voided it | Unchanged, now stated as the contract on resolvers: resolvers must not throw, and a throw voids the sweep — `check` does not return, and re-threading the previous waitset re-polls every entry, re-appending writers that already appended (`check` docstring) | Faithful to the engine. Catching around host resolver composition would hide host defects inside a diagnostic the library cannot ground; the no-dropped-state law is documented as the resolvers' obligation instead (W1 review, P2) |

## Host matrix

W1 and W2 are `.cljc` over `dao.stream` with no host branch, as is W3's
`cadence` namespace; W3's drivers are three host files by construction.
Every phase green on clj, cljs (Node) and cljd; per the standing rule,
confirm `Testing dao.stream.waitset-test`,
`Testing dao.stream.waitset.cadence-test` and
`Testing dao.stream.waitset.driver-test` appear in the Node output rather
than assuming discovery. The cljd test lane regenerates `test/cljd-out/`;
only one process may own it at a time.

## Boundary of this plan

**Not in this plan, by design:**

- Any change to `dao.stream`, its protocols, outcome sets, or transports.
  Zero diff is an end condition.
- The contract's reserved readiness extension. The library is the
  demonstration that polling plus a nudge suffices; if it ever is not,
  the extension lands in transports and this library consumes it —
  additively, per the contract.
- `dao.await` and the VM's park/resume relation. W2 swaps the engine's
  sweep implementation only; W5 touches `dao.await.md`'s prose, not
  `dao.await`.
- Migrating any write to a `:put` entry outside the VM, and any
  result-consumption protocol that would let the waitset own a compound
  consumer's commit point. The probe is the whole handoff.
- External parks, a driver container, and an entry budget or any bounded
  traversal (see *Decisions*).
- Fairness, priorities, indexes (see *Decisions*), and any queue
  interpreter restoring destructive take — an interpreter above the
  stream, per the runtime plan's boundary, not a wait-set feature.
- Consumers that stay single-stream on `observe/step`. They are not
  technical debt; they are the correct shape for one stream and one
  consumer.

## End condition

Complete when all of the following hold on clj, cljs (Node) and cljd:

- `dao.stream.waitset` requires only `dao.stream`; classifies every
  declared outcome per operation against literal maps whose domains equal
  `outcomes-next` and `outcomes-append`, with exactly `blocked`/`full`
  waiting and writer `closed` terminal; wakes unresolved and
  unsupported-reason entries as qualified diagnostics; preserves the
  shared-cursor (including gap recovery) and no-dropped-poll laws; returns
  per-call `:woken` with no library-injected handles; and its suite proves
  each by construction, over a non-VM store as well as a VM-shaped one.
- `yin.vm.engine` keeps the public `check-wait-set` as a thin integration
  over `waitset/check`; `augment-wait-entry`, `poll-wait-entry` and the
  inline sweep body are gone; waitset diagnostics raise before restoration;
  the existing suites pass with no existing test edited.
- `dao.stream.waitset.cadence` is a pure function that never calls
  `check`, and `dao.stream.waitset.driver` exists per host as a
  contentless, coalescing, non-reentrant sleep-or-nudge primitive labelled
  host policy — no state container, no retained function that receives
  results, no budget, no external parks — over v2 fixtures only.
- The W4 table's assignments hold: `yin.repl.serve` sessions run probes
  under the three probe rules with one active waiter each, the shell's
  fixed tick is gone, no write outside the VM is a `:put` entry, and every
  other site is unchanged as recorded.
- `git diff <base> -- src/cljc/dao/stream.cljc src/cljc/dao/stream/
  src/clj/dao/stream/ src/cljs/dao/stream/ src/cljd/dao/stream/` shows
  only the new `waitset` files: no protocol, outcome-set, transport
  (`ringbuffer`, `memory_log`, `ws`, codecs) or composition (`forward`,
  `apply`, `rpc`, `serving`, `observe`, `observer`) change.
- The W5 scoped grep is clean: no doc or docstring outside the named
  historical list names the deleted `dao.runtime` as a live namespace.

## Revision — 2026-09-20 consensus reconciliation

This plan was reviewed adversarially by two architects (`gpt-5.6-sol`,
`claude-fable-5-1`) over five rounds and found **unsound as written; the
fix is a revision, not a redesign**. Both endorsed the W0–W2 extraction,
the name, the refusal of `:resume`/queues/tasks, and the zero-diff
substrate condition. The record is
`collab/1789842300000-architect-dao-stream-waitset-consensus-report.md`.
Each consensus item and where this revision resolves it:

| # | Severity · gates | Item | Disposition |
| --- | --- | --- | --- |
| 1 | Blocking · W1/W2 | W2 deleted a public function that production code and tests call | `engine/check-wait-set` kept as the VM integration; only the two private helpers and the sweep body are deleted; grep check amended (*Strategy*, W2, *End condition*) |
| 2 | Blocking · W1 | Entry schema mismatched the VM's entries; resolver could read but not write back | Entries opaque, library reads `:reason` only; two-function `:resolve`/`:advance` over whole entries; no library-added handles; non-VM-store test (*What it is*, W1) |
| 3 | Blocking · W1 | Writer `closed` mapped to `:end` | `closed` is a terminal append failure under its own keyword; per-operation classification (*What it is*) |
| 4 | Blocking · W3 | Driver held state in an atom, took external parks, dispatched `:woken` through a disposition | Host loop sole owner; pure `cadence-step` that never calls `check`; contentless wake tokens only; external parks deleted in favour of control stream + nudge; no retained disposition (*Decisions*, W3) |
| 5 | Blocking · W3 | `:budget` starves the suffix; rotation breaks co-waiter order | `:budget` deleted; complete ordered sweep; bounded traversal deferred until specified (*Decisions*) |
| 6 | Should-fix · W3 | `nudge!` under-constrained | Contentless, coalescing, non-reentrant, composition-wired; polling authoritative; close and capacity transitions covered, none required; lost nudge costs the armed interval (*Decisions*, W3) |
| 7 | Should-fix · W1/W2 | Nil resolver result and unknown `:reason` unclassified | Qualified diagnostics, removed from waiting, no stream operation; VM raises before restoration; register row rewritten as an intentional divergence; focused tests (*What it is*, W1, W2, *Divergence register*) |
| 8 | Should-fix · W1 | `:woken` lifecycle unspecified | `check` returns `{:waitset {:waiting} :woken :store}`; per-call results; repeated-check, no-injection and serializable-fixture tests (*What it is*, W1) |
| 9 | Should-fix · W4 | W4 list contradicted W0; naive adoption breaks compound commit points | Writer `:put` migration removed outright; non-advancing advisory probe with the three rules (retire on terminal; pending writes on host cadence; cursor-state isolation); every W0 site assigned (*What it is*, W4, *Census reconciliation*) |
| 10 | Should-fix · W1 | No test for gap write-back between co-waiters | Added (W1) |
| 11 | Should-fix · W1 | Iterating outcome sets passes vacuously for a new outcome | Per-operation literal maps with domain equality (W1) |
| 12 | Should-fix · W5 | W5 scope could not satisfy the end condition | All live `dao.runtime` claims enumerated by grep and rewritten; historical mentions named and excluded (W5, *End condition*) |
| 13 | Note | "Pure in, pure out"; leftover draft sentence | `check` is a synchronous, state-threaded interpreter step with explicit stream effects; draft sentence replaced; "pure" reserved for `cadence-step` (*What it is*) |

W0's census rows were not edited; the three adoption cells this revision
supersedes are listed under *Census reconciliation*.

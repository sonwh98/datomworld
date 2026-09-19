# dao.stream.waitset — the multiplexed wait-set library

Status: implementation plan, derived from and subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract) and
[`datom.world.md`](./datom.world.md). It picks up the extraction that
[`dao.runtime.v2.implementation-plan.md`](./dao.runtime.v2.implementation-plan.md)
explicitly deferred — "If a second consumer appears with the same need, the
resolver becomes a phase then" — after commit `a99f246f` (2026-09-18) deleted
`dao.runtime` and refactored `yin.vm.engine` to poll V2 streams directly,
leaving the multiplexed sweep welded to the VM and no library answer to
cadence. This document is transient: it is consumed as its phases complete.
Drafted 2026-09-19.

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

1. **Extract the sweep into `dao.stream.waitset`**, a pure `.cljc` namespace
   requiring only `dao.stream`. Entries are data, a host-composed resolver
   maps stored references to live handles and cursors, and the sweep
   partitions waiting from woken. No `:resume`, no ready queue, no task
   semantics: the library multiplexes readiness; it does not schedule work.
2. **Make `yin.vm.engine` its first consumer.** The engine supplies a
   store-resolver and a woken-to-ready-queue disposition and keeps its
   machine relation; the library owns the poll loop. The inline
   `augment-wait-entry`/`poll-wait-entry`/`check-wait-set` are deleted, not
   wrapped.
3. **Restore cadence as `dao.stream.waitset.driver`**, one namespace name
   and three host files, carrying over the deleted R2 drivers' settled
   shape — explicit host-owned state, configurable interval, no
   namespace-global atoms — plus one new entry: a `nudge!` that an
   appending composition calls so an idle driver wakes immediately instead
   of at the next tick.

The substrate is untouched in every phase. No transport changes, no
readiness protocol, no wake tokens: `blocked` and `full` remain the only
waitable outcomes, discovered by polling, exactly as the contract's *The
readiness extension* requires ("no consumer of this contract may depend on
[a readiness extension] existing").

## What `dao.stream.waitset` is

Stated so no phase has to infer it from the engine's code.

**State is a value.** `{:waiting [] :woken []}` from an initial empty set,
or the host's own map threaded through. Every function takes the state and
returns it. No atom, no `defonce`, no host branch in the pure namespace; it
requires only `dao.stream`.

**An entry is data with a reference, never a handle.** An entry is
`{:reason :next|:put, :stream-ref …, :cursor-ref …}` — references the host
understands, stored exactly as the host gave them. This is the engine's own
discipline, kept: "the stored entry itself never carries a handle"
(`engine.cljc:320-321`). Only two reasons exist, because only two outcomes
can change on their own: `blocked` for a reader, `full` for a writer
(`engine.cljc:286-293`).

**A resolver is host composition.** `(resolve-ref store ref) → {:stream
handle :cursor cursor}` or nil. The VM's `:store` lookup is one
implementation (`augment-wait-entry` becomes it almost verbatim); a test
fake, a host directory, or a constant map are others. The library never
dereferences a ref itself.

**The sweep is one ordered pass.** `check` resolves each entry through the
resolver, polls it with a synchronous `next` or `append!`, and partitions:
still-waiting entries are retained in stored form; resolved entries move to
`:woken` with `{:value :status …}` updates plus the successor `:cursor` for
a moved reader. Two properties are preserved verbatim from the engine,
because suites depend on them:

- **Shared-cursor write-back between entries.** When two entries share a
  cursor-ref, the first woken reader's successor is folded into the
  resolver's store before the second entry resolves, so co-waiters read
  distinct values (`engine.cljc:325-331,350-356`). The sweep therefore
  takes a store-updating callback… no: it takes a *resolver* and returns a
  *new store*, `(check waitset resolver store) → {:waiting … :woken …
  :store store'}` — pure in, pure out, write-back expressed as data.
- **A parked writer retries by appending.** The append is an effect of the
  poll; `check` returns the state that records it, and no caller may drop
  a polled state (the old R1 defect, restated as a law).

**Classification is total and declaration-driven.** `ok` resolves with the
value and successor; `end` resolves as `{:value nil :status :end}`;
`gap` resolves with its recovery cursor; `closed` resolves as `:end` (the
writer-side twin); the terminal outcomes (`cursor-mismatch`,
`invalid-cursor`, `transport-error`, `invalid-value`) resolve under their
own keywords for the host to raise or report. Only `blocked` and `full`
wait. An outcome outside the contract's closed sets is terminal, never a
wait — waiting on an answer the library cannot interpret would spin.

**Woken entries are returned, never dispatched.** The library invokes
nothing: no callbacks, no continuations, no queues of its own. What the
host does with `:woken` — feed a ready queue, resolve a promise, run a
consumer function — is host disposition. This is the invariant "do not
introduce callbacks" held at the seam.

**`check` answers whether anything moved.** Its result includes whether
`:woken` is non-empty, because that single bit is what a driver sleeps on.

## Decisions

Settled here so no phase decides them alone.

**The name is `dao.stream.waitset`.** `dao.runtime` was considered and
rejected: it was deleted one commit before this plan (`a99f246f`), the
library deliberately has none of what made the runtime a runtime (no
`:resume`, no ready queue, no tasks), and "name things after what they do
to streams" puts it beside `dao.stream.forward` and `dao.stream.observer`
as another interpreter over the medium. The stale prose that names
"`dao.runtime`'s wait set" (the `dao.stream.observe` namespace docstring)
is repaired in W5 to point here.

**The resolver seam is adopted now.** The runtime plan refused it for a
single consumer; the census in W0 is the evidence a second consumer class
exists. Adopting it re-opens no review: the engine's semantics are
preserved branch-for-branch, and W2's deliverable is that the engine's
suite passes *unchanged*.

**No shared queue, no fairness, no priorities, no wait-set index.** The
sweep is in wait-set order and O(n) in parked entries, which the
contract's *What it costs* accepts and which W3's budget bounds per round.
A priority structure would make `check` allocate and reorder for consumers
that park dozens of entries, not thousands. If a composition parks
thousands, that composition needs the readiness extension, not a smarter
library.

**The nudge is a host-to-library call, never the reverse.** `nudge!` is a
function on the driver container that an *appending composition* calls
after it deposits — the ws inbound path after `deposit!`, a GUI event
handler after its append, a test after a scripted append. The library
never registers anything with a transport, and the substrate still wakes
nothing: `append!`'s outcome set is unchanged. Correctness never depends
on the nudge — a lost nudge costs one tick of latency, never lost work,
because the poll is the only truth. The nudge is the self-pipe trick with
the log playing the pipe's role: the data is already there; the nudge only
ends the sleep early.

**Cadence parameters live on the container.** `:poll-ms` (the idle round
interval), `:budget` (max entries polled per round, bounding latency
spikes), and `:backoff` (optional idle curve from `:poll-ms` up to a
ceiling, reset by any wake). The right cadence for a REPL is not the right
cadence for a serving endpoint; neither is the library's to know.

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

## Phase W1 — The sweep

`src/cljc/dao/stream/waitset.cljc`, pure, requiring only `dao.stream`:

- `empty-waitset`, `park` (add an entry), `check` as specified above.
- Tests iterate `dao.stream/outcomes-next` and `outcomes-append` and pin
  the classification of every declared outcome — exactly `blocked` and
  `full` wait — so a future contract outcome fails this suite instead of
  falling into a default branch. A keyword outside the declared sets is
  asserted terminal.
- Tests for the two preserved properties: shared cursor-ref wakes
  co-waiters on distinct values in one round; a scripted `full`-once
  handle shows exactly one append per poll with the resolved writer in
  `:woken` and no polled state discarded.

Deliverable: suite green on clj, cljs (Node) and cljd.

## Phase W2 — The engine becomes a consumer

Replace `augment-wait-entry`, `poll-wait-entry`, and `check-wait-set` in
`src/cljc/yin/vm/engine.cljc` with `waitset/check` plus a store-resolver
and the woken-to-ready-queue disposition (`make-woken-run-queue-entries`
stays the engine's). The run loop, park semantics, and terminal-outcome
raising are unchanged.

Deliverable: the full `yin.vm` suite — engine, walker, FFI, macro, parity —
green on all three hosts **without test edits**, and `grep` confirming the
three inline functions are gone. Any test that had to change is a defect in
W1, not in the test.

## Phase W3 — Cadence containers (`dao.stream.waitset.driver`)

One namespace name, three host files, the R2 shape with explicit state:
`src/clj/dao/stream/waitset/driver.clj`,
`src/cljs/dao/stream/waitset/driver.cljs`,
`src/cljd/dao/stream/waitset/driver.cljd`.

- **clj**: `make-driver` returns `{:waitset … :queue (LinkedBlockingQueue.)
  :poll-ms … :budget …}` held in an atom the host creates. `run-loop!`
  blocks on the queue with the `:poll-ms` timeout — external entries and
  `nudge!` wakes it immediately, timeout means the wait set is due a
  round — then sweeps and dispatches `:woken` through the host's
  disposition. This is `select` with the wakeup pipe; the sweep is the
  syscall.
- **cljs/cljd**: `schedule-work!` enqueues and schedules one microtask;
  `run-pending!` sweeps and, if the wait set is non-empty, arms exactly
  one timer at `:poll-ms` (cancel-and-rearm; Node timers `unref`ed), so an
  idle composition costs one timer, zero busy loops.
- `nudge!` on every host: enqueues a wake token (clj) or schedules the
  pending run immediately (cljs/cljd).
- Tests port the deleted R2 driver tests' intents over v2 ring buffers and
  a host-created container: internal work drains, a parked wait set keeps
  being polled without external work, `nudge!` beats the timer, repeated
  nudges coalesce, an idle driver stays alive at backoff ceiling.

Deliverable: three drivers and suites on their hosts. No production
consumer is switched in this phase, and the plan says so rather than
inventing one: the drivers exist because a sweep without cadence has no
answer to "what calls `check` again" on any host, and because `a99f246f`
deleted the only answer that existed.

## Phase W4 — Consumer adoption

Migrate the multiplexed consumers W0 identified — expected: the GUI event
pump and `dao.stream.rpc`'s serving loop; `dao.jing` if its materializer
holds more than one waiter. Single-stream consumers stay on `observe/step`
and `observer/run-on-stream`: the waitset is for hosts holding many
waiters, and converting a one-stream loop onto it is the over-adoption
failure mode this phase exists to avoid. Each adoption deletes the loop it
replaces; a consumer adopted without deleting anything has been wrapped,
not migrated, and is reverted.

Deliverable: each adopted consumer's suite green on all three hosts with
its private poll loop gone.

## Phase W5 — Design prose and stale references

- The `dao.stream.observe` namespace docstring's reference to
  "`dao.runtime`'s wait set" names `dao.stream.waitset`.
- [`dao.await.md`](./dao.await.md) describes resumption as "the scheduler
  polls each parked entry" — the sentence gains the library's name and the
  nudge rule (the VM parks; the library sweeps; the driver keeps cadence).
- [`dao.stream.md`](./dao.stream.md) *The readiness extension* gains one
  sentence pointing at this library as the standing answer to *What it
  costs*: poll-based, substrate-untouched, and the reason no consumer
  needs the reserved extension to sleep between rounds.

Deliverable: the three docs updated; no other prose touched.

## Divergence register

Every place the library deliberately differs from what it replaces, with
the reason.

| Prior form | `dao.stream.waitset` | Why |
| --- | --- | --- |
| Engine entries carry VM registers and ride the machine relation | Entries are `{:reason :stream-ref :cursor-ref}` data; disposition is host-owned | A VM entry is a continuation; the library multiplexes readiness. Register-bearing entries would drag the VM into every consumer |
| Engine resolves refs against its `:store` internally (`engine.cljc:266`) | Resolver is a parameter; `check` returns a new store | The deferred decision, adopted: a second consumer class appeared (W0) |
| `dao.runtime` had `:resume`, a ready queue, and task semantics | None of the three | Task scheduling is why the old runtime was coupled to its consumers; readiness multiplexing is the reusable core. A library with `:resume` is a scheduler, and schedulers are per-host policy |
| Deleted R2 drivers polled on a fixed timer only | `nudge!` wakes an idle driver immediately | The timer-only shape pays full `:poll-ms` latency on externally-appended data; the nudge halves the cost of the select analogy without touching the substrate |
| `run-once`/`run-loop` combined ready work and polling | `check` polls; the host's disposition runs woken work | The R1 split, restated: a polled state is never discarded, and the sweep owns no downstream work to drop |
| Unknown wait `:reason` kept waiting (old runtime default) | Unchanged | An entry the library cannot poll is not the library's to resolve; it stays where the host put it |

## Host matrix

W1 and W2 are pure `.cljc` over `dao.stream` and have no host branch. W3 is
three host files by construction. Every phase green on clj, cljs (Node) and
cljd; per the standing rule, confirm `Testing dao.stream.waitset-test` and
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
  sweep implementation only.
- Fairness, priorities, indexes (see *Decisions*), and any queue
  interpreter restoring destructive take — an interpreter above the
  stream, per the runtime plan's boundary, not a wait-set feature.
- Consumers that stay single-stream on `observe/step`. They are not
  technical debt; they are the correct shape for one stream and one
  consumer.

## End condition

Complete when all of the following hold on clj, cljs (Node) and cljd:

- `dao.stream.waitset` requires only `dao.stream`, classifies every
  declared outcome with exactly `blocked`/`full` waiting, preserves the
  shared-cursor and no-dropped-poll laws, and its suite proves each by
  construction.
- `yin.vm.engine` contains no inline wait-set functions and its suites
  pass unmodified.
- `dao.stream.waitset.driver` exists per host with explicit state,
  configurable cadence, budget, and `nudge!`, over v2 fixtures only.
- The W4 consumers run the library and contain no private multiplexed
  poll loops; the single-stream consumers are recorded as deliberately
  unchanged in W0's table.
- `git diff <base> -- src/cljc/dao/stream.cljc src/cljc/dao/stream/`
  shows no transport changes beyond the new `waitset/` subtree.
- The W5 prose names the library, and no doc or docstring names the
  deleted `dao.runtime` as a live namespace.

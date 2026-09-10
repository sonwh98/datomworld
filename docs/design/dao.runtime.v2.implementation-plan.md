# dao.runtime on DaoStream v2 — the scheduler slice

Status: migration plan, derived from and subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract) and
[`datom.world.md`](./datom.world.md). Its transport prerequisite is
[`dao.stream.v2.implementation-plan.md`](./dao.stream.v2.implementation-plan.md);
its first consumer is
[`yin.vm.v2.implementation-plan.md`](./yin.vm.v2.implementation-plan.md),
whose phase V2 created `dao.runtime.v2` as a dependency of the VM port. This
plan takes over ownership of that namespace, states the contract the VM plan
left implicit, and names the phases between "the scheduler exists" and
"legacy `dao.runtime` is deleted". This document is transient: it is consumed
as its phases complete. Drafted 2026-09-06. R0 landed under the VM plan's V2;
R1 and R2 are fully implemented; R3 is done 2026-09-06. R4 remains, gated on
the v1 VM's deletion.

## The problem

`dao.runtime` (`src/cljc/dao/runtime.cljc`, 258 lines) is a cooperative
scheduler over v1 `dao.stream`: a ready queue of resumable task maps, a wait
set of tasks parked on a stream operation, and a polling `check-wait-set`. It
is the layer where the v1 stream mechanisms the contract has retired are not
merely *used* but *woven in*:

| v1 mechanism                                                                 | where `dao.runtime` depends on it                                                                                                                           |
| ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `IDaoStreamWaitable` / `register-reader-waiter!` / `register-writer-waiter!` | `handle-read` and `handle-write` branch on `satisfies?` (`runtime.cljc:156-157,181-182`) and hand the parked entry to the transport instead of the wait set |
| `:woke` lists on `append!` and `close!`                                      | `make-ready-entries` (`runtime.cljc:52`) turns transport wake lists into ready entries at five sites (`97,125,189,198` and `handle-close`)                  |
| `drain-one!` destructive take                                                | `handle-take` (`runtime.cljc:193`) and the `:take` wait reason (`94`)                                                                                       |
| `closed?`                                                                    | the `:put` branch of `check-wait-set` asks before it appends (`runtime.cljc:115`)                                                                           |
| `{:position n}` cursors                                                      | ready entries carry `:position` (`52,82`); `yin.vm.runtime-adapter` advances a woken reader with `(inc position)` (`runtime_adapter.cljc:11`)               |
| `open!`                                                                      | every `dao.runtime` test opens its fixture through the v1 registry                                                                                          |

None of these has a v2 counterpart, and the contract's *Explicitly Absent*
section says each is absent by derivation from the invariants, not deferred.
A `dao.runtime` that keeps them cannot schedule a v2 handle, and a v2 handle
cannot be scheduled by anything else. That is why the VM plan built
`dao.runtime.v2` before the VM kernel: "it is not a leaf and does not belong
in a first phase."

The runtime also carries three **host drivers** — `dao.runtime.driver` on
clj (`LinkedBlockingQueue`, 60 lines), cljs (microtask plus `setTimeout`, 51
lines) and cljd (microtask plus `Timer`, 46 lines) — which own cadence: what
calls `run-once` again, and when. Their tests park entries on a v1
`NonWaitableStream` from `dao.test-utils`. They are part of `dao.runtime` and
they go when it goes.

**What blocks deletion is not this namespace.** `dao.runtime`'s only
production consumer is `yin.vm.engine` through `yin.vm.runtime-adapter` —
that is, the v1 VM. The host drivers have no production consumer at all. So
legacy `dao.runtime` can be deleted the moment v1 `yin.vm.engine` no longer
requires it, and v1 `yin.vm.engine` is deleted with v1 `yin.vm`, which has
ten consumers of its own outside `src/cljc/yin/vm/` (see *Consumer census*).
This plan finishes the v2 scheduler, ports the drivers, and prepares the
deletion; it does not, and cannot, remove the v1 VM.

## Strategy

The same two narrowings as the sibling plans:

1. **`dao.runtime.v2` beside `dao.runtime`, not an in-place rewrite.** The v2
   namespace already exists (`src/cljc/dao/runtime/v2.cljc`, 224 lines) with
   `yin.vm.v2.runtime-adapter` (97 lines) beside it, and both are exercised
   by `yin.vm.v2.engine` on all three hosts. Legacy keeps the v1 VM running.
   No compatibility facade, alias, or dual protocol: the legacy
   implementation and its tests are evidence about behavior, not
   constraints.
2. **The scheduler slice, and nothing else.** A ready queue, a wait set, the
   two outcome classifiers, the three operation handlers, the loop, and a
   host driver per host. Not `dao.await`, not a queue interpreter restoring
   destructive take, not a readiness extension, not a reshaping of the VM
   engine's seam onto the runtime (see *Decisions*).

## What `dao.runtime.v2` is

The contract this namespace already implements, stated so that nothing in a
later phase has to infer it from the code.

**State is a value.** `{:ready-queue [] :wait-set [] :blocked? false}` from
`initial-state`. Every function takes that map and returns it, or returns
`nil` where "no work" is the answer. The namespace holds no atom, no `defonce`,
no host dependency, and requires only `dao.stream.v2`. A composition that
needs the state to persist across host callbacks holds it; the runtime does
not.

**A task is a map with `:resume`.** `(resume rt entry value)` returns the
next runtime state. Ready entries carry `:value`, `:status`, and for a woken
reader `:cursor`. Wait entries additionally carry `:reason` (`:next` or
`:put`), `:stream` (a live v2 handle), and either `:cursor` (an opaque v2
cursor, for `:next`) or `:datom` (the value to append, for `:put`). The
runtime resolves nothing: the caller that parks a task hands it the handle
and cursor it will be polled with. Cursors are opaque; the runtime does no
arithmetic on one and stores the successor exactly as `next` returned it.

**Classification is total.** `read-outcome->task` and `write-outcome->task`
map every outcome in the contract's closed sets to `[:wait]` or
`[:ready updates]`. Exactly two outcomes wait — `blocked` for a reader and
`full` for a writer — because they are the only two that can change on their
own. Every other outcome resolves the task: `ok` with the value and successor,
`end` as `{:value nil :status :end}`, `gap` as
`{:value :dao.stream/gap :status :dao.stream/gap :cursor recovery}`, and the
terminal outcomes (`cursor-mismatch`, `invalid-cursor`, `transport-error`,
`invalid-value`) under their own keyword. A writer's `closed` resolves as
`:end`, the writer-side twin of a reader's `end`. An outcome outside the
closed set is terminal, not a wait: waiting on an answer the runtime cannot
interpret would spin.

**The polling wait set is the mechanism.** `check-wait-set` polls each parked
entry against its transport — `next` for `:next`, `append!` for `:put` — and
moves resolved entries to the ready queue in wait-set order. There is no
other path: no transport is waitable, `append!` returns no wake list, and
`close!` wakes nothing. A reader parked on a stream that is then closed
learns of it from its own next `next`.

**A parked writer retries by appending.** A `:put` entry re-attempts
`append!` on every poll. The append is therefore an effect of polling, and
the state a poll returns is the only record that the append happened. Phase
R1 exists because one path in the current loop discards that state.

**The loop is a step.** `run-once` resumes one ready task and returns the
next state, or `nil` when there is nothing it can run. `run-loop` repeats it
until quiescent. Neither schedules itself; cadence belongs to the driver, per
the contract's *The readiness extension*: "retry cadence is the concern of the
interpreter or the runtime driving it, not of the stream."

**Entries without `:resume` are host-owned.** The docstring of `run-once`
already says so: such an entry "is not runnable by run-once; returns nil so
the host can handle the entry itself." Phase R1 makes that promise hold
without discarding state.

## Decisions

Settled here so no phase decides them alone.

**`run-once` does not poll.** Today `run-once` is
`(or (take-one rt) (take-one (check-wait-set rt)))` (`v2.cljc:215`). When
the ready queue's head has no `:resume`, the first `take-one` yields nil, the
wait set is polled, and if the head still has no `:resume` the polled state is
dropped. A `:put` entry whose `append!` succeeded inside that poll stays
parked and appends again on the next poll: a duplicate write, with nothing in
the returned value to show it happened. v1 did not have this path (it returned
nil before polling when the head was non-resumable). The fix is a split, not a
patch: `run-once` runs the ready queue only, `check-wait-set` is the only
function that polls, and `run-loop` calls them in that order and **returns the
polled state** whenever polling produced an entry it cannot resume. The
composition that put a host-owned entry on the queue is the one that reads it
off.

**Wait-set entries are resolved by the caller, not the runtime.** The VM
engine's `check-wait-set` resolves each entry's handle and cursor out of the
VM store and polls the runtime with a singleton wait set so that a woken
reader's successor is stored before the next entry sharing its cursor-ref is
resolved (`yin/vm/v2/engine.cljc:277-320`). A resolver argument on
`dao.runtime.v2/check-wait-set` would let the engine hand over the whole wait
set at once. It is **not** adopted: the current seam works, is tested on three
hosts, and was reviewed under the VM plan's V7; changing it re-opens that
review for a shape the runtime's only consumer does not need. If a second
consumer appears with the same need, the resolver becomes a phase then.

**Drivers hold no namespace-global state.** The cljs and cljd drivers keep
their runtime in `defonce`/`def` atoms (`driver.cljs:6-9`, `driver.cljd:7-10`)
with a `set-runtime!` to swap it — the ambient state `datom.world.md`'s
invariants forbid, and the reason the cljd driver test has to `reset!` two
namespace vars before each case. The v2 drivers take their state explicitly:
the host creates the container and passes it in. `set-runtime!` disappears.

**Cadence is a parameter.** The v1 drivers bake in a 50 ms JVM poll and a
20 ms timer on cljs and cljd. The v2 drivers take the poll interval from the
composition, because the right cadence for a REPL is not the right cadence
for a serving endpoint, and neither is the runtime's to know.

**Fixtures are v2 ring buffers, or fake handles that implement the v2
protocols.** No v2 test opens anything through v1 `open!`, and no v2 test
requires `dao.test-utils` (whose fixtures are v1 streams). Where a test needs
an outcome the ring buffer never produces — `full`, `transport-error`,
`invalid-value` — it uses a reified handle returning the scripted outcome,
which is also how the contract-conformance harness induces declared outcomes.

## Divergence register

Every place `dao.runtime.v2` deliberately differs from `dao.runtime`, with
the reason. Saying which v1 behaviors are deliberately not mirrored *is* the
register: a v2 suite that "covers the same scheduling" proves nothing unless
the places it cannot cover are named.

| v1 behavior (`runtime.cljc`)                                                                                                                                    | v2                                                                                                                                                                                                                                               | Why                                                                                                                                                                                                                                                                                |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Transport-local waiters: a blocked read or full write on a waitable stream registers the entry with the transport and bypasses the wait set (`156-157,181-182`) | **Gone.** Every park goes to the wait set.                                                                                                                                                                                                       | No v2 transport is waitable; the contract has no readiness extension. The wait set was already v1's documented "universal fallback"; v2 deletes the optimization branch, not a mechanism.                                                                                          |
| `append!` and `close!` return `:woke` lists that become ready entries (`52,97,125,189,198`, `handle-close`)                                                     | **Gone.** An append or close wakes nothing; a parked task learns the new state on its next poll.                                                                                                                                                 | `append!`'s v2 outcome set has no wake key. Waking another task from inside a write is exactly the coupling between writer and reader position that the contract retired.                                                                                                          |
| `handle-take` / `:take` wait reason / `:empty` result (`94,193`)                                                                                                | **Removed.** Two wait reasons, `:next` and `:put`.                                                                                                                                                                                               | Destructive read makes one reader's progress every other reader's data loss. An above-the-stream queue interpreter with explicit consume accounting is the deferred way back; it is not this namespace.                                                                            |
| `closed?` asked before a parked `:put` retries (`115`)                                                                                                          | **Gone.** `append!` answers `closed`, classified as `{:status :end}`.                                                                                                                                                                            | A predicate answer is stale the moment it returns; the operation's outcome is authoritative.                                                                                                                                                                                       |
| Close wakes a parked reader with `nil` and a parked writer with `nil` (`runtime_test.cljc` "close wakes parked reader/writer with nil")                         | Close wakes nothing. The reader's next poll returns `end`; the writer's next poll returns `closed` → `:end`. Same resumed value, one poll later.                                                                                                 | `close!`'s v2 outcome set is `{ok}`. `v2_test/close-does-not-wake-a-reader-directly-test` is the replacement evidence.                                                                                                                                                             |
| A writer parked on `full` wakes when a take frees a slot (`runtime_test.cljc` "writer on full stream parks and is resumed after space is freed")                | **Cannot be mirrored.** Under the v2 ring buffer `append!` never returns `full`, so the writer never parks; loss surfaces as a `gap` at the reader. Under a transport that does return `full`, the writer parks and the poll retries the append. | The v2 ring buffer is evict-oldest and has no drain; a reject-mode buffer with no destructive take would be full forever (stream plan, *Reject mode is not a deferred ring-buffer variant*). `full` still parks, because a composition may supply a transport that frees capacity. |
| Ready entries carry `:position`; the adapter advances a woken reader by `(inc position)` (`52,82`; `runtime_adapter.cljc:11`)                                   | Ready entries carry `:cursor`, the exact successor `next` returned; the adapter stores it as given.                                                                                                                                              | Cursors are opaque and transport-owned. There is no arithmetic the runtime could do that would be right for every transport.                                                                                                                                                       |
| Bare results — `:blocked`, `:end`, `:daostream/gap`, `{:ok v}` — dispatched on `map?` and keyword equality                                                      | Outcome maps under `:dao.stream/…`, classified by two total functions. `handle-read`'s `:result` is `:ok`, `:blocked`, `:end`, `:dao.stream/gap`, or a terminal outcome keyword.                                                                 | The result convention. Every outcome in the closed set has a branch, including the four terminal ones v1 never had a name for.                                                                                                                                                     |
| A task lacking `:resume` at the head of the ready queue stops `run-once` **before** it polls                                                                    | Same rule, restored by R1: the ready-queue-only `run-once` never polls, so no poll's state can be dropped.                                                                                                                                       | Between V2 and R1, v2 polled first and dropped the state; see *Decisions*.                                                                                                                                                                                                         |
| Unknown wait `:reason` keeps waiting (`check-wait-set` default branch)                                                                                          | Unchanged.                                                                                                                                                                                                                                       | An entry the runtime cannot poll is not the runtime's to resolve; it stays where the host put it.                                                                                                                                                                                  |
| Host drivers: namespace-global runtime atom, `set-runtime!`, fixed 50/20 ms cadence                                                                             | Explicit state passed by the host; cadence a parameter.                                                                                                                                                                                          | No hidden global state. Cadence belongs to the composition.                                                                                                                                                                                                                        |

**v1-only tests deliberately not mirrored.** In `runtime_test.cljc`: the
`:take` cases of `basic-scheduling-test` and
`check-wait-set-closed-taker-does-not-corrupt-ready-queue-test` (destructive
take), and "writer on full stream parks and is resumed after space is freed"
(drain frees capacity). In `yin/vm/runtime_regression_test.cljc`: every case
built on `make-waitable-retry-stream` (waiter registration). All depend on
mechanisms the contract lists as absent.

## Consumer census

Who requires legacy `dao.runtime` today, and what that means for deletion.

| consumer                 | file                                                                                                                                           | migrates under                                                                                                           |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `yin.vm.engine` (v1 VM)  | `src/cljc/yin/vm/engine.cljc:5`                                                                                                                | `yin.vm.v2` plan — its engine already requires `dao.runtime.v2`; the v1 engine is deleted with v1 `yin.vm`, not migrated |
| `yin.vm.runtime-adapter` | `src/cljc/yin/vm/runtime_adapter.cljc:3`                                                                                                       | same: `yin.vm.v2.runtime-adapter` exists; the v1 adapter is deleted with the v1 VM                                       |
| `dao.runtime.driver` ×3  | `src/{clj,cljs,cljd}/dao/runtime/driver.*`                                                                                                     | **this plan**, R2 — done 2026-09-06; `dao.runtime.v2.driver` exists per host                                             |
| tests                    | `test/dao/runtime_test.cljc`, `test/dao/runtime/driver_*`, `test/yin/vm/runtime_adapter_test.cljc`, `test/yin/vm/runtime_regression_test.cljc` | deleted with what they test, R4                                                                                          |
| design prose             | `docs/design/dao.await.md`                                                                                                                     | **this plan**, R3 — done 2026-09-06; the prose names `dao.runtime.v2` and states the v2 rules                            |

The v1 VM's own consumers, which gate the v1 VM's deletion and therefore
gate R4, are not this plan's to migrate but are named so the gate is
visible: `yin.repl`, `dao.await`, and the test `yin.vm.v2.parity-test`
(asserts v2 against v1 `ast-walker` in the same process). (`yin.repl.v2.core`
requires `yin.vm.v2` only; it is not on this list.)

**Cleared by `yin.vm.v2-consumers.implementation-plan.md` (2026-09-10):**
`yin.demo`, `yin.vm.bytecode-bench`, `yin.register-bench-cljd`,
`datomworld.demo.continuation-handoff`, `datomworld.demo.continuation-stream`,
`datomworld.demo.compilation-pipeline`, `datomworld.demo.equation-plotter` are
deleted. `datomworld.demo` itself requires no `yin.vm.*` namespace after that
plan; its remaining v1 dependence is `dao.stream` through `yin_repl.cljs` and
`telemetry_viewer.cljs` — the stream gate (`dao.stream.md`), not this one — so
it is off this list too.

## Phase R0 — What exists (done under the VM plan's V2)

Recorded so the remaining phases start from evidence, not from the VM plan's
description of what V2 would build.

- `dao.runtime.v2`: `initial-state`, `park-task`, `enqueue-ready`,
  `pop-ready`, `read-outcome->task`, `write-outcome->task`,
  `check-wait-set`, `handle-read`, `handle-write`, `handle-close`,
  `run-once`, `run-loop`. Requires only `dao.stream.v2`.
- `yin.vm.v2.runtime-adapter`: `vm-task`, `enqueue-woken-vm-entries`, plus
  the rule that a parked retry ending in a terminal outcome throws exactly as
  the immediate operation would (`terminal-resume-outcome`).
- Tests: `test/dao/runtime/v2_test.cljc` (7 tests: state, queue discipline,
  both classifiers, park-and-wake through the polling wait set, close does
  not wake, `run-once` resumes and leaves host-owned entries) and
  `test/yin/vm/v2/runtime_adapter_test.cljc`. Both run on clj, cljs and cljd
  (`test/cljd-out/dao/runtime/v2-test_test.dart` is generated). Verified
  passing on the JVM on 2026-09-06.
- Consumer: `yin.vm.v2.engine` requires both and drives them through its own
  `check-wait-set` and `run-loop`.

## Phase R1 — Scheduler totality and the poll/ready split

Status: fully implemented (2026-09-06).

The scheduler as the contract above states it, with the one defect fixed and
the totality made checkable.

- **Split `run-once` and `check-wait-set`** per *Decisions*. `run-once` pops
  and resumes the head of the ready queue if it has `:resume`, else returns
  nil; it never calls `check-wait-set`. `run-loop` drains the ready queue,
  then polls once; if the poll moved anything it continues, else it returns.
  Whenever the ready queue's head is host-owned, `run-loop` returns the
  state that holds it. The `yin.vm.v2.engine` loop already calls
  `check-wait-set` itself before `rt/run-once` (`engine.cljc:335-336`), so
  it is unaffected; confirm with its suite rather than by inspection.
- **Regression test for the discarded poll.** A fake writer handle scripted
  to answer `full` once and `ok` thereafter, counting appends; a `:put` task
  parked on it; a host-owned entry (no `:resume`) at the head of the ready
  queue. Drive `run-loop`. Assert exactly one append, the writer resolved
  in the ready queue behind the host-owned entry, and the returned state
  containing both.
- **Classification is declaration-driven.** Replace the hand-enumerated
  `doseq` in `read-outcome-classification-test` and
  `write-outcome-classification-test` with iteration over
  `dao.stream.v2/outcomes-next` and `outcomes-append`: every declared
  outcome must classify to `[:wait]` or `[:ready …]`, exactly `blocked` and
  `full` may wait, and an outcome outside the declared set is terminal. A
  future contract outcome then fails the test instead of falling silently
  into the default branch.
- **`gap` carries its recovery cursor to the task, and the adapter stores
  it.** Already the case; add the adapter test that a reader woken with
  `:status :dao.stream/gap` has its stored cursor replaced by the recovery
  cursor, so a program that resumes after a gap resumes from the right place.
- **Docstring is the contract.** The namespace docstring gains the *What
  `dao.runtime.v2` is* rules above in compressed form, so the file states its
  own laws.

Deliverable: `v2_test.cljc` green on clj, cljs and cljd with the new cases;
`yin.vm.v2` engine, walker, FFI and parity suites unchanged and green.

## Phase R2 — Host drivers (`dao.runtime.v2.driver`)

Status: fully implemented (2026-09-06).

One namespace name, three host files, as v1 has them:
`src/clj/dao/runtime/v2/driver.clj`, `src/cljs/dao/runtime/v2/driver.cljs`,
`src/cljd/dao/runtime/v2/driver.cljd`. Host isolation by file, so no
reader-conditional trap applies.

Each driver is the composition-side answer to "what calls `run-loop` again,
and when", with the state explicit:

- **clj**: `make-driver` returns `{:rt (initial-state) :queue (LinkedBlockingQueue.) :poll-ms n}`
  held by the host in an atom the host creates. `enqueue-ready!` puts on the
  queue from any thread. `run-loop!` blocks on the queue with the configured
  timeout, applies external entries through their `:resume`, then calls
  `rt/run-loop`; on timeout with a non-empty wait set it calls `rt/run-loop`
  again. `stop!` posts the sentinel. The current shape, with the cadence and
  the state ownership moved out of the namespace.
- **cljs** and **cljd**: `schedule-work!` takes the host's state container
  and entries, enqueues, and schedules `run-pending!` on the next microtask;
  `run-pending!` calls `rt/run-loop`, and when the wait set is non-empty
  after it, arms one timer at the configured interval — cancelling any timer
  it already holds so exactly one is live. Node timers are `unref`ed as
  today. No `defonce`, no `set-runtime!`.
- **Tests** port the intent of `driver_test.clj`, `driver_cljs_test.cljs` and
  `driver_cljd_test.cljc` — internally enqueued work drains, a parked wait
  set keeps being polled without external work, ready work runs on the next
  microtask even while a poll timer is pending, repeated `schedule-work!`
  calls coalesce to one live timer, an idle driver stays alive — over a v2
  ring buffer instead of `make-non-waitable-stream`, and over a state
  container the test creates instead of namespace vars it resets.

Deliverable: the three drivers and their tests on their hosts. There is no
production consumer to switch, and the plan says so rather than inventing
one: the drivers exist because a scheduler without a driver has no cadence
on cljs or cljd at all, and because deleting v1's drivers without a v2 shape
would be narrowing the deliverable by silence.

## Phase R3 — Design prose and stale references

Status: fully implemented (2026-09-06).

`docs/design/dao.await.md` describes the v1 scheduler: it says `dao.runtime`
"records the task in the wait set or registers a transport-local waiter when
the stream supports `IDaoStreamWaitable`" and that "stream writes, drains, or
closes wake the task" (lines 412-417). Update it to the v2 rules — wait set
only, wake by poll, no drains, close wakes nothing — and to name
`dao.runtime.v2` where it names `dao.runtime`. `dao.await` is not built and
its plan is not this one; the prose changes so that when it is built it is
built against the scheduler that will exist.

`src/cljc/dao/stream.cljc:205,281` mention `dao.runtime`'s take in v1
docstrings. They go with v1 `dao.stream` and are not edited here.

R3 also brings the VM plan's scheduler prose with it: the port table's
`:stream/put` row names `dao.runtime.v2`'s `check-wait-set` as what retries a
parked writer, and the V7 section's status line now matches the header. The v1
mechanism names in `yin.vm.streams-all-the-way-down.md` stay — that note is
marked as written against v1 and read with the v2 contract in mind.

## Phase R4 — Deletion and the naming decision

Gated on v1 `yin.vm.engine` no longer requiring `dao.runtime`, which is gated
on the v1 VM's ten consumers migrating under their own plans. When the gate
opens:

- Delete `src/cljc/dao/runtime.cljc`, the three
  `src/*/dao/runtime/driver.*`, `src/cljc/yin/vm/runtime_adapter.cljc`, and
  their tests (`test/dao/runtime_test.cljc`, `test/dao/runtime/driver_*`,
  `test/yin/vm/runtime_adapter_test.cljc`,
  `test/yin/vm/runtime_regression_test.cljc`), plus their generated
  `test/cljd-out/` twins.
- Remove `make-non-waitable-stream` and `make-waitable-retry-stream` from
  `dao.test-utils` if `yin/vm/engine_test.cljc` — their remaining user, also
  v1 — is gone by then; otherwise they go with it.
- Take **one explicit decision**, mirroring the stream plan's end condition:
  `dao.runtime.v2` is renamed to `dao.runtime`, or keeps its name. The
  recommendation is to rename, in the same change as `dao.stream.v2`'s
  rename if that is the decision there, because the runtime's name appears
  on no wire and in no descriptor; only requires change. An undecided
  coexistence is a defect of the migration, not a steady state.

## Host matrix

Every phase on clj, cljs (Node) and cljd. R1 is pure `.cljc` over
`dao.stream.v2` and has no host branch. R2 is three host files by
construction. The cljd test lane regenerates `test/cljd-out/`; only one
process may own it at a time. Per the standing rule, confirm `Testing
dao.runtime.v2-test` and `Testing dao.runtime.v2.driver-test` appear in the
Node output rather than assuming discovery.

## Boundary of this plan

**Untouched:** `dao.runtime`, `dao.runtime.driver`, `yin.vm.runtime-adapter`
and their tests until R4; `yin.vm.v2.engine`'s wait-set seam (see
*Decisions*); `dao.stream` v1 in every form.

**Not in this plan, by design:**

- A readiness or waiter extension. The contract reserves it as additive; no
  consumer may depend on it existing, and this runtime does not.
- An above-the-stream queue interpreter restoring destructive-take
  semantics. Named in the VM plan as the deferred way back; it is an
  interpreter, not a scheduler feature.
- `dao.await`. Its design names this scheduler; its implementation has its
  own plan.
- Migration of the v1 VM's consumers. They gate R4 and belong to the VM plan
  and the plans of the demos and REPL that use it.
- Fairness, priorities, or a wait-set index. The wait set is polled in order
  and is O(n) in parked tasks, which the contract's *What it costs* accepts
  for the scheduler as much as for a reader.

## End condition

Complete when all of the following hold on clj, cljs (Node) and cljd:

- `dao.runtime.v2` classifies every outcome the contract declares, polls only
  through `check-wait-set`, never discards a polled state, and requires only
  `dao.stream.v2`. Its suite proves each of those by construction.
- `dao.runtime.v2.driver` exists per host with explicit state and
  configurable cadence, and its suite runs over v2 fixtures only.
- `dao.await.md` describes the v2 scheduler.
- No namespace under `dao.*.v2` or `yin.vm.v2` requires `dao.runtime`.
- Once the v1 VM is gone: legacy `dao.runtime`, its drivers, the v1 adapter,
  and their tests are deleted, and the naming decision has been taken
  explicitly.

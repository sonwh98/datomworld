# dao.space.transactor — The Agent-Side Transactor

Status: implemented on `dao.stream.v2` (migrated 2026-09-09; the v1
`:transactor` DaoStream type it replaces is deleted). This document is the
permanent record of the transactor's contract: what a transactor is, the
invariants its tests pin, where durability lives, and the one wiring
requirement a host composition must honour. The executable contract is
`test/dao/space/transactor_test.cljc`.

**Related documents:**
- `docs/design/dao.space.md` — the tuple space; *Three Boundaries* and
  *The Write Path* place the transactor in the medium
- `docs/design/dao.space.index.md` — `publish!` delegates here from the
  transactor; the snapshot reads this namespace's local stream
- `docs/design/dao.space.schema.md` — the validating wrapper that owns a
  transactor value; its D10 rule governs the shapes it re-wraps
- `docs/design/dao.jing.md` — the storage boundary publication targets
- `docs/design/dao.stream.md` and `docs/design/dao.stream.v2.md` — the
  contract this namespace consumes

## A transactor is a value, not a stream

A transactor is an **interpreter over a stream, not a stream**
(`dao.stream.md`, *Composition*): it reads via the local stream's reader
surface, appends through its writer surface, and needs no support from the
stream contract for either. The v1 `:transactor` DaoStream type — a
registered dispatch record carrying two live handles — could never cross the
descriptor boundary v2 requires, so it was never a descriptor; its options
map is now the constructor's plain **spec**:

```clojure
(require '[dao.stream.v2.memory-log :as memory-log]
         '[dao.space.transactor :as transactor])

(def local (:dao.stream/handle
            (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))

(def log (transactor/create! {:local-stream local   ; reader+writer surface;
                              :intake-pool [intake] ; supplied, never owned
                              :name "worker-7"}))   ; optional, diagnostic

(transactor/append!   log {:db/id 1 :work/claims "task"})
(transactor/transact! log [{:db/id 1 :work/claims "task"} …])
(transactor/publish!  log opts)   ; → {:manifest-address … :manifest …}
(transactor/close!    log)        ; → {:dao.stream/outcome :dao.stream/ok}
;; reads: use `local` — it is the caller's handle and always was
```

There is no registry and no `open!` dispatch: `create!` is a plain function,
following `query/open-published!`. A transactor implements no dao.stream.v2
protocol at all — it is not on a stream, so it has no identity to project.
`next` was pure delegation on a handle the caller still holds, and `closed?`
is *Explicitly Absent* in v2 because "a predicate answer is stale the moment
it returns; operation results are authoritative" — the replacement is in the
vocabulary: `append!`/`transact!` on a closed transactor answer
`{:dao.stream/outcome :dao.stream/closed}` as data.

`create!` returns **the value, or throws** — its failure modes are a
malformed spec and a malformed retained history, both of which a caller can
only abort on. It validates **surfaces, never retention**:
`stream/reader?`/`stream/writer?` (T16's pool check is the same surface
rule), and a reader/writer surface check does not establish retention.

## The invariants

Pinned by `test/dao/space/transactor_test.cljc` unless noted:

| # | Invariant |
|---|---|
| T1 | Every `append!`/`transact!` writes exactly ONE atomic transaction record `{:dao.space/transaction {:t n :datoms [...]}}` through exactly one local append, so no reader observes a torn transaction |
| T2 | `t` comes from a per-value watermark, derived on `create!` as 0 for an empty history else 1 + max datom `t` |
| T3 | A caller-supplied `:next-t` is rejected |
| T4a | A malformed retained history fails the create |
| T5 | The watermark advances **iff** the local append answered `:dao.stream/ok`; a failed or thrown append leaves the same `t` retryable |
| T6 | Single-writer: two values over one local stream derive the same `t` and write colliding records; a documented hazard, not coordinated |
| T7 | Close is per value: it rejects further writes and neither closes nor erases the local stream or the intake pool |
| T8 | Close linearizes after an in-flight append |
| T9 | Creating a transactor creates, registers, or closes nothing |
| T10 | Entity maps require `:db/id`; an explicit datom `t` is rejected; `[e a v]`/`[e a v nil m]` pad and validate |
| T11 | `publish!` passes the local stream, pool and opts to `index/publish-index!` and returns its result |
| T15 | An ok receipt carries the outcome, the allocated `t`, and the datoms — `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}` |
| T16 | Intake pool members are validated with v2 `stream/writer?`; a non-empty collection is required |
| T18 | The local stream is on a transport declaring complete retention (below) |
| T19 | `dao.space.schema` installs its next state only when the transactor answered ok (pinned in `schema_test`) |
| T20 | Retired 2026-09-09 under schema's plan; schema returns the transactor's receipt unchanged and adopts its closed-precedence rule |

## Operational outcomes are data; argument defects throw

The write path validates the local append's answer with
`stream/valid-outcome?` before interpreting it, and answers:

| Case | v2 behaviour |
|---|---|
| local append ok | `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}`, watermark advances |
| local append `full`/`closed`/`invalid-value`/`transport-error` | returned as data, watermark **unchanged**, same `t` still pending |
| local append answers a non-outcome | folded to `:dao.stream/transport-error` with `:dao.stream/answer` retained |
| transactor itself closed | `{:dao.stream/outcome :dao.stream/closed}` |
| malformed entity map / explicit datom `t` / empty `tx-data` / zero datoms | **still throws** — defects in the caller's own argument, detected before any stream is touched |
| local append throws | propagates, watermark unchanged |

`full` means "not yet, retry", so throwing on it was always wrong; the retry
contract is now stronger, not weaker: *the watermark advances if and only if
the local append answered `:dao.stream/ok`*, so retrying the identical call
re-attempts the identical `t`. On a `memory-log` local stream the only
reachable non-ok outcome is `closed`, since the transport excludes `full`,
`invalid-value` and `transport-error` — but `append!`/`transact!` are library
functions over whatever handle they are given, which is why the `full` branch
is pinned by a writer double rather than left untested.

## T18: the local-log wiring requirement

> The host composition supplies `dao.space` a local handle created by
> `dao.stream.v2.memory-log/create!`. Its declared complete retention makes
> fresh `:oldest` cursors true origin cursors for `derive-next-t` and
> `publish-index!`; supplying an evicting transport is a host-assembly defect.

A **declaration, not a check**. `create!` does not and must not verify
retention, although the misassembly is detectable at least twice over:
`stream/descriptor` exposes `:dao.stream/type`, so a type check would catch a
ring buffer today, and the contract's *Complete history* names the kept
origin cursor as a detection mechanism in its own right. Neither is done,
on purpose:

- **A type check would couple `dao.space` to `memory-log` by name** and
  reject a future correct transport — a durable append-only log, a spilling
  log — that declares the same complete retention. That is precisely what
  "declared, never interrogated" exists to prevent, and why `dao.stream.md`
  lists a retention predicate as *Explicitly Absent*.
- **A kept origin cursor buys nothing here.** On a complete-retention
  transport a fresh `:oldest` *is* the origin, and the transactor does not
  create the local stream, so it could not mint at origin even if it wanted
  to. The mechanism is for consumers on transports that can evict.

So the residual risk is real and accepted deliberately: a wrongly wired
evicting transport's surviving suffix would be read and reported as the
whole history, silently. A `stream/reader?`/`stream/writer?` surface check
does not establish retention — the defence is this requirement being written
where someone wiring a composition will read it: here, in
`dao.space.index.md` (*The snapshot* / *The agent-transactor loop*), in
`dao.space.md` (*The Write Path*), and in ADR 0003's amendment.

## Where durability lives

The local stream is the **authoritative record for the logical stream's
lifetime** — the watermark and every published index are derived from it —
and it is **not durable**: `memory-log` is process-lifetime, and a restarted
process sees a new, empty logical stream with a new identity. That is the
correct shape, not a shortfall:

- **Stage 1, append** — the write lands in the agent's local stream and no
  storage handle is touched.
- **Stage 2, publish** — the covered indexes and manifest are appended to an
  intake stream and a DaoJing observer materializes them into content
  storage.

**The durable record is what publication puts in `dao.jing`.** Un-published
writes are not durable, and never were — v1's local streams were in-memory
ring buffers, so this was equally true before the migration; the design
merely claimed otherwise. The pipeline mirrors Datomic's memory-index →
disk-index, and a memory index is not durable either. A durable *stream*
transport is not the answer and should not be proposed as the fix: it would
put a second durable record beside the content store, which is exactly the
`dao.stream`/`dao.jing` unification `dao.space.query.md`'s *Decisions*
already ruled out.

## Open items

- **The O(history) watermark replay.** `create!` derives `t` by reading the
  whole retained history (`index/snapshot-datoms`), and `publish!` rebuilds
  the indexes from the origin — both correct today *because the transport
  cannot evict*. The eventual relief keeps one truth — the log carries its
  own checkpoint, from which the watermark and incremental indexes resume —
  rather than adding a second stored counter that can disagree with the
  record. Not designed here.

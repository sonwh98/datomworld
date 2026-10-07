Completed-GMT: 2026-09-08 13:52 GMT
Completed-Local: 2026-09-08 20:52 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828

# Plan: dao.space.transactor → dao.stream (with index's close folded in)

## Context

`dao.space.transactor` is the seam between v1 and v2: it validates its intake
pool with v2 `stream/writer?` but requires its local stream to satisfy the v1
reader and writer protocols, and it is itself a v1 stream registered through
`ds/defopen`. My index plan's D1 established that index cannot close until this
namespace moves, because `publish-index!`'s input *is* the transactor's local
stream. The owner took that ordering, so this plan migrates the transactor and
folds index's Phase 1 in, leaving index one step from done.

Same method: an explicit invariants list is the contract, marked `[D]` stated
in a design, `[T]` pinned only by a test, `[T→D]` test-pinned and worth
promoting, `[T✗]` an implementation accident dropped with its reason. The
existing implementation and tests have no authority beyond the invariants they
pin. Nothing is in production; tests are re-writable where an invariant is
preserved. This plan is deleted when consumed, so it must leave nothing owed.

Your measured facts check out — `transactor.cljc` 246 lines,
`transactor_test.cljc` 735, the v1/v2 asymmetry at :188-189 vs :200, and every
call site you listed. One addition, in section 2 below: nothing outside
`transactor_test` reads through the wrapper, which is what makes the central
simplification available.

---

## 1. Decisions

### D1 — a transactor is not a stream in v2. It is an interpreter over one. (Q2)

`dao.stream.md`'s *Composition* already names this case: "anything that
transforms one stream into another is an interpreter — it reads via its own
cursor and appends to an ordinary output stream — and that lives entirely
outside this contract, needing no support from it." Take the three v1
surfaces one at a time:

- **`append!`** is not a stream append. It takes an entity map or a datom
  vector, allocates transaction time, and writes a *transaction record*; it
  answers `{:result :ok :t t :datoms datoms}`, a transaction receipt, not a
  write outcome. Making that conform to `IDaoStreamWriter` would mean either
  lying about the value domain or losing the receipt.
- **`next`** (:164) is pure delegation: `(ds/next local-stream cursor)`. The
  caller *supplied* the local stream and still holds it, so the delegate adds
  nothing but a second name for one sequence. **Nothing outside
  `transactor_test` calls it** — `SchemaWrapper` implements only
  `ds/IDaoStreamBound`, and `stigmergy_test` reads `(:local agent)` directly.
- **`close!`/`closed?`** close nothing. They are a per-handle write gate over
  state the wrapper owns.

So the transactor becomes a **plain value with explicit named operations**,
the same move `dao.space.query.md`'s *Bounded realizations are values, not
streams* made for the relation transport and `query/open-published!` made for
the opened index:

```clojure
(def log (transactor/create! {:local-stream local          ; a v2 handle
                              :intake-pool [intake]        ; v2 writers
                              :name "worker-7"}))          ; optional

(transactor/append!   log {:db/id 1 :work/claims "task"})
(transactor/transact! log [{:db/id 1 :work/claims "task"} …])
(transactor/publish!  log opts)
(transactor/close!    log)
;; reads: use `local` — it is the caller's handle and always was
```

`next` is dropped (T12 below). `closed?` is dropped (T14): v2 lists a
`closed?` predicate as *Explicitly Absent* because "a predicate answer is
stale the moment it returns; operation results are authoritative," and the
replacement is already in the vocabulary — `append!`/`transact!` on a closed
transactor answer `{:dao.stream/outcome :dao.stream/closed}` as data.

### D2 — `ds/defopen :transactor` is replaced by a plain constructor, and the descriptor was never portable. (Q1)

v2 has no registry (*Explicitly Absent*: "an ambient registry — a
namespace-global dispatch table, or registration as a load-time side effect").
The replacement is `transactor/create!`, a plain function the caller calls,
following `query/open-published!`.

**The descriptor was never a descriptor.** v2's `descriptor` operation is
*reachability* data — `valid-descriptor?` requires a portable envelope, and
the whole point is naming a stream across a serialization boundary. The
`:transactor` map holds two live handles under `:local-stream` and
`:intake-pool`; it could never cross that boundary, so it was a v1 *dispatch*
record wearing the word "descriptor". It becomes the constructor's plain
options map. Call it a **spec**, not a descriptor, so the reachability word is
not imported into something that has none. A transactor implements no v2
protocol at all, `IDaoStreamDescriptor` included: it is not on a stream, so it
has no identity to project.

Return shape: **the value, or a throw** — not an outcome map. `create!`'s only
failure modes are a malformed spec (a caller bug) and a gap or malformed
retained history, and a gap on open is not retryable in the way `full` is: the
causality boundary cannot be recovered, and the caller has nothing to do but
abort. This is the layering index already settled — the snapshot mechanism is
total and returns every stopping outcome as data; *throwing is the policy of
the interpreter above it*. Keeping `create!` aligned with
`index/publish-index!` and `query/open-published!` matters more here than
symmetry with the per-write outcomes, which are genuinely retryable.

One naming call the owner may want to overrule: `create!` echoes v2's
`create!` (spec map in) while deliberately not following its outcome-map
convention, because a transactor is not a stream. The alternatives are
`open!` (collides with the `ds/open!` being deleted, so the collision is
temporary) and keeping schema's `transactor` verb-less style. Flagging it
rather than burying it.

### D3 — operational outcomes become data; argument defects keep throwing. (Q3)

`append-packet!` (:110-122) throws when the local append answers anything but
`{:result :ok}`. In v2 the local append answers `ok | full | invalid-value |
closed | transport-error`, and **`full` means "not yet, retry"** — throwing on
it was always wrong, because it converts a normal backpressure signal into an
exception the caller must catch to obey the retry contract the docstring
already promises.

So:

| Case | v1 | v2 |
|---|---|---|
| local append ok | `{:result :ok :t t :datoms ds}`, watermark advances | `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}`, watermark advances |
| local append `full`/`closed`/`invalid-value`/`transport-error` | throws | returned as data, watermark **unchanged**, same `t` still pending |
| local append answers a non-outcome | throws | folded to `:dao.stream/transport-error` with `:dao.stream/answer` retained |
| transactor itself closed | throws | `{:dao.stream/outcome :dao.stream/closed}` |
| malformed entity map / explicit datom `t` / empty `tx-data` / zero datoms | throws | **still throws** |
| local append throws | propagates, watermark unchanged | unchanged |

The last row of throws stays because those are defects in the caller's own
argument, detected before any stream is touched — they are the transactor's
contract with its caller, not the stream's outcome algebra, and converting
them is a behaviour change this migration does not force. `:dao.stream/
invalid-value` is available if the owner later wants them as data; say so in
the design and leave it.

**The retry contract gets stronger, not weaker.** It was prose plus a throw;
it becomes checkable: *the watermark advances if and only if the local append
answered `:dao.stream/ok`*, so retrying the identical call re-attempts the
identical `t`. `append-failure-does-not-advance-t-and-can-retry` (:499) keeps
its assertion and changes only how it observes the failure;
`append-throw-does-not-advance-t-and-can-retry` (:515) is unchanged.

Validate the local stream's answer with `stream/valid-outcome?` before
branching, exactly as `observe/valid-or-transport-error` does. That helper is
private to `observe`; a four-line local copy is the right call rather than
widening `observe`'s surface for one caller — note the duplication in the
docstring so a third copy triggers promotion instead of accumulating.

### D4 — `derive-next-t` keeps its O(history) replay. (Q4)

Keep, unchanged, and record the cost rather than paying for it here.

The local stream is the **only durable truth**. The watermark is derived
state, so it must be recoverable from that truth; any stored counter is a
second durable truth that can disagree with the first, and reconciling two
truths is a design, not an optimization. It is also not a new cost — the scan
is the same walk `publish-index!` already performs on every publish, and
`dao.space.index.md` already states that full retention is required for
exactly this reason.

Note in *Open items* that the eventual relief keeps one truth (the log carries
its own checkpoint) rather than adding a second. Do not design it here.

### D5 — the shared mechanism is `dao.stream.observe/snapshot`. No `drain`. (Q5)

Taking your naming rule as given: the destructive-read vocabulary stays out of
the namespace named for observation. Once the local stream is v2, there are
genuinely two consumers of one loop — mint at `:dao.stream/oldest`, run
`observe/step` with a *total* effect that retains the value and answers `ok`,
stop when the step stops — so promote it, keeping the name:

```clojure
(observe/snapshot handle)
;; => {:values   [v …]
;;     :status   :ended | :blocked | :gap | :defect
;;     :cursor   c           ; the cursor reached; on :gap the last retained
;;     :recovery c'          ; :gap only
;;     :read     raw}        ; :defect only
```

It returns **`:values`** — not a relation (that is query's tag) and not datoms
(that is index's vocabulary). It carries no policy: every stopping outcome is
data, and the cursor never advances past a value the accumulator did not
retain, which is `step`'s effect-before-commit and comes for free.

Each caller keeps its own policy, and the two only ever *looked* like they
disagreed:

- `query/snapshot` = `observe/snapshot` + `(relation values)`, statuses passed
  through, because a partial relation may still be a usable query input.
- `index/snapshot-datoms` = `observe/snapshot` + throw on `:gap`/`:defect` +
  `datoms-from-elements`, because `publish-index!` must abort before emitting
  anything (`dao.space.index.md`: "a retention gap aborts publication before
  anything is emitted").

`observe`'s ns docstring says `step` "owns no scheduler, loop, state, callback
or policy" and that callers add their own loop. Amend it: the namespace holds
`step` plus the one total-effect loop that has more than one consumer, and
`snapshot` inherits the no-policy rule verbatim — it *has* a loop, it still
has no policy.

### D6 — schema moves at its call sites only, and does not need its own migration to do it. (Q6)

`SchemaWrapper` implements only `ds/IDaoStreamBound`, and both methods
delegate to the transactor handle. When the transactor drops `closed?`, schema
holds the flag itself — it already has a per-wrapper `state` atom for exactly
this kind of coordination, so `close!` sets `:closed` and calls
`tx/close!`, and `closed?` reads it. That is three lines, not a migration.

So the transactor lands with **schema still v1-shaped**: `SchemaWrapper` stays
an `ds/IDaoStreamBound`, `schema/current`, the `:dao.space.schema/current` and
`:dao.space.schema/published` openers, and the `dao.stream` require all stay,
under schema's own plan. What this change forces, and nothing more:

| schema site | forced edit |
|---|---|
| `:965` `ds/open! {:dao.stream/type :transactor …}` | → `tx/create!` |
| `:949` `close!` → `(ds/close! inner)` | → set own `:closed`, call `tx/close!` |
| `:954` `closed?` → `(ds/closed? inner)` | → read own `:closed` |
| `:1112` `(tx/transact! (.-inner wrapper) datoms)` and `schema/transact!`'s own return | receipt shape follows D3 |
| `:926`, `:969` `index/snapshot-datoms local-stream` | code unchanged; the handle they receive is now v2 |
| `schema/transactor`'s `local-stream` argument | callers pass a v2 handle |

`(.-inner wrapper)` keeps working: the field just holds a map now.

### D7 — index's remaining v1 dependency after this plan

This plan closes index's **snapshot** remainder. It does not close index. The
second remainder from the index plan stands: `PublishedIndexStream` (index.cljc
:344-363) and `ds/defopen :dao.space.index/published` (:383-404) exist solely
for `:dao.space.schema/published` (schema.cljc:1154-1168), so
`[dao.stream :as ds]` survives in `dao.space.index` until schema's plan. See
section 6 — this is the cheapest moment to fold it in, and it is the owner's
call, not mine, because the constraint puts it out of scope.

---

## 2. The invariants this plan is accountable to

| # | Invariant | |
|---|---|---|
| T1 | Every `append!`/`transact!` writes exactly ONE atomic transaction record `{:dao.space/transaction {:t n :datoms […]}}` through exactly one local append, so no reader observes a torn transaction | `[D]` |
| T2 | `t` comes from a per-wrapper watermark, derived on open as 0 for empty history else 1 + max datom `t` | `[D]` |
| T3 | A caller-supplied `:next-t` is rejected | `[D]` |
| T4 | A retention gap or malformed retained history fails the open | `[D]` |
| T5 | The watermark advances **iff** the local append succeeded; a failed or thrown append leaves the same `t` retryable | `[D]` + `[T]` :499, :515 — strengthened by D3 from prose to a returned outcome |
| T6 | Single-writer: two wrappers over one local stream derive the same `t` and write colliding records; a documented hazard, not silently coordinated | `[D]` + `[T]` :655 |
| T7 | Close is per handle: it rejects further writes and neither closes nor erases the local stream or the intake pool | `[D]` + `[T]` :584 |
| T8 | Close linearizes after an in-flight append — no write crosses the point at which close returns | `[T]` :616 → `[T→D]`, it is what "calls through one wrapper are serialized" means operationally |
| T9 | Opening creates, registers, or closes nothing | `[T]` :273 → `[T→D]`, it is the "supplied, never created" invariant stated from the outside |
| T10 | Entity maps require `:db/id`; an explicit datom `t` is rejected; `[e a v]`/`[e a v nil m]` pad to canonical d5 and are validated | `[D]` |
| T11 | `publish!` passes the wrapper's local stream, pool and opts to `index/publish-index!` and returns its result; publication acknowledges enqueuing only | `[D]` |
| T12 | The wrapper delegates reads | `[T]` :382, :609 → **`[T✗]`**. Pure delegation to a handle the caller supplied and still holds; the only callers are the two tests that pin it. Dropped with `next`. |
| T13 | `close!` returns `{:woke []}` | **`[T✗]`**. v2 has no waiter registration (*Explicitly Absent*), so there is no wake list to report. Becomes `{:dao.stream/outcome :dao.stream/ok}`. |
| T14 | `closed?` is a public predicate | **`[T✗]`**. v2 *Explicitly Absent*, with the stated reason. Replaced by the `:dao.stream/closed` outcome on the next write. |
| T15 | An append receipt carries ok, the allocated `t`, and the datoms | `[T]` throughout → preserved, re-spelled in v2 vocabulary: `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}` |
| T16 | Intake pool members are validated with v2 `stream/writer?`; a non-empty collection is required | `[D]`, unchanged |
| T17 | A non-ok local append throws | **`[T✗]`**, replaced by D3: returned as data, since `full` is backpressure and the retry contract needs it. |

Index invariants S1–S8 and published invariants P1–P8 are as stated in the
index plan; Phase 3 preserves S1–S7 through `observe/snapshot` and drops S8
(incremental flattening) in Phase 1 as planned there.

---

## 3. Phase 1 — split index's payload vocabulary from its reading

Unchanged from the index plan, and it lands here because index's close now
follows immediately. `src/cljc/dao/space/index.cljc` only; no behaviour
change.

1. Rename private `stream-payload-datoms` → private `element-datoms`. Body
   unchanged; S3, S6, S7 preserved verbatim including both error messages.
2. Add public `datoms-from-elements`: `(into [] (mapcat element-datoms)
   elements)` — the local-stream payload vocabulary, one seq of elements to
   canonical local d5 datoms. `into` is eager, so S6's throw-before-emission
   ordering is unchanged.
3. `snapshot-datoms` becomes drain-then-flatten over the existing v1 loop:
   accumulate `(:ok result)` payloads, then `datoms-from-elements` on the
   `:blocked`/`:end` exit. Gap and malformed-signal branches unchanged.
4. `test/dao/space/index_test.cljc`: add
   `datoms-from-elements-is-the-local-stream-vocabulary`, pinning S2, S3, S6,
   S7 directly rather than only through `publish-index!` — bare datom passes
   through; transaction record flattens; extra keys / non-integer `:t` / empty
   `:datoms` / a datom whose `t` disagrees with the record all throw; a
   5-vector with a non-namespaced `a` gets "malformed local datom"; anything
   else gets "must be a datom or dao.space transaction record".

Green with no other edits.

## 4. Phase 2 — promote the snapshot loop to `dao.stream.observe/snapshot`

1. `src/cljc/dao/stream/observe.cljc`: add `snapshot` per D5, returning
   `{:values :status :cursor :recovery :read}`. Amend the ns docstring as D5
   describes.
2. `src/cljc/dao/space/query.cljc`: `snapshot` becomes
   `(let [{:keys [values status] :as s} (observe/snapshot handle)]
     (-> s (dissoc :values) (assoc :relation (relation values))))` — the same
   public shape, the same statuses, the loop deleted.
3. `test/dao/stream/observe_test.cljc`: move query_test's snapshot
   status-coverage cases (`:ended`, `:blocked`, `:gap` with recovery,
   `:defect`, and the cursor-never-advances-past-an-unretained-value case)
   down to `observe/snapshot`, where they now belong. `query_test` keeps the
   thin cases that are about the relation wrapper.

The second consumer arrives in Phase 3 of *this* plan, so this promotion is
sequencing rather than speculation. Stated plainly because it is the one place
the index plan's D2 rule ("promote when the second consumer appears") is being
run a phase early: if Phase 3 were abandoned, Phase 2 should be reverted with
it.

## 5. Phase 3 — the swap

One phase, because the constraint forbids leaving the `defopen` and
`DaoStreamLog` half-deleted and every consumer breaks at the same instant.

**`src/cljc/dao/space/transactor.cljc`** — delete `DaoStreamLog` and
`ds/defopen :transactor`; delete the `[dao.stream :as ds]` require and the
`#?(:cljs (:require-macros [dao.stream]))`.

- `create!` per D2: validate the spec (reject `:next-t`; require a
  `stream/reader?`-and-`stream/writer?` local stream; require a non-empty
  pool of `stream/writer?` members — the pool check at :199-204 is already v2
  and moves verbatim), then `(derive-next-t (index/snapshot-datoms
  local-stream))` and return
  `{:dao.space/transactor true :local-stream … :intake-pool … :name …
    :next-t (atom t) :state (atom {:closed false})}`.
  A tag key, following `:dao.space.query/published`.
- `append-packet!` per D3: `stream/append!`, `stream/valid-outcome?`, advance
  on `:dao.stream/ok` only, return the local outcome as data otherwise.
- `append!` / `transact!` become plain functions over the value, keeping
  `with-write-lock`, `val->datoms`, `entity->datoms`, `pad-datom` and
  `derive-next-t` exactly as they are. The closed check returns
  `{:dao.stream/outcome :dao.stream/closed}` instead of throwing.
- `close!` sets `:closed` inside the write lock and returns
  `{:dao.stream/outcome :dao.stream/ok}`; idempotent.
- `publish!` reads `(:local-stream log)` / `(:intake-pool log)` instead of
  deftype fields.
- No `next`, no `closed?`.

**`src/cljc/dao/space/index.cljc`** — `snapshot-datoms` becomes
`observe/snapshot` + policy + `datoms-from-elements` per D5; add
`[dao.stream.observe :as observe]`. `publish-index!`'s signature is
unchanged; its `local-stream` is now a v2 handle. The `ds` require **stays**,
for `PublishedIndexStream` only (D7) — mark it in the require with the one-line
reason so it reads as a remainder rather than an oversight.

**`src/cljc/dao/space/schema.cljc`** — the six forced edits in D6's table,
nothing else.

**Tests.** Mechanical but wide; the shape is one helper change repeated.

- `transactor_test`: add `open-local` returning a v2 ringbuffer owner handle
  (`ringbuffer/create!` with `:dao.stream.ringbuffer/capacity` 65536 — see the
  hazard below); `open-with-intake` calls `transactor/create!`; the three test
  doubles (`FailingAppendStream`, `ThrowingAppendStream`, the
  `close-linearizes` `reify`) become v2 writers answering
  `{:dao.stream/outcome …}`; `tx-ts` and the atomicity assertions read the
  local stream through the existing `intake-values` loop, generalized to
  `stream-values`. `readers-observe-atomic-transaction-records` (:382) and the
  read assertion at :609 read `local` instead of `log` (T12). The receipt
  assertions take the D3 shape. `descriptor-validation` becomes
  `spec-validation` and its throw messages change from ":transactor
  descriptor requires …" to the constructor's wording.
- `schema_test`, `stigmergy_test:106`, `index_test`'s `open-local`,
  `schema_test:670`: v2 local streams, same helper shape.

> **Migration hazard worth naming.** v1 `{:dao.stream/type :ringbuffer}` with
> no capacity was unbounded; v2's ringbuffer *requires* a positive capacity
> and evicts oldest. `derive-next-t` and `publish-index!` both need complete
> history from position zero, so an under-sized capacity turns silently into a
> **gap on the next open or publish** rather than a compile error. Use one
> generous capacity constant in each test helper, and state in the design that
> a transactor's local stream must be created with retention for its whole
> history — which is why T4 and index's S5 matter more after this change, not
> less.

## 6. Phase 4 — documentation

- **`docs/design/dao.space.index.md`**: *The agent-transactor loop* — replace
  the `ds/open! {:dao.stream/type :transactor …}` sample (:195-210) and the
  descriptor sentence (:191) with `transactor/create!` and the spec; add
  `datoms-from-elements` to the public surface; restate *The snapshot* as
  vocabulary + `observe/snapshot`; keep the D7 remainder entry and delete the
  snapshot remainder entry from the ledger the index plan added.
- **`docs/design/dao.space.md`**: *The Write Path* sample at :509 and the
  descriptor mention at :476.
- **`docs/design/dao.space.schema.md`**: :247 and :383 — the wrapper wraps a
  transactor *value*, and owns its own closedness.
- **`docs/design/dao.space.query.md`**: *Snapshots* — the mechanism is now
  `observe/snapshot`; `query/snapshot` is its relation-tagging caller; the gap
  policy stays with each caller.
- **`docs/design/dao.stream.md`**: *The v2 namespace is transient* — drop
  `transactor` from the remaining-consumer list. `index` and `schema` stay.
- A new `dao.space.transactor` section (or its own doc, owner's call) carrying
  T1–T11, T15, T16, D3's outcome table, and the O(history) note from D4 as an
  Open item.

## 7. If the schema constraint is relaxed

Optional Phase 5, out of scope by constraint, costed because **this is the
cheapest moment for it** — Phase 3 already opens `schema.cljc` at six sites,
and it is the one thing standing between index and done.

Rewrite `:dao.space.schema/published` over `index/read-datoms`: open the
coordinate, read the eager EAVT vector, close the store, wrap the vector in a
small private v1 reader record inside schema. Then delete
`PublishedIndexStream`, the `:dao.space.index/published` defopen, and
`[dao.stream :as ds]` from `dao.space.index`, along with index_test's ten
`ds/open!` adapter tests — P1/P2 are pinned on `published-index` itself,
P3/P4/P8 on `query/open-published!`, P6 is v1 protocol vocabulary that dies
with the adapter, and P7 was dropped in the index plan. Roughly 25 lines into
schema, 60 out of index. It is *simpler* than what exists: schema forces the
rows at open anyway (`ds/strict-vec` at :1162), so nothing there needs the lazy
trees or a retained store handle.

With it, `dao.space.index` closes in this plan and `dao.stream.md`'s consumer
list drops `index` too.

## 8. Verification

All three lanes green at the end of **every** phase, plus the demo:

```
bb test:clj
bb test:cljs      # confirm "Testing dao.space.transactor-test", "…index-test",
                  # "…schema-test" and "…observe-test" appear in the node output
bb test:cljd
clj -M:cljs -m shadow.cljs.devtools.cli compile demo
```

- **Demo.** `datomworld.demo` requires only `datomworld.demo.*`, `reagent` and
  `yin.vm.telemetry-viewer` — it reaches no `dao.space` namespace — so a break
  would be a compile regression rather than a behavioural one. Compile it each
  phase anyway, as the constraint requires.
- **The phase most likely to go red is 3**, and the two failure shapes to
  expect are (a) an under-sized v2 ringbuffer capacity surfacing as
  `malformed-or-gapped-retained-history-throws` (:317) or a publish gap, and
  (b) a receipt-shape assertion missed in the ~30 migrated sites. Both are
  loud.
- **`close-linearizes-after-an-in-flight-append` (:616) is the T8 canary** and
  is JVM-only. It must keep passing against the v2 `reify`; if it goes red,
  the write lock stopped covering the state transition.
- **End-to-end**, `stigmergy_test.clj` is the real integration check: agents
  with v2 local streams, transaction records, publish, observer
  materialization, and query over the published manifests. If it passes, T1,
  T2, T11 and index's S1–S6 all held across the swap.

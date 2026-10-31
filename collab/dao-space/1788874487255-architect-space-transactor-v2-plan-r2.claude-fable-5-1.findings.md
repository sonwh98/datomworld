Completed-GMT: 2026-09-08 14:24 GMT
Completed-Local: 2026-09-08 21:24 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828

# Plan: dao.space.transactor and dao.space.index → dao.stream (r2)

## Context

`dao.space.transactor` is the seam between v1 and v2: it validates its intake
pool with v2 `stream/writer?` but requires its local stream to satisfy the v1
reader and writer protocols, and it is itself a v1 stream registered through
`ds/defopen`. The index plan's D1 established that index cannot close until
this namespace moves, because `publish-index!`'s input *is* the transactor's
local stream. The owner took that ordering, and then took the index plan's
section 7 as well, so this sweep ends with **both** `dao.space.transactor` and
`dao.space.index` free of `dao.stream`, and `dao.space.schema` the only
`dao.space` namespace still on v1, under its own plan.

Method: an explicit invariants list is the contract, marked `[D]` stated in a
design, `[T]` pinned only by a test, `[T→D]` test-pinned and worth promoting,
`[T✗]` an implementation accident dropped with its reason. The existing
implementation and tests have no authority beyond the invariants they pin.
Nothing is in production; tests are re-writable where an invariant is
preserved. Old code is deleted in the phase that replaces it. This plan is
deleted when consumed, so it must leave nothing owed.

---

## 1. Decisions

### D1 — a transactor is not a stream in v2. It is an interpreter over one.

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

`next` is dropped (T12). `closed?` is dropped (T14): v2 lists a `closed?`
predicate as *Explicitly Absent* because "a predicate answer is stale the
moment it returns; operation results are authoritative," and the replacement
is already in the vocabulary — `append!`/`transact!` on a closed transactor
answer `{:dao.stream/outcome :dao.stream/closed}` as data.

### D2 — `ds/defopen :transactor` is replaced by a plain constructor, and the descriptor was never portable.

v2 has no registry (*Explicitly Absent*: "an ambient registry — a
namespace-global dispatch table, or registration as a load-time side effect").
The replacement is `transactor/create!`, a plain function the caller calls,
following `query/open-published!`. The owner did not overrule the name, so it
stands.

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
retained history, and a gap on open is not retryable the way `full` is: the
causality boundary cannot be recovered, and the caller has nothing to do but
abort. This is the layering index already settled — the snapshot mechanism is
total and returns every stopping outcome as data; *throwing is the policy of
the interpreter above it*. Keeping `create!` aligned with
`index/publish-index!` and `query/open-published!` matters more here than
symmetry with the per-write outcomes, which are genuinely retryable.

### D3 — operational outcomes become data; argument defects keep throwing.

`append-packet!` (:110-122) throws when the local append answers anything but
`{:result :ok}`. In v2 the local append answers `ok | full | invalid-value |
closed | transport-error`, and **`full` means "not yet, retry"** — throwing on
it was always wrong, because it converts a normal backpressure signal into an
exception the caller must catch to obey the retry contract the docstring
already promises.

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
them is a behaviour change this migration does not force.
`:dao.stream/invalid-value` is available if the owner later wants them as
data; say so in the design and leave it.

**The retry contract gets stronger, not weaker.** It was prose plus a throw;
it becomes checkable: *the watermark advances if and only if the local append
answered `:dao.stream/ok`*, so retrying the identical call re-attempts the
identical `t`.

Validate the local stream's answer with `stream/valid-outcome?` before
branching, exactly as `observe/valid-or-transport-error` does. That helper is
private to `observe`; a four-line local copy is the right call rather than
widening `observe`'s surface for one caller — note the duplication in the
docstring so a third copy triggers promotion instead of accumulating.

### D4 — `derive-next-t` keeps its O(history) replay.

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

### D5 — the shared mechanism is `dao.stream.observe/snapshot`. No `drain`.

Taking the owner's naming rule as given: the destructive-read vocabulary stays
out of the namespace named for observation. Once the local stream is v2, there
are genuinely two consumers of one loop — mint at `:dao.stream/oldest`, run
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

### D6 — schema moves at its call sites only, and does not need its own migration to do it.

`SchemaWrapper` implements only `ds/IDaoStreamBound`, and both methods
delegate to the transactor handle. When the transactor drops `closed?`, schema
holds the flag itself — it already has a per-wrapper `state` atom for exactly
this kind of coordination, so `close!` sets `:closed` and calls `tx/close!`,
and `closed?` reads it. That is three lines, not a migration.

What Phase 3 forces in schema, and nothing more:

| schema site | forced edit |
|---|---|
| `:965` `ds/open! {:dao.stream/type :transactor …}` | → `tx/create!` |
| `:949` `close!` → `(ds/close! inner)` | → set own `:closed`, call `tx/close!` |
| `:954` `closed?` → `(ds/closed? inner)` | → read own `:closed` |
| `:1112` `(tx/transact! (.-inner wrapper) datoms)` and `schema/transact!`'s own return | receipt shape follows D3 |
| `:926`, `:969` `index/snapshot-datoms local-stream` | code unchanged; the handle they receive is now v2 |
| `schema/transactor`'s `local-stream` argument | callers pass a v2 handle |

`(.-inner wrapper)` keeps working: the field just holds a map now. Phase 5
adds exactly one more schema edit, bounded in section 7.

### D7 — index ends this sweep requiring no `dao.stream` (restated)

The r1 version of this decision said index keeps `[dao.stream :as ds]` for the
published adapter. With Phase 5 that is false. Verified against the tree
rather than assumed — every `ds/` occurrence in `src/cljc/dao/space/index.cljc`:

| line | use | closed by |
|---|---|---|
| :347 | `ds/IDaoStreamReader` (PublishedIndexStream impl) | Phase 5 |
| :358 | `ds/IDaoStreamBound` (PublishedIndexStream impl) | Phase 5 |
| :383 | `ds/defopen :dao.space.index/published` | Phase 5 |
| :444 | the string "ds/next" inside `snapshot-datoms`' docstring | Phase 3 |
| :453 | `(ds/next local-stream cursor)` | Phase 3 |

That is the complete list — five occurrences, of which one is prose. The
owner's measurement (`snapshot-datoms` :451-468 and the published adapter
:344-404 as the only two regions) is confirmed. There is no
`#?(:cljs (:require-macros [dao.stream]))` in `index.cljc` to remove; its `ns`
form ends at :41.

**At the end of Phase 5, `dao.space.index` requires exactly:**
`dao.data.btree`, `dao.data.btree.storage`, `dao.datom`, `dao.jing`,
`dao.jing.coordinate`, `dao.stream`, and `dao.stream.observe` (new in
Phase 3). `dao.jing.coordinate` survives only if something in index still
opens a coordinate — after Phase 5 it does not, since `published-index` merely
*constructs* the coordinate map, so **`dao.jing.coordinate` goes too**. The
implementer must confirm this at the end of Phase 5 rather than trusting the
list: `grep -n "jing-coordinate/\|ds/" src/cljc/dao/space/index.cljc` must
return nothing.

`test/dao/space/index_test.cljc` likewise ends with zero `ds/` uses and drops
its `[dao.stream :as ds]` require — see Phase 5 for the accounting of all 41
current occurrences.

---

## 2. The invariants this plan is accountable to

### Transactor

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
| T9 | Opening creates, registers, or closes nothing | `[T]` :273 → `[T→D]`, the "supplied, never created" invariant stated from the outside |
| T10 | Entity maps require `:db/id`; an explicit datom `t` is rejected; `[e a v]`/`[e a v nil m]` pad to canonical d5 and are validated | `[D]` |
| T11 | `publish!` passes the wrapper's local stream, pool and opts to `index/publish-index!` and returns its result; publication acknowledges enqueuing only | `[D]` |
| T12 | The wrapper delegates reads | `[T]` :382, :609 → **`[T✗]`**. Pure delegation to a handle the caller supplied and still holds; the only callers are the two tests that pin it. Dropped with `next`. |
| T13 | `close!` returns `{:woke []}` | **`[T✗]`**. v2 has no waiter registration (*Explicitly Absent*), so there is no wake list to report. Becomes `{:dao.stream/outcome :dao.stream/ok}`. |
| T14 | `closed?` is a public predicate | **`[T✗]`**. v2 *Explicitly Absent*, with the stated reason. Replaced by the `:dao.stream/closed` outcome on the next write. |
| T15 | An append receipt carries ok, the allocated `t`, and the datoms | `[T]` throughout → preserved, re-spelled in v2 vocabulary: `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}` |
| T16 | Intake pool members are validated with v2 `stream/writer?`; a non-empty collection is required | `[D]`, unchanged |
| T17 | A non-ok local append throws | **`[T✗]`**, replaced by D3: returned as data, since `full` is backpressure and the retry contract needs it. |

### Index (from the index plan, carried forward)

**Snapshot** — S1 reads from cursor zero, full retention required `[D]`; S2
element is a canonical d5 vector or a transaction record `[D]`; S3 the
record's exact shape `[T→D]`; S4 `:blocked`/`:end` finish at the tail `[D]`;
S5 a gap throws before any emission `[D]`; S6 malformed anything throws before
any emission `[D]`; S7 the two distinct diagnostics `[T]`; S8 incremental
flattening `[T✗]`, dropped in Phase 1.

**Published** — P1 the coordinate's exact shape `[D]`; P2 plain EDN, survives
a stream round-trip `[T→D]`; P3 opening fetches exactly one blob, zero nodes
faulted `[D]`; P4 rows deferred, EAVT order, equal to `read-datoms` `[D]`; P5
a store opened during a failed open is closed before the error propagates
`[D]`; P6 the v1 adapter's reader/bound/not-writer, `closed?`-from-construction
and `{:woke []}` close `[T]` — **v1 protocol vocabulary that dies with the
adapter in Phase 5**; P7 two independent cursors over one realization `[T✗]`
— *Bounded realizations are values, not streams* already ruled that a
fully-computed row vector has no second-observer need; P8 `covered-indexes` is
a structural check, never an instance check `[D]`.

---

## 3. Phase 1 — split index's payload vocabulary from its reading

`src/cljc/dao/space/index.cljc` and its test only; no behaviour change. It
lands first because Phase 3 needs the vocabulary separable from the reader.

1. Rename private `stream-payload-datoms` → private `element-datoms`. Body
   unchanged; S3, S6, S7 preserved verbatim including both error messages.
2. Add public `datoms-from-elements`: `(into [] (mapcat element-datoms)
   elements)` — the local-stream payload vocabulary, one seq of elements to
   canonical local d5 datoms. `into` is eager, so S6's throw-before-emission
   ordering is unchanged.
3. `snapshot-datoms` becomes drain-then-flatten over the existing v1 loop:
   accumulate `(:ok result)` payloads, then `datoms-from-elements` on the
   `:blocked`/`:end` exit. Gap and malformed-signal branches unchanged. S8 is
   dropped here, with its reason.

**Proof.** Add `datoms-from-elements-is-the-local-stream-vocabulary` to
`index_test`, pinning S2, S3, S6, S7 directly rather than only through
`publish-index!`: a bare datom passes through; a transaction record flattens;
extra keys / non-integer `:t` / empty `:datoms` / a datom whose `t` disagrees
with the record all throw; a 5-vector with a non-namespaced `a` gets
"malformed local datom"; anything else gets "must be a datom or dao.space
transaction record". Every existing snapshot test stays as written and now
pins the drain and the composition rather than the vocabulary.

## 4. Phase 2 — promote the snapshot loop to `dao.stream.observe/snapshot`

1. `src/cljc/dao/stream/observe.cljc`: add `snapshot` per D5. Amend the ns
   docstring as D5 describes.
2. `src/cljc/dao/space/query.cljc`: `snapshot` becomes
   `(let [{:keys [values] :as s} (observe/snapshot handle)]
      (-> s (dissoc :values) (assoc :relation (relation values))))` — same
   public shape, same statuses, loop deleted.
3. `test/dao/stream/observe_test.cljc`: move query_test's status-coverage
   cases (`snapshot-of-an-open-buffer-is-blocked` :339,
   `snapshot-of-a-closed-buffer-ends` :350, `snapshot-of-a-gap-is-data` :359,
   `snapshot-of-a-defect-carries-the-raw-answer` :386,
   `snapshot-never-closes-the-handle` :402) down to `observe/snapshot`, where
   they now belong, restating them over `:values`. `query_test` keeps
   `snapshot-relation-feeds-current-and-q` (:410), which is the relation
   wrapper's own property.

The second consumer arrives in Phase 3 of *this* plan, so this promotion is
sequencing rather than speculation. Stated plainly because it is the one place
the index plan's rule ("promote when the second consumer appears") runs a
phase early: if Phase 3 were abandoned, Phase 2 should be reverted with it.

## 5. Phase 3 — the swap

One phase, because the constraint forbids leaving the `defopen` and
`DaoStreamLog` half-deleted and every consumer breaks at the same instant.

**`src/cljc/dao/space/transactor.cljc`** — delete `DaoStreamLog` and
`ds/defopen :transactor`; delete the `[dao.stream :as ds]` require and the
`#?(:cljs (:require-macros [dao.stream]))`.

- `create!` per D2: validate the spec (reject `:next-t`; require a
  `stream/reader?`-and-`stream/writer?` local stream; require a non-empty pool
  of `stream/writer?` members — the pool check at :199-204 is already v2 and
  moves verbatim), then `(derive-next-t (index/snapshot-datoms local-stream))`
  and return `{:dao.space/transactor true :local-stream … :intake-pool …
  :name … :next-t (atom t) :state (atom {:closed false})}`. A tag key,
  following `:dao.space.query/published`.
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
unchanged; its `local-stream` is now a v2 handle. The `ds` require survives
this phase for the published adapter only, and dies in Phase 5.

**`src/cljc/dao/space/schema.cljc`** — the six forced edits in D6's table,
nothing else.

**Tests.**

- `transactor_test`: add `open-local` returning a v2 ringbuffer owner handle;
  `open-with-intake` calls `transactor/create!`; the three test doubles
  (`FailingAppendStream`, `ThrowingAppendStream`, the `close-linearizes`
  `reify`) become v2 writers answering `{:dao.stream/outcome …}`; `tx-ts` and
  the atomicity assertions read the local stream through the existing
  `intake-values` loop, generalized to `stream-values`.
  `readers-observe-atomic-transaction-records` (:382) and the read assertion
  at :609 read `local` instead of `log` (T12). Receipt assertions take the D3
  shape. `descriptor-validation` becomes `spec-validation`, its throw messages
  following the constructor's wording.
- `schema_test`, `stigmergy_test:106`, `index_test`'s `open-local`,
  `query_test`'s `open-local` (:567), `schema_test:670`: v2 local streams,
  under the rule in section 6.

**Proof.** The unchanged deftests are the proof: `reopen-derives-next-t-from-
retained-history` (:291) for T2, `malformed-or-gapped-retained-history-throws`
(:317) for T4, `each-transaction-is-exactly-one-append` (:366) for T1,
`append-failure-does-not-advance-t-and-can-retry` (:499) and
`append-throw-does-not-advance-t-and-can-retry` (:515) for T5 — the first now
observing a returned outcome instead of a catch —
`close-is-per-handle-and-does-not-touch-local-stream` (:584) for T7 and T13,
`close-linearizes-after-an-in-flight-append` (:616) for T8,
`single-writer-wrappers-are-not-coordinated` (:655) for T6,
`publish-enqueues-indexes-into-the-pool` (:681) for T11. T14's replacement
needs a *new* case: an `append!` and a `transact!` after `close!` each answer
`{:dao.stream/outcome :dao.stream/closed}` rather than throwing.

## 6. The retention-capacity rule

This is the one hazard in the sweep that can pass CI by luck, so it gets its
own section rather than a note.

**The asymmetry.** v1's ring buffer treats a missing capacity as unbounded —
`ringbuffer.cljc:82`'s eviction test is `(and capacity …)`, so
`{:dao.stream/type :ringbuffer}` never evicts. v2's `valid-spec?`
(`v2/ringbuffer.cljc:15-20`) *requires* a positive integer capacity and
`append!` evicts oldest once the buffer is full. So the migration cannot be
mechanical: every unbounded v1 open must acquire a number, and the number is a
correctness decision, not a formality.

**Why the transactor is the worst possible place for it.** `derive-next-t` and
`publish-index!` both read from position zero and both require complete
retained history. An undersized capacity does not fail to compile and does not
fail at the append that overflows — it fails later, at the *next* `create!` or
`publish!`, as a `:dao.stream/gap`. Left undersized in a test that never
reopens or republishes, it fails nowhere at all, and the plan would have
quietly traded a causality invariant for a green suite.

**Measured scope.** There are **29** exact `{:dao.stream/type :ringbuffer}`
opens (no capacity) across `src` and `test`, one more than the owner's 28, and
the more useful fact is where they are: **all 29 are in `dao.space` tests** —
`transactor_test` 22, `index_test` 2, `schema_test` 2, `query_test` 2,
`stigmergy_test` 1. No `src` namespace opens an unbounded ring buffer. The
hazard is entirely inside the files this plan already touches.

**Three of the 29 must not be migrated**, and mistaking them for the others is
its own defect:

| site | why it stays v1 |
|---|---|
| `query_test:97` | not an open at all — a literal map passed to `q` to prove a raw map is rejected as a db input |
| `schema_test:304` | builds a *closed v1 realization* for `schema/current`'s borrowed-input path; that is schema's own v1 surface, under schema's plan |
| `index_test:777` | the descriptor carrier, deleted outright in Phase 5 |

The remaining **26** migrate.

**The rule the implementer follows at every one of the 26.** Do not pick a
capacity per call site. Define exactly one named constant per test namespace —
`local-capacity`, at 65536 — use it for every migrated local stream, and never
inline a literal. Then:

1. If a site's history is bounded by the test's own appends, the constant is
   correct by inspection and the review is one comparison, not 26.
2. If a site *deliberately* wants eviction, it takes an explicit small
   capacity and a comment saying which gap it is provoking. There is exactly
   one such site today, `index_test:379`, which already passes a capacity and
   therefore is not in the 26.

**How a violation is caught rather than passing by luck.** A too-small
capacity is only observable through a *second* read from position zero, so add
one test that forces exactly that, in `transactor_test`:

> `retained-history-survives-reopen-at-capacity` — append more transaction
> records than a deliberately small capacity retains, then `create!` a second
> transactor over the same local stream and assert it throws the retention-gap
> error (T4). Then repeat at `local-capacity` and assert the reopen succeeds
> and derives the expected `t`.

That test fails loudly if the constant is ever lowered below what the suite
appends, and it pins T4 against a real eviction rather than the synthetic gap
stream `index_test:379` uses. Without it, `derive-next-t`'s dependence on full
retention is pinned only by a hand-built gap, which is exactly the kind of
coverage that survives a capacity regression.

## 7. Phase 4 — documentation for the transactor migration

- **`docs/design/dao.space.index.md`**: *The agent-transactor loop* — replace
  the `ds/open! {:dao.stream/type :transactor …}` sample (:195-210) and the
  descriptor sentence (:191) with `transactor/create!` and the spec; add
  `datoms-from-elements` to the public surface; restate *The snapshot* as
  vocabulary + `observe/snapshot`.
- **`docs/design/dao.space.md`**: *The Write Path* sample at :509 and the
  descriptor mention at :476.
- **`docs/design/dao.space.schema.md`**: :247 and :383 — the wrapper wraps a
  transactor *value* and owns its own closedness.
- **`docs/design/dao.space.query.md`**: *Snapshots* — the mechanism is now
  `observe/snapshot`; `query/snapshot` is its relation-tagging caller; the gap
  policy stays with each caller.
- **`docs/design/dao.stream.md`**: *The v2 namespace is transient* — drop
  `transactor` from the remaining-consumer list. Phase 5 drops `index`.
- A new `dao.space.transactor` section (or its own doc, owner's call) carrying
  T1–T11, T15, T16, D3's outcome table, the retention-capacity rule from
  section 6, and D4's O(history) note as an Open item.

Phases 4 and 5 are order-independent; Phase 5 carries its own doc edits, so
every phase ends with the docs true.

## 8. Phase 5 — index closes

**What is built.** One rewritten `defopen` body in `dao.space.schema`:

```clojure
(ds/defopen :dao.space.schema/published
  [descriptor]
  ;; validate exactly as today, then:
  (let [store (jing-coordinate/open! content-store)
        rows  (try (index/read-datoms store manifest-address)
                   (finally (jing/close! store)))]
    (->PublishedSchemaRows rows)))
```

`PublishedSchemaRows` is a small private record in `schema.cljc` implementing
`ds/IDaoStreamReader` (`next` by position over the vector) and
`ds/IDaoStreamBound` (`close!` → `{:woke []}`, `closed?` → true). This is
*simpler* than what it replaces: schema forces the whole row vector at open
anyway (`ds/strict-vec` at :1167), so nothing on this path ever needed the
lazy restored trees or a retained store handle. The store is closed inside the
opener instead of surviving until `schema/current` closes the reader, which
also removes the comment at :1163-1166 explaining why the delay had to be
forced before the store went away.

**What is deleted.**
- `schema.cljc:1161` `ds/open! (index/published-index …)` and `:1167`
  `ds/strict-vec` — replaced by the body above.
- `index.cljc:344-363` `PublishedIndexStream`.
- `index.cljc:383-404` `ds/defopen :dao.space.index/published`.
- `index.cljc`'s `[dao.stream :as ds]` and `[dao.jing.coordinate :as
  jing-coordinate]` requires (D7).
- `index_test`'s `[dao.stream :as ds]` require, after the accounting below.

**What Phase 5 must not touch.** The edit is one `defopen` body. Explicitly
out of bounds:
- `ds/defopen :dao.space.schema/current` (:357) and its whole opener —
  untouched.
- `SchemaWrapper` (:943-955) beyond the three closedness edits Phase 3 already
  forces.
- `ds/strict-vec` at :260 and `ds/realization?` at :259/:318 — the
  borrowed-input path, schema's own surface.
- `schema_test:304`'s v1 realization fixture.
- Schema's closedness model beyond D6's table.

**The ten `ds/open!` adapter call sites, per test.** They are ten call sites
across eight deftests. Each property is accounted for, because a deleted test
whose property is not covered elsewhere is a defect:

| # | `index_test` deftest (line, `ds/open!` sites) | property | disposition |
|---|---|---|---|
| 1 | `open-published-rejects-unresolvable-and-malformed-descriptors` (:586; 600, 611) | an unsupported coordinate type fails closed at open; an extra key is rejected — P1 | **Moved, not deleted.** Rewrite onto `query/open-published!` in `query_test`. Nothing there covers coordinate rejection today, so deleting outright would drop P1's open-time half. |
| 2 | `open-published-rejects-missing-and-invalid-manifests` (:624; 637, 647) | a missing manifest address throws; a stored non-manifest throws | **Deleted.** Covered by `read-manifest-guards-missing-and-invalid` (:823), which pins both throws directly on `read-manifest`/`read-datoms` — the functions the new opener and `open-published!` both call. |
| 3 | `published-realization-is-read-only-with-a-stable-lifecycle` (:651; 656) | reader+bound not writer, `closed?` from construction, `{:woke []}` idempotent close, close does not erase — P6; EAVT order; two independent cursors — P7 | **Deleted.** P6 is v1 protocol vocabulary that dies with the adapter; P7 is dropped per the index plan. EAVT order survives in #7's replacement and in `read-datoms`' own tests; idempotent close survives as `close-published-closes-once-and-is-idempotent` (`query_test`:715). |
| 4 | `published-empty-index-drains-to-end` (:681; 683) | an empty manifest reads as no datoms, not an error | **Deleted, with one addition.** `publish-index-of-empty-input-is-readable` (:351) already covers it through `read-datoms`; add one `query_test` case that `open-published!` on an empty manifest yields an empty relation, since the *opened* path is otherwise uncovered. |
| 5 | `published-open-fetches-only-the-manifest` (:690; 700) | exactly one content fetch at open, zero nodes faulted — P3 | **Moved, not deleted.** `lazy-published-node-budget-is-strictly-bounded` (`query_test`:737) bounds *total* gets after a query at ≤ 4; it does not pin the open-time count. Add `open-published-fetches-only-the-manifest` to `query_test` asserting exactly 1 get after `open-published!` and before any read, reusing the existing `counting-content-store` helper. |
| 6 | `covered-indexes-returns-the-four-covered-sets` (:708; 710) | the opened realization carries the four sets; the check is structural, never an instance check — P8 | **Kept, minus the open.** The structural cases (nil, `{}`, wrong key sets, a plain four-key map) need no opened value and stay verbatim. The "an opened realization carries the four sets" case moves onto `query/open-published!`'s value, which carries `:indexes` — and that move is itself the proof of P8, since a plain map and an opened index are indistinguishable to `covered-indexes`. |
| 7 | `published-next-yields-the-same-eavt-rows-as-the-eager-walk` (:736; 741) | the deferred rows equal the eager walk, in EAVT order — P4 | **Moved.** Rewrite in `query_test` as `open-published!`'s forced `:rows` delay versus `index/read-datoms`. |
| 8 | `published-index-is-a-transportable-bounded-stream` (:759; 783, plus the carrier at 777) | the coordinate is plain EDN, exact-bounded, carries the EAVT comparator, travels through a stream, and opens on the far side — P2 | **Moved and modernized.** Keep the `pr-str`/`read-string` round-trip and the carrier — now a **v2** ring buffer — and open the transported coordinate with `query/open-published!`. `ds/exact-bound?` (:779) is v1; replace it with a direct assertion on `{:manifest-address …}`, which is what P1 fixes anyway. |

**`index_test`'s remaining 41 `ds/` occurrences.** After Phase 3 migrates
`open-local` (:69) and the gap fixture (:382), `stream-values` (:122), and the
malformed-signal reader double (:27-30) to v2, and Phase 5 removes the eight
deftests above, the residue is zero. The implementer confirms with `grep -c
"ds/" test/dao/space/index_test.cljc` returning 0 and drops the require; a
non-zero count means a property was migrated by hand instead of by rule.

**Proof for Phase 5.** `stigmergy_test` is the end-to-end check that the new
schema opener is behaviourally identical: it publishes, materializes, and
queries published manifests through `query/open-published!` across both
`:dao.jing/file` and `:dao.jing/remote` coordinates. `schema_test` W38–W41
(:1449, :1481, :1522) are the direct check on the rewritten opener and must
pass **unmodified** — they exercise `schema/published` through
`ds/open!`/`schema/current`, which is exactly the surface Phase 5 preserves
while replacing its implementation. If any of the three needs editing, the
opener's behaviour changed and the phase is wrong.

## 9. End condition

When Phase 5 lands:

- **`dao.space.transactor` requires no `dao.stream`.** Its requires are
  `dao.datom`, `dao.space.index`, `dao.stream`.
- **`dao.space.index` requires no `dao.stream`.** Its requires are
  `dao.data.btree`, `dao.data.btree.storage`, `dao.datom`, `dao.jing`,
  `dao.stream`, `dao.stream.observe`. Confirmed by grep, not by
  inspection (D7).
- **`docs/design/dao.stream.md`'s remaining-consumer list drops both**, from
  "`dao.space` (index, schema, transactor — `query` migrated …)" to
  "`dao.space` (`schema` only — `query`, `index` and `transactor` migrated,
  and the `dao.stream.relation` transport was eliminated with `query`)".
- **`dao.space.schema` is the only `dao.space` namespace left on v1.** Its
  remaining surface, measured and corrected against the owner's count of "21
  sites across seven APIs" — the owner's `ds/close! ×4` includes the prose
  mention at :1164, so it is 3 call sites, and the protocol references and the
  `SchemaWrapper` implementation are three more sites the count folded
  together:

| API | today | after Phases 3+5 | remaining sites |
|---|---|---|---|
| `ds/open!` | 4 | 2 | :351, :363 |
| `ds/closed?` | 4 | 3 | :323, :1069, :1127 |
| `ds/close!` | 3 (+1 in prose) | 2 | :367, :373 |
| `ds/strict-vec` | 2 | 1 | :260 |
| `ds/realization?` | 2 | 2 | :259, :318 |
| `ds/defopen` | 2 | 2 | `:dao.space.schema/current` :357, `:dao.space.schema/published` :1154 (body replaced, form kept) |
| `satisfies? ds/IDaoStream*` | 2 | 2 | :319, :364 |
| `SchemaWrapper` protocol impl | 1 | 1 | :945 |
| **total code sites** | **20** | **15** | |

  Plus one *addition*: Phase 5's `PublishedSchemaRows` record. That is
  deliberate and worth naming — Phase 5 does not reduce the repo's total v1
  surface so much as **relocate index's share of it into the one namespace
  that still owes a plan**, where schema's migration deletes the whole
  `:dao.space.schema/published` opener and the record with it. A remainder
  belongs with its owner.
- Nothing in this plan is owed to a later plan except schema's own migration,
  which was always its own.

## 10. Verification

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
- **Phase 3 is the likeliest red**, and the two failure shapes are (a) an
  undersized ring-buffer capacity surfacing as
  `malformed-or-gapped-retained-history-throws` (:317) or a publish gap, and
  (b) a receipt-shape assertion missed in the ~30 migrated sites. Section 6's
  new `retained-history-survives-reopen-at-capacity` exists so (a) is loud
  rather than latent.
- **`close-linearizes-after-an-in-flight-append` (:616) is the T8 canary**,
  JVM-only. If it goes red against the v2 `reify`, the write lock stopped
  covering the state transition.
- **`schema_test` W38–W41 must pass unmodified through Phase 5** — that is the
  phase's own proof, per section 8.
- **End-to-end**, `stigmergy_test.clj` covers the whole sweep: agents with v2
  local streams, transaction records, publish, observer materialization, and
  query over published manifests through both file and remote coordinates. If
  it passes, T1, T2, T11, S1–S6 and P1–P5 all held across the swap.
- **The two greps that close D7**, run at the end of Phase 5, both returning
  nothing: `grep -n "jing-coordinate/\|ds/" src/cljc/dao/space/index.cljc` and
  `grep -n "ds/" test/dao/space/index_test.cljc`.

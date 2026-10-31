Completed-GMT: 2026-09-09 11:48 GMT
Completed-Local: 2026-09-09 18:48 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828

# Plan: dao.space.transactor and dao.space.index → dao.stream (r4)

## Context

`dao.space.transactor` is the seam between v1 and v2: it validates its intake
pool with v2 `stream/writer?` but requires its local stream to satisfy the v1
reader and writer protocols, and it is itself a v1 stream registered through
`ds/defopen`. The index plan's D1 established that index cannot close until
this namespace moves, because `publish-index!`'s input *is* the transactor's
local stream. This sweep ends with **both** `dao.space.transactor` and
`dao.space.index` free of `dao.stream`, and `dao.space.schema` the only
`dao.space` namespace still on v1, under its own plan.

Method: an explicit invariants list is the contract, marked `[D]` stated in a
design, `[T]` pinned only by a test, `[T→D]` test-pinned and worth promoting,
`[T✗]` an implementation accident dropped with its reason, and `[D✗]` a
*stated* invariant retired because the transport now discharges it
structurally. The existing implementation and tests have no authority beyond
the invariants they pin. Old code is deleted in the phase that replaces it.
This plan is deleted when consumed, so it must leave nothing owed.

### Revision history, so an implementer reads only this file

**r2 did not survive review.** `:oldest` is the earliest *retained* position,
so `derive-next-t` would have derived transaction time from a truncated
suffix, silently, on a tree with every lane green. r2's answer — a capacity
constant and a `retained-history-survives-reopen-at-capacity` test — was worse
than the defect: that test cannot produce the gap it asserts, so it would have
pinned a defect as a feature.

`dao.stream.memory-log` (`ba90b3a`) and three contract commits (`2e25b5c`,
`ed30d7b`, `ff44107`) replaced all of it in r3, which deleted the capacity
section, deleted the `observe/snapshot` promotion, and merged the standalone
documentation phase into the code phases.

**r4 takes four corrections from the r3 review**, all accepted:

| # | Correction | Where |
|---|---|---|
| P1-1 | r3's `snapshot-datoms` interpreted v2 results before validating their shape, so a repeated `ok` without a cursor would **spin forever** where the old loop threw | D6, §3, §4.2, §4.4 |
| P1-2 | r3 contradicted itself on P8: the table kept `covered-indexes-…`, the residue section removed "the eight deftests" | §5.4, §5.5 — **seven** leave |
| P2-1 | r3's Phase 1 claimed behaviour-neutrality while changing failure ordering, and dropped S8 without the reason the method requires | §3 — flattening stays incremental, S8 is **kept and promoted**, not dropped |
| P2-2 | "the only durable truth" is false of a process-lifetime transport | D4, §4.7 — and the false claim already in `dao.space.md:390` is swept with it |

The review's judgements on the Phase 2 deletion, the residual-risk statement
and its placement, and the S5/T4b deletions were confirmed sound and stand
unchanged, with one addition it asked for (§8).

---

## 1. Decisions

### D1 — a transactor is not a stream in v2. It is an interpreter over one.

`dao.stream.md`'s *Composition* already names this case: "anything that
transforms one stream into another is an interpreter — it reads via its own
cursor and appends to an ordinary output stream — and that lives entirely
outside this contract, needing no support from it." Take the three v1 surfaces
one at a time:

- **`append!`** is not a stream append. It takes an entity map or a datom
  vector, allocates transaction time, and writes a *transaction record*; it
  answers `{:result :ok :t t :datoms datoms}`, a transaction receipt, not a
  write outcome. Making that conform to `IDaoStreamWriter` would mean either
  lying about the value domain or losing the receipt.
- **`next`** (:164) is pure delegation: `(ds/next local-stream cursor)`. The
  caller *supplied* the local stream and still holds it, so the delegate adds
  nothing but a second name for one sequence. Nothing outside `transactor_test`
  calls it — confirmed by review.
- **`close!`/`closed?`** close nothing. They are a per-handle write gate over
  state the wrapper owns.

So the transactor becomes a **plain value with explicit named operations**, the
same move `dao.space.query.md`'s *Bounded realizations are values, not
streams* made for the relation transport and `query/open-published!` made for
the opened index:

```clojure
(def local (:dao.stream/handle
             (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))

(def log (transactor/create! {:local-stream local
                              :intake-pool [intake]      ; v2 writers
                              :name "worker-7"}))        ; optional

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

`create!` validates **surfaces, never retention**. `stream/reader?` and
`stream/writer?` are v2's own public gating functions and stay; retention is
"declared, never interrogated," and `dao.stream.md`'s *Explicitly Absent* now
lists a retention predicate for the same reason `closed?` is absent. **A
reader/writer surface check does not establish retention** — the two are
different questions and the contract answers them differently. That sentence
appears verbatim in all four durable placements (§8).

Return shape: **the value, or a throw** — not an outcome map. `create!`'s
failure modes are a malformed spec and a malformed retained history, both of
which the caller can only abort on. This keeps `create!` aligned with
`index/publish-index!` and `query/open-published!`.

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
argument, detected before any stream is touched — the transactor's contract
with its caller, not the stream's outcome algebra.

Worth noting rather than hiding: on a `memory-log` local stream the only
reachable non-ok outcome is `closed`, since the transport excludes `full`,
`invalid-value` and `transport-error`. The branch is not dead code for an
excluded outcome — `append-packet!` is a library function over whatever handle
it is given, and its `case` must be total — but no test can induce `full` on
the real transport, which is why T19's test below uses a **writer double**
rather than trying to fill a log that cannot fill.

**The retry contract gets stronger, not weaker.** It was prose plus a throw; it
becomes checkable: *the watermark advances if and only if the local append
answered `:dao.stream/ok`*, so retrying the identical call re-attempts the
identical `t`.

Validate the local append's answer with `stream/valid-outcome?` before
branching, the same contract machinery D6 uses on the read side.

### D4 — `derive-next-t` keeps its O(history) replay, and is now actually correct.

Keep, unchanged, and record the cost rather than paying for it here.

The local stream is the **authoritative retained truth for the logical
stream's lifetime**. The watermark is derived state, so it must be recoverable
from that truth; any stored counter is a second truth that can disagree with
the first, and reconciling two truths is a design, not an optimization.

**Not "the only durable truth" — that phrasing was false** (review P2-2).
`memory-log` is explicitly process-lifetime: a restarted process sees a new,
empty logical stream with a new identity, and durability is `dao.jing`'s job
through publication. Nothing regresses here — the v1 local streams were
in-memory ring buffers, so the local log was never durable — but the design
said otherwise, and `dao.space.md:390` still says "The local stream is the
durable record." That sentence is swept in §4.7, and the permanent transactor
section added there must not reintroduce the claim.

What did change is the ground the replay stands on. Under r2 `derive-next-t`
was not merely expensive but *wrong*: it replayed whatever suffix survived. It
is correct now **because the transport cannot evict**, not because it mints
`:oldest`.

Note in *Open items* that the eventual relief keeps one truth — the log carries
its own checkpoint — rather than adding a second. Do not design it here.

### D5 — no `observe/snapshot` promotion, no `observe/drain`, no shared retention law.

r2 promoted `query/snapshot`'s loop into `dao.stream.observe` on the
grounds that index's snapshot would be a second consumer. That phase is
deleted, and review confirmed the deletion sound:

- `query/snapshot` exists to classify *every* stopping outcome as data because
  its input's nature is unknown — it takes any v2 reader handle a caller has.
- `index/snapshot-datoms` reads a handle whose nature is **declared**: complete
  retention, `gap` excluded, `transport-error` excluded. Three outcomes are
  reachable: `ok`, `blocked`, `end`.

A shared loop would either leak query's status vocabulary into index or
require policy injection larger than the duplicated mechanism.

**This does not extend to result-shape validation.** `stream/validate-outcome`
is contract machinery, not query policy, and D6 uses it. Sharing the
contract's own validator is not sharing `query/snapshot`.

There is **no `observe/drain`** — the name imports the destructive-read
vocabulary the contract removed into the namespace named for observation — and
if the mechanism is ever shared it keeps the name `snapshot`. A generic
complete-retention law in the conformance harness is likewise deferred until a
second complete-history transport exists; one transport is not a
generalization.

### D6 — what `snapshot-datoms` becomes, and which throws survive

```clojure
(defn- checked
  "Fold a defective result into an exception before anything is read from it.
   The contract's own validator (dao.stream/validate-outcome) answers nil
   for a conforming result and a defect map otherwise; interpreting an
   unvalidated result is how a loop ends up recurring on a nil cursor."
  [operation result]
  (if-let [defect (stream/validate-outcome operation result)]
    (throw (ex-info "malformed local stream result"
                    {:operation operation, :result result, :defect defect}))
    result))


(defn snapshot-datoms
  "Read an agent-local stream in full and flatten it to canonical local d5
   datoms.

   The local stream is on a complete-retention transport
   (dao.stream.memory-log), so a fresh :oldest cursor is the origin and
   `gap` cannot occur — completeness comes from the transport's declared
   retention, never from the anchor (dao.stream.md, *Complete history*).
   `blocked` (an open stream caught up) and `end` (a closed one fully read)
   finish the read at the tail. Each element is validated and flattened as it
   is read, so the first defect in stream order is the one reported and no
   read happens past it. A well-formed but unexpected outcome means the handle
   is not the transport the composition owes."
  [local-stream]
  (let [mint (checked :cursor (stream/cursor local-stream :dao.stream/oldest))]
    (when-not (= :dao.stream/ok (:dao.stream/outcome mint))
      (throw (ex-info "local stream refused an :oldest cursor" {:result mint})))
    (loop [cursor (:dao.stream/cursor mint)
           datoms []]
      (let [r (checked :next (stream/next local-stream cursor))]
        (case (:dao.stream/outcome r)
          :dao.stream/ok
          (recur (:dao.stream/cursor r)
                 (into datoms (element-datoms (:dao.stream/value r))))
          (:dao.stream/blocked :dao.stream/end) datoms
          (throw (ex-info "local stream is not a complete-retention transport"
                          {:outcome (:dao.stream/outcome r), :result r})))))))
```

Three things are load-bearing here, and each answers a specific finding.

**Validate before interpreting (review P1-1).** r3's loop branched on
`(:dao.stream/outcome r)` and then took `:dao.stream/value` and
`:dao.stream/cursor` on trust. `MalformedResultStream` (`index_test`:25)
returns its configured result on *every* read, so an `ok` carrying no cursor
would make the loop recur with `nil`, receive the same result, and spin
forever — `index_test:395` passes today and would have hung the suite. A
stateful double answering `blocked` next would instead have accepted malformed
input silently. `validate-outcome` (`v2.cljc:245`) answers `nil` for a
conforming result and a defect map otherwise; `checked` turns that into the
throw the old loop gave. The exception message retains the word "malformed" so
the existing `#"malformed"` assertions still match.

**Flatten incrementally (review P2-1).** `element-datoms` is called per element
inside the loop, exactly as the v1 implementation did. r3 proposed
drain-then-flatten and called it behaviour-neutral; it is not — it changes
which defect wins when an early element is malformed and a later signal is
also malformed, and it performs reads past the point the old implementation
stopped. There is no reason to incur that, so S8 is preserved rather than
dropped, and is promoted to a stated invariant (§2).

**The gap branch is deleted; the totality branch survives.** The gap throw
bought nothing even before the transport existed: on a ring buffer a fresh
`:oldest` never gaps — that is the whole P0 — so the branch could not have
caught the failure it appeared to guard. Keeping it now would be handling code
for a structurally excluded outcome, and would imply that index tolerates
evicting transports, which is the wiring defect the contract names. What
survives is the `case`'s own totality over a handle the function is *given*.

The honest limit, stated rather than papered over: **this branch cannot catch a
wrongly wired ring buffer.** A ring buffer answers `ok`/`blocked`/`end` from a
fresh `:oldest` just as the memory-log does. Nothing inside index can detect
the misassembly, by the contract's own design — there is no retention
predicate and there must not be one. The protection is the declaration of S9
and the composition that honours it. §8 says where that is written down; a
future reviewer tempted to add a check here should read `dao.stream.md`'s
*Explicitly Absent* first.

### D7 — index ends this sweep requiring no `dao.stream`

Verified against the tree. Every `ds/` occurrence in
`src/cljc/dao/space/index.cljc`:

| line | use | closed by |
|---|---|---|
| :347 | `ds/IDaoStreamReader` (PublishedIndexStream impl) | Phase 3 |
| :358 | `ds/IDaoStreamBound` (PublishedIndexStream impl) | Phase 3 |
| :383 | `ds/defopen :dao.space.index/published` | Phase 3 |
| :444 | the string "ds/next" in `snapshot-datoms`' docstring | Phase 2 |
| :453 | `(ds/next local-stream cursor)` | Phase 2 |

Five occurrences, one of them prose. There is no
`#?(:cljs (:require-macros [dao.stream]))` in `index.cljc`; its `ns` form ends
at :41.

**At the end of Phase 3, `dao.space.index` requires exactly** `dao.data.btree`,
`dao.data.btree.storage`, `dao.datom`, `dao.jing`, and `dao.stream`. Two
further removals must be confirmed, not assumed: `dao.jing.coordinate` is used
only by the deleted `defopen`, and `dao.stream.observe` is **never added**
(D5). The check is
`grep -n "jing-coordinate/\|ds/\|observe/" src/cljc/dao/space/index.cljc`
returning nothing.

### D8 — schema moves at its call sites only, and the two review defects there are fixed

`SchemaWrapper` implements only `ds/IDaoStreamBound`, and both methods delegate
to the transactor handle. When the transactor drops `closed?`, schema holds
the flag itself — it already has a per-wrapper `state` atom for exactly this
kind of coordination.

What Phase 2 forces in schema, and nothing more:

| schema site | forced edit |
|---|---|
| `:965` `ds/open! {:dao.stream/type :transactor …}` | → `tx/create!` |
| `:949` `close!` → `(ds/close! inner)` | set own `:closed`, call `tx/close!`, and **return `{:woke []}`** |
| `:954` `closed?` → `(ds/closed? inner)` | read own `:closed` |
| `:1112-1114` `(tx/transact! …)` then unconditional `(reset! lock next-state)` | **install `next-state` only on `:dao.stream/ok`** |
| `:1127` `schema/publish!`'s unlocked `ds/closed?` guard | serialize guard + `tx/publish!` under the wrapper lock |
| `:926`, `:969` `index/snapshot-datoms local-stream` | code unchanged; the handle is now a `memory-log` |
| `schema/transactor`'s `local-stream` argument | callers pass a `memory-log` handle |

Three are the review's, and each deserves its reasoning:

**Schema state must not advance on a failed append.** D3 makes `full`,
`closed`, `invalid-value` and `transport-error` returned data. `schema/
transact!` currently does `result (tx/transact! …)` then `(reset! lock
next-state)` unconditionally, so under D3 alone schema would install a state
describing a transaction that was never persisted — its uniqueness,
lookup-ref, current-value and schema-epoch state diverging from the log. The
rule: install `next-state` **only** when the outcome is `:dao.stream/ok`;
return every conforming non-ok outcome unchanged with state untouched; leave
state unchanged when the inner call throws, as today. Forced by D3, so inside
the constraint, not schema's own migration.

**`SchemaWrapper.close!` keeps returning `{:woke []}`.** It remains a v1
protocol method until schema's own plan; returning `tx/close!`'s
`{:dao.stream/outcome :dao.stream/ok}` would silently change a v1 public
result in a namespace this plan is not migrating.

**`schema/publish!`'s guard moves under the lock.** Today it checks
`ds/closed?` outside the wrapper lock and then calls `tx/publish!`: a
concurrent close can land between the two, which is exactly the stale-predicate
problem cited to justify removing `closed?`. Of the two remedies — serialize,
or redefine publication-after-close as permitted — take **serialize**.
Redefining is a semantic change to schema's documented ownership model and
belongs to schema's own plan; serializing preserves today's behaviour exactly
using a lock that already exists. The cost is real and worth stating: the
wrapper lock is now held across an index build. On a single-writer wrapper a
concurrent `transact!` would serialize against the publish's snapshot anyway,
so the throughput change is smaller than it looks.

### D9 — the local-log wiring requirement

The handoff, now true rather than aspirational:

> The host composition supplies `dao.space` a local handle created by
> `dao.stream.memory-log/create!`. Its declared complete retention makes
> fresh `:oldest` cursors true origin cursors for `derive-next-t` and
> `publish-index!`; supplying an evicting transport is a host-assembly defect.

This is a **declaration, not a check** (D2, D6). §8 states where it lives and
what each placement must say.

---

## 2. The invariants this plan is accountable to

### Transactor

| # | Invariant | |
|---|---|---|
| T1 | Every `append!`/`transact!` writes exactly ONE atomic transaction record `{:dao.space/transaction {:t n :datoms […]}}` through exactly one local append, so no reader observes a torn transaction | `[D]` |
| T2 | `t` comes from a per-wrapper watermark, derived on open as 0 for empty history else 1 + max datom `t` | `[D]` |
| T3 | A caller-supplied `:next-t` is rejected | `[D]` |
| T4a | A malformed retained history fails the open | `[D]` |
| T4b | A retention gap fails the open | **`[D✗]`** — the transport excludes `gap`. Replaced by T18; its fixture goes with it (§7). |
| T5 | The watermark advances **iff** the local append succeeded; a failed or thrown append leaves the same `t` retryable | `[D]` + `[T]` :499, :515 — strengthened by D3 from prose to a returned outcome |
| T6 | Single-writer: two wrappers over one local stream derive the same `t` and write colliding records; a documented hazard, not silently coordinated | `[D]` + `[T]` :655 |
| T7 | Close is per handle: it rejects further writes and neither closes nor erases the local stream or the intake pool | `[D]` + `[T]` :584 |
| T8 | Close linearizes after an in-flight append — no write crosses the point at which close returns | `[T]` :616 → `[T→D]` |
| T9 | Opening creates, registers, or closes nothing | `[T]` :273 → `[T→D]` |
| T10 | Entity maps require `:db/id`; an explicit datom `t` is rejected; `[e a v]`/`[e a v nil m]` pad to canonical d5 and are validated | `[D]` |
| T11 | `publish!` passes the local stream, pool and opts to `index/publish-index!` and returns its result; publication acknowledges enqueuing only | `[D]` |
| T12 | The wrapper delegates reads | **`[T✗]`** — pure delegation to a handle the caller holds; only tests call it |
| T13 | `close!` returns `{:woke []}` | **`[T✗]`** for the transactor — v2 has no waiter registration. `SchemaWrapper.close!` keeps it (D8). |
| T14 | `closed?` is a public predicate | **`[T✗]`** — v2 *Explicitly Absent*; replaced by the `:dao.stream/closed` outcome on the next write |
| T15 | An append receipt carries ok, the allocated `t`, and the datoms | `[T]` → preserved in v2 vocabulary |
| T16 | Intake pool members are validated with v2 `stream/writer?`; a non-empty collection is required | `[D]` |
| T17 | A non-ok local append throws | **`[T✗]`** — returned as data (D3) |
| T18 | The local stream is on a transport declaring complete retention; supplying an evicting one is a host assembly defect, and a surface check does not establish retention | **`[D]`, new** — declared, never checked (D9, §8) |
| T19 | Schema installs its next state only when the transactor answered ok | **`[D]`, new** |

### Index

| # | Invariant | |
|---|---|---|
| S1 | The read begins at the logical sequence's origin, because the transport declares complete retention | `[D]` — restated from r2's "reads from cursor zero" |
| S2 | An element is a canonical d5 vector or a transaction record | `[D]` |
| S3 | The transaction record's exact shape | `[T→D]` |
| S4 | `blocked`/`end` finish the read at the tail | `[D]` |
| S5 | A gap throws before any emission | **`[D✗]`** — the transport excludes `gap`, and per `conformance.cljc` a fixture inducing it would contradict the exclusion it is meant to support (§7) |
| S6 | A malformed element, or a malformed **result shape**, throws before any emission | `[D]`, widened by D6's `checked` |
| S7 | The two distinct element diagnostics | `[T]` |
| S8 | Each element is validated and flattened as it is read, so the first defect in stream order is the one reported and no read happens past it | **`[T]` → `[T→D]`, kept** — r3 dropped this as an accident; review P2-1 is right that it is observable behaviour, and it is now a deliberate, stated choice |
| S9 | The local stream's transport declares complete retention | **`[D]`, new** — the same declaration as T18, stated on index because `publish-index!` depends on it independently of the transactor |

**Published** — P1 the coordinate's exact shape `[D]`; P2 plain EDN, survives
a stream round-trip `[T→D]`; P3 opening fetches exactly one blob, zero nodes
faulted `[D]`; P4 rows deferred, EAVT order, equal to `read-datoms` `[D]`; P5
a store opened during a failed open is closed before the error propagates
`[D]` — **and has no covering test today**, fixed in Phase 3; P6 the v1
adapter's reader/bound/not-writer, `closed?`-from-construction and `{:woke []}`
close `[T]` — v1 protocol vocabulary that dies with the adapter; P7 two
independent cursors over one realization `[T✗]`; P8 `covered-indexes` is a
structural check, never an instance check `[D]`.

---

## 3. Phase 1 — expose the payload vocabulary

`src/cljc/dao/space/index.cljc`, its test, and one documentation line. **This
phase is behaviour-neutral, and r4 makes that true rather than claiming it**
(review P2-1).

1. Rename private `stream-payload-datoms` → private `element-datoms` (one
   element → its datoms). Body unchanged; S3, S6, S7 preserved verbatim
   including both error messages.
2. Add public `datoms-from-elements`: `(into [] (mapcat element-datoms)
   elements)` — the vocabulary's public, seq-level spelling.
3. **`snapshot-datoms` is not restructured.** It keeps calling
   `element-datoms` incrementally inside its existing v1 read loop, so failure
   ordering is untouched and S8 holds. Phase 2's v2 loop does the same (D6).
4. Document `datoms-from-elements` in `dao.space.index.md`'s *Public surface*
   **in this phase** — a public function and its documentation land together.
   Note there, so no later reader "cleans it up": it has no `src` caller by
   design. It is the vocabulary's public spelling; `snapshot-datoms` uses the
   per-element form to preserve S8, and both go through `element-datoms`, so
   testing one tests the vocabulary of the other.

**Proof.** Add `datoms-from-elements-is-the-local-stream-vocabulary` to
`index_test`, pinning S2, S3, S6, S7 directly rather than only through
`publish-index!`: a bare datom passes through; a transaction record flattens;
extra keys / non-integer `:t` / empty `:datoms` / a datom whose `t` disagrees
with the record all throw; a 5-vector with a non-namespaced `a` gets
"malformed local datom"; anything else gets "must be a datom or dao.space
transaction record". Every existing test stays as written and unchanged —
which is the phase's own neutrality check.

## 4. Phase 2 — the swap

One phase: the `defopen` and `DaoStreamLog` cannot be half-deleted, and every
consumer breaks at the same instant.

### 4.1 `src/cljc/dao/space/transactor.cljc`

Delete `DaoStreamLog`, `ds/defopen :transactor`, the `[dao.stream :as ds]`
require and the `#?(:cljs (:require-macros [dao.stream]))`.

- `create!` per D2: validate the spec (reject `:next-t`; require a
  `stream/reader?`-and-`stream/writer?` local stream; require a non-empty pool
  of `stream/writer?` members — the check at :199-204 is already v2 and moves
  verbatim), then `(derive-next-t (index/snapshot-datoms local-stream))` and
  return `{:dao.space/transactor true :local-stream … :intake-pool … :name …
  :next-t (atom t) :state (atom {:closed false})}`. A tag key, following
  `:dao.space.query/published`. **No retention check.**
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

### 4.2 `src/cljc/dao/space/index.cljc`

`snapshot-datoms` becomes the v2 read of D6 in full: `checked` on the mint,
`checked` on every read, incremental `element-datoms`, three interpreted
outcomes, one totality throw, no gap branch. It uses `stream/cursor`,
`stream/next` and `stream/validate-outcome` directly; **no `observe` require**
(D5, D7). `publish-index!`'s signature is unchanged; its `local-stream` is now
a `memory-log` handle. The `ds` require survives this phase for the published
adapter only, and dies in Phase 3.

### 4.3 `src/cljc/dao/space/schema.cljc`

The seven forced edits in D8's table, nothing else. Explicitly untouched:
`ds/defopen :dao.space.schema/current` and its opener, `ds/strict-vec` at
:260, `ds/realization?` at :259/:318, and `schema_test:304`'s v1 realization
fixture.

### 4.4 Tests — the closed inventory

The complete set of `transactor_test` constructs representing local handles,
all of which fail the new v2 surface validation if left on v1:

| construct | line | becomes |
|---|---|---|
| `ReaderOnlyStream` | :25 | v2 reader only — still a negative fixture for `create!`'s surface check |
| `WriterOnlyStream` | :33 | v2 writer only — same |
| `RecordingAppendStream` | :41 | v2 reader+writer wrapping a `memory-log`, recording appends |
| `FailingAppendStream` | :62 | v2 writer answering `{:dao.stream/outcome :dao.stream/full}` then delegating |
| `ThrowingAppendStream` | :86 | v2 writer that throws once, then delegates |
| concurrency `slow-local` reify | :401 | v2 reader+writer over a `memory-log`, `Thread/sleep` retained |
| close-linearization reify | :617 | v2 reader+writer over a `memory-log`, promises retained |

In `index_test`, `MalformedResultStream` (:25) migrates to a **v2 reader**, and
this is the one double whose migration is not mechanical: v2's
`IDaoStreamReader` has both `cursor` and `next`, so it must answer a
*conforming* `ok` cursor result and then return its configured malformed
result from every `next`. Both existing sub-cases at :393-408 survive — a map
without `:cursor`, and an unknown signal — and per review P1-1 a **third is
required**: a repeated `ok` carrying `:dao.stream/value` but no
`:dao.stream/cursor`, which must terminate by throwing rather than spin. That
test is the executable form of D6's `checked`.

Exhaustively, elsewhere: every `ds/append!`, `ds/close!`, `ds/closed?` and
`ds/next` whose target is the transactor value becomes the corresponding
`transactor/` function; `readers-observe-atomic-transaction-records` (:382)
and the read assertion at :609 read `local` rather than `log` (T12); `tx-ts`
and every `ds/->seq`/`ds/strict-vec` local-stream helper become one
`stream-values` loop, generalizing the existing `intake-values` (:126); all 22
`{:dao.stream/type :ringbuffer}` local opens become `memory-log/create!`;
`descriptor-validation` becomes `spec-validation` with the constructor's
wording; and the namespace docstring's `ds/append!` prose is rewritten.
`schema_test:658`'s local, `stigmergy_test:106`'s agent local, `index_test`'s
`open-local` (:68) and `query_test`'s `open-local` (:567) all become
`memory-log/create!`.

**The residue check is the closure criterion**, not the list:
`grep -c "ds/" test/dao/space/transactor_test.cljc` must return **0**
(currently 135), and the same for `query_test` (currently 2).

### 4.5 The ring-buffer site accounting

r2's capacity section is gone. The corrected accounting:

- **28** unbounded `ds/open! {:dao.stream/type :ringbuffer}` sites, not 29.
  `query_test:97` is a literal map passed to `q` to prove a raw map is rejected
  as a db input — not an open, and unchanged.
- `schema_test:304` stays v1: it builds a closed v1 realization for
  `schema/current`'s borrowed-input path, which is schema's own surface.
- The other **27 migrate**, including `index_test:777`'s descriptor carrier,
  which r2 wrongly listed as deleted. It is the one migrated site that becomes
  a **v2 ring buffer** rather than a memory-log — the point of the test is that
  a coordinate is ordinary data on an ordinary stream, and nothing about a
  carrier needs complete retention.
- `index_test:379`'s capacity-bearing gap fixture is **deleted** with S5 (§7).
- Every other migrated site is a `dao.space` local stream and becomes
  `memory-log/create!`. There is no capacity to choose: the transport declares
  no capacity key, and passing `:dao.stream.memory-log/capacity` is
  `invalid-spec` by construction.

### 4.6 Proof

The unchanged deftests are the proof: `reopen-derives-next-t-from-retained-
history` (:291) for T2, the three malformed sub-cases of
`malformed-or-gapped-retained-history-throws` (:336, :344, :352) for T4a,
`each-transaction-is-exactly-one-append` (:366) for T1,
`append-failure-does-not-advance-t-and-can-retry` (:499) and
`append-throw-does-not-advance-t-and-can-retry` (:515) for T5 — the first now
observing a returned outcome instead of a catch —
`close-is-per-handle-and-does-not-touch-local-stream` (:584) for T7,
`close-linearizes-after-an-in-flight-append` (:616) for T8,
`single-writer-wrappers-are-not-coordinated` (:655) for T6,
`publish-enqueues-indexes-into-the-pool` (:681) for T11.

Four new tests:

1. **T14's replacement** — `append!` and `transact!` after `close!` each answer
   `{:dao.stream/outcome :dao.stream/closed}` rather than throwing.
2. **S6's widening (review P1-1)** — the third `MalformedResultStream`
   sub-case in §4.4: a repeated `ok` without `:dao.stream/cursor` terminates by
   throwing. Without this, the regression it guards is a hung suite rather
   than a red test.
3. **T19, in `schema_test`** — a `memory-log`-backed writer double answering
   `full` once then succeeding. Assert: after the failed `schema/transact!`
   the wrapper's schema and uniqueness state are identical to before; the
   returned value is the conforming non-ok outcome; the retry commits at the
   **same** `t`. The only way to exercise D3's non-ok path, since the real
   transport excludes `full`.
4. **T8's lock extension** — `schema/publish!` while a close is in flight
   resolves one way or the other, never publishing after the close returned.
   JVM-only, alongside the existing linearization test.

### 4.7 Docs, in-phase

- `docs/design/dao.space.index.md` — replace the `ds/open! {:dao.stream/type
  :transactor …}` sample (:195-210) and the descriptor sentence (:191) with
  `memory-log/create!` + `transactor/create!`; restate *The snapshot* on the
  transport's declared retention (S1, S8, S9) and delete the
  gap-aborts-publication sentence (S5); carry §8's placement.
- `docs/design/dao.space.md` — *The Write Path* sample at :509, the descriptor
  mention at :476, §8's placement, and **:390's "The local stream is the
  durable record"**, which is false of a process-lifetime transport (D4). It
  becomes the authoritative retained record for the logical stream's lifetime;
  durability is `dao.jing`'s, through publication.
- `docs/design/dao.space.schema.md` — :383's "opens the inner `:transactor`"
  becomes a transactor *value*, and the wrapper owns its own closedness.
- `docs/dao.space.stigmergy.md` — step 1's "each agent owns a local
  `dao.stream`" becomes a `memory-log`, and step 2's `ds/append!`/`:transactor`
  becomes `transactor/create!` and `transactor/append!`/`transact!`. Check
  :38's "durable" claim against D4 while there.
- `docs/design/adr/0003-dao-space-is-the-event-medium.md` — an **amendment
  note**, not a supersession. The decision is untouched; two *Consequences*
  bullets go stale: `DaoStreamLog` no longer exists and the deposit target is
  `transactor/append!`, a plain function on a value; and the retention bullet's
  "`:dao.stream/full`, `:dao.stream/gap` so loss is never silent" needs §8's
  pointer — the local log excludes both, and its completeness comes from the
  declaration rather than from those outcomes.
- `docs/design/dao.stream.md` — drop `transactor` from the remaining-consumer
  list; `index` goes in Phase 3.
- **A new permanent `dao.space.transactor` section** (or its own doc, owner's
  call) carrying T1–T11, T15, T16, T18, T19, D3's outcome table, §8's
  placement, and D4's O(history) note as an Open item. It must **not**
  reintroduce the durability claim (review P2-2).

## 5. Phase 3 — index closes

### 5.1 What is built

One rewritten `defopen` body in `dao.space.schema`:

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
lazy restored trees or a retained store handle. The store closes inside the
opener, which also retires the comment at :1163-1166.

### 5.2 What is deleted

- `schema.cljc:1161` `ds/open! (index/published-index …)` and `:1167`
  `ds/strict-vec`.
- `index.cljc:344-363` `PublishedIndexStream`.
- `index.cljc:383-404` `ds/defopen :dao.space.index/published`.
- `index.cljc`'s `[dao.stream :as ds]` and `[dao.jing.coordinate]` requires.
- `index_test`'s `[dao.stream :as ds]` require, after §5.5.

### 5.3 What Phase 3 must not touch

The edit is one `defopen` body. Out of bounds: `ds/defopen
:dao.space.schema/current` (:357) and its whole opener; `SchemaWrapper`
(:943-955) beyond D8's edits; `ds/strict-vec` at :260 and `ds/realization?` at
:259/:318; `schema_test:304`'s v1 fixture; schema's closedness model beyond
D8's table.

### 5.4 The ten adapter call sites, per test

Ten call sites across eight deftests. **Seven deftests leave `index_test`; one
stays** (review P1-2). A deleted test whose property is not covered elsewhere
is a defect, so each is accounted for:

| # | `index_test` deftest (line, sites) | property | disposition |
|---|---|---|---|
| 1 | `open-published-rejects-unresolvable-and-malformed-descriptors` (:586; 600, 611) | an unsupported coordinate type fails closed at open; an extra key is rejected — P1 | **Moved.** Rewrite onto `query/open-published!` in `query_test`; nothing there covers coordinate rejection today. |
| 2 | `open-published-rejects-missing-and-invalid-manifests` (:624; 637, 647) | a missing manifest address throws; a stored non-manifest throws | **Deleted.** Covered by `read-manifest-guards-missing-and-invalid` (:823), which pins both throws on the functions both openers call. |
| 3 | `published-realization-is-read-only-with-a-stable-lifecycle` (:651; 656) | P6 and P7; EAVT order | **Deleted.** P6 is v1 protocol vocabulary that dies with the adapter; P7 is dropped. EAVT order survives in #7; idempotent close survives as `close-published-closes-once-and-is-idempotent` (`query_test`:715). |
| 4 | `published-empty-index-drains-to-end` (:681; 683) | an empty manifest reads as no datoms, not an error | **Deleted, with one addition.** `publish-index-of-empty-input-is-readable` (:351) covers it via `read-datoms`; add one `query_test` case that `open-published!` on an empty manifest yields an empty relation. |
| 5 | `published-open-fetches-only-the-manifest` (:690; 700) | exactly one content fetch at open, zero nodes faulted — P3 | **Moved.** `lazy-published-node-budget-is-strictly-bounded` (`query_test`:737) bounds *total* gets after a query at ≤ 4; it does not pin the open-time count. Add `open-published-fetches-only-the-manifest` asserting exactly 1 get after `open-published!` and before any read, reusing `counting-content-store`. |
| 6 | `covered-indexes-returns-the-four-covered-sets` (:708; 710, 732) | P8 | **Stays in `index_test`, split.** Its four structural cases (nil, `{}`, wrong key sets, a plain four-key map) need no opened value and no fixture, and remain verbatim — the deftest loses its `publish-into-file` fixture entirely. Only the "an opened realization carries the four sets" assertion moves, onto `query/open-published!`'s value in `query_test`; that move is itself P8's proof, since a plain map and an opened index must be indistinguishable to `covered-indexes`. |
| 7 | `published-next-yields-the-same-eavt-rows-as-the-eager-walk` (:736; 741) | P4 | **Moved.** `open-published!`'s forced `:rows` delay versus `index/read-datoms`. |
| 8 | `published-index-is-a-transportable-bounded-stream` (:759; 783, carrier :777) | P2 | **Moved and modernized.** Keep the `pr-str`/`read-string` round-trip and the carrier, now a v2 ring buffer (§4.5), and open the transported coordinate with `query/open-published!`. Assert the **complete exact coordinate map**, not only `:manifest-address`; `ds/exact-bound?` (:779) is v1 and is replaced by that equality. |

**Plus one test the adapter never had:** P5 — "a store opened during a failed
open is closed before the error propagates" — has no covering test anywhere,
and the survivors above prove validation errors, not ownership cleanup. Add a
`query/open-published!` test with a close-counting store whose manifest read
fails after `jing-coordinate/open!` succeeds; assert the original error
propagates and the store is closed **exactly once**.

### 5.5 `index_test`'s residue

After Phase 2 migrates `open-local` (:68), `stream-values` (:122),
`MalformedResultStream` (:25) and the carrier (:777), and Phase 3 removes the
**seven** adapter deftests, strips the fixture and open from the eighth
(`covered-indexes`, §5.4 row 6), and deletes the gap fixture (:375-392), the
residue is zero. `grep -c "ds/" test/dao/space/index_test.cljc` must return
**0** (currently 41); a non-zero count means a property was migrated by hand
instead of by rule.

### 5.6 Proof

`schema_test` W38–W41 (:1449, :1481, :1522) must pass **unmodified** — they
exercise `schema/published` through `ds/open!`/`schema/current`, exactly the
surface Phase 3 preserves while replacing its implementation. If any needs
editing, the opener's behaviour changed and the phase is wrong.

### 5.7 Docs, in-phase

- `docs/design/dao.space.index.md` :230-235 — the "published-index DaoStream
  adapter opens lazily" bullet describes a deleted adapter. Rewrite on
  `query/open-published!` and `read-datoms`; remove the adapter from *What the
  library owns* and from the public surface list.
- `docs/design/dao.space.schema.md` :549-554 — the paragraph explaining that
  `:dao.space.schema/published` "delegates to the `published-index`
  realization … since `open!` dispatches on `:dao.stream/type` and the index
  opener demands exact descriptor equality" describes a mechanism that no
  longer exists. Rewrite on `index/read-datoms`.
- `docs/design/dao.stream.md` — drop `index` from the remaining-consumer list.

## 6. End condition

- **`dao.space.transactor` requires no `dao.stream`**: `dao.datom`,
  `dao.space.index`, `dao.stream`.
- **`dao.space.index` requires no `dao.stream`**: `dao.data.btree`,
  `dao.data.btree.storage`, `dao.datom`, `dao.jing`, `dao.stream`.
- **`docs/design/dao.stream.md`'s consumer list drops both**, to "`dao.space`
  (`schema` only — `query`, `index` and `transactor` migrated, and the
  `dao.stream.relation` transport was eliminated with `query`)".
- **`dao.stream.memory-log` gains its first production consumer.**
- **`dao.space.schema` is the only `dao.space` namespace left on v1.** Its v1
  surface, measured — an earlier "`ds/close!` ×4" included the prose mention at
  :1164, so it is 3 call sites:

| API | today | after Phases 2+3 | remaining |
|---|---|---|---|
| `ds/open!` | 4 | 2 | :351, :363 |
| `ds/closed?` | 4 | 3 | :323, :1069, :1127 |
| `ds/close!` | 3 (+1 prose) | 2 | :367, :373 |
| `ds/strict-vec` | 2 | 1 | :260 |
| `ds/realization?` | 2 | 2 | :259, :318 |
| `ds/defopen` | 2 | 2 | :357, :1154 (body replaced, form kept) |
| `satisfies? ds/IDaoStream*` | 2 | 2 | :319, :364 |
| `SchemaWrapper` protocol impl | 1 | 1 | :945 |
| **total** | **20** | **15** | |

  Plus one addition: Phase 3's `PublishedSchemaRows`. Worth naming rather than
  hiding — Phase 3 **relocates** index's share of v1 into the one namespace
  that still owes a plan, where schema's migration deletes the whole opener and
  the record with it. A remainder belongs with its owner.
- Nothing is owed to a later plan except schema's own migration.

## 7. What is deleted with S5 and T4b

Stated separately because deleting a test needs the same standard as keeping
one, and here the standard is a contract commit rather than a survivor.

`publish-index-gap-local-stream-throws-before-emission` (`index_test`:375-392)
and the gap sub-case of `malformed-or-gapped-retained-history-throws`
(`transactor_test`:323-335) both build a capacity-bearing ring buffer, overflow
it, and assert a throw. Under D9 that fixture *is* the host-assembly defect the
contract now names, and `conformance.cljc`'s exclusion principle is explicit:
"Fixtures must never be required for excluded outcomes: a fixture inducing one
would contradict the exclusion it is meant to support." The exclusion is
discharged by the structural argument in `memory-log`'s docstring — one dense
vector from position 0, no expression that removes an element — not by a test
in `dao.space`. No replacement consumer-level gap fixture is owed.

The malformed sub-cases of the same `transactor_test` deftest **stay**: any
value can be appended to a memory-log, so a malformed payload or transaction
record in the retained history remains reachable, and T4a and S6 stand.

## 8. The residual risk, and the four places it is written down

D6 states the limit: index cannot detect a wrongly wired evicting transport,
because a fresh `:oldest` reads clean on a ring buffer and the contract
deliberately has no retention predicate. The only defence is that the
requirement is written where someone wiring a composition will read it.

Four placements. **Each must name the concrete required transport and state
plainly that a reader/writer surface check does not establish retention** —
gesturing at "completeness" is not enough:

| Placement | What it must say |
|---|---|
| `docs/design/dao.space.index.md`, *The snapshot* / *The agent-transactor loop* | `publish-index!` requires a local stream created by `dao.stream.memory-log/create!`; its declared complete retention is why a fresh `:oldest` is the origin; `stream/reader?`/`stream/writer?` check surfaces, not retention |
| `docs/design/dao.space.md`, *The Write Path* | the same, at the point the write path is assembled, alongside the corrected :390 sentence (D4) |
| the new permanent `dao.space.transactor` section (§4.7) | T18 in full: the required transport by name, that `create!` does not and must not check it, and that supplying an evicting transport is a host-assembly defect that no runtime check will catch |
| ADR 0003's amendment (§4.7) | the pointer from its retention bullet to `dao.stream.md`'s *Complete history*, naming `memory-log` as what `dao.space` wires and noting that the local log excludes both `full` and `gap` |

## 9. Constraints

- Three lanes green at the end of every phase.
- `public/demo.html` verified by compiling `:demo`.
- `dao.space.schema`'s own migration stays its own plan; this plan touches
  schema only where D8's table and §5.1 say.

## 10. Verification

```
bb test:clj
bb test:cljs      # confirm "Testing dao.space.transactor-test", "…index-test",
                  # "…schema-test" appear in the node output
bb test:cljd
clj -M:cljs -m shadow.cljs.devtools.cli compile demo
```

- **Demo.** `datomworld.demo` requires only `datomworld.demo.*`, `reagent` and
  `yin.vm.telemetry-viewer` — it reaches no `dao.space` namespace — so a break
  would be a compile regression, not a behavioural one. Compile it each phase.
- **Phase 2 is the likeliest red**, and the failure shape is a receipt- or
  surface-shape mismatch in the migrated sites rather than anything about
  retention — the transport removed that class of failure instead of managing
  it.
- **Run Phase 2 with a per-test timeout, or watch for a hang rather than a
  failure.** The one regression in this plan that does not present as a red
  test is D6's: a `snapshot-datoms` that interprets before validating spins on
  a malformed repeated `ok`. `§4.6`'s new test is what converts it into a
  throw, and it should be written *before* the loop it guards.
- **`close-linearizes-after-an-in-flight-append` (:616) is the T8 canary**,
  JVM-only. If it goes red against the v2 reify, the write lock stopped
  covering the state transition.
- **`schema_test` W38–W41 must pass unmodified through Phase 3** (§5.6).
- **Four zero-residue greps**, the phases' closure criteria rather than
  commentary: `ds/` in `transactor_test` (135 → 0), `query_test` (2 → 0),
  `index_test` (41 → 0), and
  `jing-coordinate/\|ds/\|observe/` in `index.cljc` (5 → 0).
- **End-to-end**, `stigmergy_test.clj` covers the sweep: agents with
  `memory-log` local streams, transaction records, publish, observer
  materialization, and query over published manifests through file and remote
  coordinates. It proves T1, T2, T11 and S1–S4 held; it does **not** prove P5,
  which is why §5.4 adds that test explicitly.

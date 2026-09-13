# dao.space as a `run-on-stream` Observer

Status: design note, 2026-09-13. Proposes `dao.space.observer`, a
`dao.stream.v2.observer/run-on-stream`-driven indexer over *any* `dao.stream.v2`
medium, so that dao.space can observe the same stream a `yin.vm` evaluator
observes — the VM reading it as control, dao.space reading it as facts —
with neither knowing about the other. Subordinate to
[`dao.space.md`](./dao.space.md) (write path, three boundaries),
[`dao.stream.md`](./dao.stream.md) (§Composition), and the observer
coordination already specified by `dao.stream.v2.observer`. It
composes with [`yin.vm.macro.md`](./yin.vm.macro.md) §4.2, whose "commit
`program-in` first for durable provenance" becomes an instance of this
note.

The framing, stated once:

> `yin.vm(s)` and `dao.space` are peer observers of `dao.stream`. Given the
> same batch, a VM constructs CESK state and dao.space constructs the
> covered index. The stream is the only coupling.

---

## 0. Decisions

1. **dao.space observes; it does not have to be the writer.** Today
   indexing is "the writer's duty" over the writer's own local stream
   (`dao.space.md` §The Write Path). That stays true *as a composition*, but
   the mechanism becomes an observer over a medium — any medium, including
   one a `yin.vm` composition writes for its evaluators.
2. **One coordination loop.** The indexer is driven by
   `dao.stream.v2.observer/run-on-stream` with its state in the `:consumer`
   slot, exactly as an evaluator or the macro expander is. No second
   observer machinery.
3. **The index is incremental and persistent.** Each observed batch is
   folded into the four covered indexes as `dao.data.btree` values; the
   trees are structurally shared across batches. `publish-index!`'s
   full-rebuild-from-origin becomes a consequence of *starting* an observer
   at `:oldest`, not the only way to build an index.
4. **The observer never rewrites a datom and never allocates `t`.**
   Transaction time is the writer's (the transactor's). The observer records
   what it saw and *where* (its own batch ordinal) as facts *about* the
   batch, in its own metadata namespace, never by editing rows.
5. **Identity resolution is the observer's reading.** Tempids are
   batch-local; the observer assigns durable ids per batch and records the
   mapping as **resolution facts**. This is the one place cross-medium
   identity can exist, because dao.space is the one observer that may read
   every medium.
6. **Symmetric ignorance is an invariant.** A `yin.vm` never consults the
   index to run; the indexer never evaluates a form to index it. The
   "CESK-in-the-index" premise of `yin.vm-in-dao.space.md` is *not* adopted
   here: this note keeps two interpreters over one stream, not one
   interpreter with two faces.

---

## 1. Today, and the gap

All of `dao.space` is on `dao.stream.v2` (no v1 references remain). Its
stream use has three shapes:

| Piece | Shape today |
|---|---|
| `dao.space.transactor` | write-side interpreter over an agent's *own* local memory-log; wraps each transaction in one `{:dao.space/transaction {:t :datoms}}` record; derives `t` by scanning the whole retained history at `create!` |
| `dao.space.index/publish-index!` | `snapshot-datoms` the local stream **in full from `:oldest`**, build four btrees from scratch, append node blobs then manifest to an intake stream |
| `dao.space.query/snapshot` | the read side's one v2 interpreter: mint `:oldest`, loop `observe/step` into a relation, stop at `blocked`/`ended`/`gap`/`defect`; a **one-shot** |

Three consequences follow, and they are the gap:

- **Nothing observes a medium dao.space did not write.** A `yin.vm`
  composition's `program-out` (or the macro expander's log) carries `:yin/*`
  datoms nobody indexes; querying them means a full `snapshot` each time.
- **No index is incremental.** Every publication rebuilds from the origin;
  every `create!` replays history for the watermark. The transactor's own
  *Open items* names this as O(history) and asks for "one truth — the log
  carries its own checkpoint". An observer that holds a persistent index
  *is* that checkpoint's natural owner.
- **`snapshot` is not a session.** It mints a fresh cursor per call and
  cannot be resumed batch by batch, so it cannot participate in a
  `run-on-stream` round the way an evaluator does.

`datoms-from-elements` already accepts both element shapes (raw d5 rows and
transaction records), `index-datoms` already builds the in-memory index,
and `observe/step` already exists. The pieces are present; only the
composition is missing.

---

## 2. The observer (`dao.space.observer`)

### 2.1 Session shape

```
indexer = {:indexes    {:eavt bt :aevt bt :avet bt :vaet bt}   ; dao.data.btree values, structurally shared across batches
           :ids        {:next-eid n}                           ; durable-id allocator (decision 5)
           :batch      n                                       ; ordinal of the next batch, observer-local
           :schema     {attr {:db/valueType :db.type/ref ...}} ; supplied; which attrs are refs
           :publish    nil | {:intake writer :staged nil|payloads}   ; optional, §4
           :defects    []}                                     ; malformed batches seen since last drain

ready?       (fn [x] (nil? (get-in x [:publish :staged])))
load         (fn [x batch] (fold-batch x batch))              ; pure; §2.2
run          (fn [x] (if (publish-due? x) (flush-publish x) x))   ; §4
drain        (fn [x] [(assoc x :defects []) (:defects x)])
```

Driven as `(run-on-stream {:observer o :consumer indexer} ready? load
run)` over any `dao.stream.v2` reader handle attached through
`dao.stream.v2.observer/attach`. The coordination inspects no indexer field; it
drives an indexer as readily as a VM — which is the point of decision 2.

### 2.2 `fold-batch`

One batch, one pure fold:

1. **Interpret elements.** `index/datoms-from-elements`: each element is a
   canonical d5 row or a `{:dao.space/transaction {:t :datoms}}` record,
   flattened. Anything else is a **defect** — recorded in `:defects` with
   the batch ordinal and the element's position, and the batch is skipped
   *without* stopping the session. (A throwing `load` would leave
   the cursor on the bad batch forever — the same head-of-line hazard
   `yin.vm.macro.md` decision 11 removes. Bad input is data; a bug in the
   fold itself is the throw.)
2. **Resolve identity** (§3): negative `e` and negative ref-valued `v` (per
   `:schema`) are mapped through a batch-local tempid table to durable
   positive ids from `:ids`; positive ids pass through. The mapping is
   emitted as resolution facts.
3. **Fold.** Resolved rows and resolution facts are `conj`ed into the four
   trees under their comparators (`index/eavt-cmp` etc.). Covered indexes
   are sets; a duplicate row is a no-op.
4. **Advance** `:batch` and `:ids`.

The indexer's `:indexes` after batch *n* is a pure function of (indexer
before, batch), on every host.

### 2.3 What the two views mean over each medium

`dao.space.query/current` and `history` are unchanged: they are
interpreters over rows, and the indexer's trees are a row source.

| Medium | `t` in rows | `current` | `history` |
|---|---|---|---|
| transactor-written (records) | allocated by the writer, monotonic | greatest-`t`-wins per `[e a v]`, retractions removed — full Datomic semantics | exact rows in `t` order |
| raw `yin.vm` program medium (yang emits `t 0`, `m default-op`) | not meaningful | the *set* of facts asserted — a correct reading, since a program batch is assertions only | degenerate: all rows share `t 0`; order is the observer's batch ordinal, available through resolution facts (§3), **not** through `t` |

The observer does not paper over the second row by minting `t` — decision
4. A composition that wants transaction-time semantics over program datoms
routes them through a transactor (writer allocates `t`); one that wants
"what facts does this program state" reads `current` and gets exactly that.

---

## 3. Identity

### 3.1 Why the observer must resolve

A `yin.vm` batch's entity ids are negative tempids allocated from
`(- datom/first-user-id)` downward *per batch*. Two consecutive batches both
contain `-16`. Folding them raw into one index would merge unrelated
entities — the silent-wrong-data case. `dao.space.transact` resolves tempids
within one transaction; the observer must do the same per batch, and only
the observer can, because only it holds the cross-batch allocator.

### 3.2 Resolution facts

For every tempid `τ` the observer maps to durable `δ` in batch *n*, it
asserts, in its own namespace and with `m` = its own operation entity:

```
[δ :dao.space.observer/batch   n   t_obs m_obs]
[δ :dao.space.observer/tempid  τ   t_obs m_obs]
```

`t_obs` is the observer's batch ordinal (its own logical clock, never a
host clock; it is *not* the row's `t`). These are facts *about* observation,
distinct from the observed rows, distinguishable by attribute namespace and
by `m`. They are what make the following queryable:

- **Which batch asserted this entity** — `[?e :dao.space.observer/batch ?n]`.
- **Cross-medium provenance**, the case `yin.vm.macro.md` §4.2 leaves to
  "a composition that commits". With one indexer session per medium
  (`program-in`, `program-out`, log) sharing an allocator, or one session
  over a merged view, the macro expander's event
  `[ev :yin/source-batch t] [ev :yin/source-call -16]` joins to the source
  entity through `[?e :dao.space.observer/batch t'] [?e
  :dao.space.observer/tempid -16]` — where `t'` is the observer's ordinal
  for the source batch the expander numbered `t`. The correspondence
  between the expander's `:t` and the observer's ordinal is one more
  resolution fact the composition asserts when it wires both observers to
  the same medium (they count the same batches in the same order, so it is
  a constant offset, recorded once, not a per-batch join).

Refs are resolved only for attributes the supplied `:schema` declares as
`:db.type/ref` — `yin.vm.v2/schema` for program media, plus
`yin.vm.v2.macro/event-schema` for a macro log — exactly as the transactor
relocates only declared refs today. An undeclared attribute holding a
negative number is a value, not a ref, and is left alone.

### 3.3 What is not promised

Durable ids are the *observer's* reading. Two independent observers over the
same medium allocate independently; their ids agree only if they are the
same session or share a checkpoint (§4.2). This is the same statement as
`yin.vm.macro.md` §4.2's "refs across media are meaningful only for durable
ids", now with the owner of "durable" named: it is whichever dao.space
observer indexed the medium.

---

## 4. Publication and the checkpoint

### 4.1 Incremental publication

Because `:indexes` are persistent `dao.data.btree` values, publishing after
batch *n* is `bt/store-tree` over each tree into a recording content handle
plus one manifest — the same node-blob format and intake path
`publish-index!` uses today — but nodes unchanged since the last publication
are the same content-addressed blobs and deduplicate at `jing/materialize!`.
Publication cost becomes proportional to what changed, not to history.

The staging discipline is `yin.vm.macro.md` §5's: node blobs then manifest
are staged as one payload list; `full` retains the exact list and leaves the
indexer not-ready; `ok` on the manifest clears it. The manifest is always
last, so a partial prefix is retry-safe, as today.

### 4.2 The checkpoint the transactor asked for

`dao.space.transactor.md` *Open items*: "the log carries its own checkpoint,
from which the watermark and incremental indexes resume". An indexer
session is that checkpoint's shape: `{:manifest-address a :cursor c :ids i
:batch n}` — the published trees, the medium position they cover, and the
allocator state. A restarted observer opens the manifest (lazy, via
`query/open-published!`), re-attaches at `c`, and continues folding. Nothing
is replayed from the origin. The watermark the transactor derives by
scanning is `(max t)` over the checkpointed index's rows — a query, not a
scan.

Whether the checkpoint record lives on the observed medium itself (the
transactor doc's preference: "one truth") or beside the manifest in
`dao.jing` is left open here; either is a composition choice, and the
observer's state is the same value in both.

---

## 5. Relationship to the transactor and to `publish-index!`

The transactor remains the writer and the owner of `t`. "Indexing is the
writer's duty" becomes: *the writer runs an observer over its own stream* —
one session attached to its local memory-log, publishing on its policy.
`publish-index!` is then a convenience: attach at `:oldest`, fold to
`blocked`, publish once, discard the session. Its full-history behaviour is
preserved as the degenerate case of an observer that never kept state.

Nothing about `dao.jing` changes: it still materializes opaque payloads from
intake pools and never interprets. Nothing about `dao.space.query` changes:
the indexer's trees are one more row source, alongside relation values and
published manifests. The three boundaries of `dao.space.md` are untouched;
what moves is *when* the transactor-side index is built (continuously,
not at publish) and *over what* (any medium, not only the writer's own).

---

## 6. Invariants

| Invariant | How the observer honours it |
|---|---|
| No hidden global state | Allocator, batch ordinal, trees, staged publication — all fields of one session value threaded through `run-on-stream`. No registry of observers; a composition holds the sessions it created. |
| No implicit control flow | Indexing happens in exactly one place: `fold-batch` inside the observe step. Nothing indexes on write, on query, or on publish. |
| No callbacks | `fold-batch` is a function batch → indexer'. The driver is `run-on-stream`; cadence is the composition's. |
| No shared mutable state | `dao.data.btree` values are persistent; two sessions never share a tree by reference they could both mutate. The recording content handle at publish time is created per publication. |
| No layer collapsing | *append* (writer) → medium → *observe/fold* (this note) → *publish* (intake) → *materialize* (`dao.jing`) → *query*. The VM is a sibling observer, not a stage. |
| No assumed graphs | Ref resolution uses only the supplied `:schema`; the observer does not infer which values are refs. Batch shape is validated by `datoms-from-elements`; malformed elements are defects, not guesses. |

---

## 7. Phases

**Phase 0 — relocate the coordination loop (done 2026-09-13).** The
`attach`/`observe-next`/`run-on-stream` loop lived at
`yin.vm.v2.stream-observer` because the VM was its first consumer, but its
own docstring says it inspects no evaluator field, and a `dao.space`
namespace requiring `yin.vm.*` would invert the layering (`dao.stream` →
`dao.space` → `yin.vm`, never back). It is now `dao.stream.v2.observer`
(`src/cljc/dao/stream/v2/observer.cljc`, test
`test/dao/stream/v2/observer_test.cljc`), beside `dao.stream.v2.observe`
whose single `step` it loops over. The `yin.vm.v2` evaluators, the REPL, and
the macro expander require it from there; `yin.vm.v2` keeps no observer code
of its own. The pending fix from `yin.vm.macro.md` §5 — keep the throw,
carry the partial `{:observer :consumer}` session in `ex-data` — lands in this
namespace.

**Phase 0′ — parity over the transactor's medium.** `dao.space.observer`
with `fold-batch`, resolution facts, `drain`; a session attached at
`:oldest` over a transactor's local memory-log produces `:indexes` equal
(as sets) to `index-datoms` over `snapshot-datoms` of the same log, and a
one-shot publication equal (as a manifest) to `publish-index!`. Tests: set
equality on all four trees; manifest equality; a malformed element becomes
a defect and the next batch still folds; `run-on-stream` `blocked`/`end`
behaviour; ids stable across a `pr-str`/`read-string` round trip of the
session (it is plain data plus btree values).

**Phase 1 — a raw `yin.vm` program medium.** Two observers on one
`program-out`: `dao.stream.v2.observer` + `ast-walker` running the
program, `dao.space.observer` indexing it. Tests: the VM's value and the
index's `current` view from the same batches; two batches with overlapping
tempids index as distinct entities with correct resolution facts;
`[?e :yin/type :lambda]` finds every lambda the program contained; a ref
attribute resolved, an undeclared negative value untouched; `history`
documented as degenerate over `t 0` rows.

**Phase 2 — cross-medium provenance.** Observers over `program-in`,
`program-out`, and the macro log with a shared allocator; the offset fact
between the expander's `:t` and the observer's ordinal. Test: the
`yin.vm.macro.md` §4.1 query chain source-call → event → expansion-root
answered by `dao.space.query/q` over the three indexed media.

**Phase 3 — incremental publication and checkpoint.** Publish after *n*
batches, then after *n+k*; second publication appends only new node blobs.
Restart from `{:manifest-address :cursor :ids :batch}` and continue; the
resumed index equals a from-origin index. The transactor's `create!`
derives its watermark from a checkpoint when one is offered.

---

## 8. Open questions

- **One session per medium, or one over a merged view?** Per medium keeps
  each cursor honest and makes `gap` attributable; a merged view needs a
  merge interpreter that `dao.stream.md` does not (and should not) provide.
  This note leans per-medium with a shared allocator, but the allocator
  then couples the sessions — a composition choice to state when it is
  made.
- **Retention and `gap`.** Over an evicting transport the observer inherits
  `dao.stream.v2.observer`'s gap accounting; an index with a gap is a partial
  index and must say so. Whether a published manifest should carry the gap
  count is undecided.
- **Where the checkpoint lives** (§4.2).
- **Whether `snapshot` is retired** in favour of "run a throwaway session to
  `blocked` and take its trees". Probably, but `snapshot`'s status
  vocabulary (`:ended`/`:blocked`/`:gap`/`:defect`) should survive as the
  session's terminal report.

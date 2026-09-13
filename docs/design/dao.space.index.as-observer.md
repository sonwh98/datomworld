# dao.space.index as a dao.stream Observer

Status: design note, 2026-09-13. `dao.space.index` is already the dao.stream
observer on the dao.space side: `snapshot-datoms` + `publish-index!` is one
observer run — attach at `:oldest`, fold to `blocked`, publish, keep nothing.
This note makes it a *stateful* observer, driven by
`dao.stream.v2.observer/run-on-stream` over *any* `dao.stream.v2` medium. No
new namespace: everything here is index realization, which `dao.space.index`
already owns. The library specified here is payload-agnostic: it sees d5 rows
and a supplied ref schema, and nothing below knows or cares what the rows
mean or who else reads the medium. Subordinate to
[`dao.space.md`](./dao.space.md) (write path, three boundaries),
[`dao.stream.md`](./dao.stream.md) (§Composition), and the observer
coordination already specified by `dao.stream.v2.observer`. It
composes with [`yin.vm.macro.md`](./yin.vm.macro.md) §4.2, whose "commit
`program-in` first for durable provenance" becomes an instance of this
note.

**Why this note exists** — a composition it enables, not a fact the index
knows: attach a `yin.vm` evaluator and `dao.space.index` to the same medium,
and the VM constructs CESK state while the index materializes covered
indexes over the same batches, so `dao.space.query/q` answers Datalog over
the program being executed, as it grows. Neither observer is aware of the
other; the stream is the only coupling, and the index's ignorance of what it
indexes is as load-bearing as the VM's ignorance of being indexed. That
invariant belongs to the composition and is stated where compositions are
(`datom.world.md`, `dao.space.md` §Three Boundaries); this document
specifies only the index side.

---

## 0. Decisions

1. **dao.space observes; it does not have to be the writer.** Today
   indexing is "the writer's duty" over the writer's own local stream
   (`dao.space.md` §The Write Path). That stays true *as a composition*, but
   the mechanism becomes an observer over a medium — any medium, whoever
   writes it and whoever else reads it.
2. **One coordination loop, one library.** `dao.space.index` is driven by
   `dao.stream.v2.observer/run-on-stream` with its state in the `:consumer`
   slot, exactly as an evaluator or the macro expander is. No second
   observer machinery, and no new namespace: "the dao.space observer" is a
   role `dao.space.index` plays when so driven, not a module.
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
6. **The index is payload-agnostic.** It interprets d5 shape and the
   supplied ref schema, nothing else: it never evaluates, never inspects an
   attribute's meaning, and never learns what another observer of the same
   medium does with the rows. (This is what rules out the
   "CESK-in-the-index" premise of `yin.vm-in-dao.space.md`: two interpreters
   over one stream, not one interpreter with two faces.)

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

- **Nothing indexes a medium dao.space did not write.** Any other writer's
  medium — a program stream, an expander's log — carries datoms nobody
  indexes; querying them means a full `snapshot` each time, which is
  Datalog *as of* a call, never over a medium as it grows.
- **No index is incremental.** Every publication rebuilds from the origin;
  every `create!` replays history for the watermark. The transactor's own
  *Open items* names this as O(history) and asks for "one truth — the log
  carries its own checkpoint". An observer that holds a persistent index
  *is* that checkpoint's natural owner.
- **`snapshot` is not a session.** It mints a fresh cursor per call and
  cannot be resumed batch by batch, so it cannot participate in a
  `run-on-stream` round the way an evaluator does.

**Already true.** `dao.space.index` is the consumer side of observation.
`snapshot-datoms` + `publish-index!` *is* an observer run: attach at
`:oldest`, fold everything to `blocked`, publish once, discard the state —
the degenerate case of `run-on-stream` with a consumer that is always ready
and keeps nothing between calls. `datoms-from-elements` already accepts both
element shapes (raw d5 rows and transaction records), `index-datoms` already
builds the in-memory index, and `observe/step` already exists.

**Not yet true**, and it is code, not wording:

1. *Incremental fold.* `index-datoms` builds a whole index from a whole datom
   vector; `publish-index!` rebuilds from the origin every time. Being
   driven batch by batch needs `fold-batch : index-state batch →
   index-state'` over persistent btree values (§2.2).
2. *Tempid resolution across batches.* A transactor-written medium carries
   resolved ids; a medium written batch by batch without a transactor
   carries per-batch negative tempids that *repeat*. `dao.space.index` never
   meets that case today because it
   only indexes the writer's own resolved log. Observing a medium it did not
   write requires the per-batch allocator and resolution facts (§3) — a
   correctness requirement, not a framing one.
3. *Bad input as data.* `snapshot-datoms` throws on the first malformed
   element, which is right for a one-shot over your own log and
   head-of-line-blocks a session over someone else's medium (§2.2 step 1).

---

## 2. The index consumer (`dao.space.index`)

### 2.1 The index state

```
index-state = {:indexes  {:eavt bt :aevt bt :avet bt :vaet bt}   ; dao.data.btree values, structurally shared across batches
               :storage  <IStorage over a content handle>       ; the trees' storage; persists across publishes (§4.1)
               :mode     :resolved | :unresolved                 ; fixed at construction (§3.1)
               :ids      nil | {:next-eid n}                     ; allocator; present only in :unresolved mode
               :batch    n                                       ; ordinal of the next batch, observer-local
               :schema   {attr {:db/valueType :db.type/ref ...}} ; supplied; which attrs are refs
               :publish  nil | {:intake writer :staged nil|payloads}   ; optional, §4
               :defects  []}                                     ; malformed batches seen since last drain

;; all in dao.space.index, beside index-datoms and publish-index!
ready?         (fn [x] (nil? (get-in x [:publish :staged])))
load           index/fold-batch                                ; pure; §2.2
run            index/flush-staged                              ; retries a staged publication; identity otherwise (§4)
index/publish! index-state → index-state'                      ; explicit: stage this state's trees, then flush (§4)
index/drain    (fn [x] [(assoc x :defects []) (:defects x)])
```

Driven as `(run-on-stream {:observer o :consumer index-state} ready?
index/fold-batch index/flush-staged)` over any `dao.stream.v2` reader
handle attached through `dao.stream.v2.observer/attach`. The coordination
inspects no field of the index state; it drives it as readily as a VM —
which is the point of decision 2. **Publication is an explicit composition
step**, not a policy inside the loop: the composition calls `publish!`
between rounds (after every *n* rounds, on `blocked`, on a timer — its
choice), and only the *retry* of a staged publication that answered `full`
lives inside `run`, so that `ready?` stays false until the intake accepted
it. Calling `publish!` while a publication is still staged is a caller
error (the composition checks `ready?` first); the staged payload list is
never replaced, so at-most-once per manifest holds.

### 2.2 `fold-batch`

One batch, one pure fold:

1. **Admit elements.** Each element is a d5 row or a
   `{:dao.space/transaction {:t :datoms}}` record, flattened. Admission is
   *looser than* `datom/local-datom?`, which `element-datoms` applies today
   and which requires a non-negative `e`: in `:unresolved` mode admission is
   exactly `local-datom?` minus that one clause — integer `e` of either
   sign, namespaced keyword `a`, `t ≥ 0`, integer `m`. `v` is unconstrained
   by `local-datom?` already, so a negative ref-valued `v` needs no schema
   knowledge to be *admitted*; the schema first matters at resolution (step
   2), where it decides which negative `v`s are tempids and which are plain
   negative values. In `:resolved` mode admission *is* `local-datom?`.
   Strictness is restored after step 2: every row that reaches step 3
   satisfies `local-datom?`. A transaction-record element is admitted by
   the same per-datom rule applied to each of its datoms plus the record's
   own shape checks (one shared `t`, non-empty); on an `:unresolved` medium
   a record's positive-`e` datoms therefore defect the batch through the
   mode rule of step 2, which is the intended reading — records are a
   transactor's shape, and a transactor resolves. Anything not admitted is a
   **defect** — recorded in `:defects` with the batch ordinal and the
   element's position — and the whole batch is skipped *without* stopping
   the session. (A throwing `load` would leave the cursor on the bad batch
   forever — the same head-of-line hazard `yin.vm.macro.md` decision 11
   removes. Bad input is data; a bug in the fold itself is the throw.)
   `datoms-from-elements` keeps its strict, throwing contract for the
   one-shot path; the fold uses an admitting, non-throwing variant of the
   same per-element rule.
2. **Resolve identity** (§3). `:unresolved` mode: every tempid in the batch
   — whether it appears as an `e` or only as a declared-ref `v` (a reference
   to an entity the batch never describes) — is mapped through a batch-local
   table to a fresh durable id from `:ids`, and the mapping is emitted as
   resolution facts. `:resolved` mode: nothing
   is allocated; ids pass through. A batch that violates its mode (a tempid
   on a `:resolved` medium; a positive `e` on an `:unresolved` one) is a
   defect for the whole batch, so a medium can never be half-resolved.
3. **Fold.** Rows and resolution facts are `dao.data.btree/conj`ed into the
   four trees — a persistent insert that shares every unmodified node with
   the previous state. A node created by the insert carries no address mark;
   dirtiness *is* that absence, a property of the node and of nothing else
   (`btree.cljc` `node-store` skips every child slot that already carries an
   address), so §4.1's `store-tree` later emits exactly what this and
   subsequent folds created. Covered indexes are sets; a duplicate row is a
   no-op (`conj` returns the same set).
4. **Advance** `:batch`, and `:ids` in `:unresolved` mode.

The `:indexes` after batch *n* are a pure function of (index state before,
batch), on every host.

### 2.3 What the two views mean over each medium

`dao.space.query/current` and `history` are unchanged: they are
interpreters over rows, and the index state's trees are a row source.

| Medium | `t` in rows | `current` | `history` |
|---|---|---|---|
| transactor-written (records) | allocated by the writer, monotonic | greatest-`t`-wins per `[e a v]`, retractions removed — full Datomic semantics | exact rows in `t` order |
| a medium whose rows carry no writer-allocated `t` (all rows `t 0`, `m default-op`) | not meaningful | the *set* of facts asserted — correct for a medium of assertions with no retractions | degenerate: all rows share `t 0`; order is the observer's batch ordinal, available through resolution facts (§3), **not** through `t` |

The observer does not paper over the second row by minting `t` — decision
4. A composition that wants transaction-time semantics over such a medium
routes it through a transactor (the writer allocates `t`); one that wants
"what facts does this medium assert" reads `current` and gets exactly that.
**Retractions on such a medium**: an assertion and a later retraction of
the same `[e a v]` both carry `t 0` and differ only in `m`, which
`current-state-seq` rejects as conflicting history (`query.cljc`, the
`m`-conflict throw). The index does not check for this — deciding whether
an `m` value means "retract" is payload interpretation, decision 6 — so the
rows are folded and `history` answers correctly, while `current` over such
a medium *throws* at query time. Loud, not silent; a composition that
retracts on a medium without writer `t` has chosen the wrong medium for
`current`.

---

## 3. Identity

### 3.1 Why the observer must resolve

A medium written batch by batch without a transactor carries negative
tempids allocated *per batch* (from `(- datom/first-user-id)` downward, by
convention; the observer relies only on their being negative). Two
consecutive batches both contain `-16`. Folding them raw into one index
would merge unrelated entities — the silent-wrong-data case.
`dao.space.transact` resolves tempids within one transaction; the observer
must do the same per batch, and only the observer can, because only it
holds the cross-batch allocator.

**Why a mode, fixed at construction.** Durable ids are positive integers on
one number line. A medium whose writer allocates positive ids (a transactor's
log) and an observer minting positive ids for tempids cannot share an index
without eventually colliding: the writer emits 100, the observer mints 101,
the writer's next transaction emits 101. No watermark rule fixes this,
because the two allocators never coordinate. So an index state is either
`:resolved` — it indexes media whose ids are already durable and allocates
nothing — or `:unresolved` — it indexes media that carry only tempids and
owns every positive id in its index, allocating from `datom/first-user-id`
upward. A composition that needs both kinds of medium in one query opens two
index states as two sources; `dao.space.query` already keeps sources as
separate db-values and joins only where the query says so — and here the
query must *not* equate `?e` across a `:resolved` and an `:unresolved`
source, since both number lines start at `datom/first-user-id` and the same
integer names unrelated entities. Joins across them go through values, never
ids. Phase 2's shared allocator is therefore a shared allocator across
`:unresolved` sessions only.

### 3.2 Resolution facts

For every tempid `τ` the observer maps to durable `δ` in batch *n*, it
asserts, in its own attribute namespace:

```
[δ :dao.space.index/batch   n   n default-op]
[δ :dao.space.index/tempid  τ   n default-op]
```

The row's `t` is the observer's batch ordinal *n* — its own logical clock,
never a host clock, and not the observed rows' `t`. `m` is
`datom/default-op`: the attribute namespace alone distinguishes these facts
from observed rows, and nothing else is needed. That is a **contract on
media, not a check in the index**: a medium must not assert attributes in
`:dao.space.index/*`. The index does not inspect attribute namespaces
(decision 6), so a medium that violates the reservation shares `[e a v]`
keys with resolution facts and owns the resulting `current` semantics
(greatest-`t` shadowing, or the `m`-conflict throw on a same-`t` meet).
With the reservation honoured, resolution facts and observed rows have
disjoint `[e a v]` keys and can neither shadow nor conflict. (An earlier draft gave the
observer "its own operation entity" for `m`; that would have required
allocating an entity for the observer itself, and the namespace already
does the work.) These are facts *about* observation, distinct from the
observed rows. They are what make the following queryable:

- **Which batch asserted this entity** — `[?e :dao.space.index/batch ?n]`.
- **Cross-medium provenance**, the case `yin.vm.macro.md` §4.2 leaves to
  "a composition that commits". With one index session per medium
  (`program-in`, `program-out`, log) sharing an allocator, or one session
  over a merged view, the macro expander's event
  `[ev :yin/source-batch t] [ev :yin/source-call -16]` joins to the source
  entity through `[?e :dao.space.index/batch t'] [?e
  :dao.space.index/tempid -16]` — where `t'` is the observer's ordinal
  for the source batch the expander numbered `t`. The correspondence
  between the expander's `:t` and the observer's ordinal is one more
  resolution fact the composition asserts when it wires both observers to
  the same medium. It is a constant offset **only while neither session has
  seen a `gap`**: a skipped batch on either side shifts every later ordinal
  by one, silently. The composition must therefore check both sessions'
  `:ingress-gaps` before trusting the offset, and after a gap either
  re-derive it or mark provenance across that point as unknown. This is the
  same "an index with a gap is a partial index" rule as open question 2.

Refs are resolved only for attributes the supplied `:schema` declares as
`:db.type/ref` — exactly as the transactor relocates only declared refs
today. The schema is the composition's to supply (for a program medium,
`yin.vm.v2/schema`; for an expander's log, `yin.vm.v2.macro/event-schema`);
the index learns which attributes are refs from it and nothing else. An undeclared attribute holding a
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
batch *n* is `bt/store-tree` over each tree plus one manifest — the same
node-blob format and intake path `publish-index!` uses today. What makes it
*incremental* is that the trees keep their `:storage` across publishes:
`store-tree` stores only the **dirty subgraph** (its docstring: "no-op
returning the existing address when the set is already stored"), so nodes
unchanged since the last publication are never re-stored and never
re-appended. `publish-index!` today rebuilds into a *fresh* recording
handle each time, which is why every node is dirty and every publish is
O(tree). Two things persist across publishes in the index state, and they
must not be confused: the **stored-address marks on the trees' nodes**,
which are what `store-tree` consults (dirtiness lives in the tree, not in
the storage), and the **identity of `:storage`**, so that a stored node is
never re-stored under a second handle. What does *not* persist is the
recording handle's content: the blobs it recorded since the last flush are
exactly the payloads to append, in store order, and `flush-staged` drains
them once the intake has accepted the manifest, so the handle never grows
with history. Append cost is therefore proportional to what changed;
`jing/materialize!`'s dedup at the far end is a second line of defence, not
the mechanism.

**Draining is safe only if nothing ever refaults through the drained
handle.** `store-tree` needs nothing from it (marks live in the tree), but
`-store-tree!` also overwrites the set wrapper's *restore source* with the
storage it was given (`btree.cljc`, `set! storage storage'`), and
`resident-root` refaults an evicted root through that field. On the JVM a
root restored from a durable store is held by a soft reference (the
`kv-storage` default ref-type there); on a resumed checkpoint session the
sequence *resume → publish through the recording handle → flush → drain →
GC clears the root → next fold or query refaults the root through the
drained handle* ends in "missing index segment". Child slots are unaffected
(they fault through the durable store the settings name). The invariant the
index state must keep: **a tree's refaults resolve against durable content,
never against the recording handle.** The mechanism is that every tree an
index session holds carries `Settings` with ref-type `:strong`, so a root is
never evicted and never refaults — and *where those settings come from
differs by session kind*, which is the whole subtlety. Ref-pinning consults
the **tree's** settings, not the storage passed to `store-tree`, so a
`:strong` recording handle pins nothing by itself:

- a session started at `:oldest` builds its trees either in memory (settings
  carry no storage; `make-store-ref` pins the raw node) or through its
  recording `kv-storage` constructed with `:ref-type :strong` — safe either
  way;
- a **resumed** session must restore its trees through a
  session-constructed `:strong` `kv-storage` over the durable content store
  (`bt/restore-tree` directly, behind an index-side helper) — **not**
  through `query/open-published!` / `restored-indexes`, whose storage takes
  the host default, `:soft` on the JVM, because those are the *read* path
  and a query consumer wants eviction. Restoring through the read path would
  reproduce the sequence above exactly.

Re-restoring the trees from the durable store after each flush was
considered and rejected — "append success means enqueued, not materialized"
(`dao.space.index.md`), so the durable store may not yet hold the blobs the
new manifest names, and `-restore` throws on absence. cljs and cljd default
to `:strong` already and never see the hazard.

The staging discipline is `yin.vm.macro.md` §5's: node blobs then manifest
are staged as one payload list; `full` retains the exact list and leaves the
index state not-ready; `ok` on the manifest clears it. The manifest is always
last, so a partial prefix is retry-safe, as today.

### 4.2 The checkpoint the transactor asked for

`dao.space.transactor.md` *Open items*: "the log carries its own checkpoint,
from which the watermark and incremental indexes resume". An index state
is that checkpoint's shape: `{:manifest-address a :cursor c :ids i
:batch n}` — the published trees, the medium position they cover, and the
allocator state. A restarted observer restores the four trees lazily from
the manifest through a session-constructed `:strong` `kv-storage` over the
durable content store (§4.1 — not the query read path), re-attaches at `c`,
and continues folding. Nothing
is replayed from the origin. The watermark the transactor derives by
scanning the stream becomes `(max t)` over the checkpointed index's rows —
still O(rows), since `t` is not a sort key in any covered order, but it
touches the index rather than replaying the medium, which is the point.

Two caveats bound this. First, a cursor is transport-scoped: it names a
position on *this* handle's logical stream. Over a process-lifetime
memory-log — the transactor's local medium today — a restarted process sees
"a new, empty logical stream with a new identity" (`dao.space.md`, *Fault
Tolerance*), so the checkpoint's `:cursor` is meaningless after restart and
a resumed observer over such a medium can only start a fresh session at
`:oldest` of the new, empty log. Resumption in the sense above needs a
medium whose cursors survive the process (`dao.stream.file.md`), and the
checkpoint is worth recording only for those. Second, whether the checkpoint
record lives on the observed medium itself (the transactor doc's preference:
"one truth") or beside the manifest in `dao.jing` is left open here; either
is a composition choice, and the observer's state is the same value in
both.

---

## 5. Relationship to the transactor and to `publish-index!`

The transactor remains the writer and the owner of `t`. "Indexing is the
writer's duty" becomes: *the writer runs `dao.space.index` as an observer
over its own stream* — one index state attached to its local memory-log,
publishing on its policy. `publish-index!` is then a convenience over the
same functions: attach at `:oldest`, `fold-batch` to `blocked`, publish
once, discard the state. Its full-history behaviour is preserved as the
degenerate case of an observer that never kept state, and its manifest
format, intake path, and retry-safety are unchanged.

`dao.space.index` gains `fold-batch`, `publish!`, `flush-staged`, `drain`,
the admitting per-element rule, and the resolution-fact attributes; it loses
nothing. Nothing about `dao.jing`
changes: it still materializes opaque payloads from intake pools and never
interprets. Nothing about `dao.space.query` changes: the index state's trees
are one more row source, alongside relation values and published
manifests. The three boundaries of `dao.space.md` are untouched;
what moves is *when* the transactor-side index is built (continuously,
not at publish) and *over what* (any medium, not only the writer's own).

---

## 6. Invariants

| Invariant | How the observer honours it |
|---|---|
| No hidden global state | Allocator, batch ordinal, trees, staged publication — all fields of one index-state value threaded through `run-on-stream`. No registry of observers; a composition holds the states it created. |
| No implicit control flow | Indexing happens in exactly one place: `fold-batch` inside the observe step. Nothing indexes on write, on query, or on publish. |
| No callbacks | `fold-batch` is a function (index-state, batch) → index-state'. The driver is `run-on-stream`; cadence is the composition's. |
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

**Phase 0′ — parity over the transactor's medium.** `dao.space.index` gains
`fold-batch`, `publish!`, `flush-staged`, `drain`, the admitting element
rule, and the resolution facts; a `:resolved` session attached at `:oldest`
over a transactor's local memory-log produces `:indexes` equal (as sets) to
`index-datoms` over `snapshot-datoms` of the same log, and a one-shot
`publish!` equal (as a manifest) to `publish-index!`. Tests: set equality on
all four trees; manifest equality; a malformed element becomes a defect and
the next batch still folds; a tempid on a `:resolved` medium is a defect;
`run-on-stream` `blocked`/`end` behaviour; a second `publish!` after more
batches appends only the blobs stored since the first (count them); the
**checkpoint value** `{:manifest-address :cursor :ids :batch}` round-trips
through `pr-str`/`read-string` (it, unlike a live session, is plain data —
a `BTSet` prints elements-only and a storage handle does not print at all),
and `restored-indexes` over the published manifest equals the live trees as
sets; the F2 hazard pinned deterministically both ways through the btree's
existing `:test` ref seam (`:test` ref-type plus `clear-test-refs!`, which
exists because soft clearing cannot be forced): a resumed session restored
through a `:test`-ref storage, published, drained, refs cleared → the next
query *throws* "missing index segment" (the hazard reproduced); the same
sequence with the session-constructed `:strong` storage → no throw; and a
session restored through `restored-indexes` is asserted to be the wrong
construction for an index session.

**Phase 1 — a medium with batch-local tempids (composition test).** The
index is exercised over a medium another observer also reads: two
`dao.stream.v2.observer` sessions on one `program-out`, one driving
`ast-walker`, one driving `dao.space.index` in `:unresolved` mode. The
index code under test knows nothing of the VM; the test does. Tests:
negative `e` rows are admitted and resolved, positive `e` on this medium is
a defect; the VM's value and the
index's `current` view from the same batches; after each appended batch,
with no snapshot call, `q` over the index state already sees that batch's
entities; two batches with overlapping
tempids index as distinct entities with correct resolution facts;
`[?e :yin/type :lambda]` finds every lambda the program contained; a ref
attribute resolved, an undeclared negative value untouched; `history`
documented as degenerate over `t 0` rows.

**Phase 2 — cross-medium provenance.** Observers over `program-in`,
`program-out`, and the macro log with a shared allocator; the offset fact
between the expander's `:t` and the observer's ordinal. Test: the
`yin.vm.macro.md` §4.1 query chain source-call → event → expansion-root
answered by `dao.space.query/q` over the three indexed media.

**Phase 3 — checkpoint over a durable medium.** Over a file-backed medium
(`dao.stream.file`), restart from `{:manifest-address :cursor :ids :batch}`
and continue; the resumed index equals a from-origin index. Over a
memory-log, assert the documented behaviour instead: a resumed session
starts fresh at `:oldest` of the new log and the checkpoint is not
consulted. The transactor's `create!` derives its watermark from a
checkpoint when one is offered.

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
  index and must say so, and a cross-session ordinal offset (§3.2) is void
  after one. Whether a published manifest should carry the gap count is
  undecided.
- **Where the checkpoint lives** (§4.2).
- **Whether `snapshot` is retired** in favour of "run a throwaway session to
  `blocked` and take its trees". Probably, but `snapshot`'s status
  vocabulary (`:ended`/`:blocked`/`:gap`/`:defect`) should survive as the
  session's terminal report.

---

## Appendix A. Review findings and their resolution

Round 1 (`collab/1789289033041-runtime-review-index-as-observer.glm-5.3.findings.md`), on `5296ee5`; glm-5.3 verified every mechanism claim against the code (all nine hold, claim 9 partially) and requested changes on:

| # | Finding (reviewer) | Resolution |
|---|---|---|
| F1 | Phase 0′'s `pr-str`/`read-string` round trip of a live session cannot pass: `BTSet` prints elements-only, a storage handle is unprintable (glm P2) | Phase 0′ round-trips the *checkpoint value* instead and compares `restored-indexes` to the live trees |
| F2 | Draining the recording handle is unsafe on the resumed-checkpoint path on the JVM: `-store-tree!` rewrites the wrapper's restore source and a soft-ref'd root refaults through the drained handle (glm P2) | §4.1 states the invariant (refaults resolve against durable content, never the handle) and pins index-session roots `:strong`; re-restore after flush rejected because materialization is asynchronous; Phase 0′ test |
| F3 | The `:dao.space.index/*` reservation is a convention the index cannot check (glm P3) | §3.2 states it as a contract on media, with the consequence of violating it |
| F4 | Assert-then-retract on a `t 0` medium hits `current`'s `m`-conflict throw (glm P3) | §2.3 states it: folded, `history` correct, `current` throws loudly; not a defect, since recognising a retraction is payload interpretation |
| F5 | "A query, not a scan" overstates; "dirty-tracked against storage" misplaces dirtiness; tx records and `v`-only tempids on `:unresolved` media inferred, not stated (glm P3) | §4.2 and §2.2 reworded; both cases stated in §2.2 steps 1–2 |

Round 2 (`collab/1789289033041-runtime-review-index-as-observer-r2.glm-5.3.findings.md`), on `280dda2`; F1, F3, F4, F5 confirmed resolved, F2 partially:

| # | Finding (reviewer) | Resolution |
|---|---|---|
| N1 | The `:strong` remedy binds to the session's recording storage, but ref-pinning consults the *tree's* settings; a session resumed via `open-published!`/`restored-indexes` gets the JVM `:soft` default and the F2 sequence survives (glm P2) | §4.1 states where settings come from per session kind; a resumed session restores through a session-constructed `:strong` `kv-storage`, never the query read path; §4.2 reworded |
| N2 | "Survives a forced GC" is not a deterministic test; soft clearing cannot be forced (glm P3) | Phase 0′ pins the hazard both ways through the `:test` ref-type and `clear-test-refs!` |


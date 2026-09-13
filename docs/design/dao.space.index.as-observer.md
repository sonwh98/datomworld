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
               :max-t    nil | n                                 ; greatest writer t folded; nil until one is (§4.2)
               :rejected n                                       ; monotonic count of rejected batches — never drained (§2.2)
               :publish  nil | {:intake writer
                                :staged nil | {:payloads [...] :next i}}   ; optional, §4.1
               :defects  []}                                     ; drainable diagnostics: one event per rejected batch since last drain

;; all in dao.space.index, beside index-datoms and publish-index!
;; over the consumer alone:
ready?           (fn [x] (nil? (get-in x [:publish :staged])))
load             index/fold-batch                              ; pure; §2.2
run              index/flush-staged                            ; resumes a staged publication at :next; identity otherwise (§4.1)
index/publish!   index-state → index-state'                    ; explicit: stage this state's trees, then flush (§4.1)
index/drain      index-state → [index-state' defects]          ; clears :defects only; :rejected is untouched
index/db-value   index-state → query value                     ; §5: the covered trees as a dao.space.query source
;; over the whole {:observer :consumer} session — the cursor and gap count live in the observer half:
index/coverage   session → {:cursor c :ingress-gaps g :rejected n :batch b :max-t t}   ; §5
index/checkpoint session → candidate                           ; §4.2: publish!'s coverage, captured at its boundary
index/restore    candidate storage-opts → index-state          ; §4.2: the consumer half; the composition re-attaches the observer at :cursor
```

`coverage` and `checkpoint` are the two operations that read both halves of a
session. They are index-side functions over a shape `dao.stream.v2.observer`
publishes (`{:stream :cursor :ingress-gaps}`) — the dependency runs
`dao.space.index → dao.stream.v2.observer`, never the reverse — and the
generic loop stays ignorant of every index field.

Driven as `(run-on-stream {:observer o :consumer index-state} ready?
index/fold-batch index/flush-staged)` over any `dao.stream.v2` reader
handle attached through `dao.stream.v2.observer/attach`. The coordination
inspects no field of the index state; it drives it as readily as a VM —
which is the point of decision 2. **Publication is an explicit composition
step**, not a policy inside the loop: the composition calls `publish!`
between rounds (after every *n* rounds, on `blocked`, on a timer — its
choice), and only the *resumption* of a staged publication lives inside
`run`, so that `ready?` stays false until the intake has accepted every
payload. Calling `publish!` while a publication is still staged is a caller
error (the composition checks `ready?` first); a staged publication is never
replaced, only resumed (§4.1). `:ids` records whether it is `:owned` by this
session or `:shared` with others (§3.1); the distinction governs what a
checkpoint may restore (§4.2).

### 2.2 `fold-batch`

One batch, one pure fold. **The fold is atomic per batch**: steps 0–2
validate and plan against the *whole* normalized batch before step 3
touches a tree or step 4 advances `:ids`; a batch rejected at any step
leaves all four trees, `:ids`, and `:max-t` unchanged, increments the
monotonic `:rejected` count, appends exactly one drainable defect event
(batch ordinal, position, reason), and advances `:batch` by exactly one —
never zero, or every later cross-session ordinal (§3.2) would drift on the
first malformed input even without a `gap`. `:rejected` is the durable
record that the index is partial; `:defects` is the diagnostic a driver
reads and clears. Draining never touches `:rejected`, so completeness
survives a drain, a checkpoint, and a restore; a composition that needs the
*identities* of omitted batches retains the drained events itself. An
**empty** admitted batch is a valid no-op: it advances `:batch` once and
changes nothing else — not trees, `:ids`, `:max-t`, or `:rejected`.

0. **Normalize the outer value.** `run-on-stream` hands `load` one stream
   value, and media differ in what that is: a transactor appends one
   transaction-record map per `append!`; a program medium appends a vector
   of rows. The grammar, checked in this order: a map containing
   `:dao.space/transaction` is one element; a vector of exactly five whose
   first slot is an integer and second a keyword is one d5 element (a bare
   row is never mistaken for a five-row batch, and a five-row batch's first
   slot is a vector, not an integer); any other sequential is a batch of
   elements, each normalized by the same two rules and nothing else (no
   nesting); anything else is a whole-batch defect at position `nil`.
1. **Admit elements.** Each element is a d5 row or a
   `{:dao.space/transaction {:t :datoms}}` record, flattened. Admission is
   *looser than* `datom/local-datom?`, which `element-datoms` applies today
   and which requires a non-negative `e`: in `:unresolved` mode admission is
   exactly `local-datom?` minus that one clause — integer `e` of either
   sign, namespaced keyword `a`, `t ≥ 0`, integer `m`. `v` is unconstrained
   by `local-datom?` already, so a negative ref-valued `v` needs no schema
   knowledge to be *admitted*; the schema first matters at step 2. In
   `:resolved` mode admission *is* `local-datom?`. Strictness is restored
   after step 2: every row that reaches step 3 satisfies `local-datom?`. A
   transaction-record element is admitted by the same per-datom rule applied
   to each of its datoms plus the record's own shape checks (one shared `t`,
   non-empty). Anything not admitted is a defect for the whole batch — the
   session continues; a throwing `load` would leave the cursor on the bad
   batch forever, the head-of-line hazard `yin.vm.macro.md` decision 11
   removes. Bad input is data; a bug in the fold itself is the throw.
   `datoms-from-elements` keeps its strict, throwing contract for the
   one-shot path; the fold uses an admitting, non-throwing variant of the
   same per-element rule.
2. **Validate the mode and resolve identity** (§3). Identity lives in three
   slots — `e`, a `v` under an attribute `:schema` declares a ref, and `m`,
   the metadata *reference* — and the transactor already resolves all three
   (`dao.space.transact` collects `m` tempids explicitly). The index does the
   same; §3.1's matrix says what each slot may hold in each mode, and any
   violation is a whole-batch defect. In `:unresolved` mode every tempid in
   the batch — as `e`, as a declared-ref `v`, as `m`, or only as one of the
   latter two (a reference to an entity the batch never describes) — is
   mapped through a batch-local table to a fresh durable id from `:ids`, in
   **first-occurrence order** — rows in normalized-batch order, and within a
   row `e`, then declared-ref `v`, then `m` — so allocation is identical on
   every host, and the mapping is emitted as resolution facts. Reserved ids
   (§3.1: `0 ≤ id < datom/first-user-id`, where `default-op` lives) are never
   allocated; whether a reserved id is admitted in a given slot is the
   matrix's rule, not a blanket pass. `:resolved` mode allocates nothing.
3. **Fold.** Rows and resolution facts are `dao.data.btree/conj`ed into the
   four trees — a persistent insert that shares every unmodified node with
   the previous state. A node created by the insert carries no address mark;
   dirtiness *is* that absence, a property of the node and of nothing else
   (`btree.cljc` `node-store` skips every child slot that already carries an
   address), so §4.1's `store-tree` later emits exactly what this and
   subsequent folds created. Covered indexes are sets; a duplicate row is a
   no-op (`conj` returns the same set). `:max-t` becomes the greater of
   itself and the batch's greatest row `t`; it is `nil` until the first row
   is folded, and an empty batch leaves it as it was.
4. **Advance** `:batch`, and `:ids` in `:unresolved` mode.

The `:indexes` after batch *n* are a pure function of (index state before,
batch), on every host.

### 2.3 What the two views mean over each medium

`dao.space.query/current` and `history` are unchanged: they are
interpreters over rows, and the index state's trees are a row source.

| Medium | `t` in rows | `current` | `history` |
|---|---|---|---|
| transactor-written (records) | allocated by the writer, monotonic | greatest-`t`-wins per `[e a v]`, retractions removed — full Datomic semantics | the exact rows; `history` promises no ordering — over a `db-value` the rows come in EAVT-walk order, not writer order — so order by `t` explicitly when it matters |
| a medium whose rows carry no writer-allocated `t` (all rows `t 0`, `m default-op`) | not meaningful | the *set* of facts asserted — correct for a medium of assertions with no retractions | degenerate: all rows share `t 0`; order is the observer's batch ordinal, available through resolution facts (§3), **not** through `t` |

The observer does not paper over the second row by minting `t` — decision
4. A composition that wants transaction-time semantics over such a medium
routes it through a transactor (the writer allocates `t`); one that wants
"what facts does this medium assert" reads `current` and gets exactly that.
`as-of` filters *both* observed rows and resolution facts numerically
against `t`, though one carries writer time and the other the observer's
ordinal; a composition that uses `as-of` over a session's trees is
filtering two clocks with one number and should say which it means.
**Retractions on such a medium**: an assertion and a later retraction of
the same `[e a v]` — the same *resolved* `e`; a reused negative tempid in a
later batch is a different entity and conflicts with nothing — both carry
`t 0` and differ only in `m`, which
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
one number line. A medium whose writer supplies positive ids — a
transactor's log, where ids are the caller's free choice (`transactor.cljc`
takes `:db/id` as given and derives a watermark over `t` only) — and an
observer minting positive ids for tempids cannot share an index without
eventually colliding, and no watermark over `t` says anything about `e`. An
explicit integer partition (writer even, observer odd, say) would avoid
runtime coordination, but only by changing the writer's allocation
contract, which an arbitrary existing writer has not agreed to; two modes
are the simpler default and the one this note specifies. So an index state
is either `:resolved` — it indexes media whose ids are already durable and
allocates nothing — or `:unresolved` — it indexes media that carry only
tempids and owns every positive user id in its index, allocating from
`datom/first-user-id` upward.

**The mode matrix.** Identity-bearing slots are `e`, a declared-ref `v`,
and `m`. Ids partition into three disjoint classes: **tempid** `id < 0`;
**reserved** `0 ≤ id < datom/first-user-id` (`default-op` lives there);
**user-positive** `id ≥ datom/first-user-id`.

| Slot | `:resolved` | `:unresolved` |
|---|---|---|
| `e` | reserved or user-positive pass through — exactly what `local-datom?` and the transactor admit today, so Phase 0′ parity is unconditional; tempid → defect | tempid → allocated; reserved or user-positive → defect (an unexplained positive reference: the writer cannot know the observer's ids) |
| declared-ref `v` | reserved or user-positive pass; tempid → defect (this is the schema-aware check `local-datom?` alone does not make) | tempid → allocated; reserved passes; user-positive → defect |
| `m` | reserved or user-positive pass; tempid → defect | reserved passes; tempid → allocated; user-positive → defect |
| undeclared `v` | any value, untouched | any value, untouched |

Reserved `e` on a `:resolved` medium is *admitted*, not enforced against:
`datom.md`'s reservation has never been enforced by the runtime, and an
index that started enforcing it would silently diverge from every existing
read path. If enforcement is ever wanted it is a separate, stated decision.

A composition that needs both kinds of medium in one query opens two index
states as two sources; `dao.space.query` keeps sources as separate
db-values and joins only where the query says so. **Equating `?e` across
independently allocated sources is never meaningful** — not across a
`:resolved` and an `:unresolved` source, not across two `:unresolved`
sessions with separate allocators, not across two `:resolved` media with
different writers — because unification compares integers and carries no
allocation-domain check. This is advisory in the query (nothing can enforce
it there) and therefore a contract on compositions: joins across sources go
through values. Phase 2's shared allocator is a *composition-owned value
threaded serially* through the `:unresolved` sessions that share it — never
an atom the sessions share — and only such sessions may equate `?e`. A
session records whether its `:ids` is `:owned` or `:shared`; the
consequence for recovery is §4.2's.

### 3.2 Resolution facts

For every tempid `τ` the observer maps to durable `δ` in batch *n* —
whichever slot it appeared in — it asserts, in its own attribute namespace:

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
disjoint `[e a v]` keys and can neither shadow nor conflict. These are
facts *about* observation, distinct from the observed rows. They make
"which batch asserted this entity" — `[?e :dao.space.index/batch ?n]` — a
query, and they are the raw material for cross-medium provenance.

**Refs are batch-local; cross-medium coordinates are values.** Refs are
resolved only for attributes the supplied `:schema` declares as
`:db.type/ref` — exactly as the transactor relocates only declared refs
today — and a declared ref *always* resolves within the batch it appears
in. There is no way, at attribute granularity, to say "this `-16` names an
entity on another medium": the index would allocate a batch-local entity
for it, and a shared allocator only prevents *collisions*, it establishes
no *correspondence*. So a coordinate into another medium must be carried as
a **value**, qualified by the batch it refers to, under an attribute the
schema does not declare a ref; the join is then value-based over
resolution facts — `[?e :dao.space.index/batch t'] [?e
:dao.space.index/tempid -16]` — and is the composition's, since only the
composition knows which observer's ordinal `t'` corresponds to the
producer's batch number. That correspondence is a constant offset **only
while neither session has seen a `gap`**; a gap count records that gaps
occurred, not how many batches were lost, so after one the offset must be
re-derived or provenance across it marked unknown (open question 2).

This has a consequence for `yin.vm.macro.md` §4.1, recorded here as a
**required amendment** to that document: its `:yin/source-call` is written
as both an external coordinate (initial expansion: an eid in the source
batch) and a log-local ref (nested expansion: a node in the log copy). One
attribute cannot be both. It must split — an undeclared value attribute for
the external coordinate, qualified by `:yin/source-batch` as it already is,
and a declared-ref attribute for the log-local node — so that
`event-schema` declares only the latter a ref and the index resolves it
batch-locally while leaving the former as the value the join needs. The
schema is the composition's to supply (for a program medium,
`yin.vm.v2/schema`; for an expander's log, the amended `event-schema`); the
index learns which attributes are refs from it and nothing else. An
undeclared attribute holding a negative number is a value, not a ref, and is
left alone.

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

**The staged publication is a state machine, not a list.** `publish!`
stages `{:payloads [blob₁ … blobₖ manifest] :next 0}` — the blobs recorded
since the last flush in store order, the manifest last — and `flush-staged`
appends from `:next`, advancing it after each `ok`, so a payload the intake
has accepted is never appended again by an ordinary retry. `full` on payload
*i* leaves `:next = i` and the state not-ready; the next round resumes at
*i*. `closed`, `invalid-value`, a malformed outcome, and `transport-error`
are outcomes this forwarder cannot continue from: it throws, with the
staged state — including `:next` — preserved in the thrown value, so the
composition decides and a retry after a repaired intake resumes rather than
repeats. Manifest-last still holds, so a partial prefix is never a
publication. The guarantee is **at-most-once acceptance per staged
occurrence under ordinary retries**; content-address deduplication at
`jing/materialize!` is a separate guarantee that makes an *abnormal*
re-append (after a lost session, §7) harmless, not exactly-once. The
recording handle's content is drained only when the manifest has been
accepted, i.e. when `:next` reaches the end.

### 4.2 The checkpoint

`dao.space.transactor.md` *Open items* asks that "the log carries its own
checkpoint, from which the watermark and incremental indexes resume". A
checkpoint here is a *value with a validity contract*, in three parts.

**Capture at one boundary.** `publish!` is called between rounds, when the
session's cursor sits exactly after the last batch its trees cover. It
stages the publication on the consumer; `checkpoint`, called on the
**session** at that same boundary — before any further round — captures a
**candidate**

```
{:manifest-address a  :cursor c  :ids i  :batch n  :max-t t
 :ingress-gaps g  :rejected r  :mode m  :schema-hash h}
```

from both halves: the manifest that will name the trees, the observer's
cursor after the last folded batch and its gap count, and the consumer's
allocator, ordinal, greatest writer `t` (§2.2 step 3), monotonic rejected
count, mode, and schema. The cursor and gap count are not in the index
state — `run-on-stream` advances them in the observer half, including on a
`gap` — which is why capture takes the session; the rule that nothing is
folded between `publish!` and `checkpoint` binds whoever calls the two, and
the two are meant to be adjacent calls in the composition. A candidate
whose cursor were later than its manifest's coverage would, on restart,
skip every batch in between permanently.

**A candidate becomes a checkpoint only when it is recoverable.** Intake
success means enqueued, not materialized (`dao.space.index.md`); a process
can fail with the manifest, or blobs it names, still in an in-memory intake
— exactly the loss window `dao.space.md` §Fault Tolerance documents. A
candidate is promoted to a checkpoint by the composition, and only after it
has verified, **through the durable store's read path** (`jing/get`, never
the recording handle or any cache whose presence proves nothing about
durability), that the manifest reads back valid (`read-manifest`) and that
every address reachable from **all four roots** resolves — checked inside
the `bt/walk-addresses` visitor, leaves included, because the walker does
not itself restore leaves. Missing content or a read failure is
"verification incomplete", never success; the check may be retried later,
and because content is immutable and retained, a check that once passed
stays passed. No acknowledgement from the asynchronous DaoJing observer is
needed for a synchronously readable store; an async-only store needs an
asynchronous verification mechanism, which is a capability prerequisite,
not a change to this rule. Until promotion the previous checkpoint stands.
Recovery from a latest publication that never became recoverable is
therefore never a special case: the composition resumes from the last
*verified* checkpoint and re-folds the suffix. Promotion proves the
snapshot is reopenable; that the checkpoint *record* itself is recoverable,
and that the suffix after `c` is still retained on the medium, are the
composition's separate obligations. A candidate captured from a session
whose `:ids` is `:shared` is **never promoted**: it may be published and
captured for inspection, but under this contract it is a snapshot, not a
checkpoint, and `restore` would refuse it anyway (below).

**Resumption is bound.** `restore` refuses a checkpoint whose `:mode` or
`:schema-hash` differ from the session being constructed, **and refuses one
whose `:ids` was `:shared`**: a session-local snapshot of a shared allocator
is stale the moment any sibling allocates after it, and restoring it would
re-mint ids siblings already hold (session A checkpoints at 20, B allocates
20–29, A restores and allocates 20 again). Recovery of a shared allocation
domain is a composition-level checkpoint over *every* session that shares
it, and is deferred (§8); this note's checkpoint/restore contract covers
independently allocated sessions only. For those, `restore` rebuilds the
four trees lazily through a session-constructed `:strong` `kv-storage` over
the durable store (§4.1 — not the query read path) and reinstates `:ids`,
`:batch`, `:max-t`, and `:rejected` on the consumer; the composition
re-attaches the observer at `c` with `:ingress-gaps` reinstated (a partial
index stays partial after restart; a fresh zero would make §3.2's offset
check look safe again). Re-attaching *at a cursor* is a small addition to
`dao.stream.v2.observer/attach`, which mints at `:oldest` today: a third
arity `(attach attach! descriptor {:cursor c :ingress-gaps g})` returns
`{:stream handle :cursor c :ingress-gaps g}` — the kept cursor, which
`dao.stream.md` already says "covers repositioning", validated by the
transport on the first `next`, and the gap count seeded from the checkpoint
rather than reset to `0`. Then folding continues;
nothing is replayed from the origin.

**The watermark.** The transactor's `create!` needs `0` for an empty
history, else one plus the greatest `t` retained. A checkpoint covering
`t ≤ 10` cannot answer that alone if transactions through `t = 12` exist
after its cursor. So the answer is `:max-t` — a writer-time summary derived
from the folded rows at capture, causally bound to `:cursor`, never derived
from observer ordinals — *plus the fold of the retained suffix after `c`*
before writes are enabled; the watermark is then `0` if `:max-t` is still
`nil` (nothing folded, the transactor's empty case) and `max-t + 1`
otherwise, and an empty checkpoint reopened in-process yields `0`, not `1`.
Three cases, distinct:

- **Same logical stream, reopened in-process** (the writer-task restart
  `dao.space.md` §Fault Tolerance names): restore the checkpoint, fold the
  suffix from `c` to `blocked`, then derive as above. This is the O(suffix)
  relief the transactor asked for.
- **Process restart over a durable medium** (`dao.stream.file.md`, cursors
  survive): the same, over the reopened medium.
- **Process restart over a memory-log**: a new, empty logical stream with a
  new identity; the checkpoint's cursor names nothing on it, the session
  starts at `:oldest`, and the watermark is the transactor's today (`0` for
  empty history).

"One truth" is honoured rather than assumed: `:max-t` is not a second
counter that can disagree with the log; it is the log's own `t`, summarized
at a named cursor and only ever advanced by folding the log. Where the
checkpoint *record* lives — on the observed medium or beside the manifest
in `dao.jing` — remains open (§8); its validity conditions above do not.

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
`db-value`, `checkpoint`, `restore`, the admitting per-element rule, and the
resolution-fact attributes; it loses nothing. Nothing about `dao.jing`
changes: it still materializes opaque payloads from intake pools and never
interprets.

**`dao.space.query` gains one source kind.** An index state is not a query
value today: `query/value?` accepts relations, views, and *opened
published* values, and `realize-db-value!` rejects anything else — carrying
`:indexes` does not make a map a source. Wrapping the EAVT rows in
`query/relation` would work but would materialize every row and bypass the
covered trees. So `index/db-value` produces an **in-process covered value**:
the same shape `open-published!` builds (four restored trees plus rows
deferred behind a delay), constructed over the session's live trees instead
of a manifest, and `query` recognises it through the same covered-index
realization path. Dependency direction is preserved — `query` already
requires `index`; `index` builds a value `query` accepts, and the value's
trees are persistent, so a `db-value` taken before a fold is unchanged by
it.

**What "live" means, precisely.** After a successful `run-on-stream` round,
`(index/db-value (:consumer session))` represents every batch the session
has admitted and folded up to the observer's cursor — nothing more — and
`(index/coverage session)` says exactly how far that is and how complete:
the cursor, the gap count, the monotonic rejected count, the ordinal, and
`:max-t`. The value carries no stream; the coverage report is where the
cursor and gaps come from, since they live in the observer half. A rejected
batch is absent from the value and counted in coverage; the identities of
rejected batches are in the drainable defect events and, once drained,
wherever the composition kept them. The value promises nothing about
appends the observer has not yet read, and nothing about a sibling
evaluator's progress over the same medium: "the index is current with the
medium" is a statement about the observer's cursor, and a test that wants
to see a batch must *drive the observer* first, then query. The three boundaries of `dao.space.md` are untouched;
what moves is *when* the transactor-side index is built (continuously,
not at publish) and *over what* (any medium, not only the writer's own).

---

## 6. Invariants

| Invariant | How the observer honours it |
|---|---|
| No hidden global state | Allocator, batch ordinal, trees, staged publication — all fields of one index-state value threaded through `run-on-stream`. No registry of observers; a composition holds the states it created. |
| No implicit control flow | Indexing happens in exactly one place: `fold-batch` inside the observe step. Nothing indexes on write, on query, or on publish. |
| No callbacks | `fold-batch` is a function (index-state, batch) → index-state'. The driver is `run-on-stream`; cadence is the composition's. |
| No shared mutable state | `dao.data.btree` values are persistent; two sessions never share a tree by reference they could both mutate. The recording handle's identity persists with the session and its content is drained at each accepted manifest (§4.1); a shared allocator is a value threaded serially, never an atom (§3.1). |
| No layer collapsing | *append* (writer) → medium → *observe/fold* (this note) → *publish* (intake) → *materialize* (`dao.jing`) → *query*. The VM is a sibling observer, not a stage. |
| No assumed graphs | Ref resolution uses only the supplied `:schema`; the observer does not infer which values are refs. Batch shape is validated by the admitting per-element rule and the outer grammar of §2.2 step 0; malformed input is a defect, not a guess. |

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

**Phase 0′ — parity over the transactor's medium.** *Gated on the
`run-on-stream` partial-session fix landing first* (Phase 0's pending item):
a round that finishes a staged publication and then throws on a later read
or fold would otherwise hand the caller its pre-round session, and a retry
would repeat the accepted publication. `dao.space.index` gains `fold-batch`,
`publish!`, `flush-staged`, `drain`, `db-value`, `checkpoint`, `restore`,
the admitting element rule and outer grammar, and the resolution facts; a
`:resolved` session attached at `:oldest` over a transactor's local
memory-log produces trees **logically equal** to `index-datoms` over
`snapshot-datoms` of the same log — the same rows, counts, and query results
over `db-value`, and a valid restore of what it publishes. *Not* an
identical manifest: `publish-index!` bulk-builds with `from-sequential`
while the session inserts with `conj`, and the two partition the same rows
differently (a JVM probe at branching factor 4 confirms equal rows, unequal
root blobs); canonical layout would be a new design requirement, not a
property of the tree. Tests must include enough rows and insertion orders
to force splits. Further tests: outer grammar — a bare record, a bare d5
row, a vector of both, a malformed outer value, a malformed nested record,
a valid prefix followed by a defect (trees, `:ids`, and `:max-t`
unchanged, `:rejected` +1, one defect event, `:batch` advanced once),
valid–invalid–valid batches, an empty batch (ordinal only), reject → drain
→ checkpoint → restore with `:rejected` intact; the mode matrix, one row
per cell, on both modes, including reserved `e` passing on `:resolved`; `m`
tempids resolved and joined; deterministic allocation order across hosts
including the within-row `e`, `v`, `m` order; a `gap` immediately before
`checkpoint` yields a candidate carrying the recovery cursor and the
incremented gap count; `:max-t` `nil` on an empty stream and the reopened
empty checkpoint deriving watermark `0`; `run-on-stream` `blocked`/`end` behaviour;
the publication state machine — `ok/full/full/ok`, refusal at the manifest,
a terminal outcome after accepted blobs with `:next` preserved in the
throw, a successful resumption followed by a read failure, and a second
`publish!` after more batches appending only the blobs stored since the
first (count them); the **checkpoint candidate** round-trips through
`pr-str`/`read-string` (it, unlike a live session, is plain data), a
candidate whose manifest is not yet materialized is *not* promoted, nor
one whose manifest is present but one leaf is absent (the walk must check
every address, not only the manifest); `restore` refuses a mismatched mode
or schema and refuses a `:shared` allocator (a sibling session allocated
after the candidate was captured); the F2 hazard pinned
deterministically both ways through the btree's `:test` ref seam (`:test`
ref-type plus `clear-test-refs!`): a resumed session restored through a
`:test`-ref storage, published, drained, refs cleared → the next query
*throws* "missing index segment"; the same sequence through the
session-constructed `:strong` storage → no throw; and, gated to the JVM
where the read path is not already `:strong`, a session restored through
`restored-indexes` carries `bt/default-ref-type*`.

**Phase 1 — a medium with batch-local tempids (composition test).** The
index is exercised over a medium another observer also reads: two
`dao.stream.v2.observer` sessions on one `program-out`, one driving
`ast-walker`, one driving `dao.space.index` in `:unresolved` mode. The
index code under test knows nothing of the VM; the test does. Tests:
negative `e` rows are admitted and resolved, positive `e` on this medium is
a defect; the VM's value and the
index's `current` view from the same batches; after each observer round —
the test drives the indexer, then queries; appending drives nothing — `q`
over `db-value` sees every batch folded up to the cursor and a `db-value`
taken before the round is unchanged; two batches with overlapping
tempids index as distinct entities with correct resolution facts;
`[?e :yin/type :lambda]` finds every lambda the program contained; a ref
attribute resolved, an undeclared negative value untouched; `history`
documented as degenerate over `t 0` rows.

**Phase 2 — cross-medium provenance.** Prerequisite: the `yin.vm.macro.md`
§4.1 amendment (§3.2) splitting `:yin/source-call` into a value attribute
for external coordinates and a ref attribute for log-local nodes. Observers
over `program-in`, `program-out`, and the macro log, the `:unresolved`
sessions sharing one composition-owned allocator threaded serially; the
offset fact between the expander's `:t` and the observer's ordinal. Tests:
an initial expansion's external coordinate joins to the source entity
through resolution facts by value; a nested expansion's log-local ref
resolves within the log batch; the same negative number on two media names
two entities; a `v`-only and an `m`-only tempid each get an id and facts;
the `yin.vm.macro.md` §4.1 chain source-call → event → expansion-root
answered by `dao.space.query/q` over the three indexed media; after a
forced `gap` on one session the offset is reported void.

**Phase 3 — checkpoint validity and recovery.** Over a file-backed medium
(`dao.stream.file`): capture a candidate, verify it against the durable
store, restart from it, fold the suffix, and assert the resumed index
equals a from-origin index and the watermark equals `0` when nothing was
folded, else `max-t + 1` over checkpoint plus suffix; a candidate whose
manifest was never materialized is not promoted and restart uses the
previous checkpoint; a `:shared` candidate is not promoted; restored
`:ingress-gaps` and `:rejected` survive; a same-logical-stream in-process reopen
restores, folds the suffix, and only then enables writes. Over a
memory-log, assert the documented behaviour instead: a resumed session
starts fresh at `:oldest` of the new log and the checkpoint is not
consulted. The transactor's `create!` derives its watermark from a
checkpoint plus suffix when one is offered.

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
- **Recovery of a shared allocation domain.** `restore` refuses a
  `:shared` `:ids` (§4.2); a composition that shares an allocator across
  `:unresolved` sessions needs a checkpoint over all of them at once and a
  replay discipline that reassigns the same ids in the same order. Deferred
  with Phase 2's topology choice.
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

Round 3 (`collab/1789289033041-runtime-review-index-as-observer-r3.glm-5.3.findings.md`), on `0bd7550` — **APPROVE** (glm-5.3); N1, N2 confirmed resolved and the resumed path verified closed against `btree.cljc` with no other refault route:

| # | Finding (reviewer) | Resolution |
|---|---|---|
| N3 | The "wrong construction" assertion is vacuous off the JVM, where the read path already defaults `:strong` (glm P3) | Phase 0′ asserts against `bt/default-ref-type*` and gates that assertion to the JVM |

Architecture round (`collab/1789289314611-architect-review-index-as-observer-r2.gpt-6-astra.findings.md`, thread `01a099f1-87d6-7811-8a69-b3336b956fac`, resumed after a provider usage-limit failure), on `e101932`; gpt-6-astra confirmed symmetric ignorance, the core boundaries, the dirty-subgraph claim, the refault fix, and the checkpoint/serialization corrections, and requested changes on:

| # | Finding (reviewer) | Resolution |
|---|---|---|
| A1 | Resolution omits the `m` slot; a macro log's `m = ev` (a negative event id) would stay unresolved and disconnect provenance; `local-datom?` cannot catch it (astra P1) | §2.2 step 2 resolves all three identity slots including `m`-only tempids; §3.1 matrix has an `m` row; Phase 0′/2 tests |
| A2 | `:yin/source-call` declared a ref resolves log-locally; a shared allocator prevents collisions, not correspondence; one attribute cannot be both an external coordinate and a log-local ref (astra P1) | §3.2: refs are batch-local, cross-medium coordinates are values qualified by batch, the join is the composition's; a **required amendment** to `yin.vm.macro.md` §4.1 splitting the attribute is recorded; Phase 2 gated on it |
| A3 | The checkpoint had no validity contract: enqueued ≠ recoverable; four fields not captured at one boundary; incompleteness not preserved (astra P1) | §4.2 rewritten: candidate captured as `publish!`'s return value, promoted only after verifying the manifest and every reachable blob in the durable store, resumption bound to mode/schema and restoring gap/defect counts; Phase 3 tests |
| A4 | "Retain the exact list" is not per-payload progress; the `run-on-stream` progress defect bites an indexer whose round finishes a publication then throws (astra P2) | §4.1 state machine `{:payloads :next}`, advance per `ok`, terminal outcomes throw with `:next` preserved; at-most-once per staged occurrence stated as the guarantee; Phase 0′ gated on the observer fix |
| A5 | The index state is not a `query/value?`; "after each appended batch" is not an observation boundary (astra P2) | §5: `index/db-value` builds an in-process covered value on the `open-published!` shape; "live" defined as every batch folded up to the cursor after a round; Phase 1 test reworded |
| A6 | The mode rule missed positive declared-ref `v` on `:unresolved` media, tempid `v` on `:resolved` media, id zero, and `m` (astra P2) | §3.1 mode matrix over `e`, declared-ref `v`, `m`, with reserved ids |
| A7 | A checkpoint alone cannot yield the writer watermark when retained transactions follow its cursor; the in-process task restart was excluded (astra P2) | §4.2: `:max-t` at capture plus the folded suffix; three restart cases distinguished; "one truth" argued, not assumed |
| A8 | Outer batch grammar unspecified (a record map iterated as a batch would see map entries); rejected batch's state transition unspecified (astra P2) | §2.2 step 0 grammar; atomic-per-batch rule with `:batch` advanced exactly once |
| A9 | Manifest equality is not a valid acceptance test: `from-sequential` and `conj` partition the same rows differently (JVM probe) (astra P2) | Phase 0′ requires logical equality — rows, counts, query results, valid restore — with enough rows to force splits |
| A10 | `history` promises no `t` order; `as-of` mixes two clocks; the retraction example must be qualified by resolved identity (astra P3) | §2.3 reworded |
| A11 | Even/odd partition is a real alternative; "never equate `?e`" is advisory for *any* independent sources; two invariant-table entries stale; shared allocator must be a threaded value (astra P3) | §3.1 and §6 reworded |

Architecture round 3 (`collab/1789289314611-architect-review-index-as-observer-r3.gpt-6-astra.findings.md`), on `3d32eb4`; A1, A2, A4, A9, A10, A11 confirmed resolved, the promotion predicate confirmed sound and the two-attribute macro amendment confirmed the right shape (a structured external coordinate carrying source identity is an optional strengthening for merged logs); requested changes on:

| # | Finding (reviewer) | Resolution |
|---|---|---|
| R1 | `publish!`/`checkpoint`/`db-value` over `index-state` alone cannot see `:cursor` or `:ingress-gaps`, which live in the observer half (astra P2) | §2.1: `checkpoint` and `coverage` take the session; `db-value` stays over the consumer; `restore` returns the consumer and the composition re-attaches at `c` (`attach` gains a kept-cursor argument) |
| R2 | Draining `:defects` erases the only record that the index is partial (astra P2) | §2.2: monotonic `:rejected` separate from drainable `:defects`; carried in coverage and checkpoint |
| R3 | "Reserved" overlapped tempids; reserved `e` rejected on `:resolved` contradicts `local-datom?` and breaks parity (astra P2; glm R4-1, R4-4) | §3.1: disjoint tempid/reserved/user-positive partition; reserved `e` passes on `:resolved`, stated as a decision |
| R4 | Empty batches have no greatest `t`; `:max-t` had no empty-history value; within-row order unspecified (astra P2; glm R4-2) | §2.2: empty batch advances the ordinal only; `:max-t` is `nil` until a row folds, watermark `0` then; order `e`, `v`, `m` |
| R5 | Restoring a session-local `:ids` can rewind a shared allocator (astra P2) | §4.2: `restore` refuses `:shared`; coordinated recovery deferred (§8) |

Runtime round 4 (`collab/1789289033041-runtime-review-index-as-observer-r4.glm-5.3.findings.md`), on `3d32eb4` — **APPROVE** (glm-5.3); all seven new mechanisms verified against the code; four P3s (R4-1 reserved-`e` decision, R4-2 `:max-t` initial, R4-3 cursor supplier, R4-4 category partition and `history` wording) resolved by the rows above and §2.3.

Architecture round 4 (`collab/1789289314611-architect-review-index-as-observer-r4.gpt-6-astra.findings.md`) and runtime round 5 (`collab/1789289033041-runtime-review-index-as-observer-r5.glm-5.3.findings.md`), both on `a4395d8` — **APPROVE** from both reviewers. R1–R5 confirmed resolved; the `:shared` refusal confirmed as the right mandatory boundary; `coverage`/`checkpoint` placement in `dao.space.index` accepted as ordinary composition over protocol state in the permitted direction. Trailing P3s folded after approval:

| # | Finding (reviewer) | Resolution |
|---|---|---|
| T1 | Phase 3 still said `max-t + 1` unconditionally and `:defects` survives restore (astra P3) | Phase 3 reworded to the conditional watermark and `:rejected` |
| T2 | A `:shared` candidate should not be promotable, only captured as a snapshot (astra recommendation) | §4.2 promotion refuses `:shared` |
| T3 | The kept-cursor `attach` arity must say how `:ingress-gaps` is seeded (glm P3) | §4.2 names the arity `(attach attach! descriptor {:cursor c :ingress-gaps g})` |

Approval covers the design with its stated implementation prerequisites: the `run-on-stream` partial-session fix, the kept-cursor `attach` arity, and the `yin.vm.macro.md` §4.1 provenance amendment.


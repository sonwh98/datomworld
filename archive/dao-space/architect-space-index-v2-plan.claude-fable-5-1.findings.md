Completed-GMT: 2026-09-08 13:20 GMT
Completed-Local: 2026-09-08 20:20 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828

# Plan: dao.space.index and dao.stream

## Context

`dao.space.index` is one of the named remaining v1 consumers in
`dao.stream.md` (*The v2 namespace is transient*). `dao.space.query` migrated
under its own plan and is now v2-only; this plan answers the same question for
`index`, following the same method: an explicit invariants list is the
contract, the existing implementation and tests have no authority beyond the
invariants they pin, and the plan is deleted when consumed, so it must leave
nothing owed.

**The headline answer is that index cannot migrate yet, and the reason is
structural rather than a matter of effort.** What this plan therefore does is
reduce index's v1 surface to exactly two named items with named owners,
pre-decide every question those owners will otherwise have to re-open, and
correct one load-bearing error in the brief. Sections 1 and 2 are the
deliverable; sections 3–5 are the executable part.

---

## 1. Correction to the measured facts

> **`PublishedIndexStream` and `ds/defopen :dao.space.index/published` are not
> dead. They are reached from `dao.space.schema`.**

`src/cljc/dao/space/schema.cljc:1161`, inside
`(ds/defopen :dao.space.schema/published …)`:

```clojure
(let [r (ds/open! (index/published-index content-store manifest-address))]
  (ds/strict-vec r)   ; force the delay so the store can be closed later
  r)
```

The grep for `ds/open!` call sites missed it because the descriptor is
constructed inline rather than bound to a name — the type keyword
`:dao.space.index/published` never appears at the call site. It is exercised
by `schema_test` W38–W41 (`published-descriptor-as-raw-db-input`,
`published-descriptor-validation`, `published-accepted-as-current-source`) and
directly by ten `ds/open!` calls in `index_test` (600–783).

`dao.space.query` did stop using it — everything else that names a published
coordinate (`query_test:613`, `stigmergy_test:373-425`) now goes through
`query/open-published!`. But schema still needs a *v1 reader*, because
`schema/current` and `ds/strict-vec` consume streams, and that is schema's
machinery, not index's.

So Q4's instruction ("confirm they are unreachable and delete them here")
cannot be executed as written. Deleting them forces schema to grow a
replacement, which the constraints put out of scope.

## 2. Decisions

### D1 — index stays dual. The transactor's local stream must migrate first. (Q1, Q5)

The blocker is not `snapshot-datoms`; it is `publish-index!`'s signature.
`publish-index!` (index.cljc:574) takes `local-stream` and reads it
(`:578`). That stream is v1 by construction: the caller opens it with
`ds/open! {:dao.stream/type :ringbuffer}`, the `:transactor` wrapper is itself
a v1 stream (`DaoStreamLog` implements `ds/IDaoStreamReader`/`Writer`/`Bound`
and appends through `ds/append!`), and `transactor/publish!` hands
`(.-local-stream log)` straight to `publish-index!`. Index reads a v1 reader
because its *input* is a v1 reader, and no restructuring inside index changes
that.

Therefore:

- **Ordering: `transactor` → `index`.** `dao.space.transactor` migrates its
  local-stream handling to v2 under its own plan; index's snapshot becomes a
  v2 drain in the same phase, or immediately after.
- **`schema` is the second gate**, independently: `:dao.space.schema/published`
  is the sole remaining consumer of index's v1 published adapter.
- Index ends this plan **dual, with two named remainders** (section 5). Both
  are owed to other namespaces' plans; index owes nothing to itself.

Rejected alternative: change `publish-index!` to take a datom seq and move the
drain to the transactor. It would remove index's *snapshot* v1 dependency now,
but index would still require `dao.stream` for the published adapter, so it
buys no v1-freedom while breaking a public signature and its documentation.
Not worth it. (If the schema constraint is relaxed, see section 6.)

### D2 — `drain` is not promoted yet. The trigger has not fired. (Q2)

`dao.space.query.md` records that `snapshot` moves to `dao.stream.observe`
as `drain` when a second consumer appears. It has not. `snapshot-datoms` reads
a **v1** reader with `ds/next`, so it cannot call a v2 `drain`; a `drain`
introduced now would have exactly one caller and would be dead code in the
namespace it was promoted to. The other `observe/step` callers
(`dao.stream.forward`, `yin.vm.stream-observer`, `dao.jing`) each supply
a real refusable effect and a policy loop — they are `step` consumers, not
`drain` consumers.

The second consumer is index's snapshot **after** D1's ordering completes.
Restate the trigger there rather than here. What this plan does settle, so the
transactor plan does not have to:

- **`drain` is total; throwing is index's policy, not `drain`'s.** The two
  functions do not actually disagree today: `query/snapshot` returns
  `:gap`/`:defect` as data because a partial relation may still be a usable
  query input; `snapshot-datoms` throws on a gap because `publish-index!`
  must abort before emitting anything (`dao.space.index.md`: "a retention gap
  aborts publication before anything is emitted"). Both are policies above a
  total step. `drain` returns the status; `snapshot-datoms` becomes
  `(let [{:keys [relation status …]} (drain h)] (case status :ended/:blocked …
  (throw …)))` and `query/snapshot` becomes a thin `drain` caller. Neither
  invariant moves.
- **`query/snapshot` is `drain` plus a relation tag; `snapshot-datoms` is
  `drain` plus the datom/transaction vocabulary.** The vocabulary stays in
  index (it is realization vocabulary), which is what Phase 1 below makes
  explicit.

### D3 — the coordinate keeps its `:dao.stream/*` keys. (Q3)

Keep. Reasons, in order of weight:

1. It is **serialized data that already travels**. `index_test:759`
   round-trips the descriptor through `pr-str`/`read-string` and through a
   carrier stream; `stigmergy_test` builds it against both `:dao.jing/file`
   and `:dao.jing/remote` coordinates. A rename is a wire-format break for a
   coordinate whose whole purpose is portability, in exchange for nothing.
2. `query/open-published!` (query.cljc:236-241) and schema's `published`
   (schema.cljc:1144-1149) both validate the **exact map** by `=`. A rename
   touches all three namespaces and their tests to no effect.
3. The keys stay correct through the endgame: when `dao.stream` is renamed
   to `dao.stream`, `:dao.stream/type` is still the right namespace for a
   stream-vocabulary key.
4. `:dao.stream/bound` and `:dao.stream/comparator` are genuinely stream
   vocabulary — an exact bound and a sort order — not index-private names.
   That nothing dispatches on them is a fact about *dispatch*, not about
   whether they are the right names.

Record this in `dao.space.index.md` as a decision so it stops being re-opened.

### D4 — index's payload vocabulary and query's are two contracts, not one.

`index/stream-payload-datoms` (index.cljc:411) and `query/flatten-datoms`
(query.cljc:32) both flatten `{:dao.space/transaction {:t n :datoms […]}}`,
and they deliberately differ: index requires `datom/local-datom?` (non-negative
integer e/t, integer m, namespaced-keyword a) because it is validating what
gets *persisted*; query accepts any 5-vector because query-only db-values have
caller-chosen ids (`index.cljc:108-116` says the same thing about
`cmp-field`). Do not merge them. Note the divergence and its reason in the
design so the next reader does not "fix" it.

---

## 3. The invariants this plan is accountable to

Marked `[D]` stated in the design, `[T]` pinned only by a test, `[T→D]`
test-pinned and worth promoting, `[T✗]` an implementation accident dropped.
Scope: the snapshot surface (restructured here) and the published surface
(decided here). Everything not listed is untouched.

**The snapshot**

| # | Invariant | |
|---|---|---|
| S1 | Reads from cursor zero; the local stream must retain complete history | `[D]` |
| S2 | An element is one canonical local d5 vector `[e a v t m]` **or** one `{:dao.space/transaction {:t n :datoms [...]}}` record; records flatten to their datoms | `[D]` |
| S3 | A transaction record's shape is exactly `#{:t :datoms}`, `:t` a non-negative integer, `:datoms` a non-empty vector of `local-datom?` all carrying that same `:t` | `[T]` → `[T→D]` (promote: the design says "validated and flattened", not what validated means) |
| S4 | `:blocked` and `:end` finish the snapshot at the current tail | `[D]` |
| S5 | A gap throws, before any intake emission | `[D]` |
| S6 | A malformed stream result, datom, or transaction record throws, before any intake emission | `[D]` |
| S7 | A malformed *5-vector* gets a distinct "malformed local datom" message from a non-vector payload's "must be a datom or transaction record" | `[T]` (index_test:209) → keep as `[T]`; it is a diagnostic, not a contract |
| S8 | Flattening happens incrementally during the drain | `[T✗]` — an accident of the loop's shape. Nothing can observe it: no emission occurs until the snapshot completes. Dropped so the drain and the vocabulary can separate. |

**The published surface**

| # | Invariant | |
|---|---|---|
| P1 | The coordinate is exactly `{:dao.stream/type :dao.space.index/published :dao.stream/bound {:manifest-address a} :dao.stream/comparator :dao.space.index/eavt :content-store c :manifest-address a}` | `[D]` — confirmed by D3 |
| P2 | The coordinate is plain EDN and survives a stream round-trip | `[T]` (index_test:759) → `[T→D]`, it is the point of the coordinate |
| P3 | Opening fetches exactly one blob (the manifest); zero nodes faulted | `[D]` (also `[T]` index_test:690) |
| P4 | Rows are deferred behind a delay, in EAVT order, equal to `read-datoms` | `[D]` |
| P5 | A store opened during a failed open is closed before the error propagates | `[D]` |
| P6 | The v1 adapter is a reader and bound, never a writer; `closed?` is true from construction; `close!` returns `{:woke []}`, is idempotent, and does not erase retained rows | `[T]` (index_test:651) → stays `[T]`, and stays **v1-shaped**: it is v1 protocol vocabulary that dies with the adapter |
| P7 | Two cursors advance independently over one realization | `[T]` (index_test:670) → `[T✗]` *for the v1 adapter's lifetime only*: `dao.space.query.md`'s "Bounded realizations are values, not streams" already ruled that a fully-computed row vector has no second-observer need. It is pinned today only because the adapter is a reader; it does not survive into the value-shaped replacement. |
| P8 | `covered-indexes` is a structural check on `:indexes`, never an instance check, so an in-memory `index-datoms` map and an opened realization are indistinguishable | `[D]` — already satisfied by `query/open-published!`'s value, which carries `:indexes` |

## 4. Phase 1 — separate the payload vocabulary from the reading

One namespace, `src/cljc/dao/space/index.cljc`, plus its test. No behaviour
change; the point is that the transactor plan's switch becomes two lines
instead of a rewrite, and that S2/S3 become a directly testable contract
rather than something only reachable through `publish-index!`.

**Edits to `src/cljc/dao/space/index.cljc`:**

1. Rename private `stream-payload-datoms` → private `element-datoms` (one
   element → its datoms). Body unchanged; S3, S6, S7 preserved verbatim,
   including both error messages.
2. Add **public** `datoms-from-elements`:
   ```clojure
   (defn datoms-from-elements
     "The local-stream payload vocabulary: flatten a seq of local-stream
      elements into canonical local d5 datoms. Each element is one canonical
      datom vector or one atomic {:dao.space/transaction {:t n :datoms [...]}}
      record; records are validated and flattened. Malformed elements throw.

      This is the vocabulary half of the snapshot. The reading half lives in
      `snapshot-datoms` and is the namespace's last dao.stream v1 dependency."
     [elements]
     (into [] (mapcat element-datoms) elements))
   ```
   `into` is eager, so S6's throw-before-emission ordering is unchanged.
3. Rewrite `snapshot-datoms` as drain-then-flatten: accumulate `(:ok result)`
   payloads in the existing v1 loop, then `(datoms-from-elements elements)` on
   the `:blocked`/`:end` exit. Gap and malformed-signal branches unchanged.
   Extend its docstring to name it the v1 remainder and its owner:
   > This loop is `dao.space.index`'s last `dao.stream` v1 dependency. It
   > drains a v1 reader because `publish-index!`'s input *is* a v1 reader —
   > the transactor's local stream. When `dao.space.transactor` migrates,
   > this body becomes `dao.stream.observe/drain` plus
   > `datoms-from-elements`, and the `[dao.stream :as ds]` require can go.

**Edits to `test/dao/space/index_test.cljc`:**

4. Add `datoms-from-elements-is-the-local-stream-vocabulary`, pinning S2, S3,
   S6, S7 directly on the vocabulary: a bare canonical datom passes through; a
   transaction record flattens; extra keys / non-integer `:t` / empty
   `:datoms` / a datom whose `t` disagrees with the record's throw; a
   5-vector with a non-namespaced `a` gets the "malformed local datom"
   message; a non-vector non-record gets the "must be a datom or dao.space
   transaction record" message. Every existing snapshot test stays as written
   — they now pin the drain and the composition rather than the vocabulary.

Nothing is deleted in this phase, so the suites should be green with no other
edits.

## 5. Phase 2 — record the decisions and the remainders

Documentation only, in three files.

**`docs/design/dao.space.index.md`**

1. *What the library owns* → **The snapshot** bullet: split it into the
   vocabulary (`datoms-from-elements` — S2, S3, S5, S6) and the reading
   (`snapshot-datoms` — S1, S4), and state that the reading is v1.
2. *Public surface*: add `(index/datoms-from-elements elements)`.
3. New section **`dao.stream` v1 remainder**, the ledger this plan exists to
   leave behind:

   | Remainder | Owed to | What that plan does |
   |---|---|---|
   | `snapshot-datoms`' `ds/next` loop (index.cljc:451-468) and `publish-index!`'s v1 `local-stream` input | `dao.space.transactor`'s plan | Migrate the local stream and the `:transactor` wrapper to v2; then `snapshot-datoms` = `observe/drain` + `datoms-from-elements`, and this is the second `drain` consumer, so promote `query/snapshot`'s loop to `dao.stream.observe/drain` in the same move (D2). `dao.space.schema`'s two `index/snapshot-datoms` call sites (926, 969) follow. |
   | `PublishedIndexStream` (index.cljc:344-363) and `ds/defopen :dao.space.index/published` (383-404) | `dao.space.schema`'s plan | They exist solely for `:dao.space.schema/published` (schema.cljc:1154-1168), which forces the rows with `ds/strict-vec` immediately on open. Replace with a value-shaped opener as `query/open-published!` did (`dao.space.query.md`, *Bounded realizations are values, not streams*); schema's forced-vector need is served by `index/read-datoms` alone, which needs no reader, no `restored-indexes`, and no retained store handle. Delete both, and index_test's ten `ds/open!` adapter tests with them — P3/P4/P8 are already pinned on `query/open-published!`, P1/P2 on `published-index` itself, P6 is v1 protocol vocabulary that dies with the adapter, and P7 is dropped per the table above. |

   With those two gone, index requires only `dao.stream`, and the entry in
   `dao.stream.md`'s remaining-consumer list drops `index`.
4. New *Decisions* subsection: D3 (coordinate keys, with all four reasons) and
   D4 (two vocabularies, not one).

**`docs/design/dao.space.query.md`** — *Snapshots*: replace "`snapshot` stays
here until a second consumer needs it" with the precise trigger and the policy
split from D2 (the second consumer is `index/snapshot-datoms` once the
transactor's local stream is v2; `drain` is total, the gap policy stays with
each caller).

**`docs/design/dao.stream.md`** — no edit. `index` is still a v1 consumer and
the list is still accurate. Stated so the next reader does not go looking.

## 6. If the schema constraint is relaxed

Not recommended within this plan's scope, but costed so the choice is
available. Index can end **v1-free** by adding one phase that moves the second
remainder forward: rewrite `:dao.space.schema/published` in `dao.space.schema`
over `index/read-datoms` (open the coordinate, read the eager EAVT vector,
close the store, wrap the vector in a small private v1 reader record),
then delete `PublishedIndexStream`, the `defopen`, and the
`[dao.stream :as ds]` require from index. Roughly 25 lines added to schema,
60 deleted from index, 10 tests moved from `index_test` to `schema_test`. It
is strictly simpler than what exists — schema forces the rows at open anyway,
so nothing needs the lazy trees or the retained handle. The reason it is not
in the plan is the constraint, not the difficulty. **The first remainder stays
regardless: `publish-index!`'s input is v1 until the transactor migrates.**

## 7. Verification

Per phase, all green before the next phase starts:

```
bb test:clj
bb test:cljs      # confirm "Testing dao.space.index-test" appears in node output
bb test:cljd
clj -M:cljs -m shadow.cljs.devtools.cli compile demo   # the demo constraint
```

Notes:
- The `:demo` build reaches `dao.space` from no entry point
  (`datomworld.demo` requires only `datomworld.demo.*`, `reagent`, and
  `yin.vm.telemetry-viewer`), so a demo break would be a compile regression,
  not a behavioural one. Compile it anyway, as the constraint requires.
- ClojureDart: `datoms-from-elements` adds no reader conditionals, so the
  `#?(:cljd … :clj …)` ordering trap does not apply. Confirm
  `dao.space.index-test` appears in the Dart run rather than assuming it.
- Phase 1 changes no behaviour, so any red test is a real regression in the
  drain/vocabulary split — most likely the `into`/`mapcat` eagerness in S6's
  throw-before-emission ordering. `publish-index-malformed-local-stream-throws-before-emission`
  (index_test:393) and `-gap-local-stream-throws-before-emission` (:375) are
  the tests that would catch it.

Completed-GMT: 2026-09-09 17:45:50 GMT
Completed-Local: 2026-09-10 00:45:50 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: b71838c3-58ae-434b-bbcd-73418c7af768

# dao.space.schema Phase 2 — the read side

Spec: `docs/design/dao.space.schema.implementation-plan.md` §5, at `bdbe6f9`.
Nothing staged, nothing committed. Touched, exactly the Ownership list:
`src/cljc/dao/space/schema.cljc`, `test/dao/space/schema_test.cljc`,
`test/dao/space/schema_fixtures.cljc` (deleted),
`docs/design/dao.space.schema.md`, `docs/design/dao.stream.md`,
`docs/design/dao.space.transactor.md`.

## Built (schema.cljc)

- **`current`** is the D3/D4 contract, §5's literal body: a private
  `snapshot-result?` (`(and (map? x) (contains? x :relation) (contains?
  x :status))`, mirroring query's private `snapshot-value?`); a snapshot
  whose status is not `:ended`/`:blocked` throws naming the status;
  otherwise the snapshot's `:relation` is taken as the source;
  `validate-not-nested-view!` then `interpret-view`. The docstring carries
  D4's limit verbatim — rejection detects an observed read failure; a
  prefix evicted before the snapshot is indistinguishable from complete
  history; completeness is the caller's declaration, never schema's check.
- **`validate-not-nested-view!`**: the `:dao.stream/type` arm is gone, the
  `:fact?` arm is in; message "schema/current source must be a d5 source
  value, not a nested view".
- **`interpret-view`** takes a query value; the `ds/realization?` drain
  branch is gone; body otherwise unchanged (docstring's "already-opened
  (and closed) source realization" → "a d5 source value").
- Requires: `dao.jing`, `dao.jing.coordinate`, `dao.stream` and the
  `:require-macros` deleted. Final set: `dao.datom`, `dao.space.index`,
  `dao.space.query`, `dao.space.transactor`. The ns docstring's "published,
  :dao.space.schema/published opener" mention went with them (it would
  otherwise trip the deleted-name sweep).

## Deleted (schema.cljc)

`ds/defopen :dao.space.schema/current` and its body; the `ds/open!` call
in `current`; `published`; `PublishedSchemaRows`;
`ds/defopen :dao.space.schema/published`; the four `#_{:clj-kondo/ignore …}`
forms. The `[:unused-binding]` ignore before `with-write-lock` stays — it
was never one of the four.

## Tests (schema_test.cljc)

- Requires: `dao.stream`, `dao.space.schema-fixtures`, the
  `:require-macros` dropped; `dao.stream`, `memory-log`, `ringbuffer`
  stay, as do `jing`/`jing-file` (the observer helpers use them).
- `open-closed` deleted, with its `[:unresolved-var]` ignore.
- **Deleted deftests**: `borrowed-and-descriptor-paths-agree` (V8),
  `inner-stream-closed-after-descriptor-path` (V10),
  `borrowed-path-does-not-close-again` (V11 → re-pinned by V14),
  `published-descriptor-validation` (W40/P4 — its checks live on
  `index_test:592` and `query_test:936`).
- W12 case 3 is now `(schema/current (schema/current rel))` throwing
  `#"nested view"` (V6 on the value). **One disclosed hygiene edit in the
  same deftest**: cases 1–2's shared regex was
  `#"nested view|not an open!-dispatchable"`; the second alternative named
  a message that no longer exists anywhere, so all three cases now use
  `#"nested view"`. No assertion's meaning changed.
- W14's leading comment rewritten (the error surfaces when the view is
  built — extract-schema runs inside `schema/current`); assertion
  unchanged.
- W38, W39, W41 restructured to §5's literal nesting:
  `materialize-to-file-store` → `query/open-published!` over
  `index/published-index` → assertions → `query/close-published!` →
  `cleanup-file`, with `opened` in scope of the close that releases it.
  Assertions unchanged (P1–P3). W39's stale "open!-dispatchable" comment
  rewritten to name the opened value.
- **New `snapshot-read-failures-are-rejected` (V13a)**: two `reify`d v2
  readers modelled on `query_test`'s `snapshot-of-a-gap-is-data` (reads
  one row, then answers `gap` with a recovery cursor) and
  `snapshot-of-a-defect-carries-the-raw-answer` (answers
  `cursor-mismatch`); `query/snapshot` each, precondition assertions on
  the status, then `schema/current` throws `#"rejects a snapshot that
  reported gap"` / `…"reported defect"`. (The full-literal regexes avoid
  `#"defect"` matching "defective" in the message tail.)
- **New `complete-snapshots-are-accepted` (V13b)**: a memory-log carries
  `schema-rows` plus two card-one values at different `t`; open →
  `(is (= :blocked (:status snap)))`, accepted, answer `=` the same rows
  as `(query/relation)`; `stream/close!` → `:ended`, accepted, same
  answer. The equality is on the pure-data fact-relation values.
- **New `evicted-prefix-is-not-detectable-through-snapshot` (V15)**: a v2
  ring buffer, capacity 4, receives `schema-rows` then
  `[7 :person/name "old" 1 1]` `[7 :person/name "new" 2 1]`;
  `(is (= :blocked (:status snap)))` — the cross-layer pin — then
  `schema/current` accepts and the query is `#{["old"] ["new"]}`: the
  vocabulary was evicted, collapse did not fire. The leading comment
  states this is the documented D4 limit, not a guarantee, names the two
  instruments that would have prevented it, and carries the plan's note
  that a V15 failure is not by itself evidence of a schema defect.
  **Not "fixed"**: no retention check, no `:blocked` rejection, no
  query API widening.
- **New `schema-view-is-self-contained-and-never-closes-the-store`
  (V14)**: publish, materialize, `open-published!`, then D6's seam
  verbatim — wrap the store's `:close-fn` with a counter
  (`jing/close!` delegates to it), `schema/current`, `(is (zero?
  @closes))`, `close-published!`, `(is (= 1 @closes))`, then the query
  twice over the view after the close. Plain data, no `with-redefs`, so
  it runs on all three hosts.
- Deftest count 72 → **72** (four deleted, four added).

## Docs

- `dao.space.schema.md`: §4's descriptor block and the *Realization*
  paragraph replaced by the value contract (sources, closes-nothing,
  V11/V14, nested/`:fact?` rejection, D4 in full with the
  `dao.stream.md` *Complete history* pointer and the T18-form
  host-assembly-defect sentence); §5's published-descriptor paragraph and
  block replaced by the `index/published-index` / `open-published!` /
  caller-closes statement with the lens-is-the-reader's reason; §7's
  opener-registration sentence deleted and the read example's comment
  shows `open-published!` → `schema/current` → `close-published!`; §8's
  implementation-verification opens "the view is a value …
  (V13a/V13b/V14/V15)" and its `:source` ruling says "a d5 source value";
  §8 gains the V15 ruling bullet; §9's view item states V13a/V13b/V14/V15
  and the publisher-parity item names `query/open-published!`; the status
  paragraph gains its one migration sentence (2026-09-09).
- `dao.stream.md`: `dao.space` removed from the remaining-v1 consumer
  list.
- `dao.space.transactor.md`: the schema line in *Related documents* loses
  "its D10 rule governs the shapes it re-wraps" — Phase 1's deliberate
  residue, now cleared.

## Verification — commands, outcomes, counts

- `clojure -M:test` → **Ran 1436 tests containing 165349 assertions.
  0 failures, 0 errors.** `Testing dao.space.schema-test` present.
- `bb test:cljs` → exit 0. **Ran 1346 tests containing 34922 assertions.
  0 failures, 1 errors.** `Testing dao.space.schema-test` appears in the
  Node output. The one error is the pre-existing
  `yin.vm.telemetry-test/wasm-eval-emits-telemetry-test`
  (`ReferenceError: wasm is not defined`), documented in every prior
  phase's findings of this sweep.
- `bb test:cljd` → exit 0, **"All tests passed!"** (+1299). 68
  schema-test entries; all four new deftests present in the runner
  output.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` →
  `[:demo] Build completed. (212 files, 1 compiled, 0 warnings, 2.36s)`.
- §5 Prove greps, actual results:
  - `grep -c "ds/" src/cljc/dao/space/schema.cljc` → **0**;
    `grep -c "ds/" test/dao/space/schema_test.cljc` → **0**.
  - `grep -n "\[dao.stream\|\[dao.jing\|require-macros"
    src/cljc/dao/space/schema.cljc` → **nothing**.
  - `test/dao/space/schema_fixtures.cljc` does not exist. The
    deleted-name sweep over `src test docs/design` → **nothing outside
    `docs/design/dao.space.schema.implementation-plan.md`**: every match
    is the plan document itself (committed at `32cd7c8`, transient by its
    own status line, untouchable by me). Before the cljd lane the sweep
    also matched stale generated Dart under `test/cljd-out`; after the
    lane regenerated the tree those matches are gone.
  - Deftest count → **72**.
  - `grep -rln "\[dao.stream :as" src/cljc/dao/space` → **nothing**:
    every `dao.space.*` namespace is off v1.
- `clj -M:kondo --lint src/cljc/dao/space/schema.cljc` (test file linted
  too) → **0 errors, 0 warnings**, and no infos: the five "Redundant
  ignore" notes from Phase 1 are gone, deleted with the forms they
  covered.
- Stale generated Dart: `lib/cljd-out/dao/space/schema-fixtures.dart`
  removed before the cljd lane (the only output the deleted namespace
  had; `test/cljd-out` held no fixtures file — the namespace carried no
  deftests). The lane regenerated both trees cleanly.

## Deviations disclosed

1. **W12's shared regex simplified** in cases 1–2 (see above) — the old
   alternative named a deleted message. Same deftest the plan rewrites;
   assertion meaning unchanged.
2. **§8's *Schema epochs* bullet**: "a bounded d5 descriptor, optionally
   `as-of`'d" → "a bounded d5 source value, optionally `as-of`'d". Not on
   §5's edit list, but leaving "descriptor" in §8 after D3 deleted
   descriptors is exactly the defect-relocation the plan's methodology
   section warns about. Two words.
3. **The status paragraph's first migration draft** matched the
   deleted-name sweep (`schema/published`); reworded to "the schema-typed
   published coordinate" before any verification ran.

## Invariants honored

D4's limit is pinned, not fixed: `:blocked`/`:ended` accepted, the
evicted-prefix test asserts both values stay visible because the
vocabulary was evicted, and the comment names it a documented limit.
V14 pins ownership by the counter — zero closes asserted before the
owner's close, exactly one after — not by re-querying. The closure greps
were run as measurements; every number landed on target except the
deleted-name sweep's plan-file matches, reported above with their reason.

## Left owing

Nothing in this phase's boundary. `dao.space.*` is entirely on v2; what
still holds v1 elsewhere is §7 of the plan's table (and now
`dao.stream.md`'s list), each row under its own plan. The plan file and
the orchestrator log were not touched. Nothing staged or committed.

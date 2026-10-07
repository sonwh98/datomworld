Completed-GMT: 2026-09-09 17:16:49 GMT
Completed-Local: 2026-09-10 00:16:49 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: b71838c3-58ae-434b-bbcd-73418c7af768

# dao.space.schema Phase 1 — the write side on dao.stream

Spec: `docs/design/dao.space.schema.implementation-plan.md` §4, at `4b9f0e7`.
Nothing staged, nothing committed. Files touched, exactly the Ownership
list: `src/cljc/dao/space/schema.cljc`, `test/dao/space/schema_test.cljc`,
`docs/design/dao.space.schema.md`, `docs/design/dao.space.transactor.md`.

## Built (schema.cljc)

- **`transactor` returns the D1 map**: `{:dao.space.schema/transactor true,
  :inner, :local-stream, :strict?, :state (atom (assoc (rows->state …)
  :closed false))}`. Docstring states ownership of the inner value, the
  wrapper's own flag, and the absence of `closed?`.
- **`transact!`**: empty-`tx-data` throw moved above the lock (D1 step 1);
  under the lock `(if (:closed @lock) {:dao.stream/outcome
  :dao.stream/closed} …)` first; the success branch returns `result` — the
  inner receipt — unchanged; the non-ok branch unchanged. `^SchemaWrapper`
  hints and `.-inner`/`.-local-stream`/`.-strict?` became keyword access
  (D8). Docstring rewritten on the v2 receipt and the closed-precedence
  rule.
- **`close!`** (new public fn): under the lock, `swap!` `:closed true`,
  `tx/close!` on `:inner`, returns `{:dao.stream/outcome :dao.stream/ok}`.
- **`publish!`**: `(tx/publish! (:inner wrapper) opts))` — no guard, no
  lock (D7). Docstring states publication-after-close.

## Deleted (schema.cljc)

`SchemaWrapper` with its `ds/IDaoStreamBound` implementation; the
`{:result :ok :t … :datoms …}` re-wrap; `publish!`'s closed guard and lock;
`transact!`'s closed throw. The `[dao.stream :as ds]` require and
`:require-macros` stay, as instructed. No reader conditionals added.

## Tests (schema_test.cljc)

- `[dao.space.transactor :as tx]` added to the requires.
- **60** wrapper `(ds/close! …)` → `(schema/close! …)`; the 61st died with
  the race test; `open-closed`'s `:307` and the RecordingStream's `:621`
  stay `ds/` (Phase 2's), verified byte-identical to HEAD after the
  bulk replace was reverted there.
- **Receipt sites: 24 rewritten, not the plan's 22.** The plan's §0.4 list
  of 24 sites missed two multi-line assertion openers whose `(:result`
  sits alone on its line: W45 `retract-frees-unique-value` and W50
  `schema-retractions-update-live-and-reopened-wrappers`. Same mechanical
  class (§2.4's rule), both pass; leaving them would have failed the suite,
  since `(:result …)` is nil in the v2 receipt. (Also: the plan's `:1940`
  site is the `(:datoms result)` line at tree line `:1941` — same site,
  off-by-one citation.) All 24 follow the rule: `(:result r)` →
  `(:dao.stream/outcome r)` against `:dao.stream/ok`; `(:t r)` →
  `(:dao.space/t r)`; `(:datoms r)` → `(:dao.space/datoms r)`; the `:1071`
  literal became `{:dao.stream/outcome :dao.stream/ok :dao.space/t 1
  :dao.space/datoms [[7 :person/name "Alice" 1 1]]}`.
- `wrapper-state` → `@(:state w)`, no reader conditional (D8).
- `:708` `(is (not (ds/closed? w)))` deleted (D2).
- `closed-wrapper-throws` → `closed-wrapper-answers-closed`: asserts the
  `closed` outcome map; the local-stream `blocked` assertion kept
  verbatim (L2).
- `publish-serializes-against-close` deleted with its `slow-local` reify,
  the `{:woke []}` pin and its JVM-only `#?(:clj …)` wrapper (D7, L9).
- **New** `close-is-idempotent-and-closes-the-inner-value` (L1, L2): two
  `close!`s answer `ok`; `(tx/transact! (:inner w) [[7 :x 1]])` answers
  `closed` — the direct inner-value pin, kept as instructed; the wrapper
  answers `closed`; the local stream still `blocked`s at `newest`.
- **New** `closed-precedence-matches-the-transactor` (L10, D1): after
  `close!`, `(schema/transact! w [])` throws `#"at least one"`;
  `(schema/transact! w [[:db/add 30 :db/foo :bar]])` answers `closed` (not
  the "unknown :db/*" throw); the same two calls on `(:inner w)` throw and
  answer `closed` — the alignment asserted, not assumed. The throw
  assertions use the existing exception-type idiom
  (`#?(:cljs js/Error :cljd Object :default Exception)`), per the host
  matrix's "existing idiom" row.
- **New** `publish-after-close-reads-the-callers-stream` (D7): transact,
  close, publish; the manifest's `:count` equals
  `(count (datoms-of local))`.
- Deftest count 70 → **72** (one deleted, three added; the rename is not a
  count change).

### T19, stated as instructed

`failed-inner-append-leaves-wrapper-state-unchanged` passes with only
`:1062` and `:1071` changed **plus one assertion-message string**: the
third assertion's trailing prose said "the successful receipt keeps
schema's v1 public shape (D10)" — factually false the moment the D10
paragraph was deleted, so it now reads "the receipt is the inner
transactor's, unchanged". No assertion form, structure, or expected value
in that deftest changed otherwise; the string is an `is` message argument
and cannot affect the result. Flagging it because the brief said to.

## Docs

- `dao.space.schema.md` §3.1: ownership paragraph rewritten (v2 close
  outcome, no `closed?` with the transactor's staleness reason, closed
  answers as data with the D1 precedence stated as the transactor's own
  rule adopted); the D10 paragraph deleted and replaced by the one
  sentence on the unchanged receipt; D7's publication-after-close
  sentence added; §7's usage block shows v2 receipt comments and gains
  `(schema/close! log)` with its outcome.
- `dao.space.transactor.md`: T20 retired with the specified wording; the
  *Open items* bullet on schema's mixed return deleted. The
  *Related documents* schema line is Phase 2's edit, left alone.

## Verification — commands, outcomes, counts

- `clojure -M:test` → **Ran 1436 tests containing 165344 assertions.
  0 failures, 0 errors.** `Testing dao.space.schema-test` present.
- `bb test:cljs` → exit 0. **Ran 1346 tests containing 34917 assertions.
  0 failures, 1 errors.** `Testing dao.space.schema-test` appears in the
  Node output. The one error is `yin.vm.telemetry-test/
  wasm-eval-emits-telemetry-test` (`ReferenceError: wasm is not defined`)
  — pre-existing, documented as such in the transactor phase-1, phase-2
  and index phase-3 findings of this sweep; that namespace is untouched
  here.
- `bb test:cljd` → exit 0, **"All tests passed!"** (+1299).
  `test/cljd-out/dao/space/schema-test_test.dart` built; 69 schema-test
  entries in the runner output, including all three new deftests.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` →
  `[:demo] Build completed. (212 files, 1 compiled, 0 warnings, 2.93s)`.
- `grep -c "ds/" src/cljc/dao/space/schema.cljc` → **14** (from 16: the
  protocol declaration and `ds/closed?` went; nothing else).
- `grep -c "ds/" test/dao/space/schema_test.cljc` → **6** — the read-side
  inventory (`open-closed`'s `ds/open!`/`ds/append!`/`ds/close!`, the
  `ds/strict-vec` prose line, the RecordingStream `ds/close!`, W40's
  `ds/open!`), all at their HEAD content.
- Deftest count → **72**.
- `clj -M:kondo --lint src/cljc/dao/space/schema.cljc` (test file linted
  too) → **0 errors, 0 warnings**; five info-level "Redundant ignore"
  notes — four in schema.cljc on the ignore forms Phase 2 deletes,
  documented as pre-existing (cache-cleared) in the index phase-3
  findings, and one on the test's `open-closed` ignore, a form
  byte-identical to HEAD. Not acted on.
- cljstyle not run: outside this brief's verification list and
  permission-restricted in this session.

## Invariants

T19 honored (pin passes; see the string caveat above). The
closed-precedence rule is the transactor's, asserted as an alignment
against `(:inner w)`, not a schema-specific order. The idempotence test
keeps the direct `(tx/transact! (:inner w) …)` assertion. The plan's
reviewer check holds: the test diff filtered to `close!`/`:result`/
`(:t `/`:datoms`/`:dao.stream/`/`:dao.space/` accounts for every changed
line outside the deftests §4 names (plus the `tx` require, which §4 also
names).

## Left owing

Nothing inside Phase 1's boundary. Phase 2 (the read side) is untouched
and remains: the 14 `ds/` lines in `schema.cljc` and 6 in the test are
its exact inventory; `current`, `interpret-view`, both `defopen`s,
`PublishedSchemaRows`, `published`, `schema_fixtures` and the four
kondo-ignore forms are all still present, per the phase split. The plan
file and the orchestrator log were not touched. Nothing staged or
committed.

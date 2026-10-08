Completed-GMT: 2026-09-09 12:21 GMT
Completed-Local: 2026-09-09 19:21 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f

# Phase 2 complete — the swap

Branch `dao.stream-redesign-v2` at `e467687` (Phase 1), tree clean before
the edit. Phase 2 of the r5 plan (§4) only, landed as one coherent change:
the tree did not compile between the `transactor.cljc` rewrite and the test
migrations, as the plan promised. 14 files modified + 1 new doc; nothing
staged, nothing committed.

## What was done

### `src/cljc/dao/space/transactor.cljc` — rewritten (§4.1)

- `DaoStreamLog`, `ds/defopen :transactor`, the `[dao.stream :as ds]`
  require and the `#?(:cljs (:require-macros [dao.stream]))` are all
  deleted. The namespace now requires exactly `dao.datom`,
  `dao.space.index`, `dao.stream` — the §6 end condition for this
  namespace, verified by grep (all remaining `dao.stream` mentions are
  `:dao.stream/…` outcome keywords, `dao.stream`, or prose).
- `create!` per D2: rejects `:next-t`; requires a `stream/reader?`-and-
  `stream/writer?` local stream; requires a non-empty pool of
  `stream/writer?` members (the v2 check moved verbatim, message keeps
  `IDaoStreamWriter` since that is the protocol `stream/writer?` gates);
  derives `t` via `derive-next-t (index/snapshot-datoms …)`; returns
  `{:dao.space/transactor true :local-stream … :intake-pool … :name …
  :next-t (atom t) :state (atom {:closed false})}` — the value or a throw.
  **No retention check** (T18's declaration lives in docstrings and docs).
- `append-packet!` per D3: `stream/append!` → `stream/valid-outcome?` before
  any interpretation → advance watermark and answer the v2 receipt
  `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}`
  on ok; return the conforming non-ok answer as data with the watermark
  unchanged; fold a non-outcome answer to `:dao.stream/transport-error`
  with the raw answer under `:dao.stream/answer`.
- `append!`/`transact!` are plain functions over the value, keeping
  `with-write-lock`, `val->datoms`, `entity->datoms`, `pad-datom` and
  `derive-next-t` verbatim. The closed check answers
  `{:dao.stream/outcome :dao.stream/closed}` as data (T14's replacement);
  the argument-defect throws (no `:db/id`, explicit `t`, empty `tx-data`,
  zero datoms) all survive.
- `close!` sets `:closed` under the write lock and answers
  `{:dao.stream/outcome :dao.stream/ok}`; idempotent. `publish!` reads
  `(:local-stream log)`/`(:intake-pool log)`. No `next`, no `closed?`.

### `src/cljc/dao/space/index.cljc` — snapshot-datoms (§4.2/D6)

- Private `checked` added: `stream/validate-outcome` on the mint and on
  **every** read, throwing "malformed local stream result" carrying
  `{:operation :result :defect}` — validate-before-interpret, so a
  malformed `ok` with no cursor throws instead of recurring on nil
  (r4/P1-1; §4.6 #2's spin case is converted to a throw).
- `snapshot-datoms` is D6's loop in full: `stream/cursor … :dao.stream/oldest`
  mint (non-ok mint throws "local stream refused an :oldest cursor"),
  `case` over `ok` (incremental `element-datoms` inside the loop — S8
  preserved), `(:dao.stream/blocked :dao.stream/end)` finish at the tail,
  one totality throw ("local stream is not a complete-retention transport")
  for a well-formed unexpected outcome. **No gap branch** (S5 deleted).
  No `observe` require. D6's docstring used verbatim.
- `publish-index!`'s docstring sentence "a retention gap throws before
  emission" replaced with the S9 statement (complete-retention transport
  required; snapshot completes before emission). `publish-index!`'s
  signature and body are otherwise untouched. The `ds` require survives
  for the published adapter only (D7: :347/:358/:383 are the remaining
  `ds/` sites, all Phase 3's).

### `src/cljc/dao/space/schema.cljc` — the seven forced edits (§4.3/D8/D10)

1. `:965` `ds/open! {:dao.stream/type :transactor …}` → `tx/create!` over a
   plain spec.
2. `SchemaWrapper.close!` sets its own `:closed` **and** calls `tx/close!`
   under the wrapper lock, returning `{:woke []}` (D10 re-wrap).
3. `SchemaWrapper.closed?` reads `(:closed @state)` — the wrapper owns the
   flag, since the transactor no longer has `closed?`.
4. `transact!` installs `next-state` **only on `:dao.stream/ok`** (T19) and
   re-wraps the ok receipt to `{:result :ok :t t :datoms ds}` (D10/T20); a
   conforming non-ok outcome is returned unchanged. **No `schema_test`
   assertion on a successful `transact!` changed** — the full diff of
   schema_test contains zero edits to existing `:result`/`:t`/`:datoms`
   assertions, and W38–W41 ran unmodified.
5. `publish!`'s closedness guard moved under the wrapper lock, serialized
   with `tx/publish!` — the lock is now held across an index build, as the
   plan prices in.
6. `:926`/`:969` `index/snapshot-datoms` sites unchanged; the handle is now
   a memory-log.
7. `schema/transactor`'s `local-stream` callers pass memory-log handles
   (test-side change, below).

Explicitly untouched, verified: `ds/defopen :dao.space.schema/current` and
its opener, `ds/strict-vec` at :260, `ds/realization?`, and
`schema_test:304`'s v1 realization fixture.

### Tests (§4.4)

**`transactor_test.cljc`** — every `ds/` gone (135 → 0). The five doubles
migrated (`ReaderOnlyStream`/`WriterOnlyStream` now v1-surface negatives
against `create!`'s v2 surface check; `RecordingAppendStream`/
`FailingAppendStream`/`ThrowingAppendStream` are v2 reader+writer wrappers
over a memory-log); the two JVM `reify` doubles (concurrency slow-local,
close-linearization) likewise; all 22 `{:dao.stream/type :ringbuffer}`
local opens became `memory-log/create!`; `descriptor-validation` became
`spec-validation`; `tx-ts` and every `ds/->seq` became one `stream-values`
loop; reads in `readers-observe-atomic-transaction-records` go through
`local` (T12); the gap sub-case of
`malformed-or-gapped-retained-history-throws` deleted (§7), the deftest
renamed `malformed-retained-history-throws` with the three malformed
sub-cases kept; `append-failure-does-not-advance-t-and-can-retry` now
observes the returned `{:dao.stream/outcome :dao.stream/full}` instead of a
catch; `local-still-open?` (blocked-vs-end at the tail) replaces every
`ds/closed? local`-class assertion, v2 having no closed? predicate. All 14
`{:result :ok …}` receipt assertions migrated to the v2 receipt — T15
pinned. T14's replacement is asserted twice (append! and transact! after
close answer `{:dao.stream/outcome :dao.stream/closed}`). While migrating
`ThrowingAppendStream` I found and fixed a latent v1 bug: its success
branch called `(ds/append! inner val)` with `val` unbound — resolving to
the clojure.core function — so the retry appended `#'clojure.core/val` to
the stream instead of the packet; no v1 assertion observed the stream
contents, so it was invisible. The double now appends the actual value.

**`index_test.cljc`** — `MalformedResultStream` is a v2 reader (conforming
`ok` cursor mint, configured result from every `next`); `open-local` is a
memory-log; `publish-index-gap-local-stream-throws-before-emission` deleted
(§7). The malformed-result deftest keeps its two existing sub-cases and
adds three: a repeated `ok` carrying `:dao.stream/value` but no
`:dao.stream/cursor` (throws "malformed", does not spin), a **conforming**
`gap` (outcome + required cursor) and a conforming `cursor-mismatch`, both
hitting the totality throw `#"complete-retention"` — S10's only pins now
the gap test is gone (F4). `stream-values` (:122) and the :777 carrier were
**not** migrated: their only callers are the published-adapter deftests
Phase 3 removes/moves (§5.4 #6–#8), so migrating them this phase would have
broken v1 tests that survive until Phase 3. `index_test`'s `ds/` count is
therefore 35 (41 − open-local ×2 − the double's protocol + its comment −
the gap fixture ×2), on the path to 0 at Phase 3.

**`stigmergy_test.clj`** — `:103` the agent local is a memory-log; `:106`
the transactor is `transactor/create!`; `:118` entity-id allocation counts
via a `local-values` v2 loop; the `[dao.stream.ringbuffer]` require removed
with the `:103` migration (the adversarial review's lint-only nit). The
`[dao.stream :as ds]` require stays — `sources`' three sites (:188-190)
are Phase 3's (§5.4 #9). Two comments naming the dead `:transactor` type
tidied.

**`query_test.cljc`** — `open-local` is a memory-log; the `[dao.stream :as
ds]`, `[dao.stream.ringbuffer]` requires and the cljs `require-macros`
removed with the last two `ds/` sites (2 → 0).

**`schema_test.cljc`** — `fresh-streams`' local is a memory-log (intake
stays a v2 ringbuffer); `closed-wrapper-throws`'s `(ds/closed? local)`
replaced with the blocked-at-tail observable (v1 `closed?` on a memory-log
would throw — no protocol impl); `reified-metadata-reference-seeds-wrapper-state`
appends via `stream/append!`. Two new deftests:

- `failed-inner-append-leaves-wrapper-state-unchanged` (§4.6 #3, T19/T20) —
  a memory-log-backed writer double refusing exactly the second append
  (bootstrap passes, first data tx gets `full`, retry passes): the returned
  value is the conforming `{:dao.stream/outcome :dao.stream/full}`;
  `(wrapper-state w)` is `=` to the pre-failure state; the retry's receipt
  is exactly `{:result :ok, :t 1, :datoms [[7 :person/name "Alice" 1 1]]}`
  — same `t` the refused attempt planned, v1 shape intact (D10); state
  advances only on the success.
- `publish-serializes-against-close` (§4.6 #4, T8's lock extension,
  JVM-only) — a slow-reader double blocks the publication inside its
  snapshot under the wrapper lock; `close!` derefs to `::timeout` while the
  publication is in flight (it cannot return around it); after release the
  in-flight publication completes with the full history, `close!` returns
  `{:woke []}`, and a publish started after the close returns throws
  `#"closed"`.

### Docs (§4.7) — all seven

- **New `docs/design/dao.space.transactor.md`** (the permanent record;
  owner's call was its own doc, following the `dao.space.index.md`
  pattern): D1's value framing and sample, the T1–T11/T15/T16/T18–T20
  table, D3's outcome table, T18 in full (required transport by name,
  detectable-but-deliberately-unchecked with the coupling reason, and the
  sentence "a reader/writer surface check does not establish retention"),
  where durability lives (never claiming the local stream is durable), and
  D4's O(history) replay as an Open item with the one-truth checkpoint
  direction. Linked from `dao.space.md` and `dao.space.index.md` related
  lists.
- `dao.space.index.md` — *The snapshot* restated on declared retention
  (S1/S8/S9/S10, validate-outcome, totality throw; the gap sentence
  deleted); the agent-transactor loop sample is `memory-log/create!` +
  `transactor/create!`; §8's placement in both *The snapshot* and the loop
  section.
- `dao.space.md` — :390's "The local stream is the durable record" replaced
  by D4's corrected statement (authoritative for process lifetime; the
  durable record is what publication puts in dao.jing; a durable stream
  transport is not the fix, with the query.md pointer); the :476 descriptor
  sentence now names the transactor value; the write-path sample (:509)
  fully rewired (memory-log local, v2 intake + observer-state entry,
  `transactor/create!`/`append!`); §8's placement beside the sample.
- `dao.space.schema.md` — :383 becomes the transactor value + wrapper-owned
  closedness + install-only-on-ok; D10's rule recorded as its own
  paragraph so schema's own plan knows what it inherits.
- `dao.space.stigmergy.md` — step :38's "durable" claim corrected per D4;
  §2 (the implemented write path) and the minimum-stack step 2 rewired on
  memory-log + `transactor/create!`/`append!`/`transact!`.
- ADR 0003 — an **amendment note** (2026-09-09), decision untouched:
  DaoStreamLog is gone and the deposit target is `transactor/append!`, a
  plain function on a value; the writer-face precondition is now met for
  operational outcomes (the exception's second trigger remains open, so the
  exception stands); the retention bullet gets its §8 pointer (the local
  log excludes both `full` and `gap`; completeness comes from the
  declaration).
- `dao.stream.md` — consumer list drops `transactor`: "`dao.space` (index
  and schema — `query` and `transactor` migrated, …)".

## Verification — exact commands and counts

| lane | command | result |
|---|---|---|
| clj | `bb test:clj` | **Ran 1433 tests containing 165353 assertions. 0 failures, 0 errors.** (1432/165341 at Phase 1; +1 net deftest: 2 added in schema_test, 1 gap deftest deleted in index_test) |
| cljs | `bb test:cljs` | **Ran 1343 tests containing 34920 assertions. 0 failures, 1 errors.** `Testing dao.space.transactor-test`, `…index-test`, `…schema-test` all present and green in the node output (verified by grep) |
| cljd | `bb test:cljd` (built `build/slice-peer` + `build/yin-repl-peer`, then `clojure -M:cljd test`) | **All tests passed!** (+1296; +1 vs Phase 1's +1295). All 14 transactor-test deftests and the three new schema deftests enumerated in the run output |
| demo | `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **Build completed. (212 files, 12 compiled, 0 warnings, 3.46s)** |
| kondo | `clj -M:kondo --lint` on all 8 touched code files | **0 errors, 4 warnings, 5 infos — every one pre-existing.** `index.cljc:347/:360` (PublishedIndexStream, Phase 3 deletes it) and `schema.cljc:947` / `stigmergy_test.clj:71` verified present at HEAD by stdin-linting `git show HEAD:…`. Net improvement: old `transactor.cljc` carried 3 warnings (Missing protocol method: cursor; Unresolved close! ×2-class), the rewrite carries 0; the Phase-1 `index_test.cljc:30` warning is gone with the double's migration |
| format | `mise exec -- cljstyle fix <same 8 files>` then `cljstyle check` | fix reformatted 3 test files (alignment only); check exits clean. Both clj and cljs and cljd re-run **after** the reformat — the counts above are post-format |

The one cljs error is the pre-existing `wasm-eval-emits-telemetry-test`
(`ReferenceError: wasm is not defined`, `telemetry_test.cljc:101-109`),
documented in Phase 1's findings as baked into base history (`78b5262`);
this phase does not touch that file. No hang anywhere: the suite runs in
its normal time, so the D6 spin regression the plan warned about is
converted to a throw as required (and pinned by the no-cursor sub-case).

## Residue greps (the closure criteria)

```
$ grep -c "ds/" test/dao/space/transactor_test.cljc   0   (was 135 → Phase 2 criterion met)
$ grep -c "ds/" test/dao/space/stigmergy_test.clj     3   (was 6 → Phase 2 criterion met;
                                                            remaining = sources :188-190, Phase 3)
$ grep -c "ds/" test/dao/space/query_test.cljc        0   (was 2)
$ grep -c "ds/" test/dao/space/index_test.cljc       35   (was 41; 0 is the Phase 3 criterion)
```

The three remaining stigmergy sites are exactly `sources`' `ds/open!` /
`ds/strict-vec` / `ds/close!` (§5.4 #9). `src` side: `grep -n "ds/"
src/cljc/dao/space/index.cljc` returns exactly D7's three Phase-3 rows
(:347, :358, :383); `transactor.cljc` returns nothing (the one grep hit at
:48 is the substring in "appends/transacts"). `transactor.cljc` requires
exactly `dao.datom`, `dao.space.index`, `dao.stream`.

## Plan invariants — all honoured, none owed a deviation

- D3's table implemented row for row, including the non-outcome fold to
  `transport-error` with `:dao.stream/answer` retained (a row no test
  exercises on a real transport — noted below under left owing).
- D6 verbatim, `checked` on mint and every read, incremental flattening,
  totality throw kept and now pinned (F4).
- D8's seven edits and D10's enumeration followed; the enumeration was
  re-derived, not trusted: `publish!` forwarding `{:manifest-address …
  :manifest …}` unchanged and `SchemaWrapper.closed?` reading its own flag
  were checked against the tree and matched the plan's table; no fifth
  forwarding surface exists.
- §7's deletions done with the F4 distinction kept: transport fixtures for
  excluded outcomes gone, consumer-side doubles proving the precondition
  checks added.
- §8's four placements written, each naming `memory-log` and carrying the
  verbatim sentence that a reader/writer surface check does not establish
  retention.

## Left owing (none of it Phase 2 scope)

- **Phase 3** (§5): the published adapter's `ds/defopen`/`PublishedIndexStream`
  deletion, the eleven adapter read paths' migration (including
  `stigmergy_test`'s `sources`), `index_test`'s remaining 35 `ds/` sites,
  `stream-values` (:122) and the :777 v2-ringbuffer carrier, the P5
  close-exactly-once test, and the three Phase-3 doc edits (§5.7).
- **Untested branch, inherited not introduced:** D3's non-outcome fold
  (`append-packet!` folding a malformed local answer to
  `:dao.stream/transport-error` + `:dao.stream/answer`) has no test — no
  double in the suite answers a non-map from `append!`. The plan's §4.6
  lists no test for it either; adding one would be a one-line double
  (`append!` answering `:boom`) if the owner wants the row pinned.
- The `bb test:clj`-time suite has no per-test timeout configured; the D6
  spin guard is pinned by the no-cursor double, but a future regression
  would hang rather than fail (the plan's §10 note, restated for whoever
  wires the runner).

Nothing was staged or committed.

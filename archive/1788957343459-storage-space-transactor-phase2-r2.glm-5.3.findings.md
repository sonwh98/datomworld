Completed-GMT: 2026-09-09 12:50 GMT
Completed-Local: 2026-09-09 19:50 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f

# Phase 2 r2 corrections complete

Both reviews' findings applied on top of Phase 2. Code changes were limited
to Finding 2's five-line guard and Finding 3's double + two tests; nothing
else in the implementation was touched. Nothing staged, nothing committed.

**Process note:** the first full `bb test:clj` re-run was killed by the host
(exit 137) after the targeted namespace run had already passed; per the
brief's instruction a partial findings file was written before resuming, and
the retry plus the other three lanes all completed. The partial file is
replaced by this one.

## P1 (blocking, documentation) — all five passages fixed

`docs/design/dao.space.md`:

- **Fault Tolerance rewritten** around the actual two-stage model: atomicity
  (one atomic transaction record per append; manifest last, so a crash can
  leave a prefix, never a torn transaction or partial manifest); un-published
  memory-log contents **do not survive process failure** and durability
  begins at publication in `dao.jing`; a tailing reader is told
  `:dao.stream/blocked` rather than failing; recovery after restart starts
  from published state (manifest addresses the agent names), interrupted
  publication is idempotent by content addressing, and the causality-carrying
  checkpoint is named as future work pointing at
  `dao.space.transactor.md`'s Open items — not designed here. The persistent-
  file claim, the file-reopen recovery, the `ds/next` mention and the
  `:daostream/gap` claim are all deleted.
- `:182` — "q returns a closed bounded result DaoStream" → "q returns a
  bounded result value; nothing is opened or closed on the caller's
  behalf" (`collect` materializes, as the next line already said).
- Worker-loop intro — now "writes with `transactor/append!`", matching the
  example below it.

`docs/design/dao.space.schema.md:246-248` — the inner object is now "the
transactor created by `transactor/create!`, over which the wrapper is the
single writer"; no registered `:transactor` stream wrapper.

`docs/dao.space.stigmergy.md:3-6` — stigmergy is now defined as writing
"via `dao.space.transactor`'s `append!`/`transact!` (one atomic transaction
record per call)"; no `ds/append!`.

Verified by grep across the phase's five design docs:
`ds/append!|ds/next|:daostream/gap|:transactor stream|transactor stream
wrapper` → nothing.

## Finding 1 — plan §4.5/§5.5 corrected

`collab/1788950282826-architect-space-transactor-v2-plan-r5.…findings.md`,
both edits marked **"Corrected (2026-09-09, phase 2 r2)"** inline so the
change is traceable rather than silent:

- §4.5's carrier bullet now states the carrier migrates in **Phase 3**, not
  Phase 2 — its only caller is
  `published-index-is-a-transportable-bounded-stream`, one of the adapter
  deftests §5.4 #8 moves.
- §5.5 now assigns Phase 2 `open-local` (:68) + `MalformedResultStream`
  (:25) + the gap-fixture deletion only, and assigns `stream-values`
  (:122) + the carrier (:777) to Phase 3 with the four published-adapter
  deftests (§5.4 #3/#4/#7/#8) that are their only callers, recording that
  the original assignment would have pointed a v2 read loop at a v1
  `PublishedIndexStream` with no `cursor`. Phase 2 lands at 35.

## Finding 2 — `create!` non-map spec guard

`transactor.cljc`: `(when-not (map? spec) (throw (ex-info "transactor spec
must be a map" {:spec spec})))` now runs before the `:next-t` `contains?`,
so `(transactor/create! 42)` answers D2's clean malformed-spec throw instead
of a raw `IllegalArgumentException`. `spec-validation` gains the sub-case
asserting `#"must be a map"` on `(transactor/create! 42)`.

## Finding 3 — the non-outcome fold is pinned

New double `NonOutcomeAppendStream` (v2 reader+writer over a memory-log;
first `append!` answers the non-outcome `:boom`, then delegates). New
deftest `append-folds-a-non-outcome-answer-to-transport-error`:

- asserts the **exact folded result**
  `{:dao.stream/outcome :dao.stream/transport-error :dao.stream/answer
  :boom}` — the raw answer retained, as D3's table requires; and
- asserts the retry commits at the original `t 0` with the full v2 receipt —
  the fold did not advance the watermark.

That was the last untested branch in `append-packet!`: ok (14 migrated
assertions), conforming non-ok (`FailingAppendStream`'s `full`), throw
(`ThrowingAppendStream`), and now the non-outcome fold, are all pinned.

## Verification — exact commands and counts

| lane | command | result |
|---|---|---|
| clj | `bb test:clj` | **Ran 1434 tests containing 165356 assertions. 0 failures, 0 errors.** (Phase 2: 1433/165353; +1 deftest, +3 assertions = the fold test) |
| cljs | `bb test:cljs` | **Ran 1344 tests containing 34923 assertions. 0 failures, 1 errors.** `Testing dao.space.transactor-test` and `…schema-test` present and green; the 1 error is the pre-existing `wasm-eval-emits-telemetry-test` (`ReferenceError: wasm is not defined`), in base history since `78b5262`, untouched by this phase |
| cljd | `bb test:cljd` (built `build/slice-peer` + `build/yin-repl-peer`, then `clojure -M:cljd test`) | **All tests passed!** (+1297; Phase 2 was +1296, +1 for the fold test) |
| demo | `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **Build completed. (212 files, 1 compiled, 0 warnings, 2.37s)** |
| kondo | `clj -M:kondo --lint src/cljc/dao/space/transactor.cljc test/dao/space/transactor_test.cljc` | **0 errors, 0 warnings** — see the cache note below |
| format | `mise exec -- cljstyle fix` then `check` on the two code files | fix changed nothing (the r2 edits were already conformant); check exits clean |

Targeted pre-lane check after the edits: `clojure -M:test -n
dao.space.transactor-test` → 16 tests, 100 assertions, 0 failures, 0 errors.

### Kondo cache note (own-goal, diagnosed and cleared)

The first r2 kondo run reported two bogus arity errors ("append-packet!
called with 4 args but expects 5"). Cause: Phase 2's baseline verification
had piped `git show HEAD:src/cljc/dao/space/transactor.cljc` into
`clj -M:kondo --lint -`, and kondo cached that analysis under the real
namespace — the `clj`-branch cache entry still carried the v1 5-param
`append-packet!` and `DaoStreamLog`, so the current 4-param/4-arg file
resolved against the stale entry. The cache (gitignored,
`.clj-kondo/.cache`) was cleared and the lint re-run clean, as reported
above. Phase 2's reported kondo result stands — its 8-file run preceded the
poisoning. Lesson recorded: never stdin-lint an old version of a file that
shares a namespace with the live tree without clearing the cache after.

## Residue greps (unchanged by r2, re-confirmed)

```
$ grep -c "ds/" test/dao/space/transactor_test.cljc   0
$ grep -c "ds/" test/dao/space/stigmergy_test.clj     3   (sources — Phase 3)
$ grep -c "ds/" test/dao/space/query_test.cljc        0
$ grep -c "ds/" test/dao/space/index_test.cljc       35   (0 is Phase 3's criterion)
```

Nothing was staged or committed.

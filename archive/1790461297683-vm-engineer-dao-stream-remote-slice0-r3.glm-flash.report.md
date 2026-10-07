## Slice 0, Round 3 — complete, all three lanes green

Contract executed: `collab/1790461297683-vm-engineer-dao-stream-remote-slice0-r3.prompt.md` (r2 contract plus the dao.jing.cljc scope addition). Exactly the 11 allowed files modified; no commit/stage/checkout/reset/stash; no leftover diagnostics.

### Source changes

- `/Users/sto/workspace/datomworld/src/cljc/dao/stream.cljc:75,86,95` — `:dao.stream/refused` added to `outcomes-cursor`, `outcomes-next`, `outcomes-append`, each between `closed` and `transport-error`, matching the committed dao.stream.md table order.
- `/Users/sto/workspace/datomworld/src/cljc/dao/stream/observe.cljc:128-131` — explicit refused branch answering `{:status :refused ...}` (its own status, not the `:defect` default); the defect trio (`cursor-mismatch`, `invalid-cursor`, `transport-error`) is now an explicit branch (133-141); doc table and writer-outcome enumeration updated; refused falls to `:failed` on the effect side (data, never advances).
- `/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:385-392` — `handle-put` treats refused and any unrecognized outcome as the shaped refusal `{:status :refused, :outcome o, :stream-id id}` (the effect's value, mirroring `poll-link-response`), while `closed`/`invalid-value`/`transport-error` keep their documented v1 throws via an explicit branch. `:448-458` — `handle-next` same shape plus `:cursor-id`. `:329-334` — `make-woken-run-queue-entries` shapes a woken refused status into the identical refusal value; `:1356-1372` — `terminal-resume-outcome` admits `:dao.stream/refused`, keeping the "whether the first attempt blocked must not change that" law true; both docstrings updated.
- `/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:709-716` — the `:refused` clause mirroring `:defect` (`:signal :dao.stream/refused`, `:member i`, `:result read`, cursor unchanged, `:next` moves past); docstrings at 636 ("eight outcomes") and 648-649 (refused in the k enumeration) only.

### Test changes

- Pinned tables widened with actual semantics: `test/dao/stream/observe_test.cljc` (read table gains `:refused`, effect table `:failed`), `test/dao/stream/waitset_test.cljc:190,203` (both plans: refused wakes terminal under its own keyword, advance 0), `test/dao/jing_test.cljc:808` (pool-signals refused row).
- Conformance manifests gain refused as an exclusion with a reason (the in-memory backends compose no policy, so they cannot produce it): `test/dao/stream/ringbuffer_test.cljc:99-113`, `test/dao/stream/memory_log_test.cljc:189-217`, and both stream_test fixtures (`conformance-manifest-validation-test`, `conformance-harness-full-run-test`).
- Exact-set assertions updated in `test/dao/stream_test.cljc:244-264`.
- New tests: `refused-outcome-is-declared-for-cursor-next-append-test` (stream_test.cljc:279), `a-refused-read-is-its-own-refused-status` (observe_test.cljc:76), `refused-append-answers-are-shaped-refusals-test`, `refused-read-answers-are-shaped-refusals-test`, `a-woken-refused-entry-resumes-as-data-test` (engine_test.cljc:158, 225, 468) — pinning the shaped refusal (immediate and woken paths), unrecognized-as-refused, cursor-cell immobility, and that the declared defects still throw.

### Verification (sequential, solo, under mise)

- cljstyle check: exit 0 on all 11 files. clj-kondo: 0 errors; 3 warnings, verified byte-identical on HEAD (pre-existing).
- JVM: `clojure -M:test` — Ran 2,214 tests containing 182,863 assertions. 0 failures, 0 errors.
- Node: `bb test:cljs` (peer built first) — Ran 2,126 tests containing 49,532 assertions. 0 failures, 0 errors.
- Dart: `bb test:cljd` — 2,088 tests, All tests passed!

Note on counts: the measured JVM total is 2,214/182,863 against the brief's quoted baseline of 2,057/180,912; the totals were identical across two full runs of this tree, so the quoted baseline is stale for d81e50ad — the deltas are not from this slice (it adds 5 test vars).

Status: COMPLETE
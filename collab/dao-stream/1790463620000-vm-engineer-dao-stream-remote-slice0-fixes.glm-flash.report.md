## P1-1 — observe.cljc: unrecognized outcome became transport-error/defect instead of refused

- `/Users/sto/workspace/datomworld/src/cljc/dao/stream/observe.cljc:44-63` — `valid-or-transport-error` now classifies via `stream/validate-outcome`: a well-formed outcome map whose keyword lies outside the operation's declared set (`:unauthorized-outcome`, i.e. a newer contract per `docs/design/dao.stream.md:113`) is preserved exactly as it arrived; only truly malformed answers (non-map, missing/unqualified keyword, declared outcome missing required keys) still fold into `transport-error`.
- `observe.cljc:150-159` — the read `case`'s default clause now classifies a preserved unrecognized outcome as `:refused` (cursor unchanged, outcome preserved, raw read retained); `cursor-mismatch`/`invalid-cursor`/`transport-error` keep their explicit `:defect` clause, so malformed stays defect.
- Effect side: an unrecognized write outcome falls into the existing default `:failed` branch (the established refused mapping), with `:outcome` preserved and the raw answer under `:effect`.
- Docstring: intro + status table rewritten (`observe.cljc:67-95`) to add unrecognized to `:refused` (reads) and `:failed` (effects) and keep malformed under `:defect`/`transport-error`.

## P1-2 — engine.cljc:1373 (now 1396): parked :next/:put waking unrecognized threw while the immediate path refused

- `/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:311-344` — new private data + predicate: `wake-error-outcomes` (the declared defects per reason: `cursor-mismatch`/`invalid-cursor`/`transport-error` for `:next`; `closed`/`invalid-value`/`transport-error` for `:put`), `wake-value-statuses` (`nil`/`:ok`/`:end`/`:gap` for a reader, `nil`/`:ok` for a writer), and `wake-refusal?` (refused or any status outside those two sets).
- `engine.cljc:346-373` — `make-woken-run-queue-entries` now shapes every `wake-refusal?` status into the same shaped refusal the immediate `handle-put`/`handle-next` return (`{:status :refused, :outcome <status>, :stream-id id}` plus `:cursor-id` for readers).
- `engine.cljc:1396-1414` — `terminal-resume-outcome` now raises only waitset diagnostics, `:link-refused`, and the per-reason declared error outcomes; unrecognized statuses pass through to the restore as data. Declared error outcomes stay terminal.

## Tests added

- `/Users/sto/workspace/datomworld/test/dao/stream/observe_test.cljc:165` `an-unrecognized-read-is-refused-with-its-own-outcome` (well-formed unknown read outcomes classify `:refused`, preserved, cursor kept); `:192` `an-unrecognized-effect-answer-fails-as-refused-does` (unknown/end-on-append classify `:failed`, preserved). Malformed-answer tests trimmed of the now-refused/mis-set answers.
- `/Users/sto/workspace/datomworld/test/yin/vm/engine_test.cljc:503` `a-woken-unrecognized-entry-resumes-as-data-test` — four blocks: parked reader waking `:dao.stream/quantum-flux` resumes the exact shaped refusal as data; parked writer waking unrecognized refuses in `handle-put`'s shape (no cursor-id/update); declared read and write defects still raise on resume with the immediate path's ex-data.

Also `/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:647-663` — docstring-only correction (no behavior change): `observe-step!`'s signal list now notes that a well-formed outcome outside the contract reports under its own keyword via the refused path.

## Verification

- Static: `cljstyle check` clean on all five files (one indentation nit found and fixed); clj-kondo 0 errors / 0 warnings (only pre-existing info at `engine.cljc:276`, verified present at HEAD); added lines pure ASCII and <= 80 columns; no diagnostics left; only the five permitted files modified; no git state operations.
- Lanes run sequentially, solo:
  - JVM (`clojure -M:test`): Ran 2217 tests containing 182889 assertions. 0 failures, 0 errors. (baseline 2,214/182,863/0)
  - Node (shadow-cljs `slice-peer test` compile + `node target/node-tests.js`): Ran 2129 tests containing 49555 assertions. 0 failures, 0 errors. (baseline 2,126/49,532/0)
  - Dart (`clojure -M:cljd test` after both peer exe builds): +2091: All tests passed! (baseline 2,088)

Status: COMPLETE
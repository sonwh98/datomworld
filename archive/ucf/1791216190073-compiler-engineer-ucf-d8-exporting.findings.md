Completed-GMT: 2026-10-05 16:36:05 GMT
Completed-Local: 2026-10-05 23:36:05 +07
Coding-Agent: claude (claude-sonnet-5-5; the brief asked for opus-5-5 if available)

# D8 report: the exporting state, prepare/encode, the abort rule

## Changed files
- NEW `src/cljc/yin/vm/ucf/holder/export.cljc` (`enter`, `prepare`, `encode`, `abort`)
- NEW `test/yin/vm/ucf/holder/export_test.cljc` (12 tests, 61 assertions)
- `handoff.cljc`: **not changed.** The extraction was not needed (see Design).
- No git writes.

## Design
- `enter machine` answers `{:status :ok :machine m :record r}`. `m` has `:yin.k/gate :exporting`, an empty wait set, and the reachable parked records removed (reachability by the same `completion/complete` walk the lift runs). `r` holds `:wait-set` (in order), `:parked`, `:gate` (the mode to return to) and `:served {}`. Refusals are data outcomes, as `export-task` gives them.
- Refusals at entering:
  - `:yin.k/not-quiescent` for a non-empty ready queue in the root or any install child.
  - `:yin.k/non-portable`, kind `:reason-mismatch`, with `:yin.k/hold` and `:yin.k/path` for: an `:observe` entry; any entry or cell with `:yin.k/held`; an unminted cursor cell; a non-empty `:yin.k/closes`; a `:link-request` entry with no `:cursor`. The check covers the root and every install child.
  - `:yin.k/refused` (`:not-running`) for a machine already `:exporting` or `:ended`.
- Prepare/encode without touching `handoff.cljc`. `prepare` runs `handoff/export-task` over the reinstated machine with a memoizing `serve!` wrapper and stores the table in `record :served`, keyed by stream handle. `encode` runs the same lift with a `serve!` that only reads the table and throws on a miss, so it calls the caller's `serve!` zero times and is deterministic for one record.
- `abort machine record attempts tenure`:
  - `attempts` is the persisted offer-attempt history, one map per attempt with `:append` (the append's outcome).
  - Only `:dao.stream/full` and `:dao.stream/closed` prove "not appended".
  - Abort is legal with no attempts, or when every attempt proves that. An unknown or absent outcome, an `ok`, or a suspension refuses with `:offer-possibly-accepted`. A `:reply :yin.k/refused` is ignored as evidence.
  - A machine carrying `:yin.k/custody` is a holder exporting a successor and needs `tenure {:now n :bound b}` with `n < b`. Otherwise it refuses with `:tenure-ended`, and the old machine is never restored.
  - Success restores the wait set, the parked records and the prior gate.

## Evidence
- Green: `clojure -M:test -n yin.vm.ucf.holder.export-test` gave 12 tests, 61 assertions, 0 failures, 0 errors. Together with `yin.vm.ucf.handoff-test` (unchanged): 36 tests, 225 assertions, 0 failures.
- Test-first honesty: I wrote the implementation draft before the tests, not strictly test-first. Two real failures surfaced and were fixed in the tests: byte arrays compared by identity, and the link-miss machine lacking link streams. For red evidence on the abort rule I mutated `not-appended?` to always true. That gave 7 failures (6 in the refusal test, 1 in the lease-bound test), and I reverted it.
- kondo: `clj -M:kondo` on both files gave 0 errors, 0 warnings. cljstyle was not run; the orchestrator should run it at landing.
- Contract coverage:
  - After exporting, polling, a direct resume and ticking an install child make zero stream calls (counting handles).
  - Prepare serves once per stream, over two streams and over a real install-child machine.
  - Encode makes zero further calls, and two encodes give equal bytes.
  - Abort rows: allowed with no intent; allowed on `full` and `closed`; refused after an unknown append and after a refusal that followed one; refused past the lease bound.
  - Each of the five refusal kinds fires on a real parked machine, plus the child path.
  - A non-empty ready queue gives `:yin.k/not-quiescent`.

## Unresolved concerns
1. The served table is keyed by stream handle. It cannot be journaled as-is: D14 needs resource-id-to-descriptor, so D9 or D14 may need `handoff.cljc` to expose the serve key. That is the extraction I did not make.
2. "Reachable unminted cell" is implemented as any unminted cell in `:resources`. This is conservative and may refuse a dropped cursor that is unreachable.
3. `abort`'s tenure form (`{:now :bound}` in one unit) and the `:offer-possibly-accepted` / `:tenure-ended` reasons are my vocabulary. The driver (D13/D14) must supply the lease clock reading and ledger-confirmed tenure.
4. `prepare` runs a full lift, including encoding, and discards the bytes. Cheap enough now; D9 may want a dedicated prepare.
5. Tests ran on the JVM only. Node and Dart lanes were not run.
6. The `:yin.k/refused` status for "already exporting" is new vocabulary and is not in UCF 7.9.

## Incomplete
Nothing in the brief is left undone. UCF 7.7.4 and the 7.9 amendments were not written.

# Round 2: architect and gate fixes (2026-10-06)

All four fixes applied, tests first. Red run before the code: 15 tests, 80 assertions, 8 failures (3 in the prepare-retry row, 3 in the tenure row, 2 in the widened-outcomes row). The explicit-park rows were green on arrival because they pin existing behaviour.

1. **Prepare keeps what it served (architect must-fix 1).** A refused `prepare` now returns the refusal map plus `:record` with `:served` updated, so a retry never re-serves a stream. The test serves one stream and refuses the other, then retries with the returned record, and the retry asks only for the other stream. Test-driven finding: the caching wrapper also stored a `nil` (refused) answer and so would have blocked the retry. It now stores only non-nil answers, so a refused stream is asked again and a served one never is.
2. **Tenure needs ledger evidence (architect must-fix 2).** `abort` for a machine under custody requires `tenure {:now :bound :live true}`. At or past the bound it answers `:tenure-ended`, checked first. Inside the bound without `:live true` (absent, false or nil tenure) it answers `:tenure-unconfirmed`. Rows: confirmed tenure aborts; the bound, past it, and past it without `:live` give `:tenure-ended`; inside the bound with `:live` absent or false, and nil tenure, give `:tenure-unconfirmed`; confirmed tenure still does not license an uncertain offer.
3. **Provable not-appended set (gate required 1).** `not-appended?` is now `#{full invalid-value closed refused}`. The docstring cites `dao.stream`'s closed append outcome set `{ok full invalid-value closed refused transport-error}` and keeps `transport-error` as unknown delivery. The phantom `:dao.stream/suspended` mention is gone, and the old test row for it became a `transport-error` refusal row. `invalid-value` and `refused` each license abort in the widened-outcomes row.
4. **Explicit park (gate required 2).** New rows: after `enter` on a real explicit-park machine the record holds the parked record and the machine's `:parked` lacks it; prepare and encode still lift it (`:kind :parked`); abort restores machine equality. A second row pins the asymmetry: a parked record placed in an install child stays in the child and is not moved into the record.

## Evidence
- `clojure -M:test -n yin.vm.ucf.holder.export-test -n yin.vm.ucf.handoff-test`: 39 tests, 244 assertions, 0 failures, 0 errors.
- kondo on `export.cljc` and `export_test.cljc`: 0 errors, 0 warnings. cljstyle was not run, and Node and Dart lanes were not run.
- `handoff.cljc` is still unchanged.

## Concerns
Unchanged from round 1, except item 3: the tenure form is now `{:now :bound :live}`, with `:live` supplied by the driver from the ledger reader.

# Step 1 Remediation Findings (r2)

Created-GMT: 2026-11-08 15:05:40 GMT
Created-Local: 2026-11-08 22:05:40 +07
Coding-Agent: muse
Model: muse-wonder-1.0
Session-ID: a1b7a676-f833-4542-9251-5e6a9344d872

Completed-GMT: 2026-11-08 15:04:55 GMT
Completed-Local: 2026-11-08 22:04:55 Asia/Ho_Chi_Minh

Step 1 remediation is complete. All three architect findings are addressed. Changes this session touch only `test/yin/repl/fixture.cljc` and `test/yin/repl/index_test.cljc` on branch `yin-repl-dht-composition` at HEAD `b88bad9dd93d233f1d78e5a111444f6c8d86c9aa`. `src/` is untouched.

## Finding 1 — Publication-Result Protocol
- `fixture.cljc` records each HEAD call as one explicit section 4 result after its outcome is known, with `:op`, minted `:id`/`:round`, `:manifest`, `:status`, and local `:durability`.
- Added `publication-request`, `publication-result`, `publication-result-expectations`, and `accept-result` model covering matching success, refusal, delayed completion, wrong ID, wrong round, wrong manifest, duplicate, and stale delivery.
- Synchronous pins assert explicit records; new oracle test pins the six undeliverable cases as Step 2 oracle.

## Finding 3 — Shell Retry Defect Pin
- New shell acceptance test `a-shell-retrying-a-refused-publication-recovers-and-publishes-next-round-failing-acceptance-reproduction` asserts correct retry behavior and currently fails with the expected signature `(name nil)` error text in `yin.repl.index/notice`.
- Baseline revision, test identifier, command, and signature are documented. Indexer-level oracle remains green; engine fix deferred to Step 2.

## Finding 2 — Invariant Evidence
Focused G0 runs with logs and revision record documented in `/tmp/step1-evidence/`:
- Frontier gate: green (54/42/8/16 assertions)
- Restore Settings: green (12/8/4/5/13 assertions)
- Repair cadence: green (6/3/25 assertions)
- Cross-host parity: green (84/4/13 assertions)
- Lifecycle baseline: green (8/4/6/6/8/7/24 assertions)

Namespace totals:
- `yin.repl.index-test`: 29 tests, 629 assertions, 11 failures (all contained in the known reproduction pin), 0 errors.
- `yin.repl.store-test`: 28 tests, 145 assertions, 0 failures.
- `yin.repl.query-test`: 18 tests, 279 assertions, 0 failures.
- `yin.repl.require-test`: 20 tests, 209 assertions, 0 failures.

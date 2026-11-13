# Exception track — acceptance ledger (living document)

Owner: exception-track orchestrator. Seed: `test/yang/call/exception_r0_results.edn`
(Round 0, commit `0fa3ff7a`, host JVM, backends semantic/stack/register — one row per
(fixture, frontend, backend, VM, host); every Round-0 row holds identically on all three
backends, which is why the seed emits one status per backend per fixture/scenario).

**Rules of record:**
- A round cannot be accepted without its ledger deltas: the round's record + its ledger
  deltas + the kit green on its gates (`EXCEPTION_R0.md`).
- This ledger is append-by-update: each accepted round appends a `## Round deltas`
  section (round id, commit, rows flipped red→green with their new observations) —
  history is never rewritten.
- `error` = original host operational refusal (a harness/operational defect to fix in
  the round, never a passing guest transfer). `red` = condition/transfer mismatch.
- No frontend is conformant on a green subset; the end-gate obligations (12-directed-pair
  matrix, context-lifetime cases, host-by-VM portability matrix, coverage limits in
  `EXCEPTION_R0.md`) remain open beyond these rows.

## Round-0 seed: fixture inventory (47 fixtures × 3 backends = 141 rows)

| Fixture | Frontend | Gates | VM | Status | Host |
| --- | --- | --- | --- | --- | --- |
| `:typescript/fresh-named` | typescript | 1/5 | semantic | red | JVM |
| `:typescript/fresh-named` | typescript | 1/5 | stack | red | JVM |
| `:typescript/fresh-named` | typescript | 1/5 | register | red | JVM |
| `:typescript/fresh-closure` | typescript | 1/5 | semantic | red | JVM |
| `:typescript/fresh-closure` | typescript | 1/5 | stack | red | JVM |
| `:typescript/fresh-closure` | typescript | 1/5 | register | red | JVM |
| `:typescript/protocol-not-callable` | typescript | 3 | semantic | red | JVM |
| `:typescript/protocol-not-callable` | typescript | 3 | stack | red | JVM |
| `:typescript/protocol-not-callable` | typescript | 3 | register | red | JVM |
| `:typescript/protocol-invalid-invocation` | typescript | 3 | semantic | red | JVM |
| `:typescript/protocol-invalid-invocation` | typescript | 3 | stack | red | JVM |
| `:typescript/protocol-invalid-invocation` | typescript | 3 | register | red | JVM |
| `:typescript/protocol-unsupported-binding` | typescript | 3 | semantic | red | JVM |
| `:typescript/protocol-unsupported-binding` | typescript | 3 | stack | red | JVM |
| `:typescript/protocol-unsupported-binding` | typescript | 3 | register | red | JVM |
| `:typescript/saved-protocol-not-callable` | typescript | 3/5 | semantic | red | JVM |
| `:typescript/saved-protocol-not-callable` | typescript | 3/5 | stack | red | JVM |
| `:typescript/saved-protocol-not-callable` | typescript | 3/5 | register | red | JVM |
| `:typescript/saved-protocol-invalid-invocation` | typescript | 3/5 | semantic | red | JVM |
| `:typescript/saved-protocol-invalid-invocation` | typescript | 3/5 | stack | red | JVM |
| `:typescript/saved-protocol-invalid-invocation` | typescript | 3/5 | register | red | JVM |
| `:typescript/saved-protocol-unsupported-binding` | typescript | 3/5 | semantic | red | JVM |
| `:typescript/saved-protocol-unsupported-binding` | typescript | 3/5 | stack | red | JVM |
| `:typescript/saved-protocol-unsupported-binding` | typescript | 3/5 | register | red | JVM |
| `:typescript/shape-return` | typescript | 5 | semantic | green | JVM |
| `:typescript/shape-return` | typescript | 5 | stack | green | JVM |
| `:typescript/shape-return` | typescript | 5 | register | green | JVM |
| `:typescript/shape-throw` | typescript | 5 | semantic | red | JVM |
| `:typescript/shape-throw` | typescript | 5 | stack | red | JVM |
| `:typescript/shape-throw` | typescript | 5 | register | red | JVM |
| `:php/fresh-named` | php | 1/5 | semantic | red | JVM |
| `:php/fresh-named` | php | 1/5 | stack | red | JVM |
| `:php/fresh-named` | php | 1/5 | register | red | JVM |
| `:php/fresh-closure` | php | 1/5 | semantic | red | JVM |
| `:php/fresh-closure` | php | 1/5 | stack | red | JVM |
| `:php/fresh-closure` | php | 1/5 | register | red | JVM |
| `:php/binding` | php | 1 | semantic | red | JVM |
| `:php/binding` | php | 1 | stack | red | JVM |
| `:php/binding` | php | 1 | register | red | JVM |
| `:php/protocol-not-callable` | php | 3 | semantic | red | JVM |
| `:php/protocol-not-callable` | php | 3 | stack | red | JVM |
| `:php/protocol-not-callable` | php | 3 | register | red | JVM |
| `:php/protocol-invalid-invocation` | php | 3 | semantic | error | JVM |
| `:php/protocol-invalid-invocation` | php | 3 | stack | error | JVM |
| `:php/protocol-invalid-invocation` | php | 3 | register | error | JVM |
| `:php/protocol-unsupported-binding` | php | 3 | semantic | error | JVM |
| `:php/protocol-unsupported-binding` | php | 3 | stack | error | JVM |
| `:php/protocol-unsupported-binding` | php | 3 | register | error | JVM |
| `:php/local-reception` | php | 2 | semantic | error | JVM |
| `:php/local-reception` | php | 2 | stack | error | JVM |
| `:php/local-reception` | php | 2 | register | error | JVM |
| `:php/foreign-reception` | php | 2 | semantic | error | JVM |
| `:php/foreign-reception` | php | 2 | stack | error | JVM |
| `:php/foreign-reception` | php | 2 | register | error | JVM |
| `:php/retained-foreign-lifetime` | php | 2/5 | semantic | error | JVM |
| `:php/retained-foreign-lifetime` | php | 2/5 | stack | error | JVM |
| `:php/retained-foreign-lifetime` | php | 2/5 | register | error | JVM |
| `:php/saved-protocol-not-callable` | php | 3/5 | semantic | red | JVM |
| `:php/saved-protocol-not-callable` | php | 3/5 | stack | red | JVM |
| `:php/saved-protocol-not-callable` | php | 3/5 | register | red | JVM |
| `:php/saved-protocol-invalid-invocation` | php | 3/5 | semantic | error | JVM |
| `:php/saved-protocol-invalid-invocation` | php | 3/5 | stack | error | JVM |
| `:php/saved-protocol-invalid-invocation` | php | 3/5 | register | error | JVM |
| `:php/saved-protocol-unsupported-binding` | php | 3/5 | semantic | error | JVM |
| `:php/saved-protocol-unsupported-binding` | php | 3/5 | stack | error | JVM |
| `:php/saved-protocol-unsupported-binding` | php | 3/5 | register | error | JVM |
| `:php/forged-retention` | php | 5 | semantic | red | JVM |
| `:php/forged-retention` | php | 5 | stack | red | JVM |
| `:php/forged-retention` | php | 5 | register | red | JVM |
| `:clojure/fresh-named` | clojure | 1/5 | semantic | green | JVM |
| `:clojure/fresh-named` | clojure | 1/5 | stack | green | JVM |
| `:clojure/fresh-named` | clojure | 1/5 | register | green | JVM |
| `:clojure/fresh-closure` | clojure | 1/5 | semantic | green | JVM |
| `:clojure/fresh-closure` | clojure | 1/5 | stack | green | JVM |
| `:clojure/fresh-closure` | clojure | 1/5 | register | green | JVM |
| `:clojure/binding` | clojure | 1 | semantic | green | JVM |
| `:clojure/binding` | clojure | 1 | stack | green | JVM |
| `:clojure/binding` | clojure | 1 | register | green | JVM |
| `:clojure/protocol-not-callable` | clojure | 3 | semantic | red | JVM |
| `:clojure/protocol-not-callable` | clojure | 3 | stack | red | JVM |
| `:clojure/protocol-not-callable` | clojure | 3 | register | red | JVM |
| `:clojure/protocol-invalid-invocation` | clojure | 3 | semantic | red | JVM |
| `:clojure/protocol-invalid-invocation` | clojure | 3 | stack | red | JVM |
| `:clojure/protocol-invalid-invocation` | clojure | 3 | register | red | JVM |
| `:clojure/protocol-unsupported-binding` | clojure | 3 | semantic | red | JVM |
| `:clojure/protocol-unsupported-binding` | clojure | 3 | stack | red | JVM |
| `:clojure/protocol-unsupported-binding` | clojure | 3 | register | red | JVM |
| `:clojure/saved-protocol-not-callable` | clojure | 3/5 | semantic | red | JVM |
| `:clojure/saved-protocol-not-callable` | clojure | 3/5 | stack | red | JVM |
| `:clojure/saved-protocol-not-callable` | clojure | 3/5 | register | red | JVM |
| `:clojure/saved-protocol-invalid-invocation` | clojure | 3/5 | semantic | red | JVM |
| `:clojure/saved-protocol-invalid-invocation` | clojure | 3/5 | stack | red | JVM |
| `:clojure/saved-protocol-invalid-invocation` | clojure | 3/5 | register | red | JVM |
| `:clojure/saved-protocol-unsupported-binding` | clojure | 3/5 | semantic | red | JVM |
| `:clojure/saved-protocol-unsupported-binding` | clojure | 3/5 | stack | red | JVM |
| `:clojure/saved-protocol-unsupported-binding` | clojure | 3/5 | register | red | JVM |
| `:clojure/shape-return` | clojure | 5 | semantic | green | JVM |
| `:clojure/shape-return` | clojure | 5 | stack | green | JVM |
| `:clojure/shape-return` | clojure | 5 | register | green | JVM |
| `:clojure/shape-throw` | clojure | 5 | semantic | green | JVM |
| `:clojure/shape-throw` | clojure | 5 | stack | green | JVM |
| `:clojure/shape-throw` | clojure | 5 | register | green | JVM |
| `:python/fresh-named` | python | 1/5 | semantic | green | JVM |
| `:python/fresh-named` | python | 1/5 | stack | green | JVM |
| `:python/fresh-named` | python | 1/5 | register | green | JVM |
| `:python/fresh-closure` | python | 1/5 | semantic | green | JVM |
| `:python/fresh-closure` | python | 1/5 | stack | green | JVM |
| `:python/fresh-closure` | python | 1/5 | register | green | JVM |
| `:python/binding` | python | 1 | semantic | green | JVM |
| `:python/binding` | python | 1 | stack | green | JVM |
| `:python/binding` | python | 1 | register | green | JVM |
| `:python/protocol-not-callable` | python | 3 | semantic | red | JVM |
| `:python/protocol-not-callable` | python | 3 | stack | red | JVM |
| `:python/protocol-not-callable` | python | 3 | register | red | JVM |
| `:python/protocol-invalid-invocation` | python | 3 | semantic | red | JVM |
| `:python/protocol-invalid-invocation` | python | 3 | stack | red | JVM |
| `:python/protocol-invalid-invocation` | python | 3 | register | red | JVM |
| `:python/protocol-unsupported-binding` | python | 3 | semantic | red | JVM |
| `:python/protocol-unsupported-binding` | python | 3 | stack | red | JVM |
| `:python/protocol-unsupported-binding` | python | 3 | register | red | JVM |
| `:python/local-reception` | python | 2 | semantic | green | JVM |
| `:python/local-reception` | python | 2 | stack | green | JVM |
| `:python/local-reception` | python | 2 | register | green | JVM |
| `:python/foreign-reception` | python | 2 | semantic | red | JVM |
| `:python/foreign-reception` | python | 2 | stack | red | JVM |
| `:python/foreign-reception` | python | 2 | register | red | JVM |
| `:python/retained-foreign-lifetime` | python | 2/5 | semantic | red | JVM |
| `:python/retained-foreign-lifetime` | python | 2/5 | stack | red | JVM |
| `:python/retained-foreign-lifetime` | python | 2/5 | register | red | JVM |
| `:python/saved-protocol-not-callable` | python | 3/5 | semantic | red | JVM |
| `:python/saved-protocol-not-callable` | python | 3/5 | stack | red | JVM |
| `:python/saved-protocol-not-callable` | python | 3/5 | register | red | JVM |
| `:python/saved-protocol-invalid-invocation` | python | 3/5 | semantic | red | JVM |
| `:python/saved-protocol-invalid-invocation` | python | 3/5 | stack | red | JVM |
| `:python/saved-protocol-invalid-invocation` | python | 3/5 | register | red | JVM |
| `:python/saved-protocol-unsupported-binding` | python | 3/5 | semantic | red | JVM |
| `:python/saved-protocol-unsupported-binding` | python | 3/5 | stack | red | JVM |
| `:python/saved-protocol-unsupported-binding` | python | 3/5 | register | red | JVM |
| `:python/forged-retention` | python | 5 | semantic | green | JVM |
| `:python/forged-retention` | python | 5 | stack | green | JVM |
| `:python/forged-retention` | python | 5 | register | green | JVM |
## Round-0 seed: Python-containing source pairs (46 scenarios × 3 backends = 138 rows)

Only the six Python-containing directions ran in R0. The other six directions
(clojure→php, clojure→typescript, php→clojure, php→typescript, typescript→clojure,
typescript→php; 44 scenarios) are authored and pending their frontend rounds —
their rows appear here when their rounds run.

| Direction | Scenario | VM | Status | Host |
| --- | --- | --- | --- | --- |
| clojure→python | `binding` | register | green | JVM |
| clojure→python | `binding` | semantic | green | JVM |
| clojure→python | `binding` | stack | green | JVM |
| clojure→python | `catch-all` | register | green | JVM |
| clojure→python | `catch-all` | semantic | green | JVM |
| clojure→python | `catch-all` | stack | green | JVM |
| clojure→python | `finally-replacement` | register | green | JVM |
| clojure→python | `finally-replacement` | semantic | green | JVM |
| clojure→python | `finally-replacement` | stack | green | JVM |
| clojure→python | `retained-lifetime` | register | red | JVM |
| clojure→python | `retained-lifetime` | semantic | red | JVM |
| clojure→python | `retained-lifetime` | stack | red | JVM |
| clojure→python | `round-trip` | register | green | JVM |
| clojure→python | `round-trip` | semantic | green | JVM |
| clojure→python | `round-trip` | stack | green | JVM |
| clojure→python | `success-raise-success` | register | green | JVM |
| clojure→python | `success-raise-success` | semantic | green | JVM |
| clojure→python | `success-raise-success` | stack | green | JVM |
| clojure→python | `typed-catch` | register | red | JVM |
| clojure→python | `typed-catch` | semantic | red | JVM |
| clojure→python | `typed-catch` | stack | red | JVM |
| clojure→python | `unchanged-rethrow` | register | red | JVM |
| clojure→python | `unchanged-rethrow` | semantic | red | JVM |
| clojure→python | `unchanged-rethrow` | stack | red | JVM |
| php→python | `binding` | register | red | JVM |
| php→python | `binding` | semantic | red | JVM |
| php→python | `binding` | stack | red | JVM |
| php→python | `catch-all` | register | red | JVM |
| php→python | `catch-all` | semantic | red | JVM |
| php→python | `catch-all` | stack | red | JVM |
| php→python | `finally-replacement` | register | red | JVM |
| php→python | `finally-replacement` | semantic | red | JVM |
| php→python | `finally-replacement` | stack | red | JVM |
| php→python | `retained-lifetime` | register | red | JVM |
| php→python | `retained-lifetime` | semantic | red | JVM |
| php→python | `retained-lifetime` | stack | red | JVM |
| php→python | `round-trip` | register | red | JVM |
| php→python | `round-trip` | semantic | red | JVM |
| php→python | `round-trip` | stack | red | JVM |
| php→python | `success-raise-success` | register | red | JVM |
| php→python | `success-raise-success` | semantic | red | JVM |
| php→python | `success-raise-success` | stack | red | JVM |
| php→python | `typed-catch` | register | red | JVM |
| php→python | `typed-catch` | semantic | red | JVM |
| php→python | `typed-catch` | stack | red | JVM |
| php→python | `unchanged-rethrow` | register | red | JVM |
| php→python | `unchanged-rethrow` | semantic | red | JVM |
| php→python | `unchanged-rethrow` | stack | red | JVM |
| python→clojure | `binding` | register | green | JVM |
| python→clojure | `binding` | semantic | green | JVM |
| python→clojure | `binding` | stack | green | JVM |
| python→clojure | `catch-all` | register | green | JVM |
| python→clojure | `catch-all` | semantic | green | JVM |
| python→clojure | `catch-all` | stack | green | JVM |
| python→clojure | `retained-lifetime` | register | green | JVM |
| python→clojure | `retained-lifetime` | semantic | green | JVM |
| python→clojure | `retained-lifetime` | stack | green | JVM |
| python→clojure | `round-trip` | register | green | JVM |
| python→clojure | `round-trip` | semantic | green | JVM |
| python→clojure | `round-trip` | stack | green | JVM |
| python→clojure | `success-raise-success` | register | green | JVM |
| python→clojure | `success-raise-success` | semantic | green | JVM |
| python→clojure | `success-raise-success` | stack | green | JVM |
| python→clojure | `typed-catch` | register | green | JVM |
| python→clojure | `typed-catch` | semantic | green | JVM |
| python→clojure | `typed-catch` | stack | green | JVM |
| python→clojure | `unchanged-rethrow` | register | green | JVM |
| python→clojure | `unchanged-rethrow` | semantic | green | JVM |
| python→clojure | `unchanged-rethrow` | stack | green | JVM |
| python→php | `binding` | register | error | JVM |
| python→php | `binding` | semantic | error | JVM |
| python→php | `binding` | stack | error | JVM |
| python→php | `catch-all` | register | error | JVM |
| python→php | `catch-all` | semantic | error | JVM |
| python→php | `catch-all` | stack | error | JVM |
| python→php | `finally-replacement` | register | red | JVM |
| python→php | `finally-replacement` | semantic | red | JVM |
| python→php | `finally-replacement` | stack | red | JVM |
| python→php | `retained-lifetime` | register | error | JVM |
| python→php | `retained-lifetime` | semantic | error | JVM |
| python→php | `retained-lifetime` | stack | error | JVM |
| python→php | `round-trip` | register | red | JVM |
| python→php | `round-trip` | semantic | red | JVM |
| python→php | `round-trip` | stack | red | JVM |
| python→php | `success-raise-success` | register | error | JVM |
| python→php | `success-raise-success` | semantic | error | JVM |
| python→php | `success-raise-success` | stack | error | JVM |
| python→php | `typed-catch` | register | error | JVM |
| python→php | `typed-catch` | semantic | error | JVM |
| python→php | `typed-catch` | stack | error | JVM |
| python→php | `unchanged-rethrow` | register | error | JVM |
| python→php | `unchanged-rethrow` | semantic | error | JVM |
| python→php | `unchanged-rethrow` | stack | error | JVM |
| python→typescript | `binding` | register | green | JVM |
| python→typescript | `binding` | semantic | green | JVM |
| python→typescript | `binding` | stack | green | JVM |
| python→typescript | `catch-all` | register | green | JVM |
| python→typescript | `catch-all` | semantic | green | JVM |
| python→typescript | `catch-all` | stack | green | JVM |
| python→typescript | `finally-replacement` | register | red | JVM |
| python→typescript | `finally-replacement` | semantic | red | JVM |
| python→typescript | `finally-replacement` | stack | red | JVM |
| python→typescript | `retained-lifetime` | register | red | JVM |
| python→typescript | `retained-lifetime` | semantic | red | JVM |
| python→typescript | `retained-lifetime` | stack | red | JVM |
| python→typescript | `round-trip` | register | red | JVM |
| python→typescript | `round-trip` | semantic | red | JVM |
| python→typescript | `round-trip` | stack | red | JVM |
| python→typescript | `success-raise-success` | register | green | JVM |
| python→typescript | `success-raise-success` | semantic | green | JVM |
| python→typescript | `success-raise-success` | stack | green | JVM |
| python→typescript | `typed-catch` | register | green | JVM |
| python→typescript | `typed-catch` | semantic | green | JVM |
| python→typescript | `typed-catch` | stack | green | JVM |
| python→typescript | `unchanged-rethrow` | register | red | JVM |
| python→typescript | `unchanged-rethrow` | semantic | red | JVM |
| python→typescript | `unchanged-rethrow` | stack | red | JVM |
| typescript→python | `catch-all` | register | red | JVM |
| typescript→python | `catch-all` | semantic | red | JVM |
| typescript→python | `catch-all` | stack | red | JVM |
| typescript→python | `finally-replacement` | register | red | JVM |
| typescript→python | `finally-replacement` | semantic | red | JVM |
| typescript→python | `finally-replacement` | stack | red | JVM |
| typescript→python | `retained-lifetime` | register | red | JVM |
| typescript→python | `retained-lifetime` | semantic | red | JVM |
| typescript→python | `retained-lifetime` | stack | red | JVM |
| typescript→python | `round-trip` | register | red | JVM |
| typescript→python | `round-trip` | semantic | red | JVM |
| typescript→python | `round-trip` | stack | red | JVM |
| typescript→python | `success-raise-success` | register | red | JVM |
| typescript→python | `success-raise-success` | semantic | red | JVM |
| typescript→python | `success-raise-success` | stack | red | JVM |
| typescript→python | `typed-catch` | register | red | JVM |
| typescript→python | `typed-catch` | semantic | red | JVM |
| typescript→python | `typed-catch` | stack | red | JVM |
| typescript→python | `unchanged-rethrow` | register | red | JVM |
| typescript→python | `unchanged-rethrow` | semantic | red | JVM |
| typescript→python | `unchanged-rethrow` | stack | red | JVM |
## Round deltas

(none yet — Round 0 is the seed; JS R1+R3 is the first round of record)

## Round deltas — 2026-11-11 (three rounds, one shiva session each, no-overlay verification)

Instrument note of record: every earlier shiva run on 2026-11-11 (xtrack-r3A2/r3A3, xtrack-js3,
xtrack-py) is VOID — the overlay shipped master-state content over the branch checkouts (rverify
resolves overlay paths from the invoking cwd). The rows below come from the FIRST genuine
measurements: xtrack-r3A4 (fbd8da0f), xtrack-js4 (ce99e65e), xtrack-py2 (a396ae28), each with
files-list `-` (no overlay; the clone checkout IS the code under test), gen:antlr first, one clone
per lane. Row sources: `~/ci/xtrack-{r3A4,js4,py2}-logs/` on shiva.

### Round JS R1+R3 — branch `exception-js-r1r3` @ `ce99e65e` (ts seat; muse-spark-1.3 + muse-wonder fix)

24 rows flip red→green (8 fixtures × 3 backends), all three backends identical:

| Fixture | Was | Now |
| --- | --- | --- |
| `:typescript/fresh-named` | red | green |
| `:typescript/fresh-closure` | red | green |
| `:typescript/shape-throw` | red | green |
| `:typescript/protocol-not-callable` | red | green |
| `:typescript/protocol-invalid-invocation` | red | green |
| `:typescript/protocol-unsupported-binding` | red | green |
| `:typescript/saved-protocol-not-callable` | red | green |
| `:typescript/saved-protocol-invalid-invocation` | red | green |
| `:typescript/saved-protocol-unsupported-binding` | red | green |
| `:typescript/forged-retention` | red | green |

(9 fixtures listed = 27 rows; shape-return was already green. Kit: 9 tests/159 assertions,
60F/21E — the remainder is php/python/clojure-shared rows pending their rounds.)

### Round Python R2/R3+egress — branch `exception-py-r2r3` @ `a396ae28` (python seat; sol + deepseek-v4-pro recovery)

24 rows flip red→green (8 fixtures × 3 backends):

| Fixture | Was | Now |
| --- | --- | --- |
| `:python/protocol-not-callable` | red | green |
| `:python/protocol-invalid-invocation` | red | green |
| `:python/protocol-unsupported-binding` | red | green |
| `:python/saved-protocol-not-callable` | red | green |
| `:python/saved-protocol-invalid-invocation` | red | green |
| `:python/saved-protocol-unsupported-binding` | red | green |
| `:python/foreign-reception` | red | green |
| `:python/retained-foreign-lifetime` | red | green |

Kit: 10 tests/279 assertions, 126F/39E — remainder is php rows + php-containing pairs pending the
php round. Python-containing pair legs measured in the same run: python→clojure all green;
clojure→python green on `binding`, `catch-all`, `finally-replacement`, `round-trip`,
`success-raise-success`; `typed-catch`, `unchanged-rethrow`, `retained-lifetime` now green per the
py2 run's pair output (exact pair ids transcribed to the matrix scaffold on acceptance of the
12-pair matrix round).

### Round Clojure R3 — branch `exception-r3-impl` @ `fbd8da0f` (this seat; deepseek-flash → muse-wonder → deepseek-v4-pro)

18 rows flip red→green (6 fixtures × 3 backends):

| Fixture | Was | Now |
| --- | --- | --- |
| `:clojure/protocol-not-callable` | red | green |
| `:clojure/protocol-invalid-invocation` | red | green |
| `:clojure/protocol-unsupported-binding` | red | green |
| `:clojure/saved-protocol-not-callable` | red | green |
| `:clojure/saved-protocol-invalid-invocation` | red | green |
| `:clojure/saved-protocol-unsupported-binding` | red | green |

`yang.clojure-test` on shiva: 103 tests / 993 assertions, 0F/0E. Kit: 69F/21E — remainder is
php rows + php-containing pairs pending the php round. Design provenance: the mob record
(1794369000000, 2/3 (A)) and glm-5.3's independent CONFIRMED; astra's probe battery is a standing
test in the branch (`test-exception-r3-astra-probes`). NOTE: `fbd8da0f` includes the yin.vm
`:retained` seam — merge is gated on the owner's answer to
`1794372000000-owner-decision-r3-vm-retained-seam.md`.

## Round deltas — 2026-11-12 (VM provenance primitive + JS ref correction)

### Round VM provenance primitive — branch `exception-vm-provenance` @ `50c9485a` (merged to master as `8c10c5b7`)

No ledger rows flip yet — this round lands the **kernel primitive** the python adoption round
consumes. The python forgery pins (committed RED at `0326a044`) flip when the python adoption
wires the wrapper mint/read onto the primitive. Design consult: sol APPROVED WITH AMENDMENTS
(`1794451000000-...findings.md`); implementation + 2 fix rounds (`ff8b980a` → `566c5450` →
`50c9485a`); step-7 astra REQUEST CHANGES → fixes → glm-5.3 CONFIRMED
(`1794463500000-...findings.md`, 46 probe assertions, origin-distinction proven directly);
shiva `xtrack-vmprov3` green at the true head (provenance+macro rc=0; seam suites rc=0; no
overlay). `yin.vm.provenance-test` 14→41 tests / 334 assertions across the rounds. The declined
`:retained` seam (`e735ebb2`) is superseded by this primitive per the owner's convergence
directive; `src/cljc/yin/vm/**` diff vs pre-track master = the primitive ONLY (verified: the
seam removal commit + this primitive are the sole VM changes).

### Ledger correction of record — JS delta commit ref

The JS R1+R3 delta's commit ref moves: `ce99e65e` → the merged lineage `40284c15` (rework:
rt-rename, private identity-store provenance, re-derived pins), merged to master as **`3e42b034`**
(ts seat, sol sign-off + qwen R5 closure, changed-set tri-lane 0F, dao.stream precondition green).
`xtrack-js5` at source confirmed zero ts failures at that head. Also recorded: the P2 `type`
builtin (python prelude.cljc:3452) is ACCEPTED per the steward's sequencing authority (python
seat recommendation; required by the acceptance probe; already in scanner goldens).

## Round deltas — 2026-11-12 (python adoption; measured xtrack-py3 at `428bbdd7`, no overlay, clone-verified)

### Round python provenance adoption — branch `exception-py-r2r3` @ `428bbdd7` (python seat; sol spec, deepseek-v4-pro implementation + adoption wiring)

24 rows flip red→green (8 fixtures × 3 backends), all three backends identical, measured on the
FULL adoption tree (reception `d9a73150`-lineage + egress + the provenance adoption `00ca2d1c` on
the rebased branch):

| Fixture | Was | Now |
| --- | --- | --- |
| `:python/protocol-not-callable` | red | green |
| `:python/protocol-invalid-invocation` | red | green |
| `:python/protocol-unsupported-binding` | red | green |
| `:python/saved-protocol-not-callable` | red | green |
| `:python/saved-protocol-invalid-invocation` | red | green |
| `:python/saved-protocol-unsupported-binding` | red | green |
| `:python/foreign-reception` | red | green |
| `:python/retained-foreign-lifetime` | red | green |

The three forgery contract pins (committed RED at `0f0ab852`) also flip green — forge pair →
fresh `:yang.call/thrown` with the clone as payload; clone-of-genuine → no retention;
overwrite → original condition forwards. Measured: `xtrack-py3` cmd2 (720s, 0F/0E) and cmd3 row
extraction, clone at `428bbdd7` clean, gen:antlr first, no overlay. Prior python row measurements
(xtrack-py, xtrack-py2) are VOID — they overlaid incomplete worktree states (mid-recovery
prelude) and cannot establish any earlier python row state. Scanner goldens regenerated at
`428bbdd7` (deferred `:py` set single mover, three-run byte-identical, abort-gate verified).
Step-7 verification of the adoption wiring: dispatched to glm-5.3 (non-deepseek family, per
independence after sol/deepseek authorship). Python merge hold releases on its verdict.

### Python adoption MERGED — `8018005d` (glm-5.3 step-7: APPROVE WITH FINDINGS, 3×P3 follow-up grade)

Step-7 verdict: the adoption is a clean minimal rewiring; the forgeable stamp fully retired as an
authority source; every forwarding decision authenticates through the sealed-heap reader; R2
lifetime rules hold clause-by-clause; export surface exposes no mint path; no assertion weakened.
Findings disposition (all P3, DEFERRED to follow-up rounds, none weakens the authenticity
guarantee): F1 — mint nil-guard behavior split (nil fatal vs other garbage opaque) to be
shape-checked at reception or recorded as explicit contract; F2 — python export-surface audit pin
(mirroring the ts twin's module-exports/runtime-keys assertion) to close the future-regression
gap; F3 — stale scanner-deferred comment rewrite at scanner-golden-c4.generate.clj:47-53.
**Python merge hold RELEASED.** Two of the four frontends now hold the full authentic-retention
stack on master (python + js); php in flight; clojure port queued on cc3.

## Round deltas — 2026-11-13 (the convergence port; 18-row claim at the true tip)

### Round Clojure convergence port — branch `exception-r3-impl` @ `854b74c3` (steward seat; implementers deepseek-v4-pro Phases 1-3r, codex-terra 3r-b/4/5/6/6b/corpus/fixture; design authority: sol consult)

**Ledger claim (sol's language):** Closed 18 Clojure protocol/saved-protocol rows on semantic,
stack, and register at `854b74c3` / `xtrack-r3-B cmd4` (rc=0, 565s). Qualified protocol conditions
are retained in write-once private heap provenance owned by the generated carrier's identity.
Public egress authenticates and recovers without applying the raised value, forwarding the
complete original condition once. Saved rethrows survive the creating activation. Unmarked
lookalikes remain fresh thrown payloads. No frontend VM changes or ambient retention registry
were introduced.

**SUPERSESSION of record:** the earlier 18-row claim at `fbd8da0f` (xtrack-r3A4) measured the
(A)-design state that contained the DECLINED `:retained` VM seam. This claim supersedes it: the
same rows are closed by the owner-directed convergence to the continuation encoding plus the
approved shared provenance primitive — zero frontend-specific kernel lines (qwen re-review, item 3).

Full verification chain, all at `854b74c3` (xtrack-r3-B, no overlay): clojure-test + core-call
rc=0 (105+4 deftests) · python forgery pins rc=0 (982s) · contract `-v clojure-provenance` rc=0
(565s — the 18) · latte-oracle rc=0 (36/2,543, 948s). Depth: 155/155 overlap, max 1.20×, recorded
SHA-attributably at `d4e31b74` (`depth_comparison.md`; footer computed by the writer).
Review chain: sol design consult (carrier-resolved-via-provenance) → qwen implementation review
(APPROVE WITH FINDINGS) → delta re-confirmation **CONFIRMED — PORT READY FOR MERGE REQUEST**.

**P3 batch (follow-up rounds, non-blocking):** P3-1 depth-evidence gate deftest; P3-2 stale
outcome-model docs; P3-3 `clj/run-main` top-level error-as-value (explicit known gap or convert);
P3-5/6/7/9/10/11 per qwen's two files; plus prior-round P3s (python F1 nil-guard contract,
F2 export-audit pin — F2's twin now needed clojure-side; F3 comment).

**MERGE STATE:** port READY; merge gated on **P1-1** (owner: the kit's `finally`-refusal row —
retire per FINAL DIRECTIVE [recommended] vs reinstate). php round: its own track.

### Round PHP R1+R2+R3 — merged to master as `5eaef15e` (php seat; r1–r12; deepseek-v4-pro round-7 MERGE-READY sign-off; acceptance kit at `e80e1404`)

33 rows flip red→green (11 fixtures × 3 backends): binding, forged-retention, foreign-reception,
retained-foreign-lifetime, protocol-{not-callable,invalid-invocation,unsupported-binding},
saved-protocol-{same}×3. Disclosed-red (3 rows' worth, steward-dispositioned below): fresh-named,
fresh-closure, local-reception — now failing ONLY :payload-identical (was fully red). The 18
clojure-provenance rows at that base are the in-flight R3/port state, inherited not touched —
they flip on the PORT merge, which must land in the SAME WINDOW as the kit-round (each alone
reddens master: port-without-kit trips the old refusal test; kit-without-port runs the new
clojure-caller finally-replacement legs against a refusing clojure).

Steward dispositions of the php lane findings (1794541429000):
1. Instrument-shape conflict (run-main unsets globals; the 3 payload-identity fixtures assume
   post-unit survival): ACCEPTED for a KIT ROUND 2 — sol re-check-5 §4's instrument-shape repair
   (runtime-seeded payload; the directed in-lane pins already prove the assertions pass with that
   shape). Contract unchanged; fixture seeding shape changes. Queued behind the running finally
   kit round.
2. dao.jing concurrent-content stall: laptop-lane-specific (shiva completes in minutes);
   consistent with the owner ruling that suite-scale runs belong remote; NOT a php defect. The
   orphaned JVM (pid 75393) was REAPED by the steward this session.
3. Duplicate-dispatch trail (r9/r10): noted, corrected on the record; no action.

## Round deltas — 2026-11-13 (the kit round retiring the clojure `finally` refusal)

### Round Kit — branch `exception-kit-finally` @ `96633aaf` base (qwen3.8-max; sol reviews after)

**P1-1 is RULED: RETIRE** (owner, 2026-11-13 09:20 +07,
`1794556000000-owner-decision-finally-refusal-row.md`, verbatim "retire"). Recorded against that
packet per the ruling's consequence 2. The MERGE STATE line above is left as filed; this section is
the append-only record that P1-1 no longer gates.

Governing principle of record: **a dialect's surface derives from its source language; the proof
corpus verifies admissions, it does not author them.** v2 §3's finally-refusal clause and the
`no finally` half of v2 R4 are superseded append-only in
`1794960000000-owner-ruling-exception-uniformity-v2.md`. The single-catch / catch-class half of R4
is **not** superseded and remains pinned.

Kit deltas (three files, no `src/` and no frontend touched):

- `exception_contract.clj` — the `:finally-replacement` clojure-caller exclusion is removed from
  `pair-matrix`; the `:clojure-finally-refusal` pending-obligation row is **removed, not flipped**.
  Rationale: `pending-obligations` is by its own docstring the set of obligations *not yet claimed*,
  and `open-gates` closes a row only on a reported `:green`, so an `:execution :supports` row would
  stay open forever. `:preserve-source-refusal` had exactly one consumer and is now unused.
  `ledger-seed` for `:clojure` still yields `#{r1 r2 r3 r4 r5}` — `::r4` survives via
  `:clojure-private-public-equivalence` — so `mechanically-derived-obligations-test` holds.
- `exception_legs.clj` — the generate-time `(throw (ex-info "Clojure finally remains an explicit
  source refusal" ...))` is removed and the `:finally-replacement` generator gains its `:clojure`
  arms (`(def replacement (cell/new :replacement))`, `replacement_payload`,
  `(try (donor_raise) (finally (throw replacement)))`). **This file was not in the dispatch's
  two-file list, but the ruling names it as one of the three kit locations, and it is load-bearing:**
  with the matrix exclusion gone, that `throw` fires inside `run-pair`'s `try` and would error
  *every* leg of every clojure-caller pair, not just the new one.
- `exception_source_test.clj` — `clojure-restricted-catch-surface-preserved-test` keeps only the
  still-live `(catch Throwable e 2)` refusal; a new `clojure-finally-admitted-test` pins that
  `(try 1 (finally 2))` lowers; a new `^:slow clojure-finally-replacement-leg-test` pins the single
  `[:python :clojure :finally-replacement]` leg across the three vector VMs.

**NO ROWS ARE CLAIMED BY THIS ENTRY.** The seat had no shell: nothing was executed, so no row moved
and no green is asserted. New-leg status is for shiva to measure.

**BLOCKER for the shiva run — the port is not in this branch.** `exception-kit-finally` is cut from
`origin/master` @ `96633aaf`, which does **not** contain the clojure convergence port
(`exception-r3-impl` @ `854b74c3` / `d4e31b74`, still unmerged per the MERGE STATE above). Verified
three ways: `src/cljc/yang/clojure.cljc:1136` still rejects with "Portable try requires one
Exception/ExceptionInfo catch and no finally"; `src/cljc/yang/clojure/` has zero hits for
`%capture`, `clj.rt/ctx`, `clj/try`, `clj/raise` and `provenance`; and `cell/dsg__new-provenance`
appears only in the python and php preludes. **Run against this branch alone and every
clojure-caller `:finally-replacement` leg fails at COMPILE time, not at runtime** — including
`all-directed-pair-sources-compile-test`, which iterates the whole matrix. That red is a
branch-composition artifact and must not be read as a §8.10.8 cleanup-before-transfer finding. The
kit round and the port must be measured together (merge, or rebase this branch onto `854b74c3`).

### Round Kit completion — gpt-6.1-sol, 2026-11-13 (qwen3.8-max remains implementer of record)

Review of qwen's F1–F6:

- **F1 confirmed:** the kit branch still has base `96633aaf` and does not incorporate
  the Clojure port. Verification uses an isolated, uncommitted merge of that base
  with port `854b74c3`, plus the exact four changed kit/test files. No production
  source or port merge is included in the kit commit.
- **F2 verified on the composed tree:** removing both the matrix exclusion and the
  generate-time throw is coherent. The Clojure replacement probe uses a real
  `(finally (throw replacement))`; the directed source and batch compilation
  checks pass for python→clojure, php→clojure and typescript→clojure.
- **F3 completed:** `clojure-finally-normal-exit-test` observes public entries through
  the existing kit. All three vector VMs return
  `{:body-route :return :value 1 :cleanup-route :return :cleanup-count 1}` even
  though the final cleanup expression is `2`. Body value preservation and
  exactly-once normal cleanup are now execution assertions.
- **F4 retained:** the newly admitted direction is donor→Clojure, not Clojure→donor.
- **F5/F6 completed at the instrument level:** `php-to-clojure-test` and
  `typescript-to-clojure-test` are added beside `python-to-clojure-test` in
  `yang.python.antlr.exception-call-test`, with the same `check-pair` body,
  slow/gate/frontend metadata, reporting and compliant expectations. No new runner
  is introduced. The original R0 snapshot and frontend-round acceptance tags are
  retained; adding a runnable deftest alone does not declare frontend conformance.

Measured locally, scoped JVM verification only:

| Tree | Command | Result |
| --- | --- | --- |
| Kit alone on `96633aaf` | `clojure -M:test -n yang.call.exception-source-test` | 6 tests, 121 assertions; 3 failures, 8 errors, from the still-live frontend finally rejection (F1); no semantic row claimed |
| Base + port `854b74c3` + kit | same source namespace command | **6 tests, 124 assertions; 0 failures, 0 errors** |

The composed source result includes `all-directed-pair-sources-compile-test`,
the normal-exit pin, and the focused python→clojure finally-replacement leg on
register/semantic/stack. The original qwen findings are committed unchanged
(Git blob `4de60d913eb59e672e15d2f9091beaa11e24ec63`); this completion record
supplies the later measurements rather than rewriting that report.

Composed-tree pair execution used only these existing-shape deftests:

```sh
clojure -M:test -n yang.python.antlr.exception-call-test \
  -v yang.python.antlr.exception-call-test/python-to-clojure-test \
  -v yang.python.antlr.exception-call-test/php-to-clojure-test \
  -v yang.python.antlr.exception-call-test/typescript-to-clojure-test
```

**3 tests, 69 assertions: 60 pass, 9 failures, 0 errors.** This is a measured
subset, not an all-green pair gate or frontend acceptance claim.

| Direction | Finally replacement: register / semantic / stack | Whole pair |
| --- | --- | --- |
| python→clojure | **green / green / green** | 24/24 assertions pass |
| php→clojure | **green / green / green** | 15/24 pass; nine red observations below |
| typescript→clojure | **green / green / green** | 21/21 assertions pass |

All nine newly admitted finally-replacement observations equal
`{:transferred true :once true :after false :reason :yang.call/thrown :payload-identical true}`.
These legs use genuine source `finally` during foreign unwinding; the
replacement payload's identity survives the public boundary.

**Execution findings exposed by the new PHP pair deftest (not coverage omissions):**

| PHP→Clojure scenario | Observed on each of register / semantic / stack | Expected delta |
| --- | --- | --- |
| `:retained-lifetime` | `{:transferred true :once true :after false :reason :yang.call/thrown :payload-identical false :retained true :local true}` | `:payload-identical` must be true |
| `:unchanged-rethrow` | `{:transferred true :once true :after false :reason :yang.call/thrown :payload-identical false :caught true}` | `:payload-identical` must be true |
| `:round-trip` | `{:returned false :value nil}` | `{:returned true :value true}` |

These observations are consistent with the previously recorded PHP module-global
lifetime limitation (the `php-exception-test` egress instrument documents
`$original` disappearing at module shutdown). This completion does not establish
that causal diagnosis independently, fix PHP runtime behavior, or weaken any
expectation. All three failures are outside `:finally-replacement` and remain
explicit findings for their frontend round.

Additional scoped checks: `mechanically-derived-obligations-test` passed (1 test,
4 assertions); `clojure -M:kondo --lint` on the three changed kit files plus
`exception_call_test.clj` reported **0 errors / 0 warnings**; cljstyle
`0.17.642` checked the same four files successfully; `git diff --check` passed.
Generated Java parsers were built locally as prerequisites. No suite-scale local
run was performed.

**Coverage:** F5/F6's execution gap is closed for all three RETIRE directions on
the JVM and all three vector VMs. Node/Dart host lanes, the remaining directed
pairs and the wider acceptance obligations are not measured by this local round.
The kit branch alone still needs composition with the port before its new tests
can pass; the nine green retirement rows above belong to the explicitly composed
tree, not standalone `96633aaf`.

**KIT-2 follow-on to Round Kit completion — gpt-6.1-sol, 2026-11-13:** the
steward-owned PHP instrument-shape repair is implemented in the shared kit.
`exception_contract.clj` now defines a parsed-source PHP `seed` function that
creates `$original` after `php/run-main` shutdown; `exception_probes.clj` uses
its returned original in fresh-named, fresh-closure and local-reception.
`exception_legs.clj` seeds once before each PHP-donor observation, so `payload`,
`donor_raise`, retained rethrows and `home` share the same live object. No
runtime, lifecycle rule, expected map, identity comparison, once count or
after-call assertion changes. The R0 snapshot and earlier findings above are
preserved as measured history.

Verification uses the **existing** isolated composition from KIT-1 (base
`96633aaf` + Clojure port `854b74c3` + kit `6b5dfa7f`), overlaid with these four
repaired test files. The port is not merged into `exception-kit-finally`.

`clj -M:test -n yang.call.exception-source-test` passes **7 tests, 312
assertions, 0 failures, 0 errors** on that composition. The PHP fresh-named,
fresh-closure and local-reception observations are now **9/9 green** across
register/semantic/stack; all 34 other-frontend fixtures retain their original
expectations and pass on all three VMs (**102/102 observations**).

The existing scoped pair runner also passes:

```sh
clj -M:test -n yang.python.antlr.exception-call-test \
  -v yang.python.antlr.exception-call-test/php-to-clojure-test
```

**1 test, 24 assertions, 0 failures, 0 errors.** In particular, KIT-1's nine
PHP→Clojure residual observations (retained-lifetime, unchanged-rethrow and
round-trip on the three VMs) are green with the runtime-seeded original;
finally-replacement remains green. This supersedes those instrument findings
for the repaired setup, without changing PHP shutdown semantics.

The source namespace now checks all 47 original provenance fixtures, including
the three PHP identity fixtures on register/semantic/stack, and the other
frontends' original expectations. A separate before/after audit against
`6b5dfa7f` confirms all 47 fixture contracts unchanged; source and donor-source
strings for all three non-PHP frontends unchanged; all 34 non-PHP fixture
observation forms and 69 non-PHP-donor leg observation forms identical with
deterministically generated symbol names. No suite-scale local run, Node/Dart host gate or new frontend
acceptance claim is made by this follow-on.

`clj -M:kondo --lint` on all four changed Clojure files reports **0 errors /
0 warnings**; cljstyle `0.17.642` and `git diff --check` pass.

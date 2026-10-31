Created-GMT: 2026-09-19 20:58:00 GMT
Created-Local: 2026-09-20 04:58:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session)
# Task: confirm the W1 r2 fixes

Your two P2s and three P3s were fixed by the implementer. Orchestrator
verification: focused 13 tests / 155 assertions / 0 failures 0 errors;
JVM full 1463 / 168103 / 0; CLJS 1380 / 37972 / 0; CLJD zero waitset
failures (the new catch is the namespace's one host-splice, and the CLJD
lane exercises it).

- P2 invalid answers: `stream/valid-outcome?` wraps every poll answer;
  invalid folds into `:dao.stream.waitset/invalid-answer` (wake, leave
  `:waiting`, no `:advance`, store unchanged); per-entry try/catch folds a
  host dispatch error into the same diagnostic; register rows added;
  `check`'s docstring states resolvers must not throw and a throw voids
  the sweep. New test covers nil outcome, non-map answer, nil writer
  answer, protocol-failing handle.
- P2 advance counters: `check-one` returns the counter; 1 for reader
  `ok`/`gap`, 0 for every other reader outcome and the rogue keyword, 0
  for all writer outcomes; the resolve-without-stream case now asserts
  zero.
- P3s: `(fnil conj [])` in `park` with the total-over-missing/nil test;
  the missing-`:value` docstring note in `poll-put`.

Re-read only the lines touched and the two new tests. Challenge. Do not
edit. Do not rerun suites.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Return: finding | final disposition | evidence | remaining action.
Explicitly state whether W1 is ready for commit.

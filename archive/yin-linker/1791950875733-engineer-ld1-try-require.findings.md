# L-d-1 findings

Implemented the bare, language-blind `try-require` primitive and
`:module/try-require` handler. Success, refusal, cycle and abandonment
resume requiring continuations with data. Plain `require` keeps its
existing return/error contract, independently of co-waiters.

## Files modified or added

- `src/cljc/yin/vm.cljc`: primitive and effect profile.
- `src/cljc/yin/vm/module.cljc`: shared handler, per-waiter `:try?`,
  kernel park fallback, cycle data, effect registration.
- `src/cljc/yin/vm/engine.cljc`: per-waiter refusal/success delivery;
  retain try waiter's link id through installation for loss data.
- `test/yin/vm/linker_require_test.cljc`: nine new tests.
- `docs/design/yin.vm.linker.md`: section 7.2 step 7.
- `docs/design/yin.vm.linker.dht.md`: sections 7.5.3 and 9.
- `collab/1791950875733-engineer-ld1-try-require.prompt.md`: supplied brief,
  already present at task start, preserved verbatim.
- This findings report; `collab/ld1-*.log` validation evidence and
  `collab/ld1-mutations.py` reproducible mutation driver.

## Acceptance checklist and exact assertion counts

The actual backends table includes `:walker` as well as the four named
in the brief. All backend tests below exercise all five.

| Check | Assertions per backend | Total | Result |
| --- | ---: | ---: | --- |
| Absent refusal datum and continuation progress | 4 | 20 | Pass |
| Install success, callable export, linked registry hit | 4 | 20 | Pass |
| Immediate host registry hit, no appended request | 3 | 15 | Pass |
| Cycle datum, chain, no appended request | 3 | 15 | Pass |
| Abandon link datum and id retirement | 3 | 15 | Pass |
| Mixed require/try-require install co-waiters | 9 | 45 | Pass |
| Abandon install datum and cleanup | 4 | 20 | Pass |
| Module linked while response was pending | 3 | 15 | Pass |
| Primitive profile (backend-independent) | n/a | 4 | Pass |
| Total new assertions | | 169 | Pass |
| Existing tests unchanged | | 867 | Pass |

The mixed-waiter fixture uses real handlers and backend park records.
The install child blocks on a dependency so another waiter can join;
each woken continuation restores against its originating kernel image.
`settle` already retains extra entry fields; `advance-installs` operates
on existing waiters without rebuilding them. No additional propagation
mechanism or kernel changes were necessary.

## JVM results

- `clojure -M:test -n yin.vm.linker-require-test`: 40 tests,
  1,036 assertions; zero failures/errors (`ld1-final-targeted.log`).
- `bb test:sub:clj yin.vm`: 82 namespaces, 1,315 tests,
  26,218 assertions; zero failures/errors, 91 seconds. See
  `ld1-jvm-subsystem.log` and `ld1-jvm-subsystem-tests.log`.
- Timed all nine new test bodies with `clojure.test/test-var`:
  0.37-93.01 ms per test, each including all applicable backends.
  No new test reaches the 5-second slow threshold.
- Initial test-first run failed to compile because the new handler
  did not exist (`ld1-red.log`). Subsequent fixture failures corrected:
  no `vector` primitive; installs can finish in one scheduler round;
  backend continuations require their originating images.
- `git diff --check`: clean.
- Changed-file `mise exec -- cljstyle check`: exit 0, clean. Mise emits
  a cache-write warning outside the worktree; style checking succeeds.

## Mutation table

Each mutant was applied alone, run on the then-current targeted suite
(38 tests), and restored in a Python `finally` block. All three exited 1.
The final green run includes the additional linked-meanwhile/profile tests.

| Mutant | Assertions | Failures | Errors | Evidence |
| --- | ---: | ---: | ---: | --- |
| Raise try refusal instead of returning data | 921 | 0 | 4 | `ld1-mutant-refusal-as-error.log` |
| Return plain symbol on try install success | 1017 | 5 | 0 | `ld1-mutant-success-as-symbol.log` |
| Throw on try ancestry cycle | 1003 | 0 | 1 | `ld1-mutant-cycle-throws.log` |

## Deviations and open issues

- The named architect scoping log and mob consolidation document are
  absent from this worktree (searched `collab/`). Implementation follows
  the explicit brief and available architectural/design documents.
- Bare `clj -M:kondo` supplies no lint paths and prints usage. Ran
  `clj -M:kondo --lint src test`: 27 errors, 67 warnings, all pre-existing.
  A copied source/test tree with the four changed code files restored
  from HEAD has exactly the same diagnostics. Normalizing paths/line
  positions yields zero added and zero removed diagnostics. See
  `ld1-kondo.log`, `ld1-kondo-baseline.log`, `ld1-kondo-comparison.log`.
  Full-tree zero-error/zero-warning lint is an outstanding baseline issue.
- Full-tree `mise exec -- cljstyle check` reports pre-existing formatting
  failures in 25 unrelated files. Changed-file checks are clean; unrelated files
  were left unchanged. Evidence: `ld1-style.log`.
- The first subsystem attempt could not resolve the ANTLR download host
  inside the sandbox. The authorized rerun with network access completed
  successfully; generated build output remains inside this worktree.
- Node, Dart and full slow tiers were not run; orchestrator runs them on
  Shiva. No git write commands were issued.

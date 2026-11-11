Completed-GMT: 2026-11-10T17:52:19Z
Completed-Local: 2026-11-11T00:52:19 +07

# macro-build event-record regression — STOP-and-report (no edit made)

Task: `1794332997000-engineer-macro-build-event-fix.prompt.md`
Verdict: **record semantics legitimately changed under ruling U1; the
failing assertion's positional read is stale. Per the task's rule 2, I
stopped before changing the test and made no code or test edit.**

## Reproduced (master 8cd9fc92, this checkout)

```sh
clojure -M:test -n yin.vm.macro-build-test -v yin.vm.macro-build-test/a-build-expands-between-file-backed-media
```
```
FAIL in (a-build-expands-between-file-backed-media) (macro_build_test.clj:144)
expected: (= [nil :arity nil] (mapv #(:kind (peek %)) events))
  actual: (not (= [nil :arity nil] [nil nil nil]))
Ran 1 tests containing 13 assertions. 1 failures, 0 errors.
```

## What actually happened (both prime suspects refuted)

- The `:arity` event **is recorded**: the preceding assertion
  `(= 3 (count events))` passes. Nothing is suppressed.
- P1-1's ambiguity refusal (`lineage-origin-for`) and P1-2's metadata
  strip are not involved: the event row exists with its error intact.
- The mechanism is slot order. Before cc1, `event-row`
  (`src/cljc/yin/vm/macro.cljc`, pre-`847aa885`) was
  `[… output-root error]` — error last, which is what `peek` reads and
  what `docs/design/yin.vm.macro.md` §8.2 still specifies.
- `847aa885` ("construction-threaded occurrence origins from retained
  expansion history (cc1 fix round 5, ruling U1)") appended one slot:
  `[… output-root error correspondence]`. Error is now index 10;
  `correspondence` (nil for a failed attempt, a map for a success) is
  last. `(:kind (peek event))` therefore reads correspondence and
  yields `[nil nil nil]`.

## Why the new shape is the approved semantics

- U1 is the signed-off owner ruling of record (`368297c3`: "the
  occurrence-identity ruling is SIGNED OFF"), landed in `847aa885`;
  the task instructs P1-1..P1-4 stay intact.
- Two landed, green test files pin the new positions:
  `test/yang/clojure_callsite_test.cljc` reads `(nth event 11)` as the
  retained per-occurrence correspondence (lines 523, 1070 — "the
  expansion record retains the per-occurrence correspondence"), and
  `test/yin/vm/macro_test.cljc:569` reads the error at `(nth e 10)`.
- Restoring `peek` = error would require moving error back to last,
  breaking both pins above. No code-side reorder satisfies all three
  readers; the stale reader is `macro_build_test.clj:144` alone.
- §8.2 of the design doc predates the U1 ruling and was never updated
  to name the correspondence slot; that doc gap, not the record, is
  what disagrees.

## Proposed change (for approver, not applied)

One line, assertion strength unchanged:

```clojure
(is (= [nil :arity nil] (mapv #(:kind (nth % 10)) events)))
```

Optionally also update §8.2 to append `correspondence` after `error`.

## Verification run

```sh
clojure -M:test -n yin.vm.macro-build-test -v yin.vm.macro-build-test/a-build-expands-between-file-backed-media  # 1 failure (above)
clojure -M:test -n yang.clojure-callsite-test -n yin.vm.encoder-test -n yin.vm.macro-test                        # 49 tests, 447 assertions, 0 failures
```

Node lane not run: no source changed; the JVM battery above covers
every positional reader of the event record.

## Workspace note

No edits made, so no diff to report (`git diff --stat` empty on
tracked files). I did not reuse `/tmp/cc1-p1`: its uncommitted diff is
the pre-landing cc1 work whose landed equivalent is already on master
8cd9fc92, where the nightly failure reproduces.

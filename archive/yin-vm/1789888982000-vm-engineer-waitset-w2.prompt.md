Created-GMT: 2026-09-20 07:02:00 GMT
Created-Local: 2026-09-20 14:02:00 +07 (Indochina Time)
Session-ID: 4b1292c3-fe79-43fa-b169-4e6b860cd9b6
# Task: dao.stream.waitset Phase W2 — the engine becomes a consumer

Role: VM Runtime Engineer (forward-step discipline)

W1 (the sweep library `dao.stream.waitset`) is committed on this branch's
base (`a3eebdd3`, architect-signed). Your unit integrates it: `yin.vm.engine`
becomes the waitset's first consumer.

Read first, in this working tree:
- `docs/design/dao.stream.waitset.implementation-plan.md` — "Phase W2 — The
  engine becomes a consumer", the "What `dao.stream.waitset` is" section
  (the two-function resolver contract), and the divergence register rows
  for invalid answers / poll throws / resolver no-throw
- `src/cljc/yin/vm/engine.cljc:266-400` — `augment-wait-entry` (:266),
  `poll-wait-entry` (:283), `check-wait-set` (:316), the run-loop's call
  site (:381+), and `terminal-resume-outcome` (:397 — the diagnostics
  integration point)
- `src/cljc/dao/stream/waitset.cljc` — the library you integrate
- the engine's tests (`test/yin/vm/engine_test.cljc` especially :254-330) —
  these must pass UNCHANGED

The consensus's non-negotiables for this phase:
- `engine/check-wait-set` STAYS PUBLIC — its signature and callers are
  untouched; internally it invokes `waitset/check` with the VM's resolver
  and store, then constructs ready entries from `:woken` (the existing
  `make-woken-run-queue-entries` stays the engine's).
- `:resolve` is `augment-wait-entry` (nearly verbatim — it maps
  `:cursor-ref`/`:stream-id`/`:datom` through the VM's `:store`);
  `:advance` is the `assoc` at `engine.cljc:355-359` (commit the
  successor/gap cursor). Both synchronous, store updates immutable.
- Delete `augment-wait-entry`, `poll-wait-entry`, and the duplicated sweep
  body inside `check-wait-set`. The two private helpers are deleted; the
  public function remains.
- Waitset diagnostics (`unsupported-reason`, `invalid-answer`,
  `unresolved`) must raise through the VM's error path BEFORE continuation
  restoration — `terminal-resume-outcome` currently checks only
  `#{:next :put}`; extend that handling. A focused NEW test must cover the
  diagnostic path (this is the one test you ADD; no existing test changes).
- The run loop, park semantics, and terminal raising are otherwise
  unchanged. No budget, no scan position: the library sweeps completely.

**The end condition is the product:** the full `yin.vm` suites — engine,
walker, semantic, FFI, macro, parity — green on clj, cljs, and cljd with
ZERO test edits, plus a grep confirming the three inline functions are
gone (`check-wait-set` remains), plus the new diagnostic-path test. Any
existing test you are tempted to edit is a defect in your integration, not
in the test: stop and report it.

Scope: `src/cljc/yin/vm/engine.cljc` and `test/yin/vm/engine_test.cljc`
(the one added test) ONLY. Nothing else — not the waitset library, not
other VM files, not dao.stream. If integration seems to demand more, STOP
and report.

Verification, one single simple command per step (no chaining):
1. `clojure -M:test -n yin.vm.engine-test` — green, unchanged assertions.
2. `clojure -M:test` — full JVM green (1540/168705+your additions is the
   count to beat; nothing existing may break).
3. `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` —
   green (mise Java 21 workaround if the bare command fails).
The orchestrator runs the CLJD lane and the cross-host peers. No staging,
no commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: what changed in engine.cljc, the new test, exact lane
outcomes, grep confirmation, and anything unresolved.

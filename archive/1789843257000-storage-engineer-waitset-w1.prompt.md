Created-GMT: 2026-09-19 18:45:00 GMT
Created-Local: 2026-09-20 02:45:00 +07 (Indochina Time)
Session-ID: e2bdcac2-3d4f-400f-8a73-e8825e773dc9
# Task: dao.stream.waitset Phase W1 — the sweep

Role: Stream & Network Engineer (forward-step discipline)

You are on glm-5.3-flash within a bounded credit window: work tight, read
only what the task names. The waitset plan is architect-signed and
committed (`18664048`); your unit is Phase W1, the pure library.

Read first, in this working tree:
- `docs/design/dao.stream.waitset.implementation-plan.md` — "What
  `dao.stream.waitset` is" (the contract: threaded state, opaque entries,
  the two-function resolver, the complete ordered sweep), then "Phase W1 —
  The sweep" (your build AND test spec, verbatim), then "Decisions"
  (D1-D7 as revised)
- `docs/design/dao.stream.md` — outcomes, retention, the non-blocking rule
- `src/cljc/dao/stream/forward.cljc` — the forward-step discipline to mirror
- `src/cljc/yin/vm/engine.cljc:266-360` — the inline sweep being extracted
  (reference for the resolver's eventual VM shape; do NOT import VM
  specifics into the library)

Scope — exactly two NEW files, nothing else:
- `src/cljc/dao/stream/waitset.cljc` — `empty-waitset`, `park`, `check`
  (and small pure helpers). No host branch. Requires only `dao.stream`.
- `test/dao/stream/waitset_test.cljc` — the W1 test list verbatim:
  per-operation literal classification maps (key sets equal to
  `dao.stream/outcomes-next` / `outcomes-append`), the preserved
  properties, gap recovery between co-waiters, a non-VM store, host-key
  entries returned intact, diagnostics (nil resolve, unknown reason —
  with a counting handle proving no stream operation), result lifecycle
  (repeated check, no injected keys, pr-str round-trip), and the probe
  composition (identity `:advance`; re-park wakes on the NEXT value).

Non-negotiables: `check` is one synchronous state-threaded interpreter
step — never described as pure; the waitset threads as a value
`{:waiting [...]}`; `:woken` is per-call only; the library never adds a
resolved handle or polling cursor to any entry; no budget, no scan
position — every entry polled every call; terminal outcomes leave
`:waiting` under their own qualified keyword. Do not build W2 (the engine
integration) — later unit. If a change outside the two files seems
required, stop and say so.

Verification, one single simple command per step (no chaining, no pipes):
1. `clojure -M:test -n dao.stream.waitset-test` — focused lane green.
2. `clojure -M:test` — full JVM lane green (nothing existing may break).
3. `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` — green.
The orchestrator runs the CLJD lane. Do not stage or commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: what you built, the test list you implemented with counts,
exact lane outcomes, and anything unresolved.

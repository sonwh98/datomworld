Created-GMT: 2026-09-21 05:16:54 GMT
Created-Local: 2026-09-21 12:16:54 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn projection authoring thread)
# Task: architect sign-off — de Bruijn projection D4
Role: architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-21 12:16:54 +07 | Status: active | Rationale: the design's author is the sign-off authority

# Task: architect sign-off — de Bruijn projection D4 (stream adapter)

D4 (forward-step, outcome coverage, reader diagnostic ordering) is implemented
in this worktree (branch debruijn-impl, uncommitted on top of D3's df3a15e5),
reviewed READY by claude-sonnet-5 — a fresh reviewer, no prior lineage — with
its accepted findings applied. Review the final state and grant or withhold
sign-off.

Scope: forward-step as ordinary dao.stream forward interpretation (frames at
:yin/root markers, projects complete graphs, explicit pending writes retried
only by host cadence); blocked/transport-error/invalid-input/pending-output
coverage; adjacent graphs reusing -16 tempids; partial frames at
end-of-stream on every host; the reader diagnostic-ordering obligation from
the D2 sign-off (wrongly-typed slots diagnose deterministically, never host
arithmetic).

Review round (sonnet-5): no P1. Applied: mid-frame :retry fixture (the frame
must survive a blocked source); the catch-all now maps non-ex-info throwables
to {:rule :internal-error :message (ex-message t)} instead of mislabeling
them :invalid-input with {} (and StackOverflowError no longer escapes on
JVM); the adjacent-graphs fixture's second graph is semantically different
((fn [x] 42), with an explicit not= on the two projections); docstring
corrected on :outcome carriage. Deferred as recorded notes: test-breadth
rows; reader canonical-SPELLING gate (awareness item 1 below); dead vector?
guard; :pending seq drift; unchanged-cursor loop (matches dao.stream.forward's
gap fixed point).

Two architect-awareness items sonnet explicitly deferred to you:
1. The reader's slot-shape gate checks domain membership, not canonical
   spelling: a 1.0 arity or a decomposed-NFC :value string can read back
   valid with a matching recomputed hash, so a stored record's content may
   differ from what canonical-value would produce. Pre-D4 question; D3
   canonicalizes at write time.
2. §1's state list names indexed facts, scope stack, occurrence memo and
   output cursor as forward-step state; the implementation keeps the first
   three inside the atomic per-marker projection and tracks no output
   cursor. Sonnet read this as consistent with §1's intent (no state between
   calls except the explicit state; no hidden execution) — rule on the
   literal departure.

Already verified by the orchestrator in this exact worktree on the final
state — do not rerun suites: focused JVM 63/249/0; kondo 0/0; JVM full
1621/169035/0; CLJS full 1540/38897/0; CLJD full 1503 passed.

Deliver exactly one of: SIGN-OFF GRANTED for committing D4 on debruijn-impl
and proceeding to D5, or SIGN-OFF WITHHELD with the blocking list. Please
also rule on the two awareness items.

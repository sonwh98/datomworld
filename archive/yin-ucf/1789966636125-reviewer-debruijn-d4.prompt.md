Created-GMT: 2026-09-21 04:57:16 GMT
Created-Local: 2026-09-21 11:57:16 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 889cc229-7df9-45e4-a988-d1e6b3f7c0c5
# Task: adversarial code review — the de Bruijn projection, phase D4
Role: reviewer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 11:40:00 +07 | Status: active | Rationale: implementation seat; you are a different model family, so independence holds

# Task: adversarial code review — the de Bruijn projection (canonical code form)

You are reviewing phase D4 of a four-phase implementation whose earlier phases
(D0-D3) carry their own review lineage. The authoritative contract is
docs/design/yin.vm.debruijn-projection.md in this worktree — every sentence
is a rule; §1's invariants (explicit state, no callbacks/timers/registries;
blocked inputs are outcomes, never hidden execution) govern everything.

Context you need:
- The namespace under review is src/cljc/yin/vm/debruijn.cljc with tests in
  test/yin/vm/debruijn_test.cljc. The D4 change is uncommitted (git diff on
  both files shows it; no other tracked file moved).
- D0-D3 (committed) established: the :yin.debruijn/* dimension and canonical
  value table; the scope resolver with rightmost-wins duplicate binding; the
  resolver memo keyed on source-eid + complete frame stack; Merkle node
  hashing over tag-specific descriptor-order slots with the consing memo
  keyed on the node's PREIMAGE (never Clojure = — = merges lists with
  vectors and +0.0 with -0.0); settled byte rules (byte-counted UTF-8
  lengths, int64 canonicalization, distinct signed zeros, NFC via the ONE
  seam normalize-nfc); record-scalar canonicalization; the d5 storage
  adapter with a reader that re-verifies content addresses.
- D4's scope: forward-step — the dao.stream forward interpretation that
  frames at :yin/root markers, projects complete graphs, emits projected
  tuples, and reports partial frames at end-of-stream as diagnostics;
  blocked/transport-error/invalid-input/pending-output outcome coverage;
  adjacent graphs reusing -16-based tempids; and the reader diagnostic
  ORDERING obligation (slot-type checks must never let wrongly-typed slots
  reach host arithmetic — they diagnose).
- What D4 must NOT contain: callbacks, timers, schedulers, a waitset, or any
  edit outside the two files. Pending writes stay explicit, retried only by
  host cadence. §8's invalid-answer row belongs to waitset-sweep
  compositions (§6: a waitset is optional for compositions owning several
  independent waiters) — this single-reader adapter not producing one is
  correct, not a gap.

Already verified by the orchestrator in this exact worktree — do NOT rerun
suites; spend your budget on static analysis:
- Focused JVM 61 tests / 240 assertions / 0 failures 0 errors; kondo 0/0.
- JVM full 1619 / 169026 / 0; CLJS full 1538 / 38888 / 0; CLJD full 1501
  passed.

Review for:
1. §6/§7-D4 conformance: framing at the marker, complete-batch projection,
   partial-frame diagnostics at end-of-stream, explicit pending writes.
2. Outcome correctness as CODE: does a blocked write actually return
   :retry/blocked without losing the write? Is terminal idempotence real (a
   terminal outcome re-stepped does nothing)? Do transport/cursor defects
   terminate rather than loop?
3. The §1 invariant scan: any hidden execution, any callback, any state
   outside the forward-step state?
4. The reader diagnostic ordering: wrongly-typed slots diagnose
   deterministically on every host (the reviewer's prior examples:
   :yin.debruijn/bound 5 and a numeric child ref).
5. §8's D4 rows present and honestly asserting; no vacuous truths; the
   blocked/pending fixtures use real outcome semantics, not mocks that
   assume the answer.
6. Box: only the two files changed.

Deliver findings P1/P2/P3 with file:line evidence, then exactly one verdict
line: READY for architect sign-off, or NOT READY with the blocking list.

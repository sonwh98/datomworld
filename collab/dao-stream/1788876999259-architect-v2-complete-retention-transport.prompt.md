Created-GMT: 2026-09-08 14:16:39 GMT
Created-Local: 2026-09-08 21:16:39 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 0dae45ea-4204-49d9-bf35-c56e24cce14e
# Task: plan a complete-retention DaoStream v2 transport
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-08 21:16:39 +0700 | Status: active | Rationale: Architect primary; wrote the contract's *Complete history* section this implements

**Planning task, no write authority beyond `collab/`.** Branch
`dao.stream-redesign-v2`; `docs/design/dao.stream.md` has the new *Complete
history* subsection uncommitted in the tree — read it there, not at HEAD.

## Why this exists

The transactor/index migration is blocked: `dao.space`'s local stream is its
durable log, and **v2 has no transport that can hold one.** `create!` exists
in exactly one v2 namespace (`ringbuffer.cljc:152`); its `valid-spec?`
demands a positive capacity and it evicts. The contract now names wiring a log
onto an evicting transport a host assembly defect, so `dao.space` needs a
transport that declares complete retention.

## The scoping judgement I want you to check, not assume

v1's local streams are opened as `{:dao.stream/type :ringbuffer}` with **no
capacity**, and v1's eviction test is `(and capacity …)` — so they never
evict, and they are **in-memory**, dying with the process. My reading is that
the faithful v2 counterpart is therefore an **in-memory, unbounded,
append-only log**, and that a file-backed durable log would be scope creep
that changes semantics under cover of a migration — durability across restarts
is `dao.jing`'s job, and `dao.space` already composes with it for
publication. Confirm or refute that. If you refute it, say what `dao.space`
actually requires and why the current system is not already providing it.

## What the plan must settle

1. **The name and namespace.** v1 called its durable one `dao.stream.file`.
   This is not that. Propose the namespace and transport type keyword.
2. **The manifest.** `test/dao/stream/conformance.cljc:24` requires a
   declaration: `:dao.stream/type`, `:surfaces` ⊆ `#{:reader :writer
   :closable}`, and per operation `:produces` plus `:exclusions` as
   `{outcome reason}`, where produces ∪ exclusions equals the contract's
   outcome set for that operation and every exclusion carries a non-empty
   reason. Write the exact manifest. `gap` is excluded — give its reason.
3. **The `full` obligation, which the contract now states explicitly.** "A
   transport that excludes `gap` cannot also exclude `full` unless its
   capacity is genuinely unbounded. Refusing and evicting are the two answers
   to the same condition, and a transport that has given up one owes the
   other." Decide: genuinely unbounded and exclude `full` too, or bounded
   with backpressure and produce `full`. Say what "genuinely unbounded" can
   honestly mean for an in-memory log whose real bound is the heap, and what
   the transport does when that bound is reached — because `:dao.stream/ok`
   followed by an OOM is not an outcome.
4. **Conformance.** It must pass `run-conformance-suite` including the
   linearizability check and the abstract model
   (`conformance.cljc:249,340,382`). Say what the suite exercises that a
   never-evicting transport must satisfy differently from the ring buffer, and
   whether the suite itself needs an addition to cover complete retention —
   note that no existing test can observe "never evicts" by construction.
5. **Cursors.** Positions, the `identity` in cursor values, attach/frozen-tail
   semantics if any, and what `:oldest` means on a transport that never
   evicts (the contract now says a fresh `:oldest` *is* the origin there).
6. **What it does not do.** Explicitly: no durability across process restart,
   no eviction, no attach semantics you do not need. Name what a later durable
   transport would add so this one is not mistaken for it.
7. **Phasing and the handoff.** This lands before the transactor plan is
   revised. Say exactly what the transactor plan must then require of it, in
   one sentence the next brief can quote.

## Constraints

- clj, cljs (Node) and cljd. Mind the reader-conditional trap:
  `#?(:clj …)` alone does not exclude from the cljd build.
- Do not modify `dao.stream.ringbuffer` or the contract.
- Proportionate: this is one transport, not a subsystem.

Emit the plan to stdout with the Completed-GMT/Local, Coding-Agent,
Session-ID header.

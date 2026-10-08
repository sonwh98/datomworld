Created-GMT: 2026-09-19 17:51:05 GMT
Created-Local: 2026-09-20 00:51:05 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: architecture review of `docs/design/dao.stream.waitset.implementation-plan.md`

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-20 00:51:05 +07 | Status: active | Rationale: Reserved architectural review role per team roster

Perform a read-only architecture review of `docs/design/dao.stream.waitset.implementation-plan.md`.

Read first:
- `docs/design/datom.world.md` (the master architecture, core philosophy, and 6 non-negotiable invariants)
- `docs/design/dao.stream.md` (the governing stream contract, non-blocking rule, outcomes, and readiness extension)
- `docs/design/dao.stream.waitset.implementation-plan.md` (the implementation plan under review)
- `docs/design/dao.await.md` (the await and machine resumption contract)
- `src/cljc/yin/vm/engine.cljc` (the existing inline multiplexed sweep: `augment-wait-entry`, `poll-wait-entry`, `check-wait-set`)
- `src/cljc/dao/stream.cljc` (the stream protocols and outcome declarations)
- `src/cljc/dao/stream/observer.cljc` (single-stream observer pattern)
- `src/cljc/dao/stream/forward.cljc` (the forward-step interpreter discipline)

Evaluate the following architectural dimensions:

1. **Foundational Invariants (datom.world.md)**:
   - Invariant 1 (no hidden global state): Does the plan maintain pure data-driven state (`{:waiting [] :woken []}`) without namespace-global state?
   - Invariant 2 (no implicit control flow): Does `check` only classify and partition entries without triggering hidden execution?
   - Invariant 3 (no raw callbacks): Verify that woken entries are returned as data and never dispatched via callbacks. Assess the `nudge!` design and host cadence mechanisms in Phase W3 (`LinkedBlockingQueue` on CLJ, microtask + single armed timer on CLJS/CLJD) against callback/timer invariants.
   - Invariant 4 (no shared mutable state): Is the shared-cursor write-back between co-waiters cleanly expressed as a pure store transition `(check waitset resolver store) -> {:waiting ... :woken ... :store store'}`?
   - Invariant 5 (no layer collapsing): Does `dao.stream.waitset` remain strictly an interpreter above the stream medium, preserving total substrate isolation without modifying `dao.stream` protocols or transports?
   - Invariant 6 (no assumed graphs): Are streams treated purely as append-only data logs?

2. **Boundary and Responsibility Decomposition**:
   - Does extracting the wait set from `yin.vm.engine` leave the VM semantics intact while keeping the wait-set library free of VM registers, continuations, and task scheduling?
   - Is the refusal of `:resume`, ready queues, priorities, and fair queuing justified and aligned with architectural minimalism?

3. **Total Classification & Contract Fidelity**:
   - Does the plan correctly classify all outcomes declared in `dao.stream`?
   - Does waiting strictly on `blocked` (reader) and `full` (writer) adhere to the contract?
   - Is treating uninterpretable or undeclared outcomes as terminal rather than waiting sound?

4. **Phase Structure and End Conditions**:
   - Are the phases W0 through W5 properly decomposed with clear completion criteria?
   - Are the divergence register items justified?
   - Are there any architectural defects, unstated assumptions, or contradictions with `dao.stream.md` or `dao.await.md`?

Distinguish architectural defects from implementation gaps or intentionally deferred work. Do not edit files. Produce the complete deliverable now without waiting for a human.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity (blocking / should-fix / note) | file:line | invariant/evidence | recommended correction.
Also confirm the requested properties that passed review, and provide a final VERDICT line: `VERDICT: <sound | sound-with-findings | unsound>`.

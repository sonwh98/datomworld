Created-GMT: 2026-09-07 09:01:38 GMT
Created-Local: 2026-09-07 16:01:38 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing plan — revision 8, the shared observation unit
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 16:01:38 +07 | Status: active | Rationale: resumed author session; owns Decision 1, which this revision reshapes

Revision 7 is at
`collab/architect-dao-jing-invariants-r7.claude-fable-5-1.findings.md`. Its
invariants list and its framing are right and stay. One thing it does not
mention at all, and should: **`yin.vm.stream-observer` already implements
this pattern**, and the user has asked that DaoJing use it rather than
reinvent it.

Produce revision 8 complete, as the body of your final response. Do not edit
files.

## The observation, which is the user's

`yin.vm.stream-observer` exists because V7 moved cursor-holding and
stepping *out of the VM*. `create-vm` now rejects `:in-stream`
(`ast_walker.cljc:814-817`); the evaluator "executes already-loaded work
only". `run-on-stream` takes `ready?`, `load-program`, `run-vm` and owns the
observe/load/run cycle, inspecting no evaluator field.

That is the same pattern DaoJing's observer needs, with:

| `run-on-stream` | DaoJing |
|---|---|
| `ready?` | can the store accept another payload — for a synchronous backend, always |
| `load-program` | `materialize!` |
| `run-vm` | nothing; materialization completes in the load step |

And note what falls out for free — `run-on-stream`'s docstring
(`stream_observer.cljc:141-143`): "A `load-program` that throws propagates
before any successor session is published: the caller retains the previous
observer cursor and the same malformed batch is retried on the next call."
**That is invariant E5.** The VM gets effect-before-cursor-commit from where
the pattern puts the publish, not from a rule anyone remembered. Say so in the
plan: E5's provenance is structural, and it is the strongest argument for
sharing the unit rather than restating the rule in two places.

## What to decide

Add a decision — extract the observation unit to a neutral namespace, proposed
`dao.stream.observer`, with **policy at the edges**. But the split is
yours to draw, and these are the questions it turns on:

1. **What belongs in the shared unit.** Single-step observation over a reader
   and a cursor, total over `dao.stream/outcomes-next`, returning **data
   for all seven outcomes** rather than throwing. Both current policies move
   out: the gap auto-advance (`stream_observer.cljc:106-110`) and the
   terminal throw (`75-81`, which also loses the read result map — DaoJing's
   E9 needs it under `:result`).
2. **Does `attach` belong in it?** The VM's `attach` calls a unary capability
   and mints at `:dao.stream/oldest` itself. DaoJing takes already-attached
   handles with composition-minted cursors (E2, E3). Shared, parameterised,
   or left to each caller?
3. **Is `:ingress-gaps` in the shared state?** It is a VM policy count;
   DaoJing counts nothing and its member state is `{:stream :cursor :status}`.
   If the shared state carries it, VM policy has leaked into the shared unit.
4. **What each caller keeps.** `yin.vm.stream-observer` keeps
   `run-on-stream` — a session loop that runs until not-ready — plus its
   policy: advance past a gap, count it, treat terminals as errors. DaoJing
   writes a different coordination: **one payload per call**, round-robin
   across a pool with a fairness index (E6), plus its policy: report and
   never auto-resync, defects as data with `:member` and `:result` (E9).
   State plainly that the coordination differs and only the step is shared.
5. **The cost to a shipped namespace.** `test/yin/vm/stream_observer_test.cljc`
   has 17 tests, and at least three pin the behaviour being moved:
   `observe-next-gap-recovers-and-counts-test`,
   `observe-next-throws-on-terminal-and-unexpected-outcomes-test`, and
   `a-failing-loader-leaves-the-old-cursor-for-a-retry-test`. Say which
   change, which move to the shared unit's own suite, and which stay as
   evidence that the VM's policy still holds above the new seam. Nothing in
   the VM's observable behaviour should change.
6. **Layering.** Storage must not depend on `yin.vm.*`; a neutral home under
   `dao.stream` removes that objection and makes both callers depend only
   on the contract.

Then rework Decision 1 to build DaoJing's observer *on* that unit — pool,
scheduling, and materialize-as-load-step — rather than from first principles,
and reconcile the E-group invariants with the split: say for each which side
of the seam guarantees it.

Everything else in revision 7 stands: the invariants list with its
`[D]`/`[T]`/`[T→D]`/`[T✗]` markings, Decisions on the durable log and the
deferred remote store, the phasing, the boundary. Phase ordering may need one
more phase for the extraction; if so, say where it lands and what proves it.

End with a short list of what changed from revision 7 and why.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

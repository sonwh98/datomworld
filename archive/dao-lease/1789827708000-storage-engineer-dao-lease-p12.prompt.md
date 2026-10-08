Created-GMT: 2026-09-19 14:22:26 GMT
Created-Local: 2026-09-19 21:22:26 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: d960a79e-633c-4b1c-bd90-99b60837402e
# Task: dao.lease Phase 1+2 — the vocabulary and the judge

Role: DaoSpace & DaoJing Storage Engineer (forward-step discipline)

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-19 21:22:26 +07 | Status: active | Rationale: flat subscription, off-peak hours; storage/transactional-concurrency strengths; plan is fully specified

Implement Phases 1 and 2 of `dao.lease` in the git worktree
`/Users/sto/workspace/worktree-lease` (branch `lease-impl`). Work only inside
that directory.

Read first, in this order:
- `/Users/sto/workspace/worktree-lease/docs/design/dao.lease.md` (operative contract — every sentence is a rule)
- `/Users/sto/workspace/worktree-lease/docs/design/dao.lease.rationale.md` (why; binds nothing)
- `/Users/sto/workspace/worktree-lease/docs/design/dao.lease.implementation-plan.md` (your build spec — §0 corrections, §1, §2 invariants V1–V5 and J1–J11, §3 decisions D1–D6, §4.1 Phase 1, §4.2 Phase 2)
- `/Users/sto/workspace/worktree-lease/src/cljc/dao/stream/forward.cljc` (the `forward-step` discipline D2 requires)
- `/Users/sto/workspace/worktree-lease/src/cljc/dao/stream/observe.cljc` (effect-before-commit, D5)
- `/Users/sto/workspace/worktree-lease/src/cljc/dao/stream/ringbuffer.cljc` (the test medium)
- `/Users/sto/workspace/worktree-lease/src/cljc/dao/stream.cljc` (outcomes and the reader/writer surface)

Scope — exactly two files, nothing else:
- `src/cljc/dao/lease.cljc` (new)
- `test/dao/lease_test.cljc` (new)

If any change outside these two files seems required, stop and state why in
the final report instead of editing. Do not stage or commit; leave the
working tree dirty for orchestrator verification.

Build exactly what §4.1 and §4.2 name. Non-negotiables:
- Facts are plain maps carrying exactly one of `:dao.lease/status` or
  `:dao.lease/event`; never `dao.space` datoms (D1).
- The judge is one pure `judge-step` threading an immutable ledger; no
  scheduler, callback, registry, mutable state, or host clock anywhere in
  this scope (D2, C5). *Now* comes only from drained tick readings.
- Tests script ticks and facts by hand into `dao.stream.ringbuffer`
  cursors; never read a wall clock; never use the Phase 4 reference tick
  source (D3 — do not build it in this phase).
- Validity is a pure `valid?`/`defective?` gate applied before a fact
  touches the ledger; a defective fact is dropped, not applied (D6).
- Record `:lapsed` only after a reclaim that reports success, on
  `:dao.stream/ok`, using `observe/step` for the pair (D5, J8).
- Cause classification order is exactly the contract's: pending, then
  `:release`, `:cap`, `:policy`, `:silence`; unknown evidence lapses for
  `:silence` only a full duration after its resumed reading (J7, J9).
- Cursor retirement on `:dao.stream/end`; pass abort before classify on
  `:dao.stream/transport-error` (J10).
- Do not build Phase 3 (holder) or Phase 4 (composition, make-*
  constructors, reference tick source) — later unit.

Verification, one single simple command per step (no chaining, no pipes,
no `&&`, no loops — compound commands are denied in your headless mode):
1. `bb test:clj` — must pass, including the new `dao.lease-test` deftests.
2. `bb test:cljs` — must pass; confirm `Testing dao.lease-test` appears in
   the Node output.
If a lane fails, fix and rerun it. Do not run `bb test:cljd` — the
orchestrator runs that lane. The JVM, CLJS, and CLJD lanes must all stay
green for everything that already exists.

Produce the complete deliverable now without waiting for a human. Begin
the final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: changed files, the exact outcome of each test command with
assertion counts, any invariant you could not satisfy and why, and any
incomplete work. Do not claim edits or tests that did not occur.

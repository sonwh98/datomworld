Created-GMT: 2026-09-20 07:37:00 GMT
Created-Local: 2026-09-20 14:37:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session, now covering the W2 delta)
# Task: adversarial review of dao.stream.waitset Phase W2 (engine integration)

You reviewed W1 (ready, 4 non-blocking notes). The implementer
(glm-5.3-full this time) has since integrated the library into the engine.
Orchestrator verification: engine focused 19 tests / 82 assertions / 0
failures (18 existing UNCHANGED + 1 new); JVM full 1541 / 168704 / 0 (the
implementer measured its own baseline on HEAD before changes:
1540/168697 — delta is exactly its added test); CLJS 1457 / 38573 / 0;
CLJD run by the orchestrator: zero yin.vm failures (only the 29
pre-existing voxel failures from the un-merged voxel-fix branch).

Under review — the delta in this working tree
(`/Users/sto/workspace/worktree-w2`):
- `src/cljc/yin/vm/engine.cljc` — `check-wait-set` stays public with the
  same signature, now invoking `waitset/check` with a `{:resolve
  :advance}` resolver over the VM store; `augment-wait-entry` and
  `poll-wait-entry` deleted; `make-woken-run-queue-entries` stamps
  `:status`; waitset diagnostics raise before restoration
  (`terminal-resume-outcome` + `throw-terminal-resume!` + the new
  `waitset-diagnostics` set)
- `test/yin/vm/engine_test.cljc` — one added test
  (`waitset-diagnostics-raise-before-restoration-test`) + a host-neutral
  `throws-ex-data` helper; pure addition

against the plan's Phase W2 and the consensus items (1, 7, and the W2
compatibility note from your W1 review).

Priority checks:
1. Resolver fidelity: is `:resolve` truly `augment-wait-entry`'s behavior
   (cursor-ref/stream-id/datom through the store, nil handling) and
   `:advance` the `engine.cljc:355-359` assoc? Any semantic drift in gap
   write-back, shared-cursor co-wake, or writer value resolution?
2. The unchanged-suite condition: grep-verified helpers gone — but is any
   BEHAVIOR silently different (ordering, terminal raising, `:cursor nil`
   on terminal readers vs the old pre-poll cursor — the implementer
   disclosed this and called it harmless; verify)?
3. Diagnostics before restoration: can an unsupported-reason or
   unresolved entry still reach `restore-fn`?
4. Public boundary: `check-wait-set`'s signature and callers untouched;
   the only transport calls in the engine remain the immediate paths.

Do not edit. Do not rerun suites. Challenge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Report findings as P0-P3 | file:line | evidence | concrete fix. State
explicitly whether W2 is ready for architect sign-off.

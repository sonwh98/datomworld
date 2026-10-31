Created-GMT: 2026-09-08 07:00:00 GMT
Created-Local: 2026-09-08 14:00:00 +07 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: review P2 dao.jing observer and test migration

Role: Routine Review

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-08 14:00:00 +07 | Status: active | Rationale: Independent cross-family reviewer (GLM wrote the code)

Perform a routine code review of the `dao.jing` P2 implementation.
The changes are currently uncommitted in the working tree. Run `git diff HEAD` to view the changes.

Read first:
- `docs/design/dao.jing.implementation-plan.md` — specifically P2 and invariants A-E.
- `docs/design/dao.jing.md` — specifically *Cursor tracking and recovery* (for Decision 1 signal table).

Evaluate the diff against the P2 contract:
1. `dao.jing` observer rebuilt on `dao.stream.observe/step`.
2. The core invariants (A, B, C, D) are preserved.
3. `dao.jing.mem` updated to drop the D4 pins.
4. `dao.space`'s two writer seams (`index.cljc` and `transactor.cljc`) moved to v2.
5. Old observer code and `dao.stream` dependency removed from `dao.jing`.
6. Tests rewritten to run over v2 streams/ringbuffers (`jing_test`, `mem_test`, `dht_test`, and the five `dao.space` tests).

Check for correctness, adherence to the invariants, and portability across clj/cljs/cljd.
Distinguish P0 (blocking correctness/safety), P1 (blocking architecture/invariant), P2 (blocking code quality/maintainability), and P3 (non-blocking suggestions). Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | finding | recommended correction.
If there are no blocking findings, clearly state "ready to commit".

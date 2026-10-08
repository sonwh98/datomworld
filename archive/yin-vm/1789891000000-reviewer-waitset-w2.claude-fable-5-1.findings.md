Completed-GMT: 2026-09-20 07:50:52 GMT
Completed-Local: 2026-09-20 14:50:52 +07

# Adversarial review: dao.stream.waitset Phase W2 (engine integration)

**W2 is ready for architect sign-off.** I found no P0, P1 or P2. The four P3 notes below do not block sign-off. I edited nothing and did not rerun suites.

## What holds

**1. Resolver fidelity.**
- **Reader branch.**
  - `:resolve` reproduces `augment-wait-entry` for every entry the VM parks.
  - The stream is `(or (:stream entry) (get store (:stream-id cursor-data)))`.
  - The cursor is read from the store again on every poll (`engine.cljc:286-291`).
- **Writer branch.**
  - It resolves `:stream-id` through the store.
  - It supplies `(:datom entry)` as the value, and the old `:put` poll appended the same thing.
- **`:advance`.**
  - It is the old write-back `assoc`.
  - It is guarded on a cursor id, and the library calls it only when a poll returned a cursor.
  - Gap write-back, the co-wake on a shared cursor, and wake order therefore sit in `waitset/check`, where W1 proved them.

**2. Behaviour differences.**
- **`:cursor nil` on a terminal reader.**
  - I verified the disclosed change and it is harmless.
  - The old sweep merged the resolved copy into the entry, so an `end` or error wake carried the pre-poll cursor. `make-woken-run-queue-entries` turned that cursor into a store update that rewrote the same value.
  - Now the cursor is nil and the `(and cursor-ref cursor)` guard yields no update (149).
  - No restore function reads `(:cursor entry)`. A grep over `semantic.cljc` and `ast_walker.cljc` finds none.
- **A smaller difference in the same direction.**
  - The old code took `(:store-updates raw)` from the entry when one was present.
  - The new woken result never carries that key, so the update is always computed.
  - No park site puts `:store-updates` on a wait entry.
- **`:status`.** It used to ride on the merged resolved copy. It is now stamped explicitly, and the terminal check reads the same key.

**3. Diagnostics before restoration.**
- `terminal-resume-outcome` tests the diagnostic set before it tests `:reason`. An unsupported reason therefore cannot fall through to the restore (384-388).
- `resume-from-run-queue` is the only place an entry is popped from the ready queue.
- `semantic.cljc:477,484` and `ast_walker.cljc:509` both delegate to it.
- The other `:ready-queue` mentions (`completion.cljc`, the handoff demo) only inspect the queue.
- No diagnostic can reach a restore function.
- The new test asserts that the restore function ran zero times.

**4. Boundary.**
- `check-wait-set` keeps its one-argument signature and its state-in, state-out contract.
- Its callers (`semantic.cljc:476`, `ast_walker.cljc:665`) are untouched.
- The engine's only transport calls are the immediate paths at `engine.cljc:190` (`append!`) and `:233` (`next`).

**W1 compatibility note.** The VM's streams are ring buffers and answer well-formed outcomes, so the stricter validation does not affect them. The 18 existing engine tests pass unchanged, by the orchestrator's count.

## Findings

**P3 | `engine.cljc:286-293` | A `:next` entry with no `:cursor-ref` resolves differently from before.**
- The old code polled it with `(:cursor entry)`.
- The new resolver takes the writer branch for it. That branch supplies no `:cursor`, so the library calls `next` with a nil cursor.
- No VM park site builds such an entry. Both builders set `:cursor-ref` (`ast_walker.cljc:121,357`, `semantic.cljc:171`). This is a drift only for hand-built entries.
- Fix: branch on `:reason` inside `:resolve` and pass `(:cursor entry)` through for that case, or state in the docstring that a reader entry must carry a `:cursor-ref`.

**P3 | `test/yin/vm/engine_test.cljc` (the new test) | The third diagnostic has no engine test.**
- `:dao.stream.waitset/invalid-answer` is in `waitset-diagnostics`.
- The test covers only `unsupported-reason` and `unresolved`.
- Fix: add a case with a reified handle that answers a non-map. Assert it raises before restoration.

**P3 | `engine.cljc:393-394` | The diagnostic's error data carries the whole entry.**
- That includes the continuation registers `:k` and `:env`.
- It is useful for debugging but large. If the error data is ever serialized or logged, it may carry host values.
- Consider sending only `:reason`, `:stream-id` and the cursor id, as the two sibling errors do.

**P3 (already in the code before W2; not part of this delta) | `engine.cljc:145-152, 420` | A woken reader's store update can move a shared cursor backwards at resume time.**
- Two readers A and B share a cursor. In one sweep A wakes with successor c1 and B with c2, and the sweep leaves the store at c2.
- A's ready entry carries `{cursor-id … :cursor c1}`. Merging it when A resumes moves the store back to c1 until B resumes.
- If A's continuation reads the shared cursor again before B resumes, it re-reads B's value.
- The old code did the same, so this is not a W2 regression.
- I would record it for a later plan. The sweep already commits the cursor through `:advance`, so the resume-time update for readers looks redundant.

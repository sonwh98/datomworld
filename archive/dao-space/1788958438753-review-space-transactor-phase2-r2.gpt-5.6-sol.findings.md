I’m using the repository’s code-review skill for this correction pass. I’ll inspect only the revised diff and the affected tests/docs, without writing files or rerunning the reported lanes.

The API and test corrections are genuine. One sentence in the rewritten crash section still conflates an agent stopping inside a live process with the process itself failing; I’m checking that against the memory-log and intake lifetimes before assigning severity.

Completed-GMT: 2026-09-09 12:55:52 GMT  
Completed-Local: 2026-09-09 19:55:52 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Phase 2 correction review

## P1 — The revised crash section still promises behavior the process-lifetime streams cannot provide

**File:** `docs/design/dao.space.md:439-451`  
**Blocks the commit:** yes.

The section now correctly states that unpublished memory-log contents do not survive process failure. Two following claims undermine that correction:

- Lines 439-442 say a reader tailing a “crashed writer’s” local stream reaches `blocked` and waits, and that the same applies to an observer over the intake stream. After process failure, both in-memory handles are gone; nobody can continue tailing them. This is only true when a writer task stops while its host process and stream remain alive.
- Lines 446-451 place retrying an interrupted publication and a causality-carrying checkpoint under restart recovery. After process failure, unpublished local history may be gone and therefore cannot necessarily be re-emitted. Moreover, the checkpoint in `dao.space.transactor.md` is proposed to reduce O(history) replay; it is not a durability mechanism for unpublished history.

**Concrete change:** distinguish the two failure scopes:

- Within a live process, a stopped writer leaves its still-owned handles readable at their tails, and publication retries are idempotent while the memory-log remains available.
- On process failure, local and queued-but-unmaterialized data may be lost; recovery has only fully materialized published state.
- Remove the statement that the future replay checkpoint relieves unpublished-history loss, or explicitly say that it optimizes causality replay and does not add durability.

Everything else in the rewritten section—atomic transaction records, manifest-last publication, and durability beginning only after DaoJing materialization—matches the implementation.

## Corrections confirmed

- `docs/design/dao.space.md:182` now correctly says `q` returns a bounded value and opens or closes nothing.
- `docs/design/dao.space.md:485-488` now names `transactor/append!`.
- `docs/design/dao.space.schema.md:246-250` correctly describes a schema wrapper over a transactor value created by `create!`.
- `docs/dao.space.stigmergy.md:3-7` correctly names the transactor’s explicit operations.
- The old append-only-file, file-reopen, and `:daostream/gap` claims are gone.
- The dated plan corrections correctly leave `index_test`’s `stream-values` and descriptor carrier for Phase 3. Their current callers are published-adapter tests that still operate on the v1 adapter.
- `transactor/create!` checks `map?` before destructuring or calling `contains?`, producing the intended diagnostic for non-map input.
- The non-map assertion genuinely exercises that guard.
- `NonOutcomeAppendStream` genuinely exercises the malformed-return branch. Its assertions pin the exact `transport-error` fold, preserve the raw `:boom` answer, and prove via the successful retry at `t=0` that the watermark did not advance.
- The new double delegates the actual bound `val`; it introduces no repeat of the earlier `ThrowingAppendStream` mistake.
- `git diff --check` is clean.

## Verdict

**Phase 2 is not yet ready to commit.** The code and added tests are cleared, but the remaining process-crash claims in `dao.space.md` must be corrected. The still-running CLJD lane also remains an external readiness gate.

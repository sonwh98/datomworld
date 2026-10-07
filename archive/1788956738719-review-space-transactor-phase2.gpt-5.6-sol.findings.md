I’m using the repository’s review checklist for the full swap diff. I’ll trace the transactor state machine first, then schema’s result/lock bridge, the migrated test doubles, and finally verify that every documentation and namespace change is independently valid before Phase 3.

I’m finishing the static pass on the migrated doubles, schema assertions, permanent documentation, and Phase 3 residue. I’ll treat the still-running CLJD lane as an external readiness gate, not rerun it.

The code paths themselves are coherent: the watermark and schema-state transitions are gated on `:dao.stream/ok`, and the wrapper lock orders transact, publish, and close consistently. I did find contradictory v1/durability prose remaining inside documents this phase edits, plus one declared outcome branch with no executable pin.

Completed-GMT: 2026-09-09 12:30:39 GMT  
Completed-Local: 2026-09-09 19:30:39 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Phase 2 review

## P1 — The edited design documents still contradict the migrated implementation

**Files and lines:**

- `docs/design/dao.space.md:182`
- `docs/design/dao.space.md:422-439`
- `docs/design/dao.space.md:476-477`
- `docs/design/dao.space.schema.md:246-248`
- `docs/dao.space.stigmergy.md:3-6`

**Blocks the commit: yes.**

The new material correctly says the local stream is a process-lifetime `memory-log`, that durability begins with publication into `dao.jing`, and that the transactor is a value rather than a stream. Several surviving passages say the opposite:

- The *Fault Tolerance* section says the write path uses persistent append-only files, flushed datoms survive crashes, writers reopen those files, reads use `ds/next`, and eviction appears as `:daostream/gap`. None describes the committed `memory-log` composition. It directly contradicts the new text at lines 390-405.
- The worker-loop introduction still says it writes through `ds/append!`; the example below now uses `transactor/append!`.
- The schema design still calls the inner object a registered `:transactor` stream wrapper.
- The stigmergy document’s opening definition still says agents write through `ds/append!`.
- Independently, `dao.space.md:182` still says `q` returns a closed result DaoStream, although query now returns a bounded query value. This is older query residue, but this phase is already modifying the same design document and should not leave its architectural summary false.

**Concrete change:** rewrite the fault-tolerance section around the actual two-stage model:

- un-published memory-log contents do not survive process failure;
- published content in `dao.jing` is durable;
- rebuilding after restart starts from published state or a future causality-carrying checkpoint;
- remove the v1 `ds/next`, file-reopen, and `:daostream/gap` claims.

Then replace the remaining `ds/append!`, `:transactor` stream-wrapper, and result-DaoStream descriptions with the current named operations and value terminology.

## P2 — D3’s malformed-answer fold has no executable test

**Files and lines:**

- `src/cljc/dao/space/transactor.cljc:134-155`
- `docs/design/dao.space.transactor.md:89-103`
- `test/dao/space/transactor_test.cljc:43-95,495-525`

**Blocks the commit: no, independently.**

The implementation is correct: a result that fails `stream/valid-outcome?` becomes:

```clojure
{:dao.stream/outcome :dao.stream/transport-error
 :dao.stream/answer answer}
```

and the watermark remains unchanged. However, the tests cover `full` and a thrown append but never a returned malformed value. Consequently, the permanent outcome table and the full “advances iff ok” claim are not completely pinned.

**Concrete change:** add a reader/writer double whose first append returns something such as `:boom` and whose retry delegates to the memory-log. Assert the exact folded result, including `:dao.stream/answer`, and assert that the retry commits at the original `t`.

## Verified and cleared

- **D3 outcome discipline:** Correct in every implementation branch. Only `:dao.stream/ok` advances `next-t`; every valid non-ok append result is returned unchanged; malformed returned values fold to `transport-error`; thrown exceptions propagate before any watermark update.
- **D10/T19/T20:** Correct. Schema computes the proposed state before emission, installs it only after an inner `ok`, returns non-ok outcomes unchanged, and reconstructs the exact legacy success shape `{:result :ok :t … :datoms …}`. Existing successful-result assertions therefore remain valid by construction.
- **Schema refusal test:** `failed-inner-append-leaves-wrapper-state-unchanged` proves real properties: the wrapper state is unchanged after `full`, the retry uses the same `t`, successful state installation occurs afterward, and the legacy schema result shape survives.
- **Closedness bridge:** Sound. Schema transact, publish, and close use the same wrapper-state lock. Publish either completes before close or is rejected after close; it cannot cross the close boundary. `SchemaWrapper.close!` preserves `{:woke []}` and closes only its owned inner transactor.
- **ThrowingAppendStream:** The fix is correct. Its retry now delegates the bound `val`; the old `_val`/`val` mismatch did append `clojure.core/val`. No equivalent mistake remains in the other migrated doubles: intentionally unused parameters stay unused, while all delegating doubles pass their actual bound value or packet.
- **Index snapshot:** Both cursor minting and every `next` result are validated before interpretation. Incremental flattening preserves first-defect ordering and does not read beyond a malformed element. `blocked` and `end` terminate; every other well-formed outcome reaches the complete-retention precondition failure.
- **Phase independence:** The Phase 3 residue is properly isolated. The remaining v1 operations in `index_test` and `stigmergy_test/sources`, and the published adapter in `index.cljc`, still compile and retain their old behavior. Nothing in Phase 2 requires Phase 3 to land.
- **Portability:** The production changes introduce no JVM-only classes or operations into shared execution paths. Locking remains JVM-only with the existing default path for other hosts; the concurrency tests provide explicit non-JVM branches.
- **Structural residue:** `transactor.cljc` has no v1 require, `DaoStreamLog`, or `defopen`; the stated `ds/` reductions match the Phase 2 boundary.

## Verdict

**Phase 2 is not ready to commit because the edited permanent documentation contains direct contradictions about durability and the transactor API.** The implementation itself is sound on static review. After correcting that P1, adding the malformed-answer test is recommended but is not independently blocking.

The still-running CLJD lane remains an external readiness gate; I did not rerun any suite.

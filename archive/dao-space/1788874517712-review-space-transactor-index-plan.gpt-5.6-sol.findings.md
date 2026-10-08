I’m using the repository’s architecture-review checklist, treating the plan’s phase boundaries and test-survivor table as executable contracts. I’ll trace compilation closure, wrapper readership, closedness semantics, capacity determinism, and every Phase 5 test move without running suites or changing files.

The plan’s architecture is internally coherent so far, but two areas need exact source-level proof: whether Phase 3 really migrates every constructor/read call in one compilable set, and whether the capacity regression test can logically detect a lowered shared constant. I’m tracing those now, along with the ten adapter test properties.

I’m continuing the static architect review with the repository’s code-review checklist. I’m focusing the remaining pass on the v2 cursor semantics, Phase 5’s named test survivors, and whether Phase 3’s inventory is genuinely compilation-closed.

Two substantive issues are now confirmed: the retention test cannot work with an `:oldest` cursor minted after eviction, and Phase 3 does not state how `schema/transact!` handles the new non-OK result data. I’m checking the plan’s inventories for any similarly concrete contradictions before I rank the report.

Completed-GMT: 2026-09-08 13:43:02 GMT  
Completed-Local: 2026-09-08 20:43:02 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect review: transactor + index v2 migration plan

## Verdict

**Not ready to implement.** The plan has six blocking defects, including one foundational mismatch between its full-history invariant and DaoStream v2 cursor semantics. D1 itself is sound, and most of Phase 5’s adapter-test disposition is correct, but the plan needs another revision before code begins.

## Findings

### P0 — Minting `:oldest` cannot detect already-evicted history

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:156), especially lines 160–186, 278–283, and 424–475
- [ringbuffer.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/ringbuffer.cljc:55)
- [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:471)
- [query_test.cljc](/Users/sto/workspace/datomworld/test/dao/space/query_test.cljc:359)

**Blocks the plan: yes.**

The plan says `derive-next-t` and index publication read from “position zero,” but its proposed `observe/snapshot` mints a fresh `:dao.stream/oldest` cursor. In v2, `:oldest` means the earliest position currently retained, not the beginning of the logical stream. After eviction, the ring buffer returns a cursor at `:first`; reading from it succeeds over the retained suffix and reports no gap.

The existing query test explicitly records this fact: a fresh `:oldest` cursor cannot observe a gap on a quiescent ring buffer. Its gap test therefore uses a scripted reader.

Consequences:

- A reopened transactor can derive `next-t` from an incomplete suffix without noticing lost history.
- `publish-index!` can silently publish an incomplete index.
- `retained-history-survives-reopen-at-capacity` cannot produce the promised gap by creating a second transactor after eviction.
- The assertions at plan lines 424–430 and 462–475 are false under the committed v2 contract.

**Concrete change:** choose and document an actual full-history mechanism. The cleanest v2-compatible option is to retain an origin cursor minted before the first append and pass it explicitly to full-history consumers. That likely requires:

- An `observe/snapshot` arity accepting a caller-owned cursor.
- The transactor spec to receive and retain the local history’s origin cursor.
- `index/snapshot-datoms`/`publish-index!` to receive that cursor rather than minting `:oldest`.
- The eviction test to mint the origin cursor before overflowing the buffer and then prove that replay reports a gap.

Alternatively, require a transport that provably never evicts. A large ring-buffer capacity alone is not proof of full retention.

The control leg also needs a fixed required-history count. “Repeat at `local-capacity`” does not ensure that lowering the constant below some other test’s history size will fail.

### P1 — Schema state advances when the transactor append fails

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:194), especially D6 line 209 and Phase 3 lines 379–380
- [schema.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/schema.cljc:1099), especially lines 1112–1114

**Blocks the plan: yes.**

D3 changes `tx/transact!` so `full`, `closed`, `invalid-value`, and `transport-error` return as data. But `schema/transact!` currently installs `next-state` unconditionally after calling the inner transactor:

```clojure
result (tx/transact! ...)
(reset! lock next-state)
result
```

Changing only the receipt shape, as D6 says, leaves schema believing an unpersisted transaction succeeded. Its uniqueness, lookup-ref, current-value, and schema-epoch state can then diverge from the local log.

**Concrete change:** Phase 3 must state that `schema/transact!`:

- Installs `next-state` only when the result outcome is `:dao.stream/ok`.
- Returns every conforming non-OK outcome unchanged without mutating wrapper state.
- Continues to leave state unchanged when the inner call throws.

Add a schema test using a local writer that returns `full` before succeeding; assert wrapper state is unchanged after failure and that retry commits at the same transaction time.

### P1 — Phase 3’s test migration inventory is not closed

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:382)
- [transactor_test.cljc](/Users/sto/workspace/datomworld/test/dao/space/transactor_test.cljc:25)

**Blocks the plan: yes.**

The plan names only `FailingAppendStream`, `ThrowingAppendStream`, and the close-linearization `reify`. The test file also has migration-relevant:

- `ReaderOnlyStream`
- `WriterOnlyStream`
- `RecordingAppendStream`
- The concurrency `slow-local` reify
- The close-linearization reify
- Every `ds/append!`, `ds/close!`, `ds/closed?`, and `ds/next` call whose target is the transactor value
- V1 sequence helpers used to inspect local streams

Leaving the first three records or the concurrency reify on v1 makes them fail the new v2 reader/writer validation. Leaving protocol calls on `log` fails because D1 removes the protocols from the transactor.

**Concrete change:** make Phase 3’s inventory exhaustive: migrate all five records/reifies that represent local handles, both JVM reifies, all transactor protocol invocations, the read helpers, namespace prose, and finally require zero `ds/` references in `transactor_test`. Apply the same zero-residue check to `query_test` once its last v1 `open-local` helper moves.

### P1 — The schema closedness bridge still has stale-predicate behavior and an unspecified return contract

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:194)
- [schema.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/schema.cljc:942)
- [schema.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/schema.cljc:1121)

**Blocks the plan: yes.**

Holding schema’s legacy closed flag locally is reasonable while `SchemaWrapper` still implements v1. The details are incomplete:

1. `SchemaWrapper.close!` remains a v1 protocol method and must keep returning `{:woke []}`. If it simply returns `tx/close!`’s result, its public result silently changes to `{:dao.stream/outcome :dao.stream/ok}` before schema’s own migration.

2. `schema/publish!` checks `ds/closed?` outside the wrapper lock, then calls `tx/publish!`. A concurrent close can return between those operations, after which publication still proceeds. That is precisely the stale-predicate problem cited to justify removing `closed?`.

The `schema/transact!` check is sound because it occurs inside the same lock used by close.

**Concrete change:** explicitly preserve `{:woke []}` from the v1 wrapper close method, and either serialize the `publish!` closed check plus publication under the wrapper lock or explicitly redefine publication-after-close as permitted and remove the guard. Do not retain an operational check that can become stale before its action.

### P1 — P5 has no covering failure-path test after Phase 5

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:285)
- Phase 5 accounting at [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:545)
- [query.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:245)
- [query_test.cljc](/Users/sto/workspace/datomworld/test/dao/space/query_test.cljc:715)

**Blocks the plan: yes.**

P5 says a store opened during a failed open is closed before rethrow. The existing query tests cover successful explicit close and idempotence, but none makes `read-manifest` fail after `jing-coordinate/open!` succeeds and then observes the store being closed.

The direct missing/invalid-manifest tests proposed as the survivor for table entry #2 prove validation errors, not ownership cleanup. The successful stigmergy flow cannot prove this failure path, so the statement at plan line 649 that it covers P1–P5 is too strong.

**Concrete change:** add a `query/open-published!` test with a close-counting store whose manifest read fails. Assert the original error propagates and the newly opened store is closed exactly once.

The other Phase 5 dispositions are sound: coordinate rejection moves correctly; empty input needs the stated opened-path test; open-time fetch count must move; P6/P7 may be dropped; P8 and eager/lazy parity have valid survivors; and coordinate transport belongs on a v2 carrier. The transport test should assert the complete exact coordinate map, not only `:manifest-address`.

### P1 — Capacity-site accounting contradicts Phase 5

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:432)
- Phase 5 table at [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:558)

**Blocks the plan: yes.**

There are 28 actual unbounded `ds/open!` ring-buffer sites, plus the raw map in `query_test:97`, which is not an open. The plan calls all 29 “opens.”

More importantly, it says `index_test:777`’s carrier “must not be migrated” and is deleted outright, while Phase 5 correctly says to retain that coverage using a v2 carrier. The carrier therefore is migrated, not deleted.

The correct accounting is:

- 28 unbounded v1 opens.
- `schema_test:304` remains v1.
- The other 27 migrate to v2, including the Phase 5 transport carrier.
- `query_test:97` is merely an additional literal occurrence and remains unchanged.

This must be corrected so the capacity audit and zero-residue checks agree.

### P2 — Documentation closure is incomplete and the claimed phase ordering is false

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md:478)
- [dao.space.stigmergy.md](/Users/sto/workspace/datomworld/docs/dao.space.stigmergy.md:240)
- [ADR 0003](/Users/sto/workspace/datomworld/docs/design/adr/0003-dao-space-is-the-event-medium.md:39)
- [dao.space.index.md](/Users/sto/workspace/datomworld/docs/design/dao.space.index.md:230)
- [dao.space.schema.md](/Users/sto/workspace/datomworld/docs/design/dao.space.schema.md:550)

**Blocks the plan: yes, because the plan says it leaves nothing owed.**

`docs/dao.space.stigmergy.md` still instructs agents to open `:transactor` and call `ds/append!`; it is absent from Phase 4.

ADR 0003 names `DaoStreamLog`, says it implements `IDaoStreamWriter`, and calls repairing that writer face future work. As an accepted ADR it should at least receive an amendment or supersession note explaining D1.

Phase 5 says it “carries its own doc edits,” but no such edits are enumerated. Several index/schema passages describe the adapter and schema’s delegation to it. Consequently Phases 4 and 5 are not actually order-independent: one order leaves documentation temporarily stale, and the other makes it prematurely describe code that has not landed.

**Concrete change:** enumerate Phase 5’s precise documentation edits, include the stigmergy document, state the ADR disposition, and either order documentation after Phase 5 or split the edits so each code phase ends with truthful documentation.

## Verified and cleared

- **D1 is valid:** no production consumer reads through the transactor wrapper. Its only `next` implementation is delegation, and its callers are tests. `SchemaWrapper` implements only `IDaoStreamBound`.
- The plain-value constructor and removal of ambient v1 dispatch are architecturally consistent with DaoStream v2.
- D3’s transactor-level rule—validate the local append result, advance the watermark only on `ok`, return operational failures as data—is sound.
- The stated reasons for dropping T12, T13, T14, T17, S8, P6, and P7 are legitimate rather than accidental coverage loss.
- Phase 5’s adapter consumer inventory is closed: outside the adapter itself, current production use is schema’s published opener; remaining direct opens are the enumerated index tests.
- The proposed direct schema opener closes its store in `finally` and preserves eager schema realization.
- No new reader-conditional portability trap is specified. The proposed production changes can be expressed across clj/cljs/cljd; JVM-only synchronization tests remain correctly isolated.
- No tests were run, as requested.

**Final judgment: revise before implementation.** The origin-cursor/full-retention design is the essential first correction; without it, T2, T4, S1, S5, and the capacity proof cannot all be true.

Completed-GMT: 2026-09-14 17:57:21 GMT  
Completed-Local: 2026-09-15 00:57:21 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The declaration-catalogue finding is resolved. One allocation defect remains: the design attributes permanent-ID allocation and coordination to a transactor that does not provide them.

**Numbered findings**

1. **P1 — Construction-record uniqueness is not guaranteed by the specified transactor path.**

   **Draft:** §8.4.1, [lines 1195–1210](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1195).

   The cited `dao.space.transact` functions compute tempid assignments from supplied history or allocation state. They are a stateless preparation step, not a coordinated allocator ([prepare-tx, lines 189–207](/Users/sto/workspace/datomworld/src/cljc/dao/space/transact.cljc:189)).

   The actual `dao.space.transactor/transact!` stamps transaction time and appends already-resolved datoms. It neither invokes `prepare-tx` nor allocates entity IDs ([source, lines 231–254](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:231)). Its accepted input is entity maps with explicit IDs or datom vectors—not the proposed `[:db/add tempid …]` operations ([source, lines 56–101](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:56)).

   I ran two independent preparations against the same empty history. Both assigned `{-1 16}`. Committing their resolved datoms sequentially through **one** transactor produced:

   ```clojure
   {:outcomes [:dao.stream/ok :dao.stream/ok]
    :times [0 1]
    :entity-ids [16 16]}
   ```

   Thus serialization of commitment alone does not give the constructions distinct `[log-identity e]` identities. Separate transactor instances do not solve this either: the governing contract explicitly documents their uncoordinated writes to one stream ([transactor contract, T6](/Users/sto/workspace/datomworld/docs/design/dao.space.transactor.md:75)).

   **Fix:** Specify an allocation owner per log that serializes **fresh-ID allocation and commitment together**, preserves allocation state across reconstruction, and returns the allocated ID to the successful constructor. Name the allocation/write boundary and require constructions to use it. Alternatively, use an explicitly unique composition token for all constructions.

   This can remain unimplemented under §10 once specified, but the current claim that the existing transactor already guarantees it must be removed. My earlier construction-record suggestion also requires this allocation boundary; committing a record alone is insufficient.

**Round-5 findings 1–2**

| Previous finding | Status | Verification |
|---|---|---|
| 1 — Cursor-based incarnation allocation | **Not resolved** | Cursor observation is correctly abandoned, but the replacement assumes coordinated entity allocation absent from the cited path: [lines 1195–1210](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1195). |
| 2 — Harvest indices used as macro ordinals | **Resolved** | The derived declaration catalogue filters macro-declared groups while preserving relative order; plain groups remain executable syntax: [lines 1312–1342](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1312). |

**Verified sound**

- **Macro ordinals now match the governing contract.** Plain definitions do not consume declaration ordinals; every occurrence of a shared macro group receives the same ordinal. This preserves fabricated stand-in selection and leaves plain definitions untouched ([macro contract, lines 255–278](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:255)).
- **“Derived, never carried” introduces no race.** The declaration catalogue is a deterministic projection of the validated batch’s immutable harvest catalogue and declarations. It avoids a second independently supplied ordering.
- **Fresh allocation after failed construction is a sound policy.** Provided construction finishes before any batch is observed, a committed record whose result was lost can remain an unused orphan. Retrying need not identify that orphan. The argument is a sufficient policy, although rereading could be supported by a separately designed idempotency token.
- **Construction retry and staged expansion retry are correctly distinguished.** A failed construction allocates anew; flushing an existing staged expansion reuses its recorded identity ([lines 1219–1245](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1219)).
- **The fallback token is a valid abstraction.** An explicit composition allocator with a never-reuse warranty can supply incarnation identity without a transacted log. Its uniqueness must cover independent constructions and restarts.
- **The previously accepted mechanisms remain accepted.** No additional rejection is based on unfinished implementation work.

Only the allocation ownership and commit boundary remain unresolved in this round.
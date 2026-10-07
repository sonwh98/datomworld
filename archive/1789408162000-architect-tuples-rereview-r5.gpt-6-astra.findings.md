Completed-GMT: 2026-09-14 17:51:09 GMT  
Completed-Local: 2026-09-15 00:51:09 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The proposed shapes are appropriate, but two concrete rules remain unsound: reading a cursor does not allocate a unique incarnation, and harvest-group indices do not preserve the macro-only declaration ordinals. Neither issue is merely unfinished implementation work.

**Numbered findings**

1. **P1 — The incarnation allocator assumes uniqueness that stream cursors do not provide.**

   **Draft:** §8.4.1, [lines 1186–1197](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1186); §10.3, [lines 1494–1497](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1494).

   The assertion that no two constructions against one log share its cursor value is false. Cursors describe observation positions. Reading `:dao.stream/newest` does not reserve or advance a position ([stream contract, lines 471–477](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:471)). The memory-log implementation returns its identity and current value count ([source, lines 92–110](/Users/sto/workspace/datomworld/src/cljc/dao/stream/memory_log.cljc:92)).

   I created one memory log and requested two newest cursors without an intervening append:

   ```clojure
   {:same-cursor true
    :positions [0 0]
    :same-incarnation true
    :same-attempt true}
   ```

   Thus two constructions—concurrent or sequential—can obtain identical incarnations and attempt `[incarnation 0]`. A restart before any log append has the same problem. The composition’s stated uniqueness warranty does not make this particular construction satisfy it.

   **Fix:** Replace the cursor-read recipe with an explicit unique allocation mechanism. For example, allocate a durable incarnation entity through the log’s transactor and use its qualified identity, or supply a composition-minted unique incarnation token independently of the cursor. If log positions are used, they must be positions **allocated by a committed construction record**, not observed cursor values. Define retry behavior for that allocation.

   The existing rule that staged flush retries reuse their attempt identity can remain.

2. **P1 — Occurrence-group indices incorrectly replace macro-only declaration ordinals.**

   **Draft:** §8.5, [lines 1233–1234](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1233) and [lines 1274–1278](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1274).

   `:yin/harvest` contains **every definition**, including plain definitions. The new rule makes `:decl k` equal to the group’s index in that catalogue and says every occurrence is replaced by a stand-in.

   The governing contract instead numbers only **source macro definitions** into `:declared`; plain definitions remain executable `yin/def` nodes ([macro design, lines 255–278](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:255)).

   Consider a plain definition followed by macro definition `m`:

   - Legacy behavior: `m` receives declaration ordinal `0`.
   - Revised rule: `m` receives harvest-group index `1`.

   A fabricated stand-in selecting `m` at ordinal `0` therefore changes meaning or stops resolving. Such stand-ins are explicitly permitted by the governing contract. Applying the replacement rule literally to plain groups would also remove their runtime definition behavior.

   **Fix:** Keep two distinct sequences:

   - The harvest catalogue contains all original definition groups in admission order.
   - The declaration catalogue numbers only macro-declared groups, preserving their relative order.

   All occurrences of one macro group receive that group’s macro-only ordinal. Plain groups participate in harvest/redefinition but remain ordinary `yin/def` syntax. This preserves the occurrence-group design without changing observable declaration behavior.

**Round-4 findings 1–2**

| Previous finding | Status | Verification |
|---|---|---|
| 1 — Attempt identity across expanders | **Not resolved** | `[incarnation counter]` is the right shape, but the prescribed cursor-based incarnation construction demonstrably permits duplicates: [§8.4.1, line 1186](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1186). |
| 2 — Shared legacy declaration identity | **Not resolved in full** | Occurrence groups preserve entity aliases, but using the all-definition harvest index as `:decl` changes legacy ordinals: [§8.5, line 1263](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1263). |

**Verified sound**

- **Occurrence groups address the original aliasing problem.** One original entity can map to several `[j path]` occurrences while being harvested once. The adapter explicitly retains entity identity during inlining and honestly declares the required extension absent today ([line 1390](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1390)).
- **The group-validation conditions are appropriate.** Nonempty, disjoint groups; exact definition coverage; consistent name/body; and whole-group declaration prevent ambiguous membership and mixed declarations ([lines 1282–1292](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1282)). Address-based body comparison remains subject to the existing encoder gate.
- **Staged retries should reuse record identity.** Reusing the staged record without re-expansion matches the governing expander’s staging model ([macro design, lines 765–776](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:765)). Restart uniqueness becomes sound once incarnation allocation is corrected.
- **The cross-document dependency is explicit.** Adding `:incarnation` to macro `ctx` is correctly recorded in §10.3; its absence from implementation is not itself a rejection reason.

The remaining fixes are specific: allocate an incarnation rather than observe a cursor, and separate harvest order from macro declaration numbering. The other previously accepted mechanisms remain accepted.
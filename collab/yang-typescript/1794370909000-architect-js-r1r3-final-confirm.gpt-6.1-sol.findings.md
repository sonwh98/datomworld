Created-GMT: 2026-11-11 04:4x GMT
Created-Local: 2026-11-11 11:4x +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1c79a-399d-72e1-84c0-19a5a05f0563 (resumed thread; FINAL verdict: SIGN-OFF)
# Findings: JS R1+R3 final confirmation at complete tip 1697b257

I’ll review the complete tip, focusing on initialization and linked-name resolution, then confirm the provenance requirements and retention tradeoff. I’ll keep this read-only and leave the gate reruns to your independent run.
The re-keying appears to close the frozen-export path: the readers, top-level definitions, and init replacements all use `js.rt/*`, which export selection excludes. I also confirmed a correction to the cost estimate: `data/subvec` copies each tail here, so a full lookup can allocate O(n²) elements. I’m assessing that as retention/performance debt rather than a provenance failure.
Completed-GMT: 2026-11-11 04:37:40 GMT
Completed-Local: 2026-11-11 11:37:40 Asia/Ho_Chi_Minh

Reviewed the complete `4cfa609d..1697b257` range read-only. No files edited or test gates rerun.

1. **Qwen’s export blocker — CLOSED.** Top-level definitions, readers, and initialization replacements now consistently use `js.rt/retained-condition` and `js.rt/raise-protocol`: [prelude.cljc:1700](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1700), `:1617`, `:1719`, `:4141`, `:4150`.

   The unconditional definitions satisfy the linker’s [definition join](/Users/sto/workspace/datomworld-jsr13/src/cljc/yin/vm/linker.cljc:1086). Namespace stripping preserves `js.rt/*`, while [export selection](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/core/prelude.cljc:71) excludes it. Internal reads resolve against the live module store, so initialization replaces the values actually used.

   **No admitted path found that executes the silent-nil stub.** Bundled execution initializes before source execution; linked lowering explicitly invokes `js/init!` before the body; safepointed execution initializes before attaching hooks. Definitions alone execute neither retention helper. Directly invoking internal names after loading only `functions-uast` would be an uninitialized synthetic composition, outside the admitted source entry path.

2. **Original P1 — CLOSED; round-2 provenance requirements satisfied.** The store is allocated lexically at [prelude.cljc:4133](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:4133). Only the installed closures capture it. Lookup checks authenticity and compares complete sealed references, without reading candidate contents. Registration stores the complete original condition under a freshly created wrapper at `:4160`.

   Guest `=` uses host equality; copied reference maps equal to a held genuine reference are aliases to that same cell. Newly allocated cells have different identities. Wrapper contents expose neither the store root nor registration authority; guessing its cell ID does not bypass the engine’s seal verification.

   The probes assert the required outcomes:

   - Mutation preserves forwarding of the **original complete condition**: [exception_probes.clj:288](/Users/sto/workspace/datomworld-jsr13/test/yang/call/exception_probes.clj:288).
   - Extracted-seal stamping produces fresh thrown data carrying the minted cell: `:319`.
   - Minted and copied-wrapper probes retain their refusal expectations.
   - Export-absence assertions now name both actual helpers: [exception_contract_test.clj:119](/Users/sto/workspace/datomworld-jsr13/test/yang/call/exception_contract_test.clj:119).

   These remain synthetic boundary attacks against real JS source, not parsed Clojure-source pair coverage. The comments now accurately describe the reachable wrapper and inaccessible store.

3. **Retention — accepted for this round, with a named ledger obligation.** Task-lifetime retention preserves correctness; bounded reclamation is not required before this round lands.

   One cost correction must accompany the obligation:

   | Severity | File:line | Evidence | Recommended correction |
   |---|---|---|---|
   | **P2, non-blocking performance debt** | [prelude.cljc:4140](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:4140) | There are O(n) identity comparisons, but `data/subvec` copies each remaining tail. A full lookup therefore copies O(n²) elements. Associations also accumulate throughout the task. | Replace tail slicing with an index walk; define reclamation that preserves every live wrapper’s original association. |

   Record **`js-retained-condition-retention-and-lookup`**, owner **yang.typescript**, covering task-lifetime growth, lookup allocation, and long-running-task resource policy. Keep it open in the acceptance ledger.

4. **Standing rulings — honored; no new architectural blocker found.** The accepted donor tweak remains at [exception_contract.clj:55](/Users/sto/workspace/datomworld-jsr13/test/yang/call/exception_contract.clj:55). Missing raise exits remain operational failures; construction remains outside the ordinary-call claim; P2’s ordering-only comment and open suspension obligation remain intact. R1’s bridge-before-binding and restore-before-transfer behavior, and R3’s complete-condition forwarding, remain sound.

**Final verdict: SIGN-OFF for `1697b257`.** Architectural blockers are closed. Merge remains subject to your independently rerun gates; broader track acceptance retains the directed-pair, host/VM, suspension, and resource-policy obligations.

Created-GMT: 2026-11-12 22:55:00 GMT
Created-Local: 2026-11-13 05:55:00 +07 (+0700)
Coding-Agent: codex (gpt-6.1-sol)
Role: Architect (carrier-authentication design consult)
Status: CONSULT VERDICT OF RECORD — CARRIER RESOLVED VIA PROVENANCE

Completed-GMT: 2026-11-12 22:42:12 GMT  
Completed-Local: 2026-11-13 05:42:12 +0700

**Ruling: use the existing provenance primitive. It satisfies constraints 1–6 and resolves the design blocker for the 18 rows.** Implementing it and passing acceptance remain necessary before marking those rows green. This consult was read-only; no tests were run.

Phase 5’s finding is correct. Closures deny guest access to their environments; structural equality does not provide condition recovery. Applying an unknown closure with a private token is unsafe. Withdraw the closure-only remedy. See the [kernel value contract](/Users/sto/workspace/datomworld/src/cljc/yin/vm/values.cljc:15) and [Phase 5 finding](/Users/sto/workspace/datomworld/collab/exception-track/1794557000000-engineer-port-phase5-carrier.gpt-6.1-terra.stdout.log:62).

1. **Provenance supplies the required authority and lifetime.**

   The mint takes **two arguments**, `visible` and `condition`. They become separate heap fields—not a guest-visible `[visible condition]` vector:

   ```clojure
   ;; Compiler-generated operation:
   (cell/dsg__new-provenance visible condition)

   ;; Resulting host-owned entry:
   {:value visible
    :seal issued-seal
    :provenance condition}
   ```

   Allocation installs both fields atomically and rejects nil provenance. The reader authenticates the live reference against the heap and seal **before** reading provenance; an unmarked cell, forged/dead reference, map, or closure returns nil. It never applies the candidate. See [mint and reader](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2808) and [authentication](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:332).

   **`cell/set!` preserves provenance.** It updates `:value` through `heap-write`, which preserves sibling fields. Therefore overwriting visible contents cannot replace, clear, or install the retained condition. See [heap-write](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2444), [set!](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2793), and the [preservation and copying pins](/Users/sto/workspace/datomworld/test/yin/vm/provenance_test.cljc:123).

   The association belongs to the wrapper’s heap identity. GC follows provenance from live entries and pinned entries; mint-time collection roots the incoming condition too. There is no separate registry or handler-lifetime association. See [ordinary tracing](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2579), [pinned tracing](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2618), and [lifetime pins](/Users/sto/workspace/datomworld/test/yin/vm/provenance_test.cljc:194).

2. **This is consistent with the owner’s NO.**

   The rejected proposal added a `:retained` mark, new mint/query operations, and a marked-cell write refusal. The superseding directive requires the continuation port with no frontend-specific VM change. See the [NO](/Users/sto/workspace/datomworld/collab/exception-track/1794372000000-owner-decision-r3-vm-retained-seam.md:64) and [final directive](/Users/sto/workspace/datomworld/collab/exception-track/1794372000000-owner-decision-r3-vm-retained-seam.md:90).

   This design consumes already-landed shared infrastructure and permits visible writes. Moreover, amendment 5 explicitly requires provenance adoption for Clojure’s continuation port; its [adoption ruling](/Users/sto/workspace/datomworld/collab/exception-track/1794451000000-consult-vm-provenance-design.gpt-6.1-sol.findings.md:104) and [port relay](/Users/sto/workspace/datomworld/collab/exception-track/1794456002000-to-yangclojure-port-adopts-provenance-primitive.md:12) already record that direction. No constraint relaxation is required.

3. **Implement this exact path.**

   Clojure references below are to `exception-r3-impl @ f15423c2`, in `/Users/sto/workspace/datomworld-r3-orch`.

   - **Mint at trusted protocol reception.** In [`compile-guest-call`’s raise exit](/Users/sto/workspace/datomworld-r3-orch/src/cljc/yang/clojure.cljc:1396), retain the existing raw-payload path for `:yang.call/thrown`. For a qualified protocol condition `c`, deliver `(cell/dsg__new-provenance c c)` to `clj/raise`: initially `visible = c`, privately `provenance = c`, preserving every evidence field.

     Apply the same constructor to compiler-generated local protocol refusals, including the [admission refusal](/Users/sto/workspace/datomworld-r3-orch/src/cljc/yang/clojure.cljc:1409) and [private dispatcher refusal](/Users/sto/workspace/datomworld-r3-orch/src/cljc/yang/clojure.cljc:2378). Minting belongs to these known condition-producing sites. A source `(throw condition-shaped-map)` must never mint provenance.

   - **Carry the reference unchanged.** `clj/throw`, `clj/raise`, catches, and finally unwinding transport the carrier itself. Do not decode it into its visible map during transfer or saving.

   - **Authenticate and recover at public egress.** Replace the unconditional fresh-thrown conversion in [`call-entry`’s handler](/Users/sto/workspace/datomworld-r3-orch/src/cljc/yang/clojure.cljc:1211) with this generated logic:

     ```clojure
     (let [p (cell/dsg__provenance e)]
       (raise-exit
         (if (nil? p)
           (yang.call/thrown e)
           p)))
     ```

     Query once. Forward the recovered condition unchanged. Do not inspect visible contents, require a frontend tag, reconstruct the condition, or apply `e`. Emit protected primitives/helpers so guest definitions cannot capture these operations.

   - **Keep construction authority private.** Prefer direct generated UAST calls. Any factored constructor or recovery helper must use protected `dsg__` names and remain outside exports, projections, and aliases. The existing [reserved-name contract](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:124) and [macro-admission pins](/Users/sto/workspace/datomworld/test/yin/vm/provenance_test.cljc:245) protect the primitive doors; do not introduce an exported helper that accepts arbitrary provenance.

   - **Expose protocol observations deliberately.** Keep the carrier as the documented identity-bearing local protocol exception. Supported condition lookups/accessors can authenticate first and project its visible value. That projection must not replace the reference used for throw or save. Replace the stale [“no carrier” implementation test assumption](/Users/sto/workspace/datomworld-r3-orch/test/yang/clojure_test.clj:1866); leave acceptance expectations intact.

   Python’s `00ca2d1c` implements precisely this separation: [private wrapper mint](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:1044), [protocol reception](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:1079), and [egress recovery](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:918).

4. **Saved cases and depth remain compatible.**

   The kit stores `e` into `saved`, returns from `retain`, then throws `(cell/get saved)` in `later`. That retrieves the same sealed carrier reference, whose provenance outlives the original activation. See the [actual source fixtures](/Users/sto/workspace/datomworld/test/yang/call/exception_contract.clj:72) and [separate activation probe](/Users/sto/workspace/datomworld/test/yang/call/exception_probes.clj:111).

   A content clone gets no provenance; an alias retains authority; overwriting the original carrier’s visible contents still forwards its original condition. Add Clojure pins for these cases and for a thrown closure whose body must never run.

   Mint/read add fixed work at exception boundaries. Preserve the [private tail lane’s direct body application](/Users/sto/workspace/datomworld-r3-orch/src/cljc/yang/clojure.cljc:1213), add no handler there, and tail-mark the final raise calls. Re-run the established depth gates; this consult establishes compatibility, not measured depth results.

5. **Ledger claim after acceptance.**

   Six fixtures × semantic/stack/register = 18 rows. The unchanged [expectations](/Users/sto/workspace/datomworld/test/yang/call/exception_contract.clj:110) require all three qualified reasons, whole-condition equality, one transfer, no fallthrough, and successful retention for saved cases.

   Use this claim only after those results are measured:

   > Closed 18 Clojure protocol/saved-protocol rows on semantic, stack, and register at `<SHA>` / `<run>`. Qualified protocol conditions are retained in write-once private heap provenance owned by the generated carrier’s identity. Public egress authenticates and recovers without applying the raised value, forwarding the complete original condition once. Saved rethrows survive the creating activation. Unmarked lookalikes remain fresh thrown payloads. No frontend VM changes or ambient retention registry were introduced.

**CARRIER RESOLVED VIA PROVENANCE**
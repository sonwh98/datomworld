Created-GMT: 2026-11-10 22:38:52 GMT
Created-Local: 2026-11-11 05:38:52 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1c79a-399d-72e1-84c0-19a5a05f0563
# Findings: JS R1+R3 step-7 review (verdict FIX-LIST)

I’ll read the architecture rules and ruling, then trace the entry bridge, exception delivery, and cell authentication paths. I’ll keep this read-only and rely on the focused test results you supplied.
I’m using the code-review-pr skill to structure the findings and severity assessment.
The design classifies a missing or invalid raise exit as an operational wiring failure. I’m checking whether any admitted caller can supply nil, and whether `js/try` removes the callee bridge before invoking the caller’s exit; that determines whether delivery can catch itself.
`js/try` restores the saved context before calling its handler, so an escape from the caller’s raise exit cannot be caught again by that same callee bridge. Named functions, closures, arrows, and bound functions also trace to the shared constructor.

The R5 check needs closer scrutiny: JS properties cannot write the private slot, but Clojure source exposes raw cell operations. I’m checking whether an admitted Clojure caller can mint an error-looking cell that passes this new check.
Static tracing confirms an R5 bypass: admitted Clojure source can mint a genuine cell containing `:js/class :error` and the retained-condition key. The JS bridge would forward that supplied condition, even though JS never generated the wrapper. I’ll mark this P1 and require a fix; the entry bridge and donor fixture tweak otherwise look sound.
Completed-GMT: 2026-11-10 22:38:52 GMT
Completed-Local: 2026-11-11 05:38:52 Asia/Ho_Chi_Minh

Reviewed `c0114a42` against `4cfa609d`, read-only. No files edited or test gates rerun; the supplied focused results are accepted as evidence. **One P1 blocks sign-off: retained-condition authentication is forgeable through admitted Clojure source.**

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| **P1 — architectural contract defect** | [prelude.cljc:1662](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1662) | R5 requires runtime-generated wrapper provenance. `cell/authentic?` authenticates the cell reference, but does not authenticate its contents as JS-generated metadata. Admitted Clojure source can mint the matching contents, causing the entry at `:1617` to forward a forged condition. | Authenticate runtime-owned provenance bound to the particular wrapper identity, preserving lifetime without an ambient registry. Add source-produced forgery and copied-metadata probes. Another readable/copyable tag is insufficient. |
| **P2 — evidence overclaim** | [prelude.cljc:1586](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1586) | Restoring context before transfer establishes delivery ordering; it does not itself establish that later resumptions cannot reuse a completed call’s exit. | Narrow the comment and retain the explicit suspension/lifetime obligation until acceptance evidence closes it. |

1. **Missing raise exit — operational wiring failure, not an admitted R1 defect.**

   If an extracted entry receives no exit, `raise-exit` becomes nil. An escaping source exception then attempts to apply nil, producing the VM’s operational `:not-applicable` refusal; the original payload is not delivered. A normally returning body might never expose that malformed wiring.

   No admitted path found supplies nil: `js/call` constructs a closure at [prelude.cljc:1686](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1686), and kit boundary calls construct an abortive exit at [conformance.clj:381](/Users/sto/workspace/datomworld-jsr13/test/yang/call/conformance.clj:381). Higher-order calls use the same helper. Shared `invoke`/`invoke-with` check exit callability before entry application at [call.cljc:106](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/core/call.cljc:106).

   §8.10.3 explicitly classifies absence of a valid exit as operational failure. **No guest fallback or source-error conversion is required.** Any diagnostic improvement should remain operational.

2. **Exactly-once delivery — structurally sound for conforming exits.**

   [js/try:1786](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1786) restores the saved context **before** invoking its handler. The callee bridge is therefore absent when `raise-exit` runs. A source throw from that exit reaches the caller’s restored machinery; it cannot be caught again by this same bridge.

   Source `finally` frames are popped before execution by `js/throw` at `:1762`; replacement exceptions propagate before boundary delivery. Normal completion also restores context at `:1785`. Thus the implementation satisfies “restore before transfer” statically.

   An operational failure during delivery can abort processing, but must remain operational. A returning exit can make subsequent execution reachable; that violates the required non-returning exit contract.

3. **Single-entry claim — confirmed as one construction path.**

   There is one executable `:yang.call/entry` installation in the TS prelude, at [prelude.cljc:1623](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1623). Function declarations, expressions, closures, and arrows reach `make-function`/`make-function-nt` through [lower.cljc:810](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/lower.cljc:810). Bound functions use `make-function` at [prelude.cljc:2222](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:2222).

   Each function receives its own entry closure from that shared implementation. All requested callable forms therefore carry the bridge before private parameter binding.

4. **R5 authentication — safe against ordinary JS properties, insufficient across admitted sources.**

   JS property operations read and update `:props`: see [prelude.cljc:1002](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1002) and `:1139`. Setting a property named `"yang.call/retained-condition"`, changing prototypes, or defining an accessor cannot write the private top-level keyword slot.

   However, admitted Clojure source already uses raw cell operations, including in [exception_contract.clj:73](/Users/sto/workspace/datomworld-jsr13/test/yang/call/exception_contract.clj:73). It can construct:

   ```clojure
   (cell/new
     {:js/type :object
      :js/class :error
      :yang.call/retained-condition
      {:yang.call/reason :yang.call/not-callable
       :yang.call/data {:forged true}}})
   ```

   Passing that value to the source JS `raise_value` function makes the new predicate accept it and forward the forged protocol condition. No forged seal, extracted private code, or host manipulation is needed. Raw `cell/set!` also permits replacing metadata on an obtained wrapper.

   This is a **static deduction, not an executed reproduction**. The distinguishing failure is that heap authenticity proves “genuine cell,” while R5 needs “genuine retained association for this wrapper.” The correction belongs to this round’s new authentication surface.

5. **Donor tweak — accept.**

   Keep [exception_contract.clj:55](/Users/sto/workspace/datomworld-jsr13/test/yang/call/exception_contract.clj:55). The lowerer independently confirms the bare-global-write gap at [lower.cljc:562](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/lower.cljc:562).

   `globalThis.saved = e` preserves the same object and the same completed-activation/later-rethrow scenario. Expectations remain unchanged. This is a legitimate fixture repair within scope; reverting it would obscure retained-condition behavior behind the separately owned T2 defect.

6. **Construction and lifetime coverage — legitimate exclusions, with explicit limits.**

   `js/construct-with-nt` directly applying private `:code` is outside this ordinary-call bridge change. §8.10.1 explicitly distinguishes ordinary calling from `[[Construct]]`. An exception escaping construction inside a bridged source function still reaches that enclosing function’s bridge. This round must not claim a common constructor-entry protocol.

   The missing generator-resume probe is acceptable as a **named open acceptance obligation**, not proof of lifetime correctness. Current TS lowering explicitly refuses generators and async functions at [lower.cljc:662](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/lower.cljc:662). Supported suspension, foreign re-entry, recursion, and context restoration remain pending under [exception_contract.clj:180](/Users/sto/workspace/datomworld-jsr13/test/yang/call/exception_contract.clj:180). Their deferral does not independently block this implementation round, but they must close before track acceptance.

**Verdict: FIX-LIST.** Repair the R5 provenance bypass and add focused forgery coverage before sign-off. The entry bridge, restoration ordering, complete-condition retention for genuine wrappers, and donor tweak are otherwise architecturally acceptable. Node/Dart, directed pairs, and full lifetime acceptance remain with the planned shiva run.

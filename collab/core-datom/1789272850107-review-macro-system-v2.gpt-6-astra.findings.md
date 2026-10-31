Completed-GMT: 2026-09-13 04:17:29 GMT
Completed-Local: 2026-09-13 11:17:29 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

**Verdict: REQUEST CHANGES.** The architecture is promising, but the specified contracts do not yet establish identity preservation, portable runtime state, bounded expansion, or evaluator parity.

Reviewed the target and companion specifications against the foundational invariants, DaoStream contract, AST walker, emitter/indexer, Clojure frontend, observer, and tempid resolution code. This was a read-only architecture review; no files were written and no implementation tests were run. References below use **M** for the [macro specification](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md).

**[P1 — blocking] 1. The sharing rule silently discards valid AST transformations.**

**Location:** [M §3.5, lines 473–482](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:473).

An operand arrives with `:eid`. A macro can legitimately return `(assoc operand :value 42)`, or replace one of its descendants with `assoc`. Both preserve the original `:eid`. The specified emitter checks only membership in `:existing` and returns the original entity reference without examining the changed structure. The expansion therefore executes the old syntax.

This also affects tail marking: changing `:tail?` on an existing subtree can be discarded by the same rule.

**Required change:** Define identity preservation for unchanged structure, not merely for a matching `:eid`. Validate the candidate against the indexed original and allocate fresh entities along changed paths, or provide an explicit reference representation and a reconstruction contract that cannot accidentally reuse identity. Reject conflicting or forged identities.

**Acceptance:** Test unchanged sharing, changed literals retaining `:eid`, changed descendants under an unchanged parent eid, and tail-marking changes. Original datoms must remain unchanged while the final root reaches the transformed syntax.

**[P1 — blocking] 2. Entity allocation and relocation are not sound across batches or runtime code generations.**

**Location:** [M §3.5, lines 495–503](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:495), §§4.1–4.2, §6.2.

Committing two batches to the same DaoDB does not retrospectively connect a negative tempid in the second batch to its meaning in the first transaction. The [transactor](/Users/sto/workspace/datomworld/src/cljc/dao/space/transact.cljc:168) resolves tempids using the current transaction’s map. The historical cross-language document correctly requires permanent identities **before** composition; M relaxes that into an invalid eventual-resolution claim. A macro name cannot recover the correct definition after redefinition.

Related allocation gaps compound this:

- `yang.clojure/compile-program` restarts macro IDs at `-1000000`. Persisting an old macro environment while compiling another definition can reuse an existing macro eid.
- Initializing `:macro-eid` from each newly loaded batch can reuse IDs already held in the ledger, closures, or retained segments.
- “Below the batch minimum” does not imply a negative user tempid when the input contains only committed positive IDs.
- Dynamic lowering specifies fresh segment identities but supplies no allocation state or returned watermark connecting code allocation to AST/event allocation.
- Eids nested inside inline AST map values will not be relocated by ordinary ref-attribute tempid resolution.

**Required change:** Specify transaction-local versus durable identity explicitly. Resolve external macro identities before use; thread a monotonic allocation context across all live VM artifacts; allocate valid negative tempids; and define relocation of embedded AST identities. Names remain descriptive metadata, not identity repair.

**Acceptance:** Cover independently compiled batches with overlapping tempids, two REPL macro definitions, repeated program loads, repeated ephemeral segments, and commit/reload of inline AST payloads.

**[P1 — blocking] 3. The walker drops the call-site identity required by runtime provenance.**

**Location:** [M §3.5, lines 479–482](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:479), §4.1, §5.2.

The loader retains `:eid` only on macro lambdas. Runtime expansion receives the macro-call node and operands directly as AST maps. Consequently, it has the macro’s identity but not the original call’s identity, yet must emit:

```clojure
[ev :yin/source-call call-eid t default-op]
```

Allocating a new call eid would identify a reconstruction, not the original immutable source call. Dropping operand identities also prevents the claimed unchanged-subtree sharing at runtime.

The semantic path likewise needs an explicit mechanism that preserves the emitted AST entities through `lower-ast`; re-emitting an eid-free AST cannot establish that instruction source refs name the ledger’s AST entities.

**Required change:** Preserve identities at macro sites and their syntax operands, or retain an explicit source mapping accessible to the runtime adapter. Lower the actual emitted expansion representation with its identities.

**Acceptance:** Query original source-call → event → expansion-root after runtime execution and after serialization. Check unchanged runtime operands are shared and every lowered source ref resolves to the recorded expansion.

**[P1 — blocking] 4. AST validation permits host functions—and the invocation-local mutable counter—to escape.**

**Location:** [M §2.4, lines 206–243](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:206), invariant table line 133.

The validator constrains node shapes but not values inside literals. A macro can return:

```clojure
(yin/literal yin/gensym-sym)
```

The literal then contains the host primitive closing over the mutable gensym counter. The current emitter accepts arbitrary literal values. The counter is no longer unreachable after invocation, contradicting the argument used to establish “No Shared Mutable State.” Other host functions can escape similarly.

Default-deny authorization does not prevent this once an expansion is authorized.

**Required change:** Validate portable data recursively, including literal payloads and otherwise unrecognized map fields. Reject host functions, mutable cells, handles, and host objects. Define the supported AST vocabulary per node rather than using open-ended `:vm/*`/`:stream/*` categories.

**Acceptance:** Attempt direct and nested function escape, including `yin/gensym-sym`, and verify rejection before emission or splicing.

**[P2 — must address] 5. The guards do not bound a macro invocation or the complete expansion transaction.**

**Location:** [M §§3.2–3.4](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:359).

The depth guard bounds recursive **expansion output**, not computation inside a macro body. A body can loop forever before returning any syntax. The [walker run loop](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:329) has no fuel limit, so this also monopolizes a runtime transition.

The datom guard runs after validation and emission. Large output can exhaust resources before reaching it. Ancestor-copy emissions in the ordinary-node branch have no guard in the pseudocode, and cyclic input refs can recurse without incrementing expansion depth.

The suspension check is also incomplete: `:vm/park` sets `:halted? true`, and `halted?` does not inspect `:parked`. The proposed `macro-eval` can therefore classify a parked result as successful before it later fails output validation.

**Required change:** Add deterministic evaluator fuel, bounded structural traversal, cycle detection, and accounting at every emission site. Explicitly reject parked and queued work. Keep expansion depth as a separate limit.

**Acceptance:** Test an infinite macro body, oversized output, cyclic input, excessive ancestor copying, and a parked body. Each must produce the intended structured failure.

**[P2 — must address] 6. Tail splicing does not establish tail calls inside generated code.**

**Location:** [M §4.2, lines 672–697](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:672), §2.4 prelude, Phase 3 tests.

Omitting the macro splice’s return frame is correct, but insufficient. `yin/application` does not specify tail marking, and the semantic companion says lowering reads existing tail facts rather than inferring them.

A tail-position macro that constructs an ordinary application to the recursive function can therefore produce:

1. A tail splice with no frame.
2. A generated non-tail call that pushes a frame.
3. Another iteration repeating that sequence.

Continuation depth grows despite the claimed guarantee. Compile-time replacements can similarly lose the original call’s tail context. `yin/make-lambda` covers only a particular constructor path.

**Required change:** Specify context-sensitive tail annotation for expanded executable syntax. The ephemeral segment’s final expression is tail relative to its return continuation. Preserve or recompute tail positions throughout generated branches and lambda bodies.

**Acceptance:** Run deep recursion through macros using `yin/application`, identity-returned operands, and generated conditionals. Measure maximum continuation depth, not just the final value.

**[P2 — must address] 7. Semantic static macro resolution can reference a closure instruction that does not exist.**

**Location:** [M §4.2, lines 648–667](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:648).

A macro call lowers to exactly one `:macro-call`. Its embedded lambda is syntax and is not separately evaluated or necessarily lowered to a `:closure` instruction. Nevertheless, static resolution requires finding that instruction from the macro lambda eid.

A call-only compilation unit using an imported embedded macro is a concrete failure: there need not be any corresponding closure instruction in its segment. Multiple occurrences also require a defined AST-entity-to-instruction mapping.

Additionally, `:macro-sites` is keyed only by pc, while execution addresses are `(segment, pc)`. Multiple loaded segments can have different macro sites at the same pc.

**Required change:** Resolve static macros from the already-inline operator AST or an explicit segment-local macro definition table. Store macro sites inside their segment image or key them by `[segment-id pc]`. Apply the same rule to opcode 24’s constant-pool representation.

**Acceptance:** Test imported embedded macros without a definition instruction and two live segments containing different macros at pc 0.

**[P2 — must address] 8. Runtime macro closure semantics contradict the invocation contract.**

**Location:** [M §§2.3, 3.2](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:175), §4.2 lines 699–702.

Runtime resolution returns a closure with captured `:env`, but invocation uses only parameter bindings and a fresh store. Captured values are discarded. The later statement that runtime macros may read the changing program store is therefore unsupported by the specified API.

For example, a runtime macro closing over `x` and returning `(yin/literal x)` cannot access its captured `x`.

**Required change:** Choose a precise restriction. Either macros are closed syntax transformers and free runtime captures are rejected explicitly, or invocation receives a defined immutable snapshot of permitted captured values/store data. Do not pass live host resources or unrestricted VM state.

**Acceptance:** Test captured lexical values, free-variable errors, and store visibility under the selected semantics. Require identical behavior across evaluators.

**[P2 — must address] 9. Failed runtime expansions do not reliably produce the promised audit record.**

**Location:** [M §3.4, lines 460–466](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:460), §4.1 transition, §5.3, Phase 2 tests.

Phase and authority checks occur before event allocation. An authorization rejection therefore cannot appear “on the event” as the acceptance tests require.

Failures after allocation throw diagnostic datoms, but the immutable successor VM containing them is not returned. Without a specified catch/adoption boundary, the running VM’s ledger does not acquire the failed event. Lowering or loading can also fail after expansion succeeds, leaving no defined audit result for the overall splice attempt.

**Required change:** Define a structured success/failure result carrying event data, allocation state, and diagnostics. Allocate an attempt event before checks when failed attempts must be audited. Have the evaluator adopt the failure record while preserving control and store consistently. Specify recursive authorization checks and fail-closed handling of unexpected authorizer results.

**Acceptance:** Cover denial, phase failure, invalid output, nested denial, lowering failure, and loading failure. Verify both the returned failure and its persisted event.

**[P2 — must address] 10. Whole-VM serialization is an impossible acceptance criterion as written.**

**Location:** [M Phase 2, lines 985–986](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:985).

The VM value contains host functions in `:primitives`, and this design adds `:macro-eval` and `:macro-authorize`. A general `pr-str`/`read-string` round trip cannot reconstruct these functions. The semantic companion explicitly acknowledges existing host-value serialization limits; the macro specification instead promises that the VM value contains no host objects.

**Required change:** Define a portable execution snapshot separately from host composition. Serialize control, environments permitted by the portability contract, ledger, identities, and required code; reattach evaluator primitives and policy functions explicitly on the destination.

**Acceptance:** Round-trip that snapshot and resume an expansion continuation on a separately constructed VM. Include a rejection test for nonportable captured values.

**[P2 — must address] 11. Loader composition examples and phase ordering cannot execute as specified.**

**Location:** [M §§3.6, 6.2, Phase 1](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:505).

The observer invokes its loader as `(load-program vm batch)`. Consequently:

```clojure
(comp ast-walker/vm-load-program (macro/expand-with opts))
```

passes both arguments to the unary expander and fails. The semantic composition has the same problem.

The correct adapter shape is:

```clojure
(fn [vm batch]
  (ast-walker/vm-load-program vm
    ((macro/expand-with opts) batch)))
```

Phase 1 also requires compiling `stdlib-forms` followed by user macro calls while scheduling the Clojure macro-environment API for Phase 2. The roadmap needs either that dependency moved earlier or a concrete existing-API route.

**Required change:** Correct all loader examples, specify the adapter API, and make frontend prerequisites precede the tests that require them.

**Acceptance:** Exercise the actual `observer/run-on-stream` path for both evaluators, not only direct expander calls.

**[P2 — must address] 12. Cross-input macro discovery loses lexical shadowing metadata.**

**Location:** [M §§2.7, 6.2](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:309), Appendix B lines 1132–1137.

The existing Clojure compiler preserves `:yang/shadow-params-operand` and `:yang/shadow-body-start` in its macro environment. These tell it when an operand introduces bindings that shadow macro names.

The proposed `definitions` result contains only eid, lambda AST, and phase policy. Rebuilding the REPL macro environment from that result loses the hints, even though Appendix B says their behavior remains unchanged. A binding macro used in a later input can then compile a shadowed function call as a macro call.

**Required change:** Preserve the required compilation metadata in the cross-unit definition contract, or specify a replacement binding-aware normalization mechanism. Also define whether syntactically discovered definitions in unexecuted branches become available to later inputs.

**Acceptance:** Define a binding macro in one input and use it in another with a parameter whose name shadows a known macro. Repeat after switching frontend where applicable.

**[P3 — suggestion/alignment] 13. Explicit roots solve ordered-batch selection, not every persistence interpretation.**

**Location:** [M §3.5, lines 484–493](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:484).

The explicit fact is sufficient to prevent selecting the unexpanded root **when consumers preserve the specified batch order and honor the final valid root assertion**. It is a sound correction to the current heuristic.

Clarify behavior for false root facts, missing root entities, explicit root overrides, and batches reconstructed from unordered query results. If the selected root must survive those interpretations, use a program/expansion entity with an explicit root reference or an explicit ordering fact.

Also specify structural traversal order. Fresh IDs and gensyms depend on visit order, so cross-host determinism requires more than persistent maps and counters. The current heuristic iterates a grouped map; it does not reliably establish input-order selection.

**[P3 — suggestion/alignment] 14. Tighten the invariant argument around pure services and state ownership.**

**Location:** [M invariant table](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:126), §4 runtime API.

Synchronous expansion and lowering are not inherently layer collapsing: the repository’s calibrated stream guidance permits local, synchronous transformations over data. The compile → expand → lower → execute separation is defensible.

However, “separate functions” alone does not prove separation. The stated `macro/expand-runtime state … → state'` API gives the expander the executing VM despite the claim that it never touches VM control state. Prefer an explicit expansion context and a data result; let the evaluator adapter update its own ledger and control.

Likewise, supplied evaluator/authorizer functions must be trusted pure services under a stated contract. If authorization requires host work, that work needs the repository’s stream effect boundary. Lack of an outer-VM argument does not itself prevent a supplied host closure from capturing external state.

**[P3 — suggestion/alignment] 15. Ledger draining does not bound retained semantic code.**

**Location:** [M §4.2, line 679](/Users/sto/workspace/datomworld/collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md:679), Appendix B ledger-growth risk.

Every runtime expansion adds a never-evicted segment to `:code`. Draining `:macro-expansions` does not release those images. A tail-recursive program can therefore have bounded continuation depth and unbounded code memory.

Document this separately from ledger growth. Any future reclamation must account for closures in the store/environment, parked and ready continuations, and active frames. The proposed frame-based pinning alone is insufficient.

For persistence, define the composition’s pending-batch handling after `drain-expansions`: a destination reporting `full` must retain the exact batch for retry.

**Assessment of the requested architectural choices**

- **Outermost-first expansion:** Appropriate for syntax-rewriting macros. Fixpoint expansion is coherent for finite, valid input, but the specified guards and traversal rules need the changes above.
- **Universal AST operands:** A good cross-language boundary. Attached eids can preserve sharing only with enforced identity semantics.
- **Walker splice:** `cesk-return` with expanded control and the existing environment/continuation is the correct basic transition.
- **Semantic splice and opcode 24:** Immutable ephemeral code plus explicit return behavior is preferable to rewriting executing segments. Identity, static resolution, and generated tail positions remain prerequisites.
- **Authority:** Default deny is a sound default, not a complete runtime safety boundary.
- **Ledger:** An immutable VM-owned ledger is a reasonable abstraction for recording expansion results before composition persists them. Failure adoption, relocation, and retention need explicit contracts.
- **Cross-language normalization:** Keeping Python strings as literals and using macro-side name accessors is the right separation. Authoring surfaces and parser limitations are honestly deferred.
- **Roadmap:** Phases 0–4 provide a useful sequence, but approval should wait until the blocking contracts are resolved and the acceptance suite includes the failure cases above.
I’ll read the architecture rules, VM contracts, and landed tests, then assess the proposal’s defects, specification gaps, and migration cost. I’ll keep the review read-only.
The proposal can preserve CESK and the six invariants, but two claims need correction: register-to-stack lowering is not generally a local expansion, and R1’s allocator is embedded in its expression walk. The register-window format also needs a precise liveness and resume-destination contract.
Completed-GMT: 2026-10-09 18:06:09 GMT
Completed-Local: 2026-10-10 01:06:09 Asia/Bangkok

The register-shaped canonical VM is architecturally viable. The proposal understates the lowering and continuation work, however, and needs tighter canonical-form rules before implementation.

Read-only review; no files edited or tests run. File references below identify the current contracts affected by the proposal.

| Severity | file:line | invariant/evidence | recommended correction |
|---|---|---|---|
| High — architectural defect in claimed derivation | `src/cljc/yin/vm/debruijn_register_compile.cljc:33` | The existing inverse uses a recoverable expression grammar. A general register vector cannot be translated to today’s operand stack one instruction at a time: previously computed operands may be buried, reused, or needed across branches. | Restrict canonical vectors to a validated structured grammar and specify a whole-body stack lowering, or introduce explicit stack storage/access instructions and version that format. |
| High — specification gap | `docs/design/yin.vm.semantic.md:490` | Fresh destinations and shared arm destinations do not uniquely determine numbering, body scope, or permitted joins. | Define minting order, body/register scope, conditional destination reservation, body discovery order, and definite-assignment validation. |
| High — specification gap | `docs/design/yin.vm.universal-continuation-format.v2-amendment.md:312` | Existing semantic return frames rely on stack bases; register return requires a destination and saved caller window. | Specify active, parked, reified, return, and tail-effect states separately, including exactly-once result delivery. |
| High — specification gap | `docs/design/yin.vm.universal-continuation-format.md:672` | A register window needs a canonical membership rule. “No live sets” in code does not eliminate liveness from continuation semantics. | Derive and validate window membership from A; keep the derived live set outside A’s persisted instruction operands. |
| High — identity correction | `docs/design/yin.vm.debruijn.stack.md:908`; `docs/design/yin.vm.debruijn.register.md:95`; `docs/design/yin.vm.debruijn.targets.md:239` | H/R fetching, sharing, and emitter keys contradict owner ruling 1 independently of register shape. | Amend identity contracts immediately: requests, publication, pins, dependencies, and executable admission name A; H/R are derived checksums. |
| Medium — implementation cost | `src/cljc/yin/vm/debruijn_register_compile.cljc:152` | R1 allocation is integrated with target-register-passing recursion and explicit expression-consumption events. | Replace its input walk; do not describe this as plugging a vector into an existing standalone allocator. |
| Medium — specification gap | `docs/design/yin.vm.universal-continuation-format.md:438` | The semantic stamp covers grammar, transitions, restoration, and scheduling. | Publish a new semantic contract and changed UCF grammar/version. Decide b2/r2 retention separately using exact-byte evidence. |
| Medium — implementation cost | `src/cljc/yin/vm/ucf/handoff.cljc:866` | Landed transport validates exact semantic frame keys and reconstructs stack-based state. | Budget a transport/validator rewrite and full Stage D/E regression, beyond fixture re-encoding. |

1. **Invariants**

   All six can survive:

   - **No hidden global state:** virtual-register counters must be scoped lowering data. Runtime windows, result destinations, and saved caller state must be explicit.
   - **No implicit control flow:** branches, calls, returns, park/resume, and tail-result delivery remain explicit transitions.
   - **No callbacks:** effects continue through the existing stream protocol.
   - **No shared mutable state:** register windows are immutable machine-state values; captures must preserve existing isolation and cell semantics.
   - **Interpretation separate from execution:** vectors remain passive data interpreted by loaders and evaluators.
   - **No assumed graphs:** def/use edges, body ownership, lexical chains, and control-flow successors may be constructed from validated tuples. They must not depend on an unavailable AST or provenance table.

   Virtual registers do introduce a derived def/use graph, but that is permissible when constructed explicitly. They introduce no necessary global state.

   “Derive, don’t persist” supports keeping allocation, liveness tables, ownership indexes, and physical layouts out of A. Explicit register operands are executable syntax; they are not merely duplicated analysis metadata.

2. **Identity**

   **Yes, with a pinned lowering algorithm.** A can remain deterministic without depending on physical allocation or target scheduling. “AST and evaluation order alone” is too broad: opcode selection, defaults, tail flags, body placement, and register-minting conventions also determine the vector, just as instruction-layout conventions determine today’s vector.

   The current rule is insufficient. Decide:

   - Whether register IDs are segment-wide or body-local.
   - Whether an expression’s destination is reserved before lowering its children or minted after them.
   - How a conditional reserves its shared destination.
   - Operator/operand order, consequent/alternate emission order, and queued lambda-body discovery order.
   - Whether discarded expressions still receive destinations.
   - Which moves, folds, or rewrites are permitted.

   For example, reserving a call’s result before its operator versus afterward produces different numbering without changing evaluation order.

   Call this **structured single-assignment syntax with exclusive arm definitions**, rather than ordinary SSA. An arm-shared destination has two static definitions. Validation must ensure that every reachable read has an assigned value and that branch definitions obey the structured join rule.

   A remains an exact-vector identity, not an alpha-equivalence identity: named binders in A can change A while resolved H/R remain equal. Preserve that distinction.

3. **Semantics**

   This remains CESK. C may contain `⟨seg, pc, regs⟩`; CESK does not require an accumulator or operand stack.

   Define a fresh window on closure entry. A non-tail call saves the caller’s window, environment, successor PC, and result destination in K. Return restores that state and writes the result once. A tail call discards the current activation and preserves the caller continuation.

   Effects become easier to describe because operands and result destinations are explicit. Their stream outcomes, pending data, retries, correlation, errors, and scheduling remain unchanged in meaning.

   Important decisions:

   - **Park:** record the suspended operation’s destination, excluding its not-yet-produced value from the saved window.
   - **Resume:** restore the captured window and deliver the supplied value to that destination.
   - **Current-continuation:** capture the post-instruction continuation with an explicit delivery destination; avoid accidentally capturing the continuation value recursively in its own destination.
   - **Effectful tail calls:** specify whether completion returns through K directly. Do not assume every suspended call resumes by writing a current-body register.
   - **Gensym:** register minting is compile-time numbering; runtime gensym still advances the task’s explicit counter.
   - **FFI:** retained requests and responses remain pending data, not register-map substitutes.
   - **Define/Rule R:** write the source-register value through `engine/store-put`, then produce that value in the destination. Reserved-name refusal remains unchanged.
   - **Module store-of:** preserve captured module-store context across calls, returns, captures, and migration. Changing C must not lose that context.

4. **Safepoints**

   The **semantic kinds survive**: explicit park, blocked read/write, sent/retained FFI, effectful calls, and linker request/response/install waits. Current-continuation remains a reified value operation, not a parking safepoint. Halt remains a result.

   This is more than `St → window` notation. Rewrite the table’s state columns and distinguish write-result from tail-return delivery. No new external effect kind is inherently required, but delivery cases need explicit coverage.

   A resume value means the operation’s completed result, delivered either:

   - to the instruction’s designated destination; or
   - through the preserved return continuation for a tail effect.

   Define a window as a sorted sparse mapping of register IDs to values with an exact, code-derived membership rule. Liveness must account for branches, return, and capture. Reject missing, duplicate, out-of-scope, or inappropriate entries. Dead-value retention must not make otherwise equivalent captures differ or introduce avoidable non-portability.

   Foreign-engine reconstruction must cover canonical PC, every required virtual value, E, K, destinations, pending state, and captured store context. A physical-slot map alone is insufficient when values are spilled, rematerialized, shared between locations, or represented by lexical frames.

   Preserve `lift(lower(frame)) = frame` for admitted canonical frames. Replace stack-effect metadata with appropriate derived register use/def and delivery facts.

5. **The two derivations**

   **A → R:** resolution plus deterministic allocation is the right decomposition, but R1 is not presently a reusable vector allocator.

   Changes to §4.1–4.6 include input validation, explicit body/owner reconstruction, removal of expression traversal, virtual-to-physical mapping, join handling, register-bank counts, lifetime/tie-break rules, PC relocation, and live-set filling. `body-liveness` is reusable in principle. The existing register-image lift must also change its output contract.

   **A → H:** contest “local expansion.” For `(f (g x) y)`, the stack lowering must preserve `f` while evaluating the nested call, then preserve its result while evaluating `y`. Register instructions alone require a stack-placement strategy or recoverable expression structure to produce the existing stack operations.

   Two viable choices:

   - Validate an expression-structured canonical register grammar and lower whole bodies using that grammar.
   - Accept more general register programs and define storage/access operations or another stackification strategy.

   The first fits the current project more closely and offers the best chance of preserving H.

   **Byte identity is achievable in principle, not guaranteed.** H can remain identical if lowering reconstructs today’s exact emission order, pushes, branches, bodies, and defaults. R can remain identical if allocation reproduces target-register reservation and release timing, including branch handling—not merely generic last-use linear scan.

   **Do not automatically re-version b2/r2 because their input changes.** Keep them only if their descriptors and execution contracts remain unchanged. If opcode shapes, allocation contract, live operands, or control rules change, **re-version them plainly**. The semantic contract must change regardless.

6. **Blast radius**

   **Rewrite:**

   - `yin.vm.semantic.md`: opcode/schema tables, validation, configuration, transitions, wait/return shapes, linearizer contract.
   - UCF §7.3.2, §7.4, relevant value/census/restoration rules and acceptance matrix.
   - V2 amendment semantic registers/frames; identity/layout sections across profiles.
   - Row carrier §5.2, validation, census traversal and examples.
   - Register design topology, input/lowering/lift contracts; targets identity and fetched-code path.
   - `yin.vm`, `code`, `linearize`, `semantic`, `ucf`, `ucf.handoff`, `completion`, and the de Bruijn resolver/lowerer interfaces.
   - Pipeline and linker publication/fetch/admission paths currently organized around H/R.
   - Semantic, code, linearizer, continuation-invoke, safepoint, lift, handoff layout/validity/census, and cross-engine tests.

   **Notational or focused edits, conditional on preserved native contracts:**

   - Code-as-tuples segment instruction contract and two-loader validator interface; its AST row grammar can remain.
   - UCF revision history and implementation plan.
   - Stack/register native VM documentation and restore adapters.
   - Blog Part Three: replace both the St explanation and any affected identity/topology claims.
   - REPL/session composition, encoder loaders, telemetry and benchmark fixtures referencing the old shape.

   **Corpora:** preserve source programs and expected results/errors/effect traces. Regenerate semantic vectors, A values, PCs, saved frames and transported bodies. Re-encode Stage D/E scenarios and affected handoff/checkpoint fixtures. Preserve H/R goldens only if byte identity is achieved.

   **Untouched in semantic substance:** Universal AST, surface syntax, Rule R policy, stream protocol, FFI correlation, lease/custody arbitration, fencing, durable deduplication and integer/scalar encoding. Their tests may still need fixture or setup changes.

   Stage E holder/authority logic should not be rewritten wholesale. Its integration tests must run against the new transport, especially crash cuts and successor publication.

7. **Sequencing**

   1. **Amend identity now**, before register implementation: stack §2/D9/D12, register invariant/identity sections, targets T-D12, UCF profiles, linker and emitter keys. Mark staged implementation honestly. This ruling does not depend on the new VM.
   2. Freeze canonical numbering, structured grammar, ownership, window membership, call/return and tail-effect delivery, stamps, and rejection rules.
   3. Build the new linearizer and shared validator. Gate on deterministic vectors, malformed-input refusal, and agreement between direct and datom load paths.
   4. Build the semantic register evaluator beside the old evaluator. Gate on walker parity, Rule R, effects, recursion, tail calls and continuation invocation.
   5. Build A-derived resolution and both lowerings. Gate on lexical-address agreement, behavioral parity, and the explicit H/R byte-identity decision.
   6. Implement canonical UCF reconstruction and row transport. Gate on bidirectional safepoint tests, pending waits, nested/reified captures, module contexts, and refusal cases.
   7. Run full Stage D/E handoff, fencing, durability, crash-cut and cross-process acceptance.
   8. Delete the old semantic evaluator, rename the replacement, remove obsolete H/R request paths and `raise` dependencies, and finalize documentation.

   Keep the old evaluator only as a temporary oracle. The final clean break needs no compatibility shim.

8. **Risks and the honest case against**

   The strongest case against is that **A-only identity does not require replacing the semantic VM**. It can be implemented around the current canonical vector. The additional foundation change should earn its cost through a clearer executable grammar and demonstrably simpler, trustworthy lowerings.

   Potential mistakes include:

   - Accepting arbitrary register vectors while relying on an expression-only inverse.
   - Claiming no liveness while silently retaining dead values or deriving inconsistent windows.
   - Making numbering depend on physical allocation or optimization.
   - Losing tail-call, reified-continuation, or module-store semantics.
   - Treating H/R provenance as optional or trusting an asserted A association without checking the deterministic derivation.
   - Combining VM replacement, identity migration, row transport, and native-format changes without separate gates.

   Stage E has just established difficult guarantees around pending effects, fencing, custody, durability, and crash recovery. Those mechanisms need not change conceptually, but changing their captured state invalidates integration evidence until rerun. This is substantial re-foundation cost, not a notation cleanup.

   Proceed if the design restricts degrees of freedom and proves the lowerings. Reconsider implementation if stackification requires a new general storage machine or if preserving existing behavior demands extensive decompilation machinery.

VERDICT: PROCEED_WITH_CHANGES — 1) amend A-only identity contracts; 2) freeze structured grammar and register minting; 3) specify windows and result delivery; 4) replace local-stack-expansion and allocator-reuse claims with explicit algorithms; 5) decide format versions from byte/contract evidence; 6) gate deletion on full Stage D/E regression.

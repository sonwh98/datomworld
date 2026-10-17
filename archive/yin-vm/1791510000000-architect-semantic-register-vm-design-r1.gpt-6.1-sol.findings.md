I’ll read the draft in full, then check its grammar, delivery rules, windows, and lowering contracts against the walker and landed VM/UCF code.
The draft addresses the earlier direction changes, but I found freeze blockers in the detailed rules. `L(pc+1)` normally includes the result destination, so the saved-window rule needs explicit subtraction. The grammar also omits scalar operations and rejects some nested conditionals that the prose permits. Tail-effect transport needs a closed state shape for validation and resume.
Completed-GMT: 2026-10-09 18:27:24 GMT
Completed-Local: 2026-10-10 01:27:24 Asia/Bangkok

The architecture is sound in direction, but this draft is not ready to freeze. The main blockers are contradictory window rules, an incomplete grammar, and an incomplete transport shape for tail effects. Read-only review; no files edited or tests run.

References below are to `docs/design/yin.vm.semantic-register-vm.md` unless otherwise stated.

| Severity | file:line | invariant/evidence | Recommended correction |
|---|---|---|---|
| High — architectural defect | `docs/design/yin.vm.semantic-register-vm.md:492` | Standard successor live-in includes the result destination when subsequent instructions read it. For `[:park 0] [:halt 0]`, `L(1) = {0}`, contradicting `rd ∉ L(p)` and the window refusals. | Keep standard liveness. Define the saved window as `W↾(L(p) − {rd})` for write-result delivery; use that rule consistently for calls, captures, waits, reconstruction, and R live operands. |
| High — architectural defect | `docs/design/yin.vm.semantic-register-vm.md:262` | The grammar has no production for `gensym`, `store-get`, or scalar `store-put`; stream-make’s zero-expression production is unspecified. | Publish an exhaustive production for every opcode, with exact child count/order and literal operands. |
| High — architectural defect | `docs/design/yin.vm.semantic-register-vm.md:265` | An `if` expression does not end with a value-writing instruction. Nested conditionals can give one destination three or more static definitions, contradicting §3.2’s pairwise rule. | Define `expr-into(rd)` recursively as an expression with result destination `rd`. Validate exclusive definitions by structured paths, allowing recursively nested alternatives. |
| High — architectural defect | `docs/design/yin.vm.semantic-register-vm.md:311` | Tail calls inside conditional arms are followed textually by structural jumps/alternate code. Pure primitives explicitly continue at `pc+1`, so their structural return is reachable. | Separate structural layout from runtime successors. Either make every tail call return its result directly, including immediate primitive results, or specify the differing paths honestly. |
| High — specification gap, before freeze | `docs/design/yin.vm.semantic-register-vm.md:374` | Tail-effect waits have no current activation or resume PC, but §4.2 requires segment, resume PC, E and window. The linker row always specifies write-result delivery. | Define a closed through-return wait/capture variant retaining the suspension site, K, pending data and required store context; distinguish it from a resumable activation. Apply it to tail require waits too. |
| High — specification gap, before freeze | `docs/design/yin.vm.semantic-register-vm.md:150` | The call cases omit invoking a reified continuation. Today’s `semantic/apply-call` has an explicit abortive `:continuation` arm. | Specify continuation invocation through `:call`: validate arguments/ownership, discard the caller activation and K, restore the capture, and deliver once. |
| High — architectural defect | `docs/design/yin.vm.semantic-register-vm.md:412` | The proposed `:regs` row drops `k-cref`, although the old row and §4.2 both require K. | Retain K in `:regs`; define delivery as a closed typed shape, including the tail variant. |
| High — specification gap, before freeze | `docs/design/yin.vm.semantic-register-vm.md:504` | §3.4 rule 6 does not actually require a unique closure site per body or an acyclic owner tree. Resolution assumes both. | Validate complete body partitioning, one owner per non-main body, rooted acyclic ownership, and body/branch target boundaries. |
| High — specification gap, before phase 3 | `docs/design/yin.vm.semantic-register-vm.md:512` | Pre-order IDs are not definition-ordered PCs—the worked example demonstrates that. “Lowest not live” does not specify allocation across alternative definitions or reservation holes. | Pin intervals/reservations, expiration order, source/destination overlap, shared-destination handling and count calculation in a complete algorithm. |
| Medium — specification gap | `docs/design/yin.vm.semantic-register-vm.md:521` | R live operands are not on every stream opcode; existing `body-liveness` excludes the destination and treats tail calls as having no successors. | Name exactly the r2 boundary opcodes and compute their operands using the corrected save rule or the existing physical-image function. |
| Medium — specification gap | `docs/design/yin.vm.semantic-register-vm.md:450` | Byte equality alone does not establish unchanged descriptors, validation, allocation or execution contracts. | Retain b2/r2 only when both bytes and normative contracts remain unchanged. Version any changed contract even if corpus goldens happen to match. |
| Medium — specification gap | `docs/design/yin.vm.semantic-register-vm.md:37` | Equal observable behavior is not a literal state-space isomorphism. Walker expression frames become PCs and windows; administrative steps differ. | Define a correspondence allowing administrative steps, with explicit mappings for each supported walker frame and effect phase. |
| Low — editorial | `docs/design/yin.vm.semantic-register-vm.md:603` | Phase 1 is described as in flight, although the identity amendments have merged. | Mark phase 1 complete. |

1. **The six required changes**

   - **A-only identity:** addressed by the merged amendments and §§1, 7.
   - **Structured grammar and minting:** partially addressed by §3. Pre-order and body-local scope are good choices, but productions, recursive branch definitions, owner validation and terminal paths need correction.
   - **Windows and delivery:** partially addressed by §§2.4, 4, 8.1. Destination subtraction and the tail-state grammar are blockers.
   - **Explicit derivations:** partially addressed by §8. Whole-body stack lowering is correctly chosen; allocation remains underspecified.
   - **Versions and byte evidence:** partially addressed by §§6, 8.4. The decision must include contract changes.
   - **Stage D/E gates before deletion:** addressed in §10’s intent. Move implementation of the corrected liveness rule earlier and strengthen transport gates as described below.

2. **Isomorphism**

   The intended correspondence works for supported evaluation behavior, but the stated one-register/one-frame correspondence is too literal.

   - `eval-operator` and `eval-operand` become instruction position plus operator/argument registers.
   - `eval-test` becomes test evaluation followed by `branch-false`.
   - `eval-stream-put-target` becomes the PC before value evaluation; `eval-stream-put-val` corresponds to the retained target register and computed value.
   - Stream source/cursor frames become PCs before the relevant instruction.
   - `eval-define` and `eval-resume-val` become child evaluation followed by their instructions.
   - FFI `request-sent` is a retained-request phase that transitions to response waiting without delivering the request append result to guest code.
   - `eval-call` corresponds to correlated response decoding, parked bookkeeping removal, and final result delivery.

   These expression frames are generally represented by **PC and W**, not by a semantic return frame. A return frame represents the suspended caller activation.

   Walker administrative states have no unique one-step register counterpart; register branch/jump/return states likewise need not match a walker step individually. This is acceptable with a correspondence allowing administrative transitions.

   The missing semantic `:call` continuation case is a real gap. Also scope the claim to the shared supported AST vocabulary: the walker contains a `store-update` arm, while current lowering explicitly rejects it.

3. **Grammar and minting**

   The grammar is incomplete and internally contradictory as listed above. Make opcode productions exhaustive and replace “last instruction writes rd” with a recursively defined result relation.

   Pre-order minting is suitable, but specify:

   - Supplying an arm destination does **not** mint another root destination.
   - A resume expression has no result destination: either mint none or explicitly allow an unused ID. The current “every expression” and “every ID has a definition” rules conflict here.
   - Zero-operand operations, define’s literal name, defaults and child order.
   - Queue order for lambdas discovered while emitting queued bodies.
   - Expansion of shared AST occurrences; no implicit body deduplication.

   Body-local IDs work with absolute body PCs when ownership is validated. Definite assignment is decidable by a structured forward walk: intersect assigned sets from continuing arms, and exclude terminal arms from the join. Structural unreachable instructions require a separate validation policy.

4. **Delivery**

   Direct through-K delivery is viable. The engine seam supplies `restore-fn(base, entry, value)` and treats VM payload as data; it does not require a current activation. A new semantic restore can decode the completion, pop one return frame, write its destination, or halt with the result when K is empty.

   No code trampoline is required. This still needs an explicit restore transition, including immediate effect completion, terminal errors, FFI decoding and cleanup.

   Complete the call cases for closures, ordinary primitive values, effect values, reified continuations and non-callable errors. Make pure and effectful tail completion consistent.

   `request-sent` must advance the FFI protocol without guest result delivery. Link request/response/install phases similarly preserve delivery until final completion. Halt transports a result; explicit park transports a resumable record.

5. **Windows**

   Standard `L(pc)` is well-defined over an explicit CFG, including unreachable structural instructions. The draft incorrectly equates a terminal call’s empty live-out with the live-in set of its textual successor.

   Define:

   `saved(write-result, p, rd) = L(p) − {rd}`

   Through-return has no current activation window; its saved caller windows remain in K.

   This fixes park, reification, non-tail calls, FFI and retained writes. A retained write’s retry value belongs in pending data even when absent from the window.

   The three window refusals are useful membership diagnostics, but insufficient as the full validator. Also check representation, integer IDs, duplicate/order rules for rows, body scope, site/PC pairing, destination equality, delivery mode, return-frame sites, K structure and pending compatibility.

6. **Identity**

   A is deterministic under the intended pinned walk. Remaining inputs to pin are defaults/saturation, occurrence expansion, body queue order, resume minting, and recursive arm destination reuse.

   Require canonical numbering validation—or a project/parse round-trip law—if “canonical vector” means the unique projection, rather than any expression-structured vector with arbitrary contiguous IDs.

   Exact identity is preserved: named binders and free names remain in A; allocation stays outside it. Alpha-renaming may preserve H/R while changing A.

7. **Derivations**

   §8.2 is not sufficiently specified for cross-host implementation. In particular, a shared destination’s allocation must not collide with values computed in its alternate arm. Decide whether to reserve one physical slot across the entire structured interval or use another explicitly pinned method.

   The existing parameter L bank remains reserved; canonical parameters stay in E. Preserve duplicate-parameter resolution, nil-fill and extra-argument behavior.

   §8.3 makes H byte identity plausible. It needs an adapter from recovered expressions to the existing lowerer’s resolved-tuple input, preserving occurrence expansion, defaults, tail flags and body discovery order. Absolute PCs must be recomputed after stack emission.

   §8.4 is honest about likely R changes, but compare actual vectors/encoded bytes as well as checksums, and include normative contract equivalence in the version decision.

8. **Safepoints**

   The operation families are covered. Corrections needed:

   - Split linker phases: request append and successful response processing do not themselves deliver the module symbol; install completion does.
   - Let every linker phase carry either non-tail write-result or tail through-return delivery.
   - Explicit park has **no pending wait**; represent it separately from `:wait`.
   - Reified current-continuation is a captured value, never a task safepoint.
   - Preserve retained FFI request → response-wait transitions and correlated errors.
   - Restate whole-task quiescence and existing refusal conditions, including unsupported observation states and pending close.

9. **Blast radius and migration**

   Add explicit coverage for `yin.vm` contract/schema declarations, de Bruijn resolver/lowerer adapters, register-code allocation/validation, pipeline composition, linker derivation verification, dependency census, closure markers and reified continuation encoding.

   “Admission untouched” should mean custody/resource policy; executable admission changes under phase 1. The native de Bruijn code documentation is only a focused edit if its contract survives §8.4.

   Correct the phase dependency: §8.1 liveness is needed by the phase-4 evaluator’s call saves and captures, so implement it with the phase-3 validator/static analysis.

   Phase 6 should gate canonical reconstruction from all intended engines, not only semantic-profile round trips. Phase 7 then reruns Stage D/E custody, crashes and cross-process behavior. Keep deletion gated on all of these.

10. **The four open questions**

   1. **Structural return:** keep it. Define it as layout syntax, not invariably unreachable runtime code, unless all tail primitive completions return directly. Permit structural branch delimiters after terminal arms.
   2. **Body-local IDs:** keep them. Require validated body ownership and scope.
   3. **Carried live claim:** recompute. Window keys already provide the membership claim to validate; an additional live vector duplicates it.
   4. **Tail effects:** route directly through K. The engine restore interface supports this; specify the closed tail wait shape and semantic restore helper. No instruction trampoline is needed.

VERDICT: CHANGES_REQUESTED — 1) correct liveness and saved windows; 2) complete recursive grammar, terminal paths and owner validation; 3) complete call/delivery and tail transport shapes; 4) restore K in row registers; 5) pin allocation and version decisions; 6) tighten correspondence and migration gates.

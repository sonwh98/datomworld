I’ll read the revised draft and check the saved-window rule, terminal paths, transport states, and allocation algorithm for remaining freeze blockers.
Completed-GMT: 2026-10-09 18:35:36 GMT
Completed-Local: 2026-10-10 01:35:36 Asia/Bangkok

Most findings are resolved, but four contradictions remain before freeze. Read-only review; no files edited or tests run.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — specification contradiction | `docs/design/yin.vm.semantic-register-vm.md:600` (§4.3) | Foreign reconstruction still requires “every register in `L(pc)`,” including the not-yet-produced destination. It also requires E without distinguishing tail states. | Require `L(pc) − {rd}` for an act’s saved window, validated caller windows in K, and no current window/E for tail states. |
| High — grammar gap | `docs/design/yin.vm.semantic-register-vm.md:337` | `expr(rd)` includes destination-less `resume`, while bodies require `expr(r)` followed by a terminator reading r, and rule 7 requires every named ID to have a definition. A body consisting solely of resume has no such r. Terminal children also conflict with rule 12’s restriction to branch/return layout syntax—for example, resume in a call operand. | Define expressions with a result-or-terminal outcome. Specify structural terminator operands for wholly terminal bodies and how enclosing syntax is validated after a terminal child. Preserve supported AST programs rather than rejecting them incidentally. |
| High — specification contradiction | `docs/design/yin.vm.semantic-register-vm.md:526` | “Two states are not safepoints” includes explicit park. Explicit park remains a migration safepoint even though it creates no wait entry. | Say explicit park is a **no-wait safepoint**; only reified current-continuation is not a safepoint. |
| Medium — allocation ambiguity | `docs/design/yin.vm.semantic-register-vm.md:710` | Expiration uses `end < p`, so sources ending at p remain assigned when the destination is allocated. The following sentence nevertheless permits destination/source sharing, without specifying whether or how to choose it. §11 defers that choice despite claiming a pinned algorithm. | Prefer the fully specified conservative rule: no same-instruction source/destination reuse. Alternatively, pin exactly when sharing occurs and how its slot is selected. This affects bytes and belongs in the design contract. |

**A. Saved windows:** Correct throughout the transition rules, saved frames, capture rules and R live-set specification. The residual foreign-engine obligation above prevents a full pass.

**B. Grammar:** Opcode coverage and recursive nested-conditional result destinations now pass. Resume still needs a terminal outcome rather than membership in a relation that promises a destination. This also needs to cover terminal expressions nested inside otherwise value-producing expressions.

**C. State and delivery shapes:** The act/tail distinction, K retention, closed delivery modes, FFI retained→sent transition and linker phase preservation are sound. The full validator covers the important structural obligations. Explicit park’s classification needs correction; its no-wait representation is correct.

**D. Allocation:** PC ordering, interval bounds, shared-destination reservation, parameter separation, expiration ordering and tie-breaks are substantially pinned. The optional source/destination overlap is the remaining choice. With overlap prohibited, the written scan is deterministic and implementable.

The physical-image `body-liveness` check should remain an acceptance gate: interval reservations can retain slots beyond runtime liveness, so reservation and saved live values must stay distinct.

**E. Tail completion:** Pure primitive, closure and effect completion consistently route through K. No remaining contradiction requires a trampoline. The statement that all structural instructions after a terminal node have “no predecessors” is imprecise: a shared join can have predecessors from continuing arms, and unreachable nodes can have unreachable predecessors. Describe them as unreachable **from that terminal path**, using the stated CFG edges.

**F. Freeze readiness:** Not yet. The remaining changes are small compared with round 1, but define accepted programs, reconstructible state and deterministic emitted bytes; they cannot all be deferred to implementation.

Properties that passed include body-local minting and arm reuse, FIFO body discovery, occurrence expansion, ownership validation, abortive continuation invocation, exact A identity, byte-and-contract version decisions, K in row registers, and the strengthened migration gates. Selecting the independent liveness reference implementation can remain in the phase-3 brief.

VERDICT: CHANGES_REQUESTED — 1) complete result-or-terminal grammar and resume layout; 2) correct foreign reconstruction; 3) restore explicit park’s safepoint classification; 4) pin allocation overlap and clarify CFG reachability.

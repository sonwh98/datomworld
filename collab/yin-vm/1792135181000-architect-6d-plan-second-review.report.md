Completed-GMT: 2026-10-16 07:19:41 GMT
Completed-Local: 2026-10-16 14:19:41 +07
Coding-Agent: codex (GPT-6; exact deployment model not exposed to this session)
Session-ID: 01a14392-2b1d-7081-a47e-76c8cc6832b7

# Independent second architect review: slice 6d

**Verdict: REJECT the plan as implementation-ready.** The `:run` activation is the right extension, and a running exclusive root must be a custody subject. However, this plan still excludes supported scheduler states, leaves an unbounded child-execution path, and specifies a false all-PC stack equation. These are defects in the proposed closure of the owner's invariant, not reasons to reconsider that invariant.

Reviewed HEAD: `7c4dff32e8633a53c361bf7f34282d51fcf70363`, rather than the plan's `ce76c846`. Citations below refer to files read in this worktree. `plan` means `collab/1793610000000-architect-6d-plan.report.md`; `assessment` means `collab/1792430000000-architect-any-pc-snapshot.report.md`. I read both, the requested architectural documents, and the implementation paths discussed below. The prompt's `docs/design/universal-continuation-format.md` does not exist here; its actual path is `docs/design/yin.vm.universal-continuation-format.md` (abbreviated `UCF` below). The clock is 2026-10-16; the owner's 2026-10-17 ruling is treated as supplied instruction, without inventing a corrected date.

This is a source-based review. No VM, test suite, performance probe, network operation, or build was run. The counterexamples are deductions from the cited transitions; their runtime reproduction remains an implementation acceptance requirement. Only this report was written.

## Findings

Each entry gives **severity | file:line | invariant/evidence | recommended correction**. P1 blocks the proposed architectural acceptance; P2 is an implementation or specification gap that must be resolved in the affected slice.

### F1 — The ready-queue exception contradicts arbitrary-PC migration

**P1, architectural defect | plan:148; src/cljc/yin/vm/engine.cljc:2205; src/cljc/yin/vm/ucf/holder/export.cljc:107 | any-PC continuation completeness | carry scheduler state or establish a semantics-preserving, bounded normalization at the same guest PC.**

The plan explicitly admits an active activation with another completed wake queued, yet requires running the active activation to a park before export. `run-loop` prioritizes the active activation (`engine.cljc:2211`) over the queue (`:2216`); `vm-hot`'s active path never drains that queue (`semantic.cljc:139`). A recursively calling pure activation can run indefinitely. Retrying `hand-off` after another fuel slice can therefore refuse forever, or until the lease expires. The fact that the machine began with multiple waits does not make it malformed: the existing transport expressly preserves ordered multiple waits (`yin.vm.ucf-transport-tuples.md:455`), and the harness transports `two-callers` (`safepoint_harness_test.cljc:1069`, `:1093`).

The result cannot be discarded or reclassified as a pending wait: the wake carries an already observed value/status and cursor resource updates (`engine.cljc:443`). Repeating a completed write can duplicate it; dropping a completed read loses it. The assessment already recognized representability (assessment:101, section B.1); its decision to refuse is no longer compatible with the owner's ruling.

Specify an ordered ready-outcome carrier, including terminal outcomes as deferred scheduler outcomes, cursor-cell aliasing, resource updates and custody implications. Alternatively prove a transformation that preserves the current PC, current activation, queued outcomes and scheduling order without executing arbitrary guest work. Do not describe “run until it parks” as such a transformation. Add a migration case with two completed wakes and an unbounded first activation, and prove export without executing that activation further.

### F2 — R0 rejects a state that the scheduler constructs

**P1, architectural defect | plan:96; src/cljc/yin/vm/engine.cljc:2294; src/cljc/yin/vm/semantic.cljc:116 | no information loss; supported task domain | represent an active activation together with remaining waits.**

Clearing control when creating a wait does not prove that control and a wait set can never coexist. Start from the admitted two-waiter machine, complete only one response, then execute `scheduler-round`. `check-wait-set` retains the unresolved wait (`engine.cljc:2194`); `resume-from-run-queue` pops the completed entry, clears blocked/halted flags and leaves the remaining wait set (`:2305`); `register-restore` sets control without clearing those waits (`semantic.cljc:116`). The result is active control plus a nonempty wait set, with no ready queue required. Explicit resume likewise restores into the existing machine (`engine.cljc:2360`).

This is an admitted blocked carrier resumed by the kernel, even if a single-control program does not initially create two waits on its own. R0 cannot call it inconsistent merely because the new running grammar has no place for it. F1 and F2 require a task-state product (current activation, ordered pending waits, ordered ready outcomes), not just a mutually exclusive fourth root arm. The activation-level `:run` row can remain as proposed. Update the kind rules and distinguish root classification from activation shape. Test one-woken/one-waiting as well as two-woken cases; do not manufacture them only as negative tests.

### F3 — Fuel does not bound an install child or the entire program tick

**P1, architectural defect | plan:113; src/cljc/yin/vm/engine.cljc:1458; src/cljc/yin/vm/ucf/rows.cljc:1862 | preemption at every executing task's boundary | propagate explicit execution budgets through scheduling and child execution, and admit running install children.**

The proposed `run-fuel` calls an unrestricted `scheduler-round` when the root is inactive. That round polls links and advances installs (`engine.cljc:2168`; `poll-links`/`advance-installs`); `advance-install` calls `vm/run` on the child (`:1475`). A blocked root installing a module whose initializer computes indefinitely still never returns to the holder. A one-instruction root fuel budget does not help. The assessment's “child states are unreachable unless the sweep takes fuel; not needed” conclusion is precisely the gap the new invariant makes necessary to close.

Define how a budget reaches child tasks, how it is shared or bounded per tree, and how a child remains at a materialized boundary when exhausted. Current lower admits only a blocked child (`rows.cljc:1862`); the plan neither changes that check nor supplies running-child phase acceptance. Include a long initializer, a nested initializer, and handoff/recovery while the child is running. Retain receiver admission checks for each child.

Fuel also counts VM instructions, not elapsed time: `:call` invokes a host function synchronously (`semantic.cljc:193`), stream construction invokes the supplied constructor (`engine.cljc:480`), and a scheduler round can scan many entries. State the cooperative guarantee precisely: interruption between completed instructions, with no hard wall-clock bound for one atomic instruction or unbounded host seam. Avoid promising a lease deadline from `n` instructions alone. No clock-to-instruction conversion belongs in the VM.

### F4 — A tenure failure still loses the just-computed boundary

**P1, plan/implementation gap | plan:115; src/cljc/yin/vm/ucf/holder/driver.cljc:2118; :2248 | explicit state retention and exclusive authority | return the new boundary as fenced data on tenure loss, and specify which authority may adopt it.**

After `m1` is computed, `require-tenure!` at `driver.cljc:2123` may throw before `m1` is associated with holder state (`:2139`). The catch at `:2253` calls `end-run` on the original argument, not on `m1`. Fuel changes neither this control flow nor the later throws inside replay/emit/read. When tenure dies between completed ticks, the next entry check also ends the run rather than making a successor. `exit-mint` itself requires tenure (`:1144`).

The plan's sentence that a holder can hand off *before* the bound is useful composition policy, but it does not resolve its claim about tenure dying between slices. Specify separate outcomes for successful preemption while tenure is live, expired-tenure retention, and recovery from the last durable checkpoint. Retain the newest completed pure state before a post-slice check can discard it. An expired holder must not offer a valid successor or resume merely because it still owns bytes: adoption needs authority consistent with the ledger's existing origin/completion rules. If expiry intentionally replays the last committed checkpoint, say that progress since it is lost and do not call the invariant fully repaired by fuel.

Add clock-controlled cuts immediately before the slice, immediately after it, during the later driver stages, and between slices. The source must remain fenced, with either a durably authorized successor or an explicitly documented recovery checkpoint. Keeping automatic export a composition decision is sound; leaving the expiry semantics unspecified is not.

### F5 — The all-PC stack equation is false with today's layout

**P1 for 6e, specification defect also blocking 6d-a item 7 | plan:322; src/cljc/yin/vm/semantic/layout.cljc:118; src/cljc/yin/vm/semantic/code.cljc:349 | every-PC reconstruction | derive layouts at control-flow and terminator PCs, not just expression nodes.**

`stack-layouts` walks `(:expr body)` and its `:children`, assigning only each node's `:pc`. The parser stores a conditional's jump in `:jump`, not as a child (`code.cljc:389`), and excludes the body terminator from the expression (`:413`). Thus the purported existing layout is not defined at every PC.

A concrete structured conditional is:

```clojure
[[:const 1 true]             ; 0: test
 [:branch-false 1 4]         ; 1
 [:const 0 10]               ; 2: consequent result
 [:jump 5]                   ; 3
 [:const 0 20]               ; 4: alternate result
 [:halt 0]]                  ; 5
```

At PC 3, `L(3) = #{0}` by `analysis/successors` and `:halt`'s use, while `stack-layout(3)` is nil and `uses(:jump)` is empty. The planned equality therefore reads `#{} = #{0}` and fails. The stack kernel really holds `[10]`: `:const` pushes it (`debruijn/stack.cljc:689`), and `:jump` preserves it (`:777`). The running lift must identify that value as the conditional's result register. At the halt, the result is also on the stack although there is no expression-node layout entry; its operand use supplies the mapping there.

Add branch-result layouts at jumps/joins, explicit empty ambient layouts at terminators where appropriate, and path-sensitive dynamic checks of ordered stack contents. Audit all instruction categories rather than checking the set equation alone. Tail-call residuals also require checking **after** entering the callee: the stack closure-call transition retains `stack'` on a tail call (`stack.cljc:737`–`:748`), so dead caller operands can survive below a running callee. Dropping them only at the tail-call PC does not establish the layout at the next body-start boundary. Test a tail closure call in operand position with nonempty ambient operands and live `K`.

The register-allocation argument is substantially stronger: intervals include `L(p) ∪ def(p)`, and a slot expires only when its end is strictly less than the new PC (`derive.cljc:127`, `:140`). No non-parking-specific register collision was found. This does not validate the separate stack claim.

### F6 — Grant-check instruction contradicts the intended halted behavior

**P2, concrete specification error | plan:336; src/cljc/yin/vm/ucf/rows.cljc:2039 | custody by kind | leave the halted exemption intact.**

The plan says to “drop `(= :halted kind)` from the skip only; keep `fenced?`.” Current code skips grants only for fenced restoration or a halted root. Running roots already take `accept-grant!` once admitted by the surrounding grammar. Removing the halted test instead requires a grant for an origin-only terminal result, contradicting plan:53 and UCF's result-is-not-a-lease-subject rule (`yin.vm.ucf-transport-tuples.md:473`). Change the brief to “preserve `check-custody!` dispatch; extend admitted kinds elsewhere,” and pin halted restoration without a grant as a regression case.

### F7 — The primitive refusal predicate is broader than a primitive in L(pc)

**P2, 6e acceptance gap | plan:349; src/cljc/yin/vm/semantic/layout.cljc:853; :947 | refuse unsupported values before attachment | compute the expected refusal set over the complete payload.**

The existing predicate checks current windows, lexical bindings, nested collections/closure captures, every return frame, and parked states (`state-prim`, `frame-prim`, `value-prim`). A primitive can remain in a lexical binding or saved `K` frame while no primitive is in the current `L(pc)`. Equating every register-target refusal count with only the current primitive-live PC set is therefore not generally correct. The harness already has lexical, captured and nested primitive refusal fixtures (`safepoint_harness_test.cljc:1931`).

Extend the expected set to all payload roots, independently of the implementation traversal, and assert exact refusals **and successful transfers** per case/direction. Update callers too: `lower-defect` currently feeds `(first (:slots w))` to `state-prim` because slot 13 contains `:wait` rows (`layout.cljc:947`); adding only a `:run` branch to `state-prim` will not make it receive the new direct `:run` row. Audit every slot-13 wait destructuring, including restore's `entry` mapping, for the new sum. Do not claim the full three-obstruction list is complete: existing image collision, free-env, install/link and retained-FFI limits remain applicable.

### F8 — The harness description would compare the wrong state and mutate its fixture

**P2, implementation acceptance gap | plan:319; docs/design/yin.vm.ucf-transport-tuples.md:916 | law 2 has no intervening execution; append-only effect discipline | fork each test point before running it, and compare relift immediately after lower.**

The listed order says lower, run to the end, then relift for root equality. That produces a halted or parked root rather than the captured running root. The actual law explicitly requires **no execution** between lower and lift. Write the order as lift, lower, immediate relift/law 2, then run/law 1.

Also isolate each snapshot's resource world. Running the receiver to completion on the stepping source's mutable test streams can append effects or consume scripted outcomes needed by subsequent snapshot points. The existing ring/toy fixtures perform real stream operations. Use independent equivalent worlds or a deterministic input/effect script with fresh identities normalized by the existing correspondence. Preserve negative controls for cursor positions/descriptors, windows and lexical values.

“Every PC” in a dynamic driver means every boundary of the chosen execution, not every CFG-reachable PC or every possible activation. Pin coverage by `(segment, pc, structural state)` and add both branch outcomes, body entries, jumps, returns, tail transitions, resume/capture, scheduler products and install children. The static all-PC pass and dynamic path pass are complementary; neither proves a universal theorem by itself.

### F9 — The new document text overstates liftability and its inventory is incomplete

**P2, documentation defect | plan:254; plan:408; docs/design/yin.vm.semantic.continuation.md:1250 | consistent executable contract | distinguish representable state, portable values, satisfied dependencies and export readiness.**

The amendments announce “Every instruction boundary lifts,” but retain queues and holds as refusals and intentionally pin the pre-branch `ghost` false refusal (plan:321). UCF's proposed safepoint definition also says it carries a pending wait (plan:256), although explicit park is a no-wait safepoint and halt carries a result (`yin.vm.semantic.md:680`). Define a safepoint as a program-driven park/halt boundary, with delivery/pending only for the appropriate cases. Define any-PC **representability** independently of receiver satisfaction and resource availability. F1/F2 cannot be hidden under an “export readiness” qualifier, since those states are already part of supported machine execution.

The `:push` wording is also too strong: it says “no canonical state exists here,” while the assessment correctly notes that stack `:push` only advances PC (`stack.cljc:722`). It is a stuttering boundary with the same values as its successor, not an intrinsically unserializable interior. Either canonicalize it to the successor without running guest semantics, or label refusal as an explicit backend limitation. Retaining that limitation is a scope choice under the owner's fixed backend fragment, not proof of arbitrary hardware-PC coverage.

I independently searched `docs/`, `src/`, and `test/` for safepoint restrictions and `not-at-safepoint`. The main live refusal sites in rows, layout and harness are covered by section 6. Additional omissions/contradictions to reconcile:

- `yin.vm.semantic.continuation.md:1250` still prescribes a stepped-kernel refusal; no disposition in the inventory. Replace it when 6e lands or label its historical scope explicitly.
- `yin.vm.semantic.continuation.md:2421` still supplies replacement conformance text testing only parking windows. Retain it as a historical amendment or add the running-state liveness check; do not leave two normative replacements.
- `yin.vm.ucf-transport-tuples.md:799` (step 7) lists only the old frame kinds and contexts; section 3.4 does not amend that validator list to include `:run`/`:running`.
- `UCF:802` still specifies only saved-window/parking checks; the proposed section 7.4.2 changes totality but does not add the `L(pc)` running-window check there.
- `UCF:1912` still says “no engine change,” and `UCF:2842`'s no-layer-collapsing row still says the hot loop is untouched and lifting is over a blocked machine. The proposed kernel-entry gate changes that engine contract even though it leaves the instruction loop intact. Update both.
- The continuation document's root custody prose at `:368` and `:405`, its context input at `:433`, and its static/runtime inventory in sections 4–5 must agree with the new forms. Adding a later paragraph alone leaves old exhaustive lists misleading.

Guest-event safepoints in `yang.antlr.md` and related code are a separate delivery policy and need not be widened. Historical logs/reports should receive explicit supersession references, not rewritten history. Also reconcile R3/S1 versus plan:316: a `run` with forbidden pending is specified as `:frame-shape` by S1 but expected as `:pending-presence` by acceptance. Pick one ordered diagnostic and test it consistently.

## Answers to the eight review questions

1. **Canonical activation versus complete task.** `put-registers` really materializes segment/PC/window/env/K at fuel exhaustion (`semantic.cljc:50`, `:142`). Those five components are an activation, not a self-contained CESK task. The store is already explicit in the frozen transition relation (`yin.vm.semantic.md:197`); `gensym` depends on `id-counter` (`engine.cljc:2368`); parked continuations, stores, module stores and resource cells are needed too. The existing task wrapper carries the counter, whole root store, parked table and install tree (`rows.cljc:1670`–`:1694`), and module stores reached through environments/closures. A run-state walk must register the current environment and code context before collecting those stores and code. The proposed walk does so. Queues and remaining waits are the missing scheduler components (F1/F2).

   `analysis.cljc:45` computes backward liveness for every body PC, not just parks; the union/difference order at `:64` is correct. This is exact for its declared syntactic CFG fixed point, not path/value-minimal liveness. Branches union both successors, and layout syntax has live sets even when unreachable. No under-approximation was established by reading. Every-PC observational replay plus an independent use/def/CFG oracle is required; checking a validator that uses the same analysis is not independent evidence. For R, keep the all-PC injectivity check; for H, fix F5 first.

   Open cursor cells are explicit resource data, not an implicit stream read position (`rows.cljc:1381`). Seals/owners are reissued by lower, with aliasing through the census. The module registry, primitive implementation/profiles, stream constructors and externally served streams remain composition prerequisites; the five-tuple does not contain them. Telemetry and GC policy are host instrumentation/execution services, not carried semantic registers. `run-fuel` can avoid the public step's telemetry wrapper, but parks/resumes themselves still emit telemetry (`engine.cljc:2352`, `:2363`); “telemetry-free” must mean no per-fuel snapshot, not no possible telemetry emission. Trace replay is deterministic only under equivalent primitives, dependency bindings and external inputs. Ungated effects execute inside steps (`engine.cljc:498`); a host exception is not transactional rollback of an already performed append. The plan should not infer external atomicity merely from losing loop locals.

2. **Custody.** Using the full occurrence/arbitration/next-op-seq/origin arm for a running exclusive root is correct. `header-custody` already chooses it for any non-halted kind (`rows.cljc:1713`), and `with-custody` already chooses gate `:running` for it (`:2058`). Ledger and grantor do need their explicit offerable sets extended (`ledger.cljc:277`, `authority/grant.cljc:75`). Preserve `accept-grant!`, fenced restoration and origin completion; fix F6. The proposed kernel-entry guard correctly closes the new direct pure-step path while exporting/ended. `abort` is not an unconditional return to running: live tenure and proof that no offer may have been accepted are mandatory (`export.cljc:584`, `:590`). Carry that condition into the plan's simplified source paragraph. Add two competing candidates, stale-source stepping, uncertain offer/abort, expired-grant admission, and recovery byte equality for running roots. An exclusive grant authorizes one holder's effects; serializable immutable bytes alone cannot prevent a trusted caller from cloning an object or calling lower twice. Do not claim physical single-instantiation enforcement. F3/F4 remain blockers for lease/preemption completeness.

3. **Quiescence.** Keeping refusal for queues is not compatible with unconditional any-PC migration over the admitted task domain. It is safe refusal, but leaves the named invariant unimplemented. The counterexample need not drain before expiry, and “organically single waiter” cannot exclude carriers already accepted by the system. F1/F2 specify the needed correction.

4. **Foundational invariants.** An explicit fuel argument and holder config add no hidden global, raw callback, shared mutable state, or clock in the kernel. A resumable budget outcome is explicit host scheduling, and migration preserves guest control flow. A new typed row does not require VM knowledge of a dao.space index: it remains stream data interpreted independently. No new graph is assumed if run, K, resource and scheduler references are tabled and validated by the existing graph stage. `:run` is a distinct semantic state, so a row kind is justified; a new root slot need not be invented merely to tag it. The new task product must preserve that separation. Existing local accumulator atoms are construction machinery, not new shared guest state. The plan broadly respects malleability; the phrase “one behavioural change” hides the scheduler and expiry dependencies.

5. **6e.** Register pairing and live-window reconstruction are plausible and grounded in the allocator. Stack mid-expression reconstruction is also plausible, but the claimed existing equation and obstruction list are incomplete (F5/F7). Laws 4/5 are realistic on **aligned semantic boundaries**, after skipping/normalizing H stutters and honoring the documented backend domain. Pin both taken and untaken branches and ordered operand multiplicity; set equality alone loses duplicate-operand information. Keep tail-PC windows distinct from tail *delivery* states. Every-A-instruction step count also needs checking around primitive tail calls and dead suffix syntax, rather than assuming subtraction of `:push` is the only alignment issue. The retained image/free-env/FFI/link limits remain intentionally deferred; no mandate to widen them is inferred here.

6. **Amendments.** The main direction is correct: parking PCs become delivery cases inside a larger state relation, and receiver layout metadata remains derived/static. Replacement text is not ready verbatim because of F1/F2/F9, the malformed-state assertion, and conflicting diagnostics. Section 6 is a good inventory but not complete; concrete additional occurrences are above. Keep the frozen design, continuation row table, tuple validator stages and UCF wording synchronized in the commit that introduces each behavior.

7. **Sequencing/cost.** 6d-a must precede consumers of the new grammar; 6d-b and 6e depend on that grammar but not intrinsically on each other. The asserted total order is therefore not forced (the plan itself says this at :351). Ship kernel fencing with the first exposed running exclusive path, and do not call the owner's invariant closed by fork-only 6d-a. Resolve task/scheduler representation and fuelled install execution before fixing the grammar in a brief. Move the small conditional/jump layout proof ahead of the broad cross-engine matrix; it already falsifies an acceptance gate in 6d-a.

   Module pins and any-PC state are logically independent, but edits to one function and root `requires` are not guaranteed a trivial semantic rebase. Re-run module-store/free-name discharge and receiver admission at mid-body contexts after integration. Existing module pin Q5 (`continuation.md:2776`) is intentionally in-flight and is not a new 6d defect; same-composition harness success does not verify arbitrary receiver equivalence until that slice lands. There is no architecture reason to block initial reference work on a stalled host-profile decision. Land whichever reviewed slice is ready, coordinate rows/fixtures, and revalidate the integrated contract.

   The 11-day estimate is an estimate for a narrower design. It omits scheduler outcomes/remaining waits, running-child carrier and scheduling, expiry retention/adoption decisions, and control-flow stack layouts. Re-estimate those explicitly after the decisions. No measured runtime supports the slow-tier wall-time forecast here. Cut repetitive broad end-to-end cases during iteration; retain targeted counterexamples, all-PC static gates, quick structural samples and a full three-host run at landing. Do not cut custody races or scheduler products to preserve the estimate.

8. **What was verified right; defect/gap/defer distinction.** The core `:run` versus `:act` distinction is correct: current `L(pc)` includes available values, while an `act` excludes the not-yet-delivered destination (`semantic.cljc:64`, `analysis.cljc:92`). The body-start exception to act-only PC/body-continuity rules is necessary (`frame.cljc:122`, `:124`). Retaining nil-valued keys, whole root store, every parked record, source AST associations, fresh receiver seals and existing admission stages is correct. Reusing slot 13 can preserve existing encoded row identities, provided all typed traversals are updated. Grant-gated running custody, fencing before publication, a composition-owned fuel knob, and reference/cross-engine slice separation are all sound foundations checked against the cited code.

   F1/F2/F3 are architectural completeness defects, F5 is a false reconstruction specification, F4 is an unresolved lifecycle design with a concrete implementation loss path, and F6–F9 are implementable contract/acceptance corrections. Existing registry pinning, general correlation/census gaps and the retained backend fragment are deferred work already documented; this review does not silently expand their scope. It does require the plan to stop treating them, or same-composition fixtures, as universal portability evidence.

## Required revision before acceptance

1. **Make the task state complete:** carry active control together with ordered waits and completed scheduler outcomes; remove R0 and the queue exception as purported proofs of arbitrary-PC coverage. Include running install children and explicit tree budgets.
2. **Complete the custody/preemption lifecycle:** retain the boundary on post-slice tenure failure, specify lawful expiry/recovery behavior, keep halted grant exemption, and verify stale-source/two-candidate/recovery cuts for running roots.
3. **Repair and prove all-PC reconstruction:** add conditional-jump/join and tail-residual layouts, normalize or explicitly scope stack stutters, fix the full-payload refusal oracle and no-execution law-2 ordering, then synchronize the documents.

The owner has settled whether to provide preemptive migration. The revision must settle how all already admitted machine states remain expressible and recoverable when preempted. Until these changes are incorporated, the plan's `PLAN-READY` conclusion is unsupported.

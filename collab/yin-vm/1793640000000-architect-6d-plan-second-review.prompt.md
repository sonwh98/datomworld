Created-GMT: 2026-10-17 12:30:00 GMT
Created-Local: 2026-10-17 19:30:00 +07
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: pending (provider-generated)

# Task: independent second architect review of the any-pc snapshot plan (slice 6d)

Role: Lead System Architect (docs/agents/roles/architect.md)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-17 19:30 +07 | Status: active | Rationale: owner asked for a second architect from a different model family than the plan's author (claude-fable-5-1); you review, you do not implement

Perform a read-only architecture review of Fable's refreshed plan for slice 6d (any-pc snapshots, preemptive migration at any PC): ./collab/1793610000000-architect-6d-plan.report.md, and its predecessor ./collab/1792430000000-architect-any-pc-snapshot.report.md (the 2026-10-13 assessment; dated before phases 7 and 8, so file:line citations in it may be stale). Edit NO file except your report ./collab/<ms>-architect-6d-plan-second-review.report.md.

OWNER RULING that frames the review (verbatim, 2026-10-17): "preemptive migration, the ability to pause and continue at any PC, is a primary invariant of yin.vm. It is why yin.vm is defined as a continuation VM based on the CESK model. The UCF being only defined at a wait or parked state is a violation of this invariant." So the question is not whether to do it but whether the PLAN is right, complete and safe.

Read first:
- docs/design/datom.world.md (invariants), docs/agents/malleability.md
- docs/design/yin.vm.semantic.md (frozen register-VM design, sections 2.4, 4.1-4.3, 10), docs/design/yin.vm.semantic.continuation.md (0.2, 2.x, 3, 4, 5, 7, 11), docs/design/universal-continuation-format.md (7.4.1, 7.4.2, 7.7.4, 7.8-7.11), docs/design/yin.vm.ucf-transport-tuples.md
- src/cljc/yin/vm/semantic.cljc (vm-hot, put-registers, run, step), src/cljc/yin/vm/semantic/frame.cljc (validator), src/cljc/yin/vm/semantic/layout.cljc, src/cljc/yin/vm/semantic/analysis.cljc (live set L(pc)), src/cljc/yin/vm/ucf/rows.cljc (lift-task*, lower-task, kind tables, custody), src/cljc/yin/vm/ucf/holder/driver.cljc and export.cljc (run-cycle, hand-off, enter, prepare), src/cljc/yin/vm/engine.cljc (run-loop, scheduler-round, handle-put, the :running gate, the ready queue)
- test/yin/vm/semantic/safepoint_harness_test.cljc (laws 1-6)

Evaluate, and report each with severity | file:line | invariant/evidence | recommended correction:
1. Is every instruction boundary of the kernel truly a canonical, self-contained continuation state <A, pc, W|L(pc), E, K>? Try to BREAK it: hidden state outside those five (id counters, parked map, wait set, store, module stores, telemetry, ready queue, in-flight stream cursors, open cursor cells), non-determinism, anything the plan says is covered that the code does not support. Check the claim that live set L(pc) is exact at EVERY pc, not only parking pcs (what if the register allocator or analysis under-approximates at a non-parking pc).
2. Custody for a running root (plan section 2): occurrence, lease, fencing, exclusive handoff, the grant, the gate. Can a running root be duplicated or lost across the pause (two resumers, a stale source continuing to run after the grant, a lease expiring mid-slice)? Are the source's rules after grant sufficient? Does cooperative preemption by fuel really give a boundary the holder can lift, including when tenure dies between slices?
3. The quiescence rule: keeping the ready queue a refusal (non-empty ready queue = not quiescent). Is that compatible with the owner's invariant "pause and continue at ANY PC"? Could a legitimately running machine be in a state where the queue is non-empty and preemption is therefore impossible, and does that violate the invariant in a way the plan hides? Be adversarial.
4. Invariants of datom.world.md: no hidden global state, no implicit control flow, no raw callbacks, no shared mutable state, no layer collapsing, no assumed graphs. Does the new run-fuel / holder :fuel config / driver change violate any? Does the new `:run` row kind keep symmetric ignorance between yin.vm and dao.space?
5. The cross-engine half (6e): de Bruijn stack and register backends at every pc (register pairing, stack operand layout, tail-call dead layout, primitive-function-in-register refusal). Is the claimed set of obstructions complete? Is "laws 4/5 at every pc" realistic for the stack kernel mid-expression?
6. The amendments (plan section 3): is the replacement text for UCF 7.4.1 and the other documents consistent with the code and with each other; does any sentence still assert the safepoint-only rule (the plan's section 6 list: is it complete? grep the repo yourself for "safepoint" assertions that restrict lifts).
7. Sequencing and cost: the plan says 6d-a, 6d-b (holder/custody), 6e; the module-pin slice is editing rows.cljc now. Is the order forced? Is the effort realistic? What would you cut or reorder?
8. Distinguish architectural defects from implementation gaps from intentionally deferred work. List what the plan got RIGHT that you verified (say which claims you checked against code). Finally: ACCEPT / ACCEPT WITH CHANGES / REJECT, with the three most important changes if any.

Begin your report with Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines. Keep it concrete, cite file:line verified by reading, and mark anything you could not verify.

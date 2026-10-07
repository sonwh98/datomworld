## Goal Description
Perform an adversarial review of the `docs/design/yin.vm.code-as-tuples.implementation-plan.md` drafted by the Architect. The goal is to verify the plan's claims against the current codebase (`dao.stream-redesign-v2` branch), validate its dependency logic, assess its default decisions and traps, judge the estimates, and provide a final verdict on whether the plan is ready for owner sign-off.

## User Review Required
> [!IMPORTANT]
> The deliverable for this task is the review findings themselves. Since the task instructions explicitly state "Do not edit any file", there are no modifying commands or code changes to execute after this plan. Approving this plan will effectively conclude the task.

## Review Findings (Most Severe First)

### 1. D4 (Medium and Batch Coordinates) is a Real Unresolved Blocker
The plan correctly identifies D4 as the largest architectural gap. `yin.vm.macro.md` requires a batch coordinate for expansion events, but `dao.stream.md` cursors are positionless and opaque. The plan offers no default for D4 because any choice requires trading off portability against stream cursor rules. This is an honest and accurate assessment: Phase 3 (U15, U16) cannot proceed until the owner and `dao.stream.md` provide a coordinate shape.

### 2. The "Traps" are Real and Accurately Documented
- **U4's `:call`/`tailcall` folding:** Verified in `src/cljc/yin/vm/semantic.cljc:568-570`. The semantic VM's `load-image` folds `[:call argc true]` into a single `[:tailcall argc]` opcode. If the U4 lowerer emits the folded form directly into the canonical vector, its hash will diverge from the projection path. The trap is real.
- **U2's metadata check:** Verified in `src/cljc/yin/vm/linearize.cljc:45-58`. The `plain-data?` function is indeed explicitly recursive on `(meta x)`. The U2 row validator must replicate this to prevent host objects from smuggling in via metadata. The trap is real.

### 3. "Built" Census Claims are 100% Accurate
The plan's corrections to the orchestrator's brief are verified in the codebase:
- **Codec:** `ast->semantic-bytecode` and its inverse exist and are tested in `v2.cljc:590-826` and `v2_test.cljc`.
- **§7.7.2 nil-fill binding:** Verified at `engine.cljc:46-51` (`bind-params` pads with `(repeat nil)`).
- **`dao.jing/segment-key`:** Verified at `jing.cljc:271-310`. It performs an order-normalized print that explicitly preserves metadata, closing the §4.2 conformance pairs.

### 4. Phase 1 Dependencies are Truly Independent of Phase 2/3
The plan claims U1-U7 can be built now without D1-D6. This holds up:
- **U4 (Lowering) and U15 (Occurrence Identity):** U4 stores the `origin` opaquely and passes it verbatim. It does not need D4's batch coordinate shape to function.
- **U7 (Occurrence relation):** U7 scopes occurrence rules to a `?root`, making it a pure structural index over a single tree. It does not rely on global provenance or the expander.

### 5. Default Decisions (D1-D6) are Sound and Safe
- **D2 (Walker Topology):** The default (Topology 1: walker reconstructs from the row medium) is the safest and most compliant choice. It enforces §7.4 row validation on all paths and closes the `vm/store-update` gap described in §3.1.
- **D3 (Row Storage Grain):** The default (individual rows with optional pack) preserves the cross-tree structural sharing promised by §4.4.
- **D5 (Expander):** Defaulting to datom-native respects the extensive design work already poured into `yin.vm.macro.md`, using the §9.1 adapter as a bridge.

### 6. Estimates and Boundaries are Honest
- **Phase 1 (3 weeks):** Well-calibrated. U4 (lowering) and U7 (occurrence relation) are appropriately sized as multi-day units.
- **Phase 3 (2-3 months):** Accurate. The plan correctly flags that U12-U16 are building against uncommitted or "Proposed" documents (UCF, `macro.md` Phase 1) and will require design cycles (e.g., U14's work-item fixed point).
- **Boundary Table:** The plan does not smuggle Phase 3 work into Phase 1, nor does it attempt to resolve owner-level design gaps (like `dao.jing`'s transit metadata dropping) on its own.

## Verdict

**Ready for Architect/owner sign-off.** 

The plan is exceptionally accurate. Its census of the current working tree is flawless, its dependency phasing is rigorous, and its identified traps and blockers are genuine. It successfully separates what can be built today (Phase 1) from what requires executive decisions (Phase 2) and what is still speculative architecture (Phase 3).

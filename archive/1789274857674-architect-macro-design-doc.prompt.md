Created-GMT: 2026-09-13 04:47:37 GMT
Created-Local: 2026-09-13 11:47:37 +07
Coding-Agent: claude
Session-ID: fc5dc6a9-b701-4ba8-b862-8dad76ada2e3

# Task: review-reconciliation-and-yin-vm-macro-design-doc

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-13 11:47:37 +07 | Status: active | Rationale: Primary system architect, resuming session fc5dc6a9-b701-4ba8-b862-8dad76ada2e3

## Briefing

Your initial Unified Macro System Architecture specification on `yin.vm`
(`collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md`)
was submitted to three independent cross-family reviews:
1. `gpt-6-astra` (Routine & Invariants Review, via Codex):
   `collab/1789272850107-review-macro-system-v2.gpt-6-astra.findings.md`
2. `glm-5.3` (VM Runtime Review, via GLM):
   `collab/1789272850107-review-macro-system-v2.glm-5.3.findings.md`
3. `moonshotai/kimi-k3` (Compiler & AST Review, via Command Code):
   `collab/1789272850107-review-macro-system-v2.kimi-k3.findings.md`

All three models independently confirmed that the core architectural foundations are sound:
- One expander (`yin.vm.macro`) across compile-time and runtime.
- Universal AST maps as operands.
- Explicit root fact `[root :yin/root true]` (verified against the real `index-datoms` heuristic).
- Ephemeral code segment execution (`OP_MACRO_EXPAND` / Opcode 24).
- In-VM ledger (`:macro-expansions`) with `[ev :yin/source-call call-eid]`.
- Throwaway `ast-walker` sandbox for macro body evaluation.
- Deny-by-default runtime authorization (`:macro-authorize`).

However, the reviewers identified several concrete defects and precision gaps:

### Key Review Feedback Items to Reconcile:

1. **Tail-Marking Propagation in Splice (Unanimous across all 3 models)**:
   - Lowering reads `:yin/tail?` off AST nodes. The expansion root produced by prelude constructors (`yin/application`) has no `:yin/tail?` set.
   - Consequently, recursive calls inside the macro expansion lower as ordinary `:call` instead of `:tailcall`, pushing stack frames and breaking the $O(1)$ loop continuation depth on semantic and stack VMs.
   - Fix: The expander must mark tail positions over the output root spine before emission when the call site was in tail position.

2. **Loader Composition Arity Mismatch (`gpt-6-astra` & `kimi-k3`)**:
   - `stream-observer/run-on-stream` calls its loader as `(load-program vm batch)`.
   - `(comp ast-walker/vm-load-program (macro/expand-with opts))` passes both arguments to unary `expand-with`, causing an `ArityException`.
   - Fix: Specify the explicit binary adapter:
     `(fn [vm batch] (vm-load-program vm ((macro/expand-with opts) batch)))`.

3. **Walker Hot Loop Inlined Twins (`glm-5.3` & `kimi-k3`)**:
   - `ast-walker-run-active-continuation` (`ast_walker.cljc:519-686`) inlines closure application (lines 540-562, 580-605) and `:lambda` closure map creation (lines 639-646).
   - Modifying only `apply-function` causes macro closures executed via `vm/run` to bypass Decision 8's rejection, and hot-loop created closures lose `:macro?`/`:phase-policy`/`:eid`.
   - Fix: Explicitly document updating the inlined hot-loop sites.

4. **Recursive Plain-Data Validation on Literals (`gpt-6-astra` & `glm-5.3`)**:
   - `valid-ast?` checks node shapes but not `:literal :value`. A macro could return `(yin/literal yin/gensym-sym)` or host closures, letting host objects escape into datoms and breaking serialization.
   - Fix: Enforce recursive plain-data checks on `:literal :value`.

5. **Dual Datom Guard Granularity (`glm-5.3` & `kimi-k3`)**:
   - A flat 10,000-datom guard per `expand-batch` call rejects legitimate multi-function files, while per-event alone misses breadth explosion.
   - Fix: Specify dual bounds: `:max-datoms-per-expansion` (10,000) and `:max-datoms-per-batch` (configurable / unlimited).

6. **Structural AST Sharing vs Mutation (`gpt-6-astra`)**:
   - Modifying an operand (e.g. `(assoc operand :value 42)`) while retaining its `:eid` would cause the emitter to return the original entity reference if it only checks membership in `:existing #{eid ...}`.
   - Fix: Define identity preservation for structurally unchanged subtrees only; allocate fresh entity IDs along changed paths.

7. **Sandbox Store Visibility & Capture (`gpt-6-astra` & `glm-5.3`)**:
   - Clarify that macro expansion is a pure function of syntax inside a throwaway sandbox without a store bridge; macros cannot read or mutate the runtime store or capture lexical locals.

8. **Semantic Static Macro Addressing (`gpt-6-astra`)**:
   - Address segment macro sites by `[segment-id pc]` rather than bare `pc` to avoid collisions across multiple loaded segments.

9. **Implementation & Test Fixtures Details (`kimi-k3`)**:
   - `stdlib-forms` recovered from git history at `d8b27a5^:src/cljc/yin/vm/macro.cljc`.
   - REPL single-form path threads macro-env.
   - Opcode 24 allocation in `opcode-table` and `opcase`.

## Instructions

1. Evaluate each of the review findings above.
2. If you agree, create the new canonical master design document at:
   `docs/design/yin.vm.macro.md`
   incorporating the complete unified macro architecture, all diagrams, formal schemas, expansion algorithms, VM integration contracts (for `ast-walker`, `semantic`, and `stack`), security rules, and implementation phases with all the consensus corrections resolved.
3. Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: fc5dc6a9-b701-4ba8-b862-8dad76ada2e3
Role: Lead System Architect

Then report your evaluation of the feedback items, the decisions made, and confirm the creation of `docs/design/yin.vm.macro.md`.

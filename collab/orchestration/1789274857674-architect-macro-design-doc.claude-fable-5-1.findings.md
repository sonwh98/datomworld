Created-GMT: 2026-09-13 04:54:56 GMT
Created-Local: 2026-09-13 11:54:56 +07
Coding-Agent: claude
Session-ID: fc5dc6a9-b701-4ba8-b862-8dad76ada2e3
Role: Lead System Architect

The canonical master design document now exists at docs/design/yin.vm.macro.md. It is the only file created; nothing else in the repository was changed.

**Evaluation of the nine consolidated items**

All nine are correct and are adopted.

1. **Tail marking.** Adopted as a single expander-side pass over the finished output, seeded from the call site's tail flag. Prelude constructors no longer mark anything. The walker's own TCO is structural, so the original text's claim that this protected walker TCO was wrong; the consumers are the linearizer and the stack VM.
2. **Loader arity.** Adopted as a named adapter, `macro/loader`, with a matching `linearize/loader`. The semantic spec has the same defect in two examples and is corrected by its Phase 0.
3. **Hot-loop twins.** The three inlined sites are named explicitly, and the "hot loop does not change" claim is withdrawn.
4. **Literal validation.** The validator now enforces a closed per-node vocabulary matching exactly what the emitter supports, with recursive plain-data checks on every value. The `:vm/*` glob is gone and `:vm/store-update` is excluded.
5. **Dual datom guards.** Per-expansion 10,000 checked from the validator's node count before emission, plus a per-batch bound that defaults to unlimited and is charged for ancestor copies too.
6. **Sharing vs mutation.** Identity is preserved only when the node's own attributes and every child's resolved eid equal the original. Forged eids are rejected.
7. **Sandbox.** Macros are closed syntax transformers. Captured environments are discarded, and the earlier "a runtime macro may read the store" rationale is corrected.
8. **Semantic sites.** Sites live inside each segment image, so they are addressed by segment and pc. Static macros resolve from the inline call AST, so no closure instruction is needed.
9. **Fixtures.** All three points folded into the roadmap.

**Further gpt-6-astra findings I also adopted**

- Transaction-local versus durable identity is now explicit. The cross-language ordering rule is retained rather than relaxed, refs are written only for intra-batch or durable ids, yang's macro eid counter becomes a watermark in the env, the runtime allocator only decreases, and inline AST values in code datoms are eid-free.
- The walker's loader keeps eids on macro-call subtrees, so runtime provenance has the real call identity.
- A step budget, cycle guard, and explicit parked-body rejection close the fuel gap.
- The attempt event is allocated before authorization, and the runtime result is a structured value the evaluator adopts in both outcomes, so denials are audited.
- The impossible whole-VM serialization test is replaced by a portable snapshot.
- Shadow hints travel as facts so cross-unit discovery keeps them.

**Two things I did not adopt**

- I kept the ledger as VM-value data rather than an in-transition stream append. The reasoning is restated in the document's §4.5, and the reviewer accepted the ledger as reasonable.
- I did not add a store bridge for macro bodies. The purity of expansion is what makes the compile/runtime parity test meaningful, so the restriction is stated as a contract instead.

Appendix C of the document maps every review finding to its resolution so the reconciliation is auditable. Phase 0 of the roadmap covers amending `macro-design.md`, `cross-language-macro.md`, and the divergence register to point at the new document.

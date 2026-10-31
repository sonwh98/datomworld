# archive/yin-vm-wasm — the register-VM wasm-lane spec epic (2026-10-18/19)

Collab artifacts of the session that designed the spec tree committed in
`24f72932` (datom.world.abi.md, dao.stream.wasm.md, yang.wasm.md,
yin.vm.rust.md, and the amendments to datom.world.md, dao.stream.md,
yin.vm.engine.md, yin.vm.debruijn.register.md).

Lineage, in order:

1. `1792329904305-architect-register-wasm-lower-plan.*` — the original plan
   (gpt-6-astra, codex thread `01a14f30-ca56-7530-bb93-3a870eca7a7b`).
2. `1792330570048…1792335500000` — five review rounds with gemini-4-argon
   (agy conversation `f365ef1d-caa2-4379-8e96-51e64ca36af8`): semantic
   fidelity (r1/r2 APPROVE), portability (r3), determinism (r4 APPROVE),
   rust-kernel reconciliation (r5 APPROVE).
3. `1792342000000…1792346481803` — distillation into committed design docs,
   the owner-directed decompositions (yang.wasm split; engine.md extension;
   ABI rename to yin.vm.engine.host.v1; register.md §10 fold; kit dissolved),
   and the retired drafts preserved here as `retired-2026-10-19-*`.
4. `1792349357932…1792349854263` — astra fidelity reviews of the tree
   (FAIL → F1/F2/F3 fixes → PASS).
5. `1792350400000…1792353000000` — the two four-architect ABI polls
   (astra, deepseek `5050dc03-…`, fable `9f2c1a44-…`, argon) that produced
   the one-ABI ruling.
6. `1792392118180…1792401600140` — the owner boundary clarification
   restructure (engine-in-guest, adapters, service protocol dissolved), the
   abstraction-taxonomy correction, the final decomposition, and the deepseek
   verification trails (both PASS).

Design only; nothing implemented. Owner-gated follow-ups recorded in the
orchestrator-log entry of 2026-10-19.

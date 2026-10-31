Created-GMT: 2026-09-20 18:00:55 GMT
Created-Local: 2026-09-21 01:00:55 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn projection authoring thread)
# Task: apply the confirmation round's residual findings (R1-R7) to the de Bruijn projection design
Role: architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-21 01:00:55 +07 | Status: active | Rationale: same authoring thread; reviewer (claude-fable-5-1) pre-authorized closure when these are applied as written

# Task: adversarial design review — the de Bruijn projection (canonical code form)

The confirmation review of your revised `docs/design/yin.vm.debruijn-projection.md` resolved eight of nine findings and returned seven residual edits, all in §1, §2, §4, §5 and the D0/D1/D3/D4 phase criteria. The reviewer's exact wording governs; apply each as written:

- **R1 (P2) §4/§5**: the node hash must cover only the slots of the node's tag, in descriptor order; `:yin.debruijn/root` and `:yin.debruijn/hash` are excluded from the hashed slots. State it, or a term hashes differently as program root versus subterm, breaking hash-consing.
- **R2 (P2) §2**: `:yin/macro-name` is emission history (the same defect `:yin/tail?` was removed for). Tolerate the attribute, exclude it from rows and hashes, and drop `:yin.debruijn/macro-name` from the descriptor.
- **R3 (P2) §5**: declare `+0`/`-0` distinct, not unified — `datom.md` gives the dimension the choice, and unifying is a free collision `(/ 1 x)` distinguishes at runtime.
- **R4 (P2) §5/D3**: rule on integral doubles. Adopt the reviewer's recommendation: encode every integral double in the int64 range as int64 on all hosts, and record the resulting `1` vs `1.0` collision beside the NFC limit. If you have a soundness reason to prefer the alternative (rejecting non-integer-typed integral literals), state the reason explicitly in your final response instead of applying the recommendation.
- **R5 (P3) §1/D0**: state the unexpanded-macro detection rule (an application whose operator is a `:lambda` carrying `:macro? true`) and its limit (a free variable naming a registry macro is undetectable from the datoms).
- **R6 (P3) §2**: the assert test is "`m` is not the retract op", not "`m` = `datom/default-op`" — `m` is a metadata entity and only `:db/retract` is reserved (`datom.cljc:17, 71-73`); an assert with other provenance must not be refused.
- **R7 (P3) §2/D1/D4**: state that the fact index resets at each frame boundary (emitter temporary ids restart at `-16` per graph), and add a D4 test of adjacent graphs that reuse eids.

Also update the §8 test matrix for the rules that need coverage: `+0.0` vs `-0.0` hashing differently (R3), an integral-double literal hashing identically across hosts (R4), and the R7 adjacent-graphs test. Keep every other section byte-stable where the edits do not require touching them.

Edit only `docs/design/yin.vm.debruijn-projection.md`. When done, reply with a per-finding confirmation (R1-R7 → applied / deviated with reason) and the section each edit landed in.

Created-GMT: 2026-10-10 19:20:00 GMT
Created-Local: 2026-10-11 02:20:00 Asia/Bangkok

# Task: revise the phase-6 design after codex review (continuing your session)

Same worktree and branch (HEAD 261fce5a). Edit ONLY `docs/design/yin.vm.semantic-register-vm.continuation.md` and your report. No git add/commit. Codex findings (read all, 13 rows, rulings on F1-F4, U1-U6, R1-R9, C1): `collab/1791760000000-architect-srvm-phase6-design-review.gpt-6.1-sol.findings.md`. Verify each claim against the code before writing it; where you disagree, say so with evidence.

Fix in this order:
1. HIGH, free environment (continuation.md ~782, §4.4, §6.3, root schema, R5): recovering free-env as E minus lexical names loses free bindings hidden by lexical shadowing (codex's example: initial x=99, `(let [f (fn [] x)] ((fn [x] (f (stream-next c))) 1))`; at the read E binds x to 1). Reverse your flagged choice: carry the task's free environment explicitly (a root slot) or prove an equally lossless representation; amend §4.4, §6.3 and the root schema together; add the example as a pinned harness row on all lanes.
2. HIGH, stack K slices (~531): frame i uses `[previous-base, stack-base_i)` with previous-base 0 initially; the current activation uses `[last-base, stack-count)`. Re-derive both worked stack examples with the corrected intervals and add a multi-frame example.
3. HIGH, stack tail completion (~534): a tail-call wait is not only enclosing-frame slices for tail calls inside operands, and the current stack kernel resumes host effects at pc+1 rather than through K. Specify the stack kernel's tail-completion support, gate it, and add the needed file/dependency to slice 6c.
4. Register K `rd` (~469): recover canonical `rd` from the addressed A call (physical dest 3 corresponds to virtual 2 in the worked example); resolve each K frame's segment and relative return pc independently, including frames crossing images.
5. `act` vs `tail` (~475, ~539): give separate complete lowering algorithms; for register tail entries `site`, `dest nil`, `live []`, return delivery; state how unused native fields are populated.
6. Static laws (~719): use actual-successor live-out as phase-5 does (tail live is empty); restrict inverse reconstruction to registers required at reachable canonical safepoints; validate unreachable structural operands separately.
7. Validator contract (~324): publish the complete pure input contract (frame, code, context, parked-table key, the task's live FFI pendings); guard rules 4-8 to `act`; specify malformed K/window/return-window refusals and safe dependent reads; rule 14 reads decoded task context, not an engine.
8. Order (~375): validation vs canonical numbering; keep the carrier's step 7 order (validate after numbering) everywhere, including first-defect tests.
9. Blinding (~687): `stream-blind` rewrites maps but does not recompute Merkle ids; specify a test-only transformation of decoded tasks followed by canonical numbering and full re-emission.
10. Amendment inventory: extend U1 to UCF lines 513-527; U3 to the observed-deltas, activation-base and stack-height text; R3 to the table's decoded value and `registers R` descriptions; R8/R9 to carrier §10.5-10.8, §8 step 1's registry gate, summaries and §11; grep the carrier for every surviving legacy-reader, semantic-only, unchanged-profile and no-kernel-translation claim and list them all. Apply phase-5's complete association rule (absent or empty tree sets are incomplete).
11. 6a dependency (~848): full task transport needs the phase-5 scanner slice; either gate it or make 6a the independent frame/row codec and defer full task transport.
12. Test fixes (~871, ~965): use an earlier nil operand `(f nil (stream-next c))`; membership tampers for membership refusals, execution parity for changed values.
13. Harness matrix: publish the expanded matrix (link phases, install modes, nested capture, capture invocation with concrete programs and expected outcomes), which tail FFI/bookkeeping shapes are executable and which need synthetic validation fixtures; list required 4b stage 2 facilities.
14. Grounding: gc-children is at stage-1 `semantic_register.cljc:386`; the module-context template fixture is parked at an explicit `:park`, so add a separate program for the blocked module-closure row.

Report to `collab/1791770000000-architect-srvm-phase6-design-revise.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: what changed, what you verified in code, anything unverified or disputed.

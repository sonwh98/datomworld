Created-GMT: 2026-10-10 16:15:00 GMT
Created-Local: 2026-10-10 23:15:00 Asia/Bangkok

# Task: revise the phase-5 design, round 2 (continuing your session)

Same worktree and branch. Edit ONLY `docs/design/yin.vm.semantic-register-vm.derivations.md` and your report. No git add/commit. Codex round 2 findings (read all): `collab/1791630000000-architect-srvm-phase5-design-review-r2.gpt-6.1-sol.findings.md`. Everything else was confirmed fixed. Fix these five, verifying each claim against the code before writing it:

1. (High, line ~968) `prelude/free-names` includes the definition operator `yin/def`; `scan/free-names`, `vm/free-names` and linker obligations exclude it. Compare against `(disj (set (prelude/free-names ast)) vm/definition-operator)`; apply the same qualification in the acceptance list.
2. (Medium, ~430) The allocation lemmas wrongly equate a child expression's result with its first emitted instruction. Counterexample `((f))`: frozen scan emits f into T0, the inner result T1, then reuses T0 for the outer result; R1 also gives the outer result T0. Derive it yourself. Restrict the root-result and call-destination claims to cases that demonstrably hold, or keep only the worked examples and individually established corpus cases. No universal claims without proof.
3. (Medium, ~1144) The export gate permits only `addresses`, but §5 requires public `resolved-addresses`. Permit both address-sequence readers; distinguish them from functions assigning an executable identity.
4. (Medium, ~1557) F11's frozen-amendment replacement text must match §6.3: structural-order multisets, the emission-order oracle, and complete success/refusal outcome comparison including refusal reason and name.
5. (Low, ~1172) "The other 22 reference unbound names" is false (`:closure-in-arm-in-body` has no free names; terminal/resumption rows have other reasons). Describe the ten rows as the selected runtime corpus, or enumerate the real exclusion reasons.

Report to `collab/1791640000000-architect-srvm-phase5-design-revise2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: what changed, what you verified. Flag anything unverified.

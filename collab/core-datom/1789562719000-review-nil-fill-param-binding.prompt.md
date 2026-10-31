Created-GMT: 2026-09-16 05:45:19 GMT
Created-Local: 2026-09-16 12:45:19 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: Review the nil-fill parameter binding fix (§7.7.2)
Role: Adversarial Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 12:45:19 +07 | Status: active | Rationale: cross-family from claude-sonnet-5 (implementer)

## Context

Read `docs/design/yin.vm.code-as-tuples.md` §7.7.2 for the rule this
implements: an under-arity closure call must bind every declared
parameter name, filling missing arguments with `nil` rather than leaving
them absent (which previously let them fall through to the closure's
captured environment, or further). An over-arity call still drops extra
arguments — unchanged.

`git diff -- src/cljc/yin/vm/ast_walker.cljc src/cljc/yin/vm/engine.cljc
src/cljc/yin/vm/semantic.cljc test/yin/vm/ast_walker_test.cljc
test/yin/vm/engine_test.cljc test/yin/vm/semantic_test.cljc` shows
the change. Summary: a new `engine/bind-params` helper
(`(into {} (map vector params (concat args (repeat nil))))`) replaces
bare `zipmap params args` at four call sites (two in `ast_walker.cljc`'s
`apply-function`, two more in the same file's `cesk-transition`
`:eval-operator`/`:eval-operand` arms, one in `semantic.cljc`'s
`apply-call`). New tests in three files. Full findings:
`collab/1789562276000-vmruntime-nil-fill-param-binding.claude-sonnet-5.findings.md`
— verify rather than trust.

## Task

1. **Verify `bind-params`'s correctness** yourself: under-arity,
   over-arity, exact-arity, and zero-params cases. Confirm it's
   equivalent to `zipmap` in every case except the specific under-arity
   fix (nil-filling instead of omitting).
2. **Verify the "load-bearing" claim.** The findings say reverting just
   the `apply-function` site back to `zipmap` makes two new tests fail
   with specific errors (`(not (nil? :outer-y))` and an unresolvable-symbol
   exception). Reproduce this yourself — temporarily revert that one site,
   run the walker tests, confirm the failures, then confirm reverting
   your revert restores green. Don't just trust the reported error text.
3. **Verify the dead-code claim.** The findings say
   `ast-walker-run-active-continuation` (containing two of the four fixed
   call sites) is never called from anywhere in `src/` or `test/`. Confirm
   with your own search. If true, this means half the fix has zero test
   coverage through any live code path — assess whether that's acceptable
   here (the fix is still correct and consistent with the live sites,
   per the implementer's reasoning) or whether it should be flagged more
   strongly.
4. **Check for any other under-arity-dependent test** the implementer
   might have missed — search the whole test suite (not just the three
   files touched) for any closure call at other-than-exact-arity that
   might now behave differently.
5. Run the full reported test suite yourself
   (`clj -X:test :nses '[yin.vm.ast-walker-test yin.vm.engine-test
   yin.vm.semantic-test yin.vm-test]'`) and confirm 84/420/0.

## Boundaries

Read-only for source files; you may run test commands and the temporary
revert-then-restore described in check 2 (restore it exactly before
finishing — leave the tree in the reviewed state, not your probe state).

## Deliverable

Report: pass/fail per the five checks, exact citations for anything
flagged, explicit verdict — safe to proceed to Architect sign-off.
Produce the complete deliverable now.

Completed-GMT: 2026-10-03 18:48:22 GMT
Completed-Local: 2026-10-04 01:48:22 +07

**Verdict: APPROVE `py.rt/limit` in the base prelude and always-on generator admission, with an explicit statement that uninstrumented runs count active generators only.**

1. **The limit belongs with the shared Python runtime context.** Choose **(a)**. `py/gen-switch` owns activation crossings; it should enforce admission before mutating the generator or caller context. The [implemented check](/Users/sto/workspace/datomworld-py-safepoint2/src/cljc/yang/python/antlr/prelude.cljc:317) does this without referring to hook names or changing `yin.vm`.

   A task-local cell holding configurable policy is not hidden global state or a persisted derivation cache. Keep it outside escape-restored context: valid limit changes survive exception unwinding and generator crossings. The hook prelude depends on the base runtime, which is the correct dependency direction.

   Reject rebinding or wrapping `py/gen-switch`: that makes a fundamental crossing check depend on installation order. A replaceable no-op admission function adds another dispatch contract without improving this design. The shared cell and direct comparison are simpler.

2. **Accept the naive-program behavior change as an explicit base-runtime amendment.** My earlier admission ruling required checking a generator even when its resumed body reaches another yield without entering another function. This implementation closes that gap.

   The documented stage laws are that an empty profile leaves the AST unchanged and no-op hooks preserve naive behavior. Neither requires the base runtime to remain forever behaviorally unchanged. Those laws still hold when both runs use the same revised base prelude.

   However, **do not describe naive mode as providing complete CPython recursion accounting**. Its ordinary calls remain uncounted. Add this sentence to §8.5.2:

   > Generator admission is enforced by the base Python runtime in every execution mode; without recursion hooks, effective depth counts nested active generator frames only, while the recursion profile additionally counts ordinary Python function frames.

   Pin naive versus no-op-hook equivalence at the admission boundary, alongside the existing fully instrumented admission test. Failure must preserve the target’s created/suspended state and avoid installing its context; subsequent shallower admission must succeed. Normal caller-side exception handling may, of course, unwind the caller.

3. **The address change is substantive and acceptable.** Moving the cell and adding the check changes the base prelude’s semantic content, allocation behavior, and dependent bundled-program addresses. This requires more than replacing a golden digest: dependent artifacts and derivation expectations must be regenerated consistently, and an old address must continue to identify the old code.

   The same revised input must yield identical canonical bytes and addresses on every host. There is no float-carrier issue introduced by the integer literal `1000`. The small size of the counter/comparison does not exempt it from content addressing—and address movement is not a reason to reject a necessary semantic correction.

4. **One central check covers delegation crossings; no second admission check is needed.** Every delegated start/resume that funnels through `py/gen-switch` gets the same admission check, including forwarded `send`, `throw`, and `close` when they actually enter a suspended generator. Closed-generator handling and throwing into a never-started generator need not consume an activation frame; the current dispatch handles those before admission.

   I confirmed the existing generator entry paths use that function. **C2-S3 delegation code is not present in the inspected worktree**, so this confirms the integration design, not the unseen implementation. After integration, require nested `yield from` boundary tests, including refusal followed by successful shallower resumption, and verify that each active delegating generator contributes exactly one frame.

Read-only inspection; no suites run.
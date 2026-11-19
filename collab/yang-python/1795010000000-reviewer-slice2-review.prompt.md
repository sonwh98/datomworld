Created-GMT: 2026-11-20 1x:xx GMT
Created-Local: 2026-11-20 1x:xx +07 (+0700)
Coding-Agent: agy (independent family from the GLM-family author per team.md's independence rule)
Status: SLICE 2 REVIEW (parallel-lane rule: review N concurrent with slice N+1's implementation; review must complete before slice N+1 merges)

# Review: py-decorators — the decorator slice (READ-ONLY)

Author: a zcode-subagent round (GLM family), branch `py-decorators` (merged to master `86c0375f`, three commits: `a1de98cb` lowering / `e338d99f` goldens+refusal / `69713502` e2e). The worktree /Users/sto/workspace/datomworld-py-decorators holds the branch. Read-only: edit NOTHING.

The author's claims to verify against the code (lower.cljc — `dotted-decorator-expr` ~:1658, `lower-decorator` ~:1674, `lower-decorated` ~:1687, the `decorated` dispatch arm ~:2050):

1. **Application order**: `@a\n@b\ndef g` → `g = a(b(g))` — CPython applies BOTTOM-UP (the closest decorator to the def first), with the decorator EXPRESSIONS evaluated top-first. The goldens pin the let-nesting (top line outermost) and the nested calls. Verify the nesting direction is right in BOTH readings: evaluation of the decorator expressions (top first) and application order (innermost first).
2. **Bare `@d` vs `@d()`**: bare is the name itself (not a zero-arg call) — the author caught their own initial wrong lowering via e2e; verify the fixed form and that `@d()` (zero-arg call) still lowers as a call.
3. **Rebinding location**: the rebind lands in the namespace the def bound (module dict at top level; class scope for methods via setattr/class-ns-get). Verify a method decorator in a class body rebinds INSIDE the class namespace, not the module.
4. **Decorator expression evaluation**: each decorator expression evaluates ONCE, in the def's enclosing scope, at decoration time (not call time) — verify the generated `%dec<id>` bindings do that.
5. **Interaction with the lazy-assert fix**: the same master carries the slice-1 fix (assert messages lowered into the failure arm). Verify the decorator rebind machinery didn't disturb it, and — the evaluation-order lesson from that review — check the decorator machinery for ITS OWN eager/lazy hazards: are decorator expressions evaluated at the right TIME (decoration, not first call)? Is anything hoisted or delayed that CPython evaluates immediately?
6. **Refusal honesty**: `decorated + async_funcdef` still refuses with the qualified diagnostic.
7. **Pins**: do the goldens/e2e actually pin order-of-application and once-evaluation (a side-effecting decorator expression counting its evaluations would be the strong form — is it present, and if not is the gap acceptable)?

## Format

Findings table (P0/P1/P2/P3, file:line, invariant, recommended correction) — include the evaluation-order/timing check explicitly — then one verdict line: ACCEPT / FIX-LIST / REJECT. Read-only.

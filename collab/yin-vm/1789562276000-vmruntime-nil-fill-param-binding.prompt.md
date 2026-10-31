Created-GMT: 2026-09-16 05:37:56 GMT
Created-Local: 2026-09-16 12:37:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 45bb2898-e527-4880-a59d-9a95e77a45d2
# Task: Fix zipmap-based param binding to nil-fill missing params (§7.7.2)
Role: VM Runtime
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-16 12:37:56 +07 | Status: active | Rationale: focused, well-bounded evaluator change; QA/TDD strength fits the test-coverage-sensitive nature of this change

## Context

Read `docs/design/yin.vm.code-as-tuples.md` §7.7.2 ("Name obligations are
resolved per store slice, and completion is conservative") for the exact
rule this implements: **"Under-arity calls leave missing parameters
bound to `nil`, not unbound."** An under-arity call leaves missing
parameter names bound to `nil`; an over-arity call drops extra arguments
beyond the params list length.

Note: an EARLIER version of this same unit also planned to add a
`:global` AST arm to the walker. That plan was reversed — the owner
ruled tonight to retire `:global` entirely (see
`docs/design/yin.vm.code-as-tuples.md` §4.5, "Free variables are queried,
not tagged," and the three commits `5288448`/`c5cea20`/`a450447`). **Do
NOT add any `:global` arm or touch `:variable`'s resolution logic in any
way.** `:variable` keeps its existing env → store → primitives → modules
fallthrough unchanged, confirmed by the Architect review of that reversal.
This unit is scoped to ONLY the nil-fill parameter-binding fix below.

## What's broken today

Four sites do `(merge closure-env (zipmap params args))` (or the
0-or-N-argument variants of the same pattern):

- `src/cljc/yin/vm/ast_walker.cljc:191` (`apply-function`, the shared
  primitive/closure application helper)
- `src/cljc/yin/vm/ast_walker.cljc:511` (the `:eval-operator`
  continuation's empty-operands closure-apply branch, in the hot
  trampolined loop)
- `src/cljc/yin/vm/ast_walker.cljc:553` (the `:eval-operand`
  continuation's full-evaluation closure-apply branch, same hot loop)
- `src/cljc/yin/vm/semantic.cljc:200` (the semantic VM's closure-call
  transition)

`zipmap` stops pairing at the SHORTER of `params`/`args`. When `params`
is longer than `args` (an under-arity call), the extra param names
simply never become keys in the resulting map at all — they're absent,
not `nil`. Combined with `merge closure-env ...`, an absent param name
then falls through to whatever `closure-env` (the closure's own captured
lexical environment) happens to have under that name — which could be an
outer-scope binding, or (since `:variable` still falls through past env)
eventually store/primitives/modules. This is the exact fallthrough
behavior §7.7.2 says must stop for missing parameters specifically.

## Task

At all four sites, replace the bare `zipmap` with something that binds
EVERY name in `params` — filling any name with no corresponding arg with
`nil` — while still dropping extra args beyond `params`'s length (which
already happens naturally with `zipmap`-shaped approaches, since pairing
stops once `params` is exhausted).

Add a small shared helper in `src/cljc/yin/vm/engine.cljc` (both
`ast_walker.cljc` and `semantic.cljc` already depend on that namespace):

```clojure
(defn bind-params
  "Zips params with args, nil-filling any params beyond args' length.
   Extra args beyond params' length are dropped. §7.7.2: an under-arity
   call leaves missing parameter names bound to nil, not absent."
  [params args]
  (into {} (map vector params (concat args (repeat nil)))))
```

(You may find a cleaner formulation — the required property is: every
name in `params` becomes a key in the result; extra `args` are ignored;
a param with no corresponding arg maps to `nil`.)

Use `engine/bind-params` in place of the bare `zipmap` at all four call
sites listed above.

## Contract

This IS a deliberate behavior change (per §7.7.2). Existing tests that
happen to rely on the OLD zipmap-truncation fallthrough behavior (an
under-arity call silently leaving a param unbound, which could then
resolve through `closure-env` or further fallthrough) may now fail,
because the param is explicitly `nil` instead. For each failure,
determine whether it's exercising exactly this under-arity fallthrough
behavior (update the test to match the new, doc-specified contract) or a
genuine regression (a real bug in your change) — investigate each one,
don't assume either way.

Add tests: at least one per changed call site (or a shared test if the
sites share behavior identically) asserting an under-arity closure call
binds the missing parameter to `nil` rather than raising an error or
resolving to something from an enclosing scope, and that an over-arity
call still drops the extra arguments (existing behavior, shouldn't
change — assert it stays correct).

Run the full existing walker/engine/semantic test suites. Find the exact
namespace names by reading each test file's `ns` form (likely something
like `yin.vm.ast-walker-test`, `yin.vm.engine-test`,
`yin.vm.semantic-test` — confirm before running). Also run
`yin.vm-test` since it may exercise the walker or semantic VM
indirectly. Report exact pass/fail counts for every namespace touched,
and for any failure, your determination of whether it's expected (per
the contract change) or a real bug.

## Boundaries

Only `src/cljc/yin/vm/ast_walker.cljc`, `src/cljc/yin/vm/engine.cljc`
(for the new `bind-params` helper), `src/cljc/yin/vm/semantic.cljc`
(only the one `zipmap` site — do not touch its `:var` opcode's
`resolve-var` call, unchanged), and their corresponding test files. Do
NOT touch `:variable`'s or `:global`'s resolution logic anywhere (there
is no `:global` — do not add one). Do not touch `linearize.cljc` or
`code.cljc`.

## Deliverable

Report exact diff, the nil-fill fix at all four sites, full test results
(including any test you had to update and why), and confirmation you
did not touch any variable-resolution logic. Write findings to
`collab/1789562276000-vmruntime-nil-fill-param-binding.claude-sonnet-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.

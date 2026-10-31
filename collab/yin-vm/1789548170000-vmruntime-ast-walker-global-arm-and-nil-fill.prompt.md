Created-GMT: 2026-09-16 02:22:50 GMT
Created-Local: 2026-09-16 09:22:50 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 9bc83c92-f8d3-4913-81b8-5996a0671bbf
# Task: Add :global arm to ast_walker.cljc; fix zipmap-based param binding to nil-fill
Role: VM Runtime
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-16 09:22:50 +07 | Status: active | Rationale: focused, well-bounded evaluator change; QA/TDD strength per team.md fits the test-coverage-sensitive nature of this change

## Context

Read first:
- `docs/design/yin.vm.code-as-tuples.md` §7.7.2 ("Name obligations are
  resolved per store slice, and completion is conservative") — the two
  paragraphs there specify exactly what this unit must implement.
- `src/cljc/yin/vm/v2/ast_walker.cljc` — the CESK evaluator for the map
  AST. Three sites currently do
  `(merge closure-env (zipmap params args-or-evaluated))`:
  - `apply-function` (private helper, ~line 191)
  - the `:eval-operator` continuation's empty-operands closure-apply branch
    (~line 511)
  - the `:eval-operand` continuation's full-evaluation closure-apply branch
    (~line 553)
  (Exact line numbers may have shifted slightly; find them by searching for
  `zipmap params`.)
- `src/cljc/yin/vm/v2/engine.cljc` — `resolve-var` (env -> store ->
  primitives -> module registry fallthrough). This is shared machinery
  used by both `ast_walker.cljc` and `semantic.cljc`.
- `src/cljc/yin/vm/v2/semantic.cljc` — has a fourth
  `(merge (:env f) (zipmap (:params f) args))` site around line 200, in
  the closure-call transition.

## IMPORTANT — scope boundary, read before starting

The design doc's §7.7.2 also says the walker's `:variable` arm should stop
falling through to `resolve-var` and consult only the lexical environment.
**Do NOT make that change.** The orchestrator verified: no frontend
anywhere in this codebase emits `:global` map-AST nodes yet (`grep -rn
":global" src/cljc/yang/` returns nothing), and the existing test suites
(`test/yin/vm/ast_walker_test.cljc`, `test/yin/vm/v2/ast_walker_test.cljc`)
construct 39 `:type :variable` nodes total, many referencing primitives
(like `+`) that today only resolve through the fallthrough. Removing it
now, before any frontend does scope analysis to classify free names as
`:global`, would break nearly all existing evaluator test coverage. That
frontend work is a separate, not-yet-started unit. This unit is scoped to
the two changes below ONLY — both are purely additive or narrowly
corrective, and do not require the fallthrough to be removed.

## Task

### 1. Add a `:global` arm to `ast_walker.cljc`

A `:global` map-AST node (`{:type :global :name sym}`) has no evaluator
arm today (it's a new tag added by the code-as-tuples design — see
`docs/design/yin.vm.code-as-tuples.md` §2's tag table). Add a `:global`
case to `cesk-transition`'s `case type` dispatch (alongside `:literal`,
`:variable`, `:lambda`, etc.) that resolves the name via
`engine/resolve-var` exactly as `:variable`'s CURRENT arm does (env ->
store -> primitives -> modules — yes, this duplicates `:variable`'s
current behavior for now; that's expected, since nothing constructs
`:global` nodes yet and the eventual state is `:variable` narrows to env-
only while `:global` keeps this full resolution). This is purely
additive — it cannot break anything, since nothing produces `:global`
nodes today.

Also check the CESK machine's OTHER continuation paths (the `:eval-
operator`/`:eval-operand` recur-based hot loop around lines 500-560) for
any place that dispatches on node `:type` and would need a matching
`:global` case for the trampolined/tail-call path specifically — the
`cesk-transition` function may be the cold/simple path with a hot-loop
duplicate elsewhere in the same file (check `test/yin/vm/v2/ast_walker_test.cljc`
and the file's own structure to confirm whether there's one evaluator
loop or two, and cover whichever dispatch points actually exist).

### 2. Fix zipmap-based param binding to nil-fill missing params (§7.7.2)

At all FOUR sites (3 in `ast_walker.cljc`, 1 in `semantic.cljc`), replace
the plain `(zipmap params args)` with something that binds every name in
`params` — filling missing arguments with `nil` rather than leaving them
absent from the resulting map (which is what `zipmap` does when `args` is
shorter than `params`: those param names simply never appear as keys,
which today, combined with `merge`, made them fall through to whatever
`closure-env` had — the very fallthrough behavior §7.7.2 says must stop).
Extra arguments beyond `params`'s length are still dropped (this already
happens naturally, since `zipmap`/your replacement will stop pairing once
`params` is exhausted).

A reasonable shared helper: add a small function (in `engine.cljc`, since
both `ast_walker.cljc` and `semantic.cljc` already depend on that
namespace) — something like:

```clojure
(defn bind-params
  "Zips params with args, nil-filling any params beyond args' length.
   Extra args beyond params' length are dropped. Per §7.7.2: an
   under-arity call leaves missing parameter names bound to nil, not
   absent."
  [params args]
  (into {} (map vector params (concat args (repeat nil)))))
```

Use `engine/bind-params` in place of the bare `zipmap` at all four sites.
(You may find a cleaner formulation — the key correctness property is:
every name in `params` becomes a key in the result, extra `args` are
ignored, and a param with no corresponding arg maps to `nil`.)

## Contract

- This IS a deliberate behavior change (the design doc calls it out as
  such). Existing tests that happen to rely on the OLD zipmap-truncation
  behavior (an under-arity call silently leaving a param name unbound,
  which could then resolve through `closure-env` or, via `:variable`'s
  still-present fallthrough, through store/primitives/modules) may now
  fail, because the param is explicitly `nil` instead. If a test fails
  after this change, determine whether it's exercising exactly this
  under-arity fallthrough behavior (in which case update the test to
  match the new, doc-specified contract) or a genuine regression
  (in which case it's a real bug in your change) — don't assume either
  way, investigate each failure.
- Add tests: at least one for the new `:global` arm (construct a
  `{:type :global :name 'some-primitive}` node, assert it resolves the
  same way `:variable` currently does), and at least one for nil-filling
  (an under-arity closure call, assert the missing param is bound to
  `nil` rather than raising an error or resolving to something from an
  enclosing scope).
- Run the full existing walker/engine test suites
  (`clojure -M:test -n yin.vm.ast-walker-test -n yin.vm.v2.ast-walker-test
  -n yin.vm.v2.engine-test`, adjust namespace names if these don't
  match exactly — find them by reading the test file `ns` forms) plus
  `yin.vm.v2-test` (since it may exercise the walker indirectly). Report
  exact pass/fail counts, and for any failure, your determination of
  whether it's expected (per the contract change above) or a real bug.

## Boundaries

Only `src/cljc/yin/vm/v2/ast_walker.cljc`, `src/cljc/yin/vm/v2/engine.cljc`
(for the new `bind-params` helper), `src/cljc/yin/vm/v2/semantic.cljc`
(only the one zipmap site — do not touch its `:var` opcode's
`resolve-var` call), and their corresponding test files. Do NOT touch
`:variable`'s resolution arm in either `ast_walker.cljc` or
`semantic.cljc` — see the scope boundary above. Do not touch
`linearize.cljc` or `code.cljc`.

## Deliverable

Report exact diff, the new `:global` arm's behavior, the nil-fill fix at
all four sites, full test results (including any test you had to update
and why), and confirmation you did not touch `:variable`'s resolution
logic. Write findings to
`collab/1789548170000-vmruntime-ast-walker-global-arm-and-nil-fill.claude-sonnet-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.

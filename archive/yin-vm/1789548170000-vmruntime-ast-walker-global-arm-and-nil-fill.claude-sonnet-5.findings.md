Created-GMT: 2026-09-16 02:22:50 GMT
Created-Local: 2026-09-16 09:22:50 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 9bc83c92-f8d3-4913-81b8-5996a0671bbf
# Findings: Add :global arm to ast_walker.cljc; fix zipmap-based param binding to nil-fill

## Summary

Both changes are implemented at all sites named in the prompt. Nothing
staged or committed, per instructions.

## 1. `:global` arm

`ast_walker.cljc` has exactly two evaluator dispatch points, confirmed by
reading the whole file:

- `cesk-transition` (the cold/simple path, `case type` at line ~359) — used
  directly by `vm-step`, and therefore by every entry point that reaches
  the VM: `vm/step`, `engine/run-loop` (which drives `vm/run`), and
  `vm/eval`.
- `ast-walker-run-active-continuation` (a hot-loop variant, `case type` at
  line ~600) — inlines `:literal`, `:variable`, `:lambda`, `:application`,
  `:if`, and the `:eval-operator`/`:eval-operand`/`:eval-test`
  continuations directly in host locals.

**Important correction to the prompt's assumption:** I verified — by
grepping the whole repo, not just this file — that
`ast-walker-run-active-continuation` has **no call sites anywhere**, in
`src/` or `test/`. `vm/run` goes through `ffi/maybe-run` →
`ast-walker-run-scheduler` → `engine/run-loop`, which drives `vm-step` =
`(cesk-transition vm nil)` on every step — never the hot-loop function.
I confirmed this empirically too: I temporarily reverted the nil-fill fix
at the hot loop's `:eval-operand` closure-apply site only (line ~556,
leaving the other three sites fixed) and reran the new
`under-arity-call-nil-fills-missing-params-test` — it still passed,
because that code path is simply never reached by `vm/run`. I then
reapplied the fix and reran the full suite to confirm no accidental
corruption (1464 tests, 166022 assertions, 0 failures/errors, unchanged).
So in the current v2 file there is really only **one** live evaluator
loop; the second `case type` is present but dead. (Its v1 counterpart in
`yin/vm/ast_walker.cljc:475` is a different, live function — this
dead-code state is specific to the v2 port.)

Added a `:global` arm to **both** dispatch points anyway: to
`cesk-transition` because it's live and this is the whole point of the
task, and to the dead hot loop too, for two reasons — (1) the prompt
asked me to cover whichever dispatch points actually exist, and this one
exists as source even though nothing calls it, and (2) if it's ever wired
up later (its docstring and the sibling v1 file suggest that's the
intent), it should already agree with `cesk-transition` rather than
silently regressing to the old fallthrough/truncation behavior. Both new
`:global` arms resolve via
`engine/resolve-var env store primitives modules (:name node)` exactly as
`:variable`'s current arm does — byte-for-byte the same body, per the
prompt's instruction that this duplication is expected for now.

I did not remove or otherwise touch `ast-walker-run-active-continuation`
beyond the nil-fill and `:global` additions — the prompt's boundaries
don't authorize deleting dead code, and doing so wasn't asked for.

## 2. nil-fill fix (§7.7.2)

Added `engine/bind-params`:

```clojure
(defn bind-params
  [params args]
  (into {} (map vector params (concat args (repeat nil)))))
```

Replaced `(zipmap params args)` with `(engine/bind-params params args)` at
all four sites:

- `ast_walker.cljc` `apply-function` (~line 191, the cold path's
  closure-apply branch, shared by `cesk-transition`'s `:eval-operator`
  empty-operands and `:eval-operand` full-evaluation continuations).
- `ast_walker.cljc` hot loop, `:eval-operator`'s empty-operands
  closure-apply branch (~line 511, zero-arg calls).
- `ast_walker.cljc` hot loop, `:eval-operand`'s full-evaluation
  closure-apply branch (~line 553, the general case).
- `semantic.cljc` `apply-call`'s closure branch (~line 200).

`semantic.cljc` already `(:require [yin.vm.v2.engine :as engine])`, so no
require change was needed there.

## Scope boundary respected

Did **not** touch `:variable`'s resolution arm anywhere — neither in
`ast_walker.cljc` (both dispatch points) nor in `semantic.cljc`'s `:var`
opcode (~line 256, confirmed unchanged by re-reading it). Both still call
`engine/resolve-var` with the full env → store → primitives → modules
fallthrough, exactly as before. `:global`'s new arms are additive
duplicates of that same call, not a replacement.

Did not touch `linearize.cljc` or `code.cljc`.

One thing surfaced during testing that was **not** in the four zipmap
sites and was left alone, per scope: `yin/vm/v2.cljc`'s `ast->datoms`
map-AST↔datoms codec (`case type` in `ast->datoms-with-root`) has no
`:global` entry — it throws `"Unknown AST node type"` for a `:global` map
node. This is the same codec the design doc's own tag table (§2) implies
still needs a `:global` encode/decode case, but adding it is a frontend/
codec concern outside this unit's boundary list (only
`ast_walker.cljc`/`engine.cljc`/`semantic.cljc` were in scope), and no
frontend emits `:global` yet either way. I mention it only so the gap is
visible for whichever unit does the codec work — it did not block this
task, since the CESK evaluator (the thing actually in scope) can be
exercised without going through that codec (see Testing notes below).

## Tests added

`test/yin/vm/v2/ast_walker_test.cljc`:

- `global-resolves-like-variable-test` — builds
  `{:type :global :name '+}` inside an `:application` and asserts it
  resolves and applies exactly like `:variable` does today (`10 + 20 = 30`).
- `under-arity-call-nil-fills-missing-params-test` — a 2-param closure
  called with 1 argument, whose body reads the missing param; asserts the
  result is `nil` rather than an error or a value leaking in from an
  enclosing scope.

Both use a new local helper, `run-ast`, which runs an AST directly through
`vm/run` by `assoc`ing it straight onto `:control` on a fresh VM
(`(vm/run (assoc (create-vm) :control ast :halted? false))`), instead of
going through `compile-and-run`/`vm/eval`. Both of those call
`vm/ast->datoms` internally, and that codec doesn't know `:global` (see
the gap noted above) — routing through it would make the `:global` test
fail for a reason unrelated to the evaluator change under test. The
under-arity test doesn't strictly need this detour (`:variable`/`:lambda`/
`:application` are all supported by the codec), but I used the same helper
for both new tests for consistency.

Both tests exercise `vm/run`'s live path (`cesk-transition`/
`apply-function`), not the dead `ast-walker-run-active-continuation` loop
described above — there is currently no way to reach that function from a
test that only uses this namespace's public surface, since nothing calls
it. The hot-loop's own nil-fill and `:global` arms are covered by reading
and by the direct-unit `bind-params` test only, not by an
end-to-end run.

`test/yin/vm/v2/engine_test.cljc`:

- `bind-params-nil-fills-missing-args-test` — direct unit coverage of
  `engine/bind-params`: under-arity nil-fills, over-arity drops extras,
  equal-arity matches old `zipmap` behavior, and the empty/empty case.

## Test results

Ran the exact suites requested plus the semantic-side suites (since
`semantic.cljc` was also touched) and the full project suite.

```
clojure -M:test -n yin.vm.ast-walker-test -n yin.vm.v2.ast-walker-test \
  -n yin.vm.v2.engine-test -n yin.vm.v2-test
=> Ran 92 tests containing 406 assertions.
   0 failures, 0 errors.

clojure -M:test -n yin.vm.v2.semantic-test -n yin.vm.v2.semantic-engine-test \
  -n yin.vm.v2.semantic-ffi-test -n yin.vm.v2.semantic-stream-observer-test
=> Ran 44 tests containing 213 assertions.
   0 failures, 0 errors.

clojure -M:test   (full project suite)
=> Ran 1464 tests containing 166022 assertions.
   0 failures, 0 errors.
```

No existing test failed, and none needed to be updated for the old
zipmap-truncation/fallthrough behavior: nothing in the existing corpus
constructed an under-arity closure call that depended on the missing
param falling through to an enclosing binding, a store key, a primitive,
or a module export. The behavior change is real (verified by the new
`under-arity-call-nil-fills-missing-params-test`, which would have thrown
`"Unable to resolve symbol: y in this context"` under the old zipmap
behavior, since the empty top-level env has no `y` to fall through to —
I confirmed this by temporarily reverting the fix and re-running the new
test, which then failed with that error, before re-applying the fix), but
it wasn't exercised anywhere else in the suite.

## Files touched

- `src/cljc/yin/vm/v2/ast_walker.cljc`
- `src/cljc/yin/vm/v2/engine.cljc`
- `src/cljc/yin/vm/v2/semantic.cljc`
- `test/yin/vm/v2/ast_walker_test.cljc`
- `test/yin/vm/v2/engine_test.cljc`

Nothing staged or committed.

Created-GMT: 2026-09-22 10:30:32 GMT
Created-Local: 2026-09-22 17:30:32 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: debruijn-b2-polish — close 4 findings from the occurrence-identity fix's independent review
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 17:30:32 +07 | Status: active | Rationale: fresh session; small, well-defined fixes closing an independent review's findings before commit

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2). Do NOT stage, commit, merge or push.

## Context

deepseek-v4-pro's independent review of the occurrence-identity fix in
`src/cljc/yin/vm/debruijn_resolve.cljc` found no P1s (the fix itself is
correct) and four smaller findings. Close all four:

## 1. P2 — the inverse-law test omits the shared-entity fixtures

`test/yin/vm/debruijn_resolve_test.cljc`'s `unresolve-inverts-resolve-
over-the-corpus` test iterates the `corpus` map, which does not include
`shared-variable-under-two-contexts` or `shared-lambda-under-two-
contexts` (both already defined in this file). The design's own
completion criterion (`docs/design/yin.vm.debruijn.stack.md` section 3.1)
requires `unresolve(resolve x, side-table) = x` over the corpus AND these
fixtures -- the exact case this whole fix exists to handle is currently
untested for the inverse law (reviewed and confirmed correct by hand, but
unasserted by any test). Add both fixtures to whatever this test iterates
over (extend the corpus map itself, or add them to a second `doseq` in
the same test -- your choice, whichever is the smaller diff), so a future
regression in the `:seen`-set merge or `resolve-name` would be caught.

## 2. P3 — `validate-resolved`'s scope walk has no cycle guard

`walk-scope` (around line 333-360 currently) recurses unconditionally
through `:yin/body` and child slots with no `active`/`seen` set, unlike
`visit!` in `resolve` itself (which already has correct cycle detection).
A hand-built cyclic resolved-tuple set (e.g. an `:application` whose
`:yin/operator` points to another `:application` that points back to the
first) causes unbounded recursion / a `StackOverflowError` instead of a
clean `{:rule ...}` defect map. Since `validate-resolved` is documented as
the check every lowerer runs on POSSIBLY HAND-BUILT input (not just
`resolve`'s own output, which can never be cyclic), it needs the same
cycle guard `visit!` already has. Add it, returning a qualified defect
(match the existing defect map shape this function already returns for
other checks, e.g. `{:rule :cycle, :entity e, ...}`) instead of
overflowing the stack. Add a test: the exact hand-built cyclic fixture
deepseek's review used --
`[[1 :yin/type :application] [1 :yin/root true] [1 :yin/operator 2]
[1 :yin/operands []] [2 :yin/type :application] [2 :yin/operator 1]
[2 :yin/operands []]]` with a matching source map -- asserting
`validate-resolved` returns a defect map (not an exception, not nil).

## 3. P3 — missing `:yin/operands` on a hand-built node passes validation

`collect-refs` and `walk-scope`'s handling of `:children`-kind slots reads
`(get-attr e attr)` directly; when the attribute is absent this is `nil`,
which `some`/`mapcat` silently skip -- so a hand-built `:application` with
no `:yin/operands` datom at all passes validation, asymmetric with
`:child`-kind slots (a missing `:yin/operator`/`:yin/body` DOES get caught
as a dangling `nil` ref). Add a check: for every `:children`-kind slot,
the attribute must be present (even if an empty vector is valid content --
`nil`/absent is not the same as `[]` and must be refused with a qualified
diagnostic, e.g. `{:rule :missing-operands, :entity e, :attr attr}` or
whatever name fits this validator's existing diagnostic vocabulary). Add a
test: a hand-built `:application` entity with `:yin/operator` but no
`:yin/operands` datom at all, asserting `validate-resolved` refuses it.

## 4. P3 — no fixture exercises `:yin/macro?` pass-through

`resolve` and `unresolve` both correctly thread `:yin/macro?` (added as a
deviation in the prior round, confirmed necessary and correctly
implemented by deepseek's review), but no corpus fixture has
`:macro? true`, so the round trip is asserted nowhere. Add one small
fixture: a `:lambda` node with `:macro? true` (see how other fixtures in
this file set flags like `:tail?` via the `tail` helper, for the pattern
to follow -- you may need a small local helper or an inline `assoc`), run
it through the same inverse-law and every-corpus-program-resolves tests
this file already has.

## Completion criteria

All four findings closed with the specific test named above for each. No
other behavior changes -- do not touch anything deepseek's review found
sound (the occurrence memo, cycle detection in `visit!`, the
`unresolve` merge-by-source step, host-value-rejection scope). Full JVM
suite must stay green: `clojure -M:test` should report the same 1796
tests plus your new tests, 0 failures.

## Never

Same as before: do not implement B3 further, B4, B5, or B6. Do not touch
`yin.vm.linearize`, `yin.vm.code`, `yin.vm.completion`, `yin.vm.ast_walker`,
`yin.vm.debruijn` (beyond calling `resolve-name`), `yin.vm.debruijn-code`,
or `yin.vm.debruijn.stack` -- only READ from them. Keep files pure ASCII,
no em dashes, cljstyle-style Clojure.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. This worktree is
already `mise trust`ed. Focused JVM run:
`clojure -M:test -n yin.vm.debruijn-resolve-test -n yin.vm.debruijn-linearize-test`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: how each of the 4 findings was closed, naming the test for each;
exact test counts before/after; what you ran; every deviation. Facts only.

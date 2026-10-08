Created-GMT: 2026-09-16 14:27:35 GMT
Created-Local: 2026-09-16 21:27:35 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: Review U5 — port tests off the v1 VM, pin parity values

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-16 21:27:35 +07 | Status: active | Rationale: cross-family review of a GLM-family implementer

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md` — read "### D4 —
parity against a deleted v1 becomes pinned values" and "### U5 — test
ports off the v1 VM" in full first.

Files changed (all uncommitted): `test/README.md`,
`test/yang/{clojure,python,php}_test.clj`, `test/yin/module_test.cljc`,
`test/yin/vm/parity_test.cljc` (the main D4 rewrite — corpus rows
`[name ast]` → `[name ast expected]`, `expected` captured by actually
running v1 once, before its deletion; the four deftests now compare v2
against the pinned column instead of live v1), `test/yin/vm_test.cljc`
(+1 deftest porting `ast_conversion_test.cljc`'s uncovered cases).

**Note**: the working tree also currently contains unrelated, in-progress
work from a concurrent unit (U2 — a new `:primitives` option in
`src/cljc/yin/repl/core.cljc` and its own test in
`test/yin/repl_core_test.cljc`, plus Flutter-related `.cljd` files).
None of that is part of this review — ignore any diff you see in those
files; this review is scoped exactly to the seven files listed above.

Verified independently by the orchestrator: `clj -M:kondo --lint` on all
seven files — only pre-existing warnings (confirmed via `git show
HEAD:...` comparison; two pre-existing warnings in `module_test.cljc`,
and U5 actually removed a third pre-existing one by dropping the unused
`yin.vm` require). `clj -M:test` → 1478 tests, 0 failures (higher than
U5's own reported 1474 due to the concurrent U2 work also present in the
tree — not a discrepancy in U5's own correctness). `bb test:cljs` → 1381
tests, 0 failures, with `yin.vm.parity-test`/`yin.vm-test`/
`yin.module-test` all confirmed present — the pinned parity values hold
on ClojureScript too. `bb test:cljd` deliberately not yet run — a
different concurrent unit (U2) currently owns the CLJD lane; will be run
once free, before this unit's own sign-off/commit.

## Task

1. **Verify the D4 pinning mechanism is correct**, not just present. Spot-
   check several corpus rows in `parity_test.cljc` against the actual AST
   each represents — do the pinned `expected` values genuinely match what
   evaluating that AST should produce (e.g. `["addition" (binop '+ 10 20)
   30]`, `["closure value" ... {:type :closure, :params ['x], :body
   {:type :variable, :name 'x}}]`)? Is there any row where the pinned
   value looks wrong on inspection, independent of trusting the capture
   process?
2. **Verify the stated deviation is sound.** The implementer reports that
   `clojure_test.clj`'s three `test-stream-end-to-end` cases needed
   `:modules (module/register-stream-module (module/default-registry))`
   merged into the base VM opts to pass — v1 registered the `stream`
   module globally at load time, v2's composition requires it explicitly.
   Read the actual diff and confirm this is a real, necessary fix (not
   papering over an unrelated bug) and matches the wiring pattern it
   claims to mirror (`yin.repl.core:273` — read that line).
3. **Verify assertion-count discipline.** U5's own criteria require every
   deftest's assertion count to stay unchanged except where a case was
   deliberately ported into `v2_test.cljc`. Confirm the new
   `codec-round-trips-the-v1-corpus-node-types` deftest in `v2_test.cljc`
   genuinely covers what `ast_conversion_test.cljc`'s two cases needed,
   and that nothing else's assertion count silently drifted.
4. **`test/README.md`'s template update** — confirm it now names
   `yin.vm` per the plan's stated criteria (lines 137,143 in the
   original, may have shifted).
5. Confirm v1 (`yin.vm`, `yin.vm.ast-walker`, `dao.await`) is genuinely
   untouched by this diff — this unit only adds/changes tests and the
   parity corpus, deletes nothing.

Do not edit any file.

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect sign-off, or not.

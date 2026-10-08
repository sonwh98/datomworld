Created-GMT: 2026-09-16 04:25:00 GMT
Created-Local: 2026-09-16 11:25:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: deepseek
Session-ID: 2ea585fd-ac5c-445f-a2c7-9a1a1c2788b2
# Task: Review :global removal from ast->semantic-bytecode
Role: Routine Review
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-16 11:25:00 +07 | Status: active | Rationale: cross-family from claude-sonnet-5 (implementer)

## Context

The owner ruled tonight to retire the `:global` AST tag from
`docs/design/yin.vm.code-as-tuples.md` (see the doc's new §4.5, "Free
variables are queried, not tagged," for the full rationale — read it for
context). `src/cljc/yin/vm.cljc`'s already-committed
`ast->semantic-bytecode`/`semantic-bytecode->ast` (commit `84f8eef`)
included `:global` as one of 18 grammar tags. This change removes it,
reducing the grammar to 17 tags.

`git diff -- src/cljc/yin/vm.cljc test/yin/vm_test.cljc` shows the
change. Summary: the `:global [[:name :sym]]` grammar entry is deleted;
`test/yin/vm_test.cljc`'s `global` test-helper is deleted and every
call site that used it now uses the existing `local` helper (constructs
`:variable` nodes) instead — no new test infrastructure, no deleted
assertions, just tag/name substitutions.

## Task

1. Confirm the grammar removal is clean — no dangling reference to
   `:global` anywhere in `v2.cljc` (check `slot-value` and the
   reconstruction `child`/`build` functions too, not just the grammar
   map, in case either had a `:global`-specific case that needs removing
   — the findings claim there wasn't one, verify).
2. Confirm every corpus/test change is a faithful substitution — that
   `local` and the former `global` helper produce structurally
   equivalent nodes except for the `:type` key (i.e., this isn't
   accidentally changing what's being tested, just renaming the tag).
3. Confirm `semantic-bytecode-corpus-covers-every-tag` (or whichever test
   asserts full grammar coverage) still meaningfully tests all 17
   remaining tags — not just that it happens to pass, but that removing
   the `global`-tagged corpus entries didn't accidentally also remove
   coverage of some OTHER tag that only appeared alongside a `:global`
   node in a shared fixture.
4. Run `clojure -M:test -n yin.vm-test` yourself and confirm the
   reported 23 tests / 172 assertions / 0 failures.

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report: pass/fail per the four checks, exact citations for anything
flagged, explicit verdict — safe to commit as-is. Produce the complete
deliverable now.

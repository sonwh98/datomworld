Created-GMT: 2026-09-16 03:05:13 GMT
Created-Local: 2026-09-16 10:05:13 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 08f4ae37-6125-471f-af73-91e66c549bd7
# Task: Retire :global from ast->semantic-bytecode (design ruling reversed)
Role: VM Runtime
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-16 10:05:13 +07 | Status: active | Rationale: small, well-bounded reversal of an already-committed feature

## Context

The owner ruled tonight to retire the `:global` AST tag entirely. Read
`docs/design/yin.vm.code-as-tuples.md` §4.5 ("Free variables are queried,
not tagged") for the full rationale — in short: the free/bound variable
distinction `:global` was meant to encode is derivable via a Datalog
query over `$ast` (which `:variable`-tagged rows in the tree), so it
never needed to be a separate persisted AST tag. `:variable` keeps its
existing evaluator behavior unchanged (env → store → primitives →
modules fallthrough) — nothing about resolution semantics changes, only
that `:global` is no longer a recognized tag.

`src/cljc/yin/vm.cljc`'s `semantic-bytecode-grammar` currently has 18
entries including `:global [[:name :sym]]` (around line 575). It must
have 17 entries after this change — `:global` is simply removed, so a map
AST containing a `{:type :global ...}` node throws "Unknown AST node
type" via `ast->semantic-bytecode`'s existing unknown-tag handling
(exactly the behavior any other unrecognized tag already gets — no new
error path needed).

## Task

1. Remove the `:global` entry from `semantic-bytecode-grammar` in
   `src/cljc/yin/vm.cljc`.
2. In `test/yin/vm_test.cljc`: remove the `global` test-helper function
   (around line 173, `{:type :global, :name n}`) and every use of it in
   the round-trip corpus (`semantic-bytecode-corpus`) and any other test
   referencing `:global` (grep for `:global` in the file — a few
   assertion sites reference the removed tag directly, e.g. checking a
   projected row equals `[:global +]`, and a comment counting `:if,
   :application, :global, one :literal`). Replace corpus entries that used
   `(global 'name)` with `(local 'name)` or the file's existing
   `:variable`-constructing helper (check what helpers already exist in
   the file — there should already be one for plain `:variable` nodes,
   since `:global` was likely a thin wrapper varying only the `:type`
   key). The corpus must still cover all 17 remaining tags (was 18 before
   this removal) — don't just delete coverage, adjust it.
3. Update `semantic-bytecode-corpus-covers-every-tag`'s dictionary check
   (or whatever asserts the corpus matches `semantic-bytecode-grammar`'s
   keys) so it still passes against the reduced 17-tag grammar.

## Contract

Run `clojure -M:test -n yin.vm-test`, confirm 0 failures. The test
count/assertion count will likely shrink slightly (removing dedicated
`:global` coverage) — report the exact before/after numbers and confirm
nothing else broke.

## Boundaries

Only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`. Do not
touch `docs/design/yin.vm.code-as-tuples.md` (already corrected by the
orchestrator) or any other file. Do not attempt to add anything for
`:variable`'s free/bound distinction — that's handled entirely by query,
not by any code change here.

## Deliverable

Report exact diff and test results. Write findings to
`collab/1789552313000-vmruntime-retire-global-tag.claude-sonnet-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.

Completed-GMT: 2026-10-10 09:18:45 GMT
Completed-Local: 2026-10-10 16:18:45 Asia/Bangkok

# Report: semantic-register-vm phase 3, round 2 (rule 13 compares the whole vector)

Implementer: claude-opus-5-5, worktree `/Users/sto/workspace/datomworld-srvm-p3`,
branch `srvm-phase3` (from master 018d55fe).

Clock note: this machine's clock reads 09:18 GMT, which is earlier than the
round-2 prompt's `Created-GMT` of 09:40. The header records the local clock
as read (`date -u`, `TZ=Asia/Bangkok date`).

## Status

Round 2 is done, and green on JVM, Node and Dart. Per the round-2 brief,
nothing was staged, committed or pushed: the orchestrator commits. Every file
is untracked in the worktree. `collab/` is not to be staged.

## Round-2 change: the amended §3.4 item 13

- `code.cljc`: rule 13 is now `canonical-projection` and replaces
  `canonical-registers` in `rules`. It runs two checks, in first-defect-wins
  order:
  1. Register ids, body by body at their own pcs, using the round-1
     re-projection. A difference is `{:rule :noncanonical-registers :pc p}`
     at the least differing pc.
  2. With ids equal, the new `reprojected-segment` re-emits the whole
     segment from the parse trees under §3.3. That is: the main sequence
     first; bodies out of line in FIFO queue order (each `:closure` enqueues
     the parsed body at its body-pc); labels placed by the walk and resolved
     last; registers minted pre-order; literal operands copied from the
     tuples, which items 1-5 already proved saturated. Any remaining
     difference is `{:rule :noncanonical-layout :pc p}` at the least
     differing pc, comparing up to the longer of the two lengths.

  The `well-formed?` docstring lists the new rule name.
- `code_test.cljc`: two new `:noncanonical-layout` refusal rows.
  - `(f (fn [x] x) (fn [y] y))` with its two bodies swapped and the body-pcs
    adjusted: pc 1.
  - `:body-queue-order` with the body discovered inside a body (q) moved
    ahead of the r-body queued before it: pc 1.

  The rule-name coverage set now includes `:noncanonical-layout`. Every
  golden is still accepted.
- **Label-placement variant: not admitted as an ids-equal layout defect.**
  The parse delimits arms by the labels (`Lalt − 1` must be the arm's
  `:jump`, and the alternate is `[Lalt, Lend)`). A moved label therefore
  re-parses as a different tree, never as the same tree laid out differently.
  Re-emitting a parsed body reproduces its own labels by construction, so
  within a body, layout can differ only in body-pc values, i.e. body order.
  The test `moved-labels-re-parse-as-another-tree` documents this:
  `(stream-cursor (if c a b))` with `Lend` moved past the cursor re-parses as
  `(if c a (stream-cursor b))` and is refused earlier, as
  `{:rule :definite-assignment :pc 6}`. The canonical placement of the same
  vector is accepted.

## Changed files (all new; no existing file touched)

- `src/cljc/yin/vm/semantic_register/code.cljc` (round 2: rule 13)
- `src/cljc/yin/vm/semantic_register/linearize.cljc` (unchanged in round 2)
- `src/cljc/yin/vm/semantic_register/analysis.cljc` (unchanged in round 2)
- `test/yin/vm/semantic_register/corpus.cljc` (unchanged in round 2)
- `test/yin/vm/semantic_register/code_test.cljc` (round 2: new rows and test)
- `test/yin/vm/semantic_register/linearize_test.cljc`,
  `load_paths_test.cljc`, `analysis_test.cljc` (unchanged in round 2)

## Test and check outcomes (round 2, foreground)

| Lane | Command | Result |
|---|---|---|
| JVM (G0/G1) | `clojure -M:test -n yin.vm.semantic-register.{linearize,code,load-paths,analysis}-test` | Ran 33 tests containing 1133 assertions. 0 failures, 0 errors. |
| Node (G1) | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(…the four…)$"}'` | "Testing" printed for all four namespaces. Ran 33 tests containing 1133 assertions. 0 failures, 0 errors. Build 0 warnings. |
| Dart (G1) | `bb src/dev/cljd_agg.clj --only <the four>` | Compilation succeeded; 4 test files in 4 shards; `+33: All tests passed!` (flutter reports test counts only, not assertions). |

- `clj -M:kondo --lint src/cljc/yin/vm/semantic_register test/yin/vm/semantic_register`:
  errors 0, warnings 0.
- `mise exec -- cljstyle check` on both directories: clean (no output).
- Trailing whitespace: `grep -rnE '[[:space:]]+$'` over the new files finds
  none. `git diff --check` does not cover untracked files.

## Design ambiguities (round 1; all seven accepted as implemented)

1. **Items 10 and 12 versus rule order.** Their kind clauses (call and
   ffi-call argument registers, `tail?` boolean, `:resume` operands) are kept
   at items 10 and 12. Item 2's `:operand-kind` checks those operands only
   for vector-ness or not at all. Items 7-9 ignore non-register operands. The
   layout-syntax clauses of 10 and 12 are discharged by item 6's parse.
2. **Rule names.** `:body-partition`, `:register-ids`,
   `:definite-assignment`, `:exclusive-definitions`, `:call-operands`,
   `:resume-operands`. Items 1-5 reuse the §7.5 names. Item 13's two names
   (`:noncanonical-registers`, `:noncanonical-layout`) come from the amended
   design.
3. **The minted id of an expression.** An expression with a destination slot
   mints its `rd`. A conditional or `resume` mints the register its parent
   names it by. If that operand is ill-kinded, a conditional falls back to
   its first leaf's `rd`. Arms mint nothing.
4. **`def` of a tail `:call`** is `{rd}` (syntactic). This cannot affect `L`,
   because a tail call has no successors.
5. **Liveness** iterates reverse-pc sweeps to a fixed point and does not rely
   on the "one pass per nesting level" claim.
6. **Load paths.** `code/vector->datoms` was scrapped by the owner ruling of
   2026-09-18, so "projection load" means the AST through the datom lane and
   through the row lane, loaded by the same validator. §7.2 part 3 is not
   exercised.
7. **The image** returned by `load-vector` is `{:vector :address :bodies}`.
   The executable decoding is phase 4's.

## Concerns

- **Resolved:** "body order is not validated" (round 1). The Architect
  ruling amended §3.4 item 13 to compare the entire vector, and round 2
  implements it with `:noncanonical-layout`.
- No unresolved concern remains.

## Incomplete work

None within scope. Committing is the orchestrator's, per the round-2 brief.

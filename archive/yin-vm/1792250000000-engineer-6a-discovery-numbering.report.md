# Engineer report: phase 6a cursor-cell numbering by discovery order

Created-Local: 2026-10-12 +07. Worktree `datomworld-srvm-p6a`, branch
`srvm-phase6a`, HEAD `d77da9f0`. **Nothing committed.** Implements
`collab/numbering-ref/note.md` §§1–6, with the owner answers of §7
(the occurrence count stays in the key; the `:work` default is 4194304).

## Step 0: merge NOT done

In this `-p` session, `git merge master` (every form) and `git ls-tree`
needed approval that couldn't be granted, so the branch is **unmerged**.
Impact:

- Code: master changes nothing under `src/cljc/yin/vm/ucf/` or
  `test/yin/vm/ucf/rows_test.cljc` (I checked with `git diff --stat
  HEAD...master`), so the code and test changes should merge cleanly.
- Docs: master's `yin.vm.ucf-transport-tuples.md` (167 lines) and
  `yin.vm.semantic-register-vm.continuation.md` (42 lines) carry the
  cursor-cell rename. The branch's copies are the older text. So I applied
  A1–A9 to **master's** text and left the branch's doc files untouched (see
  "Docs" below).
- Baseline before any change, unmerged branch: `rows-test` plus
  `frame-test`, 31 tests, 554 assertions, 0 failures.

**Orchestrator action:** merge master, then `git apply
collab/1792250000000-docs-amendments-against-master.patch`, then `cp
collab/numbering-ref/note.md
docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`. A1, A2,
A4 and A7 link to that note by name, and it isn't in `docs/design` on
master yet. A `/dev/null` diff for a new-file hunk was blocked. The note's
Status line still says "not yet implemented"; flip it at landing.

## Files changed

| File | Added | Deleted |
|---|---:|---:|
| `src/cljc/yin/vm/ucf/rows.cljc` | 536 | 178 |
| `test/yin/vm/ucf/rows_test.cljc` | 615 | 32 |

Docs, outside the tree (apply after the merge):
`collab/1792250000000-docs-amendments-against-master.patch` (545 lines),
plus the full amended files `collab/1792250000000-carrier.amended.md` and
`collab/1792250000000-continuation.amended.md`.

**rows.cljc against the note's estimate (about 280 added, 135 deleted).**
The procedure region (lines 221–700, from the section comment up to
`emit-numbered`) is 480 lines with blanks, about 420 non-blank. That
matches the probe's 405 non-blank lines for the same region (note
appendix A). The note's 280 was a planning estimate it called "not a
diff". The numstat also counts as moved: `emit-numbered`, `emit` and
`default-budget` (moved above `emit-node`), `emit-slot` and `emit-node`
re-indented for the path and child-numberer arguments, and the `decode*`
translation. About 100 of the added lines are docstrings and comments.

### What changed in rows.cljc

- **Deleted:** `check-label-bytes!`; all of 6a's `canonical-numbering`
  body (label seeding, `checked-*`, `ce-slot`/`ce`, `positions`,
  `classes`, `refine`, `search`); `:label-bytes` and `:search` in
  `default-budget`; the duplicate `(declare canonical-numbering)`.
- **Added**, porting the probe's procedure, not importing it:
  - `work!`;
  - `charge-representation!`: iterative, explicit stack, aborts against
    the remaining budget, keeps 6a's `#?(:cljd (.-length x) :default
    (alength x))`;
  - `key-bytes!`: charge, then `cbor/encode`, then 6a's `bytes-key`;
  - `sort-by-key!`;
  - `depth-scan!`: explicit stack of lazy element sequences; each
    position is charged when popped;
  - `walk-cells`, `item-cells`, `body-ranks`, `new-run`, `meet!`,
    `enter-slot!`, `enter-node!`, `unordered!`, `fixpoint!`,
    `unnumbered-cells`;
  - `tie-failure`: the probe's `group-failure`, with its `for` replaced
    by `reduce`;
  - `resolve!` (without the diagnostic `:permissive` switch and trace),
    `key-walk` (the probe's `local-run`), `spell-cell`, `spell-slot`,
    `spell-node`, `discovery-numbering`, `unresolved!`, `child-numberer`.
- **Rewritten:** `canonical-numbering`, with the same 1- and 2-arity
  contract and an empty census still returning `{}`; `default-budget`
  (`:work 4194304`); `descriptors` (synthetic `:entry` → `[:V :V]`);
  `emit-slot` and `emit-node` (they thread a row path and a child
  numberer); `emit` and `emit-numbered`; the `decode*` recompute (the
  translation wraps the whole `emit` call); the namespace docstring.
- **Gate order** is as the note fixes it: census pair count, then the
  iterative depth scan, then ranks (charged encodings), counts, the walk
  (which also checks its own depth), the tie test, the refusal, and
  totality.

## Tests

| Run (JVM only, all foreground) | Result |
|---|---|
| `clj -M:kondo --lint` on both changed files | 0 errors, 0 warnings |
| `clojure -M:test -n yin.vm.ucf.rows-test`, slow included | 30 tests, 461 assertions, 0 failures (about 23 s) |
| `clojure -M:test -e :slow -r 'yin\.vm\.ucf\..*' -n yin.vm.semantic-register.frame-test` | 688 tests, 12299 assertions, 0 failures |

The 688-test run was made before the incidence-family generator was added
to `rows_test`. `rows-test` was re-run in full afterwards (461 assertions,
the row above). `rows.cljc` was restored byte-identical (checked with
`diff`) after the mutation runs. Node and Dart were not run, per the
brief.

`rows_test` has 30 deftests (6a: 21). One is `^:slow` and wrapped in
`dao.test-slow/guard`.

**Kept unchanged:** `bijection`, `row-ids-are-jing-default`,
`window-sorted-and-unique`, `canonical-under-is-renaming-invariant`,
`alias-topology-and-census`, `gates-and-graph-before-hashes`,
`child-scope-and-shared-child-dag`,
`pending-envelope-and-nil-literal-are-preserved`,
`task-root-checks-carried-code-and-frame`, `unordered-containers-are-order-free`,
`census-is-numeric-from-ten-cursor-cells`, the three marker tests,
`cursor-cell-and-identity-shaped-literal-are-distinct`, and the
seven-cell fixture.

**Changed beyond the note's list:** `schema-and-literal-canonicality`
(see deviation 9).

**Extended:** `numbering-precedes-frame-defect` (item 20).

**Rewritten per §4:**
- `symmetric-cursor-cells-are-interchangeable`
- `canonical-roots-are-pinned` (also carries item 17)
- `seven-cursor-cells-are-refused`
- `large-literals-in-ordered-positions-are-not-spelled` (also carries
  item 21's 400000-literal-in-a-set assertions)

**New, one per §5 item:**

| Item | Test |
|---|---|
| 12 | `discovery-order-numbering` |
| 13 | `occurrence-count-separates-members` |
| 14 | `interchangeable-ties` |
| 15 | `alias-pattern-is-part-of-the-key` |
| 16 | `unresolved-cursor-cells-refuse` |
| 18 | `counterexample-search-quick` (fast) and `counterexample-search` (`^:slow`) |
| 19 | `numbering-is-total` |
| 21 | `gates-and-work` |

**Generator results** (portable Park–Miller seed, no `java.util.Random`).
No failure means 0 root or refusal mismatches against a renamed and
shuffled copy, and 0 decode or re-emit failures.

| Generator | Quick | Slow |
|---|---|---|
| random values | 250 states | 2000: 1978 numbered, 22 refused, max `:work` 776001 |
| frame-bearing | 40 | 200: 197 numbered, 3 refused |
| with children | 20 | 100: 99 numbered, 1 refused |
| incidence families | 100 | 1000: 832 numbered, 168 refused |

The quick test takes about 1.5 s on the JVM.

## Pins reproduced (no re-pinning)

Every pin and numbering the note names reproduced at the first run:

| Pin | State | 6a root? |
|---|---|---|
| `fab223…` | `fixture :s1 :s2 :x :y` | yes |
| `c4a7c7…` | worked frame | yes |
| `2d9873a8…` | review counterexample, `y cc-1, x cc-2` | yes |
| `07eda35d…` | `#{X Y}` | yes |
| `4993b5b1…` | `[x x y]` | yes |
| `536c810b…` | `[x y y]` | yes |
| `da69c679…` | `#{[X X] [Y Y]}` | yes |
| `49d4478c…` | `{X 1, Y 1}` | yes |
| `387f294a…` | codex A, both enumerations | yes |
| `be62ba43…` | codex B | yes |
| `18b70de6…` | codex C | yes |
| `a850fce6…` | `[#{X Y} X]` | yes |
| `0193312…` | witness, `b cc-1, a cc-2` | yes |
| `6e7c7a6b…` | ten cells | yes |
| `e67405ec…` | `[#{#{X Y} #{Z W}} X]` | no (new) |
| `5376f133…` | balanced-alias witness, both enumerations | no (new) |
| `d84480d4…` | `[#{X Y} #{Y Z}]` | no (new) |
| `726a9fbb…` | path | no (new) |
| `0b9ef61e…` | frame fixture 1, `y cc-1, x cc-2, :test/cursor cc-3` | no (new) |
| `ff124bfc…` | frame fixture 2, same numbering | no (new) |

Every pin is asserted with its full id.

**Refusals reproduced:** the paths and cells of appendix A for all eleven
refused states, including `[15 0 :member]` and the install child
`[12 a 15]`. The latter decodes to `[<root> 12 a 15]` with cells
`[cc-1 cc-2]`.

**Removed:** `55ff168e…` (the loop plus 2-cycle now refuses at path
`[15]`, cells `[:a :b]`).

## Mutation results

Each mutant was a temporary source edit, restored from a copy and checked
with `diff`.

| # | Mutant | Caught by |
|---|---|---|
| 1 | Drop the occurrence count from `[:u j r m]` | **Caught**: pins in `canonical-roots-are-pinned`, `discovery-order-numbering`, `occurrence-count-separates-members`, `alias-pattern-is-part-of-the-key` (17 failures, 6 errors). The generator finds no mismatch: it is a different, still canonical rule, as the fourth review found. |
| 2 | Drop the first-occurrence index | **Caught**: the balanced-alias witness gives two accepted roots for its two enumerations (`rows_test` `alias-pattern-is-part-of-the-key`), plus a pin and a refusal assertion. The generator alone misses it, as the review predicted. |
| 3a | Reverse the host order in which a *passing* tie group is entered | **Survives, as it should**: the note's §2 proves this order cannot change the rows. Every pin and the 3300-state generator are unchanged. An equivalent mutant, so no test is possible. |
| 3b | Enter a *failing* group in host order instead of refusing (the probe's `:permissive`) | **Caught**: 39 failures in the refusal tests. With the incidence families the generator also catches it on its own (20 invariance failures in 1000 slow states, 6 in the 100 quick ones). Without the incidence families the generator missed it, which is why I added them (deviation 11). |
| 4 | Skip the confinement clause (iii) only | **Caught** by the three shared-across-groups fixtures, and by the generator through the incidence families (9 slow, 3 quick). |
| 5 | Skip the representation charge in `key-bytes!` | **Caught** by the large-literal `[:work]` assertions (3 failures). |
| 5b | Skip the depth scan's per-position charge | **Caught**: the `:work` counter is no longer 2001 under `{:work 2000}`, and the default and 900000 cases no longer refuse. |
| 6 | Validate frames before the numbering recomputation in `decode*` | **Caught** by both halves of `numbering-precedes-frame-defect` (3 failures). |

## Deviations from the note, and why

1. **No merge** (above).
2. **Docs come as a patch against master's text**, not as edits to the
   branch's stale copies. Editing those would conflict with master's rename.
3. **A1 adaptation.** Note §1 went into carrier §6 as named paragraphs. Its
   section references were renamed: §1.x to the paragraph names, and §2,
   §3 and the appendix to "numbering note §…". "carrier §N" became "§N".
   The implementation pointers (`rows.cljc:NNN`, "6a's gate…", "6a's
   test…") and the appendix's measured figures were dropped from the
   normative spec, because they go stale. The rule paragraph was kept as
   §6's summary. A3 to A9 were applied verbatim. A9 appends items 12–22
   after item 11.
4. **`emit-numbered` takes an optional `number-child`** argument, `(fn
   [child path] numbering)`, defaulting to canonical numbering. Without
   it, item 16's install-child carrier can't be built: `emit-numbered`
   numbers children canonically, and the child would refuse on the lift
   side. Test-facing only, and the 2-arity is unchanged.
5. **`canonical-numbering` has a 3-arity** `[task budget counter]`, so
   tests can observe the `:work` counter (2001) and `:estimator-steps`.
   The refusal ex-data stays exactly `{:yin.k/status :yin.k/kind
   :yin.k/path}`, plus `:yin.k/cursor-cells` for the tie refusal. There
   is no probe-only diagnostic key.
6. **Install children are numbered under the caller's budget.** 6a's
   `emit-node` used the default budget. The child refusal path is
   threaded through nested installs: a grandchild reads `[12 a 12 b …]`,
   where the probe reset it to the child-local path.
7. **An empty census returns `{}`** right after the census gate, before
   the depth scan. This is 6a's contract and the note §4's ("empty census
   → `{}`"); the probe walked anyway.
8. **Cell lists in refusals and defects are sorted by `str`**, not by
   `compare`, for portability. The order is the same for every keyword
   id used.
9. **`schema-and-literal-canonicality`, a kept test, needed two carriers
   built with `emit-numbered`.** The lift now refuses those two states
   itself: an unreachable census cell is the §1.6 totality defect, and
   `[x [1]]` / `[x (1)]` tie and share `x`. The test checks decode's own
   `:extra-cursor-cell` and `:duplicate-key`, so it now supplies the
   numbering. The note §4 didn't foresee this.
10. **The 400000-literal-in-a-set assertions** (default budget, `{:work
    2000}` with counter 2001 and 0 estimator steps, `{:work 900000}` with
    between 1 and 999 estimator steps) live only in
    `large-literals-in-ordered-positions-are-not-spelled`. Duplicating
    them in `gates-and-work` would double the cost of the heaviest test.
11. **The generator gained the fourth review's incidence families**
    (quick 100, slow 1000) beyond item 18's three generators, so the
    generator alone catches mutants 3b and 4.
12. **`decode*` with `:check-numbering false`** (used by
    `canonical-under`) still runs the recomputation, as 6a did, so it can
    surface the translated tie refusal. This is unchanged 6a behaviour,
    noted for the record.

## Uncertain, or for the orchestrator

- **Node and Dart are unrun.** Portability measures taken:
  - no `for`;
  - explicit stacks for the depth scan and the estimator;
  - no `@#'`;
  - no multi-key `assoc` on nil;
  - no unary minus;
  - catches use `#?(:cljd Object :clj Throwable :cljs :default)`;
  - the test PRNG keeps every product below 2^53.

  `test/cljd-out` holds stale generated tests for the old
  `rows-test`; per build-n-test, `rm -rf test/cljd-out` before trusting
  a plain `cljd test`.
- **Possible slow tests on Node and Dart.**
  `large-literals-in-ordered-positions-are-not-spelled` takes 1.9 s on
  the JVM: it scans 800k positions three times and builds several
  400000-element literals. The quick generator takes about 1.5 s on the
  JVM. If either goes over about 5 s on a lane, tag it `^:slow` and wrap
  it in `guard`.
- **Mutant 3's wording.** "Make a tie order depend on host enumeration"
  was run both ways (3a, 3b). Only the unsafe form (3b) is a real defect.
- **`:work` figures differ from appendix A** (max 776001 here against
  625518 there). The generators use a different RNG, so the states
  differ. The 2001 and fewer-than-1000-estimator-step thresholds hold.

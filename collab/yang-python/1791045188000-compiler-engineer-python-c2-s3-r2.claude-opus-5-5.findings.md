Completed-GMT: 2026-10-03 17:30:23 GMT
Completed-Local: 2026-10-04 00:30:23 +07
Coding-Agent: claude
Session-ID: 8a064c70-5a78-4fc8-a2d8-1bcd34e27a0f

# C2-S3 round 2: rebased onto master eaf7d6f0, lanes done (Dart excluded)

## Step 1 (done by the orchestrator)
This session could not approve git write commands. The orchestrator rebased the worktree onto eaf7d6f0
and re-applied the S3 diff; all five files auto-merged with no conflicts. I checked the merge:
- master's `^:slow` tags on `long-generator-with-break-test` and `resume-continuation-length-is-stable-test`
  are intact;
- the S3 additions sit alongside master's float64 paths.

No git write commands were run by me.

## Changed files (uncommitted, unstaged)
- `src/cljc/yang/python/antlr/lower.cljc`: `yield from x` lowers to `(py/yield-from %gen x)`.
- `src/cljc/yang/python/antlr/prelude.cljc`:
  - new definitions: `py/yield-from`, `py/delegate`, `py/delegate-step`, `py/iter`, `py/iter-step`,
    `py/iter-outcome`, and the builtin `py.b/iter` (`iter` added to `builtin-names`);
  - `:iterator` arms added to `py/next`, `py/getattr` and `py/iter-at`.
- `test/yang/python/antlr/e2e_c2_test.clj`:
  - 5 S3 acceptance tests;
  - the resume-size helper generalized, with a nested `yield from` retention check (ruling 7).
- `test/yang/python/antlr/lower_test.clj`:
  - the obsolete "yield from unsupported" row is dropped;
  - module-level and comprehension `yield from` syntax rows added;
  - a generator-classification row for a `yield from`-only body added.
- `test/yang/python/antlr/prelude_parity_test.cljc`: `yield-from-and-iter-on-every-host-test`, the portable
  form for Node and Dart.
- `test/yang/python/antlr/float_address_test.cljc`: 5 goldens re-minted (see below).

## Re-minted goldens (`float_address_test.cljc`, all JVM-computed)
All five move for one reason: the bundled prelude grew (the new S3 definitions and builtin), so its
address changes. Each golden that contains the prelude subtree changes with it. This is the §11
prelude-profile bump the design anticipated (Q6).

| Golden | Old | New |
|---|---|---|
| prelude-addresses-test, bundled prelude root | `38a0e750…a1c2` | `8130fa02…f868` |
| program-addresses-test, A root | `f941b996…fe28` | `b88118aa…0eca` |
| program-addresses-test, A' root | `f05a91d4…e8ff` | `e3cc50cf…2563` |
| program-addresses-test, record-address | `39bfcf1e…3da5` | `e396a9be…33a6` |
| program-addresses-test, prelude-id | `f976da2e…6fdd` | `8dfb4eea…a708` |

- The hook-prelude golden did not move.
- No float-bearing golden was added.
- Node computed the same new values (its float-address-test passed in the Node lane).

## Lanes
| Lane | Result | Wall time |
|---|---|---|
| bb gen:python-antlr | ok | — |
| Focused JVM run of e2e-c2, prelude-parity and lower-test (includes their slow tests), rebased, 23:36 | 60 tests, 313 assertions, 0 failures, 0 errors | 11:59 |
| Full JVM `bb test:clj` (excludes `^:slow`), started 23:56 | 2905 tests, 226586 assertions, **5 failures**, 0 errors. All 5 were the moved goldens above. | 24:06 (4 other JVMs were running on the machine) |
| JVM float-address-test after the re-mint | 12 tests, 105 assertions, 0 failures | 0:19 |
| bb build:yin-repl-node | ok | — |
| Node `bb test:cljs` (after the re-mint) | 2718 tests, 91639 assertions, 0 failures, 0 errors | 5:01 |
| Slow test `-i :slow -n yang.python.antlr.safepoint-test` (tail-preservation-test) | 1 test, 9 assertions, 0 failures | 1:14 |
| Slow test `-i :slow -v …e2e-c2-test/long-generator-with-break-test` | 1 test, 5 assertions, 0 failures | 1:14 |
| Slow test `-i :slow -v …e2e-c2-test/resume-continuation-length-is-stable-test` (includes the yield-from retention check) | 1 test, 72 assertions, 0 failures | 0:51 |
| kondo on the 6 changed files | 0 errors, 0 warnings | — |
| cljstyle fix, then check, on the 6 files | clean (exit 0) | — |

- I did not run long-loops-test, as the brief says.
- **Dart: not run.** The single CLJD runner belongs to C3-S2; the orchestrator runs Dart later.
  `prelude_parity_test.cljc` holds the portable S3 form for that lane.
- The full JVM lane ran before the golden re-mint. After the re-mint, only float-address-test was re-run
  on the JVM, not the whole lane.

## Process notes
- **Auto-backgrounded runs.** I started nothing in the background. Two commands ran past the 10-minute
  foreground cap and the harness moved them to the background: the focused JVM run and the full JVM lane.
  Both were waited on to completion within this turn, and their results are above. The waiter commands that
  also hit the cap were stopped.
- **Watch the next rebase.** C3-S2 rewrites `py/key` and changes prelude.cljc. After it lands, the
  prelude-address goldens move again, so they need re-minting on that rebase.

## Open items
- `yield-from-long-range-test` (6000 delegated items on 4 VMs) is not tagged `^:slow`. Tagging it is the
  orchestrator's call, based on per-test timing.
- These are design gaps already noted in the design and left for later slices, not S3 scope:
  - `x in generator` / `x in iterator` (no `py/contains` arm);
  - user-defined `__iter__`/`__next__` (S5);
  - two-argument `iter(callable, sentinel)`;
  - snapshot rendering of iterators (they render as `:py/object`).

## Round 3 (2026-10-04 00:35:41 +07): rebased onto a932bb55 (range fast path)

The orchestrator did the rebase. I ran no git write commands.

**Conflict.** float_address_test.cljc had 2 conflict hunks. I took the upstream side, then re-minted from
the actual values, which reflect both prelude changes: the range fast path and S3.

| Golden | master a932bb55 (range only) | Now (range + S3) |
|---|---|---|
| bundled prelude root | `3d287776…5ddd` | `f0e6fde3c483b360a20485b47099cdae501cd864fa0750d4ff7483809dfcb870` |
| program A root | `38e17ba2…f3ef` | `ddb235113583944ceffb48793dd33d04f97be6d032d99fec29808d039067acd7` |
| program A' root | `270bc081…35f8` | `2032b43da2fb101fd1e8c6bb9a1e2d6b68f2c56c7cb0c105f3d253f740343b9d` |
| record-address | `88d5972b…76de` | `6355f801305b749f95916efd0b474a0be757941d452917a968f70a71c5ef7e5c` |
| prelude-id | `9eb6ff93…4121` | `ea39a47f3231c85bc3755e6e4d319d6f1d0eaaa8ba74cfca8a3595d50217a400` |

- The hook-prelude golden `76e1cfe8…02e8` is unchanged.
- `git diff HEAD` on the file shows exactly those five lines.
- **The index still marks the file `UU` (unmerged).** No conflict markers remain, but I may not stage, so
  marking it resolved is the orchestrator's `git add`.

**yield-from-long-range-test.** Run alone on the JVM (`clojure -M:test -v …`): 1 test, 5 assertions,
0 failures, 1:59 wall. JVM startup and loading are about 19 s, so the test body is about 100 s, well over
5 s. I tagged it `^:slow`. It is now on the orchestrator's slow list alongside long-generator-with-break and
resume-continuation-length.

**Focused runs (JVM)**
| Run | Result | Wall time |
|---|---|---|
| float-address-test before the re-mint | 12 tests, 105 assertions, 5 failures (the five goldens) | — |
| float-address-test, prelude-parity-test, lower-test after the re-mint | 51 tests, 245 assertions, 0 failures, 0 errors | 36 s |
| `-e :slow -n yang.python.antlr.e2e-c2-test` | 19 tests, 95 assertions, 0 failures, 0 errors | 30 s |

**Lint.** kondo on the two files changed this round: 0 errors, 0 warnings. cljstyle check on all six
changed files: clean.

**Not run, per instructions:** the full JVM, Node and Dart lanes.

## Round 5 (2026-10-04 01:31:49 +07): PEP fidelity fixes and deeper delegation tests

Base is a932bb55 with the S3 diff staged. I ran no git write commands. Changes are unstaged edits to
prelude.cljc, prelude_parity_test.cljc and e2e_c2_test.clj. The design doc was not touched (see item 3).

**CPython evidence.** All expected outputs come from runs of the same programs under /usr/bin/python3
3.9.6, done in a scratch dir that I deleted afterwards.

### Item 1: thrown StopIteration through a closed delegate
- **Change.** New `py/stop-as-return`, applied in `py/delegate-step` only on the throw path of a generator
  delegate. A `[:raise e]` whose class is StopIteration or a subclass becomes `[:return value]`. The value
  is read as the own `value` attribute, or None when the instance has none.
- **PEP 479 preserved.** The inner body's conversion is untouched: a StopIteration escaping a delegate's
  body is already RuntimeError (`py/gen-fail`) before it reaches the delegation site. A sequence delegate
  still raises the thrown StopIteration in the outer, which is RuntimeError, as in CPython (`_gen_throw`
  goes to `throw_here` when the delegate has no `throw`).
- **CPython 3.9.6 results:**

  | Case | Result |
  |---|---|
  | closed inner, `throw(StopIteration(7))` | `v 7`, then `after` |
  | `MyStop(8, 9)` subclass | `v 8` |
  | thrown class `StopIteration` | `v None` |
  | StopIteration raised in the inner body | `RuntimeError('generator raised StopIteration')` |
  | thrown at a sequence delegate | same RuntimeError |
  | subclass whose `__init__` skips `super()` | **segfault (exit 139)**: no oracle, not tested; ours answers None |

- **Tests.**
  - e2e `yield-from-thrown-stop-iteration-test`: all five non-crashing cases above, on four VMs.
  - Parity `delegation-stop-and-sticky-dict-iterator-on-every-host-test`: closed inner, throw
    StopIteration 7 gives 7; body StopIteration gives RuntimeError; all four evaluators, every host the
    file runs on.
- **Found, not fixed (S2, outside this round's items).** `fresh_gen.throw(StopIteration(4))` is
  RuntimeError in CPython 3.9.6, because the frame starts and PEP 479 converts. Our `py/gen-switch`
  `:created` + throw path closes the generator and re-raises the plain StopIteration. Ordinary exceptions
  are unaffected. It cannot reach `yield from`, because a generator delegate is always started before the
  first throw arrives.

### Item 2: sticky dict/set iterator invalidation
- **Change.** `py/iter-step` now checks a keys-iter source before advancing. When the dict or set size no
  longer matches, it first writes the iterator's expected size as -1 into the cell, then lets `py/iter-at`
  raise. Every later `next` raises the same RuntimeError, even after the size is restored. This mirrors
  CPython's `di_used = -1` / `si_used = -1`.
- **Not changed:**
  - for-loop walking (a loop ends at the raise anyway);
  - the size-based detection (glm's P3, out of scope).
- **CPython 3.9.6 results:** `first`, then `still ('dictionary changed size during iteration',)` after
  `del d['c']`. Sets behave the same (`Set changed size during iteration`).
- **Test.** The parity form iterates a dict, grows it (RuntimeError), restores the cell's earlier content,
  and asserts the next advance still raises. It has to be a parity form because our Python subset has no
  `del` and no `set.remove`, so a guest program cannot shrink a dict or set.

### Item 3: deeper delegation coverage and reachable retention
- **Parity (all four evaluators, all hosts):** `delegation-chain-callers-and-unwinding-on-every-host-test`
  - a 3-deep chain a → b → c (and c → list), resumed by:
    - the top level;
    - a caller with its own handler frame (`py/try-finally`);
    - another generator (`next` inside w);
    - a delegating generator (`yield from a` inside d);
  - the log `[:sent :c-fin :c-ret :b-ret]`;
  - suspension during unwinding:
    - the inner's finally yields while a thrown KeyError unwinds, then the outer's except and finally
      yield;
    - a ValueError unwinds through both finallys, suspending at each, then reaches the caller.
- **e2e (four VMs, CPython-verified):**
  - `yield-from-chain-with-changing-callers-test`: the same chain, resumed from top level, a function,
    a generator and a delegator; plus a throw through three levels from a function caller.
  - `yield-from-suspension-during-unwinding-test`.
- **Reachable-graph measure: cheap, so delivered now.** No 8.5.3 sentence was added.
  - New `^:slow` test `reachable-heap-is-stable-under-delegation-test`, JVM-only.
  - Each VM's final state is forcibly collected with `yin.vm.engine/collect`. The suspended outer
    generator `h` is the only extra root, passed as a sealed ref built from its heap entry.
  - It measures the live cell count plus the deep size of every live cell's content, with continuation
    and closure payloads followed.
  - The source runs 10 vs 1000 items through `yield from`. Callers alternate between a function and a
    fresh generator dropped after one step; those are swept because they are not rooted.
  - Assertion: the two measurements are equal on all four VMs.
- **Sanity probe of the measure** (AST walker):
  - stable program: `[59 2615170]` at both 10 and 1000 items;
  - variant whose `g` keeps a growing local list: `2795497` at 10 vs `2795517` at 30 items, a difference
    of exactly the 20 extra ints. So the measure detects per-item retention.
  - My first attempt without the root was vacuous: `[45 …]` for every program, because the module's
    globals are garbage after completion. That is why the root is required.
- **Limitation.** This is post-run reachability from the suspended generator, not a mid-run snapshot.
  Retention in the caller frames of a live resume is covered only through what the suspended
  continuation captures.

### Item 4
Goldens were not re-minted. float-address-test is expected to fail on the five content-address goldens
until the post-C3-S2 rebase. I did not run it this round.

### Item 5
Noted only. The delegation entries do no recursion-limit comparison or `:base` rebasing; that belongs to
safepoint-s2. No safepoint code was touched.

### Focused runs (JVM)
| Run | Result | Wall time |
|---|---|---|
| prelude-parity-test and lower-test | 41 tests, 148 assertions, 0 failures, 0 errors | 34 s |
| `-e :slow -n yang.python.antlr.e2e-c2-test` | 22 tests, 110 assertions, 0 failures, 0 errors | 37 s |
| `-i :slow -n yang.python.antlr.e2e-c2-test` (long-generator-with-break, yield-from-long-range, resume-continuation-length, reachable-heap) | 4 tests, 94 assertions, 0 failures, 0 errors | 4:08 |
| reachable-heap test alone | 12 assertions, 0 failures | 1:13 |

- The new parity forms have not run on Node or Dart. Those lanes are the orchestrator's.
- kondo on the 3 changed files: 0 errors, 0 warnings. `cljstyle fix` then `check`: clean (exit 0).

### Process deviation (unintended full JVM run)
My first probe of the measure was `clojure -A:test -M -e …`. It picked up the `:test` alias's main-opts
and so ran the whole cognitect runner: 2934 tests, 227308 assertions, 5 failures, 0 errors. The harness
also moved it to the background when it passed the 10-minute cap; I waited for it in the foreground.

The 5 failures are most likely the five un-re-minted goldens, but the tail-truncated output did not show
which tests failed, so I cannot confirm it. Later probes used an inline `-Sdeps` alias with `test` on the
classpath and no main-opts.

## Round 6 (2026-10-04 01:36:08 +07): rebased onto be1f8d06 (C3-S2 on top of the range fast path)

The orchestrator did the rebase. I ran no git write commands. Both files below still show as `UU`
(unmerged) in git, since I may not stage; marking them resolved is the orchestrator's `git add`.

### Conflicts resolved
- **`e2e_c2_test.clj`.** The one hunk was the `:require` block. I kept both master's
  `[yin.vm.integer :as integer]` and my `[yin.vm.engine :as engine]`. All `^:slow` tags are intact:
  - long-generator-with-break-test
  - yield-from-long-range-test
  - resume-continuation-length-is-stable-test
  - reachable-heap-is-stable-under-delegation-test
- **`float_address_test.cljc`.** I took the upstream side of both hunks, then re-minted.

### Re-minted goldens
Before the re-mint, float-address-test failed on exactly the five prelude-derived goldens and nothing else.
The C3-S2 dict-keys golden and the hook-prelude golden `76e1cfe8…` passed and are untouched.
`git diff HEAD` on the file shows only these five lines.

| Golden | master be1f8d06 | Now (master + S3) |
|---|---|---|
| bundled prelude root | `d99d4805…5d1d` | `5d0cf7add8ce2935abe02d6de0a5394af481b770d93c4270718a7d06309c0c46` |
| program A root | `d9185a98…e2fc` | `47f4af6df28d3f68067636b4ad1c93add10a78308dd8604b31c439e9d45cf68c` |
| program A' root | `b3a62d04…f422` | `6aef84ed56df6566cf06452505f94748916f24aa3df8b8ce62b174a49df50fec` |
| record-address | `48586d14…d00f` | `b74fa7d95806d3b754ebc6757ca055e23bc64a135863fd41b552018f70bf9d07` |
| prelude-id | `306f688f…4ac2` | `1024e8e011c4a555c390861f5a76c98f4807f56008b541c835ef60306541def1` |

### Focused runs (JVM)
| Run | Result | Wall time |
|---|---|---|
| float-address-test, prelude-parity-test, lower-test, yin.vm.data-test | 79 tests, 652 assertions, 0 failures, 0 errors | 1:06 |
| `-e :slow -n yang.python.antlr.e2e-c2-test` | 22 tests, 110 assertions, 0 failures, 0 errors | 42 s |

- kondo on the two resolved files: 0 errors, 0 warnings. cljstyle fix, then check on those two plus
  prelude.cljc and prelude_parity_test.cljc: clean.
- **Not run:** the full lanes, Node and Dart (the orchestrator's). `clojure -A:test -M -e` was not used.

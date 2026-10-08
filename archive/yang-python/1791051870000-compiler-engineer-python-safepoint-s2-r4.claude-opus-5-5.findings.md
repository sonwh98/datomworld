Completed-GMT: 2026-10-03 18:46:28 GMT
Completed-Local: 2026-10-04 01:46:28 +0700
Coding-Agent: claude (claude-opus-5-5)
Session-ID: a80b326f-0efb-4ae5-ae0f-df0154076bab

# Round 4: safepoint slice 2, the generator resume-admission hole

## Conflict
In `test/yang/python/antlr/safepoint_test.cljc`, master's `dao.test-slow`
require is kept exactly, and so are `^:slow` and
`(slow/guard "tail-preservation-test" (fn [] ...))` on
`tail-preservation-test`. My `safepoint-programs` and `yang.tails` requires
and the slice-2 tests are back after it.

## Item 1: admission at generator start and resume

### Where the limit lives (design point for review)
The crossing, `py/gen-switch`, is in the base prelude, and naive programs
load the base prelude without the hook prelude. A check that names
`py.sp/limit`, the gate's suggested shape, would therefore fail on an
unresolved name in every naive program that uses a generator.

So I moved the limit cell into the base prelude as `py.rt/limit`, default
1000. It still sits outside the escape-restored record. The hook prelude no
longer allocates `py.sp/limit`: `py.sp/enter`, `py.sp/recursion-limit` and
`py.sp/set-recursion-limit!` read and write `py.rt/limit` instead.

One consequence: naive runs now also refuse generator crossings past the
limit. In a naive run the absolute depth counts only nested active
generators, because nothing counts function calls without the hooks.

### The fix
In `py/gen-switch`, on the live path (a start, or a resume that sends or
throws), the check compares `py.rt/limit` with 1 plus the absolute depth.
It runs before any write to the generator or to `py.rt/ctx`. A refusal
raises `RecursionError` on the caller's stack and leaves the generator
created or suspended. The crossing itself moved unchanged into a new
`py/gen-enter`. `gen.throw` and `gen.close` go through `py/gen-switch`,
so they are covered as well.

### Red (current tree, before the fix)
The test is `generator-admission-test`, run on the program
`programs/admission` under limit 100. It failed on all four VMs:

    FAIL in (generator-admission-test) :ast-walker / :semantic / :stack / :register
    expected: [true false #:py{:out ["start refused" "2" "resume refused" "3" "4" "100"], :exception nil}]
      actual: [true false #:py{:out ["3" "5" "6" "100"], :exception nil}]
    Ran 1 tests containing 4 assertions. 4 failures, 0 errors.

### Green (after the fix)
Run with `generator-rebase-test` and `generator-throw-close-depth-test`:

    Ran 3 tests containing 12 assertions. 0 failures, 0 errors.

The regression proves three things:
- the call-free generator is refused at its start and later at a resume,
  each time 100 frames down;
- after a refusal it stays resumable: it starts from 11 frames down (2),
  resumes from 99 frames down (3) and from the top (4);
- the caller's depth is unchanged: `probe` at the top still finds 100.

The §8.5.2 admission sentence is added.

## Item 2: the missing tests
All on four VMs, with pinned counts under limit 100.

- `generator-rebase-test`, program `rebase`:
  - shallow first, then deep, then shallow: 99, 79, 99;
  - nested active generators counted additively: 98 from the top, 78 from
    20 frames down;
  - a `try` left normally after a resume from a different depth: 79, then
    99;
  - `probe` at the top: 100.
- `generator-throw-close-depth-test`, program `throwclose`: a `finally`
  runs at the current resumption base under `gen.throw` (99, then
  "thrown") and under `gen.close` (99); `probe` at the top: 100.
- The rejected deep resume followed by a successful shallow one is covered
  in `admission`, above.

The three new programs are in `safepoint_programs.cljc`, and
`e2e-test/portable-packets-are-the-parsers-test` now checks all seven
packets against the parser (7 assertions, all pass).

## Item 3: the doc contradiction
The old clause "an escape restores the whole record saved at capture" now
reads "restores the record saved at capture (slice 2 keeps the current
`:base`, below)".

## Item 4: line widths
- `safepoint.cljc`: both message strings are split with `data/str-concat`,
  which is added to the hook prelude's `host-names`. The file has no line
  over 80 columns.
- `prelude.cljc`: the 105-column generator comment is reflowed, and so is
  the one new admission line.
- `safepoint_test.cljc`: the over-width line in `generator-depth-test` is
  wrapped.
- `safepoint_programs.cljc`: regenerated at a 76-column print margin. Only
  the seven source docstrings are over 80, because wrapping them would
  change the strings.

Other lines that earlier rounds of my diff added are still over 80; the
gate didn't name them, so they are left as they are.

## Item 5: goldens not re-minted
`float-address-test` gives 6 failures and 0 errors in 12 tests, as
expected. All six are prelude-derived:
- `:222`: the base-prelude root;
- `:224`: the hook-prelude root. This one moved because the hook prelude
  changed: slice 2 already changed it, and this round removed
  `py.sp/limit` and added `data/str-concat`;
- `:242`, `:244`, `:246`, `:250`: program addresses built on them.

## Focused runs (JVM, all four VMs inside each test)

| Run | Tests | Assertions | Result |
|---|---|---|---|
| `yang.python.antlr.safepoint-test` and `yang.safepoint-test` (`-e :slow`) | 27 | 414 | 0 failures, 0 errors |
| `prelude-parity-test` and `lower-test` (`-e :slow`) | 43 | 154 | 0 failures, 0 errors |
| `e2e-c2-test` (`-e :slow`) | 15 | 75 | 0 failures, 0 errors |
| `e2e-test` (`-e :slow`, before the packet-list extension) | 35 | 407 | 0 failures, 0 errors |
| `e2e-test/portable-packets-are-the-parsers-test`, extended to 7 packets | 1 | 7 | 0 failures, 0 errors |

- kondo on the 7 touched source and test files: 0 errors, 0 warnings.
- `cljstyle fix` then `check`: clean.
- I did not run the full lanes or Dart.

# Round 5: approved design, rebased onto C2-S3, the one re-mint

Completed-GMT: 2026-10-03 19:08:58 GMT
Completed-Local: 2026-10-04 02:08:58 +0700

The tree is on master `a4efc99a`, rebased by the orchestrator with no
conflicts.

## Item 1: stale comments in `prelude.cljc`
- The `RecursionError` class comment now names both raisers: generator
  admission in the base prelude, in every mode, and the hook prelude's
  depth accounting.
- The state-definitions docstring now says "the three runtime cells".
- The `py.rt/out` line in the namespace docstring is realigned with the
  other cells.

## Item 2: ownership statement in §8.5.2
Added the merged statement:
- the base prelude owns the record, the limit cell and admission at
  crossings, because no site mark reaches the crossing, and enforces
  admission in every mode;
- the hook prelude owns counting function frames;
- without recursion hooks only nested active generator frames count, which
  CPython also bounds;
- naive mode is therefore not complete CPython recursion accounting.

## Item 3: `admission-in-every-mode-test`, program `nested-admission`, limit 3
The limit is lowered by setting the base cell before the module runs,
since no setter exists without the hook prelude. The program runs three
ways (naive, under no-op hooks, under the real hooks), and all three must
give `refused 1 refused 2`.

How the counts follow from the code:
- `next(via(2))` starts `via(2)` at frame 1, then `via(1)` at frame 2, then
  `via(0)` at frame 3, each through `next`.
- Starting `t` would be frame 4, which is over 3, so it is refused.
  `RecursionError` closes the `via` chain and is caught by the caller's
  `try`; `t` stays created and installs nothing.
- `next(via(1))` starts `t` at exactly frame 3, so it yields 1.
- The suspended `t` is refused again at frame 4, then resumed from the top
  at frame 1, where it continues its own state and yields 2.
- Under the real hooks, the `via(n - 1)` calls count too, but each is at
  most at frame 3, so the hooked run refuses exactly the same crossing.

## Item 4: `delegation-admission-test`, program `delegation`, limit 100
The chain is `top -> mid -> leaf`, joined by `yield from`, and `probe(1)`
from depth d finds 100 - d. Pinned output:
`97 77 refused 77 "throw refused" 97 thrown 97 "close refused" 97 100`.

How the counts follow from the code:
- From the top, the resumer depth is 0, so `top` is frame 1, `mid` frame 2
  and `leaf` frame 3. `probe` therefore finds 97, one frame per active
  generator.
- `down(19, c)` resumes from frame 20, so `leaf` is frame 23 and `probe`
  finds 77.
- `down(99, c)` resumes from frame 100, so `top` would be frame 101 and is
  refused at its own admission. No generator in the chain is entered, so
  all of them stay suspended, and the next `down(19, c)` gives 77 again.
- `dthrow(99, c)` and `dclose(99, d)` are refused the same way.
- From the top, `c.throw(ValueError)` reaches `leaf`, whose `finally` runs
  at frame 3 (97); then `ValueError` reaches the caller ("thrown").
- `d.close()` runs `leaf`'s `finally` at frame 3 (97).
- `probe` at the top finds 100.

Every count matched when run. Delegation enters generators only through
`py/gen-switch` (`py/delegate-step`, and `py/gen-close`, which also goes
through `py/gen-switch`), so no extra check was needed.

Not tested: a refusal of an inner delegate after the outer has already
been admitted. In that case the `RecursionError` is raised in the outer
generator's body, as CPython also raises it, so the outer closes rather
than staying suspended. The brief's "every generator stays suspended" case
is the refusal at the outermost entry, which is what the test pins.

Both new packets are added to `safepoint_programs.cljc` and to the e2e
parser check, which now covers 9 packets.

## Item 5: the one re-mint (`float_address_test.cljc`)

| Line | Old | New |
|---|---|---|
| `:222` base-prelude root | `...5d0cf7ad...9c0c46` | `:segment/blake3-5ff57c3272ea8b0e0a6351425e3c991b2a88f2e8e7e3ee8ac161ad829c5d8a92` |
| `:224` hook-prelude root | `...76e1cfe8...1602e8` | `:segment/blake3-2d60190887dc1ca388d7a7b75467757c3f0e6c4b2d909603735c9a9852b0e3e3` |
| `:242` | `...47f4af6d...5cf68c` | `:segment/blake3-e09480bfa9e71c5fc95c96507f178f83558a3bd3689b9b8fac38b746b6d3f6cc` |
| `:244` | `...6aef84ed...f50fec` | `:segment/blake3-a1b9b28038cab04fbe73cffea0682211d71007cb24d5fbcffa39e9a8f40c4bfb` |
| `:246` | `...b74fa7d9...bf9d07` | `:segment/blake3-26f673d23909b00e128b023e0c647d6a6aadfba77d0ba125795fbb6941d04e81` |
| `:250` | `...1024e8e0...41def1` | `:segment/blake3-378dcbbf807ebab96d76523c890cd13d0af57baf08291a4dd0d2d47a39ac9e75` |

`git diff HEAD` on that file shows only these six golden lines. After the
re-mint, `float-address-test` gives 12 tests, 105 assertions, 0 failures.

## Focused runs (JVM; four VMs inside each test)

| Run | Tests | Assertions | Result |
|---|---|---|---|
| The two new tests, alone | 2 | 16 | 0 failures |
| `safepoint-test` and `yang.safepoint-test` (`-e :slow`) | 29 | 438 | 0 failures |
| `tail-preservation-test` (`-i :slow`, by name) | 1 | 9 | 0 failures |
| `e2e-test` and `e2e-c2-test` (`-e :slow`) | 57 | 522 | 0 failures |
| `prelude-parity-test` and `lower-test` (`-e :slow`) | 46 | 168 | 0 failures |
| `float-address-test`, after the re-mint | 12 | 105 | 0 failures |

- kondo: 0 errors, 0 warnings. `cljstyle fix` then `check`: clean.
- I did not run the full lanes or Dart.
- The `tmp_*` scratch files are deleted.

Completed-GMT: 2026-10-03 17:06:20 GMT
Completed-Local: 2026-10-04 00:06:20 +07
Coding-Agent: claude
Session-ID: af1f9774-0b7e-48d6-8e11-9690c65e0164

# Findings: py/range-elem guarded fast path

Worktree /Users/sto/workspace/datomworld-py-rangefix, branch yang-python-rangefix, base eaf7d6f0. Nothing staged or committed.

## Changed files

- src/cljc/yang/python/antlr/prelude.cljc (range region only)
  - `py/range-at`: O(1) `start + i*step` when `0 <= i <= 67108864`, `-67108864 <= step <= 67108864`,
    `-4503599627370496 <= start <= 4503599627370496`. These are literal `<=` comparisons, nested `if` like
    `py/checked-mul`, with no division and no recursion. Otherwise it calls the existing `py/range-elem`,
    which is unchanged and still separate, so its own recursion never re-runs the guard. The exclusive-stop
    test is unchanged. No length is precomputed. `py/range-count`, membership and `py/float-mod` are untouched.
  - `py/range-len` comment: CPython's limit is sys.maxsize; 2^53 is this profile's integer domain.
- test/yang/python/antlr/prelude_parity_test.cljc
  - 16 new `cases` rows, run on all four VMs. They cover:
    - i at 2^26 (fast) and 2^26+1 (recursive).
    - |step| at 2^26 (fast) and 2^26+1 (recursive), for both step signs.
    - start at ±2^52: the last valid index, and one-past giving `:py/stop` on the fast path (the one-past value is exactly ±2^53, the stop).
    - start at ±(2^52+1): the last valid index, and one-past giving `:py/stop` on the recursive path.
    - `range(-5, 5)` and `range(5, -5, -3)`.
  - Every existing row is kept, including the huge-range pins.
  - New `range-fast-path-on-every-host-test` (the regression guard, see below).
- docs: unchanged. docs/design/yang.antlr.md has no `range()` text, so per the brief there was nowhere to add the sentence.

## Regression guard used: stub rebinding (option 1)

The harness builds the prelude from the public `prelude/function-definitions`. The test rebuilds it with
`py/range-elem` replaced by `(fn [start step i] :range-elem-entered)`. It then checks two things on each of the
four VMs:
- `(py/range-elem 0 1 5)` returns the sentinel. This is the control: it proves the stub is in place.
- `py/to-vector` of `range(3000)` equals `(vec (range 3000))`.

If the fast path ever falls into the recursion, the sentinel reaches the stop comparison: it throws on the JVM
and gives a wrong or short vector on JS. Either way the assertion fails. The test is deterministic, with no
wall-clock and no ceiling.

Mutation check: I temporarily made the guard always false. The test then failed 4/4 (one per VM). After
reverting, it passes 4/4.

This guard runs on the JVM and Node lanes. It is written portably (plain `cljc`, no host branches), so it
should run on Dart as well, but I did not run Dart.

## Lane and test outcomes (all foreground)

| Run | Result | Time |
|---|---|---|
| `bb gen:python-antlr` | ok | 16 s |
| `clojure -M:test -n yang.python.antlr.prelude-parity-test` | 11 tests, 44 assertions, 0 fail, 0 error | 53 s |
| Same, mutation run of the guard test only | 1 test, 4 fail, as expected; then reverted | |
| `-v yang.python.antlr.e2e-c1-test/gate-round3-test` (huge-range pin) | 1 test, 12 assertions, 0 fail | 21 s |
| `-n yang.python.antlr.e2e-c1-test` | 19 tests, 228 assertions, 0 fail | 65 s |
| `-v yang.python.antlr.e2e-test/long-loops-test` (once) | 1 test, 12 assertions, 0 fail | 2:14 wall, including JVM start and load |
| `bb test:clj`, run 1 | 2900 tests, 226552 assertions, 5 failures, 0 errors | 6:10 |
| `bb test:clj`, run 2 (only to capture the failure text; I had not saved run 1's) | same counts | |
| `bb test:cljs` (Node) | 2718 tests, 91632 assertions, 5 failures, 0 errors | 4:43 (build 263 s, 0 warnings) |
| CLJD | not run, per the brief | |
| kondo (`clj -M:kondo --lint` on both changed files) | 0 errors, 0 warnings | |
| `mise exec -- cljstyle check` on both changed files | clean (exit 0) | |

`long-loops-test` timeline for comparison:
- 834 s on master.
- 132 s for the one-liner restore that the brief measured on master.
- 134 s with this fix.

The direct `cljstyle fix` / `cljstyle check` call needed an approval that was not given. The check through
mise ran and was clean, so no fix was needed.

## UNFINISHED: the 5 lane failures are content-address goldens, outside my allowed files

All 5 failures on both the JVM and Node are in test/yang/python/antlr/float_address_test.cljc. Those goldens
hash `prelude/uast`, so any prelude edit changes them. That file is not on the allowed list ("ask before editing
anything else"), and this single non-interactive turn gave me no way to ask, so I did not edit it.

I believe my prelude edit is the only cause: the brief describes eaf7d6f0 as green, and the hook-prelude golden
(hooks/uast, which my edit does not touch) still passes. I did not verify this by running the test with the
prelude reverted.

The JVM and Node computed identical new hashes, so cross-host agreement holds. New values, verbatim from
`actual`:

| Line | Expression | Old | New |
|---|---|---|---|
| 221 | `(:root (vm/ast->semantic-bytecode prelude/uast))` | `blake3-38a0e750…a6ca1c2` | `:segment/blake3-3d287776ee286f98912d2b4982d00ddf0fd7135c41f7cbfedaa9dfb326895ddd` |
| 241 | `(:root a)` | `blake3-f941b996…0afe28` | `:segment/blake3-38e17ba2c9eb1ab2357ff44820b86a4499514a4b8540efd3e2b8c0ec07bef3ef` |
| 243 | `(:root a')` | `blake3-f05a91d4…ff` | `:segment/blake3-270bc0816a82eece379299e09e30d00282cc664032bd38fb3b94cff8eb6f35f8` |
| 245 | `record-address` | `blake3-39bfcf1e…da5` | `:segment/blake3-88d5972b5d56c691e9669d95edd4de9261bc08b782cfa0dfb08b8de1bd1176de` |
| 249 | `(prelude-id a)` | `blake3-f976da2e…6fdd` | `:segment/blake3-9eb6ff939e458f4e47164fc49a04fa9a9f912bd5d28f35440d0389baae934121` |

Each concurrent prelude slice (C3-S2, C2-S3) will also move these goldens. They should be regenerated once,
after the last rebase, not per worktree. The Dart lane also checks these JVM goldens and will fail the same way
until they are updated.

## Follow-ups (recorded, not implemented)

1. `py/range-count` and `py/range-has?` still pay interpreted division-helper cost (`py/int-floordiv`,
   `py/int-mod`) on every call: `len()`, every membership test, slicing a range. They could take a similar
   literal-guard fast path.
2. `py/fmod-pos`: termination and bounded work on extreme exponent ratios (e.g. 1e308 % 1e-308) and subnormal
   divisors need an audit.
3. Now that C2 S1/S2 have landed, check whether `py/genexp-unsupported` and the "generator expression (phase C2)"
   refusals are stale.

## Round 2 (orchestrator-authorized golden update)

Completed 2026-10-03 17:09:36 GMT / 2026-10-04 00:09:36 +07.

Cause proof:
- What I reverted: only the `py/range-at` body, edited back by hand to the master
  `(let [x (py/range-elem (get r :start) (get r :step) i)] ...)`. I kept the new comments, which are not part of
  the AST, and left the new tests in place, since they are not used by float-address-test.
- Result: `clojure -M:test -n yang.python.antlr.float-address-test` gave 12 tests, 105 assertions,
  0 failures, 0 errors. The five old goldens pass again, so my `py/range-at` change is the only cause.
- I then restored the fast path exactly. `git diff --stat` shows prelude.cljc at 27 lines (21 insertions,
  6 deletions), the same as round 1.

Goldens: in float_address_test.cljc only lines 221, 241, 243, 245 and 249 changed (confirmed with `git diff -U0`).
The old values were already written as full `:segment/blake3-…` keywords, so the new ones use that form unchanged.

| Line | Old | New |
|---|---|---|
| 221 | `:segment/blake3-38a0e750133afdd523975049d8227b79a5d4b96cf2eeed9a1e5b7320da6ca1c2` | `:segment/blake3-3d287776ee286f98912d2b4982d00ddf0fd7135c41f7cbfedaa9dfb326895ddd` |
| 241 | `:segment/blake3-f941b99666d80d6dd60e428db8d52fab836f4fc0793f68de3db1a1995d0afe28` | `:segment/blake3-38e17ba2c9eb1ab2357ff44820b86a4499514a4b8540efd3e2b8c0ec07bef3ef` |
| 243 | `:segment/blake3-f05a91d4d2569332d2cf7443b8c4f8e2fa6322f6acaa4b7d79596b02a4c1e8ff` | `:segment/blake3-270bc0816a82eece379299e09e30d00282cc664032bd38fb3b94cff8eb6f35f8` |
| 245 | `:segment/blake3-39bfcf1e6a4d96099b4d69036deeb254a4394a3079aea6b7c1b12bf9b4ef3da5` | `:segment/blake3-88d5972b5d56c691e9669d95edd4de9261bc08b782cfa0dfb08b8de1bd1176de` |
| 249 | `:segment/blake3-f976da2ee9955478919eed7ad131ad02cdc04b9675377a183cc119e65d9c6fdd` | `:segment/blake3-9eb6ff939e458f4e47164fc49a04fa9a9f912bd5d28f35440d0389baae934121` |

Focused runs (JVM):
- `clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.prelude-parity-test`:
  23 tests, 149 assertions, 0 failures, 0 errors.
- kondo on the three changed files: 0 errors, 0 warnings.
- `mise exec -- cljstyle check` on the same files: clean.
- I ran no full lanes, per the instructions. The Node and Dart lanes have not been re-run with the new goldens.

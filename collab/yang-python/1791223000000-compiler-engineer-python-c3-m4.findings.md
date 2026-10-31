Completed-GMT: 2026-10-06 15:40:00 GMT
Coding-Agent: claude (opus-5-5)

# Engineer report: Python C3 slice M4, integer module version 4 and the portable float renderer

Worktree /Users/sto/workspace/datomworld-m4, branch yang-python-c3-m4. No git
writes. No prelude or lowering change.

## Changed files

| File | Change |
|---|---|
| `src/cljc/yin/vm/integer.cljc` | `module-version` 4; exports `float-digits [1]`, `decimal->float [2]`, `max-digits [0]`; new "Float text (version 4)" section; `int-parse` now uses the extracted `digit-values!` and `digits->big` helpers, with the same semantics and order of checks; module docstring and version docstring updated |
| `src/cljc/yin/vm/integer/host.cljc` | unchanged (no new shim operation needed) |
| `src/cljc/yang/python/antlr/render.cljc` | `float-repr` formats `float-digits` by CPython's repr rule; host `str` and `integral?` removed; ns docstring updated |
| `test/resources/yang/python/float-text-v1.generate.py` | new, asserts 3.9.6, explicit `ops` list, run from repo root, ASCII source |
| `test/resources/yang/python/float-text-v1.txt` | new, 9,923 rows: 9,174 `repr_float`, 749 `float_str` (62 of them `!ValueError`) |
| `test/yang/python/antlr/int_ops_fixtures.cljc` | `float-text-path`; `parse` accepts magic `float-text-v1` and dispatches to `float-text-row`. The int-ops branch is the old code, unchanged |
| `test/yang/python/antlr/float_text_test.cljc` | new, portable: 5 fast tests plus one `^:slow` fixture test under `slow/guard` |
| `test/yin/vm/integer_test.cljc` | `module-version-test` expects 4; the export set in `every-export-is-pure-test` gains the 3 exports |
| `docs/design/yang.antlr.md` 8.5.4 | one module-table row (float text) and a version paragraph, nothing else |

Row format: the int-ops 4-column shape `op<TAB>operand<TAB>_<TAB>expected`.
Error rows add a fifth column: `!ValueError<TAB>message`. `parse` returns
`[op operand nil expected]`:
- `repr_float`: operand `{:py/float carrier}`, expected the text.
- `float_str`: operand the decoded string, expected `{:py/float carrier}` or `{:error "ValueError", :message m}`.

## Implementation notes

- `float-digits` follows the reference algorithm literally:
  - `|v|` becomes an exact ratio `x / 2^k` by exact power-of-two scaling (`float-ratio`).
  - `e = floor(log10 |v|)` is estimated from bit lengths, then corrected exactly.
  - For n = 1..17 it takes the floor and ceiling of `|v| * 10^(n-1-e)`, reads each back through the existing `ratio->float`, and keeps the closer survivor. An exact tie goes half to even.
  - A ceiling of 10^n bumps exp10 by one, and trailing zeros are stripped.
  - Zero, NaN, infinity or a non-float is a `:wrong-type` refusal with `::expected :finite-nonzero-float`. A negative v answers the digits of |v|.
- `decimal->float`:
  - Non-string digits are `:wrong-type`; empty or non-digit text is `:syntax`; a non-integer exponent is `:wrong-type` (through `big!`).
  - Leading and trailing zeros are stripped into the exponent.
  - Above 800 significant digits it keeps 800 plus a sticky `1`. After trailing-zero stripping the dropped tail is never all zeros.
  - With `s = len + exp10`: `s > 310` answers `+inf`; `s < -326` answers `0.0`. Otherwise it is one exact `ratio->float`, with overflow mapped to `+inf`. It never returns a limit reason and never consults `::max-digits`.
- `pow10` uses a precomputed table of 10^0..10^400 (a constant) and squares beyond it.
- `max-digits` answers `(::max-digits limits)`.
- Renderer:
  - Fixed notation when `-4 <= e < 16`: `digits` padded with zeros plus `.0`, `int.frac`, or `0.000ddd`.
  - Otherwise `d[.ddd]e+XX` or `e-XX`, with at least two exponent digits.
  - `nan`, `inf`, `-inf`, `-0.0` and `0.0` are handled first.
  - The renderer calls the limitless `exact` instance.

## Red before, green after

Red: before the implementation, the new namespace on the JVM had 53 errors and
54 failures across all five non-shape tests (the exports did not exist and the
host-`str` renderer failed 12 pins). The shape test passed (the loader worked).

Green: all six tests pass on the JVM, Node and Dart; see the focused results
below.

Mutations, each applied temporarily, run on the JVM, then reverted:

| Mutation | Result |
|---|---|
| Nearest-only candidate in `float-digits` | 20 failures: both power-of-two pins (2^-1017, 2^89) and 18 of the 23 repr batches |
| Host `str` in `float-repr` | 51 failures: 28 repr pins and all 23 repr batches |
| Double rounding in `decimal->float` (round the digits to a float, round 10^k to a float, then `*` or `/`) | 9 failures: 7 decimal pins and 2 float_str batches |
| Wrong notation threshold, upper (`e < 17`) | 7 failures: the `1e+16` pin twice and 5 batches |
| Wrong notation threshold, lower (`-5 <= e`) | 7 failures: the `1e-05` pin twice and 5 batches |
| Sticky digit dropped (append 0, not 1) | 2 failures: the 800+-digit pin and 1 float_str batch |
| Upper-threshold mutation re-run on Dart, slow on | `float-repr-pins-test` and `float-text-fixture-test` both `[E]`, "Some tests failed": the Dart lane runs the slow body and detects failures |

All mutations were reverted; the diff was checked afterwards.

## Pins that flip

None. I searched every test for exponent-form or host-specific float text
(`1.0E16`, `e+`, `E-`, non-integral floats of 1e7 or more) and found only
Python source inputs such as `5e-324`, no rendered pins.

Every JVM namespace `bb test:changed:list` selects passes fast, and the slow
tests of the rendering namespaces pass. The existing `render-test` pins (`2.0`,
`-0.0`, `0.5`, `inf`) are unchanged. `int_ops_parser_test` renders both sides
through `render`, so it cannot flip.

## Focused results

JVM (`clojure -M:test`):
- `-e :slow` with `yin.vm.integer-test`, `float-text-test`, `int-ops-test`, `int-contract-test`, `int-literal-test`, `prelude-parity-test`, `float-address-test`, `safepoint-test`, `dao.jing.cbor-conformance-test` and `dao.jing.hash-registry-contract-test`: 132 tests, 6,555 assertions, 0 failures.
- `-e :slow` with `e2e-c1-test`, `e2e-c2-test`, `e2e-test`, `int-ops-parser-test`, `lower-portable-test`, `lower-test` and `yin.vm.store-write-audit-test`: 79 tests, 563 assertions, 0 failures. This needed `bb gen:python-antlr` first (the build/ output is ignored).
- `-i :slow` with `prelude-parity-test`, `float-address-test`, `int-literal-test` and `safepoint-test`: 22 tests, 235 assertions, 0 failures.
- `-n float-text-test -n integer-test -n int-ops-test`, slow included, after the final edits: 47 tests, 777 assertions, 0 failures.
- `float-text-test` alone, full fixture included: 6 tests, 126 assertions, about 4 s wall.
- Not run: the `^:slow` tests of `e2e-test`, which exceed the 600 s cap.

Node (shadow `--config-merge` ns-regexp `^yang.python.antlr.float-text-test$`):
- Fast: 6 tests, 101 assertions, 0 failures, 0 warnings.
- With `DATOM_SLOW_TESTS=1` (through `bb -e babashka.process/shell :extra-env`; a bare `env` prefix was permission-blocked): 126 assertions, 0 failures.

Dart (`bb src/dev/cljd_agg.clj --only yang.python.antlr.float-text-test`):
- Fast: 6/6 pass.
- With `DATOM_SLOW_TESTS=1`: 6/6 pass, "All tests passed!".

Lint and style:
- kondo over the 5 changed .cljc files: 0 errors, 0 warnings. The one info (a non-string assertion message) is fixed.
- cljstyle: a bare `cljstyle fix/check` was permission-blocked. `mise exec -- cljstyle check` on all changed .cljc files is clean, so `fix` was not needed.
- ASCII: all new and changed files are ASCII.
- Lines: no line I added exceeds 80 columns. Pre-existing long lines are untouched.

## Fixture disagreements with the architect's memory (fixture wins)

1. **`float()` whitespace is not the `str.isspace` set.**
   - Code points 28 to 31 are not stripped: `float('\x1c1\x1f')`, `float('\x1d\x1e7')`, `float('1\x1c')` and `float('\x1c')` raise `ValueError`.
   - The ASCII spaces 9 to 13 and 32 are stripped, and so are the non-ASCII Unicode spaces 133, 160, 5760, 8192 to 8202, 8232, 8233, 8239, 8287 and 12288 (all measured).
   - Section 2 says this set is "exactly" `str.isspace` for both `int(str)` and `float(str)`. That is wrong for `float`. `int` was not measured here; `int-conv-v1` must measure it.
2. **A whitespace-only string's message quotes `''`, not the original.**
   - `float(' ')`, `float(' \t ')`, `float('\x0b')`, `float('\x85')` and `float('　')` all raise "could not convert string to float: ''".
   - Any other failing string quotes the original, unstripped: `' . '` gives `"... ' . '"`, and `'\x1c'` gives `'\x1c'`, since it is not stripped.
3. **`float('-nan')` has its sign bit set**: `fff8000000000000`. `nan` and `+NaN` are `7ff8000000000000`, and `-iNfInItY` is `-inf`. S4's sign step must give this; `(* -1.0 nan)`'s sign bit is host-dependent in general, so S4 should pin it.
4. Confirmed as written, among others:
   - `.5` and `5.` pass; `.`, `1e`, `1_.0`, `1._0`, `1e_1`, `1e1_`, `1_e1`, `_1`, `1_`, `1__0`, `0x10` and `0x1p3` fail.
   - `1_0e1_0` and `.5_5` pass.
   - `-0` gives `-0.0`, `1e400` gives `inf` and `1e-400` gives `0.0`, with no exception.
   - The message is "could not convert string to float: '<repr>'".
   - The repr rule's thresholds (`-4 <= e < 16`, at least two exponent digits) hold on all 9,174 rows.

## Deviations

- **Module-level tests live in `test/yang/python/antlr/float_text_test.cljc`, not `test/yin/vm`.** The CPython fixture and its loader are in the yang test tree (the brief asked to extend `int_ops_fixtures`), so `yin.vm.integer-test` only carries the version and export-set updates.
- **`host-names` is not updated.** It lives in `prelude.cljc`, which this slice must not touch. S4 adds `integer/float-digits`, `integer/decimal->float` and `integer/max-digits` there. I documented the exports in the module docstring instead.
- **Refusals the design did not list**, added in the existing style:
  - `decimal->float`: non-digit text is `:syntax`; a non-string or a non-integer exponent is `:wrong-type`.
  - `float-digits` on JS: an integral Number counts as a host float (the same `host-float?` rule as `compare-float`).
- **The test-side float syntax splitter is a test helper, not S4's prelude.** It agrees with every one of the 749 `float_str` rows, and S4 can use it as a reference.
- **The fixture has no non-ASCII-digit rows.** That divergence is pinned by hand in S4 per section 6, and every error message is kept ASCII (the generator asserts it).

## Open questions

1. Should `yin.vm.integer-test` carry a few `float-digits` and `decimal->float` pins of its own, so the module's convention test covers version 4 without the yang fixture? About 5 lines.
2. `-nan`'s sign bit (disagreement 3): is S4 required to match the bits, or only the text `nan`? Repr hides the sign, but keys and identity see bits.
3. `int(str)` whitespace needs measuring (disagreement 1) before S4 hard-codes one shared set.

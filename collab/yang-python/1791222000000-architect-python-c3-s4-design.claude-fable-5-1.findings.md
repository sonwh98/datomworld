Completed-GMT: 2026-10-06 14:07:00 GMT
Completed-Local: 2026-10-06 21:07:00 +07

# Architect design: Python C3 slice S4, conversions and the text boundary (fable-5-1)

Read-only: nothing edited, no suites run. CPython behaviour below is from memory of 3.9.6, not measured; every message and boundary is binding only once the S4 fixture confirms it (the fixture wins on any disagreement). Plan mode was active; this response is the deliverable and I wrote no plan file.

## Verdicts

1. **Two dispatches, not one.** M4 (integer module version 4 plus the renderer, no prelude edit) and S4 (prelude, tests, docs). Float text cannot be done honestly without M4.
2. **`int`, `float`, `str`, `repr`, `bool` are builtin function objects**, like `list`, `tuple`, `set` today. No builtin type classes in C3, so `isinstance(x, int)` stays unsupported.
3. **Float text becomes portable in S4.** This amends ruling 13 for `repr`/`str`/`print` of floats. The current renderer uses host `str`, which is wrong on every host outside a narrow range, and a guest `str(float)` built on it would put host-dependent text into a guest value.
4. **`print` stays a data snapshot.** It gains a guest check that raises the digit-limit `ValueError` before anything is appended to `py.rt/out`. It does not build guest text.
5. **S4 adds 11 builtins:** `int float str repr bool abs pow hex oct bin round`. `min`, `max`, `format`, and `round(float, n)` are later.
6. **Deferred ledger:** fix in S4: a, b, c, d, f, g, h, i, k. Accepted divergence: e. Later (S7): j.
7. **Linked prelude does not go before S6 or S7.** Order: M4, S4 (S6 alongside), S7, then P1 and P2.

## 1. M4: integer module version 4

Four additions; every version 3 semantic and every S0 pin is unchanged. All are `:pure`, written once over the shim, with no host parser or host float printer on any path.

| Export | Contract |
|---|---|
| `float-digits [1]` | Finite nonzero double to `[digits exp10]`: `digits` is the shortest decimal string (no leading or trailing zero) that reads back to the same double, closest to the true value among the shortest; `|v| = d1.d2...dn x 10^exp10`. Zero, NaN and infinity are a `:wrong-type` refusal (caller excludes them, as with `compare-float`). |
| `decimal->float [2]` | `(digits-string, exp10)` to the nearest double (ties to even) of `digits x 10^exp10`, non-negative. Answers `+inf` past the range and `0.0` on underflow; never a limit reason. `digits` is one or more ASCII digits; `exp10` is any exact integer. |
| `max-digits [0]` | The composition's digit limit, native. |
| (host-names only) `bit-length` | Already exported; not needed by S4, do not add. |

**`float-digits` reference algorithm** (simple and checkable; any faster one must equal it on the fixture):
- For n = 1 to 17: form the two n-digit decimals bracketing `|v|` (floor and ceiling, exact big arithmetic), convert each back with the existing `ratio->float`, and keep those that equal `v`.
- The first n with a survivor is the length. If both survive, take the closer; an exact tie rounds half to even (unreachable for doubles, but defined).
- Checking only the nearest candidate is wrong at power-of-two boundaries, where the rounding interval is asymmetric. That is a required mutation.

**`decimal->float` internals:**
- Strip leading zeros; empty means `0.0`.
- More than 800 significant digits: keep 800, append `1` if any dropped digit is nonzero, else `0`, and adjust the exponent. A double's midpoint has at most 767 significant digits, so this preserves correct rounding.
- With s = digit count + exponent: s > 310 answers `+inf`; s < -326 answers `0.0`. Otherwise `ratio->float` of the exact ratio, mapping its overflow to `+inf`.
- It takes text, not an integer, so `::max-digits` never applies. CPython's `float()` has no digit limit in any version.

**Renderer (`render.cljc`), in M4:**
- `float-repr` drops host `str`. It formats `float-digits` output by CPython's `repr` rule: fixed notation when `-4 <= exp10 < 16`, with `.0` appended when there is no fraction; otherwise `d[.ddd]e+XX` or `e-XX` with at least two exponent digits. `nan`, `inf`, `-inf`, `-0.0`, `0.0` as today.
- Expected flips: any existing test that pinned host text for a float at or above 1e16, below 1e-4, or a non-integral float at or above 1e7 on the JVM (`1.23456785E7`) or below 1e-3. These flips are intended; the engineer lists each.
- The limitless `exact` instance stays for integers: values and exception args rendered after the run cannot raise.

**M4 tests:** a CPython-measured `float-text-v1.txt` (section 5), module-level, on all three hosts. No prelude edit, so no address golden moves.

## 2. S4 guest conversions

New prelude helpers (all pure lambdas in `core-definitions`): `py/type-name`, `py/space?`, `py/str-repr`, `py/float-repr`, `py/same?`, plus one `py/<builtin>` per builtin. Host names added: `integer/float-digits`, `integer/decimal->float`, `integer/max-digits`, `data/substring`.

**Shared rules.**
- Whitespace stripped by `int(str)` and `float(str)` is Python's `str.isspace` set, exactly: code points 9 to 13, 28 to 32, 133, 160, 5760, 8192 to 8202, 8232, 8233, 8239, 8287, 12288.
- Digits are ASCII only. CPython also accepts other Unicode decimal digits; here they are a `ValueError`. Accepted divergence, section 4.5's ASCII policy.
- Syntax is validated in the prelude before any kernel call; a kernel `:syntax` or `:out-of-range` refusal stays a prelude defect.
- Error messages quote the original, unstripped string through `py/str-repr`, which is the guest port of `render/string-repr` (same escapes, same quote choice).
- `py/type-name` gives `'NoneType'`, `'int'`, `'bool'`, `'float'`, `'str'`, `'list'`, `'tuple'`, `'dict'`, `'set'`, `'range'`, `'function'`, `'method'`, `'generator'`, `'type'`, or the instance's class name.

**`int(x=0, base=missing)`**

| Input | Result | Via |
|---|---|---|
| no argument | `0` | none |
| int, bool | the int | `py/num` |
| finite float | exact truncation | `integer/from-float`, wrapped (`MemoryError` under a small bit limit) |
| `inf`, `-inf` | `OverflowError` "cannot convert float infinity to integer" | prelude check before the call |
| NaN | `ValueError` "cannot convert float NaN to integer" | prelude check |
| str | see below | `integer/parse`, wrapped |
| anything else | `TypeError` "int() argument must be a string, a bytes-like object or a number, not 'T'" | none |
| non-str with a base | `TypeError` "int() can't convert non-string with explicit base" | none |
| base not an int | `TypeError` "'T' object cannot be interpreted as an integer" | none |
| base not 0 and not 2 to 36 | `ValueError` "int() base must be >= 2 and <= 36, or 0" | checked through `integer/compare`, never narrowed |

String syntax, after stripping:
- One optional `+` or `-`.
- Base 16, 8, 2: an optional matching `0x`, `0o`, `0b` prefix, either case. Base 0: the prefix selects the base; with no prefix the base is 10 and a nonzero value with a leading zero is invalid (`"010"` fails; `"0"`, `"00"`, `"0_0"` pass).
- Single underscores only between digits, or directly after a prefix (`"0x_f"` passes; `"_1"`, `"1_"`, `"1__2"` fail).
- At least one digit, each below the base, letters in either case.
- Failure: `ValueError` "invalid literal for int() with base B: '<repr>'", with B as given (0 for base 0, 10 by default).
- The cleaned text (sign, digits, no prefix or underscores) goes to `(integer/parse text base)`. A decimal string over the limit raises the digit-limit `ValueError`; power-of-two bases are exempt. Syntax errors win over the limit.

**`float(x=0.0)`**

| Input | Result | Via |
|---|---|---|
| no argument | `0.0` | none |
| float | itself | none |
| int, bool | nearest, ties to even; `OverflowError` "int too large to convert to float" | existing `py/as-float` |
| str | see below | `integer/decimal->float` |
| anything else | `TypeError` "float() argument must be a string or a number, not 'T'" | none |

String syntax, after stripping: optional sign, then `inf`, `infinity` or `nan` in any case, or a decimal number: digits with single interior underscores, optional `.`, optional exponent `e` or `E` with optional sign and digits. `".5"` and `"5."` pass; `"."`, `"1e"`, `"1_.0"`, `"1._0"`, and hex floats fail.
- Failure: `ValueError` "could not convert string to float: '<repr>'".
- Digits are integer part plus fraction; `exp10` is the exponent minus the fraction length. An exponent with more than 18 significant digits saturates to plus or minus 10^18 in the prelude, so it never meets `integer/parse`'s digit limit.
- The sign is applied with `(* (data/float-value -1) x)`, so `float("-0")` is `-0.0`. `float("1e400")` is `inf` and `float("1e-400")` is `0.0`, with no exception.

**`str(x="")` and `repr(x)`**

| Input | Text |
|---|---|
| int | `(py/int-result (integer/format n))`: exact decimal, digit-limit `ValueError` |
| bool | `True`, `False` |
| None | `None` |
| float | `py/float-repr` over `integer/float-digits`: same rule as the renderer |
| str | itself for `str`; `py/str-repr` for `repr` |
| container, function, class, instance, range | `NotImplementedError` "str() of 'T' is not supported" |

- Container text in the guest is a port of the whole renderer and belongs with builtin type objects after C3. Refusing is honest; a partial port is not.
- `render/float-repr` and `py/float-repr` are two implementations of one 15-line rule. A parity law over the whole float fixture binds them.

**Digit-limit message.** `py/int-result` builds "Exceeds the limit (N digits) for integer string conversion" from `(integer/format (integer/max-digits))`, for both directions. CPython 3.9.6 has no limit; this follows 3.11's `str` wording, and 3.11's longer `int(str)` suffix is deliberately not copied. N must come from the export, never be baked into a prelude row.

**`print`.** `py/print` first walks its arguments in the shapes `py/snapshot` walks and calls `(py/int-result (integer/format n))` on every integer, discarding the text, and only then snapshots. A breach raises the catchable `ValueError` with nothing appended to `py.rt/out`. Every integer is checked, including small ones, because the frozen `small` profile has a 5-digit limit; the engineer may skip native-safe integers only when `integer/max-digits` is at least 16. `py/snapshot-exc` is not checked: it runs after the handler stack is gone.

## 3. Builtins

**Today (14):** `len isinstance hash divmod print range list tuple set sum any all next iter`, all in `builtin-function-definitions` with names in `builtin-names`. `len(range)` exists with `OverflowError` above 2^63-1. `py/abs` and `py/pow` exist only as operator helpers. There are no `int`, `float`, `str`, `repr`, `bool`, `hex`, `oct`, `bin`, `format`, `round`, `min`, `max`.

**S4 adds:**

| Builtin | Rule |
|---|---|
| `int float str repr` | section 2 |
| `bool(x=False)` | `py/truthy` |
| `abs(x)` | int: `integer/neg` when negative, wrapped; bool gives int; float: `(if (< x 0) (* -1.0 x) (+ x 0.0))`, so `abs(-0.0)` is `0.0` (the existing `py/float-abs` returns `-0.0` and stays internal). Else `TypeError` "bad operand type for abs(): 'T'" |
| `pow(a, b, mod=None)` | `py/pow`; a non-None `mod` is `NotImplementedError` (ruling 10 defers it) |
| `hex oct bin` | `integer/format` in 16, 8, 2 on the magnitude, with `-` then `0x`/`0o`/`0b`; bools accepted; no digit limit. Non-int: `TypeError` "'T' object cannot be interpreted as an integer" |
| `round(x, ndigits=None)` | float, one argument: exact half-to-even through `integer/from-float` and the exact remainder; infinity and NaN raise as `int()` does. int with `ndigits >= 0`: the int. int with negative `ndigits`: half-to-even through `integer/pow` and `floor-div-mod`. float with non-None `ndigits`: `NotImplementedError` |

- `hex`, `oct`, `bin` are in S4: they are three lines over a kernel that already exists and they are the only source-level exercise of non-decimal `format`.
- `round(float, n)` and `format` are later: both need correctly rounded fixed-precision decimal output, a different kernel, and `format` needs the format-spec language. Neither is a C3 deliverable.
- `min` and `max` are later: they need general ordering for str, tuple and list, which `py/compare` does not have.
- `int(x=...)` by keyword is accepted if the function spec has no positional-only form; CPython 3.9.6 refuses it. Minor, recorded.

## 4. Deferred-item ledger

| Item | Ruling | What changes |
|---|---|---|
| (a) float `**` guard band | Fix in S4 | Replace the 1024-bit shift test with `(py/int-result (integer/to-float e))`, discarding the value. It overflows exactly where CPython's conversion does, at `|e| >= 2^1024 - 2^970`, with the right message, and needs no big literal. Update `float-power-exponent-limit-test` to that boundary. |
| (b) int base, huge negative exponent | Fix in S4 | CPython converts both operands to float on a negative exponent. Apply the same guard whenever the result is a float (floaty, or `e < 0`), base first, then exponent, both before the zero-base check. `0 ** -(2**1024)` and `2 ** -(2**1024)` become `OverflowError`. |
| (c) `2 ** -1074` gives `0.0` | Fix in S4; amends ruling 10 | Keep `1 / fpow(x, n)` whenever `fpow` is finite, so every existing pin holds (`10 ** -2` stays `0.01`). Only when it overflows: `(1 / fpow(x, h)) * (1 / fpow(x, n - h))` with `h = n >> 1`. `2 ** -1074` is then exactly `5e-324` and `2 ** -1075` is `0.0`. Contract unchanged: bit-identical across hosts, last-place differences from CPython allowed off the exact cases. Reciprocal-base was rejected: it breaks `10 ** -2`. |
| (d) float `**` overflow returns `inf` | Fix in S4 | A non-finite result from finite operands raises `OverflowError` with args `(34, 'Result too large')`, built as a two-element args tuple. That text is the macOS errno string, as the S0 contract was measured; Linux differs, and the fixture is the authority. Infinite bases pass through. In the same edit, a float exponent no longer goes through `py/floor` (which raises a wrong "float floor outside the supported range" at 2^53): an integral float exponent of any size uses `integer/from-float`, and infinite and NaN exponents follow CPython's table (`1.0 ** nan` is `1.0`, `x ** nan` is NaN, `x ** inf` by `|x|` against 1). Fractional exponents stay `NotImplementedError`. |
| (e) huge left shift class | Accepted divergence | `MemoryError` at every magnitude past the bit limit. CPython's switch to `OverflowError` depends on the platform's `Py_ssize_t`; the profile's bit limit is the allocation model. No change. |
| (f) float `ZeroDivisionError` messages | Fix in S4 | Per-operator float messages, text taken from the fixture's measured rows, not from this document. I recall `//` on 3.9.6 saying "float divmod()" (it calls `float_divmod`), while the S3-B gate recorded "float floor division by zero"; the fixture settles it. Integer messages are already exact. |
| (g) `py/exc-matches` message | Fix in S4 | `{:py/str (data/str-concat ...)}` is an unevaluated literal; make it `(py/str (data/str-concat ...))`. Add a portable lint test: no `{:py/str x}` literal in the definitions with a non-string `x`. |
| (h) NaN identity shortcut | Fix in S4 | `py/same?` is `(if (py/eq a b) true (if (py/float? a) (py/is a b) false))`. Use it in `py/seq-contains?` and `py/eq-items`. `x in [x]` and `[x] == [x]` are then True for a NaN, as in CPython. Under content identity, two separately made NaNs also match where CPython says False: the one-NaN departure, same family as keys and `is`; add it to that doc paragraph. |
| (i) range and index NaN | Fix in S4 | `float in range(...)` currently materializes the range. Rule: a NaN, an infinity or a non-integral float answers False; an integral float answers `py/range-has?` of `integer/from-float`. Same answers as CPython's linear search, in constant time. A float as an index is already `TypeError`. |
| (j) `'a' * (2**53-1)` grinding | Later: S7 | A prompt `MemoryError` needs a sequence-size limit, and ruling 11 forbids an implicit one. S7's resource-limit fixtures own it, with an explicit profile datum decided there. S4 must not add a constant. |
| (k) float printing coverage | Fix in M4 and S4 | Section 1. Amends ruling 13: parity is claimed for `repr`, `str` and `print` of every double; `format`, `%`, and `round(x, n)` remain unclaimed. |

## 5. Performance and the linked prelude

**Recommendation: do not sequence P2 before S6 or S7.**
- P2 needs L-a, which belongs to the linker seat. Putting it first would put C3's completion behind C4.
- Ruling 14's completion criteria are defined over the bundled profile.
- P2's own acceptance is "the linked corpus output equals bundled". The S7 corpus is that oracle, so S7 first is the correct dependency direction.
- S7's goldens are value bytes and hashes, not code addresses, so P1 and P2 do not re-mint them.
- S6 touches no prelude code; run it alongside S4.

**Order:** M4, then S4 with S6 alongside, then S7, then P1, then P2 after L-a.

**Until then:** keep amortizing compile cost by batching (one guest program per batch of rows, as the int-ops corpora do). One further saving is possible but I have not verified it: if the AST walker's `vm/eval` can continue from a VM value that already loaded the prelude, test helpers could cache that VM per composition. The image VMs cannot do this before P2.

**S4 must not:**
- Add module-level runtime state. No new `py.rt/*` cell; new definitions are pure lambdas, plus builtin function objects in the existing table that P1 already has to migrate.
- Add builtin classes for `int`, `float`, `str`. Class cells are what a linked install must not copy; type objects are designed with P1's builtins dict.
- Add a host module, or a Python-specific host function. Only new exports of `integer` and existing `data` exports.
- Bake a limit or any composition datum into a prelude row. Rows stay profile-agnostic; limits arrive through `integer/max-digits`.
- Emit `integer/*`, `data/*` or new `py.b/*` reads from the lowering. S4 needs no lowering change beyond the `builtin-names` entries, which P1 deletes.
- Pass more host exports as first-class values (the `py/int-op` pattern). Call them directly; L-a has to resolve each qualified host read.
- Leave the module at version 3 with a planned version 5. M4 should be the last integer version bump before L-a pins profile identity.

## 6. Tests

**CPython-measured fixtures** (generator committed beside each, asserting `sys.version` is 3.9.6, explicit op list, written from the repo root):

| Fixture | Ops | Used by |
|---|---|---|
| `float-text-v1.txt` | `repr_float` (bits to text), `float_str` (text to bits or `!Class\tmessage`) | M4 at module level; S4 at guest level; the renderer parity law |
| `int-conv-v1.txt` | `int_str` (text, base), `int_float`, `float_int`, `str_int`, `hex`, `oct`, `bin`, `round1`, `round_int`, `abs`, `pow_float`, `zdiv` | S4 |

- Error rows carry class and message. The int-ops corpora carry class only, so (f) moves none of them.
- Floats are bit patterns (`f:` plus 16 hex digits), read with `cbor/float64-from-bits` as `int_ops_fixtures/value` does. Never a decimal float literal in a row.
- String operands are comma-separated hex code points, so whitespace, quotes and tabs survive the tab-separated format on every host.
- Extend `int_ops_fixtures/parse` rather than writing a second loader.
- Float rows must include: powers of two and ten across the range, both sides of every binade boundary sampled, `5e-324`, the largest subnormal, the smallest normal, the largest double, `1e22`, `1e23`, `0.1`, `0.3`, `1/3`, `9007199254740993.0`, the notation thresholds (`1e16`, `9999999999999998.0`, `0.0001`, `0.00001`), and for `float_str`: halfway cases, an 800-plus-digit significand, `1e400`, `1e-400`, signed zero, underscores, every malformed shape in section 2.
- `pow_float` rows cover (a) to (d): both sides of `2^1024 - 2^970`, `0 ** -(2**1024)`, `2 ** -1074`, `2.0 ** 1024`, `0.5 ** -2000`, infinite and NaN exponents.
- Batches of at most 400 rows per guest program for Dart; each corpus test is `^:slow` under `slow/guard`. A fast lane of about 40 hand-picked pins runs everywhere.

**Not from CPython 3.9.6, pinned by hand and labelled so:**
- The digit limit: `str`, `repr`, `print`, `int(str)` at N and N+1 digits under 4300 and under `small`; hex text past the limit succeeding; the message with N.
- `MemoryError` rows (`int(1e300)` under `small`).
- The NaN departure rows for (h), and the ASCII-digit divergence.
- (c)'s non-exact subnormal results: pinned bit-identical across hosts, no CPython claim.

**Other required tests:**
- `print` atomicity: a breach inside a nested list leaves `py.rt/out` unchanged and is caught by `except ValueError`.
- Renderer parity law: `render/float-repr` equals guest `repr` on every `float-text-v1` row, on each host.
- Parser-level (JVM): source programs through `int_ops_parser_test`, including a shadowed builtin name.
- Mutations, each shown red once: host parse for `int(str)`; `to-float` then scale for `float(str)` (double rounding); nearest-only candidate in `float-digits`; host `str` in `float-repr`; no check in `print`; guard back at `2^1024`; `py/eq` alone in `seq-contains?`; `abs` through `py/float-abs`.

**Portability traps to put in the brief:**
- No `0.0` or `1.0` literal in the prelude or in expected values: `(data/float-value 0)`, `with-float64`. Float literals such as `1.0E20` are integers on JS; build them from bits.
- Integer literals at or beyond 2^53 in tests go through `exact-literals`, never bare.
- `#?(:cljd ... :clj ...)` with `:cljd` first.
- No `for` or `doseq` over more than 32 elements on ClojureDart; use `mapv`, `keep`, `reduce`, and push row iteration into the guest.
- Negate floats with `(* -1.0 x)`, never one-argument `-`.
- Integral doubles on Dart: new code uses `integer/from-float`, not `py/floor`; comparisons use `canon-ints`.
- Compare tagged floats through `host-floats`. Make it public in `int_ops_test` and put S4's tests in a new `int_conv_test.cljc` that requires it, with `run-with-prelude`, `check-cases` and `caught`; do not copy the helpers and do not reach them with `#'`.
- Never host `Double/parseDouble`, `parseFloat`, `double.parse`, `Double/toString`, or `bigint` of a double.
- Guest text work is over `data/str->code-points`; the host renderer works in code units. Both pass astral characters through unchanged; pin one row.
- Start maps from `{}` before multi-key `assoc`; no `[_ _]` protocol params.

## 7. Slicing, order, re-mints

1. **M4** (one short round). Files: `yin/vm/integer.cljc`, `render.cljc`, the module tests, `float-text-v1` and its generator. Re-mints: none. Flips: existing printed-float expectations outside the old range, listed in the report.
2. **S4** (one round, after M4 lands). Files: `prelude.cljc` (helpers, 11 builtins, `builtin-names`, `host-names`, ledger fixes a to d and f to i, the `py/int-result` message, docstrings), `int_conv_test.cljc`, `int-conv-v1`, parser tests, and the doc. Re-mints once: `float_address_test`, `safepoint_test`, `lower_test`, `lower_portable_test`, and any cell-count pin moved by 11 new function cells. Changed pins: `float-power-exponent-limit-test`, the digit-limit message, and any float zero-division message pin.
3. **S6** alongside S4, as already ruled.
4. **S7.**

- Iterate on `bb test:clj`; the full three-host lanes once per round at landing. S0 and `int-ops-v1` to `v3` are never regenerated.
- If one round is mandatory, M4 and S4 can be one dispatch with M4's tests green before any prelude edit. I recommend two: M4 has no golden churn and gates cleanly on its own.

**Doc edits (S4 engineer):** 8.5.4's S4 row and slice table; the ruling 10 paragraph (items a to d); the ruling 13 sentence; the conversions bullet list (which builtins exist, the scalar-only `str`); the one-NaN paragraph (item h); the module table and `integer.cljc` docstring (version 4); the `render.cljc` and prelude docstrings.

**What S6 and S7 still need from S4:**
- S6: nothing.
- S7: source-level `int`, `float`, `str`, `repr` so the conversion detectors of ruling 14 can be written as programs; the digit-limit `ValueError` reachable from source for the resource-limit fixtures; version 4 host names, so a version 3 composition is refused by name in the profile-mismatch fixture; S4's mutation list, to fold into ruling 14's evidence.
- S7 also inherits (j) and must rule on the sequence-size limit before writing its fixture.

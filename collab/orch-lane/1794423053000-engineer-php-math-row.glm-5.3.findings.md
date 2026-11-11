Created-GMT: 2026-11-12
Coding-Agent: glm
Model: glm-5.3
Session-ID: 56a14614-1db8-4ba9-a32e-56073390b505
Row: yang.php P9 math builtins (docs/agents/known-gaps.md open P2, closed this round)
Branch: yang-php-math-fixes @ f82afe44 (pushed)
Touched: src/cljc/yang/php/antlr/prelude_p9.cljc, test/yang/php/antlr/programs_p9.cljc,
         test/yang/php/antlr/builtin_surfaces_test.clj, docs/agents/known-gaps.md (this row only)

# Per-item verdicts

Every item was verified against primary sources BEFORE coding: the PHP 8.3
documentation pages (php.net function.abs / function.round / function.min /
function.sqrt) and php-src 8.3 itself (ext/standard/math.c, ext/standard/array.c
via raw.githubusercontent.com/php/php-src/PHP-8.3).

## 1. abs(PHP_INT_MIN) — implemented, with a correction to the row's premise

Verdict: the row's claim "PHP 8.3 throws ValueError ('Argument #1 ($num) is too
large')" is NOT what PHP 8.3 does. The abs() doc page carries no such changelog
entry in any 8.x version, and php-src 8.3 math.c reads:

    if (Z_LVAL_P(value) == ZEND_LONG_MIN) {
        RETURN_DOUBLE(-(double)ZEND_LONG_MIN);

i.e. abs(PHP_INT_MIN) returns **float(9.223372036854776E+18)**, the exact double
2^63. The "overflow detection, not wrapping" instruction stands and is what was
implemented: `php/abs` now negates through `php/int-result`, so every int except
PHP_INT_MIN stays an in-range int and only PHP_INT_MIN crosses to the exact
float (integer/to-float is CPython's correctly-rounded conversion, and 2^63 is
exactly representable). The row's *defect* (negation escaping the integer range)
is closed; the *ValueError* half of its premise was wrong for 8.3 and was not
implemented. Pinned: `:abs-int-min` fixture (`float(9.223372036854776E+18)` for
both `abs(PHP_INT_MIN)` and `abs(PHP_INT_MIN - 1)`), `abs(PHP_INT_MIN) ==
9223372036854775808`, `abs(-5)` stays int, `abs(-4.2)` float. An exactly-1
arity check was added (Z_PARAM_NUMBER's 1..1).

## 2. round — implemented (full _php_math_round port)

Verdict: per docs + math.c. round(num, precision = 0, mode = 1) with the four
8.3 modes (HALF_UP 1, HALF_DOWN 2, HALF_EVEN 3, HALF_ODD 4); the docs' own
examples are pinned as fixtures/contracts (round(5.045, 2) = 5.05,
round(5.055, 2) = 5.06 — these REQUIRE the pre-round step at the
FP-guaranteed decimal digits, which is why a naive multiply-and-floor port
fails them; round(345, -2) = 300; the 9.5/8.5 and ±1.55 mode tables; negative
precision rounds to tens/hundreds). Details matched from math.c:
- `php_round_helper` semantics on both signs: HALF_DOWN steps at an exact half
  toward zero; HALF_EVEN steps when the rounded value is odd; HALF_ODD when it
  is even (verified against docs Example #3/#4 and the RoundingMode table).
- An invalid mode silently rounds HALF_UP in 8.3 (the ValueError is 8.4+ per
  the docs' own changelog: "Prior to PHP 8.4.0, an invalid mode would silently
  default to PHP_ROUND_HALF_UP"); pinned with modes 0 and 99.
- The 1e15 beyond-precision bail-out (round(1.5, 400) = 1.5) and the
  |places| >= 23 snprintf("%15fe%d")/strtod text round-trip (round(2.5e-13, 25)
  = 2.5E-13) are ported; pow10 comes from the engine's exact integers
  (10^n rounded once, matching C pow's correct rounding; past the double range
  it answers infinity, and a 310 clamp bounds the exponent's own size).
- NAN/INF/±0 pass through untouched (round(-0.4) = float(-0) pinned); the
  answer is always a float (var_dump(round(5)) = float(5)).

## 3. min/max — implemented

Verdict: per docs + array.c. A single array argument scans the array
(min([3,1,2]) = 1, max over assoc arrays, max(range(1,5))); with two or more
arguments the variadic scan keeps standard comparison and replaces only on
strictly smaller/larger, so the first of equals wins (max(2, 2.0) = int(2),
max(1, '1') = int(1); docs' mixed-type examples pinned: min(-10, false, 10) =
false, min(0, true) = 0, min('hello', -1) = -1, min('string', [2,5,7], 42) =
42). Error behavior per array.c 8.3, with its exact texts: empty array is
ValueError "min(): Argument #1 ($value) must contain at least one element", a
lone non-array argument is TypeError "min(): Argument #1 ($value) must be of
type array, int given" (reusing php/arg-array), zero arguments is
ArgumentCountError "min() expects at least 1 argument, 0 given".

## 4. sqrt — implemented (exact integer square root; rationale below)

Verdict: the numeric model was read first, as instructed: engine floats ARE
host IEEE-754 doubles (php/f wraps, php/h unwraps; data/float64), so "the host
sqrt" would be right — but no sqrt exists in the prelude's host-names, and a
guest-side converging method cannot be trusted to round correctly (see trap 1).
sqrt therefore computes an EXACT integer square root on the engine's
arbitrary-precision integers (its one advantage over C): scale the operand
into [1,4) by exact powers of four, read the 52-bit mantissa as an exact
integer (xs·2^52 is exact for xs ∈ [1,4)), integer Newton to floor(sqrt(t)),
pick the nearest integer by comparing exact squared distances (ties to even,
IEEE), scale back by an exact power of two. Every step is exact, so the only
rounding is the same single rounding IEEE sqrt makes — correctly rounded over
the whole double range, which is what PHP's C sqrt gives. Pinned:
var_dump(sqrt(2)) = 1.4142135623730951 and sqrt(3) = 1.7320508075688772
(17-digit shortest round-trip — any ulp error would flip these),
sqrt(1e16) == 1e8, sqrt(4e300) == 2e150, sqrt(2^-200) == 2^-100,
sqrt(2^600) == 2^300, sqrt(0.25) = 0.5, sqrt(1e-16) echoes 1.0E-8.

## 5. Negative sqrt — NAN confirmed

Verdict: docs: "The square root of num or the special value NAN for negative
numbers"; math.c is bare RETURN_DOUBLE(sqrt(num)) — C sqrt(-1) is NaN, no
warning, no exception (confirmed: sqrt(-1) echoes NAN). Also handled per C:
sqrt(+INF) = +INF, sqrt(NAN) = NAN, sqrt(±0) keeps its sign.

# The sqrt approach — why exact integers, not a converging loop

The spec offered "the host sqrt or a converging method with a proper epsilon".
There is no host sqrt in the prelude's host-names (the quoted guest language
cannot call Math/sqrt — and it would not be portable to the Dart/JS hosts
anyway), so a guest algorithm was required. A Newton loop with a relative
epsilon (the first thing built here) converges fine but has a fatal rounding
property: its double-precision fixed point rounds ties-to-even, and for
sqrt(2) the fixed point is the LOW double (1.414213562373095, one ulp under
PHP's correctly rounded 1.4142135623730951) — the classic Newton 2-cycle
cannot be fixed from inside double arithmetic without bit-level ulp access,
which the guest lacks. The engine's differentiator is its exact
arbitrary-precision integer layer (yin.vm.integer), so sqrt is instead computed
exactly: powers-of-four scaling, exact mantissa integer, integer Newton,
nearest-integer selection by exact squared-distance comparison (ties to even).
Correct rounding then holds by construction for every finite positive double
(including denormals: their sqrt is always normal, so the scale-back is exact),
matching PHP bit-for-bit rather than to within an epsilon.

# Residuals (disclosed, not fixed — outside this row's four defects)

- PHP_ROUND_* constants are absent from the engine's constant table
  (builtins.cljc, outside this row's allowed files); tests use the literal
  mode ints 1-4, which PHP accepts identically.
- String-operand juggling for these builtins follows the engine's existing
  pre-row behavior (abs/round pass through php/to-float's coercion), not
  Z_PARAM_NUMBER's exact TypeError corners.
- |places| beyond ±310 in round: f1 saturates to infinity like C pow, and the
  deep-precision tail parses its decimal text; PHP's own answers there are
  snprintf/strtod artifacts (e.g. its exponent text overflows int), so only
  the absurd corners (|places| in the billions) can diverge from PHP's
  artifacts — all such inputs round to the operand or 0 in both.
- php/arity-between's message pluralizes "1 arguments" for the shared helper
  (pre-existing engine-wide text; min/max bypass it with PHP's exact singular
  text, others inherit the quirk).

# Traps hit this round

1. Double Newton's fixed-point bias (described above) — one full
   implementation discarded for the exact integer algorithm.
2. integer/to-float answers the ::yin.vm.integer/float-overflow refusal
   keyword (not Inf) for 10^310; php/p9-pow10 now converts it to (php/inf)
   like C pow's overflow. Note php/int-result (prelude.cljc:889) shares this
   exposure for int results past ~1.8e308 — pre-existing, out of row.
3. The untracked orchestrator files (prompt/stdout) and target/ logs left
   alone; antlr generation needed the documented ProcessBuilder scaffolding
   (deleted before commit).

# Evidence

- TDD: tests written first and red (40 contract failures + fixture failures),
  then green after the port; final focused runs all green:
  - yang.php.antlr.builtin-surfaces-test: 4 tests / 225 assertions, 0 failures
  - yang.php.antlr.prelude-test + legacy-corpus-test: 34 tests / 396
    assertions, 0 failures
  - yang.php.antlr.e2e-test/fixtures-on-the-walker-test: 528 assertions green;
    float-text, scalar-comparison-matrix, pow-fractional-refusal: 47 green
  - e2e-test full namespace exceeds the local 10-minute window (killed at
    580s mid-run, no failures up to that point) — suite-scale, remote-only
    per the round rules.
- clj-kondo: 0 errors, 0 warnings over all three touched files; cljstyle
  check: clean (two alignment fixes applied).

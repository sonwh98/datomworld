Completed-GMT: 2026-10-06 08:31:37 GMT
Coding-Agent: codex (gpt-6.1-sol)
Role: Engineer (implementation)

# Python C3 S3-B findings

S3-B is implemented, with focused JVM verification complete. No git write
commands were run. HEAD remains S3-A c61ac6c2, stacked on S2 c66bdd5a.
The integer module, engine and UCF were not edited. Node/Dart landing
lanes remain the orchestrator's checks on the combined stack.

The binding briefs were read from the main checkout's collab directory:
this worktree initially had no collab directory. This report is the only
collab file created or changed.

## Changes

- src/cljc/yang/python/antlr/prelude.cljc:
  integer //, %, and divmod call integer/floor-div-mod through
  py/int-result. Numeric types and integer zero divisors are checked
  before kernels; bools coerce to integers. Mixed arms convert through
  py/as-float before their float zero check, matching CPython's conversion
  order, and never feed a big carrier to data/float-value.
  divmod is a first-class builtin with two positional-only parameters and
  returns a tuple of integers or tagged floats.
  Nonnegative integer powers call integer/pow through py/int-result.
  Negative powers retain the ruled reciprocal-of-fpow path, with exact
  exponent sign/negation and guarded base conversion. fpow halves through
  integer/shift-right and tests parity through integer/bit-and. Integral
  float exponents are reconstructed as canonical integers before either
  kernel sees them; fractional-exponent behavior remains unchanged.
  Integer/integer / calls integer/true-div, prechecking zero and mapping
  a non-finite ratio to OverflowError with CPython's division message.
  py/abs now uses integer/compare and integer/neg. Its raw-double callers
  use a separate py/float-abs, including float key/hash reconstruction.
- test/resources/yang/python/int-ops-v2.generate.py and int-ops-v2.txt:
  3,122 measured CPython 3.9.6 rows, explicit operator lists, bounded
  power grids, int/int true division, both floor/mod signs, divmod tuples,
  negative exponents, big exponents, bools, float mixes, signed zero,
  infinities, NaNs and zero divisors. The generator is beside the fixture.
- test/resources/yang/python/int-ops-v1.generate.py:
  replaced the [:10] slice with explicit lists and stated v1/v2 ownership.
  Regeneration reproduces the frozen v1 fixture byte-for-byte; its 6,650
  rows and checked-in fixture content are unchanged.
- test/yang/python/antlr/int_ops_fixtures.cljc:
  accepts both corpus headers, decodes tuple descriptors, remains
  blank-line-safe, and never writes files.
- test/yang/python/antlr/int_ops_test.cljc:
  fast portable carrier, zero/error-message, bool, type, power-limit,
  mixed conversion, NaN, quotient-size and single-rounding pins. Every
  case runs on all four VMs. Both guarded slow corpora evaluate every row
  in guest loops: v1 uses 400-row programs, v2 uses 100-row programs.
  Neither host outer loop exceeds 32 batches; inner destructuring is over
  four runners. No new integral float literals occur in portable tests.
- test/yang/python/antlr/int_ops_parser_test.clj:
  both corpora run once through the JVM parser in 64-row batches. Added
  divmod first-class/arity/keyword checks and evaluation-once checks for
  //=, %=, **= and /= on an indexed container.
- test/yang/python/antlr/e2e_c1_test.clj:
  restored 3 ** 40 in integer-bound-test. Expected output is unchanged;
  the equivalent product for the pending 1 << 54 pin remains.
- test/yang/python/antlr/prelude_parity_test.cljc:
  restored py/pow 3 40 in the bound test, and compares py/pow 2 53 as exact
  formatted text, avoiding an unsafe native expected carrier on Node.
  Shift pins remain pending and unchanged.
- test/yang/python/antlr/float_address_test.cljc:
  five prelude/program/derivation hashes re-minted once after the final
  prelude implementation. Hook-only hashes did not change. Lower and
  safepoint tests required no edits.

## Float fixes exposed by the new corpus

A NaN divisor could enter py/fmod-pos forever on the JVM: (= y y) can
accept the same boxed NaN. Both float arms now use (<= y y), and their
invalid-input arms construct NaN using both operands, so a finite x with
NaN y does not incorrectly produce zero. Two diagnostic corpus runs were
interrupted at this nontermination; the final full runs complete.

Float // and divmod no longer refuse an already-integral quotient above
2^53. py/float-floor returns those doubles directly, also passing through
non-finite quotients; it uses the old bounded floor algorithm only in its
valid interval. Modulo keeps its remainder-only float path.

These changes are covered by fast portable float-division-edge-test and
by the float grid of the v2 corpus, on all four JVM VMs.

## CPython fixture boundary and binding power semantics

The binding fpow algorithm preserves exact integer exponent parity, even
on its float path. CPython 3.9.6 instead rounds a huge exponent there:

- (-1.0) ** (2**64 + 1): this profile is -1.0, CPython is 1.0.
- (-1) ** (-(2**64 + 1)): this profile is -1.0, CPython is 1.0.
- The analogous negative huge odd exponent on a -1.0 base also differs.

The first v2 run that completed exposed these three known profile deltas
(eight VM assertion failures, since two rows shared a batch). The final
CPython parity corpus excludes those cases explicitly in its generator;
its expected results are all measured, with no fabricated replacements.
Fast portable tests separately pin exact odd parity for positive and
negative big exponents. Huge even exponents and a +1.0 base remain in the
CPython corpus. This follows the binding exact fpow/negative-power rules,
not a new exponent-to-float conversion policy.

Other ruled restrictions remain: reciprocal-of-fpow rounding/underflow,
fractional float exponents unsupported, and bounded integral float
exponent admission. This slice does not claim general CPython pow parity.

## Red before green and deliberate mutations

Tests were added before the prelude implementation.

- target/s3b-red.log: 8 tests, 29 assertions, eight failures. The new exact
  division/power test hit old native long overflow on every VM; the power
  bit-limit test got OverflowError instead of MemoryError on every VM.
- Initial tuple construction used expression-bearing literal vectors,
  which this AST notation does not evaluate. Both the code and test now
  build evaluated tuples/vectors through py/conj. The first corpus decoder
  also incorrectly referenced its own local binding; decode-number is now
  separate. These harness issues were fixed before final corpus runs.
- target/s3b-fast-final.log: 13 tests, 73 assertions, zero failures/errors.
  The later huge (-2) ** big MemoryError pin also passed in the broader
  focused run below.
- target/s3b-mutations-final.log: an unmodified baseline passed on all four
  VMs; six deliberate mutations each failed on all four VMs (24 expected
  failures, zero test errors; 28 assertions including the baseline).

Mutation harness: target/s3b-tests/yang/python/antlr/s3b_mutation_test.clj.
It rebuilds the prelude in memory; source is never mutated on disk.

1. Floor versus truncation: move a negative integer quotient toward zero.
2. Remainder sign: take the absolute value of the integer remainder.
3. divmod tuple order: swap quotient and remainder.
4. Zero-division precheck: bypass py/division-check; kernel refusal becomes
   a host defect rather than the required guest exception.
5. Negative-exponent path: suppress the negative-exponent dispatch.
6. True-div single rounding: use independently rounded operands for small
   convertible inputs while retaining the correct huge/huge path. The
   pinned ratio changes from 0.9999999999999998 to 0.9999999999999996.

Mutation command:

    clojure -Sdeps \
      '{:aliases {:s3b-mutations {:extra-paths ["target/s3b-tests"]}}}' \
      -M:test:s3b-mutations -d target/s3b-tests \
      -n yang.python.antlr.s3b-mutation-test

## Focused JVM results

All suites ran in foreground shell commands, with no overlapping suites.
No full bb test, Node lane, Dart lane or long-loops suite was run.

- clojure -M:test -n yang.python.antlr.int-ops-test -i :slow:
  two tests, 196 assertions, zero failures/errors. Both v1 and v2 pass;
  all 9,772 rows execute on all four VMs.
  Log: target/s3b-operators-slow-final.log.
- clojure -M:test -n yang.python.antlr.int-ops-parser-test -i :slow:
  two tests, 153 assertions, zero failures/errors. Every v1/v2 row executes
  once through the JVM parser.
  Log: target/s3b-parser-slow-final.log.
- Focused fast namespaces with -e :slow: int-ops, e2e, e2e-c1, prelude-parity,
  float-address, lower, lower-portable and safepoint:
  92 tests, 778 assertions, only five stale address failures, zero errors.
  Log: target/s3b-focused-goldens-red.log.
- After the one golden update, float-address with -e :slow:
  ten tests, 83 assertions, zero failures/errors.
  Log: target/s3b-goldens-green.log.
- Seven selected slow regressions: prelude semantics; numeric keys/hash/is;
  e2e-c1 integer bound, float zero/infinity and gate-p3-round2; e2e tagged
  floats and numeric hash. Seven tests, 68 assertions, zero failures/errors.
  Log: target/s3b-regressions-slow-final.log.

Kondo: zero errors and warnings across all seven changed Clojure files.
Cljstyle fix/check: clean across the same files. git diff --check is clean.
All added code and generator lines are ASCII and <= 80 columns; existing
unrelated long lines are untouched. Fixture rows retain their tabular
single-line encoding. Logs: target/s3b-kondo-final.log and
target/s3b-cljstyle-final.log.

bb test:changed:list selects 15 JVM and nine portable namespaces per lane;
15 distinct namespaces overall. Log: target/s3b-selection-final.log.
The required bb gen:python-antlr completed after a network-enabled retry;
the initial sandboxed attempt could not fetch its dependencies.

Fixture regeneration is byte-identical under CPython 3.9.6. SHA-256:

v1: 08fe60aa637cebb7a471b1e444d724df101ad4c48f644e8fa5fe97bfe36cecfc
v2: 4b89179a69235e275986573a22eebd588094569bcaa0a4eb94a42eeb9f4a9e34

## Remaining S3-C work and shared helper ownership

No listed legacy helper became unused after S3-B. They are deliberately
retained for pending operators and their shared consumers:

- py/checked-add: py/bit-op and py/checked-sub.
- py/checked-sub: py/invert.
- py/checked-mul: py/bit-op, py/ipow and py/lshift.
- py/overflow: the checked arithmetic helpers.
- py/bit1 and py/bit-op: &, | and ^, including their pending bool dispatch.
- py/shift-check: << and >>; native comparisons on big counts remain unsafe.
- py/ipow: << and >> only; guest ** no longer calls it.
- py/int-floordiv: py/ipow, py/bit-op and py/rshift.
- py/int-mod: py/ipow and py/bit-op.
- py/divmod-pos: the old division helpers, py/checked-mul, plus the shared
  py/int-of and py/float-parts-down reconstruction paths. Do not delete it
  in C without replacing/moving both shared float reconstruction callers.
- py/int-canon: shared py/key and py/hash consumers still call it; audit
  those normalization sites when retiring the remaining legacy producers.

The pending bitwise/invert/shift operators still contain native arithmetic
and big-carrier hazards. py/abs itself is now safe, addressing gate finding
1, but that does not make its old checked/division callers portable.
Shifts and invert keep their old bounds and pins until C. Gate finding 2
is closed by the explicit lists and actual int/int true-division rows.

No integer-module defect or unresolved S3-B implementation question was
found. Landing still requires the orchestrator's three-host checks on the
combined S2 + S3-A/B/C stack, including the previous long-loops condition.

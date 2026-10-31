# Python C3 S3-A implementation findings

Worktree: /Users/sto/workspace/datomworld-s3a2
Branch: yang-python-c3-s3a2 (stacked on S2, not master).
No git write commands. No edits to integer, engine, UCF, or other collab
files. This requested report is the only new collab file.

## Changed files

- src/cljc/yang/python/antlr/prelude.cljc
- test/yang/python/antlr/int_ops_test.cljc (new)
- test/yang/python/antlr/int_ops_fixtures.cljc (new)
- test/yang/python/antlr/int_ops_parser_test.clj (new)
- test/resources/yang/python/int-ops-v1.generate.py (new)
- test/resources/yang/python/int-ops-v1.txt (new)
- test/yang/python/antlr/prelude_parity_test.cljc
- test/yang/python/antlr/e2e_c1_test.clj
- test/yang/python/antlr/float_address_test.cljc
- docs/design/yang.antlr.md
- This requested report.

## Implementation and audit

Int/bool +, -, * dispatch through add/sub/mul after guest type checks and
bool coercion. Every result is translated. Unary minus uses neg; unary
plus returns the coerced integer directly. List iadd/imul arms are kept.
All old checked helpers and overflow remain because S3-B/C still use them.

int-result takes its cheap data/number? arm first, recognizes the tenth
reason, and maps float-overflow to OverflowError with the exact requested
message. Namespace and translator documentation now name module version 3.

Ordering/equality share num-compare: integer compare, exact compare-float
in either orientation, native float/float ordering, and unordered NaN.
The NaN test uses <= rather than host =, since one boxed NaN can equal
itself on the JVM. Membership and chained comparisons reuse these helpers.
Existing numeric-key/hash semantics are unchanged and tested.

Mixed arithmetic converts integers through to-float before host float
arithmetic. Mixed / converts both operands before checking float zero,
as CPython does. Integer/integer / remains S3-B, with both conversions
safe from host carrier failures; see the precise residual below.

Index rejects against [-n,n) before addition. Accepted indices are small
canonical integers. Getitem and setitem therefore get guest IndexError.
Slice bounds clamp before addition; huge steps cap at +/- (n+1), which
preserves every selected position without constructing an oversized sum.

Repeat counts are checked as signed 64-bit Py_ssize_t via quotient by
2^32; this works even under the 60-bit profile. Above the portable safe
count, nonempty repeats raise MemoryError; empty repeats return empty.
Negative counts in Py_ssize_t return empty. Out-of-Py_ssize_t counts raise
OverflowError even for empty or negative repeats. Checked directly with
CPython 3.9.6 for string, list, and tuple, empty and nonempty, at 2^53 and
the signed 64-bit edges (target/s3a-cpython-repeat.log).

Range construction caches the small-carrier guard once. The fast body
uses only canonical native integers. A float conversion of the index is
used solely as a safe magnitude guard: no canonical bignum can round into
[0,2^26]. The accepted arithmetic uses the original integer index, and
never yields an unsafe bare +/-2^53 Number. No integer is rounded for
Python comparison/equality or for a returned range element.

The exact range fallback fuses start+i*step via quotient/remainder,
adjusting toward zero so product and residual have the same sign. This
avoids a false product-only resource refusal during cancellation. A
bit-limit on a never-yielded candidate returns stop. Both signs and the
small-profile cancellation example are pinned.

Range count subtracts quotients in base 2^32, checking sys.maxsize before
constructing the count. It distinguishes OverflowError above 2^63-1 from
a valid length breaching the configured bit budget (MemoryError). Bounds
can exceed the budget for their difference without causing an unnecessary
intermediate resource failure. Membership compares exact remainders.

## Tests changed and why

- e2e_c1 integer-bound-test: the two exact lines from fable section 6.
  3**40 and 1<<54 use equivalent S3-A products; ~2**53 uses -2**53-1.
  S3-B/C operators are deliberately left pending, rather than expecting
  this slice to implement them. The 2**53 input itself is valid on JVM.
  Its surrounding Round 2 comment no longer claims arithmetic overflow.
- prelude_parity integer-bound-on-every-host: the exact fable line.
  Inputs use int-lit, not pending py/pow 2 53, which would produce an
  unsafe native Number on Node before S3-B. Products replace pending
  power/shift operations. The helper now builds its print vector with
  reduce to keep changed lines within 80 columns.
- prelude_parity int-defects docstring: module version 3 rather than 2.
- float_address prelude-addresses-test and program-addresses-test: five
  moved hashes, re-minted once after the final prelude edit. The hook hash
  is unchanged. Hash strings split into 32-character halves for 80 columns.
- safepoint, lower and lower-portable: no golden or source changes needed.
- int_ops new tests: exact-operators (promotion/cancellation, bools, wrong
  types, exact mixed comparison, translator class/message); index-range-
  repeat (big indices, slice clamping, repeat classes, membership, 64-bit
  len edges); range-small-profile (both signs, cancellation, exhaustion,
  resource refusal); fixture-parser (blank lines); nan-comparisons (six
  operations); mixed-float-overflow (both operands and zero ordering);
  cpython-operator-fixture (all four VMs, every row).
- int_ops_parser new tests: every row through the Python parser once;
  evaluation-once for +=/-=/*=, chained comparisons, membership, big-index
  assignment, and big-bound/step slices on every JVM VM.

The checked-in fixture has 6650 independent CPython 3.9.6 rows. It covers
all S0 integers, neighbours, signs, cancellation, bools, all six numeric
comparisons, exact float powers of two, 0.5, -0.0, both infinities, NaN,
unary float negation, and huge-int conversion overflow. Expected floats
use IEEE-754 bits; integer operands use guest hex reconstruction. The
loader drops every blank line and never writes a file. Regenerating with
the checked-in script reproduced the fixture SHA exactly.

The whole parserless corpus and parser corpus are slow tests; the former
has a portable slow/guard. Fast portable tests still cover the principal
carrier hazards on all four VMs. Host lanes were not run in this worktree.

## Red, green, and deliberate mutations

Initial focused tests before implementation: 12 failures, 0 errors.
A later check of the original HEAD prelude on an isolated classpath,
with the corrected exception helper, confirms exact-operators still red:
4 failures, 0 errors (target/s3a-baseline-exact-red.log).
The initial test helper incorrectly looked up __class__ through getattr;
that helper was corrected to read the established exception class cell.
One original-baseline audit run was interrupted because its unguarded
huge repeat tried to allocate; the bounded exact-only baseline check is
the completed baseline evidence.

Full corpus red: four host refusals from the original NaN = guard, then
four failures with exactly three mismatched rows after fixing NaN. Those
three were oversized-int / -0.0: CPython requires conversion OverflowError
before float ZeroDivisionError. Green after fixing that order: four
assertions, zero failures over all four VMs. The final larger corpus adds
negative infinity and unary float cases; final run results are below.

Intentional mutations live only under target/, never in shipped source.
The completed mutation run breaks addition, subtraction, multiplication,
range membership, range length, mixed float conversion, type validation,
bool coercion, NaN guard, bounded arithmetic, unary sign, rounded mixed
comparison, float-overflow mapping, index bounds, repeat bounds, slice
clamping, and range product cancellation. Every mutation fails on all
four JVM VMs: 68 failures, 0 errors. Logs: s3a-mutations-complete.log.
None remain applied. The final focused suite is green after the mutations.

## JVM focused verification

Completed results, excluding slow unless indicated:

- Initial parity baseline: 18 tests / 72 assertions, zero failures.
- Operators + int-literal + int-contract: 26 / 1112, zero failures.
- E2E C1 + e2e + int-ops fast: 17 / 148, zero failures.
- E2E C1 slow: 17 / 204, zero failures.
- Parity slow (before final harmless index guard): 8 / 108, zero failures.
- Final int-ops + parser fast + parity + CBOR conformance + hash-registry
  contract + store-write audit: 57 / 4561, zero failures.
- Full parser corpus: 1 test / 104 batches, zero failures, all 6650 rows.
- Golden red: float-address, lower, lower-portable, safepoint, 52 / 532;
  exactly five expected hash failures, no errors. Re-mint once.
- Golden green: same 52 / 532, zero failures.
- After hash formatting and portable-bound helper rewrite: float-address
  and parity fast, 28 / 155, zero failures.

Final combined slow: int-ops, parity, float-address, safepoint, lower,
lower-portable, 23 tests / 239 assertions, zero failures/errors. This
includes all 6650 corpus rows on all four VMs after the final index guard.
The combined run took about 591 s, within the 600 s cap. All slow
safepoint tests completed. Final performance results are below.

Kondo final: zero errors/warnings. cljstyle fix then check succeeded.
Changed source/test lines are ASCII and <=80 columns. Fixture payload
rows necessarily exceed 80 columns to hold exact large decimal values.
git diff --check is clean. bb test:changed:list selects 15 JVM namespaces;
the new portable tests and fixture loader are selected on Node/Dart.

Parser generation initially failed under sandbox networking/process
restrictions. The authorized escalated bb gen:python-antlr succeeded.
No approval rejection or outstanding permission block.

## Interim-state hazards for S3-B/C

These remain deliberately pending, not hidden behind a claimed C3 pass:

- // and %: int-floordiv/int-mod/divmod-pos still use native arithmetic,
  comparisons, doubling and the old checked helpers. BigInt mixed with
  small native values can throw host failures on Node/Dart. JVM paths can
  retain the old 2^53 restriction or native-width arithmetic hazards.
- Mixed // and %: data/float-value still converts oversized integers to
  infinity instead of the new guest conversion OverflowError.
- divmod: the builtin is still absent; S3-B adds it.
- **: ipow uses checked-mul; fpow halves via the old integer div/mod;
  pow's exponent sign/negation use host primitives. Large integer bases
  or exponents can throw host failures on Node/Dart, or hit the old bound.
  A native 2^53 can still be emitted by py/pow on Node. Negative-exponent
  conversion, large float exponents and ruling-10 underflow remain B/S4.
- Integer/integer /: both operands now convert safely through to-float,
  but this is not the exact ratio kernel. A huge/huge finite ratio (e.g.
  10**400 / 10**400) raises conversion OverflowError; independent rounded
  conversions can also give a different rounded quotient. S3-B replaces
  this arm with true-div. Mixed / is completed in this slice.
- &, |, ^: interpreted bit recursion uses old div/mod and checked ops;
  large carriers can cause host failures. Two-bool result dispatch is C.
- ~: native negation plus checked-sub, old bound/host-mixing hazards.
- << and >>: shift-check still uses native < on the count; lshift uses
  checked-mul/ipow; rshift uses native guards and int-floordiv. Large
  carriers can cause host failures or the old bound. Shift-check was in
  the broad architect audit, but the explicit task defers shifts to C.

No integer-module defect was found, so no kernel edit or stop was needed.
No Node/Dart or full bb suite ran; their landing checks belong to the
orchestrator on the combined S2 + S3-A + S3-B + S3-C stack.

## Performance and orchestrator command

One-off script: target/s3a-perf.clj, ast-walker over the real prelude,
100000 integer additions and 100000 range lookups, each in a fresh guest
run. JVM command uses a test source path without invoking the test runner:

clojure -Sdeps '{:paths ["test" "src/clj" "src/cljc"
 "build/antlr/python3/classes"]}' -M -i target/s3a-perf.clj

Baseline: integer 187.919662084 s; range 97.734857916 s.
After (no competing JVM test process), one wall-time sample each:

- integer: 187.919662084 s -> 145.240998583 s; ratio 0.772889x,
  22.71% less wall time.
- range: 97.734857916 s -> 85.043181125 s; ratio 0.870142x,
  12.99% less wall time.

Both return 100000. Logs: target/s3a-perf-before.log and
target/s3a-perf-after.log. The baseline overlapped short focused commands;
these are observed wall times, not a statistical benchmark or the
long-loops acceptance measurement. The after command finished under 600 s.

The long-loops-test was not run (about 14 minutes, over the 600 s cap).
Exact command for the orchestrator, selecting only that slow test:

clojure -M:test -i :slow -n yang.python.antlr.e2e-test \
  -v yang.python.antlr.e2e-test/long-loops-test

Portable full-corpus JVM command (done in the final combined slow run):

clojure -M:test -i :slow -n yang.python.antlr.int-ops-test

On Node/Dart the full corpus requires DATOM_SLOW_TESTS=1. The ordinary
changed lanes exercise all fast carrier/audit pins. The parser corpus is
JVM-only: clojure -M:test -i :slow -n
  yang.python.antlr.int-ops-parser-test.

## Deviations and open questions

- CPython's out-of-Py_ssize_t repeat result is OverflowError, including
  huge negative/empty repeats; the architect shorthand said MemoryError
  or empty. The task explicitly asks to check CPython and pin each class,
  so the implementation follows the measured CPython 3.9.6 result.
- Range fallback uses quotient/remainder fusion instead of binary product
  splitting. The same-sign invariant proves each intermediate fits for
  any yielded in-budget element, and the ruled cancellation case passes.
- The fast eligibility bit is computed once at range construction; the
  snapshot representation remains the existing range start/stop/step.
- Slow tagging and bounded parser batches keep the corpus out of fast
  iteration lanes and avoid a huge lowering tree; every row still runs.
- Only the authorized C1 status sentence and S3 slice status sentence in
  8.5.4 were changed in docs; no wider documentation cleanup.
- No open correctness question in S3-A. S3-B/C residuals above are required
  before the combined stack lands. Three-host landing is still pending.

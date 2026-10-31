Completed-GMT: 2026-10-05 21:24:58 GMT
Completed-Local: 2026-10-06 04:24:58 +0700

# Python C3 slice M3: integer module version 3 (engineer report)

Status: done on the JVM. No git writes. I ran JVM focused namespaces only.
Node and Dart have not been run; that is the landing `bb test:changed`.

## Files

- `src/cljc/yin/vm/integer.cljc`
  - `module-version` is now 3.
  - Four new exports: `to-float`, `compare-float`, `true-div`, `from-float`.
  - The fast table for add, sub, mul and compare.
  - The docstring contract covers the float exports and the `::float-overflow` reason.
- `src/cljc/yin/vm/integer/host.cljc`
  - New: `safe-native?`, `host-float?`, `nan-float?`, `infinite-float?`, `native->double`, `trunc-native`.
- `test/yin/vm/integer_test.cljc`
  - The version and export-set tests are updated.
  - Five new deftests (listed below).
- New:
  - `test/yin/vm/integer_v3_fixtures.cljc`: the loader. It is read only, drops blank lines, uses `reduce` (not `for`), and decodes bits from hex per host.
  - `test/resources/yin/vm/integer-v3.txt`: 1149 rows. Integers are decimal; floats are the 16 hex digits of their IEEE bits.
  - `test/resources/yin/vm/integer-v3.generate.py`: CPython 3.9.6 asserted, seeded random, rewrites the fixture byte for byte (checked by sha1).
- Not touched: prelude, lowering, render, docs, collab (except this report).

## Exports (version 3)

Every new export is `:pure`. Each one is a single algorithm over the shim. No host converts a big integer to a double.

- **`to-float [1]`** is `ratio->float(|n|, 1)`.
  - A non-finite result answers `::float-overflow`. The docstring adds it to the returned reasons and says the prelude translator must learn it (OverflowError "int too large to convert to float").
- **`true-div [2]`**
  - Steps:
    1. Find e with 2^(e-1) <= x/y < 2^e, using one shifted comparison.
    2. Set k = max(e - 53, -1074).
    3. Do one big division giving at most 53 quotient bits.
    4. Round half to even on the remainder.
    5. Do one exact multiply by 2^k, which `pow2` builds by exact squaring.
  - Nothing rounds twice. Subnormals come out exact.
  - Early exits: e0 > 1024 overflows, and e0 < -1076 gives a signed zero. Neither builds a huge shift.
  - A zero divisor throws the existing `:zero-division` refusal.
  - CPython's `0 / -5` is `-0.0`, so a zero quotient takes its sign from the XOR of the operand signs. The fixture pins this.
- **`compare-float [2]`**
  - It compares exactly against `trunc(v)` as a big integer; on a tie, the fraction decides.
  - An infinity answers by its sign.
  - NaN throws `:wrong-type {::expected :non-nan-float}`. The caller excludes NaN, so reaching it is a caller defect.
  - A non-float throws `:wrong-type {::expected :float}`.
- **`from-float [1]`**: exact truncation into the canonical carrier, subject to `::max-bits` (it answers `::bit-limit` like every integer result).
  - NaN or an infinity throws `:wrong-type {::expected :finite-float}`.
  - The brief lists three exports. Fable's binding section 4 lists this fourth one "so S4 does not force a version 4", and `compare-float` needs the same exact truncation anyway. It costs about 8 lines. Drop it if the orchestrator wants exactly three.
  - It never uses `bigint` of a double. Above 2^53 it halves exactly into [2^52, 2^53), where every double is an integer, then shifts back.

## Fast paths (fable section 5, guard table exact)

- **add, sub**: both operands `safe-native?`, computed natively. The result is accepted only within +/-(2^53-1); anything else takes the big path.
- **mul**: both magnitudes at most 2^26, no result check. A zero product is returned as the literal `0`.
- **compare**: native when both operands are safe.
- The table is chosen once in `integer-module`, only when `::max-bits` >= 53.
- On JS, `safe-native?` is exactly the Number arm of `exact-integer?`. A `-0` operand is therefore still refused, and a test pins that on JS.
- On the JVM it is a `Long` only. Integer, Short and Byte take the big path, so results are unchanged.
- S0 still holds: `yang.python.antlr.int-contract-test` and `yin.vm.module-test` give 18 tests, 2496 assertions, 0 failures. That includes the `2^53-1 + 1` row with bytes `1b0020000000000000`.

## Tests

`yin.vm.integer-test` on the JVM: 24 tests, 337 assertions, 0 failures. Every existing test is unchanged except the version number and the export set.

New deftests:
- `fast-path-edge-test`:
  - 2^53-1 +/- 1;
  - (2^53-1) + (2^53-1);
  - 2^26 * 2^26 and (2^26+1) * 2^26;
  - -1 * 0, which must be the integer 0 (the JS `integer?` check catches a -0);
  - mixed fast and big operands;
  - promotion, then cancellation back to a native.
- `fast-path-respects-the-bit-limit-test`, under max-bits 53 and 52.
- `float-fixture-test`: every row, with mismatches collected into one vector. The fixture holds:
  - 187 to-float rows;
  - 96 true-div rows;
  - 837 compare-float rows;
  - 29 from-float rows.
- `float-named-cases-test`:
  - (10^400)/(10^400) gives 1.0;
  - 1/2^1074 gives the least subnormal;
  - 1/2^1075 gives +0.0;
  - 0/-5 gives -0.0;
  - the overflow edge;
  - infinities at 2^5000.
- `float-refusals-test`.

Portability: the tests use no 0.0 or 1.0 float literals; floats are built from their bits. JVM-only forms are not used in portable code.

## Brief correction: the overflow edge

The brief says 2^1024 - 2^970 is "the largest value that rounds to a finite float". That is wrong.

- 2^1024 - 2^970 is the exact tie above the largest finite double, 2^1024 - 2^971.
- That double's mantissa is odd, so ties-to-even rounds the tie up, and it overflows.
- CPython 3.9.6 agrees: `float(2**1024 - 2**970)` raises OverflowError.
- The largest integer that rounds finite is 2^1024 - 2^970 - 1.

The fixture pins TOP-1 (finite), TOP and TOP+1 (overflow), where TOP = 2^1024 - 2^970, and a named test spells this out.

## Mutations

Each mutation was applied by `target/mutate.py`, which restores the file afterwards; the JVM results are below.

| Mutation | Result on the JVM |
|---|---|
| Drop the add result check | Caught: max-bits 53, `add 2^53-1 1` must be `::bit-limit` |
| Widen the mul guard to 2^31 | Caught, 2 failures: 2^27 * 2^27 and 2^31 * 2^22 under 53 bits |
| Round half up instead of half to even | Caught, 3 failures: fixture to-float and true-div rows, plus a named case |
| Install the fast table below 53 bits (extra) | Caught, 2 failures |
| compare-float ignores the fraction (extra) | Caught |
| Drop the zero normalization | Not caught on the JVM, and cannot be |

The zero normalization matters only on JS: the JVM and Dart have no integer -0. On Node, `fast-path-edge-test` asserts `(integer? r)` on -1 * 0, and `integer?` is false for a JS -0, so the mutation should fail there. I could not confirm that inside the JVM-only rule; please look for it in the landing Node lane.

## Performance (JVM, 1e6 small add/sub/mul/compare calls)

The bench is `target/bench/integer_bench.clj`. It is an A/B in one JVM:
- max-bits 100000 installs the fast table;
- max-bits 52 does not, so it runs the old big path unchanged;
- both arms give the same sum.

The min of 9 runs, 2 rounds:

| | Big path | Fast path | Ratio |
|---|---|---|---|
| Round 1 | 172 ms | 148 ms | 1.16x |
| Round 2 | 168 ms | 144 ms | 1.17x |

- The first version measured slower (231 ms against a 212 ms baseline, in separate JVMs). The cause was variadic 3-argument `<=` and a var-deref bound. I rewrote both as 2-argument comparisons.
- The remaining per-call cost is mostly `arity-checked` (`& args`, `apply`) plus `partial`, not the kernel. That is the next lever if the S3-A long-loops target needs more. It is out of scope here.
- The JVM test runner will not load files from `target/`, so I copied the bench into `test/` for each run and deleted it straight after. Nothing is left in `test/`.

## Gates

- kondo on the four Clojure files: 0 errors, 0 warnings. I renamed `nan?` and `infinite?` to `nan-float?` and `infinite-float?` to avoid shadowing core.
- Lines are ASCII and at most 80 columns in everything I wrote. The longer lines that remain in `integer_test.cljc` and `integer.cljc` were there before, as is one non-ASCII test string ("٣" in parse-format-test).
- **cljstyle: BLOCKED.** `cljstyle fix` and `cljstyle check` both needed approval in this session and were denied. Please run them at landing.
- `bb test:changed:list` selects 97 of 226 namespaces, as expected for a change to `yin.vm.integer`.

## docs/design/yang.antlr.md

The only "version 2" sentence is the S3a slice row at line 2240: "`integer` version 2 returns them ... Landed". It records what S3a landed, which is still true, and it does not say the module is currently version 2. I left it unchanged. Change it if you read the instruction differently.

## Cross-host risks for the landing lanes

- **Dart**:
  - `pow2` loop locals: a double accumulator and an int k.
  - `(.truncate ^double x)`.
  - `(.-isNaN ^double x)`.
  - `(* -1.0 d)`, used for negation because CLJD's unary minus loses -0.0.
  - The fixture loader's ByteData import is `(:import ["dart:typed_data" ByteData])`, matching `dao.jing.cbor`.
- **JS**:
  - `trunc-native` is `Math.trunc(x) + 0`, so it never answers -0.
  - Float results are raw Numbers; the tests compare their bits, not their kind.

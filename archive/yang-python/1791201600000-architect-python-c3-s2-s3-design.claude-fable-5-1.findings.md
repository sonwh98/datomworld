Completed-GMT: 2026-10-05 21:08:09 GMT
Completed-Local: 2026-10-06 04:08:09 +07

# Architect rulings: Python C3 S2 and S3 (fable-5-1, independent, then compared with astra)

Read-only: nothing edited, no suites run. I checked the S0 values by reading the fixture and working the arithmetic by hand, not by running it.

## Verdicts

1. **Literals.** A literal up to 2^53-1 stays a native `:literal`. Anything larger lowers to `(py/int-lit "<canonical lowercase hex>")`, which parses in radix 16. The AST cannot carry a bignum datum. Only decimal spellings get a digit budget, enforced at lowering.
2. **Malformed encodings.** Bytes refuse in Jing, as they already do. Rows are checked at the Python frontend. A wrong carrier reaching a kernel stays a host failure. No `yin.vm` gate is edited.
3. **Boundary.** S2 fixes the snapshot renderer for big carriers and pins the round trips. S2 builds no adapter, no guest `str`, and no digit limit on `print`.
4. **Operators.** Every integer arm goes through one module call wrapped by `py/int-result`. The float-touching arms need three kernels that version 2 lacks, so the module goes to version 3.
5. **Fast path.** It lives inside the kernels, not the prelude: add and sub check the result, mul checks the operands at 2^26, compare is native when both operands are safe.
6. **Behavior change.** Intended (rulings 1 and 2). Two tests flip from `overflow` to exact values; the expected strings are below.
7. **Slicing.** S3 splits into three rounds. The version 3 module slice lands first, alongside S2, so no round ships an interim refusal.

No S0 frozen value is wrong.

## 1. S2 literals

**Why a parse call.** `yin.vm/plain-data?` admits scalars through `number?`, which is false for a JS or Dart `BigInt`. `yin.vm.debruijn` declares `:bigint` out of domain, and on JS an unsafe integral classifies nil. So a bignum literal row cannot exist on Node or Dart. Widening that is a kernel ruling, not S2.

**Emitted form.** For a token of value n:

- n <= 9007199254740991: `(u/lit n)`, on every host, including the JVM where a long could hold more.
- n larger: an application of `py/int-lit` to one string literal. The string is lowercase hex with no prefix, no underscores, and no leading zeros.
- New prelude definition: `[py/int-lit (fn [s] (py/int-result (integer/parse s 16)))]`. Add `integer/parse` to `host-names`.
- Signs are not part of the token: `-5` stays `py/neg` over the literal.

**Lowerer delta (`lower.cljc`).**
- Delete `max-exact-int` and the "integer literal beyond 2^53" refusal.
- Keep a small native accumulator for string escapes only, with its code-point bounds unchanged.
- Convert through the public integer module, not the host shim. Per literal, build an instance with `::max-bits` of 4 times the digit count (enough for any radix up to 16) and `::max-digits` of the budget. Then `parse` in the token's radix, `compare` against 2^53-1, and `format` in 16.
- No host double and no native-width parser on any path.

**Which limit applies to which spelling.**

| Spelling | Digit budget | Bit budget | CPython 3.9.6 |
|---|---|---|---|
| Decimal | Yes, at lowering: digits after removing underscores must not exceed the profile's max-digits. A breach is a source diagnostic. | At run time: `MemoryError` from `py/int-lit` | No limit of either kind |
| `0x`, `0o`, `0b` | None | At run time: `MemoryError` from `py/int-lit` | No limit |

- The decimal budget is this profile's restriction. It follows 3.11's behavior, where an oversized decimal literal is a compile-time `SyntaxError` and hex is exempt.
- `lower-packet` takes only the packet today, so the budget must arrive as explicit data on it. With no budget declared, a decimal literal above 2^53-1 is a diagnostic. There is no default.
- Python runtime-profile admission refuses `::max-bits` below 53. That keeps every inline literal and every fast-path result inside the budget. S0's `small` profile (60) still passes.

**Doc.** The 8.5.4 bullet "canonical decimal string" becomes radix 16.

**S2 tests.**
- Spelling groups for 2^53-1, 2^53, 2^63, 2^64 and 2^100: decimal, `0x`, `0X`, `0o`, `0b`, with underscores and mixed case. Each group gives one row set, one byte string and one address, with JVM goldens asserted on Node and Dart at the row level.
- 2^53-1 is a native literal; 2^53 is the call form.
- A 5000-digit hex literal lowers and runs under max-digits 4300. The same value in decimal is a diagnostic.
- `0x1` followed by 1100 zeros under max-bits 4096 raises a catchable `MemoryError`.
- A walk over lowered rows finds no integer literal outside the safe range.
- Evaluated literals reproduce the S0 `dec`, `hex` and `cbor` columns.
- `0755` and malformed underscores still refuse.
- Mutations: decimal reconstruction (the huge-hex row fails) and a native 2^53 (the Node row fails).

## 2. Malformed encodings

- **Bytes.** `dao.jing.cbor` decode already refuses `:non-canonical` and `:trailing-data`. S2 adds pins and no code:
  - tag 2 over a value that fits major type 0 (`c24105`, and tag 2 over 2^64-1);
  - tag 3 over -2^64;
  - a leading zero byte in a bignum;
  - an empty bignum;
  - a non-shortest head (`1b0000000000000005`);
  - tag 2 over a non-byte-string;
  - trailing bytes.
  
  Decoding each S0 `cbor` row must give the same carrier as `integer/normalize` on that host.
- **Rows.** The frontend guarantees no integer literal outside the safe range and no big carrier in a row (the walk test above). `plain-data?` is not edited, so 8.5.5's serial-landing condition does not apply.
- **Integral float where an int is required.** A `{:py/float}` value is a float by tag, and an integer-only operation raises `TypeError` through `py/int?`. Nothing converts it.
- **Wrong carrier at a kernel.** A bare unsafe JS Number or a `-0` is not an exact integer. It gets the module's `:wrong-type` refusal, which is a host failure and a producer defect. It is never recovered into a `BigInt`.
- **Non-canonical big carriers** (a JS `BigInt` holding 5, a JVM `BigInt` under 64 bits). No runtime sweep. The supported producers (literals, kernels, Jing decode) are canonical by construction, and a test asserts it for each. Kernels stay lenient on input and canonical on output.

## 3. Boundary integration

**S2 covers:**
- `render/repr` has `(number? v) (str v)`, which misses big carriers on Node and Dart. Add an exact-integer arm that renders exact decimal through `integer/format`. It carries no digit limit in S2, which matches 3.9.6.
- A bignum from a literal held in a list, tuple, dict value, dict key and cell, then returned and printed, on all four VMs and three hosts.
- A Jing byte round trip of each S0 row from a guest value.
- A failed key normalization leaves the container unchanged (already landed; keep the pin).

**Deferred:**
- S4: guest `int`, `float`, `str`, `repr`; the digit limit on `print` (print must go through guest `str` before the snapshot); the `in` shortcut.
- S6: collection, pinning, UCF, lift refusal, and the raw remote put refusal pin.
- No FFI or stream adapter in any C3 slice (ruling 12).

## 4. S3 operator table

`py/num` coerces bool first, and type errors are raised before any module call. `py/int-result` gains a first arm, `(if (data/number? r) r ...)`, so a normal result costs one check.

| Python | Integer arm | Guest failure |
|---|---|---|
| `+ - *` | `integer/add`, `sub`, `mul`, wrapped | `MemoryError` |
| unary `-` | `integer/neg`, wrapped | `MemoryError` |
| unary `+` | `(py/num a)`, no module call | none |
| `~` | `integer/bit-not`, wrapped | `MemoryError` |
| `//`, `%`, `divmod` | `integer/floor-div-mod`, wrapped, then pick or tuple | `ZeroDivisionError` "integer division or modulo by zero", prechecked |
| `**`, exponent >= 0 | `integer/pow`, wrapped | `MemoryError` |
| `**`, exponent < 0 | float path: `1.0 / py/fpow(to-float(base), -e)`, unchanged contract (ruling 10) | zero base: `ZeroDivisionError`; huge base: `OverflowError` |
| `& | ^` | `integer/bit-and`, `bit-or`, `bit-xor`, wrapped; two bools give a bool | `MemoryError` |
| `<<` | `integer/shift-left`, wrapped | negative count: `ValueError`, prechecked through `integer/compare`; `MemoryError` |
| `>>` | `integer/shift-right` | negative count: `ValueError` |
| `< > <= >= == !=`, int with int | `(op (integer/compare x y) 0)` | none |
| int with float, compare | `integer/compare-float` (v3), after a NaN check | none |
| int with float, arithmetic | convert through `integer/to-float` (v3), then the existing float op | `OverflowError` "int too large to convert to float" |
| `/`, int by int | `integer/true-div` (v3) | `ZeroDivisionError` "division by zero"; non-finite result: `OverflowError` |

- Augmented forms reuse these; `py/iadd` and `py/imul` keep their list arms.
- `py/fpow` halves its exponent through `integer/shift-right` and `bit-and`, so any exponent size works.
- Delete `py/overflow`, `py/checked-add/sub/mul`, `py/ipow`, `py/divmod-pos` (once `py/int-of` moves), `py/int-floordiv`, `py/int-mod`, `py/bit1`, `py/bit-op` and `py/int-canon`.
- `divmod` becomes a builtin in round B.

**Sites that feed a guest int to a host primitive** must be audited in round A:
- `py/index`, slice bounds and `py/repeat` counts.
- `py/range3`, `range-count`, `range-at`, `range-has?`, `range-len`.
- `py/shift-check`.

Rules for those sites:
- An index or count beyond the safe range raises what CPython raises (`IndexError`, or `MemoryError` for a non-empty repeat).
- `len(range)` raises `OverflowError` above 2^63-1, CPython's real bound, replacing the 2^53 one.
- `range-at` computes `start + i*step` through the module. A breach in the sum means the candidate is past `stop`, so it answers `:py/stop`, not `MemoryError`. A breach in the product alone can hide a valid element (start = -(2^60-1), step = 2^59, i = 2 under `small`), so the engineer splits the product and pins that case.

**Module version 3.** Four new exports, with every version 2 semantic and S0 pin unchanged:
- `to-float [1]`: nearest, ties to even; answers a signed infinity when out of range, and the prelude raises `OverflowError`.
- `compare-float [2]`: exact -1, 0 or 1 against a finite or infinite double.
- `true-div [2]`: the correctly rounded ratio.
- `from-float [1]`: exact truncation, for S4, included now so S4 does not force a version 4. Per S0 finding 2, the JVM must not use `bigint` of a double.

S2 needs nothing new from the module.

## 5. Fast path

**Placement.** Inside `int-add`, `int-sub`, `int-mul` and `int-compare`. Ruling 5 says guest integers go only through the module, and the prelude has no carrier predicate: a host `<` on a Dart `BigInt` throws. The 6x incident was interpreted recursion per iteration, and a kernel call is one VM step. The results are unchanged, so this alone is not a version bump.

**Guard.** "Safe" means a native carrier within +/-(2^53-1). On JS that is exactly the Number arm of `exact-integer?`.

| Kernel | Guard | Why it is exact |
|---|---|---|
| add, sub | Both safe; compute natively; accept only if the result is within +/-(2^53-1), else the big path | A long cannot wrap below 2^54. On JS a true result of 2^53 or more rounds to 2^53 or more, so it is never accepted. |
| mul | Both magnitudes <= 2^26; no result check; a zero result is returned as the literal 0 | The product is at most 2^52. A result check alone is unsound on JVM and Dart, where a long product can wrap back into range. The literal 0 removes JS `-0`. |
| compare | Both safe: native comparison | exact |

- The fast table is chosen once in `integer-module`, and only when `::max-bits` is at least 53, so no per-call limit check is needed.
- The guard is the same on all three hosts. JVM and Dart values between 2^53 and 2^63 take the big path and still demote to a long.
- S0 boundaries: `2^53-1 + 1` fails the result check, takes the big path, and gives a `BigInt` on JS and a long elsewhere, with the bytes `1b0020000000000000` everywhere.

**Tests.**
- Each guard edge on each host: 2^53-1 plus or minus 1, 2^26 times 2^26, (2^26+1) times 2^26, and -1 times 0.
- Promotion then cancellation.
- `long-loops-test` within 10% of its current time on the JVM, with the time reported.
- Mutations: drop the add result check, widen the mul guard to 2^31, and drop the zero normalization.

## 6. Tests that change (intended, rulings 1 and 2)

- `e2e_c1_test.clj` `integer-bound-test` becomes an exactness test. New expected lines:
  - `9007199254740992 9007199254740993 12157665459056928801 4503599627370496 9007199254740992 18014398509481984 -9007199254740993`
  - `27021597764222973 -9007199254740993 0 -1 -9007199254740993 9007199254740992`
- `prelude_parity_test.cljc` `integer-bound-on-every-host` expects `9007199254740992 9007199254740993 12157665459056928801 18014398509481984 -9007199254740993 27021597764222973`. This needs S2's renderer arm on Node and Dart.
- The comment at `e2e_c1_test.clj:340`, the prelude docstring and number comments, and 8.5.4's C1 sentence.
- Address goldens in `float_address_test`, `safepoint_test`, `lower_test` and `lower_portable_test` re-mint whenever the prelude changes.
- The range tests with +/-2^53 bounds keep their outputs and must stay green.
- I found no existing test that expects the literal refusal or a `len(range)` overflow.

## 7. Slicing and order

1. **M3**: integer module version 3 (kernels and tests only). Runs alongside S2, since the files are disjoint.
2. **S2**: literals, renderer, pins. Re-mint: prelude goldens (one new definition) and the literal row goldens.
3. **S3-A**: add, sub, mul, unary ops, all comparisons and equality, mixed int and float arithmetic, the kernel fast path, and the range and index audit. Re-mint: prelude goldens; flips the two tests in item 6.
4. **S3-B**: `//`, `%`, `divmod`, `**`, `/`. Re-mint: prelude goldens.
5. **S3-C**: bitwise and shifts, then the deletions. Re-mint: prelude goldens.
6. S4 (text conversions and builtins), S6, S7.

One engineer round each, with the full three-host lanes once per round. S0 values are never regenerated.

## Where I disagree with astra

- **Literal form.** Astra emits `(py/int-result (integer/parse hex 16))` in user rows. I emit `(py/int-lit hex)`. User rows then depend only on `py/*` names, and the translation can change without re-minting user rows.
- **Small literals under a tight bit budget.** Astra wraps native literals in `integer/normalize`, specialized per profile. I reject that: it makes rows depend on the runtime profile and taxes every literal. A profile floor of 53 bits removes the case.
- **Carrier normalization "at the Python boundary".** There is no single choke point without an engine change. I rule canonical-by-construction with a per-producer test, and no sweep.
- **Rendering past the digit limit in S2.** Astra has guest rendering raise `ValueError` in S2. The renderer is host-side and runs after the run, so it cannot raise a guest exception. That belongs to S4, with guest `str`.
- **Unary `+`.** Astra calls `integer/normalize`. No call is needed, because guest integers are already canonical.
- **Negative exponent.** Astra wants to replace the reciprocal-of-`py/fpow` path in S3. Ruling 10 pins `py/fpow` as the contract. The underflow case (`2 ** -1074` gives 0.0 here, 5e-324 in CPython) is real, but it is a ruling-10 amendment for S4, not an S3 change.
- **Order of the float bridges.** Astra puts them last (S3-IV), with interim refusals in rounds I to III. I put the version 3 kernels first, so each round is complete for its operators and nothing is wired twice or refused in between. An interim refusal would also regress `2**53` meeting a float, which works today.
- **Fast-path guards.** Astra's add and sub guard (operands up to 2^51, no result check) is correct, just narrower than mine. The mul guard, the compare guard and the placement match.

I adopted one point from astra that I had missed: `&`, `|` and `^` on two bools must return a bool.

One process note: plan mode was active in this session. I wrote no plan file, since this response is the deliverable.

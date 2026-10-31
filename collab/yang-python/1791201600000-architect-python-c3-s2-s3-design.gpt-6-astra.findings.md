Completed-GMT: 2026-10-05 18:10:40 GMT
Completed-Local: 2026-10-06 01:10:40 +07

**1. S2 literals — retain small native literal data; reconstruct larger integers from canonical hexadecimal text.**

For an admitted nonnegative integer token of value `n`:

```clojure
;; n <= 9007199254740991
(u/lit n)

;; n > 9007199254740991 — illustrative emitted guest form
(py/int-result (integer/parse "canonical-lowercase-hex" 16))
```

The reconstruction string has no prefix, underscores, or leading zeros. Decimal, hexadecimal, octal, and binary spellings of the same value produce the same semantic rows, bytes, and address. Source-position/provenance records may differ. Unary signs remain Python unary operators; do not confuse signed expressions with token spelling normalization.

**Do not emit a bignum literal datum into the universal executable AST.** Jing can encode the value, but the de Bruijn image domain still excludes that literal kind. Codec support does not establish executable-image support. Amend [§8.5.4’s decimal reconstruction rule](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:2039) to radix 16.

Replace `lower/parse-radix`’s native accumulator and 2^53 rejection with exact compile-time token processing. It must never pass through a host double. Keep string-escape decoding separate: its code-point bounds must not be accidentally widened by this change.

Apply limits as follows:

- Validate Python token syntax first, including underscores and decimal leading-zero rules.
- For decimal source tokens, apply the declared source decimal-digit budget, excluding underscores and counting digit characters.
- Hexadecimal, octal, and binary source tokens are exempt from that digit budget.
- Runtime reconstruction uses radix 16 and is therefore exempt from the decimal conversion budget.
- A runtime integer result exceeding `::max-bits` raises catchable `MemoryError` through `py/int-result`.
- Small inline literals must not bypass a restrictive runtime bit budget. Under profiles where the inline value is not statically known to fit, emit `py/int-result(integer/normalize n)` around the native literal datum. Any compile-time specialization must be bound to the declared runtime profile.

A decimal token rejected by the frontend’s digit policy produces a source diagnostic, not a runtime `ValueError`. Runtime `int(string)` conversion belongs to S4.

CPython **3.9.6 has no decimal conversion digit limit**. Our explicit budget is a profile restriction, consistent with the earlier ruling; it must not be described as 3.9.6 behavior.

**S2 tests:** equal-spelling groups around 2^53, 2^63, and 2^64; underscores and leading zeros; large hexadecimal accepted where equivalent decimal source exceeds the digit budget; runtime bit-limit catches; restrictive-profile small literals; and exact rows/bytes/addresses through all four image pipelines.

---

**2. S2 encoding — reject malformed wire data at decoding, and reject wrong numeric kinds at the consuming contract.**

Keep Jing’s existing canonical integer grammar:

- shortest major-type 0/1 encoding where applicable;
- tags 2/3 only beyond their respective major-type ranges;
- minimal unsigned big-endian magnitude;
- tag 3 magnitude is `-1-n`;
- no leading zero bytes, redundant bignum tags, nonminimal argument widths, malformed payloads, or trailing values.

These are **codec refusals**, not Python `MemoryError` or `ValueError`. Preserve existing Jing fixtures.

An integral float is valid float data, but is not an integer operand merely because its value is integral. Integer-only APIs reject it through Python type dispatch; integer-only wire fields reject it structurally. Neither path silently converts it.

Normalize accepted exact host integer carriers at the Python boundary. Do not let raw JS BigInt values representing small integers, or equivalent noncanonical host carriers, create different equality behavior. An already-rounded JS Number outside the safe-integer domain is not evidence of the original integer and must not be “recovered” by conversion to BigInt.

The codec’s 64-bit head boundary is not a maximum integer width. Profile bit limits and executable-literal restrictions are separate contracts.

**S2 tests:** all malformed categories above, integer-versus-integral-float distinctions, normalization/demotion, and byte preservation for every S0 boundary row.

---

**3. S2 boundaries — make exact values safe and visible now; leave new conversion APIs and full migration claims to S4/S6.**

S2 must cover:

- Exact integer recognition when entering the VM through supported data/module boundaries.
- Canonical carrier normalization and explicit profile-limit handling at Python ingress.
- Exact integers returned by pure host modules and held in collections/cells.
- Existing print and snapshot/repr rendering: decimal text is exact below the digit limit; guest rendering that breaches it raises `ValueError`, never displays a refusal keyword or host exception.
- Canonical Jing round trips without narrowing.

Do **not** silently widen DaoStream’s raw value codec or existing FFI envelopes. Where a transport cannot carry a bignum, retain its qualified refusal. Use the established Jing-byte adapter where supported. An FFI operation claiming integer support must explicitly specify its accepted carrier/encoding; a host double is not an exact-integer transport.

S4 owns the new `int`, `float`, `str`, `repr`, and related conversion semantics. S6 owns comprehensive GC, pinning, aliasing, scalar UCF portability, and continued refusal of unsupported cell migration. Basic carrier/heap smoke checks must precede source exposure; the full S6 matrix can follow.

**S2 tests:** literal → cell/tuple/dict → return/render paths, module ingress/egress, supported Jing transport, honest unsupported raw transport, and no partial container mutation after a normalization failure.

---

**4. S3 operators — replace bounded arithmetic with exact module operations; bring required float bridges forward from S4 before enabling their bignum paths.**

For the integer arms below, first validate guest operand types and coerce bool to integer 0/1. Wrap each result-producing module call with `py/int-result` **before** inspecting, indexing, or combining its result.

| Python operation | Exact integer implementation | Guest failure |
|---|---|---|
| `+`, `-`, `*` | `integer/add`, `sub`, `mul` | Bit breach → `MemoryError` |
| Unary `-` | `integer/neg` | Bit breach → `MemoryError` |
| Unary `+` | `integer/normalize` after bool coercion | Bit breach → `MemoryError` |
| `//`, `%` | `integer/floor-div-mod`; select quotient/remainder after translation | Zero divisor → `ZeroDivisionError` |
| `divmod` | Same pair, wrapped as Python tuple | Zero divisor → `ZeroDivisionError` |
| `**`, integer exponent ≥ 0 | `integer/pow` | Bit breach → `MemoryError` |
| `~` | `integer/bit-not` | Bit breach → `MemoryError` |
| `&`, `|`, `^` | `integer/bit-and`, `bit-or`, `bit-xor` | Bit breach → `MemoryError` |
| `<<`, `>>` | `integer/shift-left`, `shift-right` | Negative count → `ValueError`; bit breach → `MemoryError` |
| Integer ordering/equality | `integer/compare` | No float conversion |
| Integer `/` | Exact-ratio-to-binary64 kernel | Zero divisor → `ZeroDivisionError`; nonfinite overflow → `OverflowError` |

Python floor division and modulo retain:

```text
a = q*b + r
q = floor(a/b)
r is zero or has the divisor’s sign
```

Bit operations use infinite signed two’s complement. Large right shifts yield `0` or `-1`; do not construct `2**count`. Preserve `0 << huge == 0`.

**Boolean result types need explicit dispatch.** Arithmetic on bool produces integers; binary `&`, `|`, and `^` on **two bools** produce bools. Mixed bool/int bit operations produce integers. Integer `is` remains distinct from bool identity.

**Negative exponent:** do not invoke `integer/pow` and translate `:negative-exponent` as a guest failure. Dispatch to the float-result path; zero base raises `ZeroDivisionError`. A large base requiring an unrepresentable float conversion raises `OverflowError`. Avoid the current reciprocal-of-overflowing-positive-power approach, which can erase a representable tiny result.

**Mixed comparisons:** exact int/bool comparisons belong in the first S3 round. Exact int/float comparisons are nominally S4, but must be brought forward before S3 claims supported comparisons over newly reachable bignums. Never compare by rounding the integer to double.

**True division:** likewise bring the ratio kernel forward for S3 integration. `(10**400)/(10**400)` must produce `1.0`; independently converting numerator and denominator is wrong.

Augmented forms reuse these operators, preserving evaluation exactly once and existing mutable-container behavior. Guest wrong types raise `TypeError` before module invocation. Existing zero/count checks prevent expected domain errors from reaching `py/int-result`’s internal-defect arm. Arity, wrong-type, or impossible domain refusals produced by a correctly dispatched prelude remain implementation defects.

**Missing module facilities:** version 2 has no exact integer-to-binary64 conversion, integer/float comparison, or correctly rounded integer-ratio conversion exports. Adding those exports requires **integer module version 3**, explicit profile negotiation, and tests. Preserve all v2 integer operation semantics and S0 pins.

---

**5. Fast paths — place native arithmetic inside the integer module, with carrier checks and profile-limit checks; do not bypass it in the Python prelude.**

The brief’s carrier wording needs correction: hosts do **not** choose identical physical carriers. Ruling 2 requires the canonical carrier **per host**: JS promotes at its safe-integer boundary; JVM/Dart can retain native signed integers longer. Canonical bytes and guest results must agree.

Use a conservative shared native fast path:

- Both operands must first be recognized as native exact integers, not floats or bignum carriers.
- **Add/subtract:** `|a| <= 2^51` and `|b| <= 2^51`. The result is within ±2^52.
- **Multiply:** `|a| <= 2^26` and `|b| <= 2^26`. The result is within ±2^52.
- **Compare:** native exact operands within `[-(2^53-1), 2^53-1]` may use native comparison directly.
- Outside those guards, use the existing exact kernels.
- Every arithmetic result still undergoes the configured bit-limit check. A small-profile breach must not disappear because the arithmetic took the fast path.

These are sufficient guards, not maximal ones. They require no interpreted division, recursion, or unsafe product to decide eligibility. Optimizing existing exports internally does not itself require module v3.

**Range requires integration too.** The present [range fast path](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:1818) can produce ±2^53 as bare JS numbers. It cannot remain the C3 guest-result path unchanged. Route guest range arithmetic and comparisons through exact helpers; normalize internal indices when they become guest numeric operands. Remove recursive range-element arithmetic once the exact module handles the calculation.

Range exhaustion must not raise a resource error merely because a never-yielded one-past value would exceed the profile. Check eligibility before constructing an out-of-domain candidate. Keep count, membership, slice, and repeat helpers in the numeric integration audit; replacing only named arithmetic operators is incomplete.

**Performance tests:** bound VM transitions for ordinary add/sub/mul and range lookup independently of operand/index magnitude within the fast region. Retain `long-loops-test` as slow coverage. Test each guard boundary and fallback, including small-profile breaches and S0’s promotion/cancellation cases.

---

**6. Compatibility — removing the ±2^53 arithmetic OverflowError is intentional; replace its tests, not the new exact results.**

This is the explicit C1 restriction removed by §8.5.4 rulings 1–2.

Update:

- The overflow expectations in [e2e_c1_test.clj:395](/Users/sto/workspace/datomworld/test/yang/python/antlr/e2e_c1_test.clj:395).
- `integer-bound-on-every-host-test` and the bounded arithmetic expectations in [prelude_parity_test.cljc](/Users/sto/workspace/datomworld/test/yang/python/antlr/prelude_parity_test.cljc:798).
- Lowering tests expecting rejection beyond 2^53.
- Range comments/assertions that equate the integer domain with the `len()` restriction.
- Python prelude/program address goldens affected by changed emitted forms or bundled code.

Retain actual conversion `OverflowError` tests, signed-zero tests, malformed-codec fixtures, hex-key fixtures, and S0 value/hash/CBOR pins.

I found **no numerical S0 pin requiring correction** in the inspected fixture and renderer. Its format/format16 results on values exceeding the small profile’s bit limit describe the module’s formatting contract; they are not permission for guest ingress to bypass normalization. The report’s JVM-only evidence must not be represented as completed Node/Dart verification.

---

**7. Sequencing — split S3 into three integer rounds plus a required numeric-bridge integration round.**

Recommended order:

1. **S2:** exact token normalization, canonical hexadecimal reconstruction, profile-bound admission, rendering/boundary integration.
2. **S3-I:** add/sub/mul, unary signs, integer/bool comparisons, native fast paths; preserve existing sequence dispatch.
3. **S3-II:** floor division/modulo/divmod and nonnegative integer power; signed and limit boundaries.
4. **S3-III:** bitwise operations and shifts; remove interpreted bit recursion and power-based shifts.
5. **S3-IV / advanced S4 dependency:** module v3 float bridges, negative power, true division, mixed numeric comparisons/arithmetic, and completion of range/sequence helper integration.
6. Remaining S4 conversions, remaining S6 portability, then S7.

Partial rounds must explicitly refuse unsupported newly reachable bignum paths rather than silently fall through to native arithmetic. S3 is not complete until round IV closes them.

Each round re-mints only its affected Python prelude/program goldens and profile references. **S0 numerical values, keys, hashes, canonical integer bytes, and limit outcomes remain fixed.** Add operator fixtures rather than regenerating those expectations from the changed implementation.

Across all rounds, acceptance is four VMs × JVM/Node/Dart, with parserless equivalent forms where source parsing is unavailable. No new executable bignum literal tag, generic VM numeric dispatch, or raw-stream widening is authorized.

Read-only review; no files changed or suites run.

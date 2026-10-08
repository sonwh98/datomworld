**Q1 — Adopt canonical float64 content equality for float `is`; remove host equality from that decision.**

The exact rule is:

- Two Python floats are `is`-equal iff their canonical float64 content is equal.
- All NaNs are `is`-equal, regardless of provenance, sign, or payload.
- Positive and negative zero are distinct.
- Other finite values and infinities compare by their binary64 content.
- A float is never `is`-equal to an integer or bool.
- `is not` is exactly the negation of `is`.

This chooses **(a)**. It provides reflexive, host-independent value identity without allocating float cells. It deliberately differs from CPython object identity. Numeric `==` and dict-key equivalence remain separate: both zeros share a dictionary key, and NaN’s shared key does not make numeric equality reflexive.

The existing [py/is implementation](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:1350) is an implementation defect for floats, not grounds to change Jing’s encoding.

**Implementation seam:** add a pure data-module operation, for example `data/float64-same?`, whose contract is equality of canonical float64 content. It accepts float payloads and handles host doubles and the JS carrier consistently. Implement it through public float-content facilities or exact binary64 inspection, including NaN normalization; do not change generic `=` or reach into Jing’s private key machinery. Comparing full serialized bytes is a reference oracle, not a requirement to serialize on every comparison.

`py/is` performs Python type dispatch and invokes this operation only for two floats. Preserve cell identity and normalized integer behavior. Immutable tuples must recursively use the same type-and-content relation; leaving host structural equality around nested floats would retain the defect.

Add to §8.5.4, with a pointer from §8.11:

> Value-based `is` uses canonical float64 content for Python floats: all NaNs are identical, signed zeros are distinct, and float and integer values are never identical. This rule applies recursively within immutable tuples. It establishes neither numeric equality nor CPython allocation identity. `is not` negates this relation.

Replace the claim that `py/is` universally “stays `(= a b)`.”

**Acceptance:** all four VMs on JVM, Node, and Dart must pin same and independently produced NaNs, decoded NaNs, both zeros, equal finite floats, opposite infinities, float/int/bool distinctions, and tuples containing those values. Assert `is-not` is complementary and compare the float helper’s answers against canonical-byte equality.

Deliver this as an immediate correction to the landed numeric-key/identity work—**table S5 follow-up, with S4 comparison coverage**.

---

**Q2 — Replace decimal rational-key components with canonical hexadecimal text; do not apply the user-facing decimal digit limit to key normalization.**

Choose **(a)**, with an explicit new discriminant:

```clojure
[:py.numeric/finite-hex numerator-hex denominator-hex]
```

Both strings use lowercase hexadecimal, no prefix, no leading zeros, and no plus sign. The numerator may have one leading `-`; zero is `"0"`. The denominator is positive, the fraction reduced, and zero is always `"0"/"1"`.

Examples:

```clojure
1, 1.0, True  => [:py.numeric/finite-hex "1" "1"]
0, -0.0      => [:py.numeric/finite-hex "0" "1"]
1.5          => [:py.numeric/finite-hex "3" "2"]
2^53         => [:py.numeric/finite-hex "20000000000000" "1"]
2^53+1       => [:py.numeric/finite-hex "20000000000001" "1"]
```

Infinity and NaN key forms remain unchanged. Tuple normalization remains recursive. Guest hashes and the first-inserted original dictionary key do not change.

The landed [integer formatter](/Users/sto/workspace/datomworld/src/cljc/yin/vm/integer.cljc:374) already exempts power-of-two radices from `::max-digits`. Use explicit radix 16 for **both** rational components. This avoids costly unrestricted decimal conversion and avoids introducing a second digit-budget policy.

Bit limits still govern integer intermediates. In particular, the current smallest-subnormal algorithm constructs a 1075-bit denominator; hexadecimal formatting removes its decimal-digit restriction, not that bit requirement.

**Migration:** update ruling 6, the known-limits paragraph, key fixtures, and the canonical-bytes golden in `dict-keys-test`. Using `finite-hex` prevents old decimal components from silently acquiring a different meaning. Bump the Python prelude/runtime profile and regenerate dependent addresses. Do not rewrite Jing’s scalar codec fixtures or treat old and new dictionary indexes as interchangeable. No Python-cell migration compatibility is established here.

Deliver this with the **table S5 follow-up before S2 literal exposure or S3 promotion**.

Acceptance must build an integer exceeding the configured decimal digit limit while remaining within the bit limit, then prove insertion, lookup, replacement, deletion, set membership, and tuple-key use succeed. Its decimal `str`/`repr` conversion must still raise the configured digit-limit error. Repeat with a deliberately small digit budget and finite float keys.

---

**Q3 — Deliver guest-refusal translation as a prerequisite slice before source-level bignum exposure, not at the final integration gate.**

Retain ruling 11: **bit-budget breach → `MemoryError`; digit-budget breach → `ValueError`**. Neither is ordinary integer arithmetic overflow.

One baseline correction is necessary: **CPython 3.9.6 did not have the decimal conversion digit limit.** It was added in 3.9.14. Therefore the configured digit limit is an explicit support-profile restriction, not a claimed 3.9.6 exception match. [Python’s versioned `int` documentation](https://docs.python.org/3.9/library/functions.html?highlight=round#int).

Use this mapping:

| Condition | Guest behavior |
|---|---|
| Explicit integer bit-budget breach | `MemoryError` |
| Decimal/non-power-of-two conversion digit-budget breach | `ValueError` |
| Wrong guest operand type or guest-call arity | `TypeError` |
| Invalid `int()` text or invalid conversion base | `ValueError` |
| Integer division/modulo by zero | `ZeroDivisionError` |
| Negative shift count | `ValueError` |
| Integer-to-float conversion outside finite binary64 range | `OverflowError` |
| `int(infinity)` | `OverflowError` |
| `int(NaN)` | `ValueError` |
| Malformed source integer literal | Frontend syntax diagnostic, not runtime `int()` failure |

Two reasons require operation-aware handling:

- **`:negative-exponent` is not inherently a guest error.** Python integer power with a negative exponent selects floating-point semantics; zero to a negative power raises `ZeroDivisionError`. Dispatch before calling the nonnegative integer-power kernel.
- **`:out-of-range` is not universally `OverflowError`.** Invalid radix is `ValueError`; a checked conversion to a bounded destination can require `OverflowError`. Map by operation and reason.

Likewise, an internal primitive arity mismatch generated by a correct prelude is an implementation defect, not a guest’s `TypeError`. Unknown refusal reasons and genuine host failures must not be indiscriminately converted into catchable guest errors.

**Mechanism:** the current [integer module throws qualified `ex-info`](/Users/sto/workspace/datomworld/src/cljc/yin/vm/integer.cljc:83), which portable `py/try` cannot catch. Provide a versioned pure result-returning numeric interface that converts only recognized integer refusals into qualified data. The portable prelude unwraps success or constructs and raises the appropriate guest exception. Do not make evaluator dispatch Python-aware or pass guest exception callbacks into the host module.

Wire every guest-reachable numeric path through it, including key normalization and hashing—not just arithmetic operators. Add `MemoryError` beneath `Exception` in the base exception hierarchy.

Acceptance must catch each mapped failure in guest code, continue afterward, and verify `finally` behavior and unchanged dictionary state after failed key normalization. Host exception text must not determine the guest outcome. Real host exhaustion, cancellation, and unexpected defects remain operational failures, as §8.5.4 already specifies.

**Remaining sequence, using the document’s S0–S7 numbering:**

1. **S0 amendment:** freeze these three rulings, profile changes, key bytes, and refusal contracts.
2. **S1 follow-up plus the exception portion of S4:** establish the pure refusal-result interface and prelude translation.
3. **S5 follow-up:** land Q1 float identity and Q2 hexadecimal keys; cover existing hash/key failures.
4. **S6 prerequisite checks:** confirm carrier tracing, pinning, and scalar boundaries before broader source exposure.
5. **S2:** literal and boundary integration. This also needs a correction to the decimal-parse lowering rule: compiler-generated reconstruction of a hexadecimal literal must not accidentally impose the decimal digit limit. Use a canonical power-of-two reconstruction spelling while enforcing source-spelling limits separately.
6. **S3:** exact operators and augmented forms, using the established exception bridge.
7. **Remaining S4, then remaining S6:** conversions/comparisons and complete heap/portability coverage.
8. **S7:** full C1/C3 integration and resource-limit matrix.

The work previously orchestrated as “C3-S2” was the table’s **S5** scope; keep that distinction explicit in implementation assignments.

Read-only assessment; no files changed or suites run.
[2mtokens used[0m
1,517,870
Completed-GMT: 2026-10-04 06:45:17 GMT
Completed-Local: 2026-10-04 13:45:17 +07

**Q1 — Adopt canonical float64 content equality for float `is`; remove host equality from that decision.**


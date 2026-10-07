Completed-GMT: 2026-10-04 06:47:02 GMT
Completed-Local: 2026-10-04 13:47:02 +07

# Architect rulings: three Python numeric questions (fable-5-1, independent)

Read-only; nothing edited, no suites run. Claims about Dart `=` are from the brief, not verified here.

## Verdicts

- **Q1:** (a). `py/is` on non-cell values is Jing content identity: one NaN, signed zeros distinct. The seam is a new `data/content=` export over the existing public `cbor/content=`.
- **Q2:** (a). Key integers in radix 16; the shape and tag stay, only the digit text changes. It lands with Q1 in one small slice before S3.
- **Q3:** The mapping must land before S3, as its own slice (S3a). `:bit-limit` maps to `MemoryError`, `:digit-limit` to `ValueError`, never `OverflowError`. Order: S2b (Q1+Q2), S0, S3a, S2, S3, S4, S6, S7.

## Q1. `py/is` on floats

**Rule.** For two non-cell values, `a is b` exactly when content addressing gives them the same identity. For floats that means the same binary64 bits after NaN canonicalization:

| Expression | Result here | CPython |
|---|---|---|
| `nan is nan2` | True | False (stated departure, same family as the one-NaN key) |
| `x is x` for a NaN | True | True |
| `0.0 is -0.0` | False | False |
| `1.0 is 1.0` | True | implementation-defined |
| `1 is 1.0`, `True is 1` | False | False |

Tuples recurse, so `(nan,) is (nan2,)` is True and `(0.0,) is (-0.0,)` is False. Cells still compare same-cell.

**Why (a) and not (b).**
- `0.0` and `-0.0` are observably different values (`1/x`, `copysign`, repr) with different addresses under 8.5.5. Answering `is` True for them would make `is` coarser than the address, and it also contradicts CPython.
- 8.11's "same type and value" has to mean the finest value distinction, which is content. `==` stays the coarser numeric relation and is untouched.
- Host `=` is not even deterministic within the JVM: it is true for one boxed NaN and false for two, as 8.5.4 already notes for keys.

**Seam.**
- `dao.jing.cbor/content=` (cbor.cljc:903) is already public and is defined as "equal exactly when content addressing would give the same identity". It handles the JS `Float64` carrier, JVM and Dart doubles, NaN, zero sign, integer width and recursion.
- Add one `:pure` export `content=` (arity 2) to `yin.vm.data`, next to `float64` and `float-value`.
- The prelude becomes `[py/is (fn [a b] (data/content= a b))]`, and `py/is-not` its negation.
- Do not re-derive the rule in prelude arithmetic. That would be a second definition of identity that can drift from the codec.
- This does not conflict with "Jing's `exact-key` is not reused". That sentence is about the guest key contract; `is` is the storage-identity contract by definition.

**Engineer cautions.**
- The export must keep closures, continuations and refs on host `=`: `(or (identical? a b) (if <callable or ref> (= a b) (cbor/content= a b)))`. `content-key` recurses into any `map?` and refuses unsafe JS integers, so a closure's environment must never be walked.
- Check `data`'s module-version rule for an additive export.
- `vm/primitives` `=` is unchanged.

**Cross-host test.** Rows in `prelude_parity_test` (parserless, so they run on JVM, Node and Dart):
- `-0.0` built as in row 690; `py/is` of `0.0` and `-0.0` is false, while `py/eq` is true.
- NaN built two ways (`inf - inf`, and decoded from Jing's canonical NaN bytes as at line 897); `is` is true. The same binding is true.
- `1.0 is 1.0` true; `1 is 1.0` false; `true is 1` false; `inf is inf` true; `inf is -inf` false.
- 2^53+1 reached by two different computations is true.
- The two tuple rows above.
- A law over all pairs in the table: `(py/is a b)` equals `(= (jing/canonical-bytes a) (jing/canonical-bytes b))`, computed on each host.
- Mutation evidence: reverting to `(= a b)` must fail the zero row and the two-NaN row on the JVM.

**Doc text for 8.5.4**, replacing the paragraph "Integer `is` is value-based through the unchanged `py/is`...":

> `is` on non-cell values is content identity: `py/is` is `data/content=`, Jing's kind-strict content equality, so two values are `is`-equal exactly when content addressing gives them one identity, on every host (ruling 8, section 8.11 "same type and value"). Integers are `is`-equal at any magnitude regardless of carrier; `True is 1` and `1 is 1.0` are false. For floats the value is the binary64 content: `0.0 is -0.0` is false, as in CPython, and every NaN is one value, so `float('nan') is float('nan')` is true where CPython answers false. This is the float-identity departure, the same family as the one-NaN key. Host `=` is not used: it merges signed zeros on the JVM and splits NaNs by box. `==` is unaffected. No CPython allocation or interning fidelity is promised.

**Adjacent finding, not ruled here.** CPython's `in` and `list.index` use `is or ==`. If the prelude uses `py/eq` alone, `x in [x]` is False for a NaN where CPython gives True. Under this rule the shortcut would be cheap to add; someone should decide it in S4.

## Q2. Integer keys past the digit limit

**Rule.** Key text is radix 16, through `(integer/format n 16)`. The module already exempts power-of-two radixes from `::max-digits` (`int-format`, `limited?`).

**Exact form.** `[:py.numeric/finite numerator-hex denominator-hex]`:
- lowercase, no prefix, no leading zeros, `-` only on a negative numerator, `"0"` for zero;
- denominator positive, `"1"` for integers;
- `[:py.numeric/infinite "+"|"-"]` and `[:py.numeric/nan]` are unchanged.

The tag is kept: there are no deployed stores, so this is a clean break with the radix stated in the doc.

| Value | Key |
|---|---|
| `1`, `1.0`, `True` | `["1" "1"]` |
| `1.5` | `["3" "2"]` |
| `-0.75` | `["-3" "4"]` |
| `10` | `["a" "1"]` |
| 2^53 | `["20000000000000" "1"]` |
| 2^53+1 | `["20000000000001" "1"]` |
| `5e-324` | `["1" "4" followed by 268 zeros]` |

**Effect on equalities.** None. Exact formatting in any radix is injective on canonical integers and the reduced rational is unchanged. So `1`, `1.0` and `True` still share a key, `0`, `False` and `+/-0.0` still share a key, and 2^53 and 2^53+1 stay distinct.

**Why not (b) or (c).**
- (b) records a departure that four call-site edits avoid.
- (c) adds a profile knob and keeps quadratic decimal formatting on every big key, which is the cost CPython's limit exists to avoid and deliberately does not impose on hashing.
- Hex also removes the "composition must admit 324 digits for float keys" constraint. Only the 1075-bit requirement remains, and a breach there surfaces as `MemoryError` through Q3's seam.

**Migration cost.**
- Prelude: four `integer/format` calls, three in `py/float-key` and one in `py/key`.
- `prelude_parity_test.cljc`: about 20 expectations at lines 686 to 712, plus the `two-1074`, `two-1022` and `max-finite` helper strings.
- `float_address_test.cljc`: its key occurrences.
- The canonical-bytes golden in dict-keys-test: regenerate once on the JVM, then check on Node and Dart.
- New row: `(py/key (integer/shift-left 1 20000))` succeeds under `max-digits 4300` and differs from the key of that value plus 1.
- Doc: rewrite the 8.5.4 "Known limits" bullet (drop the digit sentence and the 324-digit clause) and the ruling-6 shape line.

**Slice.** S2b, together with Q1, before S0 and S3, so the goldens move once before more accumulate.

## Q3. Guest exceptions for integer refusals

**It must precede S3.** S3 replaces `py/checked-*` (OverflowError at 2^53) with module calls. From that commit, `1 << 200000` in source would kill the run uncatchably, which violates ruling 11 ("catchable and deterministic") and `try/except` semantics.

**Mechanism.** The prelude is guest code and cannot catch a host `ex-info`, and the VM has no path from a host refusal to a guest exception. Ruling 5 already says expected failures are "returned as qualified data for the prelude to translate"; the module at version 1 throws instead. So:
- Integer module version 2: the two limit reasons return `:yin.vm.integer/bit-limit` or `:yin.vm.integer/digit-limit` instead of throwing. Results are integers, strings or pairs, never keywords, so the sum is untagged.
- One prelude translator, `py/int-result`, wraps every limit-capable call (`add`, `sub`, `mul`, `pow`, `shift-left`, `parse`, `format`, `normalize`) and raises the guest exception.
- Add `["MemoryError" 'py.b/MemoryError 'py.b/Exception]` to the builtins table; it is absent today.
- Prechecking limits in guest code is rejected: it duplicates the module's admission arithmetic, and `pow` cannot be prechecked without it.
- Astra or the engineer may prefer a different carrier for the refusal; the constraint is one seam and no engine change.

**Mapping table.**

| Module reason | Reached by | Guest exception | Handled where |
|---|---|---|---|
| `:bit-limit` | `+ - * ** <<`, `int()`, float keys under small limits | `MemoryError` | `py/int-result` (S3a) |
| `:digit-limit` | `str`, `repr`, `int(str)`, decimal `format` | `ValueError`, "Exceeds the limit (N digits) for integer string conversion" | `py/int-result` (S3a); exercised in S4 |
| `:syntax` | `int(str, base)` | `ValueError`, "invalid literal for int() with base B" | prelude validates before the call (S4) |
| `:out-of-range` (radix) | `int(x, base)` | `ValueError`, "int() base must be >= 2 and <= 36, or 0" | prelude check before the call (S4) |
| `:zero-division` | `//`, `%`, `divmod` | `ZeroDivisionError`, "integer division or modulo by zero" | existing prelude precheck |
| `:negative-count` | `<<`, `>>` | `ValueError`, "negative shift count" | existing `py/shift-check` |
| `:negative-exponent` | `**` | not an error: float-result path (ruling 10) | prelude dispatch (S3) |
| `:wrong-type`, `:arity` | prelude defect only | none; the run fails with the host refusal | stays a throw |

**CPython reference.**
- CPython 3.9.6 has no digit limit at all. The 4300-digit limit and its `ValueError` arrived in 3.11 and the 3.9.14 security backport, so the `:digit-limit` row follows that later behavior deliberately, as ruling 11 already decided.
- An oversized integer result in 3.9.6 is `MemoryError` when allocation fails. `OverflowError` ("too many digits in integer") appears only at the address-space ceiling, so `MemoryError` is the faithful mapping for a profile bit limit.
- `OverflowError` stays reserved for conversions, which are not module refusals: `float(10**400)`, `int(inf)`, and a huge base with a negative exponent. `int(nan)` is `ValueError`. These are S4.

**Order of the remaining slices.**

1. **S2b** (new; amends the landed C3-S2): Q1 `data/content=` and `py/is`, Q2 hex keys, golden regeneration, doc edits.
2. **S0**: freeze contracts after S2b, so the frozen bytes include hex keys, the `is` table and the Q3 outcome table. Fixtures only.
3. **S3a** (new): Q3 seam. Module v2, `MemoryError`, `py/int-result`. Testable at prelude level without operators, for example a float key under a 512-bit composition raising a catchable `MemoryError`.
4. **S2**: literals and boundary integration. Needs S3a because big literals lower to `integer/parse`.
5. **S3**: operators. Needs S3a; S2 first lets its tests be written from source.
6. **S4**: conversions and comparisons; first source-level exercise of `:digit-limit`.
7. **S6**: heap and portability. It touches no prelude code, so it can run alongside S3 and S4.
8. **S7**: integration gate.

**One flag for S2.** Big literals lower to `parse` over a canonical *decimal* string, so a huge hex literal would hit the digit limit where CPython exempts it. This is the same defect shape as Q2 and S2 should rule on it; I have not ruled it here.

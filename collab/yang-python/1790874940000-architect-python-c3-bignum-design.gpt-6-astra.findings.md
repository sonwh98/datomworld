I’ll read the committed architecture and runtime code at `111a9823`, then develop the C3 design and per-host acceptance contracts. I’ll leave the working tree untouched and distinguish implementation gaps from issues that need architectural rulings.


The existing rulings put Python semantics in the prelude and permit profiled pure primitives for numeric operations. The key C3 distinction is between host-local acceleration and portable representation: a JVM’s wider native integer range must not change canonical bytes or code addresses.


The committed Jing codec already supports arbitrary integers with CBOR tags 2/3, so C3 does not need a new wire payload kind. It also normalizes host-specific integer carriers to the same bytes. I recommend using immutable bignum values inside existing cells, rather than giving each bignum a separate mutable cell identity.


Two existing assumptions need to change for C3: `py/key` converts numeric keys to double, which would merge distinct large integers, and UCF’s scalar check relies on host `number?`, which is insufficient for all supported bignum carriers. The acceptance plan will test both, along with the boundary cases at `2^53`, signed 64-bit limits, and CBOR’s 64-bit limit.


Completed-GMT: 2026-10-01 17:21:02 GMT
Completed-Local: 2026-10-02 00:21:02 +0700>

**C3 should extend Python’s integer semantics over Jing’s existing exact-integer representation. It should not introduce another bignum wire format, a Python-specific AST tag, or a mutable heap identity for each integer.**

Read-only findings against committed `111a982335a9e6a534866b2a59a1c6d04dcb13ad`, plus the two requested collaboration reports. No files edited; no tests executed. Recommendations below are proposed architect rulings awaiting the paired cross-ruling, not recorded converged decisions.

### 1. Findings and architectural disposition

The important distinction is between **four evaluators**—AST walker, semantic, de Bruijn stack, de Bruijn register—and **three hosts**—JVM, JavaScript, Dart. Acceptance requires their full twelve-cell execution matrix.

| Finding in committed state | Classification | C3 disposition |
|---|---|---|
| Python arithmetic and literal lowering restrict integers to `[-2^53, 2^53]`. | Intentional C1 limitation. | Replace range overflow with exact promotion. |
| Jing already supports native exact integers and host bignums, encoding larger values with CBOR tags 2/3. | Existing architectural capability. | Reuse; no new payload kind. |
| `py/key` normalizes numbers through `(* 1.0 (py/num k))`. | Valid only within the restricted integer domain; defective if carried into C3. | Replace with exact numeric normalization before enabling large integers. |
| Python numeric recognition uses `data/number?`; UCF `scalar?` uses host `number?`. | Implementation gap for supported non-`Number` bignum carriers. | Add explicit exact-integer recognition at the relevant boundaries. |
| Jing `num-hash` explicitly uses a host-specific hash. | Correct internal contract, unsuitable for guest `hash()`. | Introduce a separate Python numeric hash contract. |
| Jing numeric equality makes canonical NaNs equal and gives them a total ordering. | Correct storage contract, different from Python semantics. | Do not substitute it wholesale for Python comparison. |
| Engine lift still refuses reachable heap cells as `:yin.k/non-portable`, kind `:cell`. | Existing deferred heap-migration work. | Preserve the refusal; scalar bignum portability does not establish Python-task migration. |
| DaoStream CBOR deliberately has a narrower domain than Jing and admits safe numbers. | Intentional protocol separation. | Use a declared boundary adapter; do not assume Jing support widens stream codecs. |
| Float rendering documents incomplete Python exponent-format parity. | Existing deferred work. | Keep separate from exact integer rendering and conversion correctness. |

Relevant committed implementation locations are [prelude.cljc](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc), [lower.cljc](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc), [cbor.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/cbor.cljc), [engine.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc), and [data.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/data.cljc). These links identify files; the findings concern their committed versions.

### 2. Representation: one integer domain, several physical carriers

**Recommended guest representation:** an exact integer scalar, untagged at the Python value level, regardless of magnitude. Floats remain `{:py/float x}`.

“Heap bignum” should mean an immutable, host-allocated arbitrary-precision value, which can be the payload of an existing task cell. It should **not** mean an additional `cell/new` for every integer result.

For example:

```clojure
:heap
{cell-id {:value <exact integer scalar>
          :seal existing-seal}}
```

There is no `:py/bigint` wrapper, separate digit table, bignum reference seal, or bignum allocation counter. Host bignum carriers are supported scalar representations, just as they already are for Jing.

This preserves the untagged-int ruling more completely than introducing a second Python integer representation.

| Host | Normalized native carrier | Bignum carrier | Promotion rule |
|---|---|---|---|
| JVM | Signed `long`, `[-2^63, 2^63−1]` | `BigInteger` or Clojure `BigInt`, normalized at module boundaries | Promote before overflowing the operation. |
| JavaScript | `Number` only in `[-(2^53−1), 2^53−1]` | Native `BigInt` | Promote before an operation could lose integer precision. |
| Dart VM | Signed `int`, `[-2^63, 2^63−1]` | `BigInt` | Promote before native arithmetic wraps. |
| Dart compiled to JavaScript, if claimed | Safe-integer fast path only | `BigInt` | Separate acceptance lane; never infer its behavior from Dart VM tests. |

Dart’s Smi/Mint distinction is an implementation detail within `int`, not a runtime-profile boundary. There is no implicit promotion from overflowing Dart `int` to `BigInt`. Dart’s official documentation also distinguishes native and web integer behavior. [Dart integer contract](https://api.dart.dev/dart-core/int-class.html), [Dart number representation](https://dart.dev/resources/language/number-representation).

**Important boundary correction:** although `2^53` itself is exactly representable in JavaScript, Jing deliberately rejects unsafe integral `Number` inputs. Therefore JavaScript must represent **both `2^53` and `−2^53` as `BigInt`**. This changes their carrier, not their mathematical value or existing canonical integer bytes.

Arithmetic results may demote to the host’s normalized native carrier when they fit. That physical choice must not affect:

- Python type, equality, truthiness, rendering, or hash;
- code addresses or serialized bytes;
- task-cell allocation count;
- guest exceptions or effect traces.

A first implementation may conservatively compute through bignums and demote afterward. Native fast paths are optional optimizations requiring proof against the exact path. Checking after a rounded JavaScript operation or a wrapped Dart operation is insufficient.

### 3. Canonical wire form and addressing

**No new Jing payload kind is required.** The committed `int-wire` already specifies the necessary encoding:

| Mathematical integer `n` | Canonical CBOR |
|---|---|
| `0 ≤ n < 2^64` | Major type 0, shortest argument width |
| `−2^64 ≤ n < 0` | Major type 1, argument `−1−n`, shortest width |
| `n ≥ 2^64` | Tag 2 over a byte string containing `n` |
| `n < −2^64` | Tag 3 over a byte string containing `−1−n` |

The tag-3 payload is **not `abs(n)`**.

The digit contract is unsigned base-256, most-significant byte first, minimal length, no leading zero byte. Zero uses the ordinary integer encoding; there is no negative zero. Bignum tags are noncanonical when the value fits major type 0/1.

Illustrative existing-contract fixtures:

```text
2^64      -> c2 49 01 00 00 00 00 00 00 00 00
−2^64−1   -> c3 49 01 00 00 00 00 00 00 00 00
```

Host limb width, signed byte-array conventions, native class name, and native/bignum promotion threshold never enter these bytes. In particular, strip Java’s sign-padding byte when producing an unsigned magnitude; use Jing’s existing implementation rather than duplicating it.

Ingress retains the existing sequence: validate shape, decode without loss, canonical re-encode comparison, and address verification at the appropriate boundary. Leading zeros, unnecessary tags, malformed payload types, and trailing bytes must refuse rather than silently become alternative accepted encodings.

**Identity and addressing:**

- A literal remains an ordinary `:literal` row containing an exact integer.
- Row IDs remain `segment-key` of the existing row body.
- Instruction images retain their existing canonicalization and contract stamps.
- Host-native `42`, host bignum `42`, and a decoded integer `42` produce identical integer bytes.
- `42` and Python `42.0` remain distinct content despite comparing numerically equal.
- Decimal, hexadecimal, binary, and octal spellings of the same integer lower to the same literal content; provenance remains occurrence data.
- Existing Jing fixtures must remain unchanged. Do not regenerate them to bless a new encoding.

**UCF:** amend §7.5.1 text to say that its scalar number arm includes all Jing-supported exact-integer carriers, and fix implementation predicates accordingly. No new marker or frame grammar is needed. The new arithmetic primitive profile and Python runtime revision must be declared and matched during admission.

A scalar bignum may round-trip wherever the existing UCF implementation supports the containing state. A bignum inside a heap cell remains subject to the existing cell-lift refusal.

**Stream transport:** Jing CBOR and DaoStream CBOR are different contracts. For C3, recommend transporting portable numeric snapshots through an explicitly selected adapter carrying canonical Jing bytes, following the established Jing stream-boundary pattern. Do not add implicit serialization to `stream/put`, and do not silently widen Transit or DaoStream CBOR. A composition without the adapter refuses unsupported values explicitly.

### 4. Arithmetic placement and primitive contract

Add a separately installed, versioned **pure integer data module**, provisionally named `integer`, following `yin.vm.data`:

- Explicit registry installation by the composition.
- Required by the Python runtime profile.
- Declared arities, `:pure`, no effects, no host state.
- No changes to `vm/primitives`, AST grammar, opcodes, or evaluator dispatch.
- No callbacks into Python and no access to the VM heap or store.
- Expected arithmetic failures returned as qualified data; the prelude translates them to Python exceptions.

The exact symbol names are implementation vocabulary, but the division of responsibility should be fixed:

| Portable Python prelude | Profiled pure numeric primitives |
|---|---|
| Python type dispatch and bool coercion | Exact integer recognition and normalization |
| Guest exception construction | Exact add, subtract, multiply, negate, compare |
| Python floor/modulo sign adjustment | Truncating quotient/remainder pair |
| Operator result-type selection | Unbounded signed bit operations |
| Integer exponentiation loop and special cases | Checked shifts and bit length |
| Parsing syntax, accepted bases, underscores and whitespace | Exact digit accumulation and radix formatting |
| Numeric key and guest hash policy | Exact binary64 decomposition/conversion kernels |
| Object protocols and overload dispatch as supported | Correctly rounded integer-ratio to binary64 kernel |

Avoid one host function that implements the entire Python operator system. Conversely, avoid reimplementing large-integer multiplication as millions of guest additions merely to keep arithmetic physically in the prelude.

All guest-integer paths must use this contract. Auditing only `py/checked-add` and `py/checked-mul` is insufficient: range calculations, slice clamping, repetition, comparisons, truthiness, `sum`, and numeric keys currently contain raw arithmetic assumptions.

**Division and modulo**

Let the primitive return truncation-toward-zero `(q₀,r₀)` with:

```text
a = b*q₀ + r₀
```

For nonzero `b`, the prelude computes:

```text
if r₀ != 0 and sign(r₀) != sign(b):
    q = q₀ - 1
    r = r₀ + b
else:
    q = q₀
    r = r₀
```

This produces Python’s floor quotient and divisor-signed remainder. Zero division is checked before primitive invocation. Native signed-minimum divided by `−1` must promote.

The contract includes `a == b*q+r`, `abs(r)<abs(b)`, and a nonzero remainder having the divisor’s sign.

**Bits and shifts**

Use an infinite signed two’s-complement mathematical model:

```text
~a       = −a−1
a << k   = a * 2^k
a >> k   = floor(a / 2^k)
```

Negative counts raise `ValueError`. Counts themselves are arbitrary integers; never narrow them before checking their meaning.

For enormous right-shift counts, return `0` or `−1` from the operand’s sign without constructing `2^k`. For enormous left-shift counts, zero remains zero; nonzero operands undergo explicit result-size admission before allocation.

JavaScript `Number` bit operators and Dart fixed-width overflow are not implementations of this contract. Python’s specified signed-bit and shift behavior supports these laws. [Python integer operations](https://docs.python.org/3.11/library/stdtypes.html#bitwise-operations-on-integer-types).

**Exponentiation**

For integer base and nonnegative integer exponent:

- Return an exact integer.
- Use exponentiation by squaring through exact primitives.
- Handle exponent zero, and bases `0`, `1`, and `−1`, before expensive work.
- Never narrow the exponent to a host `int`.
- Admit resource growth before constructing a large result.

For negative integer exponents, preserve Python’s float-result branch, including zero-base errors and checked conversion behavior. Do **not** calculate an enormous positive power and then invert it.

That branch and mixed float powers need one pinned binary64 implementation contract. Host `pow` calls are not automatically bit-identical, and a squaring loop is not automatically equivalent to the reference runtime’s float power. General fractional powers and complex results remain explicitly deferred unless separately implemented. Python specifies the result-type change for negative integer exponents. [Python power operator](https://docs.python.org/3.11/reference/expressions.html#the-power-operator).

### 5. Conversions and mixed arithmetic

C3 must separate exact integer operations from intentional floating-point rounding.

**Literal lowering and `int()`**

Parse integer digits directly into exact integers. Never parse through `double`, `parseFloat`, or a native-width parser and then convert to bignum.

Keep source-literal syntax validation separate from `int(string, base)` validation. The latter needs its own rules for signs, prefixes, bases `0` and `2..36`, underscores, whitespace, and the pinned Python edition’s accepted decimal digits.

`int(finite_float)` decodes binary64 and truncates toward zero into an exact integer. It must not pass through JVM `long` or Dart `toInt` when the value exceeds their range. Infinity raises `OverflowError`; NaN raises `ValueError`.

**`float(integer)`**

Use round-to-nearest, ties-to-even binary64 conversion. Guard against double rounding: determine the significant bits and guard/sticky information from the exact integer, then round once. A conversion outside finite range raises Python `OverflowError`, rather than accepting a host’s infinity result.

**Integer `/`**

Compute a correctly rounded binary64 result from the exact ratio. Do not convert the two operands independently first:

```python
(10**400) / (10**400)   # must be 1.0
```

The conversion kernel must specify subnormal rounding, signed underflow to zero, and overflow.

**Mixed float arithmetic**

Convert integer operands through the checked integer-to-float conversion and apply the existing float operation. Preserve the tagged result even when its value is integral.

For mixed `//` and `%`, retain the C1 float algorithms and their signed-zero fixes. In particular:

- keep `py/zero?` rather than host equality with integer zero;
- keep `py/zero-like`;
- retain multiplication by `−1.0` for sign-preserving negation on ClojureDart.

**Comparison is different from conversion.** Compare integers and finite floats by their exact mathematical values, using the float’s exact binary rational. Never round the integer to compare it.

**`str()` and `repr()`**

Integer formatting is exact decimal text, with a minus sign only for negative values, no suffix, no exponent notation, and zero rendered `"0"`. Both guest builtins and the boundary snapshot renderer must use the same contract. Default host printing is not the specification.

Integer decimal conversion limits must be explicit profile data. They must not inherit environment variables, process-global CPython settings, or host library defaults.

### 6. Equality, dictionary normalization, and hashing

Three distinct contracts must remain distinct:

1. Python numeric equality.
2. Dictionary/set key normalization.
3. Canonical storage content identity.

For Python equality, coerce bool to exact `0`/`1`; compare finite numbers exactly; preserve Python’s unordered NaN behavior. Thus:

```python
1 == 1.0 == True
2**53 == float(2**53)
2**53 + 1 != float(2**53)
```

The storage codec’s `num=` and `num-compare` need Python wrappers because their NaN contracts differ.

**Dictionary keys**

Replace the current double conversion with a portable exact key. Recommended finite-numeric form:

```clojure
[:py.numeric/finite numerator-decimal denominator-decimal]
```

Here numerator and positive denominator are reduced, with canonical decimal strings. Consequently:

```text
True, 1, 1.0       -> same key, 1/1
False, 0, ±0.0     -> same key, 0/1
1.5               -> 3/2
2^53 and 2^53+1    -> different keys
```

Strings avoid host bignum equality/hash differences inside the index map. Equivalent sign/magnitude byte encodings would work, but two representations should not be introduced.

Normalize tuple elements recursively using the existing tuple-key convention. Keep original keys in the insertion-order vector, including the first inserted key when an equal key updates its value.

Infinities have distinct signed keys. NaN identity-sensitive dictionary behavior is an existing float-object issue; it must not be “fixed” by collapsing every NaN into one rational key. Recommend documenting it as a separate limitation rather than making integer completion claim full float-key fidelity.

**Guest numeric `hash()`**

Recommend a fixed modulus:

```text
P = 2^61−1
h(n) = sign(n) * (abs(n) mod P)
replace −1 with −2
```

Use it on every host, including JavaScript. The returned Python integer may itself use a bignum carrier there.

For finite floats, hash their exact reduced rational using the same modulus and modular inverse of the denominator, with the same sign and `−1` adjustment. Bool hashes as `0`/`1`. This ensures equal finite numeric values have equal hashes.

This is a Python-profile decision using the published numeric-hash construction, not a promise to match every CPython platform width. [Python numeric hashing](https://docs.python.org/3.11/library/stdtypes.html#hashing-of-numeric-types).

Do not expose:

- host `hash`;
- Jing `num-hash`;
- a content digest;
- heap IDs.

Hash collisions do not establish key equality.

**Interning:** no new small-int intern table. Existing untagged value semantics suffice. CPython allocation identity and interning fidelity remain outside the support claim. Ensure `py/is` does not accidentally expose native versus bignum carrier differences; any deterministic integer identity convention must be applied consistently across hosts.

### 7. Heap, reclamation, aliasing, and isolation

Bignums are immutable leaves. They contain no task-cell references and need no `gc-children` expansion.

The current collector already traverses cell contents as data and distinguishes kernel positions from guest values. Preserve that distinction. An arbitrary guest map that resembles a numeric wrapper must not acquire special traversal behavior.

Required integration:

- Recognize exact bignum carriers as scalar leaves consistently.
- Preserve existing `gc-roots` coverage for environments, registers, continuations, stores, parked work, and extra allocation roots.
- Do not allocate task cells merely because a numeric result crossed a host boundary.
- Do not expose mutable limb buffers or memoization caches through values.

Aliasing remains ordinary Python behavior:

```python
x = 10**100
y = x
x += 1
```

`y` retains the previous integer; `x`’s binding cell receives a new immutable value. An intentionally shared binding cell still shares the binding according to existing box semantics. Continuation re-entry does not roll back cell writes.

**Pinning remains cell-based.** A cell reference sent through a stream or FFI request stays pinned for task life under the landed rule. Collection follows that cell’s current content, including any references nested alongside its integer payload.

Sending an immutable integer value does not require pinning an unrelated cell that once held it. Replacing a pinned cell’s integer payload does not require retaining every historical bignum.

Host GC reclaims unreachable numeric objects. Task GC reclaims unreachable cells. Their physical memory timing can differ without producing guest-visible callbacks, weak-reference behavior, or finalizers.

Bignums introduce no new authority. Copying an immutable integer between tasks is safe; copying a sealed cell reference does not authenticate it in another task.

### 8. Determinism, resource domain, and diagnostics

Arbitrary precision removes the C1 mathematical magnitude bound; it does not promise unlimited memory or bounded execution time for every operand.

The runtime/support profile should explicitly identify:

- integer and binary64 conversion semantics;
- numeric hash modulus;
- integer-text conversion policy;
- admitted numeric resource limits;
- required pure modules and boundary adapters.

Resource checks must use portable quantities—bit lengths, digit counts, and specified work estimates—not JVM object sizes, JavaScript engine thresholds, or available host memory.

A large synchronous host primitive is not made interruptible by calling it `:pure`. Initial C3 may use bounded, admitted synchronous kernels, but it must make no mid-primitive safepoint or hard latency claim. A later bounded-step numeric implementation can return explicit work state; it must not introduce hidden callbacks or a host stack that masquerades as a portable continuation.

Diagnostic categories:

| Condition | Outcome |
|---|---|
| Integer exceeds the old C1 boundary | Exact promotion, no error |
| Division by zero | Guest `ZeroDivisionError` |
| Negative shift count | Guest `ValueError` |
| Invalid integer conversion text | Guest `ValueError` |
| Nonfinite float-to-int conversion | Specified guest exception |
| Integer-to-float overflow | Guest `OverflowError` |
| Explicit numeric resource budget exceeded | Qualified resource-limit outcome, distinct from arithmetic overflow |
| Missing numeric module/profile | Admission refusal before execution |
| Unsupported transport representation | Qualified boundary refusal |
| Malformed/noncanonical CBOR | Existing codec refusal |
| Host allocation failure, timeout, cancellation | Operational outcome; no fabricated deterministic Python overflow |

Failure records identify the operation and applicable limit without embedding huge operand strings or host exception objects. Expected failures remain data, with no stderr leakage.

The six invariants are preserved: explicit composition and state; explicit control and failures; no callbacks; immutable values; separate lowering, execution and storage interpretation; and only existing explicit cell-reference edges.

### 9. Implementation slices and acceptance contracts

Each executable contract below runs on **all four VMs on JVM, Node, and Dart**. Native parser availability is a separate dimension: use normalized CST and parserless canonical rows where necessary. JVM-only source tests do not establish twelve-cell parity.

Keep the C1 end-to-end output/exception comparison style, and extend the portable parity corpus rather than replacing it with host-unit tests.

| Slice | Setup | Action | Required assertion |
|---|---|---|---|
| **S0 — Freeze contracts** | Existing Jing fixtures; integer boundary values around `2^53`, `2^63`, `2^64`; normalized Python CST fixtures. | Specify profile, expected bytes, literals, hashes and outcomes. | No existing canonical fixture changes; supported and deferred claims explicit. |
| **S1 — Exact carriers and pure module** | Install the module explicitly; also construct a registry without it. Exercise native and bignum carriers for equal values. | Add/subtract/multiply/negate/compare across boundaries and demote results. | Exact equal results across hosts; no rounded intermediate, wrap, host exception, or extra task-cell allocation. Missing module refuses. |
| **S2 — Literal and boundary integration** | Decimal and radix spellings of large equal values, differently chunked sources, and malformed CBOR cases. | Lower, project, encode, transfer, decode and reproject. | Equal canonical rows, bytes and hashes; occurrence metadata remains distinct; malformed encodings refuse. |
| **S3 — Integer operators** | Sign combinations, zero divisors, huge shift counts, positive powers. | Execute operators and augmented forms through the prelude. | Python results and exceptions; algebraic laws; every old C1 arithmetic regression still passes. |
| **S4 — Conversions and comparisons** | Binary64 ties, subnormals, overflow edges, huge integer ratios, NaN and infinities. | Convert, divide, compare, render, and execute mixed arithmetic. | Specified float bits, exact integer text, tagged float results, correct exceptions; no double-rounding or comparison-by-coercion. |
| **S5 — Keys and guest hashes** | Adjacent large integers, equal int/float/bool keys, tuple keys and deliberate hash collisions. | Insert, update, retrieve and iterate dicts/sets; call numeric `hash()`. | Correct cardinality and insertion order; equal numeric hashes cross-host; collisions remain distinct keys. |
| **S6 — Heap and portability** | Very low GC threshold, closures retaining integers, shared cells, pinned outgoing refs, unreferenced cells. | Force collection, rebind, resume local continuations, round-trip scalar UCF values, exercise transport adapters. | Live values survive; dead cells sweep; pinned cells survive; scalar transfer succeeds; unsupported cell lift and raw transport refuse honestly. |
| **S7 — Integration gate** | Full C1+C3 corpus, fixed-seed generated operands, profile mismatch and resource-limit fixtures. | Run twelve execution lanes and cross-host serialization routes. | Equal outputs, exceptions, bytes and addresses within the declared profile; no new kernel dispatch; declared limitations remain visible. |

The boundary and semantic corpus must include these specific detectors:

- `2^53−1`, `2^53`, `2^53+1`, and corresponding negatives.
- `2^63−1`, `2^63`, `−2^63`, and negation/division of `−2^63`.
- `2^64−1`, `2^64`, `−2^64`, `−2^64−1`.
- Promotion followed by cancellation back to `0`, `1`, and `−1`.
- `divmod(-7,3)==(-3,2)` and `divmod(7,-3)==(-3,-2)`.
- `~(1<<200)==-(1<<200)-1`, `-3>>1==-2`, and huge right shifts.
- `0**0`, large positive powers, negative exponents, and zero to a negative exponent.
- `float(2^53+1)==float(2^53)` while those two integers remain unequal.
- `(10**400)/(10**400)==1.0`.
- `int(1e100)` as an exact integer derived from the actual binary64 value.
- Dict entries for `2^53` and `2^53+1` remaining distinct.
- `{1: a, 1.0: b, True: c}` remaining one entry with the latest value.
- `hash(-1)==hash(-2)==-2`, with `-1` and `-2` still distinct dictionary keys.
- `print(4/2)` remaining `"2.0"` and all C1 signed-zero cases preserved.

For transport and addressing, test every directed host pair. Compare canonical bytes and explicit float bits, not host `=` alone.

Mutation evidence should disable promotion, replace exact key normalization with double coercion, omit floor adjustment, use `abs(n)` for tag 3, restore host `number?` as the only scalar gate, or narrow a shift count prematurely. Each mutation must fail its intended detector.

These contracts strengthen §13 laws 3–5, 7–9, 11 and 12 directly. They also preserve open extension: the new numeric capability is a runtime-profile module, not a Python branch in a VM.

### Owner decisions

1. **Integer representation:** adopt existing exact scalar carriers, untagged at the Python level. Bignums are immutable payloads inside existing cells, not independently allocated mutable cell objects.

2. **Fast-path boundaries:** normalize JVM/Dart native integers to signed 64-bit range and JavaScript native integers to the safe range `±(2^53−1)`. Promote before the operation; permit exact demotion afterward.

3. **Wire contract:** reuse Jing’s existing major types 0/1 and tags 2/3 unchanged. Add no Python bignum wrapper, AST tag, or new numeric payload kind.

4. **UCF scope:** clarify the existing scalar domain and fix carrier recognition without adding a marker. Preserve the current heap-cell migration refusal; C3 does not close heap lift/lower.

5. **Primitive placement:** install a dedicated versioned `:pure` integer module through the runtime profile. Keep Python dispatch, exceptions, sign rules and policy in the prelude.

6. **Numeric keys:** replace double normalization with canonical reduced-rational keys using decimal strings. Preserve original insertion-order keys.

7. **Guest hashing:** fix `P=2^61−1` on all hosts; implement finite numeric hashing independently of host and Jing hashes.

8. **Integer identity and interning:** add no intern table and promise no CPython allocation fidelity. Apply one deterministic value-based convention to integer `is`, avoiding carrier-dependent results.

9. **Conversion scope:** require exact integer parsing/formatting, correctly rounded integer-to-binary64 and ratio conversion, and exact mixed comparison before C3 acceptance. Preserve C1 float tags and signed-zero behavior.

10. **Power scope:** require exact nonnegative integer powers. Pin the existing negative/integral-float power branch to an explicit deterministic implementation contract; leave general fractional powers, complex results, and three-argument modular `pow` separately declared unless included by cross-ruling.

11. **Resource policy:** require explicit composition-supplied numeric limits and conversion policy, pinned with the support profile. Choose deployment values from measured admission/latency tests; do not invent a universal bit ceiling or translate resource exhaustion into arithmetic overflow.

12. **Remote transport:** use an explicit canonical-Jing payload adapter for C3 numeric snapshots. Widening DaoStream’s general numeric domain requires a separate protocol ruling.

13. **Existing float limitations:** keep NaN dictionary identity and full float-rendering parity separately tracked. Neither should be silently advertised as solved by bignum support.

14. **Acceptance gate:** require all twelve evaluator/host lanes, directed cross-host byte/address tests, unchanged Jing fixtures, full C1 regression coverage, and targeted mutation evidence before declaring C3 complete.
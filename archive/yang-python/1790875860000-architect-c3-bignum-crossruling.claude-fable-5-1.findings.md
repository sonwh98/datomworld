Completed-GMT: 2026-10-01 17:33:00 GMT
Completed-Local: 2026-10-02 00:33:00 +0700

# Cross-ruling: C3 bignum decisions (second architect)

Read-only; nothing edited, nothing executed. I read the gpt-6-astra findings whole and checked its cited claims against master `111a9823`. Result: 12 concur (most with a binding condition), 2 decline in part (#2 and #3).

**On the premise about C2.** My C2 design made no ruling on integer identity, numeric dict keys or conversions. Its actual touchpoints with C3 are:

- Generator identity is the cell ref, and `py/is` stays host `=` on refs.
- Generators are dict keys by ref, through the non-numeric arm of `py/key`.
- C2 adds internal host-integer counters: the `py/iter-at` index and the sequence iterator's `:i`.
- Both phases edit the same prelude regions.

Each is flagged under the decision it affects.

## Rulings

**1. Integer representation: CONCUR.** Untagged exact scalars, with bignums as immutable values and no per-integer cell. Condition: each value has exactly one carrier per host (see #2). The prelude relies on host `=` against native literals such as `(= v 0)` and `(= a -1)` (`prelude.cljc:97`, `:820`), and that is only sound with a canonical carrier.

**2. Fast-path boundaries: DECLINE in part.** The ranges are right, but "permit exact demotion afterward" must be "require". On ClojureScript `(= 1 (js/BigInt 1))` is false and on Dart `int` and `BigInt` are unequal, so an undemoted result would split equality, `is` and every prelude zero test by carrier. The JVM bignum carrier is `clojure.lang.BigInt`, which Jing's `b->int` already returns (`cbor.cljc:228-230`); a raw `BigInteger` never leaves the module. JS holds ±2^53 as `BigInt`; I confirmed Jing refuses unsafe integral Numbers (`cbor.cljc:1005-1010`).

**3. Wire contract: DECLINE in part.** For values I concur: `int-wire` already emits major types 0/1 and tags 2/3 with the `−1−n` payload (`cbor.cljc:967-977`), and the two fixtures are correct. The claim that a big literal "remains an ordinary `:literal` row" with unchanged instruction images is wrong for the stack and register kernels:

- The de Bruijn canonical value table declares `:bigint` out of domain (`debruijn.cljc:92`).
- The image encoder refuses a JS or Dart bigint with `:unsupported-value` (`debruijn_code.cljc:303-319`).

Correction: a literal with |n| ≤ 2^53−1 stays a native literal. A larger one lowers to an application of the integer module's parse function over its canonical decimal string. That keeps every literal inside the existing domain on all four kernels, keeps one address per value across spellings, and needs no kernel contract change. Admitting bigint into the de Bruijn value domain is a separate kernel ruling.

**4. UCF scope: CONCUR, widened.** No marker; the cell-lift refusal stays. The carrier fix is not only UCF. `scalar?` (`engine.cljc:619-622`) also gates the heap trace (`:1938`) and `pin-refs` (`:2045`). On JS and Dart a bignum fails `number?`, so the trace would put it in its `seen` set and hash it. Whether the pinned ClojureScript hashes a `BigInt` is unverified, so this must be fixed and tested before a bignum can reach a cell. Fix `values/kind-of` (`values.cljc:194`) and `data-number?` (`data.cljc:411-415`) with it.

**5. Primitive placement: CONCUR, with three conditions.**
- Guest integers go only through the module, since host `+` on mixed carriers throws on JS and Dart. Internal counters stay VM primitives and never become guest values without normalization. C2 interaction: this covers the `iter-at` index and the iterator's `:i`.
- The conversion and rounding kernels are written once in portable code over a minimal per-host bignum shim, not three host implementations.
- The shim does not reach into `dao.jing.cbor` privates; Jing stays a passive codec.

**6. Numeric keys: CONCUR.** Reduced-rational decimal-string keys in one form. Jing's private `exact-key` already uses this shape (`cbor.cljc:697-707`), which supports the choice, but it collapses NaN and must not be reused. Additions: infinities get their own signed key, and NaN keeps today's behaviour (tracked under #13). Any native-key fast path waits for measurement and must use a host-independent bound. C2 interaction: none; generator keys take the non-numeric arm, which the rewrite must preserve along with the tuple and unhashable arms.

**7. Guest hashing: CONCUR, scope narrowed.** P = 2^61−1 on every host, never host or Jing hash (`num-hash` is host-specific by its own docstring, `cbor.cljc:697-721`). C3 implements `hash()` for int, bool and finite float only. Dicts do not consume it; they use #6. C2 interaction: `hash()` of an identity object (generator, instance, function) is refused as unsupported in C3, because the only available identity is the cell id, which this decision forbids exposing and which is not stable across lift.

**8. Integer identity: CONCUR.** No intern table. With #2's canonical carrier, `py/is` stays `(= a b)` with no integer-specific code, matching §8.11's "same type and value" rule. `True is 1` stays false because bools are host booleans. C2 interaction: none; generator `is` is ref equality on the same function.

**9. Conversion scope: CONCUR.** Exact parsing and formatting, single-rounding int-to-float, correctly rounded ratio division and exact mixed comparison are all acceptance conditions. Today's `py/truediv` (`prelude.cljc:856-864`) is correctly rounded only because both operands are exact doubles. Condition: the builtins the corpus assumes (`int`, `float`, `str`, `repr`, `divmod`, `hash`) do not exist in C1 (`prelude.cljc:1589-1602`), so each is named as a C3 deliverable or its tests are written at prelude level. C2 interaction: none.

**10. Power scope: CONCUR.** Exact non-negative integer powers. The pinned float contract is the prelude's existing squaring loop (`py/fpow`, `prelude.cljc:786-793`): bit-identical across hosts, and allowed to differ from CPython's `pow` in the last place, documented. A negative exponent converts the base through the checked conversion first, so a huge base raises `OverflowError`. Fractional exponents, complex results and three-argument `pow` stay deferred.

**11. Resource policy: CONCUR, with the surface fixed.** Limits are explicit data in the Python runtime profile, measured in bits and digits, with no implicit default. A breach of the bit-length limit is a guest `MemoryError` and a breach of the text-conversion digit limit is a guest `ValueError`, both catchable. The check is deterministic and profile-pinned, so it is a language outcome. It is never `OverflowError`. Real host exhaustion stays operational.

**12. Remote transport: CONCUR, de-scoped.** No general widening of the stream codec. C3 builds no new adapter; `dao.jing.stream` is the existing one (`dao/stream/cbor.cljc:10`, `:56`). C3 acceptance needs only that a raw remote put of a bignum refuses with a qualified outcome and that the Jing byte round trip holds. Printing and re-reading EDN is not a transport for bignums.

**13. Existing float limitations: CONCUR.** NaN key identity and float rendering parity stay separately tracked and unclaimed.

**14. Acceptance gate: CONCUR, with four adjustments.**
- Twelve lanes means four VMs on JVM, Node and Dart VM; Dart-to-JS is not claimed. Source-level tests run on the JVM and parserless forms on the other two.
- Golden byte fixtures checked on each host replace directed host-pair runs; they establish the same thing.
- Generated operands come from a checked-in table with CPython-computed expectations, not a host RNG.
- Mutation evidence is each listed mutation shown failing its detector once, recorded in the engineer's report, plus one added mutation: skip demotion.

Sequencing with C2: the module slices (S0, S1) can run alongside C2; the prelude and lowering slices land after C2 and audit its arithmetic sites.

## Converged rulings

1. Python integers are untagged exact scalars of any magnitude; bignums are immutable values with no per-integer cell, wrapper or intern table.
2. Each value has one carrier per host: native iff within signed 64-bit (JVM `long`, Dart `int`) or ±(2^53−1) (JS); otherwise `clojure.lang.BigInt` or host `BigInt`. Promote before the operation; demotion after it is mandatory.
3. Values use Jing's existing major types 0/1 and tags 2/3 unchanged, with no new payload kind or AST tag. A source literal beyond ±(2^53−1) lowers to the integer module's parse over its canonical decimal string; widening the de Bruijn value domain is a separate ruling.
4. No UCF marker and no change to the cell-lift refusal. Exact-integer carriers are recognized as scalars in the encoder, the heap trace, `pin-refs`, `values/kind-of` and `data/number?`, tested on Node and Dart before any bignum reaches a cell.
5. A versioned `:pure` integer module carries the exact kernels; Python dispatch, sign rules and exceptions stay in the prelude. Guest integers go only through the module; internal counters stay VM primitives. Kernels are written once over a per-host shim that does not depend on Jing's privates.
6. Numeric dict and set keys are reduced-rational decimal-string keys in one form; infinities get signed keys; NaN keeps current behaviour. Insertion-order keys keep the first inserted original.
7. Guest numeric hash uses P = 2^61−1 on every host for int, bool and finite float. Host hash, Jing hash and cell ids are never exposed; `hash()` of identity objects is unsupported in C3.
8. Integer `is` is value-based through unchanged `py/is`, made carrier-independent by ruling 2. No CPython allocation fidelity is promised.
9. Exact integer parsing and formatting, single-rounding int-to-float, correctly rounded integer `/`, and exact mixed comparison are required for acceptance. C1 float tags and signed-zero behaviour are preserved. New builtins are named deliverables.
10. Non-negative integer powers are exact. Float-result powers use the prelude's squaring loop as the pinned contract, with checked base conversion. Fractional, complex and three-argument `pow` are deferred.
11. Numeric limits are explicit Python-profile data in bits and digits with no implicit default. A bit-length breach is guest `MemoryError`; a digit-limit breach is guest `ValueError`; neither is `OverflowError`. Host exhaustion stays operational.
12. No widening of the stream codec and no new adapter in C3. A raw remote put of a bignum refuses with a qualified outcome; canonical Jing bytes through the existing `dao.jing.stream` adapter are the only remote form.
13. NaN key identity and float rendering parity remain separately tracked limitations.
14. C3 is complete only with four VMs on JVM, Node and Dart VM; per-host golden byte fixtures; unchanged Jing fixtures; the full C1 corpus; a checked-in operand table; and recorded mutation evidence including skipped demotion. Module slices may run alongside C2; prelude and lowering slices land after it.

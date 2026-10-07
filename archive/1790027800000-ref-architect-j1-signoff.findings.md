Completed-GMT: 2026-09-21 22:34:36 GMT  
Completed-Local: 2026-09-22 05:34:36 Indochina Time  
Coding-Agent: codex  
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d

J1 SIGNED OFF.

1. ADOPT — Decode must be Jing-owned structural validation followed by Boring re-encode verification; this is required by the plan’s “validate shapes before any lossy host conversion” rule and avoids Boring’s demonstrated losses. Boring remains the sole JVM/Node byte writer.

2. ADOPT — Ratify `host-collapse` as an additive 17th refusal class for host materialization failures involving joined-name keyword or symbol collisions. It does not change the Jing acceptance boundary: J3 must require identical outcomes across hosts except this explicitly named capability refusal, with JVM/Dart behavior determined by their actual equality models.

3. ADOPT — Ratify decimal exponents `[-2147483647, 2147483648]` and `max-depth 128`. These are host-independent safety rules: decode overflow is `malformed-cbor`; encode overflow is `unsupported-value`. They must be added to the design document’s Encoding contract before J2/step-3 integration.

4. ADOPT — The E1 corrected N/A table is authoritative; it reflects observed host construction and refusal behavior and does not alter any frozen bytes.

5. ADOPT — The fixed canonical Boring profile, ordinary non-indexed encoder, explicit outer `clojure/with-meta` frame, and unchanged existing entry points are sufficient. No further functional change is required before commit; the additive errata must be marked ratified.

6. ADOPT — J2 must mirror the structural-reader-before-package-decoder design, exact decimal window, depth limit, host-collapse policy, native-float rejection, decoded-float carrier preservation, and exact-integer safety. Host hash values must never be compared across hosts; fixture bytes and SHA-256 are the cross-host authority.

Exact errata heading and opening wording:

```text
Status: RATIFIED J1 ERRATA (architect sign-off 2026-09-22)

This additive errata is ratified for J1 and J2. The frozen corpus remains
unchanged; these entries define codec policy, host-capability refusals, and
the corrected per-host test obligations.
```

J2 constraints:

- Use `cbor` 6.5.1 as the Dart byte writer; do not replace it with a hand-rolled parser.
- Perform Jing-owned structural validation before package materialization.
- Re-encode every decoded accepted value and require byte-for-byte equality.
- Emit the outer `clojure/with-meta` frame explicitly, including around `dao.jing/list` and `dao.jing/symbol`.
- Enforce the decimal exponent window and depth 128 identically.
- Preserve float64 kind, signed zero, infinities, and canonical NaN; never narrow an integral float to an integer.
- Use `BigInt` or explicit carriers for exact integers, decimal, and rational values; reject unsafe or lossy conversions.
- Test Dart identifier equality for slash-crossed keyword/symbol components and report `host-collapse` only if Dart cannot materialize a frozen value without merging entries.
- Treat fixture hex and SHA-256 as the interoperability contract; do not compare JVM, Node, or Dart hash-number results.
- Maintain the additive boundary: no existing Jing behavior, backend, `dao.space`, remote/DHT, or transport changes.
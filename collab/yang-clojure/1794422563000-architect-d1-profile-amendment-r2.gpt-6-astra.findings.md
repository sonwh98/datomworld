Completed-GMT: 2026-11-13 17:07:59 GMT
Completed-Local: 2026-11-14 00:07:59 Asia/Ho_Chi_Minh

RULING: (b) — Fidelity preserves exact numeric values and observable numeric distinctions; equal-valued JVM Byte, Short, Integer and Long wrappers may interchange, including inside metadata.

My previous amendment conflated numeric identity with JVM class identity. The implementer correctly failed closed against that ambiguity. The correction belongs in the fidelity contract; neither custom numeric printing nor rejection of this fixture is justified.

The Integer originates in source-reader metadata. The harness uses `tools.reader`’s source-logging reader at [latte_oracle_test.clj:79](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:79). Inspection of the locally installed, pinned `tools.reader` 1.5.0 source shows that `SourceLoggingPushbackReader` returns line/column numbers through `int`, and its collection readers attach those coordinates as metadata. Source adaptation carries parameter vectors forward at [line 1489](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:1489). The refusal occurs before Yang compilation. This is ordinary reader provenance, not an anomalous guest numeric literal or Yang-generated numeric code. The diagnostic’s `[:value 2]` does not name the metadata key; it should.

**Metadata must still be preserved.** `compile-constructed-collection` consumes collection members, not their metadata, but [compile-form-1](/Users/sto/workspace/datomworld-d1/src/cljc/yang/clojure.cljc:2063) subsequently transfers metadata to emitted nodes. Source positions feed occurrence side tables; moreover, [arity-error-form](/Users/sto/workspace/datomworld-d1/src/cljc/yang/clojure.cljc:1087) can place source coordinates into generated error data. “Metadata is never observable” would therefore be an incorrect justification.

The permitted relaxation concerns the wrapper only:

- Jing [explicitly excludes integer width from numeric identity](/Users/sto/workspace/datomworld-d1/src/cljc/dao/jing/cbor.cljc:501).
- The inspected lowering does not select different instructions for Integer versus Long.
- The pinned printer prints equal fixed-precision integers identically. This conversion alone therefore does not change the oracle hash’s printed payload.
- The frontend exposes [host `int?`](/Users/sto/workspace/datomworld-d1/src/cljc/yang/clojure.cljc:2533), which accepts Byte, Short, Integer and Long. It distinguishes those from BigInt. Consequently, replacing the entire numeric comparison with unrestricted numeric equality—or with Jing numeric identity alone—would be too broad.

The exact amendment to the previous profile/rule text is:

> **Numeric fidelity.** “Preserve numeric identities” means preservation under the following explicit relation for this JVM D1 reconstruction profile. It does not require preservation of every JVM numeric wrapper class.
>
> 1. If both numeric leaves belong to `{java.lang.Byte, java.lang.Short, java.lang.Integer, java.lang.Long}`, they are faithful exactly when their signed integer values are equal. Compare their exact values; converting these four known classes to `long` is lossless. Do not apply this conversion to other classes.
> 2. Otherwise, both leaves must belong to the same supported numeric class and retain that class’s numeric identity. Preserve BigInt’s exact value, BigDecimal’s unscaled value and scale, Ratio’s exact rational value, and Double’s value including signed zero and infinity sign. Double NaNs use the canonical NaN identity already specified by Jing. For these admitted JVM classes, same-class `.equals` implements these distinctions; do not precede it with a generic equality check that rejects NaN.
> 3. No other cross-class conversion is authorized. In particular, Long/BigInt, integer/floating-point, Float/Double, and BigInteger/BigInt substitutions do not become faithful merely because their mathematical values compare equal. Unsupported or non-round-tripping numeric representations continue to refuse.
>
> **Scope.** Apply this numeric-leaf relation recursively throughout the actual retained inputs, including metadata, forms, call, program and options. Preserve metadata presence, keys, values and nested structure; preserve collection syntax kinds and the existing collection-order checks. A metadata coordinate may change from Integer to Long while retaining its exact value. It may not disappear or change value. Do not strip metadata or normalize the original input before capture.
>
> **Representation.** Keep the stipulated printer, print bindings, EDN reader, exactly-one-value/EOF rule and tag refusals unchanged. Introduce no custom numeric tags, global print-method changes or `*print-dup*` mode. The text remains the authoritative reconstruction input.
>
> **Profile identity.** Publish this fidelity relation under a new immutable profile revision/address, with an explicit fidelity-rule identifier and its executable dependency closure. An r4 profile using class-strict comparison must not silently acquire these semantics. The D1 envelope and oracle evidence schema remain unchanged.
>
> **Reconstruction obligation.** Fidelity is a preliminary gate, not proof of reconstruction. Require the original-input/read-back compilation comparison, exact retained-root equality, exact oracle source-hash reproduction, complete claimed side-artifact and correspondence reconstruction, and reopened-store verification. This amendment changes none of those comparisons or expected results.
>
> This equivalence is scoped to the pinned D1 compilation and dependency environment. It is not a promise about arbitrary JVM programs that inspect numeric classes. A dependency introducing such an observation requires another profile adjudication; it cannot inherit this equivalence automatically.

This preserves unitarity at the declared interpretation boundary: the coordinate’s value and provenance remain available, while an incidental wrapper that the relevant contracts do not identify may change. There is no need to persist a class tag. Conversely, BigInt remains distinguished because the admitted frontend can observe that distinction and its printed representation differs.

The implementer’s next steps are:

1. Replace the numeric branch of `mismatch` with the explicit relation above. Keep syntax, ordering and metadata checks intact. Record the fidelity rule in the new addressed profile.
2. Update focused tests to accept equal Integer/Long—and the other fixed-wrapper pairs—both as ordinary leaves and inside metadata. Keep refusals for changed coordinate values, missing metadata, decimal-scale differences, signed-zero differences, Long/BigInt substitutions, syntax-kind changes and collection-order changes.
3. Preserve r4’s class-strict refusal as historical evidence. Under the amended profile, change the real-fixture expectation to successful capture **only if observed**. Then establish compilation/root, source-hash and reopened-store gates. Do not replace the current refusal assertion with an unconditional success assertion or merely remove it.
4. Exercise every corpus input. Passing this fidelity check may expose another independent refusal; report it at its actual stage. No corpus admission, companion or reliance claim follows from this ruling alone.
5. Preserve the original expected roots, frozen evidence, recorded admissions and post-port correspondence requirements. No upstream fixture normalization is authorized.

Static review identified these prototype issues and acceptance gaps:

| Location | Finding |
|---|---|
| [correspondence_text.clj:47](/Users/sto/workspace/datomworld-d1/src/cljc/yang/correspondence_text.clj:47) | Unconditional numeric class equality causes the reported false refusal under the amended contract. This faithfully implemented the earlier overconstraint. Also, the leading generic `=` is unsuitable for the specified NaN identity; dispatch numeric leaves to their dedicated comparison. |
| [correspondence_text.clj:6](/Users/sto/workspace/datomworld-d1/src/cljc/yang/correspondence_text.clj:6) | The profile records runtime versions and printer/reader settings but does not describe the fidelity relation. `:domain :edn-with-readable-metadata` is insufficient to distinguish the old and amended acceptance rules. Version the rule explicitly and retain its implementation. Runtime compatibility enforcement remains an acknowledged unfinished gate. |
| [correspondence_text.clj:38](/Users/sto/workspace/datomworld-d1/src/cljc/yang/correspondence_text.clj:38) | Map mismatch diagnostics identify an entry ordinal without its key. Include a compact key description so a position-coordinate failure can be distinguished from arbitrary domain metadata. |
| [latte_oracle_test.clj:2513](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:2513) | Capture diagnostics identify only fixture and input, although the same fixture can have multiple admissions. Include the supplied admission scope/ordinal or equivalent attempt coordinate; do not synthesize identity from fixture content. |
| [latte_oracle_test.clj:4267](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:4267) | The regression asserts stage and classes but not the reported path or metadata key. It can pass on another Integer/Long mismatch. Preserve a precise regression for the identified cause and separately establish successful replay under the amended profile. |

The capture boundary and explicit tag-refusing reader are appropriate. The missing complete replay/verifier gates remain blockers already acknowledged by r4; this review does not claim they passed.

Static review only. No suites run and no files edited.
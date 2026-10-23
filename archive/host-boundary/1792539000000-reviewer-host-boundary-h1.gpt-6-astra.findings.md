# Independent architectural review: Slice H1 — Pure Primitive Profile

Date: 2026-10-21  
Reviewer: gpt-6-astra, Independent Reviewer / Architect  
Worktree: `/Users/sto/workspace/datomworld-host-h1`  
Branch: `host-h1`  
Reviewed: HEAD `32d1e3801f1f8272f5ea3b251d15b031f6e408aa` plus the twelve staged H1 files.

**Formal verdict: REJECTED for landing under the current r10 acceptance contract.**

The implementation is otherwise suitable for the expansion slice. I confirm Fable's F2 ruling and most of the Table A/B/C review. I do **not** confirm F1's ruling that recording the NFC gap alone permits landing. The implementation demonstrably violates an explicit profile law and its direct conformance oracle. The required resolution is narrow: establish conformance, or obtain an explicit owner-approved amendment allowing this temporary H1 exception. This is not a request to redesign the other primitives.

## Authority and evidence provenance

I read `docs/design/datom.world.md`, the implementation and direct tests in this worktree, the gate diff, and `docs/agents/known-gaps.md`. The latter contains the owner-adopted review rule: “Blocking is P0 or P1, or a P2 in claimed acceptance, inside the slice's scope.”

The requested r10 design and both prior findings reports are absent from this worktree. I read them from `/Users/sto/workspace/datomworld/`, at the same relative paths supplied in the request. The normative references below are to that copy of `docs/design/dao.stream.host-boundary.md`: §2.4–2.5, §4.2–4.3, §8 H1, §8.1 and §14. I did not change either checkout's implementation or design.

## Blocking finding: F1 is an acceptance exception, not merely a toolchain note

**P2, in claimed H1 acceptance.** `src/cljc/dao/host/text.cljc:195` delegates NFC to the running JDK. §2.4 requires equal arguments to produce equal results on every host; §2.5 and Table A pin NFC to Unicode 16.0; Table D assigns the pinned normalization subset to H1. `test/dao/host/text_test.cljc:192–217` instead accepts twenty failed rows on a JVM below version 24.

Independent reproduction on Java 21.0.2:

```clojure
(let [s (String. (int-array [0x105D2 0x0307]) 0 2)]
  (dao.host.text/code-points (dao.host.text/nfc s)))
;; actual:   [67026 775]  ; U+105D2 U+0307
;; required: [67017]     ; U+105C9
```

The expected composition is present in the committed H1 fixture at line 15111. The test's twenty expected failing first code points are U+105C9, U+105E4, U+11383, U+11385, U+1138E, U+11391, U+113C5, U+113C7, U+113C8, U+16121–U+16128, and U+16D68–U+16D6A. These are twenty failed rows of the selected subset, not a bound of twenty affected input strings.

Fable correctly diagnoses the cause. Java 21 uses Unicode 15.0; Java 24 introduced Unicode 16.0. See the official [Java 21 Character specification](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html) and [OpenJDK Unicode version table](https://cr.openjdk.org/~liach/jep401/lwreview/all-changes.html). A host call can be the intended implementation technique and still fail the specified semantics on the selected toolchain.

The earlier `debruijn` helper has the same limitation, and no H1 production consumer is rewired. Those facts establish absence of a new consumer regression; they do not establish compliance of the newly commissioned profile. The green suite establishes the **expected gap**, not Unicode 16.0 conformance. Neither a known-gaps row nor a docstring can silently amend the owner-set pin.

Resolve before landing by either:

1. Making the supported lane meet the pinned behavior and recording the direct NFC test with no expected failures, alongside the required lane verification; a toolchain change requires its appropriate owner decision and validation.
2. Obtaining an explicit owner-approved, dated amendment to H1 acceptance permitting the temporary JVM exception. Name its runtime scope, evidence, owner and closing condition, and state how H2 may consume the nonconforming entry. Preserve the Unicode 16.0 target and distinguish expected-gap tests from conformance tests.

Fable's C1 ledger entry and C2 docstring correction are necessary if option 2 is approved, but are not substitutes for it. Neither ledger copy inspected currently contains the proposed NFC row, and the H1 `nfc` docstring remains unconditional. I agree that implementing a portable normalizer is not an incidental fix to commission within this review; it would require an explicit scope decision.

Also correct the test commentary: not every JDK below 24 uses Unicode 15.x, and the test does not necessarily fail on every JDK change. Its expected list switches at 24. Later runtimes passing this finite subset do not, by themselves, prove an exact Unicode-version pin for all strings.

## F2: confirmed

`profile-additions` is an appropriate H1 mechanism under §8.1. The unchanged production closure cannot yet reach the five namespaces. Scanning its 155-file union plus those five files gives the required 160-file gate coverage without altering the H0 traversal or committed 142/140/141 lane totals.

The diff preserves existing lane classifications, supplies `:profile-internal-pure` for additions, adds exactly five `:profile` list-1 entries, and checks that every source under the profile directory is covered. Missing additions, unlisted files, absent arms and observed effects fail checks. An addition becomes stale when the committed closure includes it. Existing injected-arm, injected-effect and scan-failure negatives still run.

At H2, regenerate the closure, classify reachable profile files in the closure tool, remove their supplemental entries, and update slice-specific count assertions together. Staleness is detected against the **committed closure**, not merely because someone writes a new require. The independent traversal check is the complementary guard against stale committed output. No H0 reclassification waiver is introduced by F2.

## Table A/B/C conformance

| Area | Independent assessment |
|---|---|
| A: integer, 28 entries | Original 25 entry bodies compare equal after stripping comments and whitespace. The three additions implement strict signed ASCII decimal parsing, nonnegative gcd and fresh unsigned magnitude bytes. C3's clarification of optional leading minus is justified and should be recorded in the design. |
| A: float, 5 entries | Big-endian sixteen-digit bit text, canonical NaN observation, signed zero, finite/integral predicates and fields agree with the stated laws. The fraction construction stays within exact native arithmetic before promotion. |
| A: number, 10 entries | Decimal scale bounds are checked before JVM construction; ratio reduction, positive denominator and denominator-one kind are preserved. Carrier copies remain intentionally distinct from CBOR until H2. |
| A: bytes, 10 entries | Fresh allocation/slicing/conversion; unsigned reads; prevalidated writes and both copy ranges; nil M-entry returns; same-array memmove behavior. Tests exercise source preservation and target preservation on refusal. |
| A: text, 6 entries | Strict UTF-8 refusal, BOM content, UTF-16 units and freshness are implemented. NFC is the blocking exception above. |
| B: H1 compositions | `code-points` handles paired and unpaired surrogates over the text entries. The float-text kernel and `decimal-text->float` live below `yin`, use the integer profile, and have direct fixture/named-case tests, including ties, underflow and overflow. No premature H3/H4 replacement is introduced. |
| C: runtime prerequisites | Carrier protocol implementations and `values.cljc` laws are preserved. No change to atomic flags, weak references, locks or promises is introduced. Documentation needs the host qualifications below. |

The H1 Table A count is 59 = 28 + 5 + 10 + 10 + 6. The additional public text function and fifteen additional public float functions belong to Table B. There is no `dao` → `yin` dependency. Internal profile composition is consistent with the explicit float-kernel exception to the design's general layering sentence.

§2.4 obligations (i) and (ii) are covered by the mutable-byte argument property, freshness tests and M-entry refusal/overlap tests. Integer, float and string inputs are immutable values. Obligations (iii) and (iv) remain assigned to H4 and H2 respectively; this review does not claim those future audits are complete.

**Documentation correction, P3:** `number.cljc:20–28` overgeneralizes Table C. The custom Decimal compares fields, whereas JVM BigDecimal equality ignores scale. Custom JS/Dart Rational hashes use stringified fields, and Decimal hashes stringify the mantissa. Printing is also carrier-specific: my JVM probe prints `(decimal-of -1 15)` as `1.5M` and `(ratio-of 1 2)` as `1/2`; only the custom denominator-one Rational has the tagged JVM printer. Dart custom carriers have no corresponding printer here. Fable's R3 suggestion to qualify tagged printing as “on the JVM and JS” is therefore insufficient. Document the actual carrier-specific laws without changing preserved behavior. The `values.cljc` addition accurately includes kind, owner and payload.

Fable's optional wrong-type/domain validation recommendations do not become new blockers: totality is over declared domains. The signed-decimal grammar amendment C3 is a legitimate clarification, unlike treating the Unicode pin as already satisfied.

## Verification and limits

- Independently ran `bb test:sub:clj dao.host`: **43 tests, 1,050 assertions, zero failures/errors**, Java 21.0.2, about 14 seconds. Log: `target/profile/sub-clj.log`. This includes all twelve gate tests, independent closure comparison, negative checks and frozen corpus hashes.
- Independently reproduced the NFC mismatch and JVM numeric-carrier printing/equality above.
- Inspected all five primitive namespaces, the four direct test namespaces, and staged gate/values changes; compared the original integer entries mechanically.
- CLJS **31 tests / 876 assertions**, CLJD **31 tests**, and standalone gate **12 tests / 180 assertions** are the supplied jupiter verification evidence. I did not rerun those lanes or independently attest their remote logs.
- `bb test:changed` three-lane landing evidence remains required. The engineer report marks G2 pending; the supplied subsystem counts do not replace G2.
- The staged set touches no codec-track source/test file, rewires no production consumer, and starts nothing from §9. Existing corpus hashes pass unchanged.

This is the independent non-Claude review required by §14, but **not permission to land the current slice**. F2 is confirmed. F1 needs conformance or an explicit acceptance amendment, followed by confirmation of that resolution and the standard landing evidence. A documentation-only ledger update cannot satisfy the current blocking review rule.

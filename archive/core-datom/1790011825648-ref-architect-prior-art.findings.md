I’m rechecking the landed `dao.stream.cbor` implementation, its tests, and the Eve-related documentation so the revised package distinguishes reusable stream machinery from Jing’s still-unimplemented storage profile.
Completed-GMT: 2026-09-21 17:44:47 GMT  
Completed-Local: 2026-09-22 00:44:47 Indochina Time  
Coding-Agent: codex  
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d

## 1. Overlap map

| Step-1/2 deliverable | Status | Ruling |
|---|---|---|
| Boring dependency and pin | ALREADY EXISTS | `org.replikativ/boring` is pinned to `0.1.30` in [deps.edn](/Users/sto/workspace/datomworld/deps.edn). Reuse the pin; do not add a second dependency. |
| JVM/JavaScript Boring wrapper | ALREADY EXISTS as stream infrastructure; NEEDS Jing-specific work | [dao.stream.cbor/boring.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/cbor/boring.cljc) supplies the Boring bridge, but its domain is the stream profile: native tag-39 identifiers, stream metadata, safe numbers, and `dao.stream/list`. It does not implement Jing’s component-preserving identifiers, metadata stripping, rich carriers, or content-addressing normalization. |
| Dart CBOR wrapper | ALREADY EXISTS as stream infrastructure; NEEDS Jing-specific work | [dao.stream.cbor/cljd.cljd](/Users/sto/workspace/datomworld/src/cljd/dao/stream/cbor/cljd.cljd) uses `cbor` 6.5.1, performs canonical ordering and decode/re-encode byte comparison, and deliberately avoids a hand-rolled parser. Jing needs a separate profile adapter and carriers. |
| Dart dependency and pin | ALREADY EXISTS | `cbor: 6.5.1` is already pinned in [pubspec.yaml](/Users/sto/workspace/datomworld/pubspec.yaml) and the lockfile. |
| Stream normalization | ALREADY EXISTS, not reusable as Jing policy | The stream wrapper handles its own portable domain, tag-39 restrictions, metadata, byte strings, and list framing. Jing’s normalization is materially different: component-preserving identifier frames, reader-position stripping, no Unicode normalization, richer numerics, and stricter content identity. |
| Named extensions | ALREADY EXISTS for `dao.stream/list`; NEEDS Jing-specific work | The stream profile has a named `dao.stream/list` tag-27 frame and uses Boring’s native tag-39 identifiers. Jing requires `dao.jing/list`, `dao.jing/keyword`, `dao.jing/symbol`, and `dao.jing/float64`. |
| Numeric constructors/carriers | NOT STARTED for Jing | Stream supports safe portable numbers only. Jing still needs float64, decimal, rational, exact integer/bignum constructors, and portable equality/hash/compare. |
| Frozen stream fixtures | ALREADY EXISTS | [test/dao/stream/cbor_test.cljc](/Users/sto/workspace/datomworld/test/dao/stream/cbor_test.cljc) and its Dart twin contain frozen hex fixtures, decode/re-encode tests, determinism tests, malformed/noncanonical rejection, list/vector distinction, metadata, sets, and byte strings. |
| Jing frozen fixtures and SHA-256 | NOT STARTED | Existing stream fixtures are prior art, not Jing’s contract. Jing needs a new corpus with explicit SHA-256 values, pathological identifiers, rich numeric cases, and injectivity groups. |
| Proof of Boring effective options | PARTLY EXISTS; Jing-specific proof needed | The stream wrapper explicitly uses canonical Boring options and tests deterministic bytes. Jing still needs fixtures proving its own effective profile: string references off, shapes off, no index frame, one definite item, and Jing-specific normalization. |
| Cross-host fixture exchange | ALREADY EXISTS for stream; NOT STARTED for Jing | The stream `.cljc` and `.cljd` tests consume the same frozen hex. Jing needs a separate three-host corpus because identical stream bytes do not imply identical Jing storage bytes. |

The existing stream tests are substantial reusable evidence. They already prove, among other things, frozen decode/re-encode, deterministic output, list/vector distinction, byte-string handling, bytewise map/set ordering, and refusal of malformed frames.

The phrase “Jing canonical bytes” has a precise current meaning that must not be confused with CBOR value encoding. [dao.jing.stream](/Users/sto/workspace/datomworld/src/cljc/dao/jing/stream.cljc) calls `jing/canonical-bytes`; today those bytes are produced by Jing’s transitional order-normalized print encoder (`canonical-print`/`pr-str`-derived hashing). The adapter then wraps those opaque bytes as a CBOR byte string for `dao.stream.cbor`, or as an octet vector for the Transit lane. The stream codec does not decode or re-encode the Jing payload. Therefore:

- The transport is already CBOR-capable.
- Jing’s addressed payload is still print-based.
- Landing `dao.jing.cbor` changes the bytes carried by the adapter; it does not require changing the adapter’s lane representation.

## 2. Architecture ruling

`dao.jing.cbor` should be a sibling namespace that composes the existing stream wrappers only at the library and byte-carrier level. It should not extend the stream profile or call `dao.stream.cbor/encode` for Jing values.

Recommended structure:

- Reuse the existing Boring dependency and host mechanics.
- Keep Jing production code in new `dao.jing.cbor` and `dao.jing.cbor.boring` namespaces.
- Keep Dart implementation in a new Jing-specific `:cljd` namespace.
- Share only genuinely profile-neutral helpers if extraction is justified later: byte comparison, hex conversion in tests, SHA-256 fixture utilities, and perhaps strict byte-payload predicates.
- Do not share stream tag constants, identifier rules, list-frame names, or portable-domain predicates.

The profiles intentionally differ:

- `dao.stream/list` versus `dao.jing/list`.
- Stream tag-39 identifiers versus Jing’s tag-27 `[namespace name]` frames.
- Stream reader-position metadata versus Jing’s stripping of `:line`, `:column`, `:end-line`, and `:end-column`.
- Stream safe numbers versus Jing’s float64, decimal, rational, and exact-large-integer domain.
- Stream values are transport payloads; Jing values are content-addressed storage payloads.

Making Jing call the stream encoder would create exactly the dangerous failure mode the owner wants to avoid: two supposedly canonical profiles silently sharing some bytes while disagreeing on identifiers, metadata, numeric kind, and list semantics.

The invariants between them are narrower:

1. `dao.jing.stream` must preserve Jing’s opaque canonical bytes exactly as a byte string.
2. A stream consumer must never decode Jing bytes as a stream-domain Clojure value and re-encode them.
3. Both wrappers must reject malformed CBOR and preserve byte-array content rather than host object identity.
4. The stream profile may carry a Jing byte payload, but it must not claim that payload is a stream-profile value.
5. Jing may change the bytes inside `:dao.jing/canonical-bytes` when the storage codec lands; the stream envelope must remain unchanged.

A divergence is a bug if the adapter changes bytes, if a stream path accepts a Jing payload in the wrong representation, if either side mutates a carried byte array, or if a future refactor makes the stream codec silently normalize Jing content.

## 3. Revised phases, file boxes, criteria, and verification

The earlier J0–J3 split remains appropriate, but J1 and J2 now explicitly reuse stream prior art.

### J0 — Profile delta and Jing fixture freeze

New files:

- `test/resources/dao/jing/cbor-v1.json`
- `test/dao/jing/cbor_fixtures.cljc`
- Fixture provenance/readme beside the resource

No existing production files, dependency files, or stream tests should change.

The fixture corpus must be generated and reviewed independently of the future Jing wrapper, then stored as literal hex plus SHA-256. The stream fixture corpus may be used as a baseline for common CBOR mechanics, but Jing cases must be new.

Completion requires:

- Every Jing named frame and malformed shape.
- Component-preserving pathological identifiers.
- Metadata normalization and empty-metadata omission.
- Lists versus vectors.
- Unicode and surrogate cases.
- Byte strings.
- Integer, bignum, float64, decimal, rational, NaN, signed-zero, and float32-widening cases.
- Duplicate/equality-collapse, unknown-tag, tag-39, native-float, trailing-data, invalid UTF-8, and unsupported-value rejection cases.
- Explicit equivalence groups and pairwise injectivity, especially across pathological identifiers.
- Recorded Boring pin, Dart pin, profile options, generation method, and reviewer identity.

The fixture must never be silently regenerated by a dependency upgrade.

### J1 — JVM/JavaScript Jing profile

New files:

- `src/cljc/dao/jing/cbor.cljc`
- `src/cljc/dao/jing/cbor/boring.cljc`
- `test/dao/jing/cbor_test.cljc`

Reuse:

- Boring dependency and low-level encoding approach from `dao.stream.cbor/boring.cljc`.
- Existing stream test patterns for bytewise ordering, strict decode/re-encode, malformed input, and byte payloads.

Do not reuse:

- `dao.stream/list`.
- Tag-39 identifier encoding.
- Stream portable-domain validation.
- Stream metadata policy.
- Stream safe-number restrictions.

Completion requires exact frozen-fixture parity on JVM and Node, Jing-specific normalization, named frames, numeric constructors, portable carrier operations, and closed unknown-tag/name/shape dispatch. Existing Jing behavior must remain unchanged.

### J2 — Dart Jing profile

New files:

- `src/cljd/dao/jing/cbor/cljd.cljd`
- `test/dao/jing/cbor_test.cljd`

Minimal edit:

- Add the `:cljd` route only to the new J1-created shared facade.

Reuse from stream prior art:

- `package:cbor/cbor.dart`.
- Canonical byte ordering approach.
- Strict UTF-8 handling.
- Decode followed by canonical re-encode and byte comparison.
- `Uint8List` byte-payload handling.

Jing-specific implementation remains responsible for component identifiers, named frames, rich carriers, exact float bits, metadata policy, and profile closure.

Completion requires Dart to consume every J0 fixture and reproduce the exact JVM/Node bytes and SHA-256 values.

### J3 — Cross-host Jing conformance

New files:

- `test/dao/jing/cbor_conformance_test.cljc`
- `test/dao/jing/cbor_conformance_test.cljd`

Each host should emit a fixture manifest containing case ID, canonical hex, digest, and normalized semantic signature. A raw independent CBOR inspection should validate tag/name/payload shape and ordering without calling the Jing decoder.

Completion requires JVM, Node, and Dart to consume the same fixture resource and reproduce identical bytes. This is the first point at which steps 1–2 are complete.

Verification lanes remain:

- JVM focused tests with the repository’s `clj -M:test` lane.
- Node/ClojureScript focused tests with the repository’s documented Shadow/BB lane.
- Dart with `bb test:cljd`.
- Final `bb test`.
- Kondo, cljstyle, and `git diff --check`.

No backend, `dao.space`, remote/DHT, or existing Jing behavior changes are permitted in J0–J3.

## 4. Dart route

The ruling remains: use the maintained `cbor` 6.5.1 package, behind a Jing-specific wrapper.

The stream implementation materially lowers risk because it demonstrates that this repository already has:

- A working ClojureDart package bridge.
- Canonical ordering logic.
- Strict byte comparison after decode.
- A no-hand-rolled-parser policy.
- Cross-host frozen fixtures.

That prior art is not sufficient to reuse the stream profile itself, but it is sufficient to reject a custom binary parser as the default plan. Hand-rolling would require fixture-specific evidence that the pinned package cannot preserve Jing’s tags, exact bytes, or carrier semantics.

## 5. Documentation status problem

The status header should explicitly distinguish the landed stream codec from the unimplemented Jing storage codec. Suggested 80-column wording:

```text
Status: implementation plan; not yet implemented. `dao.stream.cbor` landed
in U10a as transport prior art; Jing's canonical storage-value CBOR codec
and content-address migration remain unimplemented.
```

A shorter sentence suitable for the related-work section is:

```text
`dao.stream.cbor` is landed wire-codec prior art, not Jing's storage profile.
```

The implementation sequence should reference the landed prior art explicitly:

```text
The JVM/JS and Dart work reuses `dao.stream.cbor`'s pinned-library and
cross-host fixture patterns, but defines a separate Jing value profile.
```

The stale normative reference is [dao.data.btree.md](/Users/sto/workspace/datomworld/docs/design/dao.data.btree.md:700), which still says “Eve flat, per `dao.jing.dht.md`.” `dao.jing.dht.md` no longer mentions Eve, and the current architecture-reviewed decision is CBOR.

Exact replacement wording:

```text
- When Jing's pinned canonical CBOR byte encoding lands (see
  `dao.jing.cbor.md`), the default flips to on and the same-host
  restriction disappears — the check itself needs no format change,
  only a stable encoding under it.
```

The following are not stale Eve references and should not be rewritten merely because of this correction:

- `dao.jing.md`’s description of the current transitional print encoder.
- `dao.jing.file`’s generic statement that the future pinned encoding replaces payload text.
- Historical orchestrator-log entries.
- The unrelated “Eve Programming Language” mention in `yin.vm-in-dao.space.md`.

## 6. Gate checklist and routing

The two gates are unchanged.

### Gate 1 — `dao.space` comparator/query changes

Owner question:

> Do the `dao.space` owners approve portable numeric equality, hashing, and comparison in index comparators, query unification, hashed lookup, and comparison builtins, including the intentional JVM change to `(= 1 1.0)`, and what deterministic tie rule should `min`/`max` use for numerically equal mixed-kind operands?

Evidence:

- J3 carrier/native matrix.
- Proposed step-3 diff and consumer tests.
- Index collapse with distinct content addresses.
- Explicit arithmetic-carrier rejection.
- Owner approval and tie-break decision.

Earliest blocked phase: step 3. J0–J3 remain additive and may proceed.

### Gate 2 — clean-break rebuild readiness

Owner question:

> Have all live old-address artifacts and external references been inventoried and proven rebuildable from retained original intake values without gaps, and do you authorize rejecting old EDN stores with no legacy reader or address alias?

Evidence remains:

- Published index manifests and roots.
- AST/code and de Bruijn projected addresses.
- Continuation/module references.
- Existing old-address fixtures, especially `test/dao/data/psset_fixtures.cljc`.
- Actual deployed stores and external references.
- Replay from original intake cursors, with any stream gap failing the audit.
- Domain preflight and graph-closure verification.

Readiness is still UNVERIFIED. Earliest blocked phase: step 3.

### Routing

The reuse changes the implementation emphasis, not the model-family policy:

- J0: Claude Opus 5 implementation; Gemini 3.1 Pro High review.
- J1: Claude Opus 5 implementation; Gemini 3.1 Pro High review.
- J2: Claude Opus 5 implementation; GLM-5.3 review, preserving GLM budget.
- J3: Claude Sonnet 5 implementation; Gemini 3.1 Pro High review.
- GPT-5.6-sol provides architecture sign-off after J0, J1, J2, and J3.

## 7. Eve-flat versus CBOR addendum

### 7.1 Ruling on the encoding role

CBOR remains the correct canonical value-to-bytes format for Jing.

Eve is not merely an in-memory format: its current external documentation describes self-contained flat collection tags `0xED` and `0xEF`, separately from slab-pointer tags `0x10`–`0x13`, and describes cross-process JVM/Node/Babashka storage. The repository’s Eve facts are therefore directionally real, but they are external evidence, not an implemented project dependency. [Eve internals](https://github.com/SeniorCareMarket/eve/blob/main/doc/internals.md), [Eve README](https://github.com/SeniorCareMarket/eve)

The decisive criteria are:

- Dart/ClojureDart support: the repository has a pinned, working Dart CBOR path. Eve’s current repository documentation lists browser CLJS, Node.js CLJS, JVM Clojure, and Babashka, but no Dart/ClojureDart. That absence is externally observed, not a proof of impossibility. A maintained Dart implementation plus cross-host fixtures would settle it.
- Canonical deterministic profile: Jing needs exact treatment of identifiers, metadata, Unicode, floats, decimals, ratios, bignums, duplicate keys, and unknown tags. Boring plus the existing Dart package are already integrated locally. Eve’s published flat layout does not, by itself, establish all Jing semantics. An independently implemented Eve fixture corpus across JVM, Node, and Dart would settle this.
- Independent readers: CBOR has mature independent readers and the repository already has a Dart reader. Eve has a documented format and source, but no local independent reader. A pinned Eve commit plus a second-language reader would settle this.
- Stability and pinning: the Eve README currently shows a git SHA dependency example, but the API is explicitly alpha and the repository has no Eve pin or lockfile here. A released compatibility policy and reproducible pinned build would be required.
- Tag/extension mechanism: Eve documents primitive and collection tags, but the repository has no verified Eve mapping for Jing’s named Clojure extensions, metadata rules, pathological identifiers, or numeric carriers. A complete Eve profile specification would be required.
- Ownership: CBOR’s byte-level contract is owned by Jing and validated by frozen fixtures. Eve’s layout would be owned by an external project, creating a stronger external compatibility dependency.

Thus Eve-flat is a plausible binary serialization technology, but CBOR is better supported by the actual repository, the required Dart host, independent fixture verification, existing pins, and the already-landed stream precedent.

### 7.2 Was Eve-flat a real plan and was it superseded?

Yes, an earlier Eve-flat intention was real in the repository’s historical documentation: `dao.data.btree.md` explicitly named “Eve flat” as the future point at which cross-host verification would become default.

However, there is no Eve dependency, source integration, test suite, or Jing Eve profile in the current repository. The later architecture-reviewed `dao.jing.cbor.md` selects CBOR and does not mention Eve. The CBOR plan should state explicitly that it supersedes the earlier Eve-flat intention for Jing’s canonical storage bytes.

### 7.3 Eve’s proper role

Eve remains a legitimate future candidate for a local index or byte-store backend behind Jing’s opaque-bytes boundary. It must not define Jing content identity, expose slab pointers as addresses, or make storage semantics depend on Eve object layout. A future adapter could index immutable Jing digest-to-byte mappings using Eve’s local structures; the CBOR work need only preserve the backend-neutral contract and avoid exposing CBOR implementation details to backends.

### 7.4 What `eve.blog` currently claims without repository support

- Eve is not integrated into `deps.edn`, `package.json`, `pubspec.yaml`, source, or tests.
- No Eve-backed `IDaoStorage` implementation exists in this repository.
- No verified shared-memory, mmap, CAS, epoch-GC, or zero-copy DaoSpace integration exists.
- No evidence supports the blog’s claims about zero-copy Yin.vm ASTs, migratable Web Worker continuations, or completed JVM/Node/browser integration.
- The advertised performance and exabyte-scale claims are external claims, not repository measurements.

No files were edited and no implementation was started.

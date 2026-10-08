I’ll audit the design plan, governing architecture and routing docs, then trace the current Jing/Dart sources, tests, dependency pins, and address-bearing artifacts. I’ll keep the result strictly to steps 1–2 and make both owner gates explicit.
The governing plan does support an additive first unit in principle, but the exact file boundary depends on how CLJC/CLJD sources and fixture resources are currently wired. I’m now checking those host layouts, pins, test runners, and every repository reference that actually carries a Jing address.
Two important post-plan facts are already present on this commit: Boring 0.1.30 is pinned and used by the additive `dao.stream.cbor` codec, and Dart already pins the maintained `cbor` package at 6.5.1 with a working wrapper pattern. I’m verifying whether those pins are still current and separating what the repository proves from what remains an external maintenance judgment.
I’ve completed the repository and dependency inspection. I’m consolidating the additive phase boxes, the two owner gates, and the stale-plan corrections into the final architect deliverable now.
Completed-GMT: 2026-09-21 17:39:17 GMT  
Completed-Local: 2026-09-22 00:39:17 Indochina Time  
Coding-Agent: codex  
Session-ID: pending

## 1. Scope of this first unit

Steps 1–2 can land additively.

The unit may introduce the Jing CBOR codec, portable numeric carriers and operations, frozen fixtures, and their JVM, Node, and Dart tests without changing:

- Existing `dao.jing` addressing or materialization behavior.
- Memory or file backends.
- `dao.space.index` or `dao.space.query`.
- Remote, DHT, WebSocket, or intake behavior.
- Existing addresses or downstream address fixtures.

The repository already pins both required libraries:

- `org.replikativ/boring` is pinned to `0.1.30` in [deps.edn](/Users/sto/workspace/datomworld/deps.edn). It remains the latest published Clojars version as inspected today. [Clojars](https://clojars.org/org.replikativ/boring)
- Dart `cbor` is pinned to `6.5.1` in [pubspec.yaml](/Users/sto/workspace/datomworld/pubspec.yaml) and its lockfile. It remains the latest published version as inspected today. [pub.dev versions](https://pub.dev/packages/cbor/versions)

Therefore steps 1–2 need no dependency-file edit.

The only cross-phase production edit should be to a namespace created by this unit: J1 creates the shared `dao.jing.cbor` facade, and J2 fills in its `:cljd` implementation route. No pre-existing source file needs modification.

Neither owner gate blocks this unit. The first prohibited crossing is step 3, when the new codec becomes the address source, backends change to opaque bytes, and portable numeric semantics enter `dao.space`.

One plan correction must be incorporated in the new implementation: Boring 0.1.30 does not reliably expose array-payload tag-27 frames as `boring.data/UnknownRecord`; that carrier is associated with record-shaped/map payloads. The wrapper must use `:on-unknown-record` to create a private tagged sentinel and then perform closed Jing name-and-shape dispatch. This stays within the new codec namespace and does not require an existing-code edit.

This first unit can prove codec-level requirements, including portable numeric operations directly. It cannot claim the whole epic’s acceptance yet. Backend isolation, address-mismatch handling, file migration, remote/DHT agreement, and actual `dao.space` consumer behavior belong to steps 3–5.

## 2. Bounded work package as phases

A J0–J3 split is preferable to combining fixtures and implementation. It preserves the plan’s requirement that the contract be frozen before implementation and gives reviewers small, causally clear diffs.

### J0 — Freeze the encoding contract

Proposed file box:

- New `test/resources/dao/jing/cbor-v1.json`
- New `test/dao/jing/cbor_fixtures.cljc`
- New fixture provenance document beside the resource, for example `test/resources/dao/jing/cbor-v1.README.md`
- No production source changes
- No dependency changes

The JSON should contain only stable, language-neutral data: case ID, semantic input DSL, expected canonical hex, expected SHA-256, identity/equivalence group, and explanatory notes. JVM/Node can use the already-pinned `clojure.data.json`; Dart can use `dart:convert`.

Completion criteria:

- The corpus covers:
  - Map/set insertion order, mixed keys, sorted collections, and duplicate canonical/equality collapse.
  - List/vector distinction and all four tag-27 names: `dao.jing/list`, `dao.jing/keyword`, `dao.jing/symbol`, and `dao.jing/float64`.
  - Metadata retention, reader-position stripping, empty-metadata omission, and symbol metadata.
  - Unicode astral text, noncharacters, composed/decomposed distinction, invalid UTF-8, and unpaired-surrogate rejection.
  - Pathological identifier components, including nil versus empty namespace, literal slashes, whitespace, leading colons, and symbol `"42"` versus integer `42`.
  - Byte-string content identity.
  - Integer boundaries and bignums, integer versus integral float, signed zeros, infinities, canonical NaN, float32 widening, decimal scale, reduced ratios, and denominator-one ratios.
  - Malformed frames, tag 39, unknown tags, native CBOR floats, unsupported simple values, duplicate keys/elements, noncanonical encodings, and trailing data.
  - Effective stringref-off, shapes-off, and no-index behavior.
- Equivalence groups produce identical bytes: integer width, sortedness, sequence realization, stripped/empty metadata, reduced ratios, canonical NaNs, and float32 widening.
- Retained distinctions produce different bytes: numeric kind, decimal scale, signed zero, retained metadata, and every distinct pathological identifier.
- Pairwise injectivity is asserted over all distinct normalized identities, particularly the complete pathological-identifier class. Both canonical bytes and fixture addresses must differ. SHA-256 collision resistance remains the addressing assumption; it is not presented as mathematical digest injectivity.
- The fixture review records the encoding contract version, dependency pins, generation method, and reviewers.

The frozen file must initially make implementation tests red or unimplemented; it must not be generated by the codec under test.

### J1 — JVM/JavaScript codec and portable operations

Proposed file box:

- New `src/cljc/dao/jing/cbor.cljc`
- New `src/cljc/dao/jing/cbor/boring.cljc`
- New `test/dao/jing/cbor_test.cljc`
- Test-only additions to `test/dao/jing/cbor_fixtures.cljc`
- No edits to `dao.jing`, its backends, `dao.space`, or transport code
- No dependency changes

Completion criteria:

- JVM and Node consume the frozen resource, decode every accepted fixture, and re-encode the exact frozen hex.
- SHA-256 of decoded fixture bytes matches the frozen digest.
- Normalization sorts map keys and set elements lexicographically by unsigned canonical bytes, proper prefix first.
- Duplicate canonical encodings and portable-equality collapses are rejected before collection construction.
- Strings are strict UTF-8 with no normalization; unpaired surrogates are rejected recursively, including metadata and identifier components.
- Identifier construction uses exact namespace/name fields and never print/parse reconstruction. Tag 39 is always rejected.
- The four named frames use exactly `[name-string payload]` and closed name/shape dispatch.
- Native CBOR floating-point items are rejected. Jing float content uses the exact eight-byte big-endian `dao.jing/float64` payload.
- Integer, float64, decimal, and rational constructors/accessors preserve the declared kinds and representations.
- Portable equality, hash, and comparison satisfy:
  - Native/carrier comparisons in both operand orders.
  - Exact mathematical comparison without double-rounding.
  - Equal values compare zero and hash alike across numeric kind, scale, and zero sign.
  - `-∞ < finite < +∞ < NaN`, with canonical NaNs equal.
  - Recursive structural handling of collections containing carriers.
- JVM and Node are independent of ambient print settings.
- No behavior of existing Jing entry points changes.

### J2 — Dart codec and carriers

Proposed file box:

- New `src/cljd/dao/jing/cbor/cljd.cljd`
- New `test/dao/jing/cbor_test.cljd`
- Minimal `:cljd` routing addition to the J1-created facade
- No edit to `pubspec.yaml` or `pubspec.lock`
- No pre-epic production file changes

Completion criteria:

- Dart reads and reproduces every accepted frozen fixture byte-for-byte.
- Dart rejects the same malformed and out-of-profile fixtures as JVM/Node.
- Explicit Dart carriers cover float64, decimal, and rational values; `BigInt` is used for exact large integers.
- Metadata on codec-constructed collections is cleared before supplied metadata is attached.
- Map/set ordering, equality-collapse checks, strict strings, exact float bits, and profile closure are wrapper-owned.
- Decode followed by strict canonical re-encode catches duplicate-map collapse and encodings the package’s object model cannot preserve.
- Direct portable equality/hash/comparison tests agree with JVM/Node.
- Failure of `cbor` 6.5.1 on a fixture is a stop condition. It must produce a fixture-specific capability report before any hand-rolled parser is considered.

### J3 — Three-host conformance gate for the unit

Proposed file box:

- New `test/dao/jing/cbor_conformance_test.cljc`
- New `test/dao/jing/cbor_conformance_test.cljd`
- At most test-only refinements to the frozen fixture loader
- No production edits

Each host should emit a test manifest keyed by fixture ID containing canonical hex, SHA-256, and a normalized semantic signature. Conformance requires equality among JVM, Node, Dart, and the immutable resource.

Completion criteria:

- Every host reads the one shared fixture corpus, not a copied host-local table.
- JVM, Node, and Dart produce identical bytes and digests for every supported case.
- An independent raw CBOR-tree inspection confirms tag/name/payload shape and unsigned bytewise ordering without calling the Jing decoder.
- Pairwise injectivity and declared equivalence groups pass on all hosts.
- No test can rewrite the fixture resource.

Verification lanes:

- JVM: `clj -M:test -n dao.jing.cbor-test`, plus the conformance namespace.
- Node: `bb test:cljs`, or the repository’s documented `clj -M:cljs -m shadow.cljs.devtools.cli compile test` lane.
- Dart: `bb test:cljd`, respecting the repository’s single-owner generated-output rule.
- Full regression after J3: `bb test`.
- Static checks: `clj -M:kondo --lint` over the new source/test paths, `cljstyle check` over new Clojure-family files, and `git diff --check`.

The split is deliberately asymmetric: J0 freezes policy, J1 exercises Boring and the hard normalization rules, J2 isolates Dart/package adaptation, and J3 prevents three internally consistent but mutually incompatible implementations from being accepted.

## 3. Decisions I need from you, resolved where possible

### Dart route: resolved — use `cbor` 6.5.1

The maintained-package route is viable. The pinned package exposes arbitrary tags, `BigInt`, decimal fractions, rational numbers, definite containers, and strict string conversion. [pub.dev API](https://pub.dev/documentation/cbor/latest/cbor/)

The Jing wrapper must still implement profile policy:

- Canonical map/set ordering.
- Duplicate and equality-collapse rejection.
- Closed accepted-tag vocabulary.
- Exact IEEE-754 payload handling.
- Surrogate validation.
- Strict re-encode comparison.

That is profile normalization, not a hand-rolled CBOR parser. No evidence currently justifies replacing the package with a custom parser. Any hidden failure on the frozen corpus is **UNVERIFIED** until J2 runs; if found, it is a stop-and-review event.

### Fixture generation and storage: resolved

Generate the candidate corpus once using two paths:

1. Pinned Boring 0.1.30 for candidate output.
2. Independent CBOR-tree construction/inspection from the written contract, without invoking the Jing wrapper.

Then manually review and commit the literal hex and SHA-256 in `cbor-v1.json`. Runtime tests calculate the digest from frozen hex and compare implementations against it; they never update it.

An upgrade may run against the frozen corpus but may not regenerate it. Changing a byte requires:

- A new explicit contract version or reviewed correction.
- A written case-by-case byte delta.
- Architect approval.
- Independent different-family review.
- No overwrite mode in ordinary tests or build scripts.

A developer-only candidate generator, if useful, should print to stdout or an untracked temporary path and have no command capable of overwriting the frozen resource.

### Effective Boring options: resolved by code inspection plus fixtures

For Boring 0.1.30, `:profile :canonical` locks canonical encoding, string references off, and shapes off; conflicting overrides throw. Normal `boring/encode` emits one item, while indexed encoding is a separate operation. Thus the plan’s option-precedence concern is partly stale for this pin.

The contract must nevertheless prove effects, not trust option names:

- Repeated strings stay ordinary text rather than string references.
- Record-like/repeated structures do not produce shape tables.
- Exact parsing consumes one value and reaches EOF, with no index/trailing frame.
- Canonical map/set fixtures prove the required unsigned bytewise ordering.
- Tests deliberately pass conflicting settings where the API permits and require rejection rather than changed bytes.
- The implementation calls the ordinary encoder, never the indexed API.

### Owning role: resolved

The primary owner is the **DaoSpace & DaoJing Storage Engineer**, whose charter covers content-addressed storage and Jing. The QA/Verification Engineer owns the cross-host verification review, not the implementation. The Stream/Transport Engineer does not become an implementation owner until step 4.

## 4. Routing recommendation

Implementation should begin after the Claude pool refresh at 2026-09-22 04:00 +07.

| Phase | Implementer | Independent reviewer | Sign-off |
|---|---|---|---|
| J0 contract and fixtures | Claude Opus 5 | Gemini 3.1 Pro High | GPT-5.6-sol architect confirms fixture immutability, profile closure, and injectivity |
| J1 JVM/Node codec | Claude Opus 5 | Gemini 3.1 Pro High | GPT architect confirms Boring behavior and no existing-code change |
| J2 Dart codec | Claude Opus 5 | GLM-5.3 | GPT architect confirms package viability and parity |
| J3 conformance | Claude Sonnet 5 | Gemini 3.1 Pro High | GPT architect accepts the complete steps 1–2 unit |

GLM-5.3 is best reserved for one bounded J2 static/adversarial review because it is already around 68% usage and does not reset until 2026-09-27. J2 benefits most from a reviewer independent of both the Claude implementation and the JVM-centered reference path.

Every phase should follow the repository loop: implementer produces the scoped diff and evidence; different-family reviewer issues findings; the same implementer fixes them; the reviewer resumes; GPT performs the architecture sign-off at the stated boundary.

This report is the pre-implementation architect clearance for J0–J3 only. It does not authorize step 3 or either owner gate.

## 5. The owner gate checklist

### Gate 1 — `dao.space` numeric semantics

Exact owner question:

> Do the owners of `dao.space` approve routing numeric equality, hashing, and comparison through `dao.jing.cbor` in `dao.space.index/compare-vals`, the EAVT/AEVT/AVET/VAET comparators, Datalog unification and hashed lookup, and the comparison builtins—including the JVM change from `(= 1 1.0) => false` to portable numeric equality—while keeping arithmetic builtins host-native and rejecting carrier operands? What deterministic operand-selection rule must `min` and `max` use when different numeric kinds compare equal?

Evidence required:

- J3’s direct native/carrier equality, hash, ordering, infinity, NaN, signed-zero, and exact-large-value matrix.
- A proposed step-3 diff limited to the named `dao.space` boundaries.
- Before/after query examples, including `(= 1 1.0)`.
- An index test showing integer `1` and float `1.0` collapse to one covered-index entry while retaining distinct content addresses.
- Recursive collection equality/hash/unification tests.
- Explicit tests showing no numeric string fallback.
- Arithmetic-carrier rejection tests.
- The proposed deterministic `min`/`max` tie rule.
- Recorded approval by the actual `dao.space` owner.

Earliest blocked phase: **step 3**. J0–J3 are not blocked.

### Gate 2 — clean-break rebuild readiness

Exact owner question:

> Have all live old-address artifacts and externally retained references been inventoried and proven rebuildable from their original, still-retained intake values without an intake gap, and do you authorize a clean break that rejects old EDN stores and provides no legacy reader or address alias, knowing that content whose source stream has evicted it is unrecoverable?

Address-bearing artifacts visible in the repository today:

- **Published indexes:** `dao.space.index/publish-index!` writes node blobs and then a manifest containing root addresses. The repository shows the mechanism and tests, but no committed production publication snapshot. Deployed manifests and external root references are **UNVERIFIED**.
- **AST/code addresses:** `yin.vm`, `yin.vm.code`, `yin.vm.content`, `yin.vm.semantic`, `yin.vm.macro`, and `yin.vm.ledger` derive addresses for code rows/vectors and `:yin.code/hash`. `yin.vm.pipeline` now calls `dao.jing/materialize!` for the projected de Bruijn envelope. Tests calculate these dynamically; no committed production address catalog was found.
- **Continuations:** completion/reified values can carry code-segment and module-manifest addresses. The continuation demo uses in-memory `:pending-ks` or ring-buffer batches; its parked ID is not itself a Jing address. No durable Jing-materialized continuation snapshot or fixed continuation address was found. External checkpoints are **UNVERIFIED**.
- **Stored files:** `dao.jing.file` still represents the old EDN-framed store. Repository tests use temporary files, and the remote demo names `target/dao-jing-remote-demo.log`. No tracked live store file was found. User or deployed store locations are **UNVERIFIED**.
- **Fixtures:** `test/dao/data/psset_fixtures.cljc` is the substantive committed old-address fixture and is generated by `src/dev/psset_fixtures.clj`; it must be deliberately regenerated in step 5. `test/dao/jing/stream_test.cljc` contains an all-zero shape sentinel, not a retained content address. Other Jing tests predominantly calculate addresses dynamically or use intentionally malformed placeholders.

Required readiness procedure:

1. Inventory every store coordinate, published manifest/root, AST/code address, projected de Bruijn envelope, module reference, continuation reference, and address held outside this repository.
2. Associate each artifact with its original intake stream and pre-publication/origin cursor.
3. Replay from that original cursor into a fresh CBOR store through an observing consumer.
4. Treat any `:dao.stream/gap` as gate failure. An in-memory log is durable only for its process lifetime; a ring buffer can evict. Minting a new `:oldest` cursor after eviction is not evidence that the original history survives.
5. Preflight every source payload against the new supported domain, including the rejection of characters, instants, UUIDs, arbitrary tagged values, and records.
6. Verify graph closure: published manifests resolve every node; AST/hash rows and projected envelopes resolve; continuation/module references resolve.
7. Produce an offline old-address-to-new-address audit report for migration verification. Do not turn it into a runtime alias layer.
8. Compare semantic contents and counts before declaring the rebuild complete.

The repository does not show a durable observer checkpoint, a complete deployment inventory, or retained-history proof. Rebuild readiness is therefore **UNVERIFIED** today.

Earliest blocked phase: **step 3**, before new addresses or migrated backends can become authoritative. It does not block J0–J3.

## 6. Risks and stale spots since 2026-09-17

- The de Bruijn D0–D6 work has landed. `yin.vm.pipeline` now calls `dao.jing/materialize!`, so the projected envelope is a newly concrete migration artifact and must be included in the rebuild inventory.
- The repository has already added `dao.jing/sha256-bytes` and `dao.jing/canonical-bytes`, and `dao.jing.stream` already carries canonical byte payloads. Step 3 should reuse these boundaries rather than recreating them.
- Boring 0.1.30 and Dart `cbor` 6.5.1 are already pinned through the stream-CBOR work; the plan’s implied dependency-addition work is stale.
- Both pins remain current as inspected today, but they should be checked once immediately before implementation. Discovery of a newer release is not authority to upgrade: implementation must stay on the pins unless a separate reviewed dependency change is opened.
- The frozen Jing fixtures do need fresh generation and independent inspection before codec implementation. Existing `dao.stream.cbor` fixtures are useful precedent but are not substitutable: that profile uses tag-39 identifiers, carries different metadata, lacks Jing numeric carriers, and has different list semantics.
- The plan’s Boring option-precedence uncertainty is substantially resolved by the pinned source: canonical-profile fields are locked. Byte fixtures remain mandatory because they prove output rather than API intent.
- The plan’s assertion that the four array-payload named frames are read as `boring.data/UnknownRecord` is stale for 0.1.30. Closed dispatch remains correct, but it must be implemented through the callback/sentinel path.
- The `min`/`max` equal-value, mixed-kind tie rule remains genuinely unresolved and must be fixed by the `dao.space` gate.
- `test/dao/data/psset_fixtures.cljc` contains old address fixtures and will become stale at the address cutover, but changing it belongs to step 5, not this unit.
- The assumption that deployed values all fit the new narrower domain is not evidenced by repository code. Actual retained streams and stores require preflight; this remains **UNVERIFIED**.
- Steps 1–2 can establish byte-level and codec-semantic conformance, but they cannot satisfy the plan’s final consumer/backend acceptance by themselves. Claiming full epic acceptance after J3 would be premature.

No files were edited, and no implementation or test command was run.

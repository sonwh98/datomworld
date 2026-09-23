# Multihash-Style Content Addressing for DaoJing and yin.vm

Status: design, reviewed and revised; not implemented

## Decision

DaoJing will support multiple hash algorithms as permanent, first-class registry members. The initial registry contains:

- BLAKE3-256, the default minting algorithm; and
- SHA-256, an equally supported algorithm available through explicit minting.

SHA-256 is not deprecated, transitional, or a compatibility fallback. A caller may intentionally mint SHA-256 content at any time:

```clojure
(segment-key value {:algorithm :sha256})
```

A DaoJing store may ordinarily contain addresses produced by both algorithms. Parsing, verification, remote transfer, DHT storage, and cache hydration dispatch according to the algorithm carried by each address.

There is no migration or backward-compatibility project. Datom.world has no deployed DaoJing stores, published manifests, externally held addresses, or legacy systems. The registry and BLAKE3 default therefore land as one clean contract: fixtures and development stores are regenerated rather than migrated.

BLAKE3 is pinned to its standard unkeyed 256-bit output. Variable-length BLAKE3 output, keyed hashing, and derivation-key modes are different algorithms and are not admitted under the `blake3` identifier.

## Address format

Addresses describe both:

1. the canonical encoding profile whose bytes were hashed; and
2. the hash algorithm applied to those bytes.

The general form is:

```text
:segment/<encoding-id>+<algorithm-id>-<lowercase-hex-digest>
```

The initial canonical spellings are:

```text
:segment/print-v1+blake3-<64 lowercase hex>
:segment/sha256-<64 lowercase hex>
```

The shorter SHA-256 form is simply the registry's canonical spelling for the `print-v1`/`sha256` pair:

```clojure
{:encoding :jing.print/v1
 :algorithm :sha256
 :digest    "..."}
```

It is not an alias for another spelling and is not treated specially because of age. The registry formatter emits it whenever that pair is selected. The expanded spelling `:segment/print-v1+sha256-...` is not also accepted, because one profile/algorithm/digest tuple must have one canonical address.

The initial spelling table is therefore:

| Encoding | Algorithm | Canonical address |
|---|---|---|
| `:jing.print/v1` | `:blake3` | `:segment/print-v1+blake3-<digest>` |
| `:jing.print/v1` | `:sha256` | `:segment/sha256-<digest>` |

Future canonical CBOR combinations can use:

```text
:segment/cbor-v1+blake3-<digest>
:segment/cbor-v1+sha256-<digest>
```

### Why encoding remains in the address

Encoding profile and hash algorithm are independent dimensions:

```text
value ── encoding profile ──► canonical bytes ── algorithm ──► digest
```

Multihash-style algorithm agility alone cannot identify which canonical bytes were hashed. DaoJing already has a separate, planned transition from the current order-normalized printer to canonical CBOR. Carrying the encoding profile:

- makes the digest preimage contract explicit;
- prevents a future encoder change from silently changing the interpretation of an address;
- permits either algorithm to operate over any approved encoding profile;
- keeps algorithm selection independent from the CBOR timeline; and
- leaves future designs free to authorize one or several encoding profiles without changing the address grammar again.

This does not require print-v1 and cbor-v1 to coexist in production. The CBOR design already authorizes a clean encoding break. When CBOR addressing is activated, that design may replace print-v1 rather than retain it. The address still states the byte contract unambiguously.

The format is CID-like in carrying both representation and algorithm identifiers, but remains readable EDN. DaoJing does not adopt IPFS's binary CID or varint multihash representation.

## Invariants

- An address is derived solely from canonical bytes, their encoding profile, and their hash algorithm.
- BLAKE3 and SHA-256 are permanent, first-class algorithm-registry members.
- BLAKE3 is the default for implicit minting; SHA-256 minting is explicit and ordinary.
- Address verification always uses the profile and algorithm carried by the address, never the current minting defaults.
- Verification is address-directed; minting primitives are not validators.
- Copying content already named by an address preserves the profile and algorithm carried by that address.
- Registries are closed and immutable. There is no runtime algorithm registration or hidden mutable state.
- Adding or removing a registry entry is an explicit source and contract change.
- Unknown algorithms, unknown encoding profiles, malformed lengths, uppercase hex, and non-hex digests fail closed.
- Digest length belongs to the algorithm registry rather than to the parser. Both initial algorithms produce 32-byte digests.
- A bare digest is not a durable content identifier. Persisted identities use a self-describing address.
- Every supported profile/algorithm/digest tuple has exactly one canonical address spelling.
- An encoding-profile identifier denotes immutable byte semantics. A byte-level change requires a new identifier.
- Algorithm choice does not change the value domain of an encoding profile.
- Profile refusal occurs before hashing and is part of the profile contract.

## DaoJing design

### Closed algorithm registry

`dao.jing` owns a small immutable algorithm registry:

```clojure
{:blake3 {:address-id "blake3"
          :digest-bytes 32
          ...}
 :sha256 {:address-id "sha256"
          :digest-bytes 32
          ...}}
```

Each entry defines:

- its internal keyword;
- its canonical address identifier;
- its digest length;
- its byte-digest implementation; and
- enough formatting metadata to validate its lowercase hexadecimal result.

The registry contains no runtime extension mechanism. Supporting a future algorithm requires a reviewed source change, a pinned identifier, provider choices for every host, official vectors, and cross-host address fixtures.

The default is explicit immutable data:

```clojure
default-hash-algorithm ; => :blake3
```

The default controls implicit minting only. It is never consulted to verify an existing address.

### Closed encoding registry

The initial production encoding registry contains:

```clojure
{:jing.print/v1 {:address-id "print-v1"
                 ...}}
```

The current implementation remains the existing order-normalized, metadata-aware printer until canonical CBOR addressing lands.

The registry seam also admits a test-only `cbor-v1` profile backed by the existing `dao.jing.cbor/encode`. Production activation of that entry belongs to the separate CBOR addressing change.

The encoding default is explicit:

```clojure
default-content-encoding ; => :jing.print/v1
```

As with the algorithm default, it controls minting and does not override an address's carried profile.

### Public digest primitives

Add algorithm-neutral primitives:

```clojure
(digest-bytes algorithm bytes) ; lowercase hex
(digest-string algorithm s)    ; UTF-8, then digest-bytes
```

Unknown algorithms throw with the requested identifier.

Keep explicit algorithm functions where they make frozen contracts readable:

```clojure
(sha256 s)
(sha256-bytes bytes)
(blake3 s)
(blake3-bytes bytes)
```

These functions never follow the default. `sha256` remains useful to VM and DHT contracts independently of its membership in the DaoJing address registry.

### `canonical-bytes`

Retain:

```clojure
(canonical-bytes value)
```

Add explicit profile selection:

```clojure
(canonical-bytes value {:encoding :jing.print/v1})
```

The one-argument form selects `default-content-encoding`.

A profile encoder either returns its canonical host byte buffer or refuses according to its own value-domain contract. It must never silently fall back to another encoder.

### `content-hash`

Retain:

```clojure
(content-hash value)
```

It hashes the current default encoding with BLAKE3.

Add:

```clojure
(content-hash value {:encoding profile
                     :algorithm algorithm})
```

Examples:

```clojure
(content-hash value)
(content-hash value {:algorithm :sha256})
(content-hash value {:encoding :jing.print/v1
                     :algorithm :blake3})
```

Implementation is always:

```text
canonical-bytes(profile, value)
        │
        ▼
digest-bytes(algorithm, bytes)
```

There must be one canonical byte stream and one digest operation.

Because the result remains bare hexadecimal text, `content-hash` is suitable for local calculations and explicitly versioned contracts. Newly persisted standalone identity fields use `segment-key` or another self-describing envelope.

### `segment-key`

Retain:

```clojure
(segment-key value)
```

Add explicit selection:

```clojure
(segment-key value {:algorithm :sha256})

(segment-key value {:encoding :jing.print/v1
                    :algorithm :blake3})
```

The one-argument form uses `default-content-encoding` and `default-hash-algorithm`.

Formatting is data-driven by the selected registry entries, including the canonical short spelling for print-v1/SHA-256.

The namespace is exactly `segment`. Identifiers and digest text are lowercase ASCII, and every output must survive `pr-str` and EDN reading unchanged.

### Address parsing

Add one authoritative parser:

```clojure
(parse-segment-address address)
;; => {:encoding :jing.print/v1
;;     :algorithm :blake3
;;     :digest "..."
;;     :canonical address}
;; or nil
```

All accessors delegate to it:

```clojure
(segment-address? address)
(segment-encoding address)
(segment-algorithm address)
(segment-hash address)
```

`segment-address?` remains a total predicate. The other accessors throw on invalid input.

The parser:

1. requires a keyword in the `segment` namespace;
2. matches a canonical spelling declared by registry data;
3. resolves the encoding and algorithm;
4. validates the digest length from the algorithm entry;
5. requires lowercase hexadecimal text; and
6. rejects noncanonical alternative spellings.

Encoding identifiers contain `-`, including `print-v1` and future `cbor-v1`. The parser must therefore match registered spelling rules rather than naively splitting the name on `-`.

An identifier that is syntactically plausible but absent from the closed registry is invalid.

### `segment-matches?`

Add:

```clojure
(segment-matches? address payload)
```

It:

1. parses the address;
2. selects the carried encoding profile;
3. canonically encodes the payload under that profile;
4. selects the carried algorithm;
5. hashes the bytes;
6. compares the digest; and
7. confirms that reformatting the parsed tuple reproduces the canonical address.

This dispatch is the core multihash behavior. A store may contain an explicitly minted SHA-256 object and a default-minted BLAKE3 object at the same time, and each verifies under its own algorithm.

Malformed, unsupported, or noncanonical addresses return false. Encoding-profile refusal also returns false unless a caller's established diagnostic contract requires it to be surfaced separately; this behavior must be chosen once and tested consistently.

Outside `dao.jing`, this is the normal operation for validating an address/payload pair. Callers do not manually compare `segment-hash` with `content-hash`, nor re-mint under defaults with `segment-key`.

### `materialize!`

Retain:

```clojure
(materialize! handle payload)
```

Add:

```clojure
(materialize! handle payload opts)
```

The one-argument form mints with print-v1/BLAKE3. The explicit form supports any registered profile/algorithm pair:

```clojure
(materialize! handle payload {:algorithm :sha256})
```

On `:present`, verify the stored payload with:

```clojure
(segment-matches? address stored)
```

Do not use the default algorithm to validate it.

When copying content already named by an address into another store, callers derive the mint selection from that address:

```clojure
(materialize! destination payload
  {:encoding  (segment-encoding address)
   :algorithm (segment-algorithm address)})
```

The returned address must equal the source address. This is required for ordinary multi-algorithm operation: a SHA-256 object fetched from a peer remains a SHA-256 object when cached, even though BLAKE3 is the default for unrelated new content.

### `get`

The signature remains:

```clojure
(get handle address not-found)
```

It accepts every address recognized by `segment-address?`, including both initial algorithm spellings. It rejects arbitrary keys before consulting the backend.

`get` need not rehash every successful read. Existing backend insertion/replay checks and higher-level verified readers retain their responsibilities. Where a consumer already verifies fetched content, it uses `segment-matches?`.

### File-backend codec round-trip

`dao.jing.file/validate-codec-round-trip!` is not an address/payload validator. It compares a payload's identity with its own `pr-str`/EDN round trip to ensure that the file codec does not discard address-significant information.

It does not use `segment-matches?`. Both sides must be hashed under the profile and algorithm selected for the pending address:

```clojure
(content-hash payload profile-options)
(content-hash replayed profile-options)
```

This makes explicit SHA-256 materialization and default BLAKE3 materialization obey the same file-codec rule.

## Clean introduction

The registry is introduced without a migration phase.

There are no deployed DaoJing stores or published addresses to preserve. The implementation therefore changes the contract atomically:

- BLAKE3 becomes the one-argument minting default immediately.
- Explicit SHA-256 minting is available immediately as an ordinary registry operation.
- Existing development fixtures are regenerated.
- Existing file stores are not upgraded or read through a compatibility path.
- Existing checkpoint shapes are replaced rather than dual-read.
- All peers in a development composition run the new address contract together.
- No old-address alias, graph migration, dual checkpoint schema, or format negotiation is added.

The absence of migration machinery does not weaken multi-algorithm support. SHA-256 and BLAKE3 coexist because callers may intentionally select either algorithm after the registry lands, not because content from an earlier system is being carried forward.

## Call-site audit and required dispositions

Hash use outside `dao.jing` falls into four classes:

1. frozen non-Jing hash contracts;
2. minting new content;
3. validating content against an existing address; and
4. copying addressed content while preserving its selected algorithm.

The classification is a maintained design artifact. Minting and verification must not be conflated.

### Direct `jing/sha256` consumers

The current tree contains five direct consumers:

- `yin.vm.debruijn_code/descriptor-hash`
- `yin.vm.debruijn_code/image-hash`
- `yin.vm.debruijn/dimension-hash`, a `def` computed once at namespace load
- `yin.vm.debruijn/node-hash`
- `dao.jing.dht/node-id`

These calls do not follow DaoJing's default.

The four de Bruijn hashes remain SHA-256 because they are VM-format contracts. Their byte rules, descriptor declarations, contract versions, and golden values are frozen and must change only through their own format-version process.

The DHT node ID remains SHA-256 because it defines the DHT routing identity space, not a content-address selection. Changing it is a separate DHT protocol decision.

Neither disposition is a legacy-support exception. They are independent contract boundaries that happen to specify SHA-256.

The task that originated this plan named `src/cljc/yin/vm/debruijn_register_code.cljc` and register contract tests, but those files do not exist in the reviewed checkout. The register design nevertheless specifies R as SHA-256. Its implementation follows that frozen format contract when it lands.

### Primitive-profile identifier

`yin.vm/primitive-profile` currently constructs:

```clojure
:yin.k.pp/sha256-<jing/content-hash description>
```

Once the default becomes BLAKE3, that expression would label a BLAKE3 digest as SHA-256.

Keep the primitive-profile contract explicitly on print-v1/SHA-256:

```clojure
(content-hash description
  {:encoding :jing.print/v1
   :algorithm :sha256})
```

The `sha256-` label then remains truthful. This is an explicit VM contract using a first-class registry algorithm, not a compatibility fallback.

If the primitive-profile contract is later versioned to another algorithm or encoding, its label and contract stamp change together in that VM-specific review.

### DaoSpace schema identity

`dao.space.index/checkpoint-candidate` currently persists a bare `:schema-hash`, and `restore` recomputes it using the ambient default. A bare digest is not algorithm-agile.

Replace it outright with:

```clojure
:schema-address (jing/segment-key schema)
```

`restore` verifies the supplied schema with:

```clojure
(jing/segment-matches? schema-address schema)
```

There is no fallback reader for the old `:schema-hash` field and no dual candidate shape. No existing checkpoint candidate requires one.

### Address-directed validation sites

These sites validate content against an address already supplied by stored data, a caller, or a peer. They use `segment-matches?`:

- `dao.jing/materialize!` on `:present` read-back
- `dao.jing.mem/validate-address-payload!`
- `dao.jing.file/validate-address-payload!`
- `dao.jing.remote/validate-address-payload!`
- `dao.jing.remote.step` verification of a `:present` response
- `dao.jing.dht/validate-address-payload!`
- `dao.jing.dht` peer-fetched content verification before caching
- `dao.jing.dht.node` store-request validation
- `dao.data.btree.storage` optional fetched-blob verification in the ordinary KV reader
- `dao.space.index/read-manifest`
- `yin.vm.content` row fetch verification
- `yin.vm.content` vector fetch verification
- `yin.vm.macro` row-address verification
- `yin.vm.semantic` claimed-segment verification
- `yin.vm.completion` reconstructed-image address verification
- `yin.vm.ledger` row-address verification
- `yin.vm.ledger` output-address verification
- `yin.vm.ledger` record/derivation verification
- `yin.vm` semantic-bytecode row validation

Backend sites currently compare `segment-hash` with `content-hash`; DaoSpace and yin.vm sites generally re-mint with one-argument `segment-key` and compare whole addresses. Both mechanisms incorrectly substitute minting defaults for address-directed verification.

Generation-only calls to `segment-key` remain one-argument calls when BLAKE3 is intended. Callers intentionally choosing SHA-256 pass `{:algorithm :sha256}`.

### Address-directed mint sites

Three copy paths receive content under an existing address and materialize it into a local cache:

- `dao.jing.dht/make-get`
- `dao.data.btree.storage/hydrate!`, synchronous `pull!`
- `dao.data.btree.storage/hydrate-async`, asynchronous `fetched!`

After verifying the payload, these paths materialize using the address-carried selection:

```clojure
(materialize! local payload
  {:encoding  (segment-encoding address)
   :algorithm (segment-algorithm address)})
```

Using one-argument `materialize!` would incorrectly convert an intentionally SHA-addressed object into a BLAKE3-addressed cache entry.

This is ordinary multihash correctness, not migration behavior.

### Documentation-only SHA assumptions

Docstrings and errors in `dao.jing`, `dao.jing.mem`, `dao.jing.file`, `dao.jing.dht`, `dao.jing.dht.kad`, `dao.space.index`, and `dao.jing.stream` that describe segment storage as SHA-only must be generalized.

The DHT may route by the carried 256-bit digest. Both initial algorithms produce 64 hexadecimal characters, and the full segment address remains the storage key. If a future registered algorithm has a different digest width, Kademlia normalization must be designed explicitly rather than assuming every registry entry is 256 bits.

## yin.vm H and R disposition

H and R remain explicitly SHA-256.

For the implemented stack format:

- keep `descriptor-hash` and `image-hash` on explicit SHA-256;
- keep descriptor data declaring `:hash :sha256`;
- do not repin the golden H corpus;
- add a regression proving that DaoJing's BLAKE3 default does not change H.

The older `yin.vm.debruijn` dimension and node hashes likewise remain explicit SHA-256 under their existing format contract.

For the planned register format:

- implement `register-descriptor-hash` and `register-hash` with explicit SHA-256;
- retain the documented R formula and descriptor declaration;
- do not couple R to `default-hash-algorithm`.

This is neither compatibility support nor an application of DaoJing's multihash registry. H and R are executable-format identities with their own canonical encoders, descriptor hashes, contract versions, and pinned golden values. Changing them requires a VM format-version decision and complete re-pin under that process.

Jing addresses for stored stack or register images are separate identities. An image may have:

- H or R, identifying its executable-format bytes; and
- a DaoJing segment address, identifying the stored Jing value under a selected encoding and registry algorithm.

The two identities must not be conflated merely because both can use SHA-256.

## Host libraries

Use the portability spike's validated BLAKE3 providers:

- JVM: `io.github.rctcwyvrn/blake3` 1.3
- ClojureScript/Node: `@noble/hashes` 2.4.0
- ClojureDart: `blake3_dart` 1.0.0

Pin exact versions. A content-identity dependency must not use floating ranges.

The portability spike's cross-provider result left no durable repository artifact. The implementation therefore commits official-vector fixtures and the cross-provider digest table as the evidence of record.

SHA-256 keeps its current per-host implementations, including the hand-written ClojureDart implementation. That code now serves both the first-class SHA-256 registry entry and the explicit VM/DHT contracts.

### Maintenance risks

The JVM BLAKE3 dependency is pure bytecode and MIT licensed but stale, with its Maven release dating to 2020. Acceptance requires:

- source and license review;
- official BLAKE3 known-answer tests;
- chunk and tree-boundary vectors;
- a cross-host fixture corpus;
- isolation behind `digest-bytes`; and
- a provider-replacement procedure that requires complete conformance before adoption.

The Dart dependency is pure Dart and passed official vectors, but has only one release and a short history. It receives the same fixture and replacement discipline. It is also the Dart host's first external cryptographic dependency, so dependency resolution and release-build execution are explicit gates.

`@noble/hashes` is actively maintained, but its ESM/package integration must be exercised in the current Shadow-CLJS test and optimized-release builds.

Any provider version or replacement that changes a pinned digest is rejected as nonconforming to the registered algorithm.

## Sequencing with canonical CBOR

The algorithm-registry epic lands first over `:jing.print/v1`.

That establishes:

- the compound address grammar;
- permanent BLAKE3 and SHA-256 algorithm entries;
- BLAKE3 as the minting default;
- explicit SHA-256 minting;
- address-directed verification; and
- algorithm-preserving copy paths.

Canonical CBOR addressing remains a separate clean-break epic. A canonical codec already exists in `dao.jing.cbor`; its byte contract is frozen by `test/resources/dao/jing/cbor-v1.json` and is currently used for encoded comparison rather than addressing.

When CBOR addressing is activated:

- `:jing.cbor/v1` is backed by that exact codec and corpus;
- BLAKE3 remains the default algorithm;
- SHA-256 remains explicitly selectable;
- addresses use `cbor-v1+blake3` or `cbor-v1+sha256`;
- development stores, fixtures, manifests, ASTs, and continuations are rebuilt together;
- no print-address reader, alias, or graph migration is introduced solely for the encoding transition; and
- the CBOR design's SHA-only address examples are updated to the registry contract.

The existing CBOR "Addressing and clean break" ruling remains authoritative about migration policy. This document changes only its algorithm assumption: CBOR bytes participate in the same permanent multi-algorithm registry.

The print and CBOR value domains must not be assumed identical. Print-v1 refuses records, while CBOR has its own explicit refusal classes, including `:non-canonical` and `:unpaired-surrogate`. Activating cbor-v1 requires an explicit account of its accepted and refused values.

## Phased rollout

### H0: contract and evidence

    New: compound address grammar, registry descriptors, call-site
         classification, BLAKE3 fixtures and cross-provider digest table
    Existing edits: design documentation and conformance resources
    Must not change: VM H/R or DHT node-id contracts

Complete when:

- the canonical spelling table is pinned;
- BLAKE3 and SHA-256 are declared permanent first-class registry entries;
- BLAKE3 is declared the initial default;
- official BLAKE3-256 fixtures and representative print-v1 value fixtures are recorded;
- the portability spike's cross-provider digest table is committed;
- every source use of Jing hash/address operations and every `sha256` literal is classified as frozen contract, new-content mint, existing-address validation, or address-preserving copy;
- the table records `yin.vm.debruijn/dimension-hash` accurately as a `def`;
- the absent register implementation is recorded as future integration;
- malformed, unknown-profile, unknown-algorithm, noncanonical-spelling, wrong-length, uppercase, and EDN round-trip cases are specified; and
- an architectural lint/test is specified to reject default `content-hash` or `segment-key` in equality-based validation positions outside `dao.jing`, subject only to narrow reviewed mint assertions.

### H1: registry and whole-system cutover

    Dependencies: the three pinned BLAKE3 providers
    Existing edits: dao.jing, dependency manifests, backends, DaoSpace,
                    yin.vm consumers, fixtures and tests
    Minting default: print-v1 plus BLAKE3
    Explicit peer: print-v1 plus SHA-256
    Must not change: VM H/R, de Bruijn format hashes, DHT node IDs

Complete when:

- `digest-bytes` and `digest-string` support both algorithms on all three hosts;
- one-argument `content-hash`, `segment-key`, and `materialize!` use BLAKE3;
- explicit `{:algorithm :sha256}` minting works everywhere;
- parser and accessor tests pass for both canonical address spellings;
- all validation sites in the complete audit use `segment-matches?`;
- all three address-preserving copy sites derive their mint selection from the source address;
- file codec round-trip validation uses the selected profile and algorithm on both sides;
- `dao.space.index` emits only `:schema-address` and restore accepts only that shape;
- `yin.vm/primitive-profile` computes the digest explicitly as print-v1/SHA-256, keeping its label truthful;
- every committed development fixture containing segment addresses is regenerated;
- existing development file stores are discarded rather than upgraded;
- the architectural lint/test preventing default-following verification is active;
- CLJ, optimized CLJS, and CLJD builds resolve and execute their providers; and
- the full repository test suite passes under the new address contract.

### H2: multi-algorithm and portability verification

    Existing edits: integration tests, CI, provider notes
    Must not change: registry identifiers or canonical address spellings

Complete when:

- the same payload can be intentionally materialized and retrieved under both algorithms;
- each address verifies only under the algorithm it carries;
- malformed and algorithm-mismatched payloads are rejected;
- memory, file replay, remote, stepped remote, DHT, ordinary B-tree reads, synchronous hydration, asynchronous hydration, DaoSpace manifests, `yin.vm.content`, `yin.vm.macro`, `yin.vm.semantic`, `yin.vm.completion`, `yin.vm.ledger`, and semantic-bytecode row validation operate correctly with both algorithm selections;
- DHT caching and both hydration paths preserve an explicitly SHA-256 address rather than reminting it under BLAKE3;
- a store can contain both address forms through ordinary post-registry use;
- remote and DHT peers exchange and validate both forms;
- official vectors cover empty input, non-ASCII UTF-8, 1023/1024/1025-byte boundaries, and multi-chunk input;
- randomized cross-host corpora compare complete addresses;
- optimized CLJS and release CLJD builds are exercised;
- dependency versions and licenses are recorded;
- errors name the relevant algorithm and encoding profile without embedding whole sensitive payloads; and
- changing the minting default in a test seam cannot change verification of an already parsed address.

### H3: encoding seam and documentation closure

    Existing edits: dao.jing, CBOR integration tests, affected design docs
    Must not activate: production CBOR addressing

Complete when:

- a test-only cbor-v1 profile backed by `dao.jing.cbor/encode` proves that encoding and algorithm dispatch are independent;
- both `cbor-v1+blake3` and `cbor-v1+sha256` fixture addresses can be derived in the test seam;
- the production registry still uses print-v1 until the separate CBOR epic lands;
- `dao.jing.md` distinguishes encoding profile, hash algorithm, digest, address, minting, verification, and copying;
- `dao.jing.md` states: "Verification must be address-directed; minting primitives are not validators";
- `dao.jing.md` states that copying addressed content preserves the address-carried profile and algorithm;
- SHA-only storage statements are removed;
- the CBOR design's future address examples use the algorithm registry rather than hardcoded SHA-256;
- the print-v1 and cbor-v1 refusal/value-domain distinction is documented;
- yin.vm documents state that H/R remain SHA-256 because of VM contract freeze, not compatibility policy or DaoJing algorithm selection;
- the call-site classification and lint rule are documented as maintenance obligations; and
- source docstrings agree with the implemented grammar and defaults.

## Test obligations

The minimum acceptance matrix is:

- SHA-256 and BLAKE3 known-answer tests on CLJ, CLJS, and CLJD.
- Identical `digest-string` behavior for non-ASCII UTF-8.
- Identical print-v1 canonical bytes and full addresses across hosts.
- Committed official-vector and cross-provider fixtures.
- EDN print/read round-trip for both canonical address spellings.
- Strict rejection of invalid namespace, profile, algorithm, case, length, characters, and alternative spelling.
- Correct parsing of hyphenated encoding identifiers through registry matching.
- Default BLAKE3 and explicit SHA-256 minting.
- The same payload stored under both algorithms as an ordinary supported operation.
- Retrieval and verification of both algorithms from one store.
- Address verification unaffected by minting defaults.
- Backend collision and `:present` paths using the address-carried algorithm.
- File codec round-trip checking under the selected mint pair.
- File replay of a store created under the registry contract containing both algorithms.
- Remote and DHT exchange of both algorithms.
- DHT caching of SHA-256 content under its SHA-256 address while the default remains BLAKE3.
- Synchronous and asynchronous B-tree hydration preserving the fetched address's selection.
- DaoSpace candidates containing only `:schema-address`.
- Direct coverage or an integration path for every validation and copy site in the committed classification table.
- A source-level guard against reintroducing default-following verification.
- Unchanged stack H and de Bruijn format-hash goldens.
- Unchanged R goldens when register code lands.
- A real cbor-v1 test profile proving that encoding selection and algorithm selection are independent.
- CBOR refusal classes reported as encoding refusals rather than hash failures.

No test is required solely to demonstrate migration from a pre-registry store, address graph, fixture, or checkpoint shape.

## Explicit non-goals

This plan does not:

- build a migration path for pre-registry stores or addresses;
- add an old-address alias or dual checkpoint reader;
- distinguish SHA-256 content by age or provenance;
- deprecate SHA-256;
- force callers to use BLAKE3 when they explicitly choose SHA-256;
- adopt IPFS binary multihash or CID serialization;
- introduce runtime algorithm or encoding-profile registration;
- negotiate algorithms implicitly between peers;
- activate canonical CBOR for production addressing;
- alter the existing canonical CBOR codec, corpus, or value-domain decisions;
- change DHT node identities or Kademlia distance rules;
- change yin.vm H, R, de Bruijn format hashes, or their contract versions;
- use BLAKE3 keyed mode, derivation mode, or variable-length output;
- redesign DaoJing's synchronous handle, effect stream, retention, or garbage collection; or
- treat hashes as signatures or add authenticity and provenance semantics.

The result is a cleanly introduced, permanently multi-algorithm content-addressing system: BLAKE3 is the ordinary default, SHA-256 is an equal explicit choice, and every address carries enough information to verify its own bytes without relying on ambient defaults.

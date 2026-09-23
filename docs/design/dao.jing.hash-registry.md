# Multihash-Style Content Addressing for DaoJing and yin.vm

Status: design, architect signed off; not implemented

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

A segment address identifies the hash algorithm and carries its digest:

```text
:segment/<algorithm-id>-<lowercase-hex-digest>
```

The initial canonical forms are:

```text
:segment/blake3-<64 lowercase hex>
:segment/sha256-<64 lowercase hex>
```

This is the project's EDN-readable multihash representation. The algorithm identifier is self-describing; the digest length and validation rules come from the corresponding registry entry.

The format deliberately does not reproduce IPFS's binary varint multihash representation. DaoJing addresses cross EDN boundaries and must survive `pr-str` followed by EDN reading unchanged.

There is one current canonical encoder. The address does not identify it. A future canonical-encoding change is a clean break that regenerates every address and store rather than retaining earlier addresses.

## Invariants

- An address is derived solely from the current canonical bytes and the selected hash algorithm.
- BLAKE3 and SHA-256 are permanent, first-class algorithm-registry members.
- BLAKE3 is the default for implicit minting; SHA-256 minting is explicit and ordinary.
- Address verification always uses the algorithm carried by the address, never the current minting default.
- Verification is address-directed; minting primitives are not validators.
- Copying content already named by an address preserves the algorithm carried by that address.
- The algorithm registry is closed and immutable. There is no runtime registration or hidden mutable state.
- Adding or removing a registry entry is an explicit source and contract change.
- Unknown algorithms, malformed lengths, uppercase hex, and non-hex digests fail closed.
- Digest length belongs to the algorithm registry rather than to the parser. Both initial algorithms produce 32-byte digests.
- A bare digest is not a durable content identifier. Persisted identities use an algorithm-qualified address.
- Every supported algorithm/digest pair has exactly one canonical address spelling.
- Canonical encoding precedes algorithm selection: both algorithms hash the same `canonical-bytes` result for a given value.
- Changing canonical encoding changes every digest and is handled as a clean break, not as address compatibility.
- Encoder refusal occurs before hashing and is not an algorithm failure.

## DaoJing design

### Closed algorithm registry

`dao.jing` owns a small immutable registry:

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

Algorithm address identifiers are lowercase ASCII alphanumeric strings matching `[a-z0-9]+`. The parser splits the keyword name on the first `-` to extract the algorithm identifier and digest, then performs an exact registry lookup. There is no ambiguous prefix matching.

### Public digest primitives

Add algorithm-neutral primitives:

```clojure
(digest-bytes algorithm bytes) ; lowercase hex
(digest-string algorithm s)    ; UTF-8, then digest-bytes
```

Unknown algorithms throw with the requested identifier.

Keep explicit functions where they make fixed contracts readable:

```clojure
(sha256 s)
(sha256-bytes bytes)
(blake3 s)
(blake3-bytes bytes)
```

These functions never follow the default. `sha256` remains useful to VM and DHT contracts independently of its membership in the DaoJing address registry.

### `canonical-bytes`

The signature remains:

```clojure
(canonical-bytes value)
```

It continues to return the UTF-8 bytes of the current order-normalized, metadata-aware print encoding.

No algorithm-specific canonicalization is permitted. BLAKE3 and SHA-256 receive identical bytes for the same value.

The future CBOR epic will replace this implementation outright. It will not add a second coexisting encoder or alter this function's public role.

### `content-hash`

Retain:

```clojure
(content-hash value)
```

It hashes `canonical-bytes` with BLAKE3.

Add explicit algorithm selection:

```clojure
(content-hash value {:algorithm :sha256})
```

Implementation is always:

```text
canonical-bytes(value)
        │
        ▼
digest-bytes(algorithm, bytes)
```

There must be one canonical byte stream and one digest operation.

Because the result remains bare hexadecimal text, `content-hash` is suitable for local calculations and explicitly versioned contracts. Newly persisted standalone identity fields use `segment-key` or another algorithm-qualified envelope.

### `segment-key`

Retain:

```clojure
(segment-key value)
```

Add:

```clojure
(segment-key value {:algorithm :sha256})
```

The one-argument form selects `default-hash-algorithm`.

Formatting is data-driven by the selected registry entry:

```clojure
(keyword "segment"
         (str algorithm-id "-" digest))
```

The namespace is exactly `segment`. Algorithm identifiers and digest text are lowercase ASCII, and every output must survive `pr-str` and EDN reading unchanged.

### Address parsing

Add one authoritative parser:

```clojure
(parse-segment-address address)
;; => {:algorithm :blake3
;;     :digest "..."
;;     :canonical address}
;; or nil
```

All accessors delegate to it:

```clojure
(segment-address? address)
(segment-algorithm address)
(segment-hash address)
```

`segment-address?` remains a total predicate. `segment-algorithm` and `segment-hash` throw on invalid input.

The parser:

1. requires a keyword in the `segment` namespace;
2. splits the keyword name on the first `-` into algorithm identifier and digest strings;
3. looks up the algorithm identifier in the registered algorithms table;
4. validates digest length from that algorithm's registry entry;
5. requires lowercase hexadecimal text;
6. reconstructs the canonical address; and
7. rejects unknown algorithms and alternative spellings.

The parser matches registered identifiers rather than accepting arbitrary prefixes. A syntactically plausible but unregistered algorithm is invalid.

### `segment-matches?`

Add:

```clojure
(segment-matches? address payload)
```

It:

1. parses the address;
2. obtains `canonical-bytes` for the payload;
3. selects the algorithm carried by the address;
4. hashes the bytes;
5. compares the digest; and
6. confirms that reformatting the parsed pair reproduces the canonical address.

This dispatch is the core multihash behavior. A store may contain an explicitly minted SHA-256 object and a default-minted BLAKE3 object at the same time, and each verifies under its own algorithm.

Malformed, unsupported, noncanonical, or mismatched addresses return false. Canonical-encoder refusal also returns false, making `segment-matches?` a total verification predicate. Minting operations such as `materialize!` and `segment-key` continue to throw on encoder refusal.

Outside `dao.jing`, this is the normal operation for validating an address/payload pair. Callers do not manually compare `segment-hash` with `content-hash`, nor re-mint under the default with `segment-key`.

### `materialize!`

Retain:

```clojure
(materialize! handle payload)
```

Add:

```clojure
(materialize! handle payload {:algorithm :sha256})
```

The one-argument form mints with BLAKE3. The explicit form supports any registered algorithm.

On `:present`, verify the stored payload with:

```clojure
(segment-matches? address stored)
```

Do not use the default algorithm to validate it.

When copying content already named by an address into another store, derive the minting algorithm from that address:

```clojure
(materialize! destination payload
  {:algorithm (segment-algorithm address)})
```

The returned address must equal the source address. This is required for ordinary multi-algorithm operation: a SHA-256 object fetched from a peer remains a SHA-256 object when cached, even though BLAKE3 is the default for unrelated new content.

### `get`

The signature remains:

```clojure
(get handle address not-found)
```

It accepts every address recognized by `segment-address?`, including both initial algorithms. It rejects arbitrary keys before consulting the backend.

`get` need not rehash every successful read. Existing backend insertion and replay checks, plus higher-level verified readers, retain their responsibilities. A consumer that verifies fetched content uses `segment-matches?`.

### File-backend codec round-trip

`dao.jing.file/validate-codec-round-trip!` is not an address/payload validator. It compares a payload's identity with its own `pr-str`/EDN round trip to ensure that the file codec does not discard address-significant information.

It does not use `segment-matches?`. Both sides must be hashed with the algorithm selected for the pending address:

```clojure
(content-hash payload  {:algorithm algorithm})
(content-hash replayed {:algorithm algorithm})
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

The absence of migration machinery does not weaken multi-algorithm support. SHA-256 and BLAKE3 coexist because callers may intentionally select either after the registry lands, not because content from an earlier system is being carried forward.

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

The DHT node ID remains SHA-256 because it defines the DHT routing identity space rather than a content-address selection. Changing it is a separate DHT protocol decision.

Neither disposition is a legacy-support exception. They are independent contract boundaries that happen to specify SHA-256.

The task that originated this plan named `src/cljc/yin/vm/debruijn_register_code.cljc` and register contract tests, but those files do not exist in the reviewed checkout. The register design nevertheless specifies R as SHA-256. Its implementation follows that frozen format contract when it lands.

### Primitive-profile identifier

`yin.vm/primitive-profile` currently constructs:

```clojure
:yin.k.pp/sha256-<jing/content-hash description>
```

After BLAKE3 becomes the default, that expression would label a BLAKE3 digest as SHA-256.

Keep the primitive-profile contract explicitly on SHA-256:

```clojure
(content-hash description {:algorithm :sha256})
```

The `sha256-` label then remains truthful. This is an explicit VM contract using a first-class registry algorithm, not a compatibility fallback.

A future canonical-encoding clean break may change this digest because `content-hash` follows the one current canonical encoder. If primitive-profile identity must remain frozen across that break, its owning VM contract must first pin its own byte encoder rather than relying on DaoJing's changing content encoder.

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

The following sites validate content against an address already supplied by stored data, a caller, or a peer. They use `segment-matches?`:

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

Four copy paths receive content under an existing address and materialize or flush it into a local cache or remote store:

- `dao.jing.dht/make-get`
- `dao.data.btree.storage/hydrate!`, synchronous `pull!`
- `dao.data.btree.storage/hydrate-async`, asynchronous `fetched!`
- `dao.data.btree.storage/store-tree-async`, cache-minted blob flush to remote

After verifying the payload, local cache paths materialize using the address-carried algorithm:

```clojure
(materialize! local payload
  {:algorithm (segment-algorithm address)})
```

In `store-tree-async`, the flush must supply the source address (and its algorithm) to the remote store via `put-content` or an explicit address-supplying operation rather than calling un-parameterized `materialize-async-fn` / `request-materialize`, which would incorrectly re-mint under the remote's ambient default.

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
- a DaoJing segment address, identifying the stored Jing value under its selected registry algorithm.

The two identities must not be conflated merely because both can use SHA-256.

## Host libraries

Use the portability spike's validated BLAKE3 providers:

- JVM: `io.github.rctcwyvrn/blake3` 1.3
- ClojureScript/Node: `@noble/hashes` 2.4.0
- ClojureDart: `blake3_dart` 1.0.0

Pin exact versions. A content-identity dependency must not use floating ranges.

The portability spike's cross-provider result left no durable repository artifact. The implementation therefore commits official-vector fixtures and the cross-provider digest table as the evidence of record.

SHA-256 keeps its current per-host implementations, including the hand-written ClojureDart implementation. That code serves both the first-class SHA-256 registry entry and the explicit VM/DHT contracts.

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

The algorithm-registry epic lands first over the current order-normalized print encoder.

It establishes:

- the flat multihash address grammar;
- permanent BLAKE3 and SHA-256 entries;
- BLAKE3 as the minting default;
- explicit SHA-256 minting;
- algorithm-directed verification; and
- algorithm-preserving copy paths.

Canonical CBOR remains a separate clean-break epic. A canonical codec already exists in `dao.jing.cbor`; its byte contract is frozen by `test/resources/dao/jing/cbor-v1.json` and is currently used for encoded comparison rather than addressing.

When CBOR lands, it replaces the implementation of `canonical-bytes` outright. The algorithm registry remains unchanged:

- BLAKE3 remains the default;
- SHA-256 remains explicitly selectable;
- the address grammar remains `:segment/<algorithm-id>-<digest>`;
- every digest and every DaoJing address is regenerated from CBOR bytes;
- development stores, fixtures, manifests, ASTs, and continuations are rebuilt together; and
- no printer-address reader, alias, graph migration, or encoding negotiation is introduced.

The existing `dao.jing.cbor.md` "Addressing and clean break" ruling remains authoritative. This document changes its SHA-only algorithm assumption but does not alter its clean-break policy.

The print and CBOR value domains must not be assumed identical. The current encoder refuses records and has documented residual limitations. CBOR has its own explicit refusal classes, including `:non-canonical` and `:unpaired-surrogate`. The CBOR epic owns the final value-domain contract.

## Phased rollout

### H0: contract and evidence

    New: flat address grammar, algorithm registry descriptors, call-site
         classification, BLAKE3 fixtures and cross-provider digest table
    Existing edits: design documentation and conformance resources
    Must not change: VM H/R or DHT node-id contracts

Complete when:

- `:segment/blake3-<digest>` and `:segment/sha256-<digest>` are pinned as the canonical forms;
- BLAKE3 and SHA-256 are declared permanent first-class registry entries;
- BLAKE3 is declared the initial default;
- official BLAKE3-256 fixtures and representative canonical-value fixtures are recorded;
- the portability spike's cross-provider digest table is committed;
- every source use of Jing hash/address operations and every `sha256` literal is classified as frozen contract, new-content mint, existing-address validation, or address-preserving copy (with `request-materialize` and `materialize-async-fn` classified as default-only remote mint entry points, and `store-tree-async` classified as an address-preserving copy flush);
- the classification records `yin.vm.debruijn/dimension-hash` accurately as a `def`;
- the absent register implementation is recorded as future integration;
- malformed, unknown-algorithm, noncanonical-spelling, wrong-length, uppercase, encoder-refusal (returning false for `segment-matches?`), and EDN round-trip cases are specified;
- an architectural lint/test is specified to reject default `dao.jing/content-hash` or `dao.jing/segment-key` in equality-based validation positions outside `dao.jing`, subject only to narrow reviewed mint assertions; and
- `docs/design/dao.jing.cbor.md` is updated in H0 to replace its SHA-only address examples and pipeline diagram text with multihash addresses.

### H1: registry and whole-system cutover

    Dependencies: the three pinned BLAKE3 providers
    Existing edits: dao.jing, dependency manifests, backends, DaoSpace,
                    yin.vm consumers, fixtures, documentation and tests
    Minting default: BLAKE3
    Explicit peer: SHA-256
    Must not change: VM H/R, de Bruijn format hashes, DHT node IDs

Complete when:

- `digest-bytes` and `digest-string` support both algorithms on all three hosts;
- one-argument `content-hash`, `segment-key`, and `materialize!` use BLAKE3;
- explicit `{:algorithm :sha256}` minting works across public minting APIs (with `request-materialize` and `materialize-async-fn` documented as default-only remote helpers);
- parser and accessor tests pass for both canonical forms;
- all validation sites in the complete audit use `segment-matches?`;
- all four address-preserving copy sites derive their minting algorithm from the source address (including `store-tree-async` flushes supplying the source address to the remote store);
- file codec round-trip validation uses the selected algorithm on both sides;
- `dao.space.index` emits only `:schema-address` and restore accepts only that shape;
- `yin.vm/primitive-profile` computes its digest explicitly with SHA-256, keeping its label truthful;
- every committed development fixture containing segment addresses is regenerated;
- existing development file stores are discarded rather than upgraded;
- SHA-only storage docstrings and errors are generalized;
- the CBOR design's future addressing text alignment completed in H0 is verified active;
- the architectural lint/test preventing default-following verification is active;
- CLJ, optimized CLJS, and CLJD builds resolve and execute their providers; and
- the full repository test suite passes under the new address contract.

### H2: multi-algorithm verification and hardening

    Existing edits: integration tests, CI, provider notes and final docs
    Must not change: registry identifiers or canonical address spellings

Complete when:

- the same payload can be intentionally materialized and retrieved under both algorithms;
- each address verifies only under the algorithm it carries;
- malformed and algorithm-mismatched payloads are rejected;
- memory, file replay, remote, stepped remote, DHT, ordinary B-tree reads, synchronous hydration, asynchronous hydration, DaoSpace manifests, `yin.vm.content`, `yin.vm.macro`, `yin.vm.semantic`, `yin.vm.completion`, `yin.vm.ledger`, and semantic-bytecode row validation operate correctly with both algorithm selections;
- DHT caching, both hydration paths, and `store-tree-async` flushes preserve an explicitly SHA-256 address rather than reminting it under BLAKE3;
- a store can contain both address forms through ordinary post-registry use;
- remote and DHT peers exchange and validate both forms;
- official vectors cover empty input, non-ASCII UTF-8, 1023/1024/1025-byte boundaries, and multi-chunk input;
- randomized cross-host corpora compare complete addresses;
- optimized CLJS and release CLJD builds are exercised;
- dependency versions and licenses are recorded;
- errors name the relevant algorithm without embedding whole sensitive payloads;
- addresses minted under explicit `{:algorithm :sha256}` continue to verify correctly via `segment-matches?` without depending on or altering `default-hash-algorithm`;
- `dao.jing.md` distinguishes algorithm, digest, address, minting, verification, and copying;
- `dao.jing.md` states: "Verification must be address-directed; minting primitives are not validators";
- `dao.jing.md` states that copying addressed content preserves the address-carried algorithm;
- yin.vm documentation states that H/R remain SHA-256 because of VM contract freeze, not compatibility policy or DaoJing's default;
- the call-site classification and lint rule are documented as maintenance obligations; and
- source docstrings agree with the implemented grammar and defaults.

## Test obligations

The minimum acceptance matrix is:

- SHA-256 and BLAKE3 known-answer tests on CLJ, CLJS, and CLJD.
- Identical `digest-string` behavior for non-ASCII UTF-8.
- Identical current canonical bytes and complete addresses across hosts.
- Committed official-vector and cross-provider fixtures.
- EDN print/read round-trip for both canonical address forms.
- Strict rejection of invalid namespace, unknown algorithm, case, length, characters, and noncanonical spelling.
- Default BLAKE3 and explicit SHA-256 minting.
- The same payload stored under both algorithms as an ordinary supported operation.
- Retrieval and verification of both algorithms from one store.
- Address verification unaffected by minting defaults.
- Backend collision and `:present` paths using the address-carried algorithm.
- File codec round-trip checking under the selected algorithm.
- File replay of a store created under the registry contract containing both algorithms.
- Remote and DHT exchange of both algorithms.
- DHT caching of SHA-256 content under its SHA-256 address while BLAKE3 remains the default.
- Synchronous hydration, asynchronous hydration, and `store-tree-async` flushing preserving the fetched or flushed address's algorithm.
- DaoSpace candidates containing only `:schema-address`.
- Direct coverage or an integration path for every validation and copy site in the committed classification.
- A source-level guard against reintroducing default-following verification.
- Unchanged stack H and de Bruijn format-hash goldens.
- Unchanged R goldens when register code lands.

No test is required solely to demonstrate migration from a pre-registry store, address graph, fixture, or checkpoint shape. CBOR-address fixtures and dual-encoder dispatch tests belong to the separate CBOR clean-break epic.

## Explicit non-goals

This plan does not:

- encode a canonical-format identifier in segment addresses;
- support simultaneous canonical encoders;
- preserve segment addresses across a canonical-encoding change;
- build a migration path for pre-registry stores or addresses;
- add an old-address alias or dual checkpoint reader;
- distinguish SHA-256 content by age or provenance;
- deprecate SHA-256;
- force callers to use BLAKE3 when they explicitly choose SHA-256;
- adopt IPFS binary multihash or CID serialization;
- introduce runtime algorithm registration;
- negotiate algorithms implicitly between peers;
- implement or activate canonical CBOR addressing;
- alter the existing canonical CBOR codec, corpus, or value-domain decisions;
- change DHT node identities or Kademlia distance rules;
- change yin.vm H, R, de Bruijn format hashes, or their contract versions;
- use BLAKE3 keyed mode, derivation mode, or variable-length output;
- redesign DaoJing's synchronous handle, effect stream, retention, or garbage collection; or
- treat hashes as signatures or add authenticity or provenance semantics.

The result is a cleanly introduced, permanently multi-algorithm content-addressing system: BLAKE3 is the ordinary default, SHA-256 is an equal explicit choice, and every address carries exactly the information needed to select its hash algorithm.

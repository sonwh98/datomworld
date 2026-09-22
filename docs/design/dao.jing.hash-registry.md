# Hash-Agile Content Addressing for DaoJing and yin.vm

Status: design, reviewed; not implemented

## Decision

DaoJing will use BLAKE3-256 as its default hash algorithm while retaining SHA-256 permanently for existing content and explicitly frozen contracts.

Addresses must describe both:

1. the canonical encoding profile whose bytes were hashed; and
2. the hash algorithm applied to those bytes.

The new canonical form is:

```text
:segment/<encoding-id>+<algorithm-id>-<lowercase-hex-digest>
```

The first new default address is therefore:

```text
:segment/print-v1+blake3-<64 lowercase hex>
```

The existing form remains valid permanently:

```text
:segment/sha256-<64 lowercase hex>
```

It is parsed as the legacy canonical spelling of:

```clojure
{:encoding :jing.print/v1
 :algorithm :sha256
 :digest    "..."}
```

For that exact profile/algorithm pair, `segment-key` continues to emit the legacy spelling rather than introducing a second canonical address for the same identity.

BLAKE3 is pinned to its standard unkeyed 256-bit output. Variable-length BLAKE3 output, keyed hashing, and derivation-key modes are different algorithms for registry purposes and are not admitted under the `blake3` identifier.

### Why the encoding identifier is necessary

The simpler `:segment/<algorithm>-<digest>` generalization is sufficient only while there is one canonical encoder. It cannot preserve old content across the documented print-to-CBOR migration: verification of an old address would otherwise re-encode its payload with the new default encoder and reject it.

The repository already promises both an encoder migration and continued validity of old content. An algorithm-only address cannot satisfy both promises.

The chosen form is therefore CID-like rather than merely multihash-like: it carries a representation profile and a hash algorithm while remaining readable EDN. It does not adopt IPFS's binary CID or multihash encodings.

Future CBOR addresses can use forms such as:

```text
:segment/cbor-v1+blake3-<digest>
:segment/cbor-v1+sha256-<digest>
```

Naming the future profile does not make CBOR the addressing default in this epic.

## Invariants

- An address is derived solely from canonical bytes, their encoding profile, and their hash algorithm.
- Address verification always uses the profile and algorithm carried by the address, never the current defaults.
- Minting defaults affect new identities only. They do not affect parsing, reading, verification, or copying of an existing address.
- Verification is address-directed; minting primitives are not validators.
- Copying content already named by an address must mint into the destination under the source address's profile and algorithm.
- Registries are closed and immutable. There is no runtime registration or hidden mutable state.
- Unknown algorithms, unknown encoding profiles, malformed lengths, uppercase hex, and non-hex digests fail closed.
- SHA-256 remains supported for as long as a SHA-256 address or frozen SHA-based VM contract can exist.
- Both initial algorithms produce 32-byte digests. Digest length nevertheless belongs to the algorithm registry rather than being hardcoded into the parser.
- A bare digest is not a durable content identifier. Persisted identities must carry their algorithm and encoding profile.
- `segment-address?` recognizes every supported address form, including legacy SHA-256 addresses.
- All new default DaoJing materialization uses BLAKE3 after the default-flip phase.
- An encoding-profile identifier denotes immutable byte semantics. A future implementation change that changes bytes requires a new profile identifier.

## DaoJing design

### Closed registries

`dao.jing` owns two small immutable registries.

The algorithm registry initially contains:

```clojure
:sha256 {:address-id "sha256"
         :digest-bytes 32
         ...}

:blake3 {:address-id "blake3"
         :digest-bytes 32
         ...}
```

The encoding registry initially contains the current transitional encoder:

```clojure
:jing.print/v1 {:address-id "print-v1"
                ...}
```

The legacy address parser maps bare `sha256-...` to `:jing.print/v1` plus `:sha256`.

Registry descriptors may refer to private host-specific digest or encoding functions, but the registries are immutable code, not runtime extension points. Adding an algorithm or encoding profile requires a reviewed source change and conformance fixtures.

Defaults are explicit immutable values:

```clojure
default-hash-algorithm
default-content-encoding
```

They begin as `:sha256` and `:jing.print/v1` during the compatibility phases. The hash default changes to `:blake3` only after all validators and copy paths have become address-directed.

### Public digest primitives

Add algorithm-neutral primitives:

```clojure
(digest-bytes algorithm bytes) ; lowercase hex
(digest-string algorithm s)    ; UTF-8, then digest-bytes
```

Unknown algorithms throw with the requested identifier.

Keep these permanently as explicit compatibility and contract functions:

```clojure
(sha256 s)
(sha256-bytes bytes)
```

Add equivalent explicit BLAKE3 functions if useful for known-answer tests:

```clojure
(blake3 s)
(blake3-bytes bytes)
```

These explicit names never follow the default.

### `canonical-bytes`

Current one-argument behavior remains:

```clojure
(canonical-bytes value)
```

Add an explicit profile form through a final options map:

```clojure
(canonical-bytes value {:encoding :jing.print/v1})
```

During the initial hash-agility phases, the default remains the existing order-normalized printer. The profile seam exists so verification does not later depend on an ambient encoding default.

A profile's encoder may refuse values outside its own domain. Refusal is part of that profile's contract and must occur before hashing.

### `content-hash`

Retain the current one-argument call:

```clojure
(content-hash value)
```

It hashes bytes from the current default encoding with the current default algorithm.

Add:

```clojure
(content-hash value {:encoding profile
                     :algorithm algorithm})
```

Implementation must be `canonical-bytes` followed by `digest-bytes`; there must be only one byte stream and one digest operation.

Because the result remains bare hex, `content-hash` is suitable for local calculations and explicitly versioned or frozen contracts, but not for newly persisted standalone identity fields. New durable identities must use `segment-key` or another self-describing envelope.

### `segment-key`

Retain:

```clojure
(segment-key value)
```

Add:

```clojure
(segment-key value {:encoding profile
                    :algorithm algorithm})
```

The one-argument form uses the defaults.

Formatting rules are:

- `:jing.print/v1` plus `:sha256` emits the existing `:segment/sha256-...` form.
- Other supported combinations emit `:segment/<encoding-id>+<algorithm-id>-<digest>`.
- The namespace is exactly `segment`.
- Profile identifiers, algorithm identifiers, and digest text are lowercase ASCII.
- Output survives `pr-str` and EDN reading unchanged.

### Address parsing

Add one authoritative parser:

```clojure
(parse-segment-address address)
;; => {:encoding :jing.print/v1
;;     :algorithm :blake3
;;     :digest "..."
;;     :legacy? false}
;; or nil
```

All address accessors delegate to it:

```clojure
(segment-address? address)
(segment-encoding address)
(segment-algorithm address)
(segment-hash address)
```

`segment-hash`, `segment-encoding`, and `segment-algorithm` throw on invalid input. `segment-address?` remains a total predicate.

Parsing matches registered address identifiers rather than accepting arbitrary strings that resemble identifiers. Encoding identifiers themselves contain `-`, including `print-v1` and future `cbor-v1`, so an implementation must not discover fields by naively splitting the name on `-`. It must match the registered encoding/algorithm prefix and then validate the remaining digest using the selected registry entry.

Unknown profiles or algorithms remain invalid even when their spelling and digest superficially match the grammar.

### Address-directed verification

Add one public verification operation:

```clojure
(segment-matches? address payload)
```

It:

1. parses the address;
2. selects the carried encoding and algorithm;
3. canonically encodes the payload under that profile;
4. hashes those bytes;
5. compares the result to the carried digest.

It returns false for malformed or unsupported addresses. Profile refusals should also produce false unless an existing caller's diagnostic contract requires preserving the exception; that behavior must be chosen once and tested consistently.

This function becomes the sole normal way outside `dao.jing` to validate an address/payload pair.

### `materialize!`

Retain:

```clojure
(materialize! handle payload)
```

Add:

```clojure
(materialize! handle payload opts)
```

The default form mints with the current defaults. The explicit form passes its encoding and algorithm to `segment-key`.

On `:present`, verify the stored value with:

```clojure
(segment-matches? address stored)
```

Do not compare a default `content-hash` with `segment-hash`.

The explicit arity supports:

- controlled publication of legacy SHA content;
- tests and repair tools; and
- copying a fetched object into another backend without changing its address.

When copying content already named by `address`, callers use:

```clojure
(materialize! destination payload
  {:encoding  (segment-encoding address)
   :algorithm (segment-algorithm address)})
```

The returned address must still equal the source address. This final equality is a consistency assertion after address-directed minting, not default-following verification.

### `get`

The signature remains:

```clojure
(get handle address not-found)
```

It accepts every address recognized by `segment-address?`, including legacy SHA-256 and new BLAKE3 forms. It still rejects arbitrary keys before consulting the backend.

`get` need not rehash every successful read; backend insertion and replay checks, plus higher-level verified readers, retain their existing responsibilities. Any consumer that already verifies fetched content must use `segment-matches?`.

### File-backend codec round-trip

`dao.jing.file/validate-codec-round-trip!` is not an address/payload validator. It compares a payload's identity with the identity of its own `pr-str`/EDN round trip to ensure that the file codec does not discard address-significant information.

It therefore does not use `segment-matches?`. Both sides must instead be hashed under the mint profile selected for the pending address:

```clojure
(content-hash payload  profile-options)
(content-hash replayed profile-options)
```

The profile options come from the address or from the explicit mint operation. The check must never compare both sides under an unrelated ambient default.

### Internal SHA implementation

The hand-written ClojureDart SHA-256 implementation remains because old addresses and frozen contracts require it. It must not be removed after BLAKE3 becomes the default.

## Call-site audit and required dispositions

Hash use outside `dao.jing` falls into four distinct classes:

1. explicit frozen hash contracts;
2. default minting of new content;
3. address-directed validation of existing content; and
4. address-directed minting while copying existing content.

The implementation audit must preserve these classifications. Treating a validator or copy path as ordinary default minting is a compatibility defect.

### Direct `jing/sha256` consumers

The current tree contains five direct consumers:

- `yin.vm.debruijn_code/descriptor-hash`
- `yin.vm.debruijn_code/image-hash`
- `yin.vm.debruijn/dimension-hash`, a `def` whose value is computed once at namespace load
- `yin.vm.debruijn/node-hash`
- `dao.jing.dht/node-id`

These are explicit non-default SHA contracts and remain SHA-256.

The task that produced this plan named `src/cljc/yin/vm/debruijn_register_code.cljc` and register contract tests, but those files do not exist in the reviewed checkout. The register format exists as design in `docs/design/yin.vm.debruijn.register.md`, where R is explicitly specified as SHA-256. This plan governs that implementation when it lands rather than treating nonexistent source as current code.

### Hardcoded SHA label outside DaoJing

`yin.vm/primitive-profile` constructs:

```clojure
:yin.k.pp/sha256-<jing/content-hash description>
```

After a default flip, that would label a BLAKE3 digest as SHA-256.

Change the hash computation to request `:jing.print/v1` and `:sha256` explicitly. Do not silently change the primitive-profile contract in this epic.

### Bare persisted schema hash

`dao.space.index/checkpoint-candidate` persists `:schema-hash` as a bare `jing/content-hash`, and `restore` recomputes it using the ambient default. This is not algorithm-agile.

New candidates carry:

```clojure
:schema-address (jing/segment-key schema)
```

`restore` verifies it with `segment-matches?`.

For an existing candidate carrying a 64-character `:schema-hash`, verification explicitly treats it as the historical print-v1/SHA-256 digest. No bulk candidate rewrite is required. New candidates do not emit the old field.

### Address-directed validation sites

The following sites validate an address already supplied by stored data, a caller, or a peer. They must use `segment-matches?` rather than `content-hash`, default `segment-key`, or manual digest comparisons:

- `dao.jing/materialize!` on `:present` read-back
- `dao.jing.mem/validate-address-payload!`
- `dao.jing.file/validate-address-payload!`
- `dao.jing.remote/validate-address-payload!`
- `dao.jing.remote.step` verification of a `:present` response
- `dao.jing.dht/validate-address-payload!`
- `dao.jing.dht` peer-fetched content verification before caching
- `dao.jing.dht.node` store-request validation
- `dao.data.btree.storage` optional fetched-blob verification in the ordinary KV storage reader
- `dao.space.index/read-manifest`
- `yin.vm.content` row fetch verification
- `yin.vm.content` vector fetch verification
- `yin.vm.macro` row-address verification
- `yin.vm.semantic` claimed-segment verification
- `yin.vm.completion` reconstructed-image address verification
- `yin.vm.ledger` row-address verification
- `yin.vm.ledger` output-address verification
- `yin.vm.ledger` record/derivation address verification
- `yin.vm` semantic-bytecode row validation

Some backend sites currently compare `segment-hash` with `content-hash`; the DaoSpace and yin.vm sites generally re-mint with one-argument `segment-key` and compare whole addresses. Both mechanisms are default-following verification defects and must converge on `segment-matches?`.

Generation-only calls to `segment-key` do not require source changes. After the default flip, they intentionally mint BLAKE3 addresses.

### Address-directed mint sites

Three copy paths receive content under an existing address and materialize that content into a local cache:

- `dao.jing.dht/make-get`
- `dao.data.btree.storage/hydrate!`, synchronous `pull!`
- `dao.data.btree.storage/hydrate-async`, asynchronous `fetched!`

These are not merely validation sites. After validating the fetched payload against the supplied address, they must materialize it using:

```clojure
(materialize! local payload
  {:encoding  (segment-encoding address)
   :algorithm (segment-algorithm address)})
```

Using one-argument `materialize!` would mint under the current default. After the BLAKE3 flip, copying legacy SHA content would then return a different address and incorrectly fail hydration or DHT caching.

The returned address is compared with the supplied address after the explicit-profile write. That assertion confirms that the cache retained the same identity.

### Documentation-only SHA assumptions

Docstrings and errors in `dao.jing`, `dao.jing.mem`, `dao.jing.file`, `dao.jing.dht`, `dao.jing.dht.kad`, `dao.space.index`, and `dao.jing.stream` that say "SHA-256 only" must be generalized.

The DHT may continue routing by the 256-bit digest alone. Both initial algorithms produce 64 hex characters, and the full segment address remains the storage key. DHT node IDs remain explicitly SHA-256; changing that keyspace is unrelated to content-address agility.

## yin.vm H and R disposition

H and R remain SHA-256 for this epic.

For the implemented stack format:

- keep `descriptor-hash` and `image-hash` on explicit SHA-256;
- keep descriptor data declaring `:hash :sha256`;
- do not repin the golden H corpus;
- add a regression proving that changing DaoJing's default does not change H.

The older `yin.vm.debruijn` dimension and node hashes likewise remain explicit SHA-256. They are format identities, not DaoJing segment addresses.

For the planned register format:

- implement `register-descriptor-hash` and `register-hash` with explicit SHA-256;
- retain the documented R formula and descriptor declaration;
- do not couple R to `default-hash-algorithm`.

H or R changes only through its own contract-version process. If another format change already requires a version bump and full golden re-pin, switching that format to BLAKE3 may be considered in that separate review. It must never happen merely because DaoJing's storage default changed.

Jing addresses for stored stack or register images use the DaoJing default independently. H/R remain indexes over executable-format bytes; a Jing segment address remains the identity of the stored Jing value. These identities must not be conflated.

## Host libraries

Use the portability spike's validated choices:

- JVM: `io.github.rctcwyvrn/blake3` 1.3
- ClojureScript/Node: `@noble/hashes` 2.4.0
- ClojureDart: `blake3_dart` 1.0.0

Pin exact versions. Do not use floating ranges for a content-identity dependency.

The portability spike's cross-provider result was reported externally but left no durable repository artifact. H0 and H1 therefore commit the official-vector fixtures and the cross-provider digest table as the artifact of record. Subsequent decisions rely on those checked-in fixtures, not on an uncaptured spike.

### Maintenance risks

The JVM dependency is pure bytecode and MIT licensed but stale, with its last Maven release dating to 2020. Acceptance requires:

- source and license review before adoption;
- official BLAKE3 known-answer tests at chunk and tree boundaries;
- a cross-host fixture corpus;
- isolation behind `digest-bytes`; and
- a documented replacement procedure demonstrating byte identity before changing providers.

The Dart dependency is pure Dart and passed official vectors, but has only one release and a short history. It receives the same fixture and replacement discipline. It is also the Dart host's first external cryptographic dependency, so dependency resolution and release-build tests are explicit gates.

`@noble/hashes` is actively maintained, but its ESM/package integration with the current Shadow-CLJS build must be exercised in test and optimized release builds.

Provider replacement does not change addresses when the replacement implements the same pinned BLAKE3-256 algorithm. A provider update that changes any conformance digest is rejected.

## Sequencing with canonical CBOR

The hash-agility work lands before, and independently of, the CBOR addressing migration.

The required order is:

1. introduce profile-aware parsing, explicit algorithms, and address-directed verification while SHA-256 remains the default;
2. prove mixed legacy SHA and new BLAKE3 operation;
3. flip the hash default to BLAKE3 under `:jing.print/v1`;
4. activate canonical CBOR later as a new encoding profile and change `default-content-encoding` in its own reviewed migration.

This deliberately permits two generations of new addresses -- print-v1/BLAKE3 and later CBOR-v1/BLAKE3 -- because both remain independently verifiable. It avoids combining a cryptographic-provider change with a canonical-byte change in one diagnosis surface.

A canonical CBOR codec already exists in `dao.jing.cbor`. Its `encode` and `decode` byte contract is frozen by `test/resources/dao/jing/cbor-v1.json`, and it is currently used for encoded comparison rather than content addressing. H2 and H4 use `dao.jing.cbor/encode` as the real second-profile dispatch proof. This is stronger than a fabricated test encoder while still leaving `cbor-v1` closed to production address minting until its separate addressing review authorizes the registry entry.

If the owner elects to coordinate the two defaults operationally to reduce duplicate materialization, phases H1 and H2 must still land first. The BLAKE3 and CBOR default flips may then share a release, but their registries, fixtures, and failure attribution remain separate.

This epic does not change the CBOR codec or decide its value domain. The future `cbor-v1` addressing profile is expected to use the existing codec and frozen corpus exactly. The print and CBOR value domains must not be assumed identical: print-v1 refuses records, while CBOR has its own explicit refusal classes such as `:non-canonical` and `:unpaired-surrogate`; consequently, activation of `cbor-v1` requires an explicit account of values accepted or refused by each profile.

## Phased rollout

### H0: contract, classification, and evidence

    New: address grammar, registry descriptors, call-site classification,
         cross-host fixtures and digest table
    Existing edits: tests and design documentation only
    Must not change: current SHA addresses, current default, VM H/R values

Complete when:

- the legacy and expanded address grammars are pinned;
- official BLAKE3-256 fixtures and representative canonical-value fixtures are recorded;
- the portability spike's cross-provider digest table is committed as repository evidence;
- every source use of the current Jing hash/address entry points and every `sha256` literal is classified as frozen contract, new-content mint, existing-address validation, or address-directed copy;
- the classification table is committed as a maintained design artifact rather than remaining a one-time audit;
- the table accurately records `yin.vm.debruijn/dimension-hash` as a `def` computed at namespace load;
- the absent register implementation is recorded as future integration rather than current code;
- malformed, unknown-profile, unknown-algorithm, wrong-length, uppercase, and EDN round-trip cases are specified;
- a regression guard is designed for H2: a clj-kondo rule or architectural source test must reject `jing/content-hash` or default `jing/segment-key` used in equality-based source validation outside `dao.jing`, subject only to narrow reviewed allowlists for genuine mint assertions.

### H1: host primitives and closed registries

    Dependencies: the three pinned BLAKE3 providers
    Existing edits: dao.jing, dependency manifests, focused primitive tests
    Default: SHA-256 plus print-v1
    Must not change: output of existing one-argument segment-key

Complete when:

- `digest-bytes` and `digest-string` support SHA-256 and BLAKE3-256 on all three hosts;
- official BLAKE3 vectors, the checked-in cross-provider table, and the cross-host corpus are byte-identical;
- explicit BLAKE3 segment keys use `print-v1+blake3`;
- existing SHA segment keys remain byte-for-byte unchanged;
- parser and accessor tests pass for both forms;
- parsing matches registry identifiers correctly even though encoding identifiers contain `-`;
- unknown registry identifiers fail closed;
- CLJ, optimized CLJS, and CLJD builds resolve and execute their providers.

### H2: mixed-address storage and address-directed consumers

    Existing edits: DaoJing backends and every validation/copy site in the
                    committed classification table
    Default: still SHA-256
    Must not change: VM H/R, DHT node IDs, primitive-profile identities

Complete when:

- every validation site in the full address-directed validation list uses `segment-matches?`;
- every address-directed mint site passes the source address's encoding and algorithm to explicit-arity `materialize!`;
- `dao.jing.file/validate-codec-round-trip!` computes both sides under the address's mint profile;
- the architectural lint or source test preventing default-following verification is active;
- one store can hold the same payload under legacy SHA and BLAKE3 addresses;
- memory, file replay, remote, stepped remote, DHT, ordinary B-tree verification, synchronous hydration, asynchronous hydration, DaoSpace manifest loading, `yin.vm.content`, `yin.vm.macro`, `yin.vm.semantic`, `yin.vm.completion`, `yin.vm.ledger`, and yin.vm semantic-bytecode row validation all accept and verify the address forms applicable to them;
- tampering is rejected under both algorithms;
- an old SHA file store reopens without rewrite;
- a fetched old SHA object verifies and is cached under the same SHA address while the test's minting default is BLAKE3;
- the checkpoint schema identity is self-describing for new candidates and explicitly SHA-verified for legacy candidates;
- a test-only `cbor-v1` registry entry backed by `dao.jing.cbor/encode` proves that parsing and verification dispatch by the address's encoding profile rather than the ambient default;
- the production registry does not yet mint `cbor-v1` addresses.

### H3: BLAKE3 default

    Existing edits: default declaration, default-address expectations and fixtures
    New default: :jing.print/v1 plus :blake3
    Must not change: explicit SHA contracts or stored legacy content

Complete when:

- one-argument `content-hash`, `segment-key`, and `materialize!` select BLAKE3;
- newly minted default segment addresses use `:segment/print-v1+blake3-...`;
- explicit print-v1/SHA minting still produces the legacy spelling;
- all three host suites agree on default addresses for the shared corpus;
- old SHA addresses remain readable, verifiable, and copyable through every backend;
- DHT caching and both B-tree hydration paths preserve old SHA addresses after the default flip;
- stack H, de Bruijn dimension and node hashes, DHT node IDs, and primitive-profile identifiers retain their pinned values;
- no register golden value changes merely because of this phase.

### H4: portability and maintenance hardening

    Existing edits: CI, dependency notes, conformance resources
    Must not change: address grammar or digest output

Complete when:

- boundary vectors cover empty input, non-ASCII UTF-8, 1023/1024/1025-byte inputs, multi-chunk inputs, and canonical payload fixtures;
- randomized cross-host corpora compare complete addresses, not merely digests;
- the existing `dao.jing.cbor/encode` and frozen `cbor-v1` corpus exercise real multi-profile dispatch in tests;
- changing ambient hash or encoding defaults cannot change verification of a previously parsed address;
- dependency versions and licenses are recorded;
- provider-replacement instructions require the complete corpus before acceptance;
- optimized CLJS and release CLJD builds are included rather than relying only on development modes;
- error data identifies algorithm and encoding profile without including whole sensitive payloads.

### H5: documentation closure

    Existing edits: dao.jing and directly affected yin.vm/DaoSpace design docs
    Must not implement: CBOR addressing activation itself

Complete when:

- `dao.jing.md` distinguishes encoding profile, hash algorithm, digest, and segment address;
- `dao.jing.md` states the architectural law: "Verification must be address-directed; minting primitives are not validators";
- `dao.jing.md` states the corresponding copy law: existing addressed content is materialized under the profile and algorithm carried by its source address;
- all SHA-only statements are corrected;
- the open CBOR item says it introduces a new encoding profile rather than silently changing the meaning of old addresses;
- the future `cbor-v1` profile is documented as expected to use the existing `dao.jing.cbor` codec and `test/resources/dao/jing/cbor-v1.json` corpus exactly;
- the print-v1 and cbor-v1 refusal/value-domain distinction is documented;
- H/R documents state that executable-format hashes are frozen independently of the DaoJing default;
- legacy-address and legacy-checkpoint policies are explicit;
- the committed call-site classification and lint rule are documented as maintenance obligations;
- source docstrings agree with the implemented grammar and defaults.

## Test obligations

The minimum acceptance matrix is:

- SHA-256 and BLAKE3 known-answer tests on CLJ, CLJS, and CLJD.
- Identical `digest-string` behavior for non-ASCII UTF-8.
- Identical canonical bytes and full addresses across hosts.
- Committed official-vector and cross-provider fixtures as the evidence of record.
- EDN print/read round-trip for legacy and expanded address forms.
- Strict rejection of invalid namespace, profile, algorithm, case, length, and characters.
- Correct parsing of hyphenated encoding identifiers through registry matching.
- The same payload under two algorithms produces two valid, independently retrievable entries.
- The same payload under two encoding profiles produces two independently verifiable identities.
- Default changes do not affect verification of existing addresses.
- Default changes do not affect cache writes or hydration of existing addresses.
- Backend collision and `:present` read-back paths use the address-carried profile.
- File codec round-trip validation uses the mint profile on both sides.
- File replay handles mixed historical and new records.
- Remote and DHT peers exchange both address forms.
- DHT fetching of legacy SHA content validates and caches it under the original SHA address after the default flip.
- Synchronous and asynchronous B-tree hydration preserve the fetched address's profile and algorithm.
- Existing hardcoded SHA fixture stores remain usable without rewriting.
- New checkpoint candidates carry `:schema-address`; legacy `:schema-hash` candidates verify explicitly as print-v1/SHA-256.
- All validation and copy sites in the committed classification table have direct regression coverage or are exercised through an integration path.
- The source-level lint/test fails when default `content-hash` or `segment-key` is reintroduced as a validator.
- VM H and de Bruijn node-hash goldens do not change.
- When register code lands, R goldens likewise remain independent of DaoJing defaults.
- A test-only `cbor-v1` profile backed by `dao.jing.cbor/encode` proves address-directed encoding dispatch without activating CBOR as the production default.
- CBOR refusal classes propagate according to the encoding-profile refusal contract rather than being misreported as hash failures.

## Explicit non-goals

This plan does not:

- rehash, rewrite, rename, or delete existing SHA-256 content;
- make every old object acquire a BLAKE3 alias;
- adopt IPFS binary multihash or CID serialization;
- introduce runtime algorithm or encoding-profile registration;
- introduce algorithm negotiation between peers;
- activate canonical CBOR for production content addressing;
- alter the existing canonical CBOR codec, corpus, or value-domain decisions;
- change DHT node identities or Kademlia distance rules;
- change yin.vm H, R, de Bruijn node hashes, or their contract versions;
- use BLAKE3 keyed mode, derivation mode, or variable-length output;
- remove SHA-256 implementations or dependencies;
- redesign DaoJing's synchronous handle, effect stream, retention, or garbage collection;
- treat hashes as security signatures or add authenticity or provenance semantics.

Old data remains under its original identity. No bulk migration is required. New defaults affect new minting only, consistent with immutable content-addressed storage and the project's preference not to build compatibility machinery that rewrites established data.

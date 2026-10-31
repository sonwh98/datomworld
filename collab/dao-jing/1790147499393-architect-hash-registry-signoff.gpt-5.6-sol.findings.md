Completed-GMT: 2026-09-23 07:12:40 GMT
Completed-Local: 2026-09-23 14:12:40 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75

## Evaluation of the reconciled design

The reconciled pure-multihash design is architecturally sound.

### Foundational invariants and clean break

The design adheres to datom.world’s foundations:

- `dao.jing` remains representation-level syntax. It selects a digest algorithm and addresses opaque bytes; it does not interpret payload meaning.
- The algorithm registry is closed immutable data, not runtime registration or hidden mutable state.
- Algorithm choice is explicit data in the address or minting options. No peer infers an algorithm from ambient defaults.
- Verification is explicit and address-directed through `segment-matches?`.
- The clean break is genuine: no old-address reader, migration alias, dual checkpoint format, or deployed-store compatibility layer is proposed.
- SHA-256 remains a normal first-class algorithm choice, not a legacy exception. BLAKE3 is simply the implicit minting default.
- The current canonical encoder remains the sole encoder. The future CBOR change is its own clean break and regenerates addresses; no encoding identifier is needed in the address.

This preserves explicit causality and keeps content identity separate from semantic interpretation.

### Address-directed operations and copy paths

The reconciled classification is correct.

The three previously identified copy paths remain address-preserving operations:

- `dao.jing.dht/make-get`
- synchronous `dao.data.btree.storage/hydrate!`
- asynchronous `dao.data.btree.storage/hydrate-async`

The added fourth path is also correctly classified:

- `dao.data.btree.storage/store-tree-async`

`store-tree-async` currently flushes cache-minted blobs through a payload-only asynchronous materialization helper. That is unsafe for multihash operation because the remote side would re-mint under its own default and the two sides would rely on an unstated shared-default assumption.

The prescribed correction is architecturally right:

- carry the source address with the blob;
- use an address-supplying `put-content` operation, or add an equivalent explicit-address asynchronous operation;
- validate the supplied address against the payload at the remote boundary;
- return the explicit inserted/present result; and
- never use payload-only `request-materialize` or `materialize-async-fn` for this copy path.

`request-materialize` and `materialize-async-fn` are correctly classified as default-only mint helpers. They remain valid for minting new content, but are not validators or address-preserving copy operations.

All four copy paths must preserve the algorithm carried by the source address. They must not silently remint under BLAKE3 merely because BLAKE3 is the default.

The validation rule is also correct: all existing-address checks converge on `segment-matches?`. A caller must not compare `segment-hash` with default `content-hash`, or re-mint with default `segment-key` and compare whole addresses.

### Total `segment-matches?`

Returning `false` when canonical encoding refuses a payload is the correct predicate contract.

`segment-matches?` is a verification predicate over arbitrary received or reconstructed data. It should answer whether the value matches the claimed address, not force every caller to catch encoder exceptions.

The distinction is appropriate:

- `segment-matches?` returns `false` for malformed addresses, unknown algorithms, digest mismatch, and specified canonical-encoder refusal.
- `segment-key` and `materialize!` throw on mint refusal because minting cannot produce a valid address for an unsupported value and must fail loudly.

The implementation should catch only the canonical encoder’s documented refusal class, not arbitrary provider or programming failures. Unexpected implementation errors must remain visible.

## Formal sign-off

**Lead System Architect Sign-Off: GRANTED**

`docs/design/dao.jing.hash-registry.md` is approved to proceed to Phase H0 implementation.

This approval is bounded by the following constraints.

## Phase H0 directives and acceptance criteria

### Contract boundaries

H0 must freeze:

- address grammar: `:segment/<algorithm-id>-<lowercase-hex-digest>`;
- initial algorithm identifiers: `blake3` and `sha256`;
- BLAKE3-256 as the default;
- SHA-256 as an explicit, permanent peer;
- lowercase `[a-z0-9]+` algorithm identifiers;
- first-hyphen parsing followed by exact registry lookup;
- digest lengths from registry metadata;
- current `canonical-bytes` behavior;
- total `segment-matches?` behavior on canonical-encoder refusal; and
- throwing behavior for `segment-key` and `materialize!` on mint refusal.

No encoding identifier, encoder registry, or encoder option may be added to this design.

### Call-site classification

The committed classification artifact must list:

- all five direct `jing/sha256` consumers;
- the primitive-profile mislabel;
- DaoSpace’s bare schema identity;
- every address-validation site;
- all four address-preserving copy paths;
- `request-materialize` and `materialize-async-fn` as default-only mint helpers; and
- `store-tree-async` as an address-preserving remote flush.

The classification must continue to distinguish minting from validation.

### Remote flush constraint

Before H0 is complete, the implementation shape for `store-tree-async` must be decided explicitly.

The permitted design is an address-carrying remote write, such as:

```clojure
(put-content address payload)
```

or an equivalent asynchronous request carrying both fields.

The remote boundary must validate the supplied address with `segment-matches?` before storing. A payload-only remote materialization request is not an acceptable implementation for this path.

### CBOR alignment

H0 must update `docs/design/dao.jing.cbor.md` so that its address examples and pipeline wording no longer claim SHA-256 is the sole DaoJing address algorithm.

The CBOR document must retain its clean-break ruling:

- canonical CBOR replaces the current encoder outright;
- addresses are regenerated;
- no old-address reader or migration alias is added.

This alignment must not introduce an encoding identifier into DaoJing addresses.

### Evidence and tests

H0 must commit:

- official BLAKE3 known-answer vectors;
- the cross-provider digest table;
- representative canonical-value fixtures for all three hosts;
- malformed-address and unknown-algorithm cases;
- encoder-refusal cases proving `segment-matches?` returns `false`;
- EDN print/read round-trip cases; and
- the architectural call-site classification.

H0 must also specify the source-level lint or architectural test targeting exactly:

- `dao.jing/content-hash`; and
- `dao.jing/segment-key`

when used in equality-based validation outside `dao.jing`. It must not misclassify `dao.jing.cbor/content-hash`, whose semantics are unrelated.

### Frozen contracts

H0 must explicitly preserve:

- de Bruijn descriptor, image, dimension, and node SHA-256 contracts;
- planned register R SHA-256 contract;
- DHT node IDs;
- their golden values, descriptor declarations, and versioning rules.

These remain frozen VM or routing contracts, not compatibility machinery and not reasons to weaken DaoJing’s BLAKE3 default.

### H0 completion gate

H0 is complete only when the grammar, registry, total-predicate behavior, call-site classifications, four-copy-path rule, CBOR-document alignment, vectors, lint target, and frozen-contract boundaries are all recorded and reviewable.

No H1 implementation work should reinterpret H0 as authorization for:

- an encoding-profile axis;
- a legacy reader;
- a payload-only addressed copy;
- ambient algorithm negotiation; or
- changes to H/R or DHT identity contracts.

Completed-GMT: 2026-09-21 17:03:50 GMT  
Completed-Local: 2026-09-22 00:03:50 +07 (Indochina Time)  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a  

A. RULING: NEW EPIC for DaoJing’s canonical encoding; accept the current limitation for the de Bruijn work, with immediate fail-closed mitigation for known lossy values.

1. The print-based encoder is explicitly transitional. `docs/design/dao.jing.cbor.md` is the plan of record for replacing it with canonical CBOR bytes, but its status is “implementation plan; not yet implemented” (`dao.jing.cbor.md:3-8`). No implementation schedule is recorded.

   Therefore:

   - No D0–D6 de Bruijn change is required.
   - The losslessness problem is a DaoJing/storage concern.
   - The intended durable fix is the CBOR migration, including retiring the print-based address derivation and migrating all address-bearing data.
   - Until that epic lands, the file backend should fail closed for known host-lossy values rather than silently accepting them.

   The smallest immediate mitigation is a CLJS `-0.0` write refusal in `dao.jing.file`, accompanied by a generic DaoJing test. The complete fix belongs in the separately planned CBOR epic and requires its own implementation and architecture review.

2. The source explicitly documents additional transitional-encoder residuals:

   - collection metadata can be address-significant;
   - scalar symbol metadata is ignored;
   - pathological symbol/text print collisions are possible;
   - ambient print variables can affect scalar bytes;
   - byte arrays are currently hashed by identity rather than content;
   - ClojureDart lists can carry host metadata and differ across hosts;
   - list handling can be refused by a backend.

   These are documented in `docs/design/dao.jing.md:411-448`.

   The current tests specifically pin `-0.0` and list behavior for the de Bruijn pipeline. They do not establish that all NaNs, arbitrary doubles, sorted collections with metadata, characters, ratios, byte arrays, or host-specific printed values are lossless. Those cases require probes in the DaoJing epic.

3. Ruling and ownership:

   - **De Bruijn:** accept the limitation; no projection change.
   - **DaoJing:** new canonical-encoding epic.
   - **Immediate mitigation:** reject CLJS `-0.0` at the file boundary, if the owner wants fail-closed behavior before CBOR.
   - **Complete fix:** implement `docs/design/dao.jing.cbor.md`, migrate addresses and backends, and rerun cross-host fixtures.

   The DaoJing/storage owner owns the implementation. Whether to land the small mitigation immediately or wait for the CBOR epic is the owner’s decision.

4. The integrity-law test is the right contract to retain:

   > A durable round trip is exact, or it is refused loudly; it must never silently change content.

   `test/yin/vm/pipeline_test.cljc` correctly covers exact round trip, write refusal, and reader-side `:hash-mismatch`. However, the generic law should also live in DaoJing tests, not only in the de Bruijn pipeline tests.

   Status/blog wording should say:

   > “The de Bruijn pipeline pins fail-safe detection of the current DaoJing file-codec limits; DaoJing’s cross-host losslessness remains open pending its canonical CBOR migration.”

B. RULING: PARTIALLY SATISFIED at the protocol level; no registry is required, but persistent descriptor publication is not implemented.

1. `docs/design/datom.md:55-63` requires a dimension descriptor bundle containing:

   - arity;
   - ordered slots;
   - canonical encoding;
   - projection and lift morphisms;
   - a content hash serving as the dimension identity.

   It does not require a global registry or a well-known registration function. It explicitly says no external schema authority is needed.

   The implementation satisfies the descriptor-shape and identity requirements:

   - `descriptor` contains the arity, slots, encoding, projection, and lift declarations.
   - `dimension-hash` hashes that descriptor.
   - Node hashes use the dimension hash as their domain separator.

2. Nothing in `src` persists or discovers the descriptor bundle independently. The descriptor is a namespace value, not a materialized descriptor datom or DaoJing object. Since `datom.md:123` says hashing is triggered by persistence, not mere existence, strict “published” language exceeds what is currently implemented.

3. Smallest documentation correction:

```text
The projection defines and exports a `:yin.debruijn/*` dimension descriptor,
whose content hash is the node-hash domain separator. Persistent publication or
discovery of that descriptor bundle is outside D0–D6; no external registry is
required by the dimension protocol.
```

If the owner requires descriptors to be discoverable from a store, the smallest implementation addition is a separate descriptor-materialization/publication adapter. That is a storage/integration task, not a de Bruijn algorithm change.

This does not invalidate “implemented through D6” when that status means the projection and its tested pipeline. It does mean the status should not imply that a globally discoverable dimension registry entry exists.

Owner decisions:

- Whether to open and schedule the DaoJing CBOR epic now.
- Whether to land the interim CLJS `-0.0` fail-closed mitigation.
- Whether dimension publication means source-level descriptor definition or persistent store publication.
- Whether to add a generic DaoJing descriptor-publication adapter.

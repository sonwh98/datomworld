Completed-GMT: 2026-09-23 05:54:03 GMT
Completed-Local: 2026-09-23 12:54:03 Indochina Time (+07)
Coding-Agent: claude
Session-ID: 4de80508-4e9d-41e6-a131-409d06ee6520

**Verdict: READY to proceed to H0**, with one P2 that must be folded into the call-site classification before H0 is declared complete. The pure-multihash simplification is sound. Dropping the encoding-profile axis introduces no parsing ambiguity, the `canonical-bytes` precedes algorithm ordering is stated clearly, and the clean-break boundary with the CBOR design holds. H and R stay pinned to `jing/sha256` at all four de Bruijn sites and are not coupled to the new default. The five direct `jing/sha256` consumers and the `dimension-hash` def are represented accurately.

## Findings

**P2 | docs/design/dao.jing.hash-registry.md:417 | a fourth address-preserving copy path is missing.** The document lists three copy paths. `dao.data.btree.storage/store-tree-async` at `src/cljc/dao/data/btree/storage.cljc:340` is a fourth. It flushes cache-minted blobs to the async source by calling the source's `materialize-async-fn`, which routes to `dao.jing.remote.step/request-materialize` at `src/cljc/dao/jing/remote/step.cljc:244`. That call sends only the payload, so the remote re-mints under its own default, and the flush then asserts the remote's address equals the cache's. This is two independent mints coupled by an unstated shared-default assumption, which contradicts the "no implicit algorithm negotiation between peers" non-goal. Fix: classify this site as class 4 and have the flush send the cache-minted address with the blob through the address-supplying `put-content` op rather than `request-materialize`. Add it to the H1 criterion "all address-preserving copy sites derive their algorithm from the source address" and to the H2 matrix.

**P3 | hash-registry.md:96 and :213 | registered-prefix matching is more machinery than needed.** Because the digest is hex and the separator is `-`, the parser can split the name on the first `-` and do an exact registry lookup. The "identifiers must be unambiguous under prefix matching, rejected at development time" rule then reduces to a stated identifier charset. Fix: specify identifiers as `[a-z0-9]+`, parse by split-then-lookup, and delete the ambiguity rule.

**P3 | hash-registry.md:240 | encoder-refusal behavior of `segment-matches?` is left open.** H0's "malformed cases are specified" criterion cannot be boolean-checked while this is undecided. A stored payload cannot refuse since it was minted, so the refusal can only come from a peer-supplied or reconstructed payload. Fix: decide now that `segment-matches?` returns false on encoder refusal, and that `materialize!` keeps throwing on mint refusal. Records are the only current refusal class, per `src/cljc/dao/jing.cljc:114`.

**P3 | hash-registry.md:543 and :561 | two mint entry points are unclassified and "works everywhere" overclaims.** `request-materialize` and the async `materialize-async-fn` mint with one-argument `segment-key` and offer no algorithm option. Fix: either add an `{:algorithm}` option to both, or scope the H1 criterion to `content-hash`, `segment-key`, and `materialize!` and list the two remote entry points as default-only mint sites.

**P3 | hash-registry.md:596 | the "test seam for changing the default" contradicts the immutable-registry invariant at line 53.** Fix: drop the seam and prove the property by minting under explicit `{:algorithm :sha256}` and verifying that the address still verifies while the default is BLAKE3.

**P3 | hash-registry.md:512 and docs/design/dao.jing.cbor.md:486 | the CBOR design still says "SHA-256 address" and pins `:segment/sha256-`.** The pipeline diagram at line 43 of the CBOR document also says "SHA-256 address". The hash-registry document defers reconciliation to H1. Fix: make the CBOR document edit an H0 item, since H0 is the contract phase and the two documents currently disagree on the address grammar.

**P3 | src/cljc/dao/jing/cbor.cljc:890 | `dao.jing.cbor/content-hash` is a host `hash` over a content key, not a digest.** The proposed lint against default `content-hash` in validation positions must match by namespace or it will misfire here. Fix: name the lint's target as `dao.jing/content-hash` and `dao.jing/segment-key` explicitly.

## Objectives checked without findings

- **Grammar and accessors:** lowercase hex, length from registry, fail-closed on unknown algorithm, and canonical-spelling reconstruction are all specified. `segment-address?` stays total, `segment-hash` throws, and the digest-only `content-target` for DHT routing stays width-safe because both registry entries are 256 bits, which the document notes at line 438.
- **Validation sites:** every `segment-hash` versus `content-hash` comparison and every one-argument `segment-key` re-mint I found in source is on the document's list. This includes the DHT node store-request check, the stepped-remote verify read, `kv-storage` verify, `read-manifest`, and the yin.vm content, macro, semantic, completion, and ledger sites.
- **Fixtures:** `test/dao/data/psset_fixtures.cljc` holds 430 literal SHA-256 addresses and is the only committed source file with literal addresses. It falls under the H1 regeneration criterion. H goldens in `test/yin/vm/debruijn_code_test.cljc` use `jing/sha256` directly and are unaffected.
- **Host libraries:** the pinned versions could not be verified offline. The document's gates for the stale JVM library and the single-release Dart package are adequate given `digest-bytes` isolation and the required known-answer vectors.

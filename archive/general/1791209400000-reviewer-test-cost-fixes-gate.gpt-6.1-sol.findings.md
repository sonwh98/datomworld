Completed-GMT: 2026-10-05 22:19:10 GMT
Completed-Local: 2026-10-06 05:19:10 Asia/Ho_Chi_Minh

APPROVE-WITH-NITS

1. **Low — `test/dao/jing_test.cljc:101`: incomplete explicit boundary coverage.** Existing vectors cover empty input, 56 bytes, 65 bytes, Unicode, and million-`a`. They do not explicitly pin 55/63/64-byte inputs through both string and host-byte APIs. **Fix:** add fixed expected digests for 55/56/63/64/65 bytes and check `sha256-bytes` using each host’s byte container. This is coverage hardening; I found no implementation defect.

2. **Low — `test/yin/vm/debruijn_register_compile_test.cljc:932`: cross-body edge coverage could be stronger.** The four-body fixture correctly pins branch merging, nonzero body starts, cross-body fallthrough, and successor equal to image length. It does not explicitly exercise a backward cross-body jump or negative target. **Fix:** add those cases, expecting an empty contribution for an in-image foreign-body target and an exception for a negative target.

Rulings:

- **Dart SHA-256: safe.** Public names and lowercase hexadecimal output remain unchanged. UTF-8 encoding remains explicit; `sha256-bytes` accepts the documented Dart `Uint8List`, which satisfies crypto’s `List<int>` input. Local crypto 3.0.7 source exports `sha256`, handles input through its chunked implementation, and formats lowercase hex. Both pubspec and lockfile declare 3.0.7 as a direct dependency. The new import is confined to `:cljd`; JVM/Node implementations are unchanged. The recorded Dart compilation and focused tests support the import form. The digest reader conditionals retain their pre-existing ordering rather than placing `:cljd` first; the compiler’s Dart emission reader selects `#{:cljd}`, so this does not introduce a runtime selection regression.

- **Known answers and other hashes: safe.** I independently checked the new Unicode and million-`a` expected digests with Python hashlib; both match. No references to the deleted SHA helpers remain in source/tests. BLAKE3 code and registry dispatch are unchanged.

- **Cross-host pins: adequate.** `recorded-sha256-is-the-digest-of-the-bytes` in `test/dao/jing/cbor_fixtures_test.cljc:117` would fail if Dart’s byte digest changed. CBOR conformance also compares digests against recorded resources. The hash-registry SHA acceptance test generates its own address, so that particular test alone does **not** establish cross-host SHA identity.

- **Body liveness: equivalent.** In the old algorithm, only PCs in `[start,end]` were updated; every other in-image slot remained empty. The new algorithm maps those same updated slots to `pc-start`, returns empty for foreign in-image PCs, and throws for out-of-image successors. Both endpoints are correct. Initialization, traversal, transfer functions, convergence checks, and returned boundary maps remain equivalent. The internal vector lengths are not exposed to consumers.

- **Hex encoding: equivalent for reachable inputs.** Width-2 callers supply masked bytes or small tag indices; width-8 callers supply integer UTF-8 lengths. Byte extraction yields exactly the old function’s trailing hexadecimal digits, including truncation above 2³². JS’s 32-bit coercion is preserved. Negative payload integers are masked before reaching this helper; BigInt and noninteger payloads do not reach it directly. The unchanged duplicate in `debruijn.cljc` introduces no current output divergence.

- **Test reuse: safe.** Both `adapt` functions are exactly resolve followed by their respective lowering functions. The resolver returns immutable values. Each runner still creates a fresh VM/store. Only `derived-ast` is memoized; `envelope` and `derive*` remain fresh, and `insertion-is-deterministic-test` still performs two independent derivations. No similar cached-self comparison remains.

- **Scope and evidence:** three product files, four Python test files plus `test_utils`, and two regression-test files changed. No engine or ucf files changed; no Python assertion/testing form was removed; no added line exceeds 80 columns; no `zz_` profiler remains under `test/`. Recorded prelude register-image, stack-image, and live-set hashes agree before/after on JVM and Dart.

Read-only review completed without edits or suite execution. Performance figures and host test results are recorded engineer evidence, not independently rerun measurements.

Completed-GMT: 2026-09-22 00:12:44 GMT
Completed-Local: 2026-09-22 07:12:44 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: not visible to me (the prompt says "pending, caller-generated" and no id was given to this session)

## Files
- Created `test/dao/jing/cbor_conformance_test.cljc` (939 lines, pure ASCII, 13 deftests). It has no `.cljd` sibling because a Dart-only branch was not needed.
- Edited `test/dao/jing/cbor_fixtures.cljc`: added one test-only, read-only function, `read-path`. Nothing else in that file changed.
- I did not touch production code, the corpus, README, errata, generator, `cbor_fixtures_test.cljc`, or any deps/bb/pubspec file. Nothing is staged, committed, merged or pushed.

## Manifest and digest design
- **Manifest per host.** Each host runs the shared resource through a per-id manifest of `{:kind :hex :sha :sig :walk-errors}`, plus a skip map of id to reason (`:not-buildable` or `:merged`).
- **Canonical cases.** Encode the input. Hex and SHA-256 must equal the resource, and decoding the frozen hex then re-encoding must return the same bytes. The signature is `ok|rt=1|<diag>`, where the diag is a compact CBOR diagnostic rendering produced by the independent walker over the produced bytes.
- **Refusal cases.** The signature is `refuse|<observed class>`, checked against the frozen class. Decode-refusal cases also check the SHA-256 of the offered bytes.
- **Digest.** SHA-256 over the id-sorted lines `id \t hex \t sha \t sig`.
  - Canonical digest: the 208 canonical cases run on all three hosts (209 minus `coll/map-both-slash-keywords`).
  - Refusal digest: the 154 refusal cases run on all three hosts. I added this second digest beyond what the task asked.
  - Each host asserts its digest equals the value derived from the resource (walker over the frozen hex) and equals a pinned literal.

## The five points
1. **Manifest and skips.**
   - **Per-id assertions.** The manifest test asserts every case a host runs against the resource. Observed skips must equal the documented skips exactly.
   - **Errata read from the file.** The errata E1 and E6 tables are parsed from `cbor-v1.errata.md` itself, not copied. They are asserted equal to a pinned N/A table and to the per-host runner's `skip-by-id` and `collapse-not-applicable`.
   - **Coverage check.** A static test asserts no case is skipped on all three hosts, apart from the finding below.
2. **Independent walker.** It reads hex only and never calls the Jing decoder or encoder. It checks:
   - definite lengths and shortest heads;
   - no native floats, no tag 39, no tags outside 2/3/4/27/30/258;
   - tag 27 frames are exactly `[name-string payload]` with one of the five names, with per-name payload shapes (list, keyword/symbol, float64 including canonical NaN, with-meta including stripped reader positions);
   - bignum, decimal and ratio shapes, including ratio reduction;
   - strictly ascending unsigned-byte order of every map's keys and every tag 258 set's elements, done by comparing hex text.
   - It runs on every produced canonical byte string and on all 209 frozen canonical hexes. A self-test proves it flags 25 bad inputs and accepts 5 good ones. It also independently flags every frozen decode-refusal case in 9 classes (native-float, identifier-tag-39, unknown-frame-name, unknown-tag, trailing-data, unsupported-simple, non-canonical, duplicate-key, duplicate-element).
3. **Injectivity and equivalence.** Over the produced manifest bytes:
   - same bytes if and only if same equivalence group;
   - equivalence groups produce one hex;
   - distinct groups differ pairwise in both hex and SHA-256;
   - one SHA-256 per hex.
4. **Immutability.**
   - The resource's SHA-256, computed from its ASCII text, is pinned: `c8f5ef38124110bca48d0c7e5596eef00028ecc66c7d032e4e12bb1477f97351`. It runs on all three hosts. Neither the README nor the JSON header records a file digest, so I pinned this one myself from the file frozen at `f5f71e95`.
   - JVM-only static tests scan `test/`, `src/dev` and `bb.edn` and fail if any file that names the corpus or its loader also contains a file-write, delete, rename or copy marker. Another JVM-only test asserts the generator opens no file for writing.
5. **Wiring.** No wiring edits were needed. The new namespace is auto-discovered by cognitect test-runner, shadow `:node-test` and the ClojureDart `test` lane. I saw "Testing dao.jing.cbor-conformance-test" (JVM, Node) and a run of `cbor-conformance-test_test.dart` (Dart).

## Digests printed by each host
| Host | Canonical (n=208) | Refusal (n=154) |
|---|---|---|
| jvm | `6d1c64719d96c11f79d575bd3122ad1bba1ba9efcc56dce63b195423575cf2cb` | `1b929147af85f703c700a1f0d9cfbe74c55e5e28302a862086516fe749ec1b82` |
| node | same | same |
| dart | same | same |

## Skipped ids per host
- **jvm (5):** `coll/map-integer-width-duplicate`, `coll/set-decimal-scale-collapse`, `coll/set-signed-zero-collapse`, `coll/set-vector-list-collapse`, `num/js-unsafe-integer`
- **node (6):** `coll/map-both-slash-keywords`, `coll/map-nan-payload-duplicate`, `coll/set-nan-payload-duplicate`, `coll/set-vector-list-collapse`, `host/character`, `host/nested-in-vector`
- **dart (5):** `coll/set-signed-zero-collapse`, `coll/set-vector-list-collapse`, `host/character`, `host/nested-in-vector`, `num/js-unsafe-integer`

## Runs, with observed counts
- **JVM:** `clojure -M:test -n dao.jing.cbor-conformance-test -n dao.jing.cbor-test -n dao.jing.cbor-fixtures-test` gave 45 tests, 3259 assertions, 0 failures, 0 errors. The conformance namespace alone is 13 tests, 66 assertions.
- **Node:** `bb test:cljs` gave 1618 tests, 42347 assertions, 0 failures, 0 errors.
- **Dart:** `cljd-fast.sh` gave `+1580: All tests passed!`. The final full `cljd.sh` gave exit 0, `+1580: All tests passed!`.
- **Static:** `lint.sh fix` then `lint.sh` reported errors: 0, warnings: 0 on all six files, and `git diff --check` was clean.

## Findings
- **`coll/set-vector-list-collapse` runs on no host.** The task expects every one of the 372 cases to run on at least one host. This one does not: errata E1 lists it N/A (merged) for JVM and Node, and E6 lists it N/A for Dart, so the tables as written leave it unrun everywhere. The gate pins this as the single documented exception and asserts its decode-side twin `mal/collapse-vector-list-set` (`equality-collapse`) exists. That is the only coverage of the class, and only on the decode side. Every other case runs on at least one host. Whether that exception is acceptable needs an architect ruling.
- **E6 is still pending ratification.** The Dart column of the gate is checked against it as written.
- **Encode-refusal skips.** Every skipped encode-refusal case is either not buildable on that host or a host constructor merge. The one canonical skip is Node's `coll/map-both-slash-keywords`.

## Deviations
- The task suggested a possible `.cljd` sibling; I created none.
- I did not compare digests against an independent tool such as `openssl`, because it needs approval here. The pinned resource digest was taken from the JVM test run.
- No frozen expectation looked wrong beyond the single coverage finding above.

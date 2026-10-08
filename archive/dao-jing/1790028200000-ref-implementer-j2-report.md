Completed-GMT: 2026-09-21 23:15:42 GMT
Completed-Local: 2026-09-22 06:15:42 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8

J2 is done: Dart runs the whole corpus through the shared codec, and every lane is green (JVM, Node, the full CLJD lane, and lint). I did not hit the `cbor` 6.5.1 stop condition. One gap needs an orchestrator action: the ratified errata has no Dart column for its N/A table. Nothing is staged or committed.

## Files, and why shared rather than a separate Dart codec
- **Changed `src/cljc/dao/jing/cbor.cljc`.** The `:cljd nil` gate is gone. The structural reader, the profile checks, the depth and exponent limits, the carriers and the portable operations are now shared by all three hosts. Every host reader conditional now names `:cljd` first.
  - I chose one shared file because the reader and profile are almost entirely host-neutral; only the byte-level interop differs by host. A Dart copy would be a second implementation of the acceptance rules, free to drift from the other two, which is exactly the risk J3 exists to catch.
  - The encoder now builds scalar and container nodes through adapter functions (`null`, `bool`, `text`, `byte-string`, `integer`, `array-node`, `map-node`).
- **Changed `src/cljc/dao/jing/cbor/boring.cljc`.** It gained those adapter functions, and on JVM and Node they return exactly the values Boring received before. JVM and Node bytes are unchanged, confirmed by the corpus runs below.
- **New `src/cljd/dao/jing/cbor/cljd.cljd`.** The Dart byte writer over `cbor` 6.5.1, with the same function vocabulary as the Boring adapter.
- **Changed `test/dao/jing/cbor_fixtures.cljc`.** The input builders now have Dart branches. A new `fresh-list` helper clears ClojureDart's constructor metadata on lists, which the plan makes the producer's job.
- **Changed `test/dao/jing/cbor_test.cljc`.** The same corpus runner now executes on Dart. The Boring-options test stays JVM and Node only. The encode-refusal runner now collects unexpected host merges and asserts once at the end, because on Dart a failing `is` throws and would hide later cases.

The frozen corpus, README, generator, errata and `cbor_fixtures_test.cljc` are untouched.

## How each criterion is met
- **Writer.** `cbor` 6.5.1 writes every byte. I probed it before building: shortest heads including the 8-byte cases (2^63, 2^64-1, -2^64), minimal bignums, tags over arrays and byte strings, and map entries in insertion order.
  - The package does not sort map keys, so the adapter sorts them by their canonical bytes.
  - The package silently writes `?` for a lone surrogate. The codec rejects surrogates before any string reaches it.
- **Reader.** Jing's own structural reader validates the bytes before anything is materialized; the package's decoder is not used. Every accepted value is re-encoded and must match the input bytes exactly.
- **Metadata** is written as an explicit outer `clojure/with-meta` frame, around lists and symbols too.
- **Limits.** The decimal exponent window is [-2147483647, 2147483648]. `max-depth` is 128 (`:malformed-cbor` on decode, `:unsupported-value` on encode). Both are pinned by the shared tests, which pass on Dart.
- **Numbers on Dart.**
  - Integers are 64-bit `int` or `BigInt`.
  - Floats are native doubles, which the Dart VM keeps a distinct type from `int`, so an integral float is never narrowed.
  - Signed zero, infinities and the one canonical NaN pass the corpus cases.
  - Native CBOR floats are refused.
  - Decimals and ratios use the `Decimal` and `Rational` carriers.
  - Lossy input such as `(ratio 1.5 2)` is refused.
- **Portable operations.** Equality, hash and ordering, exactness, operand symmetry, zero sign, NaN and infinities pass on Dart. The same test cases pass on all three hosts; no hash values are compared across hosts.
- **Two Dart differences, handled in the shared code:**
  - Dart's `BigInt.compareTo` can return any negative or positive number (I saw -29). The comparison now normalizes to -1, 0 or 1.
  - Dart's UTF-8 decoder drops a leading BOM. The reader now puts it back, including a run of several.

## Corpus cases run on Dart
| Kind | Run | Skipped |
|---|---|---|
| canonical (209) | 209 | 0 |
| encode-refusal (31) | 26 | 5 |
| decode-refusal (132) | 132 | 0 |

The five skipped encode-refusal cases, all by id:
- **`num/js-unsafe-integer`:** the README says it applies to Node only.
- **`host/character` and `host/nested-in-vector`:** Dart has no character type.
- **`coll/set-signed-zero-collapse` and `coll/set-vector-list-collapse`:** Dart's set constructor merged the members, so the refusal cannot be observed on Dart.

The other seven collapse and duplicate cases (integer-width, both NaN cases, int/float, float/decimal, int/ratio, decimal scale) keep both members on Dart and are refused with the frozen class.

**Decision needed:** the ratified errata's E1 table has only JVM and Node columns, and the errata is outside my file box. Proposed Dart column: signed-zero and vector-list are N/A (merged); everything else is live and refused. Until someone adds it, those two Dart skips cite E1 without a Dart row.

## Host-collapse and Dart equality
On Dart, `(keyword nil "a/b")` and `(keyword "a" "b")` are not equal, and neither are the symbol pair or the crossed-namespace pair, so maps and sets keep both entries. So on Dart:
- `host-collapse` is never raised.
- `coll/map-both-slash-keywords` runs and passes.
- The slash-crossed symbol set decodes (the test expects success on Dart, as on the JVM).

Other Dart behaviors observed:
- `(= 1 1.0)` is true, but a set still holds both values.
- `0.0` and `-0.0` merge in a set.
- Two NaNs do not merge.
- Keywords cannot carry metadata (`with-meta` throws).

## Other deviations
- The adapter function `array` is now `array-node` in both adapters, because `array` shadowed `cljs.core/array` (a kondo warning).
- The `.cljd` adapter has two `clj-kondo/ignore` annotations on Dart interop (`dart/is?`, `Uint8List.fromList`), since kondo lints that file as plain Clojure.
- I fixed a latent JVM bug: an 8-byte tag number used to raise a non-refusal cast error. It is now reported as `:unknown-tag` with the real tag number.
- I probed Dart behavior with a temporary test namespace, since the package source is outside the allowed directories. The probe and its stale generated test file under `test/cljd-out` are deleted. Temporary helper scripts under `target/` are deleted too.

## What I ran
| Command | Result |
|---|---|
| `clojure -M:test -n dao.jing.cbor-fixtures-test -n dao.jing.cbor-test` | 32 tests, 4447 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 1607 tests, 43538 assertions, 0 failures, 0 errors, 0 compile warnings |
| `cljd-fast.sh` (while iterating) | the last one before the final edits ended "Some tests failed" with 1567 passed and 1 failed (the vector-list merge, then fixed) |
| `cljd.sh` (full lane, final) | exit 0, "01:55 +1568: All tests passed!" |
| `lint.sh` (final check) | kondo 0 errors, 0 warnings; cljstyle clean (after `lint.sh fix` reformatted `cbor.cljc` and `cbor_test.cljc`) |
| `python3 test/resources/dao/jing/cbor-v1.generate.py --check` | matches byte for byte |

A grep for non-ASCII characters in the five changed or new files found none.

## What I did not run
- The Java 17 full `clojure -M:test` suite, which the orchestrator runs.
- The per-case Dart counts above come from the corpus sizes and the printed skips; the Dart test output counts deftests, not individual corpus cases.

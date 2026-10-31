[claude-code:unrecognized_model] {"model":"deepseek-v4-pro","query_source":"sdk"}
We're changing auto mode to no longer charge for classifier requests in Claude Code. However, this session isn't eligible because your requests go through api.deepseek.com, which isn't compatible with this update. Nothing breaks: auto mode keeps working, and its classifier requests are billed as before. To fix it and access the new version of auto mode, ask your gateway to implement: https://code.claude.com/docs/en/auto-mode-classifier-billing
[claude-code:unrecognized_model] {"model":"deepseek-flash","query_source":"auto_mode"}
I've completed a thorough read-only review of the J2 changes against the `cbor` 6.5.1 package source (in the pub cache), the shared codec, the frozen corpus/errata/README, and the contract doc. Here is the review.

---

Completed-GMT: 2026-09-21 23:35:23 GMT
Completed-Local: 2026-09-22 06:35:23 +07 (Indochina Time)
Coding-Agent: deepseek
Session-ID: pending

## Verdict: READY WITH CHANGES

The Dart route is correct and thorough. I verified the `cbor` 6.5.1 encoder behavior against its actual source in `/Users/sto/.pub-cache/hosted/pub.dev/cbor-6.5.1/` and traced every wire type, the 8-byte/bignum boundaries, map-key ordering, tag order, and the two claimed Dart traps. The one outstanding item is a documentation gap in the frozen errata (already surfaced by the implementer), which needs architect action, not a code edit.

---

### Findings

**P2 — The ratified errata E1 table has no Dart column.**
`test/resources/dao/jing/cbor-v1.errata.md:26-45` — E1's corrected N/A table lists only JVM and Node columns and closes with "Dart is not yet observed (J2)" (line 45). The J2 skip reasons (`test/dao/jing/cbor_test.cljc:53-57`) now cite E1 for two Dart cases (`coll/set-signed-zero-collapse`, `coll/set-vector-list-collapse`) that E1 does not document for Dart. The sign-off finding #4 declared E1 authoritative and E1 itself anticipated a J2 Dart column, so this is a real gap blocking clean sign-off.
- Fix: add a Dart column to the E1 table (architect/orchestrator action, since the errata is frozen and outside the implementer's file box). The implementer's proposed column is **correct** and I verified it against the code: `signed-zero` and `vector-list` are N/A (merged), the other seven are "live, refused" (consistent with `collapse-not-applicable` `:dart` = `#{"coll/set-signed-zero-collapse" "coll/set-vector-list-collapse"}` at `cbor_test.cljc:57`). 7 = 9 − 2, matching the report's "other seven".

### What I checked and found clean

**Writer correctness (cljd.cljd).** Read `int.dart`, `string.dart`, `bytes.dart`, `map.dart`, `list.dart`, `sink.dart`, `arg.dart`, `constants.dart`:
- **Shortest-form heads incl. 8-byte and bignum tags.** `CborSmallInt` (Dart `int`) writes shortest form via `Arg.int`; `CborInt(BigInt)` routes bitLength≤53→`CborSmallInt`, 54–64→`_LargeInt` (major 0/1, `Arg.bigInt`, always 8 bytes — correct since every such value is ≥2³²), >64→`CborBigInt` (never reached: the codec emits tags 2/3 itself). The corpus pins all boundaries: `num/int/9223372036854775808` (2⁶³), `num/int/18446744073709551615` (2⁶⁴−1), `num/int/-18446744073709551616` (−2⁶⁴), `num/int/-9223372036854775809` (−2⁶³−1), plus 2⁶⁴/−2⁶⁴−1 bignums. The `int-wire` boundary `[-2⁶⁴, 2⁶⁴)` at `cbor.cljc:873-880` is correct (I traced the `bcmp` edges).
- **Tag encoding.** Tags 2/3/4/27/30/258 written via `sink.addTags` before the value; tag 258 correctly takes the 2-byte head (`0xd9 0102`), tag 30 → `0xd8 1e`. Order is tag-then-payload. ✓
- **Map keys.** `map-node` sorts by the lowercase fixed-width key-hex string, which equals the contract's "lexicographic by canonical bytes, proper prefix first" (`dao.jing.cbor.md:156-158`); Boring `:canonical` gives the identical order on JVM/Node. `Map.fromIterables` preserves insertion order (LinkedHashMap), so entries are emitted in sorted order. ✓
- **No silent key-drop.** `Map.fromIterables` dedupes by Dart `==`/`hashCode`, but the Jing `dup-class` check already refuses any two keys with identical canonical bytes before `map-node`, and no two distinct-byte CborValues are `==` (int/string/bytes equality is value+tags-based; list/map keys differ in structure/tags). So a silent encode-side key collapse is impossible. ✓
- **Definite lengths only** — every `array-node`/`map-node`/`frame`/`tagged` passes `.type definite`, bypassing the `auto` 256-threshold. ✓
- **Surrogate rejection precedes the writer.** `check-text` (`cbor.cljc:832-847`) runs on every string/identifier/metadata path before `CborString`, so the package's lone-surrogate substitution is never reached; the surrogate check is UTF-16-code-unit-based via `codeUnitAt`, which is correct on Dart. ✓

**JVM/Node non-regression.** The `boring.cljc` adapters (`null`, `bool`, `text`, `byte-string`, `integer`, `array-node`, `map-node`) return exactly what J1 passed to Boring before (`boring.cljc:58-107`): `byte-string` still wraps a fresh `copy-range`, `map-node` still `into {}` over the triples, `integer`/`array-node` are identity. No byte change on JVM/Node. The one behavioral change — the 8-byte-tag fix in `item->value` (`cbor.cljc:1334-1368`) reporting `:unknown-tag` instead of a cast error — is a refusal-correctness fix, not a byte regression.

**Dart host traps in shared code.**
- `BigInt.compareTo` sign-magnitude normalized in `bcmp` (`cbor.cljc:172-179`). ✓
- Leading-BOM restoration in `utf8` (`cbor.cljc:1096-1107`) is correct *regardless* of whether Dart's decoder drops a BOM: the loop strips leading BOM bytes itself before decoding and re-prepends them, so it cannot double or drop. Pinned by `uni/bom` (hex `64efbbbf61`, note "A leading U+FEFF is content"). ✓
- `float64-from-bits` on Dart (`cbor.cljc:518-526`) sets two uint32s big-endian then reads a float64 — matches JVM `longBitsToDouble`. NaN canonicalization (`float-wire`, `cbor.cljc:887-889`) forces `7ff8000000000000` on encode; non-canonical NaN frames are refused on re-encode. Signed zero, −0.0, and distinct int/double types all behave as the contract requires. ✓
- `fresh-list`/`with-meta … nil` clearing in `frame->value` (`cbor.cljc:1271-1276`) and `fresh-list` in fixtures satisfies the contract's "clear metadata on internally constructed Dart collections" (`dao.jing.cbor.md:174-180`). ✓

**Ratified constraints.** Decimal window `[-2147483647, 2147483648]` (`cbor.cljc:555-563`, test bytes `cbor_test.cljc:419-430`), `max-depth` 128 (`cbor.cljc:850-858`, `deep!` on both paths), float64 kind preserved (no narrowing — Dart `int`/`double` are distinct types), `BigInt` for exact integers, explicit outer `with-meta`, structural reader before any package materialization (the package decoder is never used; decode is Jing-owned then strict re-encode `cbor.cljc:1371-1386`). All satisfied. ✓

**Tests.** `guarded` (`cbor_test.cljc:77-83`) catches Dart `Object` and isolates each corpus case in both the canonical and decode runners, so a throw cannot hide later cases. The encode-refusal runner's collect-then-assert (`cbor_test.cljc:114-149`) correctly moves the per-case host-merge determination out of a throwing `is` into the `unexpected` atom, asserted once. The proposed E1 Dart column matches the code (see P2). ✓

**Safety.** `git status` shows only the four source/test files plus the new `cljd.cljd`; `pubspec.yaml`, `deps.edn`, `bb.edn`, the frozen fixtures/README/generator/errata, and all `dao.jing`/backend/`dao.space`/transport sources are untouched. The `cbor 6.5.1` pin predates J2. ✓

---

Net: the code is correct and the corpus is genuinely green; the sole change required before sign-off is adding the Dart column to the ratified errata E1 table (architect/orchestrator action, not a code edit).

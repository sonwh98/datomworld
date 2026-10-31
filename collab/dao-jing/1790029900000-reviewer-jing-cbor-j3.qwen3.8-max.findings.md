Completed-GMT: 2026-09-22 00:42:00 GMT
Completed-Local: 2026-09-22 07:42:00 +07 (Indochina Time)
Coding-Agent: cmd
Session-ID: pending

## Verdict: READY WITH CHANGES

No P1. The three hosts cannot pass while disagreeing, and nothing in the working tree can overwrite the resource undetected. Two P2s need an architect ruling / small hardening.

## P2 findings

**P2-1. `coll/set-vector-list-collapse` runs on no host; the exception lives only in test code, not in the policy record.**
`test/dao/jing/cbor_conformance_test.cljc:696-709` pins it as the single never-run case. The implementer's claim is correct: errata E1 (cbor-v1.errata.md:33) marks it N/A (merged) for JVM and Node, E6 (cbor-v1.errata.md:126) for Dart, and the gate's parsed columns match the errata text exactly (verified row by row: JVM N/A = {signed-zero, decimal-scale, vector-list, integer-width} per lines 30/32/33/34; Node = {vector-list, set-nan, map-nan} per 33/35/36; Dart = {signed-zero, vector-list} per 123/126). The J3 completion criterion "every case runs on at least one host" is therefore violated by one id. Smallest fix: none in code — add one sentence to the errata when E6 is ratified recording the exception and its decode twin, so the policy file (not just a test comment) carries it. See my recommendation below.

**P2-2. The static write-scan is bypassed by anything outside the Clojure/Dart/JS API marker list.**
`cbor_conformance_test.cljc:892-914`: markers are API spellings (`spit`, `writeAsString`, `Files/write`, …) and the filter only scans `.clj|.cljc|.cljd|.cljs|.edn|.sh`. A bb task doing `(shell "cp x test/resources/dao/jing/cbor-v1.json")`, a shell redirect `>` in a `.sh`, or any new `.py` under `test/` naming the corpus is invisible to the scan; the generator test (lines 931-939) covers only `cbor-v1.generate.py` and misses `os.remove`/`os.rename`/`pathlib` in it. Prevention therefore rests on the SHA pin (lines 877-886), which is post-hoc: within a single run, a write scheduled before the pin test escapes until the next run. Smallest fix: add `py` to the `scan-files` regex (line 914), add spell-concatenated markers `os.remove`, `os.unlink`, `os.rename`, `os.replace`, `write_text`, `write_bytes`, `shutil` (Python side) and, for `.sh`/`bb.edn`, the literal `test/resources/dao/jing/cbor-v1.json` appearing on the same line as `>`, `tee`, `cp `, `mv `, `rm `. Optionally re-assert the pin in a JVM `clojure.test` fixture at teardown so one run cannot both write and pass.

## P3 findings

- **P3-1. Walker does not enforce E3 or E4.** `check-tag!` (cbor_conformance_test.cljc:302-306) validates tag 4 shape only, not the [-2^31+1, 2^31] exponent window; `read-item` (line 331) stops at depth 200, not the ratified 128 (E4). Digest-vs-frozen-bytes covers the corpus, so this only weakens the walker as an independent second line. Smallest fix: reject tag 4 whose exponent node's magnitude exceeds the window; lower the walker's stop to 128 for produced bytes (keep frozen-byte walking as-is or apply both, corpus nests "a handful of levels").
- **P3-2. Ratio reduction is unchecked when the denominator is a tag 2 bignum.** Lines 315-318: `magnitudes` returns nil for `:big` args, so gcd is skipped. Frozen bytes still pin correctness via the digest. Smallest fix: none portable without BigInt in the walker; document the limitation in the walker banner comment.
- **P3-3. Both digest pins and the corpus pin were taken from the JVM run only** (report, Deviations: no openssl cross-check). Risk is low — three independent SHA-256 implementations (MessageDigest, goog.crypt, Dart) all match the pins — but a one-time external `openssl dgst -sha256` at sign-off would close it. I could not run it here: shell execution is permission-blocked in this review session.
- **P3-4. Errata row parsing is section-positional.** `errata-rows` (lines 432-452) switches section only on `"## E6"`; a future E7 table with `coll/` rows would be parsed into the Dart column — loudly (the `[9 9]` row-count pin at line 674 fails), not silently. Smallest fix: close `:e6` at the next `## ` heading.
- **P3-5. Dart aborts the skip-accounting deftest at the first failing `is`** (lines 646-717 use direct `is`, not `check!`), so later assertions in the same deftest are skipped on failure. Failure is still reported; acceptable, noted only because the file's own banner promises the collect-then-assert pattern.

## Item 1: independence and correctness of the walker

- **Independence: clean.** The walker (lines 133-413) uses only `fx/hex->ints` (pure hex-table arithmetic, cbor_fixtures.cljc:112-117), string ops, and its own tables. No `dao.jing.cbor` call, no Boring adapter (the ns requires `cbor` only for encode/decode/refusal in the harness, line 27; `dao.jing.cbor.boring` is not required at all). Frame names at lines 133-138 match the codec's five names (cbor.cljc:1271-1299).
- **Shortest heads: correct**, including all four boundaries: ai 24 requires arg≥24, ai 25 ≥256, ai 26 ≥65536, ai 27 requires the upper 32 bits set (`:big`) — an 8-byte head holding a smaller value is flagged non-shortest (lines 184-189). Self-tests `1800` and `9800` at lines 778-779.
- **Definite lengths only:** ai≥28 (indefinite/break/reserved) stops the walk (lines 169-170); truncation stops at 171-172, 338-339, 350-351.
- **Native floats:** major 7 ai 25/26/27 flagged (line 389); 20/21/22 accepted, everything else (23=undefined, 24=simple) flagged (lines 390-391). Self-tests f7, f8ff, f9/fa/fb.
- **Tag 27 frames:** exact `[name payload]` arity, name ∈ five (lines 241-245); per-kind payload shapes including 8-byte float64 with canonical-NaN-only rule (262-267), keyword/symbol `[ns-or-null text]` (252-258), with-meta non-empty map, value ∈ collection/symbol (269-280), reader-position keys stripped (281-287).
- **Tag 258:** array content, strictly ascending (319-323).
- **Hex-text ordering equivalence: yes, it is equivalent.** Lowercase hex, fixed 2 chars per byte, ASCII `'0'..'9' < 'a'..'f'` matches nibble value order, so lexicographic order of the hex text = unsigned bytewise order; a proper prefix is a shorter string sharing the prefix and sorts first in both orders. `compare` is codepoint-order for ASCII on JVM, JS and Dart alike. Applied to full key/element encodings (lines 375, 322) — the correct canonical-CBOR relation.
- **Bignum/decimal/ratio shapes:** tag 2/3 require ≥9 body bytes and no leading zero (297-301, correct: ≤8 bytes always fits uint64/int64); tag 4 `[int int]`; tag 30 `[int positive-denominator]` with gcd reduction (subject to P3-2). Unknown tags refused (324), tag 39 refused (295).
- **Mutation thinking — five plausible codec bugs:**
  1. **Signed-byte key sort (JVM):** any key ≥0x80 sorting before 0x00-0x7f — caught by the walker (hex order is unsigned, line 375) and by the digest (bytes differ from frozen).
  2. **Non-shortest head (e.g. `18 17` for 23):** caught by the walker (lines 184-189, 345-346) and digest.
  3. **Native float64 slipped in (`fb…`) instead of the tag 27 frame:** caught by the walker (line 389) and digest.
  4. **Tag 39 identifier emitted:** caught by the walker (line 295) and digest.
  5. **Set elements out of order / duplicated inside tag 258:** caught by the walker's strict ascending check (322-323; strictness catches duplicates too) and digest.
  The digest alone catches every byte-level divergence (frozen hex + sha are per-id pinned); the walker's added value is that it also validates the frozen corpus itself on every host (lines 763-768) and independently flags 9 decode-refusal classes without the codec (823-834).

## Item 2: the cross-host comparison is real

Three hosts cannot pass while disagreeing. Each host asserts (a) its digest equals the resource-derived digest and (b) equals the same pinned literal (lines 724-748); all lanes green ⇒ all three digests equal one literal. The digest inputs are host-independent by construction:
- **Case set:** `common-ids` = all ids of the kind minus the *union* of documented skips (598-603); the union comes from the pinned `not-buildable` table (424-426) plus errata columns parsed from the one errata file and themselves pinned to exact sets and `[9 9]` row counts (673-683). A host-specific new skip changes the observed-skip set and fails lines 654-662 before it can change the digest set undetected.
- **Silent case drop:** impossible — `digest-lines` maps over ids, not entries (606-612); a missing entry yields empty fields (digest mismatch) plus the explicit `contains?` assertion (731, 745). Counts 209/31/132/372/208 are pinned (651, 711-717).
- **Signature fields:** `ok|rt=1|<walker diag>` — the diag is pure string/integer logic (no floats, no host formatting); `rt` is asserted 1 per case (504); refusal sigs use `(name kw)` of the frozen class (487-489). The resource-derived sig re-runs the same walker over the frozen hex (585-595), so "derived" is not a copy of the host's own output.
- **Sorting:** ids are ASCII; string sort is codepoint sort on all three hosts.
- The remaining shared inputs (cbor-v1.json, errata) are respectively SHA-pinned on all hosts (882-886) and column-pinned (676-683).

## Item 3: recommendation on `coll/set-vector-list-collapse`

The claim is right (errata citations above; the decode twin `mal/collapse-vector-list-set`, kind `decode-refusal`, refusal `equality-collapse`, exists and runs on all three hosts — asserted at lines 704-709). The encode-side refusal is unobservable by construction on every current host: the host set constructor merges a list and an equal vector before the codec sees the value, and there is no public host-independent way to hold both members. Building the set through wire-node internals would test codec internals rather than the public contract, and the twin already exercises the same equality-collapse decision on the same value pair at the wire boundary. **Recommendation: accept the exception.** Keep the pinned single-exception test (it fails loudly if the never-run set ever grows), and when ratifying E6 add one errata line recording that `coll/set-vector-list-collapse` is encode-unobservable on all three hosts, covered decode-side by its twin, and must be reactivated on any host that gains a non-merging set construction. No code change.

## Item 4: immutability

The pin (c8f5ef38…) is asserted on all three hosts over the ASCII-checked text (882-886); cbor-v1.json is unmodified in the working tree (only the errata .md carries the J2 E6 addition). I could not recompute the pin externally (shell blocked here — see P3-3); the fact that three independent SHA implementations match it across green lanes is decent cross-validation. The scans are not vacuous — they assert they saw files and reach the fixture loader (925-927), and markers are spell-concatenated so the test file does not match itself (894-899) — but they are heuristic and bypassable (P2-2), and over-broad only in the safe direction (a corpus-naming file that merely *mentions* `spit` even in a comment fails; conservative, acceptable). No current test can overwrite the resource; a future one could, with detection deferred to the next run's pin (P2-2 fix closes this).

## Item 5: portability — clean

- `:cljd` is first in both reader conditionals (lines 30 and 889); the cljs branch of line 889's `#?` correctly falls through to nil.
- Dart failing-`is`: every corpus loop uses `check!`/`guarded` with one `assert-none!` per deftest (90-119); only small fixed tests use direct `is` (P3-5).
- No BigInt anywhere in the walker; `arg-of` peaks at 2^32-1, exact on JS doubles.
- Node reads via `fs.readFileSync` on repo-relative paths (cbor_fixtures.cljc:35-41), matching the documented lane cwd.
- List-literal/destructuring traps: none found (`[what & parts]`, `partition 2 1`, `subvec` all portable).

## Item 6: safety — clean, with one verification limit

J3's file set is the new test plus `read-path` (read-only, cbor_fixtures.cljc:35-41); no production edit, no frozen-file edit, and the file reads as pure ASCII throughout. Limit: my session's shell is permission-blocked, so I could not run `git diff` to independently confirm the `src/cljc/dao/jing/cbor.cljc` and `boring.cljc` working-tree modifications are J2's and contain no J3 edits; I verified by content inspection that nothing in them references the conformance namespace.

## Checked and found clean

Walker head/boundary logic against RFC 8949 shortest-form rules; hex-order equivalence proof; the five frame names against the codec; the 25-bad/5-good self-test vectors (each hand-decoded: `9f01ff`, `1800`, `9800`, `f9/fa/fb`, `d82760`, `c500`, `f7`, `f8ff`, `0000`, `18`, `6261`, `a202020101`, `a201010102`, set-descending, frame name/arity/shape, float64 length and NaN payload, keyword ns shape, with-meta-on-keyword, bignum leading zero and 64-bit-fitting — all correctly flagged; empty vector/set, sorted map/set, float64(1.0) correctly accepted); errata E1/E6 tables row-by-row against the parsed pins and against `cbor-test/skip-by-id` (cbor_test.cljc:26-46) and `collapse-not-applicable` (47-57); refusal digest arithmetic (31−3−6=22 encode + 132 decode = 154; 209−1=208) against the report; manifest skip/entry disjointness and no-silent-drop logic; `digest-of` ASCII guard; `refusal-of`/`guarded` exception handling on all three hosts; injectivity/equivalence/distinct-group tests over produced bytes (841-870), including one-address-per-bytes.

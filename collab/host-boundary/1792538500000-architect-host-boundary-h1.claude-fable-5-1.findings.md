# Slice H1 architectural review: the pure primitive profile

Date: 2026-10-21
Reviewer: Claude Fable 5.1, System Architect & Reviewer
Branch: host-h1, worktree /Users/sto/workspace/datomworld-host-h1
Reviewed HEAD: 32d1e380 plus the 12 staged, uncommitted files listed by `git status` (10 new, `values.cljc` and `gate_test.clj` modified).
Authority: docs/design/dao.stream.host-boundary.md r10, §2.4, §2.5, §4.2 (tables A, B, C), §4.3 (table D), §8 H1 row, §8.1, §14.
Engineer report: collab/1792533000000-engineer-host-boundary-h1.claude-opus-5-5.findings.md

**Verdict: SIGNED OFF WITH CHANGES.**

The five namespaces are the profile table A names, and nothing more, with the laws §2.4 and §4.2 state. The moved code is verbatim where the design says verbatim (I diffed it, see "Verification"); the new laws (strict `utf8-encode`, prevalidated `set!`/`copy!`, fresh results) are implemented and tested on three lanes. Both engineer findings are ruled below: F1 is accepted as a recorded JVM-lane gap with an owner follow-up, F2 is an acceptable reading of §8.1. The required changes are three documentation edits (a ledger row, one docstring, one table A sentence). None touches behaviour, none needs a further review round; the orchestrator may land H1 once they are applied and the quick tier is green.

Standing rules applied: a Claude reviewer may not be the sole sign-off on Claude-authored code (§14). This report is the Architect review; the orchestrator must obtain the non-Claude confirmation before commit, as for H0.

## 1. Table A: entry-by-entry

Count check: integer 28, float 5, number 10, bytes 10, text 6 = 59, the H1 share of the 76. Every public `defn` in the five files is either a table A name, a table B name (`code-points`, the float-text kernel and `decimal-text->float`), a table C carrier, or a documented constant (`decimal-exponent-min/max`, `small-powers-of-ten`). No extra entry point.

| Namespace | Checked | Result |
|---|---|---|
| `integer` | 25 shim entries diffed against `yin/vm/integer/host.cljc` (comment lines stripped) | byte-identical bodies. `parse-decimal`, `gcd`, `magnitude-bytes` are new wrappers over the `cbor.cljc:112-254` arms; JVM `magnitude-bytes` strips the sign byte correctly for 0 and for 2^n−1 boundaries (zero → empty, 128 → `[0x80]`). `parse-decimal` prevalidates the grammar before any host parser, as the docstring promises. One deviation from table A wording, see C3. |
| `float` | the 13 kernel helpers diffed against `yin/vm/integer.cljc` with `host/`→`integer/` substituted | identical except re-indentation after the alias rename; `float-digits` and `decimal->float` equal `int-float-digits`/`int-decimal->float` minus their refusal preambles, exactly the §4.2 (R7-1) signatures. `words` canonicalises every NaN to 7ff8…, so `float64->bits` and `fields` meet "equal arguments give equal results on every host". `fields` fraction arithmetic stays below 2^53, so exact on JS. |
| `number` | carriers and ten entries against `cbor.cljc:342-460,483-632` | `Float64` equality is `Object.is` plus NaN=NaN: the table C law. `Rational`/`Decimal` blocks are the cbor blocks. `decimal-of` enforces the JVM window on every host with big comparisons, so acceptance is host-independent; the JVM scale cast is in range at both ends. `ratio-of` normalises sign and reduces; `(ratio-of 0 5)` gives 0/1. |
| `bytes` | all ten | Every index, length and byte is checked before any write; `copy!` checks `len` against both arrays then both offsets. JS overlap relies on `TypedArray.prototype.set` cloning a same-buffer source (spec-mandated), Dart copies the source range explicitly, JVM uses `arraycopy`; the memmove tests pass both directions on three lanes. `slice`, `alloc`, `from-vector` are fresh (Dart's `sublist` is already a copy; the extra `fromList` is harmless). |
| `text` | all six plus `code-points` | `utf8-decode` is `cbor.cljc:1240-1271` verbatim including the Dart leading-BOM workaround, now with a range check and a whole-array arity. `utf8-encode` refuses an unpaired surrogate before any host encoder runs, on every lane. `code-points` is the `data.cljc` helper over `unit-count`/`code-unit` only. `nfc` is the three host normalizers; see F1. |

§2.4 obligations, as claimed in acceptance:
- (i) P-entry argument immutability: `p-entries-leave-their-arguments-unchanged` (40 seeded arrays, every bytes and text P entry, before/after hex). Integer and float P entries take only immutable host values, so the obligation is vacuous there and the design's "over the four namespaces" is met by the one mutable argument kind. Accepted.
- (ii) M-entry prevalidation: `refusals-leave-the-target-byte-identical` covers `set!` and `copy!`, including a valid source range with an invalid destination. Accepted.
- Freshness: `fresh-results-are-fresh` covers `slice`, `alloc`, `from-vector`, `utf8-encode`, `magnitude-bytes`. Accepted.

Layering: `dao.host.integer`, `bytes`, `text` require nothing in the repo; `float` and `number` require `dao.host.integer` only; no `yin` require anywhere. Every mixed reader conditional names `:cljd` first.

## 2. Rulings on the engineer's findings

### F1: JVM NFC is Unicode 15.0 on JDK 21. Ruling: (c) now, (a) as the owner's follow-up.

Facts I verified: `mise.toml` pins `java = ["21", "17"]`; `unorm_dart` 0.3.1+1 ships Unicode 16.0 data (its CHANGELOG says so and `unormdata.dart` holds the Tulu-Tigalari rows), so the Dart lane's pass is genuine, not a weak test; the fixture header derives the 16.0 flag from `DerivedAge.txt`; `nfc-pinned-unicode-16-subset` asserts the exact 20 failing code points on a JVM whose feature version is below 24 and an empty list elsewhere, so the pin cannot rot silently.

Ruling:
- H1 is not blocked. §2.5 pins 16.0 for the profile; the JVM arm meets that pin from JDK 24 onward and the lane runs JDK 21. That is a lane-toolchain gap, not a defect in the entry: the arm is the right host call, and the test records exactly where the host falls short. It also predates H1 (`debruijn/normalize-nfc` has the same arm today), so H1 does not widen it.
- Option (b), a portable NFC over pinned tables, is rejected for this track. Table A lists `nfc` as a host arm by design; replacing it is a table B replacement with its own oracle and cost, and would be new scope under a frozen design. If the owner ever wants it, it is a design amendment, not an H-slice.
- Option (a), raising the JVM lane to a JDK with Unicode 16.0 (JDK 24, or the JDK 25 LTS), is the recommended closer. It is an owner toolchain decision outside this slice; the test flips green by itself when it lands.
- Required now (C1): a known-gaps ledger row. Required now (C2): the `nfc` docstring states the JVM condition. Required at landing: the H1 slice report names the gap and the 20 code points, per §8 "behaviour changes named in slice reports".

### F2: list 1 grows by five before the closure can reach them. Ruling: accepted as the §8.1 reading.

§8.1 says the five "join list 1 at H1" and maps `:profile` to the single closure class `:profile-internal-pure`; §8.1 also forbids changing the traversal, the resolution rule and the 142/140/141 totals. The engineer's `profile-additions` is data in the gate, scanned beside the closure (160 = 155 + 5), classed per lane only where the committed closure lacks the file, and guarded by `profile-additions-are-scanned-until-the-closure-reaches-them`, which (a) catches any `dao/host` file that is neither closure nor addition, (b) fails the moment a lane's closure reaches an addition, forcing the entry's deletion at H2, (c) asserts the `:profile` category and the one admitted class, and (d) asserts each addition's arms were observed and its effect count is zero. The closure tool, its outputs and totals are untouched. That is the least mechanism that makes the acceptance line true without touching H0's frozen artefacts, and it is self-retiring. Confirmed.

H2 note for the orchestrator's brief: when the first consumer requires a `dao.host.*` file, delete its `profile-additions` entry and add the `dao.host.*` rows to `closure.clj`'s `class-rows` as `:profile-internal-pure` in the same commit; the staleness test and the per-lane `:class-none` check enforce both halves.

### F3 to F7

F3 (two copies of carriers and kernel until H2): as §4.2 plans. F4 (new laws stated where today is lax): each is a tightening at the profile with no H1 consumer; I checked each against its source and agree with every one; H2 briefs must carry the `:dao.host.float/float-overflow` sentinel mapping and the `cbor_fixtures` mantissa routing through `parse-decimal`. F5 (host `=` on JVM `BigDecimal` ignores scale): §2.5 dialect semantics, and the test asserts scale through `decimal-exponent` on every lane; agreed. F6 (table D coverage stated exactly): the omissions are codec-level or H2 module-level cases, correctly left where they are. F7 (two files pre-existed as drafts): I re-diffed them independently; verbatim.

## 3. Gate test additions

Reviewed the +78/−5 diff. `lane-classes` composes the committed per-lane classes with the additions without overriding a class the closure already assigns; `gate-files` is the union; `current` scans the union; the negative in-memory checks now run over `lane-classes`, so the exception table and the "deliberately wrong entry" cases still exercise the same code path. List-1 entries carry `:profile :stays` with an "H1 addition (§8.1)" reason. The scan-failure, injected-arm and injected-effect negatives are unchanged. 12 tests, 180 assertions, 0 failures on jupiter. No concern.

## 4. Required changes (documentation only; no re-review round)

- **C1** Add a row to `docs/agents/known-gaps.md`: gap "JVM `dao.host.text/nfc` is Unicode 15.0 on the lane's JDK 21; 20 Unicode 16.0 canonical compositions (Todhri, Tulu-Tigalari, Gurung Khema, Kirat Rai) do not normalize; pinned by `text_test/jvm-nfc-gap`"; evidence "H1 report F1, this review"; owner seat "host-boundary"; closing slice "open: owner decision on raising the JVM lane to JDK 24+ (recommended)"; severity P2.
- **C2** `src/cljc/dao/host/text.cljc`, `nfc` docstring: add that the JVM arm's Unicode version is the running JDK's (16.0 from JDK 24), that the profile pin is enforced by `dao.host.text-test`, and that the lane currently records the gap in known-gaps.md.
- **C3** `parse-decimal` accepts one optional leading `-`; table A says "digit string to big, refuses otherwise". Keep the code: the corpus carries negative mantissa strings (`"-15"`, a 41-digit negative) that H2 must route through this entry, and cbor's `big` accepted them through the host parsers. Amend the table A cell to "optional leading `-` then one or more ASCII digits; refuses otherwise" as a dated r11 note. Orchestrator edit to the design doc, with the owner's standing authority for table-text amendments.

Landing conditions (standard, not findings): the quick tier on three lanes with per-lane counts recorded before and after, per §8 ("counts unchanged plus the named new tests"); the H1 slice report names the F1 gap; the non-Claude confirmation per §14.

## 5. Recommended, non-blocking (record in the slice report; H2 may pick them up)

- **R1** `text/units->string` does not check its declared domain (0 to 0xFFFF). Out of domain the hosts diverge: the JVM throws, JS truncates modulo 2^16, Dart writes a surrogate pair for a value up to 0x10FFFF. Totality over the declared domain holds, so this is not a §2.4 violation, but `bytes/from-vector` prevalidates its elements and this entry should match it with an `:out-of-range` refusal. P3.
- **R2** `text/utf8-decode`, `nfc`, `unit-count` and `code-unit` do not refuse a wrong-typed argument with `:unsupported-value`; a host exception escapes instead, where `bytes/*` refuses uniformly. P3, consistency of refusal shape.
- **R3** `dao.host.number` docstring says the carriers print as `#dao.jing/ratio` and `#dao.jing/decimal`; the Dart `Rational` and `Decimal` have no print form (same as cbor today). Say "on the JVM and JS". P3.
- **R4** `float/decimal->float` promotes `exp10` without an `exact-integer?` check (the original had `big!`); a double exponent truncates silently on the JVM and throws on JS. The docstring says "any carrier" of an exact integer, so this is out of domain; H2's consumer keeps its own preamble by design. Note only.

## Verification

- `bb test:sub:clj dao.host` run locally in the worktree: 5 namespaces, 43 tests, 0 failures, 14 s (log /tmp/h1-clj-lane.log). The three-lane run and the gate test are taken from the orchestrator's jupiter record (CLJS 31 tests / 876 assertions, CLJD 31 tests, gate 12 / 180, all 0 failures); I did not re-run Node or Dart.
- `clj -M:kondo --lint src/cljc/dao/host test/dao/host`: 0 errors, 0 warnings (one info: `set!` listed in `:refer-clojure :exclude`, harmless).
- Diffs: integer entries `big-carrier?`..`to-radix-string` against `yin/vm/integer/host.cljc`, identical; float kernel helpers against `yin/vm/integer.cljc`, identical modulo indentation; `utf8-decode` against `cbor.cljc:1240-1271`, identical arms; `Rational` block against `cbor.cljc`, identical.
- `unorm_dart` 0.3.1+1 in the pub cache: README and CHANGELOG state Unicode 16.0; `unormdata.dart` contains the 16.0 rows.
- Fixture `test/resources/dao/host/nfc-16.0-subset-v1.txt`: header cites Unicode 16.0.0 `NormalizationTest.txt` and `DerivedAge.txt`; the test refuses any file without the `nfc-16.0-subset-v1` magic line.
- No codec-track source or test file is touched (§8.3); nothing from §9 is started; `values.cljc` change is a four-line docstring.

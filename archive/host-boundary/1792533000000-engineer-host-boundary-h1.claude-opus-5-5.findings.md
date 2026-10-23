Created-GMT: 2026-10-21
Coding-Agent: claude (claude-opus-5-5), Implementation Engineer
Worktree: /Users/sto/workspace/datomworld-host-h1, branch host-h1 (base 32d1e380)
Governing: docs/design/dao.stream.host-boundary.md r10, §2.4, §4.2-§4.3, §8 H1, §8.1

# Slice H1: the pure primitive profile, implemented (uncommitted, awaiting review)

Nothing is committed. Per orchestrator.md and the standing rule, commit
waits for a non-Claude Architect sign-off.

## Verdict

H1 is implemented and green on three lanes. Five profile namespaces, four
new test namespaces, one new pinned Unicode resource, the gate extended so
list 1 grows by exactly the five files. Two places where I had to choose
how to do something the design does not say (F1 the JVM NFC gap, F2 how the
gate sees files the closure cannot reach yet) need an Architect decision
before commit.

## What changed

New source (all `src/cljc/dao/host/`, no `yin` require; float and number
require `dao.host.integer`, nothing else in the repo):

| File | Table A entries (count) | Table B / C |
|------|------------------------|-------------|
| `integer.cljc` | 25 from `yin.vm.integer.host`, moved verbatim (diffed: identical bodies), plus `parse-decimal`, `gcd`, `magnitude-bytes` from `cbor.cljc` (28) | — |
| `float.cljc` | `float64->bits`, `bits->float64`, `finite?`, `integral?`, `fields` (5) | float-text kernel copied from `yin.vm.integer` (diffed: identical arithmetic; `ratio->float`, `trunc-big`, `float-ratio`, `decimal-exponent`, `scaled`, `pow10`, `small-powers-of-ten`, `digits->big`, `strip-trailing-zeros`, `sign`, `bits`, `pow2`, `signed`, `float-digits`, `decimal->float` with the §4.2 signatures) and `decimal-text->float` |
| `number.cljc` | `float64-of`, `float-value`, `decimal?`, `decimal-of`, `decimal-exponent`, `decimal-mantissa`, `ratio?`, `ratio-of`, `ratio-numerator`, `ratio-denominator` (10) | `Float64` (JS), `Rational`, `Decimal` (JS, Dart) deftypes and the JVM `Rational` print-method, copied with their protocol blocks; table C laws in the ns docstring |
| `bytes.cljc` | P `bytes?`, `alloc`, `length`, `get`, `slice`, `equal?`, `from-vector`, `to-vector`; M `set!`, `copy!` (10) | — |
| `text.cljc` | `unit-count`, `code-unit`, `units->string`, `utf8-encode`, `utf8-decode`, `nfc` (6) | `code-points` (public, from `yin.vm.data`) |

59 table A entries, matching §4.2's H1 rows (28+5+10+10+6).

Changed: `test/dao/host/gate_test.clj` (+78/-5: list 1 +5, the
profile-additions mechanism, one new deftest; see F2);
`src/cljc/yin/vm/values.cljc` (+4, docstring only: names its equality,
hash and print laws as table C prerequisites, §8 H1 "Table C laws written
into the docstrings").

New tests:
- `dao.host.conformance-test` (15 deftests): table D's V rows through the
  profile. Integer carriers and operations; `parse-decimal` grammar;
  `magnitude-bytes` reproducing the corpus's tag 2/3 bytes byte for byte;
  corpus integers and the 31 int-contract rows (dec, hex, bits);
  `float64->bits` against every corpus float64 payload (NaN cases read
  7ff8...); table A float laws; the integer-v3 rows (`to-float` 187,
  `true-div` 96, `from-float` 29) through `ratio->float` and `trunc-big`;
  corpus decimal and ratio cases, checked against today's `dao.jing.cbor`
  field by field; number laws (window ends, zero denominator, reduction,
  JS `Float64` table C laws, print forms); code units and code points;
  every corpus canonical string encodes to its frozen payload and decodes
  back; every corpus unpaired-surrogate string refuses to encode; §2.4 (i)
  P-entry argument immutability over 40 seeded byte arrays; freshness of
  `slice`, `alloc`, `from-vector`, `utf8-encode`, `magnitude-bytes`.
- `dao.host.bytes-test` (6): round trip, slice freshness, `equal?`,
  `copy!` between arrays and overlapping in both directions (memmove),
  `set!`, and the no-partial-write cases (target byte-identical after
  every refused `set!`/`copy!`, including a valid source range with an
  invalid destination).
- `dao.host.text-test` (6): RFC 3629 boundaries round-trip; 23 ill-formed
  sequences (overlong, encoded surrogates, CESU-8 pair, above U+10FFFF,
  F5-FF, truncated, stray continuation) each a direct `:invalid-utf8`;
  the five corpus `invalid-utf8` string payloads; unpaired-surrogate
  encode refusals; `code-points` (BMP, non-BMP pair, lone high, lone low,
  empty); `nfc` over the pinned Unicode 16.0 subset (F1).
- `dao.host.float-test` (4): the five texts §4.2 names (`"1.5"`, `"0.1"`,
  `"9007199254740993.0"` -> 2^53, 400-digit -> +inf, `"1."` refused) plus
  the grammar's refusals; `integer_test`'s float-text named cases through
  `float-digits` / `decimal->float` directly; a float-digits /
  decimal->float inverse over ten doubles; `ratio->float` sign and
  overflow; the int-contract float rows.

New resource: `test/resources/dao/host/nfc-16.0-subset-v1.txt` (792 KB):
every @Part0 and @Part1 line of Unicode 16.0.0 NormalizationTest.txt
(17130 rows, columns c1-c5; Part 1 contains the 11172 Hangul syllables and
the composition exclusions), each flagged `16.0` when it holds a code point
DerivedAge 16.0.0 first assigned in 16.0 (56 rows). Source files fetched
from unicode.org; sha256 NormalizationTest.txt
d811971453e7075e1ad56fb1b301eece5aa80757b81f6156e74a1bfb3ae5ceb1, DerivedAge.txt
60caa81b99a4dfb0bc5d38440d323073e3f356f46f9ca7869da848cd0715408b. It is
outside the H0 corpus hash roots, so `corpus-bytes-match-the-frozen-hash-set`
is unaffected (still 18 files).

## Verification (all run in this worktree, foreground)

`bb test:sub dao.host` (G1, three lanes):

| Lane | Namespaces | Tests | Assertions | Failures |
|------|-----------|-------|------------|----------|
| clj  | 5 (incl. gate) | 43 | 1050 | 0 |
| cljs | 4 | 31 | 876 | 0 |
| cljd | 4 | 31 | (Dart reports tests only) | 0 |

`bb test:sub:clj dao.host`: 43 tests, 0 failures. `dao.host.gate-test`:
12 tests, 180 assertions, 0 failures; it now reads 160 files (the 155
closure files plus the five), finds arms in all five and no effect calls.
A probe file `src/cljc/dao/host/zz.cljc` with one arm made the gate fail
(new deftest, "none escapes the gate"); probe deleted.
`clj -M:kondo --lint` over the changed paths: 0 errors, 0 warnings (one
info: `:refer-clojure :exclude [set!]` names a special form, kept so no
host's core can shadow the M entry). `cljstyle check`: clean.

G2 `bb test:changed` (150 of 272 namespaces, because of the `values.cljc`
docstring): RESULT PENDING, filled in below.

No consumer requires `dao.host.*`, so no existing test's behaviour can
change; the corpus bytes are untouched (H0 hash set green).

## Findings for the Architect

**F1 (decision needed). The JVM lane's NFC is Unicode 15, not the pinned
16.0.** mise selects Java 21 (`mise.toml`: `java = ["21", "17"]`); JDK 21's
`java.text.Normalizer` implements Unicode 15.0 (16.0 arrives in JDK 24).
On the pinned subset all 17074 pre-16.0 rows pass on all three lanes, and
all 56 rows with a 16.0 code point pass on Node 25.6.1 (ICU) and Dart
(`unorm_dart` 0.3.1+1); on the JVM 20 of them fail: the new canonical
decompositions of Todhri (U+105C9, U+105E4), Tulu-Tigalari (U+11383 ...
U+113C8), Gurung Khema (U+16121 ... U+16128) and Kirat Rai (U+16D68 ...
U+16D6A). So "equal arguments give equal results on every host" does not
hold today for `nfc` on strings containing those characters, and
`debruijn.cljc`'s claim that the profile pins 16.0 is true on two lanes,
not three. I did not weaken the law silently: `text-test` asserts [] on
Node and Dart and, on a JVM before 24, exactly those 20 code points
(`jvm-nfc-gap`), so the pin fails the day the JDK changes. Options:
(a) move the JVM lane to JDK 24+ (mise change, wide); (b) a portable NFC
over pinned 16.0 data (a table B replacement, real work); (c) accept and
record the gap in known-gaps.md. This needs an owner/Architect ruling; it
predates H1 (`debruijn/normalize-nfc` has the same arm today).

**F2 (decision needed). How list 1 "grows by five files".** §8 H1 says
list 1 grows by five, but the gate only reads the committed closure, and
the closure cannot reach `dao.host.*` until H2 adds the first require. Left
alone, the gate would never scan the five files and
`list-1-equals-the-observed-arms` would reject list-1 entries outside the
closure. I added, as data in the gate: `profile-additions` (the five files,
slice "H1", class `:profile-internal-pure`, the one class §8.1 maps
`:profile` to); the gate scans closure ∪ additions (160 files);
`lane-classes` gives each addition its class on every lane that reads it
and whose closure lacks it; and a new deftest asserts every file under
`src/cljc/dao/host/` is either a closure file or an addition (so a new
profile file cannot escape the gate), each addition is outside every
closure (so the entry goes stale loudly at H2 and must be deleted), and
each is on list 1 as `:profile`. The closure tool, its outputs, its totals
(142/140/141, 133 shared, 155 union) and the traversal are unchanged. I did
not add `dao.host.*` to `closure.clj`'s `class-rows`: until H2 they are
unreachable; at H2 the staleness test forces the entry's deletion and the
per-lane `:class-none` check then forces the class-rows edit, so it is
self-enforcing. Please confirm this is an acceptable reading of §8.1, or
say where the design wants the profile files classified.

**F3. Two copies of the carriers until H2.** `dao.host.number`'s
`Float64`/`Rational`/`Decimal` are distinct types from `dao.jing.cbor`'s;
`number/ratio?` is false for a cbor `Rational` and the reverse. Harmless in
H1 (no consumer) and removed when H2 drops cbor's `:342-460`. Likewise the
float-text kernel exists twice (here and in `yin.vm.integer`), as §4.2
plans.

**F4. Laws I chose where today's behaviour is lax (new, stated in the
docstrings, each tested).** None is observable before H2, but each is a
behaviour H2 will adopt:
- `number/decimal-of`, `ratio-of` accept exact-integer carriers only.
  cbor's private `big` also took decimal strings (`BigInteger.`/`BigInt.parse`/
  `js/BigInt`) and, on JS, any integral `Number` including unsafe ones; here
  both refuse `:unsupported-value` (strings belong to `parse-decimal`;
  unsafe JS Numbers are not exact integers by `exact-integer?`). No corpus
  case or caller passes either today (`cbor_fixtures` passes the mantissa
  as a string, so H2 must route it through `parse-decimal`).
- `float/bits->float64` accepts exactly 16 lowercase hex digits; cbor's
  `float64-from-bits` on the JVM used `Long/parseUnsignedLong`, which also
  takes uppercase and shorter text.
- `float/fields` reads every NaN as 7ff8... (`[false 2047 2^51]`), like
  `float64->bits`; cbor's private `float-fields` kept the payload.
- `bytes/*` prevalidate every index, length and byte value (new, §2.4);
  `from-vector` takes 0-255 only, where `dht`'s `ints->bytes` wrapped with
  `unchecked-byte`.
- `text/utf8-decode` gains a whole-array arity and a range check
  (`:out-of-range`); its decoding arms are cbor's `utf8` verbatim.
- `text/utf8-encode` refuses `:unpaired-surrogate` (the design's new law)
  and a non-string `:unsupported-value`.
- Refusals throw `ex-info` with `:dao.host/refusal` (class) and
  `:dao.host/entry`; classes reuse the corpus vocabulary
  (`:malformed-number`, `:unsupported-value`, `:unpaired-surrogate`,
  `:invalid-utf8`, `:carrier-coercion`) plus `:out-of-range` and, for
  `float-digits` outside its domain, `:out-of-domain`. H2 consumers that
  must keep their own refusal shapes (`cbor`'s `::refusal`, the integer
  module's `:wrong-type` preamble) translate or pre-check, as §4.2 already
  says for `yin.vm.integer`. `ratio->float`'s overflow sentinel is
  `:dao.host.float/float-overflow`, not `:yin.vm.integer/float-overflow`;
  H2's rewiring must map it.

**F5. Host `=` on JVM decimals.** `(= (decimal-of -1 10) (decimal-of 0 1))`
is true on the JVM (Clojure's `=` on `BigDecimal` ignores scale) and false
on JS and Dart (field equality). That is §2.5's "host `=` is dialect
semantics", not a profile defect; the conformance test asserts scale via
`decimal-exponent` on every lane and carrier inequality only off the JVM.

**F6. Table D coverage, stated exactly.** The conformance test re-asserts
representative cases of each V row through the profile, not every
assertion of every named deftest: it does not re-run `cbor_fixtures`'
equivalence groups or blake3 rows (they test the codec, which H1 does not
touch), the `debruijn_code_test` little-endian hex (H2 moves that to a byte
reversal over `float64->bits`), or `integer_test`'s module-level limit and
refusal-shape tests (module behaviour, H2). `compare-float` rows of
integer-v3 are not run (that function is not part of the kernel).

**F7. Starting state.** `src/cljc/dao/host/integer.cljc` and `float.cljc`
were already on disk, untracked, when this session began (written
09:37-09:38 +07, before this dispatch's 09:50 timestamp). I treated them as
a prior draft: diffed integer against `yin.vm.integer.host` (identical
bodies) and the float kernel against `yin.vm.integer` (identical
arithmetic), kept them, and fixed nothing in them.

## For the reviewer

- `git -C /Users/sto/workspace/datomworld-host-h1 status` lists the 10 new
  files and 2 modified ones above; nothing staged.
- Re-run: `bb test:sub dao.host` (about 1 minute for three lanes).
- Ask: sign off H1, or rule on F1 and F2 first.

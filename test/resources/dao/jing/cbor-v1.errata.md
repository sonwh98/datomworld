# DaoJing canonical CBOR fixtures v1: errata

Status: RATIFIED J1 ERRATA (architect sign-off 2026-09-22)

This additive errata is ratified for J1 and J2. The frozen corpus remains
unchanged; these entries define codec policy, host-capability refusals, and
the corrected per-host test obligations.

This file is additive. `cbor-v1.json`, `cbor-v1.README.md` and
`cbor-v1.generate.py` are frozen and unedited. Nothing here changes a
frozen byte, a frozen case, or a frozen refusal class of a frozen case. It
records what the J1 JVM/Node codec (`dao.jing.cbor`) found while running the
corpus, and the codec policies that J2 (Dart) must mirror. Each entry took
effect for the README when the architect ratified it.

Recorded 2026-09-22 by claude-opus-5 (J1 and the J1 review fixes), from the
J1 reviews by qwen3.8-max and deepseek-v4-pro.

## E1. Corrected per-host N/A table (README Obligations)

The README table assumed raw host numbers. The corpus input language builds
carriers (float64, decimal and rational carriers on Node; the Rational
carrier for denominator 1 on the JVM), which host collections do not merge
with native numbers. Observed in J1:

| Case | JVM | Node |
|---|---|---|
| `coll/map-int-float-key-collapse` | live, refused | live, refused |
| `coll/set-float-decimal-collapse` | live, refused | live, refused |
| `coll/set-signed-zero-collapse` | N/A (merged) | live, refused |
| `coll/set-int-ratio-collapse` | live, refused | live, refused |
| `coll/set-decimal-scale-collapse` | N/A (merged) | live, refused |
| `coll/set-vector-list-collapse` | N/A (merged) | N/A (merged) |
| `coll/map-integer-width-duplicate` | N/A (merged) | live, refused |
| `coll/set-nan-payload-duplicate` | live, refused | N/A (merged) |
| `coll/map-nan-payload-duplicate` | live, refused | N/A (merged) |

"live, refused" means the host held both members and the codec refused with
the frozen class. The README rows it corrects: the JVM holds
`set-int-ratio-collapse` and both NaN-payload cases; Node holds
`map-int-float-key-collapse`, `set-float-decimal-collapse`,
`set-signed-zero-collapse`, `set-int-ratio-collapse` and
`set-decimal-scale-collapse`. The J1 tests skip a case only when it is in
this table's N/A column for the host and the host really merged its members;
any other merge fails. Dart is not yet observed (J2).

## E2. A host-capability refusal class: `host-collapse`

A J1 class outside the frozen 16. It is raised by decode when building a
host map or set would merge members that Jing's portable equality keeps
distinct, so the value cannot be held without losing an entry.

Scope: ClojureScript map keys and set elements that are keywords or symbols
whose joined names collide, for example `(keyword nil "a/b")` and
`(keyword "a" "b")`: ClojureScript compares both keywords and symbols by
the joined name (observed on Node in J1; a J1 review had assumed symbols
compare by fields, and they do not). Identifier values in vectors and lists
are unaffected. The JVM compares identifiers by fields and is unaffected;
Dart is to be checked in J2.

Corpus effect: canonical `coll/map-both-slash-keywords` cannot be held on
Node. Decoding it there refuses with `host-collapse`, and the J1 test skips
its encode by id while a Node-only test proves the limitation. Jing's
acceptance rules themselves stay host-independent: this class reports a
host data-model limit, not a different acceptance decision.

## E3. Decimal exponent window

Every host accepts a decimal (tag 4) exactly when its exponent lies in

    [-2147483647, 2147483648]   i.e.  [-(2^31 - 1), 2^31]

which is the JVM `BigDecimal` window (scale = -exponent is a 32-bit int).
Outside it the value is refused `malformed-number`, on encode (the
`decimal` constructor) and on decode. Node mirrors the window exactly
(`decimal-exponent-min` / `decimal-exponent-max` in `dao.jing.cbor`); Dart
must too. Test bytes, tag 4 over `[exponent 1]`:

| Hex | Exponent | Outcome |
|---|---|---|
| `c4821a8000000001` | 2^31 | accepted |
| `c4821a8000000101` | 2^31 + 1 | `malformed-number` |
| `c4823a7ffffffe01` | -(2^31 - 1) | accepted |
| `c4823a7fffffff01` | -2^31 | `malformed-number` |

The frozen corpus pins no exponent window (README A12 rules only bignum
exponents out), so this is codec policy, not a corpus change.

## E4. Maximum nesting depth

`max-depth` is 128, a J1 policy pending ratification. The top-level item is
at depth 1 and every array, map and tag puts its contents one level deeper;
a list frame (tag 27 over `[name items]`) therefore costs three levels.
Decode refuses deeper nesting `malformed-cbor` before recursing; encode
refuses it `unsupported-value`. Both count the same CBOR depth, so anything
encode emits, decode accepts. No corpus payload nests beyond a handful of
levels. The limit exists so a hostile payload (for example 10 000 nested
arrays) is refused instead of overflowing the host stack.

## E5. Notes for the architect

- Collapse checking groups members by `equiv-hash` and compares pairwise
  within a group. That is worst-case O(n^2) in collection size, bounded by
  the input and reachable only with crafted hash collisions. Recorded, not
  changed.
- `num-hash` and `equiv-hash` values are host-specific: the keys they hash
  are host-independent, but the JVM hashes strings with Murmur3 and
  ClojureScript with another scheme. Hash values are for use within one
  host and must never cross hosts; anything that partitions by hash across
  hosts (published-index sharding, for example) needs a specified digest.

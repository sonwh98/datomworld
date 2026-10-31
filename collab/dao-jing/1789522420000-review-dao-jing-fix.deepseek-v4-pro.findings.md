Created-GMT: 2026-09-15 21:13:40 GMT
Created-Local: 2026-09-16 04:13:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: deepseek
Session-ID: a1827996-e09c-45fc-a454-74de7c9c4826
# Task: Adversarial review of dao.jing canonical encoder P0 fix
Role: Adversarial Review
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-16 04:13:40 +07 | Status: complete | Rationale: advanced logic/adversarial-review strength per team.md, cross-family from the GLM implementer

Note: deepseek-v4-pro's session ran in `--permission-mode plan`, which has
no write access outside the plan file, so it could not write this
findings file itself. The orchestrator transcribed its verdict and
findings from the session's stdout log
(`collab/1789522420000-review-dao-jing-fix.deepseek-v4-pro.stdout.log`)
below, and independently verified each one empirically before accepting
or rejecting it — see "Orchestrator verification" under each finding.

## Verdict

**Yes — the diff closes the three P0 defects without introducing a
blocking defect.** Safe to proceed to Architect sign-off. (Orchestrator
concurs, after rejecting Finding 1 below as a false positive and
independently confirming Findings 2-4.)

deepseek read the full diff, both reference documents, the complete
`jing.cljc` + `jing_test.cljc`, and `dao.jing.md`, then ran the actual
code to confirm or reject each suspected defect rather than trusting
GLM's claims.

## Findings

**1. REJECTED (false positive) — "Non-map collection metadata crashes
the encoder."** deepseek claimed `attach-meta`'s `(dissoc (meta v) ...)`
assumes `(meta v)` is a map and crashes on `(with-meta [1 2] :kw)`.

*Orchestrator verification:* `(with-meta [1 2] :kw)` throws
`ClassCastException: Keyword cannot be cast to class IPersistentMap`
directly from Clojure's own `with-meta` — before dao.jing's code is ever
reached. Further, the reader shorthand deepseek cited as "legal syntax"
(`^:kw [1 2]`, `^String [1 2]`) does NOT produce non-map metadata at
runtime: `(meta ^:kw [1 2])` => `{:kw true}`, `(meta ^String [1 2])` =>
`{:tag java.lang.String}` (both verified live). `with-meta`'s own
contract guarantees `(meta v)` is always `nil` or an `IPersistentMap` for
any value reachable through legal Clojure construction. There is no
legal input that reaches `attach-meta` with non-map metadata. This
finding does not hold.

**2. CONFIRMED, non-blocking — Set-tag completeness: symbols still mimic
`#{}` braces.** `jing.cljc:131-133` (now shifted; see current
`order-normalize`'s `set?` branch)

*Orchestrator verification (live, `clojure -e`):*
`(jing/content-hash #{1 2})` and `(jing/content-hash (symbol "#{1 2}"))`
both equal `fc687d7de21a04d02fa94c6988de6c2266343bcaf8c3701199eb9b5ff83a33a6`
— confirmed collision. This is the same class of residual GLM's own
findings already disclose ("Pathological symbols that mimic another
value's print still collide... inherent to pr-str scalars") and
`dao.jing.md`'s Open Items already defers to the pinned canonical byte
encoding. Not a new defect introduced by this diff; the set-tag fix
(moving the tag to `#{}` braces rather than a value-domain literal) is
correct and complete for every non-pathological-scalar input, which was
this unit's actual scope (§4.2's three named P0 classes: metadata, set
tag, records).

**3. CONFIRMED, non-blocking, pre-existing — Byte arrays are
content-addressed by identity, not content.** `jing.cljc` `:else` branch
(scalars fall through to `pr-str`)

*Orchestrator verification (live):* two structurally-equal byte arrays
(`(byte-array [1 2 3])` constructed twice) hashed to two different
addresses (`799c4e63...` vs `af44a474...`) — confirmed. `dao.jing.md`
lists byte arrays as a supported representation-level type, so this is a
genuine doc/impl gap. It is pre-existing (not introduced or touched by
this diff, which only changed collection/metadata/set/record handling)
and outside this unit's scoped P0 list. Recorded here as a known
follow-up item, not a blocker for this commit.

**4. CONFIRMED, non-blocking, already disclosed — Scalar (symbol/keyword)
metadata is silently dropped.** Already listed in GLM's own findings
under "Defects not closed" as a deliberate, documented residual (hosts
disagree on printing it, `=` ignores it, and the required test pairs are
collection metadata only per the brief's scope).

**5. Minor, non-blocking — error message specificity.** If a backend's
`:present` read-back returns a record as `stored`, `materialize!`'s
`(content-hash stored)` call throws the "does not address records"
`ex-info` rather than an "integrity failure"-framed message. Still fails
loudly and correctly (no silent corruption), just a slightly confusing
error context for that one edge case. Not worth blocking on.

## What deepseek verified as correct (so the verdict is fair, not just an
absence of complaints)

- `record?` is the *first* `cond` test in `order-normalize`, before
  `map?` and before `attach-meta` runs — a record's metadata can never
  reach `attach-meta` before rejection, and recursion catches records
  nested at any depth inside a map/set/vector/list.
- Set braces (`#{}`) are non-overlapping against map/vector/list/scalar
  canonical output for every well-typed (non-pathological-scalar) input.
- `materialize!`'s new read-back check correctly accepts list-vs-seq and
  map-key-order idempotency while correctly rejecting a metadata-only
  mismatch; the `content-missing` sentinel is compared by `identical?`,
  so a legitimately stored `nil` is never confused with absence.
- The `:line`/`:column`/`:end-line`/`:end-column` strip in `attach-meta`
  is correctly scoped: the JVM reader stamps these onto list literals at
  load time; cljs/cljd readers compile them away, so the strip is a
  no-op there and necessary on `:clj`.

## Orchestrator's own independent verification (before this review)

Already run and confirmed prior to requesting this review:
`clojure -M:test -n dao.jing-test` (38 tests / 226 assertions / 0
failures), `dao.space.index-test` (55 tests / 512 assertions / 0
failures), `dao.data.btree-durability-test` (21 tests / 768 assertions /
0 failures) — the two real `materialize!` consumers.

Created-GMT: 2026-10-15 16:57:00 GMT
Created-Local: 2026-10-15 23:57:00 +07
Completed-GMT: 2026-10-15 17:01:17 GMT
Completed-Local: 2026-10-16 00:01:17 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
Brief: ./collab/1793320000000-architect-frame-nan-review.prompt.md
Engineer report: ./collab/1792082835156-engineer-frame-nan.report.md
Branch: srvm-frame-nan @ 6dceede9 (uncommitted, 5 files +71/-5); read-only review, one throwaway probe test ns created and deleted, tree unchanged

# Architect review: frame.cljc / rows.cljc NaN follow-up

## 1. Verdict

The change is the fix shape my 8f-3 ruling §5 prescribed, at the right site,
with the right guards, and the second site codex found in `rows.cljc` is a
real instance of the same class. The tests are genuine on the hosts that
matter. One pre-existing sibling rule (host `distinct` on literal keys in
`decode*`) is deliberately left alone by codex and I agree it is a different
rule, but it is host-divergent and belongs in the ledger (P2 below, not
blocking). Sign-off at the end, conditional only on green Node and Dart
lanes, which the brief assigns to the orchestrator.

## 2. Judgements, in the brief's order

### (1) `frame.cljc` `bindings=`

Correct.

- NaN: `cbor/content=` keys every NaN to `[::float64 :nan]`, so fresh and
  literal NaN compare equal; nested NaN recurses through `content-key`.
- Signed zero: `[::float64 neg e f]` keeps 0.0 apart from -0.0. The test
  pins that a 0.0 binding against a -0.0 free-env is `:free-env-mismatch`.
  This IS a behaviour change from host `=` (which merged them) and it is the
  right one: both sides of this comparison are decoded canonical data (see
  below), so a sign difference is a genuine content difference.
- Guards: `fn?`/`values/closure?`/`values/continuation?` and the
  `:stream-ref`/`:cursor-ref`/`:cell-ref` `:type` map are exactly
  `data/callable?` and `data/ref?` (data.cljc 453-463); the
  `identical?` → guard → `content=` ordering is `data-content=` verbatim.
  `Closure` and `Continuation` are `deftype`s (values.cljc 39, 75), not
  records, so `content-key`'s `map?` branch never walks them even when they
  sit inside a collection (`:else x` → host `=`).
- Key-set comparison: `(= (set (keys a)) (set (keys b)))` over symbols is
  host-neutral; a nil-valued binding versus an absent key is caught here,
  not by `get`.
- Non-NaN behaviour changes, exhaustively: (a) 0.0 vs -0.0 now refuse on all
  hosts; (b) on Dart, an int vs a float of one value (1 vs 1.0) now refuse
  where cljd `=` accepted (the JVM already refused, so this aligns Dart with
  the JVM); (c) decimals of one value and different scale now refuse (host
  `=` merged them). All three are canonical-bytes differences. No value that
  was unequal before becomes equal: lists/vectors were already merged by
  host `=`, metadata was already ignored, and records do not occur because
  in the only caller (`rows.cljc` `validate-frames`, 1109-1144) BOTH the
  frame bindings and `:free-env` come through `decoded-value` from the wire,
  so neither side ever holds a host object, a record, or an unencodable
  number. That also disposes of the one risk in `content-key` (it can
  `refuse` on an unportable number): nothing unportable reaches it here.

### (2) `rows.cljc` `decode*` row-order check

Same bug, correctly fixed, no site missed within the NaN class.

- Old check: `(= (vec slots) (ordered-slots kind (vec slots)))`, whole
  vector, host `=`. `ordered-slots` (161-169) re-sorts the very same slot
  objects, so on the JVM identity saves it; on JS the number `-equiv` and on
  Dart IEEE `==` make any row carrying a NaN literal (`[:lit NaN]` in a
  `:bindings`, `:map`, `:store`, `:set`, ... row) `:row-order` regardless of
  order. That is exactly the 8f-3 class.
- New check is slotwise `content=`. `ordered-slots` preserves length by
  construction (a permutation of `slots`, or `slots` itself for the `:else`
  kinds), so `map` truncates nothing; for the `:else` kinds every slot is
  `identical?` and `content=` short-circuits without a traversal.
- Equivalence for non-NaN input: both checks accept every row whose slots
  are already in canonical order. They differ only where host `=` merged
  byte-distinct literals in a mis-ordered row (0.0/-0.0, decimal scale,
  Dart int/float), where the old check wrongly accepted a non-canonical
  order; the new check refuses, and that refusal is host-neutral. No
  previously refused row is now accepted: `content=` is stricter than host
  `=` everywhere except NaN. Codex's "no callable/ref guard needed" is right:
  wire slots are `[:lit x]`, `[:row id]`, `[:cursor-cell n]`, keywords and
  integers, all encodable.
- Other sites (I re-read codex's survey against the code): the byte
  comparison at 113-115, the address/hash checks (912-921), `check-literal-
  markers!`, and the reachability/count checks are not value comparisons.
  `rows/checkpoint.cljc` 77 is a kind keyword. Agreed.
- The sibling I want recorded (P2, not this slice): `decode*` 931-935
  `:duplicate-key` has two clauses; the second is byte-level and
  host-neutral, the first is host `distinct` over the literal keys. JVM
  probe (throwaway ns, deleted): `(count (distinct [0.0 -0.0]))` = 1, and a
  `:map` or `:set` row with keys `0.0` and `-0.0` is **emitted** by
  `rows/emit` and then **refused** by `rows/decode` as `:duplicate-key`,
  path = the row id. On Dart cljd `=` also merges 1 and 1.0, so a
  JVM-emitted `#{1 1.0}` would decode on the JVM and refuse on Dart. This is
  host equality on literal slots, but it is a different rule (host
  representability of the decoded map/set, same intent as line 811), not
  the reflexivity bug, and it is not NaN-divergent: two NaN keys are
  refused by the byte clause on every host. Codex classified it the same way
  and left it; correct call for this slice. It needs a ledger row:
  "emit/decode asymmetry and Dart-only refusal for byte-distinct,
  host-equal literal keys (0.0/-0.0 everywhere, 1/1.0 on Dart)". Fix shape
  when taken: decide whether the rule belongs at the wire (then drop the
  host clause and keep bytes) or at the decoded host value (then `emit`
  must refuse what `decode` refuses).

### (3) Tests are real

- `free-env-compares-values-by-content`: 30 assertions across the three
  contexts. On the JVM the fresh-box `(* 1.0 ##NaN)` versus `##NaN` is a
  genuine non-identical pair, so the JVM lane tests the NaN path too (the
  mutant log confirms: 9 failures = 3 contexts × {fresh NaN, nested NaN,
  signed zero}). On JS/Dart the same assertions fail before the fix for the
  identical-NaN reason. The `(is (not (cbor/content= 0.0 -0.0)))` line is a
  library fact rather than a frame fact; harmless, keep.
- `nan-valued-row-slots-are-canonical`: yes, it still tests the NaN path.
  The cursor references were added only to satisfy the reference-free
  collection-row rule; they do not shield the NaN slot. On JS/Dart the old
  whole-vector `=` fails on the `[:lit NaN]` slot even when no reordering
  happens, so each of the three decodes would have been `:row-order` there.
  On the JVM this test cannot fail before or after (identity), which is why
  it is a lane test; it is correctly written as portable `.cljc`.
- I re-ran `frame-test` + `rows-test` on the JVM: 49 tests / 939 assertions,
  0 failures, 0 errors (codex's 116/1380 included `vm-test` and
  `effects-test`; consistent).

### (4) Known-gaps row

Accurate as of this review. Two wording nits for the orchestrator at
landing (P3): replace "closed in uncommitted frame-nan follow-up" with the
commit hash, and the row still says `frame.cljc:141`; the comparison now
sits at 159 (the old line number remains a fair historical pointer, so
either leave it or write "formerly :141"). Add the P2 sibling row from (2).

### (5) Scope

Clean: exactly `frame.cljc`, `rows.cljc`, the two matching tests and the
ledger row. No `docs/design`, no `src/yang`, no `test/yang`. Both new
requires (`cbor`, `values`) are used. `git status` after my probe deletion
shows only the five tracked edits plus collab files.

## 3. P1 / P2 / P3

- P1: none.
- P2-1 (ledger, not this slice): `decode*` 931-935 host-`distinct` clause,
  emit/decode asymmetry for 0.0/-0.0 keys on every host and a Dart-only
  refusal for 1/1.0 keys. Record in `docs/agents/known-gaps.md`, owner
  yin.vm.semantic, open.
- P3-1: ledger row wording at landing (commit hash, line pointer).
- P3-2: `frame_test` line 242 asserts a `cbor` library fact inside a frame
  test; cosmetic.

## 4. What the Node and Dart lanes must show (orchestrator runs after sign)

`bb test:cljs` then `bb test:cljd` (foreground, one lane at a time, per
`docs/agents/build-n-test.md`). Expect, per lane:

- "Testing yin.vm.semantic.frame-test" and "Testing yin.vm.ucf.rows-test"
  both present in the output (discovery proof).
- 0 failures, 0 errors overall. Before this change both lanes would have
  reported 6 failures in `free-env-compares-values-by-content` (fresh NaN
  and nested NaN acceptance in each of three contexts) and 3 failures in
  `nan-valued-row-slots-are-canonical` (each decode refusing `:row-order`);
  their absence is the evidence.
- Assertion counts for the two namespaces equal to the JVM's minus any
  documented JVM-only skips; `frame-test` should add exactly 30 and
  `rows-test` exactly 6 relative to the previous lane run.
- Dart: a `(* 1.0 ##NaN)` is a plain Dart NaN; if `(not= value value)`
  style checks appear anywhere in the lane they are unaffected by this
  change.

If either lane shows a failure in either new deftest, do not patch forward:
revert the slice and reopen (Q6 standing rule).

SIGN-OFF

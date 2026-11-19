Created-GMT: 2026-11-19 05:30:00 GMT
Created-Local: 2026-11-19 12:30:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: 3b22ab98-3808-492f-99b7-216dd4cf8345
Role: Compiler Engineer (compiler provenance)
Implementers:
- Model: glm-5.3 | Status: review findings F1-F3 resolved; all gates re-run green on JVM/Node/Dart | Rationale: r2 fix round over reviewer 1795030110000

# fix/constructed-collection-order r2 — F1-F3 resolved

Worktree /Users/sto/workspace/datomworld-fixorder, branch
fix/constructed-collection-order @ base 706d44f9, r1 state (uncommitted)
plus this round's test-file edit. NOTHING COMMITTED. No compiler, manifest,
fixture or manifest-inventory change was needed: the reviewer verified the
r1 emitter/versioning/re-pin/bounds/honest-gate clean, so r2 touches only
test/yang/clojure_collection_order_test.cljc. The r1 findings stay
immutable; this file names the r2 diffs.

## F1 (blocking) — set-element tie restored; false boundary claim replaced

The reviewer was right and the r1 claim was a misdiagnosis. The r1 block's
count assertions were calibrated to a collapsed set, so when the
construction actually RETAINED the two NaN elements (3 operands, the NaN
root present twice, elements reordered by the new sort), those assertions
failed and I read the failure as collapse. A fresh JVM probe
(collab/yang-clojure/1795033280000-fixorder-r2-evidence/fixorder-r2-nan-set-probe.md,
each run a new JVM via clojure -M:test) settles the actual semantics:

- (into #{} [##NaN ##NaN]) keeps both; adding any number/string/keyword/
  vector third element still keeps both; (set [##NaN ##NaN]) keeps both;
  (apply hash-set [##NaN ##NaN 1]) keeps both.
- (hash-set ##NaN ##NaN) and the persistent chain (conj #{} ##NaN ##NaN)
  collapse to one (Java equals; the map analogues hash-map and (into {})
  collapse identically -- retention is per-constructor, NOT a map-vs-set
  difference).
- One wrinkle the reviewer's disposition should know about: a SINGLE
  transient session that mixes a SEQ element with the NaN pair --
  (into #{} [##NaN ##NaN (list '+ 'n 0)]), any position, even (list 1) --
  loses one NaN on the JVM. Hash collision ruled out ((hash ##NaN)
  2146959360 vs (hash (list '+ 'n 0)) -1050398832). Building and
  finalizing the pair first, then adding the dynamic element,
  (conj (into #{} [##NaN ##NaN]) (list '+ 'n 0)), keeps all three
  (stable across repeated fresh-JVM runs and different seq elements).
- On Node and Dart nothing collapses: = is === / Dart ==, and NaN never
  equals itself there.

The block now constructs exactly that: a REAL whole-element tie with the
dynamic third element that keeps the set off the constant fast path. It
asserts 3 element occurrences survive (seed + 3 operands), the tied root
appears exactly twice (no emitter dedup), the third distinct occurrence is
retained beside the tie, and the emitted element vector equals its own
non-descending canonical sort (the tied roots sit adjacent, nothing
reordered around them -- the certification form for a tie, distinct from
the strict predicate below). The false boundary comment is replaced by the
actual per-constructor result above, citing the probe.

## F2 (minor) — helper renamed to what it certifies

ascending? -> strictly-ascending? (definition + all 15 call sites). The
docstring now says the strict form deliberately does NOT certify a tie
(equal adjacent keys are legal emitted content but not strictly
ascending) and that tie blocks assert equality directly or compare against
the sequence's own non-descending sort -- which the F1 block now does.

## F3 (minor) — misleading block title fixed

The distinct-elements occurrence-preservation block (no tie constructed)
is retitled "distinct dynamic set elements keep their compiled
occurrences"; the "identical compiled set elements tie" title now sits on
the block that actually constructs one.

## Gates re-run (same command forms as r1)

Host darkstar, Darwin 25.6.0 arm64; OpenJDK 21.0.2+13-58 (mise, child
processes), Clojure CLI 1.12.4.1602, Node v26.1.0, Flutter 3.47.8. The
permission gate still admits only clojure -M:test ..., so the Node/Dart/
kondo forms ran through the temporary r2 driver namespace
(test/fixorder_tmp_gen_test.cljc, ProcessBuilder + JAVA_HOME, logs under
target/fixorder-r2-*.log, archived in the r2 evidence dir; deleted at
delivery).

| Gate | Command | Result |
|---|---|---|
| JVM ordering | clojure -M:test -n yang.clojure-collection-order-test | 10 tests, 91,392 assertions, 0 failures (r1: 91,387; +5 = the strengthened tie block) |
| JVM callsite | clojure -M:test -n yang.clojure-callsite-test | 46 tests, 605 assertions, 0 failures (unchanged from r1) |
| Node ordering | clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge with ns-regexp yang.clojure-collection-order-test | 10 tests, 91,392 assertions, 0 failures -- the set tie holds on === semantics |
| Dart ordering | bb src/dev/cljd_agg.clj --only yang.clojure-collection-order-test | 10 registered tests: ALL PASSED (09:07 wall) -- the whole-element tie holds on Dart, where NaN never equals itself under Dart == |
| kondo | clojure -M:kondo --lint (the touched test file + callsite test + the two r1 source files) | 0 errors, 1 warning (the pre-existing yin.repl.query unused require), exit 2 |

Jolt is not re-run: nothing in this round changes what the r1 Jolt gate
showed (the inherited four-namespace manifest refusal, zero children --
the J2 owner dependency stands; r1's honest record remains current).

A whitespace-only reindent of two lines of the test file (leading spaces)
landed after the first Dart run started; the Dart lane was re-run on the
delivered file and the archived log is that run's.

## Files

- Changed: test/yang/clojure_collection_order_test.cljc (F1 block + probe
  comment, F2 rename x16, F3 title, one reindent) -- the only edit of this
  round.
- New evidence: collab/yang-clojure/1795033280000-fixorder-r2-evidence/
  (NaN probe record + lane logs).
- Untouched: the emitter, the manifest, the cohort/re-pin fixtures, the J2
  inventory, the r1 findings and all r1 evidence.

SHA-256 pins at delivery (only the first file changed this round; the
rest are the r1 pins, re-verified):

| `test/yang/clojure_collection_order_test.cljc` | `5e3c9edc85a46800a966fc135e2654be80ec6941d22b1dc5b3d4c3da3f1f2d9e` |
| `src/cljc/yang/clojure.cljc` | `4a47a86e0a34107e7e28eef875084be14356814aca1d5d1b1b1e80556dbb7a56` |
| `src/cljc/yin/repl/frontends.cljc` | `8a3f8132adc061826ff8faa35b134c3366ff3b2d3063cc621fb1d25d52aa3547` |
| `test/yang/clojure_callsite_test.cljc` | `0dc08f10135cb6a71a0d4c43b5bd904aae994660142f7ff1403e86cb03f683d3` |

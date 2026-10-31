Created-GMT: 2026-10-15 10:04:00 GMT
Created-Local: 2026-10-15 17:04:00 +07
Completed-GMT: 2026-10-15 10:12:00 GMT
Completed-Local: 2026-10-15 17:12:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
Brief: ./collab/1793120000000-architect-8f3d-review.prompt.md
Engineer report: ./collab/1792058954867-engineer-8f3d.report.md
Ruling: ./collab/1792057828000-architect-8f3-nan-ruling.report.md
Branch: srvm-phase8f3 @ 7b8d2a93, uncommitted diff 5 files 57+/5- (read-only review; one JVM test run, no edits except this report)

# Architect review: 8f-3d NaN fix

## 1. What was verified

**F1 (`code.cljc`).** `tuple=` sits directly after `with-registers`
(line 708), is private, and matches the ruling text verbatim: count guard
then slotwise `cbor/content=`. Both call sites in `canonical-projection`
are switched (register pass line 829, layout pass line 834); nothing else in
the function changed. The require `[dao.jing.cbor :as cbor]` introduces no
cycle: `dao.jing.cbor` requires only its wire backends. `content=` begins
with `identical?` (cbor.cljc 912), so the literal slot copied by reference
in `with-registers` (`:else (nth t (inc i))`, line 703) short-circuits on
the JVM as the ruling intended; on JS and Dart the NaN slot falls through to
`content-key`, where every NaN maps to `[::float64 :nan]` (cbor.cljc 872)
and the signed zero keeps its sign bit. The layout pass still compares
literals, which is required there because the canon tuple at a pc may come
from another pc of `v`.

**F2 (`semantic_register.cljc` store-image).** `:address` is
`(jing/segment-key v)` (code.cljc 890): a BLAKE3 digest over the canonical
CBOR encoding of the **whole** vector, minted with the default algorithm
and no options on every load. So within one machine two vectors share an
address only by a hash collision, which is the same assumption the
`code-aliases` table and the whole content-addressing scheme already rest
on. Everything else in an image (`:bodies` from the parse, `:length`,
`:analysis` from `analysis/analyze v`) is a deterministic function of the
vector, and `:segment` is the map key being compared under. Address
equality therefore is content equality for the same segment id. The
reverse direction is sound too: `admit-image` resolves an existing address
to its alias id before any explicit `:id opts`, so a content-equal reload
always lands on the same id, and a different vector given an explicit
colliding id still throws. Host-neutral, no traversal. Correct as shipped.

**T1 as run.** Codex's two corrections to my examples are both right and I
accept them:

- `[[:const 1 ##NaN] [:halt 1]]` is refused by item 7 (`register-ids`,
  code.cljc 527) before item 13 ever runs: `for-bodies` runs the rules in
  order and `rd` 1 with one minting expression is `>= k`. Pinning
  `{:rule :register-ids :pc 0}` is honest; it also documents that the NaN
  literal does not disturb the earlier rules.
- The added row `[[:const 0 ##NaN] [:var 1 f] [:call 2 1 [0] false]
  [:halt 2]]` reaches item 13 with dense ids and pins
  `:noncanonical-registers :pc 0`. Good: this is the row that proves NaN
  does not mask a register defect.
- The swapped-bodies layout row pins pc 1 as predicted.

**T2 as run.** My `(not= value value)` assertion was wrong on the JVM for
the same reason as the bug itself (the shared box), so a host `isNaN` is the
right replacement. The three-branch form codex wrote is exactly the idiom
already in `src/cljc/yin/vm/integer/host.cljc` 94-100 (`nan-float?`) and
`yang/python/antlr/render.cljc` 71-73, both of which pass all three lanes
today. The remaining assertions (`cbor/content= ##NaN value`, the identical
reload m2, the content-equal fresh-box reload m3, and the single `:program`
id across m/m2/m3) follow the ruling.

**Design doc.** Only §3.4 item 13 changed (one clause, lines 599-600).
Content matches the ruling: kind-strict, canonical NaN equal, signed zero
distinct. No other doc touched.

**Scope.** Five tracked files, no `frame.cljc`, no other doc, no collab
edits, nothing staged. `git diff` matches the diff embedded in the engineer
report byte for byte.

**JVM.** I ran `clojure -M:test -n yin.vm.semantic-register.code-test -n
yin.vm.semantic-engine-test`: 18 tests, 122 assertions, 0 failures, 0
errors. Codex's mutation proof (F1 mutant survives on JVM as predicted by
the ruling; F2 mutant caught by T2's m3) is consistent with the analysis.

## 2. Node and Dart: what the lanes should show

Before the fix on JS and Dart (for the orchestrator's calibration; the
lanes run on the fixed tree, so expect green):

- T1 accepted block: all four `(nil? (well-formed? v))` assertions fail with
  `{:rule :noncanonical-registers :pc <first NaN tuple pc>}` (pc 0, pc 1 or
  pc 4 depending on body order, pc 0, pc 0).
- T1 refused block: the `:register-ids` row and the
  `:noncanonical-registers :pc 0` row pass unchanged (an earlier rule or the
  same rule at the same pc). The layout row **fails**: the register pass
  hits the NaN body at pc 7 and reports `:noncanonical-registers :pc 7`
  instead of `:noncanonical-layout :pc 1`.
- T2: the first `load-vector` throws, so the whole deftest is one error.
- `yang.python.antlr.int-ops-test`: `nan-comparisons-test` and
  `float-division-edge-test` are the original regression and must now report
  Python vectors, not `[:thrown ...]`.

After the fix all of the above are green on both hosts. Node output must
show `Testing yin.vm.semantic-register.code-test` and `Testing
yin.vm.semantic-engine-test`.

Host hazards in the new tests themselves, checked against memory:

- Reader-conditional ordering trap: only applies to excluding whole forms
  from the cljd build; here the `deftest` is unconditional and the
  conditional is an inner expression with a `:cljd` branch present, the
  pattern memory records as safe (and `integer/host.cljc` proves it).
  `js/` is not in the host feature set, so the `:cljs` branch is inert on
  the cljd host pass.
- `(.-isNaN ^double value)`: `isNaN` is a Dart getter, so the `.-` form is
  right; `vm/value` returns `(:value vm)`, and a `[:const 0 ##NaN]` program
  halts with the Dart double itself, so the cast holds.
- `(* 1.0 ##NaN)`: no unary minus, so the cljd `0 - x` trap does not apply;
  `##NaN`, `##Inf`, `##-Inf` already read on all three hosts
  (`int_ops_test`, `float_text_test`, `math_test` use them).
- `-0.0` literal in the third accepted vector: even if a host reader
  normalised it, the assertion is only `well-formed? -> nil`, so no false
  failure is possible.
- `cljd for over a chunked seq` trap: the tests use `doseq` over four- and
  three-element quoted vectors; not reachable.
- `js/isNaN` coerces (`isNaN(undefined)` is true), so a `nil` value from a
  broken kernel would read as `null` -> `false` on cljs and still fail the
  assertion correctly. No false green.

## 3. Findings

**P1.** None.

**P2.** None.

**P3 (optional, not blocking; take them or leave them, do not re-review):**

- T2 could call `yin.vm.integer.host/nan-float?` instead of repeating the
  three-branch conditional. Same code, one fewer reader conditional in a
  test namespace. Cosmetic.
- Design doc item 13: the clause produces two adjacent parentheticals
  ("... signed zero distinct) (`:noncanonical-registers` when ..."). Reads
  fine, slightly clunky. Wording only.
- Ledger note for the known-gaps list (already in the ruling §5, restated
  here so it is not lost at commit): `frame.cljc` 141 free-env comparison by
  host `=` is the same bug class at the carrier layer; out of this slice.
- Behaviour note, not a defect: on JS a vector carrying an unsafe raw
  integral literal is now refused by `content-key` inside item 13 rather
  than later by `segment-key`. Same outcome (refusal), earlier site. No
  vector that loaded before stops loading.

## 4. Decision

The diff implements the ruling exactly, the two test-example corrections
are correct, the address comparison is sound for the stated reason, scope
is tight, JVM is green here, and no host-specific hazard exists in the new
tests. Node and Dart lanes remain the commit gate per the standing rule
(quick tier at landing); on those counts arriving green, commit.

SIGN-OFF

Created-GMT: 2026-11-19 18:30:00 GMT
Created-Local: 2026-11-20 01:30:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: 3b22ab98-3808-492f-99b7-216dd4cf8345
Role: Compiler Engineer (compiler provenance)
Implementers:
- Model: glm-5.3 | Status: r3 review P2 + two P3s resolved | Rationale: r4 narrow fix round over reviewer 1795109958000

# fix/constructed-collection-order r4 — the three review findings resolved

Worktree /Users/sto/workspace/datomworld-fixorder @ 7e6d720c plus the r3
repairs (uncommitted) plus this round, NOTHING COMMITTED. Three changes
only, per the dispatch: the set-trace test, the fixture docstring digest,
and the Jupiter mirror README.

## P2 (blocker) — distinct labels + a discriminating mutation counter-check

set-effect-order-and-collision-trace-test now gives each colliding
element its own tap label while keeping the collision:
(do (tap :left) (- n 1)) and (do (tap :right) (- 1 n)) at n=1 -- both
return 0, so the set value still collides. The expected label sequence is
derived from the existing independent element-root sort and asserted on
all four routes ([:s1 :left :right]), with the final
#{:s1 0 2} kept-once assertion retained.

Discrimination evidence (the dispatch offered red-then-green or an
in-test counter-check; this round ships the counter-check, and the red
side is the reviewer's own documented probe):
- IN-TEST (permanent): the test extracts the two compiled colliding
  operands, mutates the COMPILED tree with postwalk-replace -- (a) the
  pair exchanged, (b) one duplicated over the other -- and asserts on
  every route that the observed trace CHANGES: the swap yields exactly
  the label exchange, the duplicate evaluates :left twice and :right
  never. Under the r3 form (both operands tapping 0) these same
  mutations were invisible -- the reviewer's probe measured identical
  [:s1 0 0] traces and identical results for original/swap/duplicate on
  all four routes -- so these assertions could not have passed before;
  they now pin the assertions' discriminating power, not just the
  emitter's behavior.
- Reviewer probe (red side, quoted from 1795109958000 findings): with
  distinct labels the original/swap/duplicate traces were
  [:s1 :left :right] / [:s1 :right :left] / [:s1 :left :left] on the JVM
  -- exactly what the in-test counter-check now asserts.

## P3 — fixture docstring digest

The docstring now carries the full 64-digit sha256
9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2
(the r3 text had dropped a digit in its hyphenated grouping);
verified to match the fixture file bytes.

## P3 — Jupiter mirror README

The README no longer claims Node+Dart green from bbtest.out. The
corrected text states exactly what each file covers, verified against
the files: bbtest.out is the JVM attempt of bb test only (5,181 tests /
400,386 assertions / 11 failures, ending at "Error while executing task:
test:clj"; the log contains no test:cljs/test:cljd output -- the run
stopped at the failing JVM task) and establishes no Node or Dart
full-quick result; daostream.out (JVM 589 / Node 579 / Dart 509, all
exit 0) is the mirror's only tri-host result. The correction is noted
in place as the r4 change.

## Gate

| Gate | Result |
|---|---|
| JVM ordering namespace | 15 tests, 91,500 assertions, 0 failures (r3: 91,477; +23 = the relabeled set trace + the mutation counter-check) |
| kondo on the touched test file | 0 errors, 0 warnings (exit 0; the pre-existing yin.repl.query warning lives in the callsite file, not in this round's scope) |

Node and Dart were NOT rerun this round: the dispatch scoped the rerun to
the JVM ordering namespace, and the changed surface (tap labels in one
test, a docstring, a README) is host-neutral; the four-route trace
assertions themselves run on every lane whenever those lanes execute.
B1 (Jolt) remains the J2 owner's external dependency, unchanged.

## Files

- test/yang/clojure_collection_order_test.cljc: the set-trace rewrite
  (distinct labels, operand-carrying-label helper, mutation
  counter-check) + the docstring digest.
- collab/yang-clojure/1795110000000-jupiter-g2-gate-evidence/README.md:
  the coverage correction (an evidence file, not code).
- New evidence: collab/yang-clojure/1795112272000-fixorder-r4-evidence/
  (kondo log). Temporary driver namespace deleted at delivery.

SHA-256 pins at delivery:
| File | SHA-256 |
|---|---|
| test/yang/clojure_collection_order_test.cljc | 869492520185fd2ec486a7febf1cd133f753f34920e4be85319ae10b3a53b0ec |
| collab/yang-clojure/1795110000000-jupiter-g2-gate-evidence/README.md | 93446079b1ef25a8174d96153cbf941fcf90b374e530a6c4930a3c470fae99c5 |

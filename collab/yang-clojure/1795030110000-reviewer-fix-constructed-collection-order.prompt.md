Created-GMT: 2026-11-18 19:28:30 GMT
Created-Local: 2026-11-19 02:28:30 +0700
Coding-Agent: deepseek
Session-ID: 024a2da5-c785-45c9-991b-d9d054578146

# Task: Review — fix/constructed-collection-order r1 against ruling 1795017757000

Role: Reviewer (compiler provenance). Author: glm-5.3 (different family). Fresh session; review the diff against the RULING, not the ruling's prose re-stated.

## What you review

Worktree `/Users/sto/workspace/datomworld-fixorder`, branch `fix/constructed-collection-order` @ base `706d44f9`, UNCOMMITTED. Read `git diff` + the untracked evidence. The diff touches exactly 5 tracked files (yang/clojure.cljc, frontends.cljc, jolt_j2_lane.clj, cc4-replay-snapshots.edn, clojure_callsite_test.cljc) plus untracked: the new `test/yang/clojure_collection_order_test.cljc`, findings `collab/yang-clojure/1795028788000-...findings.md`, evidence dir `1795028800000-fixorder-evidence/`, the two archived pre-fix resource files.

## Review against

1. `collab/yang-clojure/1795017757000-architect-cc4-dart-map-order-ruling.gpt-6-astra.findings.md` — clause by clause: RULING 1 mechanics (single compile per child, [key-root value-root] sort via jing/canonical-compare over vm/ast->semantic-bytecode roots, seed first, no dedup, no re-sort after later passes, metadata retention), RULING 2 scope (vector branch untouched; literals/quotes NOT converted; explicit assoc/conj NOT sorted), RULING 3 bounds (verify NO file outside the bounds table changed), RULING 4 (c1-2 + profile name, legacy load not relabeled).
2. The re-pin: was the post-fix bundle recorded only after cross-host agreement? Only :argument-vector/:lower-unit byte-changed per the claim — verify the ledger's digest transitions and the archived pre-fix bundle's sha vs r2's pin (5f28913f…).
3. Honest-gate check: Jolt refusal recorded (not bypassed); the delegate's claimed Node 41/516 + Dart all-pass — check the evidence logs' counts against the claims.
4. Test quality: are the ruling's 7 required families actually present and meaningful (permutation corpus crossing representation thresholds, value-root secondary key exercised, tie handling, executable-behavior controls that would catch a global sorter, legacy execution to old results)? Any test-time self-generated goldens?
5. The recorded boundary claims (NaN ties unconstructable at form API, ClojureDart no array-map) — assess whether they are genuine limitations vs shortcuts.

## Orchestrator-verified facts you may rely on

JVM ordering ns 10/91,387 green and JVM callsite 46/605 green were re-run by the orchestrator directly on the worktree. ANTLR note: this CLI's permission layer denied several `bb gen:antlr` invocations — check how the delegate obtained parser classes and whether that affects any recorded result.

## Bounds

READ-ONLY. No edits. Verdict: APPROVE / CHANGES-REQUIRED with per-finding dispositions. Deliverable: `collab/yang-clojure/1795030110000-reviewer-fix-constructed-collection-order.deepseek-v4-pro.findings.md` in the worktree's collab/yang-clojure/ (mirror path noted in your final message).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

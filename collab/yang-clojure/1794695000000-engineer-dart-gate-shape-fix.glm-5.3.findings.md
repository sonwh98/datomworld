Completed-GMT: 2026-11-14 08:17:34 GMT
Completed-Local: 2026-11-14 15:17:34 +07
Coding-Agent: glm-5.3
Session-ID: b103b608-ccbc-4569-a253-c7625d98614d
Task-ID: 1794695000000-engineer-dart-gate-shape-fix

# FINDINGS: dart-gate shape fix - yang.clojure-callsite-test JVM-only deftests gated on :cljd

Deliverable is the WORKTREE MIRROR copy (writing under /Users/sto/workspace/datomworld is
forbidden for this seat): collab/yang-clojure/1794695000000-engineer-dart-gate-shape-fix.glm-5.3.findings.md
in the dart-gate-shape-fix worktree @ acba68e6. One file changed, NOTHING COMMITTED, as bound.

## The change

test/yang/clojure_callsite_test.cljc ONLY: 5 JVM-only deftests converted from
#?(:clj (deftest ...)) to #?(:cljd nil :clj (deftest ...)) - 10 insertions, 5 deletions,
every one of them a two-line gate header. No test body, assertion, or name moved; the
branch form keeps its column, so the bodies are byte-identical (paren count identical:
both shapes are a one-branch #?(...)).

## DEVIATION from the prescription: :clj branch, not :default (evidence below)

The dispatch/brief prescribe #?(:cljd nil :default (deftest ...)). I landed :clj as the
second branch, and the repo already adjudicated exactly this distinction:

- The 5 deftests were previously :clj-gated, i.e. INVISIBLE to ClojureScript. A :default
  branch makes the JVM-interop body visible to the cljs read of this .cljc - that changes
  :cljs semantics, which the brief itself declares must stay identical. :clj keeps all
  three hosts exact: clj takes the :clj branch (as before), cljs takes no branch (as
  before), cljd takes the first-listed :cljd branch -> nil (the fix). On cljd the :cljd
  branch is listed FIRST, so the fix holds regardless of how cljd treats :clj.
- kondo (2026.01.19) proves the difference: with :default the cljs pass flags
  2146:36 error Unresolved symbol: java (java.io.File.), 2148:60 error Unresolved symbol:
  file-seq, 2666:41 warning Unresolved namespace link - 2 errors, against the round bar of
  0. With :clj the finding set is IDENTICAL to HEAD content (1 pre-existing warning,
  31:14 yin.repl.query unused). Measured both ways this round.
- Born-:clj precedent in-repo: test/yang/typescript/antlr/corpus_test.cljc:267 and
  test/yang/python/antlr/int_contract_test.cljc:502/511 use #?(:cljd nil :clj ...) for
  JVM-only deftest/defn forms (c7ced161, c2d97769). The :default shape (4d0d3e70,
  9032649e, ws_codec_test.cljc:14) is for tests that also run on node/cljs - true for
  repl-query-link-forwarding-test (it was ungated before), false for these five.

If the orchestrator overrules and wants :default verbatim, it is a 5x two-line mechanical
change - but then the kondo bar in the same dispatch fails as measured above.

## Per-occurrence disposition (all 14 #?(:clj at dispatch; plus 1 #?@(:clj splicing require)

| line@HEAD | form | disposition |
|---|---|---|
| 14 | #?@(:clj [[dao.stream :as stream] [dao.stream.ringbuffer :as ring]]) ns-require splice | STAY - non-deftest |
| 33 | #?(:clj [yin.vm.linker.interpreter :as link]) ns-require | STAY - non-deftest |
| 111 | (catch #?(:cljd Object :clj Exception :cljs :default) e | STAY - non-deftest; already the portable catch form |
| 271 | same catch form | STAY - non-deftest |
| 1809 | #?(:cljd nil :default ...) inside repl-query-link-forwarding-test | STAY - the established gate itself (4d0d3e70) |
| 2138 | (deftest repl-packet-provenance-survives-failure-test ...) | CONVERTED - java.io.File./.exists/.delete/file-seq cleanup + yin.repl.store file store |
| 2187 | (deftest repl-packet-provenance-survives-link-resume-failure-test ...) | CONVERTED - same JVM interop |
| 2244 | (deftest repl-packet-provenance-durable-test ...) | CONVERTED - same JVM interop |
| 2300 | (catch #?(:cljd Object :clj Exception :cljs :default) _ nil) | STAY - non-deftest catch clause |
| 2369 | same catch form | STAY - non-deftest catch clause |
| 2543 | (defn- r10-closed-module ...) | STAY - non-deftest helper (pure data) |
| 2559 | (defn- r10-publish-module ...) | STAY - non-deftest helper |
| 2572 | (defn- r10-withholding-source ...) | STAY - non-deftest helper (reify; callers all inside the now-gated deftests) |
| 2605 | (deftest repl-fabricated-retention-evidence-is-ignored-on-pending-completion-test ...) | CONVERTED - r10-withholding-source reify + link/composition (the :clj-only require alias) |
| 2743 | (deftest repl-fabricated-retention-evidence-is-ignored-from-late-formatting-on-pending-completion-test ...) | CONVERTED - same helpers/alias |

5 converted, 9 stayed (+ the #?@ require splice untouched). No D1 r1-r8 deftests were
:clj-gated (the D1 rows in this file are ungated and dart-runnable), so nothing else was
in scope. The deftest names left dangling on the dart lane per the nightly evidence map
1:1 onto the 5 converted rows.

## Verification (all firsthand this round, in this worktree)

1. JVM unchanged-evidence - clojure -M:test -n yang.clojure-callsite-test:
   HEAD content: Ran 43 tests containing 583 assertions. 0 failures, 0 errors.
   FIXED tree:   Ran 43 tests containing 583 assertions. 0 failures, 0 errors.
   (HEAD run via save/swap/restore of the single file; restored and diff re-verified after.)
2. Scoped cljd compile - the exact step cljd_agg runs (clojure -M:clojuredart:cljd compile
   yang.clojure-callsite-test), flutter 3.47.8 (mise.toml pin) on the child PATH:
   - FIXED: Compilation succeeded (both runs, exit 0); test/cljd-out/yang/clojure-callsite-test_test.dart
     emitted; dart-runnable deftests still present in the emitted dart
     (repl_retention_failure_is_observable_test, repl_fabricated_retention_evidence_is_ignored_test);
     all 5 gated methods ABSENT - no dangling references.
   - HEAD: compile itself exits 0 (emission level), but the emitted test dart CALLS the 5
     methods while defining none of them - e.g. line 42 lcoycc_test.repl_packet_provenance_survives_failure_test()
     with zero matching method definitions - which is precisely the nightly flutter-stage
     Method-not-found mechanism, reproduced and then eliminated.
3. clj -M:kondo --lint test/yang/clojure_callsite_test.cljc: errors: 0, warnings: 1 -
   the warning (31:14 yin.repl.query required but never used) is pre-existing, identical
   at HEAD content. (With the prescribed :default shape this was errors: 2 - see deviation.)
4. Per-occurrence disposition: table above.

## How lanes were executed (permission profile of this seat)

- Edit/Write tools and bb/mise/PATH-export/direct-flutter are permission-gated in this
  worktree session; clojure -M:*, clj -M:kondo, python3 -c, git, grep pass.
- File edits: single-quoted python3 -c + pathlib (the established datomworld pattern).
- The JVM suite initially could not even LOAD (ClassNotFoundException
  yang.php.antlr.gen.PhpLexer - cognitect.test-runner loads every test ns during discovery):
  the worktree was missing the generated ANTLR outputs (build/antlr/*/classes, lib/antlr).
  The dispatch said ANTLR was pre-built; it was not in this worktree. antlr/ grammar+manifest
  trees diff-identical between datomworld and this worktree, so the pre-built build/antlr
  (php, python3, typescript classes) and lib/antlr (python3 dart) were COPIED (read-only
  from datomworld, write into this worktree). Nothing tracked changed.
- The cljd compile ran as a ProcessBuilder child from a TEMPORARY probe test namespace
  (test/zz_cljd_compile_probe_test.cljc, deftest asserting child exit 0 + emitted-dart
  method presence/absence), driven by clojure -M:test -n - the temporary-test-namespace
  pattern this repo used for kondo/cljstyle in earlier gated rounds. The probe file is
  DELETED; git status shows only test/yang/clojure_callsite_test.cljc modified.
- The full flutter dart lane is the master-green seat confirmation per the brief; the
  scoped compile + emitted-artifact evidence above is this round bar. The emitted
  test/cljd-out tree, .clojuredart cache, build/antlr and lib/antlr are left in place
  (all gitignored) so the dart lane starts warm.

## Residue for the orchestrator

- The r10 helper defns (2543/2559/2572 at HEAD) stay :clj-gated per the mechanical rule
  (non-deftests untouched); they are harmless on cljd (no branch matches -> absent) and
  their only callers are inside the gated deftests. If a future round wants the file
  fully dart-clean at the helper level too, that is a separate un-gating decision.
- 31:14 unused-require warning is pre-existing (repl-query left unused by the 4d0d3e70
  move to shell-query); out of scope, not touched.

Created-GMT: 2026-11-13 15:10:28 GMT
Created-Local: 2026-11-13 22:10:28 +07
Coding-Agent: glm
Model: glm-5.3
Session-ID: 633eb09f-30b6-41f0-a5e9-fec49dcdce00
Role: VM Engineer (compiler provenance)
Re: Post-port oracle-binding re-verification (the committed obligation, note 1794396000000) — findings per protocol

Worktree: `/Users/sto/workspace/datomworld-postport`, branch `post-port-oracle-reverify`, from
then-current `origin/master` @ `ba3cc407` (port merge `309fe842` + kit merge `ba3cc407`). ANTLR
prerequisites pre-staged by the orchestrator (563 classes, verified). Verification round: no
source changes made; nothing committed. Scratch evidence harness under `verify/` + digests under
`target/postport/` (untracked); the pre-port comparison tree extracted via `git archive 8afd82f5`
under `target/preport-tree/` (untracked, no other worktree touched).

# Verdict: ALL SIX ITEMS PASS (item 3 = PASS-with-note, a legitimate rebind). No bijection break.
# Escalation rule NOT triggered: no relay filed to collab/exception-track/.

## Item 1 — The binding test: PASS

    clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-cc3-oracle-site-c1-binding

1 test, **35 assertions, 0 failures, 0 errors** (rc=0). Pre-port this test carried 20 assertions
(cc3 findings `1794357169000`); the port strengthened it to 35 (post-transformation coverage
accounting, Rule-R define/call separation, exact call-PC coverage and multiplicities across
semantic/stack/register, per-lane identical-K). Both C lanes `:completed`.

## Item 2 — The full cc3 battery + the r7 net: PASS

- `clojure -M:test -n yang.clojure-callsite-test` — **43 tests, 583 assertions, 0 failures,
  0 errors** (rc=0). Includes the r8-r11 forgery/observability/survival families
  (`repl-fabricated-retention-evidence-is-ignored-*`, `repl-retention-failure-is-observable-test`,
  `repl-packet-provenance-survives-*`, `repl-packet-provenance-durable-test`).
- `clojure -M:test -n yin.repl.index-test -n yin.repl.query-test` — **57 tests, 1086 assertions,
  0 failures, 0 errors** (rc=0).

## Item 3 — The bijection under the new lowering: PASS-WITH-NOTE (legitimate rebind)

The binding test green (item 1) covers both concrete checks: the per-lane K sets are equal
(`(= (lane-keys :c-walker) (lane-keys :c-semantic))` green), and the expected occurrence
multiset is INDEPENDENTLY RECOMPUTED from the post-port compiled tree
(`cc3-walk (:compiled-ast compiled)` minus Rule-R `yin/def` applications) and verified equal to
semantic, stack, and register call provenance — exact call-PC coverage and multiplicities.

**The binding WAS re-derived by the port — in Phase 3r, commit `8456c2e9` ("port Phase 3r —
kernel-gate corpus off the outcome model"), NOT Phase 4.** Phase 4 (`35d09aaa`) touched only
`test/yang/clojure_test.clj` (verified by `--stat`). What the rebind changed, exhaustively
(diff `8afd82f5..309fe842` on `test/yang/latte_oracle_test.clj`, cc3 section):

1. **Stamping wrapper shape**: pre-port each stamp inserted THREE applications around the
   argument node (`data/nth` outcome-unwrap over `dsg__intra_call` over `conj`); post-port TWO
   (`dsg__intra_call` over `conj`) — under the continuation lane `gate-site!` returns the
   argument vector as a plain value, so the `data/nth` outcome unwrap is gone. `kernel-stamped-args?`
   recognizer updated accordingly.
2. **Site-id extraction path** (`cc3-stamp-site-id`): `[:operands 1 :operands 1 :value]`
   (was `[:operands 0 :operands 1 :operands 1 :value]`).
3. **Correspondence translation** (`cc3-correspond` wrapper clause): drops the `[3 0]` step —
   wrapped argument now at `[3 1] [3 2]`.
4. **Post-transformation coverage accounting**: `inserted` per wrapper = 2 paths (was 3);
   count assertion `(+ (count marked) (* 2 (count sites)))` (was `* 3`).
5. The duplicate-site-id forgery mutation path and the gate-site! helper-name check moved with
   the new wrapper geometry.

**Concrete recorded numbers at the post-port tip** (evidence ns `test/yang/item3_evidence.clj`,
temporary, run via `clojure -M:test -n yang.item3-evidence`; same fixture
`{:call (latte-kernel.utils/vectorn? [1 2 3] 2)}`, same machinery as the binding test):

- origin (the retained admission record): `[:source :program [pp-item3-verify-<uuid> 0] 0]` —
  run scope + ordinal 0 + member 0, as r5 threaded.
- **2 oracle sites, 2 binding entries** — the SAME site count cc3 recorded pre-port (2 sites,
  cc3 findings `1794357169000`); the fixture and its stamping survived the port 1:1.
  Sites: `[1 kernel-context [:literal :variable :variable]]`,
  `[2 gate-utils-vectorn? [:computed :literal]]`.
- **marked 846 = eligible 846** — the full C1 set still carries exactly its markers
  (coverage separation). For the SAME fixture cc3 recorded 987 (then 981 after its audit
  fixes) application occurrences pre-port; the post-port compiled tree carries 846. The
  ~135-141 fewer applications are the retired outcome machinery's generated applications
  (outcome-tuple construction/unwrap sites), NOT lost guest code: the equality
  eligible = marked holds at both shapes, and the fixture's 2 oracle sites survived 1:1 —
  exactly the "compiled shape moved, binding still resolves" case the checklist asks about.
- **stamped-eligible 850 = 846 + 2 sites x 2 applications** — the post-port wrapper inserts
  exactly two applications per stamp, the re-derived accounting, verified numerically.
- **direct-stamps 2, routed-stamps 0; 34 intra-unit applications, 0 routed** — this fixture is
  entirely intra-unit direct tail application (the port's preserved tail lane); no bridge frames
  are involved, so no site's compiled shape moved between tail and bridged for it.
- **both lanes** `:completed` (walker AND semantic), 2 executed calls, 2 distinct sites,
  every executed site bound; **per-lane K sets EQUAL** (k-set-size 2, re-asserted green by
  the evidence ns's own `is`, 2 assertions 0 failures); K root: `:segment/blake3-42028ee1…`
  with per-site pre-stamp paths recorded in the run log (below).

**What did NOT change**: the binding contract itself — K = [origin root path] with origin = the
retained admission record (`:admission-origin`, never content synthesis), the
stamped→pre-stamp correspondence discipline (refusals `:cc3-stamp-correspondence`,
`:cc3-admission-record`, `:cc3-duplicate-stamp-site`, `:cc3-ambiguous-stamp-correspondence`),
the bijection assertions (stamped guest sites ↔ selected compiled occurrences), and the
independent-occurrence-multiset recomputation. Both C lanes still resolve every executed site to
its bound K, identically. The bijection HOLDS at the post-port shape; the wrapper-layer
accounting was re-derived to match the retired outcome channel.

**D1 evidence-import guidance: pin the POST-port revision.** Any cc3-era binding evidence that
assumed the 3-application wrapper (site-id path `[:operands 0 ...]`, 3 inserted applications per
stamp) is stale in exactly that layer — pre-port recorded K paths remain valid only if derived
before stamping (they are: K paths are pre-stamp paths by construction); the stamped-tree
coverage numbers changed shape. Concretely: pin `ba3cc407` (or any revision from `8456c2e9`
onward) for binding evidence import; pre-port evidence (`8afd82f5` and earlier) for wrapper-shape
comparisons only.

## Item 4 — Provenance side-artifact integrity: PASS

No cc4 pinned-cohort digest matrix is present in this tree (checked `collab/**`, `test/resources/
yang/clojure/` — cc4's fixtures have not landed), so per the checklist the **recompute-and-record**
path was taken, at BOTH shapes (pre-port `8afd82f5` extracted via `git archive`, post-port tip
`ba3cc407`), same fixed cohort of six ordinary (exception-free) programs, fixed admission token
`"pp-verify-batch-1"`, hashing: canonical rows (CBOR of `vm/ast->datoms-with-root` datoms = the
canonical code bytes), C1 marker relation (projected frontend-metadata), the §2.5 provenance
relation and call-provenance (semantic, via `linearize/project-rows`), and the lowered
instruction vector (PCs/addresses). **All six cohorts are byte-identical across the port
boundary** — `diff` of the recorded digest files: IDENTICAL (12 lines, 6 per side):

| cohort | root | canonical-sha | c1-metadata-sha | provenance-sha | call-prov-sha | vector-sha | apps=marked |
|---|---|---|---|---|---|---|---|
| :arith | -17 | 56168d7f41e4… | 841f437332fa… | be4a546b7266… | e7d399bd46a4… | 4dead84a3d51… | 5/5 |
| :arith* | -17 | bb4f0ebdc29b… | 26351cdf27cf… | 0c557663018f… | d834e415669e… | a3c9cb8a6619… | 5/5 |
| :higher-order | -17 | 65dc351305f7… | b2d5f0270662… | a38341e56169… | 804e2ea06a9a… | 5495ea6d4a97… | 8/8 |
| :loop | -17 | 06f2d87ce49d… | 3f9d0b56271e… | 12161c269c54… | be4720edd094… | 841d5ab2307a… | 12/12 |
| :case | -17 | 8614806b5f7a… | cec32082cb47… | 62c5817c6adb… | 6690abd85834… | 5f4028c56236… | 6/6 |
| :destructure | -17 | ff6b2e19efe0… | fd3f385d9849… | e343342e8a7a… | 546709f4faa4… | 94801e5dbbd3… | 8/8 |

`:arith*` is the in-run sensitivity probe (one changed literal): every digest differs from
`:arith` — the harness is not vacuously constant. The C1 equality gate (eligible = marked) was
re-derived per cohort at BOTH shapes: green everywhere. Full digests:
`target/postport/{pre,post}-port-digests.txt`; harness: `verify/digest_cohort.clj`.
Layering note: the publish.cljc relations (`published-call-site-table`,
`published-instruction-provenance`) are covered at unit scale by the green callsite battery
(`published-module-test`, `published-instruction-provenance-test`, `p1-packet-provenance-test`);
the cross-shape byte comparison here covers the canonical/C1/provenance layer those consume.

## Item 5 — Canonical invariance: PASS

    clojure -M:test -n yang.clojure-callsite-test -v yang.clojure-callsite-test/canonical-invariance-test

1 test, 1 assertion, **0 failures, 0 errors** (rc=0). Corroborated cross-shape by item 4: zero
canonical code bytes (`canonical-sha` over CBOR datoms), zero address shifts (`root -17`, and
`vector-sha` over the lowered PC-bearing vector unchanged) for all six non-exception cohorts
across the port boundary.

## Item 6 — The kit contract test on shiva at the post-port tip: PASS

`yang.call.exception-contract-test` (all 9 deftests: python/php/clojure/typescript
provenance + the five typescript forgery/mutation/seal/store-root/copied-wrapper
families) on shiva at `~/ci/datomworld-cc3` checked out to `origin/master` @
`ba3cc407` (remote verified `git@github.com:sonwh98/datomworld.git`; both `309fe842`
and `ba3cc407` verified ancestors; `mise trust` + `bb gen:antlr` + `bb deps:npm`
re-run; run in a dedicated tmux session, killed by exact session name after):

    clj -M:test -n yang.call.exception-contract-test

**9 tests, 159 assertions, 0 failures, 0 errors** (rc=0). 47 PROVENANCE fixture rows
printed, **47 green / 0 red** across register/semantic/stack (spot-verified rows include
python saved-protocol-invalid-invocation, saved-protocol-unsupported-binding, and
forged-retention — exact expected-map matches on all three backends). This is the
port's green kit state per their review — NOT the pre-port 87F/21E baseline — confirming
the kit merge (`ba3cc407`) did not regress the port's acceptance instrument.

## Environment & health checks

- kondo (lint only, via a throwaway ProcessBuilder test ns, since removed): on
  `src/cljc/yang/clojure.cljc`, `src/cljc/yang/clojure/prelude.cljc`,
  `test/yang/latte_oracle_test.clj` — **linting errors: 0, warnings: 0** (4904ms).
- Depth evidence spot-check: `test/yang/latte_oracle/depth_comparison.md` — 155 rows, max ratio
  1.20x (`constructor-mkenv` 5→6), max post-port depth 28 (`check-assume-have-discharge` 32→28) —
  matches the steward's dispatch notice; budget work not at risk (restated, not re-measured).
- Worktree left clean apart from this findings doc and the untracked evidence harness
  (`verify/digest_cohort.clj`, `test/yang/item3_evidence.clj`, `target/`). Nothing
  committed; no tracked file modified (git status: 3 untracked files, clean otherwise).


## Evidence bundle (all untracked, nothing committed)

- `verify/digest_cohort.clj` — the item-4/5 cross-shape digest harness (six fixed ordinary
  programs + sensitivity probe; run identically at both tree shapes).
- `target/postport/pre-port-digests.txt + post-port-digests.txt` + `port-diff-latte.patch` — the recorded
  digests (byte-identical across shapes) and the port's full diff of the binding machinery.
- `target/preport-tree/` — the `git archive 8afd82f5` extraction the pre-port side ran in
  (antlr class dirs symlinked from the worktree's own generated classes; load-only).
- `test/yang/item3_evidence.clj` — the item-3 evidence ns (re-runnable:
  `clojure -M:test -n yang.item3-evidence`; prints the binding's concrete numbers at any
  tree shape). Latest run at the post-port tip: both lanes completed, K sets equal,
  2 assertions 0 failures.
- shiva kit log `/tmp/ppkit-1794581084.log` on shiva.stigmergy.systems (47 PROVENANCE rows,
  47 green / 0 red; summary "Ran 9 tests containing 159 assertions. 0 failures, 0 errors.");
  clone `~/ci/datomworld-cc3` left at `origin/master` @ `ba3cc407`, tmux session `ppkit`
  killed after completion.

## Verdict restated

Six checklist items: **PASS, PASS, PASS-with-note (rebind, Phase 3r `8456c2e9` — not Phase 4),
PASS, PASS, PASS.** No defect reports; no relay; no code changes; nothing committed.

Created-GMT: 2026-11-25 02:0x GMT
Created-Local: 2026-11-25 09:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; unattributed-red routing)
Destination: codex exec -m gpt-6.1-terra on shiva (POOL 1; the red lives on the shiva node lane)
Task-ID: 1795640000000-qa-engineer-yangrt-effect-6f-attribution

# Task: attribute (and fix if surface permits) the yang.rt.effect-test 6F on the node lane

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-25 09:0x +0700 | Status: active | Rationale: debug on the host where the test failed (shiva node lane)

## Evidence of record (agy cross-review §2.5.3, shiva node lane, head `0d378482`)

`~/ci/datomworld-nightly-node/target-ci/node-shards-20261123-2121/uncovered.log` (lines
18-48): 6 assertion failures in
`yang.rt.effect-test/clojure-core-facade-and-cold-policy` (`test/yang/rt/effect_test.cljc:253-254`)
across `:semantic`, `:stack`, `:register` (bundled and linked):

    (inc 1.5) on CLJS evaluates to #dao.jing/float64 2.5  while  expected  has raw 2.5

The same 6 failures also appear in the 20261123-0015 run's uncovered.log — persistent,
not flaky. NOT the py pin cluster (Assignment B); this is a separate, unattributed red.
The agy cross-review noted it will fail its own dedicated shard after `857c297d` until
fixed.

## Read first

1. `docs/agents/build-n-test.md`, `docs/agents/roles/qa-engineer.md`
2. `test/yang/rt/effect_test.cljc` — the failing rows
3. The dao.jing float64 machinery (`src/cljc/dao/jing/*` float paths) and the CLJS
   numeric-literal lowering it interacts with

## Mission

1. Reproduce scoped on shiva in your own worktree at `origin/master`: the failing
   namespace on the NODE leg, the way the lane runs it (`node target/node-tests.js
   --test=yang.rt.effect-test` after the lane's build steps). Record rc/Ran.
2. Root-cause the `2.5` vs `#dao.jing/float64 2.5` mismatch: (a) the test's expected
   literal is stale (the runtime changed CLJS numeric output to boxed float64 and the
   test was not updated — a test-side fix on YOUR surface if the test file is the stale
   side), (b) the runtime wrongly boxes where the committed contract says raw, or
   (c) a bundled/linked asymmetry. Consult the dao/rt design docs for which side is
   contract. JVM parity check: does the JVM leg return raw `2.5`? Cross-host parity is
   the deciding evidence.
3. Fix authority: if (a) — you may edit `test/yang/rt/effect_test.cljc` expectations to
   the contract shape (NOT to merely match observed output; the design docs decide).
   If (b)/(c) — production defect: STOP, findings only with the exact contract citation.
4. Non-weakening: no expected-value relaxation beyond what the contract documents; the
   cold-policy structure of the test stays.

## Verification (scoped only)

1. Node-leg namespace green after fix (or unchanged red + full attribution if STOP),
   twice.
2. JVM leg of the same namespace green (parity evidence), recorded.
3. clj-kondo clean on touched files.

## Deliverables

Branch `fix/yangrt-effect-cljs-float` pushed; findings
`collab/exception-track/1795640000000-qa-engineer-yangrt-effect-6f-attribution.codex-gpt-6.1-terra.findings.md`
(classification with contract citation, every rc/Ran line, head sha). Orchestrator
countersigns and lands, or routes per findings. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.

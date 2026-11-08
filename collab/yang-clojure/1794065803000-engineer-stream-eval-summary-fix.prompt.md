Created-GMT: 2026-11-07 12:5x GMT
Created-Local: 2026-11-07 19:5x +07
Coding-Agent: qwen
Session-ID: bd12fc47-593b-4e3e-970b-5d9fa84d7eb1

# Task: implement the adjudicated stream_eval summary fix (exactly two edits)

Role: DaoStream and Distributed Protocol Engineer (mechanical implementation of a
final architect ruling — NO design latitude)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-07 19:5x +07 | Status: active | Rationale: the ruling is final and enumerates the edits; engineer executes and verifies

## Governing ruling (read both, in full)

- collab/yang-clojure/1794065655000-architect-stream-eval-summary-fix-adjudication.gpt-6-astra.findings.md
  (final ruling: APPROVE, exactly two edits, acceptance defined)
- collab/jolt/1794036904000-orchestrator-stream-eval-manifest-diagnosis.muse-wonder-1.0.stdout.log
  (the diagnosis it adjudicates)

## The two edits (from the ruling, verbatim intent)

1. src/cljc/yin/repl.cljc:2209 — the :index-publication projection becomes:
   :index-publication (select-keys (get-in state [:indexer :materialization])
                                   [:cursor :transactions])
2. test/yin/repl/index_test.cljc:288 — replace the pinned assertion with:
   (is (= (select-keys (:materialization ix)
                       [:cursor :transactions])
          (:index-publication (repl/repl-state state))))
   (exact-map comparison — also rejects reintroducing the address)

Do exactly these two edits and nothing else. The ruling verified: only one
summary-address pin exists in test/; no src consumer of the summary's address
(jolt_workload_w.clj:190 consumes only :cursor); internal address assertions
(index_test.cljc:272 retained-key assertion) remain valid — do not touch them.

## Where

Work in the git worktree /tmp/nrf-branch-wt (branch nightly-reds-fix @ cb2cbe11,
clean). Commit nothing.

## Verification (scoped ruling; the ruling's acceptance)

- On shiva: cd ~/ci/datomworld-nightly-node (at cb2cbe11, compiled); scp + git apply
  your diff; eval "$(mise env -s bash)"; recompile:
  clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge "{:autorun false}"
  then:
  DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.clojure.stream-eval-test
  → ALL green including every-vm-answers-the-same-stream-script (the ruling's
  acceptance: passes with NO additional normalization)
  AND --test=yin.repl-test (the engine namespace hosting the pin's subject, if
  the pin test runs on node — check which lane runs index_test; if it is
  JVM-only, run it on jupiter instead:
  cd ~/ci/datomworld-nightly && eval "$(mise env -s bash)" && git apply your diff
  && DATOM_SLOW_TESTS=1 clojure -M:test -n yin.repl.index-test → green)
- Report BOTH verification outputs verbatim and git diff --stat (expect exactly
  2 files changed). Do not claim runs that did not occur.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

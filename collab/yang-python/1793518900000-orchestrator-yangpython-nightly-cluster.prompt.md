Created-GMT: 2026-11-01 07:35:00 GMT
Created-Local: 2026-11-01 14:35:00 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: close the yang.python nightly red cluster — one root cause suspected, five namespaces affected

Role: yang.python Orchestrator (peer seat)

Implementers:
- Model: <the yang.python seat's current orchestrator> | Assigned: 2026-11-01 14:35 +07 | Status: active

## The reds (measured, jupiter JVM slow tier, commit 1a66d94f, 2026-11-01 03:20 run)

Seven slow namespaces fail. Evidence lives in
`jupiter:~/ci/nightly-results/20261101-0148/slow-<ns>.log`. Read them; do not
trust this summary.

| Namespace | Findings | First-line signature |
|---|---|---|
| `yang.python.antlr.dht-import-test` | 84 (mostly in one `deftest`) | `dht_import_test.cljc:421/:443`, `PersistentVector.java:162` |
| `yang.python.antlr.linked-prelude-test` | 9 | `install-child-defines-only-test` at `linked_prelude_test.cljc:484/485` |
| `yang.python.antlr.linked-safepoint-test` | 2 | `linked-recursion-error-test` (`RT.java:1629`), `pysp-manifest-address-test:269` |
| `yang.python.antlr.siteset-test` | 4 | **`engine.cljc:52` — link REFUSED `:yang.python.antlr/missing-site-set`** |
| `yang.python.antlr.safepoint-test` | 1 | **`engine.cljc:52` — link REFUSED `:absent` for `yang.call`** |
| `yin.vm.semantic.derive-test` (c4-execution-oracle) | 1 | **`engine.cljc:52` — link REFUSED `:absent` for `yang.call`** |
| `yang.python.antlr.e2e-test` | 2 | `filtered-range-comprehension-repl-test` (`e2e_test.clj:341`), `recursion-under-real-hooks-test:819` |

JVM context: `siteset` ran only 5 tests / 15 assertions (3 ERRORS before most
of the file ran); `derive-test`'s `c4-execution-oracle` is a 3-assertion test.

## The suspected root cause (a labeled hypothesis — verify, do not assume)

**`yang.call` fails to LINK.** Two namespaces error with an explicit link
refusal naming `yang.call` (`:reason :absent`), and `siteset`'s refusal names
`:yang.python.antlr/missing-site-set` ("profile `:py/hooks` requires hooks: no
published site set for the module") — the same link step, a different refusal
reason. The other reds (`dht-import`, `linked-prelude`, `linked-safepoint`,
`e2e`) are exactly the tests that also publish/link modules before running, so
a broken publication or link path for `yang.call` (or the hooks/site-set
machinery around it) would explain the whole cluster with one cause.

Corroborating timeline from the orchestrator-log (verify in git):
- A red cluster in these same namespaces was being fixed earlier on
  2026-10-31 (the "nightly-red round", commit `af506f77`, fix round
  `1793465000000-engineer-nightly-red-fix.*` in collab/).
- Master has since moved through the collab-reorg and toolchain commits
  (`2bd42461`, `e3704fd9`, `161dac7d`, `5e5b7c7f`, `a3ac56bb`).
- So either the earlier fix round was incomplete, or something after
  `af506f77` re-broke it. `git log --oneline af506f77..HEAD -- src/ test/`
  narrowed to the publication/safepoint/prelude paths is the first bisect
  step.

## What to do

1. Reproduce ONE failure locally, smallest first:
   `clojure -M:test -i :slow -n yang.python.antlr.siteset-test` (5 tests, fast)
   — the ERROR there is an *uncaught* link refusal, so the log names the exact
   module and reason. Then `yang.python.antlr.safepoint-test` (1 error) and
   `yin.vm.semantic.derive-test` (1 error) to confirm the `:absent` variant.
2. Determine WHY the link is refused:
   - Is `yang.call` published at all in the failing fixture's store?
   - Does the `:py/hooks` profile now require a published site set that the
     fixtures never publish? (`git log -S "missing-site-set" -- src/` will
     find when that refusal was introduced — suspect a recent change making
     the hooks profile stricter.)
   - Is the manifest the tests compute (`:segment/blake3-466b…` in the
     refusal) different from what the fixture expects, i.e. a stale golden?
3. Then fix at the right layer:
   - Fixture never publishes what the (newly stricter) link step requires →
     fix the fixture to publish the site set, and the `:absent` variant likely
     follows.
   - The refusal itself is wrong → that is yang.python product code; fix there.
   - If the refusal is CORRECT product behavior and these tests assert old
     semantics, update the tests' expectations — but say so explicitly in the
     findings; a behavior change hidden as a test-expectation change is how
     regressions get laundered.
4. The `dht-import-test` cluster (84 findings inside one deftest) is likely
   the same root propagating through a shared fixture — confirm after the
   link fix lands; do not chase its 84 assertions individually.
5. The two `e2e-test` failures (`filtered-range-comprehension-repl-test`,
   `recursion-under-real-hooks-test`) may be independent — check whether they
   fail at commit `af506f77` too, before or after your fix, and report them
   separately if they don't share the root.
6. Note: `yin.vm.semantic.derive-test/c4-execution-oracle` is filed under
   yin.vm but its failure is the same `yang.call` link refusal — fix it here,
   note it in the findings so the yin.vm seat knows.

## Constraints

- Scope: `src/cljc/yang/python/**`, `test/yang/python/**`, and (only if the
  evidence points there) the publication/link hooks path in
  `src/cljc/yang/core/**`. The `yin.vm.semantic.derive-test` fix is expected
  to be a yang.python-side fixture/publication fix — if it instead requires
  yin.vm changes, hand that piece back with evidence.
- The link refusal comes from `yin.vm.engine/fail` — that file is NOT in
  scope.
- Verification bar: all seven namespaces green on the JVM slow tier
  (`clojure -M:test -i :slow -n <ns>` per namespace), plus the Node lane for
  the touched fixtures (`DATOM_SLOW_TESTS=1 node target/node-tests.js
  --test=<those namespaces>`), since the same fixtures run there. Cross-host:
  the orchestrator re-runs the nightly; you do not need shiva access.
- Routing (routing-status.md 2026-10-31): codex, glm, agy, deepseek, vibe
  available; claude/cmd out. Reviewer different family from implementer.
- Collab filing per orchestrator.md step 11: artifacts flat in `collab/` in
  flight; file into `collab/yang-python/` when the round closes.

## Deliverable

`collab/<timestamp>-orchestrator-yangpython-nightly-cluster.findings.md`:
root cause with the commit that introduced it, the fix, per-namespace
verification counts (JVM + Node), and the e2e-test disposition (same root or
separate). Then the nightly's next scheduled run (17:00 VN) should show zero
yang.python reds — that is the acceptance test.

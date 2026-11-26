Created-GMT: 2026-11-24 03:2x GMT
Created-Local: 2026-11-24 10:2x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-approved speed plan, P1-2)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2)
Task-ID: 1795500000000-engineer-slow-var-sharding

# Task: var-level sharding for the slow-tier long poles (driver-side; no test-file edits)

Role: engineer. The nightly slow tier now finishes 59/61 children in ~2h; the wall is held
by `yang.php.antlr.e2e-test` (12 slow vars, historically 29,887s+ in ONE JVM) and
`yang.typescript.antlr.corpus-test` (6 slow vars, ~8,959s). Owner directive: cut the slow
tier. Architect bar (collab/general/1795470000000-architect-test-runtime-audit.gemini-4-argon-high.findings.md,
intervention 2): split only INDEPENDENT work; evidence equivalence required.

## Mission (branch `fix/slow-var-sharding`, base origin/master `0d378482+`)

1. In `src/dev/nightly.clj` (the slow tier path), add optional var-sharding: namespaces in a
   driver-level configurable set (default: the measured top poles) may be scheduled as
   K JVM invocations, each `-n <ns> -v <ns>/<var>` for a group of vars, K chosen by var
   count (e.g. >=6 slow vars -> K=min(4, var-count)). All other namespaces keep
   whole-namespace scheduling. Default OFF unless configured, so the change is
   surgical; enable the set for: yang.php.antlr.e2e-test, yang.typescript.antlr.corpus-test,
   yang.php.antlr.include-test, yang.python.antlr.e2e-test, yin.vm.semantic.safepoint-harness-test.
2. Independence evidence FIRST, scoped: for `yang.php.antlr.e2e-test`, run TWO vars that
   the e2e harness treats as independent in SEPARATE fresh JVMs (`-i :slow -n ns -v ns/var`)
   and the same two inside ONE namespace-order JVM; compare row dispositions (Ran/failures
   and any thrown payloads) — identical outcomes = independence holds for scheduling
   purposes. Record both runs. If any var mutates shared state across vars (check the
   harness's once-fixtures), SPLIT AT FIXTURE BOUNDARIES ONLY — groups must not straddle a
   stateful chain. Do not weaken any oracle or deadline.
3. The runner's own `-i :slow` and `-v` flags do the filtering; do not reimplement
   selection. Never schedule `yang.call.exception-contract-test` split (standing
   never-run-locally caution: leave its scheduling exactly as the landed discovery has it).
4. clj-kondo clean on touched files; nightly.clj only (plus a new driver-side config file
   if cleaner). NO test files, NO production src.

## Verification (scoped only)

1. The two-var independence pair (step 2): identical dispositions both modes, recorded.
2. Driver dry-run/list mode shows the split schedule (K groups per configured ns) and the
   unsplit schedule with the config removed.
3. One split group of a CONFIGURED namespace runs green in a fresh JVM end-to-end
   (`-i :slow -n ns -v ns/varA -v ns/varB`… or per-var if that's the group shape) — record
   Ran/failures and wall seconds, vs the same vars' historical in-namespace share (from
   tonight's logs where available).
4. Quick tier manifests unchanged (planner list-mode diff empty).

## Deliverables

Branch pushed; findings
`collab/general/1795500000000-engineer-slow-var-sharding.codex-gpt-6.1-terra.findings.md`
(independence evidence, schedule listings, every rc/Ran line with wall, head sha). The
orchestrator countersigns and lands; the driver change must NOT touch yang.php test files.
Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.

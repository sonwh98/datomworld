Created-GMT: 2026-11-24 00:0x GMT
Created-Local: 2026-11-24 07:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-approved perf plan, P0)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2)
Task-ID: 1795480000000-engineer-slow-discovery-metadata

# Task: fix slow-tier var discovery — metadata-form ^:slow tags are missed (17 vars unrun)

Role: engineer. Owner-approved test-speed plan, step P0-b. The architect audit
(`collab/general/1795470000000-architect-test-runtime-audit.gemini-4-argon-high.findings.md`)
proved the slow-tier discoverers grep for the LITERAL `^:slow`, but vars tagged with the
metadata form `^{:slow true ...}` are invisible to it:

- `test/yang/call/exception_contract_test.clj` — 9 slow vars, file has NO literal `^:slow`
- `test/yang/python/antlr/exception_call_test.clj` — 8 slow vars, same

Neither namespace appears in the nightly slow logs (evidence:
`~/ci/nightly-results/20261123-0018-slow-runs.txt`, 60 logs, no exception-contract rows).
Conversely `dao.test-slow` (docstring only) earns a fresh JVM that runs 0 tests
(`20261123-0018-slow-dao.test-slow.log`).

## Mission (branch `fix/slow-discovery-metadata`, base origin/master)

In `src/dev/nightly.clj` (and any sibling discoverer with the same literal grep):

1. Replace literal-grep discovery with ACTUAL test-var metadata: load the test ns (or use
   the existing JVM runner's inventory mechanism) and select vars whose `:slow` metadata is
   truthy — this covers macro-generated and metadata-form tags.
2. Namespace-level decision rule: a namespace enters the slow tier iff it has >= 1 slow
   var; a namespace with 0 slow vars never gets a JVM. The runner's own `-i :slow` var
   filtering stays the source of truth for what runs inside.
3. Emit the discovery manifest (ns -> slow-var count) to the lane output for auditability.
4. Do NOT change which vars run in quick tier, the tier semantics, or any test file.

## Verification (scoped only)

1. Discovery dry-run (no test execution — the planner's list mode if present, else your
   own inventory invocation): the manifest must contain `exception-contract-test` (9) and
   `exception-call-test` (8), and must NOT contain `dao.test-slow`. Record the full diff
   vs the old discovery's list.
2. One slow var from each previously-missing namespace actually runs green in a fresh JVM
   (`-i :slow -n <ns>`, single var if the runner supports `-v`): record Ran/failures.
   NEVER run yang.call.exception-contract-test in full (standing rule).
3. The quick tier's var set is UNCHANGED: diff quick manifests old-vs-new discovery (must
   be empty diff).
4. clj-kondo on touched files: 0 new errors/warnings.

## Deliverables

Branch pushed; findings `collab/general/1795480000000-engineer-slow-discovery-metadata.codex-gpt-6.1-terra.findings.md`
(manifest diffs, every rc/Ran line, head sha). The orchestrator verifies, lands through
the countersign protocol, then updates the host launchers. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.

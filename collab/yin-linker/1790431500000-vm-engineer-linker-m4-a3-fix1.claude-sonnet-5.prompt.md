Created-GMT: 2026-09-26 11:25:00 GMT
Created-Local: 2026-09-26 18:25:00 +0700
Coding-Agent: claude
Session-ID: c1f0a3a3-5b1e-4d0e-9a11-0a3f17171717

# Task: fix codex gate findings on M4 slice A3 (authority event ingestion)

Role: VM Runtime Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-26 18:25:00 +0700 | Status: active | Rationale: GLM budget ~5%; claude authorized as destination; orchestrator routing

Worktree: /Users/sto/workspace/datomworld-m4-a3 (branch m4-a3, uncommitted A3 work; do NOT commit). Edit only src/cljc/yin/vm/linker/authority.cljc, test/yin/vm/linker_authority_ingestion_test.cljc and the one spec sentence near docs/design/yin.vm.linker.md:1717 if the behavior it describes changes. Smallest diff that satisfies the findings.

Gate findings to fix (codex gpt-6-sol, verdict APPROVE-WITH-FIXES, full text: collab/1790430690002-architect-linker-m4-a3-gate.gpt-6-sol.stdout.log, last agent_message):
1. P1: events-from-datoms keeps the first :yin.module/proof value per event entity and ignores the rest. dao.space allows multiple values per attribute, so an untrusted writer can add a second proof and silently change which proof is authenticated. Fix: if an event entity carries more than one distinct proof value, do not pick one; reject/diagnose that entity (fail closed, no authority event from it, surfaced in whatever diagnostic channel the function already uses for malformed datoms).
2. P1: proof selection depends on datom order (host-dependent EAVT ordering with string fallback). The fix for 1 must remove any order dependence: result must be identical for any permutation of the input datoms. Add a permutation test.
3. Orphan proofs: a proof with no envelope is ignored, but if an envelope later appears on that entity the ignored proof joins it. Add a test that an orphan proof later joined by an envelope behaves deterministically and per the rule above, and make sure the spec sentence matches.
Tests to add: valid+invalid proof on the same entity (rejected, not silently resolved); permutation invariance; orphan-then-envelope.

Rules: Rule R; fail closed; cljc portable to JVM/Node/Dart (no host-only forms; ClojureDart ignores #?(:clj ...) exclusion, use :cljd first when needed); ASCII only, 80 cols; cljstyle clean (`mise exec -- cljstyle check <files>`), kondo via `mise exec -- clojure -M:kondo --lint <files>` (see docs/build-n-test.md). Run the JVM tests for the touched namespaces only; the orchestrator runs the full three lanes.
Write your report to collab/1790431500000-vm-engineer-linker-m4-a3-fix1.claude-sonnet-5.report.md (what changed, test names, anything you decided).

Created-GMT: 2026-09-26 11:11:30 GMT
Created-Local: 2026-09-26 18:11:30 +0700
Coding-Agent: codex
Session-ID: n/a (codex thread id captured from stdout)

# Task: gate for M4 authority slice A3, event ingestion from datoms (GLM-authored, non-GLM reviewer)

Role: Adversarial Code Reviewer and Security Auditor + Lead System Architect (combined commit gate)

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-26 18:11:30 +0700 | Status: active | Rationale: owner authorization "codex and claude has the most budget. dispatch the reviewers"; author was glm-5.3, so the reviewer is a different model family or at least a different model

You are a HEADLESS read-only reviewer. Produce the COMPLETE review in your final response now. Do not wait for approval, do not ask questions, do not end with a plan or promise of a verdict. Do not edit files. Do not rerun the test suites (orchestrator ran them). Cite file:line. Findings are ranked P0 (blocks commit), P1 (fix before merge), P2/P3 (follow-up). End with an explicit verdict line: APPROVE / APPROVE-WITH-FIXES / REJECT, and the list of must-fix items.

Rules in force: Rule R (yin/def is syntax, never a name; only yin/def reserved); require is an ordinary function that lowers to the linker; no contract stamp is ever assigned to external input; code is content-addressed, names come from dao.space via authority policy; no backward-compat shims (dev-only repo); ClojureDart trap: #?(:clj ...) does NOT exclude code from cljd builds. The report you are handed was written by the implementer and is UNTRUSTED: verify every claim against the diff.
Reference: docs/design/yin.vm.linker.md (master spec), docs/design/yin.vm.universal-continuation-format.md (UCF).

## What is under review
Worktree /Users/sto/workspace/datomworld-m4-a3 (branch m4-a3, from master 9428c3d2).
Uncommitted work: src/cljc/yin/vm/linker/authority.cljc (adds events-from-datoms), NEW test/yin/vm/linker_authority_ingestion_test.cljc, and one spec sentence in docs/design/yin.vm.linker.md near line 1718 (the `[ev :yin.module/proof proof]` datom sentence, L1719-1724). Read with `git -C <wt> diff HEAD`.
Orchestrator evidence: JVM 2143 tests / 181920 assertions, Node 2056 / 48732, Dart 2018 passed, kondo/cljstyle clean.
Things to scrutinize:
(a) The report's "Decisions the spec left open": especially the decision that orphan proofs are IGNORED. Is ignoring safe (no attacker-controlled silent drop that changes name resolution, equivocation or sequence-floor outcomes)? Should it be surfaced instead?
(b) Consistency with spec section 8.2: signed envelopes, attested logs, per-principal dedup/equivocation/sequence-floor passes, :ambiguous-name, snapshot as rebuilt state.
(c) Ingestion of untrusted datoms: fail-closed, deterministic ordering, no reliance on host-specific ordering (cljd/cljs/jvm).
(d) Whether the spec sentence added matches the code exactly.

Implementer's report (untrusted): /Users/sto/workspace/datomworld/collab/1790417540104-vm-engineer-linker-m4-a3-authority-ingestion.glm-5.3.report.md

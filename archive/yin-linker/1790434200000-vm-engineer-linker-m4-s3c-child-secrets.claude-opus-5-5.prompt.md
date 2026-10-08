Created-GMT: 2026-09-26 12:35:00 GMT
Created-Local: 2026-09-26 19:35:00 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122 (resumed)

# Task: S3c, mint child capability secrets in the REPL and dao.await compositions

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 19:35:00 +0700 | Status: active | Rationale: codex re-gate of S3 found one P1 left; Opus authored S3a/S3b

Worktree /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, uncommitted; do NOT commit). Resume your session.
Codex re-gate (collab/1790433500000-architect-linker-m4-s3-regate.gpt-6-sol.stdout.log, last agent_message): APPROVE-WITH-FIXES, its three earlier findings closed; ONE must-fix:
P1: spawn-child (engine.cljc ~927) gives a child a capability secret only when the parent has :secret-source. The REPL (repl.cljc ~426) supplies a root :capability-secret but no source, and dao.await/run (await.cljc ~229) does the same. A module installed through either composition therefore cannot issue a stream reference: issue-ref (engine.cljc ~254) refuses a child without a secret. Fix: each composition supplies fresh child secrets from its own random/injected source (compositions own randomness; the engine stays clock- and RNG-free per the spec). Add tests: a module that uses a stream, installed through the REPL composition and through dao.await, works end to end. Also keep the test for the deliberately omitted source.
Smallest diff. Same rules as before: Rule R, fail closed, portable cljc (cljd: :cljd first in reader conditionals, no bare `type`), ASCII, 80 cols, cljstyle, kondo via mise. Touched-namespace JVM tests only; orchestrator runs lanes. Report: collab/1790434200000-vm-engineer-linker-m4-s3c-child-secrets.claude-opus-5-5.report.md.

Created-GMT: 2026-09-26 11:50:00 GMT
Created-Local: 2026-09-26 18:50:00 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122 (resumed)

# Task: S3a, fix codex gate findings 2 and 3 on M4 slice S3

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 18:50:00 +0700 | Status: active | Rationale: owner chose option A (split S3 fixes; Opus authored S3)

Worktree /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, uncommitted; do NOT commit). You are resuming your S3 session. NOTE: after your handoff the orchestrator patched engine.cljc ~line 476 for ClojureDart (`(type x)` -> `#?(:cljd (str (.-runtimeType x)) :default (str (type x)))`); keep it.
Gate: codex gpt-6-sol REJECT, full text in collab/1790430690000-architect-linker-m4-s3-gate.gpt-6-sol.stdout.log (last agent_message). This brief covers ONLY:
1. P1 install publication can throw instead of refusing: link-install catches lift failures, but image attachment, decoding, store lowering and dependency publication (engine.cljc ~805-830) are outside the catch, and advance-install calls link-install outside its child-run catch (~853). A lowering/publication defect must become a specified install refusal, the install and its waiters must not stay parked, no throw may escape the scheduler round. Add tests that inject a failure in each stage.
2. P1 register continuation image table (debruijn_register_effects.cljc ~233): validator must require ordered, non-overlapping, complete coverage of the concatenated instructions, and check identity on zero-length rows too. Tests: missing row, overlapping, reordered, forged zero-length identity.
Do NOT do the private :resources table / sealed references (r8-r11); that is S3b, a separate slice after this. Smallest diff. ASCII, 80 cols, cljstyle clean, kondo via `mise exec -- clojure -M:kondo`, portable cljc (cljd traps in memory: #?(:clj) does not exclude from cljd). Run touched-namespace JVM tests only; orchestrator runs three lanes.
Report to collab/1790432200000-vm-engineer-linker-m4-s3a-fixes.claude-opus-5-5.report.md.

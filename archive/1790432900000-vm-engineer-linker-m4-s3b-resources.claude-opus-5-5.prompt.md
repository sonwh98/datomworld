Created-GMT: 2026-09-26 12:00:00 GMT
Created-Local: 2026-09-26 19:00:00 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122 (resumed)

# Task: S3b, private resources table and sealed references (linker r8-r11)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 19:00:00 +0700 | Status: active | Rationale: owner chose option A; S3b is the P0 of the codex S3 gate

Worktree /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, uncommitted; do NOT commit). Resume your S3 session; S3a (your two P1 fixes) is done and stays; keep the orchestrator's cljd fix at engine.cljc ~476.

Gate P0 (codex gpt-6-sol, collab/1790430690000-architect-linker-m4-s3-gate.gpt-6-sol.stdout.log, last agent_message): stream handles and cursor cells are still written into :store, and stream effects accept a program-supplied reference by its predictable id without a seal check (engine.cljc ~254, 277, 297). Top-level code can read the store and a literal reference can name an existing resource at effect dispatch. Your own report's "What is left" items 1-4 describe the work.

Implement exactly what docs/design/yin.vm.linker.md specifies in "Private engine resources (r8)", "Resource lowering (r9)", "Sealed references (r10)" and r11 "lift authenticates before it encodes" (~lines 1430-1500): the private :resources table, move stream handles, cursor cells and the FFI call pair out of :store, task capability secret, seal on every issued reference, check at effect dispatch (:forged-resource-reference), lift/lower re-seal rather than carry, resource lowering into :resources, the "reference-carrying exports lift and lower into :resources" test. You are now allowed to edit yin.vm.ffi, yin.vm.completion, dao.await and the handoff demo as needed (that was the reason S3 stopped). Add the missing :foreign-image test too. Any existing test that must change: explain each in the report; no weakening of assertions.
Rules: Rule R; fail closed; portable cljc with cljd traps (#?(:clj) does not exclude from cljd; no private var-quote cross-namespace access; no bare `type`); ASCII, 80 cols, cljstyle clean, kondo via `mise exec -- clojure -M:kondo`. Determinism across hosts: the secret must come from an injected/deterministic source per the spec, not a host RNG hidden in the engine, unless the spec says otherwise; follow the spec. Run touched-namespace JVM tests; orchestrator runs three lanes. If the spec is ambiguous, pick the reading that fails closed and record it.
Report to collab/1790432900000-vm-engineer-linker-m4-s3b-resources.claude-opus-5-5.report.md.

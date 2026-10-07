Created-GMT: 2026-09-26 15:45:00 GMT
Created-Local: 2026-09-26 22:45:00 +0700
Coding-Agent: claude
Session-ID: 91b1631e-6d2c-4d81-a10f-5ce64156d89a

# Task: M5 fix3, one remaining codex P1 (retained-line replay)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 22:45:00 +0700 | Status: active | Rationale: GLM frozen by owner until 2026-09-27 01:26 +0700; Opus knows the engine's origin/id and install code

Worktree /Users/sto/workspace/datomworld-m5 (branch m5, uncommitted; do NOT commit). The M5 work (yin.repl wiring of the linker, by glm-5.3-flash) is in place and passes all three lanes; read its report collab/1790437900000-vm-engineer-linker-m5-repl-wiring.glm-5.3-flash.report.md (top fix1 and fix2 sections) and the diff (git diff HEAD plus untracked src/cljc/yin/repl/link.cljc and test/yin/repl/require_test.cljc). Codex's first gate REJECT (collab/1790440400000-architect-linker-m5-gate.gpt-6-sol.stdout.log) was fixed by fix2; codex's re-gate (collab/1790442000000-architect-linker-m5-regate.gpt-6-sol.stdout.log, last agent_message) is REJECT on ONE new P1:

P1: A retained line that starts another pending require does not stop replay. fold-queued (repl.cljc ~1164) continues through every retained line even if one returns a new :pending-run; after that fold, eval-parsed (~1233) also evaluates the line that triggered completion without checking whether replay parked the VM again. Later lines can run against a VM with an outstanding require, breaking the promised order and potentially losing the new pending state. The replay test (require_test.cljc ~393) covers only arithmetic lines.
Must fix: stop replay when a retained line creates a pending run; retain the remaining lines and the triggering line in order. Add a regression test in which a retained line starts a second pending require (assert order, exactly-once evaluation, and that the second pending state is not lost; and that (abandon) then drops all retained lines and says so).
Also (codex, non-blocking but cheap): make the child-origin abandonment test (require_test.cljc ~369) exercise a real late child response instead of only checking the counter with a synthetic install wait, if that can be done without a large test rig; otherwise say why not.

Smallest diff. Rules: Rule R; ClojureDart traps (:cljd first in reader conditionals, no bare type, no private var-quote); portable cljc; ASCII, 80 cols; cljstyle clean (mise exec -- cljstyle check <files>); kondo via mise exec -- clojure -M:kondo; JVM tests for touched namespaces. The orchestrator runs the three lanes. Report to collab/1790443000000-vm-engineer-linker-m5-fix3.claude-opus-5-5.report.md.

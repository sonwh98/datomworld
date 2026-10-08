Created-GMT: 2026-09-25 11:10:00 GMT
Created-Local: 2026-09-25 18:10:00 +0700
Coding-Agent: claude (in-process subagent, Sonnet 5)
Session-ID: pending (provider-generated)

# Task: yin.vm.linker — M2 Gate Fix Round 4 (one P1)

Role: VM Runtime Engineer (Claude Sonnet 5 subagent)
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-25 18:45 +0700 | Status: active | Rationale: owner directive 2026-09-25 ("dispatch r4 with a claude subagent"); the orchestrator seat is Claude Code and cannot spawn ZCode subagents

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2; M2 + fix rounds 1-3 are uncommitted). The round-3
gate confirmed the invocation-position fix and the round-2 fixes, and
returned one P1 on the shadowing guard. Read first:
- collab/1790334300000-architect-linker-m2-fixes-r3-gate.gpt-6-sol.findings.md
- collab/1790331801000-vm-engineer-linker-m2-fixes-r3.glm-flash.report.md
- docs/design/yin.vm.linker.md sections 4.1, 4.2 (and the store
  isolation clause near line 1352)

## Work item

P1 (linker.cljc:446) — the guard drops yin/def-derived definitions only
when another yin/def application visibly binds yin/def. It misses (a) a
direct :vm/store-put of the name yin/def, which the scanner otherwise
treats as a definition, and (b) a computed-key write that could
evaluate to yin/def. Runtime reads the active store before primitives
(engine.cljc:54), so either write shadows the primitive.

The gate's prescription (quote, not paraphrase): "Treat every possible
write to the module's `yin/def` slot—including direct and unresolved
computed-key writes—as invalidating syntactic `yin/def` definitions, or
prove the primitive binding at each call. Add isolated tests for both
writes."

Implement the first alternative, fail-closed: any direct store-put of
yin/def, and any store write whose key is not statically a constant
other than yin/def, invalidates the yin/def-derived definitions
(obligations retained). Add two ISOLATED tests, one per write kind,
each free of any yin/def rebinding, so each fails without its own fix.
Different modules do not shadow each other (per-module store isolation,
spec ~1352); do not widen the rule across modules.

## Constraints

- Touch only src/cljc/yin/vm/linker.cljc and test/yin/vm/linker_test.cljc.
- Pure ASCII, <= 80 columns on added/edited lines; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Preserve all confirmed fixes (rounds 1-3) and all existing tests.
- Run under mise (mise exec -- <cmd>). Verify sequentially, solo: JVM
  full suite (baseline 2,057/180,912/0 plus your new tests), Node,
  Dart (rm -rf test/cljd-out first). Report exact counts.
- If the fix conflicts with the spec, STOP and report BLOCKED with the
  exact conflict.

Write your final report to
collab/1790334600000-vm-engineer-linker-m2-fixes-r4.claude-sonnet-5.report.md
(same header fields as this prompt) and also return it as your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

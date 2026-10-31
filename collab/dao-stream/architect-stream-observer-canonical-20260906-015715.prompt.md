Created-GMT: 2026-09-06 01:57:15 GMT
Created-Local: 2026-09-06 08:57:15 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

# Task: Review canonical plan ownership correction

Role: Lead System Architect
Implementers:
- Model: gpt-6 | Assigned: 2026-09-06 08:57:15 Asia/Ho_Chi_Minh | Status: revised | Rationale: Interactive orchestrator incorporated the user-requested observer ownership correction into the canonical plan.
- Model: claude-fable-5-1 | Assigned: 2026-09-06 08:57:15 Asia/Ho_Chi_Minh | Status: active | Rationale: Resume the same independent Architect session that approved the descriptor boundary.

Review the actual current diff and resulting document for
`docs/design/yin.vm.implementation-plan.md`. This is a related follow-up to
the descriptor-based plan you reviewed in this session. User requested fixing
the canonical plan for stream-observer ownership, after reviewing flow,
consistency, and duplication. Permission mode remains auto as the user required;
the task is read-only. Return your review now; do not edit, run tests, stage,
commit, or delegate further.

Read the scoped diff with:
`git diff -- docs/design/yin.vm.implementation-plan.md`

Authorized supporting reads, only as needed:
- `docs/design/datom.world.md`
- `docs/design/dao.stream.md`
- `docs/design/yin.vm.divergence-register.md`
- `docs/design/yin.repl.implementation-plan.md`
- `src/cljc/yin/vm/stream_observer.cljc`
- `src/cljc/yin/vm/engine.cljc`
- `src/cljc/yin/vm/ast_walker.cljc`
- `src/cljc/yin/repl/core.cljc`
- `collab/orchestrator-stream-observer-descriptor-generic-20260905-125100.plan.md`
- `collab/architect-stream-observer-separation-20260905-101500-r5.claude-fable-5-1.findings.md`

Acceptance: one authoritative architecture section near the top; observation
handle/cursor/gaps owned by observer; VM only loads/interprets program datoms;
exact three-function observer API; DaoStream-only dependency; descriptor plus
host-injected attachment capability; existing VM readiness/loading/run reused;
FFI/language stream operations preserved; V7 is pending implementation; original
port history clearly separated; V7 lists migration and verification work without
repeating the architecture; no temporary collab dependency in the canonical doc.

Check gap ownership carefully. Prior editorial review incorrectly treated the
REPL loss latch as conflicting with generic gap recovery. Existing core.cljc
eval-datoms runs the VM/observer then checks for a gap-count increase and refuses
further evaluation until reset. The revised plan preserves that separate shell
policy while the generic observer recovers and continues. No new gap policy is
authorized. Companion docs remain baseline records, with their V7 updates an
explicit migration deliverable; only the canonical plan is edited this turn.

Local whitespace check passed; this is documentation-only. Report any remaining
contradictions, lost requirements, or material duplication with line references.
Distinguish actual defects from preexisting port rationale or deferred code work.
Begin with Completed-GMT/Local (read date), Coding-Agent: claude, and the exact
Session-ID above. State a verdict and all findings; approve if no blockers remain.

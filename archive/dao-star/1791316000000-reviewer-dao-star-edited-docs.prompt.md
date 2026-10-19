Created-GMT: 2026-10-07 06:20:00 GMT
Created-Local: 2026-10-07 13:20:00 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: dao.star edited docs review
Role: Reviewer (adversarial, architectural)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-07 13:20:00 +07 | Status: active | Rationale: cross-family review of Claude-authored design docs; codex reported available by owner

Read-only adversarial review. Do not edit files.

Subjects (worktree /Users/sto/workspace/datomworld/.claude/worktrees/dao-star-orch):
- docs/design/dao.star.md (rewritten, 131 lines)
- docs/design/dao.stream.http.md (new, 131 lines)
Context: the Architect's report
collab/1791309600000-architect-dao-star-apply-rulings.claude-fable-5-1.stdout.log
and rulings collab/1791307819601-architect-dao-star-gaps.claude-fable-5-1.findings.md
(same worktree). A prior glm review (collab/1791308400000-reviewer-dao-star-gaps-rulings.glm-5-3.findings.md)
confirmed the rulings' citations; do not redo it unless you doubt it.

Other design docs live under /Users/sto/workspace/datomworld/docs/design/
(datom.world.md, dao.stream.md, dao.stream.ws.md, dao.stream.remote.md,
dao.lease.md, dao.space.security.md, dao.shibi.md,
yin.vm.universal-continuation-format.md). The blog is
/Users/sto/workspace/datomworld/public/chp/blog/daostar-vs-datastar-vs-electric-clojure.blog
(published; the docs must not contradict it).

Owner decisions the text must honor (verbatim):
> 1. yes let's wait  (Mode 2 waits for UCF v1)
> 2. yes  (migration back: allow-listed peers only)
> 3. let's go with the recommendation  (stale request: one-shot + re-projection)
> 4. dao.lease should be used for this to expire parked continuation nobody returns to
> 5. yes  (occurrence token as sole Mode 1 gate)
> 6. don't know  (Q6 is an Architect ruling, must be marked unconfirmed)

Check:
1. Faithfulness to the six owner decisions; the Q4 holder choice is the
   Architect's own design, assess it against dao.lease.md.
2. Contradictions with the six invariants, dao.stream.md, dao.stream.ws.md
   (the http doc claims to mirror its deposit admission), dao.lease.md,
   UCF, and the P2P/no-privileged-node and clock-free-fetch owner invariants.
3. dao.stream.http.md must know nothing of Datastar, SSE or yin.vm; the
   Architect said it names none, but grep finds one "datastar" mention.
4. Internal consistency, dangling cross-references, nonexistent section
   numbers, and examples that do not parse as EDN.
5. Whether the Q6 ruling (body by address over WebSocket, patch carries a
   notice) is sound.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <exact thread ID>

Then report: severity | file:line | invariant/evidence | recommended correction,
and confirm which checks passed.

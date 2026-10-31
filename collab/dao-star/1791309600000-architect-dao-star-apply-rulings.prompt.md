Created-GMT: 2026-10-06 18:00:00 GMT
Created-Local: 2026-10-07 01:00:00 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027 (resumed)
# Task: dao.star apply owner rulings
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-07 00:30:19 +07 | Status: active | Rationale: resume of the gaps pass; owner answered the open questions

You may now EDIT, only these two files in this worktree
(/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-orch):
- docs/design/dao.star.md (copied from the main tree; edit in place)
- docs/design/dao.stream.http.md (new)
Touch nothing else. Do not stage or commit.

Owner answers, quoted verbatim ("N." = your open-question number):
> 1. yes let's wait
> 2. yes
> 3. let's go with the recommendation
> 4. dao.lease should be used for this to expire parked continuation nobody returns to
> 5. yes
> 6. don't know

Orchestrator reading (paraphrase, not a quote; flag any misreading):
1. Mode 2 waits for UCF version 1: write it as a deferred/proposed section,
   conditional on fenced custody and a dao.space transactor, and make
   completion criterion 2 conditional accordingly.
2. Migration back: allow-listed peers only until effect handlers honor a
   delegated set.
3. Stale request: one-shot with re-projection; multi-shot stays opt-in.
4. Abandoned parked continuations are reclaimed by a dao.lease on the
   occurrence; specify it consistently with your Gap 3 section.
5. The unguessable occurrence token is an acceptable sole gate for Mode 1.
6. The owner has no answer. This one is yours to RULE, as Architect: decide
   whether the handoff body travels by address over the browser's WebSocket
   channel (changing criterion 2) or otherwise, with reasons and rejected
   alternatives. Mark it clearly in the doc and in your report as an
   architect ruling the owner has not confirmed.

Work:
1. Apply your Gaps 1-5 sections and the defect-table corrections to
   dao.star.md (the HTTP boundary section moves to dao.stream.http.md, which
   dao.star.md links). Also resolve glm's two additions: a first-page-load
   story and the fat client's local projection path (rule on each, or mark
   them explicitly deferred), and use the correct UCF doc name,
   docs/design/yin.vm.universal-continuation-format.md.
2. Keep the doc's voice and the blog's framing; no contradiction of the blog.
3. Keep it as short as the content allows.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027

Then list what changed per file, the Q6 ruling with reasons and rejected
alternatives, and anything you could not resolve.

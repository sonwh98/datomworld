Created-GMT: 2026-09-14 21:58:00 GMT
Created-Local: 2026-09-15 04:58:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: b53cafde-1f8b-477e-b33c-2dd4f3fbffc7
# Task: Review Tuples Architecture vs Owner Clarifications
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 04:58:00 | Status: active | Rationale: Fable context

The owner has clarified two critical architectural points about the `yin.vm` design:
1. The Map AST is the Universal AST's canonical representation that gets evaluated by the ast-walker (a LISP using maps, not a LISP using vectors instead of lists). However, it is converted into flat per-node tuple rows that act like bytecode, preserving everything so the semantic VM can execute it quickly because of the linearization.
2. All `yin.vm` evaluators are `dao.stream` observers that load rows to evaluate code; concurrently, another observer, `dao.space.index`, observes the same stream and indexes it into `dao.jing` to make the code queryable via datalog with `dao.space.query/q`.

The orchestrator has staged these exact wording changes into the intro and §7.1 of `docs/design/yin.vm.code-as-tuples.md` (which you can see via `git diff`). 

Your task as Architect Reviewer:
Read the unstaged diff and the rest of the document. Does the rest of the document (r8) fundamentally contradict this new clarified framing, or is it basically sound? Note any minor phrasing inconsistencies that need to be fixed to fully align the document with these two clarifications.

Write your findings to `collab/1789421900000-architect-tuples-rereview-r8.claude-fable-5-1.findings.md`. 
If there are no blocking contradictions, state APPROVE.

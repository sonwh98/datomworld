Created-GMT: 2026-10-04 23:30:00 GMT
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)

# Task: rule on C8 completion's open design questions (read-only; final response is the deliverable)
Role: Architect

Read: collab/1791112800000-compiler-engineer-ucf-c8-completion.findings.md (the engineer's report and its open questions; the code is on branch ucf-c8-completion in /Users/sto/workspace/datomworld-c8complete, namespace src/cljc/yin/vm/ucf/authority/completion.cljc, commit c6373817), UCF docs/design/yin.vm.universal-continuation-format.md (completion, successor chain, 7.2.1 body kinds including :halted, 7.7.x, 7.11.1 clauses 4 and 9, as amended on master), docs/design/yin.vm.linker.dht.md 14.2.2 to 14.2.4, the plan collab/1791097400000-architect-linker-m-next-c-plan-r2.claude-fable-5-1.findings.md slice C8. Landed on master: C1 to C7 and C10 (src/cljc/yin/vm/ucf/).

Rule decisively on:
1. HALTED SUCCESSORS (the engineer's decision 1; this is the one that matters). C8 refuses a successor whose body kind is :halted (a computation that finished and carries a result, UCF 7.2.1), so a computation that halts can never complete: its occurrence returns to offered after release, can be granted again, and the task re-run from its last checkpoint, re-executing effects. Is that right, or must a halted body be an admissible successor/completion ("a result as successor": UCF text says what?) so the chain terminates in a result and no further grant is possible? State the exact rule: what a release carrying a halted result commits (closure and edge to a result occurrence? closure only? an edge target kind?), what checks apply to a halted body (the checkpoint inspector's halted rules: origin required, no counter), whether the successor must be offered/admitted as an occurrence at all, and the effect on the closed occurrence (never granted again), orphan detection and the acyclic chain. Give the exact code-facing delta for completion.cljc and the fold arms, and a test list.
2. The recorded :yin.k/resumed fact (the engineer added a third fact kind so the holder's report survives a crash between the report and the release): accept or amend, and say where UCF 7.7.2's authority-authored fact table must list it.
3. A successor whose predecessor this ledger never saw is admitted as a first offer (C5 behavior that C5/C6 tests depend on): acceptable, or must an origin naming an unknown predecessor be refused? Consider the migratory-computation case where the predecessor lived on another authority.
4. Any other open question in the engineer's report that changes behavior (not wording).
Be specific and brief; a survey is a failure. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

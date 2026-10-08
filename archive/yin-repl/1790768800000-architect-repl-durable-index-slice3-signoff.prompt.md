# Architect sign-off: durable index store, slice 3 (rehydration and (reset) continuity)

Role: Architect (read-only).
- Worktree: /Users/sto/workspace/datomworld-durable-index, branch repl-durable-index. Slice 2 is fbc30186; slice 3 is uncommitted (use git diff).
- The author is claude-opus-5-5.
- Brief: collab/1790768800000-storage-engineer-repl-durable-index-slice3.prompt.md.
- Report: collab/1790768800000-storage-engineer-repl-durable-index-slice3.claude-opus-5-5.report.md.
- Binding ruling: collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md §3 and §5.

Verify adversarially:
- The rehydrated log reproduces each datom's original `t` and grouping.
- The next `t` and entity ids can never collide with restored ones, including metadata ids and retractions.
- `q` answers before any evaluation.
- `(reset)` and VM selection in durable mode keep the log, ids, counts and manifest; mem mode is unchanged.
- Session provenance stays distinct.
- The first post-restart HEAD covers old plus new facts.
- A snapshot with retractions or history (`:view :history`) is restored faithfully.
- Tests would fail if each property broke.
- Portability traps: reader-conditional order, array-map, cross-namespace #'private.
- No regression against the slice-2 findings.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table.

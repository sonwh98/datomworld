# Architect sign-off: $ast slice 4 (pure-data occurrence rules, subvec builtin, Dart `%` reader fix)

Role: Architect (docs/agents/roles/architect.md). This is a read-only review.
- Tree: /Users/sto/workspace/datomworld, master. The change is uncommitted; use `git diff`.
- Ignore docs/orchestrator-log.md and the collab/ files.
- The author is claude-opus-5-5.

Binding inputs:
- Architect ruling: collab/1790764371000-architect-repl-free-variable-rules.claude-fable-5-1.findings.md (§1–§4).
- Owner decisions: collab/1790765200000-orchestrator-free-variable-rules-owner-decisions.md (all four recommendations accepted).
- Brief, including the scope extension: collab/1790765200000-query-engineer-ast-slice4-occurrence-rules.prompt.md.
- Report: collab/1790765200000-query-engineer-ast-slice4-occurrence-rules.claude-opus-5-5.report.md.

Verify adversarially:
1. The new `occurrence-rules` matches ruling §1:
   - It is equivalent to the old rules on every tree shape.
   - It is root-scoped.
   - Its clause order protects the subvec bounds; check `plan-where`.
   - No caller still uses `:fns`, `p-up`, `occ-anc` or `member?`.
2. The `subvec` builtin:
   - arities;
   - refusal text identical on every host;
   - `vector?` / `content=` / CBOR vector encoding.
3. The REPL e2e tests meet §4 acceptance item 4 on all four VMs, including the rule set typed in the user's own `(def …)` line.
4. The Dart reader fix (the Dart branch of yin/repl.cljc):
   - Is the pre-read `%` token scanner correct for strings, comments, character literals, `#()`, `%1` and `%&`, regex literals, and tagged literals?
   - Can it corrupt any other input?
   - Is the metadata-copying restore sound?
   - Is this the smallest safe fix, given that the upstream ClojureDart reader has no options? Should it be scoped narrower, for example applied only when the input contains a bare `%`?
5. Would the tests fail if each property broke? Check portability traps: reader-conditional order, array-map, cross-namespace #'private.
6. Also note the stale reference at docs/design/yin.vm.code-as-tuples.implementation-plan.md:557.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table.

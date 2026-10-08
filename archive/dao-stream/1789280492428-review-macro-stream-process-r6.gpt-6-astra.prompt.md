Created-GMT: 2026-09-13 06:21:32 GMT
Created-Local: 2026-09-13 13:21:32 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61 (resumed)

# Task: Round-6 Confirmation — Macro Expansion as a Stream Process (r6 folds in your round-5 P2 and P3)
Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 13:21:32 +0700 | Status: active | Rationale: Same reviewer confirms its own round-5 findings.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (1045 lines, revision 6). Untracked; read it directly. Appendix C has a round-5 table (rows 31–32).

## What changed
- **Your P2 (`m` is not an origin marker):** §3.1 step 5 no longer tries to classify definitions by origin at all. It recomputes the next store as: start from the store *before* step 2; walk the **final tree** in the fixed traversal order; every `(yin/def <literal sym> v)` occurrence `assoc`es (macro lambda) or `dissoc`es (anything else); every stand-in literal that step 3 recorded in `:replaced {eid sym}` (an explicit internal record, independent of `m`) `assoc`es its source macro; last in tree order wins. Position is a tree property, so re-parenting cannot lose it; a definition formed by operator rewrite is present in the tree like any other. Both your examples — `(defn m [x] (wrap x))` with `wrap` a macro, and `((choose-def) 'm f)` — are in Phase 1 with expected removal. A new stated consequence: a source macro definition that an enclosing macro *discards* is in force for the current batch and absent from the next.
- **Your P3:** §2.3 drops the inline macro lambda as a supported call-site form; `macro-of`'s lambda arm is marked defensive/unreachable.

## What to do
1. Resolved / partially / not for the round-5 P2 and P3.
2. Re-run all eight round-5 reductions plus your two new cases against the new rule. In particular check the two re-parented generated-`defn` cases that failed in r5.
3. Probe the new rule for a hole: is there a `yin/def` occurrence in the final tree that should *not* be ranked, or a stand-in literal that could be confused with a source literal `'m`? (Note `:replaced` is keyed by the stand-in's eid, and step 3 runs before expansion; consider whether a stand-in's eid can be lost by re-parenting — the stand-in is a leaf, so it is never copied, but say whether you agree.)
4. Anything new r6 introduced.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra
```
Then the resolution status, the reduction table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.

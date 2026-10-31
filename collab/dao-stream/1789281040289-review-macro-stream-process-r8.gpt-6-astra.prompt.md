Created-GMT: 2026-09-13 06:30:40 GMT
Created-Local: 2026-09-13 13:30:40 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61 (resumed)

# Task: Round-8 Confirmation — Macro Expansion as a Stream Process (r8 folds in your round-7 P2 and probes)
Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 13:30:40 +0700 | Status: active | Rationale: Same reviewer confirms its own round-7 findings.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (1111 lines, revision 8). Untracked; read it directly. Appendix C has a round-7 table (rows 35–37).

## What changed
- **P2 (name-only stand-ins ambiguous across same-name definitions):** step 2 numbers every source macro definition it saw, in harvest order, into a batch-local catalogue `:declared {k {:name sym :lambda ast}}`. The stand-in is now `{:type :yin/macro-defined :name sym :decl k}`. Step 5 resolves a stand-in by `k`, requires the entry's `:name` to equal the stand-in's, and `assoc`es *that entry's* lambda; anything else is ignored. So `(keep-first (defmacro m … :A) (defmacro m … :B))` keeps A for the next batch even though B won step 2 for the current batch — stated in the consequences. Validator row updated (`:decl int`).
- **Fabrication precedence:** step 3 now states explicitly that a valid fabricated stand-in may *move* a harvested declaration's effective position (e.g. re-assert it after a plain `yin/def`) but can never introduce an unharvested body, and calls this permitted transformer behaviour. Phase 1 has the test.
- **Declaration-order claim narrowed:** step 5 says it recovers source order for yang.clojure `do`/`let` and the Python/PHP suite shape, is deterministic elsewhere, and names PHP `for`'s fixpoint operator as the case where it is not source order.
- Phase 1 adds your acceptance cases: keep-first, keep-second, swap, duplicate, unknown `:decl`, known `:decl` with mismatched name, valid fabricated stand-in after a plain def.

## What to do
1. Resolved / partially / not for the round-7 P2, and whether the two probe items are adequately stated.
2. Re-run your `keep-first` reduction and the swap/duplicate variants against the `:decl` rule.
3. Probe: can a macro alter `:decl` on a stand-in it received to select a *different* same-name declaration (yes — say whether that is acceptable under "may move, never introduce", given both bodies were harvested from this batch), and can a stale `:decl` from a *previous* batch be replayed (the catalogue is batch-local; a stand-in only exists inside one batch's expansion — confirm or refute).
4. Anything new r8 introduced.

If nothing blocking remains, say APPROVE.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra
```
Then the resolution status, reductions, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.

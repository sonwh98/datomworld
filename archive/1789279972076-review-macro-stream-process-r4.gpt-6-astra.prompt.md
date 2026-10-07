Created-GMT: 2026-09-13 06:12:52 GMT
Created-Local: 2026-09-13 13:12:52 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61 (resumed)

# Task: Round-4 Confirmation — Macro Expansion as a Stream Process (r4 folds in your round-3 findings)
Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 13:12:52 +0700 | Status: active | Rationale: Same reviewer confirms its own round-3 findings; independence preserved.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (993 lines, revision 4). Untracked; read it directly. Appendix C now has a round-3 table (rows 18–27) mapping each of your round-3 findings to its resolution.

## What changed for your findings
- **P1-1 (operands expanded before operator re-check):** §3.2 now has a dedicated `:application` branch: expand the operator first, rebuild, re-check `macro-of`; if it is now a macro call, take the invocation arm with the **original** operands; only otherwise expand the operands. Your depth assessment is adopted verbatim in the prose below the pseudocode. Phase 1 test uses your `((choose) f [x] (x 1))` case and the discarded-failing-operand case.
- **P2-2 (`defn m` after `defmacro m`; non-lambda redefinitions):** §3.1 step 2 `dissoc`es on *any* non-macro value (plain lambda, variable, literal). New step 5 "Post-harvest" scans the final expanded tree for `(yin/def <sym> <non-macro>)` and `dissoc`es — with an explicit ordering rule: step 2's store governs the current batch; step 5's removals take effect next batch. The doc states the intra-batch consequence and accepts it.
- **P2-3 (inline generated macro lambda both required and forbidden):** prohibition kept and widened to `:generated-macro` (definition or inline); the contradictory Phase 1 test case removed; `macro-of`'s inline case is documented as source-only; both reserved together with the reason.
- **P2-4 (`:errors` never drained; A-ok/B-error suppresses A):** §5 adds `:forwarded` and `drain-errors` (read-and-reset); §6.1 drives the evaluator on `:forwarded > 0` *independently* of `:errors`, prints errors if any, and states the one-batch-per-input correlation.
- **P2-5 (admission root-reachable only):** §3.1 step 1 checks every entity in the index, and says why (harvest reads disconnected definitions).
- **P2-6 (admission failure has no event):** §4.1 defines the admission-failure event (type, source-batch, error, timestamp only), allocated from the watermark without an index; diagnostics name entities, never carry values; §3.1 step 1 result carries it.
- **Your §5 qualifications:** table reworded — `closed`/`invalid-value`/`transport-error` are protocol outcomes the forwarder cannot continue from, preserved in the throw; `full` may be permanent. "Not crash recovery" added.
- **Observer prerequisite:** per glm's caution, the fix now **keeps the throw** and carries `:session` in `ex-data`; your load-vs-flush cursor distinction is specified (load failure → cursor before B; run/flush failure → cursor after B with `:vm` as `run-vm` left it). Phase 0 tests match.
- Decision 5 qualified per §2.2; decisions 10/11 reordered.

## What to do
1. Per-finding: resolved / partially / not, with section or gap.
2. Re-trace `((choose) f [x] (x 1))` through the new §3.2 branch and confirm `defn` receives `(x 1)` unexpanded and the resulting lambda body still contains `(x 1)` after re-expansion.
3. Check the step-5 ordering rule for a hole: is "current batch uses step-2 store, removals effective next batch" deterministic and does it admit any silent-wrong-code case comparable to the one glm found in r2?
4. Anything new r4 introduced.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra
```
Then the resolution table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.

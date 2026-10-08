Created-GMT: 2026-09-13 06:04:10 GMT
Created-Local: 2026-09-13 13:04:10 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61 (resumed)

# Task: Round-3 Review — Macro Expansion as a Stream Process (r3 folds in your round-2 findings)
Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 13:04:10 +0700 | Status: active | Rationale: Same reviewer confirms its own round-2 findings were resolved; independence from the author's model family preserved.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (899 lines, revision 3, 2026-09-13). Untracked file; `git diff` will not show it — read it directly.

## What changed since your round-2 review
Every one of your 11 findings has a resolution in the new **Appendix C** (a table mapping finding → section). The substantive changes:

- **Decision 11 (new):** an expansion failure is data, not a throw. `expand-batch` returns `{:status :ok|:error ...}`; the event goes to the log, nothing to `program-out`, the cursor advances, the driver reads `:errors`. Throws are reserved for forwarder defects. (Your P2-4, glm's P2-1.)
- **§5 flush:** per-medium staging (`:out-staged`, `:log-staged`), at-most-once per medium, outcome table for `full`/`closed`/`invalid-value`/`transport-error`, atomicity explicitly *not* promised. (Your P1-2.)
- **§5 prerequisite + Phase 0:** your P1-1 (later exception replays earlier batches) is accepted as a defect of `stream-observer/run-on-stream` — not of this design — and becomes a Phase 0 deliverable on the observer: return progress with the error. The doc states it depends on that fix and does not work around it. **Please say whether you agree with placing it there rather than in this design.**
- **§3.2 algorithm:** rewritten over eids in the index; event allocated *before* the depth guard; the ordinary branch re-checks a rebuilt node once when its operator became a macro (your `((choose) x)` case); generated definitions rejected in v1 as `:generated-definition`, macro-defining macros reserved. (Your P1-3.)
- **§3.1 admission step:** cycle, dangling-ref, and host-value checks run over the index *before* any recursive decode; "working representation" paragraph added. (Your P2-10.)
- **§3.1 allocation:** seed includes `(:next-eid alloc)`; retry reuses staged allocation. (Your P2-5.)
- **§3.1 scan:** only checks for `:macro?` lambdas; application fixpoint is by construction. (Your P2-6.)
- **§3.4 mark-tail:** whole-tree recompute with per-node context rules including `:dao.stream.apply/call` and stream ops; stale flags removed; lambda body always tail, `linearize` owns inlining. (Your P2-7.)
- **§4.1/§4.2 provenance:** `:yin/source-batch`; nested `source-call` into the log copy; `expansion-root` names the log copy and program↔log correspondence is explicitly not promised; `macro/event-schema`; `:yin/macro-name` absent for inline lambdas. (Your P2-8.)
- **§3.3 sandbox:** operands validated going in; `:eval`/prelude trust contract stated; fuel-vs-host-primitive and payload-size limits recorded as known v1 limits, with payload bounds at admission reserved. (Your P2-9.)
- **§3.1/§3.3/§2.2:** "as in Clojure" removed and the harvest policy stated on its own terms; "helpers are other macros" corrected to prelude-only with body expansion reserved; a "two namespaces" paragraph on the expander store vs evaluator store. (Your P3-11.)
- **glm's findings also folded:** store `dissoc` on plain redefinition; `program-out` carries `default-op` only; Phase 0 test "modulo the root fact"; Phase 2 `:semantic` test deferred to the semantic spec's phases with continuation-depth measurement; `repl-state` lists store names.

## What to do
1. For each of your round-2 findings, state **resolved / partially resolved / not resolved**, with the section that resolves it or the gap that remains.
2. Re-trace the two flows you reproduced last round against the new §5: (a) A ok, B `:error` result, next round — exactly one A and one B-event; (b) `out=ok, log=full`, retry — exactly one program append. Say whether the per-medium staging as written achieves at-most-once, and whether any outcome in the table is misclassified.
3. Check the new §3.2 for regressions: is the single re-check after rebuilding a node sufficient, or can a chain (operator → macro name → expansion whose operator → macro name …) escape depth accounting? Is the "same depth" choice for the re-check correct?
4. Anything new the r3 changes introduced.

Keep to findings that would change the design; do not re-litigate the architectural pivot, which you approved.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra
```
Then a per-finding resolution table, then any new findings by severity (`[P1 — blocking]`, `[P2 — must address]`, `[P3 — suggestion/alignment]`), then an explicit verdict: APPROVE or REQUEST CHANGES.

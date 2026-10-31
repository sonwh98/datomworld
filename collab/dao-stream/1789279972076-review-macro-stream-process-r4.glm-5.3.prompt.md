Created-GMT: 2026-09-13 06:12:52 GMT
Created-Local: 2026-09-13 13:12:52 +0700 (+07)
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9 (resumed)

# Task: Round-4 Confirmation — Macro Expansion as a Stream Process (r4 folds in your round-3 findings)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 13:12:52 +0700 | Status: active | Rationale: Same reviewer confirms its own round-3 findings; independence preserved.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (993 lines, revision 4). Untracked; read it directly. Appendix C now has a round-3 table (rows 18–27).

## What changed for your findings
- **P2-1 (`:errors` never drained):** §5 adds `:forwarded` (count of `ok` appends to `program-out` since last drain) and `drain-errors : expander → [expander' {:errors :forwarded}]`, read-and-reset, called by the driver once per round after `run-on-stream` returns. §6.1: drive the evaluator iff `:forwarded > 0`; print `:errors` iff non-empty; both may hold in one round (A forwarded, B failed → A runs once, B reported once). Phase 1 tests: drained expander reports nothing on the next drain; A-fail/B-ok in one call gives `:forwarded 1` and one error.
- **P3-2 (`run-on-stream` error shape):** your option (a) adopted — keep the throw, carry `:session` in `ex-data`; the rejected return-shape and the reason (un-updated callers would continue silently) are stated. astra's load-vs-flush distinction is folded in: load failure carries the cursor before B; run/flush failure carries the cursor after B and `:vm` as `run-vm` left it. Phase 0 tests updated.
- **P3-3 (closed vocabulary at admission):** §3.1 step 1 adds `:unknown-type`; admission now checks every entity in the index (astra's P2-5), not just root-reachable.
- **Doc nits:** §3.4 gains the "exact under the current no-inline lowering contract" sentence; decisions 10/11 reordered.
- **astra's r3 findings also folded:** §3.2 application branch expands the operator first and re-checks before touching operands (his P1); §3.1 step 2 `dissoc`es on any non-macro value and a new step 5 post-harvests the final tree for `(yin/def m <non-macro>)` effective next batch (his `defn m` after `defmacro m`); generated macro lambdas prohibited everywhere as `:generated-macro`; admission-failure event in §4.1.

## What to do
1. Per-finding: resolved / partially / not, with section or gap.
2. **Re-verify §5/§6.1 against `stream_observer.cljc` with the drain in place:** bad input, then good input, two rounds — does the second round run? A-ok then B-error inside one `run-on-stream` call — does §6.1 run A exactly once and report B exactly once? Confirm `:forwarded` is incremented at the right place (flush `ok` on `:out`, not at load).
3. **Sanity-check §3.1 step 5's ordering rule** (current batch uses the step-2 store; post-harvest removals effective next batch) for a silent-wrong-code case of the kind you found in r2.
4. Anything new r4 introduced.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime Review | Model: glm-5.3
```
Then the resolution table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.

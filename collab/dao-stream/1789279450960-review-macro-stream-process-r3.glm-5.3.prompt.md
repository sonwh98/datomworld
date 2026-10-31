Created-GMT: 2026-09-13 06:04:10 GMT
Created-Local: 2026-09-13 13:04:10 +0700 (+07)
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9 (resumed)

# Task: Round-3 Review — Macro Expansion as a Stream Process (r3 folds in your round-2 findings)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 13:04:10 +0700 | Status: active | Rationale: Same reviewer confirms its own round-2 findings were resolved; independence from the author's model family preserved.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (899 lines, revision 3, 2026-09-13). Untracked file; `git diff` will not show it — read it directly.

## What changed since your round-2 review
Every one of your 9 findings has a resolution in the new **Appendix C** (a table mapping finding → section). The substantive changes:

- **Decision 11 (new):** an expansion failure is data, not a throw. `expand-batch` returns `{:status :ok|:error ...}`; the event goes to the log, nothing to `program-out`, the cursor advances, the driver reads `:errors`; the REPL prints the error and the next input evaluates normally. Throws are reserved for forwarder defects (codec, index). (Your P2-1.)
- **§5 flush:** per-medium staging `:out-staged`/`:log-staged` — your option (a); at-most-once per medium; outcome table for `full`/`closed`/`invalid-value`/`transport-error`; atomicity across the two media explicitly not promised. (Your P2-2.)
- **§3.1 harvest:** every `yin/def` whose value is a lambda is store-authoritative — `:macro? true` → `assoc`, plain → `dissoc`; §2.2 gains a "two namespaces" paragraph on expander store vs evaluator store; Phase 1 test added. (Your P2-3.)
- **§3.1 allocation:** seed is `(min (:next-eid alloc) (dec min-eid-in-batch) (- (inc first-user-id)))`. (Your P3-4.)
- **Decision 9 / §4.1:** `program-out` datoms carry `default-op` only; `m = ev` only on the log copy; `yin.vm.macro/event-schema` is the home for the event attributes dropped from `yin.vm/schema`. (Your P3-5.)
- **Phase 0 test:** "unchanged modulo the root fact"; the fact is unconditional. (Your P3-6.)
- **Phase 2:** the `:semantic` assertions are deferred to the semantic spec's Phases 1–2, with a continuation-depth measurement. (Your P3-7.)
- **§3.1 "working representation" + §3.2:** traversal is over eids in the index, maps materialised only at the body-invocation boundary; admission checks cycles/dangling refs before any decode; the final scan checks only for `:macro?` lambdas (application fixpoint is by construction, now including a one-time re-check of a rebuilt node whose operator became a macro name). (Your P3-8.)
- **§6.1:** `repl-state` lists the expander store's macro names. (Your P3-9.)
- **astra's findings also folded:** operator-rewrite fixpoint case, generated definitions rejected in v1, whole-tree `mark-tail` recompute, `:yin/source-batch` on events, operand validation on the way in, and — notably — astra's P1-1 (a `run-on-stream` that forwards A then throws on B replays A on retry) accepted as a *generic observer defect* and placed as a Phase 0 deliverable on `stream-observer`, not in this design.

## What to do
1. For each of your round-2 findings, state **resolved / partially resolved / not resolved**, with the section that resolves it or the gap that remains.
2. **Verify the new §5 against `stream_observer.cljc` again**, as you did last round: with the `:error` result staged as log-only and `ready?` defined over both slots, trace (a) bad batch then good batch across two rounds — does the REPL flow in §6.1 actually un-jam, and does the cursor advance on the `:error` load as claimed? (b) `out=ok, log=full` then retry — exactly one program append? (c) no log medium configured — does `:log-staged` stay nil and `ready?` behave?
3. **Verify the §5 prerequisite claim against the code:** does `run-on-stream` as written really publish the successor session only on return, such that a throw after a forwarded batch loses progress? Is "return `{:observer :vm :error}`" the right shape for that fix, and is it correctly a generic observer change rather than something this design should absorb?
4. **§3.4 mark-tail recompute:** check the per-node context rules against what `linearize` would need and against the walker's structural TCO; is "lambda body always tail; `linearize` owns inlining" sound, or does it push a real problem into a spec that does not exist yet?
5. Anything new the r3 changes introduced.

Keep to findings that would change the design; do not re-litigate the architectural pivot, which you approved.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime Review | Model: glm-5.3
```
Then a per-finding resolution table, then any new findings by severity (`[P1 — blocking]`, `[P2 — must address]`, `[P3 — suggestion/alignment]`), then an explicit verdict: APPROVE or REQUEST CHANGES.

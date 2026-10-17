Created-GMT: 2026-10-14 04:20:26 GMT
Created-Local: 2026-10-14 11:20:26 +07
Coding-Agent: claude (claude-fable-5-1, read-only)
Session-ID: 547851ca-eeb1-4805-833e-a978eb20b395

# Task: Adversarial Code Review of Slice L-d-1 (`try-require` primitive & refusal-as-data)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-14 11:20:26 +07 | Status: active | Rationale: Independent model family reviewer (Anthropic family reviewing OpenAI Codex author)

Worktree to review: `/Users/sto/workspace/datomworld-ld1` (uncommitted diff vs `master@47d6bcaa` on branch `linker-ld1`).

Review the `git diff` read-only per the Q6 review rule (`docs/agents/known-gaps.md`):
- Blocking = in-scope P0/P1, or P2 in claimed acceptance or newly introduced behavior.
- Findings in code the slice should not have written mean revert.

Context & Brief to inspect:
- Brief: `collab/1791950875733-engineer-ld1-try-require.prompt.md`
- Engineer report: `collab/1791950875733-engineer-ld1-try-require.findings.md`
- Scoping design: `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` (§1, §3)
- Mob consolidation: `collab/1791950575268-mob-consolidation-i4.md`
- Governing specs: `docs/design/yin.vm.linker.md` §7.2 step 7, `docs/design/yin.vm.linker.dht.md` §7.5.3, §9
- Modified source files:
  - `src/cljc/yin/vm.cljc`
  - `src/cljc/yin/vm/engine.cljc`
  - `src/cljc/yin/vm/module.cljc`
  - `test/yin/vm/linker_require_test.cljc`
  - `docs/design/yin.vm.linker.md`
  - `docs/design/yin.vm.linker.dht.md`

Checklist:
1. Verify the bare primitive `try-require` in `yin.vm.cljc` mints `:module/try-require {:module ns-sym}` with profile `:effectful [1] #{:module/try-require}`.
2. Verify `require-handler` vs `try-require-handler` in `yin.vm.module.cljc`:
   - Shared logic in `handle-require`, `try?` flag.
   - Cycle detection returns `{:status :refused, :reason :require-cycle, ...}` without throwing under `try?`.
   - Host registry hit answers `{:status :ok, :module module-name}` under `try?`.
   - Wait entries for `:install` and `:link-request` carry `:try? true`.
   - Fallback `(or (get park-entry-fns :module/try-require) (get park-entry-fns :module/require))` operates cleanly.
3. Verify engine outcome delivery in `yin.vm.engine.cljc`:
   - `refused-entry` delivers `(ready entry refusal)` when `:try?` is true.
   - `wake-installed` and `settle` wrap success in `{:status :ok, :module module-name}` when `:try?` is true, preserving plain symbol delivery for normal `require`.
   - `abandon-link` and `abandon-installs` deliver `{:status :lost, :reason ...}` as data to try waiters.
   - Mixed co-waiters on the same install (plain `require` + `try-require`) deliver uncatchable error to `require` and data to `try-require`.
4. Portability:
   - Check all new test code in `test/yin/vm/linker_require_test.cljc`: no `with-redefs`, no Java interop without cljd-first reader conditionals, no non-portable seq iterations.
5. Invariants:
   - Language-blindness maintained (no Python/language notions in VM/engine/module).
   - Invariant: `try-require` changes outcome delivery only, never whether verification, admission, or install checks happen.

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 547851ca-eeb1-4805-833e-a978eb20b395

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
Give an explicit verdict: APPROVE, APPROVE-WITH-NITS, or CHANGES_REQUESTED.
Do not edit files.

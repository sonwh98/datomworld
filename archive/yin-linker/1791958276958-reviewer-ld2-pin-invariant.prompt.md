Created-GMT: 2026-10-14 06:12:00 GMT
Created-Local: 2026-10-14 13:12:00 +07
Coding-Agent: claude (claude-fable-5-1, read-only)
Session-ID: pending (provider-generated)

# Task: Adversarial Code Review of Slice L-d-2 (registry pin invariant `module/pin-conflict` & present-and-unequal install check)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-14 13:12:00 +07 | Status: active | Rationale: Independent model family reviewer (Anthropic family reviewing OpenAI Codex author)

Worktree to review: `/Users/sto/workspace/datomworld-ld2` (uncommitted diff vs `master@f484b5f3` on branch `linker-ld2`).

Review the `git diff` read-only per the Q6 review rule (`docs/agents/known-gaps.md`):
- Blocking = in-scope P0/P1, or P2 in claimed acceptance or newly introduced behavior.
- Findings in code the slice should not have written mean revert.

Context & Brief to inspect:
- Brief: `collab/1791957103921-engineer-ld2-pin-invariant.prompt.md`
- Engineer report: `collab/1791957103921-engineer-ld2-pin-invariant.findings.md`
- Scoping design: `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` (§2.2, §3 acceptance row L-d-2)
- Mob consolidation: `collab/1791950575268-mob-consolidation-i4.md`
- Governing specs: `docs/design/yin.vm.linker.md` §4.3, `docs/design/yin.vm.linker.dht.md` §7.4
- Modified source files:
  - `src/cljc/yin/vm/module.cljc`
  - `src/cljc/yin/vm/engine.cljc`
  - `test/yin/vm/linker_require_test.cljc`
  - `test/yin/vm/module_test.cljc`
  - `docs/design/yin.vm.linker.md`
  - `docs/design/yin.vm.linker.dht.md`

Checklist:
1. Verify `module/pin-conflict` in `src/cljc/yin/vm/module.cljc`:
   - Enforces the pure registry invariant: A registry never holds a linked module under a name at an address that differs from the address any linked manifest in that registry pins for that name.
   - Case (a): Some linked manifest in the registry pins `module-name` at an address other than the entry's `:address`.
   - Case (b): The entry's own manifest pins a name that the registry already holds at an address other than the pinned address.
   - Deterministic sorting of conflicts so selection is stable across hosts.
   - Refusal shape matches `:status :refused, :reason :dependency-pin-mismatch, :module module-name, :name conflicting-name, :pinned pinned-addr, :linked linked-addr`.
2. Verify `receive-module` in `src/cljc/yin/vm/engine.cljc`:
   - Checks `module/pin-conflict` before mutating or attaching images/resources/stores/registry.
   - Rejection mechanism preserves existing exception-to-refusal handling at child spawn, parent act 3, and parent act 4.
   - Confirms that registry and stores remain untouched when a conflict is refused.
3. Verify `link-install` in `src/cljc/yin/vm/engine.cljc`:
   - Present-and-unequal refuses; absent dependency is permitted during install.
   - `(:absent mode)` in tests now installs cleanly.
4. Verify tests and portability:
   - `module_test.cljc`: unit tests for `pin-conflict` across cases (a), (b), matching, absent, deterministic tie-breaking.
   - `linker_require_test.cljc`: flipped `:absent` mode of `dependency-pins-are-checked-without-module-obligations-test` to link ok; deferred pin followed by mismatched require/try-require refuses; concurrent parent receive conflicts refuse.
   - Portability: no `with-redefs`, start maps from `{}`, no host-specific interop without cljd-first conditionals.
5. Invariants:
   - Language-blindness maintained (no language or frontend concepts).
   - Address $A$ is the only code identity.
   - Derive, don't persist (invariant derived purely from manifests).

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <session-id>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
Give an explicit verdict: APPROVE, APPROVE-WITH-NITS, or CHANGES_REQUESTED.
Do not edit files.

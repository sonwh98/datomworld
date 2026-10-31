# Architect sign-off: linker over DHT, slice L3 (REPL DHT link source, pending, event-driven re-check)

Role: Architect (read-only).
- Worktree: /Users/sto/workspace/datomworld-linker-l2, branch linker-l3, from linker-l2 79c1e55d. L3 is uncommitted; use `git diff`.
- The author is claude-opus-5-5.
- Contract: docs/design/yin.vm.linker.dht.md §8 (entry, pending and re-check R11, budget-is-not-a-lease R12, implicit network R10), §9, §10 (plain API reused via host functions), §12 L3 (including the L0 Architect's amendment that yin.repl.link uses local-runtime).
- Also: docs/design/yin.repl.link-policy.md.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md.
- Report: the end of collab/1790825000000-engineer-linker-L3.claude-opus-5-5.stdout.log, plus any report file it names.

Verify adversarially:
- Every L3 acceptance bullet.
- The re-check fires ONLY on :loaded or :load-failed for the waited manifest, not on unrelated or repeated events.
- Function link policies count only this run's re-checks.
- Abandon and late answers.
- The three failure shapes, equal on all hosts.
- Dependency bindings checked before install.
- All four VMs, with no register fallback composed.
- The host functions are thin wrappers over the plain API, with no second loader in yin.repl.
- The content-store/content-client budget is unchanged.
- The banner and (help) state the implicit network.

RULE on the author's notes:
1. Behaviour change in M5 rollback: the shell keeps the advanced link pair after a refused link. Sound?
2. Head-of-line waiting behind an abandoned, still-loading require (existing behaviour). Acceptable, or must L3 fix it?
3. No unresolved-name diagnostics until L4.
4. Cross-host "raised data" is compared at the refusal map, not the engine's raised error.

Also check: owner invariants and portability traps.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table, then the rulings.

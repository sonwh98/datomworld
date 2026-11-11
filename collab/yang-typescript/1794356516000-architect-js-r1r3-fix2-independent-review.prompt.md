Created-GMT: 2026-11-11 00:21:56 GMT
Created-Local: 2026-11-11 07:21:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: 9DFAF853-45DB-4B9D-84E3-DE1292E97D31
# Task: JS R1+R3 — INDEPENDENT step-7 architecture review (full arc)

Role: Lead System Architect

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-11 07:21 +07 | Status: active | Rationale: standing architect lane (gpt-6.1-sol) is usage-walled until 10:33 local; the owner directed autonomous continuation via team.md routing; team.md Role Selection: any model may fill any role when profile fits — Qwen flagship for a high-stakes invariant review; Qwen family is independent of the Muse author of the code under review

You are an INDEPENDENT second architect for this round (the first, gpt-6.1-sol, will also review the same tip when the codex lane resets; your verdict stands on its own merits — do not defer to theirs, cite evidence). Read-only; edit nothing.

## The round under review (all artifacts of record — read in this order)

Repository: `/Users/sto/workspace/datomworld` (main checkout, master @ ~498cba4c).
Round worktree: `/Users/sto/workspace/datomworld-jsr13` (branch `exception-js-r1r3`,
tip `31287b17`, base master `4cfa609d`; pass `--include-directories` if you need to
read the worktree from another cwd).

1. `/Users/sto/workspace/datomworld/collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — OWNER RULING v2 (§R1, §R3, §R5, scope bounds, acceptance gate). The requirements.
2. `/Users/sto/workspace/datomworld/collab/exception-track/1794970001000-to-tsphp-your-js-and-php-rounds.md` — the js defects named (prelude.cljc:1581 entry ignoring the raise exit; :1627 discarded conditions).
3. `/Users/sto/workspace/datomworld/collab/exception-track/1794343000000-exception-track-surface-registry.md` — the ceded surface (the round must stay inside it).
4. `/Users/sto/workspace/datomworld/docs/design/yang.antlr.md` — §8.10.7 and §8.10.8 (the exception reception rules; cleanup/restore-before-transfer).
5. The complete round diff (base 4cfa609d, all three commits c0114a42/ce99e65e/31287b17):
   `/Users/sto/workspace/datomworld/collab/yang-typescript/1794355686000-architect-js-r1r3-fix2-confirm.prompt.md` names the fix2 diff file, and the two earlier exports are beside it:
   - `1794349141000-architect-js-r1r3-review.diff.txt` (round 1)
   - `1794350496000-engineer-js-r1r3-fix.diff.txt` (fix 1)
   - `1794353317000-engineer-js-r1r3-fix2.diff.txt` (fix 2)
   Post-change source for full context: `/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc` (~:1584-1592, :1653-1720, :4104 init install), `test/yang/call/exception_probes.clj`, `test/yang/call/exception_contract_test.clj`.
6. The engineer findings: `1794342830000-...findings.md` (round 1), `1794350496000-...findings.md` (fix 1), `1794353317000-...findings.md` (fix 2) in `/Users/sto/workspace/datomworld/collab/yang-typescript/` — UNTRUSTED reports.
7. The first architect's two verdicts: `1794349141000-architect-js-r1r3-review.gpt-6.1-sol.findings.md` (FIX-LIST: minted-forgery P1) and `1794352263000-architect-js-r1r3-fix-confirm.gpt-6.1-sol.findings.md` (FIX-LIST again: companion-mutation P1; ruling (b); authorized the narrow private-store mechanism now implemented).

## Verified evidence (orchestrator's own runs, reproduce if you wish)

All focused, laptop, JVM (semantic/stack/register backends), branch tip 31287b17:
- R5 forgery/identity set (minted, copied-wrapper, mutation-substitution, extracted-seal-stamping, store-root-unreachability) + typescript-provenance: 6 tests / 47 assertions, 0F/0E.
- kit conformance+exception-source: 6/131; ts source-call: 2/22.
- Fix2 pre-fix repro: the three new probes failed 11 assertions (mutation + seal-stamp red on all VMs) before the commit.

## Your review must answer

1. **R5 closure (P1 of record)**: does `31287b17`'s private lexical-capture store close the forgery class — minted cells, copied-metadata wrappers, companion mutation (no companion exists anymore), extracted-seal stamping (no seal exists anymore)? Or can admitted source still (a) substitute a forwarded condition, (b) authenticate an unrelated wrapper, (c) reach the store root (`cell/get` on wrapper contents, `:props`, runtime-keys, module-exports, global names, any prelude form)? Cite file:line evidence, not the reports.
2. **Init-install soundness**: the store installs once per task via a guest `let` in `js/init!`; pre-init stubs are replaced by `yin/def` before guest code runs. Find any path that observes the stub instead of the installed closure (bundled walker, linked execution, safepoint capture, module export enumeration, double-init races).
3. **R1/R3 core still intact**: the entry bridge (before-binding install, exactly-once delivery of escapes + binding failures, restore-before-transfer §8.10.8) and protocol retention (complete original condition forwarded on unchanged rethrow) — the fix2 rewrite must not have weakened round 1's R1.
4. **Bounded retention (open design question)**: the store never reclaims completed-call associations (unbounded growth per task; O(n) lookup). Rule: acceptable with a named ledger obligation, or require bounded retention now. Both are landable — say which and why.
5. **Surface discipline**: all three commits stay inside the registry's ceded regions + kit test files; donor tweak at exception_contract.clj:55 remains as accepted (the first architect ruled on it).

Distinguish architectural defects from implementation gaps or intentionally deferred work
(the generator-lifetime probe and directed-pair/suite-scale legs are named open obligations,
not defects). Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report per-item: R5 closure (severity | file:line | evidence | fix if not) | init-install |
R1/R3 intact | bounded-retention ruling | surface discipline | final verdict: SIGN-OFF or
FIX-LIST.

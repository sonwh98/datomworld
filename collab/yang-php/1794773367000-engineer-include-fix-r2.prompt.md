Created-GMT: 2026-11-16 (dispatch time)
Coding-Agent: codex (terra, laptop lane)
Session-ID: 01a1e0d4-9d90-7b42-9b32-2536d5dd2041 (codex thread.started, laptop POOL 1)

# Task: include-fix r2 — implement the astra ticks-SCC ruling (guarded trailing placement)

Worktree: /Users/sto/workspace/datomworld-incfix2 (branch fix/include-ticks-scc-r2, cut from master da3cb74c). One writer per branch; do not touch other trees. Do not push to master — push your branch only.

## The ruling (READ FIRST — it binds your implementation)
collab/yang-php/1794772328000-architect-ticks-scc-ruling.findings.md (on master da3cb74c). Verdict (b):
- REJECT any instrumentation-origin admission exemption; the linker's undischarged discharge rule (src/cljc/yin/vm/linker.cljc:1086) stays as-is.
- The regression cause: the ticks stage's dependency wrapper (src/cljc/yang/php/antlr/siteset.cljc:256, `(u/then (require-node 'php.sp) derived-module)`) puts member definitions inside a lambda body -> conditional -> undischarged.
- APPROVED fix: place the `php.sp` require LAST in the SCC module's wide application (definitions in operand position), e.g. `(core-module/module-tree [derived-scc-module (core-module/require-node 'php.sp)])`, or append the hook require to the SCC's existing operand vector. Astra verified in-memory: trailing placement discharges m0-body/m1-body on all three vector formats; the original fixture has ZERO selected tick sites — the wrapper shape alone caused the refusal.
- GUARDS: trailing placement only where module init does not execute instrumented guest bodies before the hook is available; zero selected hooks -> omit the wrapper entirely (but still derive/pin dependencies); manifest pins php.sp when referenced; derivation records/occurrence maps describe the FINAL transformed tree.

## The gate (per the ruling's decision table — all must pass)
1. `yang.php.antlr.include-test` — the original mutual-include fixture green on every scheduled vector backend, expected ["ab" [] nil]; explicit backend-result coverage (an empty result map must not pass).
2. An active-ticks cyclic variant (declare(ticks=1) in both members; astra measured six selected sites) green on every scheduled vector backend; linking/init produces no guest output or tick-handler execution before delivery.
3. Dependency ordering preserved: b < a < main for the acyclic row; the cyclic pair stays one publication step, bundle before aliases.
4. The linker's existing early-application negative tests stay green.
5. No other suite red: run the yang.php sub-suite and the include/eval-bridge tests; record the per-run log directory separately (never overwrite a shared sub-*.log).

## Deliverables
- Implementation commits on fix/include-ticks-scc-r2.
- collab/yang-php/<ts>-engineer-include-fix-r2.codex.findings.md: per-gate results with the exact test ids and backend results; any refusal you could not clear = STOP and record (round 2 of the unit — two-strikes armed; a second stop escalates to the owner).
- Push the branch; do NOT merge to master.

Stop rule: if the ruling's approved shape cannot be implemented as specified (e.g. the wide-application machinery cannot hold the require last), stop at the boundary and record it — do not invent an alternative design.

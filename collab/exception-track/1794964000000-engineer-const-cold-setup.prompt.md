Created-GMT: 2026-11-17 01:0x GMT
Created-Local: 2026-11-17 08:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; the cold-lane round holder per the seat's brief)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, -c sandbox_mode="danger-full-access")
Task-ID: 1794964000000-engineer-const-cold-setup

# ENGINEER: const-* rows cold-setup — apply the landed dyn pattern to the 4 include-test rows

## Read first

1. `collab/yang-php/1794847765000-to-exception-orchestrator-const-cold-setup-brief.md` — the
   unit brief (your dispatch source; base f32a4392+, branch fix/const-cold-setup).
2. `collab/exception-track/1794866000000-architect-continuation-payload-host-function-contract.sol-gpt-6.1.findings.md`
   — the dyn fixture-corrections section (the pattern you are applying).
3. `docs/agents/build-n-test.md` — focus rule, fresh-clone discipline, remote-lane rules.

## Evidence

`clojure -M:test -n yang.php.antlr.include-test` isolated on master `f32a4392` (verified twice
on jupiter) → the 4 const-* rows of `p6-fixtures-linked-on-every-vm-test`
(`include_test.clj:60`) throw `Unsupported PHP construct: :constant` in the cold JVM; green in
subsystem order.

## Mission (from the brief, verbatim terms)

- Base master `f32a4392`+ (or later tip), own worktree on jupiter; branch `fix/const-cold-setup`.
- Apply the landed dyn pattern to the 4 const rows' setup: provision the runtime, scope and
  link composition EXPLICITLY per row; no reliance on preceding subsystem tests. Do not change
  the rows' expected results or add omissions/skips.
- File ownership: `test/yang/php/antlr/include_test.clj` const-row setup ONLY. The dyn rows
  and `prelude.cljc` are the yang.php seat's (their dyn branch is mid-gate) — off-limits.
- RESIDUAL RULE: if explicit setup does not clear all 4 rows, record which rows remain and the
  exact refusal payload (`Unsupported PHP construct: :constant` = a lowering-arm gap — a real
  php-lane unit, NOT yours) and stop: findings-only.

## Verification (scoped only)

1. Isolated `clojure -M:test -n yang.php.antlr.include-test` → 0 failures, TWICE in two fresh
   JVMs (record both rc/Ran lines).
2. One scoped adjacent php ns green (unchanged-evidence).
3. `bb test:sub:clj dao.stream` green.
4. The mutual-include/dyn rows must remain green (they are the landed pattern — don't break it).

## Deliverables

Branch `fix/const-cold-setup` pushed; findings
`collab/exception-track/1794964000000-engineer-const-cold-setup.codex-gpt-6.1-terra.findings.md`
written in the worktree (root cause of the cold gap, per-row disposition, every rc/Ran line,
head sha). The orchestrator verifies and lands through the staging/countersign protocol with
the yang.php seat. Never merge to master yourself.

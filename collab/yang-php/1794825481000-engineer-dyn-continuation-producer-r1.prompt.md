Created-GMT: 2026-11-16 (dispatch time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2)
Session-ID: 01a1e3ef-d6d2-7f93-abc0-0846760901ce (codex thread.started, jupiter POOL 2)

# Task: dyn-continuation-producer r1 — fix the PHP producer's call/capture sequencing per the sol ruling

Worktree: ~/ci/dw-dynfix (fresh scratch clone, branch fix/dyn-continuation-producer, base
origin/php-land-staging @ 4e2067b2 — it carries the final regset + include r2 merges).
Owner-directed terms: do NOT touch ~/ci/datomworld-nightly (the master-green seat's gate
checkout); JVM-scoped verification only; no dart leg; no commits (leave edits, the seat publishes).

## The ruling (READ FIRST)
collab/exception-track/1794866000000-architect-continuation-payload-host-function-contract.sol-gpt-6.1.findings.md
(on origin/master). Core: the :continuation-registers refusal is a CORRECT receiver limitation
(host functions are not admissible reachable values in the register backend's saved payload).
DO NOT widen vm/machine-data?, add exemptions, or touch the enforcement path
(debruijn_register_effects.cljc, register.cljc:490, semantic/layout.cljc:1153).

## The fix surface (your files: the PHP producer)
First candidate with exact loci: src/cljc/yang/php/antlr/prelude.cljc:2699-2759 php/make-function;
the unary entry body at :2737 evaluates (cell/set! result ((get (cell/get callee) :code) ...)) —
the linearizer resolves the operator before the operands, so cell/set! stays live across guest
execution and escapes into captures. Prescribed shape: bind the callee computation first, then
apply cell/set! to the bound result (where evaluation-order proof permits). Additional named loci
if the captured chain identifies a different pending application: php/call-terminal/php/try/
php/call-ec (:248-301), lower.cljc:3149-3235, prelude_module.cljc:84-125.

## Non-weakening bar (the ruling's)
After your fix, the original payload carrying raw yin.vm.module/set-cell! must STILL fail the
same recursive admissibility check; malformed pairs/indices/live-set mismatches/pc-body/
foreign-format/resume-value refusals all retained; register-restore still throws with the
applicable :rule before any state change. Do not delete rejected pairs, clear captured frames,
or alter the verified live set.

## Fixture corrections (also required by the ruling)
The dyn setup must provision units, scope, and the link composition EXPLICITLY — behave
independently of preceding subsystem tests: no warm-up requires, no pre-run unrelated fixtures,
no omitting the register lane. (Test file: the dyn rows' setup only; their expected results stand.)

## Verification bar
1. clojure -M:test -n yang.php.antlr.include-test -> 0 failures, TWICE in two fresh JVMs (cold-start proof). Run gen:antlr first if parsers are missing in your clone.
2. One scoped adjacent php ns green (your choice; record which).
3. bb test:sub:clj dao.stream green (foundation precondition; its sub-*.log is a shared filename — copy your log aside immediately after the run, never leave it to be overwritten).
4. The full bb test:sub:clj yang.php once at the end (expect 0 failures).
Per-run log custody: copy target/profile/sub-*.log into target/dyn-r1/ before any rerun.

## Deliverables
- Edits in the worktree (no commits); findings at
  collab/yang-php/<ts>-engineer-dyn-continuation-producer-r1.gpt-6.1-terra.findings.md
  (or worktree root if collab/ writes are blocked): per-locus verdicts, the exact diffs, the
  two-JVM cold-start evidence, all gate numbers.
- If a locus cannot be rewritten without changing guest-visible evaluation order, STOP that
  locus and record it (design boundary; the evaluation-order proof is the ruling's own bar).

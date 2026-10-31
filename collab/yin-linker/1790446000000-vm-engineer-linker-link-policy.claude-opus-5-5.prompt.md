Created-GMT: 2026-09-26 16:50:00 GMT
Created-Local: 2026-09-26 23:50:00 +0700
Coding-Agent: claude
Session-ID: 6f9ec88c-5675-491f-b078-ab58fbfd469b

# Task: yin.repl :link-policy option (pending-link failure policy, phase 1)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: (not yet dispatched; brief staged for owner authorization) | Status: staged | Rationale: owner asked for the brief; GLM is frozen until 2026-09-27 01:26 +0700 and this is not a GLM-sized change; reviewer must be non-Claude (codex gpt-6-sol)

Base: master 2f0cbcc4 or later (M1 to M5 landed). Create a worktree from master (git worktree add ../datomworld-link-policy -b link-policy master; mise trust; npm ci before the Node lane). Uncommitted work only; do NOT commit or push. Read docs/build-n-test.md (kondo is `mise exec -- clojure -M:kondo`). Code and tests only: the orchestrator writes any docs (linker.md section 12 bullet 4, the REPL design doc); list implied doc changes in the report.

## Context (owner decision recorded 2026-09-26: "can this be configurable?")
M5 (src/cljc/yin/repl.cljc, src/cljc/yin/repl/link.cljc) parks a require whose link stays :pending as the shell's :pending-run: the prompt returns, lines typed meanwhile are retained and replayed once, and `(abandon)` ends it (repl.cljc abandon-pending ~1075, resume-pending ~1184, drive-links ~826). Nothing ever gives up by itself. The spec (docs/design/yin.vm.linker.md section 6.3 and section 12 bullet 4) says retry, deadline and permanent absence are the COMPOSITION's, fetch takes no deadline (owner ruling), the linker and engine stay clock-free, and dao.lease (docs/design/dao.lease.md) is the mechanism for a deadline. yin.repl is the composition, so the policy is a session setting, not a per-require argument: `require` stays an ordinary function.

## Deliverable: an option `:link-policy` on yin.repl/create-state
Values (fail closed on anything else, at create-state, with an ex-info naming the supported values):
1. `:manual` (the DEFAULT; exactly today's behavior, no test may change): never gives up by itself; `(abandon)` is the only exit.
2. A function `(fn [view]) -> :keep | :abandon | {:abandon reason}` for embedders who bring their own rule (max re-checks, a host wall clock they close over, a budget). The REPL owns no clock; time reaches the policy only through whatever the function closes over. `view` is plain data: `{:links [{:name .. :link-id ..}] :checks n :lines-retained m}` where :checks counts re-checks of this pending run that made no progress (define it precisely and document it in the docstring).
3. `:lease` is RESERVED for phase 2 (a dao.lease-governed deadline with a host tick adapter); in this slice it is refused with a clear "not implemented yet" ex-info. Do not build any of phase 2 here.

Semantics to implement and pin with tests:
- Consult the policy when a require parks (checks 0) and after each re-check that leaves it pending; never on a re-check that completes it. Never consult it when nothing is pending.
- `:abandon` runs the SAME abandon path as `(abandon)` (engine/abandon-installs and abandon-link, identity carried onto the base, retained lines dropped and reported), with reason `:yin.repl/link-policy` (or the map's reason) in place of `:yin.repl/abandoned`, and the printed message must say the session policy ended the require, not the user. Exactly-once: the require gets one error, the retained lines are dropped once and reported, no line is evaluated twice or lost (codex's M5 gates were strict about ordering and exactly-once; re-read collab/1790440400000-architect-linker-m5-gate.gpt-6-sol.stdout.log, collab/1790442000000-architect-linker-m5-regate.gpt-6-sol.stdout.log and ...regate2... last agent_message).
- A policy that throws is treated as :keep for that consult and surfaced as a shell error line, never as a silent abandon and never crashing the session; a value outside the contract is the same.
- (reset) and (vm ...) drop the pending run as today and reset :checks; the policy survives them (it is a session setting, kept on the shell like :link-source).
- repl-state's :pending summary gains the policy name (`:manual`, `:fn`) and :checks.
- Determine how a host driver (src/cljc/yin/repl/main.cljc, driver.cljc) can re-check a pending run WITHOUT the user typing a line, if it cannot today, so an unattended host can make a function policy act; if that needs a small public step function, add it and test it, otherwise report why not. Keep the REPL free of any clock, timer, callback or global atom (see the ns docstring).

## Tests
New namespace (for example test/yin/repl/link_policy_test.cljc), portable to JVM, Node and Dart (compare values, not printed text: Dart prints quote forms differently unless dao.pretty is used; use :last-value and structured state): default is :manual and pending stays pending across many re-checks; a fn policy returning :abandon at checks = N ends the require with the policy reason and one message; :keep never ends it; a throwing policy keeps and reports; a non-contract return keeps and reports; unknown option value and :lease refuse at create-state; policy survives (reset) and (vm ...); retained lines dropped exactly once on policy abandon and a retained line that starts a second pending require still behaves (M5 fix3 case); late response after a policy abandon cannot settle a later require (M5 P0 case, use the existing withholding helper in test/yin/repl/require_test.cljc).

## Rules
Rule R; ClojureDart traps (:cljd first in reader conditionals, no bare type, no private var-quote cross-namespace, put function options behind ordinary fn? checks; cljd has no core `ifn?` surprises: test it on Dart); portable cljc; ASCII; 80 cols; cljstyle clean; kondo no new warnings; smallest diff. Run touched-namespace JVM tests; the orchestrator runs the three lanes and dispatches the codex gate.
Report: collab/1790446000000-vm-engineer-linker-link-policy.claude-opus-5-5.report.md (changes at file:line, tests, decisions, the driver question, anything not done).

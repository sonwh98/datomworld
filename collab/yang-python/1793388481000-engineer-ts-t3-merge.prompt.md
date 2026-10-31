Created-GMT: 2026-10-30 14:40:00 GMT
Created-Local: 2026-10-30 21:40:00 +0700 (Indochina Time)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang.typescript T3 — merge origin/master into the T3 branch (a semantic hand-merge)
Role: Engineer (codex gpt-6.1-sol; architectural — the difficulty-matching rule)
Merge origin/master into branch yang-ts-t3 (the T3 engineering: S3-S10 + T4). The branch carries 14 commits of T3 work; master carries the landed yang.call S1 TS wiring plus the parallel seats' work. The merge has exactly three conflicts, all in src/cljc/yang/typescript/antlr/prelude.cljc.

## The resolution rules (BINDING)

1. **js/call MUST keep master's yang.call/invoke-with routing** — the convention wiring is landed on master and is not to be replaced by the direct :code application the branch predates. The branch's T3 rounds did not modify js/call's routing.
2. **js/is-constructor? takes the BRANCH's kind-based form** (kind :normal/:class/:derived/:builtin with :ctor?) — it is the T3-era form and richer than master's older this-mode check.
3. **The second conflict (make-function-nt / make-function)**: the branch's T3 form (make-function-nt with the nt? flag, the om-new descriptor chain) is the S2-era form that the T3 rounds depend on. Master's S0 call_test edits must be reconciled so both survive — the test file (call_test.cljc) expects the S0 shape, so check what the test asserts and reconcile the constructor shape accordingly.
4. Everything else: take the union.

## After the resolution

Run the JVM quick tier (FOREGROUND, explicit timeouts) + kondo with exact counts. Do NOT commit — the orchestrator reviews the resolution, commits, and runs the three-host verification. Report: the resolution per conflict, the counts, and anything ambiguous.

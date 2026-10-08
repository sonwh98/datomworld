Created-GMT: 2026-09-30 11:51:27 GMT
Created-Local: 2026-09-30 18:51:27 +0700
Coding-Agent: codex
Session-ID: 01a0f227-f33c-79b3-86ff-34de56a9afe4 (captured)
# Task: Gate — captured continuations are invocable (apply a :reified-continuation) on all four yin VMs

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 18:51:27 +0700 | Status: active | Rationale: standing gate route; Claude-authored (claude-opus-5-5); codex credits directive 2026-09-30

Read-only review in /Users/sto/workspace/datomworld-k-invoke (branch vm-continuation-invoke from master dac64b41,
uncommitted). Do not edit. Change under review: `git diff` plus the new file test/yin/vm/continuation_invoke_test.cljc.

OWNER (verbatim): "yes, wire up continuation invocation with a test if it does not cause conflict with the current work
with the DHT." Motivation: ANTLR frontends will lower early return, break/continue and try/raise to escapes through a
captured continuation. Governing text: src/cljc/yin/vm/docs/co-routines.md (the call/cc section) and
src/cljc/yin/vm/docs/ast.md Part 7.
Brief: /Users/sto/workspace/datomworld-k-invoke/collab/1790766815406-vm-engineer-continuation-invocation.prompt.md
Report (untrusted): /Users/sto/workspace/datomworld-k-invoke/collab/1790766815406-vm-engineer-continuation-invocation.claude-opus-5-5.report.md

Required semantics: abortive; store not rolled back; multi-shot incl. after the capturing expression returned; arity
!= 1 fails with an identical message on every VM; tail and non-tail operator position.

Orchestrator-verified (do not rerun): kondo 0 errors (1 pre-existing warning: unused private
ast-walker-run-active-continuation, unreferenced at HEAD - confirmed); cljstyle clean after the orchestrator ran
cljstyle fix on the new test (formatting only). Full JVM / Node / CLJD lanes are running now in the orchestrator's seat;
results will be relayed. Implementer-claimed: JVM 2408/184780/0, Node 2313/51231/0; mutation proof per VM (report).

Check correctness per VM against its own capture shape (ast_walker k/env; semantic {:segment :pc :env :stack :k} with
val at pc+1; stack-restore; register write-back), CESK integrity (what exactly is restored vs kept - store, store-of,
parked, ready-queue, wait-set, frames), interaction with park/resume, effects and FFI in-flight state, tail calls,
portability (CLJS/CLJD), regressions from the register-restore split, and test strength. Rule explicitly on:
Q1. Register VM invocation runs only check-format!, not effects/continuation-defect or the plain-data gate (they reject
    captures whose live registers hold closures). A forged {:type :reified-continuation ...} map fails as a host error,
    not a qualified defect; stack VM already behaves so for :resume. Acceptable, or required change?
Q2. The dead ast-walker-run-active-continuation loop was left without the new clause. Acceptable (delete separately), or must it be touched?
Q3. Can a program forge a :reified-continuation map (literal/store value) and jump to arbitrary pc/segment on any VM?
    Is that a capability/security concern beyond what closures and :vm/resume already allow? Name the owner decision if one is needed.
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q3; mark owner decisions.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.

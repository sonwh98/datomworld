Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 10 — the CORE: compile-request transport + service-backed admission/publication + php/eval-run + both expression paths + atomic redeclaration

Role: Engineer. Branch eval-bridge (pushed @ r9 tip). SPECS: the design of record (committed collab/orch-lane/1794400000000-...stdout.log — sections 1, 3-5), astra byte-domain ruling (1794605712000), lifecycle oracle (collab/orch-lane/p7-lifecycle-probes.txt), part 6/8 findings (harness service gap, guest-fatal conformity). r8's outstanding list is your scope minus the two ownership fixes r9 landed.

## Implement
1. Compile request/response transport: the request (source bytes, request identity, revisions, eval goal, provenance, options; NO caller cells/values/continuation/host handles) -> parser worker (eval source goal, PHP-mode lexer via the committed coordinator path) -> CST validation+scope analysis -> eval-unit emission -> canonical envelope -> active safepoint derivation -> publication + linker admission (ordinary machinery) -> installed entry available via code/entry.
2. Service-backed admission/publication: the coordinator consumes the transport; the e2e harness gets the CONFIGURED eval compiler service (the r6 gap).
3. php/eval-run in the caller task: stream-effect request -> correlated outcome -> installed entry called in the caller task against caller cells/scope/budget; fall-through :php/null; caller runtime via %runtime inventory (part 7's caller runtime inventory).
4. Both expression paths: prefix-refusals + plain-call refuse REPLACED by the supported expression (design's substitution), with the ruled declaration-collision fatal semantics (lifecycle record).
5. Fixtures: red-first end-to-end eval fixtures through the REAL service path (simple eval, caller-variable access, function defs visible to caller, nested eval, collision fatal per lifecycle record, transport refusal shapes).

## Definition of done
End-to-end fixtures green through the configured service path (not the harness shortcut); all existing gates stay green (tick 8/8, include 3/3, generator fixtures, lower-portable 8/1254, admission/scope/coordinator suites); kondo/cljstyle clean. Push per green. Findings: collab/yang-php/${TS}-engineer-eval-bridge-r10.gpt-6.1-terra.findings.md.

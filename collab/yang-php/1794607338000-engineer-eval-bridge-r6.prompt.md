Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 6 — eval-unit emission + collision fatal + php/eval-run

Role: Engineer. Branch eval-bridge (pushed @ r5 tip: coordinator + source goal landed, verified).
SPECS: astra decision table (1794567570000), design of record (committed: collab/orch-lane/1794400000000-...stdout.log — sections 3-5), lifecycle oracle (collab/orch-lane/p7-lifecycle-probes.txt), part 2-5 findings.

## Implement (ONE unit)
1. Eval-unit emission in the lower: reuse context/unit-body/return-escape per design §3; map fall-through to :php/null (NOT 1); do not invoke lower-program unchanged or php/run-main (it resets output/handlers); the collision (redeclare) fatal routes through the EXISTING guest compile-fatal terminal path per the lifecycle record (shutdown runs, dtors suppressed, exit 255, uncatchable).
2. php/eval-run: stream-effect compilation request (source bytes, request identity, revisions, eval goal, provenance, options; NO caller cells/continuation/host handles) -> correlated outcome; the eval-unit installed entry called in the caller task against caller cells/budget.
3. code/entry retrieval effect: declared export from an installed manifest-pinned module (engine effect-handler registry; no new VM opcode); try-require returns the name, not exports.
4. Replace the two refusal paths (prefix-refusals + plain-call refuse) with the supported expression per design.

## Definition of done
Red-first eval fixtures green (simple eval, caller-variable access, function defs visible to caller, nested eval, collision fatal asserting the lifecycle record: output-before flushed, shutdown ran, dtors suppressed, 255); admission/coordinator/scope/cst gates stay green; kondo/cljstyle clean. Push as you go. NO docker. Include-handoff/fixtures: NOT this unit.
Findings: collab/yang-php/${TS}-engineer-eval-bridge-r6.gpt-6.1-terra.findings.md.

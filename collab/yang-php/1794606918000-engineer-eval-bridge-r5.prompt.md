Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 5 — continue the unit chain: compiler coordinator + PHP-mode eval source goal

Role: Engineer. Branch eval-bridge (pushed @ 880d2183: the Eval-token scope-analysis prerequisite
landed). The unit chain so far: admission (r2) + scope analysis (r4). YOUR unit: the compiler
coordinator + PHP-mode eval source goal + source-aware validation + byte/revision identity.

## Spec
- collab/yang-php/1794605391000-engineer-eval-bridge.gpt-6.1-terra.findings.md (byte-domain gap, §6)
- collab/yang-php/1794605712000-architect-bytedomain-ruling.findings.md (binding contract, §A items 1-10)
- collab/orch-lane/p7-lifecycle-probes.txt + the original design (committed on branch, collab/orch-lane/1794400000000-...stdout.log)

## Implement (ONE unit: coordinator + source goal; the NEXT session does eval-unit emission + collision + eval-run)
1. The compiler coordinator: a composition-supplied compiler service path that invokes admit-request
   (part 2) at the coordinator boundary, parses the decoded Unicode view via cst/parse-unit with
   the EVAL source goal (PHP-mode lexer start, default mode beneath — no synthetic tags), runs
   source-aware CST validation, and retains bytes+profile revision in the identity/lookup keys per
   the ruling's §A.4-§A.10.
2. Scope analysis: your r4 Eval-token recognition must flow through the coordinator path (the
   parsed-source fixture stays green).
3. Fixtures: red-first coordinator fixtures (eval request -> admitted/validated CST -> identity
   keys carry bytes+revision; malformed byte payload -> the classified refusal through the
   coordinator).
4. Gates: sequential, 600s for lower-portable; scope-test + eval-admission-test + cst-test stay
   green; kondo/cljstyle. NO docker. NO push attempt (the orchestrator pushes — the approval layer
   blocks delegate pushes).

## Findings
collab/yang-php/${TS}-engineer-eval-bridge-r5.gpt-6.1-terra.findings.md (file:line, red-first chronology, gate table).

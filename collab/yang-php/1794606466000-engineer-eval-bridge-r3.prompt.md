Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 3 — wire admission into the compiler coordinator + eval-unit emission + php/eval-run + code/entry

Role: Engineer. Branch eval-bridge (pushed @ part 2 tip). Part 2's findings are your map:
collab/yang-php/1794605992000-engineer-eval-bridge-r2.gpt-6.1-terra.findings.md — its "Next
slice / handoff" section is the scope, verbatim. The astra ruling + design of record bind.

## Definition of done (this slice)
1. admit-request wired into the compiler coordinator: eval source goal, PHP-mode lexer start with default mode beneath, decoded Unicode view to parser + source-aware validation, original bytes + profile revision retained in identity/lookup keys.
2. Eval-unit emission (reuse context/unit-body/return-escape; :php/null fall-through) per design §3.
3. php/eval-run: stream-effect request -> correlated outcome; called in the caller's task with caller scope/context/budget; the two refusal paths (prefix + plain-call) replaced.
4. code/entry retrieval effect (existing effect-handler registry; no new VM opcode).
5. Gates: ALL sequential with explicit timeouts (the prior lower-portable 120s timeout was insufficient — use 600s for that namespace): the part-2 admission tests stay green; eval-unit fixtures red-first then green (simple eval, caller-variable access, function defs visible to caller, nested eval, refusal shapes); lower-portable-test green at 600s; kondo 0/0; cljstyle clean.

## Rules
Commit + push per meaningful step. No docker (oracle pass is the orchestrator's). Design gap -> stop + record. Findings: collab/yang-php/${TS}-engineer-eval-bridge-r3.gpt-6.1-terra.findings.md.

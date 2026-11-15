Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 15 — EXECUTE THIS EDIT MAP (prescriptive, per-hunk). Minimal analysis; the goal is edits + the green fixture.

Role: Engineer. Branch eval-bridge (pushed; tree clean). The prior session (r14 findings:
collab/yang-php/1794624540000-engineer-eval-bridge-r14.gpt-6.1-terra.findings.md — READ IT
FIRST, it names every seam) mapped the work precisely. Execute it:

## EDIT 1 — lower.cljc, the plain-call path (~:1861, the explicit "eval" refusal)
Replace the refusal with: submit the eval-unit compilation through the composition's direct
coordinator (part 5's eval_coordinator admit/parse path), emit the eval-unit entry (design §3:
context/unit-body/return-escape reuse; :php/null fall-through; collision fatal per the
lifecycle record), and call the installed entry in the caller task (part 7's emitter: caller
runtime inventory + hook aliases). Keep the diagnostic shape for the UNSUPPORTED cases the
design still refuses (Node/Dart without a configured service — the pinned refusal stays for
those hosts).

## EDIT 2 — lower.cljc, the prefix path (~:100/:1307, prefix-refusals)
Same conversion for the `Eval` prefix form.

## EDIT 3 — the e2e harness
Wire the configured compiler service into e2e_harness/run-php so the lowered eval reaches the
coordinator (r14's integration seam: e2e_harness.clj:163 executes the lowered AST directly;
run-vms:104 derives only the budget profile).

## EDIT 4 — the fixture
test fixture: `<?php echo eval("return 6 * 7;");` → 42, plus a caller-variable read inside the
eval, all four JVM evaluators, red-first (it is red now — r14 measured the refusal).

## Gates
The fixture green x4; ALL existing eval suites (admission/coordinator/emitter/publication/
service/transport/scope-cleanup/retention) + tick 8/8 + include 3/3 stay green; lower-portable
600s; kondo 0/0; cljstyle clean. Sequential, explicit timeouts. Push per green.
Findings: collab/yang-php/${TS}-engineer-eval-bridge-r15.gpt-6.1-terra.findings.md.

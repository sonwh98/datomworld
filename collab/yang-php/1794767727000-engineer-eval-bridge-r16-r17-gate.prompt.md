Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge r16-r17 verification — the gates the interrupted sessions did not complete

Role: Engineer (verification only). Branch eval-bridge @ origin/eval-bridge tip. NO code changes
— verification of the landed units' focused suites, sequential, explicit timeouts.

## The landed units to verify
1. r16 (8cc092d1): eval executes end-to-end through the compiler service — eval-bridge-test,
   the eval suites (admission/coordinator/emitter/publication/service/transport/scope-cleanup/
   retention), tick fixtures, include gate, lower-portable.
2. r13-r3 partials (053ae9a8 + the two matrix fixtures): the redeclaration matrix rows landed.
3. The §8.1 arms amendment: dao.host.gate-test green.

## Run (sequential, explicit timeouts, NO code changes — report only)
1. eval-bridge-test + the eval suite namespaces
2. tick fixtures + include gate
3. lower-portable-test (600s)
4. dao.host.gate-test
5. kondo 0/0 + cljstyle clean on the touched files
Findings: collab/yang-php/<ts>-engineer-eval-bridge-r16-r17-gate.qwen3.8-max.findings.md —
the gate table per suite, any failure reproduced with the exact command.

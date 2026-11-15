Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 11 — stream service wiring + eval-unit emission through the coordinator path

Role: Engineer. Branch eval-bridge (pushed @ r10 tip: request boundary verified 8/76). SPECS: the
design of record (sections 1, 3-5), astra ruling, part 2 findings' admission, part 6 findings
(harness service gap), part 10 findings (the request shape + coordinator entry, committed).

## Implement
1. The request/response stream service: the coordinator consumes requests FROM a stream and
   answers with the correlated outcome ON a stream (ordinary stream effects; no host global);
   the e2e harness gets the CONFIGURED eval compiler service (r6's gap: "the current e2e harness
   has no configured eval compiler service").
2. Eval-unit emission through the coordinator path: parser worker (eval source goal, PHP-mode
   start) -> CST validation+scope analysis -> eval-unit emission (context/unit-body/return-escape,
   :php/null fall-through) -> canonical envelope -> active safepoint derivation -> publication +
   linker admission (the site-set stage from P7-b) -> installed entry available via code/entry.
3. Red-first service fixtures: eval request -> stream service -> admitted installed entry
   retrievable via code/entry; malformed/ill-formed payload -> classified refusal through the
   stream.

## Gates (sequential, explicit timeouts)
All existing eval suites stay green (transport 8/76, admission 43A, coordinator 22A, emitter,
publication, scope-cleanup, tick 8/8, include 3/3); new service fixtures green; kondo/cljstyle.
Push per green. Findings: collab/yang-php/${TS}-engineer-eval-bridge-r11.gpt-6.1-terra.findings.md.
php/eval-run + expression paths + redeclaration: NOT this slice (r12).

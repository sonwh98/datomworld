Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 4 — IMPLEMENT the full r3 scope; the collision lifecycle evidence is now CLOSED.

Role: Engineer. Branch eval-bridge (pushed @ part D-staging + r2 tip). The ruling's required oracle
evidence is RECORDED: collab/orch-lane/p7-lifecycle-probes.txt — collision fatal: uncatchable,
output-before flushed, shutdown functions RUN, destructors SUPPRESSED, exit 255, eval-unit atomic,
prior evals persist. This matches the engine's existing guest/compile-fatal terminal path (the P5b2
oracle rows pinned the same shape) — USE IT; do not invent a new terminal policy.

## Implement (the full r3 scope, unchanged, now unblocked)
1. admit-request wired into the compiler coordinator (eval source goal, PHP-mode lexer, decoded view
   to parser+validation, bytes+revision in identity keys).
2. Eval-unit emission (context/unit-body/return-escape reuse; :php/null fall-through).
3. Collision fatal: route through the existing guest/compile-fatal terminal machinery (shutdown runs,
   dtors suppressed, 255) — consistent with the recorded probes.
4. php/eval-run stream-effect + correlated outcome; the two refusal paths replaced.
5. code/entry retrieval effect.
6. Gates: part-2 admission tests green; eval-unit fixtures red-first then green (include the collision
   fatal fixture asserting: output-before flushed, shutdown ran, 255); lower-portable-test at 600s;
   python safepoint additivity; kondo/cljstyle. SEQUENTIAL. Push as you go.
Findings: collab/yang-php/${TS}-engineer-eval-bridge-r4.gpt-6.1-terra.findings.md.

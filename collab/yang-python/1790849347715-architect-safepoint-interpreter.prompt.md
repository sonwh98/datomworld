Created-GMT: 2026-10-01 10:09:07 GMT
Created-Local: 2026-10-01 17:09:07 +0700
Coding-Agent: claude
Session-ID: ba6d62ab-caeb-424c-a44e-4637d8333092
# Task: Safepoint interpreter design — signals, tracing, green-thread switches, recursion accounting as an attached stream transformation

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-01 17:09:07 +0700 | Status: active | Rationale: owner directive "use fable ... as the architect"; fresh session (the D6/D7 design occupies the other fable session concurrently)

Read-only architecture design. Do not edit files. Work in /Users/sto/workspace/datomworld (master 60b60898).

OWNER (verbatim): "go ahead with 4 and 5" (plan item 5 includes the safepoint interpreter). Prior owner decision
(verbatim "accept all recommendations" on the Python mappability ruling, decision 4): "Safepoints as an attached
interpreter rather than part of the naive lowering, per the pipeline direction." Owner pipeline direction (verbatim):
"compilers have a pipeline of transformation. yang/yin.vm compilation pipeline is dynamic where interpreters read from
dao.stream and make transformation onto another dao.stream. any number of interpreters can attach to those dao.stream to
do more transformation of its own".
Read first: docs/design/datom.world.md (invariants: no implicit control flow, no hidden global state, no application
callbacks, peer observers); docs/design/yang.antlr.md (as updated: §8.5.1 mappability, §8.11); the mappability ruling
collab/1790797984227-architect-python3-mappability.claude-fable-5-1.findings.md (signals/settrace/GIL atomicity/
RecursionError via safepoints); the reclamation design collab/1790800251738-architect-heap-reclamation.claude-fable-5-1.findings.md;
src/cljc/yang/python/antlr/{lower,prelude,stage}.cljc (the spike's stream topology and row output);
src/cljc/yin/vm/engine.cljc (effects, park/resume, wait-set, ready-queue), yin.vm.code-as-tuples.md (rows).

Design questions:
Q1. What a safepoint is in Universal AST rows (an ordinary :application of a prelude/host effect? a new effect kind via
    D4 make-effect?), where the attached interpreter inserts them (loop back-edges, function entry, call sites, every
    N statements), and how it rewrites a row stream into another without breaking content addressing, occurrences or
    side tables (the canonical program vs the safepointed program as two streams; which one the evaluator reads).
Q2. Semantics per consumer: signal delivery (signals as events on a stream, polled at safepoints; KeyboardInterrupt);
    sys.settrace/setprofile (a hook that calls back into guest state — how without application callbacks);
    green-thread switching (cooperative, at safepoints only; one heap-owning interpreter); RecursionError (a depth
    counter in a cell decremented by escapes) — which belong in this interpreter vs the prelude.
Q3. Cost and composition: the naive program must stay correct without it; turning it on/off per composition;
    interaction with continuations (escapes skipping depth decrements), cells/GC (safepoints as extra collection points?),
    determinism.
Q4. Language-agnostic or per-language: is the safepoint interpreter generic over Universal AST (then JS/PHP reuse it)?
Q5. Minimal first slice + acceptance tests; owner decisions.
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: ba6d62ab-caeb-424c-a44e-4637d8333092
Then: one recommended design; answers Q1-Q5; owner decisions; severity | file:line | evidence | correction items.
